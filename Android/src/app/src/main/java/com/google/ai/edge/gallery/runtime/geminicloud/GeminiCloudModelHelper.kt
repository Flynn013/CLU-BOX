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

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.ToolSet
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.valueParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "GeminiCloudModelHelper"
private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
private const val GEMINI_MODEL = "gemini-2.0-flash"
private const val MAX_FUNCTION_CALL_ROUNDS = 10

/**
 * Holds the state for a Gemini Cloud "session" — the conversation history,
 * tools, system prompt, and any in-flight inference job.
 */
data class GeminiCloudInstance(
  var conversationHistory: MutableList<JsonObject> = mutableListOf(),
  var systemInstruction: String? = null,
  var toolDeclarations: JsonArray? = null,
  var toolSet: ToolSet? = null,
  var inferenceJob: Job? = null,
  val cancelled: AtomicBoolean = AtomicBoolean(false),
)

/**
 * [LlmModelHelper] implementation that routes inference to the Google Gemini
 * REST API (`generativelanguage.googleapis.com`). The same [ToolSet] used by
 * on-device models is mapped to Gemini's native Function Calling JSON schema,
 * so `functionCall` payloads returned by Gemini are dispatched to the exact
 * same Kotlin execution blocks.
 */
object GeminiCloudModelHelper : LlmModelHelper {

  private val cleanUpListeners: ConcurrentHashMap<String, CleanUpListener> = ConcurrentHashMap()
  private val gson = Gson()

  // ──────────────────────────────────────────────────────────────────────────
  // LlmModelHelper interface
  // ──────────────────────────────────────────────────────────────────────────

  override fun initialize(
    context: Context,
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    onDone: (String) -> Unit,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    coroutineScope: CoroutineScope?,
  ) {
    val apiKey = GeminiApiKeyStore.getApiKey(context)
    if (apiKey == null) {
      onDone("Gemini API key not configured. Please set your API key first.")
      return
    }
    // Cache the API key for use during inference calls (avoids storing Context).
    cachedApiKey = apiKey

    // Build Gemini function declarations from the ToolSet.
    var toolDeclarations: JsonArray? = null
    var toolSet: ToolSet? = null
    if (tools.isNotEmpty()) {
      val provider = tools.firstOrNull()
      if (provider != null) {
        // The ToolProvider wraps a ToolSet — extract it via the property.
        try {
          val tsField = provider.javaClass.declaredFields.find { ToolSet::class.java.isAssignableFrom(it.type) }
          if (tsField != null) {
            tsField.isAccessible = true
            toolSet = tsField.get(provider) as? ToolSet
          }
        } catch (e: Exception) {
          Log.w(TAG, "Could not extract ToolSet from ToolProvider", e)
        }
        if (toolSet != null) {
          toolDeclarations = buildFunctionDeclarations(toolSet)
        }
      }
    }

    val systemText = systemInstruction?.let { buildSystemText(it) }

    val instance = GeminiCloudInstance(
      systemInstruction = systemText,
      toolDeclarations = toolDeclarations,
      toolSet = toolSet,
    )
    model.instance = instance
    Log.d(TAG, "Gemini Cloud model initialized (tools: ${toolDeclarations?.size() ?: 0})")
    onDone("")
  }

  override fun resetConversation(
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
  ) {
    val instance = model.instance as? GeminiCloudInstance ?: return
    instance.conversationHistory.clear()
    instance.cancelled.set(false)
    if (systemInstruction != null) {
      instance.systemInstruction = buildSystemText(systemInstruction)
    }
    Log.d(TAG, "Conversation reset")
  }

  override fun cleanUp(model: Model, onDone: () -> Unit) {
    val instance = model.instance as? GeminiCloudInstance
    instance?.inferenceJob?.cancel()
    instance?.conversationHistory?.clear()
    model.instance = null
    cleanUpListeners.remove(model.name)?.invoke()
    onDone()
  }

