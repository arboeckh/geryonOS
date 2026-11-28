package io.github.arboeckh.geryon.block

import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import java.io.File

/** What happened when a tag was scanned. */
sealed interface ScanOutcome {
    val tagId: String

    /** The tag has no action yet. */
    data class Unbound(override val tagId: String) : ScanOutcome

    /** The action ran. [message] is a one-line summary for the user. */
    data class Applied(override val tagId: String, val binding: TagBinding, val message: String) : ScanOutcome

    /** A grant tag: ask the user which target to unlock, then call [BlockRepository.grant]. */
    data class PickGrant(
        override val tagId: String,
        val binding: TagBinding,
        val options: List<Target>,
        val minutes: Int,
    ) : ScanOutcome
}

/**
 * Single source of truth for the catalogue, user config and runtime state.
 * Enforcers observe [policy]; UI observes [config] and [state].
 * One instance per process, owned by [io.github.arboeckh.geryon.GeryonApp].
 */
class BlockRepository(
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = File(context.filesDir, "block").apply { mkdirs() }

    private val _catalog = MutableStateFlow(loadCatalog())
    private val _extraDomains = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val _config = MutableStateFlow(read("config.json", BlockConfig.serializer()) ?: Defaults.config)
    private val _state = MutableStateFlow(Actions.prune(read("state.json", BlockState.serializer()) ?: BlockState(), clock()))

    val catalog: StateFlow<Catalog> = _catalog.asStateFlow()
    val config: StateFlow<BlockConfig> = _config.asStateFlow()
    val state: StateFlow<BlockState> = _state.asStateFlow()

    private val index: StateFlow<CatalogIndex> = combine(_catalog, _extraDomains) { c, extra -> CatalogIndex(c, extra) }
        .stateIn(scope, SharingStarted.Eagerly, CatalogIndex(_catalog.value))

    /** Latest snapshot for enforcers. Re-emitted on every change, including timers running out. */
    val policy: StateFlow<Policy> = combine(index, _config, _state) { i, c, s -> Policy(i, c, s) }
        .stateIn(scope, SharingStarted.Eagerly, Policy(index.value, _config.value, _state.value))

    init {
        scope.launch(Dispatchers.IO) { _config.drop(1).collectLatest { write("config.json", BlockConfig.serializer(), it) } }
        scope.launch(Dispatchers.IO) { _state.drop(1).collectLatest { write("state.json", BlockState.serializer(), it) } }
        // Drop expired entries as their timers end, so enforcers re-check the screen right away.
        scope.launch {
            _state.collectLatest { s ->
                val next = s.nextExpiry(clock()) ?: return@collectLatest
                delay((next - clock()).coerceAtLeast(0) + 50)
                _state.update { Actions.prune(it, clock()) }
            }
        }
    }

    // --- Tags -----------------------------------------------------------------------------

    fun onTagScanned(tagId: String): ScanOutcome {
        val binding = _config.value.binding(tagId) ?: return ScanOutcome.Unbound(tagId)
        val now = clock()
        val action = binding.action
        val message = when (action) {
            is TagAction.StartProfile -> {
                _state.update { Actions.startProfile(Actions.prune(it, now), action.profileId, action.minutes, now) }
                "${profileName(action.profileId)} on for ${action.minutes} min"
            }
            is TagAction.ToggleProfile -> {
                var on = false
                _state.update {
                    Actions.toggleProfile(Actions.prune(it, now), _config.value, action.profileId, now)
                        .also { s -> on = Actions.isProfileOn(s, _config.value, action.profileId, now) }
                }
                "${profileName(action.profileId)} ${if (on) "on" else "off"}"
            }
            is TagAction.Lockdown -> {
                _state.update { Actions.lockdown(Actions.prune(it, now), action.endProfiles) }
                if (action.endProfiles) "Block restored, profiles ended" else "Block restored"
            }
            is TagAction.Grant -> return ScanOutcome.PickGrant(
                tagId, binding, Actions.grantOptions(index.value, action), action.minutes,
            )
        }
        return ScanOutcome.Applied(tagId, binding, message)
    }

    /** Unlocks a target picked after a grant tag. */
    fun grant(targetId: String, minutes: Int): Result<Grant> {
        val now = clock()
        var result: Result<BlockState> = Result.failure(IllegalStateException())
        _state.update { current ->
            result = Actions.grant(Actions.prune(current, now), index.value, targetId, minutes, now)
            result.getOrDefault(current)
        }
        return result.map { s -> s.grants.first { it.targetId == targetId } }
    }

    fun endGrant(targetId: String) = _state.update { s -> s.copy(grants = s.grants.filterNot { it.targetId == targetId }) }

    // --- Direct control from the UI (same transitions as the matching tags) ---------------

    /** Same as a toggle tag: flips a profile on/off, or pauses/resumes the base profile. */
    fun toggleProfile(profileId: String) {
        val now = clock()
        _state.update { Actions.toggleProfile(Actions.prune(it, now), _config.value, profileId, now) }
    }

    /** Ends a running (timed or untimed) profile early. */
    fun endProfile(profileId: String) =
        _state.update { s -> s.copy(activeProfiles = s.activeProfiles.filterNot { it.profileId == profileId }) }

    // --- Config editing ------------------------------------------------------------------

    fun bindTag(binding: TagBinding) = _config.update { c ->
        c.copy(bindings = c.bindings.filterNot { it.tagId == binding.tagId } + binding)
    }

    fun unbindTag(tagId: String) = _config.update { c -> c.copy(bindings = c.bindings.filterNot { it.tagId == tagId }) }

    fun upsertProfile(profile: Profile) = _config.update { c ->
        val i = c.profiles.indexOfFirst { it.id == profile.id }
        c.copy(profiles = if (i < 0) c.profiles + profile else c.profiles.toMutableList().also { it[i] = profile })
    }

    /** Removes a profile and anything pointing at it. The base profile can't be deleted. */
    fun deleteProfile(profileId: String) {
        if (profileId == _config.value.baseProfileId) return
        _config.update { c ->
            c.copy(
                profiles = c.profiles.filterNot { it.id == profileId },
                bindings = c.bindings.filterNot {
                    val a = it.action
                    (a is TagAction.StartProfile && a.profileId == profileId) ||
                        (a is TagAction.ToggleProfile && a.profileId == profileId)
                },
            )
        }
        _state.update { s -> s.copy(activeProfiles = s.activeProfiles.filterNot { it.profileId == profileId }) }
    }

    // --- Catalogue -----------------------------------------------------------------------

    /** Replaces the catalogue, e.g. after a sync. Persisted; it wins over the bundled asset. */
    fun setCatalog(catalog: Catalog) {
        _catalog.value = catalog
        scope.launch(Dispatchers.IO) { write("catalog.json", Catalog.serializer(), catalog) }
    }

    /** Attaches large external domain lists to categories (category id → domain suffixes). */
    fun setExtraCategoryDomains(lists: Map<String, Set<String>>) {
        _extraDomains.value = lists
    }

    // --- Internals -----------------------------------------------------------------------

    private fun profileName(id: String) = _config.value.profile(id)?.name ?: id

    private fun loadCatalog(): Catalog =
        read("catalog.json", Catalog.serializer())
            ?: context.assets.open("catalog.json").bufferedReader().use { GeryonJson.decodeFromString(Catalog.serializer(), it.readText()) }

    private fun <T> read(name: String, serializer: KSerializer<T>): T? {
        val file = AtomicFile(File(dir, name))
        if (!file.baseFile.exists()) return null
        return runCatching { GeryonJson.decodeFromString(serializer, file.readFully().decodeToString()) }
            .onFailure { Log.w(TAG, "Couldn't read $name, using defaults", it) }
            .getOrNull()
    }

    private fun <T> write(name: String, serializer: KSerializer<T>, value: T) {
        val file = AtomicFile(File(dir, name))
        val out = file.startWrite()
        try {
            out.write(GeryonJson.encodeToString(serializer, value).encodeToByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.e(TAG, "Couldn't write $name", e)
        }
    }

    private companion object {
        const val TAG = "BlockRepository"
    }
}
