/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.data.git

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

private const val TAG = "JGitManager"

/**
 * Pure-native Git engine for the CLU/BOX Cognitive OS.
 *
 * Wraps Eclipse JGit to give the agent the capability to:
 *
 * 1. Clone its **own** repository (`Flynn013/CLU-BOX`) into the on-device
 *    workspace so the agent can read its source tree.
 * 2. Branch and edit Kotlin source files using [BusyBoxBridge]/
 *    [com.google.ai.edge.gallery.data.python.PythonBridge].
 * 3. Stage, commit and push the result back to the remote — closing the
 *    self-modifying loop without ever shelling out to a system `git` binary.
 *
 * No external `git` is required; JGit is a pure-Java implementation that runs
 * on Android API 29+ without modification.
 *
 * ## Workspace layout
 * ```
 * ${filesDir}/clu_workspaces/<slug>/   ← git working tree
 * ${filesDir}/clu_workspaces/<slug>/.git/
 * ```
 *
 * ## Authentication
 * For HTTPS pushes the caller supplies a personal-access token via
 * [Credentials]. The token is **not** persisted by JGitManager — callers are
 * expected to source it from `androidx.security.crypto`'s encrypted vault.
 *
 * ## Threading
 * Every public method is `suspend` and dispatched to [Dispatchers.IO]. The
 * underlying JGit instances are **not** thread-safe — callers should serialise
 * concurrent operations on the same workspace via a higher-level mutex.
 */
object JGitManager {

  /** Subdirectory under `filesDir` that hosts every workspace. */
  private const val ROOT_DIR = "clu_workspaces"

  /** Optional credentials passed to authenticated remotes (HTTPS only). */
  data class Credentials(val username: String, val token: String)

  /** Outcome of a single Git operation. */
  sealed class Result {
    data class Ok(val message: String) : Result()
    data class Err(val message: String, val cause: Throwable? = null) : Result()
  }

  // ─────────────────────────────────────────────────────────────────────────
  //  Workspace helpers
  // ─────────────────────────────────────────────────────────────────────────

