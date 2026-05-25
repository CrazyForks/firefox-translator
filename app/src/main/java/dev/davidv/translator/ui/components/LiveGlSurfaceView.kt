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

package dev.davidv.translator.ui.components

import android.content.Context
import android.graphics.Matrix
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.davidv.translator.LivePipelineJni
import kotlin.math.max

/** Sink the engine pushes each camera frame to. Blocking: the call
 *  returns only after the frame has been presented, so the caller's
 *  `FrameHandle` (the camera bytes) stays alive for the whole render. */
interface LiveFrameSink {
  /** Returns the packed `LivePipelineJni` per-frame result (or 0). */
  fun submitFrameBlocking(
    pipelinePtr: Long,
    framePtr: Long,
    cropLeft: Int,
    cropTop: Int,
    cropRight: Int,
    cropBottom: Int,
    visibleSensorW: Int,
    visibleSensorH: Int,
    fullViewW: Int,
    fullViewH: Int,
    rotationDegrees: Int,
    timestampNs: Long,
  ): Long
}

/** GPU sibling of [LiveTranslatorSurfaceView]: instead of compositing on
 *  the CPU into a `Bitmap` and blitting via Canvas, it owns an EGL GLES2
 *  context on the SurfaceView's surface and a dedicated render thread,
 *  and presents the camera+overlay composite straight to the surface via
 *  the native `GlesRenderer` (`processFrameGl`). The display rotation +
 *  FILL_CENTER scale that the Canvas path applied through `drawMatrix` is
 *  folded into a dst→clip transform handed to the renderer. */
