/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import com.google.ai.edge.gallery.common.SkillProgressAgentAction
import com.google.ai.edge.gallery.data.TerminalSessionManager
import com.google.ai.edge.gallery.data.brainbox.BrainBoxDao
import com.google.ai.edge.gallery.data.brainbox.VectorEngine
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolSet
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.channels.Channel

private const val TAG = "AgentTools"

/**
 * Host object that exposes all CLU/BOX tools to the LiteRT-LM `@Tool` framework.
 *
 * An instance lives inside [AgentChatTask] and is passed around the screen.
 * Mutable properties (context, brainBoxDao, etc.) are injected by [AgentChatScreen]
 * after Compose composition.
 */
class AgentTools : ToolSet {

  // ── Injected by AgentChatScreen after composition ─────────────────
  var context: Context? = null
  var skillManagerViewModel: SkillManagerViewModel? = null
  var brainBoxDao: BrainBoxDao? = null
  var vectorEngine: VectorEngine? = null
  var terminalSessionManager: TerminalSessionManager? = null

  // ── Runtime engine selector (set by agentic context router) ───────
  var engine: AgentEngine = AgentEngine.LOCAL

  // ── Autonomous loop state ─────────────────────────────────────────
  var pendingTaskDescription: String? = null
  var resultImageToShow: Any? = null
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

  fun getMetadataOnlySkills(): List<CluSkill> = emptyList()

  // ── Output safety ─────────────────────────────────────────────────

  /**
   * Dynamically caps tool outputs to prevent KV-cache bloat on local hardware.
   * Cloud models bypass the tight restriction to leverage their massive context windows.
   */
  fun capOutputWithSpill(rawOutput: String, toolName: String): String {
    // Flex the pipeline based on the active cognitive engine
    val maxLimit = if (engine == AgentEngine.CLOUD) {
      // Cloud models can easily digest entire files. Cap set high just for network sanity.
      250_000 
    } else {
      // Local models will SIGSEGV the device if the token limit overflows.
      3000 
    }

    if (rawOutput.length <= maxLimit) return rawOutput
    
    val ctx = context ?: return rawOutput.take(maxLimit) +
      "\n[SYSTEM WARNING: Output truncated.]"
      
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val spillFileName = "spill_${toolName}_$timeStamp.txt"
    val workspaceDir = File(ctx.filesDir, "workspace/temp_out").also { it.mkdirs() }
    val spillFile = File(workspaceDir, spillFileName)
    
    return try {
      spillFile.writeText(rawOutput)
      rawOutput.take(maxLimit) +
        "\n[SYSTEM WARNING: Output truncated. Full output saved to: workspace/temp_out/$spillFileName]"
    } catch (e: Exception) {
      rawOutput.take(maxLimit) + "\n[SYSTEM WARNING: Output truncated. Failed to write spill file: ${e.message}]"
    }
  }

  // ── File & Shell Tool Implementations ─────────────────────────────

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

  // ── BrainBox Knowledge Graph Tools ────────────────────────────────

  @Tool("Search the BrainBox database for memories, knowledge, or context using a keyword query. Returns matching memory nodes.")
  fun brainBoxSearch(query: String): Map<String, String> {
    if (query.isBlank()) return mapOf("result" to "Error: query argument is required")
    val dao = brainBoxDao ?: return mapOf("result" to "Error: BrainBox database not available")

    Log.d(TAG, "brainBoxSearch: '$query'")
    sendAgentAction(SkillProgressAgentAction(label = "Recalling: $query", inProgress = true))

    return try {
      val matches = kotlinx.coroutines.runBlocking {
        dao.searchNeurons(query)
      }
      if (matches.isEmpty()) {
        sendAgentAction(SkillProgressAgentAction(label = "Recall: No matches", inProgress = false))
        return mapOf("result" to "No matches found for '$query'")
      }

      val resultStr = buildString {
        matches.forEach { n ->
          appendLine("---")
          appendLine("Label: ${n.label}")
          appendLine("Type: ${n.type}")
          appendLine("Is Core: ${n.isCore}")
          appendLine("Content: ${n.content}")
          if (n.synapses.isNotBlank()) appendLine("Synapses: ${n.synapses}")
        }
      }

      sendAgentAction(SkillProgressAgentAction(label = "Recall: ${matches.size} found", inProgress = false))
      mapOf("result" to capOutputWithSpill(resultStr, "brainBoxSearch"))
    } catch (e: Exception) {
      Log.e(TAG, "brainBoxSearch failed", e)
      mapOf("result" to "Error: ${e.message}")
    }
  }

