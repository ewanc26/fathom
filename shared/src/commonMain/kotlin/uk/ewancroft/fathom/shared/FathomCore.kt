package uk.ewancroft.fathom.shared

import uk.ewancroft.fathom.shared.auth.Provenance
import uk.ewancroft.fathom.shared.cache.CachedHistory
import uk.ewancroft.fathom.shared.cache.HistoryCache
import uk.ewancroft.fathom.shared.format.CountText
import uk.ewancroft.fathom.shared.format.DurationText
import uk.ewancroft.fathom.shared.format.RelativeTime
import uk.ewancroft.fathom.shared.format.ServiceNames
import uk.ewancroft.fathom.shared.model.ActorSearchPage
import uk.ewancroft.fathom.shared.model.Play
import uk.ewancroft.fathom.shared.model.PlayRecords
import uk.ewancroft.fathom.shared.model.TealProfile
import uk.ewancroft.fathom.shared.model.TealStatus
import uk.ewancroft.fathom.shared.stats.ListeningStats
import uk.ewancroft.fathom.shared.stats.Period
import uk.ewancroft.fathom.shared.stats.StatsCalculator
import uk.ewancroft.fathom.shared.time.CivilDays
import uk.ewancroft.fathom.shared.time.IsoInstant
import uk.ewancroft.fathom.shared.xrpc.MAX_PAGE
import uk.ewancroft.fathom.shared.xrpc.MAX_PLAYS
import uk.ewancroft.fathom.shared.xrpc.RetryPolicy
import uk.ewancroft.fathom.shared.xrpc.TealCollections
import uk.ewancroft.fathom.shared.xrpc.Xrpc
import uk.ewancroft.fathom.shared.xrpc.XrpcQuery
import uk.ewancroft.fathom.shared.xrpc.listRecordsQuery

/** A page of plays, without the generic that Swift cannot carry across the boundary. */
data class PlayPage(
    val plays: List<Play> = emptyList(),
    val cursor: String? = null,
    val skipped: Int = 0,
    val parsed: Boolean = false,
)

/** A page of profiles, for the same reason. */
data class ProfilePage(
    val profiles: List<TealProfile> = emptyList(),
    val cursor: String? = null,
    val skipped: Int = 0,
    val parsed: Boolean = false,
)

/**
 * The whole API iOS calls.
 *
 * Kotlin/Native cannot export a generic class usefully to Swift
 * (`RecordPage<Play>` arrives as an untyped collection), and a Kotlin enum
 * arrives as a class with a companion whose entry names are not obvious. So
 * the Swift-facing surface is deliberately narrow and string-based: periods
 * travel as their ids, pages arrive as concrete types, and every formatter is
 * here rather than behind three nested singletons.
 *
 * Android does not need this and calls the underlying types directly. The two
 * are not allowed to disagree, so anything added here must delegate rather
 * than reimplement.
 */
object FathomCore {

    // ── Decoding ─────────────────────────────────────────���───────────────

    fun playsPage(json: String): PlayPage {
        val page = TealJson.decodePlays(json)
        return PlayPage(page.values(), page.cursor, page.skipped, page.parsed)
    }

    fun profilesPage(json: String): ProfilePage {
        val page = TealJson.decodeProfiles(json)
        return ProfilePage(page.values(), page.cursor, page.skipped, page.parsed)
    }

    /** The value of a single `com.atproto.repo.getRecord` response. */
    fun profile(json: String): TealProfile? = TealJson.decodeRecordValue(json)

    fun status(json: String): TealStatus? = TealJson.decodeRecordValue(json)

    fun actorSearch(json: String): ActorSearchPage? = TealJson.decodeActorSearch(json)

    fun did(json: String): String? = TealJson.decodeDid(json)

    fun pdsEndpoint(json: String): String? = TealJson.decodePdsEndpoint(json)

    fun errorDetail(json: String): String? = TealJson.decodeError(json)

    // ── Saved history ───────────────────────────────────────────────────

    /**
     * The cache file for one completed read, as JSON.
     *
     * The plays list can run to tens of thousands of records, so this is the
     * one call in the facade worth keeping off the main thread.
     */
    fun encodeHistory(
        identity: String,
        did: String,
        handle: String?,
        profile: TealProfile?,
        status: TealStatus?,
        plays: List<Play>,
        skipped: Int,
        legacy: Boolean,
        sampled: Boolean,
        savedAtEpochSeconds: Long,
    ): String = HistoryCache.encode(
        CachedHistory(
            version = HistoryCache.VERSION,
            identity = identity,
            did = did,
            handle = handle,
            profile = profile,
            status = status,
            plays = plays,
            skipped = skipped,
            legacy = legacy,
            sampled = sampled,
            savedAtEpochSeconds = savedAtEpochSeconds,
        ),
    )

