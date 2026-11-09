package uk.ewancroft.fathom.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.ewancroft.fathom.shared.TealJson
import uk.ewancroft.fathom.shared.cache.CachedHistory
import uk.ewancroft.fathom.shared.cache.HistoryCache
import uk.ewancroft.fathom.shared.model.Play
import uk.ewancroft.fathom.shared.model.PlayMerger
import uk.ewancroft.fathom.shared.model.PlayRecords
import uk.ewancroft.fathom.shared.model.ReadCheckpoint
import uk.ewancroft.fathom.shared.model.TealProfile
import uk.ewancroft.fathom.shared.model.TealStatus
import uk.ewancroft.fathom.shared.stats.ListeningStats
import uk.ewancroft.fathom.shared.stats.Period
import uk.ewancroft.fathom.shared.stats.StatsCalculator
import uk.ewancroft.fathom.shared.time.IsoInstant
import uk.ewancroft.fathom.shared.xrpc.MAX_PAGE
import uk.ewancroft.fathom.shared.xrpc.MAX_PLAYS
import uk.ewancroft.fathom.shared.xrpc.TealCollections

/**
 * Everything one dashboard render needs.
 *
 * `sampled` means the read has not reached the end of the repo — the
 * [MAX_PLAYS] guard, a failed page, or a cursor that would not advance. While
 * a read is still running it is true as well, because the figures on screen
 * really are partial at that moment. The screen must say so; a total that
 * silently drops history is a lie about someone's listening.
 */
data class Dashboard(
    val did: String,
    val handle: String?,
    val profile: TealProfile?,
    val status: TealStatus?,
    val plays: List<Play>,
    val stats: ListeningStats,
    val sampled: Boolean,
    val skippedRecords: Int,
    val usedLegacyNamespace: Boolean,
    /** Why a partial read stopped, when it failed rather than hit the guard. */
    val readFailure: String? = null,
    /** The PDS this read came from; null for a copy restored from disk. */
    val pds: String? = null,
)

/** One status re-read, plus the PDS it came from so the next poll can reuse it. */
data class StatusRefresh(
    val pds: String,
    val status: TealStatus?,
)

/**
 * Turns an identity into a dashboard.
 *
 * Reads the repo directly instead of asking teal.fm's AppView for figures.
 * That is a deliberate choice and the reason it works at all: the hosted
 * stats endpoints (`fm.teal.stats.*`) are not reliably deployed — at the time
 * of writing one public host answers `fm.teal.actor.getStatus` and 404s every
 * stats route — while every play is in the person's own repo and therefore
 * always readable.
 *
 * A first read fetches every page, because a total computed from the newest
 * thousand plays is not a total. A page costs roughly half a second on a real
 * PDS, so a long history is minutes of requests: the newest page is published
 * as soon as it arrives and the rest follow, rather than leaving the app on a
 * spinner. A later refresh stops at the first wholly known page instead.
 */
open class FathomRepository(private val client: TealReadClient = TealClient()) {

