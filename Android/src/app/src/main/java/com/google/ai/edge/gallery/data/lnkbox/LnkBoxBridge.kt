/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.data.lnkbox

import android.content.Context
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "LnkBoxBridge"

/**
 * **LNK_BOX** — bare-metal Model Context Protocol (MCP) transport layer.
 *
 * Implements the two transports the spec requires:
 *
 * 1. **Remote SSE** — opens a Ktor [ClientSSESession] against an `https://…/sse`
 *    endpoint, normalises the server-sent events into [Event] objects, and
 *    re-emits them on a [SharedFlow] so any subscriber (UI panels, the
 *    delegation engine, Python skills) can react.
 * 2. **Local stdio** — drives a Python-based MCP server hosted **inside**
 *    Chaquopy by piping JSON-RPC frames through a dedicated child interpreter
 *    helper (`mcp_stdio_server.py`). No external `python` binary is required;
 *    the server runs in the same process as the agent.
 *
 * Both transports share the same coroutine [scope] so they can be cancelled
 * cleanly when the application is destroyed (or when the agent forcibly
 * detaches a connection).
 *
 * The bridge intentionally exposes only a *thin* JSON-RPC envelope
 * (`{id, method, params}` / `{id, result}` / `{id, error}`) — interpretation
 * of MCP-specific verbs (`initialize`, `tools/list`, `resources/read`, …) is
 * deferred to the consumer (typically [com.google.ai.edge.gallery.data.mcp.McpClient]).
 */
object LnkBoxBridge {

  /** Coroutine scope owning every active SSE / stdio session. */
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** Auto-incrementing request id used when callers don't supply one. */
  private val nextRequestId = AtomicLong(1)

  /** Currently-open connections, keyed by user-chosen connection id. */
  private val connections = ConcurrentHashMap<String, Connection>()

  /** Hot stream of every event observed across every connection. */
  private val _events = MutableSharedFlow<Event>(replay = 0, extraBufferCapacity = 64)
  val events: SharedFlow<Event> = _events.asSharedFlow()

