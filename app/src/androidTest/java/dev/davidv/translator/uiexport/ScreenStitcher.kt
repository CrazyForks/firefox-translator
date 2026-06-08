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

/** One scroll position's worth of recorded ops, in screen coordinates. */
data class CaptureFrame(val ops: List<DrawOp>)

data class StitchedScreen(val ops: List<DrawOp>, val height: Int)

/**
 * Merges scroll frames into a single full-height screen.
 *
 * A scrollable view only paints what's in its viewport, so we capture at successive scroll positions
 * and reassemble the document. The scroll distance between two consecutive frames is recovered by
 * matching labels they share — a label unique in both frames moves down by exactly the scroll delta,
 * so the median of those movements is the offset — which works for any scroll container (including
 * LazyColumn, whose programmatic-scroll reporting is unreliable) and is pixel-accurate even with fling.
 *
 * Fixed chrome (app bar, etc.) is then detected as elements that appear at the same screen position
 * under two different scroll offsets; those are kept once. Everything else is scrolling content,
 * shifted into document space by its frame's offset, with overlapping re-captures de-duplicated.
 */
object ScreenStitcher {
  fun stitch(frames: List<CaptureFrame>): StitchedScreen {
    if (frames.isEmpty()) return StitchedScreen(emptyList(), 0)

    val offsets = FloatArray(frames.size)
    for (i in 1 until frames.size) {
      offsets[i] = offsets[i - 1] + estimateScrollDelta(frames[i - 1].ops, frames[i].ops)
    }

    val offsetsByScreenKey = HashMap<String, MutableSet<Int>>()
    frames.forEachIndexed { i, frame ->
      for (op in frame.ops) offsetsByScreenKey.getOrPut(key(op)) { HashSet() }.add(offsets[i].toInt())
    }
    val chromeKeys = offsetsByScreenKey.filterValues { it.size >= 2 }.keys

    val seen = HashSet<String>()
    val out = ArrayList<DrawOp>()
    frames.forEachIndexed { i, frame ->
      for (op in frame.ops) {
        if (key(op) in chromeKeys) {
          if (seen.add("C|${key(op)}")) out += op
          continue
        }
        val placed = shiftY(op, offsets[i])
        if (seen.add("S|${key(placed)}")) out += placed
      }
    }

    val height = out.maxOfOrNull { bottomOf(it) }?.let { it + 24f } ?: 0f
    return StitchedScreen(out, height.toInt())
  }

  /** Median downward movement of labels shared (and unique) between two frames. */
  private fun estimateScrollDelta(
    prev: List<DrawOp>,
    cur: List<DrawOp>,
  ): Float {
    val prevY = uniqueTextY(prev)
    val curY = uniqueTextY(cur)
    val deltas =
      prevY.keys.intersect(curY.keys)
        .map { prevY.getValue(it) - curY.getValue(it) }
        .filter { it > 5f }
        .sorted()
    if (deltas.isNotEmpty()) return deltas[deltas.size / 2]

    // No shared labels: overlap was lost. Place cur contiguously below prev rather than stacking it.
    val prevBottom = prev.filterIsInstance<DrawOp.TextRun>().maxOfOrNull { it.matrix[5] + it.baselineY } ?: 0f
    val curTop = cur.filterIsInstance<DrawOp.TextRun>().minOfOrNull { it.matrix[5] + it.baselineY } ?: 0f
    return (prevBottom - curTop).coerceAtLeast(0f)
  }

  private fun uniqueTextY(ops: List<DrawOp>): Map<String, Float> =
    ops.filterIsInstance<DrawOp.TextRun>()
      .groupBy { it.text }
      .filterValues { it.size == 1 }
      .mapValues { (_, runs) -> runs.first().let { it.matrix[5] + it.baselineY } }

  private fun shiftY(
    op: DrawOp,
    dy: Float,
  ): DrawOp {
    if (dy == 0f) return op
    val m = op.matrix.copyOf()
    m[5] = m[5] + dy
    return when (op) {
      is DrawOp.TextRun -> op.copy(matrix = m)
      is DrawOp.Rect -> op.copy(matrix = m)
      is DrawOp.Line -> op.copy(matrix = m)
      is DrawOp.Path -> op.copy(matrix = m)
      is DrawOp.Image -> op.copy(matrix = m)
    }
  }

  private fun bottomOf(op: DrawOp): Float {
    val ty = op.matrix[5]
    return ty +
      when (op) {
        is DrawOp.TextRun -> op.baselineY
        is DrawOp.Rect -> op.bottom
        is DrawOp.Line -> maxOf(op.startY, op.stopY)
        is DrawOp.Image -> op.bottom
        is DrawOp.Path -> 0f
      }
  }

  /** Position key rounded to 2px; scroll steps are far larger, so distinct content never collides. */
  private fun key(op: DrawOp): String {
    val x = (op.matrix[2] / 2f).toInt()
    val y = (op.matrix[5] / 2f).toInt()
    return when (op) {
      is DrawOp.TextRun -> "T|${op.text}|$x|$y"
      is DrawOp.Rect -> "R|$x|$y|${op.right.toInt()}|${op.bottom.toInt()}|${op.color}"
      is DrawOp.Line -> "L|$x|$y|${op.stopX.toInt()}|${op.stopY.toInt()}"
      is DrawOp.Path -> "P|$x|$y|${op.pathData.length}|${op.color}"
      is DrawOp.Image -> "I|$x|$y|${op.pngBase64.length}"
    }
  }
}
