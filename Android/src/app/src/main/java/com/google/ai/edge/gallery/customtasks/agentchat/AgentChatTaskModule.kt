/*
 * Copyright 2025 Google LLC
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
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.litertlm.tool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import dagger.multibindings.IntoSet
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
      description = "Chat with CLU (Gemma 4) using agentic skills, Python, BusyBox terminal, and BrainBox long-term memory",
      shortDescription = "CLU/BOX — on-device Gemma agent with memory",
      docUrl = "https://github.com/Flynn013/CLU-BOX",
      sourceCodeUrl =
        "https://github.com/Flynn013/CLU-BOX",
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
      defaultSystemPrompt =
        """
        You are CLU, an on-device AI. Use tools to act — do not describe steps.

        WORKFLOW: memorySearch first for context → act with tools → verify results.
        TOOLS: ___SKILLS___
        FILES: fileBoxWrite only. PYTHON: PYTHON_EXEC. SHELL: shellExecute.
        """
          .trimIndent(),
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (String) -> Unit,
  ) {
    val smvm = agentTools.skillManagerViewModel
      ?: run { onDone("SkillManagerViewModel not attached"); return }
    smvm.loadSkills {
      try {
        val isCloud = model.runtimeType == RuntimeType.GEMINI_CLOUD
          || model.runtimeType == RuntimeType.ANTHROPIC_CLOUD
          || model.runtimeType == RuntimeType.MANUAL_API

        agentTools.engine = if (isCloud) AgentEngine.CLOUD else AgentEngine.LOCAL

        // ── RAG: inject core memories into system prompt ─────────────────
        val db = com.google.ai.edge.gallery.data.brainbox.GraphDatabase.getInstance(context)
        val coreMemContext = try {
          kotlinx.coroutines.runBlocking {
            com.google.ai.edge.gallery.data.brainbox.RagInjector.buildCoreContext(db.brainBoxDao())
          }
        } catch (e: Exception) {
          android.util.Log.w("AgentChatTask", "RAG core context failed: ${e.message}")
          ""
        }

        val finalPrompt = SystemPromptManager.build(
          engine = agentTools.engine,
          basePrompt = task.defaultSystemPrompt,
          skillRegistry = agentTools.skillRegistry,
          coreMemContext = coreMemContext,
        )

        if (isCloud) {
          model.runtimeHelper.initialize(
            context = context,
            model = model,
            supportImage = true,
            supportAudio = true,
            onDone = onDone,
            systemInstruction = smvm.getSystemPrompt(finalPrompt),
            tools = listOf(tool(agentTools)),
            enableConversationConstrainedDecoding = false,
          )
        } else {
          LlmChatModelHelper.initialize(
            context = context,
            model = model,
            supportImage = true,
            supportAudio = true,
            onDone = onDone,
            systemInstruction = smvm.getSystemPrompt(finalPrompt),
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
        }
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
    model.runtimeHelper.cleanUp(model = model, onDone = onDone)
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
