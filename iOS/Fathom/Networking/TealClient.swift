import Foundation
import FathomShared

struct FathomError: LocalizedError {
    let message: String
    /// True only for absence: a 404, or a 400 from `getRecord`.
    var absent = false
    var errorDescription: String? { message }
}

/// Every HTTP call the iOS app makes.
///
/// Three services, kept distinct because they are not interchangeable:
/// the public Bluesky AppView for handle resolution, the PLC directory (or a
/// `did:web` host) for DID → PDS, and the account's own PDS for the records.
///
/// All reads are unauthenticated. Fathom has no tokens, no DPoP key and no
/// secure storage because it never writes and never sees anything private.
struct TealClient {
    private let session: URLSession
    private let publicAppView = "https://public.api.bsky.app"
    private let plcDirectory = "https://plc.directory"

    init(session: URLSession = .shared) {
        self.session = session
    }

    func resolveIdentity(_ raw: String) async throws -> String {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "@", with: "")
        guard !text.isEmpty else { throw FathomError(message: "Enter a handle or a DID.") }
        if text.hasPrefix("did:") { return text }

        let encoded = FathomCore.shared.encode(value: text)
        let url = "\(publicAppView)\(FathomCore.shared.resolveHandlePath())?handle=\(encoded)"
        let body = try await get(url, notFound: "\(text) is not an account on the network.")
        guard let did = FathomCore.shared.did(json: body) else {
            throw FathomError(message: "\(text) did not resolve to an account.")
        }
        return did
    }

    func pds(for did: String) async throws -> String {
        let documentURL: String
        if did.hasPrefix("did:plc:") {
            documentURL = "\(plcDirectory)/\(did)"
        } else if did.hasPrefix("did:web:") {
            let host = did
                .replacingOccurrences(of: "did:web:", with: "")
                .replacingOccurrences(of: "%3A", with: ":")
                .replacingOccurrences(of: ":", with: "/")
            documentURL = "https://\(host)/.well-known/did.json"
        } else {
            throw FathomError(message: "Fathom cannot look up \(did).")
        }

        let body = try await get(documentURL, notFound: "No identity document for this account.")
        guard let endpoint = FathomCore.shared.pdsEndpoint(json: body) else {
            throw FathomError(message: "This account does not publish a data server.")
        }
        return endpoint
    }

    func listRecords(
        pds: String,
        repo: String,
        collection: String,
        cursor: String?,
        limit: Int
    ) async throws -> String {
        let query = FathomCore.shared.listRecordsQuery(
            repo: repo,
            collection: collection,
            cursor: cursor,
            limit: Int32(limit)
        )
        return try await get(
            pds + FathomCore.shared.listRecordsPath() + "?" + query,
            notFound: "This account has no \(collection) records."
        )
    }

    /// A record at a known key, or nil when it does not exist.
    ///
    /// A missing `literal:self` profile or status is normal rather than an
    /// error, so a 400/404 is reported as absence. This is the one read where
    /// a 400 is treated as an answer; a listing's 400 means a rejected page.
    func getRecord(
        pds: String,
        repo: String,
        collection: String,
        rkey: String
    ) async throws -> String? {
        let query = FathomCore.shared.getRecordQuery(repo: repo, collection: collection, rkey: rkey)
        do {
            return try await get(
                pds + FathomCore.shared.getRecordPath() + "?" + query,
                notFound: nil,
                badRequestIsAbsence: true
            )
        } catch let failure as FathomError where failure.absent {
            return nil
        }
    }

    /// One GET, with bounded retries for transient failures.
    ///
    /// A 429 or 5xx is answered with up to three more attempts, waiting for
    /// `Retry-After` when the server supplies one. A 400 or 404 is never
    /// retried: the first means the request was rejected, and the second is an
    /// answer. `badRequestIsAbsence` is only true for `getRecord`.
    private func get(
        _ urlString: String,
        notFound: String?,
        badRequestIsAbsence: Bool = false
    ) async throws -> String {
        guard let url = URL(string: urlString) else {
            throw FathomError(message: "That address could not be built.")
        }

        var request = URLRequest(url: url)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.timeoutInterval = 20

        let maxAttempts = Int(FathomCore.shared.retryMaxAttempts())
        var attempt = 0

        while true {
            let data: Data
            let response: URLResponse
            do {
                (data, response) = try await session.data(for: request)
            } catch is CancellationError {
                throw CancellationError()
            } catch let failure as URLError where failure.code == .cancelled {
                throw CancellationError()
            } catch {
                if attempt + 1 < maxAttempts {
                    let delayMillis = FathomCore.shared.retryDelayMillis(
                        attempt: Int32(attempt),
                        retryAfterHeader: nil,
                        nowEpochSeconds: Int64(Date().timeIntervalSince1970)
                    )
                    try await Task.sleep(for: .milliseconds(delayMillis))
                    attempt += 1
                    continue
                }
                throw FathomError(message: "Could not reach the network. \(error.localizedDescription)")
            }

            let body = String(data: data, encoding: .utf8) ?? ""
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            let retryAfter = (response as? HTTPURLResponse)?
                .value(forHTTPHeaderField: "Retry-After")

            if (200..<300).contains(status) { return body }

            if FathomCore.shared.isRetryableStatus(status: Int32(status)),
               attempt + 1 < maxAttempts {
                let delayMillis = FathomCore.shared.retryDelayMillis(
                    attempt: Int32(attempt),
                    retryAfterHeader: retryAfter,
                    nowEpochSeconds: Int64(Date().timeIntervalSince1970)
                )
                try await Task.sleep(for: .milliseconds(delayMillis))
                attempt += 1
                continue
            }

            let detail = FathomCore.shared.errorDetail(json: body)

            if status == 404 || (status == 400 && badRequestIsAbsence) {
                throw FathomError(
                    message: notFound ?? detail ?? "That record does not exist.",
                    absent: true
                )
            }
            if status == 400 {
                throw FathomError(
                    message: detail ?? "The server rejected the request."
                )
            }

            throw FathomError(
                message: detail ?? "The server answered \(status). Try again in a moment."
            )
        }
    }
}
