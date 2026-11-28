package io.github.arboeckh.geryon.enforce.dns

import io.github.arboeckh.geryon.block.Actions
import io.github.arboeckh.geryon.block.BlockConfig
import io.github.arboeckh.geryon.block.BlockState
import io.github.arboeckh.geryon.block.Catalog
import io.github.arboeckh.geryon.block.CatalogIndex
import io.github.arboeckh.geryon.block.Category
import io.github.arboeckh.geryon.block.Effect
import io.github.arboeckh.geryon.block.Policy
import io.github.arboeckh.geryon.block.Profile
import io.github.arboeckh.geryon.block.Rule
import io.github.arboeckh.geryon.block.Selector
import io.github.arboeckh.geryon.block.Target
import io.github.arboeckh.geryon.enforce.dns.DnsTestUtil.query
import io.github.arboeckh.geryon.lists.DomainSet
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class DnsFilterTest {

    private val catalog = Catalog(
        targets = listOf(
            Target("youtube", "YouTube", domains = setOf("youtube.com", "googlevideo.com")),
            Target("wiki", "Wikipedia", domains = setOf("wikipedia.org")),
        ),
        categories = listOf(
            Category("video", "Video", targets = setOf("youtube")),
            Category("reference", "Reference", targets = setOf("wiki")),
            Category("gambling", "Gambling", domains = setOf("bet365.com"), hard = true),
        ),
    )
    private val index = CatalogIndex(catalog, mapOf("gambling" to DomainSet.of("casino.example", "slots.test")))
    private val config = BlockConfig(
        baseProfileId = "base",
        profiles = listOf(Profile("base", "Base", listOf(Rule(Effect.Block, Selector.OfCategory("video"))))),
    )
    private val now = 1_000_000L

    private fun policy(state: BlockState = BlockState()) = Policy(index, config, state)

    private fun answerTtl(r: ByteArray) = ByteBuffer.wrap(r, r.size - 4 - 2 - 4, 4).int

    @Test fun `profile-blocked domain gets 0_0_0_0 with a short TTL`() {
        val v = DnsFilter.handle(query("www.youtube.com", DnsMessage.TYPE_A), policy(), now) as DnsVerdict.Reply
        assertArrayEquals(ByteArray(4), v.message.copyOfRange(v.message.size - 4, v.message.size))
        assertEquals(DnsFilter.BLOCK_TTL, answerTtl(v.message))
    }

    @Test fun `the phone's own Private DNS server is never blocked, even when a hard list names it`() {
        val host = "abc123.casino.example"
        val v = DnsFilter.handle(query(host, DnsMessage.TYPE_A), policy(), now, systemHost = host)
        assertTrue(v is DnsVerdict.Forward)
        assertNull((v as DnsVerdict.Forward).maxTtl)
        assertTrue(DnsFilter.handle(query("x.$host", DnsMessage.TYPE_A), policy(), now, systemHost = "$host.") is DnsVerdict.Forward)
        // Its parent domain and siblings stay blocked.
        assertTrue(DnsFilter.handle(query("casino.example", DnsMessage.TYPE_A), policy(), now, systemHost = host) is DnsVerdict.Reply)
        assertTrue(DnsFilter.handle(query("other.casino.example", DnsMessage.TYPE_A), policy(), now, systemHost = host) is DnsVerdict.Reply)
    }

    @Test fun `hard-blocked domain from an external list gets a long TTL`() {
        val v = DnsFilter.handle(query("www.casino.example", DnsMessage.TYPE_A), policy(), now) as DnsVerdict.Reply
        assertEquals(DnsFilter.HARD_BLOCK_TTL, answerTtl(v.message))
        assertTrue(DnsFilter.handle(query("slots.test", DnsMessage.TYPE_A), policy(), now) is DnsVerdict.Reply)
        assertTrue(DnsFilter.handle(query("bet365.com", DnsMessage.TYPE_A), policy(), now) is DnsVerdict.Reply)
    }

    @Test fun `unrelated domains are forwarded with upstream TTLs`() {
        val v = DnsFilter.handle(query("example.org", DnsMessage.TYPE_A), policy(), now) as DnsVerdict.Forward
        assertNull(v.maxTtl)
        assertEquals("example.org", v.query.name)
    }

    @Test fun `allowed catalogue domains are forwarded with capped TTLs`() {
        val v = DnsFilter.handle(query("en.wikipedia.org", DnsMessage.TYPE_A), policy(), now) as DnsVerdict.Forward
        assertEquals(DnsFilter.POLICY_TTL, v.maxTtl)
    }

    @Test fun `granted domains are forwarded with TTLs that end with the grant`() {
        val state = Actions.grant(BlockState(), index, "youtube", 1, now).getOrThrow()
        val early = DnsFilter.handle(query("youtube.com", DnsMessage.TYPE_A), policy(state), now) as DnsVerdict.Forward
        assertEquals(DnsFilter.POLICY_TTL, early.maxTtl)
        val late = DnsFilter.handle(query("youtube.com", DnsMessage.TYPE_A), policy(state), now + 57_000) as DnsVerdict.Forward
        assertEquals(3, late.maxTtl)
        // Once it runs out, the next query is blocked again.
        assertTrue(DnsFilter.handle(query("youtube.com", DnsMessage.TYPE_A), policy(state), now + 60_000) is DnsVerdict.Reply)
    }

    @Test fun `paused base forwards with a capped TTL`() {
        val v = DnsFilter.handle(query("youtube.com", DnsMessage.TYPE_A), policy(BlockState(basePaused = true)), now) as DnsVerdict.Forward
        assertEquals(DnsFilter.POLICY_TTL, v.maxTtl)
    }

    @Test fun `unreadable queries get FORMERR, responses are dropped`() {
        val junk = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01)
        val v = DnsFilter.handle(junk, policy(), now) as DnsVerdict.Reply
        assertEquals(DnsMessage.RCODE_FORMERR, v.message[3].toInt() and 0xf)
        val response = query("example.com", 1).also { it[2] = 0x81.toByte() }
        assertSame(DnsVerdict.Drop, DnsFilter.handle(response, policy(), now))
    }
}
