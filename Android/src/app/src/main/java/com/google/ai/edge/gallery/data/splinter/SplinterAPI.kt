/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.data.splinter

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.ai.edge.gallery.data.brainbox.GraphDatabase
import com.google.ai.edge.gallery.data.busybox.BusyBoxBridge
import com.google.ai.edge.gallery.data.git.JGitManager
import com.google.ai.edge.gallery.data.scdlbox.ScdlBoxWorker
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val TAG = "SplinterAPI"

/**
 * **God-mode** Kotlin facade injected directly into the Chaquopy Python
 * interpreter at startup.  From within a `.py` skill, the agent can write:
 *
 * ```python
 * from java import jclass
 * Splinter = jclass("com.google.ai.edge.gallery.data.splinter.SplinterAPI").INSTANCE
 *
 * # SKILL_BOX
 * Splinter.skillBoxList()
 *
 * # FILE_BOX
 * Splinter.fileBoxRead("notes.md")
 * Splinter.fileBoxWrite("plan.md", "## Today\n* refactor governor")
 *
 * # SCDL_BOX
 * Splinter.scdlBoxSchedule("nightly-pull", 3600, "git_pull_clu_box")
 *
 * # LNK_BOX (MCP)
 * Splinter.lnkBoxConnect("filesystem", "stdio", "/data/.../mcp-fs")
 *
 * # BRAIN_BOX
 * Splinter.brainBoxRecall("yesterday's plan")
 * Splinter.brainBoxStore("note", "thought", "Refactor day", "")
 *
 * # CLN_BOX (personas)
 * Splinter.clnBoxCreate("kotlin_coder", "You are a senior Kotlin engineer …")
 * ```
 *
 * Every method takes/returns plain JSON-able Kotlin types so the bridge
 * works without any `Py` imports on the Python side.
 *
 * The class is exposed as a true singleton through the [INSTANCE] static so
 * the Python side never has to construct a new instance.
 */
class SplinterAPI private constructor() {

  companion object {
    /** JVM static handle Python uses via `jclass(…).INSTANCE`. */
    @JvmField
    val INSTANCE: SplinterAPI = SplinterAPI()
  }

  // ── Lazy context (set once by PythonBridge.initialize) ───────────────────

  @Volatile private var ctx: Context? = null
  private val context: Context
    get() = ctx ?: error("SplinterAPI not bound to a Context — PythonBridge.initialize() must run first.")

  /** Bind the application [Context]. Called by [com.google.ai.edge.gallery.data.python.PythonBridge]. */
  fun bind(context: Context) {
    if (ctx != null) return
    ctx = context.applicationContext
    Log.d(TAG, "SplinterAPI bound to application context")
  }

  // ─────────────────────────────────────────────────────────────────────────
  //  SKILL_BOX — dynamic Python skill execution & metadata
  // ─────────────────────────────────────────────────────────────────────────

  /** Root directory for user-authored Python skill files (`.py`). */
  private fun skillDir(): File =
    File(context.filesDir, "skill_box").apply { mkdirs() }

  /** Returns a JSON array of `{name, file, doc}` objects for every installed skill. */
  fun skillBoxList(): String {
    val skills = JSONArray()
    skillDir().listFiles { f -> f.isFile && f.name.endsWith(".py") }?.sortedBy { it.name }?.forEach { f ->
      val src = runCatching { f.readText() }.getOrDefault("")
      val doc = extractDocstring(src)
      skills.put(JSONObject().apply {
        put("name", f.nameWithoutExtension)
        put("file", f.absolutePath)
        put("doc", doc)
      })
    }
    return skills.toString()
  }

  /** Reads a skill source file. Returns the source or `[error]` line. */
  fun skillBoxRead(name: String): String {
    val f = File(skillDir(), "$name.py")
    if (!f.isFile) return "[skillBoxRead error: '$name' not found]"
    return runCatching { f.readText() }.getOrElse { "[skillBoxRead error: ${it.message}]" }
  }

  /** Creates or overwrites a skill file. */
  fun skillBoxWrite(name: String, source: String): String {
    val f = File(skillDir(), "$name.py")
    return runCatching {
      f.writeText(source)
      "[skillBoxWrite ok: ${f.absolutePath}]"
    }.getOrElse { "[skillBoxWrite error: ${it.message}]" }
  }

