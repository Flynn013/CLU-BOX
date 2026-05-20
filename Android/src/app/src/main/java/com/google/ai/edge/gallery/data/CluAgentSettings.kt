/*
 * Copyright 2026 Flynn013 / CLU-BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.data

import android.content.Context

/**
 * Lightweight key-value store for agent-level settings (non-model config).
 *
 * Currently exposes a single toggle:
 * - **stepModeEnabled** — when true, [SkillRegistry.buildFinalSystemPrompt] appends a
 *   STEP MODE addendum that tells CLU to pause after each tool result and ask
 *   "▶ Continue?" before proceeding to the next tool call.
 */
object CluAgentSettings {

    private const val PREFS_FILE    = "clu_agent_settings"
    private const val KEY_STEP_MODE = "step_mode_enabled"

    @Volatile private var _stepMode: Boolean = false
    val stepModeEnabled: Boolean get() = _stepMode

    fun load(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        _stepMode = prefs.getBoolean(KEY_STEP_MODE, false)
        return _stepMode
    }

    fun save(context: Context, enabled: Boolean) {
        _stepMode = enabled
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STEP_MODE, enabled)
            .apply()
    }
}
