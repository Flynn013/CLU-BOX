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

package com.google.ai.edge.gallery.runtime.manualapi

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

private const val TAG = "ManualApiModelHelper"
private const val MAX_FUNCTION_CALL_ROUNDS = 10

/**
 * Session state for a BESPOKE (manual API) model instance.
 */
data class ManualApiInstance(
  val apiEndpoint: String,
  val apiKey: String,
  var conversationHistory: MutableList<JsonObject> = mutableListOf(),
  var systemInstruction: String? = null,
  var toolDeclarations: JsonArray? = null,
  var toolSet: ToolSet? = null,
  var inferenceJob: Job? = null,
  val cancelled: AtomicBoolean = AtomicBoolean(false),
)

/**
 * [LlmModelHelper] implementation for BESPOKE (manually-added API) models.
 *
 * Uses the Gemini-compatible REST API format. The endpoint URL and API key
 * are stored per-model (endpoint in [Model.apiEndpoint], key in
 * [ManualApiKeyStore]).
 *
 * The same [ToolSet] used by on-device models is mapped to the native
 * Function Calling JSON schema so that skills work identically.
 */
object ManualApiModelHelper : LlmModelHelper {

  private val cleanUpListeners: ConcurrentHashMap<String, CleanUpListener> = ConcurrentHashMap()
  private val gson = Gson()

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
    val apiKey = ManualApiKeyStore.getApiKey(context, model.name)
    if (apiKey == null) {
      onDone("API key not configured for model '${model.name}'. Please set your key in VENDING_MACHINE.")
      return
    }
    val endpoint = model.apiEndpoint
    if (endpoint.isBlank()) {
      onDone("No API endpoint configured for model '${model.name}'.")
      return
    }

    // Build Gemini function declarations from the ToolSet.
    var toolDeclarations: JsonArray? = null
    var toolSet: ToolSet? = null
    if (tools.isNotEmpty()) {
      val provider = tools.firstOrNull()
      if (provider != null) {
        try {
          val tsField = provider.javaClass.declaredFields.find {
            ToolSet::class.java.isAssignableFrom(it.type)
          }
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

    val instance = ManualApiInstance(
      apiEndpoint = endpoint,
      apiKey = apiKey,
      systemInstruction = systemText,
      toolDeclarations = toolDeclarations,
      toolSet = toolSet,
    )
    model.instance = instance
    Log.d(TAG, "Manual API model '${model.name}' initialized (endpoint: $endpoint, tools: ${toolDeclarations?.size() ?: 0})")
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
    val instance = model.instance as? ManualApiInstance ?: return
    instance.conversationHistory.clear()
    instance.cancelled.set(false)
    if (systemInstruction != null) {
      instance.systemInstruction = buildSystemText(systemInstruction)
    }
    Log.d(TAG, "Conversation reset for '${model.name}'")
  }

  override fun cleanUp(model: Model, onDone: () -> Unit) {
    val instance = model.instance as? ManualApiInstance
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
    val instance = model.instance as? ManualApiInstance
    if (instance == null) {
      onError("Manual API model not initialized")
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

          val responseText = callApi(instance)
          if (instance.cancelled.get()) {
            cleanUpListener()
            return@launch
          }

          val responseJson = try {
            JsonParser.parseString(responseText).asJsonObject
          } catch (e: Exception) {
            onError("Failed to parse API response: ${e.message}")
            cleanUpListener()
            return@launch
          }

          if (responseJson.has("error")) {
            val errorMsg = responseJson.getAsJsonObject("error")
              ?.get("message")?.asString ?: "Unknown API error"
            onError(errorMsg)
            cleanUpListener()
            return@launch
          }

          val candidates = responseJson.getAsJsonArray("candidates")
          if (candidates == null || candidates.size() == 0) {
            onError("API returned no candidates")
            cleanUpListener()
            return@launch
          }

          val content = candidates[0].asJsonObject.getAsJsonObject("content")
          val parts = content?.getAsJsonArray("parts")
          if (parts == null || parts.size() == 0) {
            resultListener("", true, null)
            cleanUpListener()
            return@launch
          }

          instance.conversationHistory.add(content)

          val functionCallPart = try {
            parts.firstOrNull { it.asJsonObject.has("functionCall") }
          } catch (e: Exception) {
            Log.w(TAG, "Auto-Heal: failed to inspect parts for functionCall", e)
            null
          }

          if (functionCallPart != null) {
            try {
              val fc = functionCallPart.asJsonObject.getAsJsonObject("functionCall")
              val fnName = fc.get("name").asString
              val fnArgs = fc.getAsJsonObject("args") ?: JsonObject()
              Log.d(TAG, "Function call: $fnName($fnArgs)")
              val result = executeFunctionCall(instance.toolSet, fnName, fnArgs)
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
              resultListener("", false, null)
              continue
            } catch (e: Exception) {
              Log.e(TAG, "Auto-Heal: malformed function call — injecting error and retrying", e)
              val sanitizedMsg = (e.message ?: "unknown error").take(200)
              val errorMessage = "[System Error: Malformed tool call. Exception: $sanitizedMsg. Correct your formatting and try again.]"
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
          val fullText = textParts.joinToString("") { it.asJsonObject.get("text").asString }
          resultListener(fullText, true, null)
          cleanUpListener()
          return@launch
        }

        resultListener("[Max function call rounds reached]", true, null)
        cleanUpListener()
      } catch (e: Exception) {
        Log.e(TAG, "Inference error", e)
        onError(e.message ?: "Unknown error during inference")
        cleanUpListener()
      }
    }
  }

  override fun stopResponse(model: Model) {
    val instance = model.instance as? ManualApiInstance ?: return
    instance.cancelled.set(true)
    instance.inferenceJob?.cancel()
  }

  // ──────────────────────────────────────────────────────────────────────────
  // REST API call
  // ──────────────────────────────────────────────────────────────────────────

  private fun callApi(instance: ManualApiInstance): String {
    // Append the :generateContent action and API key to the endpoint.
    val baseUrl = instance.apiEndpoint.trimEnd('/')
    val url = if (baseUrl.contains(":generateContent")) {
      URL("$baseUrl?key=${instance.apiKey}")
    } else {
      URL("$baseUrl:generateContent?key=${instance.apiKey}")
    }

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
        Log.e(TAG, "API error $responseCode: $responseText")
      }
      return responseText
    } finally {
      conn.disconnect()
    }
  }

  private fun buildRequestBody(instance: ManualApiInstance): String {
    val body = JsonObject()

    instance.systemInstruction?.let { sysText ->
      body.add("system_instruction", JsonObject().apply {
        add("parts", JsonArray().apply {
          add(JsonObject().apply { addProperty("text", sysText) })
        })
      })
    }

    val contents = JsonArray()
    for (msg in instance.conversationHistory) {
      contents.add(msg)
    }
    body.add("contents", contents)

    instance.toolDeclarations?.let { decls ->
      if (decls.size() > 0) {
        body.add("tools", JsonArray().apply {
          add(JsonObject().apply {
            add("function_declarations", decls)
          })
        })
      }
    }

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
    return declarations
  }

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
      val paramValues = mutableListOf<Any?>(toolSet)
      for (param in fn.valueParameters) {
        val paramName = param.name ?: ""
        val value = if (args.has(paramName)) args.get(paramName).asString else ""
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
}
