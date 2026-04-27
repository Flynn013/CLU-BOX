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

package com.google.ai.edge.gallery.data.python

import android.content.Context
import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "PythonBridge"

/**
 * Singleton bridge to the on-device CPython 3.11 interpreter (Chaquopy).
 *
 * **Lifecycle**
 * Call [initialize] exactly once on the main thread (e.g. in
 * `Application.onCreate()`). All subsequent [executeScript] calls are safe
 * to issue from any thread — they automatically dispatch to [Dispatchers.IO].
 *
 * **Sandboxing**
 * Each script is executed in a fresh, empty namespace (`{}`), so scripts
 * cannot mutate the interpreter's global state or each other's variables.
 * stdout/stderr are captured per-execution via in-memory StringIO buffers
 * (see `clu_runner.py`) and returned as Kotlin strings — nothing is written
 * to Logcat by the script itself.
 *
 * **Error handling**
 * Exceptions thrown inside the script are caught by `clu_runner.run()` and
 * returned as formatted tracebacks in the stderr component. Fatal interpreter
 * errors (rare) are caught at the Kotlin layer and returned as an error string,
 * never propagated as an uncaught exception.
 */
object PythonBridge {

  @Volatile private var ready = false

  /**
   * Starts the Python interpreter using the application [Context].
   *
   * Must be called on the **main thread** before any [executeScript] call.
   * Calling this a second time is a no-op.
   */
  fun initialize(context: Context) {
    if (ready) return // volatile fast-path: avoids locking on every subsequent call
    synchronized(this) {
      // Second check inside the lock — standard double-checked locking (DCL).
      // `ready` is @Volatile so the write on the last line is visible to all threads
      // immediately after we exit this block.
      if (ready) return
      if (!Python.isStarted()) {
        Python.start(AndroidPlatform(context.applicationContext))
      }
      ready = true
      Log.d(TAG, "Python 3 interpreter started via Chaquopy")
    }
  }

  /**
   * Executes [script] on [Dispatchers.IO] and returns the captured output.
   *
   * The returned string contains:
   * - The script's stdout (if any).
   * - A `[stderr]` block containing the script's stderr / exception traceback
   *   (if any error occurred).
   * - `[No output]` if the script produced no output and no error.
   *
   * This method **never throws** — all errors are returned as strings so the
   * agent loop always gets a usable observation to reason about.
   *
   * @param script Full Python source code to execute.
   * @return Captured output string, always non-null.
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
      // clu_runner.run(code) → (stdout, stderr) tuple
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
}
