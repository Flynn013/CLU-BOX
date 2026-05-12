/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.goose.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stub for the Rust binary engine.
 *
 * CLU/BOX does not ship a Rust `goose` binary.  This stub always fails
 * [initialize] so [GooseEngineManager] immediately falls back to
 * [KotlinNativeEngine], which provides full functionality on its own.
 *
 * If you embed the Rust binary in the future, replace this stub with a real
 * implementation that spawns the binary and connects via the ACP WebSocket
 * (see MaxFlynn13/goose-android for the full RustBinaryEngine implementation).
 */
class RustBinaryEngine(private val context: Context) : GooseEngine {

    companion object {
        private const val TAG = "RustBinaryEngine"
    }

    private val _status = MutableStateFlow(EngineStatus.DISCONNECTED)
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()
    override val engineName = "Goose (Rust binary — not available)"

    override suspend fun initialize(): Boolean {
        Log.i(TAG, "Rust binary engine is not available in CLU/BOX — skipping")
        _status.value = EngineStatus.ERROR
        return false
    }

    override fun sendMessage(
        message: String,
        conversationHistory: List<ConversationMessage>,
        systemPrompt: String
    ): Flow<AgentEvent> = flow {
        emit(AgentEvent.Error("Rust binary engine is not available. Please use Kotlin native engine."))
    }

    override fun cancel() {}

    override suspend fun shutdown() {
        _status.value = EngineStatus.DISCONNECTED
    }
}
