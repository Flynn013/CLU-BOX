/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.brainbox.GraphDatabase
import com.google.ai.edge.gallery.ui.modelmanager.GlobalModelManager
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.navigation.GALLERY_ROUTE_BENCHMARK
import com.google.ai.edge.gallery.ui.navigation.GALLERY_ROUTE_MODEL
import com.google.ai.edge.gallery.ui.navigation.GalleryNavHost
import com.google.ai.edge.gallery.ui.osmodules.BrainBoxModuleScreen
import com.google.ai.edge.gallery.ui.osmodules.TheGridScreen
import com.google.ai.edge.gallery.ui.theme.absoluteBlack
import com.google.ai.edge.gallery.ui.theme.neonGreen
import com.google.ai.edge.gallery.ui.theme.terminalMidGrey
import kotlinx.coroutines.launch

/** Identifies each OS module panel. */
private enum class OsModule(val label: String, val icon: ImageVector) {
  CHAT_BOX("CHAT_BOX", Icons.Outlined.Chat),
  BRAIN_BOX("BRAIN_BOX", Icons.Outlined.Hub),
  THE_GRID("THE_GRID", Icons.Outlined.GridView),
  SKILL_BOX("SKILL_BOX", Icons.Outlined.Psychology),
  VENDING_MACHINE("VENDING MACHINE", Icons.Outlined.ShoppingCart),
  SYS_SETTINGS("SYS_SETTINGS", Icons.Outlined.Settings),
}

/**
 * Returns true for modules that render their own full-screen Scaffold (top bar + insets).
 * These must be displayed without an outer CLU/BOX Scaffold shell to avoid a double top-bar gap.
 */
private val OsModule.isFullScreenModule: Boolean
  get() = this == OsModule.CHAT_BOX ||
    this == OsModule.SKILL_BOX ||
    this == OsModule.VENDING_MACHINE

/** Top level composable representing the main CLU/BOX operating system interface. */
@Composable
fun GalleryApp(
  modelManagerViewModel: ModelManagerViewModel,
) {
  val context = LocalContext.current
  val drawerState = rememberDrawerState(DrawerValue.Closed)
  val scope = rememberCoroutineScope()
  var activeModule by remember { mutableStateOf(OsModule.CHAT_BOX) }
  val db = remember { GraphDatabase.getInstance(context) }

  // Separate nav controllers so each module retains its own back stack.
  val chatNavController = rememberNavController()
  val skillNavController = rememberNavController()

  ModalNavigationDrawer(
    drawerState = drawerState,
    drawerContent = {
      ModalDrawerSheet(
        modifier = Modifier.width(280.dp).fillMaxHeight(),
        drawerContainerColor = absoluteBlack,
      ) {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .background(absoluteBlack)
            .verticalScroll(rememberScrollState()),
        ) {
          // Drawer header
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .background(terminalMidGrey)
              .padding(horizontal = 20.dp, vertical = 24.dp),
          ) {
            Column {
              Text(
                "CLU/BOX",
                style = MaterialTheme.typography.headlineMedium,
                color = neonGreen,
                fontFamily = FontFamily.Monospace,
              )
              Text(
                "OS v0.5 — offline AI",
                style = MaterialTheme.typography.labelSmall,
                color = neonGreen.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace,
              )
            }
          }

          Spacer(Modifier.height(8.dp))

          // Module items.
          // SYS_SETTINGS redirects to VENDING_MACHINE (the real model-management / config hub).
          OsModule.entries.forEach { module ->
            DrawerItem(
              module = module,
              selected = activeModule == module,
              onClick = {
                activeModule = if (module == OsModule.SYS_SETTINGS) {
                  OsModule.VENDING_MACHINE
                } else {
                  module
                }
                scope.launch { drawerState.close() }
              },
            )
          }
        }
      }
    },
  ) {
    // Full-screen modules (CHAT_BOX, SKILL_BOX, VENDING_MACHINE) manage their own Scaffold and
    // window insets. Wrapping them in an additional Scaffold produces a double top-bar and a large
    // blank gap. Render them directly so only their own top bar appears on screen.
    //
    // Shell modules (BRAIN_BOX, THE_GRID) are simple content screens that rely on the CLU/BOX
    // Scaffold to supply the top bar and correct inset padding.
    if (activeModule.isFullScreenModule) {
      when (activeModule) {
        // CHAT_BOX: starts directly at the Agent Chat (Agent Skills) model picker —
        // the primary AI interaction window for CLU/BOX.
        OsModule.CHAT_BOX -> GalleryNavHost(
          navController = chatNavController,
          modelManagerViewModel = modelManagerViewModel,
          initialTaskId = BuiltInTaskId.LLM_AGENT_CHAT,
        )

        // SKILL_BOX: starts directly at the Agent Chat model picker, which
        // hosts the full skill import / edit / test workflow.
        OsModule.SKILL_BOX -> GalleryNavHost(
          navController = skillNavController,
          modelManagerViewModel = modelManagerViewModel,
          initialTaskId = BuiltInTaskId.LLM_AGENT_CHAT,
        )

        // VENDING_MACHINE / SYS_SETTINGS: the real model-management hub — download,
        // delete, and configure any model.  Selecting a model switches to CHAT_BOX.
        OsModule.VENDING_MACHINE -> GlobalModelManager(
          viewModel = modelManagerViewModel,
          navigateUp = { activeModule = OsModule.CHAT_BOX },
          onModelSelected = { task, model ->
            chatNavController.navigate("$GALLERY_ROUTE_MODEL/${task.id}/${model.name}")
            activeModule = OsModule.CHAT_BOX
          },
          onBenchmarkClicked = { model ->
            chatNavController.navigate("$GALLERY_ROUTE_BENCHMARK/${model.name}")
            activeModule = OsModule.CHAT_BOX
          },
        )

        else -> {} // unreachable
      }
    } else {
      // Shell modules: the CLU/BOX Scaffold supplies the top bar and status-bar insets.
      Scaffold(
        containerColor = absoluteBlack,
        topBar = {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .background(absoluteBlack)
              .windowInsetsPadding(WindowInsets.statusBars)
              .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            IconButton(onClick = { scope.launch { drawerState.open() } }) {
              Icon(Icons.Outlined.Menu, contentDescription = "Open menu", tint = neonGreen)
            }
            Text(
              activeModule.label,
              style = MaterialTheme.typography.titleMedium,
              color = neonGreen,
              fontFamily = FontFamily.Monospace,
              modifier = Modifier.padding(start = 4.dp),
            )
          }
        },
      ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
          when (activeModule) {
            OsModule.BRAIN_BOX -> BrainBoxModuleScreen(dao = db.brainBoxDao())
            OsModule.THE_GRID -> TheGridScreen()
            else -> {} // SYS_SETTINGS redirected above; other full-screen handled in outer branch
          }
        }
      }
    }
  }
}

@Composable
private fun DrawerItem(module: OsModule, selected: Boolean, onClick: () -> Unit) {
  val bgColor = if (selected) terminalMidGrey else Color.Transparent
  val textColor = if (selected) neonGreen else MaterialTheme.colorScheme.onSurface

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(bgColor)
      .clickable(onClick = onClick)
      .padding(horizontal = 20.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(module.icon, contentDescription = module.label, tint = textColor)
    Spacer(Modifier.width(16.dp))
    Text(
      module.label,
      style = MaterialTheme.typography.bodyMedium,
      color = textColor,
      fontFamily = FontFamily.Monospace,
    )
  }
}

