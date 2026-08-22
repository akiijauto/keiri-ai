plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

android {
    namespace = "jp.slo.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "jp.slo.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    /**
     * ネットワーク分離（企画書 6 / 原則4）をビルド構成で強制する。
     *
     *  - offline: INTERNET 権限を宣言しない。OSがソケットを開かせないため、
     *             「OCR中に通信していない」ことがコードレビューではなく仕組みで保証される。
     *             引き渡しは画面表示とクリップボード（Transport T1/T2）。
     *  - web:     INTERNET 権限あり。WebViewでの引き渡し（T3）を行う。
     *             通信先はネットワークセキュリティ設定の許可リストに限定する。
     */
    flavorDimensions += "network"
    productFlavors {
        create("offline") {
            dimension = "network"
            applicationIdSuffix = ".offline"
            versionNameSuffix = "-offline"
            buildConfigField("boolean", "WEB_HANDOFF_ENABLED", "false")
        }
        create("web") {
            dimension = "network"
            buildConfigField("boolean", "WEB_HANDOFF_ENABLED", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // デバッグビルドでもログに個人情報を出さない方針は変わらない
            isMinifyEnabled = false
        }
    }

    /**
     * ABIごとにAPKを分割する。
     *
     * ML Kit の同梱モデルとネイティブライブラリが4ABI分入ると50MBを超え、
     * 検証用に配布する手段（メール・チャット・MDM）で扱いにくい。
     * 実機は arm64-v8a がほとんどなので、必要なものだけを配る。
     * universalApk も残し、ABIが不明な端末はそちらを使えるようにする。
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 端末内カメラ（写真アプリを経由しないOCR専用カメラ）
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // 完全オンデバイスOCR。モデルをAPKへ同梱する版を使い、モデル取得のための通信も発生させない。
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")

    implementation("androidx.biometric:biometric:1.1.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

/**
 * 「OCRフェーズで通信しない」を、レビューではなく機械で検証する（企画書 20 / 原則8）。
 *
 * offline フレーバーのAPKに INTERNET 権限が含まれていたらビルドを失敗させる。
 * 依存ライブラリのマニフェストが権限を持ち込むことは実際に起きるため、
 * 毎回のビルドで確認する価値がある。
 */
val verifyOfflineFlavorHasNoInternet by tasks.registering {
    group = "verification"
    description = "offline フレーバーのAPKが INTERNET 権限を含まないことを検証する"

    // 検証対象は「実際にパッケージされたAPK」。必ず先にビルドさせる。
    dependsOn("assembleOfflineDebug")

    val apkDir = layout.buildDirectory.dir("outputs/apk/offline")
    val aapt2 = providers.provider {
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        val buildToolsDir = File(sdk, "build-tools")
        buildToolsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.maxByOrNull { it.name }
            ?.let { File(it, "aapt2") }
    }

    doLast {
        val tool = aapt2.orNull ?: error("aapt2 が見つかりません。ANDROID_HOME を設定してください。")
        val apks = apkDir.get().asFile.walkTopDown().filter { it.extension == "apk" }.toList()
        if (apks.isEmpty()) error("offline フレーバーのAPKが見つかりません。先に assembleOfflineDebug を実行してください。")

        for (apk in apks) {
            val output = providers.exec {
                commandLine(tool.absolutePath, "dump", "permissions", apk.absolutePath)
            }.standardOutput.asText.get()

            val forbidden = listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE")
                .filter { output.contains(it) }

            if (forbidden.isNotEmpty()) {
                error(
                    "offline フレーバーに通信権限が含まれています: ${forbidden.joinToString()}\n" +
                            "  APK: ${apk.name}\n" +
                            "  依存ライブラリのマニフェストから混入した可能性があります。" +
                            "src/offline/AndroidManifest.xml で tools:node=\"remove\" を確認してください。"
                )
            }
            logger.lifecycle("OK: ${apk.name} は INTERNET 権限を持ちません")
        }
    }
}
