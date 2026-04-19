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

package com.google.ai.edge.gallery.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

private const val TAG = "LogBoxManager"

/** Maximum lines retained in the buffer to prevent memory bloat. */
private const val MAX_LOG_LINES = 500

/**
 * Intercepts the Android Logcat stream in real-time and exposes it as a
 * [StateFlow] for the LOG_BOX UI. The stream is filtered to the
 * `CLU_ENGINE` tag by default but captures all CLU-related tags.
 *
 * The manager is lifecycle-aware: [startStream] spawns a `logcat` process
 * on [Dispatchers.IO] and [stopStream] destroys it to save CPU when the
 * LOG_BOX view is hidden.
 */
class LogBoxManager(private val context: Context) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  // ── Observable log buffer ───────────────────────────────────
  private val _logLines = MutableStateFlow<List<String>>(emptyList())
  val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

  // ── Stream lifecycle ────────────────────────────────────────
  private var logcatProcess: Process? = null
  private var readerJob: Job? = null

  @Volatile
  private var isStreaming = false

  /**
   * Starts (or restarts) the logcat stream.
   *
   * The process runs `logcat -v time` and captures all log output. Lines
   * are emitted to [logLines] in real-time. The buffer is capped at
   * [MAX_LOG_LINES]; oldest entries are dropped as new ones arrive.
   */
  fun startStream() {
    if (isStreaming) return
    Log.d(TAG, "Starting logcat stream")

    try {
      // Clear the logcat buffer first so we start fresh each time the
      // LOG_BOX is opened — prevents stale entries from previous sessions.
      Runtime.getRuntime().exec("logcat -c")

      val process = Runtime.getRuntime().exec(
        arrayOf("logcat", "-v", "time")
      )
      logcatProcess = process
      isStreaming = true

      readerJob = scope.launch {
        try {
          BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
            while (isActive && isStreaming) {
              val line = reader.readLine() ?: break
              appendLine(line)
            }
          }
        } catch (e: Exception) {
          if (isStreaming) {
            Log.e(TAG, "Logcat reader error", e)
          }
        } finally {
          isStreaming = false
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start logcat process", e)
      isStreaming = false
      appendLine("[LOG_BOX] ERROR: Failed to start logcat — ${e.message}")
    }
  }

  /**
   * Kills the logcat process and cancels the reader coroutine.
   * Must be called when the LOG_BOX view is hidden to save CPU cycles.
   */
  fun stopStream() {
    if (!isStreaming) return
    Log.d(TAG, "Stopping logcat stream")

    isStreaming = false
    readerJob?.cancel()
    readerJob = null

    try {
      logcatProcess?.destroyForcibly()
    } catch (e: Exception) {
      Log.w(TAG, "Error destroying logcat process", e)
    }
    logcatProcess = null
  }

  /**
   * Copies the current log buffer to the system clipboard and shows a
   * [Toast] confirming the action.
   */
  fun copyToClipboard() {
    val text = _logLines.value.joinToString("\n")
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("CLU_LOG_BOX", text))

    // Toast must run on the main thread.
    android.os.Handler(android.os.Looper.getMainLooper()).post {
      Toast.makeText(context, "> TELEMETRY COPIED.", Toast.LENGTH_SHORT).show()
    }
  }

  /** Clears the visible log buffer. */
  fun clearLogs() {
    _logLines.value = emptyList()
  }

  // ── Internal helpers ────────────────────────────────────────

  private fun appendLine(line: String) {
    val current = _logLines.value
    _logLines.value = if (current.size >= MAX_LOG_LINES) {
      current.drop(current.size - MAX_LOG_LINES + 1) + line
    } else {
      current + line
    }
  }
}
