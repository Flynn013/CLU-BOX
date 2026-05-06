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

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Registry and dispatcher for all tools the agent can invoke.
 *
 * Ported from MaxFlynn13/goose-android (engine/tools/ToolRouter.kt).
 */
class ToolRouter(
    workspaceDir: File,
    shellEnv: Map<String, String> = emptyMap(),
    context: Context? = null
) {
    private val tools = mutableMapOf<String, Tool>()

    init {
        register(ShellTool(workspaceDir, shellEnv))
        register(FileWriteTool(workspaceDir))
        register(FileEditTool(workspaceDir))
        register(TreeTool(workspaceDir))
    }

    fun register(tool: Tool) { tools[tool.name] = tool }
    fun registerAll(vararg toolList: Tool) { toolList.forEach { register(it) } }
    fun getToolNames(): List<String> = tools.keys.toList()

    fun getToolDefinitions(): List<JSONObject> = tools.values.map { it.getSchema() }

    suspend fun executeTool(name: String, input: JSONObject): ToolResult {
        val tool = tools[name]
            ?: return ToolResult(
                output = "Error: Unknown tool '$name'. Available: ${tools.keys.joinToString()}",
                isError = true
            )
        return try {
            tool.execute(input)
        } catch (e: Exception) {
            ToolResult(
                output = "Tool '$name' threw an exception: ${e.message ?: e.toString()}",
                isError = true
            )
        }
    }
}
