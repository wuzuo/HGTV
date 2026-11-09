// SPDX-License-Identifier: GPL-3.0-only
// 播放链路里与界面无关的那一层：取流、档位筛选、解码能力探测、设备身份读取。
// TV 端（:app）和手机端（:app-phone）共用，模块本身不含任何 Activity/View 依赖。

plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.hgtv.playback"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // org.json 必须排除：Android 平台自带同名类，打进包里会和框架类冲突。
    api(project(":core")) { exclude(group = "org.json") }
    // api 而非 implementation：VideoDataSource 继承 media3 的 BaseDataSource，
    // 两个 app 模块构造它时都要能看见 media3 的类型。
    api("androidx.media3:media3-exoplayer:1.11.1")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    testImplementation("junit:junit:4.13.2")
}
