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

import android.util.Log
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.davidv.translator.MainActivity
import dev.davidv.translator.R
import dev.davidv.translator.TestUtils
import dev.davidv.translator.UiExportRecorder
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
  // rule installs them first. It does NOT remove them afterward: applicationId has no debug suffix,
  // so the data dir is the real app's storage, and a recursive cleanup would wipe the user's other
  // downloaded models. The seeded es<->en models are the catalog's current build, so leaving them is
  // harmless (setup just overwrites es/en on the next run).
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
                // Record R.string lookups before MainActivity is created (its attachBaseContext reads
                // this), so every label can be stamped with the exact string id it resolved from.
                UiExportRecorder.reset()
                UiExportRecorder.active = true
                try {
                  base.evaluate()
                } finally {
                  UiExportRecorder.active = false
                  if (previousLocales != null) localeManager.applicationLocales = previousLocales
                }
              }
            }
        },
      )
      .around(composeTestRule)

  private val capture by lazy {
    UiExportCapture(composeTestRule, composeTestRule.activity, File(outputDir(), "ui-export"))
  }

  @Test
  fun exportScreens() {
    awaitAnyContentDescription("Main screen", "Settings")

    // FAB image-source sheet: a Material3 ModalBottomSheet (separate window) captured via the
    // all-windows draw + draw-time overlay labeling. Best-effort: never break the rest of the export.
    runCatching {
      composeTestRule.onAllNodesWithContentDescription("Translate image or file").onFirst().performClick()
      composeTestRule.waitForIdle()
      capture.captureRoute("image_source", allowScroll = false, overlaySection = "Image source")
      dismissOverlay()
    }.onFailure { Log.w("UiExport", "image_source capture failed", it) }

    composeTestRule.onAllNodesWithContentDescription("Settings").onFirst().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText(str(R.string.settings_advanced)).performScrollTo().performClick()
    composeTestRule.waitForIdle()
    capture.captureRoute("settings")
    captureDropdownOptions("settings")

    composeTestRule.onAllNodesWithText(str(R.string.common_manage)).onFirst().performScrollTo().performClick()
    composeTestRule.waitForIdle()
    // Albanian is near the top of the list (so no long scroll) and ships two voices, so the picker
    // shows more than one row.
    composeTestRule.onNodeWithText("Albanian").performScrollTo().performClick()
    composeTestRule.waitForIdle()

    // Voice-download dialog: a BasicAlertDialog (separate window) — drawing all root views captures
    // it too. Captured while the just-expanded Albanian row (and its tagged trigger) is still
    // composed. Best-effort; trigger is tagged export-trigger:voice on the TTS feature-row action.
    runCatching {
      composeTestRule
        .onAllNodes(SemanticsMatcher("voice trigger") { textTag(it).startsWith("export-trigger:voice") })
        .onFirst()
        .performScrollTo()
        .performClick()
      composeTestRule.waitForIdle()
      capture.captureRoute("voice_picker", allowScroll = false, overlaySection = "Voice picker")
      dismissOverlay()
    }.onFailure { Log.w("UiExport", "voice_picker capture failed", it) }

    goBack()

    composeTestRule.onNodeWithText(str(R.string.howto_title)).performScrollTo().performClick()
    composeTestRule.waitForIdle()
    capture.captureRoute("how_to_use")
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
      // The tag suffix is the dropdown label's R.string name, so the synthesized panel title is the
      // real (translatable) label and carries its exact id; the slug drops the settings_ prefix to
      // keep the per-dropdown filename (e.g. settings.background_mode.svg) stable.
      val resName = tag.substringAfter("export-options:")
      val resId =
        composeTestRule.activity.resources.getIdentifier(
          resName,
          "string",
          composeTestRule.activity.packageName,
        )
      val title = if (resId != 0) str(resId) else resName
      val titleId = if (resId != 0) resName else null
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

      val slug = resName.removePrefix("settings_").lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
      val svg = SvgEmitter.emitOptionsPanel(title, titleId, "$route:$slug", options)
      File(File(outputDir(), "ui-export"), "$route.$slug.svg").writeText(svg)
      Log.i("UiExport", "wrote $route.$slug.svg (${options.size} options: $options)")
    }
  }

  private fun visibleTexts(): Set<String> =
    composeTestRule
      .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
      .fetchSemanticsNodes()
      .mapNotNull { nodeText(it) }
      .toSet()

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
