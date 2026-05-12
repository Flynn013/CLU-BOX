/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.goose.engine

import android.util.Log
import com.google.ai.edge.gallery.goose.engine.mcp.McpExtensionManager
import com.google.ai.edge.gallery.goose.engine.mcp.McpTool
import com.google.ai.edge.gallery.goose.engine.providers.LlmProvider
import com.google.ai.edge.gallery.goose.engine.providers.LlmResponse
import com.google.ai.edge.gallery.goose.engine.providers.LlmToolCall
import com.google.ai.edge.gallery.goose.engine.tools.ToolRouter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The non-streaming Goose agent loop.
 *
 * Orchestrates the core think → act → observe cycle:
 *  1. Sends the conversation + tool definitions to the [LlmProvider].
 *  2. Parses the response for tool calls.
 *  3. Executes each tool call via the [ToolRouter] or [McpExtensionManager].
 *  4. Appends tool results to the conversation.
 *  5. Loops back to step 1 until the LLM responds with no tool calls
 *     (or [maxIterations] is hit).
 *
 * For token-by-token streaming, see [StreamingAgentLoop].
 *
 * Ported from MaxFlynn13/goose-android (engine/AgentLoop.kt).
 */
class AgentLoop(
    private val provider: LlmProvider,
    private val toolRouter: ToolRouter,
    private val mcpManager: McpExtensionManager,
    private val maxIterations: Int = 25
) {
    companion object {
        private const val TAG = "AgentLoop"
    }

    private val GOOSE_SYSTEM_PROMPT = """
You are Goose, a powerful AI developer assistant created by Block.

You have access to tools that let you interact with the user's system:
- shell: Execute terminal commands
- write: Create or overwrite files
- edit: Make targeted edits to existing files (find and replace)
- tree: View directory structure

When the user asks you to do something:
1. Think about what steps are needed
2. Use your tools to accomplish the task
3. Verify your work by checking the results
4. Report back to the user

Guidelines:
- Always use the shell tool to run commands rather than just suggesting them
- When editing files, use the edit tool for targeted changes, write for new files
- Check your work — after making changes, verify they're correct
- If a command fails, read the error and try to fix it
- Be proactive — if you see related issues while working, mention them
- Keep the user informed about what you're doing and why

You are running on an Android device. The shell is /system/bin/sh with BusyBox utilities available.
The working directory is the user's project workspace.
""".trimIndent()

    /**
     * Run the full agent loop for a user message.
     *
     * @param userMessage            The new user message.
     * @param conversationHistory    Previous [ConversationMessage]s.
     * @param additionalSystemPrompt Extra instructions appended to the default system prompt.
     * @return A cold [Flow] of [AgentEvent]s — collect it to drive the loop.
     */
    fun run(
        userMessage: String,
        conversationHistory: List<ConversationMessage>,
        additionalSystemPrompt: String = ""
    ): Flow<AgentEvent> = flow {

        val systemPrompt = buildString {
            append(GOOSE_SYSTEM_PROMPT)
            if (additionalSystemPrompt.isNotBlank()) {
                append("\n\n")
                append(additionalSystemPrompt)
            }
        }

        val messages = mutableListOf<ConversationMessage>()
        messages.add(ConversationMessage(role = "system", content = systemPrompt))
        messages.addAll(conversationHistory)
        messages.add(ConversationMessage(role = "user", content = userMessage))

        val toolDefs: List<JSONObject> = buildToolDefinitions()

        var iteration = 0
        val accumulatedText = StringBuilder()

        while (iteration < maxIterations) {
            iteration++
            Log.i(TAG, "Agent loop iteration $iteration")

            currentCoroutineContext().ensureActive()

            val response: LlmResponse = try {
                provider.chat(messages, toolDefs)
            } catch (e: Exception) {
                Log.e(TAG, "LLM call failed on iteration $iteration", e)
                emit(AgentEvent.Error("LLM error: ${e.message}"))
                return@flow
            }

            if (response.thinking.isNotBlank()) {
                emit(AgentEvent.Thinking(response.thinking))
            }

            if (response.text.isNotBlank()) {
                accumulatedText.append(response.text)
                emit(AgentEvent.Token(accumulatedText.toString()))
            }

            if (response.toolCalls.isEmpty()) {
                Log.i(TAG, "Agent loop complete after $iteration iteration(s) — no more tool calls")
                emit(AgentEvent.Complete(accumulatedText.toString()))
                return@flow
            }

            messages.add(ConversationMessage(
                role = "assistant",
                content = response.text
            ))

            for (toolCall in response.toolCalls) {
                currentCoroutineContext().ensureActive()

                Log.i(TAG, "Executing tool: ${toolCall.name} (id=${toolCall.id})")
                emit(AgentEvent.ToolStart(toolCall.id, toolCall.name, toolCall.input.toString()))

                val result = executeTool(toolCall)

                Log.i(TAG, "Tool ${toolCall.name} finished (error=${result.isError})")
                emit(AgentEvent.ToolEnd(toolCall.id, toolCall.name, result.output, result.isError))

                messages.add(ConversationMessage(
                    role = "tool",
                    content = result.output,
                    toolCallId = toolCall.id,
                    toolName = toolCall.name
                ))
            }
        }

        Log.w(TAG, "Agent loop hit max iterations ($maxIterations)")
        emit(AgentEvent.Error(
            "Reached maximum number of steps ($maxIterations). " +
            "The task may be too complex for a single interaction."
        ))
    }

    private fun buildToolDefinitions(): List<JSONObject> {
        val defs = toolRouter.getToolDefinitions().toMutableList()
        val mcpTools: List<McpTool> = mcpManager.getAllTools()
        for (tool in mcpTools) {
            defs.add(mcpToolToJson(tool))
        }
        return defs
    }

    private suspend fun executeTool(toolCall: LlmToolCall): ToolCallResult {
        val builtinNames = toolRouter.getToolNames()
        if (toolCall.name in builtinNames) {
            val result = toolRouter.executeTool(toolCall.name, toolCall.input)
            return ToolCallResult(result.output, result.isError)
        }
        return try {
            val mcpResult = mcpManager.callTool(toolCall.name, toolCall.input)
            ToolCallResult(mcpResult.content, mcpResult.isError)
        } catch (e: IllegalArgumentException) {
            ToolCallResult(
                "Error: Unknown tool '${toolCall.name}'. " +
                "Available: ${builtinNames.joinToString()}, " +
                "${mcpManager.getAllTools().joinToString { it.name }}",
                isError = true
            )
        } catch (e: Exception) {
            ToolCallResult("MCP tool error: ${e.message}", isError = true)
        }
    }
}

// ── Shared helpers ────────────────────────────────────────────────────────────

internal data class ToolCallResult(
    val output: String,
    val isError: Boolean = false
)

internal fun mcpToolToJson(tool: McpTool): JSONObject = JSONObject().apply {
    put("type", "function")
    put("function", JSONObject().apply {
        put("name", tool.name)
        put("description", tool.description)
        put("parameters", if (tool.inputSchema.length() > 0) {
            tool.inputSchema
        } else {
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        })
    })
}
