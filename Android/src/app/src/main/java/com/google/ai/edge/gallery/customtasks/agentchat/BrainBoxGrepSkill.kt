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
 * **FILE_GREP** — grep-style keyword search within FileBox files.
 *
 * Searches a specific file for a keyword and returns matching lines with
 * ±2 lines of surrounding context — like `grep -C 2 keyword file`.
 *
 * For searching the BrainBox memory database, use [MemorySearchSkill] instead.
 *
 * @param agentTools The [AgentTools] instance to delegate execution to.
 */
class BrainBoxGrepSkill(private val agentTools: AgentTools) : CluSkill {

    override val name: String = "fileGrep"

    override val description: String =
        "Search a FileBox file for a keyword, returning matching lines with context. " +
        "Use for searching within saved files. For memory search use memorySearch instead."

    override val jsonSchema: String = """
    {
      "name": "fileGrep",
      "description": "Keyword search within a FileBox file, returns matching lines with context.",
      "parameters": {
        "type": "object",
        "properties": {
          "file_path": {
            "type": "string",
            "description": "Relative path within FileBox (e.g. 'my_project/main.py')."
          },
          "keyword": {
            "type": "string",
            "description": "Keyword or phrase to search for (case-insensitive)."
          }
        },
        "required": ["file_path", "keyword"]
      }
    }
    """.trimIndent()

    override val fewShotExample: String =
        """fileGrep(file_path="my_project/notes.txt", keyword="TODO")"""

    override suspend fun execute(args: JSONObject): String {
        val filePath = args.optString("file_path", "")
        val keyword = args.optString("keyword", "")
        if (filePath.isBlank()) return "[FILE_GREP Error: 'file_path' is required]"
        if (keyword.isBlank()) return "[FILE_GREP Error: 'keyword' is required]"
        val result = agentTools.brainBoxGrep(filePath, keyword)
        return result["matches"] ?: "[FILE_GREP Error: no result]"
    }
}
