import Foundation
import FathomShared

/// One status poll's result: the record, and the PDS it came from so the next
/// poll can skip the PLC lookup.
struct StatusRefresh {
    let pds: String
    let status: TealStatus?
}

/// Turns a handle into a dashboard, reading the repo rather than an AppView.
///
/// teal.fm's hosted stats endpoints are not reliably deployed, and the plays
/// are in the person's own repo anyway, so the figures are computed from
/// records. A first read fetches every page, because a total computed from the
/// newest thousand plays is not a total. A page costs roughly half a second on
/// a real PDS, so the newest page is published as soon as it arrives and the
/// rest follow: a long history fills in rather than freezing the app. A later
/// refresh stops at the first wholly known page instead.
struct DashboardLoader {
    let client: TealClient

    /// A runaway guard, not a product limit. See `MAX_PLAYS` in the core.
    static let playCeiling = Int(FathomCore.shared.playCeiling())

    /// A thousand plays between screen updates: about six seconds of reading.
    static let progressEveryPages = 10

    /// Reads [raw]'s history, calling [onProgress] with the profile and status
    /// as soon as they arrive and then every `progressEveryPages` pages. A
    /// resumed read also publishes after its first page with new records,
    /// since that read may stop on the next page. Those snapshots are marked
    /// `sampled`; the returned one is sampled only if the read ended early.
    ///
    /// A complete [checkpoint] from the saved copy is a stopping point: the
    /// read pages newest-first until a whole page is already known, then stops
    /// and lets `PlayMerger` keep the newer records. [forceFull] ignores the
    /// checkpoint for the explicit "read everything" action, which is how an
    /// edited or deleted record is noticed.
    func load(
        identity raw: String,
        periodId: String,
        checkpoint: ReadCheckpoint? = nil,
        forceFull: Bool = false,
        onProgress: @escaping @MainActor (DashboardSnapshot) -> Void
    ) async throws -> DashboardSnapshot {
        let did = try await client.resolveIdentity(raw)
        let pds = try await client.pds(for: did)
        let handle = raw.hasPrefix("did:") ? nil : raw

        let profile = await loadProfile(pds: pds, did: did)
        let status = await loadStatus(pds: pds, did: did)
        let collections = FathomCore.shared.playCollections()

        let resume: ReadCheckpoint? = {
            guard !forceFull,
                  let checkpoint,
                  checkpoint.did == did,
                  FathomCore.shared.canResume(
                    plays: checkpoint.plays,
                    complete: checkpoint.complete
                  ) else { return nil }
            return checkpoint
        }()
        let merger = PlayMerger(known: resume?.plays ?? [])
        var skipped = Int(resume?.skipped ?? 0)
        var usedLegacy = resume?.legacy ?? false
        var complete = true
        var readFailure: String? = nil
        var pages = 0

        // The account and what it is playing are known before a play is read,
        // so they go on screen first. A resumed read already has a full saved
        // picture; publishing its seed would flash the reading bar for a
        // refresh that may finish on the first page.
        if resume == nil {
            await onProgress(
                snapshot(
                    did: did,
                    handle: handle,
                    profile: profile,
                    status: status,
                    plays: merger.plays,
                    periodId: periodId,
                    sampled: true,
                    skipped: skipped,
                    legacy: usedLegacy,
                    readFailure: readFailure,
                    pds: pds
                )
            )
        }

        for collection in collections {
            var cursor: String?
            while true {
                // A resumed cache may already sit at the guard. It is bounded,
                // and the first wholly-known page is the stop; a full read is
                // still stopped at exactly the same ceiling as before.
                if resume == nil && Int(merger.size) >= Self.playCeiling {
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
                    if readFailure == nil {
                        readFailure = (error as? FathomError)?.message
                            ?? "The read stopped early."
                    }
                    break
                }

                let page = FathomCore.shared.playsPage(json: json)
                guard page.parsed else {
                    complete = false
                    readFailure = "A page of plays could not be read."
                    break
                }
                if collection == collections.last, !page.plays.isEmpty {
                    usedLegacy = true
                }
                skipped += Int(page.skipped)
                let merge = merger.addPage(page: page.plays)
                // A malformed sibling is not a known record. The page cannot
                // prove where the saved tail starts, so keep reading.
                let pageIsKnown = merge.pageWasFullyKnown && page.skipped == 0

                pages += 1
                if resume != nil {
                    if !pageIsKnown &&
                        (pages == 1 || pages % Self.progressEveryPages == 0) {
                        await onProgress(
                            snapshot(
                                did: did,
                                handle: handle,
                                profile: profile,
                                status: status,
                                plays: merger.plays,
                                periodId: periodId,
                                sampled: true,
                                skipped: skipped,
                                legacy: usedLegacy,
                                readFailure: readFailure,
                                pds: pds
                            )
                        )
                    }
                } else if pages % Self.progressEveryPages == 0 {
                    await onProgress(
                        snapshot(
                            did: did,
                            handle: handle,
                            profile: profile,
                            status: status,
                            plays: merger.plays,
                            periodId: periodId,
                            sampled: true,
                            skipped: skipped,
                            legacy: usedLegacy,
                            readFailure: readFailure,
                            pds: pds
                        )
                    )
                }
                if resume != nil && pageIsKnown { break }

                guard let next = page.cursor, next != cursor else {
                    // A server that repeats a cursor would otherwise page
                    // until the guard stopped it.
                    if page.cursor != nil {
                        complete = false
                        readFailure = "The data server did not advance the page cursor."
                    }
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
            plays: merger.plays,
            periodId: periodId,
            sampled: !complete,
            skipped: skipped,
            legacy: usedLegacy,
            readFailure: readFailure,
            pds: pds
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
            legacy: snapshot.usedLegacyNamespace,
            readFailure: snapshot.readFailure,
            pds: snapshot.pds
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
            plays: FathomCore.shared.distinctPlays(plays: cached.plays),
            periodId: periodId,
            sampled: cached.sampled,
            skipped: Int(cached.skipped),
            legacy: cached.legacy,
            readFailure: nil,
            pds: nil
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
        legacy: Bool,
        readFailure: String?,
        pds: String?
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
            usedLegacyNamespace: legacy,
            readFailure: readFailure,
            pds: pds
        )
    }

    /// The status record alone, for the periodic refresh.
    ///
    /// Unlike the initial load, a network failure is thrown rather than
    /// treated as "no status": clearing a live card because one poll failed
    /// would be a worse lie than one stale minute. A missing record (400/404)
    /// is still an answer and arrives as nil.
    func refreshStatus(for snapshot: DashboardSnapshot) async throws -> StatusRefresh {
        let pds: String
        if let known = snapshot.pds {
            pds = known
        } else {
            pds = try await client.pds(for: snapshot.did)
        }

        for collection in FathomCore.shared.statusCollections() {
            let json = try await client.getRecord(
                pds: pds,
                repo: snapshot.did,
                collection: collection,
                rkey: "self"
            )
            guard let json else { continue }
            if let status = FathomCore.shared.status(json: json) {
                return StatusRefresh(pds: pds, status: status)
            }
        }
        return StatusRefresh(pds: pds, status: nil)
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
