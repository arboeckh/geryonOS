package io.github.arboeckh.geryon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.arboeckh.geryon.block.BlockConfig
import io.github.arboeckh.geryon.block.Catalog
import io.github.arboeckh.geryon.block.TagAction
import io.github.arboeckh.geryon.block.TagBinding
import io.github.arboeckh.geryon.block.Target

// --- List -----------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagsScreen(
    config: BlockConfig,
    catalog: Catalog,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Tags") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(GeryonIcons.Add, contentDescription = null) },
                text = { Text("Add tag") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (config.bindings.isEmpty()) {
                item {
                    Hint(
                        "No tags yet. Tap Add tag and hold a tag to the back of the phone. " +
                            "Blank tags are written automatically. You can also just scan an existing geryon tag from any screen.",
                    )
                }
            }
            items(config.bindings.sortedBy { it.name.lowercase() }, key = { it.tagId }) { b ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(b.tagId) }) {
                    ListRow(
                        headlineContent = { Text(b.name) },
                        supportingContent = { Text(describeAction(b.action, config, catalog)) },
                        trailingContent = {
                            Text(b.tagId, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        },
                    )
                }
            }
        }
    }
}

// --- Editor ---------------------------------------------------------------------------------

private enum class ActionKind(val title: String, val description: String) {
    Start("Turn on a profile for a while", "Starts a profile with a timer. Scanning again restarts the timer."),
    Toggle("Turn a profile on or off", "Each scan flips it. On the base profile this pauses or resumes the block."),
    Grant("Unlock one app or site", "Asks which one to unlock, then opens it for a while."),
    Lockdown("Restore the block", "Ends every unlock and resumes the base profile."),
}

