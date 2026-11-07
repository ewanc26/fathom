package uk.ewancroft.fathom.shared.xrpc

import kotlin.test.Test
import kotlin.test.assertEquals

class XrpcQueryTest {

    @Test
    fun encodesReservedCharacters() {
        assertEquals("a%20b", XrpcQuery.encode("a b"))
        assertEquals("a%26b%3Dc", XrpcQuery.encode("a&b=c"))
        assertEquals("%C3%A9", XrpcQuery.encode("é"))
    }

    @Test
    fun leavesUnreservedCharactersAlone() {
        assertEquals("aZ0-._~", XrpcQuery.encode("aZ0-._~"))
    }

    @Test
    fun keepsParameterOrderAndSkipsNulls() {
        val query = XrpcQuery()
            .add("repo", "did:plc:abc")
            .add("cursor", null)
            .add("limit", 50)
            .build()
        assertEquals("repo=did%3Aplc%3Aabc&limit=50", query)
    }

    @Test
    fun suffixesOnlyWhenThereIsSomethingToAdd() {
        assertEquals("", XrpcQuery().suffix())
        assertEquals("?limit=5", XrpcQuery().add("limit", 5).suffix())
    }

    @Test
    fun capsThePageSize() {
        val query = listRecordsQuery(repo = "did:plc:abc", collection = "fm.teal.feed.play", limit = 500)
        assertEquals(100, query.queryValue("limit")?.toInt())
    }

    @Test
    fun buildsAListRecordsQuery() {
        val query = listRecordsQuery(
            repo = "did:plc:abc",
            collection = "fm.teal.feed.play",
            cursor = "3mzq",
            limit = 20,
            reverse = true,
        )
        // Query values travel encoded, so the assertion is on the wire form.
        assertEquals("did%3Aplc%3Aabc", query.queryValue("repo"))
        assertEquals("fm.teal.feed.play", query.queryValue("collection"))
        assertEquals("3mzq", query.queryValue("cursor"))
        assertEquals("20", query.queryValue("limit"))
        assertEquals("true", query.queryValue("reverse"))
    }

    @Test
    fun buildsABlobUrl() {
        assertEquals(
            "https://eurosky.social/xrpc/com.atproto.sync.getBlob?did=did%3Aplc%3Aabc&cid=bafkavatar",
            blobUrl("https://eurosky.social/", "did:plc:abc", "bafkavatar"),
        )
    }

    private fun String.queryValue(name: String): String? =
        split('&').firstOrNull { it.substringBefore('=') == name }?.substringAfter('=')
}
