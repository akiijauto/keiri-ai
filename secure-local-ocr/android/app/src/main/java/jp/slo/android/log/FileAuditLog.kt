package jp.slo.android.log

import android.content.Context
import jp.slo.core.AuditLog
import jp.slo.core.Rfc3339
import java.io.File

/**
 * 端末内の監査ログ（企画書 13 / MVP機能9）。
 *
 * 保存先はアプリ専用領域のみ。クラウドバックアップからは除外済み。
 * 書き込む内容は jp.slo.core.AuditLog が検査しており、
 * 個人情報らしき値を渡した場合は例外になって記録されない（INV-5）。
 */
class FileAuditLog(context: Context, private val clock: () -> Long = { System.currentTimeMillis() }) {

    private val file: File = File(context.filesDir, "audit/operations.log").also {
        it.parentFile?.mkdirs()
    }

    private val listeners = mutableListOf<(AuditLog.Entry) -> Unit>()

    @Synchronized
    fun add(event: AuditLog.Event, attributes: Map<String, String> = emptyMap()): AuditLog.Entry? {
        val entry = try {
            AuditLog.entry(Rfc3339.format(clock() / 1000), event, attributes)
        } catch (e: AuditLog.PiiInLogException) {
            // ログに個人情報を書こうとした実装ミスは、握りつぶさずログ自体を残さない。
            // 事象があったことだけを記録する。
            AuditLog.entry(Rfc3339.format(clock() / 1000), event, mapOf("result" to "log_rejected"))
        }
        runCatching { file.appendText(entry.format() + "\n") }
        listeners.forEach { it(entry) }
        return entry
    }

    fun onEntry(listener: (AuditLog.Entry) -> Unit) {
        listeners.add(listener)
    }

    fun tail(lines: Int = 50): List<String> =
        runCatching { file.readLines().takeLast(lines) }.getOrDefault(emptyList())

    fun clear() {
        runCatching { file.writeText("") }
    }
}
