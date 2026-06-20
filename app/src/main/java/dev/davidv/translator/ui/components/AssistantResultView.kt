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

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/**
 * The assistant overlay's image surface: the screenshot filling the window, showing the scan
 * animation while OCR runs and then the translated image with the interactive word-selection
 * overlay (tap to select, copy/share via the system action bar). The flip to the original lives in
 * the session toolbar. Hosted in the session window via a `ComposeView` (see `WindowComposeHost`).
 */
@Composable
fun AssistantResultView(
  display: Bitmap,
  original: Bitmap?,
  selection: ImageWordSelection?,
  isProcessing: Boolean,
  detectedRegions: DetectedRegions?,
  showOriginal: Boolean,
) {
  val configuration = LocalConfiguration.current
  ImageDisplaySection(
    displayImage = display,
    originalImage = original,
    showOriginal = showOriginal,
    isProcessing = isProcessing,
    detectedRegions = detectedRegions,
    wordSelection = selection,
    maxHeight = configuration.screenHeightDp.dp,
    modifier = Modifier.fillMaxWidth(),
  )
}
