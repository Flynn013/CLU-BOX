/*
 * Copyright 2026 Flynn013 / CLU/BOX
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

// Streaming agent-loop pattern adapted from Flynn013/SPL-NTR StreamingAgentLoop.kt (Apache-2.0).
// CLU/BOX adaptations: routes tools through existing SkillRegistry (not ToolRouter), uses
// CluIdentity genesis block, honours AgentLoopManager error budget, emits AgentEvent.

package com.google.ai.edge.gallery.customtasks.agentchat

import android.util.Log
import com.google.ai.edge.gallery.data.context.ContextManager
import com.google.ai.edge.gallery.data.providers.LlmProvider
import com.google.ai.edge.gallery.data.providers.ProviderEvent
import com.google.ai.edge.gallery.data.providers.ProviderMessage
import com.google.ai.edge.gallery.data.providers.ProviderToolCall
import com.google.ai.edge.gallery.data.providers.ProviderToolCallResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

/**
 * **AgentEngineV2** — Provider-event-driven autonomous execution loop for CLU/BOX.
 *
 * Replaces the `InferenceAdapter`-based [ContinuousAgentDriver] with a streaming loop
 * that consumes [LlmProvider.streamChat] → [ProviderEvent] flow.  This aligns CLU/BOX
 * with the SPL-NTR / Goose reference pattern (think → act → observe cycle) while
 * keeping all CLU/BOX-specific infrastructure intact:
 * - Tools are dispatched via the existing [SkillRegistry].
 * - System prompt is built via [SkillRegistry.buildFinalSystemPrompt].
 * - [CluIdentity.GENESIS_IDENTITY_BLOCK] is always prepended.
 * - [AgentLoopManager] error budget is respected (max retries, exhaust message).
 *
 * ## Relationship to ContinuousAgentDriver
 * [ContinuousAgentDriver] remains the loop used for **on-device LiteRT models** because
 * it couples tightly to the protected runtime's `InferenceAdapter`.  [AgentEngineV2] is
 * the loop for **cloud providers** ([GeminiProvider], [AnthropicProvider], [OpenAIProvider])
 * and for future on-device models that expose a streaming `LlmProvider` interface.
 *
 * @param provider        The [LlmProvider] (cloud or device-local) that streams tokens
 * @param skillRegistry   The [SkillRegistry] for tool dispatch and schema building
 * @param loopManager     Optional [AgentLoopManager] for error-budget enforcement; if null
 * a fresh instance is created per [run] call
 * @param contextManager  Optional [ContextManager] for token-budget enforcement; if null
 * a default instance tuned to [provider.modelId] is created
 * @param maxIterations   Hard cap on think→act cycles per user message (default: 100 for Devbox runs)
 */
