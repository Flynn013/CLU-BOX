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
import com.google.ai.edge.gallery.data.CluAgentSettings
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

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
class SkillRegistry(private val agentTools: AgentTools) {

  /** All registered skills, keyed by [CluSkill.name] for O(1) lookup. */
  private val skills: ConcurrentHashMap<String, CluSkill> = ConcurrentHashMap()

  init {
    // Register the explicitly ported skills with full CluSkill implementations.
    val ported: List<CluSkill> = listOf(
      PythonExecSkill(),
      ShellExecuteSkill(agentTools),
      // VirtualCommandSkill removed: shell access is now provided exclusively
      // by ShellExecuteSkill, which delegates to the embedded BusyBoxBridge.
      FileBoxWriteSkill(agentTools),
      FileBoxReadLinesSkill(agentTools),
      FileEditSkill(agentTools),
      FileDiffSkill(agentTools),
      CodeSearchSkill(agentTools),
      MemorySearchSkill(agentTools),
      MemoryWriteSkill(agentTools),
      BrainBoxGrepSkill(agentTools),
      TodoSkill(agentTools),
      DelegateSkill(agentTools),
      ScdlBoxSkill(agentTools),
      WebFetchSkill(),
    )

    // Merge with metadata-only entries for all other @Tool methods.
    // These don't have a CluSkill.execute() implementation — dispatch
    // for them goes through the litertlm @Tool framework.
    val allSkills = ported + agentTools.getMetadataOnlySkills()

    allSkills.forEach { skills[it.name] = it }
    Log.d(TAG, "Registered ${skills.size} skills: ${skills.keys.joinToString()}")
  }

  /**
   * Dynamically registers [McpDynamicSkill] instances loaded at runtime from a connected
   * MCP server.  Any existing entry with the same name is replaced, allowing reconnect /
   * refresh scenarios to work correctly.
   *
   * This method is safe to call from any thread.
   */
  @Synchronized
  fun registerDynamicSkills(dynamicSkills: List<CluSkill>) {
    dynamicSkills.forEach { skills[it.name] = it }
    Log.d(TAG, "Registered ${dynamicSkills.size} dynamic MCP skills: ${dynamicSkills.map { it.name }}")
  }

