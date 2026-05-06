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

// Todo extension backed by BRAIN_BOX EPISODIC neurons — the CLU/BOX equivalent of the
// Goose platform_extensions/todo.rs built-in MCP server.

package com.google.ai.edge.gallery.data.extensions.builtin

import android.util.Log
import com.google.ai.edge.gallery.customtasks.agentchat.CluSkill
import com.google.ai.edge.gallery.data.brainbox.BrainBoxDao
import com.google.ai.edge.gallery.data.brainbox.NeuronEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

private const val TAG = "TodoExtension"

/** The BrainBox neuron type used for per-session todo lists. */
private const val TODO_NEURON_TYPE = "TODO_LIST"

/**
 * Provides the `todo_read` and `todo_write` skills used by the agent to maintain
 * a structured per-session task list.
 *
 * ## Storage
 * Each session's todo list is stored as a single EPISODIC [NeuronEntity] in the
 * BRAIN_BOX Room database (`type = "TODO_LIST"`, `label = "todo:<sessionId>"`).
 * This means:
 * - Todo state survives process death.
 * - The user can view / edit the list directly in the BrainBox UI.
 * - BrainBox semantic search can surface past session todos as context.
 *
 * ## Format
 * Todo lists are stored in GitHub-Flavour Markdown checkbox syntax:
 * ```
 * - [ ] First task
 * - [x] Completed task
 * - [ ] Another task
 * ```
 * The agent writes the *entire* list on each update (idempotent replacement).
 *
 * @param brainBoxDao  Room DAO for BRAIN_BOX neuron storage
 * @param sessionId    Current session id used as the neuron label key
 */
class TodoExtension(
    private val brainBoxDao: BrainBoxDao,
    private val sessionId: String,
) {
    private val neuronLabel = "todo:$sessionId"

    // ── Skills ─────────────────────────────────────────────────────────────

    /**
     * `todo_write` skill — writes (replaces) the entire todo list for the session.
     *
     * The model must provide the complete markdown list, not just a diff.
     */
    val todoWriteSkill: CluSkill = object : CluSkill {
        override val name = "todo_write"
        override val description = """
Write the complete todo list for the current session.
Always write the ENTIRE list — this fully replaces the previous list.
Format: GitHub-Flavour Markdown checkboxes.
  - [ ] pending task
  - [x] completed task
Do NOT include any preamble — just the task lines.
""".trimIndent()

        override val jsonSchema = """
{
  "type": "object",
  "properties": {
    "markdown": {
      "type": "string",
      "description": "Complete todo list in GFM checkbox format."
    }
  },
  "required": ["markdown"]
}
""".trimIndent()

        override val fewShotExample = """
{"markdown": "- [ ] Scaffold project\n- [ ] Write README\n- [x] Install dependencies"}
""".trimIndent()

        override suspend fun execute(args: JSONObject): String =
            writeTodo(args.optString("markdown", ""))
    }

    /**
     * `todo_read` skill — reads the current todo list for the session.
     */
    val todoReadSkill: CluSkill = object : CluSkill {
        override val name = "todo_read"
        override val description = "Read the current todo list for this session. Call this at the start of any multi-step task to understand what is already done."

        override val jsonSchema = """
{
  "type": "object",
  "properties": {},
  "required": []
}
""".trimIndent()

        override val fewShotExample = "{}"

        override suspend fun execute(args: JSONObject): String = readTodo()
    }

    // ── Implementation ─────────────────────────────────────────────────────

    /** Returns the current todo markdown, or an empty-list notice. */
    suspend fun readTodo(): String = withContext(Dispatchers.IO) {
        val neuron = brainBoxDao.searchNeurons(neuronLabel).firstOrNull {
            it.label == neuronLabel && it.type == TODO_NEURON_TYPE
        }
        if (neuron == null || neuron.content.isBlank()) {
            Log.d(TAG, "No todo list found for session '$sessionId'")
            "No todo items yet."
        } else {
            Log.d(TAG, "Read todo (${neuron.content.length} chars) for session '$sessionId'")
            neuron.content
        }
    }

    /**
     * Writes [markdown] as the full todo list for the session.
     *
     * Creates or replaces the BrainBox neuron atomically.
     */
    suspend fun writeTodo(markdown: String): String = withContext(Dispatchers.IO) {
        if (markdown.isBlank()) return@withContext "Error: todo_write requires a non-blank markdown list."

        val existing = brainBoxDao.searchNeurons(neuronLabel).firstOrNull {
            it.label == neuronLabel && it.type == TODO_NEURON_TYPE
        }

        val neuronId = existing?.id ?: UUID.randomUUID().toString()
        val neuron = NeuronEntity(
            id = neuronId,
            label = neuronLabel,
            type = TODO_NEURON_TYPE,
            content = markdown.trim(),
            synapses = "",
            isCore = false,
        )
        brainBoxDao.insertNeuron(neuron)

        val taskCount = markdown.lines().count { it.trimStart().startsWith("- ") }
        val doneCount = markdown.lines().count { it.trimStart().startsWith("- [x]") }
        Log.d(TAG, "Wrote todo ($taskCount tasks, $doneCount done) for session '$sessionId'")
        "Todo list updated ($taskCount tasks, $doneCount completed)."
    }

    /**
     * Marks a task as complete by searching for [taskText] in the current list
     * and replacing `- [ ]` with `- [x]`.
     *
     * Returns a description of the change, or an error if the task was not found.
     */
    suspend fun markDone(taskText: String): String {
        val current = readTodo()
        if (current == "No todo items yet.") return "No todo list found."
        val updated = current.lines().joinToString("\n") { line ->
            if (line.contains(taskText, ignoreCase = true) && line.trimStart().startsWith("- [ ]")) {
                line.replace("- [ ]", "- [x]")
            } else {
                line
            }
        }
        return if (updated == current) {
            "Task not found: '$taskText'"
        } else {
            writeTodo(updated)
        }
    }

    /**
     * Clears the entire todo list for this session.
     */
    suspend fun clearTodo(): String = withContext(Dispatchers.IO) {
        val neurons = brainBoxDao.searchNeurons(neuronLabel).filter {
            it.label == neuronLabel && it.type == TODO_NEURON_TYPE
        }
        for (n in neurons) brainBoxDao.deleteEpisodicById(n.id)
        Log.d(TAG, "Cleared todo for session '$sessionId'")
        "Todo list cleared."
    }
}
