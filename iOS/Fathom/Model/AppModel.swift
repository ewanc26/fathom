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
    private let store = HistoryStore()
    private let defaults: UserDefaults

    /// Bumped whenever a load starts or the account is dropped, so a read that
    /// has been overtaken cannot write its partial history over a newer one.
    private var loadToken = 0

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
        guard let identity, snapshot == nil else { return }

        // The saved copy paints the dashboard before the network is asked
        // anything; refresh then replaces it page by page. A file for another
        // account, or one that cannot be read, is simply not used.
        if let text = store.read(),
           let cached = FathomCore.shared.decodeHistory(json: text),
           cached.identity == identity {
            snapshot = loader.restore(cached, periodId: periodId)
        }

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

        loadToken += 1
        let token = loadToken

        do {
            let loaded = try await loader.load(
                identity: trimmed,
                periodId: periodId,
                onProgress: { [weak self] partial in
                    guard let self, self.loadToken == token else { return }
                    // The account is real as soon as its first page decodes,
                    // so open the dashboard before the history has finished
                    // arriving: twenty thousand plays is minutes of pages.
                    if self.identity == nil {
                        self.defaults.set(trimmed, forKey: Self.identityKey)
                        self.identity = trimmed
                        self.connecting = false
                    }
                    self.snapshot = partial
                    self.loading = true
                }
            )
            guard loadToken == token else { return }
            defaults.set(trimmed, forKey: Self.identityKey)
            identity = trimmed
            snapshot = loaded
            loading = false
            save(loaded, identity: trimmed)
        } catch is CancellationError {
            // A newer load replaced this one; there is no error to report.
            return
        } catch {
            guard loadToken == token else { return }
            loading = false
            self.error = (error as? FathomError)?.message ?? error.localizedDescription
        }
    }

    func refresh() async {
        guard let identity, !loading else { return }
        loading = true
        error = nil

        loadToken += 1
        let token = loadToken

        do {
            let loaded = try await loader.load(
                identity: identity,
                periodId: periodId,
                onProgress: { [weak self] partial in
                    // Keep the screens on the newest complete picture while
                    // the rest of the history arrives. The loader's first
                    // publish carries the profile and no plays yet, so letting
                    // it through would blank a saved fifty-thousand-play
                    // snapshot the moment the refresh started.
                    guard let self, self.loadToken == token else { return }
                    if let current = self.snapshot, partial.plays.count < current.plays.count {
                        return
                    }
                    self.snapshot = partial
                }
            )
            guard loadToken == token else { return }
            snapshot = loaded
            loading = false
            save(loaded, identity: identity)
        } catch is CancellationError {
            loading = false
        } catch {
            guard loadToken == token else { return }
            loading = false
            self.error = (error as? FathomError)?.message ?? error.localizedDescription
        }
    }

    func selectPeriod(_ id: String) async {
        guard id != periodId else { return }
        periodId = id
        defaults.set(id, forKey: Self.periodKey)

        // The period changes the aggregation, not the records. Re-reading a
        // history that can now run to thousands of plays would make every
        // chip tap cost a minute to answer a question about figures already
        // in memory.
        if let snapshot {
            self.snapshot = loader.restat(snapshot, periodId: id)
        } else {
            await refresh()
        }
    }

    func disconnect() {
        loadToken += 1
        loading = false
        store.clear()
        defaults.removeObject(forKey: Self.identityKey)
        identity = nil
        snapshot = nil
        error = nil
    }

    func dismissError() {
        error = nil
    }

    /// Writes a read for the next launch, complete or not.
    ///
    /// The snapshot's completeness flag is saved with it, so a history that
    /// stopped at the guard or on a failed page reopens as exactly that: the
    /// plays it did read, still labelled partial, with a refresh behind them.
    /// Encoding tens of thousands of plays happens off the main actor;
    /// nothing waits for the file.
    private func save(_ snapshot: DashboardSnapshot, identity: String) {
        let json = FathomCore.shared.encodeHistory(
            identity: identity,
            did: snapshot.did,
            handle: snapshot.handle,
            profile: snapshot.profile,
            status: snapshot.status,
            plays: snapshot.plays,
            skipped: Int32(snapshot.skipped),
            legacy: snapshot.usedLegacyNamespace,
            sampled: snapshot.sampled,
            savedAtEpochSeconds: Int64(Date().timeIntervalSince1970)
        )

        let store = self.store
        Task.detached(priority: .utility) { store.write(json) }
    }
}
