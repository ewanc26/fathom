package uk.ewancroft.fathom.shared.format

import uk.ewancroft.fathom.shared.time.CivilDate
import uk.ewancroft.fathom.shared.time.CivilDays

/**
 * Human durations.
 *
 * Two different things get called "duration" in this app and they must not
 * look alike: a track length is a clock position (`3:42`), while a total is
 * an amount of time (`3 h 12 m`). Mixing the two makes a dashboard that reads
 * as if someone listened to `3:42` minutes.
 */
object DurationText {

    /** A total listening amount, e.g. `3 h 12 m`, `42 m`, `38 s`, `2 d 4 h`. */
    fun listening(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        if (total < 60) return "$total s"
        val minutes = total / 60
        if (minutes < 60) return "$minutes m"
        val hours = minutes / 60
        if (hours < 24) {
            val remainder = (minutes % 60).toInt()
            return if (remainder == 0) "$hours h" else "$hours h $remainder m"
        }
        val days = hours / 24
        val leftoverHours = (hours % 24).toInt()
        return if (leftoverHours == 0) "$days d" else "$days d $leftoverHours h"
    }

    /** A track length, e.g. `3:42`, or `1:02:03`. `--:--` when the record declared none. */
    fun track(seconds: Int?): String {
        if (seconds == null || seconds < 0) return "--:--"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainder = seconds % 60
        return if (hours > 0) {
            "${hours}:${pad(minutes)}:${pad(remainder)}"
        } else {
            "${minutes}:${pad(remainder)}"
        }
    }

    private fun pad(value: Int): String = value.toString().padStart(2, '0')
}

/** Play counts, shortened only once the digits would crowd a stat tile. */
object CountText {
    fun compact(value: Int): String {
        if (value < 0) return "0"
        return when {
            value < 1_000 -> value.toString()
            value < 1_000_000 -> trimZero(value / 100.0 / 10.0) + "k"
            else -> trimZero(value / 100_000.0 / 10.0) + "M"
        }
    }

    private fun trimZero(value: Double): String {
        val rounded = (value * 10).toLong() / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
    }
}

/**
 * Times on the wire, rendered for a reader.
 *
 * The offset is supplied by the caller because this is common code and has no
 * device clock. Times are 24-hour: a listener reading their own scrobbles is
 * not helped by a guess at their locale, and a wrong `p.m.` is worse than a
 * clock position everyone can read.
 */
object RelativeTime {

    fun relative(epochSeconds: Long, nowEpochSeconds: Long, tzOffsetMinutes: Int): String {
        val delta = nowEpochSeconds - epochSeconds
        if (delta < 60) return "just now"
        if (delta < 3_600) return "${delta / 60} min ago"
        if (delta < 86_400) return "${delta / 3_600} hr ago"

        val today = CivilDays.epochDay(nowEpochSeconds, tzOffsetMinutes)
        val day = CivilDays.epochDay(epochSeconds, tzOffsetMinutes)
        val days = today - day
        if (days == 1L) return "yesterday"
        if (days in 2L..6L) return "$days days ago"
        return shortDate(day, today)
    }

    /** `17:02`, in the supplied zone. */
    fun clock(epochSeconds: Long, tzOffsetMinutes: Int): String {
        val shifted = epochSeconds + tzOffsetMinutes.toLong() * 60L
        val secondsOfDay = ((shifted % 86_400L) + 86_400L) % 86_400L
        val hours = (secondsOfDay / 3_600L).toInt()
        val minutes = ((secondsOfDay % 3_600L) / 60L).toInt()
        return "${pad(hours)}:${pad(minutes)}"
    }

    /** `Today`, `Yesterday`, or `Fri 7 Nov`. */
    fun friendlyDay(epochDay: Long, todayEpochDay: Long): String {
        val difference = todayEpochDay - epochDay
        if (difference == 0L) return "Today"
        if (difference == 1L) return "Yesterday"
        val date = CivilDate.fromEpochDay(epochDay)
        return "${CivilDays.weekdayName(epochDay)} ${date.day} ${monthName(date.month)}"
    }

    /** `7 Nov`, with the year only when it is not the current one. */
    fun shortDate(epochDay: Long, todayEpochDay: Long): String {
        val date = CivilDate.fromEpochDay(epochDay)
        val today = CivilDate.fromEpochDay(todayEpochDay)
        val month = monthName(date.month)
        return if (date.year == today.year) {
            "${date.day} $month"
        } else {
            "${date.day} $month ${date.year}"
        }
    }

    fun monthName(month: Int): String = MONTHS[(month - 1).coerceIn(0, 11)]

    private fun pad(value: Int): String = value.toString().padStart(2, '0')

    private val MONTHS = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )
}

/**
 * Friendly names for the services teal.fm records point at.
 *
 * A host is shown as-is when it is not one that deserves a name. The
 * dashboard reports where listening came from, so it must never pretend two
 * different sources are one.
 */
object ServiceNames {
    fun label(host: String?): String? {
        if (host.isNullOrBlank()) return null
        return when (host.removePrefix("www.")) {
            "music.apple.com" -> "Apple Music"
            "open.spotify.com", "spotify.com" -> "Spotify"
            "music.youtube.com", "youtube.com" -> "YouTube Music"
            "soundcloud.com", "m.soundcloud.com" -> "SoundCloud"
            "bandcamp.com" -> "Bandcamp"
            "last.fm", "www.last.fm" -> "Last.fm"
            "plyr.fm" -> "plyr.fm"
            "listenbrainz.org" -> "ListenBrainz"
            "tidal.com", "listen.tidal.com" -> "Tidal"
            "deezer.com" -> "Deezer"
            "navidrome.org" -> "Navidrome"
            else -> host.removePrefix("www.")
        }
    }
}
