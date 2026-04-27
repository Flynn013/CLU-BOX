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

package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.common.processLlmResponse
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.brainbox.BrainBoxDao
import com.google.ai.edge.gallery.data.brainbox.ChatHistoryDao
import com.google.ai.edge.gallery.data.brainbox.ChatMessageEntity
import com.google.ai.edge.gallery.data.brainbox.NeuronEntity
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageAudioClip
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageError
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageLoading
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageThinking
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ToolProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AGLlmChatViewModel"

@HiltViewModel
class LlmChatViewModel
@Inject
constructor(
  private val dataStoreRepository: DataStoreRepository,
  private val chatHistoryDao: ChatHistoryDao,
  private val brainBoxDao: BrainBoxDao,
) : LlmChatViewModelBase() {

  private var appContext: Context? = null

  override fun initAppContext(context: Context) {
    appContext = context.applicationContext
  }

  override fun loadChatHistory(taskId: String, model: Model) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val entities = chatHistoryDao.getMessages(taskId, model.name)
        for (entity in entities) {
          val side = if (entity.side == "USER") ChatSide.USER else ChatSide.AGENT
          val msg = ChatMessageText(content = entity.content, side = side)
          addMessage(model = model, message = msg)
        }
        Log.d(TAG, "Loaded ${entities.size} messages for $taskId/${model.name}")
      } catch (e: Exception) {
        Log.e(TAG, "Failed to load chat history", e)
      }
    }
  }

  override fun wipeGrid(taskId: String, model: Model) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        chatHistoryDao.deleteMessages(taskId, model.name)
        Log.d(TAG, "Wiped chat history for $taskId/${model.name}")
      } catch (e: Exception) {
        Log.e(TAG, "Failed to wipe chat history", e)
      }
    }
    clearAllMessages(model)
  }

  override fun generateResponse(
    model: Model,
    input: String,
    taskId: String,
    images: List<Bitmap>,
    audioMessages: List<ChatMessageAudioClip>,
    onFirstToken: (Model) -> Unit,
    onDone: () -> Unit,
    onError: (String) -> Unit,
    allowThinking: Boolean,
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      setInProgress(true)
      setPreparing(true)

      // Wait for model instance to be ready.
      while (model.instance == null) {
        delay(100)
      }

      // Add a loading placeholder.
      addMessage(model = model, message = ChatMessageLoading())

      var firstToken = true
      var response = ""
      var thinking = ""

      val audioClips = audioMessages.map { it.audioData }

      model.runtimeHelper.runInference(
        model = model,
        input = input,
        images = images,
        audioClips = audioClips,
        resultListener = { partialResult, done, partialThinking ->
          if (firstToken) {
            setPreparing(false)
            firstToken = false
            onFirstToken(model)
            // Replace the loading placeholder with a text message.
            removeLastMessage(model)
            if (allowThinking) {
              addMessage(
                model = model,
                message = ChatMessageThinking(content = "", inProgress = true, side = ChatSide.AGENT),
              )
            }
            addMessage(model = model, message = ChatMessageText(content = "", side = ChatSide.AGENT))
          }

          if (partialThinking != null && allowThinking) {
            thinking += partialThinking
            updateLastThinkingMessageContentIncrementally(model = model, partialContent = partialThinking)
          }

          if (partialResult.isNotEmpty()) {
            response = processLlmResponse(response = "$response$partialResult")
            updateLastTextMessageContentIncrementally(
              model = model,
              partialContent = partialResult,
              latencyMs = 0f,
            )
          }

          if (done) {
            setInProgress(false)
            // Persist to database.
            if (input.isNotBlank()) {
              viewModelScope.launch(Dispatchers.IO) {
                try {
                  chatHistoryDao.insertMessage(
                    ChatMessageEntity(
                      taskId = taskId,
                      modelName = model.name,
                      side = "USER",
                      content = input,
                      timestampMs = System.currentTimeMillis(),
                    )
                  )
                  if (response.isNotBlank()) {
                    chatHistoryDao.insertMessage(
                      ChatMessageEntity(
                        taskId = taskId,
                        modelName = model.name,
                        side = "AGENT",
                        content = response,
                        timestampMs = System.currentTimeMillis(),
                      )
                    )
                  }
                } catch (e: Exception) {
                  Log.e(TAG, "Failed to persist messages", e)
                }
              }
            }
            onDone()
          }
        },
        cleanUpListener = {
          setPreparing(false)
          setInProgress(false)
        },
        onError = { errorMsg ->
          setPreparing(false)
          setInProgress(false)
          removeLastMessage(model)
          onError(errorMsg)
        },
        coroutineScope = viewModelScope,
      )
    }
  }

  override fun runAgain(
    model: Model,
    message: ChatMessageText,
    taskId: String,
    onError: (String) -> Unit,
    allowThinking: Boolean,
  ) {
    generateResponse(
      model = model,
      input = message.content,
      taskId = taskId,
      images = emptyList(),
      audioMessages = emptyList(),
      onFirstToken = {},
      onDone = {},
      onError = onError,
      allowThinking = allowThinking,
    )
  }

  override fun handleError(
    context: Context,
    task: Task,
    model: Model,
    errorMessage: String,
    modelManagerViewModel: ModelManagerViewModel,
  ) {
    Log.e(TAG, "Inference error for model ${model.name}: $errorMessage")
    removeLastMessage(model)
    addMessage(model = model, message = ChatMessageError(content = errorMessage))
  }

  override fun resetSession(
    task: Task,
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    onDone: () -> Unit,
    enableConversationConstrainedDecoding: Boolean,
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      setIsResettingSession(true)
      try {
        model.runtimeHelper.resetConversation(
          model = model,
          supportImage = supportImage,
          supportAudio = supportAudio,
          systemInstruction = systemInstruction,
          tools = tools,
          enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
        )
        clearAllMessages(model)
      } finally {
        setIsResettingSession(false)
        onDone()
      }
    }
  }

  override fun stopResponse(model: Model) {
    viewModelScope.launch(Dispatchers.Default) {
      setInProgress(false)
      model.runtimeHelper.stopResponse(model)
    }
  }

  override fun forgeNeuron(model: Model) {
    val lastAgentMsg = getLastMessageWithTypeAndSide(
      model = model,
      type = ChatMessageType.TEXT,
      side = ChatSide.AGENT,
    ) as? ChatMessageText ?: return

    val content = lastAgentMsg.content
    if (content.isBlank()) return

    viewModelScope.launch(Dispatchers.IO) {
      try {
        val neuron = NeuronEntity(
          id = UUID.randomUUID().toString(),
          label = content.take(80).replace("\n", " "),
          type = "Session_Log",
          content = content,
        )
        brainBoxDao.insertNeuron(neuron)
        Log.d(TAG, "ForgeNeuron: saved neuron ${neuron.id}")
      } catch (e: Exception) {
        Log.e(TAG, "ForgeNeuron failed", e)
      }
    }
  }
}

// ── Thin HiltViewModel wrappers for Ask-Image and Ask-Audio screens ──────────

@HiltViewModel
class LlmAskImageViewModel
@Inject
constructor(
  dataStoreRepository: DataStoreRepository,
  chatHistoryDao: ChatHistoryDao,
  brainBoxDao: BrainBoxDao,
) : LlmChatViewModel(dataStoreRepository, chatHistoryDao, brainBoxDao)

@HiltViewModel
class LlmAskAudioViewModel
@Inject
constructor(
  dataStoreRepository: DataStoreRepository,
  chatHistoryDao: ChatHistoryDao,
  brainBoxDao: BrainBoxDao,
) : LlmChatViewModel(dataStoreRepository, chatHistoryDao, brainBoxDao)
