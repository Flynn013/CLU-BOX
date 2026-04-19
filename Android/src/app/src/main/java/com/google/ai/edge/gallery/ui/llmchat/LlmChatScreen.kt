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

import androidx.hilt.navigation.compose.hiltViewModel

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.EMPTY_MODEL
import com.google.ai.edge.gallery.data.LogBoxManager
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageAudioClip
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageImage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatView
import com.google.ai.edge.gallery.ui.common.chat.SendMessageTrigger
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.emptyStateContent
import com.google.ai.edge.gallery.ui.theme.emptyStateTitle
import com.google.ai.edge.gallery.ui.theme.neonGreen

private const val TAG = "AGLlmChatScreen"

@Composable
fun LlmChatScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  taskId: String = BuiltInTaskId.LLM_CHAT,
  onFirstToken: (Model) -> Unit = {},
  onGenerateResponseDone: (Model) -> Unit = {},
  onInferenceError: (Model) -> Unit = {},
  onSkillClicked: () -> Unit = {},
  onResetSessionClickedOverride: ((Task, Model) -> Unit)? = null,
  composableBelowMessageList: @Composable (Model) -> Unit = {},
  viewModel: LlmChatViewModel = hiltViewModel(),
  allowEditingSystemPrompt: Boolean = false,
  curSystemPrompt: String = "",
  onSystemPromptChanged: (String) -> Unit = {},
  emptyStateComposable: @Composable (Model) -> Unit = {},
  sendMessageTrigger: SendMessageTrigger? = null,
  showImagePicker: Boolean = false,
  showAudioPicker: Boolean = false,
  onChatHistoryClicked: (() -> Unit)? = null,
) {
  ChatViewWrapper(
    viewModel = viewModel,
    modelManagerViewModel = modelManagerViewModel,
    taskId = taskId,
    navigateUp = navigateUp,
    modifier = modifier,
    onSkillClicked = onSkillClicked,
    onFirstToken = onFirstToken,
    onGenerateResponseDone = onGenerateResponseDone,
    onInferenceError = onInferenceError,
    onResetSessionClickedOverride = onResetSessionClickedOverride,
    composableBelowMessageList = composableBelowMessageList,
    allowEditingSystemPrompt = allowEditingSystemPrompt,
    curSystemPrompt = curSystemPrompt,
    onSystemPromptChanged = onSystemPromptChanged,
    emptyStateComposable = emptyStateComposable,
    sendMessageTrigger = sendMessageTrigger,
    showImagePicker = showImagePicker,
    showAudioPicker = showAudioPicker,
    showWipeGridButton = true,
    onChatHistoryClicked = onChatHistoryClicked,
  )
}

