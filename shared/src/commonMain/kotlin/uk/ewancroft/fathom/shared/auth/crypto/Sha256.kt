package uk.ewancroft.fathom.shared.auth.crypto

/**
 * SHA-256, in pure Kotlin.
 *
 * The OAuth core needs a hash in three places where no platform type may
 * appear (this module's "no platform types" rule): the PKCE S256 challenge,
 * the RFC 7638 JWK thumbprint, and the DPoP `ath` — and it must be the same
 * implementation on both platforms or the values simply stop verifying.
 * FIPS 180-4 is a page of arithmetic; the round constants are checked in
 * against the well-known published values rather than computed at runtime
 * (`cbrt` is not correctly rounded, so a runtime derivation could differ by
 * an ULP between platforms and silently break every hash). The JVM's own
 * implementation cross-checks this one in jvmTest.
 */
object Sha256 {

    fun digest(input: ByteArray): ByteArray {
        // Padding: 0x80, zeros, then the original bit length as a 64-bit big-endian.
        val bitLength = input.size.toLong() * 8
        val padded = ByteArray(((input.size + 9 + 63) / 64) * 64)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        for (shift in 0 until 8) {
            padded[padded.size - 1 - shift] = (bitLength ushr (8 * shift)).toByte()
        }

        val state = intArrayOf(
            1779033703, -1150833019, 1013904242, -1521486534,
            1359893119, -1694144372, 528734635, 1541459225,
        )
        val k = intArrayOf(
            1116352408, 1899447441, -1245643825, -373957723, 961987163, 1508970993, -1841331548, -1424204075,
            -670586216, 310598401, 607225278, 1426881987, 1925078388, -2132889090, -1680079193, -1046744716,
            -459576895, -272742522, 264347078, 604807628, 770255983, 1249150122, 1555081692, 1996064986,
            -1740746414, -1473132947, -1341970488, -1084653625, -958395405, -710438585, 113926993, 338241895,
            666307205, 773529912, 1294757372, 1396182291, 1695183700, 1986661051, -2117940946, -1838011259,
            -1564481375, -1474664885, -1035236496, -949202525, -778901479, -694614492, -200395387, 275423344,
            430227734, 506948616, 659060556, 883997877, 958139571, 1322822218, 1537002063, 1747873779,
            1955562222, 2024104815, -2067236844, -1933114872, -1866530822, -1538233109, -1090935817, -965641998,
        )

        val w = IntArray(64)
        for (blockStart in padded.indices step 64) {
            for (i in 0 until 16) {
                val o = blockStart + i * 4
                w[i] = ((padded[o].toInt() and 0xff) shl 24) or
                    ((padded[o + 1].toInt() and 0xff) shl 16) or
                    ((padded[o + 2].toInt() and 0xff) shl 8) or
                    (padded[o + 3].toInt() and 0xff)
            }
            for (i in 16 until 64) {
                val s0 = (w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18)) xor (w[i - 15] ushr 3)
                val s1 = (w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19)) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }

            var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
            var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]

            for (i in 0 until 64) {
                val sum1 = (e.rotateRight(6) xor e.rotateRight(11)) xor e.rotateRight(25)
                val choice = (e and f) xor (e.inv() and g)
                val t1 = h + sum1 + choice + k[i] + w[i]
                val sum0 = (a.rotateRight(2) xor a.rotateRight(13)) xor a.rotateRight(22)
                val majority = (a and b) xor (a and c) xor (b and c)
                val t2 = sum0 + majority

                h = g; g = f; f = e
                e = d + t1
                d = c; c = b; b = a
                a = t1 + t2
            }

            state[0] += a; state[1] += b; state[2] += c; state[3] += d
            state[4] += e; state[5] += f; state[6] += g; state[7] += h
        }

        return ByteArray(32) { i -> (state[i / 4] ushr (24 - (i % 4) * 8)).toByte() }
    }
}
