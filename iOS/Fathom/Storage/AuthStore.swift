import Foundation
import Security

/// The OAuth credential's one home on iOS: the Keychain.
///
/// The session and DPoP key are serialised by the shared core
/// (`FathomCore.shared.encodeOAuthSession/encodeEs256Key` — the same strings
/// Android keeps), and stored as two generic-password items with
/// ThisDeviceOnly accessibility. It is deliberately not a file, not
/// `UserDefaults`, and not [HistoryStore]'s Application Support directory:
/// a credential does not get the same treatment as a cache of public data.
///
/// Errors are silent-by-design (`try?` semantics via `SecItem` status codes)
/// with one rule: a read that cannot authenticate returns nothing, which
/// callers treat as signed out — never a partial or stale credential.
struct AuthStore: Sendable {
    private let service: String

    init(service: String = "uk.ewancroft.fathom.auth") {
        self.service = service
    }

    /// The stored credential, or nil when nothing readable is stored.
    func load() -> (session: String, dpopKey: String)? {
        guard
            let session = read(item: "session"),
            let key = read(item: "dpop-key")
        else { return nil }
        return (session, key)
    }

    /// Replaces whatever was stored.
    func save(session: String, dpopKey: String) {
        write(item: "session", value: session)
        write(item: "dpop-key", value: dpopKey)
    }

    /// Wipes both items. `invalid_grant`, sign-out and disconnect all end here (#9, #14).
    func clear() {
        delete(item: "session")
        delete(item: "dpop-key")
    }

    // MARK: - Keychain plumbing

    private func baseQuery(item: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: item,
        ]
    }

    private func read(item: String) -> String? {
        var query = baseQuery(item: item)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var data: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &data)
        guard status == errSecSuccess, let bytes = data as? Data else { return nil }
        return String(data: bytes, encoding: .utf8)
    }

    private func write(item: String, value: String) {
        guard let data = value.data(using: .utf8) else { return }
        var attributes = baseQuery(item: item)
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly

        // Update the item if it exists, add it if it does not; the same key
        // must never accumulate two values across a sign-in cycle.
        var status = SecItemUpdate(
            baseQuery(item: item) as CFDictionary,
            [kSecValueData as String: data] as CFDictionary,
        )
        if status == errSecItemNotFound {
            status = SecItemAdd(attributes as CFDictionary, nil)
        }
        _ = status
    }

    private func delete(item: String) {
        SecItemDelete(baseQuery(item: item) as CFDictionary)
    }
}
