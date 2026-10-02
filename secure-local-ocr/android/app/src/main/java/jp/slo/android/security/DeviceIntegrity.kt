package jp.slo.android.security

import android.os.Build
import java.io.File

/**
 * Root化端末の簡易検出（企画書 15）。
 *
 * これは万能ではない。回避手段はいくらでもあるので、
 * 「業務専用端末をMDMで管理する」（企画書 16）ことが本命の対策であり、
 * ここは事故防止のための最後の一枚に過ぎない、という位置づけ。
 */
object DeviceIntegrity {

    private val SUSPICIOUS_PATHS = listOf(
        "/system/app/Superuser.apk",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/su",
        "/su/bin/su",
        "/magisk"
    )

    data class Result(val rooted: Boolean, val testKeys: Boolean, val emulator: Boolean) {
        val trustworthy: Boolean get() = !rooted && !testKeys
    }

    fun check(): Result {
        val rooted = SUSPICIOUS_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) }
        val testKeys = Build.TAGS?.contains("test-keys") == true
        val emulator = Build.FINGERPRINT.startsWith("generic") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK built for")
        return Result(rooted, testKeys, emulator)
    }
}
