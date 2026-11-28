package io.github.arboeckh.geryon.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import io.github.arboeckh.geryon.block.CatalogIndex
import io.github.arboeckh.geryon.enforce.Enforcer
import io.github.arboeckh.geryon.geryon

private data class Tab(val screen: Screen, val label: String, val icon: ImageVector)

private val Tabs = listOf(
    Tab(Screen.Home, "Home", GeryonIcons.Home),
    Tab(Screen.Tags, "Tags", GeryonIcons.Nfc),
    Tab(Screen.Profiles, "Profiles", GeryonIcons.Tune),
    Tab(Screen.Catalog, "Catalogue", GeryonIcons.Catalog),
)

/** The whole app: bottom tabs, pushed screens, and the dialogs a scanned tag can open. */
@Composable
fun GeryonRoot(model: AppModel) {
    val context = LocalContext.current
    val blocks = context.geryon.blocks
    val config by blocks.config.collectAsState()
    val state by blocks.state.collectAsState()
    val catalog by blocks.catalog.collectAsState()
    val now = rememberNow()
    val nav = model.nav
    val top = nav.top
    val openNfcSettings = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }

    BackHandler(enabled = nav.stack.size > 1 || top != Screen.Home) { nav.back() }

    // Scan results show up on Home already; elsewhere, flash them.
    val snackbar = remember { SnackbarHostState() }
    val outcome = model.lastOutcome
    LaunchedEffect(outcome) {
        if (outcome != null && nav.top != Screen.Home) snackbar.showSnackbar(outcome.text)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (top in TabScreens) {
                NavigationBar {
                    Tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = top == tab.screen,
                            onClick = { nav.selectTab(tab.screen) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
        // Each screen has its own Scaffold and top bar; only reserve room for the bottom bar here.
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        val screenModifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
        Box(screenModifier) {
            when (top) {
                Screen.Home -> {
                    val enforcers = context.geryon.enforcers
                    val rows = remember(model.resumeCount, enforcers) {
                        enforcers.map { e ->
                            EnforcerRow(e.id, e.label, e.description, e.status(context), e.setupIntent(context) != null, e.problems(context))
                        }
                    }
                    HomeScreen(
                        config = config,
                        state = state,
                        catalog = catalog,
                        enforcers = rows,
                        nfcStatus = model.nfcStatus,
                        lastOutcome = model.lastOutcome,
                        now = now,
                        onToggleBase = { blocks.toggleProfile(config.baseProfileId) },
                        onEndProfile = blocks::endProfile,
                        onEndGrant = blocks::endGrant,
                        onSetUpEnforcer = { id -> setUp(context, enforcers.firstOrNull { it.id == id }) },
                        onOpenNfcSettings = openNfcSettings,
                        onFixProblem = { p ->
                            p.fix?.let { runCatching { context.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                        },
                    )
                }
                Screen.Tags -> TagsScreen(
                    config = config,
                    catalog = catalog,
                    onOpen = { nav.push(Screen.TagEditor(it, isNew = false)) },
                    onAdd = { model.startScan(ScanPurpose.NewBinding) },
                )
                Screen.Profiles -> ProfilesScreen(
                    config = config,
                    state = state,
                    catalog = catalog,
                    now = now,
                    onOpen = { nav.push(Screen.ProfileEditor(it)) },
                    onAdd = { nav.push(Screen.ProfileEditor(null)) },
                )
                Screen.Catalog -> CatalogScreen(catalog)
                is Screen.TagEditor -> TagEditorScreen(
                    tagId = top.tagId,
                    isNew = top.isNew,
                    config = config,
                    catalog = catalog,
                    grantable = remember(catalog) { CatalogIndex(catalog).grantableTargets() },
                    onSave = {
                        blocks.bindTag(it)
                        nav.back()
                    },
                    onDelete = {
                        blocks.unbindTag(top.tagId)
                        nav.back()
                    },
                    onMove = { model.startScan(ScanPurpose.Rebind(top.tagId)) },
                    onBack = { nav.back() },
                )
                is Screen.ProfileEditor -> ProfileEditorScreen(
                    profileId = top.profileId,
                    config = config,
                    catalog = catalog,
                    onSave = {
                        blocks.upsertProfile(it)
                        nav.back()
                    },
                    onDelete = {
                        blocks.deleteProfile(it)
                        nav.back()
                    },
                    onBack = { nav.back() },
                )
                is Screen.ScanTag -> ScanTagScreen(
                    purpose = top.purpose,
                    bindingName = (top.purpose as? ScanPurpose.Rebind)?.let { config.binding(it.fromTagId)?.name },
                    nfcStatus = model.nfcStatus,
                    message = model.scanMessage,
                    lastScan = model.lastScan,
                    onOpenNfcSettings = openNfcSettings,
                    onBack = { nav.back() },
                )
            }
        }
    }

    model.pendingSetup?.let { tagId ->
        AlertDialog(
            onDismissRequest = { model.pendingSetup = null },
            title = { Text("New tag") },
            text = { Text("This tag ($tagId) doesn't do anything yet.") },
            confirmButton = { TextButton(onClick = model::setUpPendingTag) { Text("Set up this tag") } },
            dismissButton = { TextButton(onClick = { model.pendingSetup = null }) { Text("Not now") } },
        )
    }

    model.pendingGrant?.let { request ->
        key(request) {
            val policy by blocks.policy.collectAsState()
            GrantPicker(
                tagName = request.binding.name,
                options = request.options.map { GrantOption(it, targetDecision(policy, it, now)) },
                defaultMinutes = request.minutes,
                now = now,
                onPick = { target, minutes ->
                    model.pendingGrant = null
                    model.report(
                        blocks.grant(target.id, minutes).fold(
                            onSuccess = { "${target.name} unlocked for ${formatMinutes(minutes)}" },
                            onFailure = { it.message ?: "Couldn't unlock ${target.name}" },
                        ),
                    )
                },
                onDismiss = { model.pendingGrant = null },
            )
        }
    }
}

private fun setUp(context: Context, enforcer: Enforcer?) {
    val intent = enforcer?.setupIntent(context) ?: return
    runCatching { context.startActivity(intent) }
}
