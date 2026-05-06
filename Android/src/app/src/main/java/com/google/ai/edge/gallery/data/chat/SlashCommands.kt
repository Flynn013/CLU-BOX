/*
 * Copyright 2026 Flynn013 / CLU/BOX
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

// Slash-command set adapted from Goose slash_commands.rs; CLU/BOX-specific commands
// (/skills, /brain, /cln) replace Goose-only commands that don't map to CLU/BOX.

package com.google.ai.edge.gallery.data.chat

/**
 * A typed slash-command parsed from the user's input.
 *
 * Every command name is lowercase and prefixed with `/`.
 * Arguments after the first space are captured as [args].
 */
sealed class SlashCommand {
    // ── Session management ──────────────────────────────────────────────────

    /** `/clear` — Clear the current conversation context (start fresh without changing model). */
    object Clear : SlashCommand()

    /** `/new` — Alias for [Clear]; also accepted as a shortcut. */
    object New : SlashCommand()

    // ── Provider / model ────────────────────────────────────────────────────

    /**
     * `/provider <id>` — Switch to a different LLM provider for the current session.
     *
     * @param providerId Provider identifier, e.g. "gemini", "anthropic", "openai", "litert"
     */
    data class SwitchProvider(val providerId: String) : SlashCommand()

    /**
     * `/model <id>` — Switch to a different model within the current provider.
     *
     * @param modelId Model identifier, e.g. "gemini-2.0-flash", "claude-3-5-haiku-20241022"
     */
    data class SwitchModel(val modelId: String) : SlashCommand()

    // ── Recipe ──────────────────────────────────────────────────────────────

    /**
     * `/recipe [name]` — Load and run a recipe.
     *
     * If [name] is blank, shows the recipe library screen.
     */
    data class Recipe(val name: String = "") : SlashCommand()

    // ── Extensions / Skills ─────────────────────────────────────────────────

    /**
     * `/extension [name]` — Open the extensions management screen,
     * or show info about a specific extension.
     */
    data class Extension(val name: String = "") : SlashCommand()

    /**
     * `/skills` — Open SKILL_BOX (CLU/BOX equivalent of the Goose extension manager).
     */
    object Skills : SlashCommand()

    // ── Memory / BrainBox ───────────────────────────────────────────────────

    /**
     * `/brain <query>` — Search BRAIN_BOX episodic + semantic memory
     * and display results inline.
     *
     * @param query Free-text search query
     */
    data class Brain(val query: String) : SlashCommand()

    // ── Persona / CLN_BOX ───────────────────────────────────────────────────

    /**
     * `/cln [name]` — Switch to a CLN_BOX persona, or list available personas.
     *
     * @param name Persona name; blank to list all
     */
    data class Cln(val name: String = "") : SlashCommand()

    // ── Session history ─────────────────────────────────────────────────────

    /**
     * `/sessions` — Show the session list (history screen).
     */
    object Sessions : SlashCommand()

    // ── Todo ────────────────────────────────────────────────────────────────

    /**
     * `/todo [subcommand]` — Show the current todo list, or pass a subcommand.
     *
     * @param subcommand Optional: "clear", "show", or a description to append
     */
    data class Todo(val subcommand: String = "") : SlashCommand()

    // ── Workspace ───────────────────────────────────────────────────────────

    /**
     * `/workspace [path]` — Display or change the current workspace directory.
     */
    data class Workspace(val path: String = "") : SlashCommand()

    // ── Permissions ─────────────────────────────────────────────────────────

    /**
     * `/permissions` — Open the permissions audit screen.
     */
    object Permissions : SlashCommand()

    // ── Help ────────────────────────────────────────────────────────────────

    /**
     * `/help` — Print the list of available slash commands inline.
     */
    object Help : SlashCommand()

    /**
     * `/version` — Print CLU/BOX version info.
     */
    object Version : SlashCommand()

    // ── Unknown ─────────────────────────────────────────────────────────────

    /**
     * Returned when the input starts with `/` but does not match any known command.
     *
     * @param raw The full raw input string
     */
    data class Unknown(val raw: String) : SlashCommand()
}

// ── Parser ─────────────────────────────────────────────────────────────────────

