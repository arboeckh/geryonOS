package io.github.arboeckh.geryon.enforce

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.arboeckh.geryon.block.Decision
import io.github.arboeckh.geryon.block.Layer
import io.github.arboeckh.geryon.ui.GeryonTheme

/** Shown in place of a blocked app. */
class BlockedActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this) { goHome() }

        val name = intent.getStringExtra(EXTRA_NAME) ?: "This app"
        val layer = intent.getStringExtra(EXTRA_LAYER)?.let { runCatching { Layer.valueOf(it) }.getOrNull() }
        val source = intent.getStringExtra(EXTRA_SOURCE)
        val reason = when (layer) {
            Layer.Hard -> "${source ?: "This category"} is always blocked."
            Layer.Profile, Layer.Base -> "Blocked by the ${source ?: "active"} profile. Scan a grant tag to unlock it for a while."
            else -> null
        }

        setContent {
            GeryonTheme {
                Scaffold { padding ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("$name is blocked", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                        if (reason != null) {
                            Text(
                                reason,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Button(onClick = ::goHome) { Text("Go home") }
                    }
                }
            }
        }
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        private const val EXTRA_NAME = "name"
        private const val EXTRA_LAYER = "layer"
        private const val EXTRA_SOURCE = "source"

        fun intent(context: Context, name: String, decision: Decision): Intent =
            Intent(context, BlockedActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_LAYER, decision.layer.name)
                .putExtra(EXTRA_SOURCE, decision.sourceName)
    }
}
