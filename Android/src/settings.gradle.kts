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

pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
  resolutionStrategy {
    eachPlugin {
      if (requested.id.id == "com.google.android.gms.oss-licenses-plugin") {
        useModule("com.google.android.gms:oss-licenses-plugin:0.10.6")
      }
    }
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    //        mavenLocal()
    google()
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
  }
}

rootProject.name = "AI Edge Gallery"

include(":app")

// ── Local Termux engine modules ──────────────────────────────────────────────
// To graft the Termux engine natively, clone the relevant modules from
// https://github.com/termux/termux-app into the libs/ directory:
//   libs/terminal-emulator/   ← termux-app/terminal-emulator
//   libs/termux-shared/       ← termux-app/termux-shared
// Then uncomment the four lines below and comment-out the JitPack dep in
// app/build.gradle.kts.
//
// include(":terminal-emulator")
// project(":terminal-emulator").projectDir = file("libs/terminal-emulator")
// include(":termux-shared")
// project(":termux-shared").projectDir = file("libs/termux-shared")
