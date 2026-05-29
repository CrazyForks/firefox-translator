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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.View

/**
 *  Draws the translation overlay through the View hierarchy with a
 *  hardware-accelerated `Canvas` — deliberately not a SurfaceView. On
 *  Android 12+ only pixels composited through the window are dimmed by
 *  `WindowManager.LayoutParams.alpha`; a SurfaceView is a separate
 *  SurfaceFlinger layer at full opacity, so the OS flags touches as obscured
 *  and blocks them from the app below. Canvas pixels are part of the window
 *  and get dimmed, which is what lets the host window sit just under the
 *  untrusted-touch opacity cap (~0.8) and pass taps through.
 *
 *  The bitmap is the GL renderer's overlay readback (canonical size); we
 *  scale it to the view bounds at draw time.
 */
class ScreenOverlayView(context: Context) : View(context) {
  @Volatile
  private var overlay: Bitmap? = null
  private val matrix = Matrix()
  private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

  fun setOverlayBitmap(bitmap: Bitmap) {
    overlay = bitmap
    postInvalidate()
  }

  /** Drop the overlay so the window draws nothing (transparent) — used to give
   *  the screen capture a clean, overlay-free frame before an acquire. */
  fun clearOverlay() {
    overlay = null
    postInvalidate()
  }

  override fun onDraw(canvas: Canvas) {
    val bmp = overlay ?: return
    // The readback is a GL framebuffer (bottom-up), so scale to fill and flip
    // vertically to match the on-screen (top-down) orientation.
    matrix.reset()
    matrix.setScale(width.toFloat() / bmp.width, -height.toFloat() / bmp.height)
    matrix.postTranslate(0f, height.toFloat())
    canvas.drawBitmap(bmp, matrix, paint)
  }
}
