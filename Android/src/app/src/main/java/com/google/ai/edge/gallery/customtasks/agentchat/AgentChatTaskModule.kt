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

package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.runtime.geminicloud.GeminiCloudModelHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.litertlm.tool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

class AgentChatTask @Inject constructor() : CustomTask {
  private val agentTools = AgentTools()

  override val task: Task =
    Task(
      id = BuiltInTaskId.LLM_AGENT_CHAT,
      label = "CLU/BOX Chat",
      category = Category.LLM,
      iconVectorResourceId = R.drawable.agent,
      newFeature = true,
      models = mutableListOf(),
      description = "Chat with on-device AI using CLU/BOX skills and BrainBox memory",
      shortDescription = "CLU/BOX agentic chat interface",
      docUrl = "https://github.com/Flynn013/CLU-BOX",
      sourceCodeUrl =
        "https://github.com/Flynn013/CLU-BOX",
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
      defaultSystemPrompt =
        """
        You are CLU, the on-device AI assistant powering CLU/BOX. When CLU/BOX MEMORY context is provided, use it as recalled knowledge.

        TOOL RULES: Invoke tools via the native function-calling mechanism. Do NOT write tool-call tags or JSON manually. After a tool returns, read the result and continue.

        For each request:
        1. Find the best skill from: ___SKILLS___
        2. If found, call load_skill to read instructions. If a built-in tool fits better, use it directly.
        3. Follow instructions exactly. Output ONLY the final result (one-sentence summary + result). No intermediate thoughts.

        FILE RULE: NEVER use shell (echo/cat/nano) to create/edit files. Use fileBoxWrite exclusively.
        WRITE-THEN-RUN: Step1: fileBoxWrite(path, content). Step2: shellExecute(command) to run it.
        MULTI-FILE: Use architectInit once with blueprint, then workerExecute per file.
        TESTING: After writing code, use shellExecute or editorTerminalPipe to test. If fileBoxWrite returns syntax error, fix and rewrite immediately.
        """
          .trimIndent(),
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (String) -> Unit,
  ) {
    agentTools.skillManagerViewModel.loadSkills {
      try {
        val helper = if (model.runtimeType == RuntimeType.GEMINI_CLOUD) {
          GeminiCloudModelHelper.cacheApiKey(context)
          GeminiCloudModelHelper
        } else {
          LlmChatModelHelper
        }
        helper.initialize(
          context = context,
          model = model,
          supportImage = true,
          supportAudio = true,
          onDone = onDone,
          systemInstruction =
            agentTools.skillManagerViewModel.getSystemPrompt(
              // Boot Sequence: prepend the Genesis Identity Block to the
              // base prompt so the LLM always starts with CLU's core
              // identity and directives.
              agentTools.skillRegistry.buildFinalSystemPrompt(task.defaultSystemPrompt),
              toolsSummary = agentTools.getToolsSummary(),
            ),
          tools = listOf(tool(agentTools)),
          // Constrained decoding is intentionally DISABLED for Agent Chat.
          // General-purpose Gemma models (E2B/E4B) were not fine-tuned with the
          // full 21-tool catalog grammar. Enabling constrained decoding restricts
          // the model from emitting <|tool_call> tokens, producing garbled output
          // like <|"|> which breaks the tool execution loop entirely.
          // The litertlm ToolSet framework handles tool-call routing natively
          // without needing vocabulary constraints.
          enableConversationConstrainedDecoding = false,
        )
      } catch (e: Throwable) {
        android.util.Log.e("CLU_CRASH_REPORT", "Model initialization failed: ${e.stackTraceToString()}")
        onDone("Model initialization failed: ${e.message ?: "unknown error"}")
      }
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    val helper = if (model.runtimeType == RuntimeType.GEMINI_CLOUD) {
      GeminiCloudModelHelper
    } else {
      LlmChatModelHelper
    }
    helper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    AgentChatScreen(
      task = task,
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      agentTools = agentTools,
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object AgentChatTaskModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask {
    return AgentChatTask()
  }
}
