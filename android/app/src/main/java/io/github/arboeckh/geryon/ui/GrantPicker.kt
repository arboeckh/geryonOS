package io.github.arboeckh.geryon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.arboeckh.geryon.block.Decision
import io.github.arboeckh.geryon.block.Layer
import io.github.arboeckh.geryon.block.Target

/** A target a grant tag offers, with what the policy currently says about it. */
data class GrantOption(val target: Target, val decision: Decision)

/** Blocked first (that's what people come to unlock), then by name. */
fun sortGrantOptions(options: List<GrantOption>): List<GrantOption> =
    options.sortedWith(compareBy({ !it.decision.blocked }, { it.target.name.lowercase() }))

/** Shown after a grant tag: pick one target to unlock. Starts at the tag's minutes. */
@Composable
fun GrantPicker(
    tagName: String,
    options: List<GrantOption>,
    defaultMinutes: Int,
    now: Long,
    onPick: (Target, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableIntStateOf(defaultMinutes) }
    val shown = sortGrantOptions(options).filter {
        query.isBlank() || it.target.name.contains(query, true) || it.target.id.contains(query, true)
    }

    ListDialog(title = "Unlock with “$tagName”", onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            MinutesStepper(minutes, { minutes = it })
            if (options.size > 5) SearchField(query, { query = it }, "Search", Modifier.padding(top = 8.dp))
        }
        if (options.isEmpty()) {
            Text(
                "This tag has nothing it can unlock.",
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.padding(top = 8.dp)) {
            items(shown, key = { it.target.id }) { option ->
                val d = option.decision
                ListItem(
                    headlineContent = { Text(option.target.name) },
                    supportingContent = {
                        Text(
                            describeDecision(d, now),
                            color = when {
                                d.blocked -> MaterialTheme.colorScheme.error
                                d.layer == Layer.Grant -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth().clickable { onPick(option.target, minutes) }.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun GrantPickerPreview() {
    val t = SampleData.catalog.targets
    GeryonTheme {
        GrantPicker(
            tagName = "Fridge",
            options = listOf(
                GrantOption(t[0], Decision(false, Layer.Grant, "youtube", "YouTube", SampleData.NOW + 300_000)),
                GrantOption(t[1], Decision(true, Layer.Base, "base", "Base")),
                GrantOption(t[3], Decision(true, Layer.Profile, "focus", "Deep work")),
                GrantOption(t[2], Decision.Allowed),
            ),
            defaultMinutes = 20,
            now = SampleData.NOW,
            onPick = { _, _ -> },
            onDismiss = {},
        )
    }
}
