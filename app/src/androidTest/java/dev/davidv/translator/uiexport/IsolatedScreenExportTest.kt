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

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.davidv.translator.MigrationBlockingScreen
import dev.davidv.translator.MigrationUiState
import dev.davidv.translator.UiExportRecorder
import dev.davidv.translator.ui.components.DictionaryBottomSheetPreview
import dev.davidv.translator.ui.screens.NoLanguagesScreenPreview
import dev.davidv.translator.ui.theme.TranslatorTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File

/**
 * Exports state-gated screens that the navigation-driven [UiExportInstrumentedTest] can't reach
 * (no-languages, migration) plus overlay content that's awkward to drive live (dictionary), by
 * rendering each composable in isolation with mock/preview state and running the same capture
 * pipeline. Each `@Test` gets a fresh host activity, so `setContent` is only called once per screen.
 */
@RunWith(AndroidJUnit4::class)
class IsolatedScreenExportTest {
  private val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  // Screenshots are the English source for Weblate, so force the per-app locale to en before the
  // host activity launches (it's created on the first setContent). No language files are touched —
  // these screens render mock/preview state. Restored afterward.
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
                val ctx = InstrumentationRegistry.getInstrumentation().targetContext
                val localeManager =
                  if (android.os.Build.VERSION.SDK_INT >= 33) {
                    ctx.getSystemService(android.app.LocaleManager::class.java)
                  } else {
                    null
                  }
                val previousLocales = localeManager?.applicationLocales
                localeManager?.applicationLocales = android.os.LocaleList.forLanguageTags("en-US")
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

  @Test
  fun exportNoLanguages() = export("nolang") { NoLanguagesScreenPreview() }

  @Test
  fun exportMigration() =
    export("migration", allowScroll = false) {
      TranslatorTheme {
        MigrationBlockingScreen(
          state = MigrationUiState.AwaitTtsDecision(ttsCount = 2, savedBytes = 120_000_000L),
          onConvert = {},
          onDeleteAll = {},
        )
      }
    }

  @Test
  fun exportDictionary() = export("dictionary") { DictionaryBottomSheetPreview() }

  private fun export(
    route: String,
    allowScroll: Boolean = true,
    content: @Composable () -> Unit,
  ) {
    // The host ComponentActivity has no attachBaseContext hook, so inject the recording resources via
    // LocalContext instead, so stringResource lookups inside these screens are recorded the same way.
    composeTestRule.setContent {
      val base = LocalContext.current
      CompositionLocalProvider(LocalContext provides remember { UiExportRecorder.wrap(base) }) {
        content()
      }
    }
    composeTestRule.waitForIdle()
    UiExportCapture(composeTestRule, composeTestRule.activity, File(outputDir(), "ui-export"))
      .captureRoute(route, allowScroll = allowScroll)
  }

  private fun outputDir(): File {
    val arg = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
    return if (arg != null) File(arg) else File(composeTestRule.activity.getExternalFilesDir(null), "fallback")
  }
}
