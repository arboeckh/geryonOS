package io.github.arboeckh.geryon.ui

import io.github.arboeckh.geryon.block.BlockState
import io.github.arboeckh.geryon.block.CatalogIndex
import io.github.arboeckh.geryon.block.Decision
import io.github.arboeckh.geryon.block.Grant
import io.github.arboeckh.geryon.block.Layer
import io.github.arboeckh.geryon.block.Policy
import io.github.arboeckh.geryon.block.TagAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {
    private val catalog = SampleData.catalog
    private val config = SampleData.config
    private val now = SampleData.NOW
    private fun target(id: String) = catalog.targets.first { it.id == id }
    private fun policy(state: BlockState = BlockState()) = Policy(CatalogIndex(catalog), config, state)

    @Test fun `remaining time formats minutes and hours`() {
        assertEquals("0:00", formatRemaining(-5_000))
        assertEquals("4:05", formatRemaining(245_000))
        assertEquals("1h 05m", formatRemaining(3_900_000))
    }

    @Test fun `minutes stepper moves through steps and stops at the ends`() {
        assertEquals(15, nextMinutes(10))
        assertEquals(15, nextMinutes(12))
        assertEquals(10, previousMinutes(12))
        assertEquals(1, previousMinutes(1))
        assertEquals(MinuteSteps.last(), nextMinutes(MinuteSteps.last()))
        assertEquals("1 h 30 min", formatMinutes(90))
        assertEquals("2 h", formatMinutes(120))
    }

    @Test fun `actions are described in words`() {
        assertEquals("Turn on Deep work for 1 h 30 min", describeAction(TagAction.StartProfile("focus", 90), config, catalog))
        assertEquals("Pause or resume the block", describeAction(TagAction.ToggleProfile("base"), config, catalog))
        assertEquals("Unlock any app or site for 10 min", describeAction(TagAction.Grant(null, 10), config, catalog))
        assertEquals("Unlock YouTube for 5 min", describeAction(TagAction.Grant(setOf("youtube"), 5), config, catalog))
        assertEquals(
            "Unlock one of 4 apps or sites for 5 min",
            describeAction(TagAction.Grant(setOf("youtube", "reddit", "netflix", "instagram"), 5), config, catalog),
        )
        assertEquals("Restore the block and end profiles", describeAction(TagAction.Lockdown(true), config, catalog))
    }

    @Test fun `rule summary names categories and targets`() {
        assertEquals("Blocks Video · Allows YouTube", ruleSummary(config.profile("focus")!!, catalog))
    }

    @Test fun `target decision reflects base, grants and hard categories`() {
        val blocked = targetDecision(policy(), target("youtube"), now)
        assertTrue(blocked.blocked)
        assertEquals(Layer.Base, blocked.layer)

        val granted = targetDecision(policy(BlockState(grants = listOf(Grant("youtube", now, now + 60_000)))), target("youtube"), now)
        assertFalse(granted.blocked)
        assertEquals(Layer.Grant, granted.layer)
        assertEquals("Unlocked · 1:00 left", describeDecision(granted, now))

        // Domain-only target in a hard category.
        val hard = targetDecision(policy(BlockState(basePaused = true)), target("pornhub"), now)
        assertTrue(hard.blocked)
        assertEquals(Layer.Hard, hard.layer)

        assertEquals("Not blocked", describeDecision(targetDecision(policy(BlockState(basePaused = true)), target("reddit"), now), now))
    }

    @Test fun `grant options put blocked targets first`() {
        val sorted = sortGrantOptions(
            listOf(
                GrantOption(target("reddit"), Decision.Allowed),
                GrantOption(target("youtube"), Decision(true, Layer.Base)),
                GrantOption(target("instagram"), Decision(true, Layer.Base)),
            ),
        )
        assertEquals(listOf("instagram", "youtube", "reddit"), sorted.map { it.target.id })
    }

    @Test fun `profile run state covers base pause and timers`() {
        assertEquals(ProfileRunState(true, null), profileRunState("base", config, BlockState(), now))
        assertEquals(ProfileRunState(false, null), profileRunState("base", config, BlockState(basePaused = true), now))
        assertEquals(ProfileRunState(true, now + 2_400_000), profileRunState("focus", config, SampleData.state, now))
        assertEquals(ProfileRunState(false, null), profileRunState("focus", config, SampleData.state, now + 2_400_000))
    }

    @Test fun `new profile ids are slugs and avoid collisions`() {
        val id = newProfileId("Deep Work!", emptySet())
        assertTrue(id, id.matches(Regex("deep-work-[0-9a-f]{4}")))
        assertNotEquals(id, newProfileId("Deep Work!", setOf(id)))
        assertTrue(newProfileId("!!!", emptySet()).startsWith("profile-"))
    }

    @Test fun `navigator goes back through pushed screens, then to home`() {
        val nav = Navigator()
        nav.selectTab(Screen.Tags)
        nav.push(Screen.ScanTag(ScanPurpose.NewBinding))
        nav.replaceTop(Screen.TagEditor("abc", isNew = true))
        assertEquals(listOf(Screen.Tags, Screen.TagEditor("abc", true)), nav.stack.toList())
        assertTrue(nav.back())
        assertEquals(Screen.Tags, nav.top)
        assertTrue(nav.back())
        assertEquals(Screen.Home, nav.top)
        assertFalse(nav.back())
    }
}
