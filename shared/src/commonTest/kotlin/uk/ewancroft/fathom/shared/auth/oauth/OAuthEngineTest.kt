package uk.ewancroft.fathom.shared.auth.oauth

import uk.ewancroft.fathom.shared.auth.OAuthCallback
import uk.ewancroft.fathom.shared.auth.crypto.Base64Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthEngineTest {

    /** Scripted replies in call order; every call is captured for inspection. */
    private class FakeHttp : OAuthHttp {
        val calls = mutableListOf<OAuthHttpCall>()
        val replies = ArrayDeque<OAuthHttpReply>()

        fun then(status: Int, body: String? = null, vararg headers: Pair<String, String>) {
            replies += OAuthHttpReply(status, headers.toMap(), body)
        }

        fun lastProofNonce(call: Int): String? {
            val proof = calls[call].headers["dpop"]?.split('.')?.getOrNull(1) ?: return null
            val claims = Base64Url.decode(proof).decodeToString()
            val match = Regex("\"nonce\":\"([^\"]*)\"").find(claims) ?: return null
            return match.groupValues[1]
        }

        override fun call(call: OAuthHttpCall): OAuthHttpReply {
            calls += call
            return replies.removeFirstOrNull() ?: OAuthHttpReply(0, emptyMap())
        }
    }

    private fun key() = uk.ewancroft.fathom.shared.auth.crypto.Es256Platform.generate()

    private val metadataJson = """
        {"issuer":"https://auth.example",
         "authorization_endpoint":"https://auth.example/authorize",
         "token_endpoint":"https://auth.example/token",
         "pushed_authorization_request_endpoint":"https://auth.example/par",
         "revocation_endpoint":"https://auth.example/revoke"}
    """.trimIndent()

    private val grantJson =
        """{"access_token":"at","refresh_token":"rt","expires_in":600,"scope":"atproto","sub":"did:plc:example","token_type":"DPoP"}"""

    private fun engine(http: FakeHttp, now: Long = 1_000) = OAuthEngine(http) { now }

    // ── Discovery ─────────────────────────────────────────────────────

    @Test
    fun discoversTheAuthorizationServerAtThePds() {
        val http = FakeHttp().apply { then(200, metadataJson) }
        val metadata = engine(http).authorizationServer("https://pds.example/")!!
        assertEquals("https://auth.example/token", metadata.tokenEndpoint)
        assertEquals("https://pds.example/.well-known/oauth-authorization-server", http.calls.single().url)
    }

    @Test
    fun aMissingOrUnparseableDiscoveryReadsAsAbsent() {
        assertNull(engine(FakeHttp().apply { then(404) }).authorizationServer("https://pds.example"))
        assertNull(engine(FakeHttp().apply { then(200, "not json") }).authorizationServer("https://pds.example"))
        assertNull(engine(FakeHttp().apply { then(0) }).authorizationServer("https://pds.example"))
    }

    // ── Login ─────────────────────────────────────────────────────────

    @Test
    fun pushesParAndBuildsTheAuthorizeUrl() {
        val http = FakeHttp().apply {
            then(200, """{"request_uri":"urn:ietf:params:oauth:request_uri:1"}""", "dpop-nonce" to "par-nonce")
        }
        val attempt = engine(http).startAuthorization("did:plc:example", "https://pds.example", AuthorizationServerMetadata.parse(metadataJson)!!)!!

        val par = http.calls.single()
        assertEquals("https://auth.example/par", par.url)
        assertTrue(par.headers["dpop"]!!.isNotBlank(), "PAR carries a DPoP proof")
        assertEquals("S256", par.form!!["code_challenge_method"])
        assertEquals("https://pds.example", par.form!!["resource"])
        assertTrue(par.form!!.containsKey("dpop_jkt"))
        assertEquals(
            "https://auth.example/authorize?client_id=https%3A%2F%2Ffathom.ewancroft.uk%2Fclient-metadata.json&request_uri=urn%3Aietf%3Aparams%3Aoauth%3Arequest_uri%3A1",
            attempt.authorizeUrl,
        )
    }

    @Test
    fun aUseDpopNonceChallengeIsRetriedOnceWithTheFreshNonce() {
        val http = FakeHttp().apply {
            then(400, """{"error":"use_dpop_nonce"}""", "dpop-nonce" to "fresh-1")
            then(200, """{"request_uri":"urn:ietf:params:oauth:request_uri:2"}""")
        }
        engine(http).startAuthorization("did:plc:example", "https://pds.example", AuthorizationServerMetadata.parse(metadataJson)!!)!!

        assertEquals(2, http.calls.size, "exactly one retry")
        assertEquals(null, http.lastProofNonce(0), "no nonce was known for the first attempt")
        assertEquals("fresh-1", http.lastProofNonce(1), "the retry carries the challenged nonce")
    }

    @Test
    fun aSecondChallengeFailsTheCallInsteadOfLooping() {
        val http = FakeHttp().apply {
            then(400, """{"error":"use_dpop_nonce"}""", "dpop-nonce" to "one")
            then(400, """{"error":"use_dpop_nonce"}""", "dpop-nonce" to "two")
            then(400, """{"error":"use_dpop_nonce"}""", "dpop-nonce" to "three")
        }
        assertNull(
            engine(http).startAuthorization("did:plc:example", "https://pds.example", AuthorizationServerMetadata.parse(metadataJson)!!),
            "two attempts total, then failure",
        )
        assertEquals(2, http.calls.size)
    }

    @Test
    fun aDeniedCallbackIsARejectionNotAFailure() {
        val engine = engine(FakeHttp())
        val attempt = AuthorizationAttempt(
            key(),
            AuthorizationRequest("did:plc:example", "https://pds.example", "https://auth.example", "verifier", "state"),
            AuthorizationServerMetadata.parse(metadataJson)!!,
            "https://auth.example/authorize",
        )
        val denied = engine.completeLogin(attempt, OAuthCallback.Denied("access_denied"))
        assertEquals(LoginResult.Rejected("access_denied"), denied)
    }

    @Test
    fun aForgedCallbackIsNeverExchanged() {
        val http = FakeHttp()
        val engine = engine(http)
        val attempt = AuthorizationAttempt(
            key(),
            AuthorizationRequest("did:plc:example", "https://pds.example", "https://auth.example", "verifier", "outbound-state"),
            AuthorizationServerMetadata.parse(metadataJson)!!,
            "https://auth.example/authorize",
        )
        val result = engine.completeLogin(attempt, OAuthCallback.Authorised("stolen-code", "forged-state"))
        assertEquals(LoginResult.Rejected("state-mismatch"), result)
        assertTrue(http.calls.isEmpty(), "no token request was made")
    }

    @Test
    fun completesALoginAgainstTheResolvedAccount() {
        val http = FakeHttp().apply { then(200, grantJson, "dpop-nonce" to "token-nonce") }
        val engine = engine(http)
        val attempt = AuthorizationAttempt(
            key(),
            AuthorizationRequest("did:plc:example", "https://pds.example", "https://auth.example", "verifier", "outbound-state"),
            AuthorizationServerMetadata.parse(metadataJson)!!,
            "https://auth.example/authorize",
        )
        val success = assertIs<LoginResult.Success>(engine.completeLogin(attempt, OAuthCallback.Authorised("code", "outbound-state")))
        assertEquals("did:plc:example", success.session.did)
        assertEquals("at", success.session.accessToken)
        assertNull(http.lastProofNonce(0), "the exchange proof knew no nonce")

        // The nonce from the token response seeds all later proofs: the next
        // authenticated request opens with it.
        http.then(200, """{"ok":true}""")
        val sameEngine = engine
        sameEngine.authenticatedRequest(success.session, key(), "https://pds.example/xrpc/fm.teal.feed.play")
        assertEquals("token-nonce", http.lastProofNonce(1))
    }

    @Test
    fun anInvalidGrantDuringExchangeSignsOutRatherThanErroringForever() {
        val http = FakeHttp().apply { then(400, """{"error":"invalid_grant"}""") }
        val engine = engine(http)
        val attempt = AuthorizationAttempt(
            key(),
            AuthorizationRequest("did:plc:example", "https://pds.example", "https://auth.example", "verifier", "outbound-state"),
            AuthorizationServerMetadata.parse(metadataJson)!!,
            "https://auth.example/authorize",
        )
        assertEquals(
            LoginResult.SignedOut,
            engine.completeLogin(attempt, OAuthCallback.Authorised("code", "outbound-state")),
        )
    }

    // ── Refresh and revoke ─────────────────────────────────────────────

    private fun session(expiresAt: Long = 2_000) = OAuthSession(
        did = "did:plc:example",
        pdsUrl = "https://pds.example",
        issuer = "https://auth.example",
        accessToken = "stale",
        refreshToken = "stale-refresh",
        accessTokenExpiresAtSeconds = expiresAt,
        grantedScope = "atproto",
    )

    @Test
    fun refreshRotatesTheSessionForTheSameAccount() {
        val http = FakeHttp().apply {
            then(200, metadataJson)
            then(200, grantJson)
        }
        val rotated = assertIs<RefreshResult.Rotated>(
            engine(http).refresh(session(), key()),
        )
        assertEquals("at", rotated.session.accessToken)
        assertEquals("did:plc:example", rotated.session.did)
        assertEquals("stale-refresh", http.calls[1].form!!["refresh_token"])
        assertNull(http.calls[1].form!!["client_secret"], "public clients have no secret")
    }

    @Test
    fun anInvalidGrantDuringRefreshIsASignOut() {
        val http = FakeHttp().apply {
            then(200, metadataJson)
            then(400, """{"error":"invalid_grant"}""")
        }
        assertEquals(RefreshResult.SignedOut, engine(http).refresh(session(), key()))
    }

    @Test
    fun revokePostsTheRefreshTokenAndIgnoresTheReply() {
        val http = FakeHttp().apply {
            then(200, metadataJson)
            then(200, """{}""")
        }
        engine(http).revoke(session(), key())
        val revocation = http.calls[1]
        assertEquals("https://auth.example/revoke", revocation.url)
        assertEquals("stale-refresh", revocation.form!!["token"])
    }

    // ── Authenticated requests ─────────────────────────────────────────

    @Test
    fun anAuthenticatedRequestCarriesTheDpopBoundToken() {
        val http = FakeHttp().apply { then(200, """{"ok":true}""") }
        val result = engine(http).authenticatedRequest(session(expiresAt = 2_000), key(), "https://pds.example/xrpc/fm.teal.feed.play")
        assertEquals("""{"ok":true}""", (result as AuthenticatedResult.Ok).body)
        val call = http.calls.single()
        assertEquals("DPoP stale", call.headers["authorization"])
        assertTrue(call.headers["dpop"]!!.split('.').size == 3, "a compact JWS proof")
    }

    @Test
    fun aStaleTokenIsRefreshedBeforeTheRequest() {
        val http = FakeHttp().apply {
            then(200, metadataJson)
            then(200, grantJson)
            then(200, """{"ok":true}""")
        }
        val result = engine(http).authenticatedRequest(session(expiresAt = 1_000), key(), "https://pds.example/xrpc/fm.teal.feed.play")
        assertIs<AuthenticatedResult.Ok>(result)
        assertEquals("DPoP at", http.calls[2].headers["authorization"], "the rotated token served the request")
    }

    @Test
    fun a401ThatSurvivesRefreshIsASignOut() {
        val http = FakeHttp().apply {
            then(200, metadataJson)
            then(200, grantJson)
            then(401, """{"error":"invalid_token"}""")
        }
        assertEquals(
            AuthenticatedResult.SignedOut,
            engine(http).authenticatedRequest(session(expiresAt = 1_000), key(), "https://pds.example/xrpc/fm.teal.feed.play"),
        )
    }
}
