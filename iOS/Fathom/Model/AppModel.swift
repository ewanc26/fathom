import Foundation
import FathomShared

/// One fetched dashboard, in the shape the views read.
///
/// The figures inside are Kotlin types: the arithmetic lives in the shared
/// core so that iOS and Android cannot disagree about a play count.
struct DashboardSnapshot {
    let did: String
    let handle: String?
    let profile: TealProfile?
    let status: TealStatus?
    let plays: [Play]
    let stats: ListeningStats
    let sampled: Bool
    let skipped: Int
    let usedLegacyNamespace: Bool
}

/// Everything a screen can ask the dashboard to do.
///
/// There is no sign-in: every teal.fm record is public, and the handle is a
/// lookup key rather than a credential. Nothing secret is stored anywhere, so
/// the Keychain is not involved and the model only keeps the handle.
@MainActor
final class AppModel: ObservableObject {
    @Published private(set) var identity: String?
    @Published private(set) var periodId: String
    @Published private(set) var snapshot: DashboardSnapshot?
    @Published private(set) var loading = false
    @Published private(set) var connecting = false
    @Published var error: String?

    private let loader = DashboardLoader(client: TealClient())
    private let defaults: UserDefaults

    private static let identityKey = "fathom.identity"
    private static let periodKey = "fathom.period"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.identity = defaults.string(forKey: Self.identityKey)
        self.periodId = defaults.string(forKey: Self.periodKey)
            ?? FathomCore.shared.periodIds().first
            ?? "30days"
    }

    func start() async {
        guard identity != nil, snapshot == nil else { return }
        await refresh()
    }

    /// Loads an account and only then remembers it, so a typo cannot be saved.
    func connect(_ raw: String) async {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "@", with: "")
        guard !trimmed.isEmpty else {
            error = "Enter a handle or a DID."
            return
        }

        connecting = true
        error = nil
        defer { connecting = false }

        do {
            let loaded = try await loader.load(identity: trimmed, periodId: periodId)
            defaults.set(trimmed, forKey: Self.identityKey)
            identity = trimmed
            snapshot = loaded
        } catch {
            self.error = (error as? FathomError)?.message ?? error.localizedDescription
        }
    }

    func refresh() async {
        guard let identity, !loading else { return }
        loading = true
        error = nil
        defer { loading = false }

        do {
            snapshot = try await loader.load(identity: identity, periodId: periodId)
        } catch {
            self.error = (error as? FathomError)?.message ?? error.localizedDescription
        }
    }

    func selectPeriod(_ id: String) async {
        guard id != periodId else { return }
        periodId = id
        defaults.set(id, forKey: Self.periodKey)
        await refresh()
    }

    func disconnect() {
        defaults.removeObject(forKey: Self.identityKey)
        identity = nil
        snapshot = nil
        error = nil
    }

    func dismissError() {
        error = nil
    }
}
