import Foundation
import FathomShared

/// Turns a handle into a dashboard, reading the repo rather than an AppView.
///
/// teal.fm's hosted stats endpoints are not reliably deployed, and the plays
/// are in the person's own repo anyway, so the figures are computed from
/// records. The cost is a bounded read: once the cap is reached the snapshot
/// is marked `sampled` and the screens say so.
struct DashboardLoader {
    let client: TealClient

    static let maxPlays = 1_000

    func load(identity raw: String, periodId: String) async throws -> DashboardSnapshot {
        let did = try await client.resolveIdentity(raw)
        let pds = try await client.pds(for: did)
        let handle = raw.hasPrefix("did:") ? nil : raw

        let profile = await loadProfile(pds: pds, did: did)
        let status = await loadStatus(pds: pds, did: did)

        var plays: [Play] = []
        var skipped = 0
        var usedLegacy = false

        for collection in FathomCore.shared.playCollections() {
            if plays.count >= Self.maxPlays { break }
            var cursor: String?
            repeat {
                let json: String
                do {
                    json = try await client.listRecords(
                        pds: pds,
                        repo: did,
                        collection: collection,
                        cursor: cursor,
                        limit: Int(FathomCore.shared.maxPage())
                    )
                } catch {
                    // One namespace failing must not lose the other: an
                    // account mid-migration can hold both.
                    break
                }

                let page = FathomCore.shared.playsPage(json: json)
                guard page.parsed else { break }
                if collection == FathomCore.shared.playCollections().last, !page.plays.isEmpty {
                    usedLegacy = true
                }
                skipped += Int(page.skipped)
                plays.append(contentsOf: page.plays)
                cursor = page.cursor
            } while cursor != nil && plays.count < Self.maxPlays
        }

        let ordered = plays
            .sorted { lhs, rhs in
                FathomCore.shared.instant(raw: lhs.playedTime)
                    > FathomCore.shared.instant(raw: rhs.playedTime)
            }
            .prefix(Self.maxPlays)

        let sampled = plays.count >= Self.maxPlays
        let now = Int64(Date().timeIntervalSince1970)
        let stats = FathomCore.shared.computeStats(
            plays: Array(ordered),
            nowEpochSeconds: now,
            tzOffsetMinutes: Int32(TimeZone.current.secondsFromGMT() / 60),
            periodId: periodId,
            sampled: sampled,
            chartDays: 14
        )

        return DashboardSnapshot(
            did: did,
            handle: handle,
            profile: profile,
            status: status,
            plays: Array(ordered),
            stats: stats,
            sampled: sampled,
            skipped: skipped,
            usedLegacyNamespace: usedLegacy
        )
    }

    private func loadProfile(pds: String, did: String) async -> TealProfile? {
        for collection in FathomCore.shared.profileCollections() {
            guard let json = try? await client.listRecords(
                pds: pds,
                repo: did,
                collection: collection,
                cursor: nil,
                limit: 1
            ) else { continue }

            let page = FathomCore.shared.profilesPage(json: json)
            if let profile = page.profiles.first { return profile }
        }
        return nil
    }

    private func loadStatus(pds: String, did: String) async -> TealStatus? {
        for collection in FathomCore.shared.statusCollections() {
            guard let json = try? await client.getRecord(
                pds: pds,
                repo: did,
                collection: collection,
                rkey: "self"
            ) else { continue }

            if let status = FathomCore.shared.status(json: json) { return status }
        }
        return nil
    }
}
