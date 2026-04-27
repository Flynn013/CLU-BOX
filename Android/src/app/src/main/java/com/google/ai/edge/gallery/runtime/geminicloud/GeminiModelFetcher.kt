/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.runtime.geminicloud

import android.util.Log
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "GeminiModelFetcher"
private const val MODELS_LIST_URL =
  "https://generativelanguage.googleapis.com/v1beta/models?key="

/**
 * Represents a single Gemini model entry returned by the list-models API.
 */
data class GeminiModelInfo(
  /** The short model ID used in API calls, e.g. "gemini-2.0-flash". */
  val modelId: String,
  /** Human-readable display name, e.g. "Gemini 2.0 Flash". */
  val displayName: String,
  /** Longer description from the API. */
  val description: String = "",
)

/**
 * Fetches the list of available Gemini models from the Google Generative Language API.
 *
 * This is a **blocking** call — always invoke it from a background thread or coroutine.
 *
 * @param apiKey A valid Gemini API key.
 * @return A [Result] wrapping the list of [GeminiModelInfo] on success, or an exception on failure.
 */
fun fetchGeminiModels(apiKey: String): Result<List<GeminiModelInfo>> {
  return try {
    val url = URL("$MODELS_LIST_URL${apiKey.trim()}")
    val conn = url.openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.connectTimeout = 15_000
    conn.readTimeout = 30_000

    val responseCode = conn.responseCode
    val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
    val responseText = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    conn.disconnect()

    if (responseCode !in 200..299) {
      Log.e(TAG, "Gemini models list API error $responseCode: $responseText")
      val errorMsg = try {
        JsonParser.parseString(responseText).asJsonObject
          .getAsJsonObject("error")?.get("message")?.asString ?: "HTTP $responseCode"
      } catch (e: Exception) {
        "HTTP $responseCode"
      }
      return Result.failure(Exception(errorMsg))
    }

    val json = JsonParser.parseString(responseText).asJsonObject
    val modelsArray = json.getAsJsonArray("models") ?: return Result.success(emptyList())

    val models = mutableListOf<GeminiModelInfo>()
    for (element in modelsArray) {
      val obj = element.asJsonObject
      // The "name" field is "models/gemini-2.0-flash" — strip the prefix.
      val fullName = obj.get("name")?.asString ?: continue
      val modelId = fullName.removePrefix("models/")

      // Only include models that support generateContent (i.e. chat-capable).
      val supportedMethods = obj.getAsJsonArray("supportedGenerationMethods")
      val supportsGenerate = supportedMethods?.any { it.asString == "generateContent" } ?: false
      if (!supportsGenerate) continue

      val displayName = obj.get("displayName")?.asString ?: modelId
      val description = obj.get("description")?.asString ?: ""

      models.add(GeminiModelInfo(modelId = modelId, displayName = displayName, description = description))
    }

    // Sort: put gemini-2.x and gemini-1.5 first, then others alphabetically.
    val sorted = models.sortedWith(compareByDescending<GeminiModelInfo> {
      when {
        it.modelId.startsWith("gemini-2") -> 2
        it.modelId.startsWith("gemini-1.5") -> 1
        else -> 0
      }
    }.thenBy { it.modelId })

    Log.d(TAG, "Fetched ${sorted.size} Gemini models")
    Result.success(sorted)
  } catch (e: Exception) {
    Log.e(TAG, "Failed to fetch Gemini models", e)
    Result.failure(e)
  }
}
