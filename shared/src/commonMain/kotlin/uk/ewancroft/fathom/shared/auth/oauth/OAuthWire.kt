package uk.ewancroft.fathom.shared.auth.oauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The reduced Authorization Server metadata an atproto client acts on
 * (RFC 8414, plus the atproto OAuth profile's requirement that the client
 * use PAR when the server declares it). Tolerant on the optional fields,
 * strict on the types of the ones that matter — a string where an
 * endpoint belongs is corruption, not a quirk (AGENTS.md principle 4).
 */
data class AuthorizationServerMetadata(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val pushedAuthorizationRequestEndpoint: String?,
    val revocationEndpoint: String?,
    val scopesSupported: List<String>,
    val requiresPushedAuthorizationRequests: Boolean,
) {
    companion object {
        fun parse(json: String): AuthorizationServerMetadata? {
            val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null

            fun required(name: String): String? =
                (obj[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

            val issuer = required("issuer") ?: return null
            val authorizationEndpoint = required("authorization_endpoint") ?: return null
            val tokenEndpoint = required("token_endpoint") ?: return null
            val scopes = (obj["scopes_supported"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
                ?: emptyList()

            return AuthorizationServerMetadata(
                issuer = issuer,
                authorizationEndpoint = authorizationEndpoint,
                tokenEndpoint = tokenEndpoint,
                pushedAuthorizationRequestEndpoint = required("pushed_authorization_request_endpoint"),
                revocationEndpoint = required("revocation_endpoint"),
                scopesSupported = scopes,
                requiresPushedAuthorizationRequests =
                    obj["require_pushed_authorization_requests"]?.jsonPrimitive?.booleanOrNull
                        ?: (required("pushed_authorization_request_endpoint") != null),
            )
        }
    }
}

/**
 * The token endpoint response. `refresh_token` is absent on responses that
 * do not rotate, which atproto's profile makes an error for requesters that
 * sent one, so it is required here — a login that cannot renew is treated
 * as failed as soon as it appears rather than at first refresh.
 */
data class TokenGrant(
    val accessToken: String,
    val refreshToken: String,
    /** The PDS-issued access-token lifetime in seconds. */
    val expiresIn: Long,
    val scope: String,
    /** The DID the tokens authorize; compared against the account we resolved. */
    val sub: String,
) {
    companion object {
        fun parse(json: String): TokenGrant? {
            val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null

            fun required(name: String): String? =
                (obj[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

            val access = required("access_token") ?: return null
            val refresh = required("refresh_token") ?: return null
            val sub = required("sub") ?: return null
            val scope = required("scope")
            val expiresIn = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: return null
            return TokenGrant(access, refresh, expiresIn, scope ?: "", sub)
        }
    }
}

/**
 * The single error redirect shape OAuth token endpoints answer with:
 * `{"error":"invalid_grant","error_description":"…"}`. atproto additionally
 * rejects with `use_dpop_nonce` at the token endpoint and on resource
 * requests; that is a challenge, not a failure, and `error` names it.
 */
data class OAuthError(val error: String, val description: String?) {
    val isInvalidGrant: Boolean get() = error == "invalid_grant"
    val isDpopNonceChallenge: Boolean get() = error == "use_dpop_nonce"

    companion object {
        fun parse(json: String): OAuthError? {
            val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null

            fun required(name: String): String? =
                (obj[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

            val error = required("error") ?: return null
            val description = required("error_description")
            return OAuthError(error, description)
        }
    }
}
