package io.github.arboeckh.geryon.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PolicyTest {

    private val catalog = Catalog(
        targets = listOf(
            Target("youtube", "YouTube", setOf("com.google.android.youtube"), setOf("youtube.com", "googlevideo.com")),
            Target("instagram", "Instagram", setOf("com.instagram.android"), setOf("instagram.com")),
            Target("mail", "Mail", setOf("com.mail"), setOf("mail.example")),
            Target("pornapp", "Porn app", setOf("com.porn"), emptySet()),
        ),
        categories = listOf(
            Category("social", "Social", targets = setOf("instagram")),
            Category("video", "Video", targets = setOf("youtube")),
            Category("work", "Work", targets = setOf("mail")),
            Category("adult", "Adult", targets = setOf("pornapp"), domains = setOf("badsite.com"), hard = true),
        ),
    )
    private val index = CatalogIndex(catalog)

    private val config = BlockConfig(
        baseProfileId = "base",
        profiles = listOf(
            Profile("base", "Base", listOf(Rule(Effect.Block, Selector.OfCategory("social")), Rule(Effect.Block, Selector.OfCategory("video")))),
            Profile("focus", "Focus", listOf(Rule(Effect.Block, Selector.OfCategory("work")))),
            Profile("evening", "Evening", listOf(Rule(Effect.Allow, Selector.OfCategory("video")), Rule(Effect.Block, Selector.OfTarget("mail")))),
            Profile("mixed", "Mixed", listOf(Rule(Effect.Block, Selector.OfCategory("video")), Rule(Effect.Allow, Selector.OfTarget("youtube")))),
        ),
    )

    private val now = 1_000_000L
    private val yt = Subject.App("com.google.android.youtube")
    private val ig = Subject.App("com.instagram.android")
    private val mail = Subject.App("com.mail")

    private fun policy(state: BlockState = BlockState()) = Policy(index, config, state)

    @Test fun `unknown apps and domains are allowed`() {
        assertEquals(Decision.Allowed, policy().decide(Subject.App("com.calculator"), now))
        assertEquals(Decision.Allowed, policy().decide(Subject.Domain("wikipedia.org"), now))
    }

    @Test fun `base profile blocks its categories`() {
        val d = policy().decide(yt, now)
        assertTrue(d.blocked)
        assertEquals(Layer.Base, d.layer)
        assertFalse(policy().isBlocked(mail, now))
    }

    @Test fun `domains match by suffix and are case insensitive`() {
        assertTrue(policy().isBlocked(Subject.Domain("M.YouTube.com."), now))
        assertTrue(policy().isBlocked(Subject.Domain("r3---sn-abc.googlevideo.com"), now))
        assertFalse(policy().isBlocked(Subject.Domain("notyoutube.com"), now))
    }

    @Test fun `grant overrides base for that target only, until it expires`() {
        val state = Actions.grant(BlockState(), index, "youtube", 10, now).getOrThrow()
        val d = policy(state).decide(yt, now + 1)
        assertFalse(d.blocked)
        assertEquals(Layer.Grant, d.layer)
        assertEquals(now + 600_000, d.until)
        assertFalse(policy(state).isBlocked(Subject.Domain("youtube.com"), now + 1))
        assertTrue(policy(state).isBlocked(ig, now + 1))
        // Expired grant no longer counts, even before it is pruned.
        assertTrue(policy(state).isBlocked(yt, now + 600_000))
    }

    @Test fun `hard categories can't be granted or overridden`() {
        assertTrue(Actions.grant(BlockState(), index, "pornapp", 10, now).isFailure)
        assertFalse(index.isGrantable("pornapp"))
        val forged = BlockState(grants = listOf(Grant("pornapp", now, now + 1000)), basePaused = true)
        val d = policy(forged).decide(Subject.App("com.porn"), now)
        assertTrue(d.blocked)
        assertEquals(Layer.Hard, d.layer)
        assertTrue(policy(forged).isBlocked(Subject.Domain("www.badsite.com"), now))
    }

    @Test fun `grant options exclude hard targets`() {
        assertEquals(listOf("youtube", "instagram", "mail"), Actions.grantOptions(index, TagAction.Grant()).map { it.id })
        assertEquals(listOf("youtube"), Actions.grantOptions(index, TagAction.Grant(setOf("youtube", "pornapp"))).map { it.id })
    }

    @Test fun `timed profile adds blocks and ends on its own`() {
        val state = Actions.startProfile(BlockState(), "focus", 30, now)
        assertTrue(policy(state).isBlocked(mail, now + 1))
        assertEquals(Layer.Profile, policy(state).decide(mail, now + 1).layer)
        assertFalse(policy(state).isBlocked(mail, now + 30 * 60_000))
        assertEquals(now + 30 * 60_000, state.nextExpiry(now))
        assertTrue(Actions.prune(state, now + 30 * 60_000).activeProfiles.isEmpty())
    }

    @Test fun `profile allow overrides base block`() {
        val state = Actions.toggleProfile(BlockState(), config, "evening", now)
        assertFalse(policy(state).isBlocked(yt, now))
        assertTrue(policy(state).isBlocked(ig, now))
    }

    @Test fun `most recently started profile wins`() {
        var state = Actions.startProfile(BlockState(), "focus", 60, now)
        state = Actions.toggleProfile(state, config, "evening", now + 1)
        assertFalse(policy(state).isBlocked(yt, now + 2))
        state = Actions.startProfile(state, "mixed", 60, now + 3)
        assertFalse(policy(state).isBlocked(yt, now + 4)) // target rule beats category rule in "mixed"
    }

    @Test fun `block wins over allow at the same specificity`() {
        val cfg = config.copy(profiles = config.profiles + Profile("both", "Both", listOf(
            Rule(Effect.Allow, Selector.OfCategory("video")), Rule(Effect.Block, Selector.OfCategory("video")),
        )))
        val state = Actions.toggleProfile(BlockState(basePaused = true), cfg, "both", now)
        assertTrue(Policy(index, cfg, state).isBlocked(yt, now))
    }

    @Test fun `toggling a profile flips it`() {
        val on = Actions.toggleProfile(BlockState(), config, "focus", now)
        assertTrue(Actions.isProfileOn(on, config, "focus", now))
        val off = Actions.toggleProfile(on, config, "focus", now)
        assertFalse(Actions.isProfileOn(off, config, "focus", now))
    }

    @Test fun `toggling the base profile pauses it and lockdown resumes it`() {
        val paused = Actions.toggleProfile(BlockState(), config, "base", now)
        assertTrue(paused.basePaused)
        assertFalse(policy(paused).isBlocked(yt, now))
        assertTrue(policy(Actions.lockdown(paused, endProfiles = false)).isBlocked(yt, now))
    }

    @Test fun `lockdown clears grants and optionally profiles`() {
        var state = Actions.grant(BlockState(), index, "youtube", 10, now).getOrThrow()
        state = Actions.startProfile(state, "focus", 30, now)
        val soft = Actions.lockdown(state, endProfiles = false)
        assertTrue(soft.grants.isEmpty())
        assertEquals(1, soft.activeProfiles.size)
        assertTrue(Actions.lockdown(state, endProfiles = true).activeProfiles.isEmpty())
    }

    @Test fun `re-granting a target restarts its timer instead of stacking`() {
        var state = Actions.grant(BlockState(), index, "youtube", 10, now).getOrThrow()
        state = Actions.grant(state, index, "youtube", 5, now + 1000).getOrThrow()
        assertEquals(1, state.grants.size)
        assertEquals(now + 1000 + 5 * 60_000, state.grants.single().until)
    }

    @Test fun `extra category domains are matched`() {
        val withList = CatalogIndex(catalog, mapOf("adult" to setOf("huge-list-entry.net")))
        assertTrue(Policy(withList, config, BlockState()).isBlocked(Subject.Domain("cdn.huge-list-entry.net"), now))
    }

    @Test fun `config round-trips through json`() {
        val cfg = config.copy(bindings = listOf(
            TagBinding("a1", "Desk", TagAction.Grant(setOf("youtube"), 10)),
            TagBinding("b2", "Door", TagAction.Lockdown(endProfiles = true)),
            TagBinding("c3", "Focus", TagAction.StartProfile("focus", 45)),
            TagBinding("d4", "Toggle", TagAction.ToggleProfile("evening")),
        ))
        val json = GeryonJson.encodeToString(BlockConfig.serializer(), cfg)
        assertEquals(cfg, GeryonJson.decodeFromString(BlockConfig.serializer(), json))
    }

    @Test fun `bundled catalog parses and is consistent`() {
        val file = listOf("src/main/assets/catalog.json", "app/src/main/assets/catalog.json").map(::File).first { it.exists() }
        val bundled = GeryonJson.decodeFromString(Catalog.serializer(), file.readText())
        val ids = bundled.targets.map { it.id }.toSet()
        assertEquals("duplicate target ids", bundled.targets.size, ids.size)
        bundled.categories.forEach { c -> assertTrue("${c.id} names unknown targets", ids.containsAll(c.targets)) }
        Defaults.config.profiles.flatMap { it.rules }.forEach { r ->
            val sel = r.selector
            if (sel is Selector.OfCategory) assertTrue(bundled.categories.any { it.id == sel.id })
        }
    }
}
