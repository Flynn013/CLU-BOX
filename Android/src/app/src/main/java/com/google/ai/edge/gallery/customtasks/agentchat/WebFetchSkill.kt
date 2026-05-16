/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.customtasks.agentchat

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * **WEB_FETCH** — fetches the text content of a URL.
 *
 * Returns the response body (stripped of script/style tags and trimmed to
 * a token-safe limit). Useful for reading documentation, APIs, or web content
 * without needing a browser.
 *
 * This runs on-device with the device's network connection. It is sandboxed
 * to GET requests only — no cookies, no auth, no POST.
 */
class WebFetchSkill : CluSkill {

    override val name: String = "webFetch"

    override val description: String =
        "Fetch the text content of a web URL (GET only). Returns the page text, " +
        "stripped of HTML tags, truncated to 4000 chars for context safety."

    override val jsonSchema: String = """
    {
      "name": "webFetch",
      "description": "Fetch text content from a URL. Returns stripped page text.",
      "parameters": {
        "type": "object",
        "properties": {
          "url": {
            "type": "string",
            "description": "The full URL to fetch (must start with http:// or https://)."
          }
        },
        "required": ["url"]
      }
    }
    """.trimIndent()

    override val fewShotExample: String =
        """webFetch(url="https://example.com") → returns page text content"""

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override suspend fun execute(args: JSONObject): String = withContext(Dispatchers.IO) {
        val url = args.optString("url", "").trim()
        if (url.isBlank()) return@withContext "[WEB_FETCH Error: 'url' argument is required]"
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext "[WEB_FETCH Error: URL must start with http:// or https://]"
        }

        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "CLU-BOX/1.0 (Android)")
                .addHeader("Accept", "text/html,text/plain,application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val statusCode = response.code
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext "[WEB_FETCH Error: HTTP $statusCode for $url]"
            }

            // Strip HTML tags, collapse whitespace, trim to budget
            val stripped = body
                .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(4000)

            "[$statusCode] $url\n\n$stripped"
        } catch (e: Exception) {
            Log.e("WebFetchSkill", "fetch failed for $url", e)
            "[WEB_FETCH Error: ${e.message ?: "network error"}]"
        }
    }
}
