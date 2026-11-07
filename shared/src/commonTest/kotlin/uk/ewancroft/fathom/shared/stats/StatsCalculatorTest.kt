package uk.ewancroft.fathom.shared.stats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import uk.ewancroft.fathom.shared.format.CountText
import uk.ewancroft.fathom.shared.format.DurationText
import uk.ewancroft.fathom.shared.format.RelativeTime
import uk.ewancroft.fathom.shared.model.ArtistRef
import uk.ewancroft.fathom.shared.model.Play
import uk.ewancroft.fathom.shared.time.IsoInstant

class StatsCalculatorTest {

    private val now = IsoInstant.parse("2026-11-07T12:00:00Z")!!

    @Test
    fun countsOnlyTheWindow() {
        val plays = listOf(
            play("Today A", "Aphex Twin", "2026-11-07T09:00:00Z"),
            play("Today B", "Aphex Twin", "2026-11-07T10:00:00Z"),
            play("Yesterday", "Boards of Canada", "2026-11-06T20:00:00Z"),
            play("Six days", "Burial", "2026-11-01T20:00:00Z"),
            play("Eight days", "Burial", "2026-10-30T20:00:00Z"),
        )

        assertEquals(5, compute(plays, Period.AllTime).playCount)
        assertEquals(4, compute(plays, Period.SevenDays).playCount)
    }

    @Test
    fun totalsTheDeclaredDurations() {
        val plays = listOf(
            play("A", "Aphex Twin", "2026-11-07T09:00:00Z", duration = 300),
            play("B", "Aphex Twin", "2026-11-07T10:00:00Z", duration = 200),
        )
        assertEquals(500L, compute(plays, Period.SevenDays).listenedSeconds)
    }

    @Test
    fun ranksArtistsByPlaysThenName() {
        val plays = listOf(
            play("A", "Burial", "2026-11-07T09:00:00Z"),
            play("B", "Aphex Twin", "2026-11-07T10:00:00Z"),
            play("C", "Aphex Twin", "2026-11-07T11:00:00Z"),
        )
        val stats = compute(plays, Period.SevenDays)

        assertEquals(listOf("Aphex Twin", "Burial"), stats.topArtists.map { it.name })
        assertEquals(2, stats.topArtists.first().playCount)
        assertEquals(2, stats.uniqueArtists)
    }

    @Test
    fun countsEachArtistOnACollaboration() {
        val plays = listOf(
            Play(
                trackName = "Duet",
                artists = listOf(ArtistRef("A"), ArtistRef("B")),
                duration = 100,
                playedTime = "2026-11-07T09:00:00Z",
            ),
        )
        val stats = compute(plays, Period.SevenDays)

        assertEquals(2, stats.uniqueArtists)
        assertEquals(1, stats.playCount)
        assertEquals(100L, stats.listenedSeconds)
    }

    @Test
    fun streaksThroughAnUnfinishedToday() {
        // Plays yesterday and the day before, none yet today: the streak is
        // still two, because today has not happened yet.
        val plays = listOf(
            play("A", "X", "2026-11-06T09:00:00Z"),
            play("B", "X", "2026-11-05T09:00:00Z"),
        )
        assertEquals(2, compute(plays, Period.SevenDays).currentStreakDays)
    }

    @Test
    fun breaksTheStreakAtAGap() {
        val plays = listOf(
            play("A", "X", "2026-11-07T09:00:00Z"),
            play("B", "X", "2026-11-05T09:00:00Z"),
        )
        assertEquals(1, compute(plays, Period.SevenDays).currentStreakDays)
    }

    @Test
    fun returnsAChartOfFixedLengthEndingToday() {
        val stats = compute(listOf(play("A", "X", "2026-11-07T09:00:00Z")), Period.ThirtyDays, chartDays = 14)

        assertEquals(14, stats.recentDays.size)
        assertEquals("2026-11-07", stats.recentDays.last().dayKey)
        assertEquals(1, stats.recentDays.last().playCount)
        assertEquals("2026-10-25", stats.recentDays.first().dayKey)
        assertEquals(0, stats.recentDays.first().playCount)
    }

    @Test
    fun findsTheBusiestDayAndTheTopService() {
        val plays = listOf(
            play("A", "X", "2026-11-07T09:00:00Z", service = "https://open.spotify.com"),
            play("B", "X", "2026-11-07T10:00:00Z", service = "https://open.spotify.com"),
            play("C", "X", "2026-11-06T10:00:00Z", service = "https://music.apple.com"),
        )
        val stats = compute(plays, Period.SevenDays)

        assertEquals("2026-11-07", stats.busiestDay?.dayKey)
        assertEquals(2, stats.busiestDay?.playCount)
        assertEquals("open.spotify.com", stats.topService)
    }

