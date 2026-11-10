package uk.ewancroft.fathom.shared.auth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `website/client-metadata.json` is what an Authorization Server reads, and
 * `Scopes.kt` is what the client will actually request. If they drift, the
 * server grants the wrong thing and login fails at the least convenient
 * moment, so this test fails the build instead.
 *
 * It lives in `jvmTest` rather than `commonTest` because reading the repo's
 * own files is a JVM-only affordance; the scopes themselves stay in
 * commonMain so both apps compile against one definition.
 */
class ClientMetadataDriftTest {

    private fun metadata(): File {
        // Tests run with the module directory as the working directory
        // (Gradle sets it to the tested project's dir), but walk up anyway so
        // the test also works from the Android wrapper or a repo-root run.
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(4) {
            File(dir, "website/client-metadata.json").takeIf { it.isFile }
                ?.let { return it }
            dir = dir.parentFile ?: return@repeat
        }
        error("client-metadata.json not found from ${System.getProperty("user.dir")}")
    }

    private fun field(json: String, name: String): String {
        val match = Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(json)
            ?: error("metadata has no string field '$name'")
        return match.groupValues[1]
    }

    @Test
    fun hostedScopeMatchesTealScopes() {
        assertEquals(TealScopes.fullWriteRequest, field(metadata().readText(), "scope"))
    }

    @Test
    fun nativeClientDeclaresNoTokenEndpointAuthMethod() {
        // A public native client has no secret; omitting this field would
        // default it to client_secret_basic and break the token request.
        assertEquals("none", field(metadata().readText(), "token_endpoint_auth_method"))
    }

    @Test
    fun statusScopeAllowsReplacingAnExistingRecord() {
        assertEquals(
            "repo:fm.teal.actor.status?action=create&action=update&action=delete",
            TealScopes.STATUS_WRITE,
        )
    }

    @Test
    fun everyScopeInMetadataIsKnownToTheClient() {
        val known = TealScopes.all.toSet()
        val json = metadata().readText()
        field(json, "scope").split(' ').forEach { scope ->
            assertTrue(known.contains(scope), "unknown scope in metadata: $scope")
        }
    }
}
