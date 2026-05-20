/*
 * Copyright 2026 Flynn013 / CLU/BOX
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
 * Persistent settings for CLU's agentic behaviour.
 *
 * Currently exposes a single toggle:
 *
 * - **stepModeEnabled** (default `false` = FULL AUTO).
 *   When `true`, the system prompt appends a STEP MODE directive telling the
 *   model to pause after each tool result and ask the user before continuing.
 *   This is useful for debugging or for tasks where the user wants oversight
 *   of each individual tool invocation.
 *
 * Persisted in plain [android.content.SharedPreferences] (not encrypted) since
 * these are UX preferences, not secrets.
 */
object CluAgentSettings {

    private const val PREFS_FILE    = "clu_agent_settings"
    private const val KEY_STEP_MODE = "step_mode_enabled"

    @Volatile private var _stepMode: Boolean = false

    /** In-memory snapshot of the current setting. Updated by [load] and [save]. */
    val stepModeEnabled: Boolean get() = _stepMode

    /**
     * Reads the persisted value from SharedPreferences, updates the in-memory
     * snapshot, and returns it.  Call once at screen-entry or before building a
     * system prompt.
     */
    fun load(context: Context): Boolean {
        _stepMode = context.applicationContext
            .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_STEP_MODE, false)
        return _stepMode
    }

    /** Persists [enabled] and updates the in-memory snapshot. */
    fun save(context: Context, enabled: Boolean) {
        _stepMode = enabled
        context.applicationContext
            .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STEP_MODE, enabled)
            .apply()
    }
}
