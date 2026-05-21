/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.customtasks.agentchat

/**
 * System prompt assembly for the CLU/BOX on-device agentic loop.
 *
 * Optimized for Gemma 4B running on LiteRT — tight token budget, clear
 * single-tool-per-turn constraint, explicit memory-first workflow.
 */
object SystemPromptManager {

    /**
     * Build the complete system prompt combining:
     * 1. Optional username line so CLU can address the user by name.
     * 2. The CluIdentity genesis block + skill catalogue.
     * 3. Optional RAG core-memory context block.
     * 4. The engine constraint preamble.
     *
     * @param engine         Active [AgentEngine].
     * @param basePrompt     The task's default system prompt with skill placeholder.
     * @param skillRegistry  Registry for building the tool catalogue.
     * @param coreMemContext Optional formatted memory block from RagInjector.buildCoreContext().
     * @param username       Optional display name set by the user in SETTINGS → PROFILE.
     * @return               Assembled prompt string ready for the runtime.
     */
    fun build(
        engine: AgentEngine,
        basePrompt: String,
        skillRegistry: SkillRegistry,
        coreMemContext: String = "",
        username: String = "",
    ): String {
        val constraint = AgentGovernor.LOCAL_CONSTRAINT
        val identityAndSkills = skillRegistry.buildFinalSystemPrompt(basePrompt)

        return buildString {
            if (username.isNotBlank()) {
                append("USER: You are talking with $username. Address them by name when natural.\n\n")
            }
            append(identityAndSkills)
            if (coreMemContext.isNotBlank()) {
                append("\n\n")
                append(coreMemContext)
            }
            append("\n\n")
            append(constraint)
        }
    }
}
