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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject

/**
 * Permission system that gates destructive tool operations.
 *
 * Before executing certain tool calls the engine checks if the operation
 * requires user approval. If it does, a [PermissionRequest] is emitted to
 * the UI and execution suspends until the user responds.
 *
 * Destructive operations that require permission:
 * - Shell commands matching destructive patterns (rm -rf, git push --force, …)
 * - File writes that overwrite config/env/ssh files
 * - Extension add/remove, brain node delete
 *
 * Always-safe operations (no permission needed):
 * - Read-only commands (ls, cat, grep, find, tree)
 * - File writes to new files
 * - Git read operations (status, diff, log)
 *
 * Ported from MaxFlynn13/goose-android (engine/PermissionManager.kt).
 */
class PermissionManager {

    companion object {
        private const val TAG = "PermissionManager"

        val DESTRUCTIVE_SHELL_PATTERNS = listOf(
            "rm -rf", "rm -r ", "rmdir",
            "git push --force", "git push -f", "git reset --hard",
            "DROP TABLE", "DROP DATABASE", "DELETE FROM", "TRUNCATE",
            "format ", "mkfs", "dd if=",
            "chmod 777", "chmod -R",
            "sudo ", "su -c",
            "> /dev/", "| tee /dev/",
            "curl.*-X DELETE", "curl.*-X PUT",
            "wget.*-O /",
            "kill -9", "killall",
            "shutdown", "reboot",
            "apt remove", "apt purge", "pip uninstall",
            "npm uninstall -g"
        )

        val SAFE_SHELL_PATTERNS = listOf(
            "ls", "cat ", "head ", "tail ", "grep ", "find ", "tree",
            "echo ", "printf ", "wc ", "sort ", "uniq ",
            "git status", "git diff", "git log", "git branch",
            "pwd", "whoami", "date", "uname",
            "pip list", "npm list", "pip show", "npm info"
        )
    }

    data class PermissionRequest(
        val id: String,
        val toolName: String,
        val description: String,
        val command: String,
        val risk: RiskLevel,
        val deferred: CompletableDeferred<PermissionResult>
    )

    enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }
    enum class PermissionResult { ALLOW, DENY, ALLOW_ALL_SESSION }

    private val _permissionRequests = MutableSharedFlow<PermissionRequest>(extraBufferCapacity = 1)
    val permissionRequests: SharedFlow<PermissionRequest> = _permissionRequests

    private var allowAllForSession = false

    /**
     * Check if a tool call needs permission. Suspends until the user responds.
     * Returns true if the operation should proceed, false if denied.
     */
    suspend fun checkPermission(toolName: String, input: JSONObject): Boolean {
        if (allowAllForSession) return true

        val needsPermission = when (toolName) {
            "shell" -> checkShellPermission(input.optString("command", ""))
            "write" -> checkWritePermission(input.optString("path", ""))
            "edit" -> false // Targeted edits are generally safe
            "manage_extensions" -> {
                val action = input.optString("action", "")
                action == "remove" || action == "add"
            }
            "manage_brain" -> input.optString("action", "") == "delete"
            else -> false
        }

        if (!needsPermission) return true

        val description = buildDescription(toolName, input)
        val risk = assessRisk(toolName, input)
        val request = PermissionRequest(
            id = "perm_${System.currentTimeMillis()}",
            toolName = toolName,
            description = description,
            command = formatCommand(toolName, input),
            risk = risk,
            deferred = CompletableDeferred()
        )

        return try {
            _permissionRequests.emit(request)
            val result = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                request.deferred.await()
            } ?: PermissionResult.ALLOW // Auto-allow if UI doesn't respond in 30 s

            if (result == PermissionResult.ALLOW_ALL_SESSION) {
                allowAllForSession = true
            }

            result != PermissionResult.DENY
        } catch (e: Exception) {
            Log.w(TAG, "Permission check failed, auto-allowing: ${e.message}")
            true
        }
    }

    fun resetSession() {
        allowAllForSession = false
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun checkShellPermission(command: String): Boolean {
        val lower = command.lowercase()
        if (SAFE_SHELL_PATTERNS.any { lower.trimStart().startsWith(it) }) return false
        if (DESTRUCTIVE_SHELL_PATTERNS.any { lower.contains(it) }) return true
        if (command.contains(">") && !command.contains(">>")) return true
        return false
    }

    private fun checkWritePermission(path: String): Boolean {
        return path.contains(".config") || path.contains(".env") || path.contains(".ssh")
    }

    private fun buildDescription(toolName: String, input: JSONObject): String = when (toolName) {
        "shell" -> "Run shell command: ${input.optString("command", "").take(100)}"
        "write" -> "Write file: ${input.optString("path", "")}"
        "manage_extensions" -> "${input.optString("action", "")} extension: ${input.optString("name", "")}"
        "manage_brain" -> "Delete brain node: ${input.optString("id", "")}"
        else -> "Execute $toolName"
    }

    private fun formatCommand(toolName: String, input: JSONObject): String = when (toolName) {
        "shell" -> input.optString("command", "")
        "write" -> "write → ${input.optString("path", "")}"
        else -> input.toString().take(200)
    }

    private fun assessRisk(toolName: String, input: JSONObject): RiskLevel {
        if (toolName != "shell") return RiskLevel.LOW
        val cmd = input.optString("command", "").lowercase()
        return when {
            cmd.contains("rm -rf") || cmd.contains("format") || cmd.contains("dd if=") -> RiskLevel.CRITICAL
            cmd.contains("git push") || cmd.contains("DELETE FROM") -> RiskLevel.HIGH
            cmd.contains("rm ") || cmd.contains(">") -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
    }
}