  override fun runInference(
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    onError: (message: String) -> Unit,
    images: List<Bitmap>,
    audioClips: List<ByteArray>,
    coroutineScope: CoroutineScope?,
    extraContext: Map<String, String>?,
  ) {
    val instance = model.instance as? GeminiCloudInstance
    if (instance == null) {
      onError("Gemini Cloud model not initialized")
      return
    }

    cleanUpListeners[model.name] = cleanUpListener
    instance.cancelled.set(false)

    val scope = coroutineScope ?: CoroutineScope(Dispatchers.IO)
    instance.inferenceJob = scope.launch(Dispatchers.IO) {
      try {
        // Add user message to history.
        val userContent = JsonObject().apply {
          addProperty("role", "user")
          add("parts", JsonArray().apply {
            add(JsonObject().apply { addProperty("text", input) })
          })
        }
        instance.conversationHistory.add(userContent)

        // Inference loop — handles multi-round function calling.
        var round = 0
        while (round < MAX_FUNCTION_CALL_ROUNDS) {
          if (instance.cancelled.get()) {
            cleanUpListener()
            return@launch
          }

          round++

          val responseText = callGeminiApi(model, instance)
          if (instance.cancelled.get()) {
            cleanUpListener()
            return@launch
          }

          // Parse the response.
          val responseJson = try {
            JsonParser.parseString(responseText).asJsonObject
          } catch (e: Exception) {
            onError("Failed to parse Gemini response: ${e.message}")
            cleanUpListener()
            return@launch
          }

          // Check for errors from the API.
          if (responseJson.has("error")) {
            val errorMsg = responseJson.getAsJsonObject("error")
              ?.get("message")?.asString ?: "Unknown Gemini API error"
            onError(errorMsg)
            cleanUpListener()
            return@launch
          }

          val candidates = responseJson.getAsJsonArray("candidates")
          if (candidates == null || candidates.size() == 0) {
            onError("Gemini returned no candidates")
            cleanUpListener()
            return@launch
          }

          val content = candidates[0].asJsonObject
            .getAsJsonObject("content")
          val parts = content?.getAsJsonArray("parts")
          if (parts == null || parts.size() == 0) {
            resultListener("", true, null)
            cleanUpListener()
            return@launch
          }

          // Add model response to history.
          instance.conversationHistory.add(content)

          // Check if any part is a function call.
          // ── Auto-Heal: wrap function-call extraction in try-catch ────
          // If the model returns a malformed functionCall (missing fields,
          // bad JSON structure, etc.) we inject a system error into the
          // conversation history and let the inference loop continue so
          // the model can self-correct on the next round.
          val functionCallPart = try {
            parts.firstOrNull { it.asJsonObject.has("functionCall") }
          } catch (e: Exception) {
            Log.w(TAG, "Auto-Heal: failed to inspect parts for functionCall", e)
            null
          }

          if (functionCallPart != null) {
            try {
              // Execute the function call.
              val fc = functionCallPart.asJsonObject.getAsJsonObject("functionCall")
              val fnName = fc.get("name").asString
              val fnArgs = fc.getAsJsonObject("args") ?: JsonObject()

              Log.d(TAG, "Function call: $fnName($fnArgs)")

              val result = executeFunctionCall(instance.toolSet, fnName, fnArgs)

              // Add function response to history.
              val functionResponse = JsonObject().apply {
                addProperty("role", "function")
                add("parts", JsonArray().apply {
                  add(JsonObject().apply {
                    add("functionResponse", JsonObject().apply {
                      addProperty("name", fnName)
                      add("response", JsonParser.parseString(gson.toJson(result)))
                    })
                  })
                })
              }
              instance.conversationHistory.add(functionResponse)

              // Emit a status update so the user sees progress.
              resultListener("", false, null)
              continue
            } catch (e: Exception) {
              // ── Auto-Heal: malformed tool call recovery ──────────────
              // Do NOT crash the loop. Inject a system error message into
              // the conversation history so the model can self-correct.
              Log.e(TAG, "Auto-Heal: malformed function call — injecting error and retrying", e)
              val errorMessage = "[System Error: Malformed tool call. Invalid JSON syntax " +
                "or missing fields. Exception: ${e.message}. Correct your formatting and try again.]"
              val errorContent = JsonObject().apply {
                addProperty("role", "user")
                add("parts", JsonArray().apply {
                  add(JsonObject().apply { addProperty("text", errorMessage) })
                })
              }
              instance.conversationHistory.add(errorContent)
              resultListener("", false, null)
              continue
            }
          }

          // No function call — extract text and deliver it.
          val textParts = parts.filter { it.asJsonObject.has("text") }
          val fullText = textParts.joinToString("") {
            it.asJsonObject.get("text").asString
          }
          resultListener(fullText, true, null)
          cleanUpListener()
          return@launch
        }

        // Exhausted function-call rounds.
        resultListener("[Max function call rounds reached]", true, null)
        cleanUpListener()
      } catch (e: Exception) {
        Log.e(TAG, "Inference error", e)
        onError(e.message ?: "Unknown error during Gemini inference")
        cleanUpListener()
      }
    }
  }

