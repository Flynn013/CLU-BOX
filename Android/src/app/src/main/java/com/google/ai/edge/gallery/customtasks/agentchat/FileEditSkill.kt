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
import java.io.File

/**
 * CluSkill: precise in-place search-and-replace on a file.
 *
 * Replaces the first occurrence of [search] with [replace], leaving the rest
 * of the file untouched — much more token-efficient than a full fileBoxWrite
 * rewrite for targeted edits.
 *
 * Paths are sandbox-checked to prevent directory traversal outside
 * `clu_file_box`.
 */
class FileEditSkill(private val agentTools: AgentTools) : CluSkill {

    override val name = "fileEdit"

    override val description =
        "Search-and-replace a string in a file. " +
        "Use instead of full rewrites for targeted edits to existing files."

    override val jsonSchema = """
        {
          "type": "object",
          "properties": {
            "file_path": {
              "type": "string",
              "description": "Relative path from clu_file_box root, e.g. 'src/Main.kt'."
            },
            "search": {
              "type": "string",
              "description": "Exact string to find (must appear at least once)."
            },
            "replace": {
              "type": "string",
              "description": "Replacement string."
            }
          },
          "required": ["file_path", "search", "replace"]
        }
    """.trimIndent()

    override val fewShotExample =
        """{"file_path":"src/Main.kt","search":"val x = 1","replace":"val x = 2"}"""

    override suspend fun execute(args: JSONObject): String {
        val ctx = agentTools.context
            ?: return "[Error: context not available]"

        val relativePath = args.optString("file_path", "")
        val search = args.optString("search", "")
        val replace = args.optString("replace", "")

        if (relativePath.isBlank()) return "[Error: file_path required]"
        if (search.isEmpty()) return "[Error: search must not be empty]"

        val root = File(ctx.filesDir, "clu_file_box")
        val target = File(root, relativePath).canonicalFile
        if (!target.absolutePath.startsWith(root.canonicalPath)) {
            return "[Error: path traversal denied]"
        }
        if (!target.exists()) return "[Error: file not found: ${target.absolutePath}]"

        val content = target.readText()
        if (!content.contains(search)) {
            return "[Error: search string not found in ${target.absolutePath}]"
        }

        val count = content.split(search).size - 1
        target.writeText(content.replaceFirst(search, replace))
        return "Replaced 1 occurrence (of $count total). File updated: ${target.absolutePath}"
    }
}