  /** Deletes a skill file. */
  fun skillBoxDelete(name: String): String {
    val f = File(skillDir(), "$name.py")
    return if (f.delete()) "[skillBoxDelete ok: $name]" else "[skillBoxDelete error: $name]"
  }

  // ─────────────────────────────────────────────────────────────────────────
  //  SCDL_BOX — autonomous background tasks via WorkManager
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Schedules a one-shot background task. Returns the WorkManager UUID.
   *
   * @param taskId   A stable task identifier persisted by [ScdlBoxWorker].
   * @param delaySec Delay in seconds before the worker fires.
   * @param payload  Payload string forwarded to the worker (e.g. shell line).
   */
  fun scdlBoxScheduleOnce(taskId: String, delaySec: Long, payload: String): String {
    val req = OneTimeWorkRequestBuilder<ScdlBoxWorker>()
      .setInitialDelay(delaySec, TimeUnit.SECONDS)
      .setInputData(Data.Builder().putString(ScdlBoxWorker.KEY_TASK_ID, taskId)
        .putString("payload", payload).build())
      .build()
    WorkManager.getInstance(context).enqueue(req)
    return req.id.toString()
  }

  /** Schedules a recurring task. Returns the WorkManager UUID. */
  fun scdlBoxSchedulePeriodic(taskId: String, intervalMin: Long, payload: String): String {
    val req = PeriodicWorkRequestBuilder<ScdlBoxWorker>(intervalMin, TimeUnit.MINUTES)
      .setInputData(Data.Builder().putString(ScdlBoxWorker.KEY_TASK_ID, taskId)
        .putString("payload", payload).build())
      .build()
    WorkManager.getInstance(context).enqueue(req)
    return req.id.toString()
  }

  /** Cancels a previously-enqueued task by its WorkManager UUID. */
  fun scdlBoxCancel(workId: String): String {
    return runCatching {
      WorkManager.getInstance(context).cancelWorkById(UUID.fromString(workId))
      "[scdlBoxCancel ok: $workId]"
    }.getOrElse { "[scdlBoxCancel error: ${it.message}]" }
  }

  // ─────────────────────────────────────────────────────────────────────────
  //  LNK_BOX — Model Context Protocol connections
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Open a real MCP connection through [LnkBoxBridge].
   *
   * @param transport `"sse"` (Ktor remote SSE) or `"stdio"` (Chaquopy local).
   * @param target    SSE endpoint URL, or local Python module name for stdio.
   */
  fun lnkBoxConnect(id: String, transport: String, target: String): String =
    when (transport.lowercase()) {
      "sse" -> com.google.ai.edge.gallery.data.lnkbox.LnkBoxBridge.connectSse(id, target)
      "stdio" -> com.google.ai.edge.gallery.data.lnkbox.LnkBoxBridge.connectStdio(context, id, target)
      else -> "[lnkBoxConnect error: unknown transport '$transport']"
    }

  /** Send a JSON-RPC frame down a previously-opened connection. */
  fun lnkBoxSend(id: String, method: String, paramsJson: String = "{}"): String {
    val params = runCatching { JSONObject(paramsJson) }.getOrElse { JSONObject() }
    val asMap = mutableMapOf<String, Any?>()
    params.keys().forEach { k -> asMap[k] = params.opt(k) }
    return com.google.ai.edge.gallery.data.lnkbox.LnkBoxBridge.send(id, method, asMap)
  }

  fun lnkBoxList(): String {
    val arr = JSONArray()
    com.google.ai.edge.gallery.data.lnkbox.LnkBoxBridge.list().forEach { snap ->
      arr.put(JSONObject().apply {
        put("id", snap.id)
        put("transport", snap.transport)
        put("target", snap.target)
      })
    }
    return arr.toString()
  }

  fun lnkBoxDisconnect(id: String): String =
    com.google.ai.edge.gallery.data.lnkbox.LnkBoxBridge.close(id)

  // ─────────────────────────────────────────────────────────────────────────
  //  FILE_BOX — JGit + BusyBox file IO inside the workspace
  // ─────────────────────────────────────────────────────────────────────────

  /** Write a file inside the file_box workspace, creating parent dirs as needed. */
  fun fileBoxWrite(relativePath: String, content: String): String {
    val safe = relativePath.trimStart('/')
    val target = File(BusyBoxBridge.defaultWorkDir(context), safe).canonicalFile
    val root = BusyBoxBridge.defaultWorkDir(context).canonicalFile
    if (!target.absolutePath.startsWith(root.absolutePath)) return "[fileBoxWrite error: path traversal blocked]"
    return runCatching {
      target.parentFile?.mkdirs()
      target.writeText(content)
      "[fileBoxWrite ok: ${target.absolutePath}]"
    }.getOrElse { "[fileBoxWrite error: ${it.message}]" }
  }

