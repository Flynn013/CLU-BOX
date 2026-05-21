/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.runtime.cloudproviders

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.common.SkillProgressAgentAction
import com.google.ai.edge.gallery.customtasks.agentchat.AgentTools
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.providers.AnthropicProvider
import com.google.ai.edge.gallery.data.providers.ProviderEvent
import com.google.ai.edge.gallery.data.providers.ProviderMessage
import com.google.ai.edge.gallery.data.providers.ProviderRegistry
import com.google.ai.edge.gallery.data.providers.ProviderToolCallResult
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.valueParameters

private const val TAG = "AnthropicCloudHelper"
private const val MAX_TOOL_ROUNDS = 10

/**
 * Session state for an Anthropic Cloud Claude API session.
 */
data class AnthropicCloudInstance(
  val context: Context,
  var conversationHistory: MutableList<ProviderMessage> = mutableListOf(),
  var systemInstruction: String? = null,
  var toolSet: ToolSet? = null,
  var inferenceJob: Job? = null,
  val cancelled: AtomicBoolean = AtomicBoolean(false),
)

/**
 * [LlmModelHelper] implementation that routes inference to the Anthropic Claude
 * API via [AnthropicProvider] from [ProviderRegistry].
 *
 * Supports streaming tokens and multi-round tool calling.  Auth is handled
 * automatically: OAuth bearer token is preferred over API key via
 * [ProviderRegistry.get].
 */
object AnthropicCloudModelHelper : LlmModelHelper {

