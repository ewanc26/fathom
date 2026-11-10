package uk.ewancroft.fathom.shared.auth.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class Es256Test {

    @Test
    fun generatesAThumbprintInRfc7638Form() {
        val key = Es256Platform.generate()
        // The shape is asserted here; the value is cross-checked on the JVM
        // against java.security in Es256JvmCrossCheckTest, which is not
        // circular.
        assertTrue(key.thumbprint().length in 42..44, "base64url of 32 bytes, unpadded")
        assertNotEquals("", key.thumbprint())
    }

    @Test
    fun publicJwkListsTheCurveAndPoint() {
        val key = Es256Platform.generate()
        val jwk = key.publicJwk()
        assertEquals("EC", jwk["kty"])
        assertEquals("P-256", jwk["crv"])
        assertEquals(43, jwk["x"]?.length, "32 bytes of base64url")
        assertEquals(43, jwk["y"]?.length)
    }

    @Test
    fun generatedKeysAreFresh() {
        val a = Es256Platform.generate()
        val b = Es256Platform.generate()
        assertNotEquals(a.d.toList(), b.d.toList(), "the CSPRNG must not repeat")
    }
}