  /** Read a file from the file_box workspace. */
  fun fileBoxRead(relativePath: String): String {
    val safe = relativePath.trimStart('/')
    val target = File(BusyBoxBridge.defaultWorkDir(context), safe).canonicalFile
    val root = BusyBoxBridge.defaultWorkDir(context).canonicalFile
    if (!target.absolutePath.startsWith(root.absolutePath)) return "[fileBoxRead error: path traversal blocked]"
    if (!target.isFile) return "[fileBoxRead error: not a file: $relativePath]"
    return runCatching { target.readText() }.getOrElse { "[fileBoxRead error: ${it.message}]" }
  }

  /** List a directory inside the file_box workspace. */
  fun fileBoxList(relativeDir: String = ""): String = runBlocking {
    val safe = relativeDir.trimStart('/')
    val target = File(BusyBoxBridge.defaultWorkDir(context), safe).canonicalFile
    val root = BusyBoxBridge.defaultWorkDir(context).canonicalFile
    if (!target.absolutePath.startsWith(root.absolutePath)) return@runBlocking "[fileBoxList error: path traversal blocked]"
    if (!target.isDirectory) return@runBlocking "[fileBoxList error: not a directory: $relativeDir]"
    val res = BusyBoxBridge.exec(context, "ls", listOf("-la", target.absolutePath))
    if (res.isSuccess) res.stdout else "[fileBoxList error: ${res.stderr}]"
  }

