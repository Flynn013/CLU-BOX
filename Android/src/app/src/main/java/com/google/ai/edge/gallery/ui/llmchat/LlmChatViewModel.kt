/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.TokenMonitor
import com.google.ai.edge.gallery.data.brainbox.BrainBoxDao
import com.google.ai.edge.gallery.data.brainbox.ChatHistoryDao
import com.google.ai.edge.gallery.data.brainbox.ChatMessageEntity
import com.google.ai.edge.gallery.data.brainbox.NeuronEntity
import com.google.ai.edge.gallery.data.brainbox.VectorEngine
import com.google.ai.edge.gallery.runtime.runtimeHelper
import java.io.File
import java.util.Collections
import java.util.UUID
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageAudioClip
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageError
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageLoading
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageThinking
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.ChatViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ToolProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGLlmChatViewModel"

/** Maximum number of primary neurons injected as RAG context. */
private const val MAX_PRIMARY_NEURONS = 3

/** Maximum number of synapse-linked neurons fetched during graph traversal. */
private const val MAX_LINKED_NEURONS = 2

/** Minimum word length for keyword extraction from user input. */
private const val MIN_KEYWORD_LENGTH = 4

/** Maximum number of keywords extracted from user input for BrainBox search. */
private const val MAX_SEARCH_KEYWORDS = 3

// ── Phase 7: Context Auto-Compression thresholds ────────────────────────
/**
 * Maximum number of chat turns (USER + AGENT text messages) before the
 * auto-compression engine fires. This is a fallback — the primary trigger
 * is the [TokenMonitor] 80% threshold.
 */
private const val CONTEXT_COMPRESSION_TURN_THRESHOLD = 15

/**
 * When auto-compression fires, the system silently asks the LLM to produce
 * a compressed summary using this system override prompt.
 */
private const val COMPRESSION_SYSTEM_OVERRIDE =
  "CRITICAL: Summarize our current project state, active variables, file paths created, " +
  "and current goal into a dense, compressed technical format. Use bullet points. " +
  "Include ALL file paths and their purpose. Include any unfinished tasks. " +
  "Do NOT include pleasantries or filler — only technical state."

/**
 * The Snapshot prompt used when the TokenMonitor breaches the 80% critical threshold.
 * This produces a structured Session_Resume block with exactly three sections.
 */
private const val SNAPSHOT_PROMPT =
  "CRITICAL: Context limit reached. Synthesize the current workspace status, " +
  "recent breakthroughs, and the immediate pending objective into a 500-token " +
  "Markdown 'Session_Resume' block. Format as:\n" +
  "[[STATE]]\n<workspace status, files, variables>\n" +
  "[[LOGS]]\n<recent breakthroughs and completed actions>\n" +
  "[[NEXT_STEP]]\n<the immediate pending objective>"

/** Relative path inside FILE_BOX for the current resume snapshot. */
private const val RESUME_FILE_PATH = "BrainBox/resume_current.md"

/** Max chars to include when previewing a user message during compression. */
private const val MAX_USER_MESSAGE_PREVIEW_LENGTH = 200

/** Max chars to include when previewing the last agent response during compression. */
private const val MAX_AGENT_RESPONSE_PREVIEW_LENGTH = 500

// ── Tool-call stream buffering tokens ──────────────────────────────
/** Opening delimiter emitted by the LLM when it wants to invoke a tool. */
private const val TOOL_CALL_OPEN_TAG = "<|tool_call>"

/**
 * Approximate characters per SentencePiece token for Gemma models.
 * Used for the pre-flight token clamp to convert token counts to char limits.
 * Gemma SentencePiece averages ~3.2 chars/token for mixed English/code text.
 */
private const val APPROX_CHARS_PER_TOKEN = 3.2

/** Closing delimiters that signal the tool call payload is complete. */
private val TOOL_CALL_CLOSE_TAGS = listOf("</tool_call>", "<end_of_turn>")

/**
 * Regex patterns that detect garbled / broken tool-call tokens emitted by the
 * model when constrained decoding or tokenisation corrupts the output.
 * Common examples: `<|"|>`, `<|'|>`, `<||>`, `<| |>`, `<|tool_`.
 * These patterns are checked against the running output accumulator so we
 * can halt the model early rather than letting it spiral into garbage.
 */
private val GARBLED_TOOL_TOKEN_PATTERN = Regex("""<\|["'|  ].*?\|?>""")

/** Replacement text injected when a broken tool call is scrubbed from history. */
private const val TOOL_CALL_SCRUB_NOTICE =
  "[System: Tool execution interrupted or malformed. Proceed with standard text.]"

/** Cancellation token force-appended by the circuit breaker when an unresolved
 *  tool call is detected at the start of a new user turn. */
private const val TOOL_CALL_CIRCUIT_BREAKER_NOTICE =
  "[System: Previous tool execution forcefully aborted by new user input. Clear active tasks.]"

// ── Command Buffer (Message Queue) ─────────────────────────────────
/** Maximum number of commands that can be buffered while inference is running. */
private const val MAX_COMMAND_QUEUE_SIZE = 10

/**
 * A pending user command that was submitted while inference was already in progress.
 * Commands are drained sequentially once the current inference completes.
 */
data class QueuedCommand(
  val model: Model,
  val input: String,
  val taskId: String = "",
  val images: List<Bitmap> = listOf(),
  val audioMessages: List<ChatMessageAudioClip> = listOf(),
  val onFirstToken: (Model) -> Unit = {},
  val onDone: () -> Unit = {},
  val onError: (String) -> Unit = {},
  val allowThinking: Boolean = false,
)

/**
 * Scrubs incomplete or malformed tool-call blocks from [text] before it is
 * persisted to chat history, BrainBox, or Session_Resume.
 *
 * A tool-call block is considered **valid** when it contains both [TOOL_CALL_OPEN_TAG]
 * and one of the [TOOL_CALL_CLOSE_TAGS]. If an opening tag is found without a
 * matching close, the broken fragment is replaced with [TOOL_CALL_SCRUB_NOTICE].
 */
