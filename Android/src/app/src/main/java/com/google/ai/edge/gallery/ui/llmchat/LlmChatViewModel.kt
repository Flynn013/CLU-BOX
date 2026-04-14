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
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.brainbox.BrainBoxDao
import com.google.ai.edge.gallery.data.brainbox.ChatHistoryDao
import com.google.ai.edge.gallery.data.brainbox.ChatMessageEntity
import com.google.ai.edge.gallery.data.brainbox.NeuronEntity
import com.google.ai.edge.gallery.runtime.runtimeHelper
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageAudioClip
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageError
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageLoading
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageThinking
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.ChatViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ToolProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGLlmChatViewModel"

@OptIn(ExperimentalApi::class)
open class LlmChatViewModelBase(
  private val chatHistoryDao: ChatHistoryDao? = null,
  private val brainBoxDao: BrainBoxDao? = null,
) : ChatViewModel() {

  /** Tracks which (taskId, modelName) pairs have already had their history loaded. */
  private val loadedHistoryKeys = Collections.synchronizedSet(mutableSetOf<String>())

  /**
   * Loads persisted chat history for [taskId] + [model] from the database into the in-memory
   * UI state. No-op if already loaded or if no [chatHistoryDao] is available.
   */
  fun loadChatHistory(taskId: String, model: Model) {
    val key = "$taskId::${model.name}"
    // Guard against concurrent launches; only the first launch proceeds.
    if (!loadedHistoryKeys.add(key)) return
    val dao = chatHistoryDao ?: return

    viewModelScope.launch(Dispatchers.IO) {
      val rows = dao.getMessages(taskId = taskId, modelName = model.name)
      for (row in rows) {
        val side = when (row.side) {
          "USER" -> ChatSide.USER
          "AGENT" -> ChatSide.AGENT
          else -> {
            Log.w(TAG, "Unknown chat side '${row.side}' in history — skipping row id=${row.id}")
            continue
          }
        }
        addMessage(model = model, message = ChatMessageText(content = row.content, side = side))
      }
    }
  }

  /**
   * Deletes all persisted messages for [taskId] + [model] and clears the in-memory state.
   * This is the "Wipe Grid" operation.
   */
  fun wipeGrid(taskId: String, model: Model) {
    val dao = chatHistoryDao ?: return
    clearAllMessages(model = model)
    // Allow history to be freshly loaded (will be empty) on next model selection.
    loadedHistoryKeys.remove("$taskId::${model.name}")
    viewModelScope.launch(Dispatchers.IO) {
      dao.deleteMessages(taskId = taskId, modelName = model.name)
    }
  }

  // =========================================================================
  // BrainBox — Retrieval-Augmented Generation (RAG)
  // =========================================================================

  /**
   * Searches the BrainBox for neurons whose label, type, or content contain any keyword from
   * [input] (words ≥ 4 chars, up to 3 keywords).  Returns a formatted context block ready to be
   * prepended to the user's message, or null if nothing relevant was found.
   */
  suspend fun retrieveBrainContext(input: String): String? {
    val dao = brainBoxDao ?: return null
    val keywords =
      input
        .split(Regex("\\s+"))
        .map { it.trim().lowercase() }
        .filter { it.length >= 4 }
        .distinct()
        .take(3)
    if (keywords.isEmpty()) return null

    val found = mutableSetOf<NeuronEntity>()
    for (kw in keywords) {
      found.addAll(dao.searchNeurons(kw))
    }
    if (found.isEmpty()) return null

    return found
      .sortedByDescending { it.label }
      .take(3) // cap context injection at 3 neurons to avoid blowing the context window
      .joinToString("\n---\n") { n -> "## ${n.label} [${n.type}]\n${n.content}" }
  }

  /**
   * FORGE NEURON — snapshot the current conversation as a [NeuronEntity] in BrainBox.
   *
   * Builds a plain-text transcript of all [ChatMessageText] turns for [model], saves it as a
   * Session_Log neuron, and appends a confirmation message to the chat so the user can see
   * what was locked.  No-op if [brainBoxDao] is unavailable or the chat is empty.
   */
  fun forgeNeuron(model: Model) {
    val dao = brainBoxDao ?: return
    val messages = uiState.value.messagesByModel[model.name] ?: return
    val transcript =
      messages
        .filterIsInstance<ChatMessageText>()
        .filter { it.side == ChatSide.USER || it.side == ChatSide.AGENT }
        .joinToString("\n\n") { msg ->
          val speaker = if (msg.side == ChatSide.USER) "USER" else "CLU"
          "$speaker: ${msg.content}"
        }
    if (transcript.isBlank()) return

    viewModelScope.launch(Dispatchers.IO) {
      val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
      val neuron =
        NeuronEntity(
          id = UUID.randomUUID().toString(),
          label = "Session_$timestamp",
          type = "Session_Log",
          content = transcript,
        )
      dao.insertNeuron(neuron)
      Log.d(TAG, "Forged neuron: ${neuron.label} (${transcript.length} chars)")

      withContext(Dispatchers.Main) {
        addMessage(
          model = model,
          message =
            ChatMessageText(
              content =
                "⚡ **FORGED TO BRAINBOX** — session locked as `${neuron.label}`.\n" +
                  "CLU will remember this the next time you reference it.",
              side = ChatSide.AGENT,
            ),
        )
      }
    }
  }

  /** Persists a completed user or agent TEXT message to the database. */
  private fun persistMessage(taskId: String, model: Model, side: ChatSide, content: String) {
    val dao = chatHistoryDao ?: return
    val entity =
      ChatMessageEntity(
        taskId = taskId,
        modelName = model.name,
        side = side.name,
        content = content,
        timestampMs = System.currentTimeMillis(),
      )
    viewModelScope.launch(Dispatchers.IO) { dao.insertMessage(entity) }
  }

  fun generateResponse(
    model: Model,
    input: String,
    taskId: String = "",
    images: List<Bitmap> = listOf(),
    audioMessages: List<ChatMessageAudioClip> = listOf(),
    onFirstToken: (Model) -> Unit = {},
    onDone: () -> Unit = {},
    onError: (String) -> Unit,
    allowThinking: Boolean = false,
  ) {
    val accelerator = model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = "")
    viewModelScope.launch(Dispatchers.Default) {
      setInProgress(true)
      setPreparing(true)

      // Persist user message immediately.
      if (taskId.isNotEmpty() && input.isNotEmpty()) {
        persistMessage(taskId = taskId, model = model, side = ChatSide.USER, content = input)
      }

      // Loading.
      addMessage(model = model, message = ChatMessageLoading(accelerator = accelerator))

      // Wait for instance to be initialized.
      while (model.instance == null) {
        delay(100)
      }
      delay(500)

      // Run inference.
      val audioClips: MutableList<ByteArray> = mutableListOf()
      for (audioMessage in audioMessages) {
        audioClips.add(audioMessage.genByteArrayForWav())
      }

      var firstRun = true
      val start = System.currentTimeMillis()

      try {
        val resultListener: (String, Boolean, String?) -> Unit =
          { partialResult, done, partialThinkingResult ->
            if (partialResult.startsWith("<ctrl")) {
              // Do nothing. Ignore control tokens.
            } else {
              // Remove the last message if it is a "loading" message.
              // This will only be done once.
              val lastMessage = getLastMessage(model = model)
              val wasLoading = lastMessage?.type == ChatMessageType.LOADING
              if (wasLoading) {
                removeLastMessage(model = model)
              }

              val thinkingText = partialThinkingResult
              val isThinking = thinkingText != null && thinkingText.isNotEmpty()
              var currentLastMessage = getLastMessage(model = model)

              // If thinking is enabled, add a thinking message.
              if (isThinking) {
                if (currentLastMessage?.type != ChatMessageType.THINKING) {
                  addMessage(
                    model = model,
                    message =
                      ChatMessageThinking(
                        content = "",
                        inProgress = true,
                        side = ChatSide.AGENT,
                        accelerator = accelerator,
                        hideSenderLabel =
                          currentLastMessage?.type == ChatMessageType.COLLAPSABLE_PROGRESS_PANEL,
                      ),
                  )
                }
                updateLastThinkingMessageContentIncrementally(
                  model = model,
                  partialContent = thinkingText!!,
                )
              } else {
                if (currentLastMessage?.type == ChatMessageType.THINKING) {
                  val thinkingMsg = currentLastMessage as ChatMessageThinking
                  if (thinkingMsg.inProgress) {
                    replaceLastMessage(
                      model = model,
                      message =
                        ChatMessageThinking(
                          content = thinkingMsg.content,
                          inProgress = false,
                          side = thinkingMsg.side,
                          accelerator = thinkingMsg.accelerator,
                          hideSenderLabel = thinkingMsg.hideSenderLabel,
                        ),
                      type = ChatMessageType.THINKING,
                    )
                  }
                }
                currentLastMessage = getLastMessage(model = model)
                if (
                  currentLastMessage?.type != ChatMessageType.TEXT ||
                    currentLastMessage.side != ChatSide.AGENT
                ) {
                  // Add an empty message that will receive streaming results.
                  addMessage(
                    model = model,
                    message =
                      ChatMessageText(
                        content = "",
                        side = ChatSide.AGENT,
                        accelerator = accelerator,
                        hideSenderLabel =
                          currentLastMessage?.type == ChatMessageType.COLLAPSABLE_PROGRESS_PANEL ||
                            currentLastMessage?.type == ChatMessageType.THINKING,
                      ),
                  )
                }

                // Incrementally update the streamed partial results.
                val latencyMs: Long = if (done) System.currentTimeMillis() - start else -1
                if (partialResult.isNotEmpty() || wasLoading || done) {
                  updateLastTextMessageContentIncrementally(
                    model = model,
                    partialContent = partialResult,
                    latencyMs = latencyMs.toFloat(),
                  )
                }
              }

              if (firstRun) {
                firstRun = false
                setPreparing(false)
                onFirstToken(model)
              }

              if (done) {
                val finalLastMessage = getLastMessage(model = model)
                if (finalLastMessage?.type == ChatMessageType.THINKING) {
                  val thinkingMsg = finalLastMessage as ChatMessageThinking
                  if (thinkingMsg.inProgress) {
                    replaceLastMessage(
                      model = model,
                      message =
                        ChatMessageThinking(
                          content = thinkingMsg.content,
                          inProgress = false,
                          side = thinkingMsg.side,
                          accelerator = thinkingMsg.accelerator,
                          hideSenderLabel = thinkingMsg.hideSenderLabel,
                        ),
                      type = ChatMessageType.THINKING,
                    )
                  }
                }

                // Persist the completed agent response.
                if (taskId.isNotEmpty()) {
                  val agentMsg = getLastMessage(model = model)
                  if (agentMsg is ChatMessageText && agentMsg.side == ChatSide.AGENT) {
                    persistMessage(
                      taskId = taskId,
                      model = model,
                      side = ChatSide.AGENT,
                      content = agentMsg.content,
                    )
                  }
                }

                setInProgress(false)
                onDone()
              }
            }
          }

        val cleanUpListener: () -> Unit = {
          setInProgress(false)
          setPreparing(false)
        }

        val errorListener: (String) -> Unit = { message ->
          Log.e(TAG, "Error occurred while running inference")
          setInProgress(false)
          setPreparing(false)
          onError(message)
        }

        val enableThinking =
          allowThinking &&
            model.getBooleanConfigValue(key = ConfigKeys.ENABLE_THINKING, defaultValue = false)
        val extraContext = if (enableThinking) mapOf("enable_thinking" to "true") else null

        // BrainBox RAG — query for relevant neurons and prepend them as injected context.
        // Only runs when the user has actually typed something (not for image/audio-only turns).
        val augmentedInput =
          if (input.isNotBlank()) {
            val brainContext = withContext(Dispatchers.IO) { retrieveBrainContext(input) }
            if (!brainContext.isNullOrBlank()) {
              Log.d(TAG, "BrainBox: injecting context (${brainContext.length} chars)")
              "=== CLU/BOX MEMORY (relevant context retrieved from BrainBox) ===\n" +
                brainContext +
                "\n=== END MEMORY ===\n\nUser message: $input"
            } else {
              input
            }
          } else {
            input
          }

        model.runtimeHelper.runInference(
          model = model,
          input = augmentedInput,
          images = images,
          audioClips = audioClips,
          resultListener = resultListener,
          cleanUpListener = cleanUpListener,
          onError = errorListener,
          coroutineScope = viewModelScope,
          extraContext = extraContext,
        )
      } catch (e: Exception) {
        Log.e(TAG, "Error occurred while running inference", e)
        setInProgress(false)
        setPreparing(false)
        onError(e.message ?: "")
      }
    }
  }

  fun stopResponse(model: Model) {
    Log.d(TAG, "Stopping response for model ${model.name}...")
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    }
    setInProgress(false)
    model.runtimeHelper.stopResponse(model)
    Log.d(TAG, "Done stopping response")
  }

  fun resetSession(
    task: Task,
    model: Model,
    systemInstruction: Contents? = null,
    tools: List<ToolProvider> = listOf(),
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
    onDone: () -> Unit = {},
    enableConversationConstrainedDecoding: Boolean = false,
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      setIsResettingSession(true)
      clearAllMessages(model = model)
      stopResponse(model = model)

      while (true) {
        try {
          model.runtimeHelper.resetConversation(
            model = model,
            supportImage = supportImage,
            supportAudio = supportAudio,
            systemInstruction = systemInstruction,
            tools = tools,
            enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
          )
          break
        } catch (e: Exception) {
          Log.d(TAG, "Failed to reset session. Trying again")
        }
        delay(200)
      }
      setIsResettingSession(false)
      onDone()
    }
  }

  fun runAgain(
    model: Model,
    message: ChatMessageText,
    taskId: String = "",
    onError: (String) -> Unit,
    allowThinking: Boolean = false,
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      // Wait for model to be initialized.
      while (model.instance == null) {
        delay(100)
      }

      // Clone the clicked message and add it.
      addMessage(model = model, message = message.clone())

      // Run inference.
      generateResponse(
        model = model,
        input = message.content,
        taskId = taskId,
        onError = onError,
        allowThinking = allowThinking,
      )
    }
  }

  fun handleError(
    context: Context,
    task: Task,
    model: Model,
    modelManagerViewModel: ModelManagerViewModel,
    errorMessage: String,
  ) {
    // Remove the "loading" message.
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    }

    // Show error message.
    addMessage(model = model, message = ChatMessageError(content = errorMessage))

    // Clean up and re-initialize.
    viewModelScope.launch(Dispatchers.Default) {
      modelManagerViewModel.cleanupModel(
        context = context,
        task = task,
        model = model,
        onDone = {
          modelManagerViewModel.initializeModel(context = context, task = task, model = model)

          // Add a warning message for re-initializing the session.
          addMessage(
            model = model,
            message = ChatMessageWarning(content = "Session re-initialized"),
          )
        },
      )
    }
  }
}

@HiltViewModel
class LlmChatViewModel @Inject constructor(chatHistoryDao: ChatHistoryDao, brainBoxDao: BrainBoxDao) :
  LlmChatViewModelBase(chatHistoryDao, brainBoxDao)

@HiltViewModel class LlmAskImageViewModel @Inject constructor() : LlmChatViewModelBase()

@HiltViewModel class LlmAskAudioViewModel @Inject constructor() : LlmChatViewModelBase()
