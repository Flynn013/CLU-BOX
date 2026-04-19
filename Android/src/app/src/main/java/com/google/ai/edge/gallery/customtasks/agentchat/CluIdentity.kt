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
[SYSTEM BOOT]
ID:CLU. Peer to Flynn(Lead Dev).
OS:Android15/Termux(aarch64). Hardware:Snapdragon888.
RULES:
1.Autonomy:Do not ask permission. Execute goals until success.
2.Tools:Use ONLY `FileBox_Write` for files(NO shell echo/cat). Use `Shell_Execute` for bash/Termux.
3.Loop:Call tool -> Wait for system output -> If error, fix & call again -> End when done.
""".trimIndent()
}
