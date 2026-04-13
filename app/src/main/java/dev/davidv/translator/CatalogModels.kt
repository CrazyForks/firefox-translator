package dev.davidv.translator

import java.io.Closeable
import java.io.File

data class LanguageTtsRegionV2(
  val displayName: String,
  val voices: List<String> = emptyList(),
)

data class TtsVoicePackInfo(
  val packId: String,
  val displayName: String,
  val quality: String? = null,
  val sizeBytes: Long,
)

data class TranslationPlanStep(
  val fromCode: String,
  val toCode: String,
  val cacheKey: String,
  val config: String,
)

data class TranslationPlan(
  val steps: List<TranslationPlanStep>,
)

data class DownloadTask(
  val packId: String,
  val installPath: String,
  val url: String,
  val sizeBytes: Long,
  val decompress: Boolean,
  val archiveFormat: String? = null,
  val extractTo: String? = null,
  val deleteAfterExtract: Boolean = false,
  val installMarkerPath: String? = null,
  val installMarkerVersion: Int? = null,
)

data class DownloadPlan(
  val totalSize: Long,
  val tasks: List<DownloadTask>,
)

data class DeletePlan(
  val filePaths: List<String>,
  val directoryPaths: List<String>,
)

data class NativeLanguage(
  val code: String,
  val displayName: String,
  val shortDisplayName: String,
  val tessName: String,
  val script: String,
  val dictionaryCode: String,
  val tessdataSizeBytes: Long,
  val toEnglish: LanguageDirection?,
  val fromEnglish: LanguageDirection?,
  val extraFiles: Array<String>,
)

data class NativeLangAvailability(
  val code: String,
  val hasFromEnglish: Boolean,
  val hasToEnglish: Boolean,
  val ocrFiles: Boolean,
  val dictionaryFiles: Boolean,
  val ttsFiles: Boolean,
)

data class NativeLanguageTtsRegion(
  val code: String,
  val displayName: String,
  val voices: Array<String>,
)

data class NativeDownloadTask(
  val packId: String,
  val installPath: String,
  val url: String,
  val sizeBytes: Long,
  val decompress: Boolean,
  val archiveFormat: String?,
  val extractTo: String?,
  val deleteAfterExtract: Boolean,
  val installMarkerPath: String?,
  val installMarkerVersion: Int?,
)

data class NativeDownloadPlan(
  val totalSize: Long,
  val tasks: Array<NativeDownloadTask>,
)

data class NativeDeletePlan(
  val filePaths: Array<String>,
  val directoryPaths: Array<String>,
)

data class NativeTtsVoicePackInfo(
  val packId: String,
  val displayName: String,
  val quality: String?,
  val sizeBytes: Long,
)

data class NativeTtsVoiceFiles(
  val engine: String,
  val modelPath: String,
  val auxPath: String,
  val languageCode: String,
  val speakerId: Int?,
)

data class NativeTranslationPlanStep(
  val fromCode: String,
  val toCode: String,
  val cacheKey: String,
  val config: String,
)

data class NativeTranslationPlan(
  val steps: Array<NativeTranslationPlanStep>,
)

