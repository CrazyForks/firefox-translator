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

import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Magnifier
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.davidv.translator.R
import uniffi.translator.OrientedRect
import uniffi.translator.PositionedWord
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Per-word selection data for one translated image, in the image-pixel space of the displayed
 * bitmap. Source words come from the recognized text (CTC firings); translated words from the
 * overlay layout. Both produced in translator-rs; each word carries its `lineIndex`.
 */
data class ImageWordSelection(
  val imageWidth: Int,
  val imageHeight: Int,
  val sourceWords: List<PositionedWord>,
  val translatedWords: List<PositionedWord>,
)

private fun isCjkChar(c: Char): Boolean {
  val cp = c.code
  return cp in 0x4E00..0x9FFF ||
    cp in 0x3400..0x4DBF ||
    cp in 0x3040..0x30FF ||
    cp in 0xAC00..0xD7AF ||
    cp in 0xF900..0xFAFF
}

/** Join word texts: no separator for CJK-dominant runs, spaces otherwise. */
private fun joinSelected(texts: List<String>): String {
  val nonWs = texts.sumOf { t -> t.count { !it.isWhitespace() } }
  val cjk = texts.sumOf { t -> t.count { isCjkChar(it) } }
  val sep = if (nonWs > 0 && cjk * 2 >= nonWs) "" else " "
  return texts.joinToString(sep).trim()
}

/** Position along the cross-reading axis (perpendicular to the text), where lines advance. */
private fun crossPos(w: PositionedWord): Float = -w.bounds.cx * sin(w.bounds.angleRadians) + w.bounds.cy * cos(w.bounds.angleRadians)

/** Cross-axis position of an oriented rect (used to order/space merged line pills). */
private fun crossOf(rect: OrientedRect): Float = -rect.cx * sin(rect.angleRadians) + rect.cy * cos(rect.angleRadians)

/** Reading-axis position of an oriented rect (the direction text flows along it). */
private fun readOf(rect: OrientedRect): Float = rect.cx * cos(rect.angleRadians) + rect.cy * sin(rect.angleRadians)

/** True when two consecutive lines sit far enough apart (a paragraph gap) to be different blocks. */
private fun isBlockBreak(
  prev: List<PositionedWord>,
  cur: List<PositionedWord>,
): Boolean {
  val h = max(prev.maxOf { it.bounds.height }, cur.maxOf { it.bounds.height })
  return abs(crossPos(cur[0]) - crossPos(prev[0])) > 1.5f * h
}

/**
 * The copy/share text for a selection: words grouped into lines (`lineIndex`), lines merged into
 * blocks by their cross-axis gap, each block joined (CJK-aware), and blocks separated by newlines.
 */
private fun buildSelectionText(selected: List<PositionedWord>): String {
  if (selected.isEmpty()) return ""
  val lines = mutableListOf<MutableList<PositionedWord>>()
  for (w in selected) {
    val last = lines.lastOrNull()
    if (last != null && last[0].lineIndex == w.lineIndex) last.add(w) else lines.add(mutableListOf(w))
  }
  val blocks = mutableListOf<MutableList<PositionedWord>>()
  for ((i, line) in lines.withIndex()) {
    if (i == 0 || isBlockBreak(lines[i - 1], line)) blocks.add(line.toMutableList()) else blocks.last().addAll(line)
  }
  return blocks.joinToString("\n") { block -> joinSelected(block.map { it.text }) }.trim()
}

/** Whether a word reads horizontally (angle near 0/π) rather than vertically (near π/2). */
private fun isHorizontal(angleRadians: Float): Boolean {
  val pi = PI.toFloat()
  val a = ((angleRadians % pi) + pi) % pi
  return a < pi / 4f || a > 3f * pi / 4f
}

/** Index of the nearest word's transformed centre to `point`, optionally restricted to a direction. */
private fun nearestWord(
  words: List<PositionedWord>,
  point: Offset,
  t: FitTransform,
  horizontal: Boolean?,
): Int? {
  var best = -1
  var bestDist = Float.MAX_VALUE
  words.forEachIndexed { i, w ->
    if (horizontal != null && isHorizontal(w.bounds.angleRadians) != horizontal) return@forEachIndexed
    val dx = t.mapX(w.bounds.cx) - point.x
    val dy = t.mapY(w.bounds.cy) - point.y
    val d = dx * dx + dy * dy
    if (d < bestDist) {
      bestDist = d
      best = i
    }
  }
  return best.takeIf { it >= 0 }
}

