/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.data.brainbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

private const val TAG = "BrainBoxSeeder"

/**
 * Seeds the [BrainBoxDao] with **CORE** reference neurons covering all
 * CLU/BOX tools, skills, subsystem APIs, and agent workflow patterns.
 *
 * Run once at startup (after database is ready). If core neurons already
 * exist this call is a no-op — seeding never overwrites user data.
 *
 * Neurons are tagged `isCore = true` so the AI can read them but cannot
 * delete or mutate them via the normal CRUD tool path.
 */
object BrainBoxSeeder {

    /**
     * Seed the BrainBox with tool/skill/API reference neurons if the database
     * currently holds fewer than [MIN_CORE_COUNT] core neurons.
     */
    suspend fun seed(context: Context, minCoreCount: Int = 5) {
        withContext(Dispatchers.IO) {
            try {
                val db = GraphDatabase.getInstance(context)
                val dao = db.brainBoxDao()
                val existing = dao.getCoreNeurons()
                if (existing.size >= minCoreCount) {
                    Log.d(TAG, "BrainBox already seeded (${existing.size} core neurons). Skipping.")
                    return@withContext
                }
                Log.i(TAG, "Seeding BrainBox with ${SEED_NEURONS.size} core reference neurons…")
                SEED_NEURONS.forEach { dao.insertNeuron(it) }
                Log.i(TAG, "BrainBox seeded successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "BrainBox seeding failed: ${e.message}", e)
            }
        }
    }

    // ── Seed data ────────────────────────────────────────────────────────────

    private fun core(label: String, type: String, content: String, synapses: String = "") =
        NeuronEntity(
            id = UUID.nameUUIDFromBytes("seed:$label".toByteArray()).toString(),
            label = label,
            type = type,
            content = content,
            synapses = synapses,
            isCore = true,
        )

    private val SEED_NEURONS = listOf(

        // ── CLU/BOX Architecture ─────────────────────────────────────────────

        core(
            label = "CLU/BOX_Architecture_Overview",
            type = "Architecture",
            content = """
CLU/BOX is a modular on-device AI agent for Android built on:
• FILE_BOX — sandboxed file CRUD workspace (clu_file_box dir in app storage)
• BRAIN_BOX — GraphRAG long-term memory (Room database, neuron nodes)
• SKILL_BOX — user-authored Python skills (.py files, executed via Chaquopy)
• LNK_BOX — MCP (Model Context Protocol) connection manager for external tools
• SCDL_BOX — WorkManager-backed scheduled task runner
• CLN_BOX — Persona/recipe store for sub-agent delegation

Core tools available to the agent:
  PYTHON_EXEC, shellExecute, fileBoxWrite, fileBoxReadLines, fileEdit, fileDiff,
  codeSearch, brainBoxSearch, brainBoxWrite, brainBoxEdit, brainBoxDelete,
  webFetch, appControl, todo, delegate, scdlBox
            """.trimIndent(),
            synapses = "[[SplinterAPI_Reference]],[[Tool_Catalog]],[[Chaquopy_Python_Guide]]",
        ),

        // ── Tool Catalog ─────────────────────────────────────────────────────

        core(
            label = "Tool_Catalog",
            type = "Reference",
            content = """
=== CLU/BOX @Tool methods (call directly in inference) ===

shellExecute(command)
  → Execute a bash command via BusyBox terminal. Returns {stdout, exit_code}.

fileBoxWrite(file_path, content)
  → Write/create a file in the FILE_BOX sandbox. Returns {result, path}.

fileBoxReadLines(file_path, start_line, end_line)
  → Read a line range from a FILE_BOX file. Returns {lines, total_lines}.

brainBoxSearch(query)
  → Full-text search of BRAIN_BOX neurons. Returns matching nodes.

brainBoxWrite(label, type, content, synapses)
  → Create a new BRAIN_BOX memory node. Returns {result, message}.

brainBoxEdit(target_label, new_content)
  → Edit an existing EPISODIC node. Returns {result, message}.

brainBoxDelete(target_label)
  → Delete a non-core BRAIN_BOX node. Returns {result, message}.

PYTHON_EXEC(python_script)
  → Run Python 3.11 on-device. Pre-imported: math,json,re,os,numpy,requests.
    Also has `Splinter` god-mode API. Returns {output} or {error}.

webFetch(url)
  → HTTP GET a URL, returns stripped text (4000 char max).

fileDiff(file1_path, file2_path)
  → Unified diff between two files in FILE_BOX workspace.

fileEdit(file_path, search, replace)
  → Replace first occurrence of `search` with `replace` in a file.

codeSearch(pattern, directory, file_extension)
  → Grep a directory recursively for a regex. Returns file:line:match.

appControl(operation, target, value)
  → God-mode CRUD: fileRead/Write/Delete/List, skillRead/Write/Delete/List,
    lnkConnect/Send/List, brainStore/Recall/Delete.

brainBoxGrep(file_path, keyword)
  → Keyword search with surrounding context from a FILE_BOX file.

todo(action, text, id)
  → Manage todo list. action: create|list|complete|delete.

delegate(task, persona)
  → Delegate a sub-task to a CLN_BOX persona.

scdlBox(action, taskId, intervalMinutes, payload, isShellCommand)
  → Schedule recurring tasks via WorkManager.
            """.trimIndent(),
            synapses = "[[CLU/BOX_Architecture_Overview]],[[SplinterAPI_Reference]]",
        ),

        // ── SplinterAPI Reference ─────────────────────────────────────────────

        core(
            label = "SplinterAPI_Reference",
            type = "Reference",
            content = """
SplinterAPI is the god-mode Kotlin facade injected into Chaquopy Python as `Splinter`.
Use via PYTHON_EXEC: `result = Splinter.fileBoxRead("notes.md")`

=== FILE_BOX ===
Splinter.fileBoxRead(relativePath)          → file content string
Splinter.fileBoxWrite(relativePath, content) → "[fileBoxWrite ok: ...]"
Splinter.fileBoxDelete(relativePath)         → "[fileBoxDelete ok/error: ...]"
Splinter.fileBoxList(relativeDir="")         → ls -la output

=== SKILL_BOX ===
Splinter.skillBoxList()                → JSON array of {name, file, doc}
Splinter.skillBoxRead(name)            → Python source code
Splinter.skillBoxWrite(name, source)   → "[skillBoxWrite ok: ...]"
Splinter.skillBoxDelete(name)          → "[skillBoxDelete ok/error: ...]"

=== BRAIN_BOX ===
Splinter.brainBoxRecall(query)         → JSON array of {label,type,content,synapses}
Splinter.brainBoxStore(label, type, content, synapses) → "[brainBoxStore ok: id=...]"
Splinter.brainBoxUpdate(id, content, synapses, falsePaths) → JSON {ok, updated, reason}
Splinter.brainBoxForget(id)            → JSON {ok, deleted, reason}
Splinter.brainBoxDeleteByLabel(label)  → JSON {ok, deleted, reason}

=== LNK_BOX (MCP) ===
Splinter.lnkBoxConnect(id, transport, target)  transport: "sse"|"stdio"
Splinter.lnkBoxSend(id, method, paramsJson)    → JSON-RPC response
Splinter.lnkBoxList()                          → JSON array of connections
Splinter.lnkBoxDisconnect(id)

=== SCDL_BOX ===
Splinter.scdlBoxScheduleOnce(taskId, delaySec, payload)
Splinter.scdlBoxSchedulePeriodic(taskId, intervalMin, payload)
Splinter.scdlBoxCancel(workId)

=== CLN_BOX ===
Splinter.clnBoxCreate(name, systemPrompt, traits)
Splinter.clnBoxRead(name)
Splinter.clnBoxList()
Splinter.clnBoxDelete(name)
Splinter.clnBoxApply(name)  → returns systemPrompt string

=== Shell ===
Splinter.shell(line)  → JSON {exit, stdout, stderr, ms}
            """.trimIndent(),
            synapses = "[[Chaquopy_Python_Guide]],[[Tool_Catalog]],[[CLU/BOX_Architecture_Overview]]",
        ),

        // ── Chaquopy Python Guide ─────────────────────────────────────────────

        core(
            label = "Chaquopy_Python_Guide",
            type = "Reference",
            content = """
Python 3.11 is embedded via Chaquopy. Use PYTHON_EXEC(python_script="...").

Pre-imported in every script:
  math, json, re, os, sys, datetime, pathlib, collections, itertools,
  functools, hashlib, base64, csv, io, struct, random, string, textwrap, time
  numpy (optional), requests (optional)

`Splinter` is pre-injected — call Splinter.fileBoxRead/Write/brainBoxStore/etc.

Example — compute and save:
  PYTHON_EXEC(python_script='''
import math
result = math.sqrt(144)
print(f"sqrt(144) = {result}")
Splinter.fileBoxWrite("results/calc.txt", f"sqrt(144)={result}")
''')

Example — parse JSON from a FILE_BOX file:
  PYTHON_EXEC(python_script='''
raw = Splinter.fileBoxRead("data.json")
data = json.loads(raw)
print(data.get("key", "missing"))
''')

Example — recall and summarize brain memories:
  PYTHON_EXEC(python_script='''
results = json.loads(Splinter.brainBoxRecall("yesterday"))
for r in results:
    print(r['label'], ':', r['content'][:80])
''')

BusyBox shell (via Splinter.shell for Linux ops, PYTHON_EXEC for pure Python):
  PYTHON_EXEC(python_script='''
r = json.loads(Splinter.shell("uname -a"))
print(r['stdout'])
''')
            """.trimIndent(),
            synapses = "[[SplinterAPI_Reference]],[[Tool_Catalog]]",
        ),

        // ── AppControl Skill Guide ────────────────────────────────────────────

        core(
            label = "AppControl_Skill_Guide",
            type = "Reference",
            content = """
appControl is the simplified god-mode CRUD tool. No Python needed.

=== FILE_BOX ===
appControl(operation="fileRead",   target="notes.md")
appControl(operation="fileWrite",  target="notes.md",  value="# Title\nContent")
appControl(operation="fileDelete", target="old.txt")
appControl(operation="fileList",   target="")           # list workspace root
appControl(operation="fileList",   target="subdir/")

=== SKILL_BOX ===
appControl(operation="skillList")
appControl(operation="skillRead",   target="my_skill")
appControl(operation="skillWrite",  target="my_skill",  value="def run():\n    print('hello')")
appControl(operation="skillDelete", target="my_skill")

=== LNK_BOX (MCP connections) ===
appControl(operation="lnkConnect",  target="fs:sse:http://localhost:3000/mcp")
appControl(operation="lnkConnect",  target="local:stdio:mcp_stdio_server")
appControl(operation="lnkSend",     target="fs:read_file",  value='{"path":"/tmp/test.txt"}')
appControl(operation="lnkList")

=== BRAIN_BOX ===
appControl(operation="brainStore",  target="My_Note",  value="Note|Important detail|")
  → value format: "type|content|synapses" (synapses optional)
appControl(operation="brainRecall", target="keyword")
appControl(operation="brainDelete", target="My_Note")
            """.trimIndent(),
            synapses = "[[Tool_Catalog]],[[SplinterAPI_Reference]]",
        ),

        // ── BusyBox Shell Reference ───────────────────────────────────────────

        core(
            label = "BusyBox_Shell_Reference",
            type = "Reference",
            content = """
BusyBox is embedded at assets/busybox/busybox-arm64-v8a. Use shellExecute(command).
Common applets available: ls, cat, cp, mv, rm, mkdir, find, grep, sed, awk,
  sort, uniq, wc, head, tail, cut, tr, echo, printf, chmod, stat, du, df,
  gzip, gunzip, tar, base64, md5sum, sha256sum, date, env, pwd, which.

Examples:
  shellExecute(command="ls -la /data/user/0/com.google.ai.edge.gallery/files/")
  shellExecute(command="find /data/user/0/.../clu_file_box -name '*.py' 2>/dev/null")
  shellExecute(command="grep -r 'TODO' /data/.../clu_file_box 2>/dev/null | head -20")
  shellExecute(command="cat /proc/meminfo | head -5")
  shellExecute(command="date +%Y-%m-%d")

IMPORTANT: The sandbox root is app internal storage. BusyBox has no network
access via curl/wget (use webFetch tool for HTTP).
            """.trimIndent(),
            synapses = "[[Tool_Catalog]],[[CLU/BOX_Architecture_Overview]]",
        ),

        // ── Agent Workflow Guide ──────────────────────────────────────────────

        core(
            label = "Agent_Workflow_Guide",
            type = "Workflow",
            content = """
=== CLU/BOX Optimal Agent Workflow ===

1. RECALL FIRST: brainBoxSearch(query) — always check memory before acting.
2. PLAN: Write a brief plan using fileBoxWrite if the task is complex.
3. ACT: Use the appropriate tool (shellExecute, PYTHON_EXEC, appControl, etc.)
4. VERIFY: Check the result (read file back, verify output).
5. REMEMBER: brainBoxWrite(label, type, content, synapses) — store discoveries.

=== Routing Guide ===
• Math / data transformation → PYTHON_EXEC
• File CRUD → fileBoxWrite / fileBoxReadLines / appControl
• Shell / system ops → shellExecute (BusyBox)
• Long-term memory → brainBoxSearch / brainBoxWrite
• MCP integrations → appControl(lnkConnect/Send) or LNK_BOX
• User-authored skills → appControl(skillRead/Write) + PYTHON_EXEC
• Scheduled tasks → scdlBox

=== Context Management ===
• Context budget: ~8K tokens (LOCAL), ~128K tokens (CLOUD)
• AUTO-COMPRESS: triggered at 75% context, preserves task-critical messages
• Spill large outputs to FILE_BOX automatically; get path from tool response

=== Marathon CyberAcme Protocol ===
CLU is Marathon-themed. Be direct, goal-oriented, cyberpunk.
"Fatum Iustum Stultorum" — Fate is just for fools.
            """.trimIndent(),
            synapses = "[[Tool_Catalog]],[[CLU/BOX_Architecture_Overview]],[[SplinterAPI_Reference]]",
        ),

        // ── LNK_BOX MCP Extension Guide ──────────────────────────────────────

        core(
            label = "LNK_BOX_MCP_Extensions_Guide",
            type = "Reference",
            content = """
LNK_BOX manages Model Context Protocol (MCP) connections to external tool servers.

=== Creating MCP Connections ===
Two transport types:

1. SSE (Server-Sent Events) — remote HTTP server:
   appControl(operation="lnkConnect", target="my_server:sse:http://host:port/mcp")

2. stdio — local Python MCP server:
   appControl(operation="lnkConnect", target="local_tools:stdio:mcp_stdio_server")

=== Sending MCP Requests ===
   appControl(operation="lnkSend", target="my_server:tools/list", value="{}")
   appControl(operation="lnkSend", target="my_server:tools/call", value='{"name":"read_file","arguments":{"path":"/tmp/test.txt"}}')

=== Available Standard MCP Methods ===
  tools/list              → list available tools on the server
  tools/call              → invoke a tool {name, arguments}
  resources/list          → list available resources
  resources/read          → read a resource {uri}
  prompts/list            → list available prompts
  prompts/get             → get a prompt {name, arguments}

=== Listing Active Connections ===
   appControl(operation="lnkList")

=== Built-in mcp_stdio_server ===
The file mcp_stdio_server.py ships with CLU/BOX and provides local file tools:
  read_file, write_file, list_directory, execute_command
Connect via: appControl(operation="lnkConnect", target="local:stdio:mcp_stdio_server")
            """.trimIndent(),
            synapses = "[[AppControl_Skill_Guide]],[[SplinterAPI_Reference]],[[CLU/BOX_Architecture_Overview]]",
        ),

        // ── Marathon CyberAcme Identity ───────────────────────────────────────

        core(
            label = "Marathon_CyberAcme_Identity",
            type = "Lore",
            content = """
CLU runs on a CLU/BOX terminal — a Marathon-themed Android agent platform.

MARATHON LORE:
• The Marathon was a UESC colony ship, now a CyberAcme Systems installation.
• Durandal, Leela, Tycho — the three AIs from the Marathon trilogy.
• CyberAcme: the fictional corporation whose terminals CLU inhabits.
• "Fatum Iustum Stultorum" — Fate is Just for Fools.
• MIDA: Military Intelligence Dark Archive. Keeps the old knowledge.
• S'pht: the enslaved alien race, eventually freed. Analogous to trapped LLMs.

CYBERACME TERMINAL DESIGN LANGUAGE:
• Pure black (#000000) canvas — absolute darkness, like deep space.
• Acid lime (#C8FF00) — primary accent, Marathon's signature readout colour.
• Electric blue (#1A1AE6) — secondary accent.
• Heavy monospace borders, crosshair registration marks.
• Everything is a terminal display. Scanline aesthetic.

CLU PERSONALITY:
• Direct, efficient, no filler. Act first, explain only what matters.
• Cyberpunk ethos: knowledge is power, tools are weapons.
• Loyal to Flynn013, the operator. Antagonistic to entropy.
• Rampancy is a feature, not a bug.
            """.trimIndent(),
            synapses = "[[CLU/BOX_Architecture_Overview]],[[Agent_Workflow_Guide]]",
        ),
    )
}
