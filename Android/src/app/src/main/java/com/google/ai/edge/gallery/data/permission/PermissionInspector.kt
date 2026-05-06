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

package com.google.ai.edge.gallery.data.permission

import android.util.Log

/**
 * Evaluates whether a given tool call is permitted, returning a [PermissionOutcome]
 * that tells the agent loop what action to take.
 *
 * Decision priority (highest to lowest):
 * 1. **Always-deny** (persistent) — immediately denied.
 * 2. **Always-allow** (persistent) — immediately approved.
 * 3. **Session allow-once** (in-memory cache) — approved for this session.
 * 4. **Session deny-once** (in-memory cache) — denied for this session.
 * 5. **Requires interactive approval** — emits [PermissionOutcome.RequiresApproval].
 *    The caller should enqueue to [ActionRequiredQueue] and suspend until resolved.
 *
 * ## Auto-approved tools
 * Tools listed in [autoApprovedTools] skip the permission check entirely.
 * By default this includes the CLU/BOX built-in read-only tools.
 *
 * @param store The persistent [PermissionStore] for always-allow / always-deny decisions
 */
class PermissionInspector(private val store: PermissionStore) {

    companion object {
        private const val TAG = "PermissionInspector"

        /**
         * Tools that are always approved without user interaction.
         *
         * These are read-only or otherwise safe tools that do not need gating.
         * Destructive operations (shellExecute, fileBoxWrite, etc.) are NOT listed here.
         */
        val AUTO_APPROVED_TOOLS: Set<String> = setOf(
            "brainBoxGrep",
            "fileBoxReadLines",
            "todo_read",
            "treeView",
            "listFiles",
        )
    }

    /** In-memory session-level decisions that do NOT survive process death. */
    private val sessionDecisions = mutableMapOf<String, PermissionDecision>()

    // ── Core check ─────────────────────────────────────────────────────────

    /**
     * Evaluates the permission for calling [toolName] with [inputJson].
     *
     * Call this inside the agent loop **before** dispatching the tool.
     *
     * @return [PermissionOutcome.Approved] → proceed;
     *         [PermissionOutcome.RequiresApproval] → pause and show approval UI;
     *         [PermissionOutcome.Denied] → fail the tool call and inject error into context.
     */
    fun check(toolName: String, inputJson: String = ""): PermissionOutcome {
        // Auto-approved tools skip the gate entirely
        if (toolName in AUTO_APPROVED_TOOLS) return PermissionOutcome.Approved

        val skillPrincipal = Principal(PrincipalType.SKILL, toolName)
        val toolPrincipal = Principal(PrincipalType.TOOL, toolName)

        // 1. Check persistent store (always-deny overrides always-allow)
        val persistentDecision = store.load(skillPrincipal) ?: store.load(toolPrincipal)
        when (persistentDecision) {
            PermissionDecision.ALWAYS_DENY -> {
                Log.w(TAG, "Tool '$toolName' permanently denied")
                return PermissionOutcome.Denied("Tool '$toolName' is permanently denied. Change in Settings → Permissions.")
            }
            PermissionDecision.ALWAYS_ALLOW -> {
                Log.d(TAG, "Tool '$toolName' permanently allowed")
                return PermissionOutcome.Approved
            }
            else -> { /* no persistent decision — check session cache */ }
        }

        // 2. Check session cache
        val sessionDecision = sessionDecisions[skillPrincipal.toKey()]
            ?: sessionDecisions[toolPrincipal.toKey()]
        when (sessionDecision) {
            PermissionDecision.ALWAYS_ALLOW, PermissionDecision.ALLOW_ONCE -> {
                if (sessionDecision == PermissionDecision.ALLOW_ONCE) {
                    // Consume the one-time approval
                    sessionDecisions.remove(skillPrincipal.toKey())
                    sessionDecisions.remove(toolPrincipal.toKey())
                }
                return PermissionOutcome.Approved
            }
            PermissionDecision.DENY_ONCE -> {
                sessionDecisions.remove(skillPrincipal.toKey())
                sessionDecisions.remove(toolPrincipal.toKey())
                return PermissionOutcome.Denied("Tool '$toolName' was denied (once).")
            }
            PermissionDecision.ALWAYS_DENY -> {
                return PermissionOutcome.Denied("Tool '$toolName' is denied for this session.")
            }
            null -> { /* no session decision — need interactive approval */ }
        }

        Log.d(TAG, "Tool '$toolName' requires interactive approval")
        return PermissionOutcome.RequiresApproval(skillPrincipal, inputJson)
    }

    // ── Decision recording ─────────────────────────────────────────────────

    /**
     * Records the user's decision from the approval UI.
     *
     * Persistent decisions ([ALWAYS_ALLOW] / [ALWAYS_DENY]) are forwarded to [store].
     * Session decisions are cached in [sessionDecisions].
     */
    fun record(principal: Principal, decision: PermissionDecision) {
        when (decision) {
            PermissionDecision.ALWAYS_ALLOW, PermissionDecision.ALWAYS_DENY -> {
                store.save(principal, decision)
                // Also cache in session so subsequent checks don't hit disk
                sessionDecisions[principal.toKey()] = decision
            }
            PermissionDecision.ALLOW_ONCE, PermissionDecision.DENY_ONCE -> {
                sessionDecisions[principal.toKey()] = decision
            }
        }
        Log.d(TAG, "Recorded $decision for ${principal.toKey()}")
    }

    /**
     * Clears all in-memory session decisions (e.g. when starting a new session).
     * Does not affect persistent [store] entries.
     */
    fun clearSessionDecisions() {
        sessionDecisions.clear()
        Log.d(TAG, "Session decisions cleared")
    }

    /**
     * Returns a read-only view of the current session decisions.
     *
     * Keys are serialised [Principal.toKey()] strings.
     */
    fun sessionSnapshot(): Map<String, PermissionDecision> = sessionDecisions.toMap()
}
