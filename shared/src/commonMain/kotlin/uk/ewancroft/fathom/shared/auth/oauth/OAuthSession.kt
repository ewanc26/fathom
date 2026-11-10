package uk.ewancroft.fathom.shared.auth.oauth

import uk.ewancroft.fathom.shared.auth.TealScopes

/**
 * One signed-in account, and every decision an OAuth session can make
 * without the network.
 *
 * The transport produces token responses and clock readings; this type
 * decides what they mean. It carries no platform types so the secure
 * stores on each platform persist it behind one seam (issue #9: the bytes
 * go to Keychain/Keystore through a platform adapter, never to
 * preferences, backups, logs or the saved-history file).
 */
data class OAuthSession(
    /** The account the tokens authorize; never trusted — verified against `sub`. */
    val did: String,
    /** The account's PDS base URL, the `aud` every resource request names. */
    val pdsUrl: String,
    /** The Authorization Server issuer that minted the tokens. */
    val issuer: String,
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAtSeconds: Long,
    /** The scope actually granted; a login that returned less than requested is recorded, not assumed. */
    val grantedScope: String,
) {
    companion object {
        /**
         * The freshness margin applied to the token's lifetime: an access
         * token is treated as expired ten seconds before its stated end so
         * a request built at a skew boundary does not leave with a token
         * the server has already rejected.
         */
        const val EXPIRY_SKEW_SECONDS: Long = 10

        fun fromGrant(
            did: String,
            pdsUrl: String,
            issuer: String,
            grant: TokenGrant,
            nowSeconds: Long,
        ): OAuthSession? {
            // The grant's `sub` is the authorization server's statement of
            // who signed in. Mismatching the account we resolved means the
            // flow was redirected or the server is lying; either way the
            // session is not created.
            if (!grant.sub.equals(did, ignoreCase = true)) return null
            return OAuthSession(
                did = did,
                pdsUrl = pdsUrl,
                issuer = issuer,
                accessToken = grant.accessToken,
                refreshToken = grant.refreshToken,
                accessTokenExpiresAtSeconds = nowSeconds + grant.expiresIn,
                grantedScope = grant.scope,
            )
        }

        /** The refresh grant's shape: a token request with no client secret. */
        fun refreshTokenRequestBody(session: OAuthSession): Map<String, String> = mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to session.refreshToken,
            "client_id" to TealScopes.CLIENT_ID,
        )
    }

    /** True when the access token must not be used again without a refresh. */
    fun needsRefresh(nowSeconds: Long): Boolean =
        nowSeconds >= accessTokenExpiresAtSeconds - EXPIRY_SKEW_SECONDS

    /** True when the granted scope covers every scope the write path requires. */
    fun grantedEverything(scopes: List<String>): Boolean =
        scopes.all { grantedScope.split(' ').contains(it) }
}

/**
 * Per-issuer DPoP nonce memory. A nonce is issued by the server on a
 * `use_dpop_nonce` rejection (and at token issuance), and every subsequent
 * proof to that server must carry it; it rotates on each challenge, so the
 * store is a single slot per issuer and transport code retries the rejected
 * request exactly once with the fresh value.
 */
class DpopNonceStore {
    private val nonces = mutableMapOf<String, String>()

    fun remember(issuer: String, nonce: String) {
        nonces[issuer] = nonce
    }

    fun current(issuer: String): String? = nonces[issuer]

    fun clear() = nonces.clear()
}
