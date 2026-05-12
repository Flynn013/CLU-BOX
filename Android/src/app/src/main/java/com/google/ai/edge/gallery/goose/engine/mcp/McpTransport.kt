/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.goose.engine.mcp

/**
 * Transport layer abstraction for MCP (Model Context Protocol) communication.
 *
 * Implementations handle the mechanics of sending/receiving newline-delimited
 * JSON-RPC 2.0 messages over a specific transport (stdio, HTTP+SSE, etc.).
 *
 * Ported from MaxFlynn13/goose-android (engine/mcp/McpTransport.kt).
 */
interface McpTransport {

    /** Start the transport (spawn process, open connection, etc.). */
    suspend fun start(): Result<Unit>

    /** Send a JSON-RPC message string to the server. */
    suspend fun send(message: String)

    /** Block until the next JSON-RPC message is received from the server. */
    suspend fun receive(): String

    /** Shut down the transport and release all resources. */
    suspend fun close()

    /** Whether the transport is currently connected and usable. */
    val isConnected: Boolean
}
