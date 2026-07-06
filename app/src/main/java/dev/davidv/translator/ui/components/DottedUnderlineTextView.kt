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
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.TextView

/**
 * A selectable [TextView] that draws a subtle dotted underline under given
 * char ranges (`[start, endExclusive)` per `IntRange` built with `start until
 * end`). Used to mark translated words that have alternatives.
 */
class DottedUnderlineTextView
  @JvmOverloads
  constructor(
    context: Context,
    attrs: AttributeSet? = null,
  ) : TextView(context, attrs) {
    var underlineRanges: List<IntRange> = emptyList()
      set(value) {
        field = value
        invalidate()
      }

    private val density = resources.displayMetrics.density
    private val underlinePaint =
      Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(2f * density, 2.5f * density), 0f)
      }

    override fun onDraw(canvas: Canvas) {
      super.onDraw(canvas)
      if (underlineRanges.isEmpty()) return
      val l = layout ?: return
      val len = text?.length ?: 0
      // ~45% of the text colour so it reads as a hint, not an edit.
      underlinePaint.color = (currentTextColor and 0x00FFFFFF) or (0x73 shl 24)
      val padLeft = totalPaddingLeft.toFloat()
      val padTop = totalPaddingTop.toFloat()
      val gap = 2.5f * density

      for (range in underlineRanges) {
        val start = range.first.coerceIn(0, len)
        val end = (range.last + 1).coerceIn(start, len)
        if (start >= end) continue
        val firstLine = l.getLineForOffset(start)
        val lastLine = l.getLineForOffset(end)
        for (line in firstLine..lastLine) {
          val segStart = if (line == firstLine) start else l.getLineStart(line)
          val segEnd = if (line == lastLine) end else l.getLineEnd(line)
          var x1 = l.getPrimaryHorizontal(segStart)
          var x2 = l.getPrimaryHorizontal(segEnd)
          if (x1 > x2) {
            val t = x1
            x1 = x2
            x2 = t
          }
          val y = padTop + l.getLineBaseline(line) + gap
          canvas.drawLine(padLeft + x1, y, padLeft + x2, y, underlinePaint)
        }
      }
    }
  }
