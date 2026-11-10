package uk.ewancroft.fathom.data

import android.content.Context
import uk.ewancroft.fathom.shared.auth.crypto.Es256Key
import uk.ewancroft.fathom.shared.auth.oauth.OAuthSession
import uk.ewancroft.fathom.shared.auth.storage.AuthStoreFormat
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The one home of the OAuth credential on Android.
 *
 * The session and the DPoP key are serialised by the shared core
 * (`AuthStoreFormat`, the same strings iOS stores) and then encrypted by a
 * hardware-backed AES-GCM key that never leaves the Android Keystore. The
 * ciphertext file lives under `filesDir/fathom`, which the backup rules
 * already exclude — the credential never reaches preferences, backups,
 * logs or the saved-history file.
 *
 * Keystore adapters are untestable on the JVM, so the encryption itself is
 * one seam (`AuthCipher`) and the file/serialise layer is tested with a
 * fake; the production cipher is a thin Keystore call.
 */
class SecureStore(
    context: Context,
    private val cipher: AuthCipher = KeystoreAuthCipher(),
    fileName: String = "auth.bin",
) {
    private val file: File = File(File(context.applicationContext.filesDir, "fathom"), fileName)

    /** Replaces whatever was stored; only the secure store ever holds these strings. */
    fun save(session: OAuthSession, key: Es256Key): Boolean {
        val sessionBytes = AuthStoreFormat.encodeSession(session).encodeToByteArray()
        val keyBytes = AuthStoreFormat.encodeKey(key).encodeToByteArray()
        val plain = ByteArray(4 + sessionBytes.size + keyBytes.size)
        plain.writeInt(0, sessionBytes.size)
        sessionBytes.copyInto(plain, 4)
        keyBytes.copyInto(plain, 4 + sessionBytes.size)

        val encrypted = runCatching { cipher.encrypt(plain) }.getOrNull() ?: return false
        return runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeBytes(encrypted)
            if (!temporary.renameTo(file)) {
                file.delete()
                temporary.renameTo(file)
            }
            true
        }.getOrDefault(false)
    }

    /** The stored session and DPoP key, or null when nothing (or something unreadable) is stored. */
    fun load(): Pair<OAuthSession, Es256Key>? {
        val encrypted = runCatching { file.readBytes() }.getOrNull() ?: return null
        val plain = runCatching { cipher.decrypt(encrypted) }.getOrNull() ?: return null
        if (plain.size < 4) return null
        val sessionLength = plain.readInt(0)
        if (sessionLength < 0 || 4 + sessionLength > plain.size) return null
        val session = AuthStoreFormat.decodeSession(
            plain.copyOfRange(4, 4 + sessionLength).decodeToString(),
        ) ?: return null
        val key = AuthStoreFormat.decodeKey(plain.copyOfRange(4 + sessionLength, plain.size).decodeToString())
            ?: return null
        return session to key
    }

    /** Wipes the credential. Invalid_grant and sign-out both end here (#9, #14). */
    fun clear() {
        runCatching { file.delete() }
    }

    private fun ByteArray.writeInt(offset: Int, value: Int) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = (value and 0xff).toByte()
    }

    private fun ByteArray.readInt(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 24) or
            ((this[offset + 1].toInt() and 0xff) shl 16) or
            ((this[offset + 2].toInt() and 0xff) shl 8) or
            (this[offset + 3].toInt() and 0xff)
}

/** The encryption seam: ciphertext in, ciphertext out. */
interface AuthCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * AES-256-GCM under a key generated inside and never leaving the Android
 * Keystore. Each write takes a fresh IV; the blob is
 * `[12-byte IV][ciphertext + 16-byte tag]`, prefixed by a version byte so a
 * future re-wrap can coexist with devices holding the current blob.
 */
class KeystoreAuthCipher : AuthCipher {
    private val random = SecureRandom()

    private fun key(): SecretKey {
        val store = java.security.KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(TRANSFORMATION, ANDROID_KEYSTORE)
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return byteArrayOf(BLOB_VERSION) + iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        if (blob.size < 1 + IV_LENGTH || blob[0] != BLOB_VERSION) throw IllegalArgumentException("unknown blob")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob.copyOfRange(1, 1 + IV_LENGTH)))
        return cipher.doFinal(blob.copyOfRange(1 + IV_LENGTH, blob.size))
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "fathom-auth"
        const val TRANSFORMATION = "AES"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
        const val BLOB_VERSION: Byte = 1
    }
}
