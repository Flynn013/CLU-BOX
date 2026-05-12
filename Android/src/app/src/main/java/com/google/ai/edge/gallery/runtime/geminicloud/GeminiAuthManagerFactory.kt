/*
 * Copyright 2026 Flynn013 / CLU-BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.runtime.geminicloud

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Simple Hilt-injected factory that provides a [GeminiAuthManager] with the
 * application [Context] already wired in.
 *
 * Exposing [applicationContext] allows [GeminiAuthViewModel] to build
 * an [net.openid.appauth.AuthorizationService] intent without requiring a
 * direct Activity reference (safe across configuration changes).
 */
@Singleton
class GeminiAuthManagerFactory @Inject constructor(
    @ApplicationContext val applicationContext: Context,
) {
    // Optional: read CLIENT_SECRET from BuildConfig or a secure vault here.
    fun create(): GeminiAuthManager =
        GeminiAuthManager(
            context      = applicationContext,
            clientSecret = null, // Set from BuildConfig.GOOGLE_CLIENT_SECRET if needed.
        )
}
