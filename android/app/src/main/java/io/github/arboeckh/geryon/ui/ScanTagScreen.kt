package io.github.arboeckh.geryon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.arboeckh.geryon.nfc.TagScan
import java.text.DateFormat
import java.util.Date

/**
 * Waiting for a tag. geryon tags are used as they are; anything else (blank tags, tags with
 * other content) gets a fresh geryon id written to it. Either way we then go to the editor.
 */
@Composable
fun ScanTagScreen(
    purpose: ScanPurpose,
    bindingName: String?,
    nfcStatus: NfcStatus,
    message: String?,
    lastScan: TagScan?,
    onOpenNfcSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { BackTopBar(if (purpose is ScanPurpose.Rebind) "Move to another tag" else "Add a tag", onBack) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                GeryonIcons.Nfc,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 32.dp).size(96.dp),
            )
            when (nfcStatus) {
                NfcStatus.Unavailable -> Text("This device has no NFC.", color = MaterialTheme.colorScheme.error)
                NfcStatus.Off -> {
                    Text("NFC is turned off.", color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onOpenNfcSettings) { Text("Open NFC settings") }
                }
                NfcStatus.Ready -> Text(
                    "Hold a tag to the back of the phone",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                when (purpose) {
                    is ScanPurpose.Rebind ->
                        "“${bindingName ?: purpose.fromTagId}” will move to the new tag. The old tag stops doing anything."
                    ScanPurpose.NewBinding ->
                        "A geryon tag is used as it is. A blank tag, or one with anything else on it, " +
                            "gets a new geryon id written to it first. Then you choose what it does."
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (message != null) {
                Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            if (lastScan != null) LastScanCard(lastScan)
        }
    }
}

@Composable
private fun LastScanCard(scan: TagScan) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when (scan.source) {
                    TagScan.Source.LaunchedApp -> "Last tag: opened the app"
                    TagScan.Source.ForegroundRead -> "Last tag read"
                    TagScan.Source.Written -> "Last tag written"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Field("geryon id", scan.geryonId ?: "none")
            Field("URI", scan.uri ?: "none")
            Field("UID", scan.uid)
            Field("Time", DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(scan.at)))
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

@Preview(showBackground = true)
@Composable
private fun ScanTagPreview() {
    GeryonTheme {
        ScanTagScreen(
            purpose = ScanPurpose.NewBinding,
            bindingName = null,
            nfcStatus = NfcStatus.Ready,
            message = null,
            lastScan = TagScan("04:A2:3B:1C:22:80:00", null, "https://example.com", TagScan.Source.ForegroundRead),
            onOpenNfcSettings = {},
            onBack = {},
        )
    }
}
