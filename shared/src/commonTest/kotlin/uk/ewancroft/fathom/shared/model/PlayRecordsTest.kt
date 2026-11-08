package uk.ewancroft.fathom.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayRecordsTest {

    @Test
    fun keepsTheFirstCopyOfARepeatedUri() {
        val first = Play(trackName = "One", uri = "at://did:plc:x/fm.teal.feed.play/a")
        val second = Play(trackName = "One again", uri = "at://did:plc:x/fm.teal.feed.play/a")

        assertEquals(listOf(first), PlayRecords.distinct(listOf(first, second)))
    }

    @Test
    fun keepsPlaysThatHaveNoUri() {
        val first = Play(trackName = "One")
        val second = Play(trackName = "One again")

        assertEquals(listOf(first, second), PlayRecords.distinct(listOf(first, second)))
    }

    @Test
    fun keepsTwoPlaysOfTheSameTrackWithDifferentUris() {
        val first = Play(trackName = "One", uri = "at://did:plc:x/fm.teal.feed.play/a")
        val second = Play(trackName = "One", uri = "at://did:plc:x/fm.teal.feed.play/b")

        assertEquals(listOf(first, second), PlayRecords.distinct(listOf(first, second)))
    }

    @Test
    fun resumesOnlyFromACompleteIdentifiedHistory() {
        val identified = listOf(Play(uri = "at://did:plc:x/fm.teal.feed.play/a"))

        assertTrue(PlayRecords.canResume(identified, complete = true))
        assertFalse(PlayRecords.canResume(identified, complete = false))
        assertFalse(PlayRecords.canResume(emptyList(), complete = true))
        assertFalse(PlayRecords.canResume(listOf(Play(trackName = "No identity")), complete = true))
    }

    @Test
    fun mergesOverlapAndKeepsOnlyNewRecords() {
        val known = Play(trackName = "Known", uri = "at://did:plc:x/fm.teal.feed.play/known")
        val fresh = Play(trackName = "New", uri = "at://did:plc:x/fm.teal.feed.play/new")
        val merger = PlayMerger(listOf(known))

        val merge = merger.addPage(listOf(known.copy(trackName = "Known fresh"), fresh))

        assertEquals(1, merge.newRecords)
        assertFalse(merge.pageWasFullyKnown)
        assertEquals(2, merger.size)
        assertEquals("Known fresh", merger.plays.first { it.uri == known.uri }.trackName)
        assertTrue(merger.plays.contains(fresh))
    }

    @Test
    fun stopsAtAFullyKnownPageAndTakesItsFreshValues() {
        val known = Play(trackName = "Old title", uri = "at://did:plc:x/fm.teal.feed.play/a")
        val merger = PlayMerger(listOf(known))

        val merge = merger.addPage(listOf(known.copy(trackName = "Rewritten title")))

        assertEquals(0, merge.newRecords)
        assertTrue(merge.pageWasFullyKnown)
        assertEquals("Rewritten title", merger.plays.single().trackName)
    }

    @Test
    fun anEmptyPageIsNotAFullyKnownStop() {
        val merger = PlayMerger(listOf(Play(uri = "at://did:plc:x/fm.teal.feed.play/a")))

        assertFalse(merger.addPage(emptyList()).pageWasFullyKnown)
    }

    @Test
    fun anUnidentifiedRecordIsNotAFullyKnownStop() {
        val merger = PlayMerger(listOf(Play(uri = "at://did:plc:x/fm.teal.feed.play/a")))

        val merge = merger.addPage(listOf(Play(trackName = "No identity")))

        assertFalse(merge.pageWasFullyKnown)
        assertEquals(1, merge.newRecords)
    }
}
