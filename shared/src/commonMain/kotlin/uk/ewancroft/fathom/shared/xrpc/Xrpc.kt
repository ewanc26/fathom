package uk.ewancroft.fathom.shared.xrpc

/**
 * The collections Fathom reads.
 *
 * teal.fm moved from the `fm.teal.alpha.*` namespace to `fm.teal.*`. Both
 * still exist in the wild: records written before the move keep their
 * original collection, and a repo can hold a mix. Reading only the
 * production namespace would silently lose a person's older history, so
 * both are listed and the UI states which namespace a backfill came from.
 */
object TealCollections {
    const val PLAY = "fm.teal.feed.play"
    const val ACTOR_PROFILE = "fm.teal.actor.profile"
    const val ACTOR_STATUS = "fm.teal.actor.status"

    const val ALPHA_PLAY = "fm.teal.alpha.feed.play"
    const val ALPHA_ACTOR_PROFILE = "fm.teal.alpha.actor.profile"
    const val ALPHA_ACTOR_STATUS = "fm.teal.alpha.actor.status"

    /** Production first, then the pre-migration namespace. */
    val PLAYS = listOf(PLAY, ALPHA_PLAY)
    val PROFILES = listOf(ACTOR_PROFILE, ALPHA_ACTOR_PROFILE)
    val STATUSES = listOf(ACTOR_STATUS, ALPHA_ACTOR_STATUS)
}

/**
 * AT Protocol's identifier for a query or procedure.
 *
 * Paths are always top-level under `/xrpc/`, never nested, and the whole
 * NSID is the path segment.
 */
object Xrpc {
    const val PREFIX = "/xrpc/"

    fun path(nsid: String): String = PREFIX + nsid

    /** `com.atproto.repo.listRecords`, the read every repo-backed view starts from. */
    const val LIST_RECORDS = "com.atproto.repo.listRecords"
    const val DESCRIBE_REPO = "com.atproto.repo.describeRepo"
    const val GET_RECORD = "com.atproto.repo.getRecord"
    const val RESOLVE_HANDLE = "com.atproto.identity.resolveHandle"
    const val GET_BLOB = "com.atproto.sync.getBlob"
}

/**
 * Builds an XRPC query string.
 *
 * Every value is percent-encoded, because a handle, a DID, a collection and
 * a cursor all travel as query values and a cursor is opaque to us. Values
 * are appended in the order they were added so the same inputs always
 * produce the same URL, which is what makes the request cacheable and the
 * tests readable.
 */
class XrpcQuery {
    private val parts = mutableListOf<String>()

    fun add(name: String, value: String?): XrpcQuery {
        if (value == null) return this
        parts += encode(name) + "=" + encode(value)
        return this
    }

    // Deliberately non-null: an overload taking Int? would make
    // `add("cursor", null)` ambiguous with the String? overload, and a null
    // limit or flag is meaningless anyway.
    fun add(name: String, value: Int): XrpcQuery = add(name, value.toString())

    fun add(name: String, value: Boolean): XrpcQuery = add(name, value.toString())

    /** The query string without a leading `?`, ready to append. */
    fun build(): String = parts.joinToString("&")

    /** The query string with a leading `?`, or an empty string when there is nothing to add. */
    fun suffix(): String = if (parts.isEmpty()) "" else "?" + build()

    companion object {
        /**
         * RFC 3986 percent-encoding, applied to every byte that is not an
         * unreserved character. UTF-8 first, so a non-ASCII cursor survives.
         */
        fun encode(value: String): String {
            val out = StringBuilder(value.length)
            for (byte in value.encodeToByteArray()) {
                val unsigned = byte.toInt() and 0xFF
                val char = unsigned.toChar()
                if (char.isUnreserved()) {
                    out.append(char)
                } else {
                    out.append('%')
                    out.append(HEX[unsigned shr 4])
                    out.append(HEX[unsigned and 0x0F])
                }
            }
            return out.toString()
        }

        private fun Char.isUnreserved(): Boolean =
            this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' ||
                this == '-' || this == '.' || this == '_' || this == '~'

        private const val HEX = "0123456789ABCDEF"
    }
}

/**
 * `com.atproto.repo.listRecords` for one collection.
 *
 * `limit` is capped by the server at 100; passing more is not an error but
 * the extra is ignored, so the cap is applied here to keep the cursor
 * arithmetic in the caller honest.
 */
fun listRecordsQuery(
    repo: String,
    collection: String,
    cursor: String? = null,
    limit: Int = MAX_PAGE,
    reverse: Boolean? = null,
): String {
    val query = XrpcQuery()
        .add("repo", repo)
        .add("collection", collection)
        .add("cursor", cursor)
        .add("limit", limit.coerceIn(1, MAX_PAGE))
    // Absent and false are different: the server defaults to newest-first, and
    // sending reverse=false would be asking for that explicitly rather than
    // leaving the choice alone.
    if (reverse != null) query.add("reverse", reverse)
    return query.build()
}

const val MAX_PAGE = 100

/**
 * A runaway guard on one load, not a product limit.
 *
 * Fifty thousand plays is a decade of heavy listening, and reading past that
 * would spend more of a phone's memory on a dashboard than the dashboard is
 * worth. When the guard ends a read the dashboard is marked `sampled`, so the
 * screens say the figures are partial rather than passing them off as totals.
 */
const val MAX_PLAYS = 50_000

/**
 * The URL for an image stored as a blob in a repo.
 *
 * Blobs are served by the PDS that holds the record, not by the AppView, and
 * the CID has to be the one the record declared.
 */
@Suppress("unused")
fun blobUrl(pdsBase: String, did: String, cid: String): String =
    pdsBase.trimEnd('/') + Xrpc.path(Xrpc.GET_BLOB) +
        XrpcQuery().add("did", did).add("cid", cid).suffix()
