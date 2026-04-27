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

import android.util.Log
import com.google.ai.edge.gallery.customtasks.agentchat.AgentEngine
import com.google.ai.edge.gallery.data.brainbox.BrainBoxDao
import com.google.ai.edge.gallery.data.brainbox.NeuronEntity
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import java.util.UUID

private const val TAG = "ContextWindowPager"

/**
 * Manages the active token budget for agentic conversations and prevents
 * OOM crashes during continuous looping.
 *
 * ## Strategy: Sliding Window
 *
 * When the estimated token count approaches the model's physical context
 * limit, this pager executes a **sliding window** algorithm:
 *
 * 1. Always retain the **first** message (the user's initial request).
 * 2. Always retain the last [RETAINED_TAIL_MESSAGES] messages
 *    (the most recent observations / model replies).
 * 3. **Drop** all intermediate USER and MODEL turns in between.
 * 4. **Archive** every dropped turn to [BrainBoxDao] as a
 *    `ContextWindow_Archive` neuron so the context is not permanently
 *    lost and can be RAG-retrieved if needed later.
 *
 * ## Token Estimation
 *
 * An exact token count is not available at the Android layer because the
 * LiteRT-LM tokeniser runs inside the native C++ engine.  We use a
 * conservative **4 characters per token** ratio, which is accurate for
 * English prose and typical code.  A 15 % safety margin is applied on top.
 *
 * ## Usage
 *
 * ```kotlin
 * // In AgentChatScreen — before triggering the next autonomous iteration:
 * if (ContextWindowPager.shouldPrune(visibleMessages, agentTools.engine)) {
 *     ContextWindowPager.pruneAndArchive(
 *         messages       = visibleMessages.toMutableList(),
 *         engine         = agentTools.engine,
 *         brainBoxDao    = agentTools.brainBoxDao,
 *         sessionLabel   = "Agentic loop iteration $count",
 *     )
 * }
 * ```
 */
object ContextWindowPager {

  // ── Budget constants ──────────────────────────────────────────────────────

  /** Conservative token budget for Local Gemma models (8 k context). */
  const val LOCAL_TOKEN_BUDGET = 8_000

  /** Token budget for Gemini Cloud API models (generous 128 k window). */
  const val CLOUD_TOKEN_BUDGET = 128_000

  /**
   * Conservative characters-per-token ratio.
   *
   * Gemma tokeniser typically produces ~3.5–4 chars/token for English.
   * We use 4 as a safe floor; lower = more aggressive pruning = safer.
   */
  private const val CHARS_PER_TOKEN = 4

  /**
   * Fraction of the token budget at which pruning is triggered.
   *
   * 0.80 = prune when 80 % of the budget is consumed, leaving a comfortable
   * 20 % margin for the model's reply and the next tool observation.
   */
  private const val PRUNE_THRESHOLD = 0.80f

  /**
   * Number of tail messages always retained regardless of pruning.
   *
   * Retaining the last 2 ensures at least the most recent tool observation
   * and the model's reply are present, giving the model grounding context.
   */
  private const val RETAINED_TAIL_MESSAGES = 2

  // ── Public API ─────────────────────────────────────────────────────────────

  /**
   * Estimate the approximate token count for [text].
   *
   * @return Positive integer token estimate.
   */
  fun estimateTokens(text: String): Int = (text.length / CHARS_PER_TOKEN).coerceAtLeast(1)

  /**
   * Sum the estimated token counts across [messages].
   */
  fun estimateTokensForMessages(messages: List<ChatMessageText>): Int =
    messages.sumOf { estimateTokens(it.content) }

  /**
   * Returns the token budget appropriate for [engine].
   */
  fun budgetFor(engine: AgentEngine): Int =
    if (engine == AgentEngine.LOCAL) LOCAL_TOKEN_BUDGET else CLOUD_TOKEN_BUDGET

  /**
   * Returns `true` when the cumulative estimated token count of [messages]
   * has reached the pruning threshold for [engine].
   *
   * Intended to be called before queuing the next autonomous iteration so
   * the loop can proactively prune before hitting a hard overflow.
   */
  fun shouldPrune(messages: List<ChatMessageText>, engine: AgentEngine): Boolean {
    val budget = budgetFor(engine)
    val threshold = (budget * PRUNE_THRESHOLD).toInt()
    val estimated = estimateTokensForMessages(messages)
    Log.d(TAG, "shouldPrune: estimated=$estimated threshold=$threshold budget=$budget engine=$engine")
    return estimated >= threshold
  }

  /**
   * Prune [messages] using the sliding window algorithm and archive the
   * dropped turns to [brainBoxDao].
   *
   * **Algorithm:**
   * ```
   * [ initial_query | ... middle_turns_to_drop ... | tail_N ]
   *       ↓ keep               ↓ archive & drop         ↓ keep
   * ```
   *
   * @param messages      Mutable list of [ChatMessageText] in chronological order.
   *                      The list is modified in-place.
   * @param engine        Active [AgentEngine] — used for logging budget info.
   * @param brainBoxDao   Optional DAO; if null, archival is skipped (graceful degradation).
   * @param sessionLabel  Short label identifying the current session or iteration
   *                      (stored as the neuron label in BrainBox).
   * @return              The pruned list (same instance as [messages]).
   */
  suspend fun pruneAndArchive(
    messages: MutableList<ChatMessageText>,
    engine: AgentEngine,
    brainBoxDao: BrainBoxDao?,
    sessionLabel: String = "Agentic Session",
  ): List<ChatMessageText> {
    // Nothing to prune if the list is tiny.
    if (messages.size <= RETAINED_TAIL_MESSAGES + 1) {
      Log.d(TAG, "pruneAndArchive: list too small to prune (${messages.size} messages)")
      return messages
    }

    val keepFirst = messages.first()
    val keepTail = messages.takeLast(RETAINED_TAIL_MESSAGES)
    val middleToDrop = messages.drop(1).dropLast(RETAINED_TAIL_MESSAGES)

    if (middleToDrop.isEmpty()) {
      Log.d(TAG, "pruneAndArchive: no middle turns to drop")
      return messages
    }

    Log.d(
      TAG,
      "pruneAndArchive: dropping ${middleToDrop.size} middle turns " +
        "(budget=${budgetFor(engine)}, engine=$engine)",
    )

    // ── Archive dropped turns to BrainBox ─────────────────────────────────
    if (brainBoxDao != null) {
      val archivedContent = buildString {
        appendLine("=== Context Window Archive: $sessionLabel ===")
        appendLine("Engine: $engine | Pruned: ${middleToDrop.size} turns")
        appendLine()
        for (msg in middleToDrop) {
          val role = if (msg.side == ChatSide.USER) "USER" else "AGENT"
          appendLine("[$role] ${msg.content.take(500)}")
        }
      }

      try {
        val neuron = NeuronEntity(
          id = UUID.randomUUID().toString(),
          label = "CtxArchive: $sessionLabel".take(80),
          type = "ContextWindow_Archive",
          content = archivedContent,
        )
        brainBoxDao.insertNeuron(neuron)
        Log.d(TAG, "pruneAndArchive: archived ${middleToDrop.size} turns → neuron ${neuron.id}")
      } catch (e: Exception) {
        Log.e(TAG, "pruneAndArchive: BrainBox archival failed", e)
        // Archival is best-effort; continue with the prune regardless.
      }
    }

    // ── Rebuild pruned list ────────────────────────────────────────────────
    messages.clear()
    messages.add(keepFirst)
    messages.addAll(keepTail)

    Log.d(TAG, "pruneAndArchive: window reduced to ${messages.size} messages")
    return messages
  }
}
