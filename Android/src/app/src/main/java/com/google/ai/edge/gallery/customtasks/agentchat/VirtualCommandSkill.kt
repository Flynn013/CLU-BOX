/*
 * Copyright 2026 Google LLC
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

package com.google.ai.edge.gallery.customtasks.agentchat

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID

private const val TAG = "VirtualCommandSkill"

/** Termux RUN_COMMAND action — the external Termux app handles this intent. */
private const val TERMUX_ACTION = "com.termux.RUN_COMMAND"

/** Fully-qualified path to the Termux bash binary. */
private const val TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"

/** Extra key for the command arguments in the Termux RUN_COMMAND intent. */
private const val EXTRA_COMMAND_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"

/** Extra key for the working directory in the Termux RUN_COMMAND intent. */
private const val EXTRA_COMMAND_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"

/** Extra key for the path to the executable in the Termux RUN_COMMAND intent. */
private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"

/** Extra key for the PendingIntent that Termux fires back on completion. */
private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

/** How long to wait for Termux to complete the command before timing out. */
private const val VIRTUAL_COMMAND_TIMEOUT_MS = 30_000L

/**
 * [CluSkill] implementation that routes shell commands through the external
 * Termux app via `com.termux.RUN_COMMAND`, delivering the result via a
 * [PendingIntent] callback to [TermuxOutputReceiver].
 *
 * ## Execution flow
 *
 * 1. Generate a `correlationId` UUID so we can match the callback to this call.
 * 2. Build a `PendingIntent` that launches [TermuxOutputReceiver] and carries
 *    the `correlationId` as an extra.
 * 3. Fire a broadcast intent with action `com.termux.RUN_COMMAND`, embedding
 *    the bash path, the command array, and the `PendingIntent`.
 * 4. Collect [VirtualCommandResultBus.resultFlow] until we see a result whose
 *    `correlationId` matches ours, or until [VIRTUAL_COMMAND_TIMEOUT_MS] elapses.
 *
 * @param agentTools The parent [AgentTools] instance supplying the Android [Context].
 */
class VirtualCommandSkill(private val agentTools: AgentTools) : CluSkill {

  override val name: String = "virtualCommand"

  override val description: String =
    "Executes a bash command in the external Termux environment via RUN_COMMAND IPC. " +
    "Use this skill when the LOCAL COMMAND engine is unavailable or when full Termux " +
    "environment access is required."

  override val jsonSchema: String =
    """{"name":"virtualCommand","parameters":{"command":{"type":"string"}},"required":["command"]}"""

  override val fewShotExample: String =
    """virtualCommand(command="pip install flask")"""

  override suspend fun execute(args: JSONObject): String {
    val command = args.optString("command", "").trim()
    if (command.isBlank()) return "[Error: 'command' argument is required]"

    val ctx = agentTools.context
      ?: return "[Error: Android Context not available — cannot launch Termux IPC]"

    val correlationId = UUID.randomUUID().toString()
    Log.d(TAG, "execute: correlationId=$correlationId command=${command.take(120)}")

    // ── Build the PendingIntent callback ──────────────────────────────────
    // Termux fires this PendingIntent when the command completes, delivering
    // the result via the intent extras that TermuxOutputReceiver extracts.
    val callbackIntent = Intent(ctx, TermuxOutputReceiver::class.java).apply {
      action = "com.google.ai.edge.gallery.TERMUX_COMMAND_RESULT"
      putExtra(TermuxOutputReceiver.EXTRA_CORRELATION_ID, correlationId)
    }

    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    } else {
      PendingIntent.FLAG_UPDATE_CURRENT
    }

    val pendingIntent = PendingIntent.getBroadcast(
      ctx,
      correlationId.hashCode(),
      callbackIntent,
      flags,
    )

    // ── Build the Termux RUN_COMMAND intent ───────────────────────────────
    // Pass the command as a String array (bash -c "<command>") so the full
    // shell pipeline is evaluated by bash rather than the Android env.
    val termuxIntent = Intent().apply {
      action = TERMUX_ACTION
      setClassName("com.termux", "com.termux.app.RunCommandService")
      putExtra(EXTRA_COMMAND_PATH, TERMUX_BASH_PATH)
      putExtra(EXTRA_COMMAND_ARGUMENTS, arrayOf("-c", command))
      putExtra(EXTRA_COMMAND_WORKDIR, "/data/data/com.termux/files/home")
      putExtra(EXTRA_PENDING_INTENT, pendingIntent)
    }

    return try {
      ctx.startService(termuxIntent)
      Log.d(TAG, "Termux RUN_COMMAND service started for correlationId=$correlationId")

      // ── Await the callback ────────────────────────────────────────────────
      // Collect the shared flow until we see *our* result (matched by correlationId),
      // with a hard timeout to prevent the agent loop from hanging indefinitely.
      val result: VirtualCommandResult = withTimeout(VIRTUAL_COMMAND_TIMEOUT_MS) {
        VirtualCommandResultBus.resultFlow.first { it.correlationId == correlationId }
      }

      buildResultString(result)
    } catch (e: TimeoutCancellationException) {
      Log.w(TAG, "Termux command timed out after ${VIRTUAL_COMMAND_TIMEOUT_MS}ms — correlationId=$correlationId")
      "[Error: VIRTUAL COMMAND timed out after ${VIRTUAL_COMMAND_TIMEOUT_MS / 1000}s. " +
        "Check that Termux is installed and the RUN_COMMAND permission is granted.]"
    } catch (e: Exception) {
      Log.e(TAG, "Termux RUN_COMMAND failed", e)
      "[Error: Failed to launch Termux RUN_COMMAND — ${e.message?.take(200)}. " +
        "Ensure Termux is installed and com.termux.permission.RUN_COMMAND is granted.]"
    }
  }

  private fun buildResultString(result: VirtualCommandResult): String = buildString {
    if (result.stdout.isNotEmpty()) append(result.stdout)
    if (result.stderr.isNotEmpty()) {
      if (isNotEmpty()) append("\n")
      append("[stderr] ${result.stderr}")
    }
    if (isEmpty()) append("(no output)")
    append("\n[exit_code: ${result.exitCode}]")
  }
}
