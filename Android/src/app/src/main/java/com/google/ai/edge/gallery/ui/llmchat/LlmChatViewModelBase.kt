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
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageAudioClip
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ToolProvider

/** Abstract base for all LLM chat view-models. Shared by the three chat screens. */
abstract class LlmChatViewModelBase : ChatViewModel() {

  /** Store the application context for later use (e.g. file writing). */
  abstract fun initAppContext(context: Context)

  /** Reload persisted chat history from the database for the given task+model pair. */
  abstract fun loadChatHistory(taskId: String, model: Model)

  /** Delete all persisted messages for the given task+model pair and clear in-memory state. */
  abstract fun wipeGrid(taskId: String, model: Model)

  /** Run inference for the given input and stream results back through callbacks. */
  abstract fun generateResponse(
    model: Model,
    input: String,
    taskId: String,
    images: List<Bitmap>,
    audioMessages: List<ChatMessageAudioClip>,
    onFirstToken: (Model) -> Unit,
    onDone: () -> Unit,
    onError: (String) -> Unit,
    allowThinking: Boolean,
  )

  /** Re-run inference for the given message (used by the "run again" UI button). */
  abstract fun runAgain(
    model: Model,
    message: ChatMessageText,
    taskId: String,
    onError: (String) -> Unit,
    allowThinking: Boolean,
  )

  /** Handle an inference error: add an error message to the chat and log it. */
  abstract fun handleError(
    context: Context,
    task: Task,
    model: Model,
    errorMessage: String,
    modelManagerViewModel: ModelManagerViewModel,
  )

  /** Reset the conversation context (KV-cache), optionally supplying a new system prompt. */
  abstract fun resetSession(
    task: Task,
    model: Model,
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
    systemInstruction: Contents? = null,
    tools: List<ToolProvider> = emptyList(),
    onDone: () -> Unit = {},
    enableConversationConstrainedDecoding: Boolean = false,
  )

  /** Cancel in-flight inference. */
  abstract fun stopResponse(model: Model)

  /** Save the last model response as a neuron in BrainBox storage. */
  abstract fun forgeNeuron(model: Model)
}
