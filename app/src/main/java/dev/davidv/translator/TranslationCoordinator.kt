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

package dev.davidv.translator

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import uniffi.translator_core.OcrSourceSelection
import uniffi.translator_core.PreparedImageOverlay
import java.nio.ByteBuffer
import kotlin.system.measureTimeMillis

class TranslationCoordinator(
  private val translationService: TranslationService,
  private val speechService: SpeechService,
  private val languageDetector: LanguageDetector,
  private val imageProcessor: ImageProcessor,
  private val settingsManager: SettingsManager,
) {
  private val _isTranslating = MutableStateFlow(false)
  val isTranslating: StateFlow<Boolean> = _isTranslating.asStateFlow()

  private val _isOcrInProgress = MutableStateFlow(false)
  val isOcrInProgress: StateFlow<Boolean> = _isOcrInProgress.asStateFlow()

  var lastTranslatedInput: String = ""

  suspend fun preloadModel(
    from: Language,
    to: Language,
  ) {
    if (_isTranslating.value) {
      return
    }
    translationService.preloadModel(from, to)
  }

  suspend fun translateText(
    from: Language,
    to: Language,
    text: String,
  ): TranslationResult {
    if (text.isBlank()) return TranslationResult.Success(TranslatedText("", ""))

    _isTranslating.value = true
    val result: TranslationResult
    try {
      val elapsed =
        measureTimeMillis {
          result = translationService.translate(from, to, text)
        }
      Log.d("TranslationCoordinator", "Translating ${text.length} chars from ${from.displayName} to ${to.displayName} took ${elapsed}ms")
    } finally {
      lastTranslatedInput = text
      _isTranslating.value = false
    }
    return result
  }

  /** Abort the in-flight document translation (fast, mid-batch). */
  fun cancelOngoingWork() = translationService.cancelOngoingWork()

  suspend fun translateDocumentPath(
    inputPath: String,
    outputPath: String,
    from: Language,
    to: Language,
    availableLanguages: List<Language>,
    translatePdfImages: Boolean,
    txtLayout: TxtLayoutChoice,
    onProgress: (DocumentTranslationProgress) -> Unit = {},
    isCancelled: () -> Boolean = { false },
  ): Result<String> {
    _isTranslating.value = true
    try {
      val result =
        translationService.translateDocumentPath(
          inputPath = inputPath,
          outputPath = outputPath,
          from = from,
          to = to,
          availableLanguages = availableLanguages,
          translatePdfImages = translatePdfImages,
          txtLayout = txtLayout,
          onProgress = onProgress,
          isCancelled = isCancelled,
        )
      result.onSuccess { path ->
        Log.d("TranslationCoordinator", "Translated document to $path")
      }
      return result
    } finally {
      lastTranslatedInput = inputPath
      _isTranslating.value = false
    }
  }

  suspend fun detectLanguage(
    text: String,
    hint: Language?,
  ): Language? = languageDetector.detectLanguage(text, hint)

  suspend fun detectLanguageRobust(
    text: String,
    hint: Language?,
    availableLanguages: List<Language>,
  ): Language? = languageDetector.detectLanguageRobust(text, hint, availableLanguages)

  suspend fun correctBitmap(
    uri: Uri,
    deleteAfterLoad: Boolean = false,
  ): Bitmap =
    withContext(Dispatchers.IO) {
      try {
        val originalBitmap = imageProcessor.loadBitmapFromUri(uri)
        val correctedBitmap = imageProcessor.correctImageOrientation(uri, originalBitmap)

        if (correctedBitmap !== originalBitmap && !originalBitmap.isRecycled) {
          originalBitmap.recycle()
        }

        correctedBitmap
      } finally {
        if (deleteAfterLoad) {
          imageProcessor.deleteTemporaryImageUri(uri)
        }
      }
    }

  suspend fun translateImageWithOverlay(
    from: Language,
    to: Language,
    finalBitmap: Bitmap,
    onMessage: (TranslatorMessage.ImageTextDetected) -> Unit,
    readingOrder: ReadingOrder? = null,
    isAutoSource: Boolean = false,
    onMissingDetectedLanguage: (Language) -> Unit = {},
    onDetectedRegions: (List<uniffi.translator_core.OrientedRect>, Int, Int) -> Unit = { _, _, _ -> },
  ): ProcessedImageResult? =
    withContext(Dispatchers.IO) {
      _isTranslating.value = true
      val totalStart = System.currentTimeMillis()
      try {
        _isOcrInProgress.value = true
        val catalog = imageProcessor.loadCatalog() ?: return@withContext null
        val minConfidence = settingsManager.settings.value.minConfidence
        val backgroundMode = settingsManager.settings.value.backgroundMode
        val maxImageSize = settingsManager.settings.value.maxImageSize
        val sourceSelection =
          if (isAutoSource) {
            OcrSourceSelection.Auto
          } else {
            OcrSourceSelection.Specific(uniffi.translator_core.LanguageCode(from.code))
          }
        // One pass: detection fires onDetectedRegions mid-call so the UI can pill the regions and
        // run a scan animation while recognition + translation finish, without crossing the FFI
        // boundary (or rebuilding the image) twice.
        val translateStart = System.currentTimeMillis()
        val plan =
          try {
            catalog.translateImagePlanStreaming(
              finalBitmap,
              maxImageSize,
              sourceSelection,
              to,
              minConfidence,
              readingOrder,
              backgroundMode,
            ) { boxes, width, height ->
              onDetectedRegions(boxes.map { it.orientedBox }, width, height)
            }
          } catch (e: uniffi.bindings.CatalogException.MissingAsset) {
            Log.d("OCR", "translateImagePlan failed: ${e.message}")
            if (isAutoSource) {
              detectedLanguageCodeFromMissingAsset(e.message)
                ?.let(catalog::languageByCode)
                ?.let(onMissingDetectedLanguage)
            }
            return@withContext null
          } catch (e: uniffi.bindings.CatalogException) {
            Log.d("OCR", "translateImagePlan failed: ${e.message}")
            return@withContext null
          }
        _isOcrInProgress.value = false
        Log.i(
          "TranslationCoordinator",
          "translateImagePlanStreaming FFI (rgba extract + marshal + native): ${System.currentTimeMillis() - translateStart}ms",
        )

        Log.d("OCR", "complete, blocks=${plan.blocks.size}")

        val extractedText = plan.extractedText
        onMessage(TranslatorMessage.ImageTextDetected(extractedText))
        lateinit var overlayBitmap: Bitmap
        var translatedWords: List<uniffi.translator_core.PositionedWord> = emptyList()
        val rendered: uniffi.translator_render.RenderedOverlay
        val translateMs =
          measureTimeMillis {
            rendered = catalog.renderTranslatedOverlay(plan, to, MIN_OVERLAY_FONT_SIZE_PX)
            translatedWords = rendered.translatedWords
          }
        Log.i("TranslationCoordinator", "Translation took ${translateMs}ms")
        val overpaintMs =
          measureTimeMillis {
            overlayBitmap = bitmapFromRgba(rendered.rgbaBytes, plan.width.toInt(), plan.height.toInt())
              ?: return@withContext null
          }
        Log.i("TranslationCoordinator", "Overpainting took ${overpaintMs}ms")
        Log.i("TranslationCoordinator", "OCR+translate+.. total: ${System.currentTimeMillis() - totalStart}ms")

        ProcessedImageResult(
          correctedBitmap = overlayBitmap,
          extractedText = extractedText,
          translatedText = plan.translatedText,
          metadata = plan,
          translatedWords = translatedWords,
        )
      } catch (e: Exception) {
        Log.e("TranslationCoordinator", "Exception ${e.stackTrace}")
        null
      } finally {
        _isOcrInProgress.value = false
        _isTranslating.value = false
      }
    }

  suspend fun retranslateImageWithOverlay(
    cachedPlan: PreparedImageOverlay,
    from: Language,
    to: Language,
    onMessage: (TranslatorMessage.ImageTextDetected) -> Unit,
  ): ProcessedImageResult? =
    withContext(Dispatchers.IO) {
      _isTranslating.value = true
      try {
        val catalog = imageProcessor.loadCatalog() ?: return@withContext null
        val plan =
          try {
            catalog.retranslateImagePlan(cachedPlan, from, to)
          } catch (e: uniffi.bindings.CatalogException) {
            Log.d("OCR", "retranslateImagePlan failed: ${e.message}")
            return@withContext null
          }

        val extractedText = plan.extractedText
        onMessage(TranslatorMessage.ImageTextDetected(extractedText))
        lateinit var overlayBitmap: Bitmap
        var translatedWords: List<uniffi.translator_core.PositionedWord> = emptyList()
        val translatePaint =
          measureTimeMillis {
            val rendered = catalog.renderTranslatedOverlay(plan, to, MIN_OVERLAY_FONT_SIZE_PX)
            overlayBitmap = bitmapFromRgba(rendered.rgbaBytes, plan.width.toInt(), plan.height.toInt())
              ?: return@withContext null
            translatedWords = rendered.translatedWords
          }
        Log.i("TranslationCoordinator", "Retranslate overpainting took ${translatePaint}ms")

        ProcessedImageResult(
          correctedBitmap = overlayBitmap,
          extractedText = extractedText,
          translatedText = plan.translatedText,
          metadata = plan,
          translatedWords = translatedWords,
        )
      } catch (e: Exception) {
        Log.e("TranslationCoordinator", "Exception ${e.stackTrace}")
        null
      } finally {
        _isTranslating.value = false
      }
    }

  fun transliterate(
    text: String,
    from: Language,
  ): String? = translationService.transliterate(text, from)

  private fun detectedLanguageCodeFromMissingAsset(message: String?): String? {
    if (message == null) return null
    val direction = Regex("\\b([a-z]{2,3}(?:-[A-Za-z0-9]+)?)->[a-z]{2,3}(?:-[A-Za-z0-9]+)?\\b").find(message)
    return direction?.groupValues?.getOrNull(1)
  }

  suspend fun synthesizeSpeech(
    language: Language,
    text: String,
  ): SpeechSynthesisResult = speechService.synthesizeSpeech(language, text)

  suspend fun availableTtsVoices(language: Language): List<TtsVoiceOption> = speechService.availableTtsVoices(language)

  suspend fun installedTtsVoices(language: Language): List<uniffi.translator_core.InstalledTtsPack> =
    speechService.installedTtsVoices(
      language,
    )

  private fun bitmapFromRgba(
    bytes: ByteArray,
    width: Int,
    height: Int,
  ): Bitmap? {
    return try {
      Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
      }
    } catch (e: Exception) {
      Log.e("TranslationCoordinator", "Failed to decode rendered overlay bitmap", e)
      null
    }
  }

  private companion object {
    const val MIN_OVERLAY_FONT_SIZE_PX: Float = 8.0f
  }
}

data class ProcessedImageResult(
  val correctedBitmap: Bitmap,
  val extractedText: String,
  val translatedText: String,
  val metadata: PreparedImageOverlay,
  // Per-word boxes of the rendered translation (image space). Source-word boxes for the
  // original text live on `metadata.sourceWords`. Both drive drag-to-copy.
  val translatedWords: List<uniffi.translator_core.PositionedWord>,
)