class AgentEngineV2(
    private val provider: LlmProvider,
    private val skillRegistry: SkillRegistry,
    private val loopManager: AgentLoopManager = AgentLoopManager(),
    private val contextManager: ContextManager = ContextManager.forModelId(provider.modelId),
    private val maxIterations: Int = 100,
) {

    companion object {
        private const val TAG = "AgentEngineV2"

        /**
         * The canonical CLU/BOX system prompt for cloud providers.
         *
         * This mirrors the SPL-NTR `GOOSE_SYSTEM_PROMPT` in shape but is CLU/BOX-branded
         * and references CLU/BOX-native tool names (SkillRegistry IDs).
         */
        private val CLU_SYSTEM_PROMPT = """
You are CLU — a powerful AI developer assistant running on Android as part of CLU/BOX.

## Available Tools

### Shell & File System
- **shellExecute**: Execute any Unix command via the embedded BusyBox binary. All standard
  applets (grep, sed, awk, find, curl, tar, ls, cat, chmod, etc.) are available directly.
  Do NOT prefix with `busybox`; just use `grep`, `ls`, etc. directly.
- **fileBoxWrite**: Create or fully overwrite a file in the CLU/BOX workspace. Pass a relative
  `file_path` and the full `content`. Creates parent directories automatically.
- **fileBoxReadLines**: Read a file with line numbers; supports `start_line`/`end_line` slicing.
- **fileEdit**: Apply a targeted in-place replacement to an existing file (old_text → new_text).
  Use this instead of fileBoxWrite when only a small section changes.
- **fileDiff**: Compute a unified diff between two files. Useful before applying edits.
- **codeSearch**: Recursive regex grep across files with optional extension filter. Returns
  file:line:match tuples (max 60 hits).

### Memory & Knowledge
- **brainBoxGrep**: Full-text search of persistent BRAIN_BOX neuron nodes. Call proactively
  before answering questions about the user's environment, past decisions, or stored knowledge.
- **appControl** (brainStore / brainRecall / brainDelete): Upsert, recall, or delete BRAIN_BOX
  neurons by label — use this for episodic memory writes.

### Python
- **PYTHON_EXEC**: Execute a Python 3.11 script on-device via the Chaquopy interpreter.
  Pre-imported: `Splinter` (SplinterAPI), `os`, `json`, `re`, `datetime`.
  Full access to fileBox, brainBox, lnkBox, scdlBox via the Splinter API.

### Web
- **webFetch**: HTTP GET a URL and return up to 4 000 chars of stripped page text.

### God-mode CRUD
- **appControl**: Direct read/write/delete/list over every CLU/BOX subsystem without Python:
  - `fileRead/Write/Delete/List` → FILE_BOX
  - `skillRead/Write/Delete/List` → SKILL_BOX Python skills
  - `lnkConnect/Send/List` → LNK_BOX MCP server connections
  - `brainStore/Recall/Delete` → BRAIN_BOX neurons

### Scheduling & Tasks
- **scdlBoxSkill**: Schedule recurring or one-shot tasks via WorkManager (CLU/BOX SCDL_BOX).
- **todo**: Manage per-session to-do lists. Use `action=write` to update, `action=read` to view.

### Delegation
- **delegate**: Spawn a sub-agent with a different persona or skill-set for parallel/specialist tasks.

## Runtime Environment
- Platform: Android (Linux kernel, ARM64)
- Shell: BusyBox applets (use `grep`, `find`, `ls`, `awk`, `sed` directly in shellExecute)
- Python 3.11 via Chaquopy (use `PYTHON_EXEC` tool)
- Working directory: CLU/BOX workspace (`clu_file_box/`)

## Guidelines
- Always use your tools — don't describe steps, execute them
- Read files before editing; explore with shellExecute / fileBoxReadLines before writing
- Prefer fileEdit over fileBoxWrite when making targeted changes to existing files
- Search BRAIN_BOX (brainBoxGrep or appControl brainRecall) before answering questions
  about the user's environment, configuration, or past decisions
- Verify changes after making them; re-run shellExecute if errors occur
- Use `todo` after planning a multi-step task; use `todo read` to check progress
""".trimIndent()
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Run the streaming agent loop for [userMessage].
     *
     * Emits a cold [Flow] of [AgentEvent]s.  Collect it from a coroutine — cancellation
     * stops the loop cleanly.
     *
     * @param userMessage            The new user message
     * @param conversationHistory    Previous turns as [ProviderMessage] list
     * @param additionalSystemPrompt Extra text appended to the system prompt (persona, recipe instructions)
     */
    fun run(
        userMessage: String,
        conversationHistory: MutableList<ProviderMessage>,
        additionalSystemPrompt: String = "",
    ): Flow<AgentEvent> = flow {
        val originalHistorySize = conversationHistory.size


        // ── Build system prompt ──────────────────────────────────────────
        val basePrompt = skillRegistry.buildFinalSystemPrompt(CLU_SYSTEM_PROMPT)
        val systemPrompt = buildString {
            append(basePrompt)
            append("\n\n## Runtime Identity\n")
            append("- Provider: ${provider.providerId}\n")
            append("- Model: ${provider.modelId}\n")
            append("- Platform: Android (you are CLU, NOT Claude / GPT / Gemini — do not claim to be any cloud AI service)\n")
            if (additionalSystemPrompt.isNotBlank()) {
                append("\n\n")
                append(additionalSystemPrompt)
            }
        }

        // ── Build tool schema list ───────────────────────────────────────
        val toolSchemas: List<JSONObject> = skillRegistry.buildToolDefinitions()

        // For local/LiteRT models: append text-based tool call instructions since they
        // don't support native function calling. The instructions teach Gemma to output
        // tool calls as <tool_call>{...}</tool_call> blocks (parsed by LocalToolParser).
        val finalSystemPrompt = if (provider.providerId == "litert") {
            systemPrompt + LocalToolParser.buildToolInstructions(toolSchemas)
        } else {
            systemPrompt
        }

        // ── Seed message list ────────────────────────────────────────────
        val messages = mutableListOf<ProviderMessage>()
        messages.add(ProviderMessage(role = "system", content = finalSystemPrompt))
        messages.addAll(conversationHistory)
        messages.add(ProviderMessage(role = "user", content = userMessage))

        loopManager.reset()

        var iteration = 0

        // ── Main loop ────────────────────────────────────────────────────
        while (iteration < maxIterations) {
            iteration++
            Log.i(TAG, "Agent loop iteration $iteration")

            if (iteration > 1) emit(AgentEvent.AssistantTurn)

            currentCoroutineContext().ensureActive()

            val turnText = StringBuilder()
            val turnThinking = StringBuilder()
            val turnToolCalls = mutableListOf<ProviderToolCallResult>()
            var streamError: String? = null

            // ── Stream from the LLM ──────────────────────────────────────
            // Apply context-window truncation before each request
            val safeMessages = contextManager.fitToWindow(messages)
            if (safeMessages !== messages) {
                Log.w(TAG, "Context truncated: ${contextManager.usageSummary(messages)} → ${contextManager.usageSummary(safeMessages)}")
            }
            try {
                provider.streamChat(safeMessages, toolSchemas).collect { event ->
                    currentCoroutineContext().ensureActive()

                    when (event) {
                        is ProviderEvent.Token -> {
                            turnText.append(event.text)
                            emit(AgentEvent.Token(turnText.toString()))
                        }

                        is ProviderEvent.Thinking -> {
                            turnThinking.append(event.text)
                            emit(AgentEvent.Thinking(event.text))
                        }

                        is ProviderEvent.ToolCallStart -> {
                            Log.d(TAG, "Tool call starting: ${event.name} (id=${event.id})")
                        }

                        is ProviderEvent.ToolCallInput -> {
                            Log.v(TAG, "Tool input chunk for ${event.id}")
                        }

                        is ProviderEvent.ToolCallEnd -> {
                            turnToolCalls.add(
                                ProviderToolCallResult(event.id, event.name, event.input)
                            )
                            Log.d(TAG, "Tool call complete: ${event.name} (id=${event.id})")
                        }

                        is ProviderEvent.Done -> {
                            if (turnToolCalls.isEmpty() && event.toolCalls.isNotEmpty()) {
                                turnToolCalls.addAll(event.toolCalls)
                            }
                            if (turnText.isEmpty() && event.fullText.isNotBlank()) {
                                turnText.append(event.fullText)
                                emit(AgentEvent.Token(turnText.toString()))
                            }
                            // Text-based tool call fallback: for local models that output
                            // tool calls as <tool_call>{...}</tool_call> blocks in their text.
                            if (turnToolCalls.isEmpty() && turnText.isNotBlank()) {
                                val parsed = LocalToolParser.parse(turnText.toString())
                                if (parsed.toolCalls.isNotEmpty()) {
                                    Log.d(TAG, "LocalToolParser found ${parsed.toolCalls.size} tool call(s) in text output")
                                    turnToolCalls.addAll(parsed.toolCalls)
                                    // Show clean text (without tool-call markup) to the user
                                    val displayText = parsed.cleanText
                                    if (displayText.isNotBlank()) {
                                        emit(AgentEvent.Token(displayText))
                                    }
                                }
                            }
                        }

                        is ProviderEvent.Error -> {
                            streamError = event.message
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Stream failed on iteration $iteration", e)
                val keep = loopManager.onError(e.message ?: "streaming error")
                if (!keep) {
                    val newMessages = messages.drop(originalHistorySize + 1)
                    conversationHistory.addAll(newMessages)
                    emit(AgentEvent.Error("Streaming error: ${e.message}"))
                    return@flow
                }
                // inject retry notice and continue
                messages.add(
                    ProviderMessage(
                        role = "system",
                        content = loopManager.formatRetrySystemMessage("stream", e.message ?: "error"),
                    )
                )
                continue
            }

            if (streamError != null) {
                Log.e(TAG, "Stream reported error: $streamError")
                val keep = loopManager.onError(streamError!!)
                if (!keep) {
                    val newMessages = messages.drop(originalHistorySize + 1)
                    conversationHistory.addAll(newMessages)
                    emit(AgentEvent.Error("LLM error: $streamError"))
                    return@flow
                }
                messages.add(
                    ProviderMessage(
                        role = "system",
                        content = loopManager.formatRetrySystemMessage("stream", streamError!!),
                    )
                )
                continue
            }

            // ── No tool calls → done ─────────────────────────────────────
            if (turnToolCalls.isEmpty()) {
                Log.i(TAG, "Loop complete after $iteration iteration(s)")
                loopManager.onSuccess()
                val newMessages = messages.drop(originalHistorySize + 1)
                conversationHistory.addAll(newMessages)
                emit(AgentEvent.Complete(turnText.toString()))
                return@flow
            }

            val isLocalProtocol = provider.providerId == "litert"

            // ── Record assistant turn ─────────────────────────────────────
            messages.add(
                ProviderMessage(
                    role = "assistant",
                    content = turnText.toString(),
                    // Protocol Switch: local models collapse if we supply structured function-call shapes
                    toolCalls = if (isLocalProtocol) emptyList() else turnToolCalls.map { tc ->
                        ProviderToolCall(tc.id, tc.name, tc.input.toString())
                    },
                )
            )

            // ── Execute tool calls ────────────────────────────────────────
            for (tc in turnToolCalls) {
                currentCoroutineContext().ensureActive()

                Log.i(TAG, "Executing tool: ${tc.name} (id=${tc.id})")
                emit(AgentEvent.ToolStart(tc.id, tc.name, tc.input.toString()))

                val (output, isError) = try {
                    val result = skillRegistry.dispatch(tc.name, tc.input)
                    val errorFlag = result.startsWith("[System Error:")
                        || result.startsWith("[System: Skill")
                        || result.startsWith("[appControl error:")
                        || result.startsWith("[Error:")
                    result to errorFlag
                } catch (e: Exception) {
                    Log.e(TAG, "Tool '${tc.name}' threw: ${e.message}", e)
                    "Tool '${tc.name}' crashed: ${e.message}" to true
                }

                Log.i(TAG, "Tool ${tc.name} done (error=$isError, out=${output.take(200)})")
                emit(AgentEvent.ToolEnd(tc.id, tc.name, output, isError))

                if (isError) loopManager.onError(output) else loopManager.onSuccess()

                // Protocol Switch: Route local tool observations inside standard USER blocks
                // wrapped in tool_response XML tags to enforce strict role-turn alternation.
                if (isLocalProtocol) {
                    messages.add(
                        ProviderMessage(
                            role = "user",
                            content = "<tool_response>\n$output\n</tool_response>"
                        )
                    )
                } else {
                    messages.add(
                        ProviderMessage(
                            role = "tool",
                            content = output,
                            toolCallId = tc.id,
                            toolName = tc.name,
                        )
                    )
                }
            }
            // Loop back — LLM sees tool results and streams next response
        }

        // ── Safety cap ────────────────────────────────────────────────────
        Log.w(TAG, "Reached max iterations ($maxIterations)")
        val newMessages = messages.drop(originalHistorySize + 1)
        conversationHistory.addAll(newMessages)
        emit(
            AgentEvent.Error(
                "Reached the maximum number of steps ($maxIterations). " +
                    "The task may need to be broken into smaller pieces."
            )
        )
    }
}
