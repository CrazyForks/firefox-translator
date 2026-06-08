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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.davidv.translator.MainActivity
import dev.davidv.translator.TestUtils
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
                TestUtils.setupLanguagesForApp()
                try {
                  base.evaluate()
                } finally {
                  TestUtils.cleanupLanguagesForApp()
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

    composeTestRule.onAllNodesWithContentDescription("Settings").onFirst().performClick()
    composeTestRule.waitForIdle()
    captureRoute("settings")

    composeTestRule.onAllNodesWithText("Manage").onFirst().performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Bulgarian").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    captureRoute("language_manager")
    goBack()

    composeTestRule.onNodeWithText("How to use").performScrollTo().performClick()
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

  private data class Frame(val ops: List<DrawOp>, val width: Int)

  private fun drawOnce(): Frame {
    val activity = composeTestRule.activity
    var ops: List<DrawOp> = emptyList()
    var width = 0
    composeTestRule.runOnUiThread {
      val view = activity.window.decorView
      val w = view.width
      val h = view.height
      check(w > 0 && h > 0) { "decorView not laid out (${w}x$h)" }
      val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      val canvas = RecordingCanvas(bmp)
      view.draw(canvas)
      ops = canvas.ops.toList()
      width = w
    }
    return Frame(ops, width)
  }

  private fun captureRoute(route: String) {
    composeTestRule.waitForIdle()
    val frames = mutableListOf<CaptureFrame>()
    var width = 0
    val scrollId = scrollNode()?.id
    if (scrollId != null) scrollToTop()
    waitForStable()

    var prevSignature: String? = null
    var guard = 0
    while (guard++ < 60) {
      val frame = drawOnce()
      width = frame.width
      val signature = textSignature(frame.ops)
      if (signature == prevSignature) break
      frames += CaptureFrame(attachImageDescriptions(frame.ops))
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
    val texts = stitched.ops.count { it is DrawOp.TextRun }
    Log.i("UiExport", "wrote $route.svg (${frames.size} frames, ${stitched.ops.size} ops, $texts text runs)")
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