    @Test
    fun reportsUndatedPlaysInsteadOfCountingThemSilently() {
        val plays = listOf(
            play("A", "X", "2026-11-07T09:00:00Z"),
            Play(trackName = "No time", artists = listOf(ArtistRef("X"))),
        )
        val allTime = compute(plays, Period.AllTime)
        assertEquals(1, allTime.undatedPlays)
        // Undated plays cannot be placed in a window, so they are excluded there.
        assertEquals(0, compute(plays, Period.SevenDays).undatedPlays)
        assertEquals(1, compute(plays, Period.SevenDays).playCount)
    }

    @Test
    fun carriesTheSampledFlag() {
        assertTrue(compute(emptyList(), Period.AllTime, sampled = true).sampled)
    }

    @Test
    fun anEmptyWindowIsStillDrawable() {
        val stats = compute(emptyList(), Period.SevenDays, chartDays = 14)
        assertEquals(0, stats.playCount)
        assertEquals(14, stats.recentDays.size)
        assertEquals(0, stats.currentStreakDays)
        assertTrue(!stats.hasData)
    }

    @Test
    fun formatsTotalsAsAmountsAndTracksAsClocks() {
        assertEquals("38 s", DurationText.listening(38))
        assertEquals("42 m", DurationText.listening(2_520))
        assertEquals("3 h 12 m", DurationText.listening(11_520))
        assertEquals("3 h", DurationText.listening(10_800))
        assertEquals("2 d 4 h", DurationText.listening(187_200))

        assertEquals("3:42", DurationText.track(222))
        assertEquals("1:02:03", DurationText.track(3_723))
        assertEquals("0:07", DurationText.track(7))
        assertEquals("--:--", DurationText.track(null))
        assertEquals("--:--", DurationText.track(-1))
    }

    @Test
    fun shortensLargeCounts() {
        assertEquals("999", CountText.compact(999))
        assertEquals("1.2k", CountText.compact(1_234))
        assertEquals("1k", CountText.compact(1_000))
        assertEquals("12.3k", CountText.compact(12_345))
        assertEquals("1.2M", CountText.compact(1_234_567))
    }

    @Test
    fun readsTimesInTheListenersZone() {
        val instant = IsoInstant.parse("2026-11-07T17:02:12Z")!!
        assertEquals("17:02", RelativeTime.clock(instant, 0))
        assertEquals("18:02", RelativeTime.clock(instant, 60))
        assertEquals("12:02", RelativeTime.clock(instant, -300))
    }

    @Test
    fun describesRecentTimes() {
        val nowInstant = IsoInstant.parse("2026-11-07T17:00:00Z")!!
        assertEquals("just now", RelativeTime.relative(nowInstant - 30, nowInstant, 0))
        assertEquals("5 min ago", RelativeTime.relative(nowInstant - 300, nowInstant, 0))
        assertEquals("2 hr ago", RelativeTime.relative(nowInstant - 7_200, nowInstant, 0))
        assertEquals("yesterday", RelativeTime.relative(nowInstant - 86_400, nowInstant, 0))
        assertEquals("3 days ago", RelativeTime.relative(nowInstant - 3 * 86_400, nowInstant, 0))
        // Forty days before 7 November is 28 September, and a date outside
        // this week is rendered as one.
        assertEquals("28 Sep", RelativeTime.relative(nowInstant - 40 * 86_400, nowInstant, 0))
    }

    @Test
    fun labelsDaysRelativeToToday() {
        assertEquals("Today", RelativeTime.friendlyDay(20_764, 20_764))
        assertEquals("Yesterday", RelativeTime.friendlyDay(20_763, 20_764))
        assertEquals("Sat 7 Nov", RelativeTime.friendlyDay(20_764, 20_770))
    }

    private fun compute(
        plays: List<Play>,
        period: Period,
        sampled: Boolean = false,
        chartDays: Int = 14,
    ): ListeningStats = StatsCalculator.compute(
        plays = plays,
        nowEpochSeconds = now,
        tzOffsetMinutes = 0,
        period = period,
        sampled = sampled,
        chartDays = chartDays,
    )

    private fun play(
        track: String,
        artist: String,
        playedAt: String,
        duration: Int = 200,
        service: String? = "https://open.spotify.com",
    ) = Play(
        trackName = track,
        artists = listOf(ArtistRef(artist)),
        duration = duration,
        playedTime = playedAt,
        musicServiceUri = service,
    )
}
