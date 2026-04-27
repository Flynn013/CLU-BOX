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

/**
 * Centralizes all system-prompt assembly for the CLU/BOX agentic loop.
 *
 * Responsibilities:
 * - Select the correct engine-mode preamble (LOCAL vs CLOUD).
 * - Combine the CluIdentity block, skill catalogue, and engine constraint
 *   into a single final prompt string.
 * - Enforce the LOCAL "one tool call per turn" rule via the prompt so the
 *   model is aware of the constraint even before the first inference.
 *
 * Usage (in [AgentChatTaskModule.initializeModelFn]):
 * ```kotlin
 * val finalPrompt = SystemPromptManager.build(
 *     engine  = agentTools.engine,
 *     basePrompt = task.defaultSystemPrompt,
 *     skillRegistry = agentTools.skillRegistry,
 * )
 * helper.initialize(..., systemInstruction = smvm.getSystemPrompt(finalPrompt), ...)
 * ```
 */
object SystemPromptManager {

  // ── Engine-mode preambles ──────────────────────────────────────────────────

  /**
   * LOCAL mode: strict sequential execution.
   *
   * Injected when a LiteRT/on-device model is active.  The instruction
   * addresses the most common failure mode of small models: hallucinating
   * multiple simultaneous tool calls.  Only the **first** call in any
   * turn will be executed; additional calls are silently dropped to protect
   * the device context window.
   */
  private const val LOCAL_MODE_PREAMBLE = """[ENGINE: LOCAL — On-Device LiteRT/Gemma]
STRICT RULE: Execute ONLY ONE tool call per turn.
After each tool call, STOP and wait for the observation before deciding the next step.
Do NOT batch or chain commands in a single response.
Keep responses concise — omit preamble, filler, and unnecessary explanation.
"""

  /**
   * CLOUD mode: high-capacity orchestrator.
   *
   * Injected when a Gemini Cloud API model is active.  Cloud models can
   * handle wider context and longer structured reasoning chains.
   */
  private const val CLOUD_MODE_PREAMBLE = """[ENGINE: CLOUD — Gemini API High-Capacity]
You are a high-capacity orchestrator.
You may plan broadly, chain reasoning steps, batch tool calls where logical, and produce detailed structured output.
Best practices for code quality, documentation, and error handling apply.
"""

  // ── Public API ──────────────────────────────────────────────────────────────

  /**
   * Build the complete system prompt for [engine], combining:
   *
   * 1. Engine-mode preamble (LOCAL or CLOUD, as above).
   * 2. The CluIdentity genesis block + skill catalogue via [SkillRegistry.buildFinalSystemPrompt].
   * 3. The engine constraint block from [AgentGovernor].
   *
   * @param engine        Active [AgentEngine] (LOCAL or CLOUD).
   * @param basePrompt    The task's default system prompt containing the skill
   *                      placeholder `___SKILLS___` and the identity paragraph.
   * @param skillRegistry The [SkillRegistry] for building the tool catalogue and
   *                      injecting [CluIdentity.GENESIS_IDENTITY_BLOCK].
   * @return              The assembled prompt string, ready to be wrapped by
   *                      [SkillManagerViewModel.getSystemPrompt] and passed to
   *                      the engine as `systemInstruction`.
   */
  fun build(
    engine: AgentEngine,
    basePrompt: String,
    skillRegistry: SkillRegistry,
  ): String {
    val modePreamble = if (engine == AgentEngine.LOCAL) LOCAL_MODE_PREAMBLE else CLOUD_MODE_PREAMBLE
    val engineConstraint = if (engine == AgentEngine.LOCAL) AgentGovernor.LOCAL_CONSTRAINT else AgentGovernor.CLOUD_CONSTRAINT
    val skillCataloguePrompt = skillRegistry.buildFinalSystemPrompt(basePrompt)
    return "$modePreamble\n$skillCataloguePrompt\n\n$engineConstraint"
  }
}
