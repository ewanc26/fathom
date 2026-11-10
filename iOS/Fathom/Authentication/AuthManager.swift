import Foundation
import FathomShared

/// The sign-in state machine iOS drives, mirroring Android's `AuthManager`:
/// resolve the identity to its PDS, discover the authorization server, start
/// the flow, claim the callback `FathomApp.onOpenURL` already forwards, and
/// keep or clear the credential in the Keychain through `AuthStore`.
/// Every OAuth rule lives in the shared engine; this type is sequencing and
/// storage only. The authorization attempt is held in memory: a PAR session
/// is short-lived, so process death mid-flow costs a re-sign-in, not a
/// stored half-state.
@MainActor
final class AuthManager: ObservableObject {
    /// The signed-in account's DID, or nil when signed out.
    @Published private(set) var signedInDid: String?

    private let client: TealClient
    private let store: AuthStore
    private let engine: OAuthEngine
    private var attempt: AuthorizationAttempt?

    init(client: TealClient = TealClient(), store: AuthStore = AuthStore()) {
        self.client = client
        self.store = store
        self.engine = FathomCore.shared.oauthEngine(http: OAuthHttpAdapter())
        signedInDid = store.load().flatMap { FathomCore.shared.decodeOAuthSession(text: $0.session) }?.did
    }

    enum SignInOutcome {
        /// The URL to open in the browser; sign-in is now awaiting the callback.
        case browser(String)
        case failed(String)
    }

    /// Resolves `handleOrDid`, discovers its authorization server and pushes
    /// the PAR request. On success the caller opens the URL; no credential
    /// exists before the callback arrives.
    func signIn(handleOrDid: String) async -> SignInOutcome {
        do {
            let did = try await client.resolveIdentity(handleOrDid)
            let pds = try await client.pds(for: did)
            guard let metadata = engine.authorizationServer(pdsUrl: pds) else {
                return .failed("The data server offers no supported sign-in.")
            }
            guard let started = engine.startAuthorization(did: did, pdsUrl: pds, metadata: metadata) else {
                return .failed("The authorization server refused the request.")
            }
            attempt = started
            return .browser(started.authorizeUrl)
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    /// Exchanges a recognised callback and stores the credential. The
    /// callback arrives through `FathomApp.onOpenURL`; claiming it is
    /// one-shot.
    func completePending() {
        guard let callback = FathomCore.shared.takePendingAuthorization(),
              let pending = attempt else { return }
        if let success = engine.completeLogin(attempt: pending, callback: callback) as? LoginResultSuccess {
            let session = success.session
            store.save(
                session: FathomCore.shared.encodeOAuthSession(session: session),
                dpopKey: FathomCore.shared.encodeEs256Key(key: pending.key)
            )
            signedInDid = session.did
        }
        attempt = nil
    }

    /// Revokes the refresh token at the authorization server and wipes the
    /// Keychain either way: revocation is best-effort on the wire, absolute
    /// on the device.
    func signOut() {
        if let stored = store.load(),
           let session = FathomCore.shared.decodeOAuthSession(text: stored.session),
           let key = FathomCore.shared.decodeEs256Key(text: stored.dpopKey) {
            _ = Task.detached {
                self.engine.revoke(session: session, key: key)
            }
        }
        store.clear()
        signedInDid = nil
        attempt = nil
    }
}
