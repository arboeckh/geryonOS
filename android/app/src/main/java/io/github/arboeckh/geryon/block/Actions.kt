package io.github.arboeckh.geryon.block

/** Pure state transitions. The repository applies these atomically and persists the result. */
object Actions {

    private const val MINUTE = 60_000L

    /** Drops expired grants and timed profiles. */
    fun prune(state: BlockState, now: Long): BlockState = state.copy(
        activeProfiles = state.activeProfiles.filter { it.until == null || it.until > now },
        grants = state.grants.filter { it.until > now },
    )

    /** Starts (or restarts) a timed profile. */
    fun startProfile(state: BlockState, profileId: String, minutes: Int, now: Long): BlockState {
        val others = state.activeProfiles.filterNot { it.profileId == profileId }
        return state.copy(activeProfiles = others + ActiveProfile(profileId, now, now + minutes * MINUTE))
    }

    /** Flips a profile on (no timer) or off. Toggling the base profile pauses/resumes it. */
    fun toggleProfile(state: BlockState, config: BlockConfig, profileId: String, now: Long): BlockState {
        if (profileId == config.baseProfileId) return state.copy(basePaused = !state.basePaused)
        val isOn = state.activeProfiles.any { it.profileId == profileId && (it.until == null || it.until > now) }
        val others = state.activeProfiles.filterNot { it.profileId == profileId }
        return state.copy(activeProfiles = if (isOn) others else others + ActiveProfile(profileId, now))
    }

    fun isProfileOn(state: BlockState, config: BlockConfig, profileId: String, now: Long): Boolean =
        if (profileId == config.baseProfileId) !state.basePaused
        else state.activeProfiles.any { it.profileId == profileId && (it.until == null || it.until > now) }

    /** Unlocks [targetId] for [minutes]. Replaces any running grant for the same target. */
    fun grant(state: BlockState, index: CatalogIndex, targetId: String, minutes: Int, now: Long): Result<BlockState> {
        if (targetId !in index.targets) return Result.failure(IllegalArgumentException("Unknown target $targetId"))
        if (!index.isGrantable(targetId)) return Result.failure(IllegalStateException("${index.targets[targetId]?.name} can't be unlocked"))
        val others = state.grants.filterNot { it.targetId == targetId }
        return Result.success(state.copy(grants = others + Grant(targetId, now, now + minutes * MINUTE)))
    }

    fun lockdown(state: BlockState, endProfiles: Boolean): BlockState = state.copy(
        grants = emptyList(),
        basePaused = false,
        activeProfiles = if (endProfiles) emptyList() else state.activeProfiles,
    )

    /** Targets a grant tag offers: its own list (minus anything hard) or every grantable target. */
    fun grantOptions(index: CatalogIndex, action: TagAction.Grant): List<Target> {
        val allowed = action.targets
        return index.grantableTargets().filter { allowed == null || it.id in allowed }
    }
}