  /**
   * Execution router: looks up a skill by [name] and invokes its
   * [CluSkill.execute] method with the provided [args].
   *
   * Skills with `selected == false` in [SkillManagerViewModel] are gated
   * here: dispatching a disabled skill returns an informational error string
   * instead of executing, ensuring the agent loop cannot invoke skills the
   * operator has turned off in SKILL_BOX.
   *
   * @return The result string, or an error message if the skill is
   *         not found, disabled, or execution fails.
   */
  suspend fun dispatch(name: String, args: JSONObject): String {
    val skill = skills[name]
    if (skill == null) {
      Log.w(TAG, "dispatch: skill '$name' not found in registry")
      return "[System Error: Unknown skill '$name'. Available: ${skills.keys.joinToString()}]"
    }

    // ── isEnabled gate (Phase 4 Skill Enforcement) ────────────────────
    // If the SkillManagerViewModel has loaded and marks this skill as
    // deselected, refuse to execute and return a user-visible notice.
    val skillVm = agentTools.skillManagerViewModel
    if (skillVm != null) {
      val skillState = skillVm.uiState.value.skills.find { it.skill.name == name }
      if (skillState != null && !skillState.skill.selected) {
        Log.w(TAG, "dispatch: skill '$name' is disabled — skipping execution")
        return "[System: Skill '$name' is disabled. Enable it in SKILL_BOX to allow its use.]"
      }
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
   * If [SkillManagerViewModel] is available, skills marked as
   * `selected == false` are omitted from the tool declaration block,
   * so the LLM never sees—and cannot invoke—disabled skills.
   *
   * This avoids duplicating identity text and keeps the total prompt
   * within the tight token budget of small models like Gemma-4-E2B
   * (4 000-token input limit).
   */
  fun buildFinalSystemPrompt(basePrompt: String): String {
    // Determine which skill names are enabled.
    val skillVm = agentTools.skillManagerViewModel
    val disabledSkillNames: Set<String> = if (skillVm != null) {
      skillVm.uiState.value.skills
        .filter { !it.skill.selected }
        .map { it.skill.name }
        .toSet()
    } else {
      emptySet()
    }

    // The defaultSystemPrompt starts with a one-paragraph CLU identity
    // sentence followed by a blank line. Replace that paragraph with
    // the Genesis Block so we don't double-count identity tokens.
    val firstBlankLine = basePrompt.indexOf("\n\n")
    val remainder = if (firstBlankLine >= 0) {
      basePrompt.substring(firstBlankLine) // keeps the leading "\n\n"
    } else {
      "\n\n$basePrompt"
    }

    val identityAndBase = "${CluIdentity.GENESIS_IDENTITY_BLOCK}$remainder"

    val result = if (disabledSkillNames.isEmpty()) {
      identityAndBase
    } else {
      // Strip JSON schema lines that belong to disabled skills.
      val filtered = identityAndBase.lines().filter { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("{")) {
          val extractedName = try {
            JSONObject(trimmed).optString("name", "")
          } catch (_: JSONException) {
            ""
          }
          extractedName.isEmpty() || extractedName !in disabledSkillNames
        } else {
          true
        }
      }
      filtered.joinToString("\n")
    }

    // Append STEP MODE addendum when the user has enabled it in SETTINGS → CONFIG.
    val ctx = agentTools.context
    return if (ctx != null && CluAgentSettings.load(ctx)) {
      result + STEP_MODE_ADDENDUM
    } else {
      result
    }
  }

  companion object {
    private const val STEP_MODE_ADDENDUM = "\n\nSTEP MODE: After each tool result, briefly summarise what you found or did. Then ask '▶ Continue?' and wait for the user to reply before calling the next tool."
  }

  /**
   * Builds a list of tool-definition [JSONObject]s in OpenAI function-calling format
   * for every **enabled** skill in the registry.
   *
   * The list is passed by [AgentEngineV2] to [LlmProvider.streamChat] / [LlmProvider.chat]
   * so cloud providers can offer function-calling to the model.
   *
   * Each object follows:
   * ```json
   * {
   *   "type": "function",
   *   "function": {
   *     "name": "...",
   *     "description": "...",
   *     "parameters": { ... }
   *   }
   * }
   * ```
   *
   * Skills whose `jsonSchema` cannot be parsed as JSON are omitted with a warning.
   */
  fun buildToolDefinitions(): List<JSONObject> {
    val skillVm = agentTools.skillManagerViewModel
    val disabledNames: Set<String> = if (skillVm != null) {
      skillVm.uiState.value.skills.mapNotNullTo(mutableSetOf()) { s ->
        if (!s.skill.selected) s.skill.name else null
      }
    } else {
      emptySet()
    }

    val defs = mutableListOf<JSONObject>()
    for ((name, skill) in skills) {
      if (name in disabledNames) continue
      val schema = try {
        JSONObject(skill.jsonSchema)
      } catch (e: JSONException) {
        Log.w(TAG, "buildToolDefinitions: could not parse schema for '$name': ${e.message}")
        continue
      }
      try {
        val def = JSONObject().apply {
          put("type", "function")
          put("function", JSONObject().apply {
            put("name", name)
            put("description", skill.description)
            put("parameters", schema)
          })
        }
        defs.add(def)
      } catch (e: JSONException) {
        Log.w(TAG, "buildToolDefinitions: error building def for '$name': ${e.message}")
      }
    }
    Log.d(TAG, "buildToolDefinitions: ${defs.size} tool definitions built")
    return defs
  }
}
