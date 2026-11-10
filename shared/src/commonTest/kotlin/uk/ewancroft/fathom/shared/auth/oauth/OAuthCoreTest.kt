package uk.ewancroft.fathom.shared.auth.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PkceTest {

    @Test
    fun matchesTheRfc7636AppendixBVector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun verifiersUseOnlyTheUnreservedCharacters() {
        repeat(8) {
            val verifier = Pkce.verifier()
            assertEquals(64, verifier.length)
            assertTrue(
                verifier.all { c ->
                    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                        c == '-' || c == '.' || c == '_' || c == '~'
                },
                "verifier used a reserved character: $verifier",
            )
        }
    }

    @Test
    fun statesAreDifferentEveryTime() {
        assertNotEquals(Pkce.state(), Pkce.state())
    }
}

class DpopProofTest {

    private val key = uk.ewancroft.fathom.shared.auth.crypto.Es256Platform.generate()

    @Test
    fun aTokenRequestProofCarriesNoAthAndItsJtiIsFresh() {
        val proof = DpopProof.forTokenRequest(key, "https://auth.example/token", 1_772_000_000)
        val parts = proof.split('.')
        assertEquals(3, parts.size)

        val header = uk.ewancroft.fathom.shared.auth.crypto.Base64Url.decode(parts[0]).decodeToString()
        assertTrue(header.contains("\"typ\":\"dpop+jwt\""), header)
        assertTrue(header.contains("\"alg\":\"ES256\""), header)
        assertTrue(header.contains("\"crv\":\"P-256\""), header)
        assertTrue(header.contains("\"jwk\":"), header)

        val claims = uk.ewancroft.fathom.shared.auth.crypto.Base64Url.decode(parts[1]).decodeToString()
        assertTrue(claims.contains("\"htu\":\"https://auth.example/token\""), claims)
        assertTrue(claims.contains("\"htm\":\"POST\""), claims)
        assertTrue(claims.contains("\"iat\":1772000000"), claims)
        assertFalse(claims.contains("\"ath\""), "token requests never carry ath")
        assertFalse(claims.contains("\"nonce\""), "no nonce was supplied")

        val again = DpopProof.forTokenRequest(key, "https://auth.example/token", 1_772_000_000)
        assertNotEquals(proof, again, "jti must make every proof unique")
    }

    @Test
    fun aResourceRequestCarriesAthAndTheChallengedNonceOnce() {
        val proof = DpopProof.forRequest(
            key,
            "https://pds.example/xrpc/fm.teal.feed.play?x=1",
            "token",
            1_772_000_001,
            nonce = "server-said-so",
        )
        val claims = uk.ewancroft.fathom.shared.auth.crypto.Base64Url.decode(proof.split('.')[1]).decodeToString()
        // htu drops the query per RFC 9449 §4.1.
        assertTrue(claims.contains("\"htu\":\"https://pds.example/xrpc/fm.teal.feed.play\""), claims)
        assertTrue(claims.contains("\"nonce\":\"server-said-so\""), claims)
        // ath is base64url(SHA-256(access token)).
        val expectedAth = uk.ewancroft.fathom.shared.auth.crypto.Base64Url.encode(
            uk.ewancroft.fathom.shared.auth.crypto.Sha256.digest("token".encodeToByteArray()),
        )
        assertTrue(claims.contains("\"ath\":\"$expectedAth\""), claims)
    }
}

class AuthorizationServerWireTest {

    @Test
    fun parsesTheMinimalMetadata() {
        val metadata = AuthorizationServerMetadata.parse(
            """
            {
              "issuer": "https://auth.example",
              "authorization_endpoint": "https://auth.example/authorize",
              "token_endpoint": "https://auth.example/token",
              "pushed_authorization_request_endpoint": "https://auth.example/par",
              "revocation_endpoint": "https://auth.example/revoke",
              "scopes_supported": ["atproto", "repo:fm.teal.feed.play?action=create"],
              "response_modes_supported": ["query"]
            }
            """.trimIndent(),
        )!!
        assertEquals("https://auth.example", metadata.issuer)
        assertTrue(metadata.requiresPushedAuthorizationRequests)
        assertEquals(2, metadata.scopesSupported.size)
    }

    @Test
    fun parDeclarationImpliesRequirement() {
        val metadata = AuthorizationServerMetadata.parse(
            """
            {"issuer":"i","authorization_endpoint":"a","token_endpoint":"t",
             "pushed_authorization_request_endpoint":"p"}
            """.trimIndent(),
        )!!
        assertTrue(metadata.requiresPushedAuthorizationRequests)
    }

    @Test
    fun rejectsMetadataWithoutEndpooints() {
        assertNull(AuthorizationServerMetadata.parse("""{"issuer":"i"}"""))
        assertNull(AuthorizationServerMetadata.parse("not json at all"))
    }

    @Test
    fun parsesATokenGrantAndRejectsASecretlessResponse() {
        val grant = TokenGrant.parse(
            """
            {"access_token":"a","refresh_token":"r","expires_in":600,
             "scope":"atproto","sub":"did:plc:example","token_type":"DPoP"}
            """.trimIndent(),
        )!!
        assertEquals("did:plc:example", grant.sub)
        assertEquals(600, grant.expiresIn)

        assertNull(TokenGrant.parse("""{"access_token":"a","expires_in":600,"sub":"did:plc:x"}"""))
    }

    @Test
    fun readsOauthErrors() {
        val error = OAuthError.parse("""{"error":"invalid_grant","error_description":"expired"}""")!!
        assertTrue(error.isInvalidGrant)
        assertFalse(error.isDpopNonceChallenge)
        val challenge = OAuthError.parse("""{"error":"use_dpop_nonce"}""")!!
        assertTrue(challenge.isDpopNonceChallenge)
        assertNull(OAuthError.parse("""{"no_error_field":true}"""))
    }
}
