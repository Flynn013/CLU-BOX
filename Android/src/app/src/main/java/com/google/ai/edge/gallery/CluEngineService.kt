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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service that hosts the CLU/BOX inference engine.
 *
 * Promoting the LLM inference work to a foreground service artificially lowers the
 * app's `oom_adj` score, making the Android Low Memory Killer treat this process as
 * a critical user-facing task. The [PowerManager.WakeLock] prevents the CPU from
 * entering deep sleep mid-inference when the screen turns off.
 *
 * Usage from a ViewModel / Activity:
 * ```
 * val intent = Intent(context, CluEngineService::class.java)
 * ContextCompat.startForegroundService(context, intent)
 * ```
 *
 * Call [acquireInferenceWakeLock] before starting inference and
 * [releaseInferenceWakeLock] in the `finally` block when inference completes.
 */
class CluEngineService : Service() {

  companion object {
    private const val TAG = "CluEngineService"
    private const val CHANNEL_ID = "clu_engine_channel"
    private const val NOTIFICATION_ID = 9001
    private const val WAKELOCK_TAG = "CLU_BOX::InferenceWakeLock"

    /** Maximum wakelock duration (10 minutes) as a safety net. */
    private const val WAKELOCK_TIMEOUT_MS = 10L * 60 * 1000

    /**
     * Convenience method to start the service as a foreground service.
     */
    fun start(context: Context) {
      val intent = Intent(context, CluEngineService::class.java)
      context.startForegroundService(intent)
    }

    /**
     * Convenience method to stop the service.
     */
    fun stop(context: Context) {
      val intent = Intent(context, CluEngineService::class.java)
      context.stopService(intent)
    }
  }

  /** Binder for local in-process binding. */
  inner class LocalBinder : Binder() {
    val service: CluEngineService get() = this@CluEngineService
  }

  private val binder = LocalBinder()
  private var wakeLock: PowerManager.WakeLock? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    Log.d(TAG, "CluEngineService created")
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startForeground(NOTIFICATION_ID, buildNotification())
    Log.d(TAG, "CluEngineService promoted to foreground")
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder {
    return binder
  }

  override fun onDestroy() {
    releaseInferenceWakeLock()
    Log.d(TAG, "CluEngineService destroyed")
    super.onDestroy()
  }

  // ── WakeLock management ────────────────────────────────────────────────

  /**
   * Acquire a partial wakelock to keep the CPU active during inference.
   * Call this immediately before [generateResponseAsync] or shell execution begins.
   * Safe to call multiple times — only the first call acquires.
   */
  @Synchronized
  fun acquireInferenceWakeLock() {
    if (wakeLock?.isHeld == true) return
    try {
      val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
        acquire(WAKELOCK_TIMEOUT_MS)
      }
      Log.d(TAG, "WakeLock acquired")
    } catch (e: Throwable) {
      Log.e("CLU_CRASH_REPORT", "Failed to acquire WakeLock: ${e.stackTraceToString()}")
    }
  }

  /**
   * Release the wakelock. Must be called in a `finally` block after inference
   * completes, is cancelled, or errors out.
   */
  @Synchronized
  fun releaseInferenceWakeLock() {
    try {
      wakeLock?.let {
        if (it.isHeld) {
          it.release()
          Log.d(TAG, "WakeLock released")
        }
      }
    } catch (e: Throwable) {
      Log.e("CLU_CRASH_REPORT", "Failed to release WakeLock: ${e.stackTraceToString()}")
    }
    wakeLock = null
  }

  // ── Notification helpers ───────────────────────────────────────────────

  private fun createNotificationChannel() {
    val channel = NotificationChannel(
      CHANNEL_ID,
      "CLU/BOX Engine",
      NotificationManager.IMPORTANCE_LOW, // Low = no sound, but persistent icon
    ).apply {
      description = "Keeps the CLU/BOX inference engine active"
    }
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(channel)
  }

  private fun buildNotification(): Notification {
    val tapIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val pendingIntent = PendingIntent.getActivity(
      this, 0, tapIntent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("CLU/BOX Engine: Active")
      .setContentText("Inference engine is running")
      .setSmallIcon(R.mipmap.ic_launcher)
      .setOngoing(true)
      .setContentIntent(pendingIntent)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .build()
  }
}
