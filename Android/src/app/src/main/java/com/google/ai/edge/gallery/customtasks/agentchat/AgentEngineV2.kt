/*
 * Copyright 2026 Flynn013 / CLU/BOX
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

// Streaming agent-loop pattern adapted from Flynn013/SPL-NTR StreamingAgentLoop.kt (Apache-2.0).
// CLU/BOX adaptations: routes tools through existing SkillRegistry (not ToolRouter), uses
// CluIdentity genesis block, honours AgentLoopManager error budget, emits AgentEvent.

package com.google.ai.edge.gallery.customtasks.agentchat

import android.util.Log
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

/** Events emitted by [AgentEngineV2.run]. Consumed by the Compose chat screen. */
sealed class AgentEvent {
    /** Accumulated text so far for the current assistant turn. */
    data class Token(val accumulatedText: String) : AgentEvent()

    /** A reasoning/thinking token (extended thinking). */
    data class Thinking(val token: String) : AgentEvent()

    /** A tool call is starting (name and id known; input still streaming). */
    data class ToolStart(val id: String, val name: String, val inputSnapshot: String) : AgentEvent()

    /** A tool call finished (result or error). */
    data class ToolEnd(
        val id: String,
        val name: String,
        val output: String,
        val isError: Boolean,
    ) : AgentEvent()

    /** A new assistant turn is beginning (multi-turn loops, post-tool). */
    object AssistantTurn : AgentEvent()

    /** Loop finished cleanly. [finalText] is the last assistant message. */
    data class Complete(val finalText: String) : AgentEvent()

    /** A fatal error occurred. */
    data class Error(val message: String) : AgentEvent()
}

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
 * @param provider      The [LlmProvider] (cloud or device-local) that streams tokens
 * @param skillRegistry The [SkillRegistry] for tool dispatch and schema building
 * @param loopManager   Optional [AgentLoopManager] for error-budget enforcement; if null
 *                      a fresh instance is created per [run] call
 * @param maxIterations Hard cap on think→act cycles per user message (default: 25)
 */
class AgentEngineV2(
    private val provider: LlmProvider,
    private val skillRegistry: SkillRegistry,
    private val loopManager: AgentLoopManager = AgentLoopManager(),
    private val maxIterations: Int = 25,
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

### Development Tools
- **shellExecute**: Execute terminal commands via the embedded BusyBox binary. Standard Unix
  utilities (grep, sed, awk, find, curl, tar, etc.) are available. Call applets by their own
  name — do NOT use `busybox <applet>` prefix.
- **fileBoxWrite**: Create a new file or fully overwrite an existing one (creates parent directories).
- **fileBoxReadLines**: Read a file's contents with line numbers; supports start_line/end_line.
- **brainBoxGrep**: Full-text search across persistent BRAIN_BOX memory nodes.
- **pythonExec**: Execute a Python 3.11 script via the Chaquopy interpreter; full access to
  SplinterAPI (fileBoxRead, fileBoxWrite, brainBoxStore, lnkBoxConnect, scdlBoxSchedule, etc.).

### Memory & Persistence
- **brainBoxGrep**: Search episodic and semantic memory. Use proactively before answering
  questions about user preferences, past decisions, or project context.
- (Additional memory tools are available via Python → Splinter.brainBoxStore / brainBoxRecall)

### Scheduling
- **scdlBoxSkill**: Schedule recurring or one-shot tasks via WorkManager (CLU/BOX SCDL_BOX).

### Task Management
- **todo**: Manage per-session todo lists. `todo_write` to update, `todo_read` to view.

### Delegation
- **delegate**: Spawn a sub-agent on a different persona/skill-set for parallel or specialist tasks.

## Runtime Environment
- Platform: Android (Linux kernel, ARM64)
- Shell: BusyBox applets symlinked into PATH (use `grep`, `find`, `ls`, `awk`, `sed` directly)
- Python 3.11 via Chaquopy (use `pythonExec`)
- Git: Available in shell via BusyBox + JGit native manager
- Working directory: the user's CLU/BOX workspace

## Guidelines
- Always use your tools — don't describe steps, execute them
- Read files before editing; explore with shellExecute before writing
- Verify changes after making them; re-run if errors occur
- Search BRAIN_BOX before answering questions about the user's environment or past decisions
- Use `todo_write` after planning a multi-step task; use `todo_read` to resume
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
        conversationHistory: List<ProviderMessage>,
        additionalSystemPrompt: String = "",
    ): Flow<AgentEvent> = flow {

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

        // ── Seed message list ────────────────────────────────────────────
        val messages = mutableListOf<ProviderMessage>()
        messages.add(ProviderMessage(role = "system", content = systemPrompt))
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
            try {
                provider.streamChat(messages, toolSchemas).collect { event ->
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
                emit(AgentEvent.Complete(turnText.toString()))
                return@flow
            }

            // ── Record assistant turn ─────────────────────────────────────
            messages.add(
                ProviderMessage(
                    role = "assistant",
                    content = turnText.toString(),
                    toolCalls = turnToolCalls.map { tc ->
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
                    val errorFlag = result.startsWith("[System Error:") || result.startsWith("[System: Skill")
                    result to errorFlag
                } catch (e: Exception) {
                    Log.e(TAG, "Tool '${tc.name}' threw: ${e.message}", e)
                    "Tool '${tc.name}' crashed: ${e.message}" to true
                }

                Log.i(TAG, "Tool ${tc.name} done (error=$isError, out=${output.take(200)})")
                emit(AgentEvent.ToolEnd(tc.id, tc.name, output, isError))

                if (isError) loopManager.onError(output) else loopManager.onSuccess()

                messages.add(
                    ProviderMessage(
                        role = "tool",
                        content = output,
                        toolCallId = tc.id,
                        toolName = tc.name,
                    )
                )
            }
            // Loop back — LLM sees tool results and streams next response
        }

        // ── Safety cap ────────────────────────────────────────────────────
        Log.w(TAG, "Reached max iterations ($maxIterations)")
        emit(
            AgentEvent.Error(
                "Reached the maximum number of steps ($maxIterations). " +
                    "The task may need to be broken into smaller pieces."
            )
        )
    }
}
