package io.github.arboeckh.geryon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.arboeckh.geryon.nfc.TagScan
import java.text.DateFormat
import java.util.Date

enum class NfcStatus { Unavailable, Off, Ready }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    nfcStatus: NfcStatus,
    writeMode: Boolean,
    lastScan: TagScan?,
    message: String?,
    onWriteModeChange: (Boolean) -> Unit,
    onOpenNfcSettings: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("geryon", style = MaterialTheme.typography.headlineLarge)

            when (nfcStatus) {
                NfcStatus.Unavailable -> Text(
                    "This device has no NFC.",
                    color = MaterialTheme.colorScheme.error,
                )
                NfcStatus.Off -> {
                    Text("NFC is turned off.", color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onOpenNfcSettings) { Text("Open NFC settings") }
                }
                NfcStatus.Ready -> Text(
                    if (writeMode) "Hold a tag to the back of the phone to write it."
                    else "Hold a tag to the back of the phone to read it.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !writeMode,
                    onClick = { onWriteModeChange(false) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("Read") }
                SegmentedButton(
                    selected = writeMode,
                    onClick = { onWriteModeChange(true) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("Write tag") }
            }

            if (message != null) {
                Text(message, color = MaterialTheme.colorScheme.primary)
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
                    TagScan.Source.LaunchedApp -> "Opened by tag"
                    TagScan.Source.ForegroundRead -> "Tag read"
                    TagScan.Source.Written -> "Tag written"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(2.dp))
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
private fun MainScreenPreview() {
    GeryonTheme {
        MainScreen(
            nfcStatus = NfcStatus.Ready,
            writeMode = false,
            lastScan = TagScan("04:A2:3B:1C:22:80:00", "3f9a1c2b", "geryon://tag/3f9a1c2b", TagScan.Source.LaunchedApp),
            message = null,
            onWriteModeChange = {},
            onOpenNfcSettings = {},
        )
    }
}
