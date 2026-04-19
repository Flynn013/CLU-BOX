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

package com.google.ai.edge.gallery.data.brainbox

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import kotlin.math.sqrt

private const val TAG = "VectorEngine"

/**
 * Model file expected in `assets/`. Download a Universal Sentence Encoder Lite
 * `.tflite` and place it at this path to enable semantic vector search.
 *
 * When the model file is absent the engine falls back to a lightweight
 * term-frequency (bag-of-words) embedding that still supports cosine similarity
 * but without deep semantic understanding.
 */
private const val MODEL_ASSET_PATH = "text_embedder.tflite"

/**
 * Dimensionality of the fallback bag-of-words embedding.
 * Chosen to be small enough for fast similarity yet large enough to reduce
 * hash collisions across typical English vocabulary.
 */
private const val BOW_DIMENSIONS = 384

/**
 * VectorEngine — the embedding core for CLU/BOX BrainBox.
 *
 * Provides:
 * - [embed]: Generate a vector embedding for a text string.
 * - [cosineSimilarity]: Compute cosine similarity between two vectors.
 * - [search]: Find the top-K most similar vectors from a candidate set.
 *
 * Uses MediaPipe [TextEmbedder] when a model file is present in assets.
 * Falls back to a deterministic bag-of-words hash embedding otherwise.
 */
class VectorEngine(private val context: Context) {

  /** `true` when MediaPipe TextEmbedder was initialized successfully. */
  var isMediaPipeAvailable: Boolean = false
    private set

  private var textEmbedder: TextEmbedder? = null

  init {
    try {
      // Probe for the model file — if it's not in assets, skip initialization.
      val assetList = context.assets.list("") ?: emptyArray()
      if (MODEL_ASSET_PATH in assetList) {
        val options = TextEmbedder.TextEmbedderOptions.builder()
          .setBaseOptions(
            BaseOptions.builder()
              .setModelAssetPath(MODEL_ASSET_PATH)
              .build()
          )
          .build()
        textEmbedder = TextEmbedder.createFromOptions(context, options)
        isMediaPipeAvailable = true
        Log.d(TAG, "MediaPipe TextEmbedder initialized from $MODEL_ASSET_PATH")
      } else {
        Log.w(TAG, "Model not found at assets/$MODEL_ASSET_PATH — using bag-of-words fallback")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize MediaPipe TextEmbedder — using fallback", e)
      textEmbedder = null
      isMediaPipeAvailable = false
    }
  }

  // ── Public API ──────────────────────────────────────────────────────────

  /**
   * Generates a vector embedding for the given [text].
   *
   * Uses MediaPipe TextEmbedder if available, otherwise falls back to
   * a deterministic bag-of-words hash embedding.
   */
  fun embed(text: String): FloatArray {
    if (text.isBlank()) return FloatArray(BOW_DIMENSIONS)

    val embedder = textEmbedder
    if (embedder != null) {
      return try {
        val result = embedder.embed(text)
        val embedding = result.embeddingResult().embeddings().firstOrNull()
        embedding?.floatEmbedding()?.toFloatArray() ?: bowEmbed(text)
      } catch (e: Exception) {
        Log.e(TAG, "MediaPipe embed failed — falling back to BoW", e)
        bowEmbed(text)
      }
    }
    return bowEmbed(text)
  }

  /**
   * Computes the cosine similarity between two vectors.
   *
   * @return A value in [-1, 1] where 1 = identical direction, 0 = orthogonal, -1 = opposite.
   *         Returns 0 if either vector is zero-length.
   */
  fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    if (a.isEmpty() || b.isEmpty()) return 0f
    val minLen = minOf(a.size, b.size)

    var dot = 0f
    var normA = 0f
    var normB = 0f
    for (i in 0 until minLen) {
      dot += a[i] * b[i]
      normA += a[i] * a[i]
      normB += b[i] * b[i]
    }

    val denominator = sqrt(normA) * sqrt(normB)
    return if (denominator == 0f) 0f else dot / denominator
  }

  /**
   * Searches [candidates] for the top-[topK] most similar vectors to [queryEmbedding].
   *
   * @param queryEmbedding The query vector.
   * @param candidates     List of (neuronId, embedding) pairs.
   * @param topK           Number of results to return (default 3).
   * @return Sorted list of (neuronId, similarity) pairs, highest similarity first.
   */
  fun search(
    queryEmbedding: FloatArray,
    candidates: List<Pair<String, FloatArray>>,
    topK: Int = 3,
  ): List<Pair<String, Float>> {
    return candidates
      .map { (id, emb) -> id to cosineSimilarity(queryEmbedding, emb) }
      .sortedByDescending { it.second }
      .take(topK)
  }

  /**
   * Releases native resources held by the MediaPipe embedder.
   * Call when the engine is no longer needed.
   */
  fun close() {
    try {
      textEmbedder?.close()
    } catch (e: Exception) {
      Log.w(TAG, "Error closing TextEmbedder", e)
    }
    textEmbedder = null
    isMediaPipeAvailable = false
  }

  // ── Bag-of-Words Fallback ───────────────────────────────────────────────

  /**
   * Deterministic bag-of-words embedding using hash bucketing.
   *
   * Tokenizes the text into lowercased words, hashes each into a fixed-size
   * vector, increments the corresponding bucket, then L2-normalizes. This
   * produces stable, reproducible vectors that support basic cosine similarity
   * (captures lexical overlap, not deep semantics).
   */
  private fun bowEmbed(text: String): FloatArray {
    val vec = FloatArray(BOW_DIMENSIONS)
    val tokens = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }
    for (token in tokens) {
      // Use Kotlin's hashCode (stable within a JVM session) mod dimensions.
      // abs() avoids negative indices from Int.MIN_VALUE edge case.
      val bucket = (token.hashCode().toLong().and(0x7FFFFFFF) % BOW_DIMENSIONS).toInt()
      vec[bucket] += 1f
    }
    // L2-normalize so cosine similarity is meaningful.
    val norm = sqrt(vec.sumOf { (it * it).toDouble() }).toFloat()
    if (norm > 0f) {
      for (i in vec.indices) vec[i] /= norm
    }
    return vec
  }
}
