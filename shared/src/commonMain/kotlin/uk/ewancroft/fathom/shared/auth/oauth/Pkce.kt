package uk.ewancroft.fathom.shared.auth.oauth

import uk.ewancroft.fathom.shared.auth.crypto.Base64Url
import uk.ewancroft.fathom.shared.auth.crypto.Sha256
import uk.ewancroft.fathom.shared.auth.crypto.secureRandomBytes

/**
 * PKCE (RFC 7636) for a public native client. atproto's OAuth profile
 * requires the S256 method: the plain challenge is forbidden because the
 * redirect URI is a custom scheme anyone can read.
 */
object Pkce {

    /** The unreserved characters RFC 7636 §4.1 limits verifiers to. */
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    /** Generates a verifier of 64 code points (512 bits), inside the 43–128 range. */
    fun verifier(): String {
        val bytes = secureRandomBytes(64)
        return buildString(64) { bytes.forEach { append(ALPHABET[it.toInt() and 63]) } }
    }

    /** The S256 challenge: base64url(SHA-256(ASCII(verifier))), unpadded. */
    fun challenge(verifier: String): String =
        Base64Url.encode(Sha256.digest(verifier.encodeToByteArray()))

    /** An unguessable `state`, same source as the verifier. */
    fun state(): String = Base64Url.encode(secureRandomBytes(24))
}
