import Foundation

/// The saved history, in Application Support rather than Caches.
///
/// A completed read of a large repo is tens of thousands of plays and several
/// megabytes, which is why it is a file and not `UserDefaults`. It lives in
/// Application Support because the point is to survive until the next launch;
/// the Caches directory is exactly what the system is entitled to delete when
/// it wants the space, which would make a cold open as slow as the first one.
///
/// The JSON is written and read by the shared core, so the format is identical
/// to Android's and this type only knows where the bytes live.
///
/// The directory is excluded from device backups and the file is written with
/// complete file protection. It is a cache that can be rebuilt, not a second
/// copy of the person's history that outlives the app.
struct HistoryStore: Sendable {
    private let fileURL: URL

    init(fileName: String = "history.json") {
        let base = (try? FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )) ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)

        var directory = base.appendingPathComponent("Fathom", isDirectory: true)
        try? FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        // Exclude the directory rather than the file: the atomic write
        // replaces the file, but the directory keeps its resource values.
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? directory.setResourceValues(values)
        self.fileURL = directory.appendingPathComponent(fileName)
    }

    /// The saved JSON, or nil when nothing has been saved or the file is unreadable.
    func read() -> String? {
        guard let data = try? Data(contentsOf: fileURL) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// Atomic on Apple's filesystems, so a kill mid-write leaves the old copy.
    func write(_ text: String) {
        guard let data = text.data(using: .utf8) else { return }
        try? data.write(to: fileURL, options: [.atomic, .completeFileProtection])
    }

    func clear() {
        try? FileManager.default.removeItem(at: fileURL)
    }
}
