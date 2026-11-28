package io.github.arboeckh.geryon.lists

import io.github.arboeckh.geryon.block.CatalogIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random

class DomainSetTest {

    private fun parse(text: String) = DomainSet.parse(text.reader().buffered())

    @Test fun `parses HaGeZi onlydomains format`() {
        val set = parse(
            """
            # Title: HaGeZi's Encrypted DNS Bypass
            # Number of entries: 3
            #
            dns.example.net
            1.tv
            xxx
            """.trimIndent(),
        )
        assertEquals(setOf("1.tv", "dns.example.net", "xxx"), set.toSet())
    }

    @Test fun `parses hosts, wildcard and adblock lines`() {
        assertEquals("ads.example.com", DomainSet.parseLine("0.0.0.0 ads.example.com"))
        assertEquals("ads.example.com", DomainSet.parseLine("127.0.0.1\tAds.Example.com  # tracker"))
        assertEquals("ads.example.com", DomainSet.parseLine(":: ads.example.com"))
        assertEquals("ads.example.com", DomainSet.parseLine("*.ads.example.com"))
        assertEquals("ads.example.com", DomainSet.parseLine("||ads.example.com^"))
        assertEquals("ads.example.com", DomainSet.parseLine("||ads.example.com^\$important"))
        assertEquals("ads.example.com", DomainSet.parseLine("ads.example.com."))
        assertEquals("xn--bcher-kva.example", DomainSet.parseLine("xn--bcher-kva.example"))
    }

    @Test fun `skips comments, exceptions, IPs, host noise and invalid names`() {
        for (line in listOf(
            "", "   ", "# comment", "! adblock comment", "@@||allowed.com^",
            "127.0.0.1 localhost", "0.0.0.0 0.0.0.0", "::1 ip6-localhost", "1.2.3.4",
            "bücher.example", "<html>", "has space.com extra", "a..b.com", "a/b.com",
        )) {
            assertNull("'$line'", DomainSet.parseLine(line))
        }
    }

    @Test fun `contains is exact and handles edges`() {
        val set = DomainSet.of("b.com", "a.com", "c.com", "a.com", "zz.example")
        assertEquals(4, set.size)
        assertEquals(listOf("a.com", "b.com", "c.com", "zz.example"), set.toList()) // sorted, deduplicated
        for (d in listOf("a.com", "b.com", "c.com", "zz.example")) assertTrue(d, d in set)
        for (d in listOf("", "a", "a.co", "a.comm", "0.com", "zzz.example", "www.a.com", "ä.com")) assertFalse(d, d in set)
        assertFalse("a.com" in DomainSet.EMPTY)
        assertEquals(0, DomainSet.EMPTY.size)
    }

    @Test fun `matches a HashSet on random data`() {
        val rnd = Random(42)
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789-."
        fun name() = buildString { repeat(rnd.nextInt(1, 20)) { append(alphabet[rnd.nextInt(alphabet.length - 2)]) }; append(".com") }
        val names = List(20_000) { name() }
        val expected = names.toHashSet()
        val set = DomainSet.Builder().apply { names.forEach(this::add) }.build()
        assertEquals(expected.size, set.size)
        assertEquals(expected, set.toHashSet())
        assertEquals(expected.sorted(), set.toList())
        repeat(20_000) { val n = name(); assertEquals(n, n in expected, n in set) }
    }

    @Test fun `round trips through the packed file format`() {
        val set = DomainSet.of("one.example", "two.example", "three.example")
        val bytes = ByteArrayOutputStream().also { set.write(it) }.toByteArray()
        val back = DomainSet.read(ByteArrayInputStream(bytes))
        assertEquals(set.toList(), back.toList())
        assertTrue("two.example" in back)

        val empty = ByteArrayOutputStream().also { DomainSet.EMPTY.write(it) }.toByteArray()
        assertEquals(0, DomainSet.read(ByteArrayInputStream(empty)).size)
    }

    @Test(expected = IOException::class)
    fun `rejects files that are not domain sets`() {
        DomainSet.read(ByteArrayInputStream("hello world, not packed".toByteArray()))
    }

    @Test(expected = IOException::class)
    fun `rejects truncated files`() {
        val bytes = ByteArrayOutputStream().also { DomainSet.of("a.com", "b.com").write(it) }.toByteArray()
        DomainSet.read(ByteArrayInputStream(bytes.copyOf(bytes.size - 2)))
    }

    @Test fun `works as an external category list in the catalogue index`() {
        val index = io.github.arboeckh.geryon.block.CatalogIndex(
            io.github.arboeckh.geryon.block.Catalog(
                categories = listOf(io.github.arboeckh.geryon.block.Category("adult", "Adult", domains = setOf("own.example"), hard = true)),
            ),
            mapOf("adult" to DomainSet.of("listed.example", "xxx")),
        )
        fun cats(d: String) = index.match(io.github.arboeckh.geryon.block.Subject.Domain(d)).categories
        assertEquals(setOf("adult"), cats("cdn.listed.example"))
        assertEquals(setOf("adult"), cats("anything.xxx"))
        assertEquals(setOf("adult"), cats("own.example"))
        assertEquals(emptySet<String>(), cats("notlisted.example"))
        assertEquals(listOf("a.b.c", "b.c", "c"), CatalogIndex.suffixesOf("a.b.c"))
    }
}
