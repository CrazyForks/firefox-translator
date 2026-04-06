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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LangAvailability(
  val hasFromEnglish: Boolean,
  val hasToEnglish: Boolean,
  val ocrFiles: Boolean,
  val dictionaryFiles: Boolean,
) {
  val translatorFiles: Boolean get() = hasFromEnglish || hasToEnglish
}

data class LanguageAvailabilityState(
  val hasLanguages: Boolean = false,
  val availableLanguageMap: Map<Language, LangAvailability> = emptyMap(),
  val isChecking: Boolean = true,
)

fun canSwapLanguages(
  from: Language,
  to: Language,
): Boolean {
  val toCanBeSource = to.isEnglish || to.toEnglish != null
  val fromCanBeTarget = from.isEnglish || from.fromEnglish != null
  return toCanBeSource && fromCanBeTarget
}

fun canSwapLanguages(
  from: Language,
  to: Language,
  availableLanguages: Map<Language, LangAvailability>,
): Boolean {
  val toCanBeSource = to.isEnglish || availableLanguages[to]?.hasToEnglish == true
  val fromCanBeTarget = from.isEnglish || availableLanguages[from]?.hasFromEnglish == true
  return toCanBeSource && fromCanBeTarget
}

fun isDictionaryAvailable(
  filePathManager: FilePathManager,
  language: Language,
): Boolean = filePathManager.getDictionaryFile(language).exists()

fun isDictionaryAvailable(
  dictFiles: Set<String>,
  language: Language,
): Boolean = "${language.dictionaryCode}.dict" in dictFiles

fun missingFilesFrom(
  dataFiles: Set<String>,
  lang: Language,
): Pair<Long, List<ModelFile>> {
  val files = lang.fromEnglish?.allFiles() ?: return Pair(0L, emptyList())
  val missing = files.filter { it.name !in dataFiles }
  return Pair(missing.sumOf { it.sizeBytes }, missing)
}

fun missingFilesTo(
  dataFiles: Set<String>,
  lang: Language,
): Pair<Long, List<ModelFile>> {
  val files = lang.toEnglish?.allFiles() ?: return Pair(0L, emptyList())
  val missing = files.filter { it.name !in dataFiles }
  return Pair(missing.sumOf { it.sizeBytes }, missing)
}

fun missingFiles(
  dataFiles: Set<String>,
  lang: Language,
): Pair<Long, List<ModelFile>> {
  val (toSize, toFiles) = missingFilesTo(dataFiles, lang)
  val (fromSize, fromFiles) = missingFilesFrom(dataFiles, lang)
  return Pair(toSize + fromSize, toFiles + fromFiles)
}

