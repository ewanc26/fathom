package uk.ewancroft.fathom.data

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import uk.ewancroft.fathom.shared.TealJson
import uk.ewancroft.fathom.shared.xrpc.Xrpc
import uk.ewancroft.fathom.shared.xrpc.blobUrl
import uk.ewancroft.fathom.shared.xrpc.listRecordsQuery

/** Something the person using the app can act on. Never a stack trace. */
open class FathomException(message: String) : Exception(message)

/**
 * A 400 or 404: the record or collection is not there.
 *
 * Absence is an answer rather than a failure — an account with no status and
 * no legacy plays are both normal — so it travels as its own type and the
 * caller decides whether that means `null` or an empty namespace.
 */
class FathomNotFoundException(message: String) : FathomException(message)

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
) {

    /** The DID for a handle, or the input unchanged when it is already a DID. */
    suspend fun resolveIdentity(input: String): String {
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
    suspend fun pdsFor(did: String): String {
        val documentUrl = when {
            did.startsWith("did:plc:") -> "$PLC_DIRECTORY/$did"
            did.startsWith("did:web:") -> {
                val host = did.removePrefix("did:web:").replace("%3A", ":").replace(':', '/')
                "https://$host/.well-known/did.json"
            }
            else -> throw FathomException("Fathom cannot look up ${'$'}did.")
        }
        val body = get(documentUrl, notFound = "No identity document for this account.")
        return TealJson.decodePdsEndpoint(body)
            ?: throw FathomException("This account does not publish a data server.")
    }

    /** One page of `com.atproto.repo.listRecords`, as raw JSON for [TealJson]. */
    suspend fun listRecords(
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
     * for an unedited profile, so "not found" is an answer rather than a
     * failure here.
     */
    suspend fun getRecord(
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
            get(url, notFound = null)
        } catch (notFound: FathomNotFoundException) {
            null
        }
    }

    /** The URL an avatar blob is served from, or null when the record carries none. */
    fun avatarUrl(pds: String, did: String, cid: String?): String? =
        cid?.let { blobUrl(pds, did, it) }

    private suspend fun get(url: String, notFound: String?): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (failure: IOException) {
            throw FathomException("Could not reach the network. ${failure.message.orEmpty()}".trim())
        }

        response.use {
            val body = it.body?.string().orEmpty()
            if (it.isSuccessful) return@withContext body
            if (it.code == 400 || it.code == 404) {
                // Absence is an answer, not a failure: a repo with no
                // `literal:self` status is the normal state, so it is a
                // distinct type the caller can turn into null.
                throw FathomNotFoundException(
                    notFound ?: TealJson.decodeError(body) ?: "That record does not exist.",
                )
            }
            val detail = TealJson.decodeError(body)
            throw FathomException(
                detail ?: "The server answered ${it.code}. Try again in a moment.",
            )
        }
    }

    private companion object {
        const val PUBLIC_APPVIEW = "https://public.api.bsky.app"
        const val PLC_DIRECTORY = "https://plc.directory"
    }
}
