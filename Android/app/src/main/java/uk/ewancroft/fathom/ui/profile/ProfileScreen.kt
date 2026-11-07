package uk.ewancroft.fathom.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uk.ewancroft.fathom.data.Dashboard
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
fun ProfileScreen(dashboard: Dashboard, onDisconnect: () -> Unit) {
    val profile = dashboard.profile
    val name = profile?.nameOrNull()
        ?: dashboard.handle
        ?: dashboard.did

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

        OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
            Text("Disconnect")
        }

        Text(
            text = "Fathom keeps no copy of your listening. Disconnecting forgets the " +
                "handle and nothing else.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