    /** The saved history in [json], or null when it is unusable. */
    fun decodeHistory(json: String): CachedHistory? = HistoryCache.decode(json)

    // ── Collections ──────────────────────────────────────────────────────

    fun playCollections(): List<String> = TealCollections.PLAYS

    fun profileCollections(): List<String> = TealCollections.PROFILES

    fun statusCollections(): List<String> = TealCollections.STATUSES

    // ── Periods ──────────────────────────────────────────────────────────

    fun periodIds(): List<String> = Period.entries.map { it.id }

    fun periodLabel(id: String): String = Period.fromId(id).label

    fun computeStats(
        plays: List<Play>,
        nowEpochSeconds: Long,
        tzOffsetMinutes: Int,
        periodId: String,
        sampled: Boolean,
        chartDays: Int,
    ): ListeningStats = StatsCalculator.compute(
        plays = plays,
        nowEpochSeconds = nowEpochSeconds,
        tzOffsetMinutes = tzOffsetMinutes,
        period = Period.fromId(periodId),
        sampled = sampled,
        chartDays = chartDays,
    )

    // ── Formatting ───────────────────────────────────────────────────────

    fun listeningText(seconds: Long): String = DurationText.listening(seconds)

    fun countText(value: Int): String = CountText.compact(value)

    fun relativeTime(epochSeconds: Long, nowEpochSeconds: Long, tzOffsetMinutes: Int): String =
        RelativeTime.relative(epochSeconds, nowEpochSeconds, tzOffsetMinutes)

    fun clockTime(epochSeconds: Long, tzOffsetMinutes: Int): String =
        RelativeTime.clock(epochSeconds, tzOffsetMinutes)

    fun friendlyDay(epochDay: Long, todayEpochDay: Long): String =
        RelativeTime.friendlyDay(epochDay, todayEpochDay)

    fun shortDate(epochDay: Long, todayEpochDay: Long): String =
        RelativeTime.shortDate(epochDay, todayEpochDay)

    fun serviceLabel(host: String?): String? = ServiceNames.label(host)

    fun epochDay(epochSeconds: Long, tzOffsetMinutes: Int): Long =
        CivilDays.epochDay(epochSeconds, tzOffsetMinutes)

    /** Epoch seconds for an RFC 3339 timestamp, or `Long.MIN_VALUE` when unreadable. */
    fun instant(raw: String?): Long = IsoInstant.parse(raw) ?: Long.MIN_VALUE

    // ── Transport ────────────────────────────────────────────────────────

    fun listRecordsPath(): String = Xrpc.path(Xrpc.LIST_RECORDS)

    fun getRecordPath(): String = Xrpc.path(Xrpc.GET_RECORD)

    fun resolveHandlePath(): String = Xrpc.path(Xrpc.RESOLVE_HANDLE)

    fun maxPage(): Int = MAX_PAGE

    /** The ceiling on plays one load reads, so the native paging loops agree. */
    fun playCeiling(): Int = MAX_PLAYS

    /** Collapses overlapping pages/cache entries by AT URI. */
    fun distinctPlays(plays: List<Play>): List<Play> = PlayRecords.distinct(plays)

    /** True when a complete, identified read can seed an incremental refresh. */
    fun canResume(plays: List<Play>, complete: Boolean): Boolean =
        PlayRecords.canResume(plays, complete)

    // Both transports ask this policy rather than inventing their own waits.
    fun retryMaxAttempts(): Int = RetryPolicy.MAX_ATTEMPTS

    fun isRetryableStatus(status: Int): Boolean = RetryPolicy.isRetryableStatus(status)

    fun retryDelayMillis(
        attempt: Int,
        retryAfterHeader: String?,
        nowEpochSeconds: Long,
    ): Long = RetryPolicy.delayMillis(attempt, retryAfterHeader, nowEpochSeconds)

    fun listRecordsQuery(repo: String, collection: String, cursor: String?, limit: Int): String =
        // Fully qualified on purpose: inside this object the unqualified name
        // is this function, so a facade that "delegates" without the package
        // calls itself until the stack guard stops it. That is a crash on the
        // first network read, and one no unit test had exercised.
        uk.ewancroft.fathom.shared.xrpc.listRecordsQuery(
            repo = repo,
            collection = collection,
            cursor = cursor,
            limit = limit,
        )

    fun getRecordQuery(repo: String, collection: String, rkey: String): String =
        XrpcQuery()
            .add("repo", repo)
            .add("collection", collection)
            .add("rkey", rkey)
            .build()

    fun encode(value: String): String = XrpcQuery.encode(value)

    // ── Copy that must not drift ─────────────────────────────────────────

    fun provenanceComputedLocally(): String = Provenance.COMPUTED_LOCALLY

    fun productionNamespace(): String = Provenance.PRODUCTION_NAMESPACE

    fun legacyNamespace(): String = Provenance.LEGACY_NAMESPACE
}
