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

package com.google.ai.edge.gallery.customtasks.agentchat

import android.util.Log
import com.google.ai.edge.gallery.common.SkillProgressAgentAction

private const val TAG = "AgentLoopManager"

/**
 * Manages the error-recovery and status-emission lifecycle of the
 * CLU/BOX agentic "Observe → Reason → Act" loop.
 *
 * One instance should be created per chat session and shared across
 * [AgentChatScreen]'s callbacks.  It is intentionally *not* a singleton
 * so that resetting the session always produces a clean slate.
 *
 * ## Responsibilities
 * - Track consecutive inference/tool failures and enforce [MAX_RETRIES].
 * - Format tool errors as `[System: ...]` re-evaluation injections so the
 *   model can reason about an alternative approach on the next turn.
 * - Emit granular UI status strings (Thinking / Executing / Observing) via
 *   [AgentTools.actionChannel] so the operator has transparent insight into
 *   the hidden agent monologue.
 * - Provide [shouldRetry] to let [AgentChatScreen] decide whether to
 *   re-trigger inference or surface the failure to the user.
 *
 * ## Error recovery policy
 * When [onError] is called:
 * 1. The counter increments.
 * 2. If `errorCount < MAX_RETRIES` → [shouldRetry] returns `true`.
 *    The caller injects [formatRetrySystemMessage] into the conversation
 *    and re-triggers inference.
 * 3. If `errorCount >= MAX_RETRIES` → [isExhausted] is `true`.
 *    The caller surfaces a user-facing message and halts the loop.
 * 4. [onSuccess] resets the counter after any successful inference turn.
 *
 * ## Thread safety
 * This class is **not** thread-safe.  All calls must originate from the
 * Compose Main thread (which is guaranteed for [AgentChatScreen] callbacks).
 */
class AgentLoopManager {

  companion object {
    /** Maximum consecutive failures before the loop asks the user for help. */
    const val MAX_RETRIES = 3

    // ── Loop phase labels (shown in the collapsable progress panel) ───────

    /** Status emitted while waiting for the first model token. */
    const val STATUS_THINKING = "Thinking..."

    /** Status emitted just before a tool call is dispatched. */
    fun statusExecuting(toolName: String) = "Executing $toolName..."

    /** Status emitted after a tool call returns its observation. */
    fun statusObserving(toolName: String) = "Observing $toolName output..."
  }

  /** Running count of consecutive errors since the last successful turn. */
  private var errorCount = 0

  // ── Error tracking ───────────────────────────────────────────────────────

  /**
   * Record an inference or tool execution failure.
   *
   * @param errorMessage Raw error string from the engine or tool.
   * @return `true` if the loop should retry (budget not yet exhausted),
   *         `false` if the caller should halt the loop.
   */
  fun onError(errorMessage: String): Boolean {
    errorCount++
    Log.w(TAG, "onError #$errorCount/$MAX_RETRIES — $errorMessage")
    return !isExhausted
  }

  /**
   * Record a successful inference turn and reset the consecutive error counter.
   *
   * Call this from `onGenerateResponseDone` whenever the model completes
   * a turn without raising an error.
   */
  fun onSuccess() {
    if (errorCount > 0) {
      Log.d(TAG, "Cleared $errorCount consecutive error(s) after a successful turn")
      errorCount = 0
    }
  }

  /**
   * Reset all managed state.
   *
   * Call when the user starts a new conversation or resets the session.
   */
  fun reset() {
    errorCount = 0
    Log.d(TAG, "AgentLoopManager reset")
  }

  /** `true` when [MAX_RETRIES] consecutive errors have occurred without a success. */
  val isExhausted: Boolean get() = errorCount >= MAX_RETRIES

  // ── Message formatting ───────────────────────────────────────────────────

  /**
   * Format a tool or inference failure as a Role.SYSTEM re-evaluation prompt.
   *
   * The injected text follows the "Observe → Reason → Act" pattern: the model
   * receives the failure as a system observation so it can reason about an
   * alternative strategy on the next turn rather than blindly repeating the
   * same failing command.
   *
   * @param toolName      Human-readable name of the tool or operation that failed
   *                      (e.g. "shellExecute", "Inference engine").
   * @param errorMessage  The underlying error message (truncated to 300 chars).
   */
  fun formatRetrySystemMessage(toolName: String, errorMessage: String): String = buildString {
    appendLine("[System: Tool execution failed — re-evaluate and try again]")
    appendLine("Tool: $toolName")
    appendLine("Error: ${errorMessage.take(300)}")
    appendLine("Retry: $errorCount of $MAX_RETRIES allowed.")
    appendLine()
    appendLine("Do NOT repeat the exact same command. Try a different approach:")
    appendLine("  • Check if the path, syntax, or arguments are correct.")
    appendLine("  • Verify prerequisites with a simpler diagnostic command first.")
    append("  • If all approaches fail after $MAX_RETRIES retries, report the issue to the user.")
  }

  /**
   * Emit a user-facing status message for the current loop phase via
   * [AgentTools.actionChannel].
   *
   * Statuses appear in the collapsable progress panel on the chat screen,
   * giving the operator transparent insight into the hidden agent monologue.
   *
   * @param phase       A status string (use [STATUS_THINKING], [statusExecuting],
   *                    or [statusObserving] helpers, or a custom string).
   * @param agentTools  The [AgentTools] instance to route the action through.
   * @param inProgress  `true` while the operation is ongoing; `false` when done.
   */
  fun emitStatus(phase: String, agentTools: AgentTools, inProgress: Boolean = true) {
    agentTools.sendAgentAction(SkillProgressAgentAction(label = phase, inProgress = inProgress))
    Log.d(TAG, "emitStatus: '$phase' (inProgress=$inProgress)")
  }

  /**
   * Convenience: emit a "Thinking..." status while inference is starting.
   *
   * Call this at the very start of a new inference turn, before the first
   * token arrives from the model.
   */
  fun emitThinking(agentTools: AgentTools) =
    emitStatus(STATUS_THINKING, agentTools, inProgress = true)

  /**
   * Build the user-facing "loop halted" message shown when [isExhausted].
   *
   * @param engine The active [AgentEngine], used to include engine context.
   */
  fun buildExhaustedMessage(engine: AgentEngine): String =
    "[System: Agentic loop halted after $MAX_RETRIES consecutive failures " +
      "(engine=${engine.name}). The agent cannot make progress automatically. " +
      "Please provide guidance or try a different approach.]"
}
