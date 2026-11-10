package uk.ewancroft.fathom.shared.auth.storage

import uk.ewancroft.fathom.shared.auth.crypto.Es256Platform
import uk.ewancroft.fathom.shared.auth.oauth.OAuthSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthStoreFormatTest {

    private val session = OAuthSession(
        did = "did:plc:example",
        pdsUrl = "https://pds.example",
        issuer = "https://auth.example",
        accessToken = "access",
        refreshToken = "refresh",
        accessTokenExpiresAtSeconds = 1_772_000_000,
        grantedScope = "atproto repo:fm.teal.feed.play?action=create",
    )

    @Test
    fun roundTripsASessionWithoutLoss() {
        val text = AuthStoreFormat.encodeSession(session)
        val restored = AuthStoreFormat.decodeSession(text)!!
        assertEquals(session.did, restored.did)
        assertEquals(session.pdsUrl, restored.pdsUrl)
        assertEquals(session.issuer, restored.issuer)
        assertEquals(session.accessToken, restored.accessToken)
        assertEquals(session.refreshToken, restored.refreshToken)
        assertEquals(session.accessTokenExpiresAtSeconds, restored.accessTokenExpiresAtSeconds)
        assertEquals(session.grantedScope, restored.grantedScope)
        // The expiry decision survives storage.
        assertTrue(restored.needsRefresh(nowSeconds = session.accessTokenExpiresAtSeconds))
    }

    @Test
    fun roundTripsADpopKeyAsScalars() {
        val key = Es256Platform.generate()
        val restored = AuthStoreFormat.decodeKey(AuthStoreFormat.encodeKey(key))!!
        assertEquals(key.d.toList(), restored.d.toList())
        assertEquals(key.x.toList(), restored.x.toList())
        assertEquals(key.y.toList(), restored.y.toList())
        // The recovered key still makes valid proofs: same public half.
        assertEquals(key.thumbprint(), restored.thumbprint())
    }

    @Test
    fun unparseableTextReadsAsSignedOut() {
        assertNull(AuthStoreFormat.decodeSession(""))
        assertNull(AuthStoreFormat.decodeSession("not json"))
        assertNull(AuthStoreFormat.decodeSession("""{"did":"did:plc:x"}"""))
        assertNull(AuthStoreFormat.decodeKey("{\"d\":\"AA\",\"x\":\"AA\",\"y\":\"AA\"}"), "wrong scalar size rejected")
    }
}
