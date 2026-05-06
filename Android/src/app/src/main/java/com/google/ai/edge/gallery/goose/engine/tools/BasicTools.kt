/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.goose.engine.tools

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader

/**
 * Core developer tools: shell, write, edit, tree.
 *
 * Adapted from MaxFlynn13/goose-android ToolDefinitions.kt, scoped to the
 * CLU/BOX `goose_workspace` sandbox directory.
 */

// ── shell ──────────────────────────────────────────────────────────────────────

class ShellTool(
    private val workspaceDir: File,
    private val shellEnv: Map<String, String> = emptyMap()
) : Tool {
    override val name = "shell"

    override fun getSchema(): JSONObject = JSONObject("""
    {
      "type": "function",
      "function": {
        "name": "shell",
        "description": "Execute a shell command in the workspace sandbox. Returns stdout+stderr.",
        "parameters": {
          "type": "object",
          "properties": {
            "command": { "type": "string", "description": "The bash command to run" },
            "timeout": { "type": "integer", "description": "Max seconds to wait (default 30)" }
          },
          "required": ["command"]
        }
      }
    }""".trimIndent())

    override suspend fun execute(input: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val command = input.optString("command", "").trim()
        if (command.isEmpty()) return@withContext ToolResult("Error: 'command' is required", isError = true)

        val timeout = input.optInt("timeout", 30).coerceIn(1, 300)

        try {
            val pb = ProcessBuilder("sh", "-c", command).apply {
                directory(workspaceDir)
                redirectErrorStream(true)
                environment().apply {
                    putAll(shellEnv)
                    putIfAbsent("HOME", workspaceDir.absolutePath)
                    putIfAbsent("PATH", "/system/bin:/system/xbin")
                }
            }

            val process = pb.start()
            val finished = process.waitFor(timeout.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            val output = InputStreamReader(process.inputStream).use { it.readText() }

            if (!finished) {
                process.destroyForcibly()
                return@withContext ToolResult(
                    "TIMEOUT: Command exceeded ${timeout}s.\nOutput so far:\n$output",
                    isError = true
                )
            }

            val exitCode = process.exitValue()
            val result = if (output.isBlank()) "(no output, exit code $exitCode)" else output

            ToolResult(result, isError = exitCode != 0)
        } catch (e: Exception) {
            Log.e("ShellTool", "Shell error: ${e.message}", e)
            ToolResult("Shell error: ${e.message}", isError = true)
        }
    }
}

// ── write ──────────────────────────────────────────────────────────────────────

class FileWriteTool(private val workspaceDir: File) : Tool {
    override val name = "write"

    override fun getSchema(): JSONObject = JSONObject("""
    {
      "type": "function",
      "function": {
        "name": "write",
        "description": "Create or overwrite a file with the given content. Parent directories are created automatically.",
        "parameters": {
          "type": "object",
          "properties": {
            "path": { "type": "string", "description": "Relative file path within the workspace" },
            "content": { "type": "string", "description": "Full file content to write" }
          },
          "required": ["path", "content"]
        }
      }
    }""".trimIndent())

    override suspend fun execute(input: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val path = input.optString("path", "").trim()
        val content = input.optString("content", "")
        if (path.isEmpty()) return@withContext ToolResult("Error: 'path' is required", isError = true)

        try {
            val file = File(workspaceDir, path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            ToolResult("Written ${content.length} chars to $path")
        } catch (e: Exception) {
            ToolResult("Write error: ${e.message}", isError = true)
        }
    }
}

// ── edit ──────────────────────────────────────────────────────────────────────

class FileEditTool(private val workspaceDir: File) : Tool {
    override val name = "edit"

    override fun getSchema(): JSONObject = JSONObject("""
    {
      "type": "function",
      "function": {
        "name": "edit",
        "description": "Make a targeted find-and-replace edit to an existing file. Replaces the first occurrence of 'old_text' with 'new_text'.",
        "parameters": {
          "type": "object",
          "properties": {
            "path": { "type": "string", "description": "Relative file path within the workspace" },
            "old_text": { "type": "string", "description": "The exact text to find and replace" },
            "new_text": { "type": "string", "description": "The replacement text" }
          },
          "required": ["path", "old_text", "new_text"]
        }
      }
    }""".trimIndent())

    override suspend fun execute(input: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val path = input.optString("path", "").trim()
        val oldText = input.optString("old_text", "")
        val newText = input.optString("new_text", "")
        if (path.isEmpty()) return@withContext ToolResult("Error: 'path' is required", isError = true)

        try {
            val file = File(workspaceDir, path)
            if (!file.exists()) return@withContext ToolResult("File not found: $path", isError = true)

            val current = file.readText()
            if (!current.contains(oldText)) {
                return@withContext ToolResult(
                    "Error: old_text not found in $path.\n" +
                    "Make sure the text matches exactly (including whitespace and line endings).",
                    isError = true
                )
            }
            val updated = current.replaceFirst(oldText, newText)
            file.writeText(updated)
            ToolResult("Edited $path: replaced ${oldText.length} chars with ${newText.length} chars")
        } catch (e: Exception) {
            ToolResult("Edit error: ${e.message}", isError = true)
        }
    }
}

// ── tree ──────────────────────────────────────────────────────────────────────

class TreeTool(private val workspaceDir: File) : Tool {
    override val name = "tree"

    override fun getSchema(): JSONObject = JSONObject("""
    {
      "type": "function",
      "function": {
        "name": "tree",
        "description": "Show the directory structure of the workspace (or a sub-path). Returns a text tree.",
        "parameters": {
          "type": "object",
          "properties": {
            "path": { "type": "string", "description": "Sub-path to show (empty = workspace root)" },
            "max_depth": { "type": "integer", "description": "Maximum depth to display (default 4)" }
          },
          "required": []
        }
      }
    }""".trimIndent())

    override suspend fun execute(input: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val subPath = input.optString("path", "").trim()
        val maxDepth = input.optInt("max_depth", 4).coerceIn(1, 10)

        val rootDir = if (subPath.isEmpty()) workspaceDir else File(workspaceDir, subPath)
        if (!rootDir.exists()) return@withContext ToolResult("Path not found: $subPath", isError = true)

        val sb = StringBuilder()
        sb.append(rootDir.name).append("/\n")
        buildTree(rootDir, "", 0, maxDepth, sb)
        ToolResult(sb.toString())
    }

    private fun buildTree(dir: File, prefix: String, depth: Int, maxDepth: Int, sb: StringBuilder) {
        if (depth >= maxDepth) return
        val entries = dir.listFiles()?.sortedWith(compareBy({ it.isFile }, { it.name })) ?: return
        entries.forEachIndexed { idx, file ->
            val isLast = idx == entries.size - 1
            val connector = if (isLast) "└── " else "├── "
            sb.append(prefix).append(connector).append(file.name)
            if (file.isDirectory) sb.append("/")
            sb.append("\n")
            if (file.isDirectory) {
                val newPrefix = prefix + if (isLast) "    " else "│   "
                buildTree(file, newPrefix, depth + 1, maxDepth, sb)
            }
        }
    }
}
