package io.github.arboeckh.geryon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.arboeckh.geryon.block.BlockConfig
import io.github.arboeckh.geryon.block.BlockState
import io.github.arboeckh.geryon.block.Catalog
import io.github.arboeckh.geryon.enforce.EnforcerStatus

data class EnforcerRow(
    val id: String,
    val label: String,
    val description: String,
    val status: EnforcerStatus,
    val canSetUp: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    config: BlockConfig,
    state: BlockState,
    catalog: Catalog,
    enforcers: List<EnforcerRow>,
    nfcStatus: NfcStatus,
    lastOutcome: OutcomeMessage?,
    now: Long,
    onToggleBase: () -> Unit,
    onEndProfile: (String) -> Unit,
    onEndGrant: (String) -> Unit,
    onSetUpEnforcer: (String) -> Unit,
    onOpenNfcSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val base = config.profile(config.baseProfileId)
    val profiles = state.activeProfiles.filter { it.until == null || it.until > now }.sortedByDescending { it.startedAt }
    val grants = state.grants.filter { it.until > now }.sortedBy { it.until }
    val targetName = { id: String -> catalog.targets.firstOrNull { it.id == id }?.name ?: id }

    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text("geryon") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "status") {
                BlockStatusCard(
                    paused = state.basePaused,
                    summary = base?.let { ruleSummary(it, catalog) } ?: "Base profile missing",
                    onToggle = onToggleBase,
                )
            }

            if (nfcStatus != NfcStatus.Ready) {
                item(key = "nfc") {
                    WarningCard(
                        title = if (nfcStatus == NfcStatus.Off) "NFC is off" else "No NFC on this device",
                        body = if (nfcStatus == NfcStatus.Off) "Tags won't do anything until NFC is on."
                        else "Tags can't be scanned on this device.",
                        action = if (nfcStatus == NfcStatus.Off) "Open NFC settings" else null,
                        onAction = onOpenNfcSettings,
                    )
                }
            }

            items(enforcers.filter { it.status != EnforcerStatus.Running }, key = { "enforcer-${it.id}" }) { e ->
                WarningCard(
                    title = if (e.status == EnforcerStatus.Unsupported) "${e.label}: not available" else "Set up ${e.label}",
                    body = e.description,
                    action = if (e.canSetUp && e.status == EnforcerStatus.NeedsSetup) "Set up" else null,
                    onAction = { onSetUpEnforcer(e.id) },
                )
            }

            if (lastOutcome != null) {
                item(key = "outcome") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Last scan · ${formatAgo(now - lastOutcome.at)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(lastOutcome.text, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }

            item(key = "profiles-header") { SectionHeader("Active profiles") }
            if (profiles.isEmpty()) {
                item(key = "profiles-empty") { Hint("No profiles running. Tap a profile tag to start one.") }
            }
            items(profiles, key = { "profile-${it.profileId}" }) { active ->
                val name = config.profile(active.profileId)?.name ?: active.profileId
                val until = active.until
                CountdownRow(
                    title = name,
                    subtitle = if (until == null) "On until turned off" else "${formatRemaining(until - now)} left",
                    onEnd = { onEndProfile(active.profileId) },
                )
            }

            item(key = "grants-header") { SectionHeader("Unlocked") }
            if (grants.isEmpty()) {
                item(key = "grants-empty") { Hint("Nothing unlocked. Tap an unlock tag to open one app or site for a while.") }
            }
            items(grants, key = { "grant-${it.targetId}" }) { g ->
                CountdownRow(
                    title = targetName(g.targetId),
                    subtitle = "${formatRemaining(g.until - now)} left",
                    onEnd = { onEndGrant(g.targetId) },
                )
            }
        }
    }
}

@Composable
private fun BlockStatusCard(paused: Boolean, summary: String, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (paused) colors.surfaceContainerHighest else colors.primaryContainer,
            contentColor = if (paused) colors.onSurface else colors.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (paused) "Block paused" else "Block is on", style = MaterialTheme.typography.headlineSmall)
            Text(summary, style = MaterialTheme.typography.bodyMedium)
            if (paused) {
                Button(onClick = onToggle) { Text("Resume block") }
            } else {
                OutlinedButton(onClick = onToggle) { Text("Pause block") }
            }
        }
    }
}

@Composable
private fun WarningCard(title: String, body: String, action: String?, onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (action != null) FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun CountdownRow(title: String, subtitle: String, onEnd: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        ListRow(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            trailingContent = { TextButton(onClick = onEnd) { Text("End") } },
        )
    }
}

@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun HomeScreenPreview() {
    GeryonTheme {
        HomeScreen(
            config = SampleData.config,
            state = SampleData.state,
            catalog = SampleData.catalog,
            enforcers = listOf(
                EnforcerRow("apps", "App blocker", "Closes blocked apps when they open. Needs the accessibility service.", EnforcerStatus.NeedsSetup, true),
            ),
            nfcStatus = NfcStatus.Ready,
            lastOutcome = OutcomeMessage("Deep work on for 90 min", SampleData.NOW - 180_000),
            now = SampleData.NOW,
            onToggleBase = {}, onEndProfile = {}, onEndGrant = {}, onSetUpEnforcer = {}, onOpenNfcSettings = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPausedPreview() {
    GeryonTheme {
        HomeScreen(
            config = SampleData.config,
            state = SampleData.state.copy(basePaused = true, activeProfiles = emptyList(), grants = emptyList()),
            catalog = SampleData.catalog,
            enforcers = emptyList(),
            nfcStatus = NfcStatus.Off,
            lastOutcome = null,
            now = SampleData.NOW,
            onToggleBase = {}, onEndProfile = {}, onEndGrant = {}, onSetUpEnforcer = {}, onOpenNfcSettings = {},
        )
    }
}
