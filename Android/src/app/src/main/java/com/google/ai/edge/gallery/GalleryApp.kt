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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.google.ai.edge.gallery.ui.brainbox.BrainBoxScreen
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.navigation.GalleryNavHost
import com.google.ai.edge.gallery.ui.theme.neonGreen
import com.google.ai.edge.gallery.ui.theme.absoluteBlack

/** Top level composable representing the main screen of the CLU/BOX application. */
@Composable
fun GalleryApp(
  navController: NavHostController = rememberNavController(),
  modelManagerViewModel: ModelManagerViewModel,
) {
  var selectedTab by remember { mutableIntStateOf(0) }

  Scaffold(
    containerColor = absoluteBlack,
    bottomBar = {
      NavigationBar(
        containerColor = absoluteBlack,
        contentColor = neonGreen,
      ) {
        NavigationBarItem(
          selected = selectedTab == 0,
          onClick = { selectedTab = 0 },
          icon = {
            Icon(
              imageVector = Icons.Outlined.GridView,
              contentDescription = "THE GRID",
              tint = if (selectedTab == 0) neonGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          },
          label = {
            Text(
              text = "THE GRID",
              fontFamily = FontFamily.Monospace,
              color = if (selectedTab == 0) neonGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          },
          colors = NavigationBarItemDefaults.colors(
            selectedIconColor = neonGreen,
            selectedTextColor = neonGreen,
            indicatorColor = Color(0xFF1A1A1A),
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
          ),
        )
        NavigationBarItem(
          selected = selectedTab == 1,
          onClick = { selectedTab = 1 },
          icon = {
            Icon(
              imageVector = Icons.Outlined.Hub,
              contentDescription = "BrainBox",
              tint = if (selectedTab == 1) neonGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          },
          label = {
            Text(
              text = "BrainBox",
              fontFamily = FontFamily.Monospace,
              color = if (selectedTab == 1) neonGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          },
          colors = NavigationBarItemDefaults.colors(
            selectedIconColor = neonGreen,
            selectedTextColor = neonGreen,
            indicatorColor = Color(0xFF1A1A1A),
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
          ),
        )
      }
    },
  ) { innerPadding ->
    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      when (selectedTab) {
        0 -> GalleryNavHost(navController = navController, modelManagerViewModel = modelManagerViewModel)
        1 -> BrainBoxScreen()
      }
    }
  }
}

