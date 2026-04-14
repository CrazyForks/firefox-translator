package dev.davidv.translator

data class NativeTtsVoice(
  val name: String,
  val speakerId: Int,
  val displayName: String,
)

data class NativeSpeechChunkPlan(
  val content: String,
  val isPhonemes: Boolean,
  val pauseAfterMs: Int,
)

data class TtsVoiceOption(
  val name: String,
  val speakerId: Int,
  val displayName: String,
)

data class SpeechChunkPlan(
  val content: String,
  val isPhonemes: Boolean,
  val pauseAfterMs: Int?,
)

class SpeechBinding {
  companion object {
    init {
      System.loadLibrary("bindings")
    }
  }

  fun synthesizePcm(
    engine: String,
    modelPath: String,
    auxPath: String,
    supportDataPath: String?,
    languageCode: String,
    text: String,
    speechSpeed: Float = 1.0f,
    voiceName: String? = null,
    speakerId: Int? = null,
    isPhonemes: Boolean = false,
  ): PcmAudio? =
    nativeSynthesizePcm(
      engine,
      modelPath,
      auxPath,
      supportDataPath.orEmpty(),
      languageCode,
      text,
      speechSpeed,
      voiceName.orEmpty(),
      speakerId ?: -1,
      isPhonemes,
    )

  fun planSpeechChunks(
    engine: String,
    modelPath: String,
    auxPath: String,
    supportDataPath: String?,
    languageCode: String,
    text: String,
  ): List<SpeechChunkPlan>? =
    nativePlanSpeechChunks(
      engine,
      modelPath,
      auxPath,
      supportDataPath.orEmpty(),
      languageCode,
      text,
    )
      ?.map { chunk ->
        SpeechChunkPlan(
          content = chunk.content,
          isPhonemes = chunk.isPhonemes,
          pauseAfterMs = chunk.pauseAfterMs.takeIf { it >= 0 },
        )
      }
      ?.toList()

  fun listVoices(
    engine: String,
    modelPath: String,
    auxPath: String,
    supportDataPath: String?,
    languageCode: String,
  ): List<TtsVoiceOption>? =
    nativeListVoices(
      engine,
      modelPath,
      auxPath,
      supportDataPath.orEmpty(),
      languageCode,
    )?.map { voice ->
      TtsVoiceOption(name = voice.name, speakerId = voice.speakerId, displayName = voice.displayName)
    }

  private external fun nativeSynthesizePcm(
    engine: String,
    modelPath: String,
    auxPath: String,
    supportDataPath: String,
    languageCode: String,
    text: String,
    speechSpeed: Float,
    voiceName: String,
    speakerId: Int,
    isPhonemes: Boolean,
  ): PcmAudio?

  private external fun nativePlanSpeechChunks(
    engine: String,
    modelPath: String,
    auxPath: String,
    supportDataPath: String,
    languageCode: String,
    text: String,
  ): Array<NativeSpeechChunkPlan>?

  private external fun nativeListVoices(
    engine: String,
    modelPath: String,
    auxPath: String,
    supportDataPath: String,
    languageCode: String,
  ): Array<NativeTtsVoice>?
}
