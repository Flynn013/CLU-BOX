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

// Token-accounting and truncation strategy adapted from the Goose context_mgmt/mod.rs design.
// Uses a BPE-approximate character-based estimate (÷4) because the Android environment does
// not ship a tokenizer library; this matches the heuristic used by most mobile LLM apps.

package com.google.ai.edge.gallery.data.context

import android.util.Log
import com.google.ai.edge.gallery.data.providers.ProviderMessage

/**
 * Truncation strategy used when the context window is exhausted.
 *
 * Mirrors the Goose `TruncationStrategy` variants.
 */
enum class TruncationStrategy {
    /**
     * Drop the oldest non-system messages (pair-wise: user+assistant) until the
     * context fits.  Simple and predictable — the default.
     */
    DROP_OLDEST,

    /**
     * Summarise and compress tool-result messages (which tend to be the largest).
     * If that is still insufficient, falls back to [DROP_OLDEST].
     */
    COMPRESS_TOOL_RESULTS,
}

/**
 * Manages the token budget for a conversation and applies truncation when needed.
 *
 * ## Token estimation
 * Because Android ships no tokenizer library, tokens are estimated at
 * **1 token ≈ 4 characters** (a widely-used approximation for GPT/BPE vocabularies).
 * The estimate is conservative — the actual count is typically a bit lower — which
 * gives a safety margin before hitting the hard context limit.
 *
 * ## Usage in the agent loop
 * Before each `provider.streamChat(messages, tools)` call, run:
 * ```kotlin
 * val safe = contextManager.fitToWindow(messages)
 * provider.streamChat(safe, tools)
 * ```
 *
 * @param maxTokens     The context window size of the model in tokens (from model config)
 * @param reserveTokens Tokens to reserve for the model's response (default: 2048)
 * @param strategy      Truncation strategy when the budget is exceeded
 */
