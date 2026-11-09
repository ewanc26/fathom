package uk.ewancroft.fathom.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uk.ewancroft.fathom.BuildConfig
import uk.ewancroft.fathom.data.Dashboard
import uk.ewancroft.fathom.shared.FathomCore
import uk.ewancroft.fathom.shared.auth.Provenance
import uk.ewancroft.fathom.ui.components.Monogram
import uk.ewancroft.fathom.ui.components.Notice
import uk.ewancroft.fathom.ui.components.Panel

/**
 * Who the dashboard is about, and what it is reading.
 *
 * The namespace line matters more than it looks: an account mid-migration
 * holds plays in both `fm.teal.*` and `fm.teal.alpha.*`, and someone
 * comparing Fathom's total against teal.fm's needs to know both are counted.
 */
@Composable
fun ProfileScreen(
    dashboard: Dashboard,
    reading: Boolean,
    onReadEverything: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val profile = dashboard.profile
    val name = profile?.nameOrNull()
        ?: dashboard.handle
        ?: dashboard.did
    val uriHandler = LocalUriHandler.current
    var showAbout by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Monogram(name)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = dashboard.handle ?: dashboard.did,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                profile?.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                profile?.featuredItem?.let { featured ->
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Featured ${featured.type ?: "item"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    featured.mbid?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Panel {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Account",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Detail("DID", dashboard.did)
                Detail("Handle", dashboard.handle ?: "Resolved from the DID")
                Detail("Plays read", dashboard.plays.size.toString())
                Detail(
                    "Profile record",
                    if (profile != null) "Present" else "None — showing the handle instead",
                )
            }
        }

        Panel {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "How these figures are made",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Notice(
                    "${Provenance.COMPUTED_LOCALLY}. teal.fm's own stats endpoints " +
                        "aggregate across the network, so a figure here and a figure " +
                        "on teal.fm are not the same measurement.",
                )
                Text(
                    text = "Reads ${Provenance.PRODUCTION_NAMESPACE} and the older " +
                        "${Provenance.LEGACY_NAMESPACE}, unauthenticated, from this " +
                        "account's PDS.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AboutPanel(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            onOpenSource = { uriHandler.openUri(FathomCore.sourceUrl()) },
            onOpenPrivacy = { uriHandler.openUri(FathomCore.privacyUrl()) },
            onOpenTerms = { uriHandler.openUri(FathomCore.termsUrl()) },
            onOpenSupport = { uriHandler.openUri(FathomCore.supportUrl()) },
            onOpenLicence = { showAbout = true },
        )

        OutlinedButton(
            onClick = onReadEverything,
            enabled = !reading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Read everything")
        }

        Text(
            text = "Refresh reads only new plays. Read everything re-reads the " +
                "whole history to pick up edited or deleted records.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
            Text("Disconnect")
        }

        Text(
            text = "Fathom keeps one copy of your public history on this device so " +
                "the next launch opens instantly. It is excluded from device " +
                "backups. Disconnecting deletes it along with the handle.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

@Composable
private fun AboutPanel(
    versionName: String,
    versionCode: Int,
    onOpenSource: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenTerms: () -> Unit,
    onOpenSupport: () -> Unit,
    onOpenLicence: () -> Unit,
) {
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "About",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Version $versionName ($versionCode)",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = FathomCore.appDescription(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenLicence) { Text("Licence and notices") }
            TextButton(onClick = onOpenSource) { Text("Source code") }
            TextButton(onClick = onOpenPrivacy) { Text("Privacy policy") }
            TextButton(onClick = onOpenTerms) { Text("Terms of use") }
            TextButton(onClick = onOpenSupport) { Text("Support and feedback") }
            Text(
                text = FathomCore.copyright(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The licence has to be readable with no network, so the AGPL text is an
 * asset in the APK rather than a link. The third-party notices come from the
 * shared core so both platforms carry the same list.
 */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val licence = remember {
        runCatching {
            context.assets.open("AGPL-3.0.txt").bufferedReader().use { it.readText() }
        }.getOrElse {
            "The licence text could not be read from this build."
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About Fathom") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = FathomCore.copyright(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = FathomCore.licenceName(),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text(
                    text = "Third-party notices",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = FathomCore.thirdPartyNotices(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    text = "GNU Affero General Public License v3.0",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = licence,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun Detail(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
