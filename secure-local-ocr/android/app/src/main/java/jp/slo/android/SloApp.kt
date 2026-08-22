package jp.slo.android

import android.app.Application
import android.os.StrictMode

/**
 * アプリ本体の初期化。
 *
 * 意図的に「何もしない」ことが重要な場所でもある。
 * 解析SDK・広告SDK・クラッシュレポータ・外部ログ収集は一切組み込まない（企画書 5.1, 15）。
 */
class SloApp : Application() {

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            // 開発中に意図しないディスク書き込み・通信を検出する。
            // 「保存しない設計」から外れた実装が入り込んだらここで気づける。
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build()
            )
        }
    }
}
