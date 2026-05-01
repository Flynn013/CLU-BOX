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
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.AskInfoAgentAction
import com.google.ai.edge.gallery.common.CallJsAgentAction
import com.google.ai.edge.gallery.common.SkillProgressAgentAction
import com.google.ai.edge.gallery.common.decodeBase64ToBitmap
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
import com.google.ai.edge.gallery.ui.llmchat.LlmChatScreen
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
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
 * loop is forcibly halted.  This prevents a runaway agentic loop from
 * crashing the app through memory exhaustion, context overflow, or native
 * layer errors.  The counter resets whenever the **user** sends a new message.
 */
private const val MAX_AUTONOMOUS_ITERATIONS = 25

/**
 * Cooldown delay (ms) inserted between consecutive autonomous loop iterations.
 * Gives the system breathing room (GC, UI, OS memory management) and prevents
 * CPU starvation / ANR on the main thread.
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

  // Initialise the app context once for the TokenMonitor file-writing path.
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
  /** Manages error-retry logic and status emission for the agentic loop. */
  val loopManager = remember { AgentLoopManager() }
  /** Tracks how many consecutive autonomous re-triggers have fired without user input. */
  var autonomousIterationCount by remember { mutableStateOf(0) }
  var showAlertForDisabledSkill by remember { mutableStateOf(false) }
  var disabledSkillName by remember { mutableStateOf("") }
  var showChatHistorySheet by remember { mutableStateOf(false) }
  var chatHistoryRefreshKey by remember { mutableStateOf(0) }
  val chatHistoryDao = remember(context) { GraphDatabase.getInstance(context).chatHistoryDao() }
  val chatHistoryScope = rememberCoroutineScope()
  val autonomousLoopScope = rememberCoroutineScope()
  var showGeminiApiKeyDialog by remember { mutableStateOf(false) }

  // Monitor selected model — prompt for API key when CLOUD_CLU model is selected without one.
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val selectedModel = modelManagerUiState.selectedModel
  LaunchedEffect(selectedModel) {
    // ── Agentic Context Router: sync engine with model backend ────────────
    agentTools.engine = if (selectedModel.runtimeType == RuntimeType.GEMINI_CLOUD) {
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
    onFirstToken = { model ->
      updateProgressPanel(viewModel = viewModel, model = model, agentTools = agentTools)
    },
    onInferenceError = { errorMessage ->
      // ── Error recovery via AgentLoopManager ────────────────────────
      // Ask the loop manager whether we should retry or halt.
      // FIX: Cast errorMessage (Any?) to String.
      val errorStr = errorMessage?.toString() ?: "Unknown inference error"
      val shouldRetry = loopManager.onError(errorStr)
      if (shouldRetry) {
        Log.w(TAG, "Inference error — retrying (${AgentLoopManager.MAX_RETRIES - loopManager.errorCount} attempt(s) left)")
        // Inject a SYSTEM re-evaluation message so the model understands what
        // went wrong and can try a different approach on the next turn.
        val model = modelManagerViewModel.uiState.value.selectedModel
        val retryMsg = loopManager.formatRetrySystemMessage("Inference engine", errorStr)
        viewModel.addMessage(
          model = model,
          message = ChatMessageText(content = retryMsg, side = ChatSide.AGENT),
        )
        // Re-trigger inference with the system message as the new user turn.
        autonomousLoopScope.launch {
          delay(AUTONOMOUS_LOOP_COOLDOWN_MS)
          sendMessageTrigger = SendMessageTrigger(
            model = model,
            messages = listOf(ChatMessageText(content = retryMsg, side = ChatSide.USER)),
          )
        }
      } else {
        // Retry budget exhausted — break the loop and ask the user for help.
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
      // ── Success: reset the error retry counter ──────────────────────
      loopManager.onSuccess()

      // Show any image produced by tools.
      // FIX: Use explicit casting for the missing properties.
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
        // Clean up.
        agentTools.resultImageToShow = null
      }

      // Show any webview produced by tools.
      // FIX: Use explicit casting for the missing properties.
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
        // Clean up.
        agentTools.resultWebviewToShow = null
      }

      updateProgressPanel(viewModel = viewModel, model = model, agentTools = agentTools)

      // Phase 5: Autonomous Supervisor — if a pending task was queued via taskQueueUpdate,
      // silently re-trigger inference with the next task description.
      val pendingTask = agentTools.pendingTaskDescription
      if (pendingTask != null) {
        // ── Circuit Breaker: hard cap on consecutive autonomous iterations ──
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
          // Cooldown: brief delay between iterations to give the system breathing room.
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
      } else {
        // No pending task — the loop concluded naturally. Reset the counter.
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
      // Use rememberUpdatedState to ensure that LaunchedEffect captures the
      // latest active model when the model is switched during an ongoing skill execution.
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
                // Set up a safety net timeout so we NEVER hang the chat or tool execution
                launch {
                  delay(60000L) // 60 seconds max
                  if (!action.result.isCompleted) {
                    Log.e(TAG, "JS Execution timed out, completing with error.")
                    action.result.complete(
                      "{\"error\": \"Skill execution timed out. Please check network connection.\"}"
                    )
                  }
                }

                // Load url.
                suspendCancellableCoroutine<Unit> { continuation ->
                  chatWebViewClient.setPageLoadListener {
                    chatWebViewClient.setPageLoadListener(null)
                    continuation.resume(Unit)
                  }
                  Log.d(TAG, "Loading url: ${action.url}")
                  webViewRef?.loadUrl(action.url)
                }

                // Execute JS.
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
              askInfoInputValue = "" // Reset input
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
            // Create a LogMessage from the ConsoleMessage and add it to the progress panel.
            val logMessage =
              LogMessage(
                level =
                  when (curConsoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.LOG -> LogMessageLevel.Info
                    ConsoleMessage.MessageLevel.ERROR -> LogMessageLevel.Error
     