package uk.ewancroft.fathom.shared.auth.oauth

import uk.ewancroft.fathom.shared.auth.OAuthCallback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthSessionTest {

    private fun grant(sub: String = "did:plc:example", expiresIn: Long = 600) =
        TokenGrant("access", "refresh", expiresIn, "atproto repo:fm.teal.feed.play?action=create", sub)

    @Test
    fun aGrantForTheResolvedAccountCreatesASession() {
        val session = OAuthSession.fromGrant(
            did = "did:plc:example",
            pdsUrl = "https://pds.example",
            issuer = "https://auth.example",
            grant = grant(),
            nowSeconds = 1_000,
        )!!
        assertEquals("https://pds.example", session.pdsUrl)
        assertEquals(1_600, session.accessTokenExpiresAtSeconds)
    }

    @Test
    fun aGrantForAnotherAccountIsRejected() {
        assertNull(
            OAuthSession.fromGrant("did:plc:example", "https://pds.example", "https://auth.example", grant(sub = "did:plc:attacker"), 1_000),
        )
    }

    @Test
    fun expiryIsCheckedWithSkew() {
        val session = OAuthSession.fromGrant("did:plc:example", "https://pds.example", "https://auth.example", grant(expiresIn = 600), nowSeconds = 0)!!
        assertFalse(session.needsRefresh(nowSeconds = 589))
        assertTrue(session.needsRefresh(nowSeconds = 590), "ten seconds before expiry the token is already stale")
        assertTrue(session.needsRefresh(nowSeconds = 600))
    }

    @Test
    fun grantedScopeIsInspectedNotAssumed() {
        val session = OAuthSession.fromGrant("did:plc:example", "https://pds.example", "https://auth.example", grant(), 0)!!
        assertTrue(session.grantedEverything(listOf("atproto")))
        assertFalse(session.grantedEverything(listOf("repo:fm.teal.actor.status?action=create&action=update&action=delete")))
    }

    @Test
    fun aRefreshRequestBodyIsSecretless() {
        val session = OAuthSession.fromGrant("did:plc:example", "https://pds.example", "https://auth.example", grant(), 0)!!
        val body = OAuthSession.refreshTokenRequestBody(session)
        assertEquals("refresh_token", body["grant_type"])
        assertEquals("refresh", body["refresh_token"])
        assertFalse(body.containsKey("client_secret"))
        assertFalse(body.containsKey("code_verifier"), "only the initial exchange carries PKCE")
    }

    @Test
    fun nonceMemoryIsPerIssuer() {
        val store = DpopNonceStore()
        store.remember("https://auth.example", "one")
        store.remember("https://other.example", "two")
        assertEquals("one", store.current("https://auth.example"))
        assertEquals("two", store.current("https://other.example"))
        store.clear()
        assertNull(store.current("https://auth.example"))
    }
}

class AuthorizationRequestTest {

    private val metadata = AuthorizationServerMetadata(
        issuer = "https://auth.example",
        authorizationEndpoint = "https://auth.example/authorize",
        tokenEndpoint = "https://auth.example/token",
        pushedAuthorizationRequestEndpoint = "https://auth.example/par",
        revocationEndpoint = null,
        scopesSupported = emptyList(),
        requiresPushedAuthorizationRequests = true,
    )

    @Test
    fun parBindsThePdsAndTheDpopKey() {
        val request = AuthorizationRequest.start("did:plc:example", "https://pds.example", "https://auth.example")
        val body = request.parRequestBody(metadata, "THUMBPRINT")
        assertEquals(uk.ewancroft.fathom.shared.auth.TealScopes.CLIENT_ID, body["client_id"])
        assertEquals("code", body["response_type"])
        assertEquals(uk.ewancroft.fathom.shared.auth.TealScopes.fullWriteRequest, body["scope"])
        assertEquals("https://pds.example", body["resource"])
        assertEquals("THUMBPRINT", body["dpop_jkt"])
        assertEquals("S256", body["code_challenge_method"])
        assertEquals(request.verifier.length, 64)
        // The challenge in the PAR body must verify against the verifier
        // held back for the exchange.
        assertEquals(Pkce.challenge(request.verifier), body["code_challenge"])
        assertEquals("did:plc:example", body["login_hint"])
    }

    @Test
    fun theAuthorizeRedirectCarriesTheParSession() {
        val request = AuthorizationRequest.start("did:plc:example", "https://pds.example", "https://auth.example")
        val url = request.authorizeUrl(metadata, "urn:ietf:params:oauth:request_uri:6esc")
        assertEquals(
            "https://auth.example/authorize?client_id=https%3A%2F%2Ffathom.ewancroft.uk%2Fclient-metadata.json&request_uri=urn%3Aietf%3Aparams%3Aoauth%3Arequest_uri%3A6esc",
            url,
        )
    }

    @Test
    fun aForgedCallbackIsNeverExchanged() {
        val request = AuthorizationRequest.start("did:plc:example", "https://pds.example", "https://auth.example")
        val forged = OAuthCallback.Authorised("stolen-code", "someone-elses-state")
        assertNull(request.tokenRequestBody(forged))

        val honest = OAuthCallback.Authorised("real-code", request.state)
        val body = request.tokenRequestBody(honest)!!
        assertEquals("authorization_code", body["grant_type"])
        assertEquals("real-code", body["code"])
        assertEquals(request.verifier, body["code_verifier"])
        assertEquals(uk.ewancroft.fathom.shared.auth.OAuthRedirect.REDIRECT_URI, body["redirect_uri"])
    }
}
