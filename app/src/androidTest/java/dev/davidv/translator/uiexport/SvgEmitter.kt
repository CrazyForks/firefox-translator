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

import java.util.Locale

/**
 * Renders captured [DrawOp]s into a standalone SVG document. Pure: same ops in, same string out.
 *
 * Each text label carries `data-key="{route}:{seq}"` and `data-source` (the original string) so a
 * downstream viewer can swap in a translation by key while keeping its exact position.
 */
object SvgEmitter {
  fun emit(
    route: String,
    ops: List<DrawOp>,
    width: Int,
    height: Int,
  ): String {
    val sb = StringBuilder()
    sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
    sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" """)
    sb.append("""viewBox="0 0 $width $height" font-family="sans-serif">""").append('\n')

    // The window background is a fixed full-screen rect, kept once by stitching and so only covering
    // the first viewport; repaint it across the whole (possibly taller) document first.
    backgroundColor(ops, width)?.let { bg ->
      sb.appendLine("""  <rect x="0" y="0" width="$width" height="$height" fill="${hex(bg)}"/>""")
    }

    val containers = ops.filterIsInstance<DrawOp.Rect>().filterNot { isWindowBackground(it, width) }

    var textSeq = 0
    var imageSeq = 0
    val imageKeyCounts = HashMap<String, Int>()
    for (op in ops) {
      when (op) {
        // Skip the full-bleed opaque window background; the document background above already covers
        // it, and a copy stitched from a later frame would otherwise paint over earlier content.
        is DrawOp.Rect -> if (!isWindowBackground(op, width)) sb.appendLine(rect(op))
        is DrawOp.Line -> sb.appendLine(line(op))
        is DrawOp.Path -> sb.appendLine(path(op))
        is DrawOp.Image -> sb.appendLine(image(op, imageKey(route, op, imageKeyCounts) { imageSeq++ }))
        is DrawOp.TextRun -> sb.appendLine(text(op, "$route:${textSeq++}", containers))
      }
    }
    sb.append("</svg>").append('\n')
    return sb.toString()
  }

  /**
   * A standalone panel listing a dropdown's options as editable labels. Used because the real popup
   * is a separate window we can't draw; each option carries `data-key="{keyBase}:option:{i}"`.
   */
  fun emitOptionsPanel(
    title: String,
    keyBase: String,
    options: List<String>,
  ): String {
    val width = 760
    val pad = 36
    val titleSize = 50f
    val rowSize = 44f
    val rowH = 96
    val height = pad + 70 + options.size * rowH + pad

    val sb = StringBuilder()
    sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
    sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" """)
    sb.append("""viewBox="0 0 $width $height" font-family="sans-serif">""").append('\n')
    sb.appendLine("""  <rect x="0" y="0" width="$width" height="$height" fill="#14121c"/>""")
    sb.appendLine(
      """  <text x="$pad" y="${pad + titleSize.toInt()}" font-size="${num(titleSize)}" fill="#c9beff">${esc(title)}</text>""",
    )
    options.forEachIndexed { i, option ->
      val y = pad + 70 + i * rowH + rowSize.toInt()
      sb.appendLine(
        """  <text data-key="$keyBase:option:$i" data-source="${esc(option)}" x="$pad" y="$y"""" +
          """ font-size="${num(rowSize)}" fill="#e5e0ef">${esc(option)}</text>""",
      )
    }
    sb.append("</svg>").append('\n')
    return sb.toString()
  }

  private fun text(
    op: DrawOp.TextRun,
    key: String,
    containers: List<DrawOp.Rect>,
  ): String {
    val weight = if (op.bold) "bold" else "normal"
    val style = if (op.italic) "italic" else "normal"
    val (anchor, anchorX) = horizontalAnchor(op, containers)
    val anchorAttr = if (anchor == "start") "" else """ text-anchor="$anchor""""
    return """  <text data-key="$key" data-source="${esc(op.text)}" transform="${svgMatrix(op.matrix)}"""" +
      """ x="${num(anchorX)}" y="${num(op.baselineY)}" font-size="${num(op.sizePx)}"""" +
      """ font-weight="$weight" font-style="$style"$anchorAttr${fill(op.color)}>${esc(op.text)}</text>"""
  }

  /**
   * Infers a label's horizontal anchor from its margins inside the widest card-like rect that
   * encloses it, so right-placed text grows leftward and centered text stays centered when edited.
   * Compose bakes alignment into the draw position (paint is always LEFT), so geometry is the only
   * signal; this is a heuristic tuned to be conservative — it only departs from `start` when the text
   * clearly hugs the right edge or sits clearly centered.
   */
  private fun horizontalAnchor(
    op: DrawOp.TextRun,
    containers: List<DrawOp.Rect>,
  ): Pair<String, Float> {
    val left = op.matrix[2] + op.x
    val right = left + op.widthPx
    val baseline = op.matrix[5] + op.baselineY

    val container =
      containers
        .filter { r ->
          val cl = r.matrix[2] + r.left
          val cr = r.matrix[2] + r.right
          val ct = r.matrix[5] + r.top
          val cb = r.matrix[5] + r.bottom
          left >= cl - 1f && right <= cr + 1f && baseline >= ct && baseline <= cb && (cr - cl) > op.widthPx + 4f
        }
        .maxByOrNull { (it.matrix[2] + it.right) - (it.matrix[2] + it.left) }
        ?: return "start" to op.x

    val cl = container.matrix[2] + container.left
    val cr = container.matrix[2] + container.right
    val cw = cr - cl
    val leftMargin = left - cl
    val rightMargin = cr - right

    return when {
      minOf(leftMargin, rightMargin) > cw * 0.15f && kotlin.math.abs(leftMargin - rightMargin) < cw * 0.08f ->
        "middle" to (op.x + op.widthPx / 2f)
      leftMargin > cw * 0.25f && rightMargin < cw * 0.15f ->
        "end" to (op.x + op.widthPx)
      else ->
        "start" to op.x
    }
  }

  private fun rect(op: DrawOp.Rect): String {
    val w = op.right - op.left
    val h = op.bottom - op.top
    if (w <= 0f || h <= 0f) return ""
    val paint = if (op.stroke) stroke(op.color, op.strokeWidth) else fill(op.color)
    val radius = if (op.rx > 0f) """ rx="${num(op.rx)}"""" else ""
    return """  <rect transform="${svgMatrix(op.matrix)}" x="${num(op.left)}" y="${num(op.top)}"""" +
      """ width="${num(w)}" height="${num(h)}"$radius$paint/>"""
  }

  private fun line(op: DrawOp.Line): String =
    """  <line transform="${svgMatrix(op.matrix)}" x1="${num(op.startX)}" y1="${num(op.startY)}"""" +
      """ x2="${num(op.stopX)}" y2="${num(op.stopY)}"${stroke(op.color, op.strokeWidth)}/>"""

  private fun path(op: DrawOp.Path): String {
    if (op.pathData.isEmpty()) return ""
    val paint = if (op.stroke) stroke(op.color, op.strokeWidth) else fill(op.color)
    return """  <path transform="${svgMatrix(op.matrix)}" d="${op.pathData}"$paint/>"""
  }

  /** The window background: the first full-bleed opaque rect at the origin. */
  private fun backgroundColor(
    ops: List<DrawOp>,
    width: Int,
  ): Int? = ops.filterIsInstance<DrawOp.Rect>().firstOrNull { isWindowBackground(it, width) }?.color

  private fun isWindowBackground(
    op: DrawOp.Rect,
    width: Int,
  ): Boolean {
    val opaque = (op.color ushr 24) and 0xff == 0xff
    val atOrigin = op.matrix[2] <= 1f && op.matrix[5] <= 1f && op.left <= 1f && op.top <= 1f
    return opaque && atOrigin && !op.stroke && (op.right - op.left) >= width * 0.95f && (op.bottom - op.top) >= 200f
  }

  /**
   * A stable, human-readable key: the description slugged plus a per-description counter
   * (e.g. `language_manager:download-3`), falling back to a positional id for undescribed images.
   */
  private fun imageKey(
    route: String,
    op: DrawOp.Image,
    counts: HashMap<String, Int>,
    nextSeq: () -> Int,
  ): String {
    val slug = op.description?.lowercase()?.replace(Regex("[^a-z0-9]+"), "-")?.trim('-')?.takeIf { it.isNotEmpty() }
    if (slug == null) return "$route:img-${nextSeq()}"
    val n = counts.merge(slug, 1, Int::plus)!!
    return "$route:$slug-$n"
  }

  private fun image(
    op: DrawOp.Image,
    key: String,
  ): String {
    val w = op.right - op.left
    val h = op.bottom - op.top
    if (w <= 0f || h <= 0f) return ""
    val desc = if (op.description != null) """ data-desc="${esc(op.description)}"""" else ""
    return """  <image class="image" data-key="$key"$desc transform="${svgMatrix(op.matrix)}"""" +
      """ x="${num(op.left)}" y="${num(op.top)}" width="${num(w)}" height="${num(h)}"""" +
      """ href="data:image/png;base64,${op.pngBase64}"/>"""
  }

  /** Android Matrix.getValues order -> SVG matrix(a b c d e f). */
  private fun svgMatrix(m: FloatArray): String = "matrix(${num(m[0])} ${num(m[3])} ${num(m[1])} ${num(m[4])} ${num(m[2])} ${num(m[5])})"

  private fun fill(color: Int): String {
    val a = (color ushr 24) and 0xff
    return """ fill="${hex(color)}"""" + if (a < 255) """ fill-opacity="${num(a / 255f)}"""" else ""
  }

  private fun stroke(
    color: Int,
    widthPx: Float,
  ): String {
    val a = (color ushr 24) and 0xff
    val w = if (widthPx > 0f) widthPx else 1f
    return """ fill="none" stroke="${hex(color)}" stroke-width="${num(w)}"""" +
      if (a < 255) """ stroke-opacity="${num(a / 255f)}"""" else ""
  }

  private fun hex(color: Int): String = String.format("#%02x%02x%02x", (color shr 16) and 0xff, (color shr 8) and 0xff, color and 0xff)

  private fun num(v: Float): String {
    if (v == v.toLong().toFloat()) return v.toLong().toString()
    return String.format(Locale.US, "%.2f", v)
  }

  private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
