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
 * Recursively greps a directory for a pattern via BusyBox `grep -rn`.
 *
 * Returns up to 60 `file:line:content` matches.  Works on any directory the
 * app process can read — not sandboxed to `clu_file_box`.
 */
class CodeSearchSkill(private val agentTools: AgentTools) : CluSkill {

    override val name = "codeSearch"

    override val description =
        "Recursively search files for a pattern. " +
        "Returns file:line:content matches. " +
        "Call before editing to locate existing implementations."

    override val jsonSchema = """
        {
          "name": "codeSearch",
          "description": "Recursively grep for a pattern across a directory. Returns file:line:content matches.",
          "parameters": {
            "type": "object",
            "properties": {
              "pattern": {
                "type": "string",
                "description": "Search pattern (grep-compatible regex)."
              },
              "directory": {
                "type": "string",
                "description": "Absolute path of the directory to search in."
              },
              "file_extension": {
                "type": "string",
                "description": "Optional extension filter e.g. kt, py, java. Omit to search all text files."
              }
            },
            "required": ["pattern", "directory"]
          }
        }
    """.trimIndent()

    override val fewShotExample =
        """codeSearch(pattern="fun buildFinalSystemPrompt", directory="/sdcard/clu_file_box", file_extension="kt")"""

    override suspend fun execute(args: JSONObject): String {
        val pattern = args.optString("pattern",        "").ifBlank { return "[Error: pattern required]" }
        val dir     = args.optString("directory",      "").ifBlank { return "[Error: directory required]" }
        val ext     = args.optString("file_extension", "")

        val includeFlag = if (ext.isNotBlank()) "--include=\"*.$ext\"" else ""
        val cmd = "grep -rn $includeFlag -- \"$pattern\" \"$dir\" 2>/dev/null | head -60"

        val result = agentTools.shellExecute(cmd)
        val output = result["stdout"] ?: ""
        return if (output.isBlank()) "No matches found for '$pattern' in $dir." else output
    }
}