class LanguageStateManager(
  private val scope: CoroutineScope,
  private val filePathManager: FilePathManager,
  downloadEvents: SharedFlow<DownloadEvent>? = null,
) {
  private val _languageState = MutableStateFlow(LanguageAvailabilityState())
  val languageState: StateFlow<LanguageAvailabilityState> = _languageState.asStateFlow()

  private val _languageIndex = MutableStateFlow<LanguageIndex?>(null)
  val languageIndex: StateFlow<LanguageIndex?> = _languageIndex.asStateFlow()

  private val _dictionaryIndex = MutableStateFlow<DictionaryIndex?>(null)
  val dictionaryIndex: StateFlow<DictionaryIndex?> = _dictionaryIndex.asStateFlow()

  private val _dictionaryIndexVersion = MutableStateFlow(0)
  val dictionaryIndexVersion: StateFlow<Int> = _dictionaryIndexVersion.asStateFlow()

  private val _languageIndexVersion = MutableStateFlow(0)
  val languageIndexVersion: StateFlow<Int> = _languageIndexVersion.asStateFlow()

  private val _fileEvents = MutableSharedFlow<FileEvent>()
  val fileEvents: SharedFlow<FileEvent> = _fileEvents.asSharedFlow()

  private var downloadEventsJob: kotlinx.coroutines.Job? = null

  fun languageByCode(code: String): Language? = _languageIndex.value?.languageByCode(code)

  init {
    if (downloadEvents != null) {
      connectToDownloadEvents(downloadEvents)
    }
    loadLanguageIndex()
    loadDictionaryIndex()
    loadMucabFile()
  }

  private fun loadLanguageIndex() {
    scope.launch {
      withContext(Dispatchers.IO) {
        val index = filePathManager.loadLanguageIndex()
        _languageIndex.value = index
        Log.i("LanguageStateManager", "Language index loaded from file: ${index != null}")
      }
      refreshLanguageAvailability()
    }
  }

  fun connectToDownloadEvents(downloadEvents: SharedFlow<DownloadEvent>) {
    downloadEventsJob?.cancel()
    downloadEventsJob =
      scope.launch {
        downloadEvents.collect { event ->
          when (event) {
            is DownloadEvent.NewTranslationAvailable -> {
              addTranslationLanguage(event.language)
              if (event.language.extraFiles.contains("mucab.bin")) {
                loadMucabFile()
              }
            }

            is DownloadEvent.NewDictionaryAvailable -> {
              addDictionaryLanguage(event.language)
              _fileEvents.emit(FileEvent.DictionaryAvailable(event.language))
            }

            is DownloadEvent.DictionaryIndexDownloaded -> {
              _dictionaryIndex.value = event.index
              _dictionaryIndexVersion.value++
              Log.i("LanguageStateManager", "Dictionary index downloaded: ${event.index}")
            }

            is DownloadEvent.LanguageIndexDownloaded -> {
              _languageIndex.value = event.index
              _languageIndexVersion.value++
              refreshLanguageAvailability()
            }

            is DownloadEvent.DownloadError -> {
              Log.w("LanguageStateManager", "Download error: ${event.message}")
              _fileEvents.emit(FileEvent.Error(event.message))
            }
          }
        }
      }
  }

  fun refreshLanguageAvailability() {
    scope.launch {
      _languageState.value = _languageState.value.copy(isChecking = true)

      val languages = _languageIndex.value?.languages ?: return@launch

      Log.i("LanguageStateManager", "Refreshing language availability")
      val availabilityMap =
        withContext(Dispatchers.IO) {
          Log.d("LanguageStateManager", "listing")
          val dataFiles =
            filePathManager
              .getDataDir()
              .listFiles()
              ?.map { it.name }
              ?.toSet() ?: emptySet()
          val tessFiles =
            filePathManager
              .getTesseractDataDir()
              .listFiles()
              ?.map { it.name }
              ?.toSet() ?: emptySet()
          val dictFiles =
            filePathManager
              .getDictionariesDir()
              .listFiles()
              ?.map { it.name }
              ?.toSet() ?: emptySet()
          Log.d("LanguageStateManager", "listed")

          buildMap {
            languages.forEach { lang ->
              if (lang.isEnglish) {
                put(
                  lang,
                  LangAvailability(
                    hasFromEnglish = true,
                    hasToEnglish = true,
                    ocrFiles = true,
                    dictionaryFiles = isDictionaryAvailable(dictFiles, lang),
                  ),
                )
              } else {
                val fromAvailable = lang.fromEnglish != null && missingFilesFrom(dataFiles, lang).second.isEmpty()
                val toAvailable = lang.toEnglish != null && missingFilesTo(dataFiles, lang).second.isEmpty()
                val isOcrAvailable = "${lang.tessName}.traineddata" in tessFiles
                put(
                  lang,
                  LangAvailability(
                    hasFromEnglish = fromAvailable,
                    hasToEnglish = toAvailable,
                    ocrFiles = isOcrAvailable,
                    dictionaryFiles = isDictionaryAvailable(dictFiles, lang),
                  ),
                )
              }
            }
            Log.d("LanguageStateManager", "mapped")
          }
        }

      val hasLanguages = availabilityMap.any { !it.key.isEnglish && it.value.translatorFiles }
      Log.i("LanguageStateManager", "hasLanguages = $hasLanguages")
      _languageState.value =
        LanguageAvailabilityState(
          hasLanguages = hasLanguages,
          availableLanguageMap = availabilityMap,
          isChecking = false,
        )
    }
  }

  private fun addTranslationLanguage(language: Language) {
    val currentState = _languageState.value
    val updatedLanguageMap = currentState.availableLanguageMap.toMutableMap()
    val isDictAvail = isDictionaryAvailable(filePathManager, language)
    updatedLanguageMap[language] =
      LangAvailability(
        hasFromEnglish = language.fromEnglish != null,
        hasToEnglish = language.toEnglish != null,
        ocrFiles = true,
        dictionaryFiles = isDictAvail,
      )

    val hasLanguages = _languageState.value.hasLanguages || !language.isEnglish

    _languageState.value =
      currentState.copy(
        hasLanguages = hasLanguages,
        availableLanguageMap = updatedLanguageMap,
      )

    Log.i("LanguageStateManager", "Added translation language: ${language.displayName}")
  }

  private fun addDictionaryLanguage(language: Language) {
    val currentState = _languageState.value
    val updatedLanguageMap = currentState.availableLanguageMap.toMutableMap()
    updateDictionaryAvailability(updatedLanguageMap, language, available = true)

    _languageState.value =
      currentState.copy(
        availableLanguageMap = updatedLanguageMap,
      )

    Log.i("LanguageStateManager", "Added dict language: ${language.displayName}")
  }

  fun deleteDict(language: Language) {
    val currentState = _languageState.value
    val updatedLanguageMap = currentState.availableLanguageMap.toMutableMap()
    updateDictionaryAvailability(updatedLanguageMap, language, available = false)

    _languageState.value =
      currentState.copy(
        availableLanguageMap = updatedLanguageMap,
      )

    val dictionaryFile = filePathManager.getDictionaryFile(language)
    if (dictionaryFile.exists() && dictionaryFile.delete()) {
      Log.i("LanguageStateManager", "Deleted dictionary file: ${dictionaryFile.name}")
    }

    scope.launch {
      _fileEvents.emit(FileEvent.DictionaryDeleted(language))
    }

    Log.i("LanguageStateManager", "Removed dictionary for language: ${language.displayName}")
  }

  fun deleteLanguage(language: Language) {
    val currentState = _languageState.value
    val updatedLanguageMap = currentState.availableLanguageMap.toMutableMap()
    updatedLanguageMap[language] = LangAvailability(hasFromEnglish = false, hasToEnglish = false, ocrFiles = false, dictionaryFiles = false)

    val hasLanguages = updatedLanguageMap.any { !it.key.isEnglish && it.value.translatorFiles }

    _languageState.value =
      currentState.copy(
        hasLanguages = hasLanguages,
        availableLanguageMap = updatedLanguageMap,
      )

    val hasSharedDictionaryLanguage =
      updatedLanguageMap.any {
        it.key != language &&
          it.key.dictionaryCode == language.dictionaryCode &&
          it.value.translatorFiles
      }
    filePathManager.deleteLanguageFiles(language, deleteDictionary = !hasSharedDictionaryLanguage)
    scope.launch {
      _fileEvents.emit(FileEvent.LanguageDeleted(language))
    }
    Log.i("LanguageStateManager", "Removed language: ${language.displayName}")
  }

  fun getFirstAvailableFromLanguage(excluding: Language? = null): Language? {
    val state = _languageState.value
    return state.availableLanguageMap
      .filterNot { it.key == excluding }
      .filter { it.value.translatorFiles }
      .keys
      .firstOrNull()
  }

  private fun loadDictionaryIndex() {
    scope.launch {
      val index =
        withContext(Dispatchers.IO) {
          filePathManager.loadDictionaryIndex()
        }
      _dictionaryIndex.value = index
      if (index != null) {
        _fileEvents.emit(FileEvent.DictionaryIndexLoaded(index))
      }
      Log.i("LanguageStateManager", "Dictionary index loaded from file: ${index != null}")
    }
  }

  private fun loadMucabFile() {
    scope.launch {
      withContext(Dispatchers.IO) {
        val mucabFile = filePathManager.getMucabFile()
        if (mucabFile.exists()) {
          val binding = MucabBinding()
          val success = binding.open(mucabFile.absolutePath)
          if (success) {
            _fileEvents.emit(FileEvent.MucabFileLoaded(binding))
            Log.i("LanguageStateManager", "Mucab file loaded successfully")
          } else {
            Log.w("LanguageStateManager", "Failed to open mucab file")
          }
        } else {
          Log.i("LanguageStateManager", "Mucab file not found")
        }
      }
    }
  }

  private fun updateDictionaryAvailability(
    languageMap: MutableMap<Language, LangAvailability>,
    language: Language,
    available: Boolean,
  ) {
    (_languageIndex.value?.languages ?: return)
      .filter { it.dictionaryCode == language.dictionaryCode }
      .forEach { sharedLanguage ->
        val existingAvailability = languageMap[sharedLanguage]
        languageMap[sharedLanguage] =
          LangAvailability(
            hasFromEnglish = existingAvailability?.hasFromEnglish ?: false,
            hasToEnglish = existingAvailability?.hasToEnglish ?: false,
            ocrFiles = existingAvailability?.ocrFiles ?: false,
            dictionaryFiles = available,
          )
      }
  }
}
