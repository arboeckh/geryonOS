package io.github.arboeckh.geryon

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import io.github.arboeckh.geryon.block.Defaults
import io.github.arboeckh.geryon.block.ScanOutcome
import io.github.arboeckh.geryon.nfc.TagScan
import io.github.arboeckh.geryon.nfc.firstUri
import io.github.arboeckh.geryon.nfc.geryonIdFrom
import io.github.arboeckh.geryon.nfc.readTag
import io.github.arboeckh.geryon.nfc.toHex
import io.github.arboeckh.geryon.nfc.writeGeryonTag
import io.github.arboeckh.geryon.ui.GeryonTheme
import io.github.arboeckh.geryon.ui.GrantPicker
import io.github.arboeckh.geryon.ui.MainScreen
import io.github.arboeckh.geryon.ui.NfcStatus

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null

    private var nfcStatus by mutableStateOf(NfcStatus.Unavailable)
    private var writeMode by mutableStateOf(false)
    private var lastScan by mutableStateOf<TagScan?>(null)
    private var message by mutableStateOf<String?>(null)
    private var pendingGrant by mutableStateOf<ScanOutcome.PickGrant?>(null)
    private var writePresetId by mutableStateOf<String?>(Defaults.GRANT_TAG_ID)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        handleTagIntent(intent)

        setContent {
            GeryonTheme {
                MainScreen(
                    nfcStatus = nfcStatus,
                    writeMode = writeMode,
                    lastScan = lastScan,
                    message = message,
                    onWriteModeChange = {
                        writeMode = it
                        message = null
                    },
                    onOpenNfcSettings = { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) },
                    writePresets = Defaults.presetBindings.map { it.tagId to it.name } + (null to "Blank"),
                    writePresetId = writePresetId,
                    onWritePresetChange = { writePresetId = it },
                )
                pendingGrant?.let { request ->
                    GrantPicker(
                        request = request,
                        onPick = { target ->
                            pendingGrant = null
                            message = geryon.blocks.grant(target.id, request.minutes).fold(
                                onSuccess = { "${target.name} unlocked for ${request.minutes} min" },
                                onFailure = { it.message ?: "Couldn't unlock ${target.name}" },
                            )
                        },
                        onDismiss = { pendingGrant = null },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter
        nfcStatus = when {
            adapter == null -> NfcStatus.Unavailable
            !adapter.isEnabled -> NfcStatus.Off
            else -> NfcStatus.Ready
        }
        // While geryon is in the foreground it reads every tag itself instead of
        // letting Android dispatch intents to other apps.
        adapter?.enableReaderMode(this, this, READER_FLAGS, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTagIntent(intent)
    }

    /** The app was launched (or brought forward) by tapping a geryon tag. */
    private fun handleTagIntent(intent: Intent?) {
        if (intent?.action != NfcAdapter.ACTION_NDEF_DISCOVERED) return
        val tag = IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag::class.java)
        val ndef = IntentCompat.getParcelableArrayExtra(intent, NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
            ?.firstOrNull() as? NdefMessage
        val uri = intent.data ?: firstUri(ndef)
        lastScan = TagScan(
            uid = tag?.id?.toHex() ?: "unknown",
            geryonId = geryonIdFrom(uri),
            uri = uri?.toString(),
            source = TagScan.Source.LaunchedApp,
        )
        message = lastScan?.geryonId?.let(::runTagAction)
    }

    /** Runs whatever the tag is bound to. Returns the message to show. Main thread only. */
    private fun runTagAction(tagId: String): String? =
        when (val outcome = geryon.blocks.onTagScanned(tagId)) {
            is ScanOutcome.Unbound -> "This tag isn't set up yet."
            is ScanOutcome.Applied -> outcome.message
            is ScanOutcome.PickGrant -> {
                pendingGrant = outcome
                null
            }
        }

    /** Called on a binder thread for every tag tapped while the app is in the foreground. */
    override fun onTagDiscovered(tag: Tag) {
        if (writeMode) {
            val preset = writePresetId
            val result = if (preset != null) writeGeryonTag(tag, packageName, preset) else writeGeryonTag(tag, packageName)
            runOnUiThread {
                result.onSuccess {
                    lastScan = it
                    val name = geryon.blocks.config.value.binding(it.geryonId.orEmpty())?.name
                    message = if (name != null) "Tag written: $name. Tap it again to use it."
                    else "Tag written. Close the app, then tap the tag again to test the launch."
                    writeMode = false
                }.onFailure {
                    message = "Write failed: ${it.message}"
                }
            }
        } else {
            val scan = readTag(tag)
            runOnUiThread {
                lastScan = scan
                message = scan.geryonId?.let(::runTagAction)
                    ?: if (scan.geryonId == null) "Not a geryon tag. Switch to Write to set it up." else null
            }
        }
    }

    private companion object {
        const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V
    }
}
