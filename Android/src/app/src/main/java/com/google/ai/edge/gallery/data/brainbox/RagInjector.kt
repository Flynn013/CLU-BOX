/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.data.brainbox

import android.util.Log

private const val TAG = "RagInjector"

/**
 * Automatic BrainBox memory retrieval for pre-inference context injection.
 *
 * Pulls core neurons (always) + keyword-matched episodic neurons for a given user
 * message, formats them into a compact context block that can be prepended to the
 * system prompt so the on-device Gemma model has relevant long-term memory
 * without needing to call a tool first.
 *
 * Design goals:
 * - Keep injected text under 800 tokens (tight budget for 4B model).
 * - Prefer core neurons (user-curated facts) over episodic ones.
 * - Deduplicate results across core + keyword passes.
 */
object RagInjector {

    private const val MAX_TOTAL_MEMORIES = 6
    private const val MAX_CORE_MEMORIES = 3
    private const val MAX_CONTENT_CHARS = 250
    private const val MAX_KEYWORDS = 6

    /**
     * Builds a context block from core neurons — suitable for injection at
     * model-initialization time when no specific user message is known yet.
     *
     * Returns empty string if no core neurons exist.
     */
    suspend fun buildCoreContext(dao: BrainBoxDao): String {
        return try {
            val cores = dao.getCoreNeurons().take(MAX_CORE_MEMORIES)
            if (cores.isEmpty()) return ""
            formatMemories(cores, header = "CORE MEMORY")
        } catch (e: Exception) {
            Log.w(TAG, "buildCoreContext failed: ${e.message}")
            ""
        }
    }

    /**
     * Searches BrainBox for memories relevant to [userMessage] and returns
     * a formatted context block to inject before the model sees the message.
     *
     * Includes core neurons + keyword-matched episodic neurons, deduplicated,
     * up to [MAX_TOTAL_MEMORIES] total entries.
     *
     * Returns empty string if nothing found or the DAO is unavailable.
     */
    suspend fun injectForMessage(
        userMessage: String,
        dao: BrainBoxDao,
    ): String {
        if (userMessage.isBlank()) return ""

        return try {
            val seen = mutableSetOf<String>()
            val results = mutableListOf<NeuronEntity>()

            // 1. Core neurons first (always inject, up to MAX_CORE_MEMORIES)
            dao.getCoreNeurons().take(MAX_CORE_MEMORIES).forEach { n ->
                if (seen.add(n.id)) results.add(n)
            }

            // 2. Keyword FTS from the user message
            val keywords = extractKeywords(userMessage)
            for (kw in keywords) {
                if (results.size >= MAX_TOTAL_MEMORIES) break
                dao.searchNeurons(kw).forEach { n ->
                    if (results.size < MAX_TOTAL_MEMORIES && seen.add(n.id)) {
                        results.add(n)
                    }
                }
            }

            if (results.isEmpty()) return ""
            formatMemories(results, header = "RECALLED MEMORY")
        } catch (e: Exception) {
            Log.w(TAG, "injectForMessage failed: ${e.message}")
            ""
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun extractKeywords(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 3 }
            .filterNot { it in STOP_WORDS }
            .distinct()
            .take(MAX_KEYWORDS)

    private fun formatMemories(neurons: List<NeuronEntity>, header: String): String =
        buildString {
            appendLine("[$header]")
            neurons.forEach { n ->
                val content = n.content.take(MAX_CONTENT_CHARS).let {
                    if (n.content.length > MAX_CONTENT_CHARS) "$it…" else it
                }
                append("• ${n.label}")
                if (n.type.isNotBlank() && n.type != "Concept") append(" [${n.type}]")
                appendLine(": $content")
                if (n.synapses.isNotBlank()) {
                    appendLine("  ↔ ${n.synapses.take(120)}")
                }
            }
            appendLine("[/MEMORY]")
        }

    private val STOP_WORDS = setOf(
        "what", "when", "where", "which", "that", "this", "with", "from",
        "have", "will", "your", "they", "been", "more", "also", "some",
        "than", "then", "into", "over", "just", "like", "very", "well",
        "does", "make", "could", "would", "should", "about", "there",
    )
}