/** Editable copy of a binding. Kept as plain fields so switching action type doesn't lose input. */
private data class TagDraft(
    val name: String,
    val kind: ActionKind,
    val profileId: String,
    val minutes: Int,
    val grantMinutes: Int,
    val grantAny: Boolean,
    val grantTargets: Set<String>,
    val endProfiles: Boolean,
) {
    fun toAction(): TagAction = when (kind) {
        ActionKind.Start -> TagAction.StartProfile(profileId, minutes)
        ActionKind.Toggle -> TagAction.ToggleProfile(profileId)
        ActionKind.Grant -> TagAction.Grant(if (grantAny) null else grantTargets, grantMinutes)
        ActionKind.Lockdown -> TagAction.Lockdown(endProfiles)
    }

    companion object {
        fun from(binding: TagBinding?, tagId: String, config: BlockConfig): TagDraft {
            val defaultProfile = config.profiles.firstOrNull { it.id != config.baseProfileId }?.id ?: config.baseProfileId
            val d = TagDraft(
                name = binding?.name ?: "",
                kind = ActionKind.Start,
                profileId = defaultProfile,
                minutes = 30,
                grantMinutes = 10,
                grantAny = true,
                grantTargets = emptySet(),
                endProfiles = false,
            )
            return when (val a = binding?.action) {
                null -> d
                is TagAction.StartProfile -> d.copy(kind = ActionKind.Start, profileId = a.profileId, minutes = a.minutes)
                is TagAction.ToggleProfile -> d.copy(kind = ActionKind.Toggle, profileId = a.profileId)
                is TagAction.Grant -> d.copy(
                    kind = ActionKind.Grant,
                    grantMinutes = a.minutes,
                    grantAny = a.targets == null,
                    grantTargets = a.targets.orEmpty(),
                )
                is TagAction.Lockdown -> d.copy(kind = ActionKind.Lockdown, endProfiles = a.endProfiles)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditorScreen(
    tagId: String,
    isNew: Boolean,
    config: BlockConfig,
    catalog: Catalog,
    grantable: List<Target>,
    onSave: (TagBinding) -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val existing = config.binding(tagId)
    var draft by remember(tagId, existing) { mutableStateOf(TagDraft.from(existing, tagId, config)) }
    var pickingTargets by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    val profileValid = config.profile(draft.profileId) != null
    val error = when {
        (draft.kind == ActionKind.Start || draft.kind == ActionKind.Toggle) && !profileValid -> "Pick a profile."
        draft.kind == ActionKind.Grant && !draft.grantAny && draft.grantTargets.isEmpty() -> "Pick at least one app or site, or allow any."
        else -> null
    }
    val save = {
        onSave(TagBinding(tagId, draft.name.trim().ifEmpty { "Tag $tagId" }, draft.toAction()))
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            BackTopBar(if (isNew) "Set up tag" else "Edit tag", onBack) {
                TextButton(onClick = save, enabled = error == null) { Text("Save") }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text("Name") },
                placeholder = { Text("e.g. Desk, Fridge, Front door") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Tag id $tagId",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeader("When scanned")
            ActionKind.entries.forEach { kind ->
                val selected = draft.kind == kind
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected, role = Role.RadioButton) { draft = draft.copy(kind = kind) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(kind.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    kind.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (selected) {
                            Column(
                                Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp, top = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                when (kind) {
                                    ActionKind.Start, ActionKind.Toggle -> {
                                        Text("Profile", style = MaterialTheme.typography.labelLarge)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            config.profiles.forEach { p ->
                                                FilterChip(
                                                    selected = draft.profileId == p.id,
                                                    onClick = { draft = draft.copy(profileId = p.id) },
                                                    label = { Text(p.name) },
                                                )
                                            }
                                        }
                                        if (kind == ActionKind.Start) {
                                            Text("For", style = MaterialTheme.typography.labelLarge)
                                            MinutesStepper(draft.minutes, { draft = draft.copy(minutes = it) })
                                        }
                                    }
                                    ActionKind.Grant -> {
                                        Text("For", style = MaterialTheme.typography.labelLarge)
                                        MinutesStepper(draft.grantMinutes, { draft = draft.copy(grantMinutes = it) })
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Any app or site", Modifier.weight(1f))
                                            Switch(checked = draft.grantAny, onCheckedChange = { draft = draft.copy(grantAny = it) })
                                        }
                                        if (!draft.grantAny) {
                                            val names = grantable.filter { it.id in draft.grantTargets }.map { it.name }
                                            Text(
                                                if (names.isEmpty()) "Nothing picked yet" else names.joinToString(", "),
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            OutlinedButton(onClick = { pickingTargets = true }) { Text("Choose apps and sites") }
                                        } else {
                                            Text(
                                                "Anything outside the always-blocked categories.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    ActionKind.Lockdown -> {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Also end running profiles", Modifier.weight(1f))
                                            Switch(checked = draft.endProfiles, onCheckedChange = { draft = draft.copy(endProfiles = it) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            Button(onClick = save, enabled = error == null, modifier = Modifier.fillMaxWidth()) {
                Text(if (isNew) "Save tag" else "Save changes")
            }

            if (!isNew && existing != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onMove, modifier = Modifier.fillMaxWidth()) { Text("Move to another tag") }
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete tag", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (pickingTargets) {
        TargetMultiPicker(
            targets = grantable,
            selected = draft.grantTargets,
            onDone = {
                draft = draft.copy(grantTargets = it)
                pickingTargets = false
            },
            onDismiss = { pickingTargets = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete “${existing?.name}”?",
            text = "Scanning this tag will do nothing until you set it up again.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun TargetMultiPicker(
    targets: List<Target>,
    selected: Set<String>,
    onDone: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var picked by remember { mutableStateOf(selected) }
    val shown = targets.filter { it.name.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true) }
    ListDialog(
        title = "Apps and sites",
        onDismiss = onDismiss,
        confirm = { TextButton(onClick = { onDone(picked) }) { Text("Done (${picked.size})") } },
    ) {
        SearchField(query, { query = it }, "Search", Modifier.padding(horizontal = 24.dp))
        LazyColumn(Modifier.padding(top = 8.dp)) {
            items(shown, key = { it.id }) { t ->
                val on = t.id in picked
                ListRow(
                    headlineContent = { Text(t.name) },
                    leadingContent = { Checkbox(checked = on, onCheckedChange = null) },
                    modifier = Modifier.clickable { picked = if (on) picked - t.id else picked + t.id }.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TagsScreenPreview() {
    GeryonTheme { TagsScreen(SampleData.config, SampleData.catalog, onOpen = {}, onAdd = {}) }
}

@Preview(showBackground = true, heightDp = 1000)
@Composable
private fun TagEditorPreview() {
    GeryonTheme {
        TagEditorScreen(
            tagId = "a0b1c2d3", isNew = false,
            config = SampleData.config, catalog = SampleData.catalog,
            grantable = SampleData.catalog.targets.take(4),
            onSave = {}, onDelete = {}, onMove = {}, onBack = {},
        )
    }
}
