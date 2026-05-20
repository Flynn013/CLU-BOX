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
 * Selects the inference backend for the CLU agentic loop.
 *
 * - **LOCAL** → on-device LiteRT-LM (Gemma 4 E2B/E4B).
 * - **CLOUD** → remote provider routed through [ProviderRegistry]
 *   (Gemini Cloud or Anthropic Claude, whichever has credentials).
 *
 * Each variant exposes [capabilities] so the loop driver and system-prompt
 * builder can adapt their behaviour — e.g. enforcing the one-tool-per-turn
 * limit on local, or injecting the cloud-specific context constraint.
 */
enum class AgentEngine {
    LOCAL,   // On-device LiteRT-LM (Gemma 4 E2B/E4B)
    CLOUD;   // Cloud provider: Gemini / Anthropic

    /**
     * Runtime capability snapshot used by the agentic loop to decide:
     * - Which system-prompt constraint block to inject.
     * - How many tool calls are allowed per model turn.
     * - Whether to enable extended thinking / parallel tool dispatch.
     * - What context budget [ContextWindowPager] should use for pruning.
     */
    data class Capabilities(
        val contextTokens: Int,
        val maxToolCallsPerTurn: Int,
        val supportsParallelTools: Boolean,
        val supportsExtendedThinking: Boolean,
    )

    val capabilities: Capabilities
        get() = when (this) {
            LOCAL -> Capabilities(
                contextTokens            = 8_000,
                maxToolCallsPerTurn      = 1,
                supportsParallelTools    = false,
                supportsExtendedThinking = false,
            )
            CLOUD -> Capabilities(
                contextTokens            = 128_000,
                maxToolCallsPerTurn      = Int.MAX_VALUE,
                supportsParallelTools    = true,
                supportsExtendedThinking = true,
            )
        }
}
