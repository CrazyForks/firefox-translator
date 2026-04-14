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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

class SpeechService(
  private val settingsManager: SettingsManager,
  private val filePathManager: FilePathManager,
) {
  private val speechBinding = SpeechBinding()

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
        "SpeechService",
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
              "SpeechService",
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
              "SpeechService",
              "Speech chunk ${index + 1}/${chunkRequests.size}: synth ready samples=${pcmAudio.pcmSamples.size} sampleRate=${pcmAudio.sampleRate} audioMs=$audioDurationMs",
            )
            currentCoroutineContext().ensureActive()
            emit(pcmAudio)

            val silenceChunk =
              chunkRequest.pauseAfterMs?.let { pauseMs ->
                PcmAudio.silence(pcmAudio.sampleRate, pauseMs)
              }
            if (silenceChunk != null) {
              emit(silenceChunk)
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

sealed class SpeechSynthesisResult {
  data class Success(
    val audioChunks: Flow<PcmAudio>,
  ) : SpeechSynthesisResult()

  data class Error(
    val message: String,
  ) : SpeechSynthesisResult()
}
