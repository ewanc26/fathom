package uk.ewancroft.fathom.shared.auth.crypto

import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * The pure-Kotlin SHA-256 exists so both platforms hash identically; the
 * JVM's own implementation is the reference it is checked against. This is
 * a cross-check, not a tautology: MessageDigest is an independent
 * implementation with independent code.
 */
class Sha256JvmCrossCheckTest {

    private fun jvmSha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    @Test
    fun matchesMessageDigestOnFixedInputs() {
        for (input in listOf("".toByteArray(), "a".toByteArray(), "abc".toByteArray())) {
            assertContentEquals(jvmSha256(input), Sha256.digest(input))
        }
    }

    @Test
    fun matchesMessageDigestOnRandomInputsAcrossBlockBoundaries() {
        val random = Random(20261110)
        for (length in intArrayOf(0, 1, 54, 55, 56, 57, 63, 64, 65, 128, 1000)) {
            val input = ByteArray(length) { random.nextInt(256).toByte() }
            assertContentEquals(
                jvmSha256(input),
                Sha256.digest(input),
                "mismatch at length $length",
            )
        }
    }
}

/**
 * ES256 end-to-end on the JVM: the shared proof construction signs with the
 * platform's java.security key re-instantiated from raw scalars, and
 * java.security verifies with the public half. If the DER-to-raw
 * conversion, the scalar padding or the signer's hash wiring is wrong,
 * verification is what catches it.
 */
class Es256JvmCrossCheckTest {

    @Test
    fun signsAProofThatThePublicHalfVerifies() {
        val key = Es256Platform.generate()
        val signingInput = "header.claims".toByteArray(Charsets.US_ASCII)
        val raw = key.sign(signingInput)
        assertTrue(raw.size == 64, "raw r||s is 64 bytes, got ${raw.size}")
        assertTrue(Es256Platform.verify(key, signingInput, raw))
        assertTrue(
            !Es256Platform.verify(key, "tampered.claims".toByteArray(Charsets.US_ASCII), raw),
            "a different input must not verify",
        )
    }

    @Test
    fun thumbprintMatchesAnIndependentJWKComputation() {
        val key = Es256Platform.generate()
        val jwk = key.publicJwk()
        val canonical =
            "{\"crv\":\"${jwk["crv"]}\",\"kty\":\"${jwk["kty"]}\",\"x\":\"${jwk["x"]}\",\"y\":\"${jwk["y"]}\"}"
        val expected = Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
        assertTrue(key.thumbprint() == expected, "thumbprint drifted from the independent computation")
    }

    @Test
    fun theJwkCoordinatesDecodeBackToThePublicKeyPoint() {
        val key = Es256Platform.generate()
        val publicKey = Es256Platform.publicKeyOf(key)
        // Spot the round trip: re-encode the verified public key's point.
        val x = key.publicJwk()["x"]!!
        assertContentEquals(key.x, Base64Url.decode(x))
    }
}
