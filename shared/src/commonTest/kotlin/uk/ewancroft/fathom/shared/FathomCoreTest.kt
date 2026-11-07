package uk.ewancroft.fathom.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import uk.ewancroft.fathom.shared.xrpc.listRecordsQuery

/**
 * The Swift facade is supposed to delegate to the core, never reimplement it.
 *
 * A method that calls itself looks exactly like a working one until it runs:
 * `FathomCore.listRecordsQuery` was written without the package qualifier, so
 * "delegating" recursed until the stack overflowed, and the first run against
 * a real account crashed on the first network read. These tests exercise the
 * facade's transport helpers so that mistake fails here instead.
 */
class FathomCoreTest {

    @Test
    fun buildsTheSameFirstPageQueryAsTheCore() {
        assertEquals(
            listRecordsQuery("did:plc:x", "fm.teal.feed.play", null, 100),
            FathomCore.listRecordsQuery("did:plc:x", "fm.teal.feed.play", null, 100),
        )
    }

    @Test
    fun carriesACursorThroughTheFacade() {
        val query = FathomCore.listRecordsQuery("did:plc:x", "fm.teal.feed.play", "3mzq", 100)
        assertTrue(query.contains("cursor=3mzq"), "the cursor should survive: $query")
        assertTrue(query.contains("limit=100"), "the page size should survive: $query")
    }

    @Test
    fun exposesThePathsAndCeilingsBothClientsUse() {
        assertEquals("/xrpc/com.atproto.repo.listRecords", FathomCore.listRecordsPath())
        assertEquals("/xrpc/com.atproto.repo.getRecord", FathomCore.getRecordPath())
        assertEquals(100, FathomCore.maxPage())
        assertEquals(50_000, FathomCore.playCeiling())
    }

    @Test
    fun roundTripsAHistoryThroughTheFacade() {
        val json = FathomCore.encodeHistory(
            identity = "ewancroft.uk",
            did = "did:plc:ofrbh253gwicbkc5nktqepol",
            handle = "ewancroft.uk",
            profile = null,
            status = null,
            plays = emptyList(),
            skipped = 0,
            legacy = false,
            sampled = true,
            savedAtEpochSeconds = 1_794_070_932,
        )

        val decoded = FathomCore.decodeHistory(json = json)
        assertEquals("ewancroft.uk", decoded?.identity)
        assertEquals("did:plc:ofrbh253gwicbkc5nktqepol", decoded?.did)
        assertEquals(true, decoded?.sampled, "a partial read must stay labelled partial")
        assertEquals(1_794_070_932, decoded?.savedAtEpochSeconds)
    }
}
