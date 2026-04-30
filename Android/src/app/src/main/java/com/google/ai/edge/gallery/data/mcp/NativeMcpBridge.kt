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

package com.google.ai.edge.gallery.data.mcp

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream

private const val TAG = "NativeMcpBridge"

/**
 * Launches an MCP server process as a clean **stdio** subprocess.
 *
 * Unlike [com.google.ai.edge.gallery.data.NativeShellBridge], this bridge does **not** wrap the
 * command in a PTY or terminal emulator.  PTY/TTY injection would corrupt the line-delimited
 * JSON-RPC stream with ANSI escape codes, causing the JSON parser to fail silently.
 *
 * Usage:
 * ```
 * val bridge = NativeMcpBridge()
 * bridge.start("node /path/to/mcp-server/index.js", mapOf("API_KEY" to "…"))
 * bridge.sendLine(requestJson)
 * val response = bridge.readLine()
 * bridge.stop()
 * ```
 *
 * @see McpClient for the protocol layer that sits on top of this bridge.
 */
class NativeMcpBridge {

  private var process: Process? = null
  private var reader: BufferedReader? = null
  private var outputStream: OutputStream? = null

  /** `true` when the subprocess is alive and ready for I/O. */
  val isRunning: Boolean
    get() = process?.isAlive == true

  /**
   * Starts the MCP server subprocess.
   *
   * [command] is split on whitespace to form the [ProcessBuilder] argument list —
   * e.g. `"node /data/mcp/server/index.js"` becomes `["node", "/data/mcp/server/index.js"]`.
   *
   * Environment variables in [envVars] are merged into the subprocess environment **without**
   * logging their values, keeping secrets out of Logcat.
   *
   * @param command Full execution string for the MCP server.
   * @param envVars Key/value pairs injected into the subprocess environment.
   */
  fun start(command: String, envVars: Map<String, String> = emptyMap()) {
    if (isRunning) {
      Log.w(TAG, "start() called while already running — stopping previous process first")
      stop()
    }
    val tokens = command.trim().split(Regex("\\s+"))
    Log.d(TAG, "Starting MCP server: ${tokens.joinToString(" ")} (${envVars.size} env vars)")

    val proc =
      ProcessBuilder(tokens)
        // Keep stderr separate so it does not corrupt the JSON-RPC stdout stream.
        .redirectErrorStream(false)
        .also { pb ->
          val env = pb.environment()
          envVars.forEach { (k, v) -> env[k] = v }
        }
        .start()

    process = proc
    reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
    outputStream = proc.outputStream
    Log.d(TAG, "MCP server process started")
  }

  /**
   * Writes a single JSON-RPC line to the subprocess stdin.
   *
   * The newline character is appended automatically so callers need not include it.
   */
  fun sendLine(json: String) {
    try {
      outputStream?.let { out ->
        out.write((json + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
      }
    } catch (e: Exception) {
      Log.e(TAG, "sendLine failed: ${e.message}")
    }
  }

  /**
   * Reads one line from the subprocess stdout.
   *
   * Blocks the calling thread until a newline-terminated message arrives.
   * Returns `null` if the stream closes or an I/O error occurs.
   */
  fun readLine(): String? =
    try {
      reader?.readLine()
    } catch (e: Exception) {
      Log.e(TAG, "readLine failed: ${e.message}")
      null
    }

  /** Terminates the subprocess and releases all I/O resources. */
  fun stop() {
    try { outputStream?.flush() } catch (_: Exception) {}
    try { outputStream?.close() } catch (_: Exception) {}
    try { reader?.close() } catch (_: Exception) {}
    reader = null
    outputStream = null
    process?.destroyForcibly()
    process = null
    Log.d(TAG, "MCP server process stopped")
  }
}
