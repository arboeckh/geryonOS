package io.github.arboeckh.geryon

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import io.github.arboeckh.geryon.nfc.TagScan
import io.github.arboeckh.geryon.nfc.firstUri
import io.github.arboeckh.geryon.nfc.geryonIdFrom
import io.github.arboeckh.geryon.nfc.readTag
import io.github.arboeckh.geryon.nfc.toHex
import io.github.arboeckh.geryon.nfc.writeGeryonTag
import io.github.arboeckh.geryon.ui.AppModel
import io.github.arboeckh.geryon.ui.GeryonRoot
import io.github.arboeckh.geryon.ui.GeryonTheme
import io.github.arboeckh.geryon.ui.NfcStatus

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        // Only on a fresh launch: after rotation the same intent would run the tag action twice.
        if (savedInstanceState == null) handleTagIntent(intent)

        setContent {
            GeryonTheme { GeryonRoot(model) }
        }
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter
        model.nfcStatus = when {
            adapter == null -> NfcStatus.Unavailable
            !adapter.isEnabled -> NfcStatus.Off
            else -> NfcStatus.Ready
        }
        model.onResumed()
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
        val scan = TagScan(
            uid = tag?.id?.toHex() ?: "unknown",
            geryonId = geryonIdFrom(uri),
            uri = uri?.toString(),
            source = TagScan.Source.LaunchedApp,
        )
        model.onTagRead(scan, geryon.blocks)
    }

    /**
     * Called on a binder thread for every tag tapped while the app is in the foreground.
     * While the scan screen is open, a tag without a geryon id gets one written (the tag is
     * only connected during this callback, so the write has to happen here).
     */
    override fun onTagDiscovered(tag: Tag) {
        val scan = readTag(tag)
        if (model.capturing != null && scan.geryonId == null) {
            val result = writeGeryonTag(tag, packageName)
            runOnUiThread { model.onTagWritten(result, geryon.blocks) }
        } else {
            runOnUiThread { model.onTagRead(scan, geryon.blocks) }
        }
    }

    private companion object {
        const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V
    }
}
