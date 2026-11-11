// SPDX-License-Identifier: GPL-3.0-only
// 手机端：竖屏触屏形态。与 TV 端（:app）共用 :core 协议层和 :playback 播放层，
// 界面完全独立——TV 那套遥控焦点/大屏布局在触摸屏上没有一处能复用。

import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingProperties =
    Properties().apply {
        val config = rootProject.file("keystore.properties")
        if (config.exists()) config.inputStream().use { load(it) }
    }

fun signingValue(env: String, key: String): String? =
    System.getenv(env) ?: signingProperties.getProperty(key)

android {
    namespace = "com.hgtv.phone"
    compileSdk = 36

    defaultConfig {
        // 独立包名：TV 端要保持 io.github.wuzuo.hgtv 不变才能覆盖安装升级，
        // 手机端另起一个，两个 App 可以同时装在同一个手机上。
        applicationId = "io.github.wuzuo.hgtv.phone"
        // Compose 与 media3 1.11 都要求 21+，这里取 24：避开 21-23 上的
        // VectorDrawable/desugaring 边角问题，实际在用机型也都远高于此。
        minSdk = 24
        targetSdk = 36
        // 发版时由 CI 用标签与 run_number 覆盖；本地默认值只决定 debug 包的版本号。
        versionCode = System.getenv("HGTV_PHONE_VERSION_CODE")?.toInt() ?: 8
        versionName = System.getenv("HGTV_PHONE_VERSION_NAME") ?: "0.1.6"
    }

    buildFeatures { compose = true }
    // 与 TV 端共用同一套弹幕表情图（见 :app 的同名配置）。
    sourceSets["main"].assets.srcDir(rootProject.file("assets-shared"))

    signingConfigs {
        create("delivery") {
            storeFile = signingValue("HGTV_KEYSTORE", "storeFile")?.let { file(it) }
            storePassword = signingValue("HGTV_STORE_PASSWORD", "storePassword")
            keyAlias = signingValue("HGTV_KEY_ALIAS", "keyAlias")
            keyPassword = signingValue("HGTV_KEY_PASSWORD", "keyPassword")
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("delivery")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }

    packaging { resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module") }
}

dependencies {
    implementation(project(":core")) { exclude(group = "org.json") }
    implementation(project(":playback"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // 封面用的是 CDN 的 jpg/webp，Coil 自带内存+磁盘二级缓存，比手写 AsyncImage 省事。
    implementation("io.coil-kt:coil-compose:2.7.0")

    // PlayerView 复用 media3-ui；播放内核与 DataSource 走 :playback。
    implementation("androidx.media3:media3-ui:1.11.1")
    // 后台播放：界面用 MediaController 连 MediaSessionService，播放器活在服务里。
    implementation("androidx.media3:media3-session:1.11.1")
    // 播放控制层用到的图标（播放/暂停/清晰度/弹幕/画中画等）。
    implementation("androidx.compose.material:material-icons-extended")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    testImplementation("junit:junit:4.13.2")
}