  private val cleanUpListeners: ConcurrentHashMap<String, CleanUpListener> = ConcurrentHashMap()

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
      }
    }

    val systemText = systemInstruction?.let { buildSystemText(it) }

    val instance = AnthropicCloudInstance(
      context = context.applicationContext,
      systemInstruction = systemText,
      toolSet = toolSet,
    )
    model.instance = instance
    Log.d(TAG, "Anthropic Cloud model '${model.name}' initialized")
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
    val instance = model.instance as? AnthropicCloudInstance ?: return
    instance.conversationHistory.clear()
    instance.cancelled.set(false)
    if (systemInstruction != null) {
      instance.systemInstruction = buildSystemText(systemInstruction)
    }
    Log.d(TAG, "Conversation reset for '${model.name}'")
  }

  override fun cleanUp(model: Model, onDone: () -> Unit) {
    val instance = model.instance as? AnthropicCloudInstance
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
    val instance = model.instance as? AnthropicCloudInstance
    if (instance == null) {
      onError("Anthropic Cloud model '${model.name}' not initialized")
      return
    }

    cleanUpListeners[model.name] = cleanUpListener
    instance.cancelled.set(false)

    val provider = ProviderRegistry.get("anthropic", instance.context, model.name)
    if (provider == null) {
      onError("No Anthropic credentials configured. Add an API key or connect your account in SETTINGS → CLOUD.")
      cleanUpListener()
      return
    }

    val scope = coroutineScope ?: CoroutineScope(Dispatchers.IO)
    instance.inferenceJob = scope.launch(Dispatchers.IO) {
      try {
        instance.conversationHistory.add(ProviderMessage(role = "user", content = input))

        var round = 0
        while (round < MAX_TOOL_ROUNDS) {
          if (instance.cancelled.get()) {
            cleanUpListener()
            return@launch
          }
          round++

          val messages = buildMessages(instance)
          val pendingToolCalls = mutableListOf<ProviderToolCallResult>()
          val accText = StringBuilder()

          val toolDefs = buildToolDefinitions(instance.toolSet)
          provider.streamChat(messages, toolDefs).collect { event ->
            if (instance.cancelled.get()) return@collect
            when (event) {
              is ProviderEvent.Token -> {
                accText.append(event.text)
                resultListener(event.text, false, null)
              }
              is ProviderEvent.Thinking -> resultListener("", false, event.text)
              is ProviderEvent.ToolCallStart -> {
                (instance.toolSet as? AgentTools)?.sendAgentAction(
                  SkillProgressAgentAction(label = "Calling ${event.name}…", inProgress = true)
                )
              }
              is ProviderEvent.ToolCallEnd -> pendingToolCalls.add(
                ProviderToolCallResult(event.id, event.name, event.input)
              )
              is ProviderEvent.Done -> {
                if (pendingToolCalls.isEmpty()) {
                  resultListener(accText.toString().ifEmpty { event.fullText }, true, null)
                  cleanUpListener()
                  return@collect
                }
              }
              is ProviderEvent.Error -> {
                onError(event.message)
                cleanUpListener()
                return@collect
              }
              else -> {}
            }
          }

          if (instance.cancelled.get()) {
            cleanUpListener()
            return@launch
          }

          if (pendingToolCalls.isEmpty()) {
            // Done returned without tool calls — exit loop.
            return@launch
          }

          // Record assistant turn with tool calls, then dispatch each tool.
          instance.conversationHistory.add(
            ProviderMessage(
              role = "assistant",
              content = accText.toString(),
              toolCalls = pendingToolCalls.map {
                com.google.ai.edge.gallery.data.providers.ProviderToolCall(it.id, it.name, it.input.toString())
              },
            )
          )

          for (tc in pendingToolCalls) {
            val result = dispatchToolCall(instance.toolSet, tc.name, tc.input)
            (instance.toolSet as? AgentTools)?.sendAgentAction(
              SkillProgressAgentAction(
                label = "${tc.name} done",
                addItemDescription = result.take(120),
                inProgress = false,
              )
            )
            instance.conversationHistory.add(
              ProviderMessage(
                role = "user",
                content = result,
                toolCallId = tc.id,
                toolName = tc.name,
              )
            )
            resultListener("", false, null)
          }
        }

        resultListener("[Max tool-call rounds reached]", true, null)
        cleanUpListener()
      } catch (e: Exception) {
        Log.e(TAG, "Inference error for '${model.name}'", e)
        onError(e.message ?: "Unknown Anthropic inference error")
        cleanUpListener()
      }
    }
  }

  override fun stopResponse(model: Model) {
    val instance = model.instance as? AnthropicCloudInstance ?: return
    instance.cancelled.set(true)
    instance.inferenceJob?.cancel()
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  private fun buildMessages(instance: AnthropicCloudInstance): List<ProviderMessage> {
    val result = mutableListOf<ProviderMessage>()
    instance.systemInstruction?.let {
      result.add(ProviderMessage(role = "system", content = it))
    }
    result.addAll(instance.conversationHistory)
    return result
  }

  private fun buildToolDefinitions(toolSet: ToolSet?): List<JSONObject> {
    toolSet ?: return emptyList()
    val defs = mutableListOf<JSONObject>()
    for (fn in toolSet::class.memberFunctions) {
      val annotation = fn.findAnnotation<com.google.ai.edge.litertlm.Tool>() ?: continue
      try {
        val props = JSONObject()
        val required = org.json.JSONArray()
        for (param in fn.valueParameters) {
          val paramName = param.name ?: continue
          props.put(paramName, JSONObject().apply {
            put("type", "string")
            put("description", paramName)
          })
          required.put(paramName)
        }
        val schema = JSONObject().apply {
          put("type", "object")
          put("properties", props)
          if (required.length() > 0) put("required", required)
        }
        defs.add(JSONObject().apply {
          put("type", "function")
          put("function", JSONObject().apply {
            put("name", fn.name)
            put("description", annotation.description)
            put("parameters", schema)
          })
        })
      } catch (e: Exception) {
        Log.w(TAG, "buildToolDefinitions: skipping '${fn.name}': ${e.message}")
      }
    }
    Log.d(TAG, "buildToolDefinitions: built ${defs.size} tool definitions")
    return defs
  }

  @Suppress("UNCHECKED_CAST")
  private fun dispatchToolCall(toolSet: ToolSet?, name: String, args: JSONObject): String {
    if (toolSet == null) return "[Tool dispatch error: no ToolSet available]"
    val klass = toolSet::class
    val fn = klass.memberFunctions.find { it.name == name }
      ?: return "[Tool dispatch error: unknown function '$name']"
    return try {
      val paramValues = mutableListOf<Any?>(toolSet)
      for (param in fn.valueParameters) {
        val paramName = param.name ?: ""
        paramValues.add(if (args.has(paramName)) args.optString(paramName) else "")
      }
      val result = fn.call(*paramValues.toTypedArray())
      when (result) {
        is Map<*, *> -> (result as Map<String, Any>).entries
          .joinToString(", ") { "${it.key}=${it.value}" }
        else -> result?.toString() ?: "null"
      }
    } catch (e: Exception) {
      Log.e(TAG, "Tool call '$name' failed", e)
      "[Tool error: ${e.cause?.message ?: e.message ?: "unknown"}]"
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
      contents.toString()
    }
  }
}
