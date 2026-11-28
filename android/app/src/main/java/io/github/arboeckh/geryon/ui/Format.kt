package io.github.arboeckh.geryon.ui

import io.github.arboeckh.geryon.block.BlockConfig
import io.github.arboeckh.geryon.block.BlockState
import io.github.arboeckh.geryon.block.Catalog
import io.github.arboeckh.geryon.block.Decision
import io.github.arboeckh.geryon.block.Effect
import io.github.arboeckh.geryon.block.Layer
import io.github.arboeckh.geryon.block.Policy
import io.github.arboeckh.geryon.block.Profile
import io.github.arboeckh.geryon.block.Selector
import io.github.arboeckh.geryon.block.Subject
import io.github.arboeckh.geryon.block.TagAction
import io.github.arboeckh.geryon.block.Target

// Pure display helpers. Kept free of Compose so they can be unit tested.

/** "4:05" under an hour, "1h 05m" above. Never negative. */
fun formatRemaining(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%dh %02dm".format(h, m) else "%d:%02d".format(m, sec)
}

/** "just now", "3 min ago", "2 h ago". */
fun formatAgo(ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        else -> "${min / 60} h ago"
    }
}

fun formatMinutes(minutes: Int): String =
    if (minutes >= 60 && minutes % 60 == 0) "${minutes / 60} h"
    else if (minutes > 60) "${minutes / 60} h ${minutes % 60} min"
    else "$minutes min"

/** Steps the minutes stepper moves through. */
val MinuteSteps = listOf(1, 2, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 360, 480, 720)

fun nextMinutes(current: Int): Int = MinuteSteps.firstOrNull { it > current } ?: current
fun previousMinutes(current: Int): Int = MinuteSteps.lastOrNull { it < current } ?: current

fun selectorName(selector: Selector, catalog: Catalog): String = when (selector) {
    is Selector.OfCategory -> catalog.categories.firstOrNull { it.id == selector.id }?.name ?: selector.id
    is Selector.OfTarget -> catalog.targets.firstOrNull { it.id == selector.id }?.name ?: selector.id
}

/** "Blocks Social, Video · Allows YouTube", or "No rules". */
fun ruleSummary(profile: Profile, catalog: Catalog): String {
    val blocks = profile.rules.filter { it.effect == Effect.Block }.map { selectorName(it.selector, catalog) }
    val allows = profile.rules.filter { it.effect == Effect.Allow }.map { selectorName(it.selector, catalog) }
    val parts = buildList {
        if (blocks.isNotEmpty()) add("Blocks " + blocks.joinToString(", "))
        if (allows.isNotEmpty()) add("Allows " + allows.joinToString(", "))
    }
    return parts.joinToString(" · ").ifEmpty { "No rules" }
}

/** One line saying what a tag does. */
fun describeAction(action: TagAction, config: BlockConfig, catalog: Catalog): String {
    fun profile(id: String) = config.profile(id)?.name ?: "missing profile"
    return when (action) {
        is TagAction.StartProfile -> "Turn on ${profile(action.profileId)} for ${formatMinutes(action.minutes)}"
        is TagAction.ToggleProfile ->
            if (action.profileId == config.baseProfileId) "Pause or resume the block"
            else "Turn ${profile(action.profileId)} on or off"
        is TagAction.Grant -> {
            val targets = action.targets
            val what = when {
                targets == null -> "any app or site"
                targets.size <= 3 -> targets.joinToString(" or ") { id -> catalog.targets.firstOrNull { it.id == id }?.name ?: id }
                else -> "one of ${targets.size} apps or sites"
            }
            "Unlock $what for ${formatMinutes(action.minutes)}"
        }
        is TagAction.Lockdown -> if (action.endProfiles) "Restore the block and end profiles" else "Restore the block"
    }
}

/** Whether a profile is switched on right now, and until when. */
data class ProfileRunState(val on: Boolean, val until: Long?)

fun profileRunState(profileId: String, config: BlockConfig, state: BlockState, now: Long): ProfileRunState {
    val active = state.activeProfiles.firstOrNull { it.profileId == profileId && (it.until == null || it.until > now) }
    if (active != null) return ProfileRunState(true, active.until)
    return ProfileRunState(profileId == config.baseProfileId && !state.basePaused, null)
}

/**
 * What the policy currently says about a whole target. Asks about its apps if it has any
 * (domains are often shared between targets), otherwise its domains. Any "blocked" wins.
 */
fun targetDecision(policy: Policy, target: Target, now: Long): Decision {
    val subjects: List<Subject> =
        if (target.packages.isNotEmpty()) target.packages.map { Subject.App(it) }
        else target.domains.map { Subject.Domain(it) }
    val decisions = subjects.map { policy.decide(it, now) }
    return decisions.firstOrNull { it.blocked }
        ?: decisions.firstOrNull { it.layer == Layer.Grant }
        ?: decisions.firstOrNull()
        ?: Decision.Allowed
}

fun describeDecision(d: Decision, now: Long): String = when (d.layer) {
    Layer.Hard -> "Always blocked (${d.sourceName ?: d.sourceId})"
    Layer.Grant -> "Unlocked · ${formatRemaining((d.until ?: now) - now)} left"
    Layer.Profile, Layer.Base -> (if (d.blocked) "Blocked by " else "Allowed by ") + (d.sourceName ?: d.sourceId)
    Layer.Default -> "Not blocked"
}

/** A short id for new profiles: slug of the name plus a random suffix. */
fun newProfileId(name: String, taken: Set<String>): String {
    val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(20).ifEmpty { "profile" }
    while (true) {
        val id = "$slug-" + (0..0xffff).random().toString(16).padStart(4, '0')
        if (id !in taken) return id
    }
}
