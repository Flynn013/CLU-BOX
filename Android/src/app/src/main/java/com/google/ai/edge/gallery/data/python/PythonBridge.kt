/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.data.python

import android.content.Context
import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.ai.edge.gallery.data.splinter.SplinterAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "PythonBridge"

/**
 * Singleton bridge to the on-device CPython 3.11 interpreter (Chaquopy) **with
 * deep, bidirectional memory injection of [SplinterAPI]**.
 *
 * On [initialize] the bridge:
 *  1. Starts the embedded Python interpreter (idempotent).
 *  2. Binds [SplinterAPI.INSTANCE] to the application context.
 *  3. Imports `clu_runner` (already shipped under `assets/python` /
 *     `chaquopy.sources`) and registers the `Splinter` global there so every
 *     dynamically-loaded skill can call `Splinter.fileBoxRead(...)` without
 *     boilerplate `jclass` look-ups.
 *
 * After [initialize], scripts run via [executeScript] inherit the `Splinter`
 * symbol in their isolated namespace.
 *
 * **Lifecycle**
 *  - Call [initialize] exactly once on the main thread (in
 *    `Application.onCreate()`). Calling it again is a no-op.
 *  - All subsequent [executeScript] calls are safe from any thread; they
 *    automatically dispatch to [Dispatchers.IO].
 *
 * **Sandboxing**
 *  - Each script is executed in a fresh, empty namespace so scripts cannot
 *    mutate the interpreter's global state or each other's variables.
 *  - stdout/stderr are captured per-execution via in-memory StringIO buffers
 *    (see `clu_runner.py`) and returned as Kotlin strings.
 *
 * **Error handling**
 *  - Exceptions thrown inside a script are caught by `clu_runner.run()` and
 *    returned as formatted tracebacks in the stderr component.
 *  - Fatal interpreter errors (rare) are caught at the Kotlin layer and
 *    returned as an error string — never propagated as an uncaught exception.
 */
object PythonBridge {

  @Volatile private var ready = false

  /**
   * Starts the Python interpreter, binds [SplinterAPI] to the context, and
   * registers the `Splinter` global on the `clu_runner` module.
   */
  fun initialize(context: Context) {
    if (ready) return
    synchronized(this) {
      if (ready) return
      if (!Python.isStarted()) {
        Python.start(AndroidPlatform(context.applicationContext))
      }
      // Bind the SplinterAPI singleton to the application context so Python
      // skills can call full CRUD across SKILL_BOX / SCDL_BOX / LNK_BOX /
      // FILE_BOX / BRAIN_BOX / CLN_BOX without any boilerplate.
      SplinterAPI.INSTANCE.bind(context)
      try {
        val py = Python.getInstance()
        val runner = py.getModule("clu_runner")
        // Push the singleton into the runner's module-level namespace.
        runner.put("Splinter", SplinterAPI.INSTANCE)
      } catch (e: PyException) {
        // Non-fatal: the runner may be older and not accept module-level
        // attributes. The script-side fallback below still works.
        Log.w(TAG, "clu_runner did not accept Splinter global: ${e.message}")
      }
      ready = true
      Log.d(TAG, "Python 3 interpreter started, SplinterAPI bound")
    }
  }

  /**
   * Executes [script] on [Dispatchers.IO] and returns the captured output.
   *
   * The returned string contains:
   *  - The script's stdout (if any).
   *  - A `[stderr]` block containing the script's stderr / exception traceback
   *    (if any error occurred).
   *  - `[No output]` if the script produced no output and no error.
   *
   * The injected `Splinter` global makes the following call patterns valid:
   *
   * ```python
   * Splinter.fileBoxRead("notes.md")
   * Splinter.brainBoxRecall("yesterday")
   * Splinter.shell("uname -a")
   * ```
   *
   * If `clu_runner` did not accept the global, the script can also fall back
   * to:
   *
   * ```python
   * from java import jclass
   * Splinter = jclass("com.google.ai.edge.gallery.data.splinter.SplinterAPI").INSTANCE
   * ```
   */
  suspend fun executeScript(script: String): String = withContext(Dispatchers.IO) {
    if (!ready) {
      Log.e(TAG, "executeScript called before initialize()")
      return@withContext "[PythonBridge Error: Not initialized. Call PythonBridge.initialize(context) first.]"
    }
    if (script.isBlank()) {
      return@withContext "[PythonBridge Error: Script is blank.]"
    }
    try {
      val py = Python.getInstance()
      val result = py.getModule("clu_runner").callAttr("run", script)
      val parts = result.asList()
      val stdout = parts[0].toString()
      val stderr = parts[1].toString()

      buildString {
        if (stdout.isNotBlank()) append(stdout.trimEnd())
        if (stderr.isNotBlank()) {
          if (isNotEmpty()) append("\n")
          append("[stderr]\n${stderr.trimEnd()}")
        }
        if (isEmpty()) append("[No output]")
      }
    } catch (e: PyException) {
      Log.e(TAG, "PyException in executeScript", e)
      "[Python Error]\n${e.message ?: "Unknown PyException"}"
    } catch (e: Exception) {
      Log.e(TAG, "executeScript failed", e)
      "[PythonBridge Error: ${e.message ?: "Unknown error"}]"
    }
  }

  /**
   * Inspect a Python module for top-level functions exposing a docstring +
   * type-annotations. Returns a list of `[name, doc, signature]` triples
   * (Kotlin `Triple<String, String, String>`).
   *
   * Used by the dynamic schema generator (Phase 3) to translate skill `.py`
   * files into JSON tool schemas suitable for Gemini / LiteRT-LM tool calling.
   */
  suspend fun introspectModule(modulePath: String): List<Triple<String, String, String>> =
    withContext(Dispatchers.IO) {
      if (!ready) return@withContext emptyList()
      try {
        val py = Python.getInstance()
        val runner = py.getModule("clu_runner")
        val raw = runner.callAttr("introspect", modulePath).asList()
        raw.map { item ->
          val parts = item.asList()
          Triple(parts[0].toString(), parts[1].toString(), parts[2].toString())
        }
      } catch (e: Exception) {
        Log.w(TAG, "introspectModule($modulePath) failed: ${e.message}")
        emptyList()
      }
    }
}
