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

import android.util.Log
import dev.davidv.bergamot.NativeLib
import dev.davidv.bergamot.TranslationWithAlignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

class TranslationService(
  private val settingsManager: SettingsManager,
  private val filePathManager: FilePathManager,
) {
  companion object {
    @Volatile
    private var nativeLibInstance: NativeLib? = null

    private fun getNativeLib(): NativeLib =
      nativeLibInstance ?: synchronized(this) {
        nativeLibInstance ?: NativeLib().also {
          Log.d("TranslationService", "Initialized bergamot")
          nativeLibInstance = it
        }
      }

    fun cleanup() {
      synchronized(this) {
        nativeLibInstance?.cleanup()
        nativeLibInstance = null
      }
    }
  }

  private val nativeLib = getNativeLib()
  private val speechBinding = SpeechBinding()

  private var mucabBinding: MucabBinding? = null

  fun setMucabBinding(binding: MucabBinding?) {
    mucabBinding = binding
  }

  // / Requires the translation pairs to be available
  suspend fun preloadModel(
    from: Language,
    to: Language,
  ) = withContext(Dispatchers.IO) {
    if (from == to) return@withContext

    val catalog = filePathManager.loadCatalog() ?: return@withContext
    val plan = catalog.resolveTranslationPlan(from, to) ?: return@withContext
    loadPlanIntoCache(plan)
  }

  suspend fun translateMultiple(
    from: Language,
    to: Language,
    texts: Array<String>,
  ): BatchTranslationResult =
    withContext(Dispatchers.IO) {
      if (from == to) {
        return@withContext BatchTranslationResult.Success(texts.map { TranslatedText(it, null) })
      }
      val catalog =
        filePathManager.loadCatalog()
          ?: return@withContext BatchTranslationResult.Error("Catalog unavailable")
      val plan =
        catalog.resolveTranslationPlan(from, to)
          ?: return@withContext BatchTranslationResult.Error("Language pair ${from.code} -> ${to.code} not installed")
      loadPlanIntoCache(plan)

      val result: Array<String>
      val elapsed =
        measureTimeMillis {
          result = performTranslations(plan, texts)
        }
      Log.d("TranslationService", "bulk translation took ${elapsed}ms")
      val translated =
        result.map { translatedText ->
          val transliterated =
            if (settingsManager.settings.value.enableOutputTransliteration) {
              transliterate(translatedText, to)
            } else {
              null
            }
          TranslatedText(translatedText, transliterated)
        }
      return@withContext BatchTranslationResult.Success(translated)
    }

  suspend fun translate(
    from: Language,
    to: Language,
    text: String,
  ): TranslationResult =
    withContext(Dispatchers.IO) {
      if (from == to) {
        return@withContext TranslationResult.Success(TranslatedText(text, null))
      }
      // numbers don't translate :^)
      if (text.trim().toFloatOrNull() != null) {
        return@withContext TranslationResult.Success(TranslatedText(text, null))
      }

      if (text.isBlank()) {
        return@withContext TranslationResult.Success(TranslatedText("", null))
      }

      val catalog =
        filePathManager.loadCatalog()
          ?: return@withContext TranslationResult.Error("Catalog unavailable")
      val plan =
        catalog.resolveTranslationPlan(from, to)
          ?: return@withContext TranslationResult.Error("Language pair ${from.code} -> ${to.code} not installed")
      loadPlanIntoCache(plan)

      try {
        val result: String
        val elapsed =
          measureTimeMillis {
            result = performTranslations(plan, arrayOf(text)).first()
          }
        Log.d("TranslationService", "Translation took ${elapsed}ms")
        val transliterated =
          if (settingsManager.settings.value.enableOutputTransliteration) {
            transliterate(result, to)
          } else {
            null
          }
        TranslationResult.Success(TranslatedText(result, transliterated))
      } catch (e: Exception) {
        Log.e("TranslationService", "Translation failed", e)
        TranslationResult.Error("Translation failed: ${e.message}")
      }
    }

  suspend fun translateMultipleWithAlignment(
    from: Language,
    to: Language,
    texts: Array<String>,
  ): BatchAlignedTranslationResult =
    withContext(Dispatchers.IO) {
      if (from == to) {
        return@withContext BatchAlignedTranslationResult.Success(
          texts.map { TranslationWithAlignment(it, it, emptyArray()) },
        )
      }
      val catalog =
        filePathManager.loadCatalog()
          ?: return@withContext BatchAlignedTranslationResult.Error("Catalog unavailable")
      val plan =
        catalog.resolveTranslationPlan(from, to)
          ?: return@withContext BatchAlignedTranslationResult.Error(
            "Language pair ${from.code} -> ${to.code} not installed",
          )
      loadPlanIntoCache(plan)

      try {
        val results: Array<TranslationWithAlignment>
        val elapsed =
          measureTimeMillis {
            results = performTranslationsWithAlignment(plan, texts)
          }
        Log.d("TranslationService", "aligned translation took ${elapsed}ms")
        BatchAlignedTranslationResult.Success(results.toList())
      } catch (e: Exception) {
        Log.e("TranslationService", "Aligned translation failed", e)
        BatchAlignedTranslationResult.Error("Translation failed: ${e.message}")
      }
    }

  private fun performTranslations(
    plan: TranslationPlan,
    texts: Array<String>,
  ): Array<String> {
    if (plan.steps.size == 1) {
      return nativeLib.translateMultiple(texts, plan.steps[0].cacheKey)
    } else if (plan.steps.size == 2) {
      return nativeLib.pivotMultiple(plan.steps[0].cacheKey, plan.steps[1].cacheKey, texts)
    }
    return emptyArray()
  }

  private fun performTranslationsWithAlignment(
    plan: TranslationPlan,
    texts: Array<String>,
  ): Array<TranslationWithAlignment> {
    if (plan.steps.size == 1) {
      return nativeLib.translateMultipleWithAlignment(texts, plan.steps[0].cacheKey)
    } else if (plan.steps.size == 2) {
      return nativeLib.pivotMultipleWithAlignment(plan.steps[0].cacheKey, plan.steps[1].cacheKey, texts)
    }
    return emptyArray()
  }

  private fun loadPlanIntoCache(plan: TranslationPlan) {
    plan.steps.forEach { step ->
      Log.d("TranslationService", "Preloading model with key: ${step.cacheKey}")
      nativeLib.loadModelIntoCache(step.config, step.cacheKey)
      Log.d("TranslationService", "Preloaded model ${step.fromCode} -> ${step.toCode} with key: ${step.cacheKey}")
    }
  }

  fun transliterate(
    text: String,
    from: Language,
  ): String? =
    TransliterationService.transliterate(
      text,
      from,
      mucabBinding = mucabBinding,
      japaneseSpaced = settingsManager.settings.value.addSpacesForJapaneseTransliteration,
    )

  suspend fun synthesizeSpeech(
    language: Language,
    text: String,
  ): SpeechSynthesisResult =
    withContext(Dispatchers.IO) {
      if (text.isBlank()) {
        return@withContext SpeechSynthesisResult.Error("Nothing to speak")
      }

      val voiceFiles =
        filePathManager.getTtsVoiceFiles(language)
          ?: return@withContext SpeechSynthesisResult.Error(
            "No TTS voice installed for ${language.displayName}",
          )

      val supportDataPath = filePathManager.getTtsSupportDataRoot()?.absolutePath
      val speechSpeed = settingsManager.settings.value.ttsPlaybackSpeed.coerceIn(0.5f, 2.0f)
      val selectedVoiceName = settingsManager.settings.value.ttsVoiceOverrides[voiceFiles.languageCode]
      val speakerId = voiceFiles.speakerId
      Log.d(
        "TranslationService",
        "Using TTS speakerId=$speakerId voiceName=$selectedVoiceName speechSpeed=$speechSpeed engine=${voiceFiles.engine} language=${voiceFiles.languageCode}",
      )
      val chunkRequests =
        speechBinding.planSpeechChunks(
          engine = voiceFiles.engine,
          modelPath = voiceFiles.model.absolutePath,
          auxPath = voiceFiles.aux.absolutePath,
          supportDataPath = supportDataPath,
          languageCode = voiceFiles.languageCode,
          text = text,
        )
      if (chunkRequests.isNullOrEmpty()) {
        return@withContext SpeechSynthesisResult.Error(
          "Speech synthesis failed for ${language.displayName}",
        )
      }

      SpeechSynthesisResult.Success(
        flow {
          for ((index, chunkRequest) in chunkRequests.withIndex()) {
            currentCoroutineContext().ensureActive()
            Log.d(
              "TranslationService",
              "Speech chunk ${index + 1}/${chunkRequests.size}: synth start isPhonemes=${chunkRequest.isPhonemes} textLen=${chunkRequest.content.length} pauseAfterMs=${chunkRequest.pauseAfterMs}",
            )
            val pcmAudio =
              speechBinding.synthesizePcm(
                engine = voiceFiles.engine,
                modelPath = voiceFiles.model.absolutePath,
                auxPath = voiceFiles.aux.absolutePath,
                supportDataPath = supportDataPath,
                languageCode = voiceFiles.languageCode,
                text = chunkRequest.content,
                speechSpeed = speechSpeed,
                voiceName = selectedVoiceName,
                speakerId = speakerId,
                isPhonemes = chunkRequest.isPhonemes,
              ) ?: throw IllegalStateException(
                "Speech synthesis failed for ${language.displayName}",
              )
            val audioDurationMs = (pcmAudio.pcmSamples.size * 1000L) / pcmAudio.sampleRate
            Log.d(
              "TranslationService",
              "Speech chunk ${index + 1}/${chunkRequests.size}: synth ready samples=${pcmAudio.pcmSamples.size} sampleRate=${pcmAudio.sampleRate} audioMs=$audioDurationMs",
            )
            currentCoroutineContext().ensureActive()
            Log.d("TranslationService", "Speech chunk ${index + 1}/${chunkRequests.size}: emit start")
            emit(pcmAudio)
            Log.d("TranslationService", "Speech chunk ${index + 1}/${chunkRequests.size}: emit returned")

            val silenceChunk =
              chunkRequest.pauseAfterMs?.let { pauseMs ->
                PcmAudio.silence(pcmAudio.sampleRate, pauseMs)
              }
            if (silenceChunk != null) {
              val silenceMs = (silenceChunk.pcmSamples.size * 1000L) / silenceChunk.sampleRate
              Log.d(
                "TranslationService",
                "Speech chunk ${index + 1}/${chunkRequests.size}: silence emit start audioMs=$silenceMs",
              )
              emit(silenceChunk)
              Log.d("TranslationService", "Speech chunk ${index + 1}/${chunkRequests.size}: silence emit returned")
            }
          }
        },
      )
    }

  suspend fun availableTtsVoices(language: Language): List<TtsVoiceOption> =
    withContext(Dispatchers.IO) {
      val voiceFiles = filePathManager.getTtsVoiceFiles(language) ?: return@withContext emptyList()
      val supportDataPath = filePathManager.getTtsSupportDataRoot()?.absolutePath
      speechBinding.listVoices(
        engine = voiceFiles.engine,
        modelPath = voiceFiles.model.absolutePath,
        auxPath = voiceFiles.aux.absolutePath,
        supportDataPath = supportDataPath,
        languageCode = voiceFiles.languageCode,
      ) ?: emptyList()
    }
}

sealed class TranslationResult {
  data class Success(
    val result: TranslatedText,
  ) : TranslationResult()

  data class Error(
    val message: String,
  ) : TranslationResult()
}

sealed class BatchTranslationResult {
  data class Success(
    val result: List<TranslatedText>,
  ) : BatchTranslationResult()

  data class Error(
    val message: String,
  ) : BatchTranslationResult()
}

sealed class BatchAlignedTranslationResult {
  data class Success(
    val results: List<TranslationWithAlignment>,
  ) : BatchAlignedTranslationResult()

  data class Error(
    val message: String,
  ) : BatchAlignedTranslationResult()
}

sealed class SpeechSynthesisResult {
  data class Success(
    val audioChunks: Flow<PcmAudio>,
  ) : SpeechSynthesisResult()

  data class Error(
    val message: String,
  ) : SpeechSynthesisResult()
}
