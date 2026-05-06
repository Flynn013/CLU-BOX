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

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Persists per-principal [PermissionDecision]s using [SharedPreferences].
 *
 * Only **persistent** decisions ([PermissionDecision.ALWAYS_ALLOW] and
 * [PermissionDecision.ALWAYS_DENY]) are stored.  Session-only decisions
 * ([ALLOW_ONCE] / [DENY_ONCE]) are ephemeral and handled by [PermissionInspector]'s
 * in-memory cache.
 *
 * Thread safety: SharedPreferences commit is synchronous (apply() is fire-and-forget);
 * reads are safe from any thread.
 */
class PermissionStore(context: Context) {

    companion object {
        private const val TAG = "PermissionStore"
        private const val PREFS_NAME = "clu_permissions"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Persistent storage ─────────────────────────────────────────────────

    /**
     * Saves a permanent decision for [principal].
     *
     * Only [PermissionDecision.ALWAYS_ALLOW] and [PermissionDecision.ALWAYS_DENY]
     * are meaningful here; session decisions are ignored.
     */
    fun save(principal: Principal, decision: PermissionDecision) {
        when (decision) {
            PermissionDecision.ALWAYS_ALLOW,
            PermissionDecision.ALWAYS_DENY -> {
                prefs.edit().putString(principal.toKey(), decision.name).apply()
                Log.d(TAG, "Saved $decision for ${principal.toKey()}")
            }
            else -> { /* session decisions are not persisted */ }
        }
    }

    /**
     * Returns the persisted decision for [principal], or `null` if no permanent
     * decision has been stored (i.e. the tool needs interactive approval each time).
     */
    fun load(principal: Principal): PermissionDecision? {
        val name = prefs.getString(principal.toKey(), null) ?: return null
        return try {
            PermissionDecision.valueOf(name)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unknown decision value '$name' for ${principal.toKey()} — clearing")
            prefs.edit().remove(principal.toKey()).apply()
            null
        }
    }

    /**
     * Removes the stored decision for [principal].
     *
     * Call this when the user revokes a previously set "always allow / deny" rule.
     */
    fun clear(principal: Principal) {
        prefs.edit().remove(principal.toKey()).apply()
        Log.d(TAG, "Cleared permission for ${principal.toKey()}")
    }

    /** Removes all stored permission decisions. */
    fun clearAll() {
        prefs.edit().clear().apply()
        Log.d(TAG, "All permissions cleared")
    }

    /**
     * Returns a snapshot of all stored persistent decisions.
     *
     * Useful for the Permissions audit screen.
     */
    fun snapshot(): Map<String, PermissionDecision> {
        return prefs.all.mapNotNull { (key, value) ->
            val decision = try {
                PermissionDecision.valueOf(value as? String ?: return@mapNotNull null)
            } catch (e: IllegalArgumentException) { return@mapNotNull null }
            key to decision
        }.toMap()
    }
}
