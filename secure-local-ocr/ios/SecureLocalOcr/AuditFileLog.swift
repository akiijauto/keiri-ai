import Foundation
import SloCore

/// 端末内の監査ログ（企画書 13）。
///
/// 保存先はアプリ専用領域のみ。iCloudバックアップからは除外する。
/// 書き込む内容は SloAuditLog が検査しており、個人情報らしき値は記録されない（INV-5）。
final class AuditFileLog {

    private let url: URL
    private let queue = DispatchQueue(label: "jp.slo.audit")
    private(set) var recent: [String] = []

    init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("audit", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        url = dir.appendingPathComponent("operations.log")

        // iCloud等へ同期させない（企画書 15「クラウドバックアップ禁止」）
        var resourceValues = URLResourceValues()
        resourceValues.isExcludedFromBackup = true
        var mutableDir = dir
        try? mutableDir.setResourceValues(resourceValues)
    }

    @discardableResult
    func add(_ event: SloAuditEvent, _ attributes: [String: String] = [:]) -> String {
        let timestamp = SloRfc3339.format(Int(Date().timeIntervalSince1970))
        let entry: SloAuditEntry
        do {
            entry = try SloAuditLog.entry(timestamp: timestamp, event: event, attributes: attributes)
        } catch {
            // ログに個人情報を書こうとした実装ミスは、握りつぶさず内容を落として記録する。
            entry = (try? SloAuditLog.entry(timestamp: timestamp, event: event,
                                            attributes: ["result": "log_rejected"]))
                ?? SloAuditEntry(timestamp: timestamp, event: event, attributes: [:])
        }
        let line = entry.format()
        queue.async { [url] in
            if let data = (line + "\n").data(using: .utf8) {
                if let handle = try? FileHandle(forWritingTo: url) {
                    handle.seekToEndOfFile()
                    handle.write(data)
                    try? handle.close()
                } else {
                    try? data.write(to: url)
                }
            }
        }
        recent.append(line)
        if recent.count > 200 { recent.removeFirst() }
        return line
    }
}
