package uk.ewancroft.fathom.shared.auth.crypto

/**
 * Unpadded base64url (RFC 4648 §5), the only encoding JOSE uses.
 */
object Base64Url {

    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i + 3 <= bytes.size) {
            val n = ((bytes[i].toInt() and 0xff) shl 16) or
                ((bytes[i + 1].toInt() and 0xff) shl 8) or
                (bytes[i + 2].toInt() and 0xff)
            out.append(alphabet[n ushr 18 and 63])
            out.append(alphabet[n ushr 12 and 63])
            out.append(alphabet[n ushr 6 and 63])
            out.append(alphabet[n and 63])
            i += 3
        }
        val remainder = bytes.size - i
        if (remainder == 1) {
            val n = (bytes[i].toInt() and 0xff) shl 16
            out.append(alphabet[n ushr 18 and 63])
            out.append(alphabet[n ushr 12 and 63])
        } else if (remainder == 2) {
            val n = ((bytes[i].toInt() and 0xff) shl 16) or ((bytes[i + 1].toInt() and 0xff) shl 8)
            out.append(alphabet[n ushr 18 and 63])
            out.append(alphabet[n ushr 12 and 63])
            out.append(alphabet[n ushr 6 and 63])
        }
        return out.toString()
    }

    fun decode(text: String): ByteArray {
        require(text.length % 4 != 1) { "invalid base64url length ${text.length}" }
        val out = ByteArray((text.length * 3 + 3) / 4)
        var buffer = 0
        var bits = 0
        var o = 0
        for (c in text) {
            val v = alphabet.indexOf(c)
            require(v >= 0) { "invalid base64url character '$c'" }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[o++] = (buffer ushr bits).toByte()
            }
        }
        return out.copyOf(o)
    }
}
