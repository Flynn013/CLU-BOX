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
 * **MEMORY_SEARCH** — searches the BrainBox knowledge-graph database for stored memories.
 *
 * This is the primary tool for long-term memory retrieval. Call it FIRST whenever you
 * need to recall facts about the user, their projects, or past conversations.
 *
 * Delegates to [AgentTools.brainBoxSearch] which queries the Room database via FTS.
 */
class MemorySearchSkill(private val agentTools: AgentTools) : CluSkill {

    override val name: String = "memorySearch"

    override val description: String =
        "Search long-term memory (BrainBox) for stored facts, project context, and user preferences. " +
        "Call this FIRST before answering questions about the user, their environment, or past decisions."

    override val jsonSchema: String = """
    {
      "name": "memorySearch",
      "description": "Search BrainBox memory for facts, context, and user preferences.",
      "parameters": {
        "type": "object",
        "properties": {
          "query": {
            "type": "string",
            "description": "Keyword or phrase to search for in memory."
          }
        },
        "required": ["query"]
      }
    }
    """.trimIndent()

    override val fewShotExample: String =
        """memorySearch(query="project structure") → returns stored facts about the user's projects"""

    override suspend fun execute(args: JSONObject): String {
        val query = args.optString("query", "")
        if (query.isBlank()) return "[MEMORY_SEARCH Error: 'query' argument is required]"
        val result = agentTools.brainBoxSearch(query)
        return result["result"] ?: "[MEMORY_SEARCH Error: no result returned]"
    }
}