/** Words in `start..end`, keeping only the start word's writing direction. */
private fun selectedWords(
  words: List<PositionedWord>,
  start: Int,
  end: Int,
): List<PositionedWord> {
  val horizontal = isHorizontal(words[start].bounds.angleRadians)
  return (start..end).filter { isHorizontal(words[it].bounds.angleRadians) == horizontal }.map { words[it] }
}

/** Merge consecutive words on one line into a single oriented pill spanning them. */
private fun mergeAlongLine(words: List<PositionedWord>): OrientedRect {
  val a = words[0].bounds.angleRadians
  val cosA = cos(a)
  val sinA = sin(a)
  var uMin = Float.MAX_VALUE
  var uMax = -Float.MAX_VALUE
  var height = 0f
  for (w in words) {
    val u = w.bounds.cx * cosA + w.bounds.cy * sinA
    if (u - w.bounds.width / 2f < uMin) uMin = u - w.bounds.width / 2f
    if (u + w.bounds.width / 2f > uMax) uMax = u + w.bounds.width / 2f
    if (w.bounds.height > height) height = w.bounds.height
  }
  val uMid = (uMin + uMax) / 2f
  val ref = words[0].bounds
  val d = uMid - (ref.cx * cosA + ref.cy * sinA)
  return OrientedRect(
    cx = ref.cx + d * cosA,
    cy = ref.cy + d * sinA,
    width = uMax - uMin,
    height = height,
    angleRadians = a,
  )
}

/** Bottom corner at the leading (`leading=true`) or trailing end of a word, in image coords. */
private fun handlePoint(
  rect: OrientedRect,
  leading: Boolean,
): Offset {
  val c = cos(rect.angleRadians)
  val s = sin(rect.angleRadians)
  val sign = if (leading) -1f else 1f
  val hw = sign * rect.width / 2f
  val hh = rect.height / 2f
  // reading axis (c, s); perpendicular-down (image y points down) is (-s, c).
  return Offset(rect.cx + hw * c - hh * s, rect.cy + hw * s + hh * c)
}

private fun copyToClipboard(
  context: Context,
  text: String,
) {
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  clipboard.setPrimaryClip(ClipData.newPlainText("Selection", text))
}

private const val MENU_COPY = 1
private const val MENU_SHARE = 2
private const val MENU_SEARCH = 3

/**
 * A floating text-selection action bar (Copy / Share / Web search) over the image, mirroring the
 * system text toolbar. `textProvider`/`rectProvider` are read live so the bar follows the
 * selection. Returns a callback; the caller drives `startActionMode`.
 */
private class SelectionActionModeCallback(
  private val context: Context,
  private val textProvider: () -> String,
  private val rectProvider: () -> android.graphics.Rect,
  private val onFinished: () -> Unit,
) : ActionMode.Callback2() {
  override fun onCreateActionMode(
    mode: ActionMode,
    menu: Menu,
  ): Boolean {
    menu.add(0, MENU_COPY, 0, android.R.string.copy)
    menu.add(0, MENU_SHARE, 1, "Share")
    menu.add(0, MENU_SEARCH, 2, "Web search")
    return true
  }

  override fun onPrepareActionMode(
    mode: ActionMode,
    menu: Menu,
  ): Boolean = false

  override fun onActionItemClicked(
    mode: ActionMode,
    item: MenuItem,
  ): Boolean {
    val text = textProvider()
    when (item.itemId) {
      MENU_COPY -> copyToClipboard(context, text)
      MENU_SHARE -> {
        val intent =
          Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
          }
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      }
      MENU_SEARCH -> {
        val intent =
          Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
        runCatching { context.startActivity(intent) }
      }
      else -> return false
    }
    mode.finish()
    return true
  }

  override fun onDestroyActionMode(mode: ActionMode) {
    onFinished()
  }

  override fun onGetContentRect(
    mode: ActionMode,
    view: View,
    outRect: android.graphics.Rect,
  ) {
    outRect.set(rectProvider())
  }
}

