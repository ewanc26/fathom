package uk.ewancroft.fathom.shared.auth.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Common hex, because kotlin.text hex formatting is JVM-flavoured. */
private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
    for (byte in bytes) {
        val unsigned = byte.toInt() and 0xff
        append("0123456789abcdef"[unsigned ushr 4])
        append("0123456789abcdef"[unsigned and 0xf])
    }
}

class Sha256Test {

    @Test
    fun hashesTheEmptyString() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hex(Sha256.digest(ByteArray(0))),
        )
    }

    @Test
    fun hashesAbc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex(Sha256.digest("abc".encodeToByteArray())),
        )
    }

    @Test
    fun hashesTheTwoBlockVector() {
        // "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
        val input = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex(Sha256.digest(input.encodeToByteArray())),
        )
    }

    @Test
    fun base64UrlRoundTrips() {
        for (length in 0..12) {
            val bytes = ByteArray(length) { (it * 37 + 11).toByte() }
            assertEquals(
                bytes.toList(),
                Base64Url.decode(Base64Url.encode(bytes)).toList(),
                "round trip failed at length $length",
            )
        }
    }

    @Test
    fun base64UrlMatchesRfc4648Vectors() {
        assertEquals("Zg", Base64Url.encode(byteArrayOf(102)))
        assertEquals("Zm8", Base64Url.encode("fo".encodeToByteArray()))
        assertEquals("Zm9v", Base64Url.encode("foo".encodeToByteArray()))
        assertEquals("Zm9vYg", Base64Url.encode("foob".encodeToByteArray()))
        assertEquals("Zm9vYmE", Base64Url.encode("fooba".encodeToByteArray()))
        assertEquals("Zm9vYmFy", Base64Url.encode("foobar".encodeToByteArray()))
    }

    @Test
    fun base64UrlDecodesTheUrlAlphabet() {
        // The URL-safe alphabet maps 62 and 63 to '-' and '_'. Three input
        // characters are 18 bits: exactly two whole bytes, the final two
        // bits ('8' is 60 = 111100) fall away unused.
        val bytes = Base64Url.decode("-_8")
        assertEquals(listOf<Byte>(0xfb.toByte(), 0xff.toByte()), bytes.toList())
    }

    @Test
    fun base64UrlRejectsInvalidInput() {
        assertTrue(runCatching { Base64Url.decode("A") }.isFailure, "length 1 is invalid")
        assertTrue(runCatching { Base64Url.decode("A*B=") }.isFailure, "invalid character rejected")
    }
}
