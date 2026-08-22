# ML Kit のモデル読み込みに必要なクラスを保持する
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }

# WebView から呼ばれる JavaScript インターフェース
-keepclassmembers class jp.slo.android.handoff.SloWebViewBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# 共有コアのデータクラスはリフレクションを使わないため縮小してよい
