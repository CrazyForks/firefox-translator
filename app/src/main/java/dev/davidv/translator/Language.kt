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

import org.json.JSONObject

data class ModelFile(
  val name: String,
  val sizeBytes: Long,
  val path: String,
)

data class LanguageDirection(
  val model: ModelFile,
  val srcVocab: ModelFile,
  val tgtVocab: ModelFile,
  val lex: ModelFile,
) {
  fun allFiles(): List<ModelFile> = listOf(model, srcVocab, tgtVocab, lex).distinctBy { it.name }

  fun totalSize(): Long = allFiles().sumOf { it.sizeBytes }
}

data class Language(
  val code: String,
  val displayName: String,
  val shortDisplayName: String,
  val tessName: String,
  val script: String,
  val dictionaryCode: String,
  val tessdataSizeBytes: Long,
  val toEnglish: LanguageDirection?,
  val fromEnglish: LanguageDirection?,
  val extraFiles: List<String>,
) {
  val tessFilename: String get() = "$tessName.traineddata"
  val sizeBytes: Long get() = (toEnglish?.totalSize() ?: 0) + (fromEnglish?.totalSize() ?: 0) + tessdataSizeBytes
  val isEnglish: Boolean get() = code == "en"

  override fun equals(other: Any?): Boolean = other is Language && code == other.code

  override fun hashCode(): Int = code.hashCode()

  override fun toString(): String = "Language($code)"
}

data class LanguageIndex(
  val languages: List<Language>,
  val updatedAt: Long,
  val version: Int,
  val translationModelsBaseUrl: String,
  val tesseractModelsBaseUrl: String,
  val dictionaryBaseUrl: String,
  val dictionaryVersion: Int,
) {
  private val byCode: Map<String, Language> = languages.associateBy { it.code }
  val english: Language get() = byCode.getValue("en")
  val downloadable: List<Language> get() = languages.filter { !it.isEnglish }

  fun languageByCode(code: String): Language? = byCode[code]
}

fun parseLanguageIndex(json: String): LanguageIndex {
  val root = JSONObject(json)
  val languagesArray = root.getJSONArray("languages")
  val languages =
    (0 until languagesArray.length()).map { i ->
      val lang = languagesArray.getJSONObject(i)
      Language(
        code = lang.getString("code"),
        displayName = lang.getString("name"),
        shortDisplayName = lang.getString("shortName"),
        tessName = lang.getString("tessName"),
        script = lang.getString("script"),
        dictionaryCode = lang.getString("dictionaryCode"),
        tessdataSizeBytes = lang.getLong("tessdataSizeBytes"),
        toEnglish = if (lang.isNull("toEnglish")) null else parseDirection(lang.getJSONObject("toEnglish")),
        fromEnglish = if (lang.isNull("fromEnglish")) null else parseDirection(lang.getJSONObject("fromEnglish")),
        extraFiles =
          buildList {
            val arr = lang.optJSONArray("extraFiles")
            if (arr != null) {
              for (j in 0 until arr.length()) add(arr.getString(j))
            }
          },
      )
    }
  return LanguageIndex(
    languages = languages,
    updatedAt = root.getLong("updatedAt"),
    version = root.getInt("version"),
    translationModelsBaseUrl = root.getString("translationModelsBaseUrl"),
    tesseractModelsBaseUrl = root.getString("tesseractModelsBaseUrl"),
    dictionaryBaseUrl = root.getString("dictionaryBaseUrl"),
    dictionaryVersion = root.getInt("dictionaryVersion"),
  )
}

private fun parseDirection(obj: JSONObject): LanguageDirection =
  LanguageDirection(
    model = parseModelFile(obj.getJSONObject("model")),
    srcVocab = parseModelFile(obj.getJSONObject("srcVocab")),
    tgtVocab = parseModelFile(obj.getJSONObject("tgtVocab")),
    lex = parseModelFile(obj.getJSONObject("lex")),
  )

private fun parseModelFile(obj: JSONObject): ModelFile =
  ModelFile(
    name = obj.getString("name"),
    sizeBytes = obj.getLong("sizeBytes"),
    path = obj.getString("path"),
  )
