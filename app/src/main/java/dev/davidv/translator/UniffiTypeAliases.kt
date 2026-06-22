package dev.davidv.translator

typealias ReadingOrder = uniffi.translator_core.ReadingOrder
typealias NothingReason = uniffi.translator_translate.NothingReason
typealias BackgroundMode = uniffi.translator_core.BackgroundMode
typealias PreparedImageOverlay = uniffi.translator_core.PreparedImageOverlay
typealias TokenAlignment = uniffi.translator_translate.TokenAlignment
typealias TranslationWithAlignment = uniffi.translator_translate.TranslationWithAlignment
typealias Feature = uniffi.translator.Feature
typealias DownloadPlan = uniffi.translator_core.DownloadPlan
typealias DownloadTask = uniffi.translator_core.DownloadTask
typealias DeletePlan = uniffi.translator_core.DeletePlan
typealias TtsVoicePackInfo = uniffi.translator_core.TtsVoicePackInfo
typealias TtsVoicePickerRegion = uniffi.translator_core.TtsVoicePickerRegion

val BackgroundMode.displayName: String
  get() =
    when (this) {
      BackgroundMode.WHITE_ON_BLACK -> "White on Black"
      BackgroundMode.BLACK_ON_WHITE -> "Black on White"
      BackgroundMode.AUTO_DETECT -> "Auto-detect Colors"
    }