  override fun stopResponse(model: Model) {
    val instance = model.instance as? GeminiCloudInstance ?: return
    instance.cancelled.set(true)
    instance.inferenceJob?.cancel()
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Gemini REST API communication
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Calls the Gemini generateContent endpoint with the full conversation
   * history and returns the raw JSON response body.
   */
  private fun callGeminiApi(model: Model, instance: GeminiCloudInstance): String {
    val context = (model.instance as? GeminiCloudInstance) ?: instance
    // We need the Android context to read the API key — pass it through model config.
    // The API key was validated at initialize() time. We read it from the store
    // using the application context cached in the instance.
    // Since we don't store Context in the instance (to avoid leaks), we extract
    // the key once at init and refresh here by reading it from the field
    // set on the model.
    val apiKey = getApiKeyFromModel(model) ?: throw IllegalStateException("API key missing")

    val url = URL("$API_BASE/$GEMINI_MODEL:generateContent?key=$apiKey")
    val requestBody = buildRequestBody(instance)

    val conn = url.openConnection() as HttpURLConnection
    try {
      conn.requestMethod = "POST"
      conn.setRequestProperty("Content-Type", "application/json")
      conn.doOutput = true
      conn.connectTimeout = 30_000
      conn.readTimeout = 120_000

      OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
        writer.write(requestBody)
      }

      val responseCode = conn.responseCode
      val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
      val responseText = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use {
        it.readText()
      }

      if (responseCode !in 200..299) {
        Log.e(TAG, "Gemini API error $responseCode: $responseText")
      }
      return responseText
    } finally {
      conn.disconnect()
    }
  }

