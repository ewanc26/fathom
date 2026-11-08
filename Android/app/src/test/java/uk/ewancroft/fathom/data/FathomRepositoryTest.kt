package uk.ewancroft.fathom.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.ewancroft.fathom.shared.cache.CachedHistory
import uk.ewancroft.fathom.shared.cache.HistoryCache
import uk.ewancroft.fathom.shared.model.Play
import uk.ewancroft.fathom.shared.model.ReadCheckpoint
import uk.ewancroft.fathom.shared.stats.Period
import uk.ewancroft.fathom.shared.xrpc.TealCollections

/**
 * The paging loop is where a failed page becomes a claim about the whole
 * history. These tests use a fake transport so that decision is checked
 * without a PDS: a 404 is an empty namespace, while a 400 is a page that was
 * rejected and must leave the read partial.
 */
class FathomRepositoryTest {

    @Test
    fun aBadRequestOnALaterPageLeavesTheReadPartial() = runTest {
        val client = FakeClient(
            playResponses = listOf(
                { playPage("One", cursor = "next") },
                { throw FathomBadRequestException("InvalidRequest: bad cursor") },
            ),
        )

        val dashboard = FathomRepository(client).load(
            identity = IDENTITY,
            period = Period.AllTime,
            nowEpochSeconds = NOW,
            tzOffsetMinutes = 0,
        )

        assertTrue(dashboard.sampled)
        assertEquals(1, dashboard.plays.size)
        assertNotNull(dashboard.readFailure)
        assertTrue(dashboard.readFailure!!.contains("bad cursor"))
    }

    @Test
    fun aRepeatedUriAcrossPagesCountsOnce() = runTest {
        val uri = "at://did:plc:test/fm.teal.feed.play/repeated"
        val client = FakeClient(
            playResponses = listOf(
                { playPage("One", cursor = "next", uri = uri) },
                { playPage("One again", cursor = null, uri = uri) },
            ),
        )

        val dashboard = FathomRepository(client).load(
            identity = IDENTITY,
            period = Period.AllTime,
            nowEpochSeconds = NOW,
            tzOffsetMinutes = 0,
        )

        assertEquals(1, dashboard.plays.size)
        assertEquals(uri, dashboard.plays.single().uri)
        assertFalse(dashboard.sampled)
    }

    @Test
    fun twoPlaysOfTheSameTrackWithDifferentUrisCountTwice() = runTest {
        val client = FakeClient(
            playResponses = listOf(
                { playPage("Same", cursor = "next", uri = "at://did:plc:test/fm.teal.feed.play/a") },
                { playPage("Same", cursor = null, uri = "at://did:plc:test/fm.teal.feed.play/b") },
            ),
        )

        val dashboard = FathomRepository(client).load(
            identity = IDENTITY,
            period = Period.AllTime,
            nowEpochSeconds = NOW,
            tzOffsetMinutes = 0,
        )

        assertEquals(2, dashboard.plays.size)
    }

    @Test
    fun aCompleteCheckpointStopsAtTheFirstKnownPage() = runTest {
        val uri = "at://did:plc:test/fm.teal.feed.play/known"
        val checkpoint = checkpoint(
            listOf(Play(trackName = "Known", playedTime = "2026-11-07T09:00:00Z", uri = uri)),
        )
        val client = FakeClient(
            playResponses = listOf(
                { playPage("Known", cursor = "older", uri = uri) },
            ),
        )

        val dashboard = FathomRepository(client).load(
            identity = IDENTITY,
            period = Period.AllTime,
            checkpoint = checkpoint,
            nowEpochSeconds = NOW,
            tzOffsetMinutes = 0,
        )

        assertEquals(1, client.playRequests)
        assertFalse(dashboard.sampled)
        assertEquals(1, dashboard.plays.size)
    }

    @Test
    fun newPlaysAreMergedBeforeTheKnownTail() = runTest {
        val knownUri = "at://did:plc:test/fm.teal.feed.play/known"
        val newUri = "at://did:plc:test/fm.teal.feed.play/new"
        val checkpoint = checkpoint(
            listOf(Play(trackName = "Known", playedTime = "2026-11-07T09:00:00Z", uri = knownUri)),
        )
        val client = FakeClient(
            playResponses = listOf(
                {
                    playPage(
                        "New",
                        cursor = "older",
                        uri = newUri,
                        playedTime = "2026-11-07T10:00:00Z",
                    )
                },
                { playPage("Known", cursor = null, uri = knownUri) },
            ),
        )

        val dashboard = FathomRepository(client).load(
            identity = IDENTITY,
            period = Period.AllTime,
            checkpoint = checkpoint,
            nowEpochSeconds = NOW,
            tzOffsetMinutes = 0,
        )

        assertEquals(2, client.playRequests)
        assertEquals(2, dashboard.plays.size)
        assertEquals("New", dashboard.plays.first().trackName)
        assertFalse(dashboard.sampled)
    }

    @Test
    fun aPageWithAMalformedSiblingIsNotAFullyKnownStop() = runTest {
        val uri = "at://did:plc:test/fm.teal.feed.play/known"
        val checkpoint = checkpoint(
            listOf(Play(trackName = "Known", playedTime = "2026-11-07T09:00:00Z", uri = uri)),
        )
        val client = FakeClient(
            playResponses = listOf(
                { playPage("Known", cursor = "older", uri = uri, malformedSibling = true) },
                { playPage("Known", cursor = null, uri = uri) },
            ),
        )

        val dashboard = FathomRepository(client).load(
            identity = IDENTITY,
            period = Period.AllTime,
            checkpoint = checkpoint,
            nowEpochSeconds = NOW,
            tzOffsetMinutes = 0,
        )

        assertEquals(2, client.playRequests)
        assertEquals(1, dashboard.skippedRecords)
        assertFalse(dashboard.sampled)
    }

