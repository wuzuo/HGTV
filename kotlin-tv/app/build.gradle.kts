import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

val signingProperties =
    Properties().apply {
        val config = rootProject.file("keystore.properties")
        if (config.exists()) config.inputStream().use { load(it) }
    }

fun signingValue(env: String, key: String): String? =
    System.getenv(env) ?: signingProperties.getProperty(key)

android {
    namespace = "com.hgtv.tv"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.wuzuo.hgtv"
        minSdk = 23
        targetSdk = 36
        versionCode = System.getenv("HGTV_VERSION_CODE")?.toInt() ?: 72
        versionName = System.getenv("HGTV_VERSION_NAME") ?: "0.1.31"
        // 真机签名服务地址（可选，填独立签名版手机或运行签名服务的电脑）。
        // 空字符串＝不启用，播放仍走本机签名；电视上也可在「设置 → 签名服务」里改。
        buildConfigField(
            "String",
            "SIGNING_SERVICE",
            "\"${System.getenv("HGTV_SIGNING_SERVICE") ?: ""}\"",
        )
    }
    buildFeatures { buildConfig = true }
    // 弹幕表情图与手机端共用一份：图必须和 core 里的 53 个标签表严格对齐，
    // 两处各存一份迟早会漂。
    sourceSets["main"].assets.srcDir(rootProject.file("assets-shared"))
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty(
                "robolectric.dependency.repo.url",
                "https://repo.maven.apache.org/maven2",
            )
        }
    }
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1") {
        // API 23 tests use legacy graphics, without the cross-platform native renderer.
        exclude(group = "org.robolectric", module = "nativeruntime-dist-compat")
    }
    implementation("androidx.core:core:1.8.0")
    implementation("com.google.zxing:core:3.5.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(project(":core")) { exclude(group = "org.json") }
    // 取流/档位/解码能力/设备身份，与手机端共用。
    implementation(project(":playback"))
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
}
