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
import org.json.JSONObject

private const val TAG = "SkillRegistry"

/**
 * Central registry of all active [CluSkill] instances.
 *
 * Responsibilities:
 * - Holds the canonical list of skills available to the LLM.
 * - Provides an execution router via [dispatch] that looks up a
 *   skill by name and calls its [CluSkill.execute] method.
 * - Builds the final composite system prompt via [buildFinalSystemPrompt],
 *   combining [CluIdentity.GENESIS_IDENTITY_BLOCK] with the skills prompt
 *   and the application-level base prompt.
 *
 * A new instance is created per [AgentTools] lifecycle (not a true
 * process-global singleton) because skills hold a reference to their
 * parent [AgentTools].
 */
class SkillRegistry(agentTools: AgentTools) {

  /** All registered skills, keyed by [CluSkill.name] for O(1) lookup. */
  private val skills: Map<String, CluSkill>

  init {
    // Register the explicitly ported skills with full CluSkill implementations.
    val ported: List<CluSkill> = listOf(
      ShellExecuteSkill(agentTools),
      FileBoxWriteSkill(agentTools),
      FileBoxReadLinesSkill(agentTools),
      BrainBoxGrepSkill(agentTools),
    )

    // Merge with metadata-only entries for all other @Tool methods.
    // These don't have a CluSkill.execute() implementation — dispatch
    // for them goes through the litertlm @Tool framework.
    val allSkills = ported + agentTools.getMetadataOnlySkills()

    skills = allSkills.associateBy { it.name }
    Log.d(TAG, "Registered ${skills.size} skills: ${skills.keys.joinToString()}")
  }

  /**
   * Execution router: looks up a skill by [name] and invokes its
   * [CluSkill.execute] method with the provided [args].
   *
   * @return The result string, or an error message if the skill is
   *         not found or execution fails.
   */
  suspend fun dispatch(name: String, args: JSONObject): String {
    val skill = skills[name]
    if (skill == null) {
      Log.w(TAG, "dispatch: skill '$name' not found in registry")
      return "[System Error: Unknown skill '$name'. Available: ${skills.keys.joinToString()}]"
    }
    return try {
      skill.execute(args)
    } catch (e: Exception) {
      Log.e(TAG, "dispatch: skill '$name' threw an exception", e)
      val sanitizedMsg = (e.message ?: "unknown error").take(200)
      "[System Error: Skill '$name' failed. Exception: $sanitizedMsg]"
    }
  }

  /**
   * Constructs the final composite system prompt by replacing the
   * first paragraph of [basePrompt] (the generic "You are CLU..."
   * identity line) with [CluIdentity.GENESIS_IDENTITY_BLOCK].
   *
   * This avoids duplicating identity text and keeps the total prompt
   * within the tight token budget of small models like Gemma-4-E2B
   * (4 000-token input limit).
   */
  fun buildFinalSystemPrompt(basePrompt: String): String {
    // The defaultSystemPrompt starts with a one-paragraph CLU identity
    // sentence followed by a blank line. Replace that paragraph with
    // the Genesis Block so we don't double-count identity tokens.
    val firstBlankLine = basePrompt.indexOf("\n\n")
    val remainder = if (firstBlankLine >= 0) {
      basePrompt.substring(firstBlankLine) // keeps the leading "\n\n"
    } else {
      "\n\n$basePrompt"
    }
    return "${CluIdentity.GENESIS_IDENTITY_BLOCK}$remainder"
  }
}
