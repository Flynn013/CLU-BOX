/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import com.google.ai.edge.gallery.common.SkillProgressAgentAction
import com.google.ai.edge.gallery.customtasks.agentchat.AgentEngineV2
import com.google.ai.edge.gallery.customtasks.agentchat.AgentEvent
import com.google.ai.edge.gallery.customtasks.agentchat.AgentTools
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.providers.ProviderMessage
import com.google.ai.edge.gallery.data.providers.ProviderRegistry
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.ToolSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "GeminiCloudModelHelper"

/**
 * Holds the state for a Gemini Cloud "session" — the conversation history,
 * tools, system prompt, and any in-flight inference job.
 */
data class GeminiCloudInstance(
  val context: Context, // Injected so we can dynamically fetch API keys at inference time
  var conversationHistory: MutableList<ProviderMessage> = mutableListOf(),
  var systemInstruction: String? = null,
  var toolSet: ToolSet? = null,
  var inferenceJob: Job? = null,
  val cancelled: AtomicBoolean = AtomicBoolean(false),
)

/**
 * [LlmModelHelper] implementation that routes inference to the Google Gemini
 * REST API via [AgentEngineV2].
 */
object GeminiCloudModelHelper : LlmModelHelper {

  private val cleanUpListeners: ConcurrentHashMap<String, CleanUpListener> = ConcurrentHashMap()

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
    var toolSet: ToolSet? = null
    if (tools.isNotEmpty()) {
      val provider = tools.firstOrNull()
      if (provider != null) {
        try {
          val tsField = provider.javaClass.declaredFields.find { ToolSet::class.java.isAssignableFrom(it.type) }
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

    val instance = GeminiCloudInstance(
      context = context.applicationContext,
      systemInstruction = systemText,
      toolSet = toolSet,
    )
    model.instance = instance
    Log.d(TAG, "Gemini Cloud model initialized")
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

    val provider = ProviderRegistry.get("gemini", instance.context, model.name)
    if (provider == null) {
      onError("No Gemini credentials configured. Add an API key or sign in with Google in SETTINGS → CLOUD.")
      cleanUpListener()
      return
    }

    val agentTools = instance.toolSet as? AgentTools
    val skillRegistry = agentTools?.skillRegistry
    if (skillRegistry == null) {
      onError("SkillRegistry not available")
      cleanUpListener()
      return
    }

    val scope = coroutineScope ?: CoroutineScope(Dispatchers.IO)
    instance.inferenceJob = scope.launch(Dispatchers.IO) {
      try {
        val engine = AgentEngineV2(provider, skillRegistry)
        var accText = ""
        var accThinking = ""

        engine.run(input, instance.conversationHistory, instance.systemInstruction.orEmpty()).collect { event ->
          if (instance.cancelled.get()) return@collect
          when (event) {
            is AgentEvent.Token -> {
              val partial = event.text.removePrefix(accText)
              accText = event.text
              resultListener(partial, false, null)
            }
            is AgentEvent.Thinking -> {
              val partial = event.text.removePrefix(accThinking)
              accThinking = event.text
              resultListener("", false, partial)
            }
            is AgentEvent.ToolStart -> {
              agentTools.sendAgentAction(
                SkillProgressAgentAction(label = "Calling ${event.name}…", inProgress = true)
              )
            }
            is AgentEvent.ToolEnd -> {
              agentTools.sendAgentAction(
                SkillProgressAgentAction(
                  label = "${event.name} done",
                  inProgress = false,
                  addItemTitle = event.name,
                  addItemDescription = event.output.take(120),
                )
              )
              resultListener("", false, null)
            }
            is AgentEvent.Complete -> {
              val partial = event.text.removePrefix(accText)
              if (partial.isNotEmpty()) {
                resultListener(partial, false, null)
              }
              resultListener("", true, null)
              cleanUpListener()
            }
            is AgentEvent.Error -> {
              onError(event.message)
              cleanUpListener()
            }
            else -> {}
          }
        }
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
  // Helpers
  // ──────────────────────────────────────────────────────────────────────────

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
