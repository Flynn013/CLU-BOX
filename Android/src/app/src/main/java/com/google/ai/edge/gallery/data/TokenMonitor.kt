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

package com.google.ai.edge.gallery.data

import android.util.Log

private const val TAG = "TokenMonitor"

/**
 * Average characters per SentencePiece token for Gemma models.
 * Empirically measured: English text averages ~3.5 chars/token for Gemma 4B.
 * Code text averages ~3.0 chars/token. We use a conservative 3.2 to avoid
 * underestimating and blowing the context window.
 */
private const val CHARS_PER_TOKEN = 3.2

/**
 * The critical threshold as a fraction of the total context window.
 * When estimated token usage reaches 80%, the compression interceptor fires.
 */
private const val CRITICAL_THRESHOLD = 0.80

/**
 * Default context window size in tokens for Gemma 4B.
 * This is overridden by `maxNumTokens` from the model config when available.
 */
private const val DEFAULT_CONTEXT_WINDOW = 8192

/**
 * Real-time token monitor that tracks the estimated SentencePiece token count
 * of the current conversation session. Designed for on-device Gemma 4B inference
 * where every token of context window must be maximised for work.
 *
 * The monitor uses a character-to-token ratio estimation (Gemma SentencePiece
 * averages ~3.2 chars/token) since the LiteRT runtime does not expose a public
 * `sizeInTokens()` API. This is conservative — it will trigger compression
 * slightly early rather than risk a context overflow.
 */
class TokenMonitor(
  private val contextWindowSize: Int = DEFAULT_CONTEXT_WINDOW,
) {
  /** Accumulated estimated token count for the current session. */
  @Volatile
  private var currentTokens: Int = 0

  /** Number of messages tracked since last reset. */
  @Volatile
  private var messageCount: Int = 0

  /** The hard token limit at which compression must fire (80% of window). */
  val criticalLimit: Int
    get() = (contextWindowSize * CRITICAL_THRESHOLD).toInt()

  /** Current estimated token usage. Thread-safe read. */
  val estimatedTokens: Int
    get() = currentTokens

  /** Current context window utilisation as a percentage (0.0–1.0). */
  val utilisation: Double
    get() = if (contextWindowSize > 0) currentTokens.toDouble() / contextWindowSize else 0.0

  /**
   * Estimates the SentencePiece token count for [text] using the Gemma
   * character-to-token ratio.
   */
  fun estimateTokens(text: String): Int {
    if (text.isEmpty()) return 0
    return (text.length / CHARS_PER_TOKEN).toInt().coerceAtLeast(1)
  }

  /**
   * Tracks a new message being added to the conversation context.
   * Call this for every user message, system injection, and agent response.
   */
  fun trackMessage(text: String) {
    val tokens = estimateTokens(text)
    currentTokens += tokens
    messageCount++
    Log.d(
      TAG,
      "trackMessage: +$tokens tokens (total=$currentTokens/$contextWindowSize, " +
        "${(utilisation * 100).toInt()}% used, messages=$messageCount)"
    )
  }

  /**
   * Returns `true` when the estimated token usage has breached the 80%
   * critical threshold and context compression must fire.
   */
  fun shouldCompress(): Boolean {
    val shouldFire = currentTokens >= criticalLimit
    if (shouldFire) {
      Log.w(
        TAG,
        "CRITICAL: Token threshold breached ($currentTokens >= $criticalLimit). " +
          "Compression interceptor must fire."
      )
    }
    return shouldFire
  }

  /**
   * Resets the token counter after a context compression/purge cycle.
   * Call this after the conversation history has been wiped and the
   * Genesis Message has been re-injected.
   *
   * @param genesisTokens The estimated tokens in the re-injected resume block.
   */
  fun reset(genesisTokens: Int = 0) {
    Log.d(
      TAG,
      "reset: clearing $currentTokens tokens ($messageCount messages). " +
        "Re-seeding with $genesisTokens genesis tokens."
    )
    currentTokens = genesisTokens
    messageCount = if (genesisTokens > 0) 1 else 0
  }

  /**
   * Returns a diagnostic summary suitable for logging or BrainBox storage.
   */
  fun diagnosticSummary(): String {
    return "TokenMonitor[tokens=$currentTokens, window=$contextWindowSize, " +
      "limit=$criticalLimit, util=${(utilisation * 100).toInt()}%, msgs=$messageCount]"
  }
}
