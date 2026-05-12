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

// Suspend-and-resume pattern for tool approval adapted from the Goose
// action_required_manager.rs design — implemented as a Kotlin Channel-based queue.

package com.google.ai.edge.gallery.data.permission

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A pending approval request that the UI should display.
 *
 * @param id         UUID matching the tool-call id so the agent loop can correlate the response
 * @param toolName   The skill / tool being requested
 * @param inputJson  The arguments the model wants to pass to the tool
 * @param riskSummary A human-readable description of what the tool will do (used in the approval sheet)
 */
data class ApprovalRequest(
    val id: String,
    val toolName: String,
    val inputJson: String,
    val riskSummary: String = "",
)

/**
 * Queues tool-call approval requests and suspends the agent loop until the UI responds.
 *
 * ## Protocol
 *
 * **Agent-loop side** (producer):
 * ```kotlin
 * val decision = actionRequiredQueue.awaitDecision(
 *     id = toolCallId, toolName = "shellExecute", inputJson = "{...}"
 * )
 * ```
 * The call suspends until the UI resolves the request.
 *
 * **UI side** (consumer):
 * ```kotlin
 * // Observe pending requests:
 * actionRequiredQueue.pending.collect { request -> ... }
 *
 * // Resolve after user taps Approve/Deny:
 * actionRequiredQueue.resolve(request.id, PermissionDecision.ALLOW_ONCE)
 * ```
 *
 * Only one pending request is surfaced at a time (sequential modal UI).
 * Additional requests queue behind and surface as the first resolves.
 *
 * Thread safety: [awaitDecision] must be called from a coroutine (it suspends).
 * [resolve] is safe to call from the Main thread.
 */
class ActionRequiredQueue {

    companion object {
        private const val TAG = "ActionRequiredQueue"
    }

    // Channel of (requestId → responseDecision) pairs
    private val responseChannels = mutableMapOf<String, Channel<PermissionDecision>>()

    private val _pending = MutableStateFlow<ApprovalRequest?>(null)
    /**
     * The currently pending approval request, or `null` when there is nothing to show.
     *
     * The approval UI should collect this and render a modal sheet when non-null.
     */
    val pending: StateFlow<ApprovalRequest?> = _pending.asStateFlow()

    // FIFO queue of requests waiting their turn
    private val requestQueue = ArrayDeque<ApprovalRequest>()

    // ── Agent loop API ──────────────────────────────────────────────────────

    /**
     * Enqueues an approval request and **suspends** until the UI calls [resolve].
     *
     * @return The [PermissionDecision] chosen by the user.
     */
    suspend fun awaitDecision(
        id: String,
        toolName: String,
        inputJson: String,
        riskSummary: String = "",
    ): PermissionDecision {
        val request = ApprovalRequest(id, toolName, inputJson, riskSummary)
        val responseChannel = Channel<PermissionDecision>(capacity = 1)
        responseChannels[id] = responseChannel

        synchronized(requestQueue) {
            requestQueue.addLast(request)
            if (requestQueue.size == 1) {
                // This is the only request — surface it immediately
                _pending.value = request
            }
        }

        Log.d(TAG, "Awaiting decision for tool='$toolName' id='$id'")
        val decision = responseChannel.receive()
        Log.d(TAG, "Decision received for id='$id': $decision")
        return decision
    }

    // ── UI API ─────────────────────────────────────────────────────────────

    /**
     * Resolves the pending approval request with the user's [decision].
     *
     * Call this from the Compose approval sheet's confirm/deny buttons.
     *
     * @param requestId The [ApprovalRequest.id] of the request being resolved
     * @param decision  The user's chosen [PermissionDecision]
     */
    fun resolve(requestId: String, decision: PermissionDecision) {
        val channel = responseChannels.remove(requestId)
        if (channel == null) {
            Log.w(TAG, "resolve() called for unknown requestId='$requestId'")
            return
        }

        channel.trySend(decision)

        // Advance the queue
        synchronized(requestQueue) {
            requestQueue.removeFirstOrNull { it.id == requestId }
            _pending.value = requestQueue.firstOrNull()
        }
        Log.d(TAG, "Resolved requestId='$requestId' with $decision; queue size=${requestQueue.size}")
    }

    /**
     * Cancels all pending requests with [PermissionDecision.DENY_ONCE].
     *
     * Call this when the session is reset or the user exits the chat.
     */
    fun cancelAll() {
        val ids = responseChannels.keys.toList()
        for (id in ids) {
            val ch = responseChannels.remove(id) ?: continue
            ch.trySend(PermissionDecision.DENY_ONCE)
        }
        requestQueue.clear()
        _pending.value = null
        Log.d(TAG, "Cancelled ${ids.size} pending approval(s)")
    }

    /** Returns `true` if there are any pending approval requests. */
    val hasPending: Boolean get() = requestQueue.isNotEmpty()
}

/** Extension to remove a matching element from an [ArrayDeque] (for clarity). */
private fun <T> ArrayDeque<T>.removeFirstOrNull(predicate: (T) -> Boolean): T? {
    val idx = indexOfFirst(predicate)
    return if (idx >= 0) removeAt(idx) else null
}
