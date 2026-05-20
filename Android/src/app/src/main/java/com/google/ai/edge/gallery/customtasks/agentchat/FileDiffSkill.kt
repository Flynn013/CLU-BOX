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
 * Runs BusyBox `diff -u` on two sandbox files and returns the unified diff.
 *
 * Exit codes: 0 = identical, 1 = differ (diff in stdout), 2+ = error.
 * Both paths are resolved relative to the `clu_file_box` sandbox root.
 */
class FileDiffSkill(private val agentTools: AgentTools) : CluSkill {

    override val name = "fileDiff"

    override val description =
        "Compare two files and return a unified diff. " +
        "Call after editing a file to verify the change is exactly what was intended."

    override val jsonSchema = """
        {
          "name": "fileDiff",
          "description": "Compare two files and return a unified diff.",
          "parameters": {
            "type": "object",
            "properties": {
              "file1_path": {
                "type": "string",
                "description": "Path to the first file (original). Relative to the clu_file_box workspace."
              },
              "file2_path": {
                "type": "string",
                "description": "Path to the second file (modified). Relative to the clu_file_box workspace."
              }
            },
            "required": ["file1_path", "file2_path"]
          }
        }
    """.trimIndent()

    override val fewShotExample =
        """fileDiff(file1_path="backup/main.py", file2_path="main.py")"""

    override suspend fun execute(args: JSONObject): String {
        val f1 = args.optString("file1_path", "").ifBlank { return "[Error: file1_path required]" }
        val f2 = args.optString("file2_path", "").ifBlank { return "[Error: file2_path required]" }

        val ctx = agentTools.context ?: return "[Error: context not available]"
        val root = java.io.File(ctx.filesDir, "clu_file_box").canonicalPath
        val abs1 = java.io.File(root, f1.trimStart('/')).canonicalPath
        val abs2 = java.io.File(root, f2.trimStart('/')).canonicalPath

        val result   = agentTools.shellExecute("diff -u \"$abs1\" \"$abs2\"")
        val exitCode = result["exit_code"]?.toIntOrNull() ?: 0
        val stdout   = result["stdout"] ?: ""

        return when (exitCode) {
            0    -> "Files are identical."
            1    -> stdout.ifBlank { "[No diff output]" }
            else -> "[diff error (exit $exitCode): ${stdout.take(300)}]"
        }
    }
}