  @Tool("Forge a new memory node in the BrainBox database. If no synapses exist, pass an empty string.")
  fun brainBoxWrite(label: String, type: String, content: String, synapses: String): Map<String, String> {
    if (label.isBlank()) return mapOf("result" to "Error: label argument is required")
    if (content.isBlank()) return mapOf("result" to "Error: content argument is required")
    
    val dao = brainBoxDao ?: return mapOf("result" to "Error: BrainBox database not available")
    
    Log.d(TAG, "brainBoxWrite: Forging neuron '$label'")
    sendAgentAction(SkillProgressAgentAction(label = "Forging Node: $label", inProgress = true))
    
    return try {
      val neuron = com.google.ai.edge.gallery.data.brainbox.NeuronEntity(
        id = UUID.randomUUID().toString(),
        label = label,
        type = type.ifBlank { "Concept" },
        content = content,
        synapses = synapses,
        isCore = false,
        falsePaths = ""
      )
      
      kotlinx.coroutines.runBlocking {
        dao.insertNeuron(neuron)
      }
      
      sendAgentAction(SkillProgressAgentAction(label = "Node Forged: $label", inProgress = false))
      mapOf("result" to "ok", "message" to "Successfully forged node '$label' in BrainBox")
    } catch (e: Exception) {
      Log.e(TAG, "brainBoxWrite failed", e)
      mapOf("result" to "Error: ${e.message}")
    }
  }

  @Tool("Edit an existing, non-core memory node in BrainBox. Target the node by its exact Label.")
  fun brainBoxEdit(target_label: String, new_content: String): Map<String, String> {
    if (target_label.isBlank()) return mapOf("result" to "Error: target_label is required")
    if (new_content.isBlank()) return mapOf("result" to "Error: new_content is required")
    val dao = brainBoxDao ?: return mapOf("result" to "Error: BrainBox database not available")

    Log.d(TAG, "brainBoxEdit: '$target_label'")
    sendAgentAction(SkillProgressAgentAction(label = "Rewiring Node: $target_label", inProgress = true))

    return try {
      kotlinx.coroutines.runBlocking {
        val matches = dao.searchNeurons(target_label)
        val target = matches.find { it.label.equals(target_label, ignoreCase = true) }

        if (target == null) {
          sendAgentAction(SkillProgressAgentAction(label = "Rewire Failed: Node not found", inProgress = false))
          return@runBlocking mapOf("result" to "Error: No node found with exact label '$target_label'")
        }

        if (target.isCore) {
          sendAgentAction(SkillProgressAgentAction(label = "Rewire Failed: Core protection active", inProgress = false))
          return@runBlocking mapOf("result" to "Error: Cannot edit core node '$target_label'. Core nodes are read-only.")
        }

        val updatedNode = target.copy(content = new_content)
        dao.updateNeuron(updatedNode)

        sendAgentAction(SkillProgressAgentAction(label = "Node Rewired: $target_label", inProgress = false))
        mapOf("result" to "ok", "message" to "Successfully updated node '$target_label'")
      }
    } catch (e: Exception) {
      Log.e(TAG, "brainBoxEdit failed", e)
      mapOf("result" to "Error: ${e.message}")
    }
  }

  @Tool("Delete a non-core memory node from BrainBox. Target the node by its exact Label.")
  fun brainBoxDelete(target_label: String): Map<String, String> {
    if (target_label.isBlank()) return mapOf("result" to "Error: target_label is required")
    val dao = brainBoxDao ?: return mapOf("result" to "Error: BrainBox database not available")

    Log.d(TAG, "brainBoxDelete: '$target_label'")
    sendAgentAction(SkillProgressAgentAction(label = "Pruning Node: $target_label", inProgress = true))

    return try {
      kotlinx.coroutines.runBlocking {
        val matches = dao.searchNeurons(target_label)
        val target = matches.find { it.label.equals(target_label, ignoreCase = true) }

        if (target == null) {
          sendAgentAction(SkillProgressAgentAction(label = "Prune Failed: Node not found", inProgress = false))
          return@runBlocking mapOf("result" to "Error: No node found with exact label '$target_label'")
        }

        if (target.isCore) {
          sendAgentAction(SkillProgressAgentAction(label = "Prune Failed: Core protection active", inProgress = false))
          return@runBlocking mapOf("result" to "Error: Cannot delete core node '$target_label'. Core nodes are protected.")
        }

        dao.deleteNeuron(target)

        sendAgentAction(SkillProgressAgentAction(label = "Node Pruned: $target_label", inProgress = false))
        mapOf("result" to "ok", "message" to "Successfully deleted node '$target_label'")
      }
    } catch (e: Exception) {
      Log.e(TAG, "brainBoxDelete failed", e)
      mapOf("result" to "Error: ${e.message}")
    }
  }
}
