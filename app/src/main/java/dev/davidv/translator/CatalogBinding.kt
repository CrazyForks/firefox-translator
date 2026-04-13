package dev.davidv.translator

class CatalogBinding {
  companion object {
    init {
      System.loadLibrary("bindings")
    }
  }

  fun openCatalog(
    bundledJson: String,
    diskJson: String?,
  ): Long = nativeOpenCatalog(bundledJson, diskJson.orEmpty())

  fun closeCatalog(handle: Long) {
    if (handle != 0L) {
      nativeCloseCatalog(handle)
    }
  }

  fun formatVersion(handle: Long): Int = nativeFormatVersion(handle)

  fun generatedAt(handle: Long): Long = nativeGeneratedAt(handle)

  fun dictionaryVersion(handle: Long): Int = nativeDictionaryVersion(handle)

  fun languages(handle: Long): Array<NativeLanguage> = nativeLanguages(handle) ?: emptyArray()

  fun computeLanguageAvailability(
    handle: Long,
    baseDir: String,
  ): Array<NativeLangAvailability> = nativeComputeLanguageAvailability(handle, baseDir) ?: emptyArray()

  fun dictionaryInfo(
    handle: Long,
    dictionaryCode: String,
  ): DictionaryInfo? = nativeDictionaryInfo(handle, dictionaryCode)

  fun ttsPackIds(
    handle: Long,
    languageCode: String,
  ): Array<String> = nativeTtsPackIds(handle, languageCode) ?: emptyArray()

  fun orderedTtsRegions(
    handle: Long,
    languageCode: String,
  ): Array<NativeLanguageTtsRegion> = nativeOrderedTtsRegions(handle, languageCode) ?: emptyArray()

  fun ttsVoicePackInfo(
    handle: Long,
    packId: String,
  ): NativeTtsVoicePackInfo? = nativeTtsVoicePackInfo(handle, packId)

  fun canSwapLanguages(
    handle: Long,
    fromCode: String,
    toCode: String,
  ): Boolean = nativeCanSwapLanguages(handle, fromCode, toCode)

  fun canTranslate(
    handle: Long,
    baseDir: String,
    fromCode: String,
    toCode: String,
  ): Boolean = nativeCanTranslate(handle, baseDir, fromCode, toCode)

  fun resolveTranslationPlan(
    handle: Long,
    baseDir: String,
    fromCode: String,
    toCode: String,
  ): NativeTranslationPlan? = nativeResolveTranslationPlan(handle, baseDir, fromCode, toCode)

  fun planLanguageDownload(
    handle: Long,
    baseDir: String,
    languageCode: String,
  ): NativeDownloadPlan = nativePlanLanguageDownload(handle, baseDir, languageCode)

  fun planDictionaryDownload(
    handle: Long,
    baseDir: String,
    languageCode: String,
  ): NativeDownloadPlan? = nativePlanDictionaryDownload(handle, baseDir, languageCode)

  fun planTtsDownload(
    handle: Long,
    baseDir: String,
    languageCode: String,
    selectedPackId: String?,
  ): NativeDownloadPlan? = nativePlanTtsDownload(handle, baseDir, languageCode, selectedPackId.orEmpty())

  fun planDeleteLanguage(
    handle: Long,
    baseDir: String,
    languageCode: String,
  ): NativeDeletePlan = nativePlanDeleteLanguage(handle, baseDir, languageCode)

  fun planDeleteDictionary(
    handle: Long,
    baseDir: String,
    languageCode: String,
  ): NativeDeletePlan = nativePlanDeleteDictionary(handle, baseDir, languageCode)

  fun planDeleteTts(
    handle: Long,
    baseDir: String,
    languageCode: String,
  ): NativeDeletePlan = nativePlanDeleteTts(handle, baseDir, languageCode)

  fun planDeleteSupersededTts(
    handle: Long,
    baseDir: String,
    languageCode: String,
    selectedPackId: String,
  ): NativeDeletePlan = nativePlanDeleteSupersededTts(handle, baseDir, languageCode, selectedPackId)

  fun ttsSizeBytesForLanguage(
    handle: Long,
    languageCode: String,
  ): Long = nativeTtsSizeBytesForLanguage(handle, languageCode)

  fun defaultTtsPackIdForLanguage(
    handle: Long,
    languageCode: String,
  ): String? = nativeDefaultTtsPackIdForLanguage(handle, languageCode)

  fun resolveTtsVoiceFiles(
    handle: Long,
    baseDir: String,
    languageCode: String,
  ): NativeTtsVoiceFiles? = nativeResolveTtsVoiceFiles(handle, baseDir, languageCode)

  private external fun nativeOpenCatalog(
    bundledJson: String,
    diskJson: String,
  ): Long

  private external fun nativeCloseCatalog(handle: Long)

  private external fun nativeFormatVersion(handle: Long): Int

  private external fun nativeGeneratedAt(handle: Long): Long

  private external fun nativeDictionaryVersion(handle: Long): Int

  private external fun nativeLanguages(handle: Long): Array<NativeLanguage>?

  private external fun nativeComputeLanguageAvailability(
    handle: Long,
    baseDir: String,
  ): Array<NativeLangAvailability>?

  private external fun nativeDictionaryInfo(
    handle: Long,
    dictionaryCode: String,
  ): DictionaryInfo?

  private external fun nativeTtsPackIds(
    handle: Long,
    languageCode: String,
  ): Array<String>?

  private external fun nativeOrderedTtsRegions(
    handle: Long,
    languageCode: String,
  ): Array<NativeLanguageTtsRegion>?

  private external fun nativeTtsVoicePackInfo(
    handle: Long,
    packId: String,
  ): NativeTtsVoicePackInfo?

  private external fun nativeCanSwapLanguages(
    handle: Long,
    fromCode: String,
    toCode: String,
  ): Boolean

  private external fun nativeCanTranslate(
    handle: Long,
    baseDir: String,
    fromCode: String,
    toCode: String,
  ): Boolean

  private external fun nativeResolveTranslationPlan(
    handle: Long,
    baseDir: String,
    fromCode: String,
    toCode: String,
  ): NativeTranslationPlan?

  private external fun nativePlanLanguageDownload(
    handle: Long,
    baseDir: String,
    languageCode: String,
  ): NativeDownloadPlan

  private external fun nativePlanDictionaryDownload(
    handle: Long,
    baseDir: String,
    languageCode: String,
  ): NativeDownloadPlan?

  private external fun nativePlanTtsDownload(
    handle: Long,
    baseDir: String,
    languageCode: String,
    selectedPackId: String?,
  ): NativeDownloadPlan?

  private external fun nativePlanDeleteLanguage(
    handle: Long,
    baseDir: String,
    languageCode: String,
  ): NativeDeletePlan

  private external fun nativePlanDeleteDictionary(
    handle: Long,
    baseDir: String,
    languageCode: String,
  ): NativeDeletePlan

  private external fun nativePlanDeleteTts(
    handle: Long,
    baseDir: String,
    languageCode: String,
  ): NativeDeletePlan

  private external fun nativePlanDeleteSupersededTts(
    handle: Long,
    baseDir: String,
    languageCode: String,
    selectedPackId: String,
  ): NativeDeletePlan

  private external fun nativeTtsSizeBytesForLanguage(
    handle: Long,
    languageCode: String,
  ): Long

  private external fun nativeDefaultTtsPackIdForLanguage(
    handle: Long,
    languageCode: String,
  ): String?

  private external fun nativeResolveTtsVoiceFiles(
    handle: Long,
    baseDir: String,
    languageCode: String,
  ): NativeTtsVoiceFiles?
}
