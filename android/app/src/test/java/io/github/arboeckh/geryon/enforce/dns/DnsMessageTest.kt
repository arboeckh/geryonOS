package io.github.arboeckh.geryon.enforce.dns

import io.github.arboeckh.geryon.enforce.dns.DnsTestUtil.query
import io.github.arboeckh.geryon.enforce.dns.DnsTestUtil.response
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class DnsMessageTest {

    private fun u16(b: ByteArray, off: Int) = IpUdp.u16(b, off)
    private fun u32(b: ByteArray, off: Int) = ByteBuffer.wrap(b, off, 4).int

    @Test fun `parses a query and lowercases the name`() {
        val q = DnsMessage.parseQuery(query("WWW.Example.COM", DnsMessage.TYPE_AAAA, id = 0xbeef))!!
        assertEquals("www.example.com", q.name)
        assertEquals(DnsMessage.TYPE_AAAA, q.type)
        assertEquals(DnsMessage.CLASS_IN, q.qclass)
        assertEquals(0xbeef, q.id)
        assertEquals(17 + 4, q.questionBytes.size)
    }

    @Test fun `root query has an empty name`() {
        assertEquals("", DnsMessage.parseQuery(query(".", 2))!!.name)
    }

    @Test fun `rejects responses, truncated and multi-question messages`() {
        val r = query("example.com", 1).also { it[2] = (it[2].toInt() or 0x80).toByte() }
        assertNull(DnsMessage.parseQuery(r))
        val full = query("example.com", 1, edns = false)
        assertNull(DnsMessage.parseQuery(full.copyOf(full.size - 3)))
        assertNull(DnsMessage.parseQuery(full.copyOf(5)))
        val two = full.copyOf().also { it[5] = 2 }
        assertNull(DnsMessage.parseQuery(two))
    }

    @Test fun `rejects name pointer loops`() {
        val msg = ByteArray(12 + 2 + 4)
        msg[5] = 1
        msg[12] = 0xc0.toByte(); msg[13] = 12 // points at itself
        assertNull(DnsMessage.parseQuery(msg))
    }

    @Test fun `blocked A answer is 0_0_0_0 with the query's id and question`() {
        val raw = query("ads.example.com", DnsMessage.TYPE_A, id = 0x4242)
        val q = DnsMessage.parseQuery(raw)!!
        val r = DnsMessage.blockedResponse(q, ttl = 5)

        assertEquals(0x4242, u16(r, 0))
        val flags = u16(r, 2)
        assertTrue("QR set", flags and 0x8000 != 0)
        assertTrue("RD copied", flags and 0x0100 != 0)
        assertTrue("RA set", flags and 0x0080 != 0)
        assertEquals(DnsMessage.RCODE_NOERROR, flags and 0xf)
        assertEquals(1, u16(r, 4)); assertEquals(1, u16(r, 6)); assertEquals(0, u16(r, 8)); assertEquals(0, u16(r, 10))
        assertArrayEquals(q.questionBytes, r.copyOfRange(12, 12 + q.questionBytes.size))

        var p = 12 + q.questionBytes.size
        assertEquals(0xc00c, u16(r, p)); p += 2
        assertEquals(DnsMessage.TYPE_A, u16(r, p)); p += 2
        assertEquals(1, u16(r, p)); p += 2
        assertEquals(5, u32(r, p)); p += 4
        assertEquals(4, u16(r, p)); p += 2
        assertArrayEquals(ByteArray(4), r.copyOfRange(p, p + 4))
        assertEquals(r.size, p + 4)
    }

    @Test fun `blocked AAAA answer is all zeros`() {
        val q = DnsMessage.parseQuery(query("ads.example.com", DnsMessage.TYPE_AAAA))!!
        val r = DnsMessage.blockedResponse(q, ttl = 300)
        val rdlenAt = r.size - 16 - 2
        assertEquals(16, u16(r, rdlenAt))
        assertEquals(300, u32(r, rdlenAt - 4))
        assertArrayEquals(ByteArray(16), r.copyOfRange(r.size - 16, r.size))
    }

    @Test fun `other blocked types get NXDOMAIN with no answers`() {
        val q = DnsMessage.parseQuery(query("ads.example.com", 65))!! // HTTPS
        val r = DnsMessage.blockedResponse(q, ttl = 5)
        assertEquals(DnsMessage.RCODE_NXDOMAIN, u16(r, 2) and 0xf)
        assertEquals(0, u16(r, 6))
        assertEquals(12 + q.questionBytes.size, r.size)
    }

    @Test fun `servfail response`() {
        val q = DnsMessage.parseQuery(query("example.com", 1))!!
        assertEquals(DnsMessage.RCODE_SERVFAIL, u16(DnsMessage.response(q, DnsMessage.RCODE_SERVFAIL), 2) and 0xf)
    }

    @Test fun `format error echoes the id`() {
        val junk = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x05)
        val r = DnsMessage.formatError(junk)!!
        assertEquals(0x1234, u16(r, 0))
        assertEquals(DnsMessage.RCODE_FORMERR, u16(r, 2) and 0xf)
        assertNull(DnsMessage.formatError(byteArrayOf(1, 2)))
    }

    @Test fun `caps answer and authority TTLs but leaves OPT alone`() {
        val r = response(id = 7, ttl = 3600)
        assertTrue(DnsMessage.capTtls(r, 10))
        val ttls = readTtls(r)
        assertEquals(listOf(10, 10, 10), ttls.dropLast(1))
        assertEquals(0x8000, ttls.last()) // OPT's flags untouched
    }

    @Test fun `does not raise TTLs that are already low`() {
        val r = response(id = 7, ttl = 3)
        assertTrue(DnsMessage.capTtls(r, 10))
        assertEquals(listOf(3, 3, 3), readTtls(r).dropLast(1))
    }

    @Test fun `treats TTLs as unsigned`() {
        val r = response(id = 7, ttl = -1) // 0xffffffff
        assertTrue(DnsMessage.capTtls(r, 10))
        assertEquals(listOf(10, 10, 10), readTtls(r).dropLast(1))
    }

    @Test fun `cap fails cleanly on truncated responses`() {
        val r = response(id = 7, ttl = 3600)
        assertEquals(false, DnsMessage.capTtls(r.copyOf(40), 10))
    }

    @Test fun `reads compressed names`() {
        val r = response(id = 7, ttl = 60)
        val out = StringBuilder()
        // The A record's owner name points into the CNAME's rdata.
        assertNotNull(DnsMessage.readName(r, 45, out))
        assertEquals("edge.example.com", out.toString())
    }

    /** TTL of every record after the question, in order. */
    private fun readTtls(r: ByteArray): List<Int> {
        var p = 12
        p = skip(r, p) + 4
        val total = u16(r, 6) + u16(r, 8) + u16(r, 10)
        return List(total) {
            p = skip(r, p)
            val ttl = u32(r, p + 4)
            p += 10 + u16(r, p + 8)
            ttl
        }
    }

    private fun skip(r: ByteArray, start: Int): Int {
        var p = start
        while (true) {
            val len = r[p].toInt() and 0xff
            if (len == 0) return p + 1
            if (len and 0xc0 == 0xc0) return p + 2
            p += 1 + len
        }
    }
}
