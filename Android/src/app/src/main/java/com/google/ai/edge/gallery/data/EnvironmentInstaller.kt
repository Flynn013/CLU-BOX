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
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

private const val TAG = "EnvironmentInstaller"

/**
 * Bootstraps a self-contained Linux sysroot inside the app's private data
 * directory by extracting the Termux `bootstrap-aarch64.zip`.
 *
 * **Source priority:**
 * 1. Bundled asset at `assets/bootstrap-aarch64.zip` — used when the zip is
 *    shipped inside the APK (avoids any network requirement).
 * 2. Network download from the official Termux release — used as a fallback
 *    when no bundled asset is present.
 *
 * After installation the sysroot lives at `context.filesDir/usr/` and provides
 * `bash`, `apt`/`pkg`, and the corresponding shared libraries — all without
 * requiring the external Termux app to be installed.
 *
 * Typical lifecycle:
 * 1. [MstrCtrlScreen] or [TerminalSessionManager] calls [ensureInstalled].
 * 2. If the sysroot is already present the call is a no-op.
 * 3. Otherwise the bootstrap archive is copied from assets (or downloaded),
 *    extracted, symlinks are created from `SYMLINKS.txt`, and file permissions
 *    are fixed.
 * 4. Once [state] emits [State.Ready], the terminal can start.
 */
object EnvironmentInstaller {

  /**
   * Name of the bundled bootstrap asset (placed in `app/src/main/assets/`).
   * If this asset exists it is used in preference to a network download.
   */
  private const val BOOTSTRAP_ASSET_NAME = "bootstrap-aarch64.zip"

  /**
   * URL of the official Termux bootstrap archive for aarch64.
   *
   * Used as a fallback when no bundled asset is available.
   * Update the URL if a newer bootstrap is published or if the release
   * scheme changes.
   */
  private const val BOOTSTRAP_URL =
    "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip"

  /** SharedPreferences file used to track installation state across launches. */
  private const val PREFS_NAME = "env_installer_prefs"
  private const val KEY_INSTALLED = "bootstrap_installed"

  /**
   * Separator used in Termux's `SYMLINKS.txt` — the UTF-8 leftward arrow (U+2190).
   *
   * Each line has the format: `link_target←link_name`.
   */
  private const val SYMLINK_SEPARATOR = "←"

  // ── Observable installation state ──────────────────────────────────

  /** Describes the current phase of the bootstrap installation. */
  sealed class State {
    /** No installation attempted yet. */
    data object Idle : State()

    /** Downloading the bootstrap archive. [percent] is 0–100. */
    data class Downloading(val percent: Int) : State()

    /** Extracting the zip to the sysroot. */
    data object Extracting : State()

    /** Processing symlinks and fixing file permissions. */
    data object FixingPermissions : State()

    /** The sysroot is fully installed and ready to use. */
    data object Ready : State()

    /** Installation failed. [message] contains a human-readable reason. */
    data class Failed(val message: String) : State()
  }

  private val _state = MutableStateFlow<State>(State.Idle)

  /** Observable installation state for UI progress indicators. */
  val state: StateFlow<State> = _state.asStateFlow()

  /**
   * Guards concurrent calls to [ensureInstalled].
   *
   * Without this mutex, two simultaneous callers (e.g. a UI LaunchedEffect
   * and TerminalSessionManager.runPreFlightCheck) both pass the
   * `isInstalled()` fast-path check, both download to the same
   * `cacheDir/bootstrap-aarch64.zip` file concurrently, and the two
   * interleaved writes corrupt the archive — causing extraction to fail
   * with an invalid-zip error and leaving the environment permanently broken.
   */
  private val installMutex = Mutex()

  // ── Path helpers ───────────────────────────────────────────────────

  /** Root of the internal sysroot: `context.filesDir/usr`. */
  fun prefixDir(context: Context): File = File(context.filesDir, "usr")

  /** Bin directory containing executables (`$PREFIX/bin`). */
  fun binDir(context: Context): File = File(prefixDir(context), "bin")

