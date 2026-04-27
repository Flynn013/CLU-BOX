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

package com.google.ai.edge.gallery.ui.modelmanager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.proto.ImportedModel
import com.google.ai.edge.gallery.ui.common.TaskIcon
import com.google.ai.edge.gallery.ui.common.modelitem.ModelItem
import com.google.ai.edge.gallery.ui.theme.neonGreen
import kotlin.text.endsWith
import kotlin.text.lowercase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AGGlobalMM"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalModelManager(
  viewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  onModelSelected: (Task, Model) -> Unit,
  onBenchmarkClicked: (Model) -> Unit,
  modifier: Modifier = Modifier,
  openDrawer: (() -> Unit)? = null,
) {
  val uiState by viewModel.uiState.collectAsState()
  val localModels = remember { mutableStateListOf<Model>() }
  val cloudModels = remember { mutableStateListOf<Model>() }
  val bespokeModels = remember { mutableStateListOf<Model>() }
  val importedModels = remember { mutableStateListOf<Model>() }
  // Gemini Cloud models added by the user via the GEMINI tab (persisted in DataStore).
  val geminiModels = remember { mutableStateListOf<Model>() }
  val taskCandidates = remember { mutableStateListOf<Task>() }
  var modelForTaskCandidate by remember { mutableStateOf<Model?>(null) }
  var showTaskSelectorBottomSheet by remember { mutableStateOf(false) }
  var showImportModelSheet by remember { mutableStateOf(false) }
  var showAddApiModelDialog by remember { mutableStateOf(false) }
  var showUnsupportedFileTypeDialog by remember { mutableStateOf(false) }
  var showUnsupportedWebModelDialog by remember { mutableStateOf(false) }
  val selectedLocalModelFileUri = remember { mutableStateOf<Uri?>(null) }
  val selectedImportedModelInfo = remember { mutableStateOf<ImportedModel?>(null) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var showImportDialog by remember { mutableStateOf(false) }
  var showImportingDialog by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val snackbarHostState = remember { SnackbarHostState() }
  val modelItemExpandedStates = remember { mutableStateMapOf<String, Boolean>() }

  // VENDING_MACHINE tab state: 0=LOCAL, 1=CLOUD, 2=BESPOKE, 3=GEMINI
  var selectedTab by remember { mutableIntStateOf(0) }
  val tabTitles = listOf("LOCAL", "CLOUD", "BESPOKE", "GEMINI")

  val promoId = "gm4_banner"
  var showPromo by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    showPromo = !viewModel.dataStoreRepository.hasViewedPromo(promoId = promoId)
  }

  val filePickerLauncher: ActivityResultLauncher<Intent> =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
      if (result.resultCode == android.app.Activity.RESULT_OK) {
        result.data?.data?.let { uri ->
          val fileName = getFileName(context = context, uri = uri)
          Log.d(TAG, "Selected file: $fileName")
          // Show warning for model file types other than .task and .litertlm.
          if (fileName != null && !fileName.endsWith(".task") && !fileName.endsWith(".litertlm")) {
            showUnsupportedFileTypeDialog = true
          }
          // Show warning for web-only model (by checking if the file name has "-web" in it).
          else if (fileName != null && fileName.lowercase().contains("-web")) {
            showUnsupportedWebModelDialog = true
          } else {
            selectedLocalModelFileUri.value = uri
            showImportDialog = true
          }
        } ?: run { Log.d(TAG, "No file selected or URI is null.") }
      } else {
        Log.d(TAG, "File picking cancelled.")
      }
    }

  LaunchedEffect(uiState.modelImportingUpdateTrigger) {
    val allModelsSet = mutableSetOf<Model>()
    for (task in uiState.tasks) {
      for (model in task.models) {
        allModelsSet.add(model)
      }
    }
    val sortedModels = allModelsSet.toList().sortedBy { it.displayName.ifEmpty { it.name } }

    // Split into VENDING_MACHINE tabs:
    // LOCAL: LiteRT-LM models (on-device) + imported
    // CLOUD: AICore / system-level Gemini Cloud (from allowlist)
    // BESPOKE: Manually-added API models
    // GEMINI: User-added Gemini Cloud API models (from DataStore via GEMINI tab)
    val persistedGeminiIds = viewModel.dataStoreRepository.readGeminiCloudModels().map { it.modelId }.toSet()
    localModels.clear()
    cloudModels.clear()
    bespokeModels.clear()
    importedModels.clear()
    geminiModels.clear()
    for (model in sortedModels) {
      when {
        model.runtimeType == RuntimeType.MANUAL_API -> bespokeModels.add(model)
        model.runtimeType == RuntimeType.GEMINI_CLOUD && persistedGeminiIds.contains(model.name) ->
          geminiModels.add(model)
        model.runtimeType == RuntimeType.AICORE ||
          model.runtimeType == RuntimeType.GEMINI_CLOUD -> cloudModels.add(model)
        model.imported -> importedModels.add(model)
        else -> localModels.add(model)
      }
    }
  }

  val handleClickModel: (Model) -> Unit = { model ->
    val tasks = viewModel.uiState.value.tasks
    val tasksForModel = tasks.filter { task -> task.models.any { it.name == model.name } }
    // If there is only one task for the model, navigate to the model directly.
    if (tasksForModel.size == 1) {
      onModelSelected(tasksForModel[0], model)
    }
    // If there are multiple tasks for the model, show a bottom sheet for the user to choose which
    // task to use.
    else if (tasksForModel.size > 1) {
      taskCandidates.clear()
      taskCandidates.addAll(tasksForModel)
      modelForTaskCandidate = model
      showTaskSelectorBottomSheet = true
    }
  }

  // Handle system's edge swipe.
  BackHandler { navigateUp() }

  Scaffold(
    modifier = modifier,
    topBar = {
      CenterAlignedTopAppBar(
        title = {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Icon(
                Icons.AutoMirrored.Rounded.ListAlt,
                modifier = Modifier.size(20.dp),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text =
                  "${stringResource(R.string.drawer_models_label)} (${localModels.size + cloudModels.size + bespokeModels.size + importedModels.size})",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
              )
            }
          }
        },
        // CLU/BOX drawer hamburger — only shown when called from the CLU/BOX shell.
        navigationIcon = {
          if (openDrawer != null) {
            IconButton(onClick = openDrawer) {
              Icon(
                imageVector = Icons.Rounded.Menu,
                contentDescription = "Open CLU/BOX menu",
                tint = MaterialTheme.colorScheme.onSurface,
              )
            }
          }
        },
        // The "action" component at the right.
        actions = {
          IconButton(onClick = { navigateUp() }) {
            Icon(
              imageVector = Icons.Rounded.Close,
              contentDescription = stringResource(R.string.cd_close_icon),
              tint = MaterialTheme.colorScheme.onSurface,
            )
          }
        },
        modifier = modifier,
      )
    },
    floatingActionButton = {
      // Context-sensitive FAB: import local model on LOCAL tab, add API model on BESPOKE tab.
      if (selectedTab == 0) {
        val cdImportModelFab = stringResource(R.string.cd_import_model_button)
        SmallFloatingActionButton(
          onClick = { showImportModelSheet = true },
          containerColor = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.secondary,
          modifier = Modifier.semantics { contentDescription = cdImportModelFab },
        ) {
          Icon(Icons.Filled.Add, contentDescription = null)
        }
      } else if (selectedTab == 2) {
        SmallFloatingActionButton(
          onClick = { showAddApiModelDialog = true },
          containerColor = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.secondary,
          modifier = Modifier.semantics { contentDescription = "Add API Model" },
        ) {
          Icon(Icons.Filled.Add, contentDescription = null)
        }
      }
    },
  ) { innerPadding ->
    Box() {
      Column(
        modifier = Modifier
          .background(MaterialTheme.colorScheme.surfaceContainer)
          .fillMaxWidth()
          .padding(top = innerPadding.calculateTopPadding()),
      ) {
        // ── VENDING_MACHINE Tabs ─────────────────────────────────
        TabRow(
          selectedTabIndex = selectedTab,
          containerColor = MaterialTheme.colorScheme.surfaceContainer,
          contentColor = neonGreen,
          indicator = { tabPositions ->
            if (selectedTab < tabPositions.size) {
              TabRowDefaults.SecondaryIndicator(
                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                color = neonGreen,
              )
            }
          },
        ) {
          tabTitles.forEachIndexed { index, title ->
            Tab(
              selected = selectedTab == index,
              onClick = { selectedTab = index },
              text = {
                Text(
                  title,
                  fontFamily = FontFamily.Monospace,
                  color = if (selectedTab == index)
                    neonGreen
                  else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
              },
            )
          }
        }

        // ── Tab content ──────────────────────────────────────────
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(horizontal = 16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
          contentPadding =
            PaddingValues(top = 16.dp, bottom = innerPadding.calculateBottomPadding() + 80.dp),
        ) {
          when (selectedTab) {
            // ── Tab 0: LOCAL ─────────────────────────────────────
            0 -> {
              item(key = "promo") {
                AnimatedVisibility(
                  visible = showPromo,
                  enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }) + expandVertically(),
                  exit = fadeOut() + shrinkVertically(),
                ) {
                  PromoBannerGm4(
                    onDismiss = {
                      showPromo = false
                      viewModel.dataStoreRepository.addViewedPromoId(promoId = promoId)
                    }
                  )
                }
              }

              items(localModels) { model ->
                val expanded = modelItemExpandedStates.getOrDefault(model.name, true)
                ModelItem(
                  model = model,
                  task = null,
                  modelManagerViewModel = viewModel,
                  onModelClicked = handleClickModel,
                  onBenchmarkClicked = onBenchmarkClicked,
                  expanded = expanded,
                  showBenchmarkButton = model.runtimeType == RuntimeType.LITERT_LM,
                  onExpanded = { modelItemExpandedStates[model.name] = it },
                )
              }

              // Imported models in the LOCAL tab.
              if (importedModels.isNotEmpty()) {
                item(key = "imported_models_label") {
                  Text(
                    stringResource(R.string.model_list_imported_models_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                      .padding(horizontal = 16.dp)
                      .padding(top = 32.dp, bottom = 8.dp),
                  )
                }
              }
              items(importedModels) { model ->
                ModelItem(
                  model = model,
                  task = null,
                  modelManagerViewModel = viewModel,
                  onModelClicked = handleClickModel,
                  onBenchmarkClicked = onBenchmarkClicked,
                  expanded = true,
                  showBenchmarkButton = model.runtimeType == RuntimeType.LITERT_LM,
                )
              }
            }

            // ── Tab 1: CLOUD ─────────────────────────────────────
            1 -> {
              if (cloudModels.isEmpty()) {
                item(key = "cloud_empty") {
                  Text(
                    "No cloud models available.\nCloud models (AICore / System Gemini) require a compatible Pixel device.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(32.dp),
                  )
                }
              }
              items(cloudModels) { model ->
                val expanded = modelItemExpandedStates.getOrDefault(model.name, true)
                ModelItem(
                  model = model,
                  task = null,
                  modelManagerViewModel = viewModel,
                  onModelClicked = handleClickModel,
                  onBenchmarkClicked = onBenchmarkClicked,
                  expanded = expanded,
                  showBenchmarkButton = false,
                  onExpanded = { modelItemExpandedStates[model.name] = it },
                )
              }
            }

            // ── Tab 2: BESPOKE ───────────────────────────────────
            2 -> {
              if (bespokeModels.isEmpty()) {
                item(key = "bespoke_empty") {
                  Text(
                    "No bespoke models added yet.\nTap + to add an API model (Google Gemini, OpenAI-compatible, etc.)",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(32.dp),
                  )
                }
              }
              items(bespokeModels) { model ->
                val expanded = modelItemExpandedStates.getOrDefault(model.name, true)
                ModelItem(
                  model = model,
                  task = null,
                  modelManagerViewModel = viewModel,
                  onModelClicked = handleClickModel,
                  onBenchmarkClicked = onBenchmarkClicked,
                  expanded = expanded,
                  showBenchmarkButton = false,
                  onExpanded = { modelItemExpandedStates[model.name] = it },
                )
              }
            }

            // ── Tab 3: GEMINI ────────────────────────────────────────
            3 -> {
              item(key = "gemini_api_tab") {
                GeminiApiTab(
                  viewModel = viewModel,
                  onModelAdded = { modelId, displayName ->
                    viewModel.addGeminiCloudModel(
                      modelId = modelId,
                      displayName = displayName,
                    )
                    scope.launch {
                      snackbarHostState.showSnackbar("Gemini model '$displayName' added to chat")
                    }
                  },
                )
              }
              // Show already-added Gemini models so they can be selected / navigated to.
              if (geminiModels.isNotEmpty()) {
                item(key = "gemini_models_label") {
                  Text(
                    "Added Gemini Models",
                    color = neonGreen,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                  )
                }
              }
              items(geminiModels) { model ->
                val expanded = modelItemExpandedStates.getOrDefault(model.name, true)
                ModelItem(
                  model = model,
                  task = null,
                  modelManagerViewModel = viewModel,
                  onModelClicked = handleClickModel,
                  onBenchmarkClicked = onBenchmarkClicked,
                  expanded = expanded,
                  showBenchmarkButton = false,
                  onExpanded = { modelItemExpandedStates[model.name] = it },
                )
              }
            }
          }
        }
      }

      SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(alignment = Alignment.BottomCenter).padding(bottom = 32.dp),
      )

      // Gradient overlay at the bottom.
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .height(innerPadding.calculateBottomPadding())
            .background(
              Brush.verticalGradient(
                colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surfaceContainer)
              )
            )
            .align(Alignment.BottomCenter)
      )
    }
  }

  if (showTaskSelectorBottomSheet) {
    ModalBottomSheet(
      onDismissRequest = { showTaskSelectorBottomSheet = false },
      sheetState = sheetState,
    ) {
      Column(
        modifier = Modifier.padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          stringResource(R.string.model_manager_select_task_title),
          color = MaterialTheme.colorScheme.onSurface,
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.padding(bottom = 8.dp).padding(start = 16.dp),
        )
        for (task in taskCandidates) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier =
              Modifier.fillMaxWidth()
                .clickable {
                  val model = modelForTaskCandidate
                  if (model != null) {
                    onModelSelected(task, model)
                  }
                  scope.launch {
                    sheetState.hide()
                    showTaskSelectorBottomSheet = false
                  }
                }
                .padding(horizontal = 16.dp, vertical = 4.dp),
          ) {
            Text(
              task.label,
              color = MaterialTheme.colorScheme.onSurface,
              style = MaterialTheme.typography.titleMedium,
            )
            TaskIcon(task = task, width = 40.dp)
          }
        }
      }
    }
  }

  // Import model bottom sheet.
  if (showImportModelSheet) {
    ModalBottomSheet(onDismissRequest = { showImportModelSheet = false }, sheetState = sheetState) {
      Text(
        "Import model",
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp),
      )
      val cbImportFromLocalFile = stringResource(R.string.cd_import_model_from_local_file_button)
      Box(
        modifier =
          Modifier.clickable {
              scope.launch {
                // Give it sometime to show the click effect.
                delay(200)
                showImportModelSheet = false

                // Show file picker.
                val intent =
                  Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    // Single select.
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                  }
                filePickerLauncher.launch(intent)
              }
            }
            .semantics {
              role = Role.Button
              contentDescription = cbImportFromLocalFile
            }
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
          Icon(Icons.AutoMirrored.Outlined.NoteAdd, contentDescription = null)
          Text("From local model file", modifier = Modifier.clearAndSetSemantics {})
        }
      }
    }
  }

  // Import dialog
  if (showImportDialog) {
    selectedLocalModelFileUri.value?.let { uri ->
      ModelImportDialog(
        uri = uri,
        onDismiss = { showImportDialog = false },
        onDone = { info ->
          selectedImportedModelInfo.value = info
          showImportDialog = false
          showImportingDialog = true
        },
      )
    }
  }

  // Importing in progress dialog.
  if (showImportingDialog) {
    selectedLocalModelFileUri.value?.let { uri ->
      selectedImportedModelInfo.value?.let { info ->
        ModelImportingDialog(
          uri = uri,
          info = info,
          onDismiss = { showImportingDialog = false },
          onDone = {
            viewModel.addImportedLlmModel(info = it)
            showImportingDialog = false

            // Show a snack bar for successful import.
            scope.launch { snackbarHostState.showSnackbar("Model imported successfully") }
          },
        )
      }
    }
  }

  // Alert dialog for unsupported file type.
  if (showUnsupportedFileTypeDialog) {
    AlertDialog(
      icon = {
        Icon(
          Icons.Rounded.Error,
          contentDescription = stringResource(R.string.cd_error),
          tint = MaterialTheme.colorScheme.error,
        )
      },
      onDismissRequest = { showUnsupportedFileTypeDialog = false },
      title = { Text("Unsupported file type") },
      text = { Text("Only \".task\" or \".litertlm\" file type is supported.") },
      confirmButton = {
        Button(onClick = { showUnsupportedFileTypeDialog = false }) {
          Text(stringResource(R.string.ok))
        }
      },
    )
  }

  // Alert dialog for unsupported web model.
  if (showUnsupportedWebModelDialog) {
    AlertDialog(
      icon = {
        Icon(
          Icons.Rounded.Error,
          contentDescription = stringResource(R.string.cd_error),
          tint = MaterialTheme.colorScheme.error,
        )
      },
      onDismissRequest = { showUnsupportedWebModelDialog = false },
      title = { Text("Unsupported model type") },
      text = { Text("Looks like the model is a web-only model and is not supported by the app.") },
      confirmButton = {
        Button(onClick = { showUnsupportedWebModelDialog = false }) {
          Text(stringResource(R.string.ok))
        }
      },
    )
  }

  // Add API Model dialog (BESPOKE tab).
  if (showAddApiModelDialog) {
    AddApiModelDialog(
      onDismiss = { showAddApiModelDialog = false },
      onModelAdded = { label, baseUrl, apiKey, modelId, contextWindowSize ->
        viewModel.addBespokeApiModel(
          modelLabel = label,
          baseUrl = baseUrl,
          apiKey = apiKey,
          modelId = modelId,
          contextWindowSize = contextWindowSize,
        )
        showAddApiModelDialog = false
        scope.launch { snackbarHostState.showSnackbar("API model '$label' added") }
      },
    )
  }
}

// Helper function to get the file name from a URI
private fun getFileName(context: Context, uri: Uri): String? {
  if (uri.scheme == "content") {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex != -1) {
          return cursor.getString(nameIndex)
        }
      }
    }
  } else if (uri.scheme == "file") {
    return uri.lastPathSegment
  }
  return null
}
