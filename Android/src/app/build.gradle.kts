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

        // REQUIRED BY APPAUTH LIBRARY: Tells the browser how to deep-link back to the app
        manifestPlaceholders["appAuthRedirectScheme"] = "com.google.ai.edge.gallery"
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
}

// ---------------------------------------------------------
// THE ACTUAL BYPASS: Android Components Variant API
// ---------------------------------------------------------
androidComponents {
    onVariants { variant ->
        variant.packaging.resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
        variant.packaging.resources.excludes.add("META-INF/gradle/incremental.annotation.processors")
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

    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation(libs.androidx.compose.navigation)
    
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    
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

    implementation(libs.openid.appauth)
    implementation(libs.play.services.oss.licenses)

    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.1")
    implementation("com.github.termux.termux-app:terminal-view:v0.118.1")

    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.kotlin.codegen)

    implementation(libs.commonmark)
    implementation(libs.richtext)
    
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

chaquopy {
    defaultConfig {
        version = "3.11"
        pip { }
    }
    productFlavors { }
}
