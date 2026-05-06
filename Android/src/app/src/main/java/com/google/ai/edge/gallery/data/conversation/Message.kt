/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Message content shape adapted from Flynn013/SPL-NTR ChatModels.kt (Apache-2.0) and the
// Goose conversation/message.rs MessageContent enum — extended with CLU/BOX-specific variants
// (SkillProgress, SystemNotification) and mapped to the existing ChatSide/ChatMessageType system.

package com.google.ai.edge.gallery.data.conversation

import com.google.ai.edge.gallery.data.session.SessionMessage
import com.google.ai.edge.gallery.data.session.MessageRole
import com.google.ai.edge.gallery.data.session.ToolCallStatus
import com.google.ai.edge.gallery.data.session.SessionToolCall
import java.util.UUID

// ── Role ───────────────────────────────────────────────────────────────────────

/** Which party produced this [Message]. */
enum class Role { USER, ASSISTANT, SYSTEM, TOOL }

// ── Tool call types ────────────────────────────────────────────────────────────

/** Status of a tool call as it progresses through the agent loop. */
enum class ToolStatus { PENDING, RUNNING, COMPLETE, ERROR }

/**
 * A tool-call record embedded inside an [Message] with role [Role.ASSISTANT].
 *
 * A single assistant turn may invoke multiple tools.
 */
data class ToolCallRecord(
    val id: String,
    val name: String,
    val inputJson: String = "",
    val output: String = "",
    val status: ToolStatus = ToolStatus.PENDING,
)

// ── Message content ────────────────────────────────────────────────────────────

/**
 * Typed content variants that can appear inside a [Message].
 *
 * Mirrors the Goose `MessageContent` enum and the SPL-NTR `ChatMessage` hierarchy —
 * adapted to the CLU/BOX identity layer (CLU, not Goose).
 */
sealed class MessageContent {
    /** Plain text or Markdown from the user or assistant. */
    data class Text(val text: String, val isMarkdown: Boolean = true) : MessageContent()

    /**
     * Chain-of-thought reasoning text produced during extended-thinking mode
     * (Anthropic "thinking", Gemini "thinking" feature).
     */
    data class Thinking(val text: String, val inProgress: Boolean = false) : MessageContent()

    /**
     * A request from the assistant to call a tool.  Always paired with a
     * subsequent [ToolResult] message.
     */
    data class ToolRequest(
        val id: String,
        val name: String,
        val inputJson: String,
    ) : MessageContent()

    /**
     * The result (success or error) returned by a tool invocation.
     *
     * Emitted by the agent loop after [ToolRequest] is executed.
     */
    data class ToolResult(
        val id: String,
        val name: String,
        val output: String,
        val isError: Boolean = false,
    ) : MessageContent()

    /**
     * An "action required" content block — the loop has paused and is
     * waiting for the user to approve or deny this tool call.
     *
     * The UI should render an approval sheet; approved/denied results flow
     * back through [com.google.ai.edge.gallery.data.permission.ActionRequiredQueue].
     */
    data class ToolConfirmationRequest(
        val id: String,
        val name: String,
        val inputJson: String,
        /** Human-readable risk summary shown on the approval sheet. */
        val riskSummary: String = "",
    ) : MessageContent()

    /**
     * A skill-progress status string shown in the collapsable progress panel
     * (CLU/BOX-specific; maps to `SkillProgressAgentAction` at the chat layer).
     */
    data class SkillProgress(
        val statusLine: String,
        val inProgress: Boolean = true,
    ) : MessageContent()

    /**
     * A non-interactive system notification displayed as a system bubble
     * (info, warning, or error).
     */
    enum class NotificationLevel { INFO, WARNING, ERROR }

    data class SystemNotification(
        val text: String,
        val level: NotificationLevel = NotificationLevel.INFO,
    ) : MessageContent()
}

// ── Message ────────────────────────────────────────────────────────────────────

