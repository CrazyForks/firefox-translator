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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.StateFlow

/**
 * The main-screen image surface: the image scaled to fill the available width (scaling small
 * images up), capped at `maxHeight` — no scroll, no zoom — and sized to the image so the content
 * below it sits right under it. The word-selection overlay is drawn directly on it; while OCR
 * runs it shows the scan animation. `showOriginal` (driven by the top-bar flip) swaps to the
 * original image and its source words.
 */
@Composable
fun ImageDisplaySection(
  displayImage: Bitmap,
  originalImage: Bitmap?,
  showOriginal: Boolean,
  isOcrInProgress: StateFlow<Boolean>,
  isTranslating: StateFlow<Boolean>,
  detectedRegions: DetectedRegions?,
  wordSelection: ImageWordSelection?,
  maxHeight: Dp,
  modifier: Modifier = Modifier,
) {
  val isOcrInProgressState by isOcrInProgress.collectAsState()
  val isTranslatingState by isTranslating.collectAsState()
  val isProcessing = isOcrInProgressState || isTranslatingState

  val shown = if (showOriginal && originalImage != null) originalImage else displayImage
  val aspect = displayImage.width.toFloat() / displayImage.height.toFloat()

  BoxWithConstraints(modifier.fillMaxWidth()) {
    val heightForWidth = maxWidth / aspect
    val dispW = if (heightForWidth <= maxHeight) maxWidth else maxHeight * aspect
    val dispH = if (heightForWidth <= maxHeight) heightForWidth else maxHeight

    Box(
      modifier =
        Modifier
          .align(Alignment.TopCenter)
          .size(dispW, dispH),
    ) {
      Image(
        bitmap = shown.asImageBitmap(),
        contentDescription = if (showOriginal) "Original image" else "Translated image",
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
      )

      if (isProcessing && detectedRegions != null) {
        ScanAnimationOverlay(
          regions = detectedRegions,
          modifier = Modifier.matchParentSize(),
        )
      } else if (wordSelection != null) {
        WordSelectionOverlay(
          words = if (showOriginal) wordSelection.sourceWords else wordSelection.translatedWords,
          imageWidth = wordSelection.imageWidth,
          imageHeight = wordSelection.imageHeight,
          modifier = Modifier.matchParentSize(),
        )
      }
    }
  }
}