private enum class DragMode { NEW, START, END }

/**
 * Persistent Lens-style word selection over the displayed image. A drag selects the contiguous
 * reading-order run of words (same writing direction as where it started); the selection stays
 * after release with draggable end handles, and a floating action bar (Copy / Share / Web search)
 * tracks it. Tapping the image clears the selection. Nothing is auto-copied.
 *
 * `words` are in image-pixel space; mapped with the image's ContentScale.Fit transform, so this
 * stays aligned inside the image's transformed (zoom/pan) container.
 */
@Composable
fun WordSelectionOverlay(
  words: List<PositionedWord>,
  imageWidth: Int,
  imageHeight: Int,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val view = LocalView.current
  val handlePainter = painterResource(R.drawable.selection_handle)
  // Loupe under the dragging finger so the text/handle isn't obscured. Native Magnifier is API
  // 28+; older devices simply get no loupe.
  val magnifier = remember(view) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Magnifier(view) else null }
  DisposableEffect(magnifier) {
    onDispose { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) magnifier?.dismiss() }
  }

  var selStart by remember(words) { mutableStateOf<Int?>(null) }
  var selEnd by remember(words) { mutableStateOf<Int?>(null) }
  var dragging by remember { mutableStateOf(false) }
  var contentRect by remember { mutableStateOf(android.graphics.Rect()) }
  // The Canvas's top-left within the root view; the action bar's content rect must be in
  // root-view coords, but selection geometry is computed in Canvas-local coords.
  var canvasOrigin by remember { mutableStateOf(Offset.Zero) }

  val hasSelection = selStart != null && selEnd != null
  val selectedText =
    rememberUpdatedState(
      if (hasSelection) buildSelectionText(selectedWords(words, selStart!!, selEnd!!)) else "",
    )
  val rectState = rememberUpdatedState(contentRect)

  // Drive the floating action bar: present while there's a selection and no active drag.
  var actionMode by remember { mutableStateOf<ActionMode?>(null) }
  LaunchedEffect(hasSelection, dragging) {
    if (hasSelection && !dragging) {
      if (actionMode == null) {
        actionMode =
          view.startActionMode(
            SelectionActionModeCallback(
              context = context,
              textProvider = { selectedText.value },
              rectProvider = { rectState.value },
              onFinished = { actionMode = null },
            ),
            ActionMode.TYPE_FLOATING,
          )
      } else {
        actionMode?.invalidateContentRect()
      }
    } else {
      actionMode?.finish()
      actionMode = null
    }
  }
  DisposableEffect(Unit) {
    onDispose { actionMode?.finish() }
  }

  Canvas(
    modifier =
      modifier
        .onGloballyPositioned { canvasOrigin = it.positionInRoot() }
        .pointerInput(words, imageWidth, imageHeight) {
          val slop = viewConfiguration.touchSlop
          val handleRadius = 28.dp.toPx()

          fun transform() = fitTransform(size.width.toFloat(), size.height.toFloat(), imageWidth, imageHeight)

          fun recomputeRect() {
            val s = selStart ?: return
            val e = selEnd ?: return
            val t = transform()
            var l = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var r = -Float.MAX_VALUE
            var b = -Float.MAX_VALUE
            for (w in selectedWords(words, s, e)) {
              val topY = t.mapY(w.bounds.cy) - (w.bounds.height / 2f) * t.scale
              top = min(top, topY)
              for (leading in listOf(true, false)) {
                val p = handlePoint(w.bounds, leading)
                l = min(l, t.mapX(p.x))
                r = max(r, t.mapX(p.x))
                b = max(b, t.mapY(p.y))
              }
            }
            contentRect =
              android.graphics.Rect(
                (l + canvasOrigin.x).toInt(),
                (top + canvasOrigin.y).toInt(),
                (r + canvasOrigin.x).toInt(),
                (b + canvasOrigin.y).toInt(),
              )
          }

          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val t = transform()
            val s0 = selStart
            val e0 = selEnd
            val mode =
              if (s0 != null && e0 != null) {
                val startP = handlePoint(words[s0].bounds, leading = true)
                val endP = handlePoint(words[e0].bounds, leading = false)
                val startView = Offset(t.mapX(startP.x), t.mapY(startP.y))
                val endView = Offset(t.mapX(endP.x), t.mapY(endP.y))
                when {
                  (down.position - startView).getDistance() < handleRadius -> DragMode.START
                  (down.position - endView).getDistance() < handleRadius -> DragMode.END
                  else -> DragMode.NEW
                }
              } else {
                DragMode.NEW
              }
            val newAnchor = if (mode == DragMode.NEW) nearestWord(words, down.position, t, null) else null

            var moved = false
            while (true) {
              val event = awaitPointerEvent()
              val change = event.changes.firstOrNull { it.id == down.id } ?: break
              if (!change.pressed) break
              val d = change.position - down.position
              if (!moved && d.getDistance() > slop) moved = true
              if (moved) {
                change.consume()
                dragging = true
                when (mode) {
                  DragMode.START -> {
                    val dir = isHorizontal(words[e0!!].bounds.angleRadians)
                    nearestWord(words, change.position, transform(), dir)?.let { nw ->
                      selStart = min(nw, e0)
                      selEnd = max(nw, e0)
                    }
                  }
                  DragMode.END -> {
                    val dir = isHorizontal(words[s0!!].bounds.angleRadians)
                    nearestWord(words, change.position, transform(), dir)?.let { nw ->
                      selStart = min(s0, nw)
                      selEnd = max(s0, nw)
                    }
                  }
                  DragMode.NEW -> {
                    val anchor = newAnchor
                    if (anchor != null) {
                      val dir = isHorizontal(words[anchor].bounds.angleRadians)
                      nearestWord(words, change.position, transform(), dir)?.let { nw ->
                        selStart = min(anchor, nw)
                        selEnd = max(anchor, nw)
                      }
                    }
                  }
                }
                recomputeRect()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                  magnifier?.show(canvasOrigin.x + change.position.x, canvasOrigin.y + change.position.y)
                }
              }
            }

            dragging = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) magnifier?.dismiss()
            if (!moved && mode == DragMode.NEW) {
              selStart = null
              selEnd = null
            }
          }
        },
  ) {
    val s = selStart ?: return@Canvas
    val e = selEnd ?: return@Canvas
    val t = fitTransform(size.width, size.height, imageWidth, imageHeight)
    // One merged pill per line. Cap each pill's height to the cross-axis gap to its nearest
    // neighbour so tightly-spaced lines meet halfway instead of overlapping — but only count
    // neighbours that overlap along the reading axis (i.e. the same column), so a parallel column
    // sitting at the same height doesn't shrink it.
    val pills =
      selectedWords(words, s, e)
        .groupBy { it.lineIndex }
        .values
        .map { mergeAlongLine(it) }
    pills.forEachIndexed { i, pill ->
      val cross = crossOf(pill)
      val read0 = readOf(pill) - pill.width / 2f
      val read1 = readOf(pill) + pill.width / 2f
      var gap = Float.MAX_VALUE
      pills.forEachIndexed { j, other ->
        if (j != i) {
          val o0 = readOf(other) - other.width / 2f
          val o1 = readOf(other) + other.width / 2f
          if (read0 < o1 && o0 < read1) gap = min(gap, abs(cross - crossOf(other)))
        }
      }
      drawOrientedPill(pill.copy(height = min(pill.height, gap)), t, Color(0x553B82F6))
    }
    // End handles: pin markers whose tip sits at the line bottom, hanging below it. The opening
    // pin points up-right (toward the selection), the closing pin up-left. The drawable's tip is
    // at its bottom-centre, so anchor and rotate about that.
    val handleSize = 26.dp.toPx()

    fun drawHandle(
      p: Offset,
      degrees: Float,
    ) {
      withTransform({
        translate(t.mapX(p.x) - handleSize / 2f, t.mapY(p.y) - handleSize)
        rotate(degrees, pivot = Offset(handleSize / 2f, handleSize))
      }) {
        with(handlePainter) { draw(Size(handleSize, handleSize)) }
      }
    }
    drawHandle(handlePoint(words[s].bounds, leading = true), -135f)
    drawHandle(handlePoint(words[e].bounds, leading = false), 135f)
  }
}