@Composable
fun LlmAskImageScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LlmAskImageViewModel = hiltViewModel(),
) {
  ChatViewWrapper(
    viewModel = viewModel,
    modelManagerViewModel = modelManagerViewModel,
    taskId = BuiltInTaskId.LLM_ASK_IMAGE,
    navigateUp = navigateUp,
    modifier = modifier,
    showImagePicker = true,
    showAudioPicker = false,
    emptyStateComposable = { model ->
      Box(modifier = Modifier.fillMaxSize()) {
        Column(
          modifier =
            Modifier.align(Alignment.Center).padding(horizontal = 48.dp).padding(bottom = 48.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Text(stringResource(R.string.askimage_emptystate_title), style = emptyStateTitle)
          val contentRes =
            if (model.runtimeType == RuntimeType.AICORE) R.string.askimage_emptystate_content_aicore
            else R.string.askimage_emptystate_content
          Text(
            stringResource(contentRes),
            style = emptyStateContent,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      }
    },
  )
}

@Composable
fun LlmAskAudioScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LlmAskAudioViewModel = hiltViewModel(),
) {
  ChatViewWrapper(
    viewModel = viewModel,
    modelManagerViewModel = modelManagerViewModel,
    taskId = BuiltInTaskId.LLM_ASK_AUDIO,
    navigateUp = navigateUp,
    modifier = modifier,
    showImagePicker = false,
    showAudioPicker = true,
    emptyStateComposable = {
      Box(modifier = Modifier.fillMaxSize()) {
        Column(
          modifier =
            Modifier.align(Alignment.Center).padding(horizontal = 48.dp).padding(bottom = 48.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Text(stringResource(R.string.askaudio_emptystate_title), style = emptyStateTitle)
          Text(
            stringResource(R.string.askaudio_emptystate_content),
            style = emptyStateContent,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      }
    },
  )
}

@Composable
fun ChatViewWrapper(
  viewModel: LlmChatViewModelBase,
  modelManagerViewModel: ModelManagerViewModel,
  taskId: String,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  onSkillClicked: () -> Unit = {},
  onFirstToken: (Model) -> Unit = {},
  onGenerateResponseDone: (Model) -> Unit = {},
  onInferenceError: (Model) -> Unit = {},
  onResetSessionClickedOverride: ((Task, Model) -> Unit)? = null,
  composableBelowMessageList: @Composable (Model) -> Unit = {},
  emptyStateComposable: @Composable (Model) -> Unit = {},
  allowEditingSystemPrompt: Boolean = false,
  curSystemPrompt: String = "",
  onSystemPromptChanged: (String) -> Unit = {},
  sendMessageTrigger: SendMessageTrigger? = null,
  showImagePicker: Boolean = false,
  showAudioPicker: Boolean = false,
  showWipeGridButton: Boolean = false,
  onChatHistoryClicked: (() -> Unit)? = null,
) {
  val context = LocalContext.current
  // Guard: if the task doesn't exist yet (e.g. before model download) bail out
  // instead of crashing on a null pointer.
  val task = modelManagerViewModel.getTaskById(id = taskId) ?: return
  val allowThinking = task.allowThinking()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val selectedModel = modelManagerUiState.selectedModel
  var showWipeConfirmDialog by remember { mutableStateOf(false) }

  // LOG_BOX manager — created once per ChatViewWrapper lifecycle.
  val logBoxManager = remember { LogBoxManager(context.applicationContext) }

  // Load persisted chat history whenever the selected model changes,
  // but only when a real model is selected (not the EMPTY_MODEL sentinel).
  LaunchedEffect(selectedModel.name) {
    viewModel.initAppContext(context)
    if (selectedModel != EMPTY_MODEL) {
      viewModel.loadChatHistory(taskId, selectedModel)
    }
  }

  if (showWipeConfirmDialog) {
    AlertDialog(
      onDismissRequest = { showWipeConfirmDialog = false },
      title = {
        Text(
          "WIPE GRID?",
          fontFamily = FontFamily.Monospace,
          color = neonGreen,
        )
      },
      text = {
        Text(
          "This will permanently delete all chat history for this model.",
          fontFamily = FontFamily.Monospace,
        )
      },
      confirmButton = {
        TextButton(onClick = {
          viewModel.wipeGrid(taskId, selectedModel)
          showWipeConfirmDialog = false
        }) {
          Text("CONFIRM", color = neonGreen, fontFamily = FontFamily.Monospace)
        }
      },
      dismissButton = {
        TextButton(onClick = { showWipeConfirmDialog = false }) {
          Text("CANCEL", fontFamily = FontFamily.Monospace)
        }
      },
    )
  }

  val wrappedComposableBelowMessageList: @Composable (Model) -> Unit = { model ->
    if (showWipeGridButton) {
      OutlinedButton(
        onClick = { showWipeConfirmDialog = true },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = neonGreen),
      ) {
        Text("⚠ WIPE GRID", fontFamily = FontFamily.Monospace)
      }
    }
    composableBelowMessageList(model)
  }

  ChatView(
    task = task,
    viewModel = viewModel,
    modelManagerViewModel = modelManagerViewModel,
    onSendMessage = { model, messages ->
      // Check if inference is currently running — if so, mark text
      // messages as "queued" so they render with the muted style.
      val isCurrentlyProcessing = viewModel.uiState.value.inProgress
      for (message in messages) {
        val messageToAdd = if (isCurrentlyProcessing && message is ChatMessageText) {
          ChatMessageText(
            content = message.content,
            side = message.side,
            latencyMs = message.latencyMs,
            isMarkdown = message.isMarkdown,
            accelerator = message.accelerator,
            hideSenderLabel = message.hideSenderLabel,
            isQueued = true,
          )
        } else {
          message
        }
        viewModel.addMessage(model = model, message = messageToAdd)
      }

      var text = ""
      val images: MutableList<Bitmap> = mutableListOf()
      val audioMessages: MutableList<ChatMessageAudioClip> = mutableListOf()
      var chatMessageText: ChatMessageText? = null
      for (message in messages) {
        if (message is ChatMessageText) {
          chatMessageText = message
          text = message.content
        } else if (message is ChatMessageImage) {
          images.addAll(message.bitmaps)
        } else if (message is ChatMessageAudioClip) {
          audioMessages.add(message)
        }
      }
      if ((text.isNotEmpty() && chatMessageText != null) || audioMessages.isNotEmpty()) {
        if (text.isNotEmpty()) {
          modelManagerViewModel.addTextInputHistory(text)
        }
        viewModel.generateResponse(
          model = model,
          input = text,
          taskId = taskId,
          images = images,
          audioMessages = audioMessages,
          onFirstToken = onFirstToken,
          onDone = { onGenerateResponseDone(model) },
          onError = { errorMessage ->
            viewModel.handleError(
              context = context,
              task = task,
              model = model,
              errorMessage = errorMessage,
              modelManagerViewModel = modelManagerViewModel,
            )
            onInferenceError(model)
          },
          allowThinking = allowThinking,
        )

        firebaseAnalytics?.logEvent(
          GalleryEvent.GENERATE_ACTION.id,
          bundleOf("capability_name" to task.id, "model_id" to model.name),
        )
      }
    },
    onRunAgainClicked = { model, message ->
      if (message is ChatMessageText) {
        viewModel.runAgain(
          model = model,
          message = message,
          taskId = taskId,
          onError = { errorMessage ->
            viewModel.handleError(
              context = context,
              task = task,
              model = model,
              errorMessage = errorMessage,
              modelManagerViewModel = modelManagerViewModel,
            )
          },
          allowThinking = allowThinking,
        )
      }
    },
    onBenchmarkClicked = { _, _, _, _ -> },
    onResetSessionClicked = { model ->
      if (onResetSessionClickedOverride != null) {
        onResetSessionClickedOverride(task, model)
      } else {
        viewModel.resetSession(
          task = task,
          model = model,
          supportImage = showImagePicker,
          supportAudio = showAudioPicker,
        )
      }
    },
    showStopButtonInInputWhenInProgress = true,
    onStopButtonClicked = { model -> viewModel.stopResponse(model = model) },
    onSkillClicked = onSkillClicked,
    navigateUp = navigateUp,
    modifier = modifier,
    composableBelowMessageList = wrappedComposableBelowMessageList,
    showImagePicker = showImagePicker,
    emptyStateComposable = emptyStateComposable,
    allowEditingSystemPrompt = allowEditingSystemPrompt,
    curSystemPrompt = curSystemPrompt,
    onSystemPromptChanged = onSystemPromptChanged,
    sendMessageTrigger = sendMessageTrigger,
    showAudioPicker = showAudioPicker,
    onForgeNeuronClicked = { viewModel.forgeNeuron(selectedModel) },
    onChatHistoryClicked = onChatHistoryClicked,
    logBoxManager = logBoxManager,
  )
}
