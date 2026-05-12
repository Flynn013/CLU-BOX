/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.goose.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Goose engine uses its own separate DataStore instance ("goose_engine_settings")
// to avoid colliding with the Gallery app's existing DataStore.
private val Context.gooseDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "goose_engine_settings")

/**
 * Persistent settings storage for the Goose engine, backed by DataStore.
 *
 * Ported from MaxFlynn13/goose-android (data/SettingsStore.kt).
 */
class SettingsStore(private val context: Context) {

    fun getString(key: String, default: String = ""): Flow<String> {
        val prefKey = stringPreferencesKey(key)
        return context.gooseDataStore.data.map { prefs -> prefs[prefKey] ?: default }
    }

    suspend fun setString(key: String, value: String) {
        val prefKey = stringPreferencesKey(key)
        context.gooseDataStore.edit { prefs -> prefs[prefKey] = value }
    }

    fun getInt(key: String, default: Int = 0): Flow<Int> {
        val prefKey = intPreferencesKey(key)
        return context.gooseDataStore.data.map { prefs -> prefs[prefKey] ?: default }
    }

    suspend fun setInt(key: String, value: Int) {
        val prefKey = intPreferencesKey(key)
        context.gooseDataStore.edit { prefs -> prefs[prefKey] = value }
    }

    fun getBoolean(key: String, default: Boolean = false): Flow<Boolean> {
        val prefKey = booleanPreferencesKey(key)
        return context.gooseDataStore.data.map { prefs -> prefs[prefKey] ?: default }
    }

    suspend fun setBoolean(key: String, value: Boolean) {
        val prefKey = booleanPreferencesKey(key)
        context.gooseDataStore.edit { prefs -> prefs[prefKey] = value }
    }

    fun getActiveProvider(): Flow<String> = getString(SettingsKeys.ACTIVE_PROVIDER, "anthropic")
    fun getActiveModel(): Flow<String> = getString(SettingsKeys.ACTIVE_MODEL, "claude-sonnet-4-20250514")
}
