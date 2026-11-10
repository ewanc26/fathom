package uk.ewancroft.fathom.shared.auth.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uk.ewancroft.fathom.shared.auth.crypto.Base64Url
import uk.ewancroft.fathom.shared.auth.crypto.Es256Key
import uk.ewancroft.fathom.shared.auth.oauth.OAuthSession

/**
 * The string formats the platform secure stores persist.
 *
 * The strings produced here *are* the credential — an access token, a
 * refresh token and a DPoP private scalar in plaintext JSON (base64url is
 * encoding, not protection). The only code that may hold them is the
 * Keychain/Keystore adapter on each platform; they must never reach
 * preferences, backups, logs, crash reports or the saved-history file.
 *
 * JSON rather than CBOR: the stores persist small opaque strings, and the
 * shared core owns the format so Android and iOS cannot disagree about it.
 */
object AuthStoreFormat {

    @Serializable
    private data class Es256KeyJson(val d: String, val x: String, val y: String)

    @Serializable
    private data class SessionJson(
        val did: String,
        val pdsUrl: String,
        val issuer: String,
        val accessToken: String,
        val refreshToken: String,
        val accessTokenExpiresAt: Long,
        val grantedScope: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun encodeKey(key: Es256Key): String {
        val jwk = key.publicJwk()
        return json.encodeToString(
            Es256KeyJson(d = Base64Url.encode(key.d), x = jwk["x"] ?: "", y = jwk["y"] ?: ""),
        )
    }

    fun decodeKey(text: String): Es256Key? {
        val stored = runCatching { json.decodeFromString<Es256KeyJson>(text) }.getOrNull() ?: return null
        return runCatching {
            Es256Key(
                d = Base64Url.decode(stored.d),
                x = Base64Url.decode(stored.x),
                y = Base64Url.decode(stored.y),
            )
        }.getOrNull()
    }

    fun encodeSession(session: OAuthSession): String = json.encodeToString(
        SessionJson(
            did = session.did,
            pdsUrl = session.pdsUrl,
            issuer = session.issuer,
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            accessTokenExpiresAt = session.accessTokenExpiresAtSeconds,
            grantedScope = session.grantedScope,
        ),
    )

    fun decodeSession(text: String): OAuthSession? {
        val stored = runCatching { json.decodeFromString<SessionJson>(text) }.getOrNull() ?: return null
        return OAuthSession(
            did = stored.did,
            pdsUrl = stored.pdsUrl,
            issuer = stored.issuer,
            accessToken = stored.accessToken,
            refreshToken = stored.refreshToken,
            accessTokenExpiresAtSeconds = stored.accessTokenExpiresAt,
            grantedScope = stored.grantedScope,
        )
    }
}
