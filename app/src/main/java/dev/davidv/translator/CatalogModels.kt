package dev.davidv.translator

import android.graphics.Bitmap
import uniffi.bindings.CatalogException
import uniffi.bindings.CatalogHandle
import uniffi.bindings.DocumentProgressEvent
import uniffi.bindings.DocumentProgressSink
import uniffi.bindings.TxtLayout
import uniffi.bindings.sampleOverlayColorsRgba
import java.nio.ByteBuffer

typealias CatalogError = CatalogException

// / Caller-chosen layout for `.txt` translation. Ignored for other
// / document types. `Reflow.wrapColumns == null` means do not re-wrap.
sealed class TxtLayoutChoice {
  data object Preserve : TxtLayoutChoice()

  data class Reflow(val wrapColumns: Int?) : TxtLayoutChoice()

  fun toUniffi(): TxtLayout =
    when (this) {
      Preserve -> TxtLayout.Preserve
      is Reflow -> TxtLayout.Reflow(wrap = wrapColumns?.toUInt())
    }
}

sealed class DocumentTranslationProgress {
  data object Preparing : DocumentTranslationProgress()

  data class PdfPlan(
    val textPages: Int,
    val imageXobjects: Int,
    val rasterPages: Int,
  ) : DocumentTranslationProgress()

  /** Text-translation completion as a smooth fraction in [0, 1]. */
  data class TranslatingText(
    val fraction: Float,
  ) : DocumentTranslationProgress()

  /** Image-XObject OCR pass: real item counts. */
  data class TranslatingImages(
    val current: Int,
    val total: Int,
  ) : DocumentTranslationProgress()

  /** Page-raster overlay pass: real item counts. */
  data class TranslatingRasterPages(
    val current: Int,
    val total: Int,
  ) : DocumentTranslationProgress()

  data object Writing : DocumentTranslationProgress()
}

private fun DocumentProgressEvent.toDocumentTranslationProgress(): DocumentTranslationProgress =
  when (this) {
    DocumentProgressEvent.Preparing -> DocumentTranslationProgress.Preparing
    is DocumentProgressEvent.PdfPlan ->
      DocumentTranslationProgress.PdfPlan(
        textPages = textPages.toInt(),
        imageXobjects = imageXobjects.toInt(),
        rasterPages = rasterPages.toInt(),
      )
    is DocumentProgressEvent.TranslatingText ->
      DocumentTranslationProgress.TranslatingText(fraction = fraction)
    is DocumentProgressEvent.TranslatingImages ->
      DocumentTranslationProgress.TranslatingImages(
        current = current.toInt(),
        total = total.toInt(),
      )
    is DocumentProgressEvent.TranslatingRasterPages ->
      DocumentTranslationProgress.TranslatingRasterPages(
        current = current.toInt(),
        total = total.toInt(),
      )
    DocumentProgressEvent.Writing -> DocumentTranslationProgress.Writing
  }

private fun rgbaBytes(bitmap: Bitmap): ByteArray =
  ByteArray(bitmap.byteCount).also { bytes ->
    bitmap.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
  }

data class LanguageTtsRegionV2(
  val displayName: String,
  val voices: List<String> = emptyList(),
)

data class LanguageAvailabilityEntry(
  val language: Language,
  val availability: LangAvailability,
)

data class CatalogFileEntry(
  val name: String,
  val sizeBytes: Long,
  val installPath: String,
  val url: String,
)

