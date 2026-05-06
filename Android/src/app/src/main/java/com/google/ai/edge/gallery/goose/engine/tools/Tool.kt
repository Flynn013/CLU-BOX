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

import org.json.JSONObject

/**
 * A tool that the agent can invoke.
 *
 * Each tool provides:
 *  - A unique [name] (used by the LLM to call it).
 *  - A [getSchema] method returning an OpenAI-style tool definition JSON.
 *  - An [execute] method that performs the action and returns a [ToolResult].
 */
interface Tool {
    val name: String
    fun getSchema(): JSONObject
    suspend fun execute(input: JSONObject): ToolResult
}

/** Result from executing a tool. */
data class ToolResult(
    val output: String,
    val isError: Boolean = false
)
