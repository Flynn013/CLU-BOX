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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.collectAsState
import com.google.ai.edge.gallery.customtasks.agentchat.AgentTools
import com.google.ai.edge.gallery.customtasks.agentchat.SkillManagerBottomSheet
import com.google.ai.edge.gallery.customtasks.agentchat.SkillManagerViewModel
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.EMPTY_MODEL
import com.google.ai.edge.gallery.data.FileBoxManager
import com.google.ai.edge.gallery.data.convertValueToTargetType
import com.google.ai.edge.gallery.data.brainbox.GraphDatabase
import com.google.ai.edge.gallery.ui.common.ConfigDialog
import com.google.ai.edge.gallery.ui.modelmanager.GlobalModelManager
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.navigation.GALLERY_ROUTE_BENCHMARK
import com.google.ai.edge.gallery.ui.navigation.GALLERY_ROUTE_MODEL
import com.google.ai.edge.gallery.ui.navigation.GalleryNavHost
import com.google.ai.edge.gallery.data.TerminalSessionManager
import com.google.ai.edge.gallery.ui.osmodules.BrainBoxModuleScreen
import com.google.ai.edge.gallery.ui.osmodules.FileBoxScreen
import com.google.ai.edge.gallery.ui.osmodules.MstrCtrlScreen
import com.google.ai.edge.gallery.ui.theme.absoluteBlack
import com.google.ai.edge.gallery.ui.theme.neonGreen
import com.google.ai.edge.gallery.ui.theme.terminalMidGrey
import kotlinx.coroutines.launch

/** Identifies each OS module panel. */
private enum class OsModule(val label: String, val icon: ImageVector) {
  CHAT_BOX("CHAT_BOX", Icons.Outlined.Chat),
  BRAIN_BOX("BRAIN_BOX", Icons.Outlined.Hub),
  FILE_BOX("FILE_BOX", Icons.Outlined.Code),
  MSTR_CTRL("MSTR_CTRL", Icons.Outlined.Terminal),
  SKILL_BOX("SKILL_BOX", Icons.Outlined.Psychology),
  MODEL_MANAGER("MODEL_MANAGER", Icons.Outlined.DashboardCustomize),
  SYS_SETTINGS("SETTINGS", Icons.Outlined.Settings),
}

