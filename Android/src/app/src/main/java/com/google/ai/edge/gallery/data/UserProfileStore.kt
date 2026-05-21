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
 * Lightweight user profile store backed by plain SharedPreferences.
 *
 * Stores only the display name (max 24 chars) shown in chat sender labels
 * and injected into the CLU system prompt so the AI can address the user
 * by name.  No encryption needed — this is a display preference, not a secret.
 */
object UserProfileStore {

    private const val PREFS_NAME = "user_profile"
    private const val KEY_USERNAME = "display_name"
    const val MAX_LEN = 24

    fun getUsername(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_USERNAME, "") ?: ""

    fun setUsername(context: Context, name: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_USERNAME, name.trim().take(MAX_LEN))
            .apply()
    }
}
