package uk.ewancroft.fathom.shared.auth.oauth

import uk.ewancroft.fathom.shared.auth.OAuthCallback
import uk.ewancroft.fathom.shared.auth.TealScopes
import uk.ewancroft.fathom.shared.auth.crypto.Es256Key
import uk.ewancroft.fathom.shared.auth.crypto.Es256Platform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The shared engine that walks the atproto OAuth 2.1 flow over [OAuthHttp].
 *
 * The engine owns every rule that must not drift between platforms: the PAR
 * request and the authorize redirect built from its session, the token
 * exchange gated on a matching `state`, the exactly-once `use_dpop_nonce`
 * retry, refresh rotation, revocation, and DPoP-bound resource requests.
 * Each platform supplies transport and a clock, and nothing else.
 *
 * The `use_dpop_nonce` rule (issue #9): a rejection carrying a server nonce
 * is retried exactly once with that nonce in the proof. A second challenge
 * for the same call fails the call — there is no loop.
 */
class OAuthEngine(
    private val http: OAuthHttp,
    private val clock: () -> Long,
) {
    private val parser = Json { ignoreUnknownKeys = true }

    // ── Discovery ──────────────────────────────────────────────────────

    /** Authorize-server discovery (RFC 8414) at the account's PDS. */
    fun authorizationServer(pdsUrl: String): AuthorizationServerMetadata? {
        val reply = http.call(
            OAuthHttpCall(method = "GET", url = "${pdsUrl.trimEnd('/')}/.well-known/oauth-authorization-server", headers = emptyMap()),
        )
        if (!reply.isOk()) return null
        return reply.body?.let { AuthorizationServerMetadata.parse(it) }
    }

    // ── Login ──────────────────────────────────────────────────────────

    /**
     * Starts an authorization: generates the DPoP key, builds the PAR request
     * and pushes it. The returned attempt carries the authorize URL the
     * platform hands to the browser plus the state needed to finish. On any
     * failure there is no partial side effect — no PAR session exists.
     */
    fun startAuthorization(
        did: String,
        pdsUrl: String,
        metadata: AuthorizationServerMetadata,
    ): AuthorizationAttempt? {
        val key = Es256Platform.generate()
        val request = AuthorizationRequest.start(did, pdsUrl, metadata.issuer)
        val parEndpoint = metadata.pushedAuthorizationRequestEndpoint ?: return null

        val pushed = callWithNonceRetry(metadata.issuer) { nonce ->
            OAuthHttpCall(
                method = "POST",
                url = parEndpoint,
                headers = mapOf(
                    "content-type" to "application/x-www-form-urlencoded",
                    "dpop" to DpopProof.forTokenRequest(key, parEndpoint, clock(), nonce),
                ),
                form = request.parRequestBody(metadata, key.thumbprint()),
            )
        } ?: return null

        if (!pushed.isOk()) return null
        val requestUri = parser
            .parseToJsonElement(pushed.body ?: return null).jsonObject["request_uri"]
            ?.let { (it as? JsonPrimitive)?.takeIf { p -> p !is kotlinx.serialization.json.JsonNull }?.content }
            ?: return null

        return AuthorizationAttempt(
            key = key,
            request = request,
            metadata = metadata,
            authorizeUrl = request.authorizeUrl(metadata, requestUri),
        )
    }

    /**
     * Finishes a login: validates the callback against the outbound request
     * and exchanges the code. A denied callback or a mismatched state is a
     * rejection, not a failure — the caller reports it, nothing is stored.
     */
    fun completeLogin(attempt: AuthorizationAttempt, callback: OAuthCallback): LoginResult {
        val authorised = when (callback) {
            is OAuthCallback.Denied -> return LoginResult.Rejected(callback.error)
            OAuthCallback.NotCallback -> return LoginResult.Rejected("not-a-callback")
            is OAuthCallback.Authorised -> callback
        }

        val form = attempt.request.tokenRequestBody(authorised)
            ?: return LoginResult.Rejected("state-mismatch")

        val reply = callWithNonceRetry(attempt.metadata.issuer) { nonce ->
            OAuthHttpCall(
                method = "POST",
                url = attempt.metadata.tokenEndpoint,
                headers = mapOf(
                    "content-type" to "application/x-www-form-urlencoded",
                    "dpop" to DpopProof.forTokenRequest(attempt.key, attempt.metadata.tokenEndpoint, clock(), nonce),
                ),
                form = form,
            )
        } ?: return LoginResult.Transported

        if (!reply.isOk()) {
            val error = reply.body?.let { OAuthError.parse(it) }
            if (error?.isInvalidGrant == true) return LoginResult.SignedOut
            return LoginResult.Failed(error?.error ?: "http-${reply.status}")
        }
        val grant = reply.body?.let { TokenGrant.parse(it) } ?: return LoginResult.Failed("unparseable-grant")
        val session = OAuthSession.fromGrant(attempt.request.did, attempt.request.pdsUrl, attempt.metadata.issuer, grant, clock())
            ?: return LoginResult.Rejected("account-mismatch")

        // The token endpoint always supplies the nonce every later proof to
        // this issuer must carry (RFC 9449 §8).
        rememberNonce(attempt.metadata.issuer, reply)
        return LoginResult.Success(session)
    }

    // ── Token lifecycle ────────────────────────────────────────────────

    /**
     * Rotates the access token with the refresh grant, proving with the same
     * DPoP key the token is bound to. An `invalid_grant` — expired, revoked
     * or rotated-away — is a sign-out, not a network failure to retry.
     */
    fun refresh(session: OAuthSession, key: Es256Key): RefreshResult {
        val metadata = authorizationServer(session.pdsUrl)
            ?: return RefreshResult.Failed("authorization-server-unreachable")

        val reply = callWithNonceRetry(metadata.issuer) { nonce ->
            OAuthHttpCall(
                method = "POST",
                url = metadata.tokenEndpoint,
                headers = mapOf(
                    "content-type" to "application/x-www-form-urlencoded",
                    "dpop" to DpopProof.forTokenRequest(key, metadata.tokenEndpoint, clock(), nonce),
                ),
                form = OAuthSession.refreshTokenRequestBody(session),
            )
        } ?: return RefreshResult.Failed("transport")

        if (!reply.isOk()) {
            val error = reply.body?.let { OAuthError.parse(it) }
            if (error?.isInvalidGrant == true) return RefreshResult.SignedOut
            return RefreshResult.Failed(error?.error ?: "http-${reply.status}")
        }
        val grant = reply.body?.let { TokenGrant.parse(it) } ?: return RefreshResult.Failed("unparseable-grant")
        val rotated = OAuthSession.fromGrant(session.did, session.pdsUrl, session.issuer, grant, clock())
            ?: return RefreshResult.SignedOut
        rememberNonce(metadata.issuer, reply)
        return RefreshResult.Rotated(rotated)
    }

    /**
     * Revokes the refresh token at the authorization server's revocation
     * endpoint. The platform clears its secure store regardless: revocation
     * is best-effort on the wire, absolute on the device (#9, #14).
     */
    fun revoke(session: OAuthSession, key: Es256Key) {
        val metadata = authorizationServer(session.pdsUrl)
        val endpoint = metadata?.revocationEndpoint ?: return
        val reply = callWithNonceRetry(metadata.issuer) { nonce ->
            OAuthHttpCall(
                method = "POST",
                url = endpoint,
                headers = mapOf(
                    "content-type" to "application/x-www-form-urlencoded",
                    "dpop" to DpopProof.forTokenRequest(key, endpoint, clock(), nonce),
                ),
                form = mapOf(
                    "client_id" to TealScopes.CLIENT_ID,
                    "token" to session.refreshToken,
                ),
            )
        } ?: return
        rememberNonce(metadata.issuer, reply)
    }

    // ── Authenticated requests ─────────────────────────────────────────

    /**
     * A DPoP-authenticated POST. A stale access token is refreshed first;
     * a nonce challenge is retried exactly once; a 401 that survives both
     * is a sign-out.
     */
    fun authenticatedRequest(
        session: OAuthSession,
        key: Es256Key,
        url: String,
        jsonBody: String? = null,
    ): AuthenticatedResult {
        var current = session
        if (current.needsRefresh(clock())) {
            when (val refreshed = refresh(current, key)) {
                is RefreshResult.Rotated -> current = refreshed.session
                RefreshResult.SignedOut -> return AuthenticatedResult.SignedOut
                is RefreshResult.Failed -> return AuthenticatedResult.Failed(refreshed.reason)
            }
        }

        val reply = callWithNonceRetry(current.issuer) { nonce ->
            OAuthHttpCall(
                method = "POST",
                url = url,
                headers = mapOf(
                    "content-type" to "application/json",
                    "authorization" to "DPoP ${current.accessToken}",
                    "dpop" to DpopProof.forRequest(key, url, current.accessToken, clock(), nonce),
                ),
                rawBody = jsonBody,
            )
        } ?: return AuthenticatedResult.Failed("transport")

        if (reply.isOk()) return AuthenticatedResult.Ok(reply.body)
        if (reply.status == 401) return AuthenticatedResult.SignedOut
        return AuthenticatedResult.Failed("http-${reply.status}")
    }

    // ── Nonce memory ───────────────────────────────────────────────────

    /**
     * One nonce per issuer, rotated on every challenge response — the same
     * lifetime Piper's indigo client keeps. Memory is in-process: a fresh
     * app launch simply re-learns the nonce from one 401, which is why the
     * retry is per-call and never long-lived.
     */
    private var nonce: Pair<String, String>? = null

    private fun nonceFor(issuer: String): String? = nonce?.takeIf { it.second == issuer }?.first

    private fun rememberNonce(issuer: String, reply: OAuthHttpReply) {
        reply.header(DPOP_NONCE_HEADER)?.takeIf { it.isNotEmpty() }?.let { nonce = it to issuer }
    }

    /**
     * Performs a call, retrying exactly once when the server answers with a
     * `use_dpop_nonce` challenge — named by the `DPoP-Nonce` response header
     * and, in atproto's implementation, by the error name in the body.
     */
    private fun callWithNonceRetry(issuer: String, callFor: (nonce: String?) -> OAuthHttpCall): OAuthHttpReply? {
        val first = http.call(callFor(nonceFor(issuer)))
        rememberNonce(issuer, first)
        if (first.isOk() || !isNonceChallenge(first)) return first

        // The challenge is only actionable when it carries the nonce to use;
        // a body error without the header cannot be retried, so the call
        // fails rather than loops.
        val freshNonce = first.header(DPOP_NONCE_HEADER)?.takeIf { it.isNotEmpty() } ?: return first
        val second = http.call(callFor(freshNonce))
        rememberNonce(issuer, second)
        return second
    }

    private fun isNonceChallenge(reply: OAuthHttpReply): Boolean {
        if (reply.isOk()) return false
        val named = reply.body?.let { OAuthError.parse(it) }?.isDpopNonceChallenge == true
        return named
    }

    private companion object {
        const val DPOP_NONCE_HEADER = "dpop-nonce"
    }
}

data class AuthorizationAttempt(
    val key: Es256Key,
    val request: AuthorizationRequest,
    val metadata: AuthorizationServerMetadata,
    val authorizeUrl: String,
)

sealed interface LoginResult {
    data class Success(val session: OAuthSession) : LoginResult

    /** The authorisation was denied, or the callback did not match the outbound request. */
    data class Rejected(val reason: String) : LoginResult

    /** The network refused; the attempt can be retried. */
    object Transported : LoginResult

    data class Failed(val reason: String) : LoginResult
    object SignedOut : LoginResult
}

sealed interface RefreshResult {
    data class Rotated(val session: OAuthSession) : RefreshResult
    object SignedOut : RefreshResult
    data class Failed(val reason: String) : RefreshResult
}

sealed interface AuthenticatedResult {
    data class Ok(val body: String?) : AuthenticatedResult
    object SignedOut : AuthenticatedResult
    data class Failed(val reason: String) : AuthenticatedResult
}
