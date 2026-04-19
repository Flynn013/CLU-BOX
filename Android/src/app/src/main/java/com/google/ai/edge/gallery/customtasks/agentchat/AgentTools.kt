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
import com.google.ai.edge.gallery.data.brainbox.VectorEngine
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking

private const val TAG = "AGAgentTools"

/**
 * Maximum character length for any single string argument accepted by a tool.
 * Prevents the LLM (or a prompt-injection) from sending megabytes of junk data
 * into a tool call. 32 KB is generous for any realistic code file or command.
 */
private const val MAX_INPUT_CHARS = 32_768

/**
 * Maximum character length for any single tool output field delivered to the LLM.
 * Prevents runaway outputs (e.g. large file reads, verbose shell dumps) from
 * consuming the context window. ~3000 chars ≈ 750–950 tokens for Gemma.
 */
private const val MAX_OUTPUT_CHARS = 3000

// ── Input-Validation Helpers ───────────────────────────────────────────────

/** Truncates any input string to [MAX_INPUT_CHARS]. */
private fun capInput(text: String): String =
  if (text.length > MAX_INPUT_CHARS) text.take(MAX_INPUT_CHARS) else text

/**
 * Returns a non-null error message if [path] contains path-traversal sequences
 * (`..`) or absolute-path prefixes that would escape the FILE_BOX sandbox.
 */
private fun validateRelativePath(path: String): String? {
  val normalized = path.trim()
  if (normalized.isEmpty()) return "Path must not be empty"
  if (normalized.contains("..")) return "Path must not contain '..'"
  if (normalized.startsWith("/")) return "Path must be relative (no leading '/')"
  return null
}

/**
 * Dangerous shell metacharacter patterns that should be blocked from tool-supplied commands.
 * This prevents the LLM from constructing shell injections via tool arguments.
 */
private val DANGEROUS_SHELL_PATTERNS = listOf(
  "&&", "||", ";", "|", "`", "\$(", "\${", ">", "<", "\n", "*", "?",
)

/**
 * Returns a non-null error message if [command] contains suspicious
 * metacharacters that could enable shell injection via chained commands.
 */
private fun validateShellCommand(command: String): String? {
  if (command.isBlank()) return "Command must not be empty"
  if (command.length > MAX_INPUT_CHARS) return "Command exceeds maximum length"
  for (pattern in DANGEROUS_SHELL_PATTERNS) {
    if (command.contains(pattern)) {
      return "Command contains disallowed shell metacharacter: '$pattern'. " +
        "Execute only a single, simple command per call."
    }
  }
  return null
}

/** Hard cap on the total serialized size of a tool result map (all keys + values).
 *  Prevents recursive tool loops from overflowing the KV cache with accumulated
 *  results. ~6000 chars ≈ ~1500–1900 tokens — leaves room for the model to respond. */
private const val MAX_TOOL_RESULT_TOTAL_CHARS = 6000

/** Truncates [text] to [MAX_OUTPUT_CHARS] with a suffix marker when trimmed. */
private fun capOutput(text: String): String {
  return if (text.length > MAX_OUTPUT_CHARS) {
    text.take(MAX_OUTPUT_CHARS) + "\n...[TRUNCATED FOR MEMORY SAFETY]"
  } else {
    text
  }
}

/**
 * Truncates [text] and, when trimming occurs, spills the full content to a temp
 * file inside `BrainBox/temp_out/` so the LLM can paginate it later via
 * `fileBoxReadLines` or `brainBoxGrep`.
 *
 * @param text The raw tool output.
 * @param spillDir A writable directory (typically `context.filesDir/clu_file_box/BrainBox`)
 *                 where overflow files are written. If null, falls back to plain [capOutput].
 * @return The (possibly truncated) text with a pointer to the spill file.
 */
private fun capOutputWithSpill(text: String, spillDir: java.io.File?): String {
  if (text.length <= MAX_OUTPUT_CHARS) return text
  if (spillDir == null) return capOutput(text)
  return try {
    val dir = java.io.File(spillDir, "BrainBox/temp_out").also { it.mkdirs() }
    val ts = System.currentTimeMillis()
    val spillFile = java.io.File(dir, "spill_${ts}.txt")
    spillFile.writeText(text, Charsets.UTF_8)
    val relPath = "BrainBox/temp_out/spill_${ts}.txt"
    text.take(MAX_OUTPUT_CHARS) +
      "\n\n[SYSTEM OVERRIDE: Output too large (${text.length} chars). " +
      "Full log saved to $relPath. Use fileBoxReadLines or brainBoxGrep to inspect further.]"
  } catch (e: Exception) {
    Log.w(TAG, "capOutputWithSpill: failed to write spill file", e)
    capOutput(text)
  }
}

/**
 * Caps the total serialized size of a tool result map. If the combined
 * key-value content exceeds [MAX_TOOL_RESULT_TOTAL_CHARS], individual values
 * are aggressively truncated proportionally to bring the total under the limit.
 */
private fun capResultMap(result: Map<String, String>): Map<String, String> {
  val totalSize = result.entries.sumOf { it.key.length + it.value.length }
  if (totalSize <= MAX_TOOL_RESULT_TOTAL_CHARS) return result

  Log.w("AgentTools", "capResultMap: total size $totalSize exceeds $MAX_TOOL_RESULT_TOTAL_CHARS — truncating values")
  val keyOverhead = result.keys.sumOf { it.length }
  val availableForValues = (MAX_TOOL_RESULT_TOTAL_CHARS - keyOverhead).coerceAtLeast(result.size * 50)
  val perValueBudget = availableForValues / result.size.coerceAtLeast(1)

  return result.mapValues { (_, v) ->
    if (v.length > perValueBudget) {
      v.take(perValueBudget) + "\n...[TRUNCATED FOR MEMORY SAFETY]"
    } else {
      v
    }
  }
}

// ── Resolution Token Formatters ────────────────────────────────────
// Every tool result map gets a "resolution" key that contains a strict,
// formatted token telling the LLM the execution loop is officially over.
// This prevents the model from re-invoking a tool on the next turn.

/** Max chars shown per value when building the resolution summary. */
private const val MAX_RESOLUTION_VALUE_LENGTH = 120

/** Template for a successful resolution token. */
private fun buildSuccessResolution(entries: Iterable<Map.Entry<String, *>>): String {
  val output = entries.joinToString(", ") { "${it.key}=${it.value.toString().take(MAX_RESOLUTION_VALUE_LENGTH)}" }
  return "[System: Tool executed successfully. Result: $output]"
}

/** Template for a failed resolution token. */
private fun buildFailureResolution(error: String): String {
  return "[System: Tool failed with error: $error. " +
    "Analyze the error, explain what went wrong to the user, and " +
    "if possible, fix the issue and retry. Do NOT stop responding.]"
}

/** Injects a `resolution` field into a success result map. */
private fun withSuccessResolution(result: Map<String, String>): Map<String, String> {
  return result + ("resolution" to buildSuccessResolution(result.entries))
}

/** Injects a `resolution` field into a failure result map. */
private fun withFailureResolution(result: Map<String, String>): Map<String, String> {
  return result + ("resolution" to buildFailureResolution(result["error"] ?: "unknown error"))
}

/** Auto-selects success or failure resolution based on the `status` field. */
private fun withResolution(result: Map<String, String>): Map<String, String> {
  val capped = capResultMap(result)
  val status = capped["status"]?.lowercase() ?: ""
  return if (status == "failed" || capped.containsKey("error")) {
    withFailureResolution(capped)
  } else {
    withSuccessResolution(capped)
  }
}

