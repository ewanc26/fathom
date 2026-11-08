package uk.ewancroft.fathom.shared.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import uk.ewancroft.fathom.shared.model.ArtistRef
import uk.ewancroft.fathom.shared.model.Play

class PlaySearchTest {

    @Test
    fun aBlankQueryReturnsEveryPlayInOrder() {
        val plays = listOf(play("One"), play("Two"), play("Three"))

        assertEquals(plays, PlaySearchIndex(plays).search(""))
        assertEquals(plays, PlaySearchIndex(plays).search("   "))
        assertEquals(plays, plays.filter { PlaySearch.matches(it, "") })
    }

    @Test
    fun matchingIgnoresCase() {
        val plays = listOf(play("Bad Guy"), play("everything i wanted"))

        assertEquals(listOf("Bad Guy"), PlaySearchIndex(plays).search("BAD").map { it.trackName })
    }

    @Test
    fun matchingIgnoresDiacriticsInEitherDirection() {
        val precomposed = play("Déjà Vu", artists = listOf("Beyoncé"))
        val decomposed = play("Sigur Rós", artists = listOf("Sigur Rós"))

        assertTrue(PlaySearch.matches(precomposed, "deja"))
        assertTrue(PlaySearch.matches(precomposed, "beyonce"))
        assertTrue(PlaySearch.matches(decomposed, "ros"))
        assertFalse(PlaySearch.matches(precomposed, "beyonca"))
    }

    @Test
    fun matchingHandlesDecomposedInput() {
        val play = play("Café", artists = listOf("Artist"))

        assertTrue(PlaySearch.matches(play, "cafe\u0301"))
        assertTrue(PlaySearch.matches(play, "cafe"))
    }

    @Test
    fun matchingSearchesEveryArtistAndTheRelease() {
        val play = play(
            track = "The Less I Know the Better",
            artists = listOf("Tame Impala"),
            release = "Currents",
        )

        assertTrue(PlaySearch.matches(play, "tame"))
        assertTrue(PlaySearch.matches(play, "currents"))
        assertTrue(PlaySearch.matches(play, "less know"))
        assertFalse(PlaySearch.matches(play, "lonerism"))
    }

    @Test
    fun matchingUsesTheDeprecatedArtistNamesWhenArtistsAreAbsent() {
        val play = Play(trackName = "Nightcall", artistNames = listOf("Kavinsky"))

        assertTrue(PlaySearch.matches(play, "kavinsky"))
        assertEquals(listOf(play), PlaySearchIndex(listOf(play)).search("kav"))
    }

    @Test
    fun aMultiWordQueryMatchesAcrossFields() {
        val play = play("Lemonade", artists = listOf("Beyoncé"), release = "Lemonade")

        assertTrue(PlaySearch.matches(play, "beyonce lemonade"))
        assertTrue(PlaySearch.matches(play, "lemonade beyonce"))
        assertFalse(PlaySearch.matches(play, "beyonce renaissance"))
    }

    @Test
    fun theIndexReturnsOnlyMatchesInTheOriginalOrder() {
        val plays = listOf(
            play("Alpha", artists = listOf("One")),
            play("Beta", artists = listOf("Two")),
            play("Alphabet", artists = listOf("Three")),
        )

        val index = PlaySearchIndex(plays)

        assertEquals(listOf("Alpha", "Alphabet"), index.search("alpha").map { it.trackName })
        assertEquals(listOf("Alpha"), index.search("one").map { it.trackName })
    }

    @Test
    fun aQueryWithNoMatchesReturnsAnEmptyList() {
        val index = PlaySearchIndex(listOf(play("One"), play("Two")))

        assertTrue(index.search("nothing here").isEmpty())
    }

    @Test
    fun foldingKeepsTheCharactersThatMakeTitlesDistinct() {
        assertEquals("don't stop", PlaySearch.fold("Don't Stop"))
        assertEquals("ae oe ss", PlaySearch.fold("Æ Œ ß"))
    }

    private fun play(
        track: String,
        artists: List<String> = listOf("Artist"),
        release: String? = null,
    ): Play = Play(
        trackName = track,
        artists = artists.map { ArtistRef(artistName = it) },
        releaseName = release,
    )
}
