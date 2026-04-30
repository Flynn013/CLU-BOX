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

package com.google.ai.edge.gallery.ui.common

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val TAG = "AGMemoryWarning"
private const val BYTES_IN_GB = 1024f * 1024 * 1024
/** Threshold above which swap usage is considered critical (90 %). */
private const val SWAP_CRITICAL_THRESHOLD = 0.90
/** Polling interval for swap pressure checks in milliseconds. */
private const val SWAP_POLL_INTERVAL_MS = 5_000L

/** Composable function to display a memory warning alert dialog. */
@Composable
fun MemoryWarningAlert(onProceeded: () -> Unit, onDismissed: () -> Unit) {
  AlertDialog(
    title = { Text(stringResource(R.string.memory_warning_title)) },
    text = { Text(stringResource(R.string.memory_warning_content)) },
    onDismissRequest = onDismissed,
    confirmButton = {
      TextButton(onClick = onProceeded) {
        Text(stringResource(R.string.memory_warning_proceed_anyway))
      }
    },
    dismissButton = { TextButton(onClick = onDismissed) { Text(stringResource(R.string.cancel)) } },
  )
}

/** Checks if the device's memory is lower than the required minimum for the given model. */
fun isMemoryLow(context: Context, model: Model): Boolean {
  val activityManager =
    context.getSystemService(android.app.Activity.ACTIVITY_SERVICE) as? ActivityManager
  val minDeviceMemoryInGb = model.minDeviceMemoryInGb
  return if (activityManager != null && minDeviceMemoryInGb != null) {
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memoryInfo)
    var deviceMemInGb = memoryInfo.totalMem / BYTES_IN_GB
    // API 34+ uses advertisedMem instead of totalMem for better accuracy.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      deviceMemInGb = memoryInfo.advertisedMem / BYTES_IN_GB
    }
    Log.d(
      TAG,
      "Device memory (GB): $deviceMemInGb. " +
        "Model's required min device memory (GB): $minDeviceMemoryInGb.",
    )
    deviceMemInGb < minDeviceMemoryInGb
  } else {
    false
  }
}

/**
 * Returns a cold [Flow] that emits `true` whenever swap usage exceeds
 * [SWAP_CRITICAL_THRESHOLD] and `false` when it drops back below. Polls
 * [/proc/meminfo] every [SWAP_POLL_INTERVAL_MS] milliseconds, but only
 * emits when the critical state actually changes to avoid redundant updates.
 *
 * Collect this on a background coroutine and surface a
 * [ChatMessageWarning] in the active chat when it emits `true`.
 */
fun observeSwapPressure(): Flow<Boolean> = flow {
  var lastState: Boolean? = null
  while (true) {
    val current = isSwapPressureCritical()
    if (current != lastState) {
      emit(current)
      lastState = current
    }
    delay(SWAP_POLL_INTERVAL_MS)
  }
}

/**
 * Returns `true` when swap usage is above the critical threshold.
 * Reads `/proc/meminfo` directly with early termination once both
 * SwapTotal and SwapFree values are found; safe to call from any thread.
 */
fun isSwapPressureCritical(): Boolean {
  return try {
    val meminfoFile = File("/proc/meminfo")
    if (!meminfoFile.exists()) return false
    var swapTotal = 0L
    var swapFree = 0L
    var foundTotal = false
    var foundFree = false
    meminfoFile.useLines { lines ->
      for (line in lines) {
        when {
          !foundTotal && line.startsWith("SwapTotal:") -> {
            swapTotal = parseMemInfoKb(line)
            foundTotal = true
          }
          !foundFree && line.startsWith("SwapFree:") -> {
            swapFree = parseMemInfoKb(line)
            foundFree = true
          }
        }
        if (foundTotal && foundFree) break // early termination
      }
    }
    if (swapTotal == 0L) return false // no swap partition
    val swapUsedRatio = (swapTotal - swapFree).toDouble() / swapTotal.toDouble()
    Log.d(TAG, "Swap: total=${swapTotal}kB free=${swapFree}kB used=${String.format("%.1f", swapUsedRatio * 100)}%")
    swapUsedRatio > SWAP_CRITICAL_THRESHOLD
  } catch (e: Exception) {
    Log.e(TAG, "Failed to read swap pressure from /proc/meminfo", e)
    false
  }
}

/** Parses a `/proc/meminfo` line of the form `SwapTotal:   1048576 kB` → 1048576. */
private fun parseMemInfoKb(line: String): Long =
  line.substringAfter(":").trim().split(" ").firstOrNull()?.toLongOrNull() ?: 0L