  /** Resolve the on-disk directory for a named workspace (creates parents). */
  fun workspaceDir(context: Context, slug: String): File {
    val safe = slug.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "default" }
    return File(File(context.filesDir, ROOT_DIR), safe).apply {
      parentFile?.mkdirs()
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  //  Public commands
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Clone [remoteUrl] into [workspaceDir(context, slug)] and check out [branch].
   *
   * If the workspace already contains a `.git` directory the call short-circuits
   * with a [Result.Ok] so the operation is idempotent.
   */
  suspend fun clone(
    context: Context,
    slug: String,
    remoteUrl: String,
    branch: String = "main",
    credentials: Credentials? = null,
  ): Result = withContext(Dispatchers.IO) {
    val dir = workspaceDir(context, slug)
    val gitDir = File(dir, ".git")
    if (gitDir.isDirectory) {
      return@withContext Result.Ok("Workspace '$slug' already cloned at ${dir.absolutePath}")
    }
    runCatching {
      Git.cloneRepository()
        .setURI(remoteUrl)
        .setDirectory(dir)
        .setBranch(branch)
        .apply { credentials?.let { setCredentialsProvider(UsernamePasswordCredentialsProvider(it.username, it.token)) } }
        .call()
        .close()
    }.fold(
      onSuccess = {
        Log.d(TAG, "Cloned $remoteUrl @ $branch -> ${dir.absolutePath}")
        Result.Ok("Cloned $remoteUrl ($branch) -> ${dir.absolutePath}")
      },
      onFailure = { e ->
        Log.e(TAG, "clone failed", e)
        Result.Err("clone failed: ${e.message ?: e.javaClass.simpleName}", e)
      },
    )
  }

  /** Pull (fast-forward where possible) the given branch from `origin`. */
  suspend fun pull(
    context: Context,
    slug: String,
    branch: String = "main",
    credentials: Credentials? = null,
  ): Result = withContext(Dispatchers.IO) {
    runCatching {
      open(context, slug).use { git ->
        git.pull()
          .setRemote("origin")
          .setRemoteBranchName(branch)
          .apply { credentials?.let { setCredentialsProvider(UsernamePasswordCredentialsProvider(it.username, it.token)) } }
          .call()
      }
    }.fold(
      onSuccess = { res -> Result.Ok("pull $branch: successful=${res.isSuccessful}") },
      onFailure = { e ->
        Log.e(TAG, "pull failed", e)
        Result.Err("pull failed: ${e.message ?: e.javaClass.simpleName}", e)
      },
    )
  }

  /** Create [branch] from HEAD (or [startPoint] if provided) and check it out. */
  suspend fun branch(
    context: Context,
    slug: String,
    branch: String,
    startPoint: String? = null,
  ): Result = withContext(Dispatchers.IO) {
    runCatching {
      open(context, slug).use { git ->
        git.checkout()
          .setCreateBranch(true)
          .setName(branch)
          .apply { startPoint?.let { setStartPoint(it) } }
          .call()
      }
    }.fold(
      onSuccess = { Result.Ok("checked out new branch '$branch'") },
      onFailure = { e ->
        Log.e(TAG, "branch failed", e)
        Result.Err("branch failed: ${e.message ?: e.javaClass.simpleName}", e)
      },
    )
  }

  /**
   * `git add <patterns> && git commit -m <message>` in one call.
   *
   * @param patterns paths relative to the workspace root. Pass `[""]` (or `[
   *                 "."]`) to stage everything.
   */
  suspend fun commit(
    context: Context,
    slug: String,
    patterns: List<String>,
    message: String,
    author: PersonIdent = PersonIdent("CLU-Agent", "agent@clubox.local"),
  ): Result = withContext(Dispatchers.IO) {
    runCatching {
      open(context, slug).use { git ->
        val add = git.add()
        patterns.forEach { add.addFilepattern(it.ifEmpty { "." }) }
        add.call()
        git.commit()
          .setAuthor(author)
          .setCommitter(author)
          .setMessage(message)
          .call()
          .name
      }
    }.fold(
      onSuccess = { sha -> Result.Ok("committed $sha — $message") },
      onFailure = { e ->
        Log.e(TAG, "commit failed", e)
        Result.Err("commit failed: ${e.message ?: e.javaClass.simpleName}", e)
      },
    )
  }

  /** Push [branch] to `origin`. Requires HTTPS credentials. */
  suspend fun push(
    context: Context,
    slug: String,
    branch: String = "main",
    credentials: Credentials,
    force: Boolean = false,
  ): Result = withContext(Dispatchers.IO) {
    runCatching {
      open(context, slug).use { git ->
        val refSpec = RefSpec("refs/heads/$branch:refs/heads/$branch")
        val results = git.push()
          .setRemote("origin")
          .setRefSpecs(refSpec)
          .setForce(force)
          .setCredentialsProvider(UsernamePasswordCredentialsProvider(credentials.username, credentials.token))
          .call()
        results.joinToString { it.uri.toString() }
      }
    }.fold(
      onSuccess = { uris -> Result.Ok("pushed $branch -> $uris") },
      onFailure = { e ->
        Log.e(TAG, "push failed", e)
        Result.Err("push failed: ${e.message ?: e.javaClass.simpleName}", e)
      },
    )
  }

  /** Hard-reset the workspace to [ref] (defaults to `HEAD`). Use with care. */
  suspend fun reset(
    context: Context,
    slug: String,
    ref: String = "HEAD",
  ): Result = withContext(Dispatchers.IO) {
    runCatching {
      open(context, slug).use { git ->
        git.reset()
          .setMode(ResetCommand.ResetType.HARD)
          .setRef(ref)
          .call()
      }
    }.fold(
      onSuccess = { Result.Ok("hard-reset to $ref") },
      onFailure = { e ->
        Log.e(TAG, "reset failed", e)
        Result.Err("reset failed: ${e.message ?: e.javaClass.simpleName}", e)
      },
    )
  }

  /** Returns a short status summary (porcelain-ish). */
  suspend fun status(context: Context, slug: String): Result = withContext(Dispatchers.IO) {
    runCatching {
      open(context, slug).use { git ->
        val s = git.status().call()
        buildString {
          append("branch=${git.repository.branch} ")
          append("clean=${s.isClean} ")
          append("added=${s.added.size} ")
          append("changed=${s.changed.size} ")
          append("modified=${s.modified.size} ")
          append("untracked=${s.untracked.size}")
        }
      }
    }.fold(
      onSuccess = { Result.Ok(it) },
      onFailure = { e -> Result.Err("status failed: ${e.message ?: e.javaClass.simpleName}", e) },
    )
  }

  // ─────────────────────────────────────────────────────────────────────────
  //  Internals
  // ─────────────────────────────────────────────────────────────────────────

  private fun open(context: Context, slug: String): Git {
    val dir = workspaceDir(context, slug)
    require(File(dir, ".git").isDirectory) { "Workspace '$slug' is not a git repository — run clone() first." }
    return Git.open(dir)
  }
}
