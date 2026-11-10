package uk.ewancroft.fathom.data

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import uk.ewancroft.fathom.shared.auth.crypto.Es256Key
import uk.ewancroft.fathom.shared.auth.oauth.OAuthSession
import java.io.File

/**
 * The file, envelope and shared-format layers of [SecureStore], with the
 * Keystore cipher faked: Robolectric has no AndroidKeyStore, which is exactly
 * why the cipher is one seam. The fake is a trivial byte rotation — enough
 * to prove the store never stores or trusts plaintext, and that a flipped
 * ciphertext bit surfaces as unreadable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureStoreTest {

    /** Deterministic, non-Keystore stand-in. Treated as opaque by the store. */
    private class RotatingCipher : AuthCipher {
        val encryptCalls = mutableListOf<ByteArray>()

        override fun encrypt(plain: ByteArray): ByteArray {
            encryptCalls += plain
            return ByteArray(plain.size) { i -> (plain[i] + 7).toByte() }
        }

        override fun decrypt(blob: ByteArray): ByteArray =
            ByteArray(blob.size) { i -> (blob[i] - 7).toByte() }
    }

    private lateinit var application: Application
    private lateinit var cipher: RotatingCipher
    private lateinit var store: SecureStore

    private fun session() = OAuthSession(
        did = "did:plc:example",
        pdsUrl = "https://pds.example",
        issuer = "https://auth.example",
        accessToken = "access-token",
        refreshToken = "refresh-token",
        accessTokenExpiresAtSeconds = 1_000L,
        grantedScope = "atproto",
    )

    /** Raw scalar sizes are the only invariant the store enforces; no signing happens here. */
    private fun key(): Es256Key = Es256Key(
        d = ByteArray(32) { it.toByte() },
        x = ByteArray(32) { (it + 1).toByte() },
        y = ByteArray(32) { (it + 2).toByte() },
    )

    private fun storedFile(): File = File(File(application.filesDir, "fathom"), "auth-test.bin")

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        cipher = RotatingCipher()
        store = SecureStore(application, cipher, fileName = "auth-test.bin")
        store.clear()
    }

    @Test
    fun roundTripsASessionAndItsDpopKey() {
        assertTrue(store.save(session(), key()))

        val loaded = store.load() ?: throw AssertionError("nothing stored")
        assertEquals("did:plc:example", loaded.first.did)
        assertEquals("refresh-token", loaded.first.refreshToken)
        assertEquals(1_000L, loaded.first.accessTokenExpiresAtSeconds)
        assertEquals(32, loaded.second.d.size)
        assertEquals(1.toByte(), loaded.second.x[0])
        assertEquals(2.toByte(), loaded.second.y[0])
    }

    @Test
    fun aSaveReplacesThePreviousSession() {
        assertTrue(store.save(session(), key()))
        assertTrue(store.save(session().copy(refreshToken = "rotated"), key()))
        assertEquals("rotated", store.load()!!.first.refreshToken)
    }

    @Test
    fun thePlaintextNeverReachesTheFileUntouched() {
        assertTrue(store.save(session(), key()))
        val stored = storedFile().readBytes()

        val token = "access-token".encodeToByteArray()
        var found = false
        outer@ for (i in 0..stored.size - token.size) {
            for (j in token.indices) {
                if (stored[i + j] != token[j]) continue@outer
            }
            found = true
            break
        }
        assertFalse("credential bytes found in the stored file", found)
    }

    @Test
    fun corruptedCiphertextReadsAsSignedOut() {
        assertTrue(store.save(session(), key()))
        val file = storedFile()
        val blob = file.readBytes()
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte()
        file.writeBytes(blob)

        assertNull(store.load())
    }

    @Test
    fun clearRemovesEverything() {
        assertTrue(store.save(session(), key()))
        store.clear()
        assertNull(store.load())
        assertFalse(storedFile().exists())
    }
}