    /**
     * Reads [identity]'s history, calling [onProgress] with a dashboard after
     * the profile and status arrive and then every [PROGRESS_EVERY_PAGES]
     * pages. A resumed read publishes after its first page with new records,
     * since that read may stop on the next page. Every dashboard passed to
     * [onProgress] is marked sampled; the returned one is sampled only if the
     * read ended early.
     *
     * A complete [checkpoint] from the saved copy is a stopping point: the
     * read pages newest-first until a whole page is already known, then stops
     * and lets [PlayMerger] keep the newer records. [forceFull] ignores the
     * checkpoint for the explicit "read everything" action, which is how an
     * edited or deleted record is noticed.
     */
    open suspend fun load(
        identity: String,
        period: Period,
        checkpoint: ReadCheckpoint? = null,
        forceFull: Boolean = false,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
        tzOffsetMinutes: Int = currentOffsetMinutes(nowEpochSeconds),
        onProgress: ((Dashboard) -> Unit)? = null,
    ): Dashboard = withContext(Dispatchers.IO) {
        val did = client.resolveIdentity(identity)
        val pds = client.pdsFor(did)
        val handle = if (identity.startsWith("did:")) null else identity.removePrefix("@")

        val profile = loadProfile(pds, did)
        val status = loadStatus(pds, did)

        val resume = checkpoint?.takeIf {
            !forceFull && it.did == did && PlayRecords.canResume(it.plays, it.complete)
        }
        val merger = PlayMerger(resume?.plays ?: emptyList())
        var skipped = resume?.skipped ?: 0
        var legacy = resume?.legacy ?: false
        var complete = true
        var readFailure: String? = null
        var pages = 0

        fun publish(sampled: Boolean) {
            onProgress?.invoke(
                snapshot(
                    did = did,
                    handle = handle,
                    profile = profile,
                    status = status,
                    plays = merger.plays,
                    period = period,
                    nowEpochSeconds = nowEpochSeconds,
                    tzOffsetMinutes = tzOffsetMinutes,
                    sampled = sampled,
                    skipped = skipped,
                    legacy = legacy,
                    readFailure = readFailure,
                    pds = pds,
                ),
            )
        }

        // The account and what it is playing are known before any play is
        // read, so they go on screen first. A resumed read already has a full
        // saved picture; publishing its seed would flash the reading banner
        // for a refresh that may finish on the first page.
        if (resume == null) publish(sampled = true)

        for (collection in TealCollections.PLAYS) {
            var cursor: String? = null
            while (true) {
                // A resumed cache may already sit at the guard. It is bounded,
                // and the first wholly-known page is the stop; a full read is
                // still stopped at exactly the same ceiling as before.
                if (resume == null && merger.size >= MAX_PLAYS) {
                    complete = false
                    break
                }

                // A 404 is absence: the migration means an account can hold
                // one namespace and not the other, and an unknown collection
                // is answered inconsistently across PDS implementations.
                // A 400 is not absence: it is a rejected request (a bad cursor,
                // for example), so the rest of this namespace is unread and the
                // dashboard must say so.
                val response = runCatching {
                    client.listRecords(pds, did, collection, cursor, MAX_PAGE)
                }
                response.exceptionOrNull()?.let { failure ->
                    if (failure is CancellationException) throw failure
                    if (failure !is FathomNotFoundException) {
                        complete = false
                        if (readFailure == null) {
                            readFailure = (failure as? FathomException)?.message
                                ?: "The read stopped early."
                        }
                    }
                }
                val body = response.getOrNull() ?: break

                val page = TealJson.decodePlays(body)
                if (!page.parsed) {
                    complete = false
                    if (readFailure == null) readFailure = "A page of plays could not be read."
                    break
                }
                if (collection == TealCollections.ALPHA_PLAY && page.records.isNotEmpty()) {
                    legacy = true
                }
                skipped += page.skipped
                val merge = merger.addPage(page.values())
                // A malformed sibling is not a known record. The page cannot
                // prove where the saved tail starts, so keep reading.
                val pageIsKnown = merge.pageWasFullyKnown && page.skipped == 0

                pages++
                if (resume != null) {
                    if (!pageIsKnown &&
                        (pages == 1 || pages % PROGRESS_EVERY_PAGES == 0)
                    ) {
                        publish(sampled = true)
                    }
                } else if (pages % PROGRESS_EVERY_PAGES == 0) {
                    publish(sampled = true)
                }
                if (resume != null && pageIsKnown) break

                val next = page.cursor ?: break
                // A server that repeats a cursor would otherwise page until
                // the guard stopped it.
                if (next == cursor) {
                    complete = false
                    if (readFailure == null) {
                        readFailure = "The data server did not advance the page cursor."
                    }
                    break
                }
                cursor = next
            }
        }

        snapshot(
            did = did,
            handle = handle,
            profile = profile,
            status = status,
            plays = merger.plays,
            period = period,
            nowEpochSeconds = nowEpochSeconds,
            tzOffsetMinutes = tzOffsetMinutes,
            sampled = !complete,
            skipped = skipped,
            legacy = legacy,
            readFailure = readFailure,
            pds = pds,
        )
    }

    /**
     * Re-reads just the status record for [dashboard].
     *
     * The status is the one thing on the dashboard that changes without the
     * repo changing, and it expires in ten minutes. One `getRecord` a minute
     * keeps it honest; plays and figures are untouched. Unlike the initial
     * load, a network failure is thrown rather than turned into "no status":
     * clearing a live card because one poll failed would be a worse lie than
     * one stale minute.
     */
    open suspend fun refreshStatus(dashboard: Dashboard): StatusRefresh {
        val pds = dashboard.pds ?: client.pdsFor(dashboard.did)
        return StatusRefresh(
            pds = pds,
            status = loadStatus(pds, dashboard.did, allowFailure = false),
        )
    }

    /**
     * The same dashboard for a different period, without reading the repo.
     *
     * A period changes the aggregation, not the records: the plays are already
     * in memory, and re-fetching thousands of them to move a seven-day window
     * would make every chip tap cost a minute.
     */
    open fun rerange(
        dashboard: Dashboard,
        period: Period,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
        tzOffsetMinutes: Int = currentOffsetMinutes(nowEpochSeconds),
    ): Dashboard = dashboard.copy(
        stats = StatsCalculator.compute(
            plays = dashboard.plays,
            nowEpochSeconds = nowEpochSeconds,
            tzOffsetMinutes = tzOffsetMinutes,
            period = period,
            sampled = dashboard.sampled,
        ),
    )

