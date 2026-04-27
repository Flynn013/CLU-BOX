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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking

/**
 * Result payload returned by the external Termux `com.termux.RUN_COMMAND` execution.
 *
 * @param stdout     Combined standard output of the command, trimmed.
 * @param stderr     Combined standard error output, trimmed.
 * @param exitCode   Unix exit code (0 = success).
 * @param correlationId Optional ID set by the caller to match requests with responses.
 */
data class VirtualCommandResult(
  val stdout: String,
  val stderr: String,
  val exitCode: Int,
  val correlationId: String = "",
)

/**
 * Process-global bus that bridges the BroadcastReceiver (which fires on the
 * main thread in the manifest-declared process) to the coroutine-based agent loop.
 *
 * Usage:
 * - **Producer:** `TermuxOutputReceiver.onReceive` calls [emit].
 * - **Consumer:** `VirtualCommandSkill.execute` suspends on [resultFlow] until
 *   the matching result arrives, then returns it to the agent loop.
 */
object VirtualCommandResultBus {
  private const val TAG = "VirtualCommandResultBus"

  /** Replay=0: only active collectors receive results, preventing stale data accumulation. */
  private val _resultFlow = MutableSharedFlow<VirtualCommandResult>(
    replay = 0,
    extraBufferCapacity = 16,
  )

  /** Public read-only view consumed by [VirtualCommandSkill]. */
  val resultFlow: SharedFlow<VirtualCommandResult> = _resultFlow.asSharedFlow()

  /**
   * Emit a result onto the bus.  Must be called from a context that can block
   * briefly (the BroadcastReceiver's `onReceive` window is acceptable for
   * `tryEmit` since `extraBufferCapacity > 0`).
   */
  fun emit(result: VirtualCommandResult) {
    val emitted = _resultFlow.tryEmit(result)
    if (!emitted) {
      Log.w(TAG, "emit: buffer full — result for '${result.correlationId}' dropped")
    }
  }
}

/**
 * Exported `BroadcastReceiver` that captures the async result of a Termux
 * `com.termux.RUN_COMMAND` invocation.
 *
 * Termux delivers the result via the [PendingIntent] we attach to the
 * launch Intent.  The receiver extracts stdout, stderr, and exit code from
 * the incoming [Intent] extras and emits them onto [VirtualCommandResultBus]
 * so the waiting [VirtualCommandSkill.execute] coroutine can consume them.
 *
 * Termux extras used:
 * - `TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE` (Bundle)
 *   - `stdout`: String
 *   - `stderr`: String
 *   - `exitCode`: Int
 */
class TermuxOutputReceiver : BroadcastReceiver() {

  companion object {
    private const val TAG = "TermuxOutputReceiver"

    // Extras emitted by Termux into the result PendingIntent.
    private const val EXTRA_RESULT_BUNDLE = "result"
    private const val EXTRA_STDOUT = "stdout"
    private const val EXTRA_STDERR = "stderr"
    private const val EXTRA_EXIT_CODE = "exitCode"

    // Correlation ID we stash in the launch intent so we can match the result.
    const val EXTRA_CORRELATION_ID = "com.google.ai.edge.gallery.CORRELATION_ID"
  }

  override fun onReceive(context: Context, intent: Intent) {
    Log.d(TAG, "onReceive: action=${intent.action}")

    val correlationId = intent.getStringExtra(EXTRA_CORRELATION_ID) ?: ""

    // Termux wraps stdout/stderr/exitCode in a nested Bundle keyed by "result".
    val resultBundle = intent.getBundleExtra(EXTRA_RESULT_BUNDLE)
    val stdout = resultBundle?.getString(EXTRA_STDOUT)?.trim() ?: ""
    val stderr = resultBundle?.getString(EXTRA_STDERR)?.trim() ?: ""
    val exitCode = resultBundle?.getInt(EXTRA_EXIT_CODE, -1) ?: -1

    Log.d(TAG, "Termux result: exit=$exitCode correlationId='$correlationId' stdout=${stdout.take(120)}")

    VirtualCommandResultBus.emit(
      VirtualCommandResult(
        stdout = stdout,
        stderr = stderr,
        exitCode = exitCode,
        correlationId = correlationId,
      )
    )
  }
}