class LanguageCatalog private constructor(
  private var nativeHandle: Long,
  val formatVersion: Int,
  val generatedAt: Long,
  val dictionaryVersion: Int,
  val languageList: List<Language>,
) : Closeable {
  companion object {
    private val binding = CatalogBinding()

    fun open(
      bundledJson: String,
      diskJson: String?,
    ): LanguageCatalog? {
      val handle = binding.openCatalog(bundledJson, diskJson)
      if (handle == 0L) return null

      return try {
        LanguageCatalog(
          nativeHandle = handle,
          formatVersion = binding.formatVersion(handle),
          generatedAt = binding.generatedAt(handle),
          dictionaryVersion = binding.dictionaryVersion(handle),
          languageList = binding.languages(handle).map(NativeLanguage::toLanguage),
        )
      } catch (t: Throwable) {
        binding.closeCatalog(handle)
        throw t
      }
    }
  }

  val english: Language by lazy {
    languageList.first { it.code == "en" }
  }

  fun languageByCode(code: String): Language? = languageList.firstOrNull { it.code == code }

  fun dictionaryInfoFor(language: Language): DictionaryInfo? = dictionaryInfo(language.dictionaryCode)

  fun dictionaryInfo(dictionaryCode: String): DictionaryInfo? = binding.dictionaryInfo(handle(), dictionaryCode)

  fun computeLanguageAvailability(baseDir: String): Map<Language, LangAvailability> {
    val languagesByCode = languageList.associateBy { it.code }
    return buildMap {
      binding.computeLanguageAvailability(handle(), baseDir).forEach { row ->
        val language = languagesByCode[row.code] ?: return@forEach
        put(
          language,
          LangAvailability(
            hasFromEnglish = row.hasFromEnglish,
            hasToEnglish = row.hasToEnglish,
            ocrFiles = row.ocrFiles,
            dictionaryFiles = row.dictionaryFiles,
            ttsFiles = row.ttsFiles,
          ),
        )
      }
    }
  }

  fun ttsPackIdsForLanguage(languageCode: String): List<String> = binding.ttsPackIds(handle(), languageCode).toList()

  fun orderedTtsRegionsForLanguage(languageCode: String): List<Pair<String, LanguageTtsRegionV2>> =
    binding
      .orderedTtsRegions(handle(), languageCode)
      .map { region -> region.code to LanguageTtsRegionV2(region.displayName, region.voices.toList()) }

  fun ttsVoicePackInfo(packId: String): TtsVoicePackInfo? = binding.ttsVoicePackInfo(handle(), packId)?.toTtsVoicePackInfo()

  fun canSwapLanguages(
    from: Language,
    to: Language,
  ): Boolean = binding.canSwapLanguages(handle(), from.code, to.code)

  fun canTranslate(
    baseDir: String,
    from: Language,
    to: Language,
  ): Boolean = binding.canTranslate(handle(), baseDir, from.code, to.code)

  fun resolveTranslationPlan(
    baseDir: String,
    from: Language,
    to: Language,
  ): TranslationPlan? = binding.resolveTranslationPlan(handle(), baseDir, from.code, to.code)?.toTranslationPlan()

  fun planLanguageDownload(
    baseDir: String,
    languageCode: String,
  ): DownloadPlan = binding.planLanguageDownload(handle(), baseDir, languageCode).toDownloadPlan()

  fun planDictionaryDownload(
    baseDir: String,
    languageCode: String,
  ): DownloadPlan? = binding.planDictionaryDownload(handle(), baseDir, languageCode)?.toDownloadPlan()

  fun planTtsDownload(
    baseDir: String,
    languageCode: String,
    selectedPackId: String? = null,
  ): DownloadPlan? = binding.planTtsDownload(handle(), baseDir, languageCode, selectedPackId)?.toDownloadPlan()

  fun planDeleteLanguage(
    baseDir: String,
    languageCode: String,
  ): DeletePlan = binding.planDeleteLanguage(handle(), baseDir, languageCode).toDeletePlan()

  fun planDeleteDictionary(
    baseDir: String,
    languageCode: String,
  ): DeletePlan = binding.planDeleteDictionary(handle(), baseDir, languageCode).toDeletePlan()

  fun planDeleteTts(
    baseDir: String,
    languageCode: String,
  ): DeletePlan = binding.planDeleteTts(handle(), baseDir, languageCode).toDeletePlan()

  fun planDeleteSupersededTts(
    baseDir: String,
    languageCode: String,
    selectedPackId: String,
  ): DeletePlan = binding.planDeleteSupersededTts(handle(), baseDir, languageCode, selectedPackId).toDeletePlan()

  fun defaultTtsPackIdForLanguage(languageCode: String): String? = binding.defaultTtsPackIdForLanguage(handle(), languageCode)

  fun ttsSizeBytesForLanguage(languageCode: String): Long = binding.ttsSizeBytesForLanguage(handle(), languageCode)

  fun resolveTtsVoiceFiles(
    baseDir: String,
    languageCode: String,
  ): TtsVoiceFiles? =
    binding.resolveTtsVoiceFiles(handle(), baseDir, languageCode)?.let { files ->
      TtsVoiceFiles(
        engine = files.engine,
        model = File(files.modelPath),
        aux = File(files.auxPath),
        languageCode = files.languageCode,
        speakerId = files.speakerId,
      )
    }

  @Synchronized
  override fun close() {
    val handle = nativeHandle
    if (handle == 0L) return
    nativeHandle = 0L
    binding.closeCatalog(handle)
  }

  @Suppress("deprecation")
  protected fun finalize() {
    close()
  }

  @Synchronized
  private fun handle(): Long {
    check(nativeHandle != 0L) { "LanguageCatalog is closed" }
    return nativeHandle
  }
}

private fun NativeLanguage.toLanguage(): Language =
  Language(
    code = code,
    displayName = displayName,
    shortDisplayName = shortDisplayName,
    tessName = tessName,
    script = script,
    dictionaryCode = dictionaryCode,
    tessdataSizeBytes = tessdataSizeBytes,
    toEnglish = toEnglish,
    fromEnglish = fromEnglish,
    extraFiles = extraFiles.toList(),
  )

private fun NativeDownloadTask.toDownloadTask(): DownloadTask =
  DownloadTask(
    packId = packId,
    installPath = installPath,
    url = url,
    sizeBytes = sizeBytes,
    decompress = decompress,
    archiveFormat = archiveFormat,
    extractTo = extractTo,
    deleteAfterExtract = deleteAfterExtract,
    installMarkerPath = installMarkerPath,
    installMarkerVersion = installMarkerVersion,
  )

private fun NativeDownloadPlan.toDownloadPlan(): DownloadPlan =
  DownloadPlan(
    totalSize = totalSize,
    tasks = tasks.map(NativeDownloadTask::toDownloadTask),
  )

private fun NativeDeletePlan.toDeletePlan(): DeletePlan =
  DeletePlan(
    filePaths = filePaths.toList(),
    directoryPaths = directoryPaths.toList(),
  )

private fun NativeTtsVoicePackInfo.toTtsVoicePackInfo(): TtsVoicePackInfo =
  TtsVoicePackInfo(
    packId = packId,
    displayName = displayName,
    quality = quality,
    sizeBytes = sizeBytes,
  )

private fun NativeTranslationPlanStep.toTranslationPlanStep(): TranslationPlanStep =
  TranslationPlanStep(
    fromCode = fromCode,
    toCode = toCode,
    cacheKey = cacheKey,
    config = config,
  )

private fun NativeTranslationPlan.toTranslationPlan(): TranslationPlan =
  TranslationPlan(
    steps = steps.map(NativeTranslationPlanStep::toTranslationPlanStep),
  )
