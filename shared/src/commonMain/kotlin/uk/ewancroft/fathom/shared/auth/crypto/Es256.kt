package uk.ewancroft.fathom.shared.auth.crypto

/**
 * A P-256 key pair for ES256, in transportable raw form.
 *
 * The DPoP key must survive reinstall-persistent secure storage and be
 * re-presentable as a JWK, so it is held as raw scalars — the private
 * scalar `d` and the public point coordinates `x`/`y`, each exactly 32
 * bytes — rather than as a platform key object. Keychain/Keystore wrap
 * these bytes; no other form of the key exists outside them.
 *
 * A raw `d` is dangerous to generate by hand (a bad RNG breaks the curve's
 * security assumptions), so generation goes through [Es256Platform], which
 * uses the platform crypto API. This type is the storage and
 * proof-construction edge of the key, not its manufacturer.
 */
data class Es256Key(val d: ByteArray, val x: ByteArray, val y: ByteArray) {

    init {
        require(d.size == 32 && x.size == 32 && y.size == 32) {
            "P-256 scalars are 32 bytes: d=${d.size} x=${x.size} y=${y.size}"
        }
    }

    /** The public JWK exactly as it appears in a DPoP proof header. */
    fun publicJwk(): Map<String, String> = mapOf(
        "kty" to "EC",
        "crv" to "P-256",
        "x" to Base64Url.encode(x),
        "y" to Base64Url.encode(y),
    )

    /**
     * The RFC 7638 thumbprint: SHA-256 over the canonical JSON with the
     * required members sorted lexicographically (crv, kty, x, y), then
     * unpadded base64url.
     */
    fun thumbprint(): String {
        val canonical =
            "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"${Base64Url.encode(x)}\",\"y\":\"${Base64Url.encode(y)}\"}"
        return Base64Url.encode(Sha256.digest(canonical.encodeToByteArray()))
    }

    /** Signs over the exact bytes of a JWS signing input, returning the ES256 signature. */
    fun sign(signingInput: ByteArray): ByteArray = Es256Platform.sign(this, signingInput)
}

/**
 * The thin platform edge for P-256: keys are generated and signatures are
 * produced by the platform crypto stack (java.security on the shared
 * javaMain source set, the Security framework on iOS). Keys are transported
 * as raw scalars and re-instantiated per signature, because the secure
 * stores persist bytes, not platform key objects.
 */
internal expect object Es256Platform {

    /** Generates a fresh P-256 key pair from the platform CSPRNG. */
    fun generate(): Es256Key

    /** Signs the SHA-256 hashing of `signingInput` with `key`'s private key, returning raw r||s. */
    fun sign(key: Es256Key, signingInput: ByteArray): ByteArray
}

/** Random bytes from the platform's strongest source, for PKCE verifiers and states. */
internal expect fun secureRandomBytes(count: Int): ByteArray

/**
 * Converts an ASN.1 DER ECDSA-Sig-Value (SEQUENCE of two INTEGERs) to the
 * raw 64-byte r||s form JWS ES256 uses. Platform signers emit DER; the
 * conversion is common so a DER quirk cannot differ per platform.
 */
internal fun derToRawSignature(der: ByteArray): ByteArray {
    require(der.size >= 8 && der[0] == 0x30.toByte()) { "not a DER sequence" }
    var i = 2
    require(der[i] == 0x02.toByte()) { "expected INTEGER for r" }
    val rLen = der[i + 1].toInt()
    val r = stripLeadingZeros(der.copyOfRange(i + 2, i + 2 + rLen))
    i += 2 + rLen
    require(der[i] == 0x02.toByte()) { "expected INTEGER for s" }
    val sLen = der[i + 1].toInt()
    val s = stripLeadingZeros(der.copyOfRange(i + 2, i + 2 + sLen))

    require(r.size <= 32 && s.size <= 32) { "P-256 scalars exceed 32 bytes" }
    val out = ByteArray(64)
    r.copyInto(out, 32 - r.size)
    s.copyInto(out, 64 - s.size)
    return out
}

private fun stripLeadingZeros(bytes: ByteArray): ByteArray {
    var start = 0
    while (start < bytes.size - 1 && bytes[start] == 0.toByte()) start++
    return bytes.copyOfRange(start, bytes.size)
}
