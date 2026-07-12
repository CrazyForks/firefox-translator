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
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.ServerSocket

/**
 * Exposes the on-device translation engine over the subset of the LibreTranslate
 * HTTP API that maps onto it: POST /translate, POST /detect, GET /languages.
 * Any app that already speaks LibreTranslate can point at this server unchanged.
 *
 * NanoHTTPD serves each request on its own worker thread, so `runBlocking` around
 * the suspending engine calls just parks that worker; the engine's own mutex +
 * worker pool serialises the actual translation.
 */
class LibreTranslateHttpServer(
  hostname: String,
  port: Int,
  private val app: TranslatorApplication,
) : NanoHTTPD(hostname, port) {
  init {
    // Bind with SO_REUSEADDR so a port/interface change restarts cleanly without
    // tripping over the previous socket lingering in TIME_WAIT.
    setServerSocketFactory {
      ServerSocket().apply { reuseAddress = true }
    }
  }

  override fun serve(session: IHTTPSession): Response {
    if (session.method == Method.OPTIONS) return cors(newFixedLengthResponse(Response.Status.OK, MIME_JSON, "{}"))
    return try {
      val response =
        when {
          session.method == Method.POST && session.uri == "/translate" -> handleTranslate(session)
          session.method == Method.POST && session.uri == "/detect" -> handleDetect(session)
          session.method == Method.GET && session.uri == "/languages" -> handleLanguages()
          else -> error(Response.Status.NOT_FOUND, "Not found")
        }
      cors(response)
    } catch (e: Exception) {
      Log.e(TAG, "serve failed", e)
      cors(error(Response.Status.INTERNAL_ERROR, e.message ?: "internal error"))
    }
  }

  private fun handleTranslate(session: IHTTPSession): Response {
    val params = readParams(session)
    val target = params.string("target") ?: return error(Response.Status.BAD_REQUEST, "'target' is required")
    val source = params.string("source") ?: "auto"
    val format = params.string("format") ?: "text"
    val (texts, isArray) = params.q() ?: return error(Response.Status.BAD_REQUEST, "'q' is required")

    val catalog = app.filePathManager.loadCatalog() ?: return error(Response.Status.INTERNAL_ERROR, "catalog unavailable")
    val to = catalog.languageByCode(target) ?: return error(Response.Status.BAD_REQUEST, "target language '$target' not available")
    val available = catalog.languageRows.filter { it.availability.translatorFiles }.map { it.language }

    var detected: Language? = null
    val from: Language =
      if (source == "auto") {
        runBlocking { app.translationCoordinator.detectLanguageRobust(texts.joinToString("\n"), null, available) }
          ?.also { detected = it }
          ?: return error(Response.Status.BAD_REQUEST, "could not detect source language")
      } else {
        catalog.languageByCode(source) ?: return error(Response.Status.BAD_REQUEST, "source language '$source' not available")
      }

    if (from != to && !catalog.canTranslate(from, to)) {
      return error(Response.Status.BAD_REQUEST, "translation ${from.code} -> ${to.code} not available")
    }

    val translated = ArrayList<String>(texts.size)
    for (text in texts) {
      if (from == to) {
        translated.add(text)
        continue
      }
      if (format == "html") {
        translated.add(runBlocking { app.translationService.translateHtmlFragments(from, to, listOf(text)) }.firstOrNull() ?: text)
        continue
      }
      when (val result = runBlocking { app.translationCoordinator.translateText(from, to, text) }) {
        is TranslationResult.Success -> translated.add(result.result.translated)
        is TranslationResult.Error -> return error(Response.Status.INTERNAL_ERROR, result.message)
      }
    }

    val body =
      JSONObject().apply {
        put("translatedText", if (isArray) JSONArray(translated) else translated.first())
        detected?.let { put("detectedLanguage", JSONObject().put("confidence", DETECTED_CONFIDENCE).put("language", it.code)) }
      }
    return json(Response.Status.OK, body)
  }

  private fun handleDetect(session: IHTTPSession): Response {
    val params = readParams(session)
    val (texts, _) = params.q() ?: return error(Response.Status.BAD_REQUEST, "'q' is required")
    val catalog = app.filePathManager.loadCatalog() ?: return error(Response.Status.INTERNAL_ERROR, "catalog unavailable")
    val available = catalog.languageRows.filter { it.availability.translatorFiles }.map { it.language }

    val detected = runBlocking { app.translationCoordinator.detectLanguageRobust(texts.joinToString("\n"), null, available) }
    val body = JSONArray()
    detected?.let { body.put(JSONObject().put("confidence", DETECTED_CONFIDENCE).put("language", it.code)) }
    return json(Response.Status.OK, body.toString())
  }

  private fun handleLanguages(): Response {
    val catalog = app.filePathManager.loadCatalog() ?: return error(Response.Status.INTERNAL_ERROR, "catalog unavailable")
    val languages = catalog.languageRows.filter { it.availability.translatorFiles }.map { it.language }
    val body = JSONArray()
    languages.forEach { lang ->
      val targets = languages.filter { it != lang && catalog.canTranslate(lang, it) }.map { it.code }
      body.put(
        JSONObject()
          .put("code", lang.code)
          .put("name", lang.displayName)
          .put("targets", JSONArray(targets)),
      )
    }
    return json(Response.Status.OK, body.toString())
  }

  private fun readParams(session: IHTTPSession): RequestParams {
    val files = HashMap<String, String>()
    session.parseBody(files)
    val postData = files["postData"]
    val jsonBody = postData?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
    return RequestParams(jsonBody, session.parameters)
  }

  private class RequestParams(
    private val json: JSONObject?,
    private val form: Map<String, List<String>>,
  ) {
    fun string(name: String): String? {
      val fromJson = json?.let { if (it.has(name)) it.optString(name) else null }?.takeIf { it.isNotEmpty() }
      return fromJson ?: form[name]?.firstOrNull()?.takeIf { it.isNotEmpty() }
    }

    // LibreTranslate's `q` is either a single string or an array of strings; the
    // response mirrors that shape. Returns null when `q` is absent.
    fun q(): Pair<List<String>, Boolean>? {
      val fromJson = json?.opt("q")
      when (fromJson) {
        is JSONArray -> return (0 until fromJson.length()).map { fromJson.getString(it) } to true
        is String -> return listOf(fromJson) to false
      }
      val fromForm = form["q"] ?: return null
      if (fromForm.isEmpty()) return null
      return if (fromForm.size > 1) fromForm to true else listOf(fromForm.first()) to false
    }
  }

  private fun json(
    status: Response.Status,
    body: JSONObject,
  ): Response = newFixedLengthResponse(status, MIME_JSON, body.toString())

  private fun json(
    status: Response.Status,
    body: String,
  ): Response = newFixedLengthResponse(status, MIME_JSON, body)

  private fun error(
    status: Response.Status,
    message: String,
  ): Response = newFixedLengthResponse(status, MIME_JSON, JSONObject().put("error", message).toString())

  private fun cors(response: Response): Response =
    response.apply {
      addHeader("Access-Control-Allow-Origin", "*")
      addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
      addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
    }

  companion object {
    private const val TAG = "LibreTranslateServer"
    private const val MIME_JSON = "application/json"

    // The native robust detector returns a single best language code without a
    // score, so we report full confidence to satisfy the LibreTranslate schema.
    private const val DETECTED_CONFIDENCE = 100.0
  }
}
