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

import org.json.JSONObject

/**
 * **MEMORY_WRITE** — stores a new fact or note in the BrainBox knowledge graph.
 *
 * Use this to persist important user facts, project context, decisions, and preferences
 * so they can be recalled in future conversations via [MemorySearchSkill].
 *
 * Type guidance:
 * - "Fact" — verifiable fact about the user or their environment
 * - "Project" — project details, architecture, paths
 * - "Preference" — user likes, dislikes, habits
 * - "Decision" — choices made, reasoning
 * - "Session" — summary of a conversation or task
 */
class MemoryWriteSkill(private val agentTools: AgentTools) : CluSkill {

    override val name: String = "memoryWrite"

    override val description: String =
        "Store a new fact, preference, or project note in long-term memory (BrainBox). " +
        "Use after learning something important about the user or completing a significant task."

    override val jsonSchema: String = """
    {
      "name": "memoryWrite",
      "description": "Store a new fact or note in BrainBox long-term memory.",
      "parameters": {
        "type": "object",
        "properties": {
          "label": {
            "type": "string",
            "description": "Short unique title for this memory (e.g. 'User preferred language', 'Project CLU-BOX path')."
          },
          "content": {
            "type": "string",
            "description": "The full content to store. Be specific and complete."
          },
          "type": {
            "type": "string",
            "description": "Category: Fact, Project, Preference, Decision, or Session.",
            "enum": ["Fact", "Project", "Preference", "Decision", "Session"]
          },
          "links": {
            "type": "string",
            "description": "Optional comma-separated labels of related memories, e.g. '[[CLU-BOX]], [[Android SDK]]'."
          }
        },
        "required": ["label", "content", "type"]
      }
    }
    """.trimIndent()

    override val fewShotExample: String =
        """memoryWrite(label="User's project path", content="/home/user/CLU-BOX — Android Kotlin app using Jetpack Compose", type="Project")"""

    override suspend fun execute(args: JSONObject): String {
        val label = args.optString("label", "")
        val content = args.optString("content", "")
        val type = args.optString("type", "Fact")
        val links = args.optString("links", "")
        if (label.isBlank()) return "[MEMORY_WRITE Error: 'label' is required]"
        if (content.isBlank()) return "[MEMORY_WRITE Error: 'content' is required]"
        val result = agentTools.brainBoxWrite(label, type, content, links)
        return result["message"] ?: result["result"] ?: "[MEMORY_WRITE Error: unknown result]"
    }
}