class LanguageCatalog private constructor(
  private val handle: CatalogHandle,
  val formatVersion: Int,
  val generatedAt: Long,
  val dictionaryVersion: Int,
  val languageRows: List<LanguageAvailabilityEntry>,
  val languageList: List<Language>,
  private val languagesByCode: Map<String, Language>,
  private val availabilityByCode: Map<String, LangAvailability>,
) {
  companion object {
    fun open(
      bundledJson: String,
      diskJson: String?,
      baseDir: String,
    ): LanguageCatalog? {
      val handle = CatalogHandle.open(bundledJson, diskJson, baseDir)
      val rows = handle.languageRows()
      val languageRows =
        rows.map { row ->
          LanguageAvailabilityEntry(
            language =
              Language(
                code = row.language.code,
                displayName = row.language.displayName,
                shortDisplayName = row.language.shortDisplayName,
                script = row.language.script,
                dictionaryCode = row.language.dictionaryCode,
              ),
            availability =
              LangAvailability(
                hasFromEnglish = row.availability.hasFromEnglish,
                hasToEnglish = row.availability.hasToEnglish,
                ocrFiles = row.availability.ocrFiles,
                dictionaryFiles = row.availability.dictionaryFiles,
                ttsFiles = row.availability.ttsFiles,
              ),
          )
        }
      val languageList = languageRows.map { it.language }
      val languagesByCode = languageList.associateBy { it.code }
      val availabilityByCode =
        languageRows.associate { row -> row.language.code to row.availability }
      return LanguageCatalog(
        handle = handle,
        formatVersion = handle.formatVersion(),
        generatedAt = handle.generatedAt(),
        dictionaryVersion = handle.dictionaryVersion(),
        languageRows = languageRows,
        languageList = languageList,
        languagesByCode = languagesByCode,
        availabilityByCode = availabilityByCode,
      )
    }
  }

  val english: Language by lazy {
    languagesByCode.getValue("en")
  }

  fun languageByCode(code: String): Language? = languagesByCode[code]

  fun dictionaryInfoFor(language: Language): DictionaryInfo? = dictionaryInfo(language.dictionaryCode)

  fun dictionaryInfo(dictionaryCode: String): DictionaryInfo? =
    handle.dictionaryInfo(dictionaryCode)?.let {
      DictionaryInfo(date = it.date, filename = it.filename, size = it.size.toLong(), type = it.typeName, wordCount = it.wordCount.toLong())
    }

  fun supportFilesByKind(kind: String): List<CatalogFileEntry> =
    handle.supportFilesByKind(kind).map { file ->
      CatalogFileEntry(
        name = file.name,
        sizeBytes = file.sizeBytes.toLong(),
        installPath = file.installPath,
        url = file.url,
      )
    }

  @Throws(CatalogException::class)
  fun lookupDictionary(
    language: Language,
    word: String,
  ): WordWithTaggedEntries? = handle.lookupDictionary(language.code, word)

  fun availabilityFor(language: Language?): LangAvailability? = language?.let { availabilityByCode[it.code] }

  fun hasTtsVoices(languageCode: String): Boolean = handle.hasTtsVoices(languageCode)

  fun installedOcrEngines(languageCode: String): List<String> = handle.installedOcrEngines(languageCode)

  fun availableOcrEngines(languageCode: String): List<String> = handle.availableOcrEngines(languageCode)

  fun planOcrEngineDownload(
    languageCode: String,
    engine: String,
  ): DownloadPlan? = handle.planOcrEngineDownload(languageCode, engine)

  fun planOcrEngineDownloads(
    languageCodes: List<String>,
    engine: String,
  ): DownloadPlan = handle.planOcrEngineDownloads(languageCodes, engine)

  fun planOcrEngineUpgrades(
    languageCodes: List<String>,
    engine: String,
  ): DownloadPlan = handle.planOcrEngineUpgrades(languageCodes, engine)

  fun planDeleteSupersededFiles(): DeletePlan = handle.planDeleteSupersededFiles()

  fun ttsVoicePickerRegions(languageCode: String): List<TtsVoicePickerRegion> = handle.ttsVoicePickerRegions(languageCode)

  fun installedTtsVoicePickerRegions(languageCode: String): List<TtsVoicePickerRegion> = handle.installedTtsVoicePickerRegions(languageCode)

  fun ttsSampleText(languageCode: String): String? = handle.ttsSampleText(languageCode)

  fun canSwapLanguages(
    from: Language,
    to: Language,
  ): Boolean = handle.canSwapLanguages(from.code, to.code)

  fun canTranslate(
    from: Language,
    to: Language,
  ): Boolean = handle.canTranslate(from.code, to.code)

  fun warmTranslationModels(
    from: Language,
    to: Language,
  ): Boolean = handle.warmTranslationModels(from.code, to.code)

  @Throws(CatalogException::class)
  fun translateText(
    from: Language,
    to: Language,
    text: String,
  ): String = handle.translateText(from.code, to.code, text)

  fun translateMixedTexts(
    inputs: List<String>,
    forcedSourceLanguage: Language?,
    targetLanguage: Language,
    availableLanguages: List<Language>,
  ): uniffi.translator_translate.MixedTextTranslationResult =
    handle.translateMixedTexts(
      inputs,
      forcedSourceLanguage?.code,
      targetLanguage.code,
      availableLanguages.map { it.code },
    )

  @Throws(CatalogException::class)
  fun translateHtmlFragments(
    from: Language,
    to: Language,
    fragments: List<String>,
  ): List<String> = handle.translateHtmlFragments(from.code, to.code, fragments)

  @Throws(CatalogException::class)
  fun translateImagePlan(
    bitmap: Bitmap,
    maxImageSize: Int,
    sourceSelection: uniffi.translator_core.OcrSourceSelection,
    to: Language,
    minConfidence: Int,
    readingOrder: ReadingOrder?,
    backgroundMode: BackgroundMode,
    detection: List<uniffi.translator_core.DetectedTextBox>? = null,
  ): uniffi.translator_core.PreparedImageOverlay =
    handle.translateImagePlan(
      rgbaBytes(bitmap),
      bitmap.width.toUInt(),
      bitmap.height.toUInt(),
      maxImageSize.toUInt(),
      sourceSelection,
      to.code,
      minConfidence.toUInt(),
      readingOrder,
      backgroundMode,
      detection,
    )

  @Throws(CatalogException::class)
  fun detectImageBoxes(
    bitmap: Bitmap,
    maxImageSize: Int,
  ): List<uniffi.translator_core.DetectedTextBox> =
    handle.detectImageBoxes(
      rgbaBytes(bitmap),
      bitmap.width.toUInt(),
      bitmap.height.toUInt(),
      maxImageSize.toUInt(),
    )

  @Throws(CatalogException::class)
  fun retranslateImagePlan(
    prepared: uniffi.translator_core.PreparedImageOverlay,
    from: Language,
    to: Language,
  ): uniffi.translator_core.PreparedImageOverlay = handle.retranslateImagePlan(prepared, from.code, to.code)

  /** Internal accessor for the raw uniffi `CatalogHandle`. The live-
   *  overlay pipeline constructor takes this so it can hold an Arc to
   *  the underlying TranslatorSession for its async acquire/refresh
   *  worker. */
  internal fun planarHandle(): CatalogHandle = handle

  @Throws(CatalogException::class)
  fun renderTranslatedOverlay(
    plan: uniffi.translator_core.PreparedImageOverlay,
    targetLanguage: Language,
    minFontSizePx: Float,
  ): uniffi.translator_render.RenderedOverlay = uniffi.bindings.renderTranslatedOverlay(plan, targetLanguage.code, minFontSizePx)

  @Throws(CatalogException::class)
  fun detectDocumentQuad(bitmap: Bitmap): uniffi.translator_align.DocumentDetection? =
    handle.detectDocumentQuad(rgbaBytes(bitmap), bitmap.width.toUInt(), bitmap.height.toUInt())

  @Throws(CatalogException::class)
  fun warpDocumentRgba(
    bitmap: Bitmap,
    quad: uniffi.translator_align.DocumentQuad,
    outWidth: Int? = null,
    outHeight: Int? = null,
    postprocess: Boolean = true,
  ): uniffi.translator_align.WarpedImageRgba =
    handle.warpDocumentRgba(
      rgbaBytes(bitmap),
      bitmap.width.toUInt(),
      bitmap.height.toUInt(),
      quad,
      outWidth?.toUInt(),
      outHeight?.toUInt(),
      postprocess,
    )

  @Throws(CatalogException::class)
  fun translateDocumentPath(
    inputPath: String,
    outputPath: String,
    from: Language,
    to: Language,
    availableLanguages: List<Language>,
    translatePdfImages: Boolean,
    txtLayout: TxtLayoutChoice,
    onProgress: (DocumentTranslationProgress) -> Unit = {},
    isCancelled: () -> Boolean = { false },
  ): String =
    handle.translateDocumentPathWithProgress(
      inputPath,
      outputPath,
      from.code,
      to.code,
      availableLanguages.map { it.code },
      translatePdfImages,
      txtLayout.toUniffi(),
      object : DocumentProgressSink {
        override fun onProgress(event: DocumentProgressEvent) {
          onProgress(event.toDocumentTranslationProgress())
        }

        override fun isCancelled(): Boolean = isCancelled()
      },
    )

  /** Abort an in-flight document translation; workers stop within ~one batch. */
  fun cancelOngoingWork() = handle.cancelOngoingWork()

  fun planDownload(
    languageCode: String,
    feature: Feature,
    selectedTtsPackId: String? = null,
  ): DownloadPlan? = handle.planDownload(languageCode, feature, selectedTtsPackId)

  fun planSupportDownloadByKind(kind: String): DownloadPlan? = handle.planSupportDownloadByKind(kind)

  fun prepareDelete(
    languageCode: String,
    feature: Feature,
  ): DeletePlan = handle.prepareDelete(languageCode, feature)

  fun prepareDeleteSupportByKind(kind: String): DeletePlan = handle.prepareDeleteSupportByKind(kind)

  fun prepareDeleteSupersededTts(
    languageCode: String,
    selectedPackId: String,
  ): DeletePlan = handle.prepareDeleteSupersededTts(languageCode, selectedPackId)

  fun prepareDeleteTtsPack(packId: String): DeletePlan = handle.prepareDeleteTtsPack(packId)

  fun defaultTtsPackIdForLanguage(languageCode: String): String? = handle.defaultTtsPackId(languageCode)

  fun sizeBytesForFeature(
    languageCode: String,
    feature: Feature,
  ): Long = handle.sizeBytes(languageCode, feature).toLong()

  fun supportSizeBytesByKind(kind: String): Long = handle.supportSizeBytesByKind(kind).toLong()

  fun supportInstalledByKind(kind: String): Boolean {
    val sizeBytes = supportSizeBytesByKind(kind)
    val plan = planSupportDownloadByKind(kind) ?: return false
    return sizeBytes > 0 && plan.tasks.isEmpty()
  }

  fun availableTtsVoices(languageCode: String): List<TtsVoiceOption> =
    handle.availableTtsVoices(languageCode).map { voice ->
      TtsVoiceOption(
        name = voice.name,
        speakerId = voice.speakerId.toInt(),
        displayName = voice.displayName,
      )
    }

  fun installedTtsVoices(languageCode: String): List<uniffi.translator_core.InstalledTtsPack> = handle.installedTtsVoices(languageCode)

  fun planSpeechChunks(
    languageCode: String,
    text: String,
    packId: String? = null,
  ): List<SpeechChunkPlan> =
    handle.planSpeechChunks(languageCode, text, packId).map { chunk ->
      SpeechChunkPlan(
        content = chunk.content,
        isPhonemes = chunk.isPhonemes,
        pauseAfterMs = chunk.pauseAfterMs,
      )
    }

  @Throws(CatalogException::class)
  fun synthesizeSpeechPcm(
    languageCode: String,
    text: String,
    speechSpeed: Float,
    voiceName: String?,
    isPhonemes: Boolean,
    packId: String? = null,
  ): PcmAudio {
    val audio =
      handle.synthesizeSpeechPcm(languageCode, text, speechSpeed, voiceName, isPhonemes, packId)
    return PcmAudio(sampleRate = audio.sampleRate, pcmSamples = audio.pcmSamples.toShortArray())
  }
}

