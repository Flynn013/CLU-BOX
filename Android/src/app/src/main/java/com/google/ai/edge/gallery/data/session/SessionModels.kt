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

package com.google.ai.edge.gallery.data.session

// ── Session models ─────────────────────────────────────────────────────────────

/**
 * Lightweight summary of a chat session shown in the session list (history screen).
 *
 * Stored in a flat JSON array via [SessionRepository] for fast enumeration without
 * loading all message bodies.
 */
data class SessionInfo(
    /** UUID that also serves as the filename of the message body (`<id>.json`). */
    val id: String,
    val title: String = "Untitled",
    val createdAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
    val lastMessage: String = "",
    val providerId: String = "",
    val modelId: String = "",
)

/** Role of a participant in a chat session. */
enum class MessageRole { SYSTEM, USER, ASSISTANT, TOOL }

/** Status of a tool call within a session message. */
enum class ToolCallStatus { PENDING, RUNNING, COMPLETE, ERROR }

/**
 * A single tool call record embedded in a [SessionMessage].
 */
data class SessionToolCall(
    val id: String,
    val name: String,
    val status: ToolCallStatus = ToolCallStatus.COMPLETE,
    /** Raw JSON string of the tool input arguments. */
    val input: String = "",
    /** Tool output text (or error message). */
    val output: String = "",
)

/**
 * A single message in a chat session, persisted as part of `<session-id>.json`.
 *
 * Mirrors the data shape used by SPL-NTR's `ChatMessage` model
 * (Flynn013/SPL-NTR, Apache-2.0) but adapted to CLU/BOX conventions.
 */
data class SessionMessage(
    val id: String,
    val role: MessageRole,
    val content: String = "",
    val toolCalls: List<SessionToolCall> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    /** Non-null for role=TOOL: correlates this result back to its request. */
    val toolCallId: String? = null,
    /** Non-null for role=TOOL: name of the tool that produced this result. */
    val toolName: String? = null,
    /** Chain-of-thought / extended thinking text (Anthropic / Gemini thinking). */
    val thinking: String = "",
)
