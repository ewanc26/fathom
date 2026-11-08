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

/**
 * One recording and how much of the window it accounts for.
 *
 * Identity is the MusicBrainz recording id when the record carries one, and
 * the artist line plus title otherwise. [name] is the title; [artist] is the
 * line to render under it.
 */
data class TrackTally(
    val name: String,
    val artist: String,
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
    val topTracks: List<TrackTally> = emptyList(),
    val recentDays: List<DayBucket> = emptyList(),
    /** Plays in [recentDays] only, which is not the selected period's total. */
    val recentPlayCount: Int = 0,
    val busiestDay: DayBucket? = null,
    val topService: String? = null,
    val currentStreakDays: Int = 0,
    /**
     * Plays in the read whose timestamp is missing or unreadable, for every
     * period. All-time totals include them; a window cannot, so its totals
     * leave them out while this count still reports them.
     */
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
        val tracks = LinkedHashMap<String, Tally>()
        val dayCounts = HashMap<Long, Int>()
        val daySeconds = HashMap<Long, Long>()
        val services = HashMap<String, Int>()

        var playCount = 0
        var listenedSeconds = 0L
        var undatedPlays = 0

        for (play in plays) {
            val playedAt = IsoInstant.parse(play.playedTime)
            val day = playedAt?.let { CivilDays.epochDay(it, tzOffsetMinutes) }

            if (playedAt == null) {
                // The count is about the read, not the selected period:
                // History must be able to say how many records it cannot
                // place, even when a window excludes them from the totals.
                undatedPlays++
                if (windowStart != null) continue
            } else if (windowStart != null && (day == null || day < windowStart || day > today)) {
                continue
            }

            val seconds = (play.duration ?: 0).coerceAtLeast(0).toLong()
            playCount++
            listenedSeconds += seconds
            tracks.tally(
                key = play.trackKey(),
                name = play.title(),
                mbid = play.recordingMbId ?: play.trackMbId,
                detail = play.artistLine(),
            ).add(seconds)

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

        val recentPlayCount = recentDays.sumOf { it.playCount }

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
            uniqueTracks = tracks.size,
            uniqueReleases = releases.size,
            topArtists = artists.values
                .sortedWith(compareByDescending<Tally> { it.playCount }.thenBy { it.name.lowercase() })
                .take(leaders.coerceIn(0, 50))
                .map { it.toArtist() },
            topReleases = releases.values
                .sortedWith(compareByDescending<Tally> { it.playCount }.thenBy { it.name.lowercase() })
                .take(leaders.coerceIn(0, 50))
                .map { it.toRelease() },
            topTracks = tracks.values
                .sortedWith(
                    compareByDescending<Tally> { it.playCount }
                        .thenBy { it.name.lowercase() }
                        .thenBy { it.detail.orEmpty().lowercase() },
                )
                .take(leaders.coerceIn(0, 50))
                .map { it.toTrack() },
            recentDays = recentDays,
            recentPlayCount = recentPlayCount,
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

    private class Tally(val name: String, val mbid: String?, val detail: String? = null) {
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
        fun toTrack() = TrackTally(name, detail.orEmpty(), mbid, playCount, seconds)
    }

    private fun LinkedHashMap<String, Tally>.tally(
        key: String,
        name: String,
        mbid: String?,
        detail: String? = null,
    ): Tally = getOrPut(key) { Tally(name, mbid?.takeIf { it.isNotBlank() }, detail) }
}