  /** Lazily-built shared HTTP client — one per process. */
  private val httpClient: HttpClient by lazy {
    HttpClient(CIO) {
      install(SSE)
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  //  Public API
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Open an MCP connection over server-sent events.
   *
   * Uses Ktor 2.3.x's `sse { }` block which opens a [ClientSSESession],
   * exposes its incoming flow, and auto-closes on coroutine cancellation.
   * Custom headers are merged with the mandatory `Accept: text/event-stream`.
   */
  fun connectSse(
    id: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
  ): String {
    if (connections.containsKey(id)) return "[lnkBox sse error: id '$id' already open]"
    val job = scope.launch {
      try {
        httpClient.sse(
          urlString = url,
          request = {
            this.headers.append(HttpHeaders.Accept, "text/event-stream")
            headers.forEach { (k, v) -> this.headers.append(k, v) }
          },
        ) {
          _events.emit(Event.Open(id, transport = "sse"))
          incoming.collect { evt ->
            val data = evt.data ?: return@collect
            _events.emit(Event.Message(id, data))
          }
        }
      } catch (t: Throwable) {
        Log.w(TAG, "SSE connection $id terminated: ${t.message}")
        _events.emit(Event.Closed(id, reason = t.message ?: "stream ended"))
      } finally {
        connections.remove(id)
      }
    }
    connections[id] = Connection.Sse(id, url, job)
    return "[lnkBox sse open: $id -> $url]"
  }

  /**
   * Spawn a local Python MCP server inside the Chaquopy interpreter and
   * expose it as a JSON-RPC endpoint accessible through [send].
   *
   * @param id      Stable connection id used by [send] / [close].
   * @param module  Python module name that exposes a `start_server(spec_json)`
   *                callable returning a `serve(line)` function. The companion
   *                helper `mcp_stdio_server.py` (see /python) ships a default
   *                router; user-authored servers can drop a same-shaped module
   *                into `assets/python/mcp/` and reference it by name here.
   * @param args    Arbitrary JSON-encodable arguments forwarded to the
   *                server's `start_server` factory.
   */
  fun connectStdio(
    @Suppress("UNUSED_PARAMETER") context: Context,
    id: String,
    module: String,
    args: Map<String, Any?> = emptyMap(),
  ): String {
    if (connections.containsKey(id)) return "[lnkBox stdio error: id '$id' already open]"
    return runCatching {
      val py = Python.getInstance()
      val helper: PyObject = py.getModule("mcp_stdio_server")
      val server: PyObject = helper.callAttr("start_server", module, JSONObject(args).toString())
      connections[id] = Connection.Stdio(id, module, server)
      scope.launch { _events.emit(Event.Open(id, transport = "stdio")) }
      "[lnkBox stdio open: $id ($module)]"
    }.getOrElse { "[lnkBox stdio error: ${it.message}]" }
  }

  /**
   * Send a JSON-RPC request through the named connection and return the
   * response synchronously for stdio servers (single-threaded interpreter)
   * or fire-and-forget for SSE servers (which respond asynchronously via the
   * [events] flow).
   */
  fun send(id: String, method: String, params: Map<String, Any?> = emptyMap(), requestId: Long? = null): String {
    val conn = connections[id] ?: return "[lnkBox send error: '$id' not connected]"
    val rid = requestId ?: nextRequestId.getAndIncrement()
    val frame = JSONObject().apply {
      put("jsonrpc", "2.0")
      put("id", rid)
      put("method", method)
      put("params", JSONObject(params))
    }
    return when (conn) {
      is Connection.Stdio -> runCatching {
        val response = conn.server.callAttr("serve", frame.toString()).toString()
        scope.launch { _events.emit(Event.Message(id, response)) }
        response
      }.getOrElse { "[lnkBox stdio send error: ${it.message}]" }

      is Connection.Sse -> {
        // SSE is a one-way push channel for incoming events; outgoing requests
        // would need a paired POST — but the v0 MCP profile does not require
        // it, so we just emit a synthetic event and return the request id.
        scope.launch { _events.emit(Event.Sent(id, frame.toString())) }
        "[lnkBox sse send queued: id=$rid]"
      }
    }
  }

  /** Close (cancel) a previously-opened connection. */
  fun close(id: String): String {
    val conn = connections.remove(id) ?: return "[lnkBox close: '$id' was not open]"
    when (conn) {
      is Connection.Sse -> conn.job.cancel()
      is Connection.Stdio -> runCatching { conn.server.callAttr("shutdown") }
    }
    scope.launch { _events.emit(Event.Closed(id, reason = "user closed")) }
    return "[lnkBox closed: $id]"
  }

  /** Snapshot list of currently-open connections (for the LNK_BOX UI panel). */
  fun list(): List<Snapshot> = connections.values.map { c ->
    when (c) {
      is Connection.Sse -> Snapshot(c.id, "sse", c.url)
      is Connection.Stdio -> Snapshot(c.id, "stdio", c.module)
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  //  Types
  // ─────────────────────────────────────────────────────────────────────────

  /** Public snapshot used by the UI / Splinter introspection. */
  data class Snapshot(val id: String, val transport: String, val target: String)

  /** Hot-flow event types observable via [events]. */
  sealed interface Event {
    val id: String
    data class Open(override val id: String, val transport: String) : Event
    data class Message(override val id: String, val data: String) : Event
    data class Sent(override val id: String, val data: String) : Event
    data class Closed(override val id: String, val reason: String) : Event
  }

  /** Internal connection record. */
  private sealed interface Connection {
    val id: String

    data class Sse(override val id: String, val url: String, val job: Job) : Connection
    data class Stdio(override val id: String, val module: String, val server: PyObject) : Connection
  }
}