/** Overload for Map<String, Any> (used by runJs). */
@Suppress("UNCHECKED_CAST")
private fun withResolutionAny(result: Map<String, Any>): Map<String, Any> {
  // Cap string values in the result map to prevent context overflow.
  val capped = result.mapValues { (_, v) ->
    if (v is String && v.length > MAX_OUTPUT_CHARS) {
      v.take(MAX_OUTPUT_CHARS) + "\n...[TRUNCATED FOR MEMORY SAFETY]"
    } else {
      v
    }
  }
  val status = (capped["status"] as? String)?.lowercase() ?: ""
  val error = capped["error"] as? String
  val resolution = if (status == "failed" || error != null) {
    buildFailureResolution(error ?: "unknown error")
  } else {
    buildSuccessResolution(capped.entries)
  }
  return capped + ("resolution" to resolution)
}

class AgentTools() : ToolSet {
  lateinit var context: Context
  lateinit var skillManagerViewModel: SkillManagerViewModel
  var brainBoxDao: BrainBoxDao? = null

  /** Optional VectorEngine for embedding generation and semantic search. */
  var vectorEngine: VectorEngine? = null

  /** Optional reference to the MSTR_CTRL terminal session for shell tools. */
  var terminalSessionManager: com.google.ai.edge.gallery.data.TerminalSessionManager? = null

  /** Lazily initialized FileBoxManager for file workspace operations. */
  val fileBoxManager: com.google.ai.edge.gallery.data.FileBoxManager by lazy {
    com.google.ai.edge.gallery.data.FileBoxManager(context)
  }

  /** Lazily initialized OracleManager for offline .zim search. */
  val oracleManager: com.google.ai.edge.gallery.data.OracleManager by lazy {
    com.google.ai.edge.gallery.data.OracleManager(context)
  }

  /**
   * Directory used by [capOutputWithSpill] to persist large tool outputs
   * that exceed [MAX_OUTPUT_CHARS]. Sits inside the FILE_BOX root so the
   * LLM can paginate it with `fileBoxReadLines` or search with `brainBoxGrep`.
   */
  internal val spillDir: java.io.File by lazy { fileBoxManager.root }

  private val _actionChannel = Channel<AgentAction>(Channel.UNLIMITED)
  val actionChannel: ReceiveChannel<AgentAction> = _actionChannel
  var resultImageToShow: CallJsSkillResultImage? = null
  var resultWebviewToShow: CallJsSkillResultWebview? = null

  // ── Dynamic tool catalog via SkillRegistry ────────────────────────────

  /** Lazily initialised [SkillRegistry] that holds all [CluSkill] instances. */
  val skillRegistry: SkillRegistry by lazy { SkillRegistry(this) }

  /**
   * Returns a formatted multi-line string listing every native tool with its
   * description. Intended for injection into the system prompt via the
   * `___TOOLS___` placeholder. Delegates to [SkillRegistry.getToolsSummary].
   */
  fun getToolsSummary(): String = skillRegistry.getToolsSummary()

  /**
   * Returns metadata-only [CluSkill] instances for all `@Tool` methods that
   * do NOT have a dedicated [CluSkill] class (i.e. everything except
   * [ShellExecuteSkill] and [FileBoxWriteSkill]).
   *
   * These provide [name], [description], and empty schema/example fields.
   * Execution for these tools goes through the litertlm `@Tool` framework.
   */
  internal fun getMetadataOnlySkills(): List<CluSkill> {
    // Tools that already have dedicated CluSkill implementations.
    val ported = setOf("shellExecute", "fileBoxWrite", "fileBoxReadLines", "brainBoxGrep")

    data class ToolEntry(val name: String, val description: String)

    val catalog = listOf(
      ToolEntry("loadSkill", "Load skill instructions by name."),
      ToolEntry("runJs", "Run JavaScript skill script."),
      ToolEntry("runIntent", "Fire Android intent."),
      ToolEntry("queryBrain", "Search BrainBox knowledge graph."),
      ToolEntry("saveBrainNeuron", "Save memory to BrainBox."),
      ToolEntry("vectorRecall", "Semantic search: top-3 neurons by cosine similarity."),
      ToolEntry("commitMemory", "Write memory with title+synapses+ground_truth. Auto-embeds."),
      ToolEntry("workspaceMap", "JSON tree of clu_file_box. Call first to orient."),
      ToolEntry("fileBoxRead", "Read file from FILE_BOX."),
      ToolEntry("taskQueueUpdate", "Set status=pending+next_task or status=complete."),
      ToolEntry("architectInit", "Planner-Worker: commit blueprint once. Starts worker phase."),
      ToolEntry("workerExecute", "Planner-Worker: write one file, mark DONE, auto-continue."),
      ToolEntry("commandOverride", "Run terminal cmd on MSTR_CTRL. NO file creation."),
      ToolEntry("operatorHalt", "Stop autonomous loop, present reason to user."),
      ToolEntry("oracleSearch", "Search offline .zim docs for technical answers."),
      ToolEntry("gitDiffRead", "Git diff for file or workspace."),
      ToolEntry("workspaceSyncSnapshot", "Unified FILE_BOX+MSTR_CTRL state for debugging."),
      ToolEntry("editorTerminalPipe", "Pipe FILE_BOX file to MSTR_CTRL for execution."),
      ToolEntry("createSkill", "Create new custom SKILL.md. Saved permanently."),
    )

    return catalog
      .filter { it.name !in ported }
      .map { entry ->
        object : CluSkill {
          override val name = entry.name
          override val description = entry.description
          override val jsonSchema = ""
          override val fewShotExample = ""
          override suspend fun execute(args: org.json.JSONObject): String {
            return "[Error: '$name' must be invoked through the native @Tool framework, not the CluSkill router.]"
          }
        }
      }
  }

  /** Loads skill. */
  @Tool(description = "Loads a skill.")
  fun loadSkill(
    @ToolParam(description = "The name of the skill to load.") skillName: String
  ): Map<String, String> {
    return withResolution(runBlocking(Dispatchers.IO) {
      try {
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
      } catch (e: Exception) {
        Log.e(TAG, "loadSkill: unexpected error", e)
        mapOf("error" to "System Error: ${e.message ?: "unknown"}", "status" to "failed")
      }
    })
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
    return withResolutionAny(runBlocking(Dispatchers.IO) {
      try {
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
      } catch (e: Exception) {
        Log.e(TAG, "runJs: unexpected error", e)
        mapOf("error" to "System Error: ${e.message ?: "unknown"}", "status" to "failed" as Any)
      }
    })
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
    return withResolution(runBlocking(Dispatchers.IO) {
      try {
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
      } catch (e: Exception) {
        Log.e(TAG, "runIntent: unexpected error", e)
        mapOf("error" to "System Error: ${e.message ?: "unknown"}", "status" to "failed")
      }
    })
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
    return withResolution(runBlocking(Dispatchers.IO) {
      try {
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
      } catch (e: Exception) {
        Log.e(TAG, "queryBrain: unexpected error", e)
        mapOf("error" to "System Error: ${e.message ?: "unknown"}", "status" to "failed")
      }
    })
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
    return withResolution(runBlocking(Dispatchers.IO) {
      try {
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
      } catch (e: Exception) {
        Log.e(TAG, "saveBrainNeuron: unexpected error", e)
        mapOf("error" to "System Error: ${e.message ?: "unknown"}", "status" to "failed")
      }
    })
  }

