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

import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "ShellManager"

/** Timeout in seconds before a shell process is forcibly killed. */
private const val TIMEOUT_SECONDS = 10L

/**
 * Executes a shell command via `sh -c` and returns the combined stdout + stderr output.
 *
 * A strict [TIMEOUT_SECONDS]-second timeout is enforced. If the process does not finish
 * in time it is destroyed and `"TIMEOUT ERROR"` is returned, preventing the app from hanging
 * on runaway commands (e.g. accidental infinite loops).
 *
 * @param command The shell command string to execute.
 * @return The raw terminal output (stdout followed by stderr), or `"TIMEOUT ERROR"`.
 */
fun executeCommand(command: String): String {
  return try {
    Log.d(TAG, "executeCommand: $command")

    val process = ProcessBuilder("sh", "-c", command)
      .redirectErrorStream(false)
      .start()

    // Read stdout and stderr on background threads so the pipe buffers don't fill up
    // and deadlock the process before waitFor returns.
    val stdoutRef = AtomicReference("")
    val stderrRef = AtomicReference("")

    val stdoutThread = Thread {
      stdoutRef.set(process.inputStream.bufferedReader(Charsets.UTF_8).readText())
    }.also { it.start() }

    val stderrThread = Thread {
      stderrRef.set(process.errorStream.bufferedReader(Charsets.UTF_8).readText())
    }.also { it.start() }

    val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    if (!finished) {
      process.destroyForcibly()
      stdoutThread.interrupt()
      stderrThread.interrupt()
      Log.w(TAG, "executeCommand: process timed out after ${TIMEOUT_SECONDS}s — killed")
      return "TIMEOUT ERROR"
    }

    // Block until reader threads complete (no timeout — process already exited so
    // streams will close and readText() will return).
    // Bounded join prevents indefinite hangs if a reader thread gets stuck.
    stdoutThread.join(5_000)
    stderrThread.join(5_000)

    val stdout = stdoutRef.get()
    val stderr = stderrRef.get()

    val combined = buildString {
      if (stdout.isNotEmpty()) append(stdout)
      if (stderr.isNotEmpty()) {
        if (isNotEmpty()) append("\n")
        append(stderr)
      }
    }

    Log.d(TAG, "executeCommand: exit=${process.exitValue()}, output=${combined.length} chars")
    combined.ifEmpty { "(no output)" }
  } catch (e: Exception) {
    Log.e(TAG, "executeCommand: exception", e)
    "ERROR: ${e.message}"
  }
}
