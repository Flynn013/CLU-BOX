/*
 * Copyright 2026 Google LLC
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

package com.google.ai.edge.gallery.data.mcp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val TAG = "McpConnectionManager"

/**
 * Manages the lifecycle of all active MCP server connections on behalf of the LNK_BOX UI.
 *
 * Responsibilities:
 * - Persists server configurations via [McpVault] (AES-256-GCM encrypted).
 * - Exposes [statuses] as a [StateFlow] so the Compose UI can react to connection changes.
 * - Notifies the skill layer when new tools become available, via [setOnConnectionReady].
 *
 * The [onConnectionReady] callback receives the live [McpClient] and the list of loaded [McpTool]
 * objects.  The caller (typically [com.google.ai.edge.gallery.GalleryApp]) wraps each tool in a
 * [com.google.ai.edge.gallery.customtasks.agentchat.McpDynamicSkill] and registers it with the
 * [com.google.ai.edge.gallery.customtasks.agentchat.SkillRegistry] so the LLM can invoke them.
 */
class McpConnectionManager(private val context: Context) {

  private val vault = McpVault(context)
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  /** Active (bridge, client) pairs, keyed by server name. */
  private val activeConnections = mutableMapOf<String, Pair<NativeMcpBridge, McpClient>>()

  private val _statuses = MutableStateFlow<List<McpConnectionStatus>>(emptyList())

  /** Live connection status for every known server (persisted + ephemeral). */
  val statuses: StateFlow<List<McpConnectionStatus>> = _statuses.asStateFlow()

  /**
   * Callback invoked (on [Dispatchers.IO]) whenever a server completes its handshake and
   * its tools have been listed.
   *
   * Signature: `(client: McpClient, tools: List<McpTool>) -> Unit`
   */
  private var onConnectionReady: ((McpClient, List<McpTool>) -> Unit)? = null

  fun setOnConnectionReady(callback: (McpClient, List<McpTool>) -> Unit) {
    onConnectionReady = callback
  }

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  /**
   * Loads persisted server configs from the [McpVault] and initialises [statuses] with
   * [McpConnectionState.IDLE] entries.  Should be called once during app startup.
   */
  fun initialize() {
    val configs = vault.loadAll()
    _statuses.value = configs.map { McpConnectionStatus(it, McpConnectionState.IDLE) }
    Log.d(TAG, "Initialised — ${configs.size} persisted server(s)")
  }

  // ── CRUD ──────────────────────────────────────────────────────────────────

  /** Persists [config] to the vault and immediately starts a connection. */
  fun addAndConnect(config: McpServerConfig) {
    vault.save(config)
    // Add an idle status entry if not already present, then connect.
    if (_statuses.value.none { it.config.name == config.name }) {
      updateStatus(McpConnectionStatus(config, McpConnectionState.IDLE))
    }
    connect(config)
  }

  /** Terminates the active connection for [name] (if any) without removing it from the vault. */
  fun disconnect(name: String) {
    activeConnections[name]?.first?.stop()
    activeConnections.remove(name)
    val existing = _statuses.value.find { it.config.name == name } ?: return
    updateStatus(existing.copy(state = McpConnectionState.IDLE, tools = emptyList()))
    Log.d(TAG, "Disconnected from '$name'")
  }

  /** Terminates the connection and removes [name] from the vault permanently. */
  fun deleteServer(name: String) {
    disconnect(name)
    vault.delete(name)
    _statuses.value = _statuses.value.filter { it.config.name != name }
    Log.d(TAG, "Deleted server '$name'")
  }

  /** Re-initiates the connection for a server that is currently [McpConnectionState.IDLE] or
   * [McpConnectionState.ERROR]. */
  fun reconnect(name: String) {
    val status = _statuses.value.find { it.config.name == name } ?: return
    if (status.state == McpConnectionState.CONNECTING || status.state == McpConnectionState.CONNECTED) return
    connect(status.config)
  }

  // ── Tool dispatch ─────────────────────────────────────────────────────────

  /**
   * Routes an MCP tool call to the active connection for [serverName].
   *
   * This method is used by [com.google.ai.edge.gallery.customtasks.agentchat.McpDynamicSkill]
   * when the agent loop requests a tool invocation.
   */
  suspend fun callTool(serverName: String, toolName: String, arguments: JSONObject): String {
    val (_, client) =
      activeConnections[serverName] ?: return "[MCP Error: '$serverName' not connected]"
    return client.callTool(toolName, arguments)
  }

  // ── Internal ──────────────────────────────────────────────────────────────

  private fun connect(config: McpServerConfig) {
    updateStatus(McpConnectionStatus(config, McpConnectionState.CONNECTING))
    scope.launch {
      val bridge = NativeMcpBridge()
      val client = McpClient(bridge)
      try {
        bridge.start(config.command, config.envVars)
        if (!client.initialize()) {
          updateStatus(
            McpConnectionStatus(config, McpConnectionState.ERROR, errorMessage = "Handshake failed")
          )
          bridge.stop()
          return@launch
        }
        val tools = client.listTools()
        activeConnections[config.name] = Pair(bridge, client)
        updateStatus(McpConnectionStatus(config, McpConnectionState.CONNECTED, tools))
        onConnectionReady?.invoke(client, tools)
        Log.d(TAG, "Connected to '${config.name}' — ${tools.size} tool(s) loaded")
      } catch (e: Exception) {
        Log.e(TAG, "Connection to '${config.name}' failed", e)
        bridge.stop()
        updateStatus(
          McpConnectionStatus(
            config,
            McpConnectionState.ERROR,
            errorMessage = e.message ?: "Unknown error",
          )
        )
      }
    }
  }

  private fun updateStatus(status: McpConnectionStatus) {
    val current = _statuses.value.toMutableList()
    val idx = current.indexOfFirst { it.config.name == status.config.name }
    if (idx >= 0) current[idx] = status else current.add(status)
    _statuses.value = current
  }
}
