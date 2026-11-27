package io.github.arboeckh.geryon.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.arboeckh.geryon.block.Catalog
import io.github.arboeckh.geryon.block.Category
import io.github.arboeckh.geryon.block.Target

/** Read-only view of what geryon knows how to block. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(catalog: Catalog, modifier: Modifier = Modifier) {
    var showTargets by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    val categoriesOf = remember(catalog) {
        catalog.categories.flatMap { c -> c.targets.map { it to c } }.groupBy({ it.first }, { it.second })
    }
    val targetName = { id: String -> catalog.targets.firstOrNull { it.id == id }?.name ?: id }
    fun Target.matches() = query.isBlank() || name.contains(query, true) || id.contains(query, true) ||
        packages.any { it.contains(query, true) } || domains.any { it.contains(query, true) }
    fun Category.matches() = query.isBlank() || name.contains(query, true) || id.contains(query, true) ||
        targets.any { targetName(it).contains(query, true) } || domains.any { it.contains(query, true) }

    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text("Catalogue") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "controls") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !showTargets,
                            onClick = { showTargets = false },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text("Categories (${catalog.categories.size})") }
                        SegmentedButton(
                            selected = showTargets,
                            onClick = { showTargets = true },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text("Apps & sites (${catalog.targets.size})") }
                    }
                    SearchField(query, { query = it }, "Search names, apps, domains")
                }
            }
            if (showTargets) {
                items(catalog.targets.filter { it.matches() }, key = { "t-${it.id}" }) { t ->
                    val cats = categoriesOf[t.id].orEmpty()
                    ExpandableCard(
                        title = t.name,
                        hard = cats.any { it.hard },
                        summary = cats.joinToString(", ") { it.name }.ifEmpty { "No category" },
                        expanded = expanded == "t-${t.id}",
                        onToggle = { expanded = if (expanded == "t-${t.id}") null else "t-${t.id}" },
                    ) {
                        Detail("Apps", t.packages.sorted())
                        Detail("Domains", t.domains.sorted())
                        if (cats.any { it.hard }) Note("In an always-blocked category, so it can't be unlocked.")
                    }
                }
            } else {
                items(catalog.categories.filter { it.matches() }, key = { "c-${it.id}" }) { c ->
                    ExpandableCard(
                        title = c.name,
                        hard = c.hard,
                        summary = buildList {
                            if (c.targets.isNotEmpty()) add(c.targets.joinToString(", ") { targetName(it) })
                            if (c.domains.isNotEmpty()) add("${c.domains.size} more domains")
                        }.joinToString(" · ").ifEmpty { "Empty" },
                        expanded = expanded == "c-${c.id}",
                        onToggle = { expanded = if (expanded == "c-${c.id}") null else "c-${c.id}" },
                    ) {
                        Detail("Apps & sites", c.targets.map { targetName(it) }.sorted())
                        Detail("Other domains", c.domains.sorted())
                        if (c.hard) Note("Always blocked. Profiles and unlocks can't change this.")
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandableCard(
    title: String,
    hard: Boolean,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    details: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth().animateContentSize().clickable(onClick = onToggle)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (hard) HardBadge()
            }
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
            )
            if (expanded) details()
        }
    }
}

@Composable
internal fun HardBadge() {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text("always blocked") },
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = MaterialTheme.colorScheme.errorContainer,
            disabledLabelColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        border = null,
    )
}

@Composable
private fun Detail(label: String, values: List<String>) {
    if (values.isEmpty()) return
    Column(Modifier.padding(top = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        val shown = values.take(MAX_DETAIL)
        Text(shown.joinToString("\n"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        if (values.size > MAX_DETAIL) {
            Text("and ${values.size - MAX_DETAIL} more", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
}

private const val MAX_DETAIL = 40

@Preview(showBackground = true)
@Composable
private fun CatalogScreenPreview() {
    GeryonTheme { CatalogScreen(SampleData.catalog) }
}
