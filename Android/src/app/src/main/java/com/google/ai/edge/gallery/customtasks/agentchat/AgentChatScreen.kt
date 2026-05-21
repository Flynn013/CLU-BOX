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

package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.AskInfoAgentAction
import com.google.ai.edge.gallery.common.CallJsAgentAction
import com.google.ai.edge.gallery.common.SkillProgressAgentAction
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.BaseGalleryWebViewClient
import com.google.ai.edge.gallery.ui.common.GalleryWebView
import com.google.ai.edge.gallery.ui.common.buildTrackableUrlAnnotatedString
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageCollapsableProgressPanel
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageImage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageInfo
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatHistorySheet
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWebView
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.LogMessage
import com.google.ai.edge.gallery.ui.common.chat.LogMessageLevel
import com.google.ai.edge.gallery.ui.common.chat.SendMessageTrigger
import com.google.ai.edge.gallery.ui.llmchat.ContextWindowPager
import com.google.ai.edge.gallery.ui.llmchat.LlmChatScreen
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.customtasks.agentchat.ui.ToolExecutionBox
import com.google.ai.edge.gallery.data.brainbox.GraphDatabase
import com.google.ai.edge.gallery.runtime.geminicloud.GeminiApiKeyDialog
import com.google.ai.edge.gallery.runtime.geminicloud.GeminiApiKeyStore
import com.google.ai.edge.gallery.ui.theme.neonGreen
import com.google.ai.edge.litertlm.tool
import java.lang.Exception
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

private const val TAG = "AGAgentChatScreen"

/**
 * Maximum number of consecutive autonomous loop iterations allowed before the
 * loop is forcibly halted.
 */
private const val MAX_AUTONOMOUS_ITERATIONS = 100

/**
 * Cooldown delay (ms) inserted between consecutive autonomous loop iterations.
 */
private const val AUTONOMOUS_LOOP_COOLDOWN_MS = 1500L

