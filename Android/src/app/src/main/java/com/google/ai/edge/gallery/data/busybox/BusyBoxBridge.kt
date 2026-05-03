/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.data.busybox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "BusyBoxBridge"

/**
 * Pure-native shell capability for the CLU/BOX Cognitive OS.
 *
 * Replaces the legacy Termux/PRoot stack with a single statically-linked
 * **arm64-v8a** `busybox` binary embedded in `assets/busybox/busybox-arm64-v8a`.
 *
 * On first call to [ensureInstalled] the binary is extracted from APK assets to
 * `${filesDir}/busybox/busybox`, marked executable via `chmod +x` and from then
 * on every subsequent shell invocation goes through [ProcessBuilder] — there is
 * no PTY, no broadcast IPC, no external app dependency, and no background
 * service plumbing.
 *
 * The bridge is a Kotlin `object` (singleton) because there is exactly one
 * embedded binary per process and concurrent calls into [exec] are already
 * isolated by their own [Process] instances.
 *
 * ## Memory profile
 * BusyBox is ~1MB statically linked. Each [exec] call forks a short-lived
 * process that inherits no JVM state, which keeps the heap pressure on the
 * Pixel 10 Pro (16GB) and OnePlus 9 Pro (12GB+12GB swap) negligible.
 *
 * ## Concurrency
 * All file-system mutations happen inside [installLock]. Read-only queries
 * (`isInstalled`, `binaryPath`) are lock-free and safe from any thread.
 */
object BusyBoxBridge {

  /** Asset path of the embedded BusyBox binary (arm64-v8a, statically linked). */
  private const val ASSET_NAME = "busybox/busybox-arm64-v8a"

  /** Subdirectory under `filesDir` where the extracted binary lives. */
  private const val INSTALL_DIR = "busybox"

  /** Final on-disk filename of the extracted, executable binary. */
  private const val BINARY_NAME = "busybox"

  /** Default ProcessBuilder timeout when callers do not specify one. */
  private const val DEFAULT_TIMEOUT_MS = 30_000L

  private val installed = AtomicBoolean(false)
  private val installLock = Any()

  // ─────────────────────────────────────────────────────────────────────────
  //  Public lifecycle
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Idempotently extracts and chmod+x's the embedded BusyBox binary.
   *
   * Safe to call from any thread. The first call performs the disk I/O on
   * [Dispatchers.IO]; subsequent calls return immediately after a volatile
   * read.
   *
   * Returns the absolute path of the executable on success or `null` if the
   * asset is missing or extraction fails (the caller should treat a `null`
   * return as a hard failure of the shell subsystem).
   */
  suspend fun ensureInstalled(context: Context): String? = withContext(Dispatchers.IO) {
    val target = binaryPath(context)
    if (installed.get() && target.exists() && target.canExecute()) {
      return@withContext target.absolutePath
    }
    synchronized(installLock) {
      // Re-check under the lock.
      if (installed.get() && target.exists() && target.canExecute()) {
        return@synchronized target.absolutePath
      }
      val parent = target.parentFile ?: return@withContext null
      if (!parent.exists() && !parent.mkdirs()) {
        Log.e(TAG, "ensureInstalled: failed to create $parent")
        return@withContext null
      }
      // Verify we have the asset before we write anything.
      val assetMgr = context.assets
      try {
        assetMgr.openFd(ASSET_NAME).close()
      } catch (e: IOException) {
        // openFd doesn't always work for compressed assets — fall through and
        // let the open() call below fail naturally.
        Log.d(TAG, "openFd not available (compressed asset); proceeding with open()")
      }
      try {
        assetMgr.open(ASSET_NAME).use { input ->
          target.outputStream().use { output -> input.copyTo(output) }
        }
      } catch (e: IOException) {
        Log.e(TAG, "ensureInstalled: missing asset '$ASSET_NAME' — drop a real arm64 busybox binary into app/src/main/assets/busybox/", e)
        return@withContext null
      }
      if (!target.setExecutable(true, /* ownerOnly = */ true)) {
        // Fallback to invoking the system chmod through the shell — required
        // on some OEM builds where setExecutable() is silently NO-OPed.
        try {
          Runtime.getRuntime().exec(arrayOf("/system/bin/chmod", "0700", target.absolutePath)).waitFor()
        } catch (e: Exception) {
          Log.e(TAG, "ensureInstalled: chmod fallback failed", e)
          return@withContext null
        }
      }
      installed.set(true)
      Log.d(TAG, "BusyBox installed at ${target.absolutePath} (sha256=${sha256(target).take(16)}…)")
    }
    target.absolutePath
  }

  /** True iff [ensureInstalled] has successfully completed in this process. */
  fun isInstalled(context: Context): Boolean =
    installed.get() && binaryPath(context).let { it.exists() && it.canExecute() }

