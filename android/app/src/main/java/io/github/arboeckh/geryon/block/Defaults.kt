package io.github.arboeckh.geryon.block

import kotlinx.serialization.json.Json

val GeryonJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    classDiscriminator = "type"
}

/**
 * First-run configuration: a base profile that blocks social media and video, plus two
 * preset tags (write them from the Write tab): unlock one app for 10 minutes, and restore the block.
 */
object Defaults {
    const val BASE_PROFILE_ID = "base"
    const val GRANT_TAG_ID = "unlock-10"
    const val RESTORE_TAG_ID = "restore"

    val presetBindings = listOf(
        TagBinding(GRANT_TAG_ID, "Unlock an app for 10 min", TagAction.Grant(minutes = 10)),
        TagBinding(RESTORE_TAG_ID, "Restore blocks", TagAction.Lockdown(endProfiles = true)),
    )

    val config = BlockConfig(
        bindings = presetBindings,
        baseProfileId = BASE_PROFILE_ID,
        profiles = listOf(
            Profile(
                id = BASE_PROFILE_ID,
                name = "Base",
                rules = listOf(
                    Rule(Effect.Block, Selector.OfCategory("social")),
                    Rule(Effect.Block, Selector.OfCategory("video")),
                ),
            ),
        ),
    )
}
