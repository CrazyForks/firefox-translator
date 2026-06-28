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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.davidv.translator.MainActivity
import dev.davidv.translator.R
import dev.davidv.translator.TestUtils
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File

@RunWith(AndroidJUnit4::class)
class UiExportInstrumentedTest {
  private val composeTestRule = createAndroidComposeRule<MainActivity>()

  // The ViewModel's LanguageStateManager scans for languages at first launch and survives Activity
  // recreation, so the files must exist before the compose rule launches the activity. This outer
  // rule installs them first, then removes them after, so a normally-launched app keeps no test state.
  @get:Rule
  val ruleChain: RuleChain =
    RuleChain
      .outerRule(
        object : TestRule {
          override fun apply(
            base: Statement,
            description: Description,
          ): Statement =
            object : Statement() {
              override fun evaluate() {
                // Screenshots are the English source for Weblate, and the texts.json must match the
                // English string values for exact key association, so force the app's per-app locale
                // to en regardless of the device language. Restored afterward.
                val ctx = InstrumentationRegistry.getInstrumentation().targetContext
                val localeManager =
                  if (android.os.Build.VERSION.SDK_INT >= 33) {
                    ctx.getSystemService(android.app.LocaleManager::class.java)
                  } else {
                    null
                  }
                val previousLocales = localeManager?.applicationLocales
                localeManager?.applicationLocales = android.os.LocaleList.forLanguageTags("en-US")
                TestUtils.setupLanguagesForApp()
                try {
                  base.evaluate()
                } finally {
                  TestUtils.cleanupLanguagesForApp()
                  if (previousLocales != null) localeManager.applicationLocales = previousLocales
                }
              }
            }
        },
      )
      .around(composeTestRule)

  @Test
  fun exportScreens() {
    awaitAnyContentDescription("Main screen", "Settings")
    captureRoute("main")

    // FAB image-source sheet: a Material3 ModalBottomSheet (separate window) captured via the
    // all-windows draw + draw-time overlay labeling. Best-effort: never break the rest of the export.
    runCatching {
      composeTestRule.onAllNodesWithContentDescription("Translate image or file").onFirst().performClick()
      composeTestRule.waitForIdle()
      waitForStable()
      captureRoute("image_source", allowScroll = false, overlaySection = "Image source")
      dismissOverlay()
    }.onFailure { Log.w("UiExport", "image_source capture failed", it) }

    composeTestRule.onAllNodesWithContentDescription("Settings").onFirst().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText(str(R.string.settings_advanced)).performScrollTo().performClick()
    composeTestRule.waitForIdle()
    captureRoute("settings")
    captureDropdownOptions("settings")

    composeTestRule.onAllNodesWithText(str(R.string.common_manage)).onFirst().performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Bulgarian").performScrollTo().performClick()
    composeTestRule.waitForIdle()

    // Voice-download dialog: a BasicAlertDialog (separate window) — drawing all root views captures
    // it too. Done BEFORE the scrolling capture below, while the just-expanded row (and its tagged
    // trigger) is still composed; capturing language_manager scrolls the LazyColumn and would
    // dispose it. Best-effort; trigger is tagged export-trigger:voice on the TTS feature-row action.
    runCatching {
      composeTestRule
        .onAllNodes(SemanticsMatcher("voice trigger") { textTag(it).startsWith("export-trigger:voice") })
        .onFirst()
        .performScrollTo()
        .performClick()
      composeTestRule.waitForIdle()
      waitForStable()
      captureRoute("voice_picker", allowScroll = false, overlaySection = "Voice picker")
      dismissOverlay()
    }.onFailure { Log.w("UiExport", "voice_picker capture failed", it) }

    captureRoute("language_manager")
    goBack()

    composeTestRule.onNodeWithText(str(R.string.howto_title)).performScrollTo().performClick()
    composeTestRule.waitForIdle()
    captureRoute("how_to_use")
    goBack()
  }

  private fun awaitAnyContentDescription(vararg descriptions: String) {
    // High ceiling only for the initial cold start + reactive language load; waitUntil returns as
    // soon as a screen appears, so this doesn't slow normal runs.
    composeTestRule.waitUntil(timeoutMillis = 20000) {
      descriptions.any { description ->
        try {
          composeTestRule.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()
        } catch (_: Exception) {
          false
        }
      }
    }
  }

  private fun goBack() {
    composeTestRule.runOnUiThread {
      composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
    }
    composeTestRule.waitForIdle()
  }

  /**
   * Dismiss a separate-window overlay (ModalBottomSheet / Dialog) with a real BACK key, which the
   * window manager routes to the focused overlay window. The activity's onBackPressedDispatcher
   * doesn't see the overlay's back handler (different window), so goBack() would finish the activity.
   */
  private fun dismissOverlay() {
    UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
    composeTestRule.waitForIdle()
  }

  private fun str(id: Int): String = composeTestRule.activity.getString(id)

  private data class Frame(val ops: List<DrawOp>, val width: Int)