  // =========================================================================
  // VECTOR_RECALL — Semantic vector search across BrainBox
  // =========================================================================

  /**
   * Embeds the [search_query], runs cosine similarity against all stored
   * neuron embeddings, and returns the top-3 most relevant neurons.
   */
  @Tool(
    description = "Semantic search: embed a query and find the top-3 most relevant neurons " +
      "in the BrainBox knowledge graph via cosine similarity. Returns the title, type, " +
      "ground_truth (content), synapses, and similarity score for each match."
  )
  fun vectorRecall(
    @ToolParam(description = "The natural-language search query to embed and match against stored memories.")
    search_query: String,
  ): Map<String, String> {
    return withResolution(runBlocking(Dispatchers.IO) {
      try {
        val dao = brainBoxDao
        val engine = vectorEngine
        if (dao == null) {
          return@runBlocking mapOf("error" to "BrainBox not available", "status" to "failed")
        }
        if (engine == null) {
          return@runBlocking mapOf("error" to "VectorEngine not available", "status" to "failed")
        }

        val query = search_query.trim()
        if (query.isBlank()) {
          return@runBlocking mapOf("error" to "search_query must not be empty", "status" to "failed")
        }

        // Embed the query.
        val queryEmbedding = engine.embed(query)

        // Fetch all neurons and filter to those with non-empty embeddings.
        val allNeurons = dao.getAllNeurons()
        val candidates = allNeurons
          .filter { it.embedding.isNotEmpty() }
          .map { it.id to it.embedding }

        if (candidates.isEmpty()) {
          // Fallback to keyword search if no embeddings exist yet.
          val q = query.lowercase()
          val keywordMatched = allNeurons.filter {
            it.label.lowercase().contains(q) || it.content.lowercase().contains(q)
          }.take(3)
          val summary = keywordMatched.joinToString(separator = "\n---\n") { n ->
            "[${n.type}] ${n.label}\nContent: ${n.content}\nSynapses: ${n.synapses}"
          }.ifEmpty { "(no neurons found)" }
          return@runBlocking mapOf(
            "result" to capOutput(summary),
            "count" to keywordMatched.size.toString(),
            "search_mode" to "keyword_fallback",
            "status" to "succeeded",
          )
        }

        // Run vector search — top 3.
        val topResults = engine.search(queryEmbedding, candidates, topK = 3)
        val neuronMap = allNeurons.associateBy { it.id }

        val summary = topResults.mapNotNull { (id, similarity) ->
          val n = neuronMap[id] ?: return@mapNotNull null
          "[${n.type}] ${n.label} (similarity: ${"%.3f".format(similarity)})\n" +
            "Content: ${n.content}\n" +
            "Synapses: ${n.synapses}\n" +
            "False Paths: ${n.falsePaths}"
        }.joinToString(separator = "\n---\n").ifEmpty { "(no matching neurons)" }

        Log.d(TAG, "vectorRecall query='$query' returned ${topResults.size} results")
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Vector_Recall: ${topResults.size} matches for \"${query.take(40)}\"",
            inProgress = false,
            addItemTitle = "Vector_Recall",
            addItemDescription = "Query: $query → ${topResults.size} results",
          )
        )

        mapOf(
          "result" to capOutput(summary),
          "count" to topResults.size.toString(),
          "search_mode" to if (engine.isMediaPipeAvailable) "mediapipe_semantic" else "bow_fallback",
          "status" to "succeeded",
        )
      } catch (e: Exception) {
        Log.e(TAG, "vectorRecall: unexpected error", e)
        mapOf("error" to "System Error: ${e.message ?: "unknown"}", "status" to "failed")
      }
    })
  }

  // =========================================================================
  // COMMIT_MEMORY — AI autonomously writes a vectorised memory
  // =========================================================================

  /**
   * Allows the AI to autonomously write a new memory to BrainBox.
   * The text is automatically embedded for future vector recall.
   */
  @Tool(
    description = "Autonomously write a memory to BrainBox. Kotlin intercepts the data, " +
      "generates a vector embedding, saves it to the database, and returns a success " +
      "resolution token. Use this to remember facts, decisions, or context."
  )
  fun commitMemory(
    @ToolParam(description = "Short, unique title for this memory (e.g. 'User prefers Python').")
    title: String,
    @ToolParam(description = "Comma-separated [[Wiki-Links]] connecting this memory to related neurons (e.g. '[[Python]],[[User_Prefs]]'). Pass empty string if none.")
    synapses: String,
    @ToolParam(description = "The verified ground truth content to store — the actual knowledge.")
    ground_truth: String,
    @ToolParam(description = "Incorrect assumptions or dead-end paths to avoid. Pass empty string if none.")
    false_paths: String,
  ): Map<String, String> {
    return withResolution(runBlocking(Dispatchers.IO) {
      try {
        val dao = brainBoxDao
        val engine = vectorEngine
        if (dao == null) {
          return@runBlocking mapOf("error" to "BrainBox not available", "status" to "failed")
        }
        if (engine == null) {
          return@runBlocking mapOf("error" to "VectorEngine not available", "status" to "failed")
        }

        val cleanTitle = title.trim()
        val cleanTruth = ground_truth.trim()
        if (cleanTitle.isBlank() || cleanTruth.isBlank()) {
          return@runBlocking mapOf(
            "error" to "title and ground_truth must not be empty",
            "status" to "failed",
          )
        }

        // Concatenate text for embedding: title + ground truth + synapses.
        val textToEmbed = "$cleanTitle $cleanTruth ${synapses.trim()}"
        val embedding = engine.embed(textToEmbed)

        // Reuse existing ID if a neuron with this title already exists.
        val existing = dao.getAllNeurons().find { it.label.equals(cleanTitle, ignoreCase = true) }
        val neuron = NeuronEntity(
          id = existing?.id ?: UUID.randomUUID().toString(),
          label = cleanTitle,
          type = "Memory",
          content = cleanTruth,
          synapses = synapses.trim(),
          falsePaths = false_paths.trim(),
          embedding = embedding,
        )
        dao.insertNeuron(neuron)

        val action = if (existing != null) "updated" else "created"
        Log.d(TAG, "commitMemory: $action neuron '$cleanTitle' (${embedding.size}-dim embedding)")

        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Commit_Memory: $action \"$cleanTitle\"",
            inProgress = false,
            addItemTitle = "Commit_Memory",
            addItemDescription = "$cleanTitle — ${cleanTruth.take(80)}…",
          )
        )

        mapOf(
          "title" to cleanTitle,
          "action" to action,
          "embedding_dims" to embedding.size.toString(),
          "status" to "succeeded",
        )
      } catch (e: Exception) {
        Log.e(TAG, "commitMemory: unexpected error", e)
        mapOf("error" to "System Error: ${e.message ?: "unknown"}", "status" to "failed")
      }
    })
  }

  // =========================================================================
  // WORKSPACE_MAP — JSON file/folder tree for AI orientation
  // =========================================================================

  /**
   * Scans the `clu_file_box` sandbox and returns a clean JSON representation
   * of the current file/folder tree. Used by the AI to orient itself before
   * reading, writing, or navigating the workspace.
   */
  @Tool(
    description = "Scans the clu_file_box workspace and returns a JSON tree of all files " +
      "and folders. Use this to orient yourself before reading or writing files."
  )
  fun workspaceMap(): Map<String, String> {
    return withResolution(runBlocking(Dispatchers.IO) {
      try {
      Log.d(TAG, "workspaceMap: scanning workspace")
      val json = fileBoxManager.workspaceMapJson()

      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Workspace_Map: scanned file tree",
          inProgress = false,
          addItemTitle = "Workspace_Map",
          addItemDescription = "Returned workspace JSON tree",
        )
      )

      mapOf(
        "workspace_tree" to json,
        "status" to "succeeded",
      )
      } catch (e: Exception) {
        Log.e(TAG, "workspaceMap: unexpected error", e)
        mapOf("error" to "System Error: ${e.message ?: "unknown"}", "status" to "failed")
      }
    })
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
    description = "The ONLY permitted tool for creating, writing, or overwriting files on disk. " +
      "Pass the relative path and the raw file content. " +
      "You can create nested folders automatically by including them in the file_path " +
      "(e.g. 'new_project/folder/file.txt'). Only text-based extensions are allowed " +
      "(e.g. .txt, .kt, .js, .json, .md, .html, .py, .ts, .css, .xml, .yaml). " +
      "NEVER use shell commands (echo, cat, nano) to write files — always use this tool."
  )
  fun fileBoxWrite(
    @ToolParam(description = "Relative path inside FILE_BOX (e.g. 'my_app/src/main.kt'). Nested directories are auto-created.")
    file_path: String,
    @ToolParam(description = "The full text/code content to write to the file.")
    content: String,
  ): Map<String, String> {
    return withResolution(runBlocking(Dispatchers.IO) {
      try {
      val safePath = file_path.trim()
      val safeContent = capInput(content)
      Log.d(TAG, "fileBoxWrite: path='$safePath' content=${safeContent.length} chars")

      validateRelativePath(safePath)?.let { err ->
        return@runBlocking mapOf("error" to err, "status" to "failed")
      }

      val mgr = fileBoxManager

      if (!mgr.isAllowedExtension(safePath)) {
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "FILE_BOX: rejected '$safePath' (extension not allowed)",
            inProgress = false,
          )
        )
        return@runBlocking mapOf(
          "error" to "Extension not allowed. Only text/code files are permitted.",
          "status" to "failed",
        )
      }

      val isNew = !java.io.File(mgr.root, safePath).exists()
      val ok = mgr.writeCodeFile(safePath, safeContent)

      if (!ok) {
        return@runBlocking mapOf("error" to "Failed to write file", "status" to "failed")
      }

      _actionChannel.send(
        SkillProgressAgentAction(
          label = "FILE_BOX: wrote '$safePath'",
          inProgress = false,
          addItemTitle = "FILE_BOX Write",
          addItemDescription = "Path: $safePath (${safeContent.length} chars)",
        )
      )

      // Phase 4: BrainBox telemetry — log new file creations as neurons.
      if (isNew) {
        val dao = brainBoxDao
        if (dao != null) {
          val neuron = NeuronEntity(
            id = UUID.randomUUID().toString(),
            label = safePath,
            type = "File_Creation_Log",
            content = safeContent,
          )
          dao.insertNeuron(neuron)
          Log.d(TAG, "fileBoxWrite: logged new file creation to BrainBox: $safePath")
        }
      }

      // ── Phase 5: Auto-Validator Interceptor (Syntax Gatekeeper) ──
      // After saving, inspect the file extension and run a syntax check.
      // If validation fails, return the error to force the LLM to debug.
      val ext = safePath.substringAfterLast('.', "").lowercase()
      val absolutePath = java.io.File(mgr.root, safePath).absolutePath
      val tsm = terminalSessionManager

      // Escape the file path for safe shell interpolation (replace ' with '\'').
      val escapedPath = absolutePath.replace("'", "'\\''")
      val validationCmd: String? = when (ext) {
        "py" -> "python -m py_compile '$escapedPath'"
        "js" -> "node --check '$escapedPath'"
        else -> null
      }

      if (validationCmd != null && tsm != null) {
        Log.d(TAG, "fileBoxWrite: running auto-validation: $validationCmd")
        val (exitCode, validationOutput) = tsm.executeCommandWithExitCode(validationCmd)
        if (exitCode != 0) {
          val errMsg = validationOutput.ifEmpty { "Unknown syntax error" }
          Log.w(TAG, "fileBoxWrite: validation FAILED for '$safePath': $errMsg")

          // Delete the rejected file to keep the sandbox clean.
          mgr.deleteFile(safePath)
          Log.d(TAG, "fileBoxWrite: deleted rejected file '$safePath'")

          _actionChannel.send(
            SkillProgressAgentAction(
              label = "FILE_BOX: syntax error in '$safePath' — file deleted",
              inProgress = false,
              addItemTitle = "Auto-Validator",
              addItemDescription = "REJECTED & DELETED: $errMsg",
            )
          )
          return@runBlocking mapOf(
            "file_path" to safePath,
            "status" to "rejected_and_deleted",
            "error" to "FILE REJECTED & DELETED due to syntax error: $errMsg. " +
              "You MUST fix the code and call fileBoxWrite again with the corrected content.",
          )
        }
        Log.d(TAG, "fileBoxWrite: validation PASSED for '$safePath'")
      }

      mapOf(
        "file_path" to safePath,
        "status" to "succeeded",
        "message" to if (validationCmd != null) "File written and validated successfully" else "File written successfully",
      )
      } catch (e: Exception) {
        Log.e(TAG, "fileBoxWrite: unexpected error", e)
        mapOf("error" to "System Error: ${e.message ?: "unknown"}", "status" to "failed")
      }
    })
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
    return withResolution(runBlocking(Dispatchers.IO) {
      try {
      val safePath = file_path.trim()
      Log.d(TAG, "fileBoxRead: path='$safePath'")

      validateRelativePath(safePath)?.let { err ->
        return@runBlocking mapOf("error" to err, "status" to "failed")
      }

      val content = fileBoxManager.readCodeFile(safePath)

      if (content == null) {
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "FILE_BOX: file not found '$safePath'",
            inProgress = false,
          )
        )
        return@runBlocking mapOf(
          "error" to "File not found or is not readable: $safePath",
          "status" to "failed",
        )
      }

      _actionChannel.send(
        SkillProgressAgentAction(
          label = "FILE_BOX: read '$safePath'",
          inProgress = false,
          addItemTitle = "FILE_BOX Read",
          addItemDescription = "Path: $safePath (${content.length} chars)",
        )
      )

      mapOf("file_path" to safePath, "content" to capOutputWithSpill(content, spillDir), "status" to "succeeded")
      } catch (e: Exception) {
        Log.e(TAG, "fileBoxRead: unexpected error", e)
        mapOf("error" to "System Error: ${e.message ?: "unknown"}", "status" to "failed")
      }
    })
  }

  // =========================================================================
  // FileBox_Read_Lines — Paginated file reader (Context Pager)
  // =========================================================================

  /**
   * Reads a specific range of lines from a file, allowing the LLM to paginate
   * through large outputs without consuming the entire context window.
   */
  @Tool(
    description = "Reads a specific chunk of a file by line numbers to save memory. " +
      "Use this if a file or log output was truncated."
  )
  fun fileBoxReadLines(
    @ToolParam(description = "Relative path inside FILE_BOX (e.g. 'BrainBox/temp_out/spill_123.txt').")
    file_path: String,
    @ToolParam(description = "0-based start line number (inclusive).")
    start_line: Int,
    @ToolParam(description = "0-based end line number (exclusive).")
    end_line: Int,
  ): Map<String, String> {
    return withResolution(runBlocking(Dispatchers.IO) {
      try {
        val safePath = file_path.trim()
        Log.d(TAG, "fileBoxReadLines: path='$safePath' lines=$start_line..$end_line")

        validateRelativePath(safePath)?.let { err ->
          return@runBlocking mapOf("error" to err, "status" to "failed")
        }

        val content = fileBoxManager.readCodeFile(safePath)

        if (content == null) {
          return@runBlocking mapOf(
            "error" to "File not found or not readable: $safePath",
            "status" to "failed",
          )
        }

        val allLines = content.lines()
        val safeStart = start_line.coerceIn(0, allLines.size)
        val safeEnd = end_line.coerceIn(safeStart, allLines.size)
        val chunk = allLines.subList(safeStart, safeEnd).joinToString("\n")

        _actionChannel.send(
          SkillProgressAgentAction(
            label = "FILE_BOX: read lines $safeStart–$safeEnd of '$safePath'",
            inProgress = false,
          )
        )

        mapOf(
          "file_path" to safePath,
          "start_line" to safeStart.toString(),
          "end_line" to safeEnd.toString(),
          "total_lines" to allLines.size.toString(),
          "content" to capOutput(chunk),
          "status" to "succeeded",
        )
      } catch (e: Exception) {
        Log.e(TAG, "fileBoxReadLines: unexpected error", e)
        mapOf("error" to "System Error: ${e.message ?: "unknown"}", "status" to "failed")
      }
    })
  }

  // =========================================================================
  // BrainBox_Grep — Keyword search with context lines
  // =========================================================================

  /**
   * Scans a file for a keyword and returns matching lines with ±2 lines of
   * surrounding context, similar to `grep -C 2`.
   */
  @Tool(
    description = "Searches a file for a keyword or error code and returns the matching lines " +
      "plus 2 lines of context above and below each match."
  )
  fun brainBoxGrep(
    @ToolParam(description = "Relative path inside FILE_BOX (e.g. 'BrainBox/temp_out/spill_123.txt').")
    file_path: String,
    @ToolParam(description = "The keyword or error string to search for (case-insensitive).")
    keyword: String,
  ): Map<String, String> {
    return withResolution(runBlocking(Dispatchers.IO) {
      try {
        val safePath = file_path.trim()
        val safeKeyword = keyword.trim()
        Log.d(TAG, "brainBoxGrep: path='$safePath' keyword='$safeKeyword'")

        validateRelativePath(safePath)?.let { err ->
          return@runBlocking mapOf("error" to err, "status" to "failed")
        }
        if (safeKeyword.isBlank()) {
          return@runBlocking mapOf("error" to "keyword must not be empty", "status" to "failed")
        }

        val content = fileBoxManager.readCodeFile(safePath)

        if (content == null) {
          return@runBlocking mapOf(
            "error" to "File not found or not readable: $safePath",
            "status" to "failed",
          )
        }

        val allLines = content.lines()
        val lowerKeyword = safeKeyword.lowercase()
        val contextRadius = 2

        // Collect indices of matching lines.
        val matchIndices = allLines.indices.filter {
          allLines[it].lowercase().contains(lowerKeyword)
        }

        if (matchIndices.isEmpty()) {
          return@runBlocking mapOf(
            "file_path" to safePath,
            "keyword" to safeKeyword,
            "matches" to "0",
            "result" to "No matches found for '$safeKeyword'.",
            "status" to "succeeded",
          )
        }

        // Build context windows around each match, merging overlaps.
        val included = mutableSetOf<Int>()
        for (idx in matchIndices) {
          for (i in (idx - contextRadius)..(idx + contextRadius)) {
            if (i in allLines.indices) included.add(i)
          }
        }

        val result = included.sorted().joinToString("\n") { i ->
          val marker = if (i in matchIndices) ">>>" else "   "
          "$marker ${i + 1}: ${allLines[i]}"
        }

        _actionChannel.send(
          SkillProgressAgentAction(
            label = "GREP: ${matchIndices.size} matches for '$safeKeyword' in '$safePath'",
            inProgress = false,
          )
        )

        mapOf(
          "file_path" to safePath,
          "keyword" to safeKeyword,
          "matches" to matchIndices.size.toString(),
          "result" to capOutput(result),
          "status" to "succeeded",
        )
      } catch (e: Exception) {
        Log.e(TAG, "brainBoxGrep: unexpected error", e)
        mapOf("error" to "System Error: ${e.message ?: "unknown"}", "status" to "failed")
      }
    })
  }

  // =========================================================================
  // Task Queue — Autonomous supervisor loop
  // =========================================================================

  /**
   * Thread-safe container for the next pending task description.
   * Uses [AtomicReference] so concurrent reads/writes from the inference
   * loop and tool callbacks never race.  Read with [getPendingTask] and
   * write with [setPendingTask] or the [AtomicReference] API directly.
   */
  private val _pendingTask = AtomicReference<String?>(null)

  /** Public accessor used by the ViewModel's inference loop. */
  var pendingTaskDescription: String?
    get() = _pendingTask.get()
    set(value) { _pendingTask.set(value) }

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
    return withResolution(runBlocking(Dispatchers.IO) {
      try {
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
      } catch (e: Exception) {
        Log.e(TAG, "taskQueueUpdate: unexpected error", e)
        mapOf("error" to "System Error: ${e.message ?: "unknown"}", "status" to "failed")
      }
    })
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
    return withResolution(runBlocking(Dispatchers.IO) {
      try {
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
      } catch (e: Exception) {
        Log.e(TAG, "architectInit: unexpected error", e)
        pendingTaskDescription = null  // Circuit breaker: clear queue on fatal error.
        mapOf("error" to "System Error: ${e.message ?: "unknown"}", "status" to "failed")
      }
    })
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
    return withResolution(runBlocking(Dispatchers.IO) {
      try {
      val safePath = target_file_path.trim()
      val safeContent = capInput(code_content)
      val finished = is_project_finished.trim().lowercase() == "true"
      Log.d(TAG, "workerExecute: path='$safePath', finished=$finished, content=${safeContent.length} chars")

      validateRelativePath(safePath)?.let { err ->
        return@runBlocking mapOf("error" to err, "status" to "failed")
      }

      // 1. Write the file via FileBoxManager.
      val mgr = fileBoxManager
      if (!mgr.isAllowedExtension(safePath)) {
        return@runBlocking mapOf(
          "error" to "Extension not allowed for '$safePath'.",
          "status" to "failed",
        )
      }
      val isNew = !java.io.File(mgr.root, safePath).exists()
      val ok = mgr.writeCodeFile(safePath, safeContent)
      if (!ok) {
        return@runBlocking mapOf("error" to "Failed to write $safePath", "status" to "failed")
      }

      // BrainBox telemetry for new files.
      if (isNew) {
        brainBoxDao?.insertNeuron(
          NeuronEntity(
            id = UUID.randomUUID().toString(),
            label = safePath,
            type = "File_Creation_Log",
            content = safeContent,
          )
        )
      }

      // 2. Read and update blueprint.md — mark target file as DONE.
      val blueprint = mgr.readCodeFile(BLUEPRINT_PATH)
      if (blueprint != null) {
        // Replace the first occurrence of "- [ ] target_file_path" with "- [x] target_file_path"
        val updatedBlueprint = blueprint.replaceFirst(
          "- [ ] $safePath",
          "- [x] $safePath",
        )
        mgr.writeCodeFile(BLUEPRINT_PATH, updatedBlueprint)
      }

      _actionChannel.send(
        SkillProgressAgentAction(
          label = if (finished) "Worker: project complete ✓" else "Worker: wrote '$safePath'",
          inProgress = !finished,
          addItemTitle = "Worker_Execute",
          addItemDescription = "Path: $safePath (${safeContent.length} chars)" +
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
        "file_path" to safePath,
        "is_project_finished" to finished.toString(),
        "status" to "succeeded",
      )
      } catch (e: Exception) {
        Log.e(TAG, "workerExecute: unexpected error", e)
        pendingTaskDescription = null  // Circuit breaker: clear queue on fatal error.
        mapOf("error" to "System Error: ${e.message ?: "unknown"}", "status" to "failed")
      }
    })
  }

  // =========================================================================
  // SHELL_EXECUTE — Run terminal commands invisibly and return raw output
  // =========================================================================

  /**
   * Executes a shell command and returns the combined stdout + stderr output.
   * Pipes through [TerminalSessionManager] when available (invisible mode —
   * output is NOT printed to the MstrCtrlScreen). Falls back to the standalone
   * [executeCommand] if the terminal session is not wired.
   *
   * A strict 10-second timeout is enforced.
   */
  @Tool(
    description = "Executes bash commands in the Termux environment. STRICTLY PROHIBITED FOR FILE CREATION OR EDITING. " +
      "Use only to run test scripts, check directory listings, execute programs, or debug stack traces. " +
      "You will receive the raw terminal output. To create or modify files, you MUST use fileBoxWrite instead."
  )
  fun shellExecute(
    @ToolParam(description = "The shell command to execute (e.g. 'ls -la', 'python3 test.py', 'git status'). NEVER use echo/cat/nano/tee/sed to write files.")
    command: String,
  ): Map<String, String> {
    return withResolution(runBlocking(Dispatchers.IO) {
      try {
      val safeCmd = command.trim()
      Log.d(TAG, "shellExecute: command='$safeCmd'")

      validateShellCommand(safeCmd)?.let { err ->
        return@runBlocking mapOf("error" to err, "status" to "failed")
      }

      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Shell: executing command…",
          inProgress = true,
          addItemTitle = "Shell_Execute",
          addItemDescription = "$ $safeCmd",
        )
      )

      val tsm = terminalSessionManager
      val output = if (tsm != null) {
        tsm.sendCommand(safeCmd, visible = false)
      } else {
        com.google.ai.edge.gallery.data.executeCommand(safeCmd)
      }

      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Shell: command finished",
          inProgress = false,
          addItemTitle = "Shell_Execute",
          addItemDescription = "$ $safeCmd\n${output.take(200)}${if (output.length > 200) "…" else ""}",
        )
      )

      val status = when {
        output == "TIMEOUT ERROR" -> "timeout"
        output.startsWith("ERROR:") -> "error"
        else -> "succeeded"
      }

      mapOf(
        "command" to safeCmd,
        "output" to capOutputWithSpill(output, spillDir),
        "status" to status,
      )
      } catch (e: SecurityException) {
        Log.e(TAG, "shellExecute: W^X / SELinux block", e)
        mapOf(
          "error" to "[System: Terminal execution blocked by Android OS Security (W^X). Fallback required.]",
          "status" to "failed",
        )
      } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "shellExecute: native library load failed", e)
        mapOf(
          "error" to "[System: Terminal native library unavailable. Shell execution disabled.]",
          "status" to "failed",
        )
      } catch (e: Exception) {
        Log.e(TAG, "shellExecute: unexpected error", e)
        mapOf("error" to "System Error: ${e.message ?: "unknown"}", "status" to "failed")
      }
    })
  }

  // =========================================================================
  // COMMAND_OVERRIDE — Run terminal commands visibly on MstrCtrlScreen
  // =========================================================================

  /**
   * Same as [shellExecute] but explicitly prints the AI's input and output
   * visibly onto the MstrCtrlScreen so the user can watch the AI work in
   * real time.
   */
  @Tool(
    description = "Execute a terminal command and display both input and output visibly on the " +
      "MSTR_CTRL terminal screen so the user can watch in real time. STRICTLY PROHIBITED FOR FILE CREATION OR EDITING. " +
      "Use this when you want the user to see what you are doing. You will also receive the raw output."
  )
  fun commandOverride(
    @ToolParam(description = "The shell command to execute visibly (e.g. 'git status', 'npm test'). NEVER use echo/cat/nano/tee/sed to write files.")
    command: String,
  ): Map<String, String> {
    return withResolution(runBlocking(Dispatchers.IO) {
      try {
      val safeCmd = command.trim()
      Log.d(TAG, "commandOverride: command='$safeCmd'")

      validateShellCommand(safeCmd)?.let { err ->
        return@runBlocking mapOf("error" to err, "status" to "failed")
      }

      _actionChannel.send(
        SkillProgressAgentAction(
          label = "MSTR_CTRL: running visible command…",
          inProgress = true,
          addItemTitle = "Command_Override",
          addItemDescription = "$ $safeCmd",
        )
      )

      val tsm = terminalSessionManager
      val output = if (tsm != null) {
        tsm.sendCommand(safeCmd, visible = true)
      } else {
        // Fallback: run silently if terminal session is not available.
        com.google.ai.edge.gallery.data.executeCommand(safeCmd)
      }

      _actionChannel.send(
        SkillProgressAgentAction(
          label = "MSTR_CTRL: command complete",
          inProgress = false,
          addItemTitle = "Command_Override",
          addItemDescription = "$ $safeCmd\n${output.take(200)}${if (output.length > 200) "…" else ""}",
        )
      )

      val status = when {
        output == "TIMEOUT ERROR" -> "timeout"
        output.startsWith("ERROR:") -> "error"
        else -> "succeeded"
      }

      mapOf(
        "command" to safeCmd,
        "output" to capOutputWithSpill(output, spillDir),
        "status" to status,
      )
      } catch (e: SecurityException) {
        Log.e(TAG, "commandOverride: W^X / SELinux block", e)
        mapOf(
          "error" to "[System: Terminal execution blocked by Android OS Security (W^X). Fallback required.]",
          "status" to "failed",
        )
      } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "commandOverride: native library load failed", e)
        mapOf(
          "error" to "[System: Terminal native library unavailable. Shell execution disabled.]",
          "status" to "failed",
        )
      } catch (e: Exception) {
        Log.e(TAG, "commandOverride: unexpected error", e)
        mapOf("error" to "System Error: ${e.message ?: "unknown"}", "status" to "failed")
      }
    })
  }

  // =========================================================================
  // OPERATOR_HALT — Suspend the autonomous loop for human review
  // =========================================================================

  /**
   * Immediately suspends the ChatViewModel's autonomous worker loop and
   * outputs the [reason] to the user UI. Used when the AI completes a
   * major milestone or hits a wall and requires the Operator (Flynn) to
   * review the codebase or issue the next directive.
   */
  @Tool(
    description = "Immediately stops the autonomous work loop and presents the reason " +
      "to the user. Use this when you complete a major milestone, need clarification, " +
      "or hit a wall that requires human review."
  )
  fun operatorHalt(
    @ToolParam(description = "A clear explanation of why you are stopping (milestone reached, blocker, need user decision, etc.).")
    reason: String,
  ): Map<String, String> {
    return withResolution(runBlocking(Dispatchers.IO) {
      try {
      Log.d(TAG, "operatorHalt: reason='$reason'")

      // Clear any pending task to break the autonomous loop.
      pendingTaskDescription = null

      _actionChannel.send(
        SkillProgressAgentAction(
          label = "⛔ OPERATOR HALT",
          inProgress = false,
          addItemTitle = "Operator_Halt",
          addItemDescription = reason,
        )
      )

      mapOf(
        "reason" to reason,
        "status" to "halted",
      )
      } catch (e: Exception) {
        Log.e(TAG, "operatorHalt: unexpected error", e)
        mapOf("error" to "System Error: ${e.message ?: "unknown"}", "status" to "failed")
      }
    })
  }

  // =========================================================================
  // ORACLE_SEARCH — Offline .zim archive search (token-capped)
  // =========================================================================

  /**
   * Searches offline .zim archives (StackOverflow, docs) and returns
   * token-optimized Markdown results. Capped to ~1500 tokens to keep
   * the LLM context lean.
   */
  @Tool(
    description = "Search offline documentation archives (.zim) for answers to technical " +
      "questions. Returns token-optimized Markdown results. Use this to look up APIs, " +
      "error messages, or programming concepts without internet access."
  )
  fun oracleSearch(
    @ToolParam(description = "The search query (e.g. 'Python list comprehension', 'Kotlin coroutines', 'git rebase').")
    query: String,
  ): Map<String, String> {
    return withResolution(runBlocking(Dispatchers.IO) {
      try {
      Log.d(TAG, "oracleSearch: query='$query'")

      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Oracle: searching '$query'…",
          inProgress = true,
          addItemTitle = "Oracle_Search",
          addItemDescription = "Query: $query",
        )
      )

      val result = oracleManager.search(query)

      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Oracle: search complete",
          inProgress = false,
          addItemTitle = "Oracle_Search",
          addItemDescription = "Query: $query\n${result.take(200)}${if (result.length > 200) "…" else ""}",
        )
      )

      mapOf(
        "query" to query,
        "result" to capOutput(result),
        "status" to "succeeded",
      )
      } catch (e: Exception) {
        Log.e(TAG, "oracleSearch: unexpected error", e)
        mapOf("error" to "System Error: ${e.message ?: "unknown"}", "status" to "failed")
      }
    })
  }

  // =========================================================================
  // GIT_DIFF_READ — Context-saving unified diff output
  // =========================================================================

  /**
   * Returns the output of `git diff --unified=0` for a specific file path
   * (or the entire workspace if path is empty). Uses minimal context to
   * save token space.
   */
  @Tool(
    description = "Returns the git diff for a file or the entire workspace. Uses " +
      "--unified=0 to minimize context and save tokens. Use this to review code changes."
  )
  fun gitDiffRead(
    @ToolParam(description = "Relative file path to diff (e.g. 'src/main.py'). Pass empty string for full workspace diff.")
    path: String,
  ): Map<String, String> {
    return withResolution(runBlocking(Dispatchers.IO) {
      try {
      val safePath = path.trim()
      Log.d(TAG, "gitDiffRead: path='$safePath'")

      // Block path-traversal when a specific file is requested.
      if (safePath.isNotBlank() && safePath.contains("..")) {
        return@runBlocking mapOf("error" to "Path must not contain '..'", "status" to "failed")
      }

      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Git Diff: reading changes…",
          inProgress = true,
          addItemTitle = "Git_Diff_Read",
          addItemDescription = "Path: ${safePath.ifEmpty { "(full workspace)" }}",
        )
      )

      val tsm = terminalSessionManager
      val cmd = if (safePath.isBlank()) {
        "git diff --unified=0 2>&1"
      } else {
        val escapedPath = safePath.replace("'", "'\\''")
        "git diff --unified=0 -- '$escapedPath' 2>&1"
      }

      val output = if (tsm != null) {
        tsm.sendCommand(cmd, visible = false)
      } else {
        com.google.ai.edge.gallery.data.executeCommand(cmd)
      }

      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Git Diff: done",
          inProgress = false,
          addItemTitle = "Git_Diff_Read",
          addItemDescription = "Path: ${safePath.ifEmpty { "(full workspace)" }}\n${output.take(200)}${if (output.length > 200) "…" else ""}",
        )
      )

      val status = when {
        output.contains("not a git repository") -> "not_git"
        output == "TIMEOUT ERROR" -> "timeout"
        output.startsWith("ERROR:") -> "error"
        output.isBlank() || output == "(no output)" -> "no_changes"
        else -> "succeeded"
      }

      mapOf(
        "path" to safePath,
        "diff" to capOutput(output),
        "status" to status,
      )
      } catch (e: Exception) {
        Log.e(TAG, "gitDiffRead: unexpected error", e)
        mapOf("error" to "System Error: ${e.message ?: "unknown"}", "status" to "failed")
      }
    })
  }

  fun sendAgentAction(action: AgentAction) {
    runBlocking(Dispatchers.IO) { _actionChannel.send(action) }
  }

  // =========================================================================
  // WORKSPACE_SYNC_SNAPSHOT — Unified editor + terminal state for the AI
  // =========================================================================

  /**
   * Returns a JSON snapshot unifying the FILE_BOX editor state and the
   * MSTR_CTRL terminal state, allowing the AI to correlate terminal errors
   * with the exact file and line it is looking at.
   */
  @Tool(
    description = "Returns a unified snapshot of the FILE_BOX editor and MSTR_CTRL terminal " +
      "state. Use this to correlate terminal errors with the file/line currently open in the " +
      "editor. Returns current_file, cursor_line, terminal_cwd, and terminal_last_output."
  )
  fun workspaceSyncSnapshot(): Map<String, String> {
    return withResolution(runBlocking(Dispatchers.IO) {
      try {
      Log.d(TAG, "workspaceSyncSnapshot")

      val mgr = fileBoxManager
      val tsm = terminalSessionManager

      val currentFile = mgr.currentFilePath.value ?: "(none)"
      val cursorLine = mgr.cursorLine.value.toString()
      val terminalCwd = tsm?.sandboxRoot?.absolutePath ?: "(unknown)"

      // Grab last non-empty output lines from the terminal buffer.
      val lastOutput = tsm?.outputLines?.value
        ?.takeLast(5)
        ?.joinToString("\n") { it.text }
        ?.ifEmpty { "(no recent output)" }
        ?: "(terminal not active)"

      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Sync Snapshot: $currentFile:$cursorLine",
          inProgress = false,
          addItemTitle = "Workspace_Sync_Snapshot",
          addItemDescription = "File: $currentFile, Line: $cursorLine",
        )
      )

      mapOf(
        "current_file" to currentFile,
        "cursor_line" to cursorLine,
        "terminal_cwd" to terminalCwd,
        "terminal_last_output" to capOutput(lastOutput),
        "status" to "succeeded",
      )
      } catch (e: Exception) {
        Log.e(TAG, "workspaceSyncSnapshot: unexpected error", e)
        mapOf("error" to "System Error: ${e.message ?: "unknown"}", "status" to "failed")
      }
    })
  }

  // =========================================================================
  // EDITOR_TERMINAL_PIPE — Run the current editor file in the terminal
  // =========================================================================

  /**
   * High-speed execution loop: pipes the file currently open in the FILE_BOX
   * editor directly into the MSTR_CTRL shell. Captures stdout/stderr and, if
   * an error occurs, extracts the offending line number so the AI can refocus.
   */
  @Tool(
    description = "Pipes the file currently open in FILE_BOX into the MSTR_CTRL terminal " +
      "for execution. Automatically detects the runtime (python3, node, sh) from the file " +
      "extension. Returns stdout/stderr output and, on error, the error_line number so you " +
      "can refocus the editor."
  )
  fun editorTerminalPipe(
    @ToolParam(description = "Optional override file path. If empty, uses the file currently open in the editor.")
    file_path: String,
  ): Map<String, String> {
    return withResolution(runBlocking(Dispatchers.IO) {
      try {
      val mgr = fileBoxManager
      val tsm = terminalSessionManager

      // Determine file to run.
      val targetPath = file_path.trim().ifEmpty {
        mgr.currentFilePath.value
      }

      if (targetPath.isNullOrBlank()) {
        return@runBlocking mapOf(
          "error" to "No file is currently open in the editor and no file_path was provided.",
          "status" to "failed",
        )
      }

      val absolutePath = java.io.File(mgr.root, targetPath).absolutePath
      val ext = targetPath.substringAfterLast('.', "").lowercase()

      // Auto-detect runtime from extension.
      val runtime = when (ext) {
        "py" -> "python3"
        "js" -> "node"
        "sh", "bash" -> "sh"
        "rb" -> "ruby"
        "lua" -> "lua"
        else -> null
      }

      if (runtime == null) {
        return@runBlocking mapOf(
          "error" to "No known runtime for '.$ext' files. Supported: .py, .js, .sh, .bash, .rb, .lua",
          "status" to "failed",
        )
      }

      // POSIX single-quote escaping: replace each literal ' with the sequence '\''.
      // This closes the current single-quoted string, appends an escaped literal
      // single-quote (\'), then reopens a new single-quoted string — a standard
      // POSIX shell idiom for safely quoting arbitrary file paths.
      val escapedPath = absolutePath.replace("'", "'\\''")
      val cmd = "$runtime '$escapedPath' 2>&1"

      Log.d(TAG, "editorTerminalPipe: $cmd")

      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Pipe: $runtime $targetPath",
          inProgress = true,
          addItemTitle = "Editor_Terminal_Pipe",
          addItemDescription = "$ $cmd",
        )
      )

      val output = if (tsm != null) {
        tsm.sendCommand(cmd, visible = true)
      } else {
        com.google.ai.edge.gallery.data.executeCommand(cmd)
      }

      // Try to extract error line number from common error output patterns.
      // Python: "File "...", line 42"   Node: "path:42:"   Shell: "line 42:"
      // Anchored to 'line' keyword or file-path-like context to reduce false positives.
      val errorLineRegex = Regex(
        """(?:line\s+(\d+)|(?:[a-zA-Z0-9_./-]+):(\d+):\d*\s)""",
        RegexOption.IGNORE_CASE,
      )
      val errorLineMatch = errorLineRegex.find(output)
      val errorLine = errorLineMatch?.let { m ->
        m.groupValues[1].ifEmpty { m.groupValues[2] }
      } ?: ""

      val hasError = output.contains("Error", ignoreCase = true) ||
        output.contains("Traceback", ignoreCase = true) ||
        output.contains("SyntaxError", ignoreCase = true) ||
        output == "TIMEOUT ERROR"

      _actionChannel.send(
        SkillProgressAgentAction(
          label = if (hasError) "Pipe: error in $targetPath${if (errorLine.isNotEmpty()) " line $errorLine" else ""}"
                  else "Pipe: $targetPath executed OK",
          inProgress = false,
          addItemTitle = "Editor_Terminal_Pipe",
          addItemDescription = "$ $cmd\n${output.take(200)}${if (output.length > 200) "…" else ""}",
        )
      )

      val result = mutableMapOf(
        "file_path" to targetPath,
        "runtime" to runtime,
        "output" to capOutputWithSpill(output, spillDir),
        "status" to if (hasError) "error" else "succeeded",
      )
      if (errorLine.isNotEmpty()) {
        result["error_line"] = errorLine
        result["action_hint"] = "Refocus Editor on Line $errorLine"
      }

      result
      } catch (e: Exception) {
        Log.e(TAG, "editorTerminalPipe: unexpected error", e)
        mapOf("error" to "System Error: ${e.message ?: "unknown"}", "status" to "failed")
      }
    })
  }

  // =========================================================================
  // CREATE_SKILL — Let the LLM author its own skills
  // =========================================================================

  /**
   * Allows the LLM to create a new custom skill by writing a SKILL.md
   * (name, description, instructions) through the existing skill manager.
   * The new skill is immediately persisted and available for future sessions.
   */
  @Tool(
    description = "Create a new custom skill for CLU/BOX. Write a SKILL.md with a name, " +
      "description, and freeform instructions that teach the AI how to perform a task. " +
      "The skill is saved permanently and can be loaded with load_skill in future sessions."
  )
  fun createSkill(
    @ToolParam(description = "Short, kebab-case skill name (e.g. 'code-reviewer', 'daily-planner').")
    skill_name: String,
    @ToolParam(description = "One-line description of what the skill does.")
    skill_description: String,
    @ToolParam(description = "Full freeform instructions (markdown). Describe examples, steps, " +
      "which native tools to call, constraints, and output format. This is what the AI reads " +
      "when the skill is loaded.")
    skill_instructions: String,
  ): Map<String, String> {
    return withResolution(runBlocking(Dispatchers.IO) {
      try {
        Log.d(TAG, "createSkill: name='$skill_name'")

        val name = skill_name.trim()
        val description = skill_description.trim()
        val instructions = skill_instructions.trim()

        if (name.isBlank() || description.isBlank() || instructions.isBlank()) {
          return@runBlocking mapOf(
            "error" to "skill_name, skill_description, and skill_instructions must all be non-empty.",
            "status" to "failed",
          )
        }

        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Creating skill \"$name\"…",
            inProgress = true,
            addItemTitle = "Create_Skill",
            addItemDescription = "$name — $description",
          )
        )

        // Use a blocking latch so we can surface success/failure synchronously.
        val resultRef = AtomicReference<Map<String, String>>(null)
        val latch = java.util.concurrent.CountDownLatch(1)

        skillManagerViewModel.saveSkillEdit(
          index = -1, // negative index = create new skill
          name = name,
          description = description,
          instructions = instructions,
          scriptsContent = emptyMap(), // no JS scripts — instruction-only skill
          onSuccess = {
            resultRef.set(
              mapOf(
                "skill_name" to name,
                "message" to "Skill '$name' created successfully. It is now available in the skill list and can be loaded with load_skill.",
                "status" to "succeeded",
              )
            )
            latch.countDown()
          },
          onError = { error ->
            resultRef.set(
              mapOf(
                "error" to error,
                "status" to "failed",
              )
            )
            latch.countDown()
          },
        )

        // Wait up to 10 seconds for the skill manager to finish writing.
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)

        val result = resultRef.get() ?: mapOf(
          "error" to "Skill creation timed out.",
          "status" to "failed",
        )

        val succeeded = result["status"] == "succeeded"
        _actionChannel.send(
          SkillProgressAgentAction(
            label = if (succeeded) "Skill \"$name\" created" else "Failed to create skill \"$name\"",
            inProgress = false,
            addItemTitle = "Create_Skill",
            addItemDescription = if (succeeded) "$name — $description" else result["error"] ?: "unknown error",
          )
        )

        result
      } catch (e: Exception) {
        Log.e(TAG, "createSkill: unexpected error", e)
        mapOf("error" to "System Error: ${e.message ?: "unknown"}", "status" to "failed")
      }
    })
  }
}

fun getSkillSecretKey(skillName: String): String {
  return "skill___${skillName}"
}