private val chatViewJavascriptInterface = ChatWebViewJavascriptInterface()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentChatScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  agentTools: AgentTools,
  viewModel: LlmChatViewModel = hiltViewModel(),
  skillManagerViewModel: SkillManagerViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  agentTools.context = context
  agentTools.skillManagerViewModel = skillManagerViewModel
  agentTools.brainBoxDao = remember(context) { GraphDatabase.getInstance(context).brainBoxDao() }
  agentTools.vectorEngine = remember(context) { com.google.ai.edge.gallery.data.brainbox.VectorEngine(context) }
  agentTools.terminalSessionManager = remember(context) {
    com.google.ai.edge.gallery.data.TerminalSessionManager(context)
  }

  LaunchedEffect(Unit) { viewModel.initAppContext(context) }
  val density = LocalDensity.current
  val windowInfo = LocalWindowInfo.current
  val screenWidthDp = remember { with(density) { windowInfo.containerSize.width.toDp() } }
  var showSkillManagerBottomSheet by remember { mutableStateOf(false) }
  var showAskInfoDialog by remember { mutableStateOf(false) }
  var currentAskInfoAction by remember { mutableStateOf<AskInfoAgentAction?>(null) }
  var askInfoInputValue by remember { mutableStateOf("") }
  var webViewRef: WebView? by remember { mutableStateOf(null) }
  val chatWebViewClient = remember { ChatWebViewClient(context = context) }
  var curSystemPrompt by remember { mutableStateOf(task.defaultSystemPrompt) }
  val systemPromptUpdatedMessage = stringResource(R.string.system_prompt_updated)
  var sendMessageTrigger by remember { mutableStateOf<SendMessageTrigger?>(null) }
  val loopManager = remember { AgentLoopManager() }
  var autonomousIterationCount by remember { mutableStateOf(0) }
  var showAlertForDisabledSkill by remember { mutableStateOf(false) }
  var disabledSkillName by remember { mutableStateOf("") }
  var showChatHistorySheet by remember { mutableStateOf(false) }
  var chatHistoryRefreshKey by remember { mutableStateOf(0) }
  val chatHistoryDao = remember(context) { GraphDatabase.getInstance(context).chatHistoryDao() }
  val chatHistoryScope = rememberCoroutineScope()
  val autonomousLoopScope = rememberCoroutineScope()
  var showGeminiApiKeyDialog by remember { mutableStateOf(false) }
  // Thinking mode: initialised from the task default so Gemma 4 starts with
  // thinking enabled; user can toggle the THINK chip to override at runtime.
  var thinkingModeEnabled by remember { mutableStateOf(task.allowThinking()) }

  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val selectedModel = modelManagerUiState.selectedModel
  LaunchedEffect(selectedModel) {
    agentTools.engine = if (selectedModel.runtimeType == RuntimeType.GEMINI_CLOUD
        || selectedModel.runtimeType == RuntimeType.ANTHROPIC_CLOUD) {
      AgentEngine.CLOUD
    } else {
      AgentEngine.LOCAL
    }
    if (selectedModel.runtimeType == RuntimeType.GEMINI_CLOUD &&
      !GeminiApiKeyStore.hasApiKey(context)
    ) {
      showGeminiApiKeyDialog = true
    }
  }

  LlmChatScreen(
    modelManagerViewModel = modelManagerViewModel,
    taskId = BuiltInTaskId.LLM_AGENT_CHAT,
    navigateUp = navigateUp,
    allowThinkingOverride = thinkingModeEnabled,
    onFirstToken = { model ->
      updateProgressPanel(viewModel = viewModel, model = model, agentTools = agentTools)
    },
    onInferenceError = { errorMessage ->
      val errorStr = errorMessage?.toString() ?: "Unknown inference error"
      val shouldRetry = loopManager.onError(errorStr)
      if (shouldRetry) {
        Log.w(TAG, "Inference error — retrying (${AgentLoopManager.MAX_RETRIES - loopManager.errorCount} attempt(s) left)")
        val model = modelManagerViewModel.uiState.value.selectedModel
        val retryMsg = loopManager.formatRetrySystemMessage("Inference engine", errorStr)
        viewModel.addMessage(
          model = model,
          message = ChatMessageText(content = retryMsg, side = ChatSide.AGENT),
        )
        autonomousLoopScope.launch {
          delay(AUTONOMOUS_LOOP_COOLDOWN_MS)
          sendMessageTrigger = SendMessageTrigger(
            model = model,
            messages = listOf(ChatMessageText(content = retryMsg, side = ChatSide.USER)),
          )
        }
      } else {
        Log.w(TAG, "Inference error budget exhausted — halting agentic loop")
        agentTools.pendingTaskDescription = null
        autonomousIterationCount = 0
        val model = modelManagerViewModel.uiState.value.selectedModel
        viewModel.addMessage(
          model = model,
          message = ChatMessageText(
            content = loopManager.buildExhaustedMessage(agentTools.engine),
            side = ChatSide.AGENT,
          ),
        )
        loopManager.reset()
      }
    },
    onGenerateResponseDone = { model ->
      loopManager.onSuccess()

      val resultImage = agentTools.resultImageToShow as? ResultImage
      resultImage?.let { image ->
        image.base64?.let { base64 ->
          decodeBase64ToBitmap(base64String = base64)?.let { bitmap ->
            viewModel.addMessage(
              model = model,
              message =
                ChatMessageImage(
                  bitmaps = listOf(bitmap),
                  imageBitMaps = listOf(bitmap.asImageBitmap()),
                  side = ChatSide.AGENT,
                  maxSize = (screenWidthDp.value * 0.8).toInt(),
                  latencyMs = -1.0f,
                  hideSenderLabel = true,
                ),
            )
          }
        }
        agentTools.resultImageToShow = null
      }

      val resultWebview = agentTools.resultWebviewToShow as? ResultWebView
      resultWebview?.let { webview ->
        val url = webview.url ?: ""
        val iframe = webview.iframe == true
        val aspectRatio = webview.aspectRatio ?: 1.333f
        viewModel.addMessage(
          model = model,
          message =
            ChatMessageWebView(
              url = url,
              iframe = iframe,
              aspectRatio = aspectRatio,
              hideSenderLabel = true,
            ),
        )
        agentTools.resultWebviewToShow = null
      }

      updateProgressPanel(viewModel = viewModel, model = model, agentTools = agentTools)

      val pendingTask = agentTools.pendingTaskDescription
      if (pendingTask != null) {
        val maxLoops = agentTools.governor.maxLoops
        if (autonomousIterationCount >= maxLoops) {
          Log.w(
            TAG,
            "Autonomous loop HALTED: reached $maxLoops iterations (engine=${agentTools.engine}). " +
              "Clearing pending task to prevent crash."
          )
          agentTools.pendingTaskDescription = null
          viewModel.addMessage(
            model = model,
            message = ChatMessageText(
              content = "[System: Autonomous loop halted after $maxLoops " +
                "iterations to protect device stability. Send a new message to continue.]",
              side = ChatSide.AGENT,
            ),
          )
        } else {
          autonomousIterationCount++
          agentTools.pendingTaskDescription = null
          Log.d(TAG, "Autonomous loop: iteration $autonomousIterationCount — re-triggering inference with task='$pendingTask'")

          // Auto-compress context if near capacity before continuing the loop.
          val allMsgs = viewModel.uiState.value.messagesByModel[model.name] ?: mutableListOf()
          val textMsgs = allMsgs.filterIsInstance<ChatMessageText>().toMutableList()
          val engine = agentTools.engine

          if (ContextWindowPager.shouldPrune(textMsgs, engine)) {
            Log.d(TAG, "Context near full — auto-compressing before iteration $autonomousIterationCount")
            autonomousLoopScope.launch {
              val retained = ContextWindowPager.pruneAndArchive(
                messages = textMsgs,
                engine = engine,
                brainBoxDao = agentTools.brainBoxDao,
                sessionLabel = "ctx_${System.currentTimeMillis()}",
              )
              resetSessionWithCurrentSkills(
                viewModel, modelManagerViewModel, skillManagerViewModel,
                task, curSystemPrompt, agentTools,
                onDone = { resetModel ->
                  retained.forEach { msg -> viewModel.addMessage(model = resetModel, message = msg) }
                  autonomousLoopScope.launch {
                    delay(AUTONOMOUS_LOOP_COOLDOWN_MS)
                    sendMessageTrigger = SendMessageTrigger(
                      model = resetModel,
                      messages = listOf(ChatMessageText(content = "[CONTINUE] $pendingTask", side = ChatSide.USER)),
                    )
                  }
                },
              )
            }
          } else {
            autonomousLoopScope.launch {
              delay(AUTONOMOUS_LOOP_COOLDOWN_MS)
              sendMessageTrigger = SendMessageTrigger(
                model = model,
                messages = listOf(
                  ChatMessageText(
                    content = "[CONTINUE] $pendingTask",
                    side = ChatSide.USER,
                  ),
                ),
              )
            }
          }
        }
      } else {
        autonomousIterationCount = 0
      }
    },
    onResetSessionClickedOverride = { task, model ->
      loopManager.reset()
      autonomousIterationCount = 0
      resetSessionWithCurrentSkills(
        viewModel,
        modelManagerViewModel,
        skillManagerViewModel,
        task,
        curSystemPrompt,
        agentTools,
      )
    },
    onSkillClicked = { showSkillManagerBottomSheet = true },
    showImagePicker = true,
    showAudioPicker = true,
    composableBelowMessageList = { model ->
      val actionChannel = agentTools.actionChannel
      val doneIcon = ImageVector.vectorResource(R.drawable.skill)
      val currentModel by androidx.compose.runtime.rememberUpdatedState(model)
      LaunchedEffect(actionChannel) {
        for (action in actionChannel) {
          Log.d(TAG, "Handling action: $action")
          when (action) {
            is SkillProgressAgentAction -> {
              viewModel.updateCollapsableProgressPanelMessage(
                model = currentModel,
                title = action.label,
                inProgress = action.inProgress,
                doneIcon = doneIcon,
                addItemTitle = action.addItemTitle,
                addItemDescription = action.addItemDescription,
                customData = action.customData,
              )
            }
            is CallJsAgentAction -> {
              try {
                launch {
                  delay(60000L)
                  if (!action.result.isCompleted) {
                    Log.e(TAG, "JS Execution timed out, completing with error.")
                    action.result.complete(
                      "{\"error\": \"Skill execution timed out. Please check network connection.\"}"
                    )
                  }
                }

                suspendCancellableCoroutine<Unit> { continuation ->
                  chatWebViewClient.setPageLoadListener {
                    chatWebViewClient.setPageLoadListener(null)
                    continuation.resume(Unit)
                  }
                  Log.d(TAG, "Loading url: ${action.url}")
                  webViewRef?.loadUrl(action.url)
                }

                Log.d(TAG, "Start to run js")
                chatViewJavascriptInterface.onResultListener = { result ->
                  Log.d(TAG, "Got result:\n$result")
                  action.result.complete(result)
                }

                val safeData = JSONObject.quote(action.data)
                val safeSecret = JSONObject.quote(action.secret)
                val script =
                  """
                  (async function() {
                      var startTs = Date.now();
                      while(true) {
                        if (typeof ai_edge_gallery_get_result === 'function') {
                          break;
                        }
                        await new Promise(resolve=>{
                          setTimeout(resolve, 100)
                        });
                        if (Date.now() - startTs > 10000) {
                          break;
                        }
                      }
                      var result = await ai_edge_gallery_get_result($safeData, $safeSecret);
                      AiEdgeGallery.onResultReady(result);
                  })()
                  """
                    .trimIndent()
                webViewRef?.evaluateJavascript(script, null)
              } catch (e: Exception) {
                action.result.completeExceptionally(e)
              }
            }
            is AskInfoAgentAction -> {
              currentAskInfoAction = action
              askInfoInputValue = ""
              showAskInfoDialog = true
            }
          }
        }
      }

      GalleryWebView(
        modifier = Modifier.size(300.dp),
        onWebViewCreated = { webView ->
          webViewRef = webView
          webView.addJavascriptInterface(chatViewJavascriptInterface, "AiEdgeGallery")
        },
        customWebViewClient = chatWebViewClient,
        onConsoleMessage = { consoleMessage ->
          consoleMessage?.let { curConsoleMessage ->
            val logMessage =
              LogMessage(
                level =
                  when (curConsoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.LOG -> LogMessageLevel.Info
                    ConsoleMessage.MessageLevel.ERROR -> LogMessageLevel.Error
                    ConsoleMessage.MessageLevel.WARNING -> LogMessageLevel.Warning
                    else -> LogMessageLevel.Info
                  },
                source = curConsoleMessage.sourceId(),
                lineNumber = curConsoleMessage.lineNumber(),
                message = curConsoleMessage.message(),
              )
            viewModel.addLogMessageToLastCollapsableProgressPanel(
              model = model,
              logMessage = logMessage,
            )
            Log.d(
              TAG,
              "${curConsoleMessage.message()} " +
                "-- From line ${curConsoleMessage.lineNumber()} of ${curConsoleMessage.sourceId()}",
            )
          }
        },
      )
    },
    allowEditingSystemPrompt = true,
    curSystemPrompt = curSystemPrompt,
    onSystemPromptChanged = { newPrompt ->
      curSystemPrompt = newPrompt
      resetSessionWithCurrentSkills(
        viewModel,
        modelManagerViewModel,
        skillManagerViewModel,
        task,
        curSystemPrompt,
        agentTools,
        onDone = { model ->
          viewModel.addMessage(
            model = model,
            message = ChatMessageInfo(content = systemPromptUpdatedMessage),
          )
        },
      )
    },
    emptyStateComposable = { _ ->
      Box(modifier = Modifier.fillMaxSize())
    },
    sendMessageTrigger = sendMessageTrigger,
    onChatHistoryClicked = { showChatHistorySheet = true },
    composableAboveInput = {
      val skillsState by skillManagerViewModel.uiState.collectAsState()
      val selectedSkills = skillsState.skills.filter { it.skill.selected }

      Column(modifier = Modifier.fillMaxWidth()) {
        // ── Context fill progress bar ──────────────────────────────────────
        // Thin 2dp bar under the chat banner. Green → orange → red as context fills.
        val chatUiStateCtx by viewModel.uiState.collectAsState()
        val modelMgrCtx by modelManagerViewModel.uiState.collectAsState()
        val ctxMsgs = chatUiStateCtx.messagesByModel[modelMgrCtx.selectedModel.name]
          ?.filterIsInstance<ChatMessageText>() ?: emptyList()
        val ctxFill = (ctxMsgs.sumOf { it.content.length }.toFloat()
          / (ContextWindowPager.budgetFor(agentTools.engine) * 4)).coerceIn(0f, 1f)
        val ctxBarColor = when {
          ctxFill >= 0.85f -> MaterialTheme.colorScheme.error
          ctxFill >= 0.60f -> Color(0xFFFF9800)
          else -> neonGreen
        }
        LinearProgressIndicator(
          progress = ctxFill,
          modifier = Modifier.fillMaxWidth().height(2.dp),
          color = ctxBarColor,
          trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        )

        // ── Thinking mode toggle + live skill chip rail ─────────────────
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          FilterChip(
            selected = thinkingModeEnabled,
            onClick = { thinkingModeEnabled = !thinkingModeEnabled },
            label = {
              Text(
                "THINK",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
              )
            },
            leadingIcon = {
              Icon(
                imageVector = Icons.Outlined.Psychology,
                contentDescription = "Thinking mode",
                modifier = Modifier.size(14.dp),
              )
            },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = neonGreen.copy(alpha = 0.18f),
              selectedLabelColor = neonGreen,
              selectedLeadingIconColor = neonGreen,
            ),
          )
          selectedSkills.take(5).forEach { skillState ->
            SuggestionChip(
              onClick = { showSkillManagerBottomSheet = true },
              label = {
                Text(
                  skillState.skill.name.take(12),
                  fontFamily = FontFamily.Monospace,
                  fontSize = 10.sp,
                )
              },
            )
          }
          if (selectedSkills.size > 5) {
            SuggestionChip(
              onClick = { showSkillManagerBottomSheet = true },
              label = {
                Text(
                  "+${selectedSkills.size - 5}",
                  fontFamily = FontFamily.Monospace,
                  fontSize = 10.sp,
                )
              },
            )
          }
        }

        // ── Tool execution cards ────────────────────────────────────────
        val activeTool by agentTools.governor.activeTool.collectAsState()
        val toolHistory by agentTools.governor.toolHistory.collectAsState()
        val recentHistory = toolHistory.takeLast(3)
        if (recentHistory.isNotEmpty() || activeTool != null) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            recentHistory.forEach { exec ->
              ToolExecutionBox(execution = exec)
            }
            activeTool?.let { exec ->
              ToolExecutionBox(execution = exec)
            }
          }
        }
      }
    },
  )

  if (showAskInfoDialog && currentAskInfoAction != null) {
    val action = currentAskInfoAction!!
    SecretEditorDialog(
      title = action.dialogTitle,
      fieldLabel = action.fieldLabel,
      value = askInfoInputValue,
      onValueChange = { askInfoInputValue = it },
      onDone = {
        action.result.complete(askInfoInputValue)
        showAskInfoDialog = false
        currentAskInfoAction = null
      },
      onDismiss = {
        action.result.complete("")
        showAskInfoDialog = false
        currentAskInfoAction = null
      },
    )
  }

  if (showSkillManagerBottomSheet) {
    SkillManagerBottomSheet(
      agentTools = agentTools,
      skillManagerViewModel = skillManagerViewModel,
      onDismiss = { selectedSkillsChanged ->
        showSkillManagerBottomSheet = false
        if (selectedSkillsChanged) {
          Log.d(TAG, "Selected skill changed. Resetting conversation.")
          resetSessionWithCurrentSkills(
            viewModel,
            modelManagerViewModel,
            skillManagerViewModel,
            task,
            curSystemPrompt,
            agentTools,
          )
        }
      },
    )
  }

  if (showAlertForDisabledSkill) {
    AlertDialog(
      onDismissRequest = { showAlertForDisabledSkill = false },
      title = { Text("The \"$disabledSkillName\" skill is currently disabled") },
      text = { Text(stringResource(R.string.enable_skill_dialog_content)) },
      confirmButton = {
        Button(onClick = { showAlertForDisabledSkill = false }) {
          Text(stringResource(R.string.ok))
        }
      },
    )
  }

  if (showChatHistorySheet) {
    key(chatHistoryRefreshKey) {
      ChatHistorySheet(
        chatHistoryDao = chatHistoryDao,
        onDismiss = { showChatHistorySheet = false },
        onSessionSelected = { _, modelName ->
          val matchingModel = task.models.find { it.name == modelName }
          if (matchingModel != null) {
            modelManagerViewModel.selectModel(model = matchingModel)
          }
          showChatHistorySheet = false
        },
        onSessionDeleted = { sessionTaskId, modelName ->
          val matchingModel = task.models.find { it.name == modelName }
          if (matchingModel != null) {
            viewModel.wipeGrid(sessionTaskId, matchingModel)
          } else {
            chatHistoryScope.launch {
              chatHistoryDao.deleteMessages(taskId = sessionTaskId, modelName = modelName)
            }
          }
          chatHistoryRefreshKey++
        },
      )
    }
  }

  if (showGeminiApiKeyDialog) {
    GeminiApiKeyDialog(
      onDismiss = { showGeminiApiKeyDialog = false },
      onApiKeySaved = { key ->
        GeminiApiKeyStore.setApiKey(context, key)
        showGeminiApiKeyDialog = false
      },
    )
  }
}

