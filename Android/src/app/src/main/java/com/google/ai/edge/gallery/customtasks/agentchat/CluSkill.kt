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

import org.json.JSONObject

/**
 * Contract for a modular CLU/BOX skill.
 *
 * Each skill provides:
 * - **Metadata** ([name], [description], [jsonSchema], [fewShotExample])
 *   used by [SkillRegistry] to dynamically generate the system prompt.
 * - An [execute] method that performs the actual work when dispatched
 *   by the execution router.
 *
 * Implementations are registered in [SkillRegistry] and their metadata
 * is injected into the LLM's context window at boot time.
 *
 * Note: In the current architecture, tool dispatch is handled by the
 * litertlm `@Tool` framework on [AgentTools]. The [execute] method
 * provides a secondary dispatch path for skills that need to be
 * invoked programmatically or through a manual routing layer.
 */
interface CluSkill {

  /** Unique identifier matching the `@Tool` method name in [AgentTools]. */
  val name: String

  /** Strict usage description including any prohibitions or fencing rules. */
  val description: String

  /** Exact JSON schema the model must produce for this tool's arguments. */
  val jsonSchema: String

  /** A flawless few-shot example demonstrating correct tool invocation. */
  val fewShotExample: String

  /**
   * Execute the skill with the given arguments.
   *
   * @param args Parsed JSON object containing the tool arguments.
   * @return A human-readable result string.
   */
  suspend fun execute(args: JSONObject): String
}
