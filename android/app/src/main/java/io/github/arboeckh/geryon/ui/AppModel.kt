package io.github.arboeckh.geryon.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.github.arboeckh.geryon.block.BlockRepository
import io.github.arboeckh.geryon.block.ScanOutcome
import io.github.arboeckh.geryon.nfc.TagScan

enum class NfcStatus { Unavailable, Off, Ready }

/** Everywhere the app can be. The four tabs are roots; the rest are pushed on top. */
sealed interface Screen {
    data object Home : Screen
    data object Tags : Screen
    data object Profiles : Screen
    data object Catalog : Screen

    data class TagEditor(val tagId: String, val isNew: Boolean) : Screen

    /** null = new profile. */
    data class ProfileEditor(val profileId: String?) : Screen

    /** Waiting for a tag to be held to the phone, to bind it or to move a binding onto it. */
    data class ScanTag(val purpose: ScanPurpose) : Screen
}

sealed interface ScanPurpose {
    data object NewBinding : ScanPurpose
    data class Rebind(val fromTagId: String) : ScanPurpose
}

val TabScreens = listOf(Screen.Home, Screen.Tags, Screen.Profiles, Screen.Catalog)

class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val top: Screen get() = stack.last()

    fun push(screen: Screen) {
        stack += screen
    }

    fun replaceTop(screen: Screen) {
        stack[stack.lastIndex] = screen
    }

    fun selectTab(tab: Screen) {
        stack.clear()
        stack += tab
    }

    /** Returns false when there's nowhere to go back to (let the system finish the activity). */
    fun back(): Boolean = when {
        stack.size > 1 -> {
            stack.removeAt(stack.lastIndex)
            true
        }
        top != Screen.Home -> {
            selectTab(Screen.Home)
            true
        }
        else -> false
    }
}

data class OutcomeMessage(val text: String, val at: Long = System.currentTimeMillis())

/**
 * UI state that outlives recomposition and rotation: where we are, and what the last tag did.
 * The activity feeds it NFC events; screens read it.
 */
class AppModel : ViewModel() {
    val nav = Navigator()

    var nfcStatus by mutableStateOf(NfcStatus.Unavailable)

    /** Bumped in onResume so screens re-check things like enforcer status. */
    var resumeCount by mutableIntStateOf(0)
        private set

    /** What the last scanned tag did. Shown on Home and as a snackbar elsewhere. */
    var lastOutcome by mutableStateOf<OutcomeMessage?>(null)
        private set

    var pendingGrant by mutableStateOf<ScanOutcome.PickGrant?>(null)

    /** An unbound tag was scanned: offer to set it up. */
    var pendingSetup by mutableStateOf<String?>(null)

    /** Raw details of the last tag seen, shown on the scan screen. */
    var lastScan by mutableStateOf<TagScan?>(null)
        private set

    /** Status line on the scan screen (errors, "not a geryon tag"…). */
    var scanMessage by mutableStateOf<String?>(null)

    val capturing: ScanPurpose? get() = (nav.top as? Screen.ScanTag)?.purpose

    fun onResumed() {
        resumeCount++
    }

    fun report(text: String) {
        lastOutcome = OutcomeMessage(text)
    }

    fun startScan(purpose: ScanPurpose) {
        scanMessage = null
        nav.push(Screen.ScanTag(purpose))
    }

    /** A tag was read, either in the foreground or because it launched the app. Main thread. */
    fun onTagRead(scan: TagScan, blocks: BlockRepository) {
        lastScan = scan
        val id = scan.geryonId
        val purpose = capturing
        if (purpose != null) {
            if (id == null) scanMessage = "Couldn't read a geryon id from that tag. Hold it still and try again."
            else captured(id, purpose, blocks)
            return
        }
        if (id == null) {
            report("Not a geryon tag. Add it from the Tags tab to set it up.")
            return
        }
        when (val outcome = blocks.onTagScanned(id)) {
            is ScanOutcome.Unbound -> pendingSetup = id
            is ScanOutcome.Applied -> report(outcome.message)
            is ScanOutcome.PickGrant -> pendingGrant = outcome
        }
    }

    /** A blank (or foreign) tag was written while capturing. Main thread. */
    fun onTagWritten(result: Result<TagScan>, blocks: BlockRepository) {
        val purpose = capturing ?: return
        result.onSuccess { scan ->
            lastScan = scan
            captured(scan.geryonId ?: return, purpose, blocks)
        }.onFailure {
            scanMessage = "Couldn't write the tag: ${it.message ?: it.javaClass.simpleName}"
        }
    }

    private fun captured(tagId: String, purpose: ScanPurpose, blocks: BlockRepository) {
        val config = blocks.config.value
        val existing = config.binding(tagId)
        when (purpose) {
            ScanPurpose.NewBinding -> {
                nav.replaceTop(Screen.TagEditor(tagId, isNew = existing == null))
                if (existing != null) report("That tag is already set up as “${existing.name}”")
            }
            is ScanPurpose.Rebind -> {
                val binding = config.binding(purpose.fromTagId)
                when {
                    binding == null -> nav.back()
                    tagId == purpose.fromTagId -> scanMessage = "That's the same tag. Hold a different one."
                    existing != null -> scanMessage = "That tag is already set up as “${existing.name}”. Delete it first."
                    else -> {
                        blocks.bindTag(binding.copy(tagId = tagId))
                        blocks.unbindTag(purpose.fromTagId)
                        nav.back()
                        nav.replaceTop(Screen.TagEditor(tagId, isNew = false))
                        report("Moved “${binding.name}” to the new tag")
                    }
                }
            }
        }
    }

    fun setUpPendingTag() {
        val id = pendingSetup ?: return
        pendingSetup = null
        nav.push(Screen.TagEditor(id, isNew = true))
    }
}
