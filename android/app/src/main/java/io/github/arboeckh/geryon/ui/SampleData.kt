package io.github.arboeckh.geryon.ui

import io.github.arboeckh.geryon.block.ActiveProfile
import io.github.arboeckh.geryon.block.BlockConfig
import io.github.arboeckh.geryon.block.BlockState
import io.github.arboeckh.geryon.block.Catalog
import io.github.arboeckh.geryon.block.Category
import io.github.arboeckh.geryon.block.Effect
import io.github.arboeckh.geryon.block.Grant
import io.github.arboeckh.geryon.block.Profile
import io.github.arboeckh.geryon.block.Rule
import io.github.arboeckh.geryon.block.Selector
import io.github.arboeckh.geryon.block.TagAction
import io.github.arboeckh.geryon.block.TagBinding
import io.github.arboeckh.geryon.block.Target

/** Fixed data for Compose previews. */
internal object SampleData {
    const val NOW = 1_800_000_000_000L

    val catalog = Catalog(
        targets = listOf(
            Target("youtube", "YouTube", setOf("com.google.android.youtube"), setOf("youtube.com", "youtu.be")),
            Target("instagram", "Instagram", setOf("com.instagram.android"), setOf("instagram.com")),
            Target("reddit", "Reddit", setOf("com.reddit.frontpage"), setOf("reddit.com")),
            Target("netflix", "Netflix", setOf("com.netflix.mediaclient"), setOf("netflix.com")),
            Target("pornhub", "Pornhub", emptySet(), setOf("pornhub.com")),
        ),
        categories = listOf(
            Category("social", "Social media", setOf("instagram", "reddit")),
            Category("video", "Video", setOf("youtube", "netflix")),
            Category("adult", "Adult", setOf("pornhub"), setOf("xvideos.com", "xnxx.com"), hard = true),
        ),
    )

    val config = BlockConfig(
        baseProfileId = "base",
        profiles = listOf(
            Profile(
                "base", "Base",
                listOf(Rule(Effect.Block, Selector.OfCategory("social")), Rule(Effect.Block, Selector.OfCategory("video"))),
            ),
            Profile(
                "focus", "Deep work",
                listOf(Rule(Effect.Block, Selector.OfCategory("video")), Rule(Effect.Allow, Selector.OfTarget("youtube"))),
            ),
        ),
        bindings = listOf(
            TagBinding("3f9a1c2b", "Desk", TagAction.StartProfile("focus", 90)),
            TagBinding("a0b1c2d3", "Fridge", TagAction.Grant(setOf("youtube", "netflix"), 20)),
            TagBinding("77e1aa02", "Front door", TagAction.Lockdown(endProfiles = true)),
        ),
    )

    val state = BlockState(
        activeProfiles = listOf(ActiveProfile("focus", NOW - 600_000, NOW + 2_400_000)),
        grants = listOf(Grant("youtube", NOW - 120_000, NOW + 480_000)),
    )
}