internal fun sanitizeToolCallArtifacts(text: String): String {
  if (!text.contains(TOOL_CALL_OPEN_TAG)) return text

  val result = StringBuilder()
  var searchFrom = 0

  while (searchFrom < text.length) {
    val openIdx = text.indexOf(TOOL_CALL_OPEN_TAG, searchFrom)
    if (openIdx == -1) {
      // No more open tags — append the rest verbatim.
      result.append(text, searchFrom, text.length)
      break
    }

    // Append everything before this open tag.
    result.append(text, searchFrom, openIdx)

    // Look for the matching close tag.
    val afterOpen = openIdx + TOOL_CALL_OPEN_TAG.length
    val closeMatch = TOOL_CALL_CLOSE_TAGS
      .map { tag -> tag to text.indexOf(tag, afterOpen) }
      .filter { it.second != -1 }
      .minByOrNull { it.second }

    if (closeMatch != null) {
      // Valid block — the tool framework already executed it; drop the raw
      // tags from the persisted history so they don't confuse future context.
      val closeEnd = closeMatch.second + closeMatch.first.length
      searchFrom = closeEnd
    } else {
      // Broken / incomplete block — scrub it entirely.
      Log.w(TAG, "sanitizeToolCallArtifacts: scrubbed incomplete tool-call block")
      result.append(TOOL_CALL_SCRUB_NOTICE)
      // Nothing left to salvage after the open tag — skip to end.
      searchFrom = text.length
    }
  }
  return result.toString().trim()
}

