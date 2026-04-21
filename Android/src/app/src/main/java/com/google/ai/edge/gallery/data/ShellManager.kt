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

package com.google.ai.edge.gallery.data

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "ShellManager"

/** Timeout in seconds before a shell process is forcibly killed. */
private const val TIMEOUT_SECONDS = 60L

/** Maximum time (ms) to wait for reader threads to finish after the process exits. */
private const val READER_THREAD_JOIN_TIMEOUT_MS = 5_000L

/**
 * Executes a shell command via the internal bash binary and returns the combined stdout + stderr output.
 *
 * A strict [TIMEOUT_SECONDS]-second timeout is enforced. If the process does not finish
 * in time it is destroyed and `"TIMEOUT ERROR"` is returned, preventing the app from hanging
 * on runaway commands (e.g. accidental infinite loops).
 *
 * @param context Application context used to resolve the internal bash binary path.
 * @param command The shell command string to execute.
 * @return The raw terminal output (stdout followed by stderr), or `"TIMEOUT ERROR"`.
 */
fun executeCommand(context: Context, command: String): String {
  return try {
    Log.d(TAG, "executeCommand: $command")

    // Use the extracted bash binary so shell syntax (&&, |, $VAR) works correctly.
    // /system/bin/sh does not support these features reliably on Android.
    val bashPath = EnvironmentInstaller.bashPath(context).absolutePath
    if (!File(bashPath).exists()) {
      throw IllegalStateException("Bash binary missing at $bashPath")
    }

    val pb = ProcessBuilder(bashPath, "-c", command)
      .redirectErrorStream(false)

    // $HOME uses the Termux-compatible home directory so pkg/apt config files work.
    val homeDir = EnvironmentInstaller.homeDir(context).also { it.mkdirs() }
    val tmpDir  = EnvironmentInstaller.tmpDir(context).also  { it.mkdirs() }
    val binDir  = EnvironmentInstaller.binDir(context)
    val prefix  = EnvironmentInstaller.prefixDir(context)
    val libDir  = EnvironmentInstaller.libDir(context)
    val shell   = EnvironmentInstaller.shellPath(context)

    // Build PATH: sysroot bin + applets (busybox) + stock Android fallback.
    val appletsDir = File(binDir, "applets")
    val effectivePath = buildString {
      if (binDir.isDirectory) {
        append(binDir.absolutePath)
        if (appletsDir.isDirectory) append(":${appletsDir.absolutePath}")
        append(":")
      }
      append("/system/bin:/system/xbin")
    }

    pb.environment()["HOME"]           = homeDir.absolutePath
    pb.environment()["TMPDIR"]         = tmpDir.absolutePath
    pb.environment()["LANG"]           = "en_US.UTF-8"
    pb.environment()["SHELL"]          = shell
    pb.environment()["PATH"]           = effectivePath
    if (prefix.isDirectory) {
      pb.environment()["PREFIX"]         = prefix.absolutePath
      pb.environment()["LD_LIBRARY_PATH"] = libDir.absolutePath
    }

    val process = pb.start()

    // Read stdout and stderr on background threads so the pipe buffers don't fill up
    // and deadlock the process before waitFor returns.
    val stdoutRef = AtomicReference("")
    val stderrRef = AtomicReference("")

    val stdoutThread = Thread {
      stdoutRef.set(process.inputStream.bufferedReader(Charsets.UTF_8).readText())
    }.also { it.start() }

    val stderrThread = Thread {
      stderrRef.set(process.errorStream.bufferedReader(Charsets.UTF_8).readText())
    }.also { it.start() }

    val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    if (!finished) {
      process.destroyForcibly()
      stdoutThread.interrupt()
      stderrThread.interrupt()
      Log.w(TAG, "executeCommand: process timed out after ${TIMEOUT_SECONDS}s — killed")
      return "TIMEOUT ERROR"
    }

    // Block until reader threads complete (no timeout — process already exited so
    // streams will close and readText() will return).
    // Bounded join prevents indefinite hangs if a reader thread gets stuck.
    stdoutThread.join(READER_THREAD_JOIN_TIMEOUT_MS)
    stderrThread.join(READER_THREAD_JOIN_TIMEOUT_MS)

    val stdout = stdoutRef.get()
    val stderr = stderrRef.get()

    val combined = buildString {
      if (stdout.isNotEmpty()) append(stdout)
      if (stderr.isNotEmpty()) {
        if (isNotEmpty()) append("\n")
        append(stderr)
      }
    }

    Log.d(TAG, "executeCommand: exit=${process.exitValue()}, output=${combined.length} chars")
    combined.ifEmpty { "(no output)" }
  } catch (e: SecurityException) {
    Log.e(TAG, "executeCommand: blocked by Android security (W^X / SELinux)", e)
    "ERROR: [System: Terminal execution blocked by Android OS Security (W^X). Fallback required.]"
  } catch (e: UnsatisfiedLinkError) {
    Log.e(TAG, "executeCommand: native library load failed", e)
    "ERROR: [System: Terminal native library unavailable. Shell execution disabled.]"
  } catch (e: Exception) {
    Log.e(TAG, "executeCommand: exception", e)
    "ERROR: ${e.message}"
  }
}