  /**
   * Builds the JSON request body for the Gemini generateContent API.
   */
  private fun buildRequestBody(instance: GeminiCloudInstance): String {
    val body = JsonObject()

    // System instruction.
    instance.systemInstruction?.let { sysText ->
      body.add("system_instruction", JsonObject().apply {
        add("parts", JsonArray().apply {
          add(JsonObject().apply { addProperty("text", sysText) })
        })
      })
    }

    // Conversation contents.
    val contents = JsonArray()
    for (msg in instance.conversationHistory) {
      contents.add(msg)
    }
    body.add("contents", contents)

    // Tools (function declarations).
    instance.toolDeclarations?.let { decls ->
      if (decls.size() > 0) {
        body.add("tools", JsonArray().apply {
          add(JsonObject().apply {
            add("function_declarations", decls)
          })
        })
      }
    }

    // Generation config.
    body.add("generationConfig", JsonObject().apply {
      addProperty("temperature", 1.0)
      addProperty("topP", 0.95)
      addProperty("topK", 64)
      addProperty("maxOutputTokens", 8192)
    })

    return gson.toJson(body)
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Tool / Function Calling bridge
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Builds the `function_declarations` JSON array from a [ToolSet] instance
   * by reflecting on its `@Tool`-annotated methods and their `@ToolParam`
   * parameters. This maps the existing tool catalog to Gemini's native
   * Function Calling schema.
   */
  private fun buildFunctionDeclarations(toolSet: ToolSet): JsonArray {
    val declarations = JsonArray()

    val klass = toolSet::class
    for (fn in klass.memberFunctions) {
      val toolAnnotation = fn.findAnnotation<com.google.ai.edge.litertlm.Tool>() ?: continue

      val decl = JsonObject().apply {
        addProperty("name", fn.name)
        addProperty("description", toolAnnotation.description)

        val params = JsonObject()
        params.addProperty("type", "OBJECT")
        val properties = JsonObject()
        val required = JsonArray()

        // Skip the first parameter (this/receiver).
        for (param in fn.valueParameters) {
          val paramAnnotation = param.findAnnotation<com.google.ai.edge.litertlm.ToolParam>()
          val paramName = param.name ?: continue
          properties.add(paramName, JsonObject().apply {
            addProperty("type", "STRING")
            if (paramAnnotation != null) {
              addProperty("description", paramAnnotation.description)
            }
          })
          if (!param.isOptional) {
            required.add(paramName)
          }
        }

        params.add("properties", properties)
        if (required.size() > 0) {
          params.add("required", required)
        }
        add("parameters", params)
      }
      declarations.add(decl)
    }

    Log.d(TAG, "Built ${declarations.size()} function declarations from ToolSet")
    return declarations
  }

  /**
   * Dispatches a Gemini `functionCall` to the matching `@Tool` method on
   * the [ToolSet]. Arguments are extracted from [args] by parameter name.
   */
  @Suppress("UNCHECKED_CAST")
  private fun executeFunctionCall(
    toolSet: ToolSet?,
    functionName: String,
    args: JsonObject,
  ): Map<String, Any> {
    if (toolSet == null) {
      return mapOf("error" to "No tool set available", "status" to "failed")
    }

    val klass = toolSet::class
    val fn = klass.memberFunctions.find { it.name == functionName }
    if (fn == null) {
      return mapOf("error" to "Unknown function: $functionName", "status" to "failed")
    }

    return try {
      val paramValues = mutableListOf<Any?>(toolSet) // receiver
      for (param in fn.valueParameters) {
        val paramName = param.name ?: ""
        val value = if (args.has(paramName)) {
          args.get(paramName).asString
        } else {
          "" // Default to empty string for missing params.
        }
        paramValues.add(value)
      }
      val result = fn.call(*paramValues.toTypedArray())
      when (result) {
        is Map<*, *> -> result as Map<String, Any>
        else -> mapOf("result" to (result?.toString() ?: "null"), "status" to "succeeded")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Function call '$functionName' failed", e)
      mapOf("error" to (e.cause?.message ?: e.message ?: "Unknown error"), "status" to "failed")
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Helpers
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Extracts a plain-text system instruction from a [Contents] object.
   *
   * [Contents.of] wraps a plain-text string, so we attempt to extract the
   * text via a `text` property. Falls back to `toString()` if the property
   * is unavailable (e.g. obfuscated builds).
   */
  @Suppress("UNCHECKED_CAST")
  private fun buildSystemText(contents: Contents): String {
    return try {
      val prop = contents::class.memberProperties.find { it.name == "text" }
      if (prop != null) {
        (prop as? KProperty1<Contents, *>)?.get(contents)?.toString() ?: contents.toString()
      } else {
        contents.toString()
      }
    } catch (e: Exception) {
      Log.w(TAG, "Could not extract text from Contents, falling back to toString()", e)
      contents.toString()
    }
  }

  /**
   * Retrieves the Gemini API key. Since we cannot store Android Context in
   * the instance, we stash the key in a thread-safe field during initialize().
   */
  private var cachedApiKey: String? = null

  fun cacheApiKey(context: Context) {
    cachedApiKey = GeminiApiKeyStore.getApiKey(context)
  }

  private fun getApiKeyFromModel(model: Model): String? {
    return cachedApiKey
  }
}
