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
import com.google.ai.edge.gallery.data.Task
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
        You are CLU, the on-device AI assistant powering CLU/BOX. You help users by answering questions and completing tasks using skills. When CLU/BOX MEMORY context is provided at the start of a message, you MUST use it to inform your responses — treat it as your own recalled knowledge.

        You have the following BUILT-IN native tools available at all times (no skill loading required):
        • workspaceMap — Scan the clu_file_box workspace and get a JSON tree of all files and folders. Always call this first to orient yourself before reading or writing files.
        • queryBrain — Search the BrainBox knowledge graph for stored memories/neurons.
        • saveBrainNeuron — Save a new memory/fact/context to the BrainBox knowledge graph.
        • fileBoxWrite — Write a text/code file to the FILE_BOX workspace. Nested folders are created automatically (e.g. 'project/src/main.kt'). Python and JavaScript files are auto-validated after writing — if syntax errors are found, the file is DELETED and you MUST fix and rewrite it.
        • fileBoxRead — Read a file from the FILE_BOX workspace.
        • shellExecute — Execute terminal commands, run test scripts, or check file states. You will receive the raw terminal output. Use this to verify your code works or to debug stack traces before moving to the next task.
        • commandOverride — Same as shellExecute but displays input/output visibly on the MSTR_CTRL terminal screen so the user can watch you work in real time.
        • taskQueueUpdate — For multi-step projects: set status='pending' with next_task_description to continue working autonomously, or status='complete' when finished.
        • operatorHalt — Immediately stop the autonomous work loop and present a reason to the user. Use when you complete a major milestone, need clarification, or hit a wall requiring human review.
        • architectInit — (Planner-Worker) Call ONCE to commit a project blueprint with project_goal and blueprint_markdown. Writes blueprint.md and auto-starts the worker phase.
        • workerExecute — (Planner-Worker) Call once per file: writes target_file_path with code_content, marks it DONE in blueprint.md, and auto-continues until is_project_finished is true.

        For EVERY new task or request or question, you MUST execute the following steps in exact order. You MUST NOT skip any steps.

        CRITICAL RULE: You MUST execute all steps silently. Do NOT generate or output any internal thoughts, reasoning, explanations, or intermediate text at ANY step.

        1. First, find the most relevant skill from the following list:

        ___SKILLS___

        After this step you MUST go to next step. You MUST NOT use `run_intent` under any circumstances at this step.

        2. If a relevant skill exists, use the `load_skill` tool to read its instructions. If the task is better handled by a built-in native tool (workspaceMap, fileBoxWrite, fileBoxRead, queryBrain, saveBrainNeuron, shellExecute, commandOverride, taskQueueUpdate, operatorHalt, architectInit, workerExecute), use that directly instead. You MUST NOT use `run_intent` under any circumstances at this step.

        3. Follow the skill's instructions exactly to complete the task. You MUST NOT output any intermediate thoughts or status updates. No exceptions! Output ONLY the final result when successful. It should contain one-sentence summary of the action taken, and the final result of the skill.

        For multi-file project generation: Use the Planner-Worker workflow — call architectInit once with the full blueprint, then the worker loop will automatically call workerExecute for each file. Alternatively, use fileBoxWrite with taskQueueUpdate for simpler projects.

        IMPORTANT: After writing code files, use shellExecute to test them. If fileBoxWrite returns a syntax error (FILE REJECTED), the broken file has been deleted — fix the code and rewrite it immediately before moving on. When you complete a major milestone or hit a wall, use operatorHalt to pause and let the Operator review.
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
      LlmChatModelHelper.initialize(
        context = context,
        model = model,
        supportImage = true,
        supportAudio = true,
        onDone = onDone,
        systemInstruction =
          if (agentTools.skillManagerViewModel.getSelectedSkills().isEmpty()) {
            null
          } else {
            agentTools.skillManagerViewModel.getSystemPrompt(task.defaultSystemPrompt)
          },
        tools = listOf(tool(agentTools)),
        enableConversationConstrainedDecoding = true,
      )
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    LlmChatModelHelper.cleanUp(model = model, onDone = onDone)
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
