package uk.ewancroft.fathom.data

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import uk.ewancroft.fathom.shared.TealJson
import uk.ewancroft.fathom.shared.xrpc.RetryPolicy
import uk.ewancroft.fathom.shared.xrpc.Xrpc
import uk.ewancroft.fathom.shared.xrpc.blobUrl
import uk.ewancroft.fathom.shared.xrpc.listRecordsQuery

/** Something the person using the app can act on. Never a stack trace. */
open class FathomException(message: String) : Exception(message)

/**
 * Absence: the requested record or collection is not there.
 *
 * A 404 from any read means this, and so does a 400 from `getRecord`, where a
 * missing `literal:self` record is a normal state rather than a malformed
 * request. Paging must not treat any other 400 as absence.
 */
class FathomNotFoundException(message: String) : FathomException(message)

/**
 * A 400 from a listing or an identity lookup: the request itself was rejected.
 *
 * AT Protocol also uses 400 for an invalid cursor. Treating that as an empty
 * collection would report a complete history that stopped at page N, so this
 * travels separately and leaves the read partial.
 */
class FathomBadRequestException(message: String) : FathomException(message)

/**
 * Every HTTP call Fathom makes.
 *
 * Three services are involved and they are not interchangeable:
 *
 * - the public Bluesky AppView, used only for handle → DID resolution;
 * - `plc.directory` (or a `did:web` host), used only for DID → PDS;
 * - the account's own PDS, which holds the teal.fm records and is the only
 *   source of the dashboard's figures.
 *
 * Reads are unauthenticated because every record involved is public. That is
 * why Fathom needs no sign-in, no tokens and no secure storage.
 */
class TealClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build(),
) : TealReadClient {

    /** The DID for a handle, or the input unchanged when it is already a DID. */
    override suspend fun resolveIdentity(input: String): String {
        val text = input.trim().removePrefix("@")
        if (text.isEmpty()) throw FathomException("Enter a handle or a DID.")
        if (text.startsWith("did:")) return text

        val body = get(
            PUBLIC_APPVIEW + Xrpc.path(Xrpc.RESOLVE_HANDLE) +
                "?handle=" + uk.ewancroft.fathom.shared.xrpc.XrpcQuery.encode(text),
            notFound = "$text is not an account on the network.",
        )
        return TealJson.decodeDid(body)
            ?: throw FathomException("$text did not resolve to an account.")
    }

    /** The PDS that hosts [did]'s repo. */
    override suspend fun pdsFor(did: String): String {
        val documentUrl = when {
            did.startsWith("did:plc:") -> "$PLC_DIRECTORY/$did"
            did.startsWith("did:web:") -> {
                val host = did.removePrefix("did:web:").replace("%3A", ":").replace(':', '/')
                "https://$host/.well-known/did.json"
            }
            else -> throw FathomException("Fathom cannot look up $did.")
        }
        val body = get(documentUrl, notFound = "No identity document for this account.")
        return TealJson.decodePdsEndpoint(body)
            ?: throw FathomException("This account does not publish a data server.")
    }

    /** One page of `com.atproto.repo.listRecords`, as raw JSON for [TealJson]. */
    override suspend fun listRecords(
        pds: String,
        repo: String,
        collection: String,
        cursor: String?,
        limit: Int,
    ): String = get(
        pds + Xrpc.path(Xrpc.LIST_RECORDS) + "?" +
            listRecordsQuery(repo = repo, collection = collection, cursor = cursor, limit = limit),
        notFound = "This account has no $collection records.",
    )

    /**
     * A single record at a known key, or null when it does not exist.
     *
     * A missing `literal:self` record is the normal state for a status and
     * for an unedited profile, so a 400 or 404 there is an answer rather than
     * a failure. That is the one read where a 400 is treated as absence.
     */
    override suspend fun getRecord(
        pds: String,
        repo: String,
        collection: String,
        rkey: String,
    ): String? {
        val url = pds + Xrpc.path(Xrpc.GET_RECORD) + "?" +
            uk.ewancroft.fathom.shared.xrpc.XrpcQuery()
                .add("repo", repo)
                .add("collection", collection)
                .add("rkey", rkey)
                .build()
        return try {
            get(url, notFound = null, badRequestIsAbsence = true)
        } catch (notFound: FathomNotFoundException) {
            null
        }
    }

    /** The URL an avatar blob is served from, or null when the record carries none. */
    fun avatarUrl(pds: String, did: String, cid: String?): String? =
        cid?.let { blobUrl(pds, did, it) }

    /**
     * One GET, with bounded retries for transient failures.
     *
     * A 429 or 5xx is answered with up to three more attempts, waiting for
     * `Retry-After` when the server supplies one. A 400 or 404 is never
     * retried: the first means the request was rejected, and the second is an
     * answer. `badRequestIsAbsence` is only true for `getRecord`.
     */
    private suspend fun get(
        url: String,
        notFound: String?,
        badRequestIsAbsence: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        var attempt = 0

        while (true) {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            val response = try {
                http.newCall(request).execute()
            } catch (failure: IOException) {
                if (attempt + 1 < RetryPolicy.MAX_ATTEMPTS) {
                    delay(RetryPolicy.delayMillis(attempt, nowEpochSeconds = nowSeconds()))
                    attempt++
                    continue
                }
                throw FathomException(
                    "Could not reach the network. ${failure.message.orEmpty()}".trim(),
                )
            }

            var code = 0
            var body = ""
            var retryAfter: String? = null
            response.use {
                code = it.code
                body = it.body?.string().orEmpty()
                retryAfter = it.header("Retry-After")
            }

            if (code in 200..299) return@withContext body

            if (RetryPolicy.isRetryableStatus(code) &&
                attempt + 1 < RetryPolicy.MAX_ATTEMPTS
            ) {
                delay(RetryPolicy.delayMillis(attempt, retryAfter, nowSeconds()))
                attempt++
                continue
            }

            val detail = TealJson.decodeError(body)

            if (code == 404 || (code == 400 && badRequestIsAbsence)) {
                throw FathomNotFoundException(
                    notFound ?: detail ?: "That record does not exist.",
                )
            }
            if (code == 400) {
                throw FathomBadRequestException(
                    detail ?: "The server rejected the request.",
                )
            }
            throw FathomException(
                detail ?: "The server answered $code. Try again in a moment.",
            )
        }

        error("the retry loop always returns or throws")
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1_000L

    private companion object {
        const val PUBLIC_APPVIEW = "https://public.api.bsky.app"
        const val PLC_DIRECTORY = "https://plc.directory"
    }
}
