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

package dev.davidv.translator.uiexport

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Typeface
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * A software [Canvas] that records the draw calls a view issues while it renders, instead of only
 * producing pixels. Drawing a laid-out view into this canvas on the UI thread yields a flat,
 * painter-ordered [ops] list with each element's text/geometry, color, font metrics and transform.
 *
 * It still paints into the backing bitmap (every override calls `super`), so the bitmap doubles as a
 * visual sanity check that what we recorded matches what the user sees.
 */
class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
  val ops = mutableListOf<DrawOp>()

  // Effective layer alpha per save level, kept in sync with the canvas save count. Compose hides
  // elements (e.g. the at-rest pull-to-refresh indicator) by drawing them inside an alpha-0 layer;
  // we honor that so their ops are dropped (or dimmed) instead of recorded at full opacity.
  private val alphaStack = ArrayDeque<Float>().apply { addLast(1f) }

  private fun curAlpha(): Float = alphaStack.last()

  private fun syncTo(count: Int) {
    while (alphaStack.size > count && alphaStack.size > 1) alphaStack.removeLast()
    while (alphaStack.size < count) alphaStack.addLast(alphaStack.last())
  }

  /** True if the rect is entirely outside the current clip (so the real renderer wouldn't paint it). */
  private fun clippedOut(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
  ): Boolean =
    try {
      quickReject(left, top, right, bottom)
    } catch (_: Throwable) {
      false
    }

  /** Scale a color's alpha channel by the current layer alpha. */
  private fun withLayerAlpha(color: Int): Int {
    val a = (((color ushr 24) and 0xff) * curAlpha()).toInt().coerceIn(0, 255)
    return (a shl 24) or (color and 0x00ffffff)
  }

  override fun save(): Int {
    val r = super.save()
    syncTo(saveCount)
    return r
  }

  override fun saveLayer(
    bounds: RectF?,
    paint: Paint?,
  ): Int {
    val parent = curAlpha()
    val r = super.saveLayer(bounds, paint)
    syncTo(saveCount)
    alphaStack[alphaStack.size - 1] = parent * ((paint?.alpha ?: 255) / 255f)
    return r
  }

  override fun saveLayer(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    paint: Paint?,
  ): Int {
    val parent = curAlpha()
    val r = super.saveLayer(left, top, right, bottom, paint)
    syncTo(saveCount)
    alphaStack[alphaStack.size - 1] = parent * ((paint?.alpha ?: 255) / 255f)
    return r
  }

  override fun saveLayerAlpha(
    bounds: RectF?,
    alpha: Int,
  ): Int {
    val parent = curAlpha()
    val r = super.saveLayerAlpha(bounds, alpha)
    syncTo(saveCount)
    alphaStack[alphaStack.size - 1] = parent * (alpha / 255f)
    return r
  }

  override fun saveLayerAlpha(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    alpha: Int,
  ): Int {
    val parent = curAlpha()
    val r = super.saveLayerAlpha(left, top, right, bottom, alpha)
    syncTo(saveCount)
    alphaStack[alphaStack.size - 1] = parent * (alpha / 255f)
    return r
  }

  override fun restore() {
    super.restore()
    syncTo(saveCount)
  }

  override fun restoreToCount(saveCount: Int) {
    super.restoreToCount(saveCount)
    syncTo(this.saveCount)
  }

  private fun snapshotMatrix(): FloatArray {
    val m = Matrix()
    getMatrix(m)
    val values = FloatArray(9)
    m.getValues(values)
    return values
  }

  private fun Paint.isBold(): Boolean = (typeface ?: Typeface.DEFAULT).isBold || isFakeBoldText

  private fun Paint.isItalic(): Boolean = (typeface ?: Typeface.DEFAULT).isItalic

  private fun recordText(
    text: String,
    x: Float,
    y: Float,
    paint: Paint,
  ) {
    if (text.isEmpty() || curAlpha() < 0.01f) return
    if (clippedOut(x, y - paint.textSize, x + paint.measureText(text), y + paint.descent())) return
    ops +=
      DrawOp.TextRun(
        matrix = snapshotMatrix(),
        text = text,
        x = x,
        baselineY = y,
        color = withLayerAlpha(paint.color),
        sizePx = paint.textSize,
        bold = paint.isBold(),
        italic = paint.isItalic(),
      )
  }

  override fun drawText(
    text: String,
    x: Float,
    y: Float,
    paint: Paint,
  ) {
    recordText(text, x, y, paint)
    super.drawText(text, x, y, paint)
  }

  override fun drawText(
    text: String,
    start: Int,
    end: Int,
    x: Float,
    y: Float,
    paint: Paint,
  ) {
    recordText(text.substring(start, end), x, y, paint)
    super.drawText(text, start, end, x, y, paint)
  }

  override fun drawText(
    text: CharSequence,
    start: Int,
    end: Int,
    x: Float,
    y: Float,
    paint: Paint,
  ) {
    recordText(text.subSequence(start, end).toString(), x, y, paint)
    super.drawText(text, start, end, x, y, paint)
  }

  override fun drawText(
    text: CharArray,
    index: Int,
    count: Int,
    x: Float,
    y: Float,
    paint: Paint,
  ) {
    recordText(String(text, index, count), x, y, paint)
    super.drawText(text, index, count, x, y, paint)
  }

  override fun drawTextRun(
    text: CharSequence,
    start: Int,
    end: Int,
    contextStart: Int,
    contextEnd: Int,
    x: Float,
    y: Float,
    isRtl: Boolean,
    paint: Paint,
  ) {
    recordText(text.subSequence(start, end).toString(), x, y, paint)
    super.drawTextRun(text, start, end, contextStart, contextEnd, x, y, isRtl, paint)
  }

  override fun drawTextRun(
    text: CharArray,
    index: Int,
    count: Int,
    contextIndex: Int,
    contextCount: Int,
    x: Float,
    y: Float,
    isRtl: Boolean,
    paint: Paint,
  ) {
    recordText(String(text, index, count), x, y, paint)
    super.drawTextRun(text, index, count, contextIndex, contextCount, x, y, isRtl, paint)
  }

  private fun recordRect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    rx: Float,
    paint: Paint,
  ) {
    if (curAlpha() < 0.01f || clippedOut(left, top, right, bottom)) return
    ops +=
      DrawOp.Rect(
        matrix = snapshotMatrix(),
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        rx = rx,
        color = withLayerAlpha(paint.color),
        stroke = paint.style != Paint.Style.FILL,
        strokeWidth = paint.strokeWidth,
      )
  }

  override fun drawRect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    paint: Paint,
  ) {
    recordRect(left, top, right, bottom, 0f, paint)
    super.drawRect(left, top, right, bottom, paint)
  }

  override fun drawRect(
    rect: RectF,
    paint: Paint,
  ) {
    recordRect(rect.left, rect.top, rect.right, rect.bottom, 0f, paint)
    super.drawRect(rect, paint)
  }

  override fun drawRoundRect(
    rect: RectF,
    rx: Float,
    ry: Float,
    paint: Paint,
  ) {
    recordRect(rect.left, rect.top, rect.right, rect.bottom, rx, paint)
    super.drawRoundRect(rect, rx, ry, paint)
  }

  override fun drawRoundRect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    rx: Float,
    ry: Float,
    paint: Paint,
  ) {
    recordRect(left, top, right, bottom, rx, paint)
    super.drawRoundRect(left, top, right, bottom, rx, ry, paint)
  }

  override fun drawLine(
    startX: Float,
    startY: Float,
    stopX: Float,
    stopY: Float,
    paint: Paint,
  ) {
    if (curAlpha() >= 0.01f &&
      !clippedOut(minOf(startX, stopX), minOf(startY, stopY), maxOf(startX, stopX), maxOf(startY, stopY))
    ) {
      ops +=
        DrawOp.Line(
          matrix = snapshotMatrix(),
          startX = startX,
          startY = startY,
          stopX = stopX,
          stopY = stopY,
          color = withLayerAlpha(paint.color),
          strokeWidth = paint.strokeWidth,
        )
    }
    super.drawLine(startX, startY, stopX, stopY, paint)
  }

  private fun recordImage(
    bitmap: Bitmap,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    paint: Paint?,
  ) {
    if (bitmap.width <= 0 || bitmap.height <= 0 || curAlpha() < 0.01f || clippedOut(left, top, right, bottom)) return
    // Compose draws vector icons as ALPHA_8 masks tinted by the paint, and caches some drawables as
    // hardware bitmaps; neither encodes to PNG directly. Re-draw the bitmap with its paint onto an
    // ARGB surface so the tint/color-filter is baked in, then encode that. Hardware bitmaps can't be
    // drawn onto a software canvas, so copy those to a software config first.
    val src = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    if (src == null) return
    val baked = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    Canvas(baked).drawBitmap(src, 0f, 0f, paint)
    val stream = ByteArrayOutputStream()
    if (!baked.compress(Bitmap.CompressFormat.PNG, 100, stream) || stream.size() == 0) {
      Log.w("UiExport", "bitmap compress failed: config=${bitmap.config} ${bitmap.width}x${bitmap.height}")
      return
    }
    ops +=
      DrawOp.Image(
        matrix = snapshotMatrix(),
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        pngBase64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP),
      )
  }

  override fun drawBitmap(
    bitmap: Bitmap,
    left: Float,
    top: Float,
    paint: Paint?,
  ) {
    recordImage(bitmap, left, top, left + bitmap.width, top + bitmap.height, paint)
    super.drawBitmap(bitmap, left, top, paint)
  }

  override fun drawBitmap(
    bitmap: Bitmap,
    src: Rect?,
    dst: Rect,
    paint: Paint?,
  ) {
    recordImage(bitmap, dst.left.toFloat(), dst.top.toFloat(), dst.right.toFloat(), dst.bottom.toFloat(), paint)
    super.drawBitmap(bitmap, src, dst, paint)
  }

  override fun drawBitmap(
    bitmap: Bitmap,
    src: Rect?,
    dst: RectF,
    paint: Paint?,
  ) {
    recordImage(bitmap, dst.left, dst.top, dst.right, dst.bottom, paint)
    super.drawBitmap(bitmap, src, dst, paint)
  }

  override fun drawBitmap(
    bitmap: Bitmap,
    matrix: Matrix,
    paint: Paint?,
  ) {
    val corners = floatArrayOf(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
    matrix.mapPoints(corners)
    recordImage(bitmap, corners[0], corners[1], corners[2], corners[3], paint)
    super.drawBitmap(bitmap, matrix, paint)
  }

  override fun drawRenderNode(renderNode: RenderNode) {
    // Software canvas can't replay RenderNodes (transient overscroll-stretch / layer effects). Skip
    // rather than crash; the wrapped content is captured in frames where it isn't behind a layer.
    Log.w("UiExport", "skipped RenderNode ${renderNode.width}x${renderNode.height}")
  }

  override fun drawPath(
    path: Path,
    paint: Paint,
  ) {
    val bounds = RectF()
    path.computeBounds(bounds, true)
    if (curAlpha() >= 0.01f && !clippedOut(bounds.left, bounds.top, bounds.right, bounds.bottom)) {
      ops +=
        DrawOp.Path(
          matrix = snapshotMatrix(),
          pathData = flattenToSvg(path),
          color = withLayerAlpha(paint.color),
          stroke = paint.style != Paint.Style.FILL,
          strokeWidth = paint.strokeWidth,
        )
    }
    super.drawPath(path, paint)
  }

  private fun recordArc(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    startAngle: Float,
    sweepAngle: Float,
    useCenter: Boolean,
    paint: Paint,
  ) {
    if (curAlpha() < 0.01f || clippedOut(left, top, right, bottom)) return
    val path = Path()
    path.addArc(left, top, right, bottom, startAngle, sweepAngle)
    if (useCenter) path.lineTo((left + right) / 2f, (top + bottom) / 2f)
    ops +=
      DrawOp.Path(
        matrix = snapshotMatrix(),
        pathData = flattenToSvg(path),
        color = withLayerAlpha(paint.color),
        stroke = paint.style != Paint.Style.FILL,
        strokeWidth = paint.strokeWidth,
      )
  }

  override fun drawArc(
    oval: RectF,
    startAngle: Float,
    sweepAngle: Float,
    useCenter: Boolean,
    paint: Paint,
  ) {
    recordArc(oval.left, oval.top, oval.right, oval.bottom, startAngle, sweepAngle, useCenter, paint)
    super.drawArc(oval, startAngle, sweepAngle, useCenter, paint)
  }

  override fun drawArc(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    startAngle: Float,
    sweepAngle: Float,
    useCenter: Boolean,
    paint: Paint,
  ) {
    recordArc(left, top, right, bottom, startAngle, sweepAngle, useCenter, paint)
    super.drawArc(left, top, right, bottom, startAngle, sweepAngle, useCenter, paint)
  }

  /** Walk each contour with [PathMeasure] and emit a polyline approximation as SVG path data. */
  private fun flattenToSvg(path: Path): String {
    val measure = PathMeasure(path, false)
    val pos = FloatArray(2)
    val sb = StringBuilder()
    do {
      val length = measure.length
      if (length <= 0f) continue
      val steps = (length / 2f).toInt().coerceIn(2, 256)
      for (i in 0..steps) {
        val d = length * i / steps
        measure.getPosTan(d, pos, null)
        sb.append(if (i == 0) "M" else "L").append(fmt(pos[0])).append(' ').append(fmt(pos[1])).append(' ')
      }
    } while (measure.nextContour())
    return sb.toString().trim()
  }

  private fun fmt(v: Float): String = ((v * 100).toInt() / 100f).toString()
}