@OptIn(ExperimentalApi::class)
open class LlmChatViewModelBase(
  private val chatHistoryDao: ChatHistoryDao? = null,
  private val brainBoxDao: BrainBoxDao? = null,
  private val vectorEngine: VectorEngine? = null,
) : ChatViewModel() {

  /** Tracks which (taskId, modelName) pairs have already had their history loaded. */
  private val loadedHistoryKeys = Collections.synchronizedSet(mutableSetOf<String>())

  /**
   * Per-model [TokenMonitor] instances. Lazily created on first inference.
   * Tracks real-time token estimates for the active conversation.
   */
  private val tokenMonitors = mutableMapOf<String, TokenMonitor>()

  /**
   * Application context reference — set once via [initAppContext] so the
   * compression interceptor can write `resume_current.md` to FILE_BOX.
   */
  @Volatile
  private var appContext: Context? = null

  /** Call once from the screen/activity to provide the application context. */
  fun initAppContext(context: Context) {
    appContext = context.applicationContext
  }

  // ── Command Buffer (Message Queue) ─────────────────────────────────
  // When the user sends a message while inference is already running,
  // the command is enqueued here and drained sequentially on completion.
  private val _commandQueue = MutableStateFlow<List<QueuedCommand>>(emptyList())

  /** Observable command queue for the UI to show queued message status. */
  val commandQueue = _commandQueue.asStateFlow()

  /**
   * Returns (or creates) the [TokenMonitor] for [model], sized to the model's
   * configured `max_tokens` context window.
   */
  private fun getTokenMonitor(model: Model): TokenMonitor {
    return tokenMonitors.getOrPut(model.name) {
      val windowSize = model.getIntConfigValue(
        key = ConfigKeys.MAX_TOKENS,
        defaultValue = DEFAULT_MAX_TOKEN,
      )
      TokenMonitor(contextWindowSize = windowSize).also {
        Log.d(TAG, "TokenMonitor created for '${model.name}' with window=$windowSize")
      }
    }
  }

  /**
   * Loads persisted chat history for [taskId] + [model] from the database into the in-memory
   * UI state. No-op if already loaded or if no [chatHistoryDao] is available.
   */
  fun loadChatHistory(taskId: String, model: Model) {
    val key = "$taskId::${model.name}"
    // Guard against concurrent launches; only the first launch proceeds.
    if (!loadedHistoryKeys.add(key)) return
    val dao = chatHistoryDao ?: return

    viewModelScope.launch(Dispatchers.IO) {
      try {
        val rows = dao.getMessages(taskId = taskId, modelName = model.name)
        for (row in rows) {
          val side = when (row.side) {
            "USER" -> ChatSide.USER
            "AGENT" -> ChatSide.AGENT
            else -> {
              Log.w(TAG, "Unknown chat side '${row.side}' in history — skipping row id=${row.id}")
              continue
            }
          }
          addMessage(model = model, message = ChatMessageText(content = row.content, side = side))
        }
      } catch (e: Exception) {
        Log.e(TAG, "loadChatHistory: failed to load messages for $key", e)
      }
    }
  }

  /**
   * Deletes all persisted messages for [taskId] + [model] and clears the in-memory state.
   * This is the "Wipe Grid" operation.
   */
  fun wipeGrid(taskId: String, model: Model) {
    val dao = chatHistoryDao ?: return
    clearAllMessages(model = model)
    // Allow history to be freshly loaded (will be empty) on next model selection.
    loadedHistoryKeys.remove("$taskId::${model.name}")
    viewModelScope.launch(Dispatchers.IO) {
      try {
        dao.deleteMessages(taskId = taskId, modelName = model.name)
      } catch (e: Exception) {
        Log.e(TAG, "wipeGrid: failed to delete messages", e)
      }
    }
  }

  // =========================================================================
  // BrainBox — Retrieval-Augmented Generation (RAG)
  // =========================================================================

  /** Regex that matches [[Wiki-Link]] references embedded in neuron content. */
  private val wikiLinkPattern = Regex("""\[\[(.+?)]]""")

  /**
   * Searches the BrainBox for neurons whose label, type, content, or synapses contain any
   * keyword from [input] (words ≥ 4 chars, up to 3 keywords).  Returns a formatted context
   * block ready to be prepended to the user's message, or null if nothing relevant was found.
   *
   * When a matched neuron has [[Wiki-Link]] synapses, a second pass resolves those links
   * and appends the connected neurons — giving the LLM traversal across the knowledge graph.
   */
  suspend fun retrieveBrainContext(input: String): String? {
    val dao = brainBoxDao ?: return null
    val keywords =
      input
        .split(Regex("\\s+"))
        .map { it.trim().lowercase() }
        .filter { it.length >= MIN_KEYWORD_LENGTH }
        .distinct()
        .take(MAX_SEARCH_KEYWORDS)
    if (keywords.isEmpty()) return null

    // Count how many keywords matched each neuron to rank by relevance.
    val hitCount = mutableMapOf<NeuronEntity, Int>()
    for (kw in keywords) {
      for (neuron in dao.searchNeurons(kw)) {
        hitCount[neuron] = (hitCount[neuron] ?: 0) + 1
      }
    }
    if (hitCount.isEmpty()) return null

    val topNeurons = hitCount.entries
      .sortedByDescending { it.value }
      .take(MAX_PRIMARY_NEURONS)
      .map { it.key }

    // Resolve synapse links: pull any neurons referenced via [[Wiki-Links]].
    val synapseLabels = topNeurons
      .flatMap { wikiLinkPattern.findAll(it.synapses).map { m -> m.groupValues[1] } }
      .distinct()
    val linkedNeurons =
      if (synapseLabels.isNotEmpty()) {
        synapseLabels
          .flatMap { dao.searchNeurons(it) }
          .filter { it !in topNeurons }
          .distinct()
          .take(MAX_LINKED_NEURONS)
      } else {
        emptyList()
      }

    val allNeurons = topNeurons + linkedNeurons
    return allNeurons.joinToString("\n---\n") { n ->
      val synTag = if (n.synapses.isNotBlank()) "\nSynapses: ${n.synapses}" else ""
      "## ${n.label} [${n.type}]$synTag\n${n.content}"
    }
  }

  // =========================================================================
  // Phase 7: Context Auto-Compression Engine (Memory Defrag)
  // =========================================================================

  /**
   * Returns the number of USER + AGENT text message turns currently in the
   * chat history for [model].
   */
  fun getTextTurnCount(model: Model): Int {
    val messages = uiState.value.messagesByModel[model.name] ?: return 0
    return messages.count {
      it is ChatMessageText && (it.side == ChatSide.USER || it.side == ChatSide.AGENT)
    }
  }

  /**
   * Returns `true` when the active chat context has exceeded either the
   * TokenMonitor's 80% threshold (primary) or the turn count fallback.
   */
  fun needsContextCompression(model: Model): Boolean {
    val monitor = getTokenMonitor(model)
    if (monitor.shouldCompress()) return true
    return getTextTurnCount(model) >= CONTEXT_COMPRESSION_TURN_THRESHOLD
  }

  /**
   * Builds a plain-text transcript of the current chat for [model],
   * suitable for asking the LLM to produce a compressed summary.
   * Agent messages are sanitized to remove any residual tool-call syntax.
   */
  private fun buildTranscriptForCompression(model: Model): String {
    val messages = uiState.value.messagesByModel[model.name] ?: return ""
    return messages
      .filterIsInstance<ChatMessageText>()
      .filter { it.side == ChatSide.USER || it.side == ChatSide.AGENT }
      .joinToString("\n") { msg ->
        val speaker = if (msg.side == ChatSide.USER) "USER" else "CLU"
        val content = if (msg.side == ChatSide.AGENT) sanitizeToolCallArtifacts(msg.content) else msg.content
        "$speaker: $content"
      }
  }

  /**
   * Executes the full context compression cycle (The Recontextualization Loop):
   *
   * 1. Generates a Snapshot prompt from the current chat history using the
   *    structured `[[STATE]], [[LOGS]], [[NEXT_STEP]]` format.
   * 2. Deterministically extracts key data (files, goals, last response).
   * 3. Saves the `Session_Resume` block to `clu_file_box/BrainBox/resume_current.md`.
   * 4. Saves a permanent `Session_Snapshot` neuron in BrainBox for long-term memory.
   * 5. Purges the active conversation history (UI + persisted).
   * 6. The Cold Start: re-injects the resume block as the "Genesis Message."
   * 7. Resets the [TokenMonitor] with the genesis token cost.
   *
   * This process is autonomous and invisible to the user UI.
   *
   * Returns the summary text that was injected, or `null` if compression
   * was skipped (e.g. BrainBox unavailable, empty history).
   */
  suspend fun compressContext(
    model: Model,
    taskId: String,
  ): String? {
    val transcript = buildTranscriptForCompression(model)
    if (transcript.isBlank()) return null

    val dao = brainBoxDao ?: run {
      Log.w(TAG, "compressContext: BrainBox unavailable, skipping compression")
      return null
    }

    val monitor = getTokenMonitor(model)
    Log.d(
      TAG,
      "compressContext: compressing ${getTextTurnCount(model)} turns. ${monitor.diagnosticSummary()}"
    )

    // ── Build the Session_Resume block ──────────────────────────────
    val resumeLines = mutableListOf<String>()
    resumeLines.add("# Session_Resume (auto-generated)")
    resumeLines.add("")

    // [[STATE]] — workspace status, files, variables
    resumeLines.add("[[STATE]]")
    val filePathPattern = Regex("""[\w\-]+(?:/[\w\-]+)+\.\w{1,10}""")
    val mentionedFiles = filePathPattern.findAll(transcript)
      .map { it.value }
      .distinct()
      .toList()
    if (mentionedFiles.isNotEmpty()) {
      resumeLines.add("Files referenced:")
      mentionedFiles.forEach { resumeLines.add("- $it") }
    } else {
      resumeLines.add("No file paths detected in session.")
    }
    resumeLines.add("")

    // [[LOGS]] — recent breakthroughs and completed actions
    resumeLines.add("[[LOGS]]")
    val userMessages = (uiState.value.messagesByModel[model.name] ?: emptyList())
      .filterIsInstance<ChatMessageText>()
      .filter { it.side == ChatSide.USER }
      .takeLast(3)
    if (userMessages.isNotEmpty()) {
      resumeLines.add("Recent user goals:")
      userMessages.forEach { resumeLines.add("- ${it.content.take(MAX_USER_MESSAGE_PREVIEW_LENGTH)}") }
    }
    val lastAgentMsg = (uiState.value.messagesByModel[model.name] ?: emptyList())
      .filterIsInstance<ChatMessageText>()
      .filter { it.side == ChatSide.AGENT }
      .lastOrNull()
    if (lastAgentMsg != null) {
      resumeLines.add("Last CLU response (truncated):")
      resumeLines.add(lastAgentMsg.content.take(MAX_AGENT_RESPONSE_PREVIEW_LENGTH))
    }
    resumeLines.add("")

    // [[NEXT_STEP]] — the immediate pending objective
    resumeLines.add("[[NEXT_STEP]]")
    val lastUserMsg = userMessages.lastOrNull()
    if (lastUserMsg != null) {
      resumeLines.add("Continue from: ${lastUserMsg.content.take(MAX_USER_MESSAGE_PREVIEW_LENGTH)}")
    } else {
      resumeLines.add("Awaiting next directive from Operator.")
    }

    val summary = resumeLines.joinToString("\n")

    // ── Save resume_current.md to FILE_BOX (atomic write) ─────────────
    val ctx = appContext
    if (ctx != null) {
      withContext(Dispatchers.IO) {
        try {
          val fileBoxRoot = File(ctx.filesDir, "clu_file_box")
          val resumeFile = File(fileBoxRoot, RESUME_FILE_PATH)
          resumeFile.parentFile?.mkdirs()
          // Atomic write: write to a temp file, then rename. This prevents
          // a half-written file if the OS kills the process mid-write.
          val tmpFile = File(resumeFile.parentFile, "${resumeFile.name}.tmp")
          tmpFile.writeText(summary, Charsets.UTF_8)
          tmpFile.renameTo(resumeFile)
          Log.d(TAG, "compressContext: saved resume_current.md (${summary.length} chars)")
        } catch (e: Exception) {
          Log.e(TAG, "compressContext: failed to write resume_current.md", e)
        }
      }
    }

    // ── Save permanent Session_Snapshot neuron to BrainBox ──────────
    val timestamp =
      java.time.LocalDateTime.now()
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
    withContext(Dispatchers.IO) {
      try {
        dao.insertNeuron(
          NeuronEntity(
            id = UUID.randomUUID().toString(),
            label = "Session_Snapshot_$timestamp",
            type = "Session_Snapshot",
            content = summary,
          )
        )
      } catch (e: Exception) {
        Log.e(TAG, "compressContext: failed to save Session_Snapshot neuron", e)
      }
    }
    Log.d(TAG, "compressContext: saved Session_Snapshot neuron (${summary.length} chars)")

    // ── Purge: Clear active chat history ────────────────────────────
    clearAllMessages(model = model)

    // ── The Cold Start: Re-inject genesis message ───────────────────
    withContext(Dispatchers.Main) {
      addMessage(
        model = model,
        message = ChatMessageText(
          content = summary,
          side = ChatSide.AGENT,
        ),
      )
    }

    // ── Reset TokenMonitor with genesis token cost ──────────────────
    val genesisTokens = monitor.estimateTokens(summary)
    monitor.reset(genesisTokens)
    Log.d(TAG, "compressContext: recontextualization complete. ${monitor.diagnosticSummary()}")

    // ── Wipe persisted history and re-persist the genesis message ───
    val chatDao = chatHistoryDao
    if (chatDao != null && taskId.isNotEmpty()) {
      withContext(Dispatchers.IO) {
        try {
          chatDao.deleteMessages(taskId = taskId, modelName = model.name)
          chatDao.insertMessage(
            ChatMessageEntity(
              taskId = taskId,
              modelName = model.name,
              side = ChatSide.AGENT.name,
              content = summary,
              timestampMs = System.currentTimeMillis(),
            )
          )
        } catch (e: Exception) {
          Log.e(TAG, "compressContext: failed to wipe/re-persist history", e)
        }
      }
    }

    return summary
  }

  /**
   * FORGE NEURON — snapshot the current conversation as a [NeuronEntity] in BrainBox.
   *
   * Builds a plain-text transcript of all [ChatMessageText] turns for [model], saves it as a
   * Session_Log neuron, and appends a confirmation message to the chat so the user can see
   * what was locked.  Any [[Wiki-Links]] found inside the transcript are automatically
   * extracted and stored in the synapses field.
   *
   * No-op if [brainBoxDao] is unavailable or the chat is empty.
   */
  fun forgeNeuron(model: Model) {
    val dao = brainBoxDao ?: return
    val messages = uiState.value.messagesByModel[model.name] ?: return
    val transcript =
      messages
        .filterIsInstance<ChatMessageText>()
        .filter { it.side == ChatSide.USER || it.side == ChatSide.AGENT }
        .joinToString("\n\n") { msg ->
          val speaker = if (msg.side == ChatSide.USER) "USER" else "CLU"
          "$speaker: ${msg.content}"
        }
    if (transcript.isBlank()) return

    // Auto-extract any [[Wiki-Links]] from the transcript.
    val extractedSynapses =
      wikiLinkPattern.findAll(transcript)
        .map { it.groupValues[1] }
        .distinct()
        .joinToString(",") { "[[${it}]]" }

    viewModelScope.launch(Dispatchers.IO) {
      try {
        val timestamp =
          java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))

        val label = "Session_$timestamp"

        // Generate vector embedding for the session transcript.
        val engine = vectorEngine
        val embedding = if (engine != null) {
          val textToEmbed = "$label $transcript $extractedSynapses"
          engine.embed(textToEmbed)
        } else {
          floatArrayOf()
        }

        val neuron =
          NeuronEntity(
            id = UUID.randomUUID().toString(),
            label = label,
            type = "Session_Log",
            content = transcript,
            synapses = extractedSynapses,
            embedding = embedding,
          )
        dao.insertNeuron(neuron)
        Log.d(TAG, "Forged neuron: ${neuron.label} (${transcript.length} chars, ${embedding.size}-dim embedding)")

        withContext(Dispatchers.Main) {
          addMessage(
            model = model,
            message =
              ChatMessageText(
                content =
                  "⚡ **FORGED TO BRAINBOX** — session locked as `${neuron.label}`.\n" +
                    "CLU will remember this the next time you reference it." +
                    if (embedding.isNotEmpty()) "\n📐 Vector embedding: ${embedding.size} dimensions" else "",
                side = ChatSide.AGENT,
              ),
          )
        }
      } catch (e: Exception) {
        Log.e(TAG, "forgeNeuron: failed to forge neuron", e)
      }
    }
  }

  /** Persists a completed user or agent TEXT message to the database.
   *  Agent content is sanitized to remove broken tool-call fragments. */
  private fun persistMessage(taskId: String, model: Model, side: ChatSide, content: String) {
    val dao = chatHistoryDao ?: return
    // Sanitize agent responses so broken tool-call syntax never poisons context.
    val safeContent = if (side == ChatSide.AGENT) sanitizeToolCallArtifacts(content) else content
    val entity =
      ChatMessageEntity(
        taskId = taskId,
        modelName = model.name,
        side = side.name,
        content = safeContent,
        timestampMs = System.currentTimeMillis(),
      )
    viewModelScope.launch(Dispatchers.IO) {
      try {
        dao.insertMessage(entity)
      } catch (e: Exception) {
        Log.e(TAG, "persistMessage: failed to insert chat message", e)
      }
    }
  }

  // ── Command Buffer: drain helper ───────────────────────────────────
  /**
   * Pops the first entry from [_commandQueue] (if any) and dispatches it
   * to [generateResponse].  Called after every terminal completion of an
   * inference run (normal finish, error, timeout).  The queued messages
   * have already been rendered in the Chat UI with a "queued" style; once
   * they are popped, the UI transitions them to standard styling.
   */
  private fun drainCommandQueue() {
    val queue = _commandQueue.value
    if (queue.isEmpty()) return

    val next = queue.first()
    _commandQueue.update { it.drop(1) }
    Log.d(TAG, "drainCommandQueue: popping queued command — remaining: ${queue.size - 1}")

    // Transition the queued message in the UI to normal styling by
    // finding the first ChatMessageText with isQueued=true and replacing
    // it with a non-queued copy.
    dequeueMessageInUI(next.model, next.input)

    generateResponse(
      model = next.model,
      input = next.input,
      taskId = next.taskId,
      images = next.images,
      audioMessages = next.audioMessages,
      onFirstToken = next.onFirstToken,
      onDone = next.onDone,
      onError = next.onError,
      allowThinking = next.allowThinking,
    )
  }

  /**
   * Finds the first queued [ChatMessageText] in the UI for [model] whose
   * content matches [input] and replaces it with a non-queued copy so the
   * bubble transitions from muted to standard styling.
   */
  private fun dequeueMessageInUI(model: Model, input: String) {
    val messages = uiState.value.messagesByModel[model.name] ?: return
    val idx = messages.indexOfFirst {
      it is ChatMessageText && it.isQueued && it.content == input
    }
    if (idx >= 0) {
      val queued = messages[idx] as ChatMessageText
      replaceMessage(
        model = model,
        index = idx,
        message = ChatMessageText(
          content = queued.content,
          side = queued.side,
          latencyMs = queued.latencyMs,
          isMarkdown = queued.isMarkdown,
          accelerator = queued.accelerator,
          hideSenderLabel = queued.hideSenderLabel,
          isQueued = false,
        ),
      )
    }
  }

  fun generateResponse(
    model: Model,
    input: String,
    taskId: String = "",
    images: List<Bitmap> = listOf(),
    audioMessages: List<ChatMessageAudioClip> = listOf(),
    onFirstToken: (Model) -> Unit = {},
    onDone: () -> Unit = {},
    onError: (String) -> Unit,
    allowThinking: Boolean = false,
  ) {
    // ── Re-entrancy guard + Command Buffer ─────────────────────────
    // The native C++ LLM backend does not support concurrent
    // sendMessageAsync calls on the same Conversation — re-entering
    // would cause a hard crash.  Instead of silently dropping the
    // request, we enqueue it and drain after the current inference
    // completes.
    if (uiState.value.inProgress) {
      val currentSize = _commandQueue.value.size
      if (currentSize >= MAX_COMMAND_QUEUE_SIZE) {
        Log.w(TAG, "generateResponse: command queue full ($currentSize). Dropping request.")
        return
      }
      Log.d(TAG, "generateResponse: inference in progress — enqueueing command (queue size: ${currentSize + 1})")
      _commandQueue.update { queue ->
        queue + QueuedCommand(
          model = model,
          input = input,
          taskId = taskId,
          images = images,
          audioMessages = audioMessages,
          onFirstToken = onFirstToken,
          onDone = onDone,
          onError = onError,
          allowThinking = allowThinking,
        )
      }
      return
    }

    val accelerator = model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = "")
    val monitor = getTokenMonitor(model)
    viewModelScope.launch(Dispatchers.Default) {
      try {
      setInProgress(true)
      setPreparing(true)

      // Persist user message immediately.
      if (taskId.isNotEmpty() && input.isNotEmpty()) {
        persistMessage(taskId = taskId, model = model, side = ChatSide.USER, content = input)
      }

      // ── Circuit Breaker: detect unresolved tool calls ──────────────
      // If the last agent message contains an unclosed <|tool_call> block
      // (opened but never completed), the LLM may try to re-invoke it on
      // the next turn. Force-append a cancellation token so the model
      // knows the previous task is dead and can respond normally.
      val lastAgentMsg = getLastMessageWithTypeAndSide(
        model = model,
        type = ChatMessageType.TEXT,
        side = ChatSide.AGENT,
      )
      if (lastAgentMsg is ChatMessageText) {
        val content = lastAgentMsg.content
        val hasOpen = content.contains(TOOL_CALL_OPEN_TAG)
        val hasClosed = TOOL_CALL_CLOSE_TAGS.any { content.contains(it) }
        if (hasOpen && !hasClosed) {
          Log.w(TAG, "Circuit breaker: unresolved tool call detected — injecting cancellation token")
          addMessage(
            model = model,
            message = ChatMessageText(
              content = TOOL_CALL_CIRCUIT_BREAKER_NOTICE,
              side = ChatSide.AGENT,
            ),
          )
          if (taskId.isNotEmpty()) {
            persistMessage(
              taskId = taskId,
              model = model,
              side = ChatSide.AGENT,
              content = TOOL_CALL_CIRCUIT_BREAKER_NOTICE,
            )
          }
        }
      }

      // Phase 7: Context Auto-Compression — if the TokenMonitor breaches
      // the 80% critical threshold (or turn count fallback is exceeded),
      // fire the compression interceptor. This is autonomous and invisible.
      var compressionContext: String? = null
      try {
        if (needsContextCompression(model)) {
          Log.d(
            TAG,
            "Context compression triggered. ${monitor.diagnosticSummary()}"
          )
          compressionContext = compressContext(model = model, taskId = taskId)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Context compression failed — continuing without compression", e)
      }

      // Loading.
      addMessage(model = model, message = ChatMessageLoading(accelerator = accelerator))

      // Wait for instance to be initialized (timeout after 30 seconds).
      var instanceWaitMs = 0L
      val maxInstanceWaitMs = 30_000L
      while (model.instance == null) {
        delay(100)
        instanceWaitMs += 100
        if (instanceWaitMs >= maxInstanceWaitMs) {
          Log.e(TAG, "generateResponse: model instance not initialized after ${maxInstanceWaitMs}ms")
          setInProgress(false)
          setPreparing(false)
          onError("Model failed to initialize within ${maxInstanceWaitMs / 1000}s. Try resetting the session.")
          drainCommandQueue()
          return@launch
        }
      }
      delay(500)

      // Run inference.
      val audioClips: MutableList<ByteArray> = mutableListOf()
      for (audioMessage in audioMessages) {
        audioClips.add(audioMessage.genByteArrayForWav())
      }

      var firstRun = true
      val start = System.currentTimeMillis()

      // ── Tool-call stream buffering state ───────────────────────────
      // When the LLM starts emitting a tool-call block we buffer tokens
      // instead of rendering them to the Chat UI. This prevents partial
      // tool-call syntax from being shown and protects context integrity.
      var isBufferingToolCall = false
      val toolCallBuffer = StringBuilder()

      // Running accumulator of ALL tokens emitted so far (including
      // tool-call blocks). Used to detect garbled tool-call artifacts
      // that span multiple token emissions.
      val fullResponseAccumulator = StringBuilder()

      // Flag set when the interceptor detects a garbled tool call and
      // needs to abort the current inference run.
      var shouldCancelInference = false

      val resultListener: (String, Boolean, String?) -> Unit =
          { partialResult, done, partialThinkingResult ->
            // ── Garbled token detection ─────────────────────────────
            // Append every token to the running accumulator before any
            // other processing. If we spot a known-broken pattern
            // (e.g. <|"|>) we immediately flag for cancellation and
            // suppress the garbage from reaching the UI.
            fullResponseAccumulator.append(partialResult)

            // ── Tool-call buffering interceptor ─────────────────────
            // Accumulate the token first; detect open/close boundaries
            // on the *running accumulation* (toolCallBuffer) so that
            // tags split across multiple token emissions are handled.
            var tokenForUI = partialResult

            if (shouldCancelInference) {
              // Already flagged — suppress everything until done.
              tokenForUI = ""
              if (done) {
                Log.w(TAG, "Inference ended after garbled-token cancellation")
                shouldCancelInference = false
              }
            } else if (isBufferingToolCall) {
              toolCallBuffer.append(partialResult)
              val bufStr = toolCallBuffer.toString()
              val closed = TOOL_CALL_CLOSE_TAGS.any { bufStr.contains(it) }
              if (closed || done) {
                // Buffer complete — the litertlm ToolSet framework has
                // already invoked the @Tool method. Discard the raw tags.
                Log.d(TAG, "Tool-call buffer closed (${toolCallBuffer.length} chars)")
                isBufferingToolCall = false
                toolCallBuffer.clear()
              }
              // While buffering, suppress rendering to the Chat UI.
              tokenForUI = ""
            } else if (partialResult.contains(TOOL_CALL_OPEN_TAG)) {
              // Entering a tool-call block. Split: keep text before the
              // tag for normal rendering, start buffering from the tag.
              val idx = partialResult.indexOf(TOOL_CALL_OPEN_TAG)
              tokenForUI = partialResult.substring(0, idx)
              toolCallBuffer.clear()
              toolCallBuffer.append(partialResult.substring(idx))
              isBufferingToolCall = true
              Log.d(TAG, "Tool-call buffer started")
            } else if (!done) {
              // ── Check for garbled tool tokens in the recent output ──
              // Inspect the last ~40 chars of the accumulator (enough
              // for any mangled <|…|> sequence) to avoid repeated full
              // scans. If a broken token is found, cancel inference so
              // the recursive restart can re-prompt the model cleanly.
              val tail = fullResponseAccumulator.takeLast(40)
              if (GARBLED_TOOL_TOKEN_PATTERN.containsMatchIn(tail)) {
                Log.w(TAG, "Garbled tool token detected in output: …${tail}")
                shouldCancelInference = true
                tokenForUI = ""
                // Ask the model helper to stop generating immediately.
                try {
                  model.runtimeHelper.stopResponse(model)
                } catch (e: Exception) {
                  Log.e(TAG, "Failed to stop inference after garbled token", e)
                }
              }
            }

            if (tokenForUI.startsWith("<ctrl")) {
              // Do nothing. Ignore control tokens.
            } else {
              // Remove the last message if it is a "loading" message.
              // This will only be done once.
              val lastMessage = getLastMessage(model = model)
              val wasLoading = lastMessage?.type == ChatMessageType.LOADING
              if (wasLoading) {
                removeLastMessage(model = model)
              }

              val thinkingText = partialThinkingResult
              val isThinking = thinkingText != null && thinkingText.isNotEmpty()
              var currentLastMessage = getLastMessage(model = model)

              // If thinking is enabled, add a thinking message.
              if (isThinking) {
                if (currentLastMessage?.type != ChatMessageType.THINKING) {
                  addMessage(
                    model = model,
                    message =
                      ChatMessageThinking(
                        content = "",
                        inProgress = true,
                        side = ChatSide.AGENT,
                        accelerator = accelerator,
                        hideSenderLabel =
                          currentLastMessage?.type == ChatMessageType.COLLAPSABLE_PROGRESS_PANEL,
                      ),
                  )
                }
                updateLastThinkingMessageContentIncrementally(
                  model = model,
                  partialContent = thinkingText!!,
                )
              } else {
                if (currentLastMessage?.type == ChatMessageType.THINKING) {
                  val thinkingMsg = currentLastMessage as ChatMessageThinking
                  if (thinkingMsg.inProgress) {
                    replaceLastMessage(
                      model = model,
                      message =
                        ChatMessageThinking(
                          content = thinkingMsg.content,
                          inProgress = false,
                          side = thinkingMsg.side,
                          accelerator = thinkingMsg.accelerator,
                          hideSenderLabel = thinkingMsg.hideSenderLabel,
                        ),
                      type = ChatMessageType.THINKING,
                    )
                  }
                }
                currentLastMessage = getLastMessage(model = model)
                if (
                  currentLastMessage?.type != ChatMessageType.TEXT ||
                    currentLastMessage.side != ChatSide.AGENT
                ) {
                  // Add an empty message that will receive streaming results.
                  addMessage(
                    model = model,
                    message =
                      ChatMessageText(
                        content = "",
                        side = ChatSide.AGENT,
                        accelerator = accelerator,
                        hideSenderLabel =
                          currentLastMessage?.type == ChatMessageType.COLLAPSABLE_PROGRESS_PANEL ||
                            currentLastMessage?.type == ChatMessageType.THINKING,
                      ),
                  )
                }

                // Incrementally update the streamed partial results.
                val latencyMs: Long = if (done) System.currentTimeMillis() - start else -1
                if (tokenForUI.isNotEmpty() || wasLoading || done) {
                  updateLastTextMessageContentIncrementally(
                    model = model,
                    partialContent = tokenForUI,
                    latencyMs = latencyMs.toFloat(),
                  )
                }
              }

              if (firstRun) {
                firstRun = false
                setPreparing(false)
                onFirstToken(model)
              }

              if (done) {
                val finalLastMessage = getLastMessage(model = model)
                if (finalLastMessage?.type == ChatMessageType.THINKING) {
                  val thinkingMsg = finalLastMessage as ChatMessageThinking
                  if (thinkingMsg.inProgress) {
                    replaceLastMessage(
                      model = model,
                      message =
                        ChatMessageThinking(
                          content = thinkingMsg.content,
                          inProgress = false,
                          side = thinkingMsg.side,
                          accelerator = thinkingMsg.accelerator,
                          hideSenderLabel = thinkingMsg.hideSenderLabel,
                        ),
                      type = ChatMessageType.THINKING,
                    )
                  }
                }

                // ── Recursive Restart: garbled tool-call recovery ─────
                // If the interceptor flagged a garbled tool token, the
                // model's output is garbage. Instead of persisting it and
                // calling onDone (which would show broken text to the
                // user), we scrub the broken response and re-invoke
                // inference with a corrective system message that tells
                // the model to retry with clean syntax.
                if (shouldCancelInference) {
                  shouldCancelInference = false
                  Log.w(TAG, "Garbled tool-call detected — initiating recursive restart")
                  // Remove the broken agent message from the chat.
                  val brokenMsg = getLastMessage(model = model)
                  if (brokenMsg is ChatMessageText && brokenMsg.side == ChatSide.AGENT) {
                    removeLastMessage(model = model)
                  }
                  setInProgress(false)
                  // Re-invoke inference with a corrective prompt.
                  generateResponse(
                    model = model,
                    input = "[System: Your previous response contained malformed tool-call " +
                      "tokens. The broken output has been discarded. To use a tool, you MUST " +
                      "output the tool call using the native function-calling mechanism. Do " +
                      "NOT manually type <|tool_call> tags or JSON. Simply invoke the tool " +
                      "function directly. Now, please retry the user's original request.]",
                    taskId = taskId,
                    onFirstToken = onFirstToken,
                    onDone = onDone,
                    onError = onError,
                    allowThinking = allowThinking,
                  )
                } else {
                  // Persist the completed agent response and track tokens.
                  if (taskId.isNotEmpty()) {
                    val agentMsg = getLastMessage(model = model)
                    if (agentMsg is ChatMessageText && agentMsg.side == ChatSide.AGENT) {
                      monitor.trackMessage(agentMsg.content)
                      persistMessage(
                        taskId = taskId,
                        model = model,
                        side = ChatSide.AGENT,
                        content = agentMsg.content,
                      )
                    }
                  }

                  setInProgress(false)
                  onDone()
                  drainCommandQueue()
              }
            }
          }

        val cleanUpListener: () -> Unit = {
          setInProgress(false)
          setPreparing(false)
          drainCommandQueue()
        }

        val errorListener: (String) -> Unit = { message ->
          Log.e(TAG, "Error occurred while running inference")
          setInProgress(false)
          setPreparing(false)
          onError(message)
          drainCommandQueue()
        }

        val enableThinking =
          allowThinking &&
            model.getBooleanConfigValue(key = ConfigKeys.ENABLE_THINKING, defaultValue = false)
        val extraContext = if (enableThinking) mapOf("enable_thinking" to "true") else null

        // BrainBox RAG — query for relevant neurons and prepend them as injected context.
        // Only runs when the user has actually typed something (not for image/audio-only turns).
        // Phase 7: If compression just fired, also prepend the compressed session state.
        val augmentedInput =
          if (input.isNotBlank()) {
            val brainContext = withContext(Dispatchers.IO) { retrieveBrainContext(input) }
            val contextParts = mutableListOf<String>()

            // Inject compressed session state if compression just occurred.
            if (!compressionContext.isNullOrBlank()) {
              contextParts.add(
                "=== CLU/BOX COMPRESSED SESSION STATE ===\n$compressionContext\n=== END SESSION STATE ==="
              )
            }

            // Inject BrainBox RAG context.
            if (!brainContext.isNullOrBlank()) {
              Log.d(TAG, "BrainBox: injecting context (${brainContext.length} chars)")
              contextParts.add(
                "=== CLU/BOX MEMORY (relevant context retrieved from BrainBox) ===\n$brainContext\n=== END MEMORY ==="
              )
            }

            if (contextParts.isNotEmpty()) {
              contextParts.joinToString("\n\n") + "\n\nUser message: $input"
            } else {
              input
            }
          } else {
            input
          }

        // Track the full augmented input tokens (user message + RAG context)
        // since that is the complete payload hitting the LLM context window.
        if (augmentedInput.isNotEmpty()) {
          monitor.trackMessage(augmentedInput)
        }

        // ── Pre-flight token clamp ──────────────────────────────────────
        // Truncate the payload string BEFORE it reaches sendMessageAsync.
        // This prevents oversized context-injection payloads (RAG + compression
        // + long user input) from crashing the native C++ layer when the
        // combined token count exceeds the model's physical context window.
        val maxPayloadChars = ((monitor.criticalLimit) * APPROX_CHARS_PER_TOKEN).toInt()  // criticalLimit is already 80% of context window (in tokens); convert to chars
        val clampedInput = if (augmentedInput.length > maxPayloadChars) {
          Log.w(
            TAG,
            "Pre-flight clamp: truncating input from ${augmentedInput.length} to $maxPayloadChars chars"
          )
          augmentedInput.take(maxPayloadChars) +
            "\n\n[System: Input was truncated to fit within the context window.]"
        } else {
          augmentedInput
        }

        model.runtimeHelper.runInference(
          model = model,
          input = clampedInput,
          images = images,
          audioClips = audioClips,
          resultListener = resultListener,
          cleanUpListener = cleanUpListener,
          onError = errorListener,
          coroutineScope = viewModelScope,
          extraContext = extraContext,
        )
      } catch (e: Throwable) {
        Log.e("CLU_CRASH_REPORT", "Inference pipeline failed: ${e.stackTraceToString()}")
        if (e is OutOfMemoryError) {
          Log.e("CLU_CRASH_REPORT", "OOM in inference pipeline — requesting GC")
          System.gc()
        }
        setInProgress(false)
        setPreparing(false)
        onError(e.message ?: "")
        drainCommandQueue()
      }
    }
  }

  fun stopResponse(model: Model) {
    Log.d(TAG, "Stopping response for model ${model.name}...")
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    }
    setInProgress(false)
    model.runtimeHelper.stopResponse(model)
    Log.d(TAG, "Done stopping response")
    drainCommandQueue()
  }

  fun resetSession(
    task: Task,
    model: Model,
    systemInstruction: Contents? = null,
    tools: List<ToolProvider> = listOf(),
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
    onDone: () -> Unit = {},
    enableConversationConstrainedDecoding: Boolean = false,
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      setIsResettingSession(true)
      // Clear any pending commands — they belong to the old session.
      _commandQueue.update { emptyList() }
      clearAllMessages(model = model)
      stopResponse(model = model)

      var resetAttempts = 0
      val maxResetAttempts = 10
      while (resetAttempts < maxResetAttempts) {
        try {
          model.runtimeHelper.resetConversation(
            model = model,
            supportImage = supportImage,
            supportAudio = supportAudio,
            systemInstruction = systemInstruction,
            tools = tools,
            enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
          )
          break
        } catch (e: Exception) {
          resetAttempts++
          Log.d(TAG, "Failed to reset session (attempt $resetAttempts/$maxResetAttempts). Trying again")
          if (resetAttempts >= maxResetAttempts) {
            Log.e(TAG, "Reset session failed after $maxResetAttempts attempts — giving up", e)
          }
        }
        delay(200)
      }
      setIsResettingSession(false)
      onDone()
    }
  }

  fun runAgain(
    model: Model,
    message: ChatMessageText,
    taskId: String = "",
    onError: (String) -> Unit,
    allowThinking: Boolean = false,
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      // Wait for model to be initialized (timeout after 30 seconds).
      var waitMs = 0L
      val maxWaitMs = 30_000L
      while (model.instance == null) {
        delay(100)
        waitMs += 100
        if (waitMs >= maxWaitMs) {
          Log.e(TAG, "runAgain: model instance not initialized after ${maxWaitMs}ms — aborting")
          onError("Model failed to initialize. Please try resetting the session.")
          return@launch
        }
      }

      // Clone the clicked message and add it.
      addMessage(model = model, message = message.clone())

      // Run inference.
      generateResponse(
        model = model,
        input = message.content,
        taskId = taskId,
        onError = onError,
        allowThinking = allowThinking,
      )
    }
  }

  fun handleError(
    context: Context,
    task: Task,
    model: Model,
    modelManagerViewModel: ModelManagerViewModel,
    errorMessage: String,
  ) {
    // Remove the "loading" message.
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    }

    // Show error message.
    addMessage(model = model, message = ChatMessageError(content = errorMessage))

    // Clean up and re-initialize.
    viewModelScope.launch(Dispatchers.Default) {
      modelManagerViewModel.cleanupModel(
        context = context,
        task = task,
        model = model,
        onDone = {
          modelManagerViewModel.initializeModel(context = context, task = task, model = model)

          // Add a warning message for re-initializing the session.
          addMessage(
            model = model,
            message = ChatMessageWarning(content = "Session re-initialized"),
          )
        },
      )
    }
  }
}

@HiltViewModel
class LlmChatViewModel @Inject constructor(chatHistoryDao: ChatHistoryDao, brainBoxDao: BrainBoxDao, vectorEngine: VectorEngine) :
  LlmChatViewModelBase(chatHistoryDao, brainBoxDao, vectorEngine)

@HiltViewModel class LlmAskImageViewModel @Inject constructor() : LlmChatViewModelBase()

@HiltViewModel class LlmAskAudioViewModel @Inject constructor() : LlmChatViewModelBase()
