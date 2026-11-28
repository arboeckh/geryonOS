package io.github.arboeckh.geryon.block

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Catalogue: what exists to be blocked. Shipped as assets/catalog.json, later synced.
// ---------------------------------------------------------------------------

/**
 * A thing the user thinks of as one unit, e.g. "YouTube": its app packages and its domains.
 * Granting a target unlocks all of them together.
 */
@Serializable
data class Target(
    val id: String,
    val name: String,
    val packages: Set<String> = emptySet(),
    /** Domain suffixes: "youtube.com" also matches "m.youtube.com". */
    val domains: Set<String> = emptySet(),
)

/** A group of targets plus loose domains, e.g. "social" or "adult". */
@Serializable
data class Category(
    val id: String,
    val name: String,
    val targets: Set<String> = emptySet(),
    val domains: Set<String> = emptySet(),
    /** Hard categories are always blocked. Nothing (grants, profiles) can override them. */
    val hard: Boolean = false,
)

@Serializable
data class Catalog(
    val version: Int = 1,
    val targets: List<Target> = emptyList(),
    val categories: List<Category> = emptyList(),
)

// ---------------------------------------------------------------------------
// User configuration: profiles and what each tag does. Persisted as config.json.
// ---------------------------------------------------------------------------

@Serializable
enum class Effect { Block, Allow }

@Serializable
sealed interface Selector {
    @Serializable @SerialName("target")
    data class OfTarget(val id: String) : Selector

    @Serializable @SerialName("category")
    data class OfCategory(val id: String) : Selector
}

@Serializable
data class Rule(val effect: Effect, val selector: Selector)

@Serializable
data class Profile(val id: String, val name: String, val rules: List<Rule> = emptyList())

/** What scanning a tag does. Tags only hold geryon://tag/<id>; the meaning lives here. */
@Serializable
sealed interface TagAction {
    /** Turn a profile on for [minutes]. Scanning again restarts the timer. */
    @Serializable @SerialName("start_profile")
    data class StartProfile(val profileId: String, val minutes: Int) : TagAction

    /** Turn a profile on (no timer) or off. On the base profile this pauses/resumes it. */
    @Serializable @SerialName("toggle_profile")
    data class ToggleProfile(val profileId: String) : TagAction

    /**
     * Let the user pick one target to unlock for [minutes].
     * [targets] limits the choice; null means every target outside hard categories.
     */
    @Serializable @SerialName("grant")
    data class Grant(val targets: Set<String>? = null, val minutes: Int = 10) : TagAction

    /** Re-establish the block: drop all grants, resume the base profile, optionally end profiles. */
    @Serializable @SerialName("lockdown")
    data class Lockdown(val endProfiles: Boolean = false) : TagAction
}

@Serializable
data class TagBinding(val tagId: String, val name: String, val action: TagAction)

@Serializable
data class BlockConfig(
    val baseProfileId: String,
    val profiles: List<Profile> = emptyList(),
    val bindings: List<TagBinding> = emptyList(),
) {
    fun profile(id: String): Profile? = profiles.firstOrNull { it.id == id }
    fun binding(tagId: String): TagBinding? = bindings.firstOrNull { it.tagId == tagId }
}

// ---------------------------------------------------------------------------
// Runtime state: what is switched on right now. Persisted as state.json.
// ---------------------------------------------------------------------------

@Serializable
data class ActiveProfile(val profileId: String, val startedAt: Long, val until: Long? = null)

@Serializable
data class Grant(val targetId: String, val startedAt: Long, val until: Long)

@Serializable
data class BlockState(
    val activeProfiles: List<ActiveProfile> = emptyList(),
    val grants: List<Grant> = emptyList(),
    /** Set by toggling the base profile off. Lockdown clears it. */
    val basePaused: Boolean = false,
) {
    /** Earliest timer that ends after [now], or null if nothing is on a timer. */
    fun nextExpiry(now: Long): Long? =
        (activeProfiles.mapNotNull { it.until } + grants.map { it.until })
            .filter { it > now }
            .minOrNull()
}

// ---------------------------------------------------------------------------
// Evaluation
// ---------------------------------------------------------------------------

/** Something an enforcer asks about. */
sealed interface Subject {
    data class App(val packageName: String) : Subject
    data class Domain(val name: String) : Subject
}

/** Which layer of the stack decided. Listed top (wins) to bottom. */
enum class Layer { Hard, Grant, Profile, Base, Default }

data class Decision(
    val blocked: Boolean,
    val layer: Layer,
    /** Category, profile or target id that decided, for display. */
    val sourceId: String? = null,
    /** Display name of [sourceId]. */
    val sourceName: String? = null,
    /** For grants: when the allowance ends. */
    val until: Long? = null,
) {
    companion object {
        val Allowed = Decision(blocked = false, layer = Layer.Default)
    }
}
