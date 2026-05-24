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

sealed class AgentEvent {
  data object AssistantTurn : AgentEvent()

  data class Token(val text: String) : AgentEvent()

  data class Thinking(val text: String) : AgentEvent()

  data class Complete(val text: String) : AgentEvent()

  data class Error(val message: String) : AgentEvent()

  data class ToolStart(
    val id: String,
    val name: String,
    val input: String,
  ) : AgentEvent()

  data class ToolEnd(
    val id: String,
    val name: String,
    val output: String,
    val isError: Boolean,
  ) : AgentEvent()
}
