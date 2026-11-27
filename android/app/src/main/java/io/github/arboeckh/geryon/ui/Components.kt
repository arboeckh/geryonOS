package io.github.arboeckh.geryon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay

/** Wall-clock time that ticks every [periodMs], for countdowns. */
@Composable
fun rememberNow(periodMs: Long = 1_000): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(periodMs - System.currentTimeMillis() % periodMs)
            value = System.currentTimeMillis()
        }
    }
    return now
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(GeryonIcons.Back, contentDescription = "Back") }
        },
        actions = { actions() },
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/** − 30 min + : moves through [MinuteSteps]. */
@Composable
fun MinutesStepper(minutes: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalIconButton(onClick = { onChange(previousMinutes(minutes)) }, enabled = minutes > MinuteSteps.first()) {
            Icon(GeryonIcons.Remove, contentDescription = "Less time")
        }
        Text(
            formatMinutes(minutes),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 88.dp),
        )
        FilledTonalIconButton(onClick = { onChange(nextMinutes(minutes)) }, enabled = minutes < MinuteSteps.last()) {
            Icon(GeryonIcons.Add, contentDescription = "More time")
        }
    }
}

@Composable
fun SearchField(query: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(GeryonIcons.Search, contentDescription = null) },
        trailingIcon = if (query.isEmpty()) null else {
            { IconButton(onClick = { onChange("") }) { Icon(GeryonIcons.Close, contentDescription = "Clear") } }
        },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

/** A tall dialog for searchable lists, which AlertDialog doesn't size well. */
@Composable
fun ListDialog(
    title: String,
    onDismiss: () -> Unit,
    confirm: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
        ) {
            Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp))
                Column(Modifier.weight(1f, fill = false).padding(top = 16.dp)) { content() }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(if (confirm == null) "Close" else "Cancel") }
                    confirm?.invoke()
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
