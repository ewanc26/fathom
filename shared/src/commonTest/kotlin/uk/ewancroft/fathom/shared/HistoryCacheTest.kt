package uk.ewancroft.fathom.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import uk.ewancroft.fathom.shared.cache.CachedHistory
import uk.ewancroft.fathom.shared.cache.HistoryCache
import uk.ewancroft.fathom.shared.model.ArtistRef
import uk.ewancroft.fathom.shared.model.Play
import uk.ewancroft.fathom.shared.model.TealProfile
import uk.ewancroft.fathom.shared.model.TealStatus

/**
 * The cache is the only thing Fathom writes, so it is the only thing that can
 * come back corrupted. These cover the round trip and the three ways a file
 * stops being usable: another version, truncation, and no identity to match.
 */
class HistoryCacheTest {

    @Test
    fun writesAndReadsBackAWholeHistory() {
        val entry = CachedHistory(
            identity = "ewancroft.uk",
            did = "did:plc:ofrbh253gwicbkc5nktqepol",
            handle = "ewancroft.uk",
            profile = TealProfile(displayName = "Ewan Croft"),
            status = TealStatus(time = "2026-11-07T17:02:12Z"),
            plays = listOf(
                Play(
                    trackName = "aneurysm",
                    artists = listOf(ArtistRef(artistName = "SXCREDMANE & ZMAJOR")),
                    duration = 110,
                    playedTime = "2026-11-07T17:02:12Z",
                    releaseName = "aneurysm - Single",
                    musicServiceUri = "https://music.apple.com",
                ),
                Play(trackName = "rain", playedTime = "2026-11-07T17:04:00Z"),
            ),
            skipped = 2,
            legacy = true,
            sampled = true,
            savedAtEpochSeconds = 1_794_070_932,
        )

        val decoded = HistoryCache.decode(HistoryCache.encode(entry))

        assertNotNull(decoded)
        assertEquals(entry, decoded)
        assertEquals(2, decoded.plays.size)
        assertEquals(true, decoded.sampled)
        assertEquals("aneurysm", decoded.plays.first().trackName)
        assertEquals("SXCREDMANE & ZMAJOR", decoded.plays.first().artistLine())
    }

    @Test
    fun rejectsAFileWrittenByAnotherVersion() {
        val json = """{"version":99,"identity":"ewancroft.uk","did":"did:plc:x"}"""
        assertNull(HistoryCache.decode(json))
    }

    @Test
    fun rejectsATruncatedFile() {
        val json = """{"version":1,"identity":"ewancroft.uk","did":"did:plc:x""""
        assertNull(HistoryCache.decode(json))
    }

    @Test
    fun rejectsAnEntryThatCannotBeMatchedToAnAccount() {
        val json = """{"version":1,"identity":"","did":"did:plc:x"}"""
        assertNull(HistoryCache.decode(json))
    }

    @Test
    fun toleratesKeysItDoesNotKnow() {
        val json = """{"version":1,"identity":"ewancroft.uk","did":"did:plc:x","future":"field"}"""
        val decoded = HistoryCache.decode(json)
        assertNotNull(decoded)
        assertEquals("ewancroft.uk", decoded.identity)
    }
}
