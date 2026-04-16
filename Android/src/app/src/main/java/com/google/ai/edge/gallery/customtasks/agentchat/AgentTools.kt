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
import com.google.ai.edge.gallery.common.AgentAction
import com.google.ai.edge.gallery.common.AskInfoAgentAction
import com.google.ai.edge.gallery.common.CallJsAgentAction
import com.google.ai.edge.gallery.common.CallJsSkillResult
import com.google.ai.edge.gallery.common.CallJsSkillResultImage
import com.google.ai.edge.gallery.common.CallJsSkillResultWebview
import com.google.ai.edge.gallery.common.LOCAL_URL_BASE
import com.google.ai.edge.gallery.common.SkillProgressAgentAction
import com.google.ai.edge.gallery.data.brainbox.BrainBoxDao
import com.google.ai.edge.gallery.data.brainbox.NeuronEntity
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking

private const val TAG = "AGAgentTools"

class AgentTools() : ToolSet {
  lateinit var context: Context
  lateinit var skillManagerViewModel: SkillManagerViewModel
  var brainBoxDao: BrainBoxDao? = null

  /** Lazily initialized FileBoxManager for file workspace operations. */
  val fileBoxManager: com.google.ai.edge.gallery.data.FileBoxManager by lazy {
    com.google.ai.edge.gallery.data.FileBoxManager(context)
  }

  private val _actionChannel = Channel<AgentAction>(Channel.UNLIMITED)
  val actionChannel: ReceiveChannel<AgentAction> = _actionChannel
  var resultImageToShow: CallJsSkillResultImage? = null
  var resultWebviewToShow: CallJsSkillResultWebview? = null

