import Foundation
import FathomShared

/// Turns a handle into a dashboard, reading the repo rather than an AppView.
///
/// teal.fm's hosted stats endpoints are not reliably deployed, and the plays
/// are in the person's own repo anyway, so the figures are computed from
/// records. Every page is read, because a total computed from the newest
/// thousand plays is not a total. A page costs roughly half a second on a real
/// PDS, so the newest page is published as soon as it arrives and the rest
/// follow: a long history fills in rather than freezing the app.
struct DashboardLoader {
    let client: TealClient

    /// A runaway guard, not a product limit. See `MAX_PLAYS` in the core.
    static let playCeiling = Int(FathomCore.shared.playCeiling())

    /// A thousand plays between screen updates: about six seconds of reading.
    static let progressEveryPages = 10

    /// Reads [raw]'s history, calling [onProgress] with the profile and status
    /// as soon as they arrive and then every `progressEveryPages` pages. Those
    /// snapshots are marked `sampled`; the returned one is sampled only if the
    /// read ended early.
    func load(
        identity raw: String,
        periodId: String,
        onProgress: @escaping @MainActor (DashboardSnapshot) -> Void
    ) async throws -> DashboardSnapshot {
        let did = try await client.resolveIdentity(raw)
        let pds = try await client.pds(for: did)
        let handle = raw.hasPrefix("did:") ? nil : raw

        let profile = await loadProfile(pds: pds, did: did)
        let status = await loadStatus(pds: pds, did: did)

        let collections = FathomCore.shared.playCollections()
        var plays: [Play] = []
        var skipped = 0
        var usedLegacy = false
        var complete = true
        var pages = 0

        // The account and what it is playing are known before a play is read,
        // so they go on screen first.
        await onProgress(
            snapshot(
                did: did,
                handle: handle,
                profile: profile,
                status: status,
                plays: plays,
                periodId: periodId,
                sampled: true,
                skipped: skipped,
                legacy: usedLegacy
            )
        )

        for collection in collections {
            var cursor: String?
            while true {
                if plays.count >= Self.playCeiling {
                    complete = false
                    break
                }

                let json: String
                do {
                    json = try await client.listRecords(
                        pds: pds,
                        repo: did,
                        collection: collection,
                        cursor: cursor,
                        limit: Int(FathomCore.shared.maxPage())
                    )
                } catch is CancellationError {
                    throw CancellationError()
                } catch let failure as URLError where failure.code == .cancelled {
                    // Nobody is waiting for this read any more; that is not
                    // the same thing as a history that ran out.
                    throw CancellationError()
                } catch let failure as FathomError where failure.absent {
                    // Nothing in this namespace. An account mid-migration can
                    // hold one and not the other, which is not a partial
                    // history.
                    break
                } catch {
                    // Keep what was read, but stop claiming it is the lot.
                    complete = false
                    break
                }

                let page = FathomCore.shared.playsPage(json: json)
                guard page.parsed else {
                    complete = false
                    break
                }
                if collection == collections.last, !page.plays.isEmpty {
                    usedLegacy = true
                }
                skipped += Int(page.skipped)
                plays.append(contentsOf: page.plays)

                pages += 1
                if pages % Self.progressEveryPages == 0 {
                    await onProgress(
                        snapshot(
                            did: did,
                            handle: handle,
                            profile: profile,
                            status: status,
                            plays: plays,
                            periodId: periodId,
                            sampled: true,
                            skipped: skipped,
                            legacy: usedLegacy
                        )
                    )
                }

                guard let next = page.cursor, next != cursor else {
                    // A server that repeats a cursor would otherwise page
                    // until the guard stopped it.
                    if page.cursor != nil { complete = false }
                    break
                }
                cursor = next
            }
        }

        return snapshot(
            did: did,
            handle: handle,
            profile: profile,
            status: status,
            plays: plays,
            periodId: periodId,
            sampled: !complete,
            skipped: skipped,
            legacy: usedLegacy
        )
    }

    /// The same snapshot for a different period, without reading the repo.
    ///
    /// A period changes the aggregation, not the records: the plays are
    /// already in memory, and re-reading thousands of them to move a seven-day
    /// window would make every chip tap cost a minute.
    func restat(_ snapshot: DashboardSnapshot, periodId: String) -> DashboardSnapshot {
        self.snapshot(
            did: snapshot.did,
            handle: snapshot.handle,
            profile: snapshot.profile,
            status: snapshot.status,
            plays: snapshot.plays,
            periodId: periodId,
            sampled: snapshot.sampled,
            skipped: snapshot.skipped,
            legacy: snapshot.usedLegacyNamespace
        )
    }

    /// A saved history, restatted for the period on screen.
    ///
    /// The saved flag travels: a history that stopped at the guard or on a
    /// failed page reopens labelled partial, not passed off as a total.
    func restore(_ cached: CachedHistory, periodId: String) -> DashboardSnapshot {
        snapshot(
            did: cached.did,
            handle: cached.handle,
            profile: cached.profile,
            status: cached.status,
            plays: cached.plays,
            periodId: periodId,
            sampled: cached.sampled,
            skipped: Int(cached.skipped),
            legacy: cached.legacy
        )
    }

    /// Orders plays newest first and computes the figures for [periodId].
    private func snapshot(
        did: String,
        handle: String?,
        profile: TealProfile?,
        status: TealStatus?,
        plays: [Play],
        periodId: String,
        sampled: Bool,
        skipped: Int,
        legacy: Bool
    ) -> DashboardSnapshot {
        let ordered = plays.sorted { lhs, rhs in
            FathomCore.shared.instant(raw: lhs.playedTime)
                > FathomCore.shared.instant(raw: rhs.playedTime)
        }

        let stats = FathomCore.shared.computeStats(
            plays: ordered,
            nowEpochSeconds: Int64(Date().timeIntervalSince1970),
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
            plays: ordered,
            stats: stats,
            sampled: sampled,
            skipped: skipped,
            usedLegacyNamespace: legacy
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
