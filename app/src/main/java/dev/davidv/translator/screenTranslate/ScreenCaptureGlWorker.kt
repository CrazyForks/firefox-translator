/*
 * Copyright (C) 2024 David V
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.davidv.translator.screenTranslate

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import dev.davidv.translator.LivePipelineJni
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 *  Off-screen GPU worker for screen-translate. A `VirtualDisplay` writes the
 *  captured screen into a `GL_TEXTURE_EXTERNAL_OES`; per frame this drives the
 *  same native overlay-only `GlesRenderer` the camera path uses, but renders
 *  into a PBuffer (no window surface), reads the canonical overlay back, and
 *  hands it to [onOverlayBitmap] for a Canvas `View` to draw.
 *
 *  The display is decoupled from a SurfaceView on purpose: the overlay window
 *  must composite through the View hierarchy so its `LayoutParams.alpha` can
 *  sit under the untrusted-touch cap and pass taps through (a SurfaceView is a
 *  separate full-opacity layer that blocks them). See [ScreenOverlayView].
 */
class ScreenCaptureGlWorker(
  displayWidth: Int,
  displayHeight: Int,
  private val onClearOverlay: () -> Unit,
  private val onOverlayBitmap: (Bitmap, Int, Int) -> Unit,
) {
  @Volatile
  var pipelinePtr: Long = 0L

  // OCR canonical is half the display: read_camera_rgba renders into a half-res
  // FBO (cheap ~4.6MB readback) which rec crops from (det downsamples further).
  // The overlay PRESENT, though, runs at full display res (pw/ph) so glyphs
  // rasterize at oversample=2 and the Canvas draws the pill bitmap 1:1 instead
  // of upscaling a half-res render (which looked pixelated). The present
  // readback is full-res but happens once per acquire, not per frame.
  private val cw = (displayWidth / 2).coerceAtLeast(1)
  private val ch = (displayHeight / 2).coerceAtLeast(1)
  private val pw = displayWidth.coerceAtLeast(1)
  private val ph = displayHeight.coerceAtLeast(1)

  private var glThread: GlThread? = null

  @Volatile
  private var sourceSurface: Surface? = null
  private val sourceSurfaceLock = Object()

  fun start() {
    glThread = GlThread().also { it.start() }
  }

  fun stop() {
    glThread?.shutdown()
    glThread = null
  }

  fun awaitSourceSurface(timeoutMs: Long = 5_000): Surface? {
    val deadline = System.currentTimeMillis() + timeoutMs
    synchronized(sourceSurfaceLock) {
      while (sourceSurface == null) {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) return null
        try {
          sourceSurfaceLock.wait(remaining)
        } catch (_: InterruptedException) {
          return null
        }
      }
      return sourceSurface
    }
  }

  fun setSourceBufferSize(
    width: Int,
    height: Int,
  ) {
    glThread?.setSourceBufferSize(width, height)
  }

  private fun publishSourceSurface(s: Surface) {
    synchronized(sourceSurfaceLock) {
      sourceSurface = s
      sourceSurfaceLock.notifyAll()
    }
  }

  private inner class GlThread : Thread("screen-translate-gl") {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var rendererPtr: Long = 0L
    private var sourceTexId: Int = 0

    private val stLock = Object()
    private var surfaceTexture: SurfaceTexture? = null

    @Volatile
    private var running = true

    @Volatile
    private var srcBufW = 0

    @Volatile
    private var srcBufH = 0

    private val lock = Object()
    private var frameAvailable = false
    private val stMatrix = FloatArray(16)

    // Direct buffer the native side copies the CPU-rendered overlay canvas into.
    // Sized to full screen; the canvas covers only its (smaller) union-AABB.
    private val readBuffer = ByteBuffer.allocateDirect(pw * ph * 4).order(ByteOrder.nativeOrder())
    private val overlayGeom = IntArray(4) // [bitmapW, bitmapH, destLeft, destTop]

    // Reused overlay Bitmap (sized to the canvas sub-region); realloc on size change.
    private var overlayBmp: Bitmap? = null

    fun setSourceBufferSize(
      w: Int,
      h: Int,
    ) {
      synchronized(stLock) {
        srcBufW = w
        srcBufH = h
        surfaceTexture?.setDefaultBufferSize(w, h)
      }
    }

    fun shutdown() {
      synchronized(lock) {
        running = false
        lock.notifyAll()
      }
      try {
        join(500)
      } catch (_: InterruptedException) {
      }
    }

    override fun run() {
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
      if (!setupEgl()) {
        Log.e(TAG, "EGL PBuffer setup failed")
        return
      }
      rendererPtr = LivePipelineJni.createGlRenderer()
      if (rendererPtr == 0L) {
        Log.e(TAG, "createGlRenderer failed")
        teardownEgl()
        return
      }
      LivePipelineJni.setRendererOverlayOnly(rendererPtr)
      // Modal overlay opacity for the screen path. On-screen opacity is
      // OVERLAY_ALPHA × window-alpha (0.79); 1.0 keeps pills as opaque as the
      // touch cap allows. Lower it for a more see-through overlay.
      LivePipelineJni.setRendererOverlayAlpha(rendererPtr, OVERLAY_ALPHA)
      sourceTexId = createExternalOesTexture()
      if (sourceTexId == 0) {
        Log.e(TAG, "createExternalOesTexture failed")
        LivePipelineJni.destroyGlRenderer(rendererPtr)
        rendererPtr = 0L
        teardownEgl()
        return
      }
      val st = SurfaceTexture(sourceTexId)
      synchronized(stLock) {
        surfaceTexture = st
        if (srcBufW > 0 && srcBufH > 0) st.setDefaultBufferSize(srcBufW, srcBufH)
      }
      st.setOnFrameAvailableListener {
        synchronized(lock) {
          frameAvailable = true
          lock.notifyAll()
        }
      }
      publishSourceSurface(Surface(st))
      Log.i(TAG, "screen-translate GL ready, canonical ${cw}x$ch, pipelinePtr=$pipelinePtr")

      // Auto change detection (SCREEN_CHANGE_DETECTION.md): per captured frame the
      // native monitor computes a coarse-gray diff (pill regions masked out) and
      // decides hide (movement) / acquire (settled) / nothing. On settle we
      // *dispatch* OCR to a background worker (non-blocking) and poll for results,
      // presenting provisional pills the instant detection lands and the full
      // translation when ready. The monitor keeps running during the acquire, so
      // movement aborts the in-flight OCR. `wantsTick`/`acquiring` drive timed
      // polling (the mirror stops emitting frames once the screen is static).
      var wantsTick = false
      var acquiring = false
      var lastVersion = 0L
      while (true) {
        var hadFrame = false
        synchronized(lock) {
          if (wantsTick || acquiring) {
            if (running && !frameAvailable) {
              try {
                lock.wait(POLL_MS)
              } catch (_: InterruptedException) {
              }
            }
          } else {
            while (running && !frameAvailable) {
              try {
                lock.wait()
              } catch (_: InterruptedException) {
              }
            }
          }
          if (!running) return@synchronized
          hadFrame = frameAvailable
          frameAvailable = false
        }
        if (!running) break

        // Always drain the producer so its BufferQueue keeps flowing.
        if (hadFrame) {
          st.updateTexImage()
          st.getTransformMatrix(stMatrix)
        }
        val pipeline = pipelinePtr
        if (pipeline == 0L) continue

        // Monitor movement/settle — runs even while acquiring, so movement during
        // an in-flight OCR aborts it.
        val now = System.nanoTime()
        val ret =
          if (hadFrame) {
            val uv = uvFromSurfaceTexture(stMatrix)
            LivePipelineJni.screenMonitorFrameGl(pipeline, rendererPtr, sourceTexId, cw, ch, uv, now)
          } else {
            LivePipelineJni.screenMonitorTick(pipeline, now)
          }
        wantsTick = (ret and MONITOR_WANTS_TICK) != 0
        when (ret and MONITOR_ACTION_MASK) {
          MONITOR_ACTION_HIDE -> {
            onClearOverlay()
            if (acquiring) {
              LivePipelineJni.screenAbortAcquire(pipeline)
              acquiring = false
              Log.i(TAG, "movement during acquire → abort")
            }
          }
          MONITOR_ACTION_ACQUIRE -> {
            // Settled: dispatch OCR to the worker (non-blocking). The overlay is
            // already hidden, so the current texture is the clean settled frame.
            val uv = uvFromSurfaceTexture(stMatrix)
            val dispatched =
              LivePipelineJni.screenDispatchAcquire(
                pipeline,
                rendererPtr,
                sourceTexId,
                cw,
                ch,
                pw,
                ph,
                uv,
              )
            if (dispatched != 0) {
              acquiring = true
              lastVersion = LivePipelineJni.screenAcquireState(pipeline) and 0xFFFFFFFFL
            } else {
              Log.w(TAG, "screenDispatchAcquire dropped (worker busy)")
            }
          }
        }

        // Poll the worker: present new overlays (provisional, then full) as they
        // land, and clear `acquiring` once it's done.
        if (acquiring) {
          val state = LivePipelineJni.screenAcquireState(pipeline)
          val version = state and 0xFFFFFFFFL
          val busy = (state ushr 32) != 0L
          if (version != lastVersion) {
            val tPresent = System.nanoTime()
            // Native renders the overlay canvas (CPU) and copies it into
            // readBuffer; no GPU composite/readback. geom = [W, H, left, top].
            readBuffer.position(0)
            val bytes =
              LivePipelineJni.screenReadOverlay(pipeline, readBuffer, overlayGeom, cw, ch, pw, ph)
            if (bytes > 0) {
              val bw = overlayGeom[0]
              val bh = overlayGeom[1]
              var bmp = overlayBmp
              if (bmp == null || bmp.width != bw || bmp.height != bh) {
                bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                overlayBmp = bmp
              }
              readBuffer.position(0)
              readBuffer.limit(bytes)
              bmp.copyPixelsFromBuffer(readBuffer)
              readBuffer.clear()
              onOverlayBitmap(bmp, overlayGeom[2], overlayGeom[3])
              Log.i(TAG, "present v=$version (${(System.nanoTime() - tPresent) / 1_000_000}ms)")
            }
            lastVersion = version
          }
          if (!busy) acquiring = false
        }
      }

      surfaceTexture?.release()
      surfaceTexture = null
      if (sourceTexId != 0) {
        GLES20.glDeleteTextures(1, intArrayOf(sourceTexId), 0)
        sourceTexId = 0
      }
      if (rendererPtr != 0L) {
        LivePipelineJni.destroyGlRenderer(rendererPtr)
        rendererPtr = 0L
      }
      teardownEgl()
    }

    private fun createExternalOesTexture(): Int {
      val ids = IntArray(1)
      GLES20.glGenTextures(1, ids, 0)
      val id = ids[0]
      if (id == 0) return 0
      val target = GL_TEXTURE_EXTERNAL_OES
      GLES20.glBindTexture(target, id)
      GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
      GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
      GLES20.glBindTexture(target, 0)
      return id
    }

    private fun setupEgl(): Boolean {
      eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
      if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
      val ver = IntArray(2)
      if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) return false
      val cfgAttribs =
        intArrayOf(
          EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
          EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
          EGL14.EGL_RED_SIZE, 8,
          EGL14.EGL_GREEN_SIZE, 8,
          EGL14.EGL_BLUE_SIZE, 8,
          EGL14.EGL_ALPHA_SIZE, 8,
          EGL14.EGL_NONE,
        )
      val cfgs = arrayOfNulls<EGLConfig>(1)
      val num = IntArray(1)
      if (!EGL14.eglChooseConfig(eglDisplay, cfgAttribs, 0, cfgs, 0, 1, num, 0) || num[0] <= 0) {
        return false
      }
      val cfg = cfgs[0] ?: return false
      eglContext =
        EGL14.eglCreateContext(
          eglDisplay,
          cfg,
          EGL14.EGL_NO_CONTEXT,
          intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
          0,
        )
      if (eglContext == EGL14.EGL_NO_CONTEXT) return false
      eglSurface =
        EGL14.eglCreatePbufferSurface(
          eglDisplay,
          cfg,
          intArrayOf(EGL14.EGL_WIDTH, pw, EGL14.EGL_HEIGHT, ph, EGL14.EGL_NONE),
          0,
        )
      if (eglSurface == EGL14.EGL_NO_SURFACE) return false
      return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun teardownEgl() {
      if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
        EGL14.eglMakeCurrent(
          eglDisplay,
          EGL14.EGL_NO_SURFACE,
          EGL14.EGL_NO_SURFACE,
          EGL14.EGL_NO_CONTEXT,
        )
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
      }
      eglDisplay = EGL14.EGL_NO_DISPLAY
      eglContext = EGL14.EGL_NO_CONTEXT
      eglSurface = EGL14.EGL_NO_SURFACE
    }
  }

  companion object {
    private const val TAG = "ScreenCaptureGlWorker"

    /** Overlay opacity multiplier for the screen path (× the 0.79 window
     *  alpha). 1.0 = as opaque as the touch cap allows; lower for see-through. */
    private const val OVERLAY_ALPHA = 1.0f

    /** Poll interval while a settle deadline is armed (the monitor wants ticks):
     *  the mirror stops emitting frames once motion stops, so we wake on a timer
     *  to hit the deadline. */
    private const val POLL_MS = 30L

    // Packed result of the native screen monitor (see LivePipelineJni).
    private const val MONITOR_ACTION_MASK = 0x3
    private const val MONITOR_ACTION_HIDE = 1
    private const val MONITOR_ACTION_ACQUIRE = 2
    private const val MONITOR_WANTS_TICK = 0x100

    private const val GL_TEXTURE_EXTERNAL_OES: Int = 0x8D65
    private const val EGL_OPENGL_ES3_BIT: Int = 0x40

    /** Row-major 3×3 unit-quad → sensor-uv from a `SurfaceTexture`
     *  4×4 transform (column-major): the capture has no rotation, just the
     *  producer's crop + the conventional vertical flip. */
    fun uvFromSurfaceTexture(m: FloatArray): FloatArray =
      floatArrayOf(
        m[0], m[4], m[12],
        m[1], m[5], m[13],
        0f, 0f, 1f,
      )
  }
}
