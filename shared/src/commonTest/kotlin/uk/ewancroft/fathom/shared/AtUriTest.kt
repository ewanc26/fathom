package uk.ewancroft.fathom.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtUriTest {

    @Test
    fun parsesARecordUri() {
        val uri = AtUri.parse("at://did:plc:abc123/fm.teal.feed.play/3mzqeselnybem")
        assertEquals("did:plc:abc123", uri?.authority)
        assertEquals("fm.teal.feed.play", uri?.collection)
        assertEquals("3mzqeselnybem", uri?.recordKey)
        assertEquals("at://did:plc:abc123/fm.teal.feed.play/3mzqeselnybem", uri?.toUri())
    }

    @Test
    fun parsesAnActorUri() {
        val uri = AtUri.parse("at://did:plc:abc123")
        assertEquals("did:plc:abc123", uri?.authority)
        assertNull(uri?.collection)
        assertNull(uri?.recordKey)
    }

    @Test
    fun parsesACollectionUri() {
        val uri = AtUri.parse("at://alice.test/fm.teal.feed.play")
        assertEquals("alice.test", uri?.authority)
        assertEquals("fm.teal.feed.play", uri?.collection)
        assertNull(uri?.recordKey)
    }

    @Test
    fun rejectsMalformedValues() {
        assertNull(AtUri.parse(null))
        assertNull(AtUri.parse(""))
        assertNull(AtUri.parse("at://"))
        assertNull(AtUri.parse("https://example.com/x"))
        assertNull(AtUri.parse("at://did:plc:abc123/fm.teal.feed.play/3mzq/extra"))
        assertNull(AtUri.parse("at://did:plc:abc123/fm.teal.feed.play/"))
        // A collection needs three segments; two is not an NSID.
        assertNull(AtUri.parse("at://did:plc:abc123/fmteal/3mzq"))
        assertNull(AtUri.parse("at://did:plc:abc123/fm.teal.feed.play/3mzq?x=1"))
        // Three short segments is a valid NSID shape, whatever it means.
        assertTrue(AtUri.isCollection("a.b.c"))
    }

    @Test
    fun recognisesIdentifiers() {
        assertTrue(AtUri.isCollection("fm.teal.feed.play"))
        assertTrue(!AtUri.isCollection("fm.teal"))
        assertTrue(!AtUri.isCollection("fm..play"))
        assertTrue(!AtUri.isCollection("fm.teal.4play"))
        assertTrue(AtUri.isHandle("eurosky.social"))
        assertTrue(!AtUri.isHandle("no-dot"))
        assertTrue(AtUri.isAuthority("did:plc:iwhuynr6mm6xxuh25o4do2tx"))
    }
}
