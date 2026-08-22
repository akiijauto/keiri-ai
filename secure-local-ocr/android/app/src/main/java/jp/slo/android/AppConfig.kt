package jp.slo.android

import android.content.Context
import android.content.pm.PackageManager
import java.net.URI

/**
 * 端末ごとの設定と、ビルドの素性の自己申告。
 *
 * 実運用では登録先URLと許可オリジンをMDMで配布する構成を想定している。
 * 検証段階ではそれが用意できないため、デバッグビルドに限って端末上で変更できるようにする。
 * リリースビルドでは組み込みの許可リストから外れたURLを受け付けない。
 */
object AppConfig {

    private const val PREFS = "slo_config"
    private const val KEY_TARGET_URL = "target_url"
    private const val KEY_ALLOW_SCREENSHOTS = "allow_screenshots"

    /** 出荷時の既定値。実運用ではMDM配布の設定で上書きする。 */
    const val DEFAULT_TARGET_URL = "https://form.example.co.jp/registration/"

    /** 組み込みの許可オリジン。リリースビルドではこれ以外へ値を渡さない。 */
    val BUILTIN_ALLOWED_ORIGINS = listOf(
        "https://form.example.co.jp"
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun targetUrl(context: Context): String {
        val stored = prefs(context).getString(KEY_TARGET_URL, null) ?: return DEFAULT_TARGET_URL
        // リリースビルドでは、許可リストから外れた保存値を無視して既定へ戻す。
        if (!BuildConfig.DEBUG && originOf(stored) !in BUILTIN_ALLOWED_ORIGINS) return DEFAULT_TARGET_URL
        return stored
    }

    fun setTargetUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_TARGET_URL, url.trim()).apply()
    }

    /**
     * WebViewへ値を渡してよいオリジン。
     *
     * デバッグビルドに限り、設定した登録先のオリジンを一時的に加える。
     * 検証用の参照実装（社内LANのPCなど）へ繋ぐために必要で、
     * リリースビルドではこの加算を行わない。
     */
    fun allowedOrigins(context: Context): List<String> {
        if (!BuildConfig.DEBUG) return BUILTIN_ALLOWED_ORIGINS
        val configured = originOf(targetUrl(context))
        return if (configured != null && configured !in BUILTIN_ALLOWED_ORIGINS) {
            BUILTIN_ALLOWED_ORIGINS + configured
        } else {
            BUILTIN_ALLOWED_ORIGINS
        }
    }

    fun originOf(url: String): String? = runCatching {
        val u = URI(url)
        val scheme = u.scheme ?: return null
        val host = u.host ?: return null
        val port = if (u.port == -1) "" else ":${u.port}"
        "$scheme://$host$port"
    }.getOrNull()

    /**
     * 検証時のみスクリーンショットを許可する（デバッグビルド限定）。
     *
     * 本来 FLAG_SECURE は常時有効にすべきものだが、
     * 実機検証の結果を記録・報告する手段が無いと検証そのものが回らない。
     * リリースビルドではこの設定を読まず、常に抑止する。
     */
    fun allowScreenshots(context: Context): Boolean {
        if (!BuildConfig.DEBUG) return false
        return prefs(context).getBoolean(KEY_ALLOW_SCREENSHOTS, false)
    }

    fun setAllowScreenshots(context: Context, allow: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOW_SCREENSHOTS, allow).apply()
    }

    /**
     * このビルドが通信権限を持っているかを実行時に自己確認する。
     *
     * ビルド時の検査（:app:verifyOfflineFlavorHasNoInternet）に加えて、
     * 利用者が端末上でも確かめられるようにするための表示用。
     * 「持っていない」と表示されるなら、OCR中に通信できないことがOSによって保証されている。
     */
    fun hasInternetPermission(context: Context): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS
        )
        info.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true
    }.getOrDefault(false)

    /** 端末上の検証画面に出す、このビルドの素性。 */
    fun describe(context: Context): List<Pair<String, String>> = listOf(
        "ビルド" to "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})",
        "フレーバー" to BuildConfig.FLAVOR,
        "パッケージ" to context.packageName,
        "INTERNET権限" to if (hasInternetPermission(context)) "保持している" else "保持していない",
        "Web入力支援" to if (BuildConfig.WEB_HANDOFF_ENABLED) "有効" else "無効（表示とコピーのみ）",
        "登録先URL" to targetUrl(context),
        "許可オリジン" to allowedOrigins(context).joinToString(" / ")
    )
}
