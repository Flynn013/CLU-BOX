/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.ui.osmodules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

// ==============================================================================
// 1. DATA MODELS
// ==============================================================================

data class FeatureNode(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val subFeatures: List<FeatureNode> = emptyList(),
)

data class AppConfig(
    val workingTitle: String,
    val references: List<String>,
    val uiTheme: String,
    val features: List<FeatureNode>,
)

// ==============================================================================
// 2. MAIN CREATION SCREEN
// ==============================================================================

// Theme colors — Flynn Dark + Electric Cyan
private val BgColor    = Color(0xFF121212)
private val CardColor  = Color(0xFF1E1E1E)
private val CyanAccent = Color(0xFF00E5FF)
private val TextColor  = Color(0xFFE0E0E0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppProjectCreationScreen(
    onGeneratePlanningSession: (AppConfig) -> Unit,
    onCancel: () -> Unit,
) {
    var workingTitle by remember { mutableStateOf("") }
    var uiTheme by remember { mutableStateOf("") }
    var references by remember { mutableStateOf(listOf<String>()) }
    var newReferenceLink by remember { mutableStateOf("") }
    var features by remember { mutableStateOf(listOf<FeatureNode>()) }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "New App Project",
                        color = CyanAccent,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = TextColor)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    onGeneratePlanningSession(
                        AppConfig(workingTitle, references, uiTheme, features)
                    )
                },
                containerColor = CyanAccent,
                contentColor = Color.Black,
            ) {
                Icon(Icons.Default.Check, contentDescription = "Generate")
                Spacer(Modifier.width(8.dp))
                Text("Generate Planning Session", fontWeight = FontWeight.Bold)
            }
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 100.dp),
        ) {

            // --- WORKING TITLE ---
            item {
                ProjectSectionHeader("WORKING TITLE")
                OutlinedTextField(
                    value = workingTitle,
                    onValueChange = { workingTitle = it },
                    placeholder = { Text("e.g. GRID Engine") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = cluTextFieldColors(CyanAccent, CardColor, TextColor),
                    singleLine = true,
                )
            }

            // --- UI THEME ---
            item {
                ProjectSectionHeader("UI THEME")
                OutlinedTextField(
                    value = uiTheme,
                    onValueChange = { uiTheme = it },
                    placeholder = { Text("Describe the visual style, colors, and layout vibe...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    colors = cluTextFieldColors(CyanAccent, CardColor, TextColor),
                    maxLines = 5,
                )
            }

            // --- REFERENCES ---
            item {
                ProjectSectionHeader("REFERENCES")
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardColor),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        references.forEach { ref ->
                            Text(
                                "- $ref",
                                color = TextColor,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newReferenceLink,
                                onValueChange = { newReferenceLink = it },
                                placeholder = { Text("Link or File path") },
                                modifier = Modifier.weight(1f),
                                colors = cluTextFieldColors(CyanAccent, BgColor, TextColor),
                                singleLine = true,
                            )
                            IconButton(onClick = {
                                if (newReferenceLink.isNotBlank()) {
                                    references = references + newReferenceLink
                                    newReferenceLink = ""
                                }
                            }) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Add Reference",
                                    tint = CyanAccent,
                                )
                            }
                        }
                    }
                }
            }

            // --- APP FEATURES ---
            item {
                ProjectSectionHeader("APP FEATURES")
            }

            items(features) { feature ->
                FeatureCard(
                    feature = feature,
                    depth = 0,
                    cyanAccent = CyanAccent,
                    cardColor = CardColor,
                    textColor = TextColor,
                    onAddSubFeature = { parentId, newSubFeature ->
                        features = updateFeatureTree(features, parentId, newSubFeature)
                    },
                )
            }

            item {
                var showAddRootFeature by remember { mutableStateOf(false) }

                if (showAddRootFeature) {
                    FeatureInputDialog(
                        onDismiss = { showAddRootFeature = false },
                        onConfirm = { name, desc ->
                            features = features + FeatureNode(name = name, description = desc)
                            showAddRootFeature = false
                        },
                    )
                }

                OutlinedButton(
                    onClick = { showAddRootFeature = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanAccent),
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Feature")
                    Spacer(Modifier.width(8.dp))
                    Text("Add Root Feature")
                }
            }
        }
    }
}

// ==============================================================================
// 3. UI COMPONENTS & HELPERS
// ==============================================================================

@Composable
private fun ProjectSectionHeader(title: String) {
    Text(
        text = title,
        color = Color.Gray,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 4.dp, top = 8.dp),
    )
}

@Composable
private fun FeatureCard(
    feature: FeatureNode,
    depth: Int,
    cyanAccent: Color,
    cardColor: Color,
    textColor: Color,
    onAddSubFeature: (String, FeatureNode) -> Unit,
) {
    var showAddSub by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp, bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(cardColor)
            .padding(16.dp),
    ) {
        Text(feature.name, color = cyanAccent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(feature.description, color = textColor, fontSize = 14.sp)

        Spacer(Modifier.height(12.dp))

        // Render children recursively with a slightly darker card surface.
        feature.subFeatures.forEach { sub ->
            FeatureCard(
                feature = sub,
                depth = depth + 1,
                cyanAccent = cyanAccent,
                cardColor = BgColor,
                textColor = textColor,
                onAddSubFeature = onAddSubFeature,
            )
        }

        Text(
            text = "+ Add Sub-Feature",
            color = cyanAccent.copy(alpha = 0.7f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clickable { showAddSub = true }
                .padding(top = 8.dp),
        )

        if (showAddSub) {
            FeatureInputDialog(
                onDismiss = { showAddSub = false },
                onConfirm = { name, desc ->
                    onAddSubFeature(feature.id, FeatureNode(name = name, description = desc))
                    showAddSub = false
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeatureInputDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardColor,
        title = { Text("Add Feature", color = Color.White) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Feature Name") },
                    singleLine = true,
                    colors = cluTextFieldColors(CyanAccent, BgColor, Color.White),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Description / Requirements") },
                    colors = cluTextFieldColors(CyanAccent, BgColor, Color.White),
                    modifier = Modifier.height(100.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name, desc) }) {
                Text("Add", color = CyanAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) }
        },
    )
}

/** Styles OutlinedTextField for the Flynn Dark + Electric Cyan theme. */
@Composable
private fun cluTextFieldColors(accent: Color, bg: Color, text: Color) =
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = accent,
        unfocusedBorderColor = Color.DarkGray,
        focusedContainerColor = bg,
        unfocusedContainerColor = bg,
        focusedTextColor = text,
        unfocusedTextColor = text,
        cursorColor = accent,
        focusedLabelColor = accent,
        unfocusedLabelColor = Color.Gray,
    )

/** Recursively inserts [newChild] under the node with [targetId]. */
fun updateFeatureTree(
    nodes: List<FeatureNode>,
    targetId: String,
    newChild: FeatureNode,
): List<FeatureNode> = nodes.map { node ->
    if (node.id == targetId) {
        node.copy(subFeatures = node.subFeatures + newChild)
    } else {
        node.copy(subFeatures = updateFeatureTree(node.subFeatures, targetId, newChild))
    }
}