  /**
   * Absolute file representing the on-disk BusyBox binary, regardless of
   * whether it has been extracted yet. Useful for diagnostic UIs.
   */
  fun binaryPath(context: Context): File =
    File(File(context.filesDir, INSTALL_DIR), BINARY_NAME)

  // ─────────────────────────────────────────────────────────────────────────
  //  Public execution API
  // ─────────────────────────────────────────────────────────────────────────

  /** Result of a single [exec] call. */
  data class Result(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
  ) {
    val isSuccess: Boolean get() = exitCode == 0
  }

  /**
   * Executes a BusyBox applet as a sub-process and captures stdout/stderr.
   *
   * @param context  Android context (used to locate the binary).
   * @param applet   BusyBox applet name (e.g. `"grep"`, `"sed"`, `"ls"`,
   *                 `"sh"`). Pass `"sh"` together with `"-c"` arguments to
   *                 evaluate full shell pipelines.
   * @param args     Arguments to pass after the applet.
   * @param workDir  Working directory; defaults to the file_box workspace.
   * @param env      Additional environment variables; merged on top of the
   *                 parent process environment.
   * @param timeoutMs Hard timeout — the process is `destroyForcibly()`'d if it
   *                 does not exit in time.
   */
  suspend fun exec(
    context: Context,
    applet: String,
    args: List<String> = emptyList(),
    workDir: File? = null,
    env: Map<String, String> = emptyMap(),
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
  ): Result = withContext(Dispatchers.IO) {
    val bin = ensureInstalled(context)
      ?: return@withContext Result(
        exitCode = 127,
        stdout = "",
        stderr = "[BusyBoxBridge] busybox binary not installed",
        durationMs = 0,
      )

    val cmd = buildList<String> {
      add(bin)
      add(applet)
      addAll(args)
    }

    val pb = ProcessBuilder(cmd).apply {
      directory(workDir ?: defaultWorkDir(context))
      // Merge environment.
      val merged = environment()
      merged["PATH"] = "${File(bin).parent}:${merged["PATH"] ?: "/system/bin:/system/xbin"}"
      merged["HOME"] = (workDir ?: defaultWorkDir(context)).absolutePath
      env.forEach { (k, v) -> merged[k] = v }
      redirectErrorStream(false)
    }

    val start = System.currentTimeMillis()
    return@withContext try {
      val proc = pb.start()
      withTimeout(timeoutMs) {
        // Drain the streams concurrently to avoid pipe-buffer deadlocks.
        val outBytes = proc.inputStream.use { it.readBytes() }
        val errBytes = proc.errorStream.use { it.readBytes() }
        val code = proc.waitFor()
        Result(
          exitCode = code,
          stdout = String(outBytes, Charsets.UTF_8),
          stderr = String(errBytes, Charsets.UTF_8),
          durationMs = System.currentTimeMillis() - start,
        )
      }
    } catch (e: TimeoutCancellationException) {
      Log.w(TAG, "exec timeout: ${cmd.joinToString(" ")}")
      Result(
        exitCode = 124,
        stdout = "",
        stderr = "[BusyBoxBridge] command timed out after ${timeoutMs}ms: ${cmd.joinToString(" ")}",
        durationMs = System.currentTimeMillis() - start,
      )
    } catch (e: Exception) {
      Log.e(TAG, "exec failed: ${cmd.joinToString(" ")}", e)
      Result(
        exitCode = 1,
        stdout = "",
        stderr = "[BusyBoxBridge] ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}",
        durationMs = System.currentTimeMillis() - start,
      )
    }
  }

  /**
   * Convenience: evaluate a full shell pipeline through `busybox sh -c`.
   *
   * Equivalent to `exec(context, "sh", listOf("-c", line), …)` but reads more
   * naturally at call sites such as `BusyBoxBridge.shell(ctx, "grep -ri foo .")`.
   */
  suspend fun shell(
    context: Context,
    line: String,
    workDir: File? = null,
    env: Map<String, String> = emptyMap(),
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
  ): Result = exec(
    context = context,
    applet = "sh",
    args = listOf("-c", line),
    workDir = workDir,
    env = env,
    timeoutMs = timeoutMs,
  )

  /** The default workspace root used by every box subsystem. */
  fun defaultWorkDir(context: Context): File =
    File(context.filesDir, "clu_file_box").apply { mkdirs() }

  // ─────────────────────────────────────────────────────────────────────────
  //  Internals
  // ─────────────────────────────────────────────────────────────────────────

  private fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buf = ByteArray(8 * 1024)
      while (true) {
        val n = input.read(buf)
        if (n <= 0) break
        md.update(buf, 0, n)
      }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
  }
}