private fun updateProgressPanel(viewModel: LlmChatViewModel, model: Model, agentTools: AgentTools) {
  val lastProgressPanelMessage =
    viewModel.getLastMessageWithType(
      model = model,
      type = ChatMessageType.COLLAPSABLE_PROGRESS_PANEL,
    )
  if (
    lastProgressPanelMessage != null &&
            lastProgressPanelMessage is ChatMessageCollapsableProgressPanel &&
            lastProgressPanelMessage.inProgress
  ) {
    // Finalize any in-progress panel by replacing the active verb with its past tense.
    val title = lastProgressPanelMessage.title
    val doneTitle = when {
      title.startsWith("Loading")    -> title.replaceFirst("Loading", "Loaded")
      title.startsWith("Calling")    -> title.replaceFirst("Calling", "Called")
      title.startsWith("Executing")  -> title.replaceFirst("Executing", "Executed")
      title.startsWith("Writing")    -> title.replaceFirst("Writing", "Written")
      title.startsWith("Reading")    -> title.replaceFirst("Reading", "Read")
      title.startsWith("Searching")  -> title.replaceFirst("Searching", "Searched")
      title.startsWith("AppControl") -> title.replaceFirst("AppControl", "Done")
      title.startsWith("appControl") -> title.replaceFirst("appControl", "Done")
      title.startsWith("Fetching")   -> title.replaceFirst("Fetching", "Fetched")
      title.startsWith("Diff")       -> title.replaceFirst("Diff", "Diffed")
      title.startsWith("PYTHON_EXEC")-> "Python executed"
      else                           -> title // already final or unknown pattern
    }
    if (doneTitle != title) {
      agentTools.sendAgentAction(
        SkillProgressAgentAction(label = doneTitle, inProgress = false)
      )
    }
  }
}

