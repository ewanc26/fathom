package uk.ewancroft.fathom.shared.auth.oauth

import uk.ewancroft.fathom.shared.auth.OAuthCallback
import uk.ewancroft.fathom.shared.auth.OAuthRedirect
import uk.ewancroft.fathom.shared.auth.TealScopes
import uk.ewancroft.fathom.shared.xrpc.XrpcQuery

/**
 * The authorization request, in the order the flow uses it: a PAR form
 * (atproto's profile requires PAR), the authorize redirect built from the
 * PAR session URI, and the token exchange that #8's
 * [uk.ewancroft.fathom.shared.auth.OAuthRedirect] parser feeds.
 *
 * Everything is construction: the platform transport performs the HTTP and
 * hands the bodies and responses verbatim to this module, so no platform
 * ever assembles an OAuth string of its own.
 *
 * The outbound request state (verifier, state, issuer, pds, did) is what a
 * restart must survive to finish a login across process death: it is prose
 * for #9's storage step, and it leaks nothing if persisted — the verifier
 * is the only secret and it is single-use.
 */
data class AuthorizationRequest(
    val did: String,
    val pdsUrl: String,
    val issuer: String,
    val verifier: String,
    val state: String,
) {
    companion object {
        fun start(did: String, pdsUrl: String, issuer: String): AuthorizationRequest =
            AuthorizationRequest(did, pdsUrl, issuer, Pkce.verifier(), Pkce.state())
    }

    /** The PAR form body: one entry per RFC 9126 plus PKCE and DPoP's key binding. */
    fun parRequestBody(metadata: AuthorizationServerMetadata, keyJwkThumbprint: String): Map<String, String> = mapOf(
        "client_id" to TealScopes.CLIENT_ID,
        "response_type" to "code",
        "scope" to TealScopes.fullWriteRequest,
        "redirect_uri" to uk.ewancroft.fathom.shared.auth.OAuthRedirect.REDIRECT_URI,
        "state" to state,
        "code_challenge" to Pkce.challenge(verifier),
        "code_challenge_method" to "S256",
        "login_hint" to did,
        // atproto's profile binds the request to the account's PDS and the
        // client's DPoP key at PAR time, so the authorization cannot be
        // replayed against another PDS or presented with another key.
        "resource" to pdsUrl,
        "dpop_jkt" to keyJwkThumbprint,
    )

    /** The authorize redirect, carrying the PAR request URI the token server returned. */
    fun authorizeUrl(metadata: AuthorizationServerMetadata, parRequestUri: String): String {
        require(metadata.pushedAuthorizationRequestEndpoint != null) {
            "authorizeUrl needs a PAR session; direct authorize requests are not sent"
        }
        val base = metadata.authorizationEndpoint.substringBefore('?')
        return "$base?client_id=${XrpcQuery.encode(TealScopes.CLIENT_ID)}&request_uri=${XrpcQuery.encode(parRequestUri)}"
    }

    /**
     * The token exchange body for a recognised success callback. The state
     * must match the outbound request — a mismatch is a different flow or a
     * forged callback, and the exchange must not be attempted at all.
     */
    fun tokenRequestBody(callback: OAuthCallback.Authorised): Map<String, String>? {
        if (callback.state != state) return null
        return mapOf(
            "grant_type" to "authorization_code",
            "code" to callback.code,
            "redirect_uri" to OAuthRedirect.REDIRECT_URI,
            "client_id" to TealScopes.CLIENT_ID,
            "code_verifier" to verifier,
        )
    }
}