  private fun drawOnce(overlaySection: String? = null): Frame {
    val activity = composeTestRule.activity
    var ops: List<DrawOp> = emptyList()
    var width = 0
    composeTestRule.runOnUiThread {
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
      listOf(composeTestRule.activity.window.decorView)
    }

  private fun captureRoute(
    route: String,
    allowScroll: Boolean = true,
    overlaySection: String? = null,
  ) {
    composeTestRule.waitForIdle()
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
      composeTestRule.waitForIdle()
    }

    val stitched = ScreenStitcher.stitch(frames)
    val svg = SvgEmitter.emit(route, stitched.ops, width, stitched.height)
    val outDir = File(outputDir(), "ui-export")
    outDir.mkdirs()
    File(outDir, "$route.svg").writeText(svg)
    writeSectionTexts(outDir, route, stitched.ops)
    val texts = stitched.ops.count { it is DrawOp.TextRun }
    Log.i("UiExport", "wrote $route.svg (${frames.size} frames, ${stitched.ops.size} ops, $texts text runs)")
  }

  /** `<route>.texts.json` = { section -> distinct ordered visible strings }, for exact key matching. */
  private fun writeSectionTexts(
    outDir: File,
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
      composeTestRule
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
   * For each dropdown opted in with `Modifier.testTag("export-options:<Name>")`, open it, read its
   * option texts from the popup's semantics, and write a small per-dropdown options panel. The popup
   * is a separate window we can't draw, so we synthesize the panel from text only.
   */
  private fun captureDropdownOptions(route: String) {
    val tags =
      composeTestRule
        .onAllNodes(SemanticsMatcher("export-options tag") { node -> textTag(node).startsWith("export-options:") })
        .fetchSemanticsNodes()
        .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
        .distinct()

    for (tag in tags) {
      val name = tag.substringAfter("export-options:")
      composeTestRule.onNodeWithTag(tag).performScrollTo()
      composeTestRule.waitForIdle()

      val selected = nodeText(composeTestRule.onNodeWithTag(tag).fetchSemanticsNode())
      val before = visibleTexts()
      composeTestRule.onNodeWithTag(tag).performClick()
      composeTestRule.waitForIdle()

      val newOptions =
        composeTestRule
          .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
          .fetchSemanticsNodes()
          .mapNotNull { node -> nodeText(node)?.takeIf { it.isNotBlank() && it !in before }?.let { node to it } }
          .sortedBy { it.first.boundsInRoot.top }
          .map { it.second }
      val options = (listOfNotNull(selected) + newOptions).distinct()

      composeTestRule.onNodeWithTag(tag).performClick()
      composeTestRule.waitForIdle()

      val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
      val svg = SvgEmitter.emitOptionsPanel(name, "$route:$slug", options)
      File(File(outputDir(), "ui-export"), "$route.$slug.svg").writeText(svg)
      Log.i("UiExport", "wrote $route.$slug.svg (${options.size} options: $options)")
    }
  }

  private fun textTag(node: SemanticsNode): String = node.config.getOrNull(SemanticsProperties.TestTag) ?: ""

  private fun nodeText(node: SemanticsNode): String? =
    node.config.getOrNull(SemanticsProperties.EditableText)?.text
      ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

  private fun visibleTexts(): Set<String> =
    composeTestRule
      .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
      .fetchSemanticsNodes()
      .mapNotNull { nodeText(it) }
      .toSet()

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
      composeTestRule.waitForIdle()
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
      composeTestRule
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
      composeTestRule.runOnUiThread { action.invoke(0) }
      composeTestRule.waitForIdle()
      return
    }
    val scrollBy = node.config.getOrNull(SemanticsActions.ScrollBy)?.action ?: return
    repeat(5) {
      val value = currentScroll() ?: 0f
      if (value <= 1f) return
      composeTestRule.runOnUiThread { scrollBy.invoke(0f, -value) }
      composeTestRule.waitForIdle()
    }
  }

  private fun currentScroll(): Float? = scrollNode()?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke()

  private fun scrollNode(): SemanticsNode? =
    composeTestRule
      .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))
      .fetchSemanticsNodes()
      .filter { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
      .maxByOrNull { it.boundsInRoot.height }

  private fun swipeUp(nodeId: Int) {
    composeTestRule
      .onNode(SemanticsMatcher("scroll node $nodeId") { it.id == nodeId })
      .performTouchInput {
        // Small, slow swipe: keeps fling negligible so consecutive frames overlap enough for the
        // stitcher to recover the scroll offset by matching shared labels.
        swipeUp(startY = height * 0.72f, endY = height * 0.40f, durationMillis = 600)
      }
  }

  /**
   * AGP copies anything under the runner's additionalTestOutputDir into
   * build/outputs/.../connected_android_test_additional_output before uninstalling the app, so
   * unlike the app's files dir it survives the post-run cleanup.
   */
  private fun outputDir(): File {
    val arg = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
    return if (arg != null) File(arg) else File(composeTestRule.activity.getExternalFilesDir(null), "fallback")
  }
}
