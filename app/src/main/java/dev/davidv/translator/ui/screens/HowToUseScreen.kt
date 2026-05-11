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

package dev.davidv.translator.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.davidv.translator.R
import dev.davidv.translator.ui.theme.TranslatorTheme

private data class Tip(
  val iconRes: Int,
  val title: String,
  val body: String,
)

private data class TipSection(
  val title: String,
  val tips: List<Tip>,
)

private val sections =
  listOf(
    TipSection(
      title = "From other apps",
      tips =
        listOf(
          Tip(
            iconRes = R.drawable.share,
            title = "Share to translate",
            body = "Share text, links, or images from any app to translate them.",
          ),
          Tip(
            iconRes = R.drawable.copy,
            title = "Translate without leaving other apps",
            body = "Select text anywhere, then tap \"Translate\" in the copy toolbar to see the translation in a compact popup.",
          ),
        ),
    ),
    TipSection(
      title = "Inside the app",
      tips =
        listOf(
          Tip(
            iconRes = R.drawable.draft,
            title = "Translate documents",
            body = "Open the Documents tab to translate EPUB, PDF, and TXT files.",
          ),
          Tip(
            iconRes = R.drawable.dictionary,
            title = "Dictionary lookup",
            body = "Long-press any word in a translation to see its definition. Data sourced from Wiktionary.",
          ),
          Tip(
            iconRes = R.drawable.volume_up,
            title = "Change voice or speed",
            body = "Long-press the play button to pick a different TTS voice or adjust playback speed.",
          ),
        ),
    ),
  )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HowToUseScreen(onBack: () -> Unit) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("How to use") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(
              painter = painterResource(id = R.drawable.arrow_back),
              contentDescription = "Back",
            )
          }
        },
      )
    },
  ) { paddingValues ->
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(paddingValues)
          .padding(horizontal = 16.dp)
          .verticalScroll(rememberScrollState()),
    ) {
      Spacer(modifier = Modifier.height(8.dp))

      sections.forEach { section ->
        Text(
          text = section.title,
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        )
        Card(
          modifier = Modifier.fillMaxWidth(),
          colors =
            CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            ),
        ) {
          Column(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            section.tips.forEach { tip -> TipRow(tip) }
          }
        }
        Spacer(modifier = Modifier.height(8.dp))
      }

      Spacer(modifier = Modifier.height(16.dp))
    }
  }
}

@Composable
private fun TipRow(tip: Tip) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Icon(
      painter = painterResource(id = tip.iconRes),
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(24.dp).padding(top = 2.dp),
    )
    Column(
      modifier = Modifier.padding(start = 16.dp),
    ) {
      Text(
        text = tip.title,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = tip.body,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
      )
    }
  }
}

@Preview(showBackground = true)
@Composable
fun HowToUseScreenPreview() {
  TranslatorTheme {
    HowToUseScreen(onBack = {})
  }
}

@Preview(
  showBackground = true,
  uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun HowToUseScreenDarkPreview() {
  TranslatorTheme {
    HowToUseScreen(onBack = {})
  }
}
