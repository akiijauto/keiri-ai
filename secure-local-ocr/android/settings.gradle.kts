pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "secure-local-ocr-android"

// :core は Android SDK を必要としない純粋な Kotlin/JVM モジュール。
// 業務ロジック（正規化・抽出・Envelope）はすべてここに置き、CI で単体テストを回す。
include(":core")

// :app は Android SDK がある環境でのみ構成する。
// SDK 無しの環境（CI のロジックテストなど）で `:core:test` を実行できるようにするための分岐。
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }

if (hasAndroidSdk) {
    include(":app")
} else {
    logger.lifecycle("Android SDK が見つからないため :app を構成から除外しました（:core のみ）。")
}
