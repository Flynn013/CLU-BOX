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
import java.io.File

/**
 * Surgically replaces the first occurrence of [search] with [replace] inside a sandbox file.
 *
 * Much more token-efficient than a full [FileBoxWriteSkill] rewrite for small targeted edits.
 * Both paths are resolved inside the `clu_file_box` sandbox with traversal protection.
 */
class FileEditSkill(private val agentTools: AgentTools) : CluSkill {

    override val name = "fileEdit"

    override val description =
        "Surgically search-and-replace a string in a file. " +
        "More efficient than rewriting the whole file with fileBoxWrite — use for targeted edits."

    override val jsonSchema = """
        {
          "name": "fileEdit",
          "description": "Replace the first occurrence of a string in a file.",
          "parameters": {
            "type": "object",
            "properties": {
              "file_path": {
                "type": "string",
                "description": "File path relative to the clu_file_box workspace."
              },
              "search": {
                "type": "string",
                "description": "Exact string to find, including whitespace and newlines."
              },
              "replace": {
                "type": "string",
                "description": "Replacement string. Pass an empty string to delete the found region."
              }
            },
            "required": ["file_path", "search", "replace"]
          }
        }
    """.trimIndent()

    override val fewShotExample =
        """fileEdit(file_path="app/Main.kt", search="val count = 0", replace="val count = 1")"""

    override suspend fun execute(args: JSONObject): String {
        val path    = args.optString("file_path", "").ifBlank { return "[Error: file_path required]" }
        val search  = args.optString("search",    "")
        val replace = args.optString("replace",   "")

        if (search.isEmpty()) return "[Error: search must not be empty]"

        val ctx    = agentTools.context ?: return "[Error: context not available]"
        val root   = File(ctx.filesDir, "clu_file_box")
        val target = File(root, path.trimStart('/')).canonicalFile

        if (!target.absolutePath.startsWith(root.canonicalPath)) {
            return "[Error: path traversal not allowed]"
        }
        if (!target.exists()) return "[Error: file not found: $path]"

        return try {
            val content     = target.readText(Charsets.UTF_8)
            if (!content.contains(search)) return "[Error: search string not found in $path]"
            val occurrences = content.split(search).size - 1
            target.writeText(content.replaceFirst(search, replace), Charsets.UTF_8)
            "Replaced 1 occurrence (of $occurrences total). File updated: $path"
        } catch (e: Exception) {
            "[Error: ${e.message}]"
        }
    }
}
