package uk.ewancroft.fathom.shared.auth

import kotlin.concurrent.Volatile

/**
 * The result of parsing the OAuth redirect `uk.ewancroft.fathom:/oauth/callback`.
 *
 * Parsing answers one question only — "is this our callback, and what does it
 * carry?" — because *validating* the callback (matching `state` against the
 * outbound request, redeeming `code` with PKCE and DPoP) is the login flow's
 * job and is not implemented yet. Nothing here touches the network.
 */
sealed interface OAuthCallback {

    /**
     * The authorisation server redirected back with a code. `iss` is present
     * when the server follows OAuth 2.1 authorization-server metadata, and
     * identifies which PDS issued the code.
     */
    data class Authorised(
        val code: String,
        val state: String,
        val iss: String? = null,
    ) : OAuthCallback

    /**
     * The listener denied the request, or the server rejected it. `state` may
     * be absent from an error redirect, so unlike [Authorised] it is optional.
     */
    data class Denied(
        val error: String,
        val errorDescription: String? = null,
        val state: String? = null,
    ) : OAuthCallback

    /** The URI is not the OAuth callback at all. */
    object NotCallback : OAuthCallback
}

/**
 * Parses the native redirect declared in `website/client-metadata.json`.
 *
 * The redirect is a reverse-domain scheme with a single colon and a single
 * slash (`uk.ewancroft.fathom:/oauth/callback`), which is deliberately not a
 * hierarchical `//` URL: the authority-less form is the shape atproto's
 * reference clients register for native apps. Both platforms still deliver it
 * as a simple string after registering the scheme, so the parser takes a
 * string and never touches a platform URI type.
 *
 * Strict decoding applies (AGENTS.md principle 4): a success without both
 * `code` and `state` is corruption of the contract Fathom itself always
 * sends, so it is [OAuthCallback.NotCallback] rather than a lenient guess.
 */
object OAuthRedirect {

    const val SCHEME = "uk.ewancroft.fathom"

    const val PATH = "/oauth/callback"

    /** The callback as it appears in the client metadata's `redirect_uris`. */
    const val REDIRECT_URI = "$SCHEME:$PATH"

    /** The single callback path both apps deliver here. */
    fun parse(url: String): OAuthCallback {
        val prefix = "$SCHEME:"
        if (!url.startsWith(prefix, ignoreCase = true)) return OAuthCallback.NotCallback

        var rest = url.substring(prefix.length)
        if (!rest.startsWith(PATH)) return OAuthCallback.NotCallback

        val queryAt = rest.indexOf('?')
        if (queryAt < 0) return OAuthCallback.NotCallback
        val query = rest.substring(queryAt + 1)

        var code: String? = null
        var state: String? = null
        var iss: String? = null
        var error: String? = null
        var errorDescription: String? = null

        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val rawName = if (eq < 0) pair else pair.substring(0, eq)
            val rawValue = if (eq < 0) "" else pair.substring(eq + 1)
            when (decodeQueryToken(rawName)) {
                "code" -> code = decodeQueryToken(rawValue)
                "state" -> state = decodeQueryToken(rawValue)
                "iss" -> iss = decodeQueryToken(rawValue)
                "error" -> error = decodeQueryToken(rawValue)
                "error_description" -> errorDescription = decodeQueryToken(rawValue)
            }
        }

        // The redirect carries either a code or an error. A code without a
        // state cannot be matched to the flow that started it, so it is not
        // usable; an error without an error code says nothing either.
        val knownCode = code?.takeIf { it.isNotEmpty() }
        if (knownCode != null) {
            val knownState = state?.takeIf { it.isNotEmpty() } ?: return OAuthCallback.NotCallback
            return OAuthCallback.Authorised(knownCode, knownState, iss?.takeIf { it.isNotEmpty() })
        }
        val knownError = error?.takeIf { it.isNotEmpty() } ?: return OAuthCallback.NotCallback
        return OAuthCallback.Denied(
            knownError,
            errorDescription?.takeIf { it.isNotEmpty() },
            state?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Percent-decodes one query token (`%XX` only). `+` is left alone: the
     * redirect's values are codes and single-use tokens, and inventing the
     * HTML-form-urlencoded `+`-means-space rule would silently rewrite a
     * value that was never form-encoded.
     */
    private fun decodeQueryToken(token: String): String {
        if (!token.contains('%')) return token
        val out = StringBuilder(token.length)
        var i = 0
        while (i < token.length) {
            val c = token[i]
            if (c == '%' && i + 2 < token.length) {
                val hex = token.substring(i + 1, i + 3)
                val value = hex.toIntOrNull(16)
                if (value != null) {
                    out.append(value.toChar())
                    i += 3
                    continue
                }
            }
            out.append(c)
            i += 1
        }
        return out.toString()
    }
}

/**
 * The most recent OAuth callback received by this process, retained for the
 * login flow (issue #9) to claim and validate against its outbound request.
 *
 * OAuth is not implemented yet, so a callback can only arrive if a listener
 * completes a flow that no Fathom build started. It is stored rather than
 * dropped so that the moment the flow exists, the platform wiring already in
 * place delivers into it — nothing here fabricates a login.
 */
object PendingAuthorization {
    @Volatile
    private var callback: OAuthCallback.Authorised? = null

    /** Records a recognised success redirect. Overwrites a stale one. */
    fun retain(authorised: OAuthCallback.Authorised) {
        callback = authorised
    }

    /** Takes the retained callback, if any. Claiming is one-shot. */
    fun take(): OAuthCallback.Authorised? {
        val held = callback
        callback = null
        return held
    }
}
