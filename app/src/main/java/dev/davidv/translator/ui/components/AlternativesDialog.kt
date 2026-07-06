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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.davidv.translator.WordAlternative
import dev.davidv.translator.WordAlternatives

private val WS = Regex("\\s+")

/**
 * A window of `[before]` words, the word at [boldIndex] in bold, and `[after]`
 * words — with ellipses when the sentence extends past the window. Only the
 * single swapped word is bolded; any downstream cascade is shown plain.
 */
private fun window(
  sentence: String,
  boldIndex: Int,
  before: Int = 3,
  after: Int = 3,
): AnnotatedString {
  val words = sentence.split(WS).filter { it.isNotBlank() }
  if (boldIndex !in words.indices) return AnnotatedString(sentence)
  val start = (boldIndex - before).coerceAtLeast(0)
  val end = (boldIndex + after).coerceAtMost(words.size - 1)
  return buildAnnotatedString {
    if (start > 0) append("… ")
    for (i in start until boldIndex) append(words[i] + " ")
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(words[boldIndex]) }
    for (i in boldIndex + 1..end) append(" " + words[i])
    if (end < words.size - 1) append(" …")
  }
}

/**
 * A little window for a low-confidence word. Each alternative is run to the end
 * of the sentence (a steer with the forced prefix) so the option shows the
 * *actual* re-decoded sentence, windowed around the swap. Tapping commits it.
 */
@Composable
fun AlternativesDialog(
  target: WordAlternatives,
  fullText: String,
  steerPreview: suspend (String) -> String?,
  onPick: (WordAlternative) -> Unit,
  onDismiss: () -> Unit,
) {
  val begin = target.tgtBegin.toInt().coerceIn(0, fullText.length)
  val head = fullText.substring(0, begin)
  // The swapped word sits right after the confirmed prefix, so its word index
  // (in both the original and every re-decoded sentence) is the prefix's word
  // count.
  val boldIndex = head.split(WS).filter { it.isNotBlank() }.size

  // option text -> re-decoded sentence (null while loading / on failure).
  val previews = remember(target) { mutableStateMapOf<String, String?>() }
  LaunchedEffect(target) {
    for (option in target.options) {
      previews[option.text] = steerPreview(head + option.text)
    }
  }

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 6.dp,
    ) {
      Column(
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = window(fullText, boldIndex),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        for (option in target.options) {
          val preview = previews[option.text]
          Text(
            // Before the steer returns, show the swap in place with no tail.
            text = window(preview ?: (head + option.text), boldIndex),
            style = MaterialTheme.typography.bodyLarge,
            color =
              if (preview == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
              } else {
                MaterialTheme.colorScheme.onSurface
              },
            modifier =
              Modifier
                .fillMaxWidth()
                .clickable { onPick(option) }
                .padding(vertical = 12.dp),
          )
        }
      }
    }
  }
}
