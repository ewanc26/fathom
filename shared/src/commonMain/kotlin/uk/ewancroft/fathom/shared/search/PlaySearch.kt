package uk.ewancroft.fathom.shared.search

import uk.ewancroft.fathom.shared.model.Play

/**
 * A searchable view over the plays already in memory.
 *
 * History can hold tens of thousands of plays, and a query is typed one
 * character at a time. Folding every track, artist and release on each
 * keystroke would repeat the same work for the same records, so the folded
 * text is built once when the index is created and only scanned afterwards.
 *
 * The index never reads the network: a search is a question about what Fathom
 * has already read, not a reason to read the repo again.
 */
class PlaySearchIndex(plays: List<Play>) {

    private data class Entry(val play: Play, val haystack: String)

    private val entries: List<Entry> = plays.map { Entry(it, PlaySearch.haystack(it)) }

    /**
     * The plays whose track, artists or release contain every term in
     * [query]. A blank query returns every play, in the order supplied.
     */
    fun search(query: String): List<Play> {
        val terms = PlaySearch.terms(query)
        if (terms.isEmpty()) return entries.map { it.play }
        return entries
            .filter { entry -> terms.all { term -> entry.haystack.contains(term) } }
            .map { it.play }
    }
}

/**
 * The one definition of a History match.
 *
 * Matching is case- and diacritic-insensitive, and a multi-word query is a
 * conjunction: `beyonce lemonade` finds a Lemonade play by Beyoncé even
 * though no single field contains the whole phrase. Both apps use this
 * through [PlaySearchIndex], so the same query returns the same plays on each.
 */
object PlaySearch {

    /** Lowercase [text] and fold common Latin diacritics to their base letters. */
    fun fold(text: String): String = buildString(text.length) {
        for (character in text.lowercase()) append(fold(character))
    }

    /** The folded, non-blank terms in [query]. */
    fun terms(query: String): List<String> =
        fold(query)
            .split(' ', '\t', '\n', '\r')
            .filter { it.isNotBlank() }

    /** True when [play] contains every term in [query]. */
    fun matches(play: Play, query: String): Boolean {
        val terms = terms(query)
        if (terms.isEmpty()) return true
        val haystack = haystack(play)
        return terms.all { haystack.contains(it) }
    }

    /**
     * The folded text a query is matched against.
     *
     * Fields are separated by NUL so a term can never be assembled from the
     * end of one field and the start of another. The deprecated
     * `artistNames` list is included through [Play.artistNames], which already
     * prefers the current `artists` field when both are present.
     */
    internal fun haystack(play: Play): String = buildString {
        append(fold(play.trackName))
        append('\u0000')
        for (artist in play.artistNames()) {
            append(fold(artist))
            append('\u0000')
        }
        append(fold(play.releaseName.orEmpty()))
        append('\u0000')
        append(fold(play.releaseDiscriminant.orEmpty()))
    }

    private fun fold(character: Char): String = when (character) {
        in 'a'..'z', in '0'..'9' -> character.toString()

        'à', 'á', 'â', 'ã', 'ä', 'å', 'ā', 'ă', 'ą' -> "a"
        'ç', 'ć', 'ĉ', 'ċ', 'č' -> "c"
        'ď', 'đ', 'ð' -> "d"
        'è', 'é', 'ê', 'ë', 'ē', 'ĕ', 'ė', 'ę', 'ě' -> "e"
        'ĝ', 'ğ', 'ġ', 'ģ' -> "g"
        'ĥ', 'ħ' -> "h"
        'ì', 'í', 'î', 'ï', 'ĩ', 'ī', 'ĭ', 'į', 'ı' -> "i"
        'ĵ' -> "j"
        'ķ' -> "k"
        'ĺ', 'ļ', 'ľ', 'ł' -> "l"
        'ñ', 'ń', 'ņ', 'ň', 'ŉ' -> "n"
        'ò', 'ó', 'ô', 'õ', 'ö', 'ō', 'ŏ', 'ő', 'ø' -> "o"
        'ŕ', 'ŗ', 'ř' -> "r"
        'ś', 'ŝ', 'ş', 'š' -> "s"
        'ţ', 'ť', 'ŧ' -> "t"
        'ù', 'ú', 'û', 'ü', 'ũ', 'ū', 'ŭ', 'ů', 'ű', 'ų' -> "u"
        'ŵ' -> "w"
        'ý', 'ÿ', 'ŷ' -> "y"
        'ź', 'ż', 'ž' -> "z"

        'æ' -> "ae"
        'œ' -> "oe"
        'ß' -> "ss"
        'þ' -> "th"
        // Combining marks left behind by decomposed input. Removing them is
        // what makes "Beyonce" match a record written as "Beyonce" + U+0301.
        in '\u0300'..'\u036F',
        in '\u1AB0'..'\u1AFF',
        in '\u1DC0'..'\u1DFF',
        in '\u20D0'..'\u20FF',
        in '\uFE20'..'\uFE2F',
        -> ""

        else -> character.toString()
    }
}
