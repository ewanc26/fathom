package uk.ewancroft.fathom.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import uk.ewancroft.fathom.shared.model.Play
import uk.ewancroft.fathom.shared.model.TealProfile
import uk.ewancroft.fathom.shared.model.TealStatus
import uk.ewancroft.fathom.shared.time.IsoInstant

class TealJsonTest {

    @Test
    fun decodesARealPlayPage() {
        val page = TealJson.decodePlays(PLAY_PAGE)

        assertTrue(page.parsed)
        assertEquals(2, page.records.size)
        assertEquals(1, page.skipped)
        assertEquals("3mzqeqt4axqg3", page.cursor)

        val first = page.records.first().value
        assertEquals("aneurysm", first.title())
        assertEquals("SXCREDMANE & ZMAJOR", first.artistLine())
        assertEquals(110, first.duration)
        assertEquals("at://did:plc:x/fm.teal.feed.play/3mzqeselnybem", first.uri)
        assertEquals("bafyreibz", first.cid)
        assertEquals("aneurysm - Single", first.releaseLine())
        assertEquals("music.apple.com", first.serviceHost())
    }

    @Test
    fun keepsGoodRecordsWhenASiblingIsMalformed() {
        // The second record has a number where the lexicon says string. It
        // must cost exactly one play, not the page.
        val page = TealJson.decodePlays(PLAY_PAGE)
        assertEquals(2, page.records.size)
        assertEquals(1, page.skipped)
    }

    @Test
    fun reportsAnUnrecognisedBodyRatherThanAnEmptyRepo() {
        val page = TealJson.decodePlays("""{"error":"InvalidRequest","message":"bad"}""")
        assertFalse(page.parsed)
        assertTrue(page.records.isEmpty())
        assertEquals("InvalidRequest: bad", TealJson.decodeError("""{"error":"InvalidRequest","message":"bad"}"""))
    }

    @Test
    fun decodesAProfileWithABlobAvatar() {
        val page = TealJson.decodeProfiles(PROFILE_PAGE)
        val profile = page.records.single().value

        assertEquals("Ewan", profile.displayName)
        assertEquals("bafkreiavatar", profile.avatar?.cid())
        assertEquals("image/png", profile.avatar?.mimeType)
        assertEquals("artist", profile.featuredItem?.type)
    }

    @Test
    fun decodesAnExpiredEmptyStatus() {
        val entry = TealJson.decodeSingleRecord<TealStatus>(EMPTY_STATUS)
        assertNotNull(entry)

        val status = entry.value
        assertEquals("", status.item?.trackName)
        assertFalse(status.isListening(NOW, IsoInstant.parse(status.expiry)))
        assertFalse(status.isLive(NOW))
    }

    @Test
    fun decodesALiveStatus() {
        val entry = TealJson.decodeSingleRecord<TealStatus>(LIVE_STATUS)
        assertNotNull(entry)

        val status = entry.value
        assertTrue(status.isListening(NOW, IsoInstant.parse(status.expiry)))
        assertTrue(status.isLive(NOW))
        assertEquals("Cherry Wine", status.item?.title())
    }

    @Test
    fun fallsBackToTenMinutesAfterTimeWhenExpiryIsMissing() {
        val status = TealStatus(
            time = "2026-11-07T17:02:40Z",
            item = Play(trackName = "Cherry Wine"),
        )
        val time = IsoInstant.parse(status.time)!!

        assertTrue(status.isLive(time + 599))
        assertFalse(status.isLive(time + 600))
    }

    @Test
    fun doesNotClaimLivenessWithoutAnExpiryOrAReadableTime() {
        val status = TealStatus(item = Play(trackName = "No clock"))
        assertFalse(status.isLive(NOW))
    }

    @Test
    fun fallsBackToTheDeprecatedArtistArray() {
        val play = TealJson.decodePlays(
            """{"records":[{"uri":"at://a/b/c","value":{"trackName":"X","artistNames":["Old Client"]}}]}""",
        ).records.single().value

        assertEquals("Old Client", play.artistLine())
    }

    @Test
    fun rendersARecordWithNoArtistAtAll() {
        val play = Play(trackName = "")
        assertEquals("Untitled track", play.title())
        assertEquals("Unknown artist", play.artistLine())
        assertNull(play.releaseKey())
    }

    @Test
    fun keepsTheReleaseDiscriminant() {
        val play = Play(trackName = "Song", releaseName = "Album", releaseDiscriminant = "Deluxe Edition")
        assertEquals("Album (Deluxe Edition)", play.releaseLine())
    }

    private companion object {
        const val NOW = 1_794_070_932L

        val PLAY_PAGE = """
            {"records":[
              {"uri":"at://did:plc:x/fm.teal.feed.play/3mzqeselnybem",
               "cid":"bafyreibz","value":{"${'$'}type":"fm.teal.feed.play",
               "artists":[{"artistName":"SXCREDMANE & ZMAJOR"}],"duration":110,
               "trackName":"aneurysm","playedTime":"2026-11-07T17:02:12Z",
               "releaseName":"aneurysm - Single","musicServiceUri":"https://music.apple.com",
               "submissionClientAgent":"piper/v0.1.1"}},
              {"uri":"at://did:plc:x/fm.teal.feed.play/broken","value":{"trackName":5}},
              {"uri":"at://did:plc:x/fm.teal.feed.play/3mzqeqt4axqg3",
               "cid":"bafyreigt6","value":{"${'$'}type":"fm.teal.feed.play",
               "artists":[{"artistName":"ZMAJOR & zxroth"}],"duration":70,
               "trackName":"LUNATIC","playedTime":"2026-11-07T17:01:20Z"}}
            ],"cursor":"3mzqeqt4axqg3"}
        """.trimIndent()

        val PROFILE_PAGE = """
            {"records":[{"uri":"at://did:plc:x/fm.teal.actor.profile/self",
             "value":{"${'$'}type":"fm.teal.actor.profile","displayName":"Ewan",
             "avatar":{"${'$'}type":"blob","ref":{"${'$'}link":"bafkreiavatar"},
                       "mimeType":"image/png","size":12345},
             "featuredItem":{"mbid":"mbid:abc","type":"artist"}}}]}
        """.trimIndent()

        val EMPTY_STATUS = """
            {"uri":"at://did:plc:x/fm.teal.actor.status/self",
             "value":{"${'$'}type":"fm.teal.actor.status","item":{"artists":[],"trackName":""},
                      "time":"2026-11-07T17:02:40Z","expiry":"2026-11-07T17:01:40Z"}}
        """.trimIndent()

        val LIVE_STATUS = """
            {"uri":"at://did:plc:x/fm.teal.actor.status/self",
             "value":{"${'$'}type":"fm.teal.actor.status",
                      "item":{"trackName":"Cherry Wine","duration":240,
                              "artists":[{"artistName":"Hozier"}]},
                      "time":"2026-11-07T17:02:40Z","expiry":"2026-11-07T17:12:40Z"}}
        """.trimIndent()
    }
}
