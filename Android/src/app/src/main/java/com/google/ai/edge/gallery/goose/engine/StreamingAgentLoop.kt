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
import com.google.ai.edge.gallery.goose.engine.providers.LlmToolCall
import com.google.ai.edge.gallery.goose.engine.providers.StreamEvent
import com.google.ai.edge.gallery.goose.engine.tools.ToolRouter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

/**
 * Streaming variant of the Goose agent loop.
 *
 * Identical think → act → observe cycle as [AgentLoop], but uses
 * [LlmProvider.streamChat] so tokens are emitted to the UI the instant
 * they arrive from the API.
 *
 * Ported from MaxFlynn13/goose-android (engine/StreamingAgentLoop.kt).
 */
class StreamingAgentLoop(
    private val provider: LlmProvider,
    private val toolRouter: ToolRouter,
    private val mcpManager: McpExtensionManager,
    private val maxIterations: Int = 25,
    private val permissionManager: PermissionManager? = null,
    private val contextTracker: ContextTracker? = null,
    private val tokenCounter: TokenCounter? = null
) {
    companion object {
        private const val TAG = "StreamingAgentLoop"
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
     * Run the streaming agent loop for a user message.
     *
     * @param userMessage            The new user message.
     * @param conversationHistory    Previous messages for context.
     * @param additionalSystemPrompt Extra instructions appended to the system prompt.
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
        for (histMsg in conversationHistory) {
            if (histMsg.role == "system") continue
            messages.add(histMsg)
        }
        messages.add(ConversationMessage(role = "user", content = userMessage))

        val toolDefs: List<JSONObject> = buildToolDefinitions()
        var iteration = 0
        val globalAccumulatedText = StringBuilder()

        while (iteration < maxIterations) {
            iteration++
            Log.i(TAG, "Streaming agent loop iteration $iteration")

            currentCoroutineContext().ensureActive()

            // Context-window management: truncate if conversation is approaching the limit
            val modelId = provider.modelId
            if (tokenCounter != null) {
                val action = tokenCounter.checkContextUsage(messages, modelId)
                if (action == TokenCounter.ContextAction.HARD_TRUNCATE ||
                    action == TokenCounter.ContextAction.COMPACT) {
                    val truncated = tokenCounter.truncateMessages(messages, modelId)
                    messages.clear()
                    messages.addAll(truncated)
                    Log.i(TAG, "Context truncated to ${messages.size} messages for model $modelId")
                }
            }

            val turnText = StringBuilder()
            val turnThinking = StringBuilder()
            val turnToolCalls = mutableListOf<LlmToolCall>()
            var streamError: String? = null

            try {
                val streamFlow: Flow<StreamEvent> = provider.streamChat(messages, toolDefs)

                streamFlow.collect { event ->
                    currentCoroutineContext().ensureActive()

                    when (event) {
                        is StreamEvent.Token -> {
                            turnText.append(event.text)
                            globalAccumulatedText.append(event.text)
                            emit(AgentEvent.Token(globalAccumulatedText.toString()))
                        }
                        is StreamEvent.Thinking -> {
                            turnThinking.append(event.text)
                            emit(AgentEvent.Thinking(event.text))
                        }
                        is StreamEvent.ToolCallStart -> {
                            Log.d(TAG, "Tool call starting: ${event.name} (id=${event.id})")
                        }
                        is StreamEvent.ToolCallInput -> {
                            Log.v(TAG, "Tool call input chunk for ${event.id}")
                        }
                        is StreamEvent.ToolCallEnd -> {
                            turnToolCalls.add(LlmToolCall(event.id, event.name, event.input))
                            Log.d(TAG, "Tool call complete: ${event.name} (id=${event.id})")
                        }
                        is StreamEvent.Done -> {
                            if (turnToolCalls.isEmpty() && event.toolCalls.isNotEmpty()) {
                                turnToolCalls.addAll(event.toolCalls)
                            }
                            if (turnText.isEmpty() && event.fullText.isNotBlank()) {
                                turnText.append(event.fullText)
                                globalAccumulatedText.append(event.fullText)
                                emit(AgentEvent.Token(globalAccumulatedText.toString()))
                            }
                        }
                        is StreamEvent.Error -> {
                            streamError = event.message
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Stream failed on iteration $iteration", e)
                emit(AgentEvent.Error("Streaming error: ${e.message}"))
                return@flow
            }

            if (streamError != null) {
                Log.e(TAG, "Stream reported error: $streamError")
                emit(AgentEvent.Error("LLM stream error: $streamError"))
                return@flow
            }

            if (turnToolCalls.isEmpty()) {
                Log.i(TAG, "Streaming loop complete after $iteration iteration(s) — no tool calls")
                emit(AgentEvent.Complete(globalAccumulatedText.toString()))
                return@flow
            }

            messages.add(ConversationMessage(
                role = "assistant",
                content = turnText.toString(),
                toolCalls = turnToolCalls.map { ToolCallInfo(it.id, it.name, it.input) }
            ))

            for (toolCall in turnToolCalls) {
                currentCoroutineContext().ensureActive()

                // Permission check for destructive operations
                if (permissionManager != null) {
                    val allowed = permissionManager.checkPermission(toolCall.name, toolCall.input)
                    if (!allowed) {
                        Log.i(TAG, "Tool ${toolCall.name} denied by permission manager")
                        val deniedResult = ToolCallResult(
                            "Operation denied by user.",
                            isError = true
                        )
                        emit(AgentEvent.ToolStart(toolCall.id, toolCall.name, toolCall.input.toString()))
                        emit(AgentEvent.ToolEnd(toolCall.id, toolCall.name, deniedResult.output, deniedResult.isError))
                        messages.add(ConversationMessage(
                            role = "tool",
                            content = deniedResult.output,
                            toolCallId = toolCall.id,
                            toolName = toolCall.name
                        ))
                        continue
                    }
                }

                Log.i(TAG, "Executing tool: ${toolCall.name} (id=${toolCall.id})")
                emit(AgentEvent.ToolStart(toolCall.id, toolCall.name, toolCall.input.toString()))

                val result = executeTool(toolCall)

                // Track context for file operations
                when (toolCall.name) {
                    "write", "edit" -> contextTracker?.recordFileModified(
                        toolCall.input.optString("path", "")
                    )
                    "shell" -> contextTracker?.recordCommand(
                        toolCall.input.optString("command", ""),
                        if (result.isError) 1 else 0,
                        result.output
                    )
                }

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

        Log.w(TAG, "Streaming agent loop hit max iterations ($maxIterations)")
        if (globalAccumulatedText.isNotEmpty()) {
            emit(AgentEvent.Complete(globalAccumulatedText.toString()))
        }
        emit(AgentEvent.Error(
            "Reached maximum number of steps ($maxIterations). " +
            "The task may be too complex for a single interaction."
        ))
    }

    private fun buildToolDefinitions(): List<JSONObject> {
        val defs = toolRouter.getToolDefinitions().toMutableList()
        val mcpTools: List<McpTool> = mcpManager.getAllTools()
        for (tool in mcpTools) defs.add(mcpToolToJson(tool))
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
