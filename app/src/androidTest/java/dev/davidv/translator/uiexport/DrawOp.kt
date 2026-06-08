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

/**
 * A captured paint operation in root (device) coordinates. The 3x3 affine [matrix] is the canvas
 * transform in effect when the op was drawn, in Android Matrix.getValues() order
 * [scaleX, skewX, transX, skewY, scaleY, transY, 0, 0, 1].
 */
sealed interface DrawOp {
  val matrix: FloatArray

  data class TextRun(
    override val matrix: FloatArray,
    val text: String,
    val x: Float,
    val baselineY: Float,
    val color: Int,
    val sizePx: Float,
    val bold: Boolean,
    val italic: Boolean,
    /** Measured advance width of the run, used to infer horizontal alignment from container margins. */
    val widthPx: Float,
  ) : DrawOp

  data class Rect(
    override val matrix: FloatArray,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val rx: Float,
    val color: Int,
    val stroke: Boolean,
    val strokeWidth: Float,
  ) : DrawOp

  data class Line(
    override val matrix: FloatArray,
    val startX: Float,
    val startY: Float,
    val stopX: Float,
    val stopY: Float,
    val color: Int,
    val strokeWidth: Float,
  ) : DrawOp

  /** Non-text vector content (icons, dividers drawn as paths) flattened to an SVG path string. */
  data class Path(
    override val matrix: FloatArray,
    val pathData: String,
    val color: Int,
    val stroke: Boolean,
    val strokeWidth: Float,
  ) : DrawOp

  /** A raster (vector asset rasterized by Compose, photo, etc.) with its device bounds and PNG bytes. */
  data class Image(
    override val matrix: FloatArray,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val pngBase64: String,
    /** contentDescription of the covering semantics node, if any (the bitmap itself carries no meaning). */
    val description: String? = null,
  ) : DrawOp
}
