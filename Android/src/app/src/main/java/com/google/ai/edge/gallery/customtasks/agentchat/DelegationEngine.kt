/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.splinter.SplinterAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

private const val TAG = "DelegationEngine"

/**
 * **DelegationEngine** — the primary agent's "spawn sub-worker" capability.
 *
 * The native Kotlin tool exposed to the model (`delegate_to`) packages a
 * persona name from [SplinterAPI.clnBoxApply], a task prompt, and an optional
 * timeout, then spins up a fresh [ContinuousAgentDriver] inside a child
 * coroutine. The sub-worker runs the same Goose-style loop as the parent
 * but with its own context window, its own error budget, and a system prompt
 * resolved from the named CLN_BOX persona.
 *
 * Each delegated job collects its final assistant message and returns it as
 * a synthesis blob, which the parent agent then appends to its own context
 * window as a `TOOL` observation. This lets a single high-level instruction
 * fan out into many parallel research / coding / planning jobs and converge
 * back into the parent's reasoning thread without any HTTP layer.
 *
 * The engine intentionally keeps no persistent state aside from the in-memory
 * map of [active] jobs — restarting the app drops every in-flight delegation,
 * which matches the user's expectation that delegation is a session-local
 * primitive.
 */
class DelegationEngine(
  private val context: Context,
  private val parentLoopManager: AgentLoopManager,
  private val parentAgentTools: AgentTools,
  private val parentSkillRegistry: SkillRegistry,
  private val inferenceFactory: (persona: String) -> ContinuousAgentDriver.InferenceAdapter,
) {

  /** Coroutine scope owning every delegated worker. */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** Snapshot of an in-flight or completed delegation. */
  data class Job(
    val id: String,
    val persona: String,
    val prompt: String,
    val startedAtMs: Long,
    var finishedAtMs: Long? = null,
    var result: String? = null,
    var error: String? = null,
  )

  private val active = ConcurrentHashMap<String, Job>()

  /**
   * Delegate a single task to a persona-bound sub-worker.
   *
   * @param persona  Name of the CLN_BOX persona whose `systemPrompt` will seed
   *                 the sub-worker. Falls back to a generic helper preamble
   *                 when the persona is missing.
   * @param prompt   Task description the sub-worker will receive as the seed
   *                 user message.
   * @param timeoutMs Hard wall-clock deadline; the worker is cancelled and the
   *                  job marked as `error="timeout"` when it elapses.
   *                  Defaults to 90s.
   * @return         The final assistant text emitted by the sub-worker.
   */
  suspend fun delegate(persona: String, prompt: String, timeoutMs: Long = 90_000): String {
    val systemPrompt = SplinterAPI.INSTANCE.clnBoxApply(persona).ifBlank {
      "You are CLU/BOX delegated worker '$persona'. Solve the task with the available tools and return only the final answer."
    }
    val job = Job(
      id = UUID.randomUUID().toString(),
      persona = persona,
      prompt = prompt,
      startedAtMs = System.currentTimeMillis(),
    )
    active[job.id] = job

    val workerGovernor = AgentGovernor(maxLoops = 12)
    val workerLoop = AgentLoopManager()
    val driver = ContinuousAgentDriver(
      governor = workerGovernor,
      loopManager = workerLoop,
      agentTools = parentAgentTools,
      skillRegistry = parentSkillRegistry,
      inference = inferenceFactory(persona),
    )

    return try {
      val result = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<String> { cont ->
          driver.start(prompt, systemPrompt)
          // Poll the running flag; complete when the driver finishes.
          scope.launch {
            while (driver.running.value || driver.snapshotContext().isEmpty()) {
              kotlinx.coroutines.delay(100)
            }
            val finalText = driver.snapshotContext()
              .lastOrNull { it.role == ContinuousAgentDriver.ContextEntry.Role.ASSISTANT }
              ?.content ?: "[delegate '$persona' produced no output]"
            cont.resume(finalText)
          }
          cont.invokeOnCancellation { driver.stop() }
        }
      } ?: run {
        driver.stop()
        job.error = "timeout after ${timeoutMs}ms"
        "[delegate '$persona' timed out]"
      }
      job.result = result
      job.finishedAtMs = System.currentTimeMillis()
      result
    } catch (t: Throwable) {
      job.error = t.message
      job.finishedAtMs = System.currentTimeMillis()
      Log.e(TAG, "delegate '$persona' crashed", t)
      "[delegate '$persona' error: ${t.message}]"
    }
  }

  /**
   * Delegate the same prompt to many personas in parallel and synthesise the
   * results into a single JSON envelope: `[{persona, ok, output}, …]`.
   */
  suspend fun fanOut(personas: List<String>, prompt: String, timeoutMs: Long = 90_000): String {
    val deferred: List<Deferred<JSONObject>> = personas.map { persona ->
      scope.async {
        val out = delegate(persona, prompt, timeoutMs)
        JSONObject().apply {
          put("persona", persona)
          put("ok", !out.startsWith("[delegate"))
          put("output", out)
        }
      }
    }
    val arr = JSONArray()
    deferred.awaitAll().forEach(arr::put)
    return arr.toString(2)
  }

  /** Snapshot list of every job the engine has ever spawned this session. */
  fun snapshot(): List<Job> = active.values.sortedBy { it.startedAtMs }

  /** Cancel every running worker (e.g. on session reset). */
  fun shutdown() {
    scope.cancel()
    active.clear()
  }

  // ─────────────────────────────────────────────────────────────────────────
  //  Native CluSkill adapter — exposes `delegate_to` to the parent agent
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Wraps the engine as a [CluSkill] so the parent agent can invoke
   * delegation through the same tool-call channel as any other native skill.
   *
   * The model emits `{"persona": "kotlin_coder", "prompt": "Refactor X"}`
   * and the registry routes it here; the engine returns the sub-worker's
   * synthesis, which the parent's [ContinuousAgentDriver] appends to its
   * context as a TOOL observation.
   */
  fun asSkill(): CluSkill = object : CluSkill {
    override val name: String = "delegate_to"
    override val description: String =
      "Spawn a persona-bound sub-agent to handle a focused subtask in parallel and " +
        "return its synthesis. Personas are managed in CLN_BOX."

    override suspend fun execute(args: JSONObject): String {
      val personasArr = args.optJSONArray("personas")
      val prompt = args.optString("prompt").ifBlank {
        return "[delegate error: 'prompt' is required]"
      }
      val timeoutMs = args.optLong("timeoutMs", 90_000L)

      return if (personasArr != null && personasArr.length() > 0) {
        val personas = (0 until personasArr.length()).map { personasArr.getString(it) }
        fanOut(personas, prompt, timeoutMs)
      } else {
        val persona = args.optString("persona").ifBlank {
          return "[delegate error: 'persona' or 'personas' is required]"
        }
        delegate(persona, prompt, timeoutMs)
      }
    }
  }
}
