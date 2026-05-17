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
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.Choreographer
import android.view.View
import dev.davidv.translator.BitmapOverlayFrame
import dev.davidv.translator.ImuService

/** Phase 1 → Phase 5 of FUTURE_BITMAP_OVERLAY.md.
 *
 *  Draws a canonical-frame bitmap warped to the current frame via
 *  `Matrix.setPolyToPoly`.
 *
 *  When an `ImuService` and the frame's intrinsics + per-frame IMU
 *  rotation are all available, the view also **extrapolates** the
 *  homography at refresh rate (60–120 Hz) between camera frames
 *  (30 Hz). This decouples render rate from camera rate: overlays
 *  glide smoothly under fast pan instead of stepping. The math is the
 *  same as the Rust-side IMU prior — `K · R_delta_cam · K^-1` composed
 *  with the stored canonical→camera-frame H.
 *
 *  If no IMU is available we just draw the stored H — degrades to the
 *  Phase 4 behaviour, no jitter introduced. */
class PlanarBitmapOverlayView(context: Context) : View(context) {
  private val paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      isFilterBitmap = true
    }
  private val matrix = Matrix()
  private val srcPoints = FloatArray(8)
  private val dstPoints = FloatArray(8)

  private var frame: BitmapOverlayFrame? = null
  private var imuService: ImuService? = null

  private val vsyncCallback =
    Choreographer.FrameCallback { _ ->
      // Re-invalidate every vsync while a frame is set. Cheap: a
      // single bitmap draw warped via Matrix is well below a frame
      // budget on any phone, and re-querying IMU is sub-microsecond.
      if (frame != null) {
        invalidate()
        Choreographer.getInstance().postFrameCallback(this.vsyncCallbackRef)
      }
    }

  // Holding the reference so we can re-post in the lambda body above.
  private val vsyncCallbackRef: Choreographer.FrameCallback get() = vsyncCallback

  fun setImuService(svc: ImuService?) {
    imuService = svc
  }

  fun update(newFrame: BitmapOverlayFrame?) {
    val wasNull = frame == null
    frame = newFrame
    if (newFrame != null && wasNull) {
      // (Re)arm the vsync loop on first non-null frame.
      Choreographer.getInstance().postFrameCallback(vsyncCallbackRef)
    }
    invalidate()
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    Choreographer.getInstance().removeFrameCallback(vsyncCallbackRef)
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val f = frame ?: return
    val bmp: Bitmap = f.bitmap
    if (bmp.isRecycled) return
    val viewW = width.toFloat()
    val viewH = height.toFloat()
    if (viewW <= 0f || viewH <= 0f) return

    // CENTER_CROP letterbox mapping (matches PreviewView default).
    val displayW = f.displayWidth.toFloat()
    val displayH = f.displayHeight.toFloat()
    if (displayW <= 0f || displayH <= 0f) return
    val scale = kotlin.math.max(viewW / displayW, viewH / displayH)
    val offsetX = (viewW - displayW * scale) * 0.5f
    val offsetY = (viewH - displayH * scale) * 0.5f

    // Decide which H to project through. Prefer the IMU-extrapolated
    // one if we have everything we need; otherwise fall back to the
    // stored camera-frame H (Phase 4 behaviour).
    val h = extrapolatedHomography(f) ?: f.homography

    val bw = f.frameWidth.toFloat()
    val bh = f.frameHeight.toFloat()
    srcPoints[0] = 0f
    srcPoints[1] = 0f
    srcPoints[2] = bw
    srcPoints[3] = 0f
    srcPoints[4] = bw
    srcPoints[5] = bh
    srcPoints[6] = 0f
    srcPoints[7] = bh

    val cropL = f.cropLeft.toFloat()
    val cropT = f.cropTop.toFloat()
    val ox = f.bitmapOriginCanonicalX
    val oy = f.bitmapOriginCanonicalY
    val canonicalCorners =
      floatArrayOf(ox, oy, ox + bw, oy, ox + bw, oy + bh, ox, oy + bh)
    for (i in 0 until 4) {
      val sx = canonicalCorners[i * 2]
      val sy = canonicalCorners[i * 2 + 1]
      val qx = h[0] * sx + h[1] * sy + h[2]
      val qy = h[3] * sx + h[4] * sy + h[5]
      val qw = h[6] * sx + h[7] * sy + h[8]
      if (qw == 0f || !qw.isFinite()) return
      val cropLocalX = qx / qw
      val cropLocalY = qy / qw
      val displayX = cropLocalX + cropL
      val displayY = cropLocalY + cropT
      dstPoints[i * 2] = displayX * scale + offsetX
      dstPoints[i * 2 + 1] = displayY * scale + offsetY
    }

    matrix.reset()
    if (!matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)) {
      return
    }
    canvas.drawBitmap(bmp, matrix, paint)
  }

  /** Compute `H_imu_delta · H_camera_frame`, mirroring the Rust
   *  `imu_prior::predict_canonical_to_current`. Inputs:
   *    - frame.imuRotationAtFrame: device-frame R at the camera frame
   *    - imuService.currentRotation(): device-frame R right now
   *    - frame.intrinsics: K
   *  Returns null when anything's missing (no IMU service, no
   *  intrinsics, or the rotation matrix is malformed); the caller
   *  falls back to the stored H. */
  private fun extrapolatedHomography(f: BitmapOverlayFrame): FloatArray? {
    val svc = imuService ?: return null
    val rotPrev = f.imuRotationAtFrame ?: return null
    if (rotPrev.size != 9) return null
    val intr = f.intrinsics ?: return null
    val rotCurr =
      try {
        svc.currentRotation()
      } catch (_: Throwable) {
        return null
      }
    if (rotCurr.size != 9) return null

    // R_delta_dev = R_curr · R_prev^T
    val rPrevT = transpose3(rotPrev)
    val rDeltaDev = mat3Mul(rotCurr, rPrevT)
    // Sandwich into camera frame: M · R · M^T, M = diag(1, -1, -1).
    val rDeltaCam = FloatArray(9)
    for (i in 0..2) {
      for (j in 0..2) {
        val sign = (if (i == 0) 1 else -1) * (if (j == 0) 1 else -1)
        rDeltaCam[i * 3 + j] = sign * rDeltaDev[i * 3 + j]
      }
    }
    // K · R · K^-1
    val k =
      floatArrayOf(
        intr.fx, 0f, intr.cx, 0f, intr.fy, intr.cy, 0f, 0f, 1f,
      )
    val kInv =
      floatArrayOf(
        1f / intr.fx,
        0f,
        -intr.cx / intr.fx,
        0f,
        1f / intr.fy,
        -intr.cy / intr.fy,
        0f,
        0f,
        1f,
      )
    val kr = mat3Mul(k, rDeltaCam)
    val hImu = mat3Mul(kr, kInv)
    // Compose with the camera-frame H to get canonical→current.
    return mat3Mul(hImu, f.homography)
  }

  private fun mat3Mul(
    a: FloatArray,
    b: FloatArray,
  ): FloatArray {
    val out = FloatArray(9)
    for (i in 0..2) {
      for (j in 0..2) {
        var s = 0f
        for (k in 0..2) s += a[i * 3 + k] * b[k * 3 + j]
        out[i * 3 + j] = s
      }
    }
    return out
  }

  private fun transpose3(m: FloatArray): FloatArray = floatArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])
}