/**
 * Parses slash-command strings typed by the user into typed [SlashCommand] instances.
 *
 * Returns `null` for inputs that do not start with `/`, indicating they should be
 * treated as ordinary chat messages.
 */
object SlashCommandParser {

    /**
     * Parses [input] into a [SlashCommand], or returns `null` if the input is not a
     * slash command (does not start with `/`).
     */
    fun parse(input: String): SlashCommand? {
        val trimmed = input.trim()
        if (!trimmed.startsWith("/")) return null

        val parts = trimmed.removePrefix("/").trim().split(Regex("\\s+"), limit = 2)
        val name = parts[0].lowercase()
        val args = if (parts.size > 1) parts[1].trim() else ""

        return when (name) {
            "clear"       -> SlashCommand.Clear
            "new"         -> SlashCommand.New
            "provider"    -> if (args.isNotBlank()) SlashCommand.SwitchProvider(args)
                             else SlashCommand.Unknown(trimmed)
            "model"       -> if (args.isNotBlank()) SlashCommand.SwitchModel(args)
                             else SlashCommand.Unknown(trimmed)
            "recipe"      -> SlashCommand.Recipe(args)
            "extension",
            "ext"         -> SlashCommand.Extension(args)
            "skills",
            "skill"       -> SlashCommand.Skills
            "brain",
            "memory"      -> SlashCommand.Brain(args)
            "cln",
            "persona"     -> SlashCommand.Cln(args)
            "sessions",
            "history"     -> SlashCommand.Sessions
            "todo"        -> SlashCommand.Todo(args)
            "workspace",
            "work",
            "cd"          -> SlashCommand.Workspace(args)
            "permissions",
            "perms"       -> SlashCommand.Permissions
            "help"        -> SlashCommand.Help
            "version"     -> SlashCommand.Version
            else          -> SlashCommand.Unknown(trimmed)
        }
    }

    /** Returns `true` if [input] starts with `/` (indicating a potential slash command). */
    fun isSlashCommand(input: String): Boolean = input.trimStart().startsWith("/")

    // ── Auto-complete ────────────────────────────────────────────────────────

    /**
     * All known command names (without the `/` prefix), sorted alphabetically.
     *
     * Use this to drive the auto-complete suggestion list in the chat input bar.
     */
    val ALL_COMMAND_NAMES: List<String> = listOf(
        "brain",
        "clear",
        "cln",
        "ext",
        "extension",
        "help",
        "history",
        "model",
        "new",
        "permissions",
        "perms",
        "provider",
        "recipe",
        "sessions",
        "skill",
        "skills",
        "todo",
        "version",
        "workspace",
    ).sorted()

    /**
     * Returns the subset of [ALL_COMMAND_NAMES] whose prefix matches [partialInput]
     * (e.g. "/c" → ["clear", "cln"]).
     *
     * [partialInput] should start with `/` but that prefix is stripped before matching.
     */
    fun completions(partialInput: String): List<String> {
        val prefix = partialInput.removePrefix("/").lowercase()
        return ALL_COMMAND_NAMES.filter { it.startsWith(prefix) }
    }

    // ── Help text ────────────────────────────────────────────────────────────

    /**
     * Human-readable help text listing all commands.
     *
     * Displayed inline when the user types `/help`.
     */
    val HELP_TEXT: String = """
**CLU/BOX Slash Commands**

**Session**
`/clear` or `/new`         — Clear the current conversation
`/sessions` or `/history`  — Browse session history

**Model & Provider**
`/provider <id>`           — Switch provider (gemini | anthropic | openai | litert)
`/model <id>`              — Switch model within the current provider

**Skills & Extensions**
`/skills`                  — Open SKILL_BOX to manage skills
`/extension [name]`        — Manage MCP extensions

**Memory**
`/brain <query>`           — Search BRAIN_BOX episodic memory

**Persona**
`/cln [name]`              — Switch CLN_BOX persona (blank = list all)

**Tasks**
`/todo [subcommand]`       — View or manage the current todo list
`/recipe [name]`           — Run a recipe (blank = browse library)

**Workspace**
`/workspace [path]`        — Show or change the active workspace directory

**Permissions**
`/permissions`             — Review tool permission rules

**Meta**
`/version`                 — Show CLU/BOX version info
`/help`                    — Show this help message
""".trimIndent()
}
