package uk.ewancroft.fathom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import uk.ewancroft.fathom.ui.components.Notice

/**
 * The first screen, and the only one that needs typing.
 *
 * There is no sign-in because there is nothing to authorise: every teal.fm
 * record is public once the handle is known. Asking for a password to read a
 * public repo would be theatre.
 */
@Composable
fun ConnectScreen(
    connecting: Boolean,
    error: String?,
    onConnect: (String) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = "Fathom",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Your teal.fm listening, measured.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Fathom reads the plays already in your AT Protocol repo and " +
                    "works the figures out on the device. Nothing is uploaded, and no " +
                    "account password is needed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(28.dp))

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Handle or DID") },
                placeholder = { Text("you.example") },
                singleLine = true,
                enabled = !connecting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onConnect(input) },
                enabled = !connecting && input.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (connecting) "Looking up…" else "Open dashboard")
            }

            if (error != null) {
                Spacer(Modifier.height(16.dp))
                Notice(error)
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "Reads fm.teal.feed.play and the older fm.teal.alpha.feed.play " +
                    "directly from your data server.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
