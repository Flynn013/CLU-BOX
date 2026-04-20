/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.common.cleanUpMediapipeTaskErrorMessage
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.DEFAULT_VISION_ACCELERATOR
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope

private const val TAG = "AGLlmChatModelHelper"

data class LlmModelInstance(
  val engine: Engine,
  var conversation: Conversation,
  /** Cached system instruction for conversation reset during pruning. */
  var systemInstruction: Contents? = null,
  /** Cached tool providers for conversation reset during pruning. */
  var tools: List<ToolProvider> = listOf(),
)

object LlmChatModelHelper : LlmModelHelper {
  // Indexed by model name.
  private val cleanUpListeners: MutableMap<String, CleanUpListener> = mutableMapOf()

  @OptIn(ExperimentalApi::class) // opt-in experimental flags
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
    // Prepare options.
    val maxTokens =
      model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
    val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
    val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
    val temperature =
      model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
    val accelerator =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
    val visionAccelerator =
      model.getStringConfigValue(
        key = ConfigKeys.VISION_ACCELERATOR,
        defaultValue = DEFAULT_VISION_ACCELERATOR.label,
      )
    val visionBackend =
      when (visionAccelerator) {
        Accelerator.CPU.label -> Backend.CPU()
        Accelerator.GPU.label -> Backend.GPU()
        Accelerator.NPU.label ->
          Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        else -> Backend.GPU()
      }
    val shouldEnableImage = supportImage
    val shouldEnableAudio = supportAudio
    val preferredBackend =
      when (accelerator) {
        Accelerator.CPU.label -> Backend.CPU()
        Accelerator.GPU.label -> Backend.GPU()
        Accelerator.NPU.label ->
          Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        else -> Backend.CPU()
      }
    Log.d(TAG, "Preferred backend: $preferredBackend")

    val modelPath = model.getPath(context = context)

    // Pre-ignition: verify model file exists and is readable before Engine init.
    val modelFile = File(modelPath)
    if (!modelFile.exists()) {
      Log.e("CLU_CRASH_REPORT", "Model file not found at: $modelPath")
      onDone("Diagnostic: Model file not found at $modelPath")
      return
    }
    if (!modelFile.canRead()) {
      Log.e("CLU_CRASH_REPORT", "Model file not readable at: $modelPath")
      onDone("Diagnostic: Model file not readable at $modelPath")
      return
    }

    val engineConfig =
      EngineConfig(
        modelPath = modelPath,
        backend = preferredBackend,
        visionBackend = if (shouldEnableImage) visionBackend else null, // must be GPU for Gemma 3n
        audioBackend = if (shouldEnableAudio) Backend.CPU() else null, // must be CPU for Gemma 3n
        maxNumTokens = maxTokens,
        cacheDir =
          if (modelPath.startsWith("/data/local/tmp"))
            context.getExternalFilesDir(null)?.absolutePath
          else null,
      )

    // Create an instance of LiteRT LM engine and conversation.
    try {
      val engine = Engine(engineConfig)
      engine.initialize()

      ExperimentalFlags.enableConversationConstrainedDecoding =
        enableConversationConstrainedDecoding
      val conversation =
        engine.createConversation(
          ConversationConfig(
            samplerConfig =
              if (preferredBackend is Backend.NPU) {
                null
              } else {
                SamplerConfig(
                  topK = topK,
                  topP = topP.toDouble(),
                  temperature = temperature.toDouble(),
                )
              },
            systemInstruction = systemInstruction,
            tools = tools,
          )
        )
      ExperimentalFlags.enableConversationConstrainedDecoding = false
      model.instance = LlmModelInstance(
        engine = engine,
        conversation = conversation,
        systemInstruction = systemInstruction,
        tools = tools,
      )
    } catch (e: Throwable) {
      val errorMsg = e.stackTraceToString()
      Log.e("CLU_CRASH_REPORT", "Engine initialization failed: $errorMsg")
      // Explicit OOM recovery: release resources before reporting.
      if (e is OutOfMemoryError) {
        Log.e("CLU_CRASH_REPORT", "OOM during engine init — forcing GC and releasing model")
        try { model.instance = null } catch (cleanup: Throwable) {
          Log.e("CLU_CRASH_REPORT", "Cleanup after OOM failed: ${cleanup.message}")
        }
        System.gc()
        onDone("OutOfMemoryError: Device does not have enough RAM for this model. Try selecting a smaller model from the model list.")
      } else {
        onDone(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error"))
      }
      return
    }
    onDone("")
  }

  @OptIn(ExperimentalApi::class) // opt-in experimental flags
  override fun resetConversation(
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
  ) {
    try {
      Log.d(TAG, "Resetting conversation for model '${model.name}'")

      val instance = model.instance as LlmModelInstance? ?: return
      instance.conversation.close()

      val engine = instance.engine
      val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
      val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
      val temperature =
        model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
      val shouldEnableImage = supportImage
      val shouldEnableAudio = supportAudio
      Log.d(TAG, "Enable image: $shouldEnableImage, enable audio: $shouldEnableAudio")

      val accelerator =
        model.getStringConfigValue(
          key = ConfigKeys.ACCELERATOR,
          defaultValue = Accelerator.GPU.label,
        )
      ExperimentalFlags.enableConversationConstrainedDecoding =
        enableConversationConstrainedDecoding
      val newConversation =
        engine.createConversation(
          ConversationConfig(
            samplerConfig =
              if (accelerator == Accelerator.NPU.label) {
                null
              } else {
                SamplerConfig(
                  topK = topK,
                  topP = topP.toDouble(),
                  temperature = temperature.toDouble(),
                )
              },
            systemInstruction = systemInstruction,
            tools = tools,
          )
        )
      ExperimentalFlags.enableConversationConstrainedDecoding = false
      instance.conversation = newConversation
      instance.systemInstruction = systemInstruction
      instance.tools = tools

      Log.d(TAG, "Resetting done")
    } catch (e: Exception) {
      Log.d(TAG, "Failed to reset conversation", e)
    }
  }

