/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.goose.engine

import com.google.ai.edge.gallery.goose.data.SettingsStore
import kotlinx.coroutines.flow.first
import java.util.Calendar

/**
 * AdaptivePrompt — Generates a personalized system-prompt addendum based on
 * accumulated user behavior patterns across sessions.
 *
 * Tracks tool usage, topics, response style preferences, and time-of-day patterns
 * to tailor the AI's behavior to the individual user.
 *
 * Ported from MaxFlynn13/goose-android (engine/AdaptivePrompt.kt).
 */
class AdaptivePrompt(private val settingsStore: SettingsStore) {

    companion object {
        private const val KEY_TOOL_COUNTS = "adaptive_tool_counts"
        private const val KEY_TOPIC_COUNTS = "adaptive_topic_counts"
        private const val KEY_TOTAL_RESPONSE_LENGTH = "adaptive_total_response_len"
        private const val KEY_INTERACTION_COUNT = "adaptive_interaction_count"
        private const val KEY_MORNING_TOPICS = "adaptive_morning_topics"
        private const val KEY_EVENING_TOPICS = "adaptive_evening_topics"
    }

    @Volatile
    private var cachedPrompt: String = ""

    /** Non-suspend accessor for use inside flow builders — returns cached value. */
    fun getPersonalizedPromptSync(): String = cachedPrompt

    /** Call this after [recordInteraction] to refresh the in-memory cache. */
    suspend fun refreshCache() {
        cachedPrompt = getPersonalizedPrompt()
    }

    /** Build a personalized prompt addendum from the accumulated profile. */
    suspend fun getPersonalizedPrompt(): String {
        val profile = getProfile()
        if (profile.interactionCount < 5) return "" // Not enough data yet

        val lines = mutableListOf<String>()

        // Response style preference
        val avgLen = profile.avgResponseLength
        when {
            avgLen < 80 -> lines.add("The user prefers concise responses (avg $avgLen words).")
            avgLen > 300 -> lines.add("The user prefers detailed, thorough responses.")
        }

        // Top tools → workflow inference
        val topTools = profile.topTools.take(3)
        if (topTools.isNotEmpty()) {
            val workflow = inferWorkflow(topTools)
            if (workflow.isNotBlank()) lines.add(workflow)
        }

        // Common topics
        val topTopics = profile.topTopics.take(3)
        if (topTopics.isNotEmpty()) {
            lines.add("The user often works on: ${topTopics.joinToString(", ")}.")
        }

        // Time-based patterns
        val timeHint = getTimeBasedHint(profile)
        if (timeHint.isNotBlank()) lines.add(timeHint)

        if (lines.isEmpty()) return ""
        return "\n[User Profile]\n${lines.joinToString("\n")}"
    }

    /** Record an interaction to update the user profile. */
    suspend fun recordInteraction(
        toolsUsed: List<String>,
        topic: String,
        responseLength: Int
    ) {
        // Update tool counts
        val toolCounts = loadMap(KEY_TOOL_COUNTS)
        for (tool in toolsUsed) {
            toolCounts[tool] = (toolCounts[tool] ?: 0) + 1
        }
        saveMap(KEY_TOOL_COUNTS, toolCounts)

        // Update topic counts
        if (topic.isNotBlank()) {
            val topicCounts = loadMap(KEY_TOPIC_COUNTS)
            topicCounts[topic] = (topicCounts[topic] ?: 0) + 1
            saveMap(KEY_TOPIC_COUNTS, topicCounts)

            // Track time-of-day topics
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val timeKey = if (hour in 5..11) KEY_MORNING_TOPICS else KEY_EVENING_TOPICS
            val timeTopics = loadMap(timeKey)
            timeTopics[topic] = (timeTopics[topic] ?: 0) + 1
            saveMap(timeKey, timeTopics)
        }

        // Update response length running average
        val totalLen = settingsStore.getInt(KEY_TOTAL_RESPONSE_LENGTH).first() + responseLength
        val count = settingsStore.getInt(KEY_INTERACTION_COUNT).first() + 1
        settingsStore.setInt(KEY_TOTAL_RESPONSE_LENGTH, totalLen)
        settingsStore.setInt(KEY_INTERACTION_COUNT, count)
    }

    /** Get the current user profile. */
    suspend fun getProfile(): UserProfile {
        val toolCounts = loadMap(KEY_TOOL_COUNTS)
        val topicCounts = loadMap(KEY_TOPIC_COUNTS)
        val totalLen = settingsStore.getInt(KEY_TOTAL_RESPONSE_LENGTH).first()
        val count = settingsStore.getInt(KEY_INTERACTION_COUNT).first()
        val morningTopics = loadMap(KEY_MORNING_TOPICS)
        val eveningTopics = loadMap(KEY_EVENING_TOPICS)

        return UserProfile(
            interactionCount = count,
            avgResponseLength = if (count > 0) totalLen / count else 0,
            topTools = toolCounts.entries.sortedByDescending { it.value }.map { it.key },
            topTopics = topicCounts.entries.sortedByDescending { it.value }.map { it.key },
            morningTopics = morningTopics.entries.sortedByDescending { it.value }.map { it.key },
            eveningTopics = eveningTopics.entries.sortedByDescending { it.value }.map { it.key }
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun inferWorkflow(topTools: List<String>): String {
        val toolSet = topTools.toSet()
        return when {
            "shell" in toolSet && "edit" in toolSet && "git" in toolSet ->
                "The user frequently uses git, shell, and file editing — prioritize developer workflows."
            "shell" in toolSet && "python" in toolSet ->
                "The user frequently runs shell commands and Python — prioritize scripting workflows."
            "shell" in toolSet && "edit" in toolSet ->
                "The user frequently uses shell and file editing — prioritize coding workflows."
            "web_search" in toolSet || "fetch" in toolSet ->
                "The user frequently searches the web — prioritize research and information gathering."
            else ->
                "The user's most-used tools: ${topTools.joinToString(", ")}."
        }
    }

    private fun getTimeBasedHint(profile: UserProfile): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hour in 5..11 && profile.morningTopics.isNotEmpty() ->
                "It's morning — the user typically focuses on: ${profile.morningTopics.first()}."
            hour in 18..23 && profile.eveningTopics.isNotEmpty() ->
                "It's evening — the user typically focuses on: ${profile.eveningTopics.first()}."
            else -> ""
        }
    }

    private suspend fun loadMap(key: String): MutableMap<String, Int> {
        val raw = settingsStore.getString(key, "").first()
        if (raw.isBlank()) return mutableMapOf()
        return try {
            raw.split(";")
                .filter { it.contains("=") }
                .associate { entry ->
                    val parts = entry.split("=", limit = 2)
                    parts[0] to (parts[1].toIntOrNull() ?: 0)
                }
                .toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private suspend fun saveMap(key: String, map: Map<String, Int>) {
        // Keep only top 20 entries to bound storage
        val trimmed = map.entries
            .sortedByDescending { it.value }
            .take(20)
            .joinToString(";") { "${it.key}=${it.value}" }
        settingsStore.setString(key, trimmed)
    }
}

/** Accumulated user behavior profile. */
data class UserProfile(
    val interactionCount: Int,
    val avgResponseLength: Int,
    val topTools: List<String>,
    val topTopics: List<String>,
    val morningTopics: List<String>,
    val eveningTopics: List<String>
)
