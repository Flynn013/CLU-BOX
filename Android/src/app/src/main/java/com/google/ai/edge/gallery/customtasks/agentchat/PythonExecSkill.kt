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

import com.google.ai.edge.gallery.data.python.PythonBridge
import org.json.JSONObject

/**
 * **PYTHON_EXEC** — gives the LLM agent direct access to the on-device
 * CPython 3.11 interpreter via [PythonBridge].
 *
 * ## Routing guidance (enforced by [SystemPromptManager])
 * Prefer `PYTHON_EXEC` for:
 * - Arithmetic and mathematical calculations.
 * - File parsing / text transformation / formatting.
 * - Local database queries or data manipulation.
 * - Any task expressible in pure Python without Linux binaries.
 *
 * Reserve `SHELL_EXEC` exclusively for tasks requiring Linux-specific
 * binaries: `git`, `curl`, `node` MCP servers, or POSIX system tools.
 *
 * ## Execution model
 * Each script runs in an isolated namespace — no shared state between calls.
 * All I/O is captured; nothing is written to Logcat by the script itself.
 * The tool **always returns a string** (never throws), so the agent loop
 * always receives an observable result.
 *
 * ## JSON schema
 * ```json
 * { "python_script": "<Python source code string>" }
 * ```
 */
class PythonExecSkill : CluSkill {

  override val name = "PYTHON_EXEC"

  override val description =
    "Execute a Python 3.11 script on-device using the embedded CPython interpreter. " +
      "PREFERRED for: math, data analysis, JSON processing, text transformation, file parsing, " +
      "and any logic expressible in pure Python. " +
      "Pre-imported: math, json, re, os, datetime, pathlib, collections, itertools, " +
      "hashlib, base64, csv, random, textwrap, numpy, requests. " +
      "Also: Splinter (CLU/BOX API for file/memory/shell access). " +
      "Each call runs isolated — no state shared between calls."

  override val jsonSchema =
    """
    {
      "name": "PYTHON_EXEC",
      "description": "Run a Python 3.11 script on-device. Common modules pre-imported. Returns stdout+stderr.",
      "parameters": {
        "type": "object",
        "properties": {
          "python_script": {
            "type": "string",
            "description": "Complete Python 3 source code to execute. Common modules already imported."
          }
        },
        "required": ["python_script"]
      }
    }
    """.trimIndent()

  override val fewShotExample =
    """
    // Math — no imports needed:
    PYTHON_EXEC(python_script="print(math.sqrt(144))")  → 12.0
    // Data — numpy pre-imported:
    PYTHON_EXEC(python_script="arr=numpy.array([1,2,3]); print(arr.mean())")  → 2.0
    // File via Splinter:
    PYTHON_EXEC(python_script="data=Splinter.fileBoxRead('notes.txt'); print(len(data), 'chars')")
    """.trimIndent()

  override suspend fun execute(args: JSONObject): String {
    val script = args.optString("python_script")
    if (script.isBlank()) {
      return "[PYTHON_EXEC Error: 'python_script' argument is required and must not be blank.]"
    }
    return PythonBridge.executeScript(script)
  }
}
