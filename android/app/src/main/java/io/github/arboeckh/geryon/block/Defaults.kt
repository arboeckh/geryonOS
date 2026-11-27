package io.github.arboeckh.geryon.block

import kotlinx.serialization.json.Json

val GeryonJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    classDiscriminator = "type"
}

/** First-run configuration: a base profile that blocks social media and video. */
object Defaults {
    const val BASE_PROFILE_ID = "base"

    val config = BlockConfig(
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