  /** Hard-coded shortcut: `git pull` the agent's own CLU-BOX repository. */
  fun fileBoxGitPullSelf(branch: String = "main"): String = runBlocking {
    when (val r = JGitManager.pull(context, "self", branch)) {
      is JGitManager.Result.Ok -> r.message
      is JGitManager.Result.Err -> "[fileBoxGitPullSelf error: ${r.message}]"
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  //  BRAIN_BOX — RAG / graph memory CRUD
  // ─────────────────────────────────────────────────────────────────────────

  /** Search BrainBox neurons (returns JSON array of label/type/content tuples). */
  fun brainBoxRecall(query: String): String = runBlocking {
    val dao = GraphDatabase.getInstance(context).brainBoxDao()
    val matches = dao.searchNeurons(query)
    val arr = JSONArray()
    matches.forEach { n ->
      arr.put(JSONObject().apply {
        put("label", n.label)
        put("type", n.type)
        put("isCore", n.isCore)
        put("content", n.content)
        put("synapses", n.synapses)
      })
    }
    arr.toString()
  }

  /**
   * Store (forge) a new **EPISODIC** neuron — the only memory tier the AI is
   * allowed to author. Returns `[brainBoxStore ok: id=…]` on success.
   *
   * Note: `isCore` is hard-wired to `false` here. CORE memories may only be
   * created by the user through the BrainBox UI — the AI cannot promote its
   * own EPISODIC neurons to CORE through this API.
   */
  fun brainBoxStore(label: String, type: String, content: String, synapses: String): String = runBlocking {
    val dao = GraphDatabase.getInstance(context).brainBoxDao()
    runCatching {
      val id = UUID.randomUUID().toString()
      val entity = com.google.ai.edge.gallery.data.brainbox.NeuronEntity(
        id = id,
        label = label,
        type = type,
        content = content,
        synapses = synapses,
        isCore = false, // explicit: CORE provenance is reserved for the user.
      )
      dao.insertNeuron(entity)
      "[brainBoxStore ok: id=$id]"
    }.getOrElse { "[brainBoxStore error: ${it.message}]" }
  }

  /**
   * AI-safe partial update of an existing EPISODIC neuron. Returns a JSON
   * envelope `{ok, updated}` where `updated` is the rows-affected count.
   *
   * If the row is CORE the underlying SQL `WHERE isCore = 0` clause produces
   * 0 affected rows, so the agent simply receives `ok=false, updated=0` and
   * the user's curated memory is preserved untouched.
   */
  fun brainBoxUpdate(id: String, content: String, synapses: String = "", falsePaths: String = ""): String = runBlocking {
    val dao = GraphDatabase.getInstance(context).brainBoxDao()
    val updated = runCatching { dao.updateEpisodicFields(id, content, synapses, falsePaths) }.getOrElse {
      return@runBlocking "[brainBoxUpdate error: ${it.message}]"
    }
    val current = runCatching { dao.getNeuronById(id) }.getOrNull()
    JSONObject().apply {
      put("ok", updated > 0)
      put("updated", updated)
      put("isCore", current?.isCore ?: false)
      put("reason", if (updated == 0) {
        if (current == null) "id not found" else "CORE memory is immutable for the AI"
      } else "")
    }.toString()
  }

  /**
   * AI-safe delete ("forget") of an EPISODIC neuron. CORE neurons are
   * explicitly protected by the SQL clause; if the agent attempts to delete
   * one it receives a structured refusal envelope instead of a silent ok.
   */
  fun brainBoxForget(id: String): String = runBlocking {
    val dao = GraphDatabase.getInstance(context).brainBoxDao()
    val before = runCatching { dao.getNeuronById(id) }.getOrNull()
    val deleted = runCatching { dao.deleteEpisodicById(id) }.getOrElse {
      return@runBlocking "[brainBoxForget error: ${it.message}]"
    }
    JSONObject().apply {
      put("ok", deleted > 0)
      put("deleted", deleted)
      put("reason", when {
        deleted > 0 -> ""
        before == null -> "id not found"
        before.isCore -> "CORE memory is immutable for the AI"
        else -> "unknown"
      })
    }.toString()
  }

  // ─────────────────────────────────────────────────────────────────────────
  //  CLN_BOX — recipe / persona engine
  // ─────────────────────────────────────────────────────────────────────────

  private fun clnDir(): File = File(context.filesDir, "cln_box").apply { mkdirs() }

  /** Create or overwrite a persona. Returns a JSON envelope. */
  fun clnBoxCreate(name: String, systemPrompt: String, traits: String = ""): String {
    val f = File(clnDir(), "$name.json")
    val json = JSONObject().apply {
      put("name", name)
      put("systemPrompt", systemPrompt)
      put("traits", traits)
      put("updatedAt", System.currentTimeMillis())
    }
    return runCatching {
      f.writeText(json.toString())
      json.toString()
    }.getOrElse { "[clnBoxCreate error: ${it.message}]" }
  }

  fun clnBoxRead(name: String): String {
    val f = File(clnDir(), "$name.json")
    if (!f.isFile) return "[clnBoxRead error: '$name' not found]"
    return runCatching { f.readText() }.getOrElse { "[clnBoxRead error: ${it.message}]" }
  }

  fun clnBoxList(): String {
    val arr = JSONArray()
    clnDir().listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name }?.forEach { f ->
      runCatching { arr.put(JSONObject(f.readText())) }
    }
    return arr.toString()
  }

  fun clnBoxDelete(name: String): String =
    if (File(clnDir(), "$name.json").delete()) "[clnBoxDelete ok: $name]" else "[clnBoxDelete error: $name]"

  /**
   * Resolves a persona by name and returns its `systemPrompt` string ready to
   * be prepended to a delegated agent's context. Returns an empty string when
   * the persona is missing so callers can degrade gracefully.
   *
   * The delegation engine ([com.google.ai.edge.gallery.customtasks.agentchat.DelegationEngine])
   * uses this to materialise sub-worker prompts.
   */
  fun clnBoxApply(name: String): String {
    val f = File(clnDir(), "$name.json")
    if (!f.isFile) return ""
    return runCatching {
      JSONObject(f.readText()).optString("systemPrompt", "")
    }.getOrDefault("")
  }

  // ─────────────────────────────────────────────────────────────────────────
  //  Misc / diagnostics
  // ─────────────────────────────────────────────────────────────────────────

  /** Quick BusyBox shell helper exposed to Python. */
  fun shell(line: String): String = runBlocking {
    val r = BusyBoxBridge.shell(context, line)
    JSONObject().apply {
      put("exit", r.exitCode)
      put("stdout", r.stdout)
      put("stderr", r.stderr)
      put("ms", r.durationMs)
    }.toString()
  }

  // ─────────────────────────────────────────────────────────────────────────
  //  Helpers
  // ─────────────────────────────────────────────────────────────────────────

  private fun extractDocstring(source: String): String {
    val triple = Regex("\"\"\"([\\s\\S]*?)\"\"\"")
    return triple.find(source)?.groupValues?.getOrNull(1)?.trim() ?: ""
  }
}
