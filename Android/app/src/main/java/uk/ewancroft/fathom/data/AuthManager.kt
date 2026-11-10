package uk.ewancroft.fathom.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.ewancroft.fathom.shared.auth.PendingAuthorization
import uk.ewancroft.fathom.shared.auth.oauth.AuthorizationServerMetadata
import uk.ewancroft.fathom.shared.auth.crypto.Es256Key
import uk.ewancroft.fathom.shared.auth.oauth.OAuthEngine
import uk.ewancroft.fathom.shared.auth.oauth.OAuthHttp
import uk.ewancroft.fathom.shared.auth.oauth.OAuthSession
import uk.ewancroft.fathom.shared.auth.oauth.epochSeconds

/**
 * The sign-in state machine Android drives: resolve the identity to its PDS,
 * discover the authorization server, start the flow, claim the callback the
 * manifest already routes here (#27), and keep or clear the credential in
 * [SecureStore]. Every OAuth rule lives in the shared engine; this class is
 * sequencing and storage only.
 *
 * The authorization attempt is held in memory, deliberately: a PAR session
 * is short-lived, so a process death mid-flow costs a re-sign-in rather than
 * a stored half-state. The callback itself is claimed from
 * [PendingAuthorization], which the activity has already fed.
 */
class AuthManager(
    private val reads: TealReadClient,
    private val http: OAuthHttp,
    private val store: SecureStore,
) {
    private val engine = OAuthEngine(http) { epochSeconds() }
    private var attempt: uk.ewancroft.fathom.shared.auth.oauth.AuthorizationAttempt? = null

    /** The signed-in credential, from the secure store. */
    fun stored(): Pair<OAuthSession, Es256Key>? = runCatching { store.load() }.getOrNull()

    sealed interface SignInOutcome {
        /** The URL the platform hands to the browser; sign-in is now awaiting the callback. */
        data class Browser(val url: String) : SignInOutcome

        data class Failed(val reason: String) : SignInOutcome
    }

    /**
     * Resolves [handleOrDid] to its account and PDS, discovers its
     * authorization server and pushes the PAR request. On success the
     * platform opens [SignInOutcome.Browser.url] in the system browser;
     * no credential exists yet.
     */
    suspend fun signIn(handleOrDid: String): SignInOutcome = withContext(Dispatchers.IO) {
        val did = try {
            reads.resolveIdentity(handleOrDid)
        } catch (error: Exception) {
            return@withContext SignInOutcome.Failed(error.message ?: "cannot resolve the account")
        }
        val pds = try {
            reads.pdsFor(did)
        } catch (error: Exception) {
            return@withContext SignInOutcome.Failed(error.message ?: "cannot resolve the data server")
        }
        val metadata: AuthorizationServerMetadata = engine.authorizationServer(pds)
            ?: return@withContext SignInOutcome.Failed("the data server offers no supported sign-in")
        val started = engine.startAuthorization(did, pds, metadata)
            ?: return@withContext SignInOutcome.Failed("the authorization server refused the request")
        attempt = started
        SignInOutcome.Browser(started.authorizeUrl)
    }

    /** The authorization server's grant, if a recognised callback has arrived. */
    fun completePending(): OAuthSession? {
        val callback = PendingAuthorization.take() ?: return null
        val pending = attempt ?: return null
        return when (val result = engine.completeLogin(pending, callback)) {
            is uk.ewancroft.fathom.shared.auth.oauth.LoginResult.Success -> {
                store.save(result.session, pending.key)
                attempt = null
                result.session
            }
            else -> null
        }
    }

    /**
     * Revokes the refresh token at the authorization server and wipes the
     * store either way: revocation is best-effort on the wire, absolute on
     * the device (#9, #14).
     */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        val credential = store.load() ?: return@withContext
        engine.revoke(credential.first, credential.second)
        store.clear()
        attempt = null
        Unit
    }

    /** Refreshes the stored access token through the auth manager's engine. */
    suspend fun refreshIfStale(): OAuthSession? = withContext(Dispatchers.IO) {
        val credential = store.load() ?: return@withContext null
        if (!credential.first.needsRefresh(epochSeconds())) return@withContext credential.first
        when (val rotated = engine.refresh(credential.first, credential.second)) {
            is uk.ewancroft.fathom.shared.auth.oauth.RefreshResult.Rotated -> {
                store.save(rotated.session, credential.second)
                rotated.session
            }
            else -> null
        }
    }

}