/** Top level composable representing the main CLU/BOX operating system interface. */
@Composable
fun GalleryApp(
  modelManagerViewModel: ModelManagerViewModel,
) {
  val context = LocalContext.current
  val drawerState = rememberDrawerState(DrawerValue.Closed)
  val scope = rememberCoroutineScope()
  var activeModule by remember { mutableStateOf(OsModule.CHAT_BOX) }
  // True when the SYS_SETTINGS drawer item is tapped — shows the ConfigDialog overlay.
  var showSysSettingsDialog by remember { mutableStateOf(false) }
  // True when the SKILL_BOX drawer item is tapped — shows the SkillManagerBottomSheet overlay.
  var showSkillBoxSheet by remember { mutableStateOf(false) }
  val db = remember { GraphDatabase.getInstance(context) }
  val fileBoxManager = remember { FileBoxManager(context) }
  val terminalSessionManager = remember { TerminalSessionManager(context) }
  val skillManagerViewModel: SkillManagerViewModel = hiltViewModel()
  // AgentTools instance used by the SkillManagerBottomSheet for skill testing.
  val agentTools = remember {
    AgentTools().apply {
      this.skillManagerViewModel = skillManagerViewModel
    }
  }
  agentTools.context = context
  agentTools.brainBoxDao = remember(context) { GraphDatabase.getInstance(context).brainBoxDao() }
  agentTools.vectorEngine = remember(context) { com.google.ai.edge.gallery.data.brainbox.VectorEngine(context) }
  agentTools.terminalSessionManager = terminalSessionManager

  // Separate nav controllers so each module retains its own back stack.
  val chatNavController = rememberNavController()

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
          OsModule.entries.forEach { module ->
            DrawerItem(
              module = module,
              selected = activeModule == module,
              onClick = {
                if (module == OsModule.SYS_SETTINGS) {
                  // Show the Configurations dialog over the current module.
                  showSysSettingsDialog = true
                } else if (module == OsModule.SKILL_BOX) {
                  // Show the Skill Manager bottom sheet over the current module.
                  showSkillBoxSheet = true
                } else {
                  activeModule = module
                }
                scope.launch { drawerState.close() }
              },
            )
          }
        }
      }
    },
  ) {
    // ============================================================
    // Content area — wrapped in a Box so we can overlay the
    // right-edge tab button that opens the navigation drawer.
    // ============================================================
    Box(modifier = Modifier.fillMaxSize()) {
      // ── CHAT_BOX: ALWAYS in composition ────────────────────────
      // The chat NavHost stays mounted so that LLM inference,
      // conversation state, scroll position, and streaming continue
      // uninterrupted even when the user visits another module.
      // When another module is active we hide it visually (alpha 0)
      // but it remains composed and its ViewModel keeps running.
      val chatBoxVisible = activeModule == OsModule.CHAT_BOX
      Box(
        modifier = Modifier
          .fillMaxSize()
          .alpha(if (chatBoxVisible) 1f else 0f),
      ) {
        GalleryNavHost(
          navController = chatNavController,
          modelManagerViewModel = modelManagerViewModel,
          initialTaskId = BuiltInTaskId.LLM_AGENT_CHAT,
        )
      }

      // ── Other modules: rendered on top when active ─────────────
      if (!chatBoxVisible) {
        when (activeModule) {
          // MODEL_MANAGER: full-screen, manages own Scaffold.
          OsModule.MODEL_MANAGER -> GlobalModelManager(
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

          // Shell modules: the CLU/BOX Scaffold supplies the top bar and status-bar insets.
          else -> {
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
                  Text(
                    activeModule.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = neonGreen,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 12.dp),
                  )
                }
              },
            ) { innerPadding ->
              Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                when (activeModule) {
                  OsModule.BRAIN_BOX -> BrainBoxModuleScreen(
                    dao = db.brainBoxDao(),
                    vectorEngine = remember(context) { com.google.ai.edge.gallery.data.brainbox.VectorEngine(context) },
                  )
                  OsModule.FILE_BOX -> FileBoxScreen(fileBoxManager = fileBoxManager)
                  OsModule.MSTR_CTRL -> MstrCtrlScreen(sessionManager = terminalSessionManager)
                  else -> {} // all modules covered above
                }
              }
            }
          }
        }
      }

      // ── Right-edge tab button ──────────────────────────────────
      // A tall slender tab anchored to the vertical middle of the
      // right edge.  Tapping it opens the ModalNavigationDrawer.
      val tabShape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
      Box(
        modifier = Modifier
          .align(Alignment.CenterEnd)
          .offset(x = 0.dp)
          .width(28.dp)
          .height(120.dp)
          .clip(tabShape)
          .background(absoluteBlack.copy(alpha = 0.85f))
          .border(1.dp, neonGreen.copy(alpha = 0.6f), tabShape)
          .clickable { scope.launch { drawerState.open() } },
        contentAlignment = Alignment.Center,
      ) {
        // Vertical "CLU" text
        Text(
          text = "C\nL\nU",
          color = neonGreen,
          fontFamily = FontFamily.Monospace,
          fontSize = 12.sp,
          lineHeight = 14.sp,
          textAlign = TextAlign.Center,
        )
      }

      // ── SYS_SETTINGS configuration dialog ──────────────────────
      // Shown as an overlay when the user taps the SETTINGS drawer item.
      if (showSysSettingsDialog) {
        val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
        val selectedModel = modelManagerUiState.selectedModel
        if (selectedModel != EMPTY_MODEL && selectedModel.configs.isNotEmpty()) {
          val task = modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_AGENT_CHAT)
          val modelConfigs = selectedModel.configs.toMutableList()
          // Remove task-specific configs that don't apply in the global settings context.
          modelConfigs.removeIf { it.key == ConfigKeys.RESET_CONVERSATION_TURN_COUNT }
          if (task?.allowThinking() != true) {
            modelConfigs.removeIf { it.key == ConfigKeys.ENABLE_THINKING }
          }
          ConfigDialog(
            title = "Configurations",
            configs = modelConfigs,
            initialValues = selectedModel.configValues,
            showSystemPromptEditorTab = true,
            onDismissed = { showSysSettingsDialog = false },
            onOk = { curConfigValues, _, _ ->
              showSysSettingsDialog = false
              // Persist updated config values to the model.
              val oldConfigValues = selectedModel.configValues
              var changed = false
              for (config in modelConfigs) {
                val key = config.key.label
                val oldValue = convertValueToTargetType(
                  value = oldConfigValues.getValue(key),
                  valueType = config.valueType,
                )
                val newValue = convertValueToTargetType(
                  value = curConfigValues.getValue(key),
                  valueType = config.valueType,
                )
                if (oldValue != newValue) {
                  changed = true
                  break
                }
              }
              if (changed) {
                selectedModel.prevConfigValues = oldConfigValues
                selectedModel.configValues = curConfigValues
                modelManagerViewModel.updateConfigValuesUpdateTrigger()
              }
            },
          )
        } else {
          // No model selected or no configs — dismiss immediately.
          LaunchedEffect(Unit) { showSysSettingsDialog = false }
        }
      }

      // ── SKILL_BOX skill manager bottom sheet ───────────────────
      // Shown as an overlay when the user taps the SKILL_BOX drawer item.
      if (showSkillBoxSheet) {
        SkillManagerBottomSheet(
          agentTools = agentTools,
          skillManagerViewModel = skillManagerViewModel,
          onDismiss = { _ -> showSkillBoxSheet = false },
        )
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

