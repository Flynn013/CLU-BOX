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

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("com.google.protobuf")
    id("com.chaquo.python")
    id("com.google.android.gms.oss-licenses-plugin")
    // FIREBASE PURGED: google-services plugin removed
}

android {
    namespace = "com.google.ai.edge.gallery"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.google.ai.edge.gallery"
        minSdk = 29
        targetSdk = 35 
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    buildFeatures {
        compose = true
        buildConfig = true
    }
    
    // Note: 'packaging {}' is unavailable when Chaquopy is applied (it wraps the Android
    // extension with BaseExtension which exposes the legacy 'packagingOptions' alias).
    @Suppress("Deprecation")
    packagingOptions {
        jniLibs {
            // Force physical extraction of .so files from the APK so that
            // ProcessBuilder can execute libproot.so / libbash.so directly.
            // Without this, AGP leaves them compressed inside the APK zip and
            // nativeLibraryDir paths are unexecutable, causing immediate SIGKILL.
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/gradle/incremental.annotation.processors"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.android.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // material-icons-extended includes all core icons — no need to declare core separately.
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation(libs.androidx.compose.navigation)
    // navigation-runtime-ktx is a transitive dependency of compose.navigation; explicit entry removed.
    
    // CameraX — use catalog-managed versions (1.4.2) rather than hardcoded 1.3.4
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    
    // ML Kit on-device prompt API (used by AICoreModelHelper)
    implementation(libs.mlkit.genai.prompt)
    
    implementation("com.google.ai.edge.litert:litert:1.0.1")
    implementation(libs.litertlm)
    implementation(libs.com.google.code.gson)
    
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    implementation(libs.androidx.work.runtime)

    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("com.google.accompanist:accompanist-systemuicontroller:0.34.0")

    implementation(libs.androidx.datastore)
    implementation(libs.protobuf.javalite)
    
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    // OAuth / OpenID Connect for model download auth
    implementation(libs.openid.appauth)

    // OSS license viewer
    implementation(libs.play.services.oss.licenses)

    // Termux terminal-emulator + terminal-view (JitPack)
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.1")
    implementation("com.github.termux.termux-app:terminal-view:v0.118.1")

    // Moshi — JSON serialisation used by Types.kt and IntentHandler.kt
    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.kotlin.codegen)

    // Halilibo RichText / CommonMark — Markdown rendering used by MarkdownText.kt
    implementation(libs.commonmark)
    implementation(libs.richtext)
    
    // FIREBASE PURGED: BOM, Analytics, Crashlytics, and Messaging dependencies removed
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.26.1"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

// ── Chaquopy — Python 3.11 for on-device execution ──────────────────────────
chaquopy {
    defaultConfig {
        version = "3.11"
        // buildPython resolves automatically from PATH on CI (python3)
        pip {
            // No pip packages required for core OS-level logic.
            // Add packages here as needed, e.g.: install("requests")
        }
    }
    productFlavors { }
}
