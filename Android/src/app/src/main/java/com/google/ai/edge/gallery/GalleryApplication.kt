/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery

import android.app.Application
import android.util.Log
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.brainbox.BrainBoxSeeder
import com.google.ai.edge.gallery.data.busybox.BusyBoxBridge
import com.google.ai.edge.gallery.data.python.PythonBridge
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "GalleryApplication"

@HiltAndroidApp
class GalleryApplication : Application() {

  @Inject lateinit var dataStoreRepository: DataStoreRepository

  override fun onCreate() {
    super.onCreate()

    // ── Chaquopy — initialize Python on the main thread (Chaquopy requirement) ──
    // Python.start() must be called before any background thread calls getInstance().
    // PythonBridge wraps the double-checked-lock so this is safe even if called
    // more than once (e.g. in tests).
    try {
      PythonBridge.initialize(this)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize Python interpreter — PYTHON_EXEC skill unavailable", e)
    }

    // ── BusyBox — extract the embedded binary on IO thread eagerly ──
    // BusyBoxBridge.ensureInstalled() is idempotent and locks internally.
    // Pre-installing at startup avoids a cold-start delay on the first
    // shellExecute/PYTHON_EXEC call from the agent loop.
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val path = BusyBoxBridge.ensureInstalled(this@GalleryApplication)
        if (path != null) {
          Log.d(TAG, "BusyBox ready at $path")
        } else {
          Log.e(TAG, "BusyBox install failed — shellExecute skill unavailable (check busybox assets)")
        }
      } catch (e: Exception) {
        Log.e(TAG, "BusyBox initialization error", e)
      }
    }

    // IO BYPASS: Shift the DataStore synchronous load off the Main Thread
    // so the app bootloader doesn't choke out on cold start.
    CoroutineScope(Dispatchers.IO).launch {
      ThemeSettings.themeOverride.value = dataStoreRepository.readTheme()
    }

    // ── BrainBox — seed core reference neurons on first run ──
    // BrainBoxSeeder is idempotent: it only inserts when fewer than
    // MIN_CORE_COUNT core neurons exist, preserving all user data.
    CoroutineScope(Dispatchers.IO).launch {
      try {
        BrainBoxSeeder.seed(this@GalleryApplication)
      } catch (e: Exception) {
        Log.e(TAG, "BrainBox seeding error", e)
      }
    }
  }
}
