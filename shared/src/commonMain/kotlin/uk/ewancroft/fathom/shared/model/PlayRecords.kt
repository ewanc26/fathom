package uk.ewancroft.fathom.shared.model

/**
 * A bounded read that can be used as the starting point for an incremental
 * refresh.
 *
 * [complete] is the inverse of the cache's `sampled` flag. Only a complete
 * read can be a stopping point: a partial read does not cover the older
 * history, so treating its first page as overlap would hide records.
 */
data class ReadCheckpoint(
    val did: String,
    val plays: List<Play>,
    val skipped: Int = 0,
    val legacy: Boolean = false,
    val complete: Boolean = false,
)

/** What one page contributed to a [PlayMerger]. */
data class PageMerge(
    val newRecords: Int,
    val pageWasFullyKnown: Boolean,
)

/**
 * A play history being assembled from pages, optionally seeded with a saved
 * read.
 *
 * The seed is not merely a cache of values: it is the known tail of the
 * history. A refresh reads newest-first and can stop once a whole page was
 * already known, because everything beneath that page is the saved history.
 * A page with even one new or unidentified record cannot be that stopping
 * point.
 */
class PlayMerger(known: List<Play>) {
    private val byUri = LinkedHashMap<String, Play>()
    private val withoutUri = ArrayList<Play>()

    init {
        for (play in known) {
            val uri = PlayRecords.uriOf(play)
            if (uri == null) withoutUri += play else byUri[uri] = play
        }
    }

    /** Every play merged so far, with each URI represented once. */
    val plays: List<Play>
        get() {
            val merged = ArrayList<Play>(withoutUri.size + byUri.size)
            merged.addAll(withoutUri)
            merged.addAll(byUri.values)
            return merged
        }

    val size: Int
        get() = withoutUri.size + byUri.size

    /**
     * Adds [page], replacing known records with their freshly read values.
     *
     * A page is fully known only when it has at least one usable record and
     * every record on it already had a URI. A page of only malformed records
     * is not a safe stop either, because it cannot prove that the saved tail
     * begins beneath it.
     */
    fun addPage(page: List<Play>): PageMerge {
        var newRecords = 0
        var usable = 0
        var allKnown = true
        for (play in page) {
            usable++
            val uri = PlayRecords.uriOf(play)
            if (uri == null) {
                withoutUri += play
                newRecords++
                allKnown = false
            } else if (byUri.containsKey(uri)) {
                byUri[uri] = play
            } else {
                byUri[uri] = play
                newRecords++
                allKnown = false
            }
        }
        return PageMerge(
            newRecords = newRecords,
            pageWasFullyKnown = usable > 0 && allKnown,
        )
    }
}

/**
 * Record identity for plays.
 *
 * A play's track and time are not identity: the same track can legitimately
 * be played twice, and one record can appear on two overlapping pages. The AT
 * URI is the only stable identity. Plays from a cache written before URIs
 * were stored have none, so they are kept rather than guessed at.
 */
object PlayRecords {

    /** The record's AT URI, or null when the value arrived without an envelope. */
    fun uriOf(play: Play): String? = play.uri?.takeIf { it.isNotBlank() }

    /**
     * Keeps the first play for each AT URI.
     *
     * Plays without a URI are all kept: without identity, dropping one would
     * be a guess, and a duplicated play is a smaller error than a lost one.
     */
    fun distinct(plays: List<Play>): List<Play> {
        val seen = HashSet<String>(plays.size)
        val distinct = ArrayList<Play>(plays.size)
        for (play in plays) {
            val uri = uriOf(play)
            if (uri == null || seen.add(uri)) distinct += play
        }
        return distinct
    }

    /**
     * True when [plays] came from a complete read and every record has an AT
     * URI. Only then can a fully-known page prove where the saved tail starts.
     */
    fun canResume(plays: List<Play>, complete: Boolean): Boolean =
        complete && plays.isNotEmpty() && plays.all { uriOf(it) != null }
}
