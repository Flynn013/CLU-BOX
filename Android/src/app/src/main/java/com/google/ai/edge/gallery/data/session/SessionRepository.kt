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

// JSON persistence pattern adapted from Flynn013/SPL-NTR SessionRepository (Apache-2.0),
// backed by CLU/BOX's existing Room database for BrainBox memory integration.

package com.google.ai.edge.gallery.data.session

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists chat sessions and their message bodies to JSON files in the app's private
 * storage (`filesDir/sessions/`).
 *
 * This is the CLU/BOX equivalent of SPL-NTR's `SessionRepository`, adapted to work
 * alongside the existing Room/BrainBox memory layer:
 * - [SessionInfo] index lives in `sessions/index.json` (fast list queries)
 * - Individual message bodies live in `sessions/messages/<session-id>.json`
 *
 * BrainBox (episodic memory) integration: callers can optionally snapshot key session
 * data into BrainBox via the existing `BrainBoxIO.storeEpisodic()` mechanism —
 * that is handled at the call site, not here, to keep this class focused on raw I/O.
 *
 * Thread safety: all methods dispatch to [Dispatchers.IO] internally and are safe to
 * call from any coroutine context.
 */
class SessionRepository(private val context: Context) {

    companion object {
        private const val TAG = "SessionRepository"
        private const val INDEX_FILE = "index.json"
        private const val SESSIONS_DIR = "sessions"
        private const val MESSAGES_DIR = "messages"
    }

    // ── Directory helpers ──────────────────────────────────────────────────

    private fun sessionsDir(): File =
        File(context.filesDir, SESSIONS_DIR).also { it.mkdirs() }

    private fun messagesDir(): File =
        File(sessionsDir(), MESSAGES_DIR).also { it.mkdirs() }

    private fun indexFile(): File = File(sessionsDir(), INDEX_FILE)

    private fun messageFile(sessionId: String): File =
        File(messagesDir(), "$sessionId.json")

    // ── Session index ──────────────────────────────────────────────────────

