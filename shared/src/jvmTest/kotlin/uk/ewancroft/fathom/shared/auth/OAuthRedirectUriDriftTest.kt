package uk.ewancroft.fathom.shared.auth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The redirect URI is a contract between three parties: the hosted client
 * metadata, the scheme each app registers with the OS, and the parser that
 * receives the callback. `OAuthRedirect` is the one definition both platforms
 * register from (via the facade), so this test makes sure the metadata agrees
 * with it — a drift would silently strand the callback on a browser "cannot
 * open link" page on one platform while the other works.
 *
 * JVM-only for the same reason as [ClientMetadataDriftTest]: reading the
 * repository's own files is a host affordance.
 */
class OAuthRedirectUriDriftTest {

    private fun metadata(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(4) {
            File(dir, "website/client-metadata.json").takeIf { it.isFile }
                ?.let { return it }
            dir = dir.parentFile ?: return@repeat
        }
        error("client-metadata.json not found from ${System.getProperty("user.dir")}")
    }

    @Test
    fun hostedRedirectIsTheOneBothAppsRegister() {
        val json = metadata().readText()
        val match = Regex("\"redirect_uris\"\\s*:\\s*\\[\"([^\"]*)\"]").find(json)
            ?: error("metadata has no redirect_uris entry")
        assertEquals(OAuthRedirect.REDIRECT_URI, match.groupValues[1])
    }
}
