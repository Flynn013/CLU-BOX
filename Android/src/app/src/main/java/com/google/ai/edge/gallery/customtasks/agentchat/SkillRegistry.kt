/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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

/** Step-mode addendum appended to the system prompt when STEP mode is enabled. */
private const val STEP_MODE_ADDENDUM =
    "\n\nSTEP MODE: After each tool result, briefly summarise what you found or did. " +
    "Then ask '► Continue?' and wait for the user to reply before calling the next tool."

/**
 * Central registry of all active [CluSkill] instances.
 *
 * Responsibilities:
 * - Holds the canonical list of skills available to the LLM.
 * - Provides an execution router via [dispatch] that looks up a skill by name
 *   and calls its [CluSkill.execute] method.
 * - Builds the final composite system prompt via [buildFinalSystemPrompt],
 *   combining [CluIdentity.GENESIS_IDENTITY_BLOCK] with the skills prompt,
 *   the application-level base prompt, and an optional STEP MODE addendum.
 */
class SkillRegistry(private val agentTools: AgentTools) {

    /** All registered skills, keyed by [CluSkill.name] for O(1) lookup. */
    private val skills: ConcurrentHashMap<String, CluSkill> = ConcurrentHashMap()

    init {
        val ported: List<CluSkill> = listOf(
            PythonExecSkill(),
            ShellExecuteSkill(agentTools),
            FileBoxWriteSkill(agentTools),
            FileBoxReadLinesSkill(agentTools),
            MemorySearchSkill(agentTools),
            MemoryWriteSkill(agentTools),
            BrainBoxGrepSkill(agentTools),
            TodoSkill(agentTools),
            DelegateSkill(agentTools),
            ScdlBoxSkill(agentTools),
            WebFetchSkill(),
            // ── Coding tools ──────────────────────────────────────────
            FileDiffSkill(agentTools),
            FileEditSkill(agentTools),
            CodeSearchSkill(agentTools),
        )

        val allSkills = ported + agentTools.getMetadataOnlySkills()
        allSkills.forEach { skills[it.name] = it }
        Log.d(TAG, "Registered ${skills.size} skills: ${skills.keys.joinToString()}")
    }

    @Synchronized
    fun registerDynamicSkills(dynamicSkills: List<CluSkill>) {
        dynamicSkills.forEach { skills[it.name] = it }
        Log.d(TAG, "Registered ${dynamicSkills.size} dynamic MCP skills: ${dynamicSkills.map { it.name }}")
    }

    suspend fun dispatch(name: String, args: JSONObject): String {
        val skill = skills[name]
        if (skill == null) {
            Log.w(TAG, "dispatch: skill '$name' not found")
            return "[System Error: Unknown skill '$name'. Available: ${skills.keys.joinToString()}]"
        }

        val skillVm = agentTools.skillManagerViewModel
        if (skillVm != null) {
            val skillState = skillVm.uiState.value.skills.find { it.skill.name == name }
            if (skillState != null && !skillState.skill.selected) {
                Log.w(TAG, "dispatch: skill '$name' is disabled")
                return "[System: Skill '$name' is disabled. Enable it in SKILL_BOX to allow its use.]"
            }
        }

        return try {
            skill.execute(args)
        } catch (e: Exception) {
            Log.e(TAG, "dispatch: skill '$name' threw", e)
            "[System Error: Skill '$name' failed. Exception: ${(e.message ?: "unknown").take(200)}]"
        }
    }

    fun buildFinalSystemPrompt(basePrompt: String): String {
        val skillVm = agentTools.skillManagerViewModel
        val disabledSkillNames: Set<String> = if (skillVm != null) {
            skillVm.uiState.value.skills
                .filter { !it.skill.selected }
                .map { it.skill.name }
                .toSet()
        } else {
            emptySet()
        }

        val firstBlankLine = basePrompt.indexOf("\n\n")
        val remainder = if (firstBlankLine >= 0) basePrompt.substring(firstBlankLine) else "\n\n$basePrompt"
        val identityAndBase = "${CluIdentity.GENESIS_IDENTITY_BLOCK}$remainder"

        val result = if (disabledSkillNames.isEmpty()) {
            identityAndBase
        } else {
            identityAndBase.lines().filter { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("{")) {
                    val extractedName = try {
                        JSONObject(trimmed).optString("name", "")
                    } catch (_: JSONException) { "" }
                    extractedName.isEmpty() || extractedName !in disabledSkillNames
                } else {
                    true
                }
            }.joinToString("\n")
        }

        // Append STEP MODE addendum when the user has enabled it.
        val ctx = agentTools.context
        return if (ctx != null && CluAgentSettings.load(ctx)) {
            result + STEP_MODE_ADDENDUM
        } else {
            result
        }
    }

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
                Log.w(TAG, "buildToolDefinitions: bad schema for '$name': ${e.message}")
                continue
            }
            try {
                defs.add(JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", name)
                        put("description", skill.description)
                        put("parameters", schema)
                    })
                })
            } catch (e: JSONException) {
                Log.w(TAG, "buildToolDefinitions: error for '$name': ${e.message}")
            }
        }
        Log.d(TAG, "buildToolDefinitions: ${defs.size} definitions")
        return defs
    }
}
