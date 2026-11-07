package uk.ewancroft.fathom.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.ewancroft.fathom.shared.TealJson
import uk.ewancroft.fathom.shared.model.Play
import uk.ewancroft.fathom.shared.model.TealProfile
import uk.ewancroft.fathom.shared.model.TealStatus
import uk.ewancroft.fathom.shared.stats.ListeningStats
import uk.ewancroft.fathom.shared.stats.Period
import uk.ewancroft.fathom.shared.stats.StatsCalculator
import uk.ewancroft.fathom.shared.time.IsoInstant
import uk.ewancroft.fathom.shared.xrpc.MAX_PAGE
import uk.ewancroft.fathom.shared.xrpc.TealCollections

/**
 * Everything one dashboard render needs.
 *
 * `sampled` means the repo held more plays than the cap allowed us to read,
 * so the figures are over the most recent [FathomRepository.MAX_PLAYS] plays
 * rather than over all of them. The screen must say so; an all-time total
 * that silently drops history is a lie about someone's listening.
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
)

/**
 * Turns an identity into a dashboard.
 *
 * Reads the repo directly instead of asking teal.fm's AppView for figures.
 * That is a deliberate choice and the reason it works at all: the hosted
 * stats endpoints (`fm.teal.stats.*`) are not reliably deployed — at the time
 * of writing one public host answers `fm.teal.actor.getStatus` and 404s every
 * stats route — while every play is in the person's own repo and therefore
 * always readable. Figures computed from a bounded slice are labelled as
 * such rather than presented as network totals.
 */
class FathomRepository(private val client: TealClient = TealClient()) {

    suspend fun load(
        identity: String,
        period: Period,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
        tzOffsetMinutes: Int = currentOffsetMinutes(nowEpochSeconds),
    ): Dashboard = withContext(Dispatchers.IO) {
        val did = client.resolveIdentity(identity)
        val pds = client.pdsFor(did)
        val handle = if (identity.startsWith("did:")) null else identity.removePrefix("@")

        val profile = loadProfile(pds, did)
        val status = loadStatus(pds, did)

        var skipped = 0
        var legacy = false
        val plays = ArrayList<Play>(MAX_PLAYS)

        for (collection in TealCollections.PLAYS) {
            if (plays.size >= MAX_PLAYS) break
            var cursor: String? = null
            do {
                // A collection that 400s is not a reason to lose the other
                // namespace: the migration means an account can hold one and
                // not the other, and an unknown collection is answered
                // inconsistently across PDS implementations.
                val page = runCatching {
                    TealJson.decodePlays(
                        client.listRecords(pds, did, collection, cursor, MAX_PAGE),
                    )
                }.getOrNull() ?: break
                if (!page.parsed) break
                if (collection == TealCollections.ALPHA_PLAY && page.records.isNotEmpty()) {
                    legacy = true
                }
                skipped += page.skipped
                page.values().forEach { plays += it }
                cursor = page.cursor
            } while (cursor != null && plays.size < MAX_PLAYS)
        }

        val ordered = plays
            .sortedWith(
                compareByDescending<Play> { IsoInstant.parse(it.playedTime) ?: Long.MIN_VALUE }
                    .thenByDescending { it.playedTime.orEmpty() },
            )
            .take(MAX_PLAYS)

        val sampled = plays.size >= MAX_PLAYS
        val stats = StatsCalculator.compute(
            plays = ordered,
            nowEpochSeconds = nowEpochSeconds,
            tzOffsetMinutes = tzOffsetMinutes,
            period = period,
            sampled = sampled,
        )

        Dashboard(
            did = did,
            handle = handle,
            profile = profile,
            status = status,
            plays = ordered,
            stats = stats,
            sampled = sampled,
            skippedRecords = skipped,
            usedLegacyNamespace = legacy,
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

    private suspend fun loadStatus(pds: String, did: String): TealStatus? {
        for (collection in TealCollections.STATUSES) {
            val body = runCatching { client.getRecord(pds, did, collection, RKEY_SELF) }
                .getOrNull() ?: continue
            TealJson.decodeSingleRecord<TealStatus>(body)?.let { return it.value }
        }
        return null
    }

    companion object {
        /** The ceiling on plays read for one dashboard. Ten pages of a hundred. */
        const val MAX_PLAYS = 1_000

        private const val RKEY_SELF = "self"

        /** The device's offset from UTC at [epochSeconds], in minutes east. */
        fun currentOffsetMinutes(epochSeconds: Long): Int =
            java.util.TimeZone.getDefault().getOffset(epochSeconds * 1_000L) / 60_000
    }
}
