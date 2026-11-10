package uk.ewancroft.fathom.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uk.ewancroft.fathom.shared.auth.oauth.OAuthHttp
import uk.ewancroft.fathom.shared.auth.oauth.OAuthHttpCall
import uk.ewancroft.fathom.shared.auth.oauth.OAuthHttpReply
import uk.ewancroft.fathom.shared.xrpc.XrpcQuery
import java.util.concurrent.TimeUnit

/**
 * The Android edge of the shared OAuth engine: OkHttp behind the one
 * synchronous [OAuthHttp] seam. The engine decides everything OAuth-shaped;
 * this class only performs the call it is handed.
 *
 * Calls are synchronous, so run them off the main thread (the read transport
 * in [TealClient] follows the same rule). Timeouts are one generous envelope
 * around a whole exchange — Piper uses 45 s, and an authorization round trip
 * is slower than a record read.
 */
class OkHttpOAuthHttp(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build(),
) : OAuthHttp {

    private val formType = "application/x-www-form-urlencoded".toMediaType()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val htmlFormType = "application/x-www-form-urlencoded".toMediaType()

    override fun call(call: OAuthHttpCall): OAuthHttpReply {
        val rawBody = call.rawBody
        val form = call.form
        val body = when {
            rawBody != null -> rawBody.toRequestBody(jsonType)
            form != null -> formEncode(form).toRequestBody(htmlFormType)
            else -> ByteArray(0).toRequestBody(null)
        }

        val builder = Request.Builder()
            .method(call.method, body.takeIf { call.method != "GET" })
            .url(call.url)
        call.headers.forEach { (name, value) -> builder.header(name, value) }

        return try {
            http.newCall(builder.build()).execute().use { response ->
                val headers = response.headers.names()
                    .associateWith { name -> response.headers.values(name).firstOrNull() ?: "" }
                    .mapKeys { it.key.lowercase() }
                OAuthHttpReply(
                    status = response.code,
                    headers = headers,
                    body = response.body?.string(),
                )
            }
        } catch (error: Exception) {
            // The seam's contract: a failed call is a reply with status 0,
            // never an exception across the boundary.
            OAuthHttpReply(status = 0, headers = emptyMap())
        }
    }

    private fun formEncode(form: Map<String, String>): String =
        form.entries.joinToString("&") { (name, value) ->
            XrpcQuery.encode(name) + "=" + XrpcQuery.encode(value)
        }
}
