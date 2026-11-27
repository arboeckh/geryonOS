package io.github.arboeckh.geryon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.arboeckh.geryon.block.BlockConfig
import io.github.arboeckh.geryon.block.BlockState
import io.github.arboeckh.geryon.block.Catalog
import io.github.arboeckh.geryon.block.Effect
import io.github.arboeckh.geryon.block.Profile
import io.github.arboeckh.geryon.block.Rule
import io.github.arboeckh.geryon.block.Selector
import io.github.arboeckh.geryon.block.TagAction

// --- List -----------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    config: BlockConfig,
    state: BlockState,
    catalog: Catalog,
    now: Long,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ordered = config.profiles.sortedWith(compareBy({ it.id != config.baseProfileId }, { it.name.lowercase() }))
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Profiles") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(GeryonIcons.Add, contentDescription = null) },
                text = { Text("New profile") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Hint("The base profile is always on unless paused. Other profiles stack on top of it while they run.")
            }
            items(ordered, key = { it.id }) { p ->
                val isBase = p.id == config.baseProfileId
                val run = profileRunState(p.id, config, state, now)
                val until = run.until
                val status = when {
                    run.on && until != null -> "On · ${formatRemaining(until - now)} left"
                    run.on -> "On"
                    isBase -> "Paused"
                    else -> "Off"
                }
                Card(Modifier.fillMaxWidth().clickable { onOpen(p.id) }) {
                    ListItem(
                        overlineContent = if (isBase) ({ Text("Base") }) else null,
                        headlineContent = { Text(p.name) },
                        supportingContent = { Text(ruleSummary(p, catalog)) },
                        trailingContent = {
                            Text(
                                status,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (run.on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}

// --- Editor ---------------------------------------------------------------------------------

@Composable
fun ProfileEditorScreen(
    profileId: String?,
    config: BlockConfig,
    catalog: Catalog,
    onSave: (Profile) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val existing = profileId?.let(config::profile)
    val isBase = profileId != null && profileId == config.baseProfileId
    var name by rememberSaveable(profileId) { mutableStateOf(existing?.name ?: "") }
    var rules by remember(profileId) { mutableStateOf(existing?.rules ?: emptyList()) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    val valid = name.isNotBlank()
    val save = {
        val id = profileId ?: newProfileId(name, config.profiles.mapTo(HashSet()) { it.id })
        onSave(Profile(id, name.trim(), rules))
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            BackTopBar(if (profileId == null) "New profile" else "Edit profile", onBack) {
                TextButton(onClick = save, enabled = valid) { Text("Save") }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                placeholder = { Text("e.g. Deep work, Bedtime") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (isBase) {
                Hint("This is the base profile: it's on unless paused, and can't be deleted.")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("Rules", Modifier.weight(1f))
                TextButton(onClick = { adding = true }) {
                    Icon(GeryonIcons.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add rule")
                }
            }
            if (rules.isEmpty()) {
                Hint("No rules yet. Add a category or an app to block or allow.")
            } else {
                Hint("A rule on an app or site beats a rule on its category. Block beats Allow at the same level.")
            }
            rules.forEachIndexed { i, rule ->
                RuleRow(
                    rule = rule,
                    catalog = catalog,
                    onEffect = { e -> rules = rules.toMutableList().also { it[i] = rule.copy(effect = e) } },
                    onRemove = { rules = rules.filterIndexed { j, _ -> j != i } },
                )
            }

            Spacer(Modifier.height(8.dp))
            Button(onClick = save, enabled = valid, modifier = Modifier.fillMaxWidth()) {
                Text(if (profileId == null) "Create profile" else "Save changes")
            }
            if (existing != null && !isBase) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete profile", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (adding) {
        RulePicker(
            catalog = catalog,
            existing = rules.mapTo(HashSet()) { it.selector },
            onPick = { selector ->
                rules = rules + Rule(Effect.Block, selector)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
    if (confirmDelete && existing != null) {
        val affected = config.bindings.filter {
            val a = it.action
            (a is TagAction.StartProfile && a.profileId == existing.id) || (a is TagAction.ToggleProfile && a.profileId == existing.id)
        }
        ConfirmDialog(
            title = "Delete “${existing.name}”?",
            text = if (affected.isEmpty()) "This can't be undone."
            else "These tags start or toggle it and will be removed too: ${affected.joinToString(", ") { it.name }}.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                onDelete(existing.id)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun RuleRow(rule: Rule, catalog: Catalog, onEffect: (Effect) -> Unit, onRemove: () -> Unit) {
    val hard = (rule.selector as? Selector.OfCategory)?.let { s -> catalog.categories.firstOrNull { it.id == s.id }?.hard } == true
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(selectorName(rule.selector, catalog), style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        hard -> "Category · always blocked"
                        rule.selector is Selector.OfCategory -> "Category"
                        else -> "App or site"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SingleChoiceSegmentedButtonRow {
                Effect.entries.forEachIndexed { i, e ->
                    SegmentedButton(
                        selected = rule.effect == e,
                        onClick = { onEffect(e) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = Effect.entries.size),
                        icon = {},
                    ) { Text(e.name) }
                }
            }
            IconButton(onClick = onRemove) { Icon(GeryonIcons.Delete, contentDescription = "Remove rule") }
        }
    }
}

/** Pick a category or a target from the catalogue to add as a rule. */
@Composable
private fun RulePicker(
    catalog: Catalog,
    existing: Set<Selector>,
    onPick: (Selector) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showTargets by rememberSaveable { mutableStateOf(false) }
    fun matches(name: String, id: String) = name.contains(query, ignoreCase = true) || id.contains(query, ignoreCase = true)

    ListDialog(title = "Add rule", onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !showTargets,
                    onClick = { showTargets = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Categories") }
                SegmentedButton(
                    selected = showTargets,
                    onClick = { showTargets = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Apps & sites") }
            }
            SearchField(query, { query = it }, "Search")
        }
        LazyColumn(Modifier.padding(top = 8.dp)) {
            if (showTargets) {
                items(catalog.targets.filter { matches(it.name, it.id) }, key = { "t-${it.id}" }) { t ->
                    val selector = Selector.OfTarget(t.id)
                    PickRow(t.name, null, added = selector in existing, enabled = true) { onPick(selector) }
                }
            } else {
                items(catalog.categories.filter { matches(it.name, it.id) }, key = { "c-${it.id}" }) { c ->
                    val selector = Selector.OfCategory(c.id)
                    val names = c.targets.mapNotNull { id -> catalog.targets.firstOrNull { it.id == id }?.name }
                    PickRow(
                        c.name,
                        if (c.hard) "Always blocked, no rule needed" else names.joinToString(", ").ifEmpty { null },
                        added = selector in existing,
                        enabled = !c.hard,
                    ) { onPick(selector) }
                }
            }
        }
    }
}

@Composable
private fun PickRow(title: String, subtitle: String?, added: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val active = enabled && !added
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = when {
            added -> ({ Text("Already in this profile") })
            subtitle != null -> ({ Text(subtitle, maxLines = 2) })
            else -> null
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = if (active) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        ),
        modifier = Modifier.clickable(enabled = active, onClick = onClick).padding(horizontal = 8.dp),
    )
}

@Preview(showBackground = true)
@Composable
private fun ProfilesScreenPreview() {
    GeryonTheme {
        ProfilesScreen(SampleData.config, SampleData.state, SampleData.catalog, SampleData.NOW, onOpen = {}, onAdd = {})
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun ProfileEditorPreview() {
    GeryonTheme {
        ProfileEditorScreen("focus", SampleData.config, SampleData.catalog, onSave = {}, onDelete = {}, onBack = {})
    }
}
