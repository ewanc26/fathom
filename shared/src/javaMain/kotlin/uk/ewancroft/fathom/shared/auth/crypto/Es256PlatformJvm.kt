package uk.ewancroft.fathom.shared.auth.crypto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.ECPublicKey as Pub
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec

/**
 * ES256 on any JVM-backed target (the jvm test target and the Android
 * app), through java.security. Keys travel as raw scalars and are
 * re-instantiated through EC*KeySpec per operation, so one stored form
 * serves both secure stores.
 */
internal actual object Es256Platform {

    private val params: ECParameterSpec by lazy {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(256)
        (generator.generateKeyPair().public as Pub).params
    }

    actual fun generate(): Es256Key {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(256)
        val pair = generator.generateKeyPair()
        val public = pair.public as Pub
        val private = pair.private as ECPrivateKey
        return Es256Key(scalar(private.s.toByteArray()), scalar(public.w.affineX.toByteArray()), scalar(public.w.affineY.toByteArray()))
    }

    actual fun sign(key: Es256Key, signingInput: ByteArray): ByteArray {
        val factory = KeyFactory.getInstance("EC")
        val private = factory.generatePrivate(ECPrivateKeySpec(BigInteger(1, key.d), params))
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(private)
        signer.update(signingInput)
        return derToRawSignature(signer.sign())
    }

    /** Left-pads a big-endian scalar to exactly 32 bytes (BI strips, DER adds). */
    private fun scalar(raw: ByteArray): ByteArray {
        val unsigned = if (raw.size > 32 && raw.first() == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        return ByteArray(32).also { unsigned.copyInto(it, 32 - unsigned.size) }
    }

    /** Reconstructs the public key, so JVM tests can verify signatures directly. */
    fun publicKeyOf(key: Es256Key): ECPublicKey = KeyFactory.getInstance("EC").generatePublic(
        ECPublicKeySpec(ECPoint(BigInteger(1, key.x), BigInteger(1, key.y)), params),
    ) as ECPublicKey

    /** Verifies a raw r||s signature, for tests and future needs. */
    fun verify(key: Es256Key, signingInput: ByteArray, rawSignature: ByteArray): Boolean {
        val der = rawToDer(rawSignature)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initVerify(publicKeyOf(key))
        signer.update(signingInput)
        return signer.verify(der)
    }
}

/** Packs raw r||s back into DER, for java.security verification. */
internal fun rawToDer(raw: ByteArray): ByteArray {
    val r = raw.copyOfRange(0, 32)
    val s = raw.copyOfRange(32, 64)
    val rDer = derInteger(r)
    val sDer = derInteger(s)
    return byteArrayOf(0x30, (rDer.size + sDer.size).toByte()) + rDer + sDer
}

private fun derInteger(value: ByteArray): ByteArray {
    // A DER INTEGER is signed: prepend a zero byte only when the value's
    // high bit is set (a negative Kotlin Byte), so it reads as positive.
    val padded = if (value.first() < 0) byteArrayOf(0) + value else value
    return byteArrayOf(0x02, padded.size.toByte()) + padded
}

internal actual fun secureRandomBytes(count: Int): ByteArray {
    val bytes = ByteArray(count)
    SecureRandom().nextBytes(bytes)
    return bytes
}
