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
     * 1. The engine constraint preamble (always LOCAL for Gemma on-device).
     * 2. The CluIdentity genesis block + skill catalogue.
     * 3. Optional RAG core-memory context block.
     *
     * @param engine         Active [AgentEngine] (LOCAL only now — cloud removed).
     * @param basePrompt     The task's default system prompt with skill placeholder.
     * @param skillRegistry  Registry for building the tool catalogue.
     * @param coreMemContext Optional formatted memory block from RagInjector.buildCoreContext().
     * @return               Assembled prompt string ready for the LiteRT runtime.
     */
    fun build(
        engine: AgentEngine,
        basePrompt: String,
        skillRegistry: SkillRegistry,
        coreMemContext: String = "",
    ): String {
        val constraint = AgentGovernor.LOCAL_CONSTRAINT
        val identityAndSkills = skillRegistry.buildFinalSystemPrompt(basePrompt)

        return buildString {
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
