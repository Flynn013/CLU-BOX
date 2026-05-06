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

// Permission semantics adapted from the Goose permission/permission_confirmation.rs model.
// CLU/BOX maps Goose's Extension principal to Skill (SkillRegistry), and uses
// SharedPreferences for persistence (no Room dependency added).

package com.google.ai.edge.gallery.data.permission

// ── Core enums ─────────────────────────────────────────────────────────────────

/**
 * The decision an operator (user) can make for a tool-call permission request.
 *
 * Maps directly to the Goose `Permission` enum:
 * ```
 * AlwaysAllow | AllowOnce | Cancel | DenyOnce | AlwaysDeny
 * ```
 */
enum class PermissionDecision {
    /** Permanently allow — future calls to this tool are auto-approved. */
    ALWAYS_ALLOW,
    /** Allow this single invocation only; ask again next time. */
    ALLOW_ONCE,
    /** Deny this single invocation but do not persist the decision. */
    DENY_ONCE,
    /** Permanently deny — future calls to this tool are auto-rejected. */
    ALWAYS_DENY,
}

/**
 * The type of entity whose permission is being managed.
 *
 * Mirrors Goose's `PrincipalType`:
 * - [SKILL]  — a registered CLU/BOX skill / tool (maps to Goose Extension)
 * - [TOOL]   — a specific named invocation within a skill
 */
enum class PrincipalType { SKILL, TOOL }

/**
 * A fully qualified principal identifier used as the key in [PermissionStore].
 *
 * @param type  Whether this principal is a [PrincipalType.SKILL] or [PrincipalType.TOOL]
 * @param name  The skill / tool name (matches [SkillRegistry] keys)
 */
data class Principal(val type: PrincipalType, val name: String) {
    /** Serialisation key used in SharedPreferences. */
    fun toKey(): String = "${type.name}:$name"
}

/**
 * Outcome of a permission check — tells the caller what to do next.
 */
sealed class PermissionOutcome {
    /** The call is approved — proceed with execution. */
    object Approved : PermissionOutcome()

    /**
     * The call requires interactive approval — show an approval sheet and
     * await the user's decision via [ActionRequiredQueue].
     */
    data class RequiresApproval(val principal: Principal, val inputJson: String) : PermissionOutcome()

    /** The call is denied — surface an error to the agent loop. */
    data class Denied(val reason: String) : PermissionOutcome()
}
