/*
 * Copyright 2026 Flynn013 / CLU-BOX
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
 * CluSkill: recursive grep wrapper for searching code files.
 *
 * Returns up to 60 `file:line:match` results — enough to locate a symbol
 * across a project without overflowing the context window.
 */
class CodeSearchSkill(private val agentTools: AgentTools) : CluSkill {

    override val name = "codeSearch"

    override val description =
        "Recursively search files for a pattern. Returns file:line:match results. " +
        "Supports grep regex patterns."

    override val jsonSchema = """
        {
          "type": "object",
          "properties": {
            "pattern": {
              "type": "string",
              "description": "grep pattern (supports regex, e.g. 'fun buildFinal.*Prompt')."
            },
            "directory": {
              "type": "string",
              "description": "Directory to search in (absolute path or relative to clu_file_box)."
            },
            "file_extension": {
              "type": "string",
              "description": "Filter by file extension without dot, e.g. kt, py, java. Optional."
            }
          },
          "required": ["pattern", "directory"]
        }
    """.trimIndent()

    override val fewShotExample =
        """{"pattern":"fun buildFinalSystemPrompt","directory":"/sdcard","file_extension":"kt"}"""

    override suspend fun execute(args: JSONObject): String {
        val pattern = args.optString("pattern", "")
        if (pattern.isBlank()) return "[Error: pattern required]"

        val rawDir = args.optString("directory", "")
        if (rawDir.isBlank()) return "[Error: directory required]"

        val dir = resolveDir(rawDir)
        val ext = args.optString("file_extension", "")

        val includeFlag = if (ext.isNotBlank()) "--include=\"*.$ext\"" else ""
        val cmd = "grep -rn $includeFlag -- \"$pattern\" \"$dir\" 2>/dev/null | head -60"

        val result = agentTools.shellExecute(cmd)
        val output = result["stdout"].orEmpty().trim()
        return if (output.isBlank()) "No matches found." else output
    }

    private fun resolveDir(dir: String): String {
        if (dir.startsWith("/")) return dir
        val ctx = agentTools.context ?: return dir
        return java.io.File(ctx.filesDir, "clu_file_box/$dir").canonicalPath
    }
}
