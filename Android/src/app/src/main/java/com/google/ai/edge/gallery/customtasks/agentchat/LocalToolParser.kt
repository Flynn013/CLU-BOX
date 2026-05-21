/*
 * Copyright 2026 Flynn013 / CLU-BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// Ported from Flynn013/SPL-NTR LocalToolParser.kt (Apache-2.0), adapted to use
// CLU-BOX's ProviderToolCallResult instead of SPL-NTR's LlmToolCall.

package com.google.ai.edge.gallery.customtasks.agentchat

import android.util.Log
import com.google.ai.edge.gallery.data.providers.ProviderToolCallResult
import org.json.JSONObject

/**
 * Parses tool calls from local-model text output.
 *
 * Local models (Gemma) don't have native function calling but can be instructed to output
 * tool calls in a specific text format. This parser detects those patterns and converts
 * them to [ProviderToolCallResult] objects for [AgentEngineV2].
 *
 * Supported formats:
 * 1. XML-style:      `<tool_call>{"name":"shell","arguments":{"command":"ls"}}</tool_call>`
 * 2. Markdown-style: `\`\`\`tool_call\n{...}\n\`\`\``
 * 3. Function-style: `TOOL_CALL: shell({"command":"ls"})`
 *
 * [buildToolInstructions] generates the system prompt block that teaches local models
 * to output format #1.
 */
object LocalToolParser {

    private const val TAG = "LocalToolParser"

    private val XML_PATTERN = Regex(
        """<tool_call>\s*(\{.*?\})\s*</tool_call>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val MARKDOWN_PATTERN = Regex(
        """```tool_call\s*\n(\{.*?\})\s*\n```""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val FUNCTION_PATTERN = Regex(
        """TOOL_CALL:\s*(\w+)\((\{.*?\})\)""",
        RegexOption.DOT_MATCHES_ALL,
    )

    data class ParseResult(
        val cleanText: String,
        val toolCalls: List<ProviderToolCallResult>,
    )

    /** Parse accumulated streaming text for tool calls. Returns clean prose + extracted calls. */
    fun parse(text: String): ParseResult {
        val toolCalls = mutableListOf<ProviderToolCallResult>()
        var cleanText = text

        // XML format (preferred — what buildToolInstructions teaches)
        XML_PATTERN.findAll(text).forEach { match ->
            val tc = parseJsonToolCall(match.groupValues[1])
            if (tc != null) {
                toolCalls.add(tc)
                cleanText = cleanText.replace(match.value, "")
            }
        }

        // Markdown format
        if (toolCalls.isEmpty()) {
            MARKDOWN_PATTERN.findAll(text).forEach { match ->
                val tc = parseJsonToolCall(match.groupValues[1])
                if (tc != null) {
                    toolCalls.add(tc)
                    cleanText = cleanText.replace(match.value, "")
                }
            }
        }

        // Function format
        if (toolCalls.isEmpty()) {
            FUNCTION_PATTERN.findAll(text).forEach { match ->
                val name = match.groupValues[1]
                val argsStr = match.groupValues[2]
                try {
                    val args = JSONObject(argsStr)
                    toolCalls.add(
                        ProviderToolCallResult(
                            id = "local_${System.currentTimeMillis()}_${toolCalls.size}",
                            name = name,
                            input = args,
                        )
                    )
                    cleanText = cleanText.replace(match.value, "")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse function-style tool call: ${e.message}")
                }
            }
        }

        return ParseResult(cleanText = cleanText.trim(), toolCalls = toolCalls)
    }

    /** True when text contains a partial (still streaming) tool call. */
    fun hasPartialToolCall(text: String): Boolean =
        (text.contains("<tool_call>") && !text.contains("</tool_call>")) ||
            (text.contains("```tool_call") && text.count { it == '`' } % 6 != 0) ||
            (text.contains("TOOL_CALL:") && !text.contains(")"))

    /** Strip tool-call markup from text before showing it in the chat UI. */
    fun sanitizeForDisplay(text: String): String {
        var clean = text
            .replace(XML_PATTERN, "")
            .replace(MARKDOWN_PATTERN, "")
            .replace(FUNCTION_PATTERN, "")

        val firstMarker = listOf("<tool_call>", "```tool_call", "TOOL_CALL:")
            .map { clean.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
        if (firstMarker != null) clean = clean.substring(0, firstMarker)

        return clean.trimEnd()
    }

    /**
     * Generates the tool-use instruction block appended to the system prompt for local models.
     * Teaches Gemma to output tool calls in XML format.
     */
    fun buildToolInstructions(tools: List<JSONObject>): String {
        if (tools.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("\n\n## Tool Use (Local Model Protocol)")
        sb.appendLine("Call tools by outputting a <tool_call> block:")
        sb.appendLine("<tool_call>{\"name\": \"tool_name\", \"arguments\": {\"param\": \"value\"}}</tool_call>")
        sb.appendLine()
        sb.appendLine("Available tools:")

        for (tool in tools) {
            val fn = if (tool.has("function")) tool.getJSONObject("function") else tool
            val name = fn.optString("name", "unknown")
            val desc = fn.optString("description", "")
            val params = fn.optJSONObject("parameters") ?: fn.optJSONObject("input_schema")

            sb.appendLine()
            sb.appendLine("### $name")
            sb.appendLine(desc)
            if (params != null) {
                val props = params.optJSONObject("properties")
                if (props != null) {
                    sb.appendLine("Parameters:")
                    props.keys().forEach { key ->
                        val prop = props.getJSONObject(key)
                        sb.appendLine("  - $key (${prop.optString("type", "string")}): ${prop.optString("description", "")}")
                    }
                }
            }
        }

        sb.appendLine()
        sb.appendLine("Call ONE tool at a time. After the result arrives, continue your response.")
        return sb.toString()
    }

    private fun parseJsonToolCall(json: String): ProviderToolCallResult? = try {
        val obj = JSONObject(json)
        val name = obj.optString("name", "").ifBlank { return null }
        val args = obj.optJSONObject("arguments")
            ?: obj.optJSONObject("input")
            ?: obj.optJSONObject("params")
            ?: JSONObject()
        ProviderToolCallResult(
            id = "local_${System.currentTimeMillis()}",
            name = name,
            input = args,
        )
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse tool call JSON: ${e.message}")
        null
    }
}