private fun resetSessionWithCurrentSkills(
  viewModel: LlmChatViewModel,
  modelManagerViewModel: ModelManagerViewModel,
  skillManagerViewModel: SkillManagerViewModel,
  task: Task,
  curSystemPrompt: String,
  agentTools: AgentTools,
  onDone: (Model) -> Unit = {},
) {
  val model = modelManagerViewModel.uiState.value.selectedModel
  viewModel.resetSession(
    task = task,
    model = model,
    systemInstruction =
      skillManagerViewModel.getSystemPrompt(
        curSystemPrompt,
      ),
    tools = listOf(tool(agentTools)),
    supportImage = true,
    supportAudio = true,
    onDone = { onDone(model) },
    enableConversationConstrainedDecoding = false,
  )
}

class ChatWebViewJavascriptInterface {
  var onResultListener: ((String) -> Unit)? = null

  @JavascriptInterface
  fun onResultReady(result: String) {
    onResultListener?.invoke(result)
  }
}

class ChatWebViewClient(val context: Context) : BaseGalleryWebViewClient(context = context) {
  private var onPageLoaded: (() -> Unit)? = null

  fun setPageLoadListener(listener: (() -> Unit)?) {
    onPageLoaded = listener
  }

  override fun onPageFinished(view: WebView?, url: String?) {
    super.onPageFinished(view, url)
    Log.d(TAG, "page loaded")
    onPageLoaded?.invoke()
  }
}

fun decodeBase64ToBitmap(base64String: String): android.graphics.Bitmap? {
  return try {
    val decodedBytes = android.util.Base64.decode(base64String, android.util.Base64.DEFAULT)
    android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
  } catch (e: Exception) {
    null
  }
}

data class ResultImage(val base64: String?)
data class ResultWebView(val url: String?, val iframe: Boolean?, val aspectRatio: Float?)