class ContextManager(
    val maxTokens: Int = DEFAULT_CONTEXT_TOKENS,
    private val reserveTokens: Int = DEFAULT_RESERVE_TOKENS,
    private val strategy: TruncationStrategy = TruncationStrategy.DROP_OLDEST,
) {

    companion object {
        private const val TAG = "ContextManager"

        /** Default context window if the model config does not specify one. */
        const val DEFAULT_CONTEXT_TOKENS = 8192

        /** Tokens reserved for the model response (not counted against the input budget). */
        const val DEFAULT_RESERVE_TOKENS = 2048

        /** Characters-per-token ratio used for estimation. */
        private const val CHARS_PER_TOKEN = 4

        /** Maximum characters to keep in a single tool result before truncation. */
        private const val MAX_TOOL_RESULT_CHARS = 4_000

        /**
         * Common context-window sizes keyed by well-known model id substrings.
         *
         * Callers can use [forModelId] to build a [ContextManager] without needing
         * the full model config object.
         */
        private val MODEL_CONTEXT_MAP: Map<String, Int> = mapOf(
            "gemini-3.5-flash" to 1_048_576,
            "gemini-2.0-flash" to 1_048_576,
            "gemini-2.5-flash" to 1_048_576,
            "gemini-2.5-pro"   to 1_048_576,
            "gemini-1.5-pro"   to 1_048_576,
            "gemini-1.5-flash" to 1_048_576,
            "claude-3-5-sonnet" to 200_000,
            "claude-3-5-haiku"  to 200_000,
            "claude-3-opus"     to 200_000,
            "gpt-4o"            to 128_000,
            "gpt-4-turbo"       to 128_000,
            "gpt-4o-mini"       to 128_000,
            "o4-mini"           to 200_000,
            "o3-mini"           to 200_000,
            "gemma-4-e4b"       to 32_000,
            "gemma-4-e2b"       to 32_000,
            "gemma3"            to 8_192,
            "gemma-3n"          to 4_096,
            "qwen2.5"           to 32_768,
            "deepseek"          to 65_536,
            "phi-4"             to 16_384,
            "mistral"           to 32_768,
        )

        /**
         * Creates a [ContextManager] tuned for [modelId] by looking up its
         * known context window size.  Falls back to [DEFAULT_CONTEXT_TOKENS].
         */
        fun forModelId(modelId: String, strategy: TruncationStrategy = TruncationStrategy.DROP_OLDEST): ContextManager {
            val lc = modelId.lowercase()
            val tokens = MODEL_CONTEXT_MAP.entries
                .firstOrNull { (key, _) -> key in lc }?.value
                ?: DEFAULT_CONTEXT_TOKENS
            Log.d(TAG, "ContextManager for '$modelId': maxTokens=$tokens")
            return ContextManager(maxTokens = tokens, strategy = strategy)
        }
    }

    // ── Token estimation ────────────────────────────────────────────────────

    /**
     * Estimates the number of tokens in [text] using the chars-per-token heuristic.
     */
    fun estimateTokens(text: String): Int = (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    /**
     * Estimates the total token count for a list of [ProviderMessage]s.
     *
     * Each message is assigned ~4 overhead tokens (role + formatting).
     */
    fun estimateTokens(messages: List<ProviderMessage>): Int {
        var total = 0
        for (msg in messages) {
            total += 4 // per-message overhead
            total += estimateTokens(msg.content)
            for (tc in msg.toolCalls) {
                total += estimateTokens(tc.name) + estimateTokens(tc.inputJson)
            }
        }
        return total
    }

    /** The effective input budget: maxTokens minus reserved response tokens. */
    val inputBudget: Int get() = (maxTokens - reserveTokens).coerceAtLeast(0)

    /** Returns `true` if [messages] fit within [inputBudget]. */
    fun fits(messages: List<ProviderMessage>): Boolean =
        estimateTokens(messages) <= inputBudget

    // ── Truncation ──────────────────────────────────────────────────────────

    /**
     * Returns a trimmed copy of [messages] that fits within [inputBudget], applying
     * the configured [strategy].
     *
     * The **system message** (role=system) is always preserved.
     * The **most recent user message** is always preserved.
     *
     * If the messages already fit, the original list is returned unchanged (no copy).
     */
    fun fitToWindow(messages: List<ProviderMessage>): List<ProviderMessage> {
        if (fits(messages)) return messages

        Log.w(TAG, "Context budget exceeded (est. ${estimateTokens(messages)} > $inputBudget). Truncating…")

        return when (strategy) {
            TruncationStrategy.DROP_OLDEST -> dropOldest(messages)
            TruncationStrategy.COMPRESS_TOOL_RESULTS -> {
                val compressed = compressToolResults(messages)
                if (fits(compressed)) compressed else dropOldest(compressed)
            }
        }
    }

    // ── Strategies ──────────────────────────────────────────────────────────

    /**
     * Drops the oldest non-system, non-final-user messages until the list fits.
     *
     * Messages are dropped from the front of the list (oldest first).
     * A system message at index 0 is always kept.
     */
    private fun dropOldest(messages: List<ProviderMessage>): List<ProviderMessage> {
        val mutable = messages.toMutableList()

        val systemIdx = mutable.indexOfFirst { it.role == "system" }
        var dropped = 0

        // Scan forward from just after the system message. We re-evaluate
        // lastUserIdx on every iteration because indices shift after each removal.
        var i = if (systemIdx == 0) 1 else 0

        while (!fits(mutable) && i < mutable.size) {
            val lastUserIdx = mutable.indexOfLast { it.role == "user" }
            // Never remove the last user message (it's the current request)
            if (i == lastUserIdx) {
                i++
                continue
            }
            mutable.removeAt(i)
            dropped++
            // Do not advance i; the former element at i+1 has now slid to i.
        }

        if (dropped > 0) Log.w(TAG, "Dropped $dropped message(s) to fit context window")
        return mutable
    }

    /**
     * Truncates large tool-result messages to at most [MAX_TOOL_RESULT_CHARS] characters.
     *
     * Tool results (role="tool") are the primary cause of context bloat.
     * Long outputs (e.g. shell commands dumping large files) are trimmed with a
     * notice appended so the model knows truncation occurred.
     */
    private fun compressToolResults(messages: List<ProviderMessage>): List<ProviderMessage> {
        val maxChars = MAX_TOOL_RESULT_CHARS
        return messages.map { msg ->
            if (msg.role == "tool" && msg.content.length > maxChars) {
                val trimmed = msg.content.take(maxChars)
                val notice = "\n[…truncated — ${msg.content.length - maxChars} chars omitted to fit context window]"
                msg.copy(content = trimmed + notice)
            } else {
                msg
            }
        }
    }

    // ── Usage accounting ────────────────────────────────────────────────────

    /**
     * Returns a human-readable usage summary for debug / status display.
     *
     * Example: "~3 400 / 8 192 tokens used (41%)"
     */
    fun usageSummary(messages: List<ProviderMessage>): String {
        val used = estimateTokens(messages)
        val pct = if (inputBudget > 0) (used * 100L / inputBudget).coerceAtMost(100) else 0
        return "~${"%,d".format(used)} / ${"%,d".format(inputBudget)} tokens ($pct%)"
    }
}
