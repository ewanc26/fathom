package uk.ewancroft.fathom.shared.stats

import uk.ewancroft.fathom.shared.model.Play
import uk.ewancroft.fathom.shared.time.CivilDays
import uk.ewancroft.fathom.shared.time.IsoInstant

/** One artist and how much of the window they account for. */
data class ArtistTally(
    val name: String,
    val mbid: String?,
    val playCount: Int,
    val seconds: Long,
)

/** One release and how much of the window it accounts for. */
data class ReleaseTally(
    val name: String,
    val mbid: String?,
    val playCount: Int,
    val seconds: Long,
)

/** One calendar day, always present in a contiguous series even when empty. */
data class DayBucket(
    val dayKey: String,
    val epochDay: Long,
    val playCount: Int,
    val seconds: Long,
)

/**
 * Everything the dashboard draws, computed once.
 *
 * `listenedSeconds` is the sum of the durations the submitting client
 * declared. teal.fm's tracking rule decides whether a play counts at all;
 * the record does not carry how long the person actually listened, so this is
 * "time in tracks played" and the UI labels it that way rather than claiming
 * a measurement it does not have.
 *
 * `sampled` is true when the caller stopped paging before the repo ran out.
 * An "All time" figure built from the newest thousand plays is not all time,
 * and the screen has to say so.
 */
data class ListeningStats(
    val playCount: Int = 0,
    val listenedSeconds: Long = 0L,
    val uniqueArtists: Int = 0,
    val uniqueTracks: Int = 0,
    val uniqueReleases: Int = 0,
    val topArtists: List<ArtistTally> = emptyList(),
    val topReleases: List<ReleaseTally> = emptyList(),
    val recentDays: List<DayBucket> = emptyList(),
    val busiestDay: DayBucket? = null,
    val topService: String? = null,
    val currentStreakDays: Int = 0,
    val undatedPlays: Int = 0,
    val sampled: Boolean = false,
) {
    val hasData: Boolean get() = playCount > 0
}

/**
 * Aggregates plays into a dashboard.
 *
 * Pure and deterministic: the clock and the local UTC offset are passed in,
 * so a test can assert on a fixed date without a fake clock, and the same
 * play list always produces the same figures.
 */
object StatsCalculator {

    fun compute(
        plays: List<Play>,
        nowEpochSeconds: Long,
        tzOffsetMinutes: Int,
        period: Period,
        sampled: Boolean = false,
        chartDays: Int = 14,
        leaders: Int = 5,
    ): ListeningStats {
        val today = CivilDays.epochDay(nowEpochSeconds, tzOffsetMinutes)
        val windowStart = period.windowDays?.let { today - (it - 1).toLong() }

        val artists = LinkedHashMap<String, Tally>()
        val releases = LinkedHashMap<String, Tally>()
        val trackKeys = HashSet<String>()
        val dayCounts = HashMap<Long, Int>()
        val daySeconds = HashMap<Long, Long>()
        val services = HashMap<String, Int>()

        var playCount = 0
        var listenedSeconds = 0L
        var undatedPlays = 0

        for (play in plays) {
            val playedAt = IsoInstant.parse(play.playedTime)
            val day = playedAt?.let { CivilDays.epochDay(it, tzOffsetMinutes) }

            if (windowStart != null) {
                // A play that cannot be dated cannot be placed in a window.
                if (day == null || day < windowStart || day > today) continue
            } else if (playedAt == null) {
                undatedPlays++
            }

            val seconds = (play.duration ?: 0).coerceAtLeast(0).toLong()
            playCount++
            listenedSeconds += seconds
            trackKeys += play.trackKey()

            val names = play.artistNames()
            if (names.isEmpty()) {
                artists.tally(UNKNOWN_ARTIST_KEY, "Unknown artist", null).add(seconds)
            } else {
                for (name in names) {
                    val mbid = play.artists.firstOrNull { it.artistName == name }?.artistMbId
                    val key = mbid?.takeIf { it.isNotBlank() } ?: name.trim().lowercase()
                    artists.tally(key, name.trim(), mbid).add(seconds)
                }
            }

            play.releaseKey()?.let { key ->
                val name = play.releaseLine()
                releases.tally(key, name, play.releaseMbId).add(seconds)
            }

            if (day != null) {
                dayCounts[day] = (dayCounts[day] ?: 0) + 1
                daySeconds[day] = (daySeconds[day] ?: 0L) + seconds
            }

            play.serviceHost()?.let { host ->
                services[host] = (services[host] ?: 0) + 1
            }
        }

        val chart = chartDays.coerceIn(0, 90)
        val recentDays = ArrayList<DayBucket>(chart)
        for (offset in chart - 1 downTo 0) {
            val day = today - offset
            recentDays += DayBucket(
                dayKey = CivilDays.keyOf(day),
                epochDay = day,
                playCount = dayCounts[day] ?: 0,
                seconds = daySeconds[day] ?: 0L,
            )
        }

        val busiestDay = dayCounts.keys
            .map { day ->
                DayBucket(
                    dayKey = CivilDays.keyOf(day),
                    epochDay = day,
                    playCount = dayCounts[day] ?: 0,
                    seconds = daySeconds[day] ?: 0L,
                )
            }
            .sortedWith(compareByDescending<DayBucket> { it.playCount }.thenBy { it.epochDay })
            .firstOrNull()

        val topService = services.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()
            ?.key

        return ListeningStats(
            playCount = playCount,
            listenedSeconds = listenedSeconds,
            uniqueArtists = artists.size,
            uniqueTracks = trackKeys.size,
            uniqueReleases = releases.size,
            topArtists = artists.values
                .sortedWith(compareByDescending<Tally> { it.playCount }.thenBy { it.name.lowercase() })
                .take(leaders.coerceIn(0, 50))
                .map { it.toArtist() },
            topReleases = releases.values
                .sortedWith(compareByDescending<Tally> { it.playCount }.thenBy { it.name.lowercase() })
                .take(leaders.coerceIn(0, 50))
                .map { it.toRelease() },
            recentDays = recentDays,
            busiestDay = busiestDay,
            topService = topService,
            currentStreakDays = streak(today, dayCounts),
            undatedPlays = undatedPlays,
            sampled = sampled,
        )
    }

    /**
     * Consecutive days with at least one play, counting back from today.
     *
     * A day still in progress does not break a streak: if yesterday has plays
     * and today has none yet, the streak is counted from yesterday. Otherwise
     * every streak would read as zero every morning.
     */
    private fun streak(today: Long, dayCounts: Map<Long, Int>): Int {
        var cursor = if ((dayCounts[today] ?: 0) > 0) today else today - 1
        var length = 0
        while ((dayCounts[cursor] ?: 0) > 0) {
            length++
            cursor--
        }
        return length
    }

    private const val UNKNOWN_ARTIST_KEY = "\u0000unknown"

    private class Tally(val name: String, val mbid: String?) {
        var playCount: Int = 0
            private set
        var seconds: Long = 0L
            private set

        fun add(seconds: Long) {
            playCount++
            this.seconds += seconds
        }

        fun toArtist() = ArtistTally(name, mbid, playCount, seconds)
        fun toRelease() = ReleaseTally(name, mbid, playCount, seconds)
    }

    private fun LinkedHashMap<String, Tally>.tally(key: String, name: String, mbid: String?): Tally =
        getOrPut(key) { Tally(name, mbid?.takeIf { it.isNotBlank() }) }
}