    /** The cache entry for a completed read of [identity]. */
    open fun toCache(identity: String, dashboard: Dashboard, savedAtEpochSeconds: Long): CachedHistory =
        CachedHistory(
            version = HistoryCache.VERSION,
            identity = identity,
            did = dashboard.did,
            handle = dashboard.handle,
            profile = dashboard.profile,
            status = dashboard.status,
            plays = dashboard.plays,
            skipped = dashboard.skippedRecords,
            legacy = dashboard.usedLegacyNamespace,
            sampled = dashboard.sampled,
            savedAtEpochSeconds = savedAtEpochSeconds,
        )

    /**
     * A saved history, restatted for the period on screen.
     *
     * The saved copy keeps the read's `sampled` flag: a history that stopped
     * at the guard or on a failed page is still drawn, still labelled partial,
     * and still refreshed behind that label.
     */
    open fun fromCache(
        cached: CachedHistory,
        period: Period,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
        tzOffsetMinutes: Int = currentOffsetMinutes(nowEpochSeconds),
    ): Dashboard = snapshot(
        did = cached.did,
        handle = cached.handle,
        profile = cached.profile,
        status = cached.status,
        plays = deduplicatedCache(cached),
        period = period,
        nowEpochSeconds = nowEpochSeconds,
        tzOffsetMinutes = tzOffsetMinutes,
        sampled = cached.sampled,
        skipped = cached.skipped,
        legacy = cached.legacy,
    )

    /**
     * A saved history deduplicated before it reaches the screens.
     *
     * The cache is written from a deduplicated read, but an older file or a
     * hand-edited one can still contain the same record twice. Deduplicating
     * here means the figures and History cannot disagree about it.
     */
    private fun deduplicatedCache(cached: CachedHistory): List<Play> =
        PlayRecords.distinct(cached.plays)

    /** Orders plays newest first and computes the figures for [period]. */
    private fun snapshot(
        did: String,
        handle: String?,
        profile: TealProfile?,
        status: TealStatus?,
        plays: List<Play>,
        period: Period,
        nowEpochSeconds: Long,
        tzOffsetMinutes: Int,
        sampled: Boolean,
        skipped: Int,
        legacy: Boolean,
        readFailure: String? = null,
        pds: String? = null,
    ): Dashboard {
        val ordered = plays.sortedWith(
            compareByDescending<Play> { IsoInstant.parse(it.playedTime) ?: Long.MIN_VALUE }
                .thenByDescending { it.playedTime.orEmpty() },
        )
        return Dashboard(
            did = did,
            handle = handle,
            profile = profile,
            status = status,
            plays = ordered,
            stats = StatsCalculator.compute(
                plays = ordered,
                nowEpochSeconds = nowEpochSeconds,
                tzOffsetMinutes = tzOffsetMinutes,
                period = period,
                sampled = sampled,
            ),
            sampled = sampled,
            skippedRecords = skipped,
            usedLegacyNamespace = legacy,
            readFailure = readFailure,
            pds = pds,
        )
    }

    private suspend fun loadProfile(pds: String, did: String): TealProfile? {
        for (collection in TealCollections.PROFILES) {
            val page = runCatching {
                TealJson.decodeProfiles(client.listRecords(pds, did, collection, null, 1))
            }.getOrNull() ?: continue
            page.records.firstOrNull()?.let { return it.value }
        }
        return null
    }

    /**
     * The status record, or null when neither namespace has one.
     *
     * The initial dashboard load treats every failure as "no status", because
     * a missing status is the normal state of an account whose tracker is not
     * running. A poll passes `allowFailure = false` so that a network problem
     * cannot be mistaken for a deleted record and clear a live card.
     */
    private suspend fun loadStatus(
        pds: String,
        did: String,
        allowFailure: Boolean = true,
    ): TealStatus? {
        for (collection in TealCollections.STATUSES) {
            val body = try {
                client.getRecord(pds, did, collection, RKEY_SELF)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: FathomException) {
                if (!allowFailure) throw failure
                continue
            }
            val entry = body?.let { TealJson.decodeSingleRecord<TealStatus>(it) } ?: continue
            return entry.value
        }
        return null
    }

    companion object {
        /** A thousand plays between screen updates: about six seconds of reading. */
        private const val PROGRESS_EVERY_PAGES = 10

        private const val RKEY_SELF = "self"

        /** The device's offset from UTC at [epochSeconds], in minutes east. */
        fun currentOffsetMinutes(epochSeconds: Long): Int =
            java.util.TimeZone.getDefault().getOffset(epochSeconds * 1_000L) / 60_000
    }
}
