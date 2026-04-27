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

/**
 * Runtime governor for the autonomous agentic loop.
 *
 * @param maxLoops Maximum consecutive autonomous iterations before the circuit
 *                 breaker halts the loop to protect device stability.
 */
data class AgentGovernor(
  // 25 iterations balances giving the model enough attempts to complete multi-step tasks
  // while preventing runaway loops that could drain battery or exhaust context on device.
  val maxLoops: Int = 25,
) {
  companion object {
    /**
     * Additional system constraint injected when running on a LOCAL engine.
     * Reminds the model to be concise given the 4k token context window.
     */
    val LOCAL_CONSTRAINT: String = """
[LOCAL ENGINE CONSTRAINT]
Context window: ~4096 tokens. Be extremely concise in every response.
Prefer short tool calls over lengthy explanations. Omit preamble.
""".trimIndent()

    /**
     * Additional system constraint injected when running on the CLOUD engine.
     * Allows fuller responses since the Gemini API has a larger context window.
     */
    val CLOUD_CONSTRAINT: String = """
[CLOUD ENGINE — Full Context Available]
Gemini API model active. Structured, detailed responses are acceptable.
Use best practices for code quality and explanations.
""".trimIndent()
  }
}