/**
 * A single typed message in the CLU/BOX conversation layer.
 *
 * A message carries one or more [MessageContent] items — e.g. an assistant turn
 * may include a [MessageContent.Thinking] block followed by a [MessageContent.Text]
 * block and one or more [MessageContent.ToolRequest] blocks.
 *
 * This class is the in-memory representation used by the chat ViewModel and
 * the [com.google.ai.edge.gallery.customtasks.agentchat.AgentEngineV2] loop.
 * For persistence, convert to/from [SessionMessage] via [toSessionMessage] /
 * [fromSessionMessage].
 */
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val contents: List<MessageContent>,
    val timestamp: Long = System.currentTimeMillis(),
    /** When role=TOOL, the id of the ToolRequest this answers. */
    val toolCallId: String? = null,
) {
    // ── Convenience factories ────────────────────────────────────────────

    companion object {
        fun user(text: String): Message =
            Message(role = Role.USER, contents = listOf(MessageContent.Text(text)))

        fun assistant(text: String): Message =
            Message(role = Role.ASSISTANT, contents = listOf(MessageContent.Text(text)))

        fun system(text: String, level: MessageContent.NotificationLevel = MessageContent.NotificationLevel.INFO): Message =
            Message(role = Role.SYSTEM, contents = listOf(MessageContent.SystemNotification(text, level)))

        fun toolResult(toolCallId: String, name: String, output: String, isError: Boolean = false): Message =
            Message(
                role = Role.TOOL,
                toolCallId = toolCallId,
                contents = listOf(MessageContent.ToolResult(toolCallId, name, output, isError)),
            )

        fun thinking(text: String, inProgress: Boolean = false): Message =
            Message(role = Role.ASSISTANT, contents = listOf(MessageContent.Thinking(text, inProgress)))

        fun toolRequest(id: String, name: String, inputJson: String): Message =
            Message(role = Role.ASSISTANT, contents = listOf(MessageContent.ToolRequest(id, name, inputJson)))

        fun confirmationRequest(id: String, name: String, inputJson: String, riskSummary: String = ""): Message =
            Message(
                role = Role.ASSISTANT,
                contents = listOf(MessageContent.ToolConfirmationRequest(id, name, inputJson, riskSummary)),
            )

        fun skillProgress(statusLine: String, inProgress: Boolean = true): Message =
            Message(role = Role.SYSTEM, contents = listOf(MessageContent.SkillProgress(statusLine, inProgress)))

        // ── Conversion from SessionMessage ────────────────────────────────

        fun fromSessionMessage(sm: SessionMessage): Message {
            val contents = buildList<MessageContent> {
                if (sm.thinking.isNotEmpty()) add(MessageContent.Thinking(sm.thinking))
                if (sm.content.isNotEmpty()) add(MessageContent.Text(sm.content))
                for (tc in sm.toolCalls) {
                    if (tc.output.isNotEmpty() || tc.status == SessionToolCall(tc.id, tc.name, ToolCallStatus.COMPLETE, tc.input, tc.output).status) {
                        add(MessageContent.ToolResult(tc.id, tc.name, tc.output, tc.status == ToolCallStatus.ERROR))
                    } else {
                        add(MessageContent.ToolRequest(tc.id, tc.name, tc.input))
                    }
                }
                if (sm.role == MessageRole.SYSTEM && isEmpty()) {
                    add(MessageContent.SystemNotification(sm.content))
                }
            }
            return Message(
                id = sm.id,
                role = when (sm.role) {
                    MessageRole.USER -> Role.USER
                    MessageRole.ASSISTANT -> Role.ASSISTANT
                    MessageRole.SYSTEM -> Role.SYSTEM
                    MessageRole.TOOL -> Role.TOOL
                },
                contents = contents,
                timestamp = sm.timestamp,
                toolCallId = sm.toolCallId,
            )
        }
    }

    // ── Conversion to SessionMessage ──────────────────────────────────────

    fun toSessionMessage(): SessionMessage {
        val text = contents.filterIsInstance<MessageContent.Text>().joinToString("") { it.text }
        val thinkingText = contents.filterIsInstance<MessageContent.Thinking>().joinToString("") { it.text }
        val toolCalls = contents.mapNotNull { content ->
            when (content) {
                is MessageContent.ToolRequest -> SessionToolCall(
                    id = content.id, name = content.name,
                    status = ToolCallStatus.PENDING, input = content.inputJson,
                )
                is MessageContent.ToolResult -> SessionToolCall(
                    id = content.id, name = content.name,
                    status = if (content.isError) ToolCallStatus.ERROR else ToolCallStatus.COMPLETE,
                    output = content.output,
                )
                else -> null
            }
        }
        return SessionMessage(
            id = id,
            role = when (role) {
                Role.USER -> MessageRole.USER
                Role.ASSISTANT -> MessageRole.ASSISTANT
                Role.SYSTEM -> MessageRole.SYSTEM
                Role.TOOL -> MessageRole.TOOL
            },
            content = text,
            thinking = thinkingText,
            toolCalls = toolCalls,
            timestamp = timestamp,
            toolCallId = toolCallId,
        )
    }

    // ── Convenience accessors ─────────────────────────────────────────────

    /** Returns the plain-text portion of this message, or empty string if none. */
    val text: String get() = contents.filterIsInstance<MessageContent.Text>().joinToString("") { it.text }

    /** Returns the thinking text of this message, or empty string if none. */
    val thinking: String get() = contents.filterIsInstance<MessageContent.Thinking>().joinToString("") { it.text }

    /** Returns all tool requests in this message. */
    val toolRequests: List<MessageContent.ToolRequest>
        get() = contents.filterIsInstance<MessageContent.ToolRequest>()

    /** True if this message contains any tool requests. */
    val hasToolCalls: Boolean get() = toolRequests.isNotEmpty()
}
