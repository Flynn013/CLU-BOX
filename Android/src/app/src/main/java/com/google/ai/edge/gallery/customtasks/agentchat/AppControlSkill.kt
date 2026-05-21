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

import android.util.Log
import com.google.ai.edge.gallery.common.SkillProgressAgentAction
import com.google.ai.edge.gallery.data.splinter.SplinterAPI
import org.json.JSONObject

private const val TAG = "AppControlSkill"

/**
 * **appControl** — God-mode CRUD skill that wraps the [SplinterAPI] Chaquopy
 * bridge, giving models direct read / write / edit / delete access to every
 * CLU/BOX subsystem **without** writing Python code.
 *
 * ## Operations
 * | operation       | target                  | value (required?)  |
 * |-----------------|-------------------------|--------------------|
 * | fileRead        | relative path           | —                  |
 * | fileWrite       | relative path           | file content       |
 * | fileDelete      | relative path           | —                  |
 * | fileList        | directory path or ""    | —                  |
 * | skillRead       | skill name              | —                  |
 * | skillWrite      | skill name              | Python source      |
 * | skillDelete     | skill name              | —                  |
 * | skillList       | —                       | —                  |
 * | lnkConnect      | "id:transport:target"   | —                  |
 * | lnkSend         | "id:method"             | JSON params        |
 * | lnkList         | —                       | —                  |
 * | brainStore      | label                   | "type|content|syn" |
 * | brainRecall     | query                   | —                  |
 * | brainDelete     | exact label             | —                  |
 *
 * Backed entirely by [SplinterAPI.INSTANCE] — no Python interpreter round-trip
 * for basic operations.
 */
class AppControlSkill(private val agentTools: AgentTools) : CluSkill {

    override val name = "appControl"

    override val description =
        "God-mode CRUD for all CLU/BOX subsystems via SplinterAPI. " +
            "Supports: fileRead/Write/Delete/List (FILE_BOX), " +
            "skillRead/Write/Delete/List (SKILL_BOX Python skills), " +
            "lnkConnect/Send/List (LNK_BOX MCP connections), " +
            "brainStore/Recall/Delete (BRAIN_BOX neurons). " +
            "Use 'operation' to specify the action, 'target' for the resource, 'value' for content."

    override val jsonSchema =
        """
        {
          "name": "appControl",
          "description": "Direct CRUD over FILE_BOX, SKILL_BOX, LNK_BOX, and BRAIN_BOX via SplinterAPI.",
          "parameters": {
            "type": "object",
            "properties": {
              "operation": {
                "type": "string",
                "description": "One of: fileRead, fileWrite, fileDelete, fileList, skillRead, skillWrite, skillDelete, skillList, lnkConnect, lnkSend, lnkList, brainStore, brainRecall, brainDelete"
              },
              "target": {
                "type": "string",
                "description": "Resource target: file path, skill name, lnk id, brain label, or compound 'id:method' for lnkSend / 'id:transport:url' for lnkConnect. Leave empty for list ops."
              },
              "value": {
                "type": "string",
                "description": "Content/payload. Required for write ops. For brainStore: 'type|content|synapses'. For lnkSend: JSON params. For lnkConnect target is 'id:transport:url'."
              }
            },
            "required": ["operation"]
          }
        }
        """.trimIndent()

    override val fewShotExample =
        """
        appControl(operation="fileRead", target="notes.md")
        appControl(operation="fileWrite", target="plan.txt", value="# Today\n- Refactor")
        appControl(operation="skillList")
        appControl(operation="brainStore", target="Marathon_Lore", value="Lore|CyberAcme built the Marathon|")
        appControl(operation="brainRecall", target="Marathon")
        appControl(operation="lnkConnect", target="fs:stdio:/data/mcp/filesystem")
        """.trimIndent()

    override suspend fun execute(args: JSONObject): String {
        val operation = args.optString("operation").trim()
        val target = args.optString("target", "").trim()
        val value = args.optString("value", "").trim()
        val splinter = SplinterAPI.INSTANCE
        Log.d(TAG, "appControl: op=$operation target=$target")
        agentTools.sendAgentAction(SkillProgressAgentAction(label = "appControl: $operation", inProgress = true))

        val result = when (operation) {
            "fileRead" -> {
                if (target.isEmpty()) return "[appControl error: target (file path) required for fileRead]"
                splinter.fileBoxRead(target)
            }
            "fileWrite" -> {
                if (target.isEmpty()) return "[appControl error: target (file path) required for fileWrite]"
                if (value.isEmpty()) return "[appControl error: value (content) required for fileWrite]"
                splinter.fileBoxWrite(target, value)
            }
            "fileDelete" -> {
                if (target.isEmpty()) return "[appControl error: target (file path) required for fileDelete]"
                splinter.fileBoxDelete(target)
            }
            "fileList" -> splinter.fileBoxList(target)
            "skillRead" -> {
                if (target.isEmpty()) return "[appControl error: target (skill name) required for skillRead]"
                splinter.skillBoxRead(target)
            }
            "skillWrite" -> {
                if (target.isEmpty()) return "[appControl error: target (skill name) required for skillWrite]"
                if (value.isEmpty()) return "[appControl error: value (Python source) required for skillWrite]"
                splinter.skillBoxWrite(target, value)
            }
            "skillDelete" -> {
                if (target.isEmpty()) return "[appControl error: target (skill name) required for skillDelete]"
                splinter.skillBoxDelete(target)
            }
            "skillList" -> splinter.skillBoxList()
            "lnkConnect" -> {
                // target format: "id:transport:url"
                val parts = target.split(":", limit = 3)
                if (parts.size < 3) return "[appControl error: lnkConnect target must be 'id:transport:url']"
                splinter.lnkBoxConnect(parts[0], parts[1], parts[2])
            }
            "lnkSend" -> {
                // target format: "id:method"
                val parts = target.split(":", limit = 2)
                if (parts.size < 2) return "[appControl error: lnkSend target must be 'id:method']"
                splinter.lnkBoxSend(parts[0], parts[1], value.ifEmpty { "{}" })
            }
            "lnkList" -> splinter.lnkBoxList()
            "brainStore" -> {
                if (target.isEmpty()) return "[appControl error: target (label) required for brainStore]"
                // value format: "type|content|synapses"
                val parts = value.split("|", limit = 3)
                val type = parts.getOrNull(0)?.trim()?.ifEmpty { "Note" } ?: "Note"
                val content = parts.getOrNull(1)?.trim() ?: value
                val synapses = parts.getOrNull(2)?.trim() ?: ""
                splinter.brainBoxStore(target, type, content, synapses)
            }
            "brainRecall" -> {
                if (target.isEmpty()) return "[appControl error: target (query) required for brainRecall]"
                splinter.brainBoxRecall(target)
            }
            "brainDelete" -> {
                if (target.isEmpty()) return "[appControl error: target (label) required for brainDelete]"
                splinter.brainBoxDeleteByLabel(target)
            }
            else -> "[appControl error: unknown operation '$operation'. Valid ops: fileRead/Write/Delete/List, skillRead/Write/Delete/List, lnkConnect/Send/List, brainStore/Recall/Delete]"
        }
        agentTools.sendAgentAction(SkillProgressAgentAction(
            label = "appControl: $operation done",
            inProgress = false,
            addItemTitle = "appControl:$operation",
            addItemDescription = result.take(80),
        ))
        return result
    }
}