    @Test
    fun aPartialCheckpointDoesNotStopTheRead() = runTest {
        val uri = "at://did:plc:test/fm.teal.feed.play/known"
        val checkpoint = checkpoint(
            listOf(Play(trackName = "Known", playedTime = "2026-11-07T09:00:00Z", uri = uri)),
            complete = false,
        )
        val client = FakeClient(
            playResponses = listOf(
                { playPage("Known", cursor = "older", uri = uri) },
                { playPage("Known again", cursor = null, uri = uri) },
            ),
        )

        val dashboard = FathomRepository(client).load(
            identity = IDENTITY,
            period = Period.AllTime,
            checkpoint = checkpoint,
            nowEpochSeconds = NOW,
            tzOffsetMinutes = 0,
        )

        assertEquals(2, client.playRequests)
        assertEquals(1, dashboard.plays.size)
        assertFalse(dashboard.sampled)
    }

    @Test
    fun readEverythingIgnoresTheCheckpointAndTakesTheFreshValue() = runTest {
        val uri = "at://did:plc:test/fm.teal.feed.play/known"
        val checkpoint = checkpoint(
            listOf(Play(trackName = "Old title", playedTime = "2026-11-07T09:00:00Z", uri = uri)),
        )
        val client = FakeClient(
            playResponses = listOf(
                { playPage("Rewritten", cursor = "older", uri = uri) },
                {
                    playPage(
                        "Tail",
                        cursor = null,
                        uri = "at://did:plc:test/fm.teal.feed.play/tail",
                    )
                },
            ),
        )

        val dashboard = FathomRepository(client).load(
            identity = IDENTITY,
            period = Period.AllTime,
            checkpoint = checkpoint,
            forceFull = true,
            nowEpochSeconds = NOW,
            tzOffsetMinutes = 0,
        )

        assertEquals(2, client.playRequests)
        assertEquals("Rewritten", dashboard.plays.first { it.uri == uri }.trackName)
        assertFalse(dashboard.sampled)
    }

    @Test
    fun aRestoredCacheIsDeduplicatedByUri() = runTest {
        val uri = "at://did:plc:test/fm.teal.feed.play/cached"
        val cached = CachedHistory(
            version = HistoryCache.VERSION,
            identity = IDENTITY,
            did = DID,
            plays = listOf(
                Play(trackName = "One", playedTime = "2026-11-07T09:00:00Z", uri = uri),
                Play(trackName = "One again", playedTime = "2026-11-07T09:01:00Z", uri = uri),
            ),
        )

        val dashboard = FathomRepository(FakeClient()).fromCache(
            cached = cached,
            period = Period.AllTime,
            nowEpochSeconds = NOW,
            tzOffsetMinutes = 0,
        )

        assertEquals(1, dashboard.plays.size)
        assertEquals(uri, dashboard.plays.single().uri)
    }

    @Test
    fun aMissingCollectionIsNotPartial() = runTest {
        val dashboard = FathomRepository(FakeClient()).load(
            identity = IDENTITY,
            period = Period.AllTime,
            nowEpochSeconds = NOW,
            tzOffsetMinutes = 0,
        )

        assertFalse(dashboard.sampled)
        assertTrue(dashboard.plays.isEmpty())
        assertNull(dashboard.readFailure)
    }

    private class FakeClient(
        private val playResponses: List<() -> String> = emptyList(),
    ) : TealReadClient {

        var playRequests: Int = 0
            private set

        override suspend fun resolveIdentity(input: String): String = DID

        override suspend fun pdsFor(did: String): String = PDS

        override suspend fun listRecords(
            pds: String,
            repo: String,
            collection: String,
            cursor: String?,
            limit: Int,
        ): String {
            if (collection in TealCollections.PLAYS) {
                val response = playResponses.getOrNull(playRequests)
                    ?: throw FathomNotFoundException("no more pages")
                playRequests++
                return response()
            }
            throw FathomNotFoundException("no profile records")
        }

        override suspend fun getRecord(
            pds: String,
            repo: String,
            collection: String,
            rkey: String,
        ): String? = null
    }

    private companion object {
        const val IDENTITY = "ewancroft.uk"
        const val DID = "did:plc:test"
        const val PDS = "https://pds.example"
        const val NOW = 1_794_070_932L

        fun checkpoint(plays: List<Play>, complete: Boolean = true): ReadCheckpoint =
            ReadCheckpoint(
                did = DID,
                plays = plays,
                skipped = 0,
                legacy = false,
                complete = complete,
            )

        fun playPage(
            track: String,
            cursor: String?,
            uri: String = "at://did:plc:test/fm.teal.feed.play/one",
            playedTime: String = "2026-11-07T09:00:00Z",
            malformedSibling: Boolean = false,
        ): String {
            val value = """{"trackName":"$track","artists":[{"artistName":"Artist"}],""" +
                """"playedTime":"$playedTime","duration":200}"""
            return buildString {
                append("""{"records":[{"uri":"$uri","cid":"bafy","value":""")
                append(value)
                append("}")
                if (malformedSibling) {
                    append(""",{"uri":"at://did:plc:test/fm.teal.feed.play/bad","cid":"bafy","value":{"trackName":5}}""")
                }
                append("]")
                if (cursor != null) append(""","cursor":"$cursor"""")
                append("}")
            }
        }
    }
}
