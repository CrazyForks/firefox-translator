package dev.davidv.translator

class TransliterateBinding {
  companion object {
    init {
      System.loadLibrary("bindings")
    }
  }

  fun transliterate(
    text: String,
    languageCode: String,
    sourceScript: String,
    targetScript: String = "Latn",
    japaneseDictPath: String? = null,
    japaneseSpaced: Boolean = true,
  ): String? =
    nativeTransliterateWithPolicy(
      text,
      languageCode,
      sourceScript,
      targetScript,
      japaneseDictPath.orEmpty(),
      japaneseSpaced,
    )

  private external fun nativeTransliterateWithPolicy(
    text: String,
    languageCode: String,
    sourceScript: String,
    targetScript: String,
    japaneseDictPath: String,
    japaneseSpaced: Boolean,
  ): String?
}
