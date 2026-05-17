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
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.davidv.translator.CompositedFrame
import kotlin.math.max

/** Single display surface for the live-translate camera screen: camera
 *  pixels + warped overlay arrive as one composited [Bitmap] from the
 *  Rust compositor and we blit it 1:1 (FILL_CENTER letterboxed) to a
 *  [SurfaceView]. Replaces the previous `PreviewView` + transparent
 *  overlay-View stack, where the two surfaces updated independently
 *  and could drift apart under motion.
 *
 *  Frame rate is whatever the engine produces — typically the camera
 *  rate (~30 Hz). No vsync / IMU-extrapolation loop on this side; if
 *  the engine doesn't send a new frame, we keep showing the last one.
 *  That's a feature, not a bug: it guarantees the displayed pixels and
 *  the overlay agree by construction.
 *
 *  Threading: [update] is safe to call from any thread (the engine
 *  calls it from its detector worker). The draw runs on whichever
 *  thread invoked update, after acquiring the surface canvas. We
 *  ignore updates that arrive while the surface is not ready
 *  (between [surfaceDestroyed] and [surfaceCreated]) — the engine's
 *  StateFlow re-delivers the latest frame whenever a new subscriber
 *  appears, so we'll catch up on the next emission. */
class LiveTranslatorSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
  private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
  private val drawMatrix = Matrix()

  @Volatile
  private var surfaceReady: Boolean = false

  @Volatile
  private var lastFrame: CompositedFrame? = null
  private val drawLock = Any()

  init {
    holder.addCallback(this)
    // Opaque surface — we always cover every pixel of the view with
    // either camera or overlay or letterbox, so we can skip the
    // alpha channel for the composite blit.
    setWillNotDraw(true)
  }

  override fun surfaceCreated(holder: SurfaceHolder) {
    surfaceReady = true
    // Repaint the last frame we received so the surface comes up
    // populated immediately instead of showing whatever stale
    // buffer the SurfaceFlinger had.
    lastFrame?.let { drawComposited(it) }
  }

  override fun surfaceChanged(
    holder: SurfaceHolder,
    format: Int,
    width: Int,
    height: Int,
  ) {
    lastFrame?.let { drawComposited(it) }
  }

  override fun surfaceDestroyed(holder: SurfaceHolder) {
    surfaceReady = false
  }

  /** Called by the engine when a new composited frame is ready.
   *  Stores it as the latest and immediately draws if the surface
   *  is live. Cheap when nothing changed: we never schedule a draw
   *  for the same Bitmap reference twice. */
  fun update(frame: CompositedFrame?) {
    if (frame == null) {
      // Engine cleared its state (camera unbound or session reset).
      // Leave the last frame on screen rather than blanking; the
      // next frame will overwrite. Clearing here causes a brief
      // black flash on every clear/re-acquire cycle.
      return
    }
    synchronized(drawLock) {
      if (lastFrame === frame) return
      lastFrame = frame
    }
    if (surfaceReady) {
      drawComposited(frame)
    }
  }

  /** Acquire the surface canvas, blit the composited bitmap with
   *  FILL_CENTER letterboxing, post. Mirrors the math
   *  `PreviewView.ScaleType.FILL_CENTER` uses, so the rendered scene
   *  occupies the same view region as the old preview did. */
  private fun drawComposited(frame: CompositedFrame) {
    val viewW = width
    val viewH = height
    if (viewW <= 0 || viewH <= 0) return
    val bitmap = frame.bitmap
    if (bitmap.isRecycled) return
    val bmpW = bitmap.width.toFloat()
    val bmpH = bitmap.height.toFloat()
    if (bmpW <= 0f || bmpH <= 0f) return

    val canvas =
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          holder.lockHardwareCanvas()
        } else {
          holder.lockCanvas()
        }
      } catch (e: Throwable) {
        Log.w(TAG, "lockCanvas failed", e)
        null
      } ?: return
    try {
      val scale = max(viewW / bmpW, viewH / bmpH)
      val offsetX = (viewW - bmpW * scale) * 0.5f
      val offsetY = (viewH - bmpH * scale) * 0.5f
      drawMatrix.reset()
      drawMatrix.postScale(scale, scale)
      drawMatrix.postTranslate(offsetX, offsetY)
      canvas.drawColor(Color.BLACK)
      canvas.drawBitmap(bitmap, drawMatrix, paint)
    } finally {
      try {
        holder.unlockCanvasAndPost(canvas)
      } catch (e: Throwable) {
        Log.w(TAG, "unlockCanvasAndPost failed", e)
      }
    }
  }

  companion object {
    private const val TAG = "LiveTranslatorSurfaceView"
  }
}