  /** Lib directory containing shared libraries (`$PREFIX/lib`). */
  fun libDir(context: Context): File = File(prefixDir(context), "lib")

  /**
   * The `$HOME` directory for Termux sessions: `context.filesDir/home`.
   *
   * This is a dedicated home directory **separate** from the app's `filesDir`
   * and separate from the sysroot, matching Termux's own layout
   * (`/data/data/com.termux/files/home`).
   */
  fun homeDir(context: Context): File = File(context.filesDir, "home")

  /**
   * The `$TMPDIR` for Termux sessions: `$PREFIX/tmp`.
   *
   * Placing tmp inside the sysroot ensures that temporary files created by
   * package management scripts (e.g. `pkg`/`apt`) stay within a directory
   * that bash and libc can find via the standard Termux conventions.
   */
  fun tmpDir(context: Context): File = File(prefixDir(context), "tmp")

  /** Absolute path to the internal `bash` binary. */
  fun bashPath(context: Context): File = File(binDir(context), "bash")

  /** Absolute path to the internal `sh` binary. */
  fun shPath(context: Context): File = File(binDir(context), "sh")

  /**
   * Returns the best available shell path inside the bootstrapped sysroot.
   *
   * Resolution order:
   * 1. `$PREFIX/bin/bash` — full Bash shell (preferred).
   * 2. `$PREFIX/bin/sh` — POSIX shell fallback.
   * 3. `/system/bin/sh` — stock Android shell (pre-bootstrap or if bootstrap failed).
   */
  fun shellPath(context: Context): String {
    val bash = bashPath(context)
    if (bash.exists() && bash.canExecute()) return bash.absolutePath
    val sh = shPath(context)
    if (sh.exists() && sh.canExecute()) return sh.absolutePath
    return "/system/bin/sh"
  }

  /**
   * Returns `true` when the sysroot appears to be fully installed.
   *
   * Checks the persisted flag first (fast), then verifies that the key
   * binary actually exists on disk (correctness).
   */
  fun isInstalled(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    if (!prefs.getBoolean(KEY_INSTALLED, false)) return false
    return binDir(context).isDirectory &&
      (bashPath(context).exists() || shPath(context).exists())
  }

  // ── Public API ─────────────────────────────────────────────────────

