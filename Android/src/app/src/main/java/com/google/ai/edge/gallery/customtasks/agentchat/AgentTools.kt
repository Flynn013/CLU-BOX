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

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.common.AskInfoAgentAction
import com.google.ai.edge.gallery.common.CallJsAgentAction
import com.google.ai.edge.gallery.common.SkillProgressAgentAction
import com.google.ai.edge.gallery.data.TerminalSessionManager
import com.google.ai.edge.gallery.data.brainbox.BrainBoxDao
import com.google.ai.edge.gallery.data.brainbox.VectorEngine
import com.google.ai.edge.litertlm.Tool
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.channels.Channel

private const val TAG = "AgentTools"
// 3 000 chars keeps tool output well within the ~4 096-token local context window.
// Larger payloads risk KV-cache bloat and native SIGSEGV on device.
private const val MAX_TOOL_OUTPUT_CHARS = 3000

/**
 * Host object that exposes all CLU/BOX tools to the LiteRT-LM `@Tool` framework.
 *
 * An instance lives inside [AgentChatTask] and is passed around the screen.
 * Mutable properties (context, brainBoxDao, etc.) are injected by [AgentChatScreen]
 * after Compose composition.
 */
class AgentTools {

  // ── Injected by AgentChatScreen after composition ─────────────────
  var context: Context? = null
  var skillManagerViewModel: SkillManagerViewModel? = null
  var brainBoxDao: BrainBoxDao? = null
  var vectorEngine: VectorEngine? = null
  var terminalSessionManager: TerminalSessionManager? = null

  // ── Runtime engine selector (set by agentic context router) ───────
  var engine: AgentEngine = AgentEngine.LOCAL

  // ── Autonomous loop state ─────────────────────────────────────────
  /** If non-null, the loop will auto-re-trigger inference with this description. */
  var pendingTaskDescription: String? = null

  /** If non-null, the screen will render this image after the turn. */
  var resultImageToShow: Any? = null

  /** If non-null, the screen will render this WebView after the turn. */
  var resultWebviewToShow: Any? = null

  // ── Circuit-breaker governor ───────────────────────────────────────
  val governor: AgentGovernor = AgentGovernor()

  // ── Skill registry ─────────────────────────────────────────────────
  val skillRegistry: SkillRegistry by lazy { SkillRegistry(this) }

  // ── Agent action channel (consumed by AgentChatScreen) ───────────
  val actionChannel: Channel<Any> = Channel(Channel.BUFFERED)

  fun sendAgentAction(action: Any) {
    actionChannel.trySend(action)
  }

  // ── CluSkill metadata stubs ────────────────────────────────────────
  /**
   * Returns metadata-only [CluSkill] entries for @Tool methods that don't
   * have a full [CluSkill] implementation. These are routed through the
   * litertlm @Tool framework directly.
   */
  fun getMetadataOnlySkills(): List<CluSkill> = emptyList()

  // ── Output safety ─────────────────────────────────────────────────

  /**
   * Hard-caps tool outputs to prevent KV-cache bloat and SIGSEGV on token overflow.
   * If truncated, the full output is spilled to a temp file in the sandbox.
   */
  fun capOutputWithSpill(rawOutput: String, toolName: String): String {
    if (rawOutput.length <= MAX_TOOL_OUTPUT_CHARS) return rawOutput
    val ctx = context ?: return rawOutput.take(MAX_TOOL_OUTPUT_CHARS) +
      "\n[SYSTEM WARNING: Output truncated.]"
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val spillFileName = "spill_${toolName}_$timeStamp.txt"
    val workspaceDir = File(ctx.filesDir, "workspace/temp_out").also { it.mkdirs() }
    val spillFile = File(workspaceDir, spillFileName)
    return try {
      spillFile.writeText(rawOutput)
      rawOutput.take(MAX_TOOL_OUTPUT_CHARS) +
        "\n[SYSTEM WARNING: Output truncated. Full output saved to: workspace/temp_out/$spillFileName]"
    } catch (e: Exception) {
      rawOutput.take(MAX_TOOL_OUTPUT_CHARS) + "\n[SYSTEM WARNING: Output truncated. Failed to write spill file: ${e.message}]"
    }
  }

  // ── Tool implementations ──────────────────────────────────────────

  /**
   * Executes a shell command in the sandbox and returns its output.
   * The result map uses keys "stdout" and "exit_code".
   */
  @Tool("Execute a bash command in the sandboxed terminal. Returns stdout and exit code.")
  fun shellExecute(command: String): Map<String, String> {
    if (command.isBlank()) return mapOf("stdout" to "Error: command argument is required", "exit_code" to "1")
    Log.d(TAG, "shellExecute: $command")
    sendAgentAction(SkillProgressAgentAction(label = "Executing: $command", inProgress = true))
    val tsm = terminalSessionManager
    if (tsm == null) {
      Log.w(TAG, "shellExecute: TerminalSessionManager not available")
      return mapOf("stdout" to "[Error: Terminal session not available]", "exit_code" to "1")
    }
    val (exitCode, output) = tsm.executeCommandWithExitCode(command)
    val capped = capOutputWithSpill(output, "shellExecute")
    Log.d(TAG, "shellExecute exit=$exitCode output=${output.take(200)}")
    sendAgentAction(SkillProgressAgentAction(label = "Executed: $command", inProgress = false))
    return mapOf("stdout" to capped, "exit_code" to exitCode.toString())
  }