class LiveGlSurfaceView(context: Context) :
  SurfaceView(context), SurfaceHolder.Callback, LiveFrameSink {
  @Volatile
  var onSizeChanged: ((Int, Int) -> Unit)? = null

  private var glThread: GlThread? = null

  init {
    holder.addCallback(this)
  }

  override fun surfaceCreated(holder: SurfaceHolder) {
    glThread = GlThread(holder.surface).also { it.start() }
  }

  override fun surfaceChanged(
    holder: SurfaceHolder,
    format: Int,
    width: Int,
    height: Int,
  ) {
    glThread?.resize(width, height)
    onSizeChanged?.invoke(width, height)
  }

  override fun surfaceDestroyed(holder: SurfaceHolder) {
    glThread?.shutdown()
    glThread = null
  }

  override fun submitFrameBlocking(
    pipelinePtr: Long,
    framePtr: Long,
    cropLeft: Int,
    cropTop: Int,
    cropRight: Int,
    cropBottom: Int,
    visibleSensorW: Int,
    visibleSensorH: Int,
    fullViewW: Int,
    fullViewH: Int,
    rotationDegrees: Int,
    timestampNs: Long,
  ): Long {
    val req =
      Request(
        pipelinePtr, framePtr, cropLeft, cropTop, cropRight, cropBottom,
        visibleSensorW, visibleSensorH, fullViewW, fullViewH, rotationDegrees,
        timestampNs,
      )
    return glThread?.render(req) ?: 0L
  }

  private class Request(
    val pipelinePtr: Long,
    val framePtr: Long,
    val cropLeft: Int,
    val cropTop: Int,
    val cropRight: Int,
    val cropBottom: Int,
    val visibleSensorW: Int,
    val visibleSensorH: Int,
    val fullViewW: Int,
    val fullViewH: Int,
    val rotationDegrees: Int,
    val timestampNs: Long,
  )

  /** Owns the EGL context + native renderer; all GL touches happen here.
   *  `render` hands a frame over from the analyzer thread and blocks
   *  until this thread has presented it. */
  private inner class GlThread(private val surface: Surface) : Thread("live-gl") {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var rendererPtr: Long = 0L

    @Volatile
    private var running = true

    @Volatile
    private var surfW = 0

    @Volatile
    private var surfH = 0

    private val lock = Object()
    private var pending: Request? = null
    private var result = 0L
    private var resultReady = false

    fun resize(
      w: Int,
      h: Int,
    ) {
      surfW = w
      surfH = h
    }

    fun render(req: Request): Long {
      synchronized(lock) {
        if (!running) return 0L
        pending = req
        resultReady = false
        lock.notifyAll()
        while (!resultReady && running) {
          try {
            lock.wait()
          } catch (_: InterruptedException) {
            return 0L
          }
        }
        return if (resultReady) result else 0L
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
      // Render thread must win scheduling against the OCR worker (MNN
      // saturates cores during detect/recognize); without this the
      // analyzer thread, blocked in `render`, stalls the camera for the
      // whole OCR burst.
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
      if (!setupEgl()) {
        Log.e(TAG, "EGL setup failed; GL present disabled")
        return
      }
      rendererPtr = LivePipelineJni.createGlRenderer()
      if (rendererPtr == 0L) {
        Log.e(TAG, "createGlRenderer failed")
        teardownEgl()
        return
      }
      while (true) {
        val req =
          synchronized(lock) {
            while (running && pending == null) {
              try {
                lock.wait()
              } catch (_: InterruptedException) {
              }
            }
            if (!running) null else pending.also { pending = null }
          } ?: break
        val packed = renderFrame(req)
        synchronized(lock) {
          result = packed
          resultReady = true
          lock.notifyAll()
        }
      }
      if (rendererPtr != 0L) {
        LivePipelineJni.destroyGlRenderer(rendererPtr)
        rendererPtr = 0L
      }
      teardownEgl()
    }

    private fun renderFrame(req: Request): Long {
      val vw = surfW
      val vh = surfH
      if (vw <= 0 || vh <= 0) return 0L
      android.opengl.GLES20.glViewport(0, 0, vw, vh)
      val xform =
        displayXform(req.visibleSensorW, req.visibleSensorH, req.rotationDegrees, vw, vh)
      val packed =
        LivePipelineJni.processFrameGl(
          req.pipelinePtr,
          req.framePtr,
          rendererPtr,
          xform,
          req.cropLeft,
          req.cropTop,
          req.cropRight,
          req.cropBottom,
          req.visibleSensorW,
          req.visibleSensorH,
          req.fullViewW,
          req.fullViewH,
          req.timestampNs,
        )
      EGL14.eglSwapBuffers(eglDisplay, eglSurface)
      return packed
    }

    private fun setupEgl(): Boolean {
      eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
      if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
      val ver = IntArray(2)
      if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) return false
      val cfgAttribs =
        intArrayOf(
          EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
          EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
          EGL14.EGL_RED_SIZE, 8,
          EGL14.EGL_GREEN_SIZE, 8,
          EGL14.EGL_BLUE_SIZE, 8,
          EGL14.EGL_ALPHA_SIZE, 0,
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
          intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
          0,
        )
      if (eglContext == EGL14.EGL_NO_CONTEXT) return false
      eglSurface =
        EGL14.eglCreateWindowSurface(eglDisplay, cfg, surface, intArrayOf(EGL14.EGL_NONE), 0)
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
    private const val TAG = "LiveGlSurfaceView"

    /** Row-major 3×3 mapping dst-pixel coords (top-left origin, y-down,
     *  `dstW`×`dstH`) to clip space, folding in the same display rotation
     *  + FILL_CENTER scale + centering as `LiveTranslatorSurfaceView`'s
     *  `drawMatrix`, then NDC. Result = ndc_from_surface · (dst→view). */
    fun displayXform(
      dstW: Int,
      dstH: Int,
      rotationDegrees: Int,
      viewW: Int,
      viewH: Int,
    ): FloatArray {
      val bmpW = dstW.toFloat()
      val bmpH = dstH.toFloat()
      val r = ((rotationDegrees % 360) + 360) % 360
      val rotatedW: Float
      val rotatedH: Float
      if (r == 90 || r == 270) {
        rotatedW = bmpH
        rotatedH = bmpW
      } else {
        rotatedW = bmpW
        rotatedH = bmpH
      }
      val scale = max(viewW / rotatedW, viewH / rotatedH)
      val offsetX = (viewW - rotatedW * scale) * 0.5f
      val offsetY = (viewH - rotatedH * scale) * 0.5f
      val m = Matrix()
      m.postRotate(r.toFloat(), bmpW / 2f, bmpH / 2f)
      m.postTranslate(-(bmpW - rotatedW) / 2f, -(bmpH - rotatedH) / 2f)
      m.postScale(scale, scale)
      m.postTranslate(offsetX, offsetY)
      val dstToView = FloatArray(9)
      m.getValues(dstToView) // row-major: [a b c  d e f  g h i]
      // ndc_from_surface: view-px (top-left, y-down) -> clip (-1..1, y-up)
      val ndc =
        floatArrayOf(
          2f / viewW, 0f, -1f,
          0f, -2f / viewH, 1f,
          0f, 0f, 1f,
        )
      return mat3Mul(ndc, dstToView)
    }

    /** Row-major 3×3 product `a · b`. */
    private fun mat3Mul(
      a: FloatArray,
      b: FloatArray,
    ): FloatArray {
      val o = FloatArray(9)
      for (row in 0 until 3) {
        for (col in 0 until 3) {
          o[row * 3 + col] =
            a[row * 3] * b[col] +
            a[row * 3 + 1] * b[3 + col] +
            a[row * 3 + 2] * b[6 + col]
        }
      }
      return o
    }
  }
}
