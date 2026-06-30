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
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import dev.davidv.translator.UiExportRecorder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal fun textTag(node: SemanticsNode): String = node.config.getOrNull(SemanticsProperties.TestTag) ?: ""

internal fun nodeText(node: SemanticsNode): String? =
  node.config.getOrNull(SemanticsProperties.EditableText)?.text
    ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

/**
 * The screen-capture pipeline shared by the navigation-driven export (UiExportInstrumentedTest) and
 * the isolated-composable export (IsolatedScreenExportTest): draw every attached window into a
 * RecordingCanvas, label ops with their enclosing section / image description, scroll-and-stitch
 * tall content, then emit `<route>.svg` + `<route>.texts.json` into [outDir]. Works against any
 * ComponentActivity, so the caller can either drive the real app or `setContent` a single screen.
 */
class UiExportCapture(
  private val rule: ComposeTestRule,
  private val activity: ComponentActivity,
  private val outDir: File,
) {
  private data class Frame(val ops: List<DrawOp>, val width: Int)

  fun captureRoute(
    route: String,
    allowScroll: Boolean = true,
    overlaySection: String? = null,
  ) {
    rule.waitForIdle()
    val frames = mutableListOf<CaptureFrame>()
    var width = 0
    val scrollId = if (allowScroll) scrollNode()?.id else null
    if (scrollId != null) scrollToTop()
    waitForStable()

    var prevSignature: String? = null
    var guard = 0
    while (guard++ < 60) {
      val frame = drawOnce(overlaySection)
      width = frame.width
      val signature = textSignature(frame.ops)
      if (signature == prevSignature) break
      // Overlay routes (dialog/sheet): ops are labeled at draw time, and the underlying window stays
      // un-sectioned (rendered as dimmed context but not cropped); skip bounds-based labeling.
      val withMeta =
        if (overlaySection != null) {
          attachImageDescriptions(frame.ops)
        } else {
          attachSections(attachImageDescriptions(frame.ops))
        }
      frames += CaptureFrame(withMeta)
      prevSignature = signature
      if (scrollId == null) break
      swipeUp(scrollId)
      rule.waitForIdle()
    }

    val stitched = ScreenStitcher.stitch(frames)
    val svg = SvgEmitter.emit(route, stitched.ops, width, stitched.height) { UiExportRecorder.idFor(it) }
    outDir.mkdirs()
    File(outDir, "$route.svg").writeText(svg)
    writeSectionTexts(route, stitched.ops)
    val texts = stitched.ops.count { it is DrawOp.TextRun }
    Log.i("UiExport", "wrote $route.svg (${frames.size} frames, ${stitched.ops.size} ops, $texts text runs)")
  }

  private fun drawOnce(overlaySection: String? = null): Frame {
    var ops: List<DrawOp> = emptyList()
    var width = 0
    rule.runOnUiThread {
      val decor = activity.window.decorView
      val w = decor.width
      val h = decor.height
      check(w > 0 && h > 0) { "decorView not laid out (${w}x$h)" }
      val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      val canvas = RecordingCanvas(bmp)
      val base = IntArray(2).also { decor.getLocationOnScreen(it) }
      // Draw every attached window (activity + any dialog/popup), composited at its screen offset,
      // in attach order (later = on top). Separate-window surfaces (BasicAlertDialog, ModalBottomSheet)
      // aren't in decorView, so this is what makes dialogs/sheets appear in the SVG. Their semantics
      // bounds are window-relative (useless for labeling against device-coord ops), so we tag the ops
      // drawn for non-decor windows with `overlaySection` here, at draw time.
      for (view in rootViews()) {
        if (view === decor) {
          view.draw(canvas)
          continue
        }
        if (!view.isShown || view.width <= 0 || view.height <= 0) continue
        val loc = IntArray(2).also { view.getLocationOnScreen(it) }
        val save = canvas.save()
        canvas.translate((loc[0] - base[0]).toFloat(), (loc[1] - base[1]).toFloat())
        val from = canvas.ops.size
        view.draw(canvas)
        canvas.restoreToCount(save)
        if (overlaySection != null) {
          for (i in from until canvas.ops.size) {
            val op = canvas.ops[i]
            // Don't tag a full-bleed scrim rect (ModalBottomSheet draws one): it would blow the
            // section's crop bbox up to the whole screen. Leaving it un-sectioned still renders it
            // (dimmed backdrop) but keeps the crop tight to the actual sheet/dialog content.
            val isScrim =
              op is DrawOp.Rect &&
                (op.right - op.left) >= w * 0.9f && (op.bottom - op.top) >= h * 0.9f
            if (!isScrim) canvas.ops[i] = op.withSection(overlaySection)
          }
        }
      }
      ops = canvas.ops.toList()
      width = w
    }
    return Frame(ops, width)
  }

  /** All attached root views (via WindowManagerGlobal); falls back to the activity decorView. */
  private fun rootViews(): List<View> =
    try {
      val cls = Class.forName("android.view.WindowManagerGlobal")
      val instance = cls.getMethod("getInstance").invoke(null)
      val field = cls.getDeclaredField("mViews").apply { isAccessible = true }
      @Suppress("UNCHECKED_CAST")
      (field.get(instance) as List<View>).toList()
    } catch (e: Exception) {
      Log.w("UiExport", "WindowManagerGlobal.mViews unavailable; drawing decorView only", e)
      listOf(activity.window.decorView)
    }

  /** `<route>.texts.json` = { section -> distinct ordered visible strings }, for exact key matching. */
  private fun writeSectionTexts(
    route: String,
    ops: List<DrawOp>,
  ) {
    val bySection = LinkedHashMap<String, MutableList<String>>()
    for (op in ops) {
      if (op !is DrawOp.TextRun) continue
      val section = op.section ?: continue
      val text = op.text.trim()
      if (text.isEmpty()) continue
      val list = bySection.getOrPut(section) { mutableListOf() }
      if (text !in list) list.add(text)
    }
    val json = JSONObject()
    for ((section, list) in bySection) json.put(section, JSONArray(list))
    File(outDir, "$route.texts.json").writeText(json.toString(2))
  }

  /** Label each op with the smallest enclosing `export-section:<Name>` node (live per-frame coords). */
  private fun attachSections(ops: List<DrawOp>): List<DrawOp> {
    val sections =
      rule
        .onAllNodes(SemanticsMatcher("export-section tag") { textTag(it).startsWith("export-section:") })
        .fetchSemanticsNodes()
        .map { it.boundsInRoot to textTag(it).substringAfter("export-section:") }
    if (sections.isEmpty()) return ops
    return ops.map { op ->
      val (px, py) = opAnchor(op)
      val name =
        sections
          .filter { (b, _) -> px >= b.left && px <= b.right && py >= b.top && py <= b.bottom }
          .minByOrNull { (b, _) -> b.width * b.height }
          ?.second
      if (name == null) op else op.withSection(name)
    }
  }

  private fun opAnchor(op: DrawOp): Pair<Float, Float> =
    when (op) {
      is DrawOp.TextRun -> (op.matrix[2] + op.x + op.widthPx / 2f) to (op.matrix[5] + op.baselineY)
      is DrawOp.Rect -> (op.matrix[2] + (op.left + op.right) / 2f) to (op.matrix[5] + (op.top + op.bottom) / 2f)
      is DrawOp.Line -> (op.matrix[2] + (op.startX + op.stopX) / 2f) to (op.matrix[5] + (op.startY + op.stopY) / 2f)
      is DrawOp.Path -> op.matrix[2] to op.matrix[5]
      is DrawOp.Image -> (op.matrix[2] + (op.left + op.right) / 2f) to (op.matrix[5] + (op.top + op.bottom) / 2f)
    }

  private fun DrawOp.withSection(name: String): DrawOp =
    when (this) {
      is DrawOp.TextRun -> copy(section = name)
      is DrawOp.Rect -> copy(section = name)
      is DrawOp.Line -> copy(section = name)
      is DrawOp.Path -> copy(section = name)
      is DrawOp.Image -> copy(section = name)
    }

  private fun textSignature(ops: List<DrawOp>): String =
    ops.filterIsInstance<DrawOp.TextRun>().joinToString("|") { "${it.text}@${it.baselineY.toInt()}" }

  /**
   * Block until the rendered draw ops stop changing between samples, so transient animations
   * (pull-to-refresh indicator, spinners, settling overscroll) aren't captured mid-flight.
   */
  private fun waitForStable() {
    var prev = ""
    var guard = 0
    while (guard++ < 12) {
      val signature = drawSignature(drawOnce().ops)
      if (signature == prev) return
      prev = signature
      rule.waitForIdle()
      Thread.sleep(120)
    }
  }

  private fun drawSignature(ops: List<DrawOp>): String {
    val sb = StringBuilder()
    for (op in ops) {
      when (op) {
        is DrawOp.TextRun -> sb.append("T").append(op.matrix[5].toInt())
        is DrawOp.Rect -> sb.append("R").append(op.matrix[2].toInt()).append(',').append(op.matrix[5].toInt())
        is DrawOp.Line -> sb.append("L").append(op.matrix[5].toInt())
        is DrawOp.Path -> sb.append("P").append(op.pathData.hashCode())
        is DrawOp.Image -> sb.append("I").append(op.matrix[5].toInt())
      }
    }
    return sb.toString()
  }

  /** Tag each captured image with the contentDescription of the smallest semantics node covering it. */
  private fun attachImageDescriptions(ops: List<DrawOp>): List<DrawOp> {
    val described =
      rule
        .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
        .fetchSemanticsNodes()
        .mapNotNull { node ->
          val desc = node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
          if (desc.isNullOrEmpty()) null else node.boundsInRoot to desc
        }
    if (described.isEmpty()) return ops
    return ops.map { op ->
      if (op !is DrawOp.Image) {
        op
      } else {
        val cx = op.matrix[2] + (op.left + op.right) / 2f
        val cy = op.matrix[5] + (op.top + op.bottom) / 2f
        val match =
          described
            .filter { (b, _) -> cx >= b.left && cx <= b.right && cy >= b.top && cy <= b.bottom }
            .minByOrNull { (b, _) -> b.width * b.height }
        if (match != null) op.copy(description = match.second) else op
      }
    }
  }

  /**
   * Reset a scrollable to the top, landing exactly at offset 0. Avoids both swiping and over-scrolling
   * (a large negative ScrollBy is consumed by PullToRefreshBox as a pull, showing its spinner).
   */
  private fun scrollToTop() {
    val node = scrollNode() ?: return
    node.config.getOrNull(SemanticsActions.ScrollToIndex)?.action?.let { action ->
      rule.runOnUiThread { action.invoke(0) }
      rule.waitForIdle()
      return
    }
    val scrollBy = node.config.getOrNull(SemanticsActions.ScrollBy)?.action ?: return
    repeat(5) {
      val value = currentScroll() ?: 0f
      if (value <= 1f) return
      rule.runOnUiThread { scrollBy.invoke(0f, -value) }
      rule.waitForIdle()
    }
  }

  private fun currentScroll(): Float? = scrollNode()?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke()

  private fun scrollNode(): SemanticsNode? =
    rule
      .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))
      .fetchSemanticsNodes()
      .filter { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
      .maxByOrNull { it.boundsInRoot.height }

  private fun swipeUp(nodeId: Int) {
    rule
      .onNode(SemanticsMatcher("scroll node $nodeId") { it.id == nodeId })
      .performTouchInput {
        // Small, slow swipe: keeps fling negligible so consecutive frames overlap enough for the
        // stitcher to recover the scroll offset by matching shared labels.
        swipeUp(startY = height * 0.72f, endY = height * 0.40f, durationMillis = 600)
      }
  }
}
