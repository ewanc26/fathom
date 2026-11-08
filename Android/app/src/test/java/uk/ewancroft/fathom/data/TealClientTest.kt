package uk.ewancroft.fathom.data

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transport decides whether a status code means "not there" or "could not
 * read it". That distinction is what keeps a partial history honestly partial,
 * so these tests drive it with a fake OkHttp interceptor rather than a PDS.
 */
class TealClientTest {

    @Test
    fun namesAnUnsupportedDidMethodInTheError() = runTest {
        val failure = runCatching {
            TealClient().pdsFor("did:key:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK")
        }.exceptionOrNull()

        assertTrue(failure is FathomException)
        assertTrue(failure?.message?.contains("did:key:z6Mk") == true)
    }

    @Test
    fun aBadRequestFromAListIsNotAbsence() = runTest {
        val fake = FakeHttp(
            listOf({
                response(
                    400,
                    """{"error":"InvalidRequest","message":"bad cursor"}""",
                )
            }),
        )

        val failure = runCatching {
            TealClient(fake.client()).listRecords(PDS, DID, COLLECTION, "bad", 100)
        }.exceptionOrNull()

        assertTrue(failure is FathomBadRequestException)
        assertTrue(failure?.message?.contains("bad cursor") == true)
        assertEquals(1, fake.requests)
    }

    @Test
    fun aMissingListIsAbsenceAndIsNotRetried() = runTest {
        val fake = FakeHttp(listOf({ response(404, "{}") }))

        val failure = runCatching {
            TealClient(fake.client()).listRecords(PDS, DID, COLLECTION, null, 100)
        }.exceptionOrNull()

        assertTrue(failure is FathomNotFoundException)
        assertEquals(1, fake.requests)
    }

    @Test
    fun aBadRequestFromGetRecordIsAbsence() = runTest {
        val fake = FakeHttp(listOf({ response(400, "{}") }))

        val body = TealClient(fake.client())
            .getRecord(PDS, DID, "fm.teal.actor.status", "self")

        assertNull(body)
        assertEquals(1, fake.requests)
    }

    @Test
    fun retriesARateLimitAndThenSucceeds() = runTest {
        val fake = FakeHttp(
            listOf(
                { response(429, "{}", retryAfter = "0") },
                { response(200, """{"records":[],"cursor":null}""") },
            ),
        )

        val body = TealClient(fake.client())
            .listRecords(PDS, DID, COLLECTION, null, 100)

        assertTrue(body.contains("\"records\""))
        assertEquals(2, fake.requests)
    }

    @Test
    fun stopsAfterThreeRetriesAndKeepsTheFailure() = runTest {
        val fake = FakeHttp(
            List(4) { { response(500, "{}", retryAfter = "0") } },
        )

        val failure = runCatching {
            TealClient(fake.client()).listRecords(PDS, DID, COLLECTION, null, 100)
        }.exceptionOrNull()

        assertTrue(failure is FathomException)
        assertEquals(4, fake.requests)
    }

    private class FakeHttp(
        private val responses: List<() -> Response>,
    ) : Interceptor {

        var requests: Int = 0
            private set

        override fun intercept(chain: Interceptor.Chain): Response {
            val response = responses.getOrElse(requests) { responses.last() }.invoke()
            requests++
            return response.newBuilder().request(chain.request()).build()
        }

        fun client(): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(this)
            .build()
    }

    private companion object {
        const val PDS = "https://pds.example"
        const val DID = "did:plc:test"
        const val COLLECTION = "fm.teal.feed.play"

        fun response(code: Int, body: String, retryAfter: String? = null): Response {
            val builder = Response.Builder()
                .request(Request.Builder().url("$PDS/xrpc/test").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(body.toResponseBody("application/json".toMediaType()))
            if (retryAfter != null) builder.header("Retry-After", retryAfter)
            return builder.build()
        }
    }
}
