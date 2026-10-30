// SPDX-License-Identifier: GPL-3.0-only
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// The loopback bridge is a standalone Gradle root so the Windows client can build it without
// the old multi-client repository. It still reuses the shared content core from the TV client.
rootProject.name = "bridge"

include(":core")
project(":core").projectDir = file("../kotlin-tv/core")