  /**
   * Ensures the Linux sysroot is installed. If already present this is a
   * no-op that immediately sets [state] to [State.Ready].
   *
   * Must be called from a coroutine context. Runs heavy I/O on [Dispatchers.IO].
   *
   * **Source priority:**
   * 1. Bundled `assets/bootstrap-aarch64.zip` (fast — no network required).
   * 2. Network download from GitHub releases (fallback when no asset found).
   */
  suspend fun ensureInstalled(context: Context) {
    // Fast path: already installed — no lock needed.
    if (isInstalled(context)) {
      _state.value = State.Ready
      return
    }

    // Slow path: acquire the mutex so that only one coroutine downloads and
    // extracts the bootstrap at a time.  Re-check after acquiring in case a
    // concurrent caller finished the install while we were waiting.
    installMutex.withLock {
      if (isInstalled(context)) {
        _state.value = State.Ready
        return
      }

      withContext(Dispatchers.IO) {
        try {
          val zipFile = File(context.cacheDir, BOOTSTRAP_ASSET_NAME)

          // 1. Try bundled asset; fall back to network download.
          val fromAsset = tryCopyFromAsset(context, zipFile)
          if (!fromAsset) {
            _state.value = State.Downloading(0)
            downloadBootstrap(zipFile)
          }

          // 2. Extract
          _state.value = State.Extracting
          extractBootstrap(zipFile, context.filesDir)

          // 3. Symlinks + permissions
          _state.value = State.FixingPermissions
          processSymlinks(context.filesDir)
          fixPermissions(context)

          // 4. Create runtime directories that Termux expects.
          homeDir(context).mkdirs()
          tmpDir(context).mkdirs()

          // 5. Clean up
          zipFile.delete()

          // 6. Persist success
          context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_INSTALLED, true).apply()

          _state.value = State.Ready
          Log.i(TAG, "Bootstrap installed at ${prefixDir(context).absolutePath}")
        } catch (e: Exception) {
          Log.e(TAG, "Bootstrap installation failed", e)
          _state.value = State.Failed(e.message ?: "Unknown error")
        }
      } // withContext(Dispatchers.IO)
    } // installMutex.withLock
  }

  // ── Private helpers ────────────────────────────────────────────────

  /**
   * Attempts to copy the bundled `assets/bootstrap-aarch64.zip` to [destination].
   *
   * @return `true` if the asset was found and successfully copied, `false` if
   *         the asset is not bundled (caller should fall back to downloading).
   */
  private fun tryCopyFromAsset(context: Context, destination: File): Boolean {
    return try {
      context.assets.open(BOOTSTRAP_ASSET_NAME).use { input ->
        Log.d(TAG, "Copying bootstrap from bundled asset")
        FileOutputStream(destination).use { output ->
          input.copyTo(output)
        }
      }
      Log.d(TAG, "Asset copy complete → ${destination.absolutePath}")
      true
    } catch (e: IOException) {
      Log.d(TAG, "No bundled bootstrap asset found ($BOOTSTRAP_ASSET_NAME) — will download")
      false
    }
  }

  /**
   * Downloads the bootstrap archive to [destination], following HTTP redirects.
   *
   * GitHub release assets redirect through multiple HTTPS→HTTPS hops; we
   * handle up to 5 redirects manually to cover cross-host redirects that
   * [HttpURLConnection.setInstanceFollowRedirects] may not follow.
   */
  private fun downloadBootstrap(destination: File) {
    var currentUrl = URL(BOOTSTRAP_URL)
    var redirectCount = 0
    var connection: HttpURLConnection

    // Manually follow redirects to handle cross-host HTTPS→HTTPS hops
    // (e.g. github.com → objects.githubusercontent.com).
    while (true) {
      if (redirectCount > 5) throw RuntimeException("Too many redirects")
      Log.d("CLU_BOOTSTRAP", "Downloading from: $currentUrl")
      connection = currentUrl.openConnection() as HttpURLConnection
      connection.instanceFollowRedirects = false
      connection.connectTimeout = 30_000
      connection.readTimeout = 60_000
      connection.connect()

      val responseCode = connection.responseCode
      Log.d("CLU_BOOTSTRAP", "Response code: $responseCode from $currentUrl")

      when (responseCode) {
        HttpURLConnection.HTTP_MOVED_TEMP,
        HttpURLConnection.HTTP_MOVED_PERM,
        HttpURLConnection.HTTP_SEE_OTHER,
        307, 308,
        -> {
          val location = connection.getHeaderField("Location")
            ?: throw RuntimeException("Redirect without Location header")
          connection.disconnect()
          Log.d("CLU_BOOTSTRAP", "Redirect #$redirectCount → $location")
          currentUrl = URL(location)
          redirectCount++
        }

        HttpURLConnection.HTTP_OK -> {
          Log.d("CLU_BOOTSTRAP", "Download stream ready (${connection.contentLengthLong} bytes)")
          break
        }
        else -> {
          connection.disconnect()
          throw RuntimeException("Download failed: HTTP $responseCode")
        }
      }
    }

    try {
      val totalBytes = connection.contentLengthLong
      var downloadedBytes = 0L

      BufferedInputStream(connection.inputStream).use { input ->
        FileOutputStream(destination).use { output ->
          val buffer = ByteArray(8192)
          var bytesRead: Int
          while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
            downloadedBytes += bytesRead
            if (totalBytes > 0) {
              val percent = ((downloadedBytes * 100) / totalBytes).toInt()
              _state.value = State.Downloading(percent.coerceIn(0, 100))
            }
          }
        }
      }

      Log.d(TAG, "Downloaded $downloadedBytes bytes to ${destination.absolutePath}")
    } finally {
      connection.disconnect()
    }
  }

  /**
   * Extracts the bootstrap zip into [targetDir].
   *
   * The zip typically contains `usr/bin/`, `usr/lib/`, etc. at its top level,
   * so extraction to `context.filesDir` produces `context.filesDir/usr/bin/bash`.
   */
  private fun extractBootstrap(zipFile: File, targetDir: File) {
    ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
      var entry = zis.nextEntry
      while (entry != null) {
        val outFile = File(targetDir, entry.name)
        // Guard against zip-slip path traversal attacks.
        val targetCanonical = targetDir.canonicalPath + File.separator
        if (!outFile.canonicalPath.startsWith(targetCanonical)) {
          throw SecurityException("Zip entry outside target dir: ${entry.name}")
        }
        if (entry.isDirectory) {
          outFile.mkdirs()
        } else {
          outFile.parentFile?.mkdirs()
          FileOutputStream(outFile).use { fos ->
            zis.copyTo(fos)
          }
        }
        zis.closeEntry()
        entry = zis.nextEntry
      }
    }
    Log.d(TAG, "Extracted bootstrap to ${targetDir.absolutePath}")
  }

  /**
   * Processes `SYMLINKS.txt` produced by the Termux bootstrap.
   *
   * Each line has the format `link_target←link_name` where both paths are
   * relative to [rootDir]. On Android API 21+ symlinks are created via
   * [android.system.Os.symlink].
   */
  private fun processSymlinks(rootDir: File) {
    val symlinksFile = File(rootDir, "SYMLINKS.txt")
    if (!symlinksFile.exists()) {
      Log.d(TAG, "No SYMLINKS.txt found — skipping symlink creation")
      return
    }

    var created = 0
    var failed = 0

    symlinksFile.readLines().forEach { line ->
      if (line.isBlank()) return@forEach
      val parts = line.split(SYMLINK_SEPARATOR)
      if (parts.size != 2) return@forEach

      val target = parts[0].trim()
      val linkPath = parts[1].trim()
      val linkFile = File(rootDir, linkPath)

      try {
        linkFile.parentFile?.mkdirs()
        // Remove any existing file at the link location.
        if (linkFile.exists()) linkFile.delete()
        Os.symlink(target, linkFile.absolutePath)
        created++
      } catch (e: Exception) {
        Log.w(TAG, "Symlink failed: $linkPath → $target (${e.message})")
        failed++
      }
    }

    // Clean up the SYMLINKS.txt to keep the sysroot tidy.
    symlinksFile.delete()
    Log.d(TAG, "Symlinks: $created created, $failed failed")
  }

  /**
   * Makes all files under `bin/`, `lib/`, and `libexec/` executable (0755).
   *
   * The bootstrap zip extracts to a `usr/`-rooted structure inside `filesDir`.
   * The zip format does not preserve Unix permissions, so every binary and
   * shared library needs an explicit `chmod 0755` after extraction.
   *
   * Also covers flat extraction layouts (`filesDir/bin/` etc.) in case the
   * zip uses a different root structure.
   *
   * Uses [Os.chmod] for world-executable permission setting to avoid Exit 126
   * ("Permission Denied") and dynamic-linker failures at runtime.
   */
  private fun fixPermissions(context: Context) {
    val dirs = listOf(
      // Flat layout (filesDir/bin, filesDir/lib, filesDir/libexec)
      File(context.filesDir, "bin"),
      File(context.filesDir, "lib"),
      File(context.filesDir, "libexec"),
      // usr/-prefixed layout (filesDir/usr/bin, filesDir/usr/lib, filesDir/usr/libexec)
      binDir(context),
      libDir(context),
      File(prefixDir(context), "libexec"),
    )
    var count = 0
    for (dir in dirs) {
      if (!dir.isDirectory) continue
      dir.walkTopDown().filter { it.isFile }.forEach { file ->
        try {
          Os.chmod(file.absolutePath, 0b111_101_101) // 0755
          count++
        } catch (e: Exception) {
          // Fallback to Java API if Os.chmod fails (e.g. on older API levels).
          if (file.setExecutable(true, false)) count++
        }
      }
    }
    Log.d(TAG, "Made $count files executable")
  }
}