  /** Loads skill. */
  @Tool(description = "Loads a skill.")
  fun loadSkill(
    @ToolParam(description = "The name of the skill to load.") skillName: String
  ): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      val skills = skillManagerViewModel.getSelectedSkills()
      val skill = skills.find { it.name == skillName.trim() }
      val skillContent =
        if (skill != null) {
          "---\nname: ${skill.name}\ndescription: ${skill.description}\n---\n\n${skill.instructions}"
        } else {
          "Skill not found"
        }
      Log.d(TAG, "load skill. Skill content:\n$skillContent")
      if (skill != null) {
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Loading skill \"$skillName\"",
            inProgress = true,
            addItemTitle = "Load \"${skill.name}\"",
            addItemDescription = "Description: ${skill.description}",
            customData = skill,
          )
        )
      } else {
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Failed to load skill \"$skillName\"",
            inProgress = false,
          )
        )
      }

      mapOf("skill_name" to skillName, "skill_instructions" to skillContent)
    }
  }

  /** Call JS skill */
  @Tool(description = "Runs JS script")
  fun runJs(
    @ToolParam(description = "The name of skill") skillName: String,
    @ToolParam(description = "The script name to run. Use 'index.html' if not provided by user")
    scriptName: String,
    @ToolParam(
      description = "The data to pass to the script. Use empty string if not provided by user"
    )
    data: String,
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      Log.d(
        TAG,
        "runJS tool called with:" +
          "\n- skillName: ${skillName}\n- scriptName: ${scriptName}\n- data: ${data}\n",
      )

      val skills = skillManagerViewModel.getSelectedSkills()
      val skill = skills.find { it.name == skillName.trim() }

      if (skill == null) {
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Failed to call skill \"$scriptName\"",
            inProgress = false,
          )
        )
        return@runBlocking mapOf(
          "error" to "Skill \"${scriptName}\" not found",
          "status" to "failed",
        )
      }

      // Check secret. If a skill requires a secret and the secret is not provided, show error.
      var secret = ""
      if (skill.requireSecret) {
        val savedSecret =
          skillManagerViewModel.dataStoreRepository.readSecret(
            key = getSkillSecretKey(skillName = skillName)
          )
        if (savedSecret == null || savedSecret.isEmpty()) {
          val action =
            AskInfoAgentAction(
              dialogTitle = "Enter secret",
              fieldLabel =
                skill.requireSecretDescription.ifEmpty {
                  "The JS script needs a secret (API key / token) to proceed:"
                },
            )
          _actionChannel.send(action)
          secret = action.result.await()
          if (secret.isNotEmpty()) {
            skillManagerViewModel.dataStoreRepository.saveSecret(
              key = getSkillSecretKey(skillName = skillName),
              value = secret,
            )
            Log.d(TAG, "Got Secret from ask info dialog: ${secret.substring(0, 3)}")
          } else {
            Log.d(TAG, "The ask info dialog got cancelled. No secret.")
          }
        } else {
          secret = savedSecret
        }
      }

      // Get the url for the skill.
      val url =
        skillManagerViewModel.getJsSkillUrl(skillName = skillName, scriptName = scriptName)
          ?: return@runBlocking mapOf(
            "result" to "JS Skill URL not set properly or skill not found"
          )
      Log.d(TAG, "Calling JS script.\n- url: $url\n- data: $data")

      // Update progress.
      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Calling JS script \"${skillName}/${scriptName}\"",
          inProgress = true,
          addItemTitle = "Call JS script: \"${skillName}/${scriptName}\"",
          addItemDescription = "- URL: ${url.replace(LOCAL_URL_BASE, "")}\n- Data: $data",
          customData = skill,
        )
      )

      // Actually run it and wait for the result.
      val action =
        CallJsAgentAction(url = url, data = data.trim().ifEmpty { "{}" }, secret = secret)
      _actionChannel.send(action)
      val result = action.result.await()

      // Try to parse result to CallJsSkillResult.
      val moshi: Moshi = Moshi.Builder().build()
      val jsonAdapter: JsonAdapter<CallJsSkillResult> =
        moshi.adapter(CallJsSkillResult::class.java).failOnUnknown()
      val resultJson = runCatching { jsonAdapter.fromJson(result) }.getOrNull()
      val error = resultJson?.error

      // Failed to parse. Treat its whole as a result string.
      if (
        resultJson == null ||
          (resultJson.result == null && resultJson.webview == null && resultJson.image == null)
      ) {
        mapOf("result" to result, "status" to "succeeded")
      }
      // Error case.
      else if (error != null) {
        mapOf("error" to error, "status" to "failed")
      }
      // Non-error cases.
      else {
        // Handle image and webview in result.
        val image = resultJson.image
        val webview = resultJson.webview
        if (image != null) {
          Log.d(TAG, "Got an image response.")
          resultImageToShow = image
        }
        if (webview != null) {
          Log.d(TAG, "Got an webview response.")
          val webviewUrl =
            skillManagerViewModel.getJsSkillWebviewUrl(
              skillName = skillName,
              url = webview.url ?: "",
            )
          Log.d(TAG, "Webview url: $webviewUrl")
          resultWebviewToShow = webview.copy(url = webviewUrl)
        }
        Log.d(TAG, "Result: ${resultJson.result}")
        mapOf("result" to (resultJson.result ?: ""), "status" to "succeeded")
      }
    }
  }

  @Tool(
    description =
      "Run an Android intent. It is used to interact with the app to perform certain actions."
  )
  fun runIntent(
    @ToolParam(description = "The intent to run.") intent: String,
    @ToolParam(
      description = "A JSON string containing the parameter values required for the intent."
    )
    parameters: String,
  ): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      Log.d(TAG, "Run intent. Intent: '$intent', parameters: '$parameters'")
      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Executing intent \"$intent\"",
          inProgress = true,
          addItemTitle = "Execute intent \"$intent\"",
          addItemDescription = "Parameters: $parameters",
        )
      )
      if (IntentHandler.handleAction(context, intent, parameters)) {
        return@runBlocking mapOf(
          "action" to intent,
          "parameters" to parameters,
          "result" to "succeeded",
        )
      } else {
        return@runBlocking mapOf(
          "action" to intent,
          "parameters" to parameters,
          "result" to "failed",
        )
      }
    }
  }

  /**
   * Query the BRAIN_BOX knowledge graph.
   *
   * Returns all neurons whose label or content contains [query] (case-insensitive). If [query] is
   * empty, all neurons are returned.
   */
  @Tool(description = "Searches the CLU/BOX BrainBox knowledge graph for neurons matching a query. Returns label, type, and content for each matching neuron.")
  fun queryBrain(
    @ToolParam(description = "The search term. Pass an empty string to retrieve all neurons.") query: String,
  ): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      val dao = brainBoxDao
      if (dao == null) {
        Log.w(TAG, "queryBrain: BrainBoxDao not wired")
        return@runBlocking mapOf("error" to "BrainBox not available", "status" to "failed")
      }
      val all = dao.getAllNeurons()
      val matched = if (query.isBlank()) all else {
        val q = query.lowercase()
        all.filter { it.label.lowercase().contains(q) || it.content.lowercase().contains(q) }
      }
      val summary = matched.joinToString(separator = "\n") { n ->
        "[${n.type}] ${n.label}: ${n.content}"
      }.ifEmpty { "(no neurons found)" }
      Log.d(TAG, "queryBrain query='$query' returned ${matched.size} neurons")
      mapOf("result" to summary, "count" to matched.size.toString(), "status" to "succeeded")
    }
  }

  /**
   * Save a new neuron (memory) to the BRAIN_BOX knowledge graph.
   *
   * If a neuron with the same [label] already exists it is overwritten.
   */
  @Tool(description = "Saves a neuron to the CLU/BOX BrainBox knowledge graph. Use this to remember facts, preferences, context, or any information the user wants stored persistently.")
  fun saveBrainNeuron(
    @ToolParam(description = "A short, unique label identifying this memory (e.g. 'User preference: dark mode').") label: String,
    @ToolParam(description = "The category or type of memory (e.g. 'preference', 'fact', 'context', 'task').") type: String,
    @ToolParam(description = "The full content to store.") content: String,
  ): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      val dao = brainBoxDao
      if (dao == null) {
        Log.w(TAG, "saveBrainNeuron: BrainBoxDao not wired")
        return@runBlocking mapOf("error" to "BrainBox not available", "status" to "failed")
      }
      // Reuse the existing ID if a neuron with this label already exists, so it is overwritten.
      val existing = dao.getAllNeurons().find { it.label.equals(label.trim(), ignoreCase = true) }
      val neuron = NeuronEntity(
        id = existing?.id ?: UUID.randomUUID().toString(),
        label = label.trim(),
        type = type.trim(),
        content = content.trim(),
      )
      dao.insertNeuron(neuron)
      Log.d(TAG, "saveBrainNeuron saved neuron label='$label' (${if (existing != null) "updated" else "created"})")
      mapOf("label" to label, "type" to type, "status" to "succeeded")
    }
  }

  // =========================================================================
  // FILE_BOX — Sandboxed code workspace
  // =========================================================================

  /**
   * Write a text/code file to the CLU/BOX FILE_BOX workspace.
   *
   * Nested folders are created automatically — include them in the file_path
   * (e.g. "new_project/backend/src/api.js").
   */
  @Tool(
    description = "Writes a text or code file to the CLU/BOX FILE_BOX workspace. " +
      "You can create nested folders automatically by including them in the file_path " +
      "(e.g. 'new_project/folder/file.txt'). Only text-based extensions are allowed " +
      "(e.g. .txt, .kt, .js, .json, .md, .html, .py, .ts, .css, .xml, .yaml)."
  )
  fun fileBoxWrite(
    @ToolParam(description = "Relative path inside FILE_BOX (e.g. 'my_app/src/main.kt'). Nested directories are auto-created.")
    file_path: String,
    @ToolParam(description = "The full text/code content to write to the file.")
    content: String,
  ): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      Log.d(TAG, "fileBoxWrite: path='$file_path' content=${content.length} chars")
      val mgr = fileBoxManager

      if (!mgr.isAllowedExtension(file_path)) {
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "FILE_BOX: rejected '$file_path' (extension not allowed)",
            inProgress = false,
          )
        )
        return@runBlocking mapOf(
          "error" to "Extension not allowed. Only text/code files are permitted.",
          "status" to "failed",
        )
      }

      val isNew = !java.io.File(mgr.root, file_path).exists()
      val ok = mgr.writeCodeFile(file_path, content)

      if (!ok) {
        return@runBlocking mapOf("error" to "Failed to write file", "status" to "failed")
      }

      _actionChannel.send(
        SkillProgressAgentAction(
          label = "FILE_BOX: wrote '$file_path'",
          inProgress = false,
          addItemTitle = "FILE_BOX Write",
          addItemDescription = "Path: $file_path (${content.length} chars)",
        )
      )

      // Phase 4: BrainBox telemetry — log new file creations as neurons.
      if (isNew) {
        val dao = brainBoxDao
        if (dao != null) {
          val neuron = NeuronEntity(
            id = UUID.randomUUID().toString(),
            label = file_path,
            type = "File_Creation_Log",
            content = content,
          )
          dao.insertNeuron(neuron)
          Log.d(TAG, "fileBoxWrite: logged new file creation to BrainBox: $file_path")
        }
      }

      mapOf("file_path" to file_path, "status" to "succeeded")
    }
  }

  /**
   * Read a file from the CLU/BOX FILE_BOX workspace.
   */
  @Tool(
    description = "Reads a text or code file from the CLU/BOX FILE_BOX workspace. " +
      "Returns the file content as a string."
  )
  fun fileBoxRead(
    @ToolParam(description = "Relative path of the file to read inside FILE_BOX (e.g. 'my_app/src/main.kt').")
    file_path: String,
  ): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      Log.d(TAG, "fileBoxRead: path='$file_path'")
      val content = fileBoxManager.readCodeFile(file_path)

      if (content == null) {
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "FILE_BOX: file not found '$file_path'",
            inProgress = false,
          )
        )
        return@runBlocking mapOf(
          "error" to "File not found or is not readable: $file_path",
          "status" to "failed",
        )
      }

      _actionChannel.send(
        SkillProgressAgentAction(
          label = "FILE_BOX: read '$file_path'",
          inProgress = false,
          addItemTitle = "FILE_BOX Read",
          addItemDescription = "Path: $file_path (${content.length} chars)",
        )
      )

      mapOf("file_path" to file_path, "content" to content, "status" to "succeeded")
    }
  }

  // =========================================================================
  // Task Queue — Autonomous supervisor loop
  // =========================================================================

  /** Volatile flag: when a pending task is queued, the ViewModel can read it. */
  @Volatile var pendingTaskDescription: String? = null

  /**
   * Updates the autonomous task queue.  When [status] is "pending", the ViewModel's
   * inference loop will automatically re-invoke inference with [next_task_description]
   * as a system-level instruction, creating a continuous work loop until the task queue
   * is empty (status = "complete").
   */
  @Tool(
    description = "Updates the autonomous task queue. Use status='pending' and provide " +
      "a next_task_description to continue working on a multi-step project without user " +
      "prompting. Use status='complete' when all tasks are finished."
  )
  fun taskQueueUpdate(
    @ToolParam(description = "Description of the next task to perform. Only used when status is 'pending'.")
    next_task_description: String,
    @ToolParam(description = "Either 'pending' (more work to do) or 'complete' (all tasks finished).")
    status: String,
  ): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      val normalizedStatus = status.trim().lowercase()
      Log.d(TAG, "taskQueueUpdate: status='$normalizedStatus', desc='$next_task_description'")

      when (normalizedStatus) {
        "pending" -> {
          pendingTaskDescription = next_task_description.trim()
          _actionChannel.send(
            SkillProgressAgentAction(
              label = "Task queued: ${next_task_description.take(60)}…",
              inProgress = true,
              addItemTitle = "Task Queue: Pending",
              addItemDescription = next_task_description,
            )
          )
          mapOf("status" to "pending", "queued_task" to next_task_description)
        }
        "complete" -> {
          pendingTaskDescription = null
          _actionChannel.send(
            SkillProgressAgentAction(
              label = "Task queue complete ✓",
              inProgress = false,
              addItemTitle = "Task Queue: Complete",
              addItemDescription = "All tasks finished.",
            )
          )
          mapOf("status" to "complete")
        }
        else -> {
          mapOf("error" to "Invalid status '$status'. Use 'pending' or 'complete'.", "status" to "failed")
        }
      }
    }
  }

  // =========================================================================
  // Planner-Worker — Dual-agent autonomous project generation
  // =========================================================================

  /** Blueprint file path inside FILE_BOX. */
  private val BLUEPRINT_PATH = "blueprint.md"

  /**
   * Architect_Init: the planning phase of the dual-agent workflow.
   *
   * The LLM calls this once to commit a project blueprint. The callback:
   * 1. Writes [blueprint_markdown] to `clu_file_box/blueprint.md`.
   * 2. Queues a pending task that instructs the worker to begin executing
   *    the first pending file from the blueprint.
   */
  @Tool(
    description = "Architect phase: commits a project blueprint. Call this ONCE at the start of " +
      "a multi-file project. Provide the full project goal and a markdown blueprint that lists " +
      "every file to create with its path and status (PENDING/DONE). After this call the worker " +
      "phase begins automatically."
  )
  fun architectInit(
    @ToolParam(description = "High-level description of the project goal.")
    project_goal: String,
    @ToolParam(description = "Full markdown blueprint listing every file to generate. " +
      "Each file entry should be on its own line in the format: '- [ ] path/to/file.ext' " +
      "(PENDING) or '- [x] path/to/file.ext' (DONE).")
    blueprint_markdown: String,
  ): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      Log.d(TAG, "architectInit: goal='${project_goal.take(80)}', blueprint=${blueprint_markdown.length} chars")

      // Write the blueprint to FILE_BOX.
      val mgr = fileBoxManager
      val written = mgr.writeCodeFile(BLUEPRINT_PATH, blueprint_markdown)
      if (!written) {
        return@runBlocking mapOf(
          "error" to "Failed to write blueprint.md",
          "status" to "failed",
        )
      }

      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Architect: blueprint committed (${blueprint_markdown.lines().size} lines)",
          inProgress = true,
          addItemTitle = "Architect_Init",
          addItemDescription = "Goal: ${project_goal.take(120)}\nBlueprint written to $BLUEPRINT_PATH",
        )
      )

      // Log goal to BrainBox.
      val dao = brainBoxDao
      if (dao != null) {
        dao.insertNeuron(
          NeuronEntity(
            id = UUID.randomUUID().toString(),
            label = "Project_Goal",
            type = "Architect",
            content = project_goal,
          )
        )
      }

      // Auto-trigger the worker phase.
      pendingTaskDescription =
        "WORKER PHASE: Read blueprint.md from FILE_BOX using fileBoxRead(file_path='blueprint.md'). " +
        "Find the first file marked as '- [ ]' (PENDING). Generate its full content and call " +
        "workerExecute with the target_file_path, code_content, and is_project_finished=false. " +
        "Continue until all files are DONE, then call workerExecute with is_project_finished=true."

      mapOf(
        "blueprint_path" to BLUEPRINT_PATH,
        "project_goal" to project_goal,
        "status" to "succeeded",
      )
    }
  }

  /**
   * Worker_Execute: the execution phase of the dual-agent workflow.
   *
   * Called once per file. The callback:
   * 1. Writes [code_content] to [target_file_path] via FileBoxManager.
   * 2. Reads blueprint.md, marks the target file as DONE.
   * 3. If [is_project_finished] is false, queues the next pending file.
   */
  @Tool(
    description = "Worker phase: writes a single file and updates the blueprint. Call this for " +
      "each file in the project. Set is_project_finished to true only when every file in the " +
      "blueprint is DONE."
  )
  fun workerExecute(
    @ToolParam(description = "Relative file path to write (e.g. 'my_app/src/main.kt').")
    target_file_path: String,
    @ToolParam(description = "The full text/code content for the file.")
    code_content: String,
    @ToolParam(description = "Set to 'true' when ALL files in the blueprint are complete, 'false' otherwise.")
    is_project_finished: String,
  ): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      val finished = is_project_finished.trim().lowercase() == "true"
      Log.d(TAG, "workerExecute: path='$target_file_path', finished=$finished, content=${code_content.length} chars")

      // 1. Write the file via FileBoxManager.
      val mgr = fileBoxManager
      if (!mgr.isAllowedExtension(target_file_path)) {
        return@runBlocking mapOf(
          "error" to "Extension not allowed for '$target_file_path'.",
          "status" to "failed",
        )
      }
      val isNew = !java.io.File(mgr.root, target_file_path).exists()
      val ok = mgr.writeCodeFile(target_file_path, code_content)
      if (!ok) {
        return@runBlocking mapOf("error" to "Failed to write $target_file_path", "status" to "failed")
      }

      // BrainBox telemetry for new files.
      if (isNew) {
        brainBoxDao?.insertNeuron(
          NeuronEntity(
            id = UUID.randomUUID().toString(),
            label = target_file_path,
            type = "File_Creation_Log",
            content = code_content,
          )
        )
      }

      // 2. Read and update blueprint.md — mark target file as DONE.
      val blueprint = mgr.readCodeFile(BLUEPRINT_PATH)
      if (blueprint != null) {
        // Replace the first occurrence of "- [ ] target_file_path" with "- [x] target_file_path"
        val updatedBlueprint = blueprint.replaceFirst(
          "- [ ] $target_file_path",
          "- [x] $target_file_path",
        )
        mgr.writeCodeFile(BLUEPRINT_PATH, updatedBlueprint)
      }

      _actionChannel.send(
        SkillProgressAgentAction(
          label = if (finished) "Worker: project complete ✓" else "Worker: wrote '$target_file_path'",
          inProgress = !finished,
          addItemTitle = "Worker_Execute",
          addItemDescription = "Path: $target_file_path (${code_content.length} chars)" +
            if (finished) "\n✓ All files complete." else "",
        )
      )

      // 3. Queue next iteration or signal completion.
      if (!finished) {
        pendingTaskDescription =
          "WORKER PHASE: Read the updated blueprint.md using fileBoxRead(file_path='blueprint.md'). " +
          "Find the next file marked as '- [ ]' (PENDING). Generate its full content and call " +
          "workerExecute with the target_file_path, code_content, and is_project_finished set " +
          "appropriately (true only if this is the last file)."
      } else {
        pendingTaskDescription = null
      }

      mapOf(
        "file_path" to target_file_path,
        "is_project_finished" to finished.toString(),
        "status" to "succeeded",
      )
    }
  }

  // =========================================================================
  // SHELL_EXECUTE — Run terminal commands and return raw output
  // =========================================================================

  /**
   * Executes a shell command on the device and returns the combined stdout + stderr output.
   *
   * A strict 10-second timeout is enforced; if the process hangs it is killed and
   * "TIMEOUT ERROR" is returned.
   */
  @Tool(
    description = "Use this to execute terminal commands, run test scripts, or check file states. " +
      "You will receive the raw terminal output. Use this to verify your code works or to debug " +
      "stack traces before moving to the next task."
  )
  fun shellExecute(
    @ToolParam(description = "The shell command to execute (e.g. 'ls -la', 'cat file.txt', 'python3 test.py').")
    command: String,
  ): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      Log.d(TAG, "shellExecute: command='$command'")

      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Shell: executing command…",
          inProgress = true,
          addItemTitle = "Shell_Execute",
          addItemDescription = "$ $command",
        )
      )

      val output = com.google.ai.edge.gallery.data.executeCommand(command)

      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Shell: command finished",
          inProgress = false,
          addItemTitle = "Shell_Execute",
          addItemDescription = "$ $command\n${output.take(200)}${if (output.length > 200) "…" else ""}",
        )
      )

      val status = when {
        output == "TIMEOUT ERROR" -> "timeout"
        output.startsWith("ERROR:") -> "error"
        else -> "succeeded"
      }

      mapOf(
        "command" to command,
        "output" to output,
        "status" to status,
      )
    }
  }

  fun sendAgentAction(action: AgentAction) {
    runBlocking(Dispatchers.Default) { _actionChannel.send(action) }
  }
}

fun getSkillSecretKey(skillName: String): String {
  return "skill___${skillName}"
}