    /**
     * Returns all sessions sorted by creation time descending (newest first).
     */
    suspend fun loadSessions(): List<SessionInfo> = withContext(Dispatchers.IO) {
        try {
            val file = indexFile()
            if (!file.exists()) return@withContext emptyList()
            val jsonArray = JSONArray(file.readText())
            val sessions = mutableListOf<SessionInfo>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                sessions.add(
                    SessionInfo(
                        id = obj.getString("id"),
                        title = obj.optString("title", "Untitled"),
                        createdAt = obj.optLong("createdAt", 0L),
                        messageCount = obj.optInt("messageCount", 0),
                        lastMessage = obj.optString("lastMessage", ""),
                        providerId = obj.optString("providerId", ""),
                        modelId = obj.optString("modelId", ""),
                    )
                )
            }
            sessions.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            Log.e(TAG, "loadSessions failed", e)
            emptyList()
        }
    }

    /**
     * Persists the given [sessions] index (full overwrite).
     *
     * The session list is written atomically via a temp file to avoid partial writes.
     */
    suspend fun saveSessions(sessions: List<SessionInfo>) = withContext(Dispatchers.IO) {
        try {
            val jsonArray = JSONArray()
            for (s in sessions) {
                jsonArray.put(JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("createdAt", s.createdAt)
                    put("messageCount", s.messageCount)
                    put("lastMessage", s.lastMessage)
                    put("providerId", s.providerId)
                    put("modelId", s.modelId)
                })
            }
            val target = indexFile()
            val tmp = File(target.parent, "${target.name}.tmp")
            tmp.writeText(jsonArray.toString(2))
            tmp.renameTo(target)
        } catch (e: Exception) {
            Log.e(TAG, "saveSessions failed", e)
        }
    }

    /**
     * Upserts a single [SessionInfo] in the index without loading the full list into memory.
     * Creates a new entry if [session.id] is not found; replaces the existing one otherwise.
     */
    suspend fun upsertSession(session: SessionInfo) = withContext(Dispatchers.IO) {
        val current = loadSessions().toMutableList()
        val idx = current.indexOfFirst { it.id == session.id }
        if (idx >= 0) current[idx] = session else current.add(0, session)
        saveSessions(current)
    }

    /**
     * Removes the session with [sessionId] from the index and deletes its message body.
     */
    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        val current = loadSessions().filter { it.id != sessionId }
        saveSessions(current)
        try { messageFile(sessionId).delete() } catch (_: Exception) {}
    }

    // ── Message bodies ─────────────────────────────────────────────────────

    /**
     * Loads all messages for [sessionId], or an empty list if none are stored yet.
     */
    suspend fun loadMessages(sessionId: String): List<SessionMessage> = withContext(Dispatchers.IO) {
        loadMessagesSync(sessionId)
    }

    /**
     * Synchronous variant used by fork / export operations already running on IO.
     */
    fun loadMessagesSync(sessionId: String): List<SessionMessage> {
        return try {
            val file = messageFile(sessionId)
            if (!file.exists()) return emptyList()
            val arr = JSONArray(file.readText())
            val result = mutableListOf<SessionMessage>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val role = try { MessageRole.valueOf(obj.getString("role")) }
                           catch (_: Exception) { MessageRole.SYSTEM }

                val toolCalls = mutableListOf<SessionToolCall>()
                obj.optJSONArray("toolCalls")?.let { tcArr ->
                    for (j in 0 until tcArr.length()) {
                        val tc = tcArr.getJSONObject(j)
                        toolCalls.add(
                            SessionToolCall(
                                id = tc.optString("id", ""),
                                name = tc.optString("name", "unknown"),
                                status = try { ToolCallStatus.valueOf(tc.getString("status")) }
                                         catch (_: Exception) { ToolCallStatus.COMPLETE },
                                input = tc.optString("input", ""),
                                output = tc.optString("output", ""),
                            )
                        )
                    }
                }

                result.add(
                    SessionMessage(
                        id = obj.getString("id"),
                        role = role,
                        content = obj.optString("content", ""),
                        toolCalls = toolCalls,
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        toolCallId = obj.optString("toolCallId", "").takeIf { it.isNotEmpty() },
                        toolName = obj.optString("toolName", "").takeIf { it.isNotEmpty() },
                        thinking = obj.optString("thinking", ""),
                    )
                )
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "loadMessagesSync($sessionId) failed", e)
            emptyList()
        }
    }

    /**
     * Persists [messages] for [sessionId] (full overwrite, atomic rename).
     *
     * Also updates the session index entry's [SessionInfo.messageCount] and
     * [SessionInfo.lastMessage] fields.
     */
    suspend fun saveMessages(sessionId: String, messages: List<SessionMessage>) =
        withContext(Dispatchers.IO) {
            try {
                val arr = JSONArray()
                for (msg in messages) {
                    val obj = JSONObject().apply {
                        put("id", msg.id)
                        put("role", msg.role.name)
                        put("content", msg.content)
                        put("timestamp", msg.timestamp)
                        if (msg.thinking.isNotEmpty()) put("thinking", msg.thinking)
                        msg.toolCallId?.let { put("toolCallId", it) }
                        msg.toolName?.let { put("toolName", it) }
                        if (msg.toolCalls.isNotEmpty()) {
                            val tcArr = JSONArray()
                            for (tc in msg.toolCalls) {
                                tcArr.put(JSONObject().apply {
                                    put("id", tc.id)
                                    put("name", tc.name)
                                    put("status", tc.status.name)
                                    put("input", tc.input)
                                    put("output", tc.output)
                                })
                            }
                            put("toolCalls", tcArr)
                        }
                    }
                    arr.put(obj)
                }
                val target = messageFile(sessionId)
                val tmp = File(target.parent, "${sessionId}.tmp")
                tmp.writeText(arr.toString(2))
                tmp.renameTo(target)
            } catch (e: Exception) {
                Log.e(TAG, "saveMessages($sessionId) failed", e)
            }
        }

    /**
     * Convenience: saves messages and upserts the index entry in one call.
     *
     * @param session  Updated [SessionInfo] (title, messageCount, lastMessage, etc.)
     * @param messages Messages to persist for [session.id]
     */
    suspend fun saveSessionWithMessages(session: SessionInfo, messages: List<SessionMessage>) {
        saveMessages(session.id, messages)
        upsertSession(session)
    }
}
