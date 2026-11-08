package uk.ewancroft.fathom.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.ewancroft.fathom.shared.format.DurationText
import uk.ewancroft.fathom.shared.format.ServiceNames

/**
 * `fm.teal.feed.defs#artist` as it appears inside a play record.
 *
 * `artistName` is the only required field, and a real repo contains plays
 * whose artist array is present but empty, so every caller has to be able to
 * render a track with no artist.
 */
@Serializable
data class ArtistRef(
    val artistName: String = "",
    val artistMbId: String? = null,
) {
    fun isUsable(): Boolean = artistName.isNotBlank()
}

/**
 * An `fm.teal.feed.play` record.
 *
 * Field names and optionality follow the published lexicon, with two
 * deliberate additions:
 *
 * - `artistNames` is deprecated in the lexicon but is still what older
 *   clients wrote, and it is not always accompanied by `artists`, so both
 *   are decoded and [artistLine] prefers the current field.
 * - nothing is stored for `releaseDiscriminant` beyond what the record says.
 *   A deluxe edition and its original are different releases to a listener
 *   and the UI must not merge them by name alone.
 *
 * `duration` is the track length the submitting client declared. It is not a
 * measurement of time listened, and Fathom never presents it as one.
 */
@Serializable
data class Play(
    @SerialName("\$type") val type: String? = null,
    /**
     * The AT URI of the record envelope, not a lexicon field.
     *
     * It is the record's stable identity: two pages can overlap, and a cache
     * can be restored with the same play twice, but the URI is what makes it
     * one record. Null for a value decoded without an envelope.
     */
    val uri: String? = null,
    /** The CID of the record envelope; not a lexicon field. */
    val cid: String? = null,
    val trackName: String = "",
    val trackMbId: String? = null,
    val recordingMbId: String? = null,
    val duration: Int? = null,
    val artists: List<ArtistRef> = emptyList(),
    val artistNames: List<String> = emptyList(),
    val artistMbIds: List<String> = emptyList(),
    val releaseName: String? = null,
    val releaseMbId: String? = null,
    val isrc: String? = null,
    val originUri: String? = null,
    val musicServiceUri: String? = null,
    val submissionClientAgent: String? = null,
    val playedTime: String? = null,
    val trackDiscriminant: String? = null,
    val releaseDiscriminant: String? = null,
) {

    /** The track title, or a neutral placeholder for the empty records that exist in the wild. */
    fun title(): String = trackName.trim().ifBlank { "Untitled track" }

    /** Every artist name this record carries, in the order they appeared. */
    fun artistNames(): List<String> {
        val current = artists.mapNotNull { it.artistName.takeIf(String::isNotBlank) }
        if (current.isNotEmpty()) return current
        return artistNames.mapNotNull { it.takeIf(String::isNotBlank) }
    }

    /** `A, B & C`, or `Unknown artist`. */
    fun artistLine(): String {
        val names = artistNames()
        return when (names.size) {
            0 -> "Unknown artist"
            1 -> names.first()
            2 -> "${names[0]} & ${names[1]}"
            else -> names.dropLast(1).joinToString(", ") + " & " + names.last()
        }
    }

    /** The release name with its discriminant kept, because they are different releases. */
    fun releaseLine(): String {
        val name = releaseName?.trim().orEmpty()
        val discriminant = releaseDiscriminant?.trim().orEmpty()
        return when {
            name.isEmpty() -> ""
            discriminant.isEmpty() -> name
            else -> "$name ($discriminant)"
        }
    }

    /** A stable identity for "the same recording", for uniqueness counts. */
    fun trackKey(): String {
        val byId = recordingMbId ?: trackMbId
        if (!byId.isNullOrBlank()) return byId
        return artistLine().lowercase() + "\u0000" + trackName.trim().lowercase()
    }

    /** A stable identity for "the same release", or null when the record names no release. */
    fun releaseKey(): String? {
        val name = releaseName?.trim().orEmpty()
        if (name.isEmpty()) return null
        if (!releaseMbId.isNullOrBlank()) return releaseMbId
        return name.lowercase() + "\u0000" + releaseDiscriminant?.trim().orEmpty().lowercase()
    }

    /** True when this play names something a person can read. */
    fun hasTrack(): Boolean = trackName.isNotBlank()

    /** `3:42`, or `--:--` when the record declared no length. */
    fun durationText(): String = DurationText.track(duration)

    /** A friendly name for the service this came from, when one is known. */
    fun serviceLabel(): String? = ServiceNames.label(serviceHost())

    /**
     * The host of the service this play came from, lowercased and without
     * `www.`, or null when the record names no service.
     */
    fun serviceHost(): String? = hostOf(musicServiceUri) ?: hostOf(originUri)

    /** True when this play has a timestamp that can be placed on a calendar. */
    fun isDated(): Boolean = playedTime?.isNotBlank() == true

    companion object {
        /** Extracts a bare host from a URI without needing a full URI parser. */
        fun hostOf(uri: String?): String? {
            if (uri.isNullOrBlank()) return null
            var text = uri.trim()
            val schemeAt = text.indexOf("://")
            text = when {
                schemeAt >= 0 -> text.substring(schemeAt + 3)
                text.startsWith("//") -> text.substring(2)
                else -> text
            }
            val end = text.indexOfFirst { it == '/' || it == '?' || it == '#' }
            if (end >= 0) text = text.substring(0, end)
            if (text.contains('@')) text = text.substringAfter('@')
            text = text.substringBefore(':')
            text = text.lowercase().removePrefix("www.")
            return text.ifBlank { null }
        }
    }
}

/** One record as `com.atproto.repo.listRecords` returned it. */
data class RecordEntry<T>(
    val uri: String,
    val cid: String?,
    val value: T,
)

/**
 * A page of records, plus how much of it was usable.
 *
 * `skipped` counts records whose value did not decode. A repo can hold a
 * partially written or older-schema record, and dropping the whole page for
 * one of them would hide years of listening, so bad siblings are counted and
 * the rest are kept.
 */
data class RecordPage<T>(
    val records: List<RecordEntry<T>> = emptyList(),
    val cursor: String? = null,
    val skipped: Int = 0,
    val parsed: Boolean = false,
) {
    fun values(): List<T> = records.map { it.value }
}
