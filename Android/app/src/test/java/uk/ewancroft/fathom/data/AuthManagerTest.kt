package uk.ewancroft.fathom.data

import android.app.Application
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import uk.ewancroft.fathom.shared.auth.OAuthCallback
import uk.ewancroft.fathom.shared.auth.PendingAuthorization
import uk.ewancroft.fathom.shared.auth.oauth.OAuthHttp
import uk.ewancroft.fathom.shared.auth.oauth.OAuthHttpCall
import uk.ewancroft.fathom.shared.auth.oauth.OAuthHttpReply

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthManagerTest {

    private class FakeReads : TealReadClient {
        override suspend fun resolveIdentity(input: String) = "did:plc:example"
        override suspend fun pdsFor(did: String) = "https://pds.example"
        override suspend fun listRecords(pds: String, repo: String, collection: String, cursor: String?, limit: Int): String =
            throw UnsupportedOperationException()
        override suspend fun getRecord(pds: String, repo: String, collection: String, rkey: String): String =
            throw UnsupportedOperationException()
    }

    private class ScriptedOAuth : OAuthHttp {
        val calls = mutableListOf<OAuthHttpCall>()
        val replies = ArrayDeque<OAuthHttpReply>()

        fun then(status: Int, body: String?, vararg headers: Pair<String, String>) {
            replies += OAuthHttpReply(status, headers.toMap(), body)
        }

        override fun call(call: OAuthHttpCall): OAuthHttpReply {
            calls += call
            return replies.removeFirstOrNull() ?: OAuthHttpReply(0, emptyMap())
        }
    }

    private val metadata = """
        {"issuer":"https://auth.example","authorization_endpoint":"https://auth.example/authorize",
         "token_endpoint":"https://auth.example/token",
         "pushed_authorization_request_endpoint":"https://auth.example/par",
         "revocation_endpoint":"https://auth.example/revoke"}
    """.trimIndent()

    private lateinit var store: SecureStore
    private lateinit var http: ScriptedOAuth
    private lateinit var manager: AuthManager

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication() as android.app.Application
        store = SecureStore(context, AuthManagerTestCipher(), fileName = "auth-manager-test.bin")
        store.clear()
        http = ScriptedOAuth()
        manager = AuthManager(FakeReads(), http, store)
    }

    @Test
    fun signInResolvesThroughParAndHandsBackTheAuthorizeUrl() = runTest {
        http.then(200, metadata)
        http.then(200, """{"request_uri":"urn:ietf:params:oauth:request_uri:1"}""")
        val outcome = manager.signIn("you.example") as AuthManager.SignInOutcome.Browser
        assertTrue(outcome.url.startsWith("https://auth.example/authorize?"))
        assertEquals("https://pds.example/.well-known/oauth-authorization-server", http.calls[0].url)
        assertEquals("https://auth.example/par", http.calls[1].url)
        // No credential exists before the browser ever opens.
        assertNull(manager.stored())
    }

    @Test
    fun aFailedDiscoveryReportsRatherThanOpeningABrowser() = runTest {
        http.then(404, "gone")
        val outcome = manager.signIn("you.example")
        assertTrue(outcome is AuthManager.SignInOutcome.Failed)
    }


    @Test
    fun aRecognisedCallbackIsExchangedAndStored() = runTest {
        http.then(200, metadata)
        http.then(200, """{"request_uri":"urn:ietf:params:oauth:request_uri:1"}""")
        manager.signIn("you.example") as AuthManager.SignInOutcome.Browser
        http.then(200, """{"access_token":"at","refresh_token":"rt","expires_in":600,"scope":"atproto","sub":"did:plc:example"}""")

        // The activity feeds the callback; the outbound state is read from the
        // captured PAR form.
        val state = http.calls[1].form!!["state"]!!
        PendingAuthorization.retain(OAuthCallback.Authorised("code", state))

        val session = manager.completePending()
        assertEquals("did:plc:example", session!!.did)
        assertEquals("did:plc:example", manager.stored()!!.first.did)
        // Once claimed, the callback is gone — a second sweep signs nothing in.
        assertNull(manager.completePending())
    }

    @Test
    fun aSignOutRevokesAndWipesTheStore() = runTest {
        http.then(200, metadata)
        http.then(200, """{"request_uri":"urn:x"}""")
        manager.signIn("you.example")
        val state = http.calls[1].form!!["state"]!!
        http.then(200, """{"access_token":"at","refresh_token":"rt","expires_in":600,"scope":"atproto","sub":"did:plc:example"}""")
        PendingAuthorization.retain(OAuthCallback.Authorised("code", state))
        manager.completePending()

        http.then(200, metadata)
        http.then(200, "{}")
        manager.signOut()

        assertNull(manager.stored())
        assertTrue(http.calls.any { it.url == "https://auth.example/revoke" })
    }
}

/** A trivial non-Keystore cipher: Robolectric has no AndroidKeyStore. */
class AuthManagerTestCipher : AuthCipher {
    override fun encrypt(plain: ByteArray) = ByteArray(plain.size) { (plain[it] + 3).toByte() }
    override fun decrypt(blob: ByteArray) = ByteArray(blob.size) { (blob[it] - 3).toByte() }
}
