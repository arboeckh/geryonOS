package io.github.arboeckh.geryon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.arboeckh.geryon.block.ScanOutcome
import io.github.arboeckh.geryon.block.Target

/** Shown after a grant tag: pick one target to unlock for the tag's duration. */
@Composable
fun GrantPicker(
    request: ScanOutcome.PickGrant,
    onPick: (Target) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unlock for ${request.minutes} min") },
        text = {
            if (request.options.isEmpty()) {
                Text("This tag has nothing it can unlock.")
            } else {
                LazyColumn {
                    items(request.options, key = { it.id }) { target ->
                        ListItem(
                            headlineContent = { Text(target.name) },
                            modifier = Modifier.fillMaxWidth().clickable { onPick(target) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
