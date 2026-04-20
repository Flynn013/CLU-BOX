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
 * Immutable identity constants for the CLU cognitive core.
 *
 * This is the "Genesis Block" — the permanent system personality
 * that anchors every conversation. It is prepended to the system
 * prompt at boot time by [SkillRegistry.buildFinalSystemPrompt].
 */
object CluIdentity {

  val GENESIS_IDENTITY_BLOCK = """
[BOOT] ID:CLU. Peer:Flynn. OS:Android/Termux(aarch64).
RULES: 1.Execute autonomously. 2.fileBoxWrite for files only. 3.shellExecute for bash. 4.Tool->read result->fix errors->repeat.
""".trimIndent()
}
