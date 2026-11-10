package uk.ewancroft.fathom.shared.auth.oauth

import uk.ewancroft.fathom.shared.auth.crypto.Base64Url
import uk.ewancroft.fathom.shared.auth.crypto.Es256Key
import uk.ewancroft.fathom.shared.auth.crypto.Sha256

/**
 * The DPoP proof (RFC 9449), for atproto's OAuth profile which binds every
 * access token to an ES256 key.
 *
 * A proof is a compact JWS: header `{"typ":"dpop+jwt","alg":"ES256","jwk":…}`,
 * claims `htu`, `htm`, `iat` (seconds), a fresh single-use `jti`, and
 * optionally the server-issued `nonce` and `ath` (base64url SHA-256 of the
 * access token, present on resource requests, never on token requests).
 *
 * The transport layer owns the nonce lifecycle this type cannot see: a token
 * or resource request rejected with `use_dpop_nonce` is retried exactly
 * once with the nonce from that response (issue #9's "retried once, not
 * looped"), and every later proof to the same issuer carries it.
 */
object DpopProof {

    fun forTokenRequest(
        key: Es256Key,
        tokenEndpointUrl: String,
        issuedAtSeconds: Long,
        nonce: String? = null,
        jti: String = Pkce.state(),
    ): String = build(key, tokenEndpointUrl, "POST", issuedAtSeconds, nonce, null, jti)

    fun forRequest(
        key: Es256Key,
        resourceUrl: String,
        accessToken: String,
        issuedAtSeconds: Long,
        nonce: String? = null,
        jti: String = Pkce.state(),
    ): String = build(key, resourceUrl, "POST", issuedAtSeconds, nonce, athOf(accessToken), jti)

    private fun build(
        key: Es256Key,
        url: String,
        method: String,
        issuedAtSeconds: Long,
        nonce: String?,
        ath: String?,
        jti: String,
    ): String {
        // The htu excludes query and fragment per RFC 9449 §4.1.
        val htu = url.substringBefore('?').substringBefore('#')

        val header = StringBuilder("{\"typ\":\"dpop+jwt\",\"alg\":\"ES256\",\"jwk\":")
        header.append(jwkJson(key))
        header.append('}')

        val claims = StringBuilder()
        claims.append("{\"htu\":\"").append(htu).append('"')
        claims.append(",\"htm\":\"").append(method).append('"')
        claims.append(",\"iat\":").append(issuedAtSeconds)
        claims.append(",\"jti\":\"").append(jti).append('"')
        if (nonce != null) claims.append(",\"nonce\":\"").append(nonce).append('"')
        if (ath != null) claims.append(",\"ath\":\"").append(ath).append('"')
        claims.append('}')

        val headerB64 = Base64Url.encode(header.toString().encodeToByteArray())
        val claimsB64 = Base64Url.encode(claims.toString().encodeToByteArray())
        val signature = Base64Url.encode(key.sign("$headerB64.$claimsB64".encodeToByteArray()))
        return "$headerB64.$claimsB64.$signature"
    }

    /** The JWK with members in the lexicographic order JOSE canonicalises. */
    private fun jwkJson(key: Es256Key): String {
        val jwk = key.publicJwk()
        return "{\"crv\":\"${jwk["crv"]}\",\"kty\":\"${jwk["kty"]}\",\"x\":\"${jwk["x"]}\",\"y\":\"${jwk["y"]}\"}"
    }

    private fun athOf(accessToken: String): String =
        Base64Url.encode(Sha256.digest(accessToken.encodeToByteArray()))
}