fun sampleOverlayColors(
  bitmap: Bitmap,
  bounds: Rect,
  backgroundMode: BackgroundMode,
  wordRects: Array<Rect>? = null,
): OverlayColors {
  val sampleMargin = 4
  val cropLeft = (bounds.left - sampleMargin).coerceAtLeast(0)
  val cropTop = (bounds.top - sampleMargin).coerceAtLeast(0)
  val cropRight = (bounds.right + sampleMargin).coerceAtMost(bitmap.width)
  val cropBottom = (bounds.bottom + sampleMargin).coerceAtMost(bitmap.height)
  val cropWidth = cropRight - cropLeft
  val cropHeight = cropBottom - cropTop
  if (cropWidth <= 0 || cropHeight <= 0) {
    return OverlayColors(
      background = android.graphics.Color.WHITE,
      foreground = android.graphics.Color.BLACK,
    )
  }

  val croppedBitmap = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropWidth, cropHeight)
  val localBounds =
    uniffi.translator_core.Rect(
      left = (bounds.left - cropLeft).coerceIn(0, cropWidth).toUInt(),
      top = (bounds.top - cropTop).coerceIn(0, cropHeight).toUInt(),
      right = (bounds.right - cropLeft).coerceIn(0, cropWidth).toUInt(),
      bottom = (bounds.bottom - cropTop).coerceIn(0, cropHeight).toUInt(),
    )
  val localWordRects =
    wordRects?.mapNotNull { rect ->
      val left = (rect.left - cropLeft).coerceIn(0, cropWidth)
      val top = (rect.top - cropTop).coerceIn(0, cropHeight)
      val right = (rect.right - cropLeft).coerceIn(0, cropWidth)
      val bottom = (rect.bottom - cropTop).coerceIn(0, cropHeight)
      if (right <= left || bottom <= top) {
        null
      } else {
        uniffi.translator_core.Rect(
          left = left.toUInt(),
          top = top.toUInt(),
          right = right.toUInt(),
          bottom = bottom.toUInt(),
        )
      }
    }
  val colors =
    sampleOverlayColorsRgba(
      rgbaBytes(croppedBitmap),
      croppedBitmap.width.toUInt(),
      croppedBitmap.height.toUInt(),
      localBounds,
      backgroundMode,
      localWordRects,
    )
  croppedBitmap.recycle()
  return OverlayColors(
    background = colors?.backgroundArgb?.toInt() ?: android.graphics.Color.WHITE,
    foreground = colors?.foregroundArgb?.toInt() ?: android.graphics.Color.BLACK,
  )
}
