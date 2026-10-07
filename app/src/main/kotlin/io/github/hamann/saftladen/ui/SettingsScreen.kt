package io.github.hamann.saftladen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.hamann.saftladen.settings.SaftladenSettings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onEdit: ((SaftladenSettings) -> SaftladenSettings) -> Unit,
    onSave: () -> Unit,
    onSendTestReport: () -> Unit,
    onFlushNow: () -> Unit,
    onClearBuffer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.loaded) {
        // Avoid showing the defaults for a frame before the stored settings arrive.
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading…")
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Saftladen", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Reports each sensor's battery status as JSON when a ride ends. " +
                "Reports are buffered on the device until the endpoint accepts them.",
            style = MaterialTheme.typography.bodySmall,
        )

        SwitchRow(
            label = "Report at end of ride",
            checked = state.settings.enabled,
            onCheckedChange = { checked -> onEdit { it.copy(enabled = checked) } },
        )

        OutlinedTextField(
            value = state.settings.endpointUrl,
            onValueChange = { url -> onEdit { it.copy(endpointUrl = url) } },
            label = { Text("Endpoint URL") },
            placeholder = { Text("https://example.com/karoo/batteries") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.settings.authHeaderName,
            onValueChange = { name -> onEdit { it.copy(authHeaderName = name) } },
            label = { Text("Auth header name (optional)") },
            placeholder = { Text("Authorization") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.settings.authHeaderValue,
            onValueChange = { value -> onEdit { it.copy(authHeaderValue = value) } },
            label = { Text("Auth header value (optional)") },
            placeholder = { Text("Bearer …") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        SwitchRow(
            label = "Include sensor serial numbers",
            checked = state.settings.includeSerialNumbers,
            onCheckedChange = { checked -> onEdit { it.copy(includeSerialNumbers = checked) } },
        )

        Button(
            onClick = onSave,
            enabled = state.unsavedChanges && !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.unsavedChanges) "Save settings" else "Saved")
        }

        HorizontalDivider()

        // The buttons come before the status card: the card grows and shrinks with the
        // last message, and anything below it would move under the rider's finger.
        Button(
            onClick = onSendTestReport,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Send test report now")
        }

        OutlinedButton(
            onClick = onFlushNow,
            enabled = !state.busy && state.pendingCount > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Retry buffered reports")
        }

        OutlinedButton(
            onClick = onClearBuffer,
            enabled = !state.busy && state.pendingCount > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Discard buffered reports")
        }

        StatusCard(state)
    }
}

@Composable
private fun StatusCard(state: SettingsUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Buffer", style = MaterialTheme.typography.titleSmall)
            Text(
                "${state.pendingCount} report(s) waiting",
                style = MaterialTheme.typography.bodyMedium,
            )
            state.status.lastAttemptAt?.let { at ->
                Text(
                    "Last attempt ${at.formatLocal()}: ${state.status.lastMessage}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.status.lastSuccessAt?.let { at ->
                Text("Last success ${at.formatLocal()}", style = MaterialTheme.typography.bodySmall)
            }
            state.message?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (state.busy) {
                Text("Working…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The label has to yield, or it pushes the switch off the 480px-wide screen.
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private val localTime: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault())

private fun Instant.formatLocal(): String = localTime.format(this)
