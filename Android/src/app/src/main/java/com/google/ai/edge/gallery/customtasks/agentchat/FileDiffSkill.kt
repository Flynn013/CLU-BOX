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
 * CluSkill: compare two files and return a unified diff.
 *
 * Paths are resolved relative to the `clu_file_box` workspace root if they
 * don't start with `/`.  Absolute paths outside the workspace are allowed so
 * the agent can compare, e.g., a downloaded reference against an edited copy.
 */
class FileDiffSkill(private val agentTools: AgentTools) : CluSkill {

    override val name = "fileDiff"

    override val description =
        "Compare two files and return a unified diff. " +
        "Relative paths resolve to the clu_file_box workspace root."

    override val jsonSchema = """
        {
          "type": "object",
          "properties": {
            "file1_path": {
              "type": "string",
              "description": "Path of the first (old) file."
            },
            "file2_path": {
              "type": "string",
              "description": "Path of the second (new) file."
            }
          },
          "required": ["file1_path", "file2_path"]
        }
    """.trimIndent()

    override val fewShotExample =
        """{"file1_path":"old_main.kt","file2_path":"main.kt"}"""

    override suspend fun execute(args: JSONObject): String {
        val raw1 = args.optString("file1_path", "")
        val raw2 = args.optString("file2_path", "")
        if (raw1.isBlank()) return "[Error: file1_path required]"
        if (raw2.isBlank()) return "[Error: file2_path required]"

        val abs1 = resolvePath(raw1)
        val abs2 = resolvePath(raw2)

        val result = agentTools.shellExecute("""diff -u "$abs1" "$abs2"""")
        val exitCode = result["exit_code"]?.trim()?.toIntOrNull() ?: -1
        return when (exitCode) {
            0 -> "Files are identical."
            1 -> result["stdout"]?.ifBlank { "[No diff output]" } ?: "[No diff output]"
            else -> "[diff error (exit $exitCode): ${result["stdout"].orEmpty().take(300)} ${result["stderr"].orEmpty().take(200)}]"
        }
    }

    private fun resolvePath(path: String): String {
        if (path.startsWith("/")) return path
        val ctx = agentTools.context ?: return path
        val root = File(ctx.filesDir, "clu_file_box")
        return File(root, path).canonicalPath
    }
}
