import Foundation
import FathomShared

struct FathomError: LocalizedError {
    let message: String
    /// True when the server answered 400 or 404: absence rather than failure.
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
            throw FathomError(message: "Fathom cannot look up this identity.")
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
    /// error, so a 400/404 is reported as absence.
    func getRecord(
        pds: String,
        repo: String,
        collection: String,
        rkey: String
    ) async throws -> String? {
        let query = FathomCore.shared.getRecordQuery(repo: repo, collection: collection, rkey: rkey)
        do {
            return try await get(pds + FathomCore.shared.getRecordPath() + "?" + query, notFound: nil)
        } catch let failure as FathomError where failure.absent {
            return nil
        }
    }

    private func get(_ urlString: String, notFound: String?) async throws -> String {
        guard let url = URL(string: urlString) else {
            throw FathomError(message: "That address could not be built.")
        }

        var request = URLRequest(url: url)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.timeoutInterval = 20

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw FathomError(message: "Could not reach the network. \(error.localizedDescription)")
        }

        let body = String(data: data, encoding: .utf8) ?? ""
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0

        if (200..<300).contains(status) { return body }

        let detail = FathomCore.shared.errorDetail(json: body)

        if status == 400 || status == 404 {
            throw FathomError(
                message: notFound ?? "That record does not exist.",
                absent: true
            )
        }

        throw FathomError(
            message: detail ?? "The server answered \(status). Try again in a moment."
        )
    }
}
