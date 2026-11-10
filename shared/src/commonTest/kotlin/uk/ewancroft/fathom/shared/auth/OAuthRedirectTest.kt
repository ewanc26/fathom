package uk.ewancroft.fathom.shared.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthRedirectTest {

    @Test
    fun parsesAnAuthorisedRedirectWithIss() {
        val result = OAuthRedirect.parse(
            "uk.ewancroft.fathom:/oauth/callback?code=abc123&state=xyz&iss=https://pds.example",
        )
        val authorised = assertIs<OAuthCallback.Authorised>(result)
        assertEquals("abc123", authorised.code)
        assertEquals("xyz", authorised.state)
        assertEquals("https://pds.example", authorised.iss)
    }

    @Test
    fun parsesAnAuthorisedRedirectWithoutIss() {
        val result = OAuthRedirect.parse(
            "uk.ewancroft.fathom:/oauth/callback?state=xyz&code=abc123",
        )
        val authorised = assertIs<OAuthCallback.Authorised>(result)
        assertEquals("abc123", authorised.code)
        assertNull(authorised.iss)
    }

    @Test
    fun parsesADeniedRedirect() {
        val result = OAuthRedirect.parse(
            "uk.ewancroft.fathom:/oauth/callback?error=access_denied&error_description=User+declined&state=xyz",
        )
        val denied = assertIs<OAuthCallback.Denied>(result)
        assertEquals("access_denied", denied.error)
        // `+` is not rewritten: values here were never form-encoded.
        assertEquals("User+declined", denied.errorDescription)
        assertEquals("xyz", denied.state)
    }

    @Test
    fun parsesADeniedRedirectWithoutState() {
        val result = OAuthRedirect.parse(
            "uk.ewancroft.fathom:/oauth/callback?error=access_denied",
        )
        val denied = assertIs<OAuthCallback.Denied>(result)
        assertEquals("access_denied", denied.error)
        assertNull(denied.state)
    }

    @Test
    fun percentDecodesQueryValues() {
        val result = OAuthRedirect.parse(
            "uk.ewancroft.fathom:/oauth/callback?code=a%2Bb&state=x%20y",
        )
        val authorised = assertIs<OAuthCallback.Authorised>(result)
        assertEquals("a+b", authorised.code)
        assertEquals("x y", authorised.state)
    }

    @Test
    fun rejectsASuccessWithoutState() {
        val result = OAuthRedirect.parse("uk.ewancroft.fathom:/oauth/callback?code=abc123")
        assertEquals(OAuthCallback.NotCallback, result)
    }

    @Test
    fun rejectsAnErrorWithoutAnErrorCode() {
        val result = OAuthRedirect.parse("uk.ewancroft.fathom:/oauth/callback?error=")
        assertEquals(OAuthCallback.NotCallback, result)
    }

    @Test
    fun rejectsOtherURIs() {
        assertTrue(OAuthRedirect.parse("https://fathom.ewancroft.uk/") == OAuthCallback.NotCallback)
        assertTrue(OAuthRedirect.parse("uk.ewancroft.fathom:/oauth") == OAuthCallback.NotCallback)
        assertTrue(OAuthRedirect.parse("uk.ewancroft.fathom:/other/callback?code=a&state=b") == OAuthCallback.NotCallback)
        assertTrue(OAuthRedirect.parse("") == OAuthCallback.NotCallback)
    }

    @Test
    fun matchesTheRedirectContractedCaseInsensitively() {
        val result = OAuthRedirect.parse(
            "UK.EWANCROFT.FATHOM:/oauth/callback?code=abc123&state=xyz",
        )
        assertTrue(result is OAuthCallback.Authorised)
    }

    @Test
    fun pendingAuthorizationRetainsAndTakesOnce() {
        val authorised = OAuthCallback.Authorised("code", "state")
        PendingAuthorization.retain(authorised)
        assertEquals(authorised, PendingAuthorization.take())
        assertNull(PendingAuthorization.take())
    }
}