  /**
   * Writes content to a file in the CLU FileBox sandbox.
   * Returns "ok" or an error string.
   */
  @Tool("Write content to a file in the FileBox sandbox. This is the ONLY way to create or overwrite files.")
  fun fileBoxWrite(file_path: String, content: String): Map<String, String> {
    if (file_path.isBlank()) return mapOf("result" to "Error: file_path argument is required")
    if (content.isBlank()) return mapOf("result" to "Error: content argument is required")
    val ctx = context ?: return mapOf("result" to "Error: context not available")
    Log.d(TAG, "fileBoxWrite: $file_path (${content.length} chars)")
    sendAgentAction(SkillProgressAgentAction(label = "Writing: $file_path", inProgress = true))
    return try {
      val safeRelative = file_path.trimStart('/')
      val root = File(ctx.filesDir, "clu_file_box")
      val target = File(root, safeRelative).canonicalFile
      // Path traversal guard — target must be under clu_file_box.
      if (!target.absolutePath.startsWith(root.canonicalPath)) {
        return mapOf("result" to "Error: path traversal not allowed")
      }
      target.parentFile?.mkdirs()
      target.writeText(content, Charsets.UTF_8)
      sendAgentAction(SkillProgressAgentAction(label = "Written: $file_path", inProgress = false))
      mapOf("result" to "ok", "path" to target.absolutePath)
    } catch (e: Exception) {
      Log.e(TAG, "fileBoxWrite failed", e)
      mapOf("result" to "Error: ${e.message}")
    }
  }

  /**
   * Reads a line range from a file in the FileBox sandbox.
   * Useful for inspecting large files without consuming the full context window.
   */
  @Tool("Read a range of lines from a file in the FileBox sandbox.")
  fun fileBoxReadLines(file_path: String, start_line: Int, end_line: Int): Map<String, String> {
    if (file_path.isBlank()) return mapOf("lines" to "Error: file_path argument is required")
    val ctx = context ?: return mapOf("lines" to "Error: context not available")
    Log.d(TAG, "fileBoxReadLines: $file_path [$start_line..$end_line]")
    return try {
      val safeRelative = file_path.trimStart('/')
      val root = File(ctx.filesDir, "clu_file_box")
      val target = File(root, safeRelative).canonicalFile
      if (!target.absolutePath.startsWith(root.canonicalPath)) {
        return mapOf("lines" to "Error: path traversal not allowed")
      }
      if (!target.exists()) return mapOf("lines" to "Error: file not found at $file_path")
      val allLines = target.readLines(Charsets.UTF_8)
      val slice = allLines.drop(start_line.coerceAtLeast(0))
        .take((end_line - start_line.coerceAtLeast(0)).coerceAtLeast(1))
      val result = slice.joinToString("\n")
      val capped = capOutputWithSpill(result, "fileBoxReadLines")
      mapOf("lines" to capped, "total_lines" to allLines.size.toString())
    } catch (e: Exception) {
      Log.e(TAG, "fileBoxReadLines failed", e)
      mapOf("lines" to "Error: ${e.message}")
    }
  }

  /**
   * Searches a file for a keyword and returns matching lines with ±2 lines of context.
   */
  @Tool("Search a file in the FileBox sandbox for a keyword and return matching lines.")
  fun brainBoxGrep(file_path: String, keyword: String): Map<String, String> {
    if (file_path.isBlank()) return mapOf("matches" to "Error: file_path argument is required")
    if (keyword.isBlank()) return mapOf("matches" to "Error: keyword argument is required")
    val ctx = context ?: return mapOf("matches" to "Error: context not available")
    Log.d(TAG, "brainBoxGrep: $file_path keyword='$keyword'")
    return try {
      val safeRelative = file_path.trimStart('/')
      val root = File(ctx.filesDir, "clu_file_box")
      val target = File(root, safeRelative).canonicalFile
      if (!target.absolutePath.startsWith(root.canonicalPath)) {
        return mapOf("matches" to "Error: path traversal not allowed")
      }
      if (!target.exists()) return mapOf("matches" to "Error: file not found at $file_path")
      val lines = target.readLines(Charsets.UTF_8)
      val result = buildString {
        lines.forEachIndexed { idx, line ->
          if (line.contains(keyword, ignoreCase = true)) {
            val start = (idx - 2).coerceAtLeast(0)
            val end = (idx + 2).coerceAtMost(lines.size - 1)
            appendLine("--- Line ${idx + 1} ---")
            for (i in start..end) appendLine("[${i + 1}] ${lines[i]}")
          }
        }
        if (isEmpty()) append("(no matches)")
      }
      val capped = capOutputWithSpill(result, "brainBoxGrep")
      mapOf("matches" to capped)
    } catch (e: Exception) {
      Log.e(TAG, "brainBoxGrep failed", e)
      mapOf("matches" to "Error: ${e.message}")
    }
  }
}