  override fun cleanUp(model: Model, onDone: () -> Unit) {
    if (model.instance == null) {
      return
    }

    val instance = model.instance as LlmModelInstance

    try {
      instance.conversation.close()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to close the conversation: ${e.message}")
    }

    try {
      instance.engine.close()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to close the engine: ${e.message}")
    }

    val onCleanUp = cleanUpListeners.remove(model.name)
    if (onCleanUp != null) {
      onCleanUp()
    }
    model.instance = null

    onDone()
    Log.d(TAG, "Clean up done.")
  }

  override fun stopResponse(model: Model) {
    val instance = model.instance as? LlmModelInstance ?: return
    instance.conversation.cancelProcess()
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
    val instance = model.instance as? LlmModelInstance
    if (instance == null) {
      onError("LlmModelInstance is not initialized.")
      return
    }

    // Set listener.
    if (!cleanUpListeners.containsKey(model.name)) {
      cleanUpListeners[model.name] = cleanUpListener
    }

    val conversation = instance.conversation

    // ── Defensive input size clamp ──────────────────────────────────
    // Hard-cap text input to prevent oversized payloads from crashing
    // the native C++ layer. The ViewModel pre-flight clamp handles most
    // cases, but this serves as a last-resort JNI boundary guard.
    val maxTokens =
      model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
    val maxInputChars = (maxTokens * 3.2).toInt()  // conservative chars-per-token
    val safeInput = if (input.length > maxInputChars) {
      Log.w(TAG, "runInference: input (${input.length} chars) exceeds safety limit ($maxInputChars chars) — truncating")
      input.take(maxInputChars) + "\n[System: Input truncated for memory safety.]"
    } else {
      input
    }

    val contents = mutableListOf<Content>()
    for (image in images) {
      contents.add(Content.ImageBytes(image.toPngByteArray()))
    }
    for (audioClip in audioClips) {
      contents.add(Content.AudioBytes(audioClip))
    }
    // add the text after image and audio for the accurate last token
    if (safeInput.trim().isNotEmpty()) {
      contents.add(Content.Text(safeInput))
    }

    try {
      conversation.sendMessageAsync(
        Contents.of(contents),
        object : MessageCallback {
          override fun onMessage(message: Message) {
            resultListener(message.toString(), false, message.channels["thought"])
          }

          override fun onDone() {
            resultListener("", true, null)
          }

          override fun onError(throwable: Throwable) {
            if (throwable is CancellationException) {
              Log.i(TAG, "The inference is cancelled.")
              resultListener("", true, null)
            } else {
              // ── Sliding context window: auto-prune on token overflow ──
              // When the native engine reports "token ids are too long"
              // (MediaPipe error code 3), the accumulated KV-cache has
              // exceeded the model's physical context window. Reset the
              // Conversation (clearing the native KV-cache) and retry
              // with just the current user input. The system prompt and
              // tool catalog are re-injected from cached values.
              val msg = throwable.message ?: ""
              if (msg.contains("token ids are too long", ignoreCase = true) ||
                  msg.contains("Exceeding the maximum number of tokens", ignoreCase = true)) {
                Log.w(TAG, "Token overflow detected — pruning context and retrying")
                try {
                  instance.conversation.close()
                  val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
                  val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
                  val temperature = model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
                  val accel = model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = "")
                  instance.conversation = instance.engine.createConversation(
                    ConversationConfig(
                      samplerConfig = if (accel == Accelerator.NPU.label) null else
                        SamplerConfig(topK = topK, topP = topP.toDouble(), temperature = temperature.toDouble()),
                      systemInstruction = instance.systemInstruction,
                      tools = instance.tools,
                    )
                  )
                  // Retry with the fresh (pruned) conversation.
                  instance.conversation.sendMessageAsync(
                    Contents.of(contents),
                    object : MessageCallback {
                      override fun onMessage(m: Message) {
                        resultListener(m.toString(), false, m.channels["thought"])
                      }
                      override fun onDone() { resultListener("", true, null) }
                      override fun onError(t: Throwable) {
                        Log.e(TAG, "Retry after context prune also failed", t)
                        onError("Error: ${t.message}")
                      }
                    },
                    extraContext ?: emptyMap(),
                  )
                } catch (resetErr: Exception) {
                  Log.e(TAG, "Failed to prune context after token overflow", resetErr)
                  onError("Error: $msg")
                }
              } else {
                Log.e("CLU_CRASH_REPORT", "Inference callback error: ${throwable.stackTraceToString()}")
                onError("Error: ${throwable.message}")
              }
            }
          }
        },
        extraContext ?: emptyMap(),
      )
    } catch (e: Throwable) {
      Log.e("CLU_CRASH_REPORT", "sendMessageAsync failed: ${e.stackTraceToString()}")
      if (e is OutOfMemoryError) {
        Log.e("CLU_CRASH_REPORT", "OOM during inference — requesting GC")
        System.gc()
        onError("OutOfMemoryError during inference. Try a shorter prompt or restart the session.")
      } else {
        onError("Inference failed: ${e.message ?: "unknown error"}")
      }
    }
  }

  private fun Bitmap.toPngByteArray(): ByteArray {
    val stream = ByteArrayOutputStream()
    this.compress(Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
  }
}
