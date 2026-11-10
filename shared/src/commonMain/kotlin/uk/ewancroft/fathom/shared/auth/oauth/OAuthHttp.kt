package uk.ewancroft.fathom.shared.auth.oauth

/**
 * The HTTP edge between the shared OAuth engine and each platform's stack.
 *
 * Everything OAuth-shaped — bodies, headers, endpoints, retries — is decided
 * by the shared code; the platform side only performs the call. The contract
 * is deliberately synchronous and total: timeout, transport failure and any
 * platform exception surface as a [OAuthHttpReply] with a status of 0, so
 * the engine's retry logic can treat "the network refused" like any other
 * failure instead of leaking exceptions across the boundary.
 */
data class OAuthHttpCall(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    /** Form fields, encoded application/x-www-form-urlencoded by the platform. Absent means no body. */
    val form: Map<String, String>? = null,
    /** A raw UTF-8 body (a JSON record), used instead of [form] on resource requests. */
    val rawBody: String? = null,
)

data class OAuthHttpReply(
    /** 0 means the call did not complete (timeout, transport failure). */
    val status: Int,
    /** Response headers, case-insensitively keyed by the lowercase name. */
    val headers: Map<String, String>,
    val body: String? = null,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    fun isOk(): Boolean = status in 200..299
}

fun interface OAuthHttp {
    fun call(call: OAuthHttpCall): OAuthHttpReply
}

/** Wall-clock seconds since the epoch, for token expiry and proof times. */
expect fun epochSeconds(): Long
