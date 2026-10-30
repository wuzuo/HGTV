// SPDX-License-Identifier: GPL-3.0-only
// Loopback bridge root project: the Windows client spawns this jar and talks to it over
// 127.0.0.1, so it is packaged next to the Flutter build by scripts/build-windows-flutter.ps1.
plugins {
    kotlin("jvm") version "2.1.20"
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    testImplementation("junit:junit:4.13.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
}

application { mainClass.set("com.hgtv.bridge.MainKt") }
