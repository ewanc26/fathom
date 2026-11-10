package uk.ewancroft.fathom.shared.auth.crypto

import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Security.*

/**
 * ES256 on iOS through the Security framework. Keys travel as raw scalars
 * (the storage form that persists in the Keychain) and are re-instantiated
 * into SecKey objects per signature through the ANSI X9.63 external
 * representation SecKey accepts (`0x04 || d || x || y`).
 *
 * SecKeyCreateSignature with the *Message* X962 SHA-256 variant hashes the
 * input itself and emits x9.62 DER — the same behaviour as java.security's
 * SHA256withECDSA — so both platforms sign the same bytes and both go
 * through the common DER-to-raw conversion.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object Es256Platform {

    actual fun generate(): Es256Key {
        memScoped {
            val error = alloc<CFErrorRefVar>()
            val private = SecKeyCreateRandomKey(keyAttributes(), error.ptr)
                ?: throw secFailure("SecKeyCreateRandomKey", error.value)
            val representation = SecKeyCopyExternalRepresentation(private, error.ptr)
                ?: throw secFailure("SecKeyCopyExternalRepresentation", error.value)
            val blob = toByteArray(representation)
            // X9.63 private form: 0x04 || d(32) || x(32) || y(32)
            check(blob.size == 97 && blob[0] == 0x04.toByte()) { "unexpected P-256 blob size ${blob.size}" }
            return Es256Key(
                blob.copyOfRange(1, 33),
                blob.copyOfRange(33, 65),
                blob.copyOfRange(65, 97),
            )
        }
    }

    actual fun sign(key: Es256Key, signingInput: ByteArray): ByteArray {
        memScoped {
            val error = alloc<CFErrorRefVar>()
            val signature = SecKeyCreateSignature(
                secKeyOf(key),
                kSecKeyAlgorithmECDSASignatureMessageX962SHA256,
                toCFData(signingInput),
                error.ptr,
            ) ?: throw secFailure("SecKeyCreateSignature", error.value)
            return derToRawSignature(toByteArray(signature))
        }
    }

    private fun secKeyOf(key: Es256Key): SecKeyRef {
        memScoped {
            val error = alloc<CFErrorRefVar>()
            val blob = byteArrayOf(0x04) + key.d + key.x + key.y
            return SecKeyCreateWithData(toCFData(blob), keyAttributes(), error.ptr)
                ?: throw secFailure("SecKeyCreateWithData", error.value)
        }
    }

    /** `{"kSecAttrKeyType": ECSECPrimeRandom, "kSecAttrKeySizeInBits": 256}` as a CFDictionary. */
    private fun keyAttributes(): CFDictionaryRef = memScoped {
        val dictionary = CFDictionaryCreateMutable(null, 4, null, null)
            ?: throw secFailure("CFDictionaryCreateMutable", null)
        val size = CFNumberCreate(null, kCFNumberSInt32Type, alloc<IntVar>().also { it.value = 256 }.ptr)
        CFDictionaryAddValue(dictionary, platform.Security.kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
        CFDictionaryAddValue(dictionary, platform.Security.kSecAttrKeySizeInBits, size)
        CFDictionaryAddValue(dictionary, kSecAttrKeyClass, kSecAttrKeyClassPrivate)
        dictionary
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(count: Int): ByteArray {
    val bytes = ByteArray(count)
    bytes.usePinned { pinned ->
        val status = SecRandomCopyBytes(null, count.convert(), pinned.addressOf(0).reinterpret<UByteVar>())
        if (status != 0) throw IllegalStateException("SecRandomCopyBytes failed: $status")
    }
    return bytes
}

@OptIn(ExperimentalForeignApi::class)
private fun toCFData(bytes: ByteArray): CFDataRef = bytes.usePinned { pinned ->
    CFDataCreate(null, pinned.addressOf(0).reinterpret(), bytes.size.convert())
        ?: throw IllegalStateException("CFDataCreate failed")
}

@OptIn(ExperimentalForeignApi::class)
private fun toByteArray(data: CFDataRef): ByteArray {
    val length = CFDataGetLength(data).convert<Int>()
    val pointer = CFDataGetBytePtr(data) ?: throw IllegalStateException("CFData has no byte pointer")
    return pointer.readBytes(length)
        ?: throw IllegalStateException("CFData byte read failed for length $length")
}

@OptIn(ExperimentalForeignApi::class)
private fun secFailure(operation: String, error: CFErrorRef?): IllegalStateException =
    IllegalStateException("$operation failed: code ${if (error == null) "unknown" else (CFErrorGetCode(error))}")
