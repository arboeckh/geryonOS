package io.github.arboeckh.geryon.enforce.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class IpUdpTest {

    private val payload = DnsTestUtil.query("example.com", 1)

    private fun ip(s: String): ByteArray = InetAddress.getByName(s).address

    /** One's-complement sum over [len] bytes; 0xffff when a checksum inside is correct. */
    private fun sum(vararg parts: ByteArray): Int {
        var s = 0L
        for (b in parts) {
            var i = 0
            while (i + 1 < b.size) { s += IpUdp.u16(b, i); i += 2 }
            if (i < b.size) s += (b[i].toInt() and 0xff) shl 8
        }
        while (s ushr 16 != 0L) s = (s and 0xffff) + (s ushr 16)
        return s.toInt()
    }

    private fun pseudo(src: ByteArray, dst: ByteArray, udpLen: Int) =
        src + dst + byteArrayOf(0, 17, (udpLen ushr 8).toByte(), udpLen.toByte())

    @Test fun `IPv4 round trip with valid checksums`() {
        val p = UdpPacket(ip("10.111.222.1"), 40000, ip("10.111.222.2"), 53, payload)
        val bytes = IpUdp.build(p)
        assertEquals(20 + 8 + payload.size, bytes.size)
        assertEquals(0xffff, sum(bytes.copyOfRange(0, 20)))
        val udp = bytes.copyOfRange(20, bytes.size)
        assertEquals(0xffff, sum(pseudo(p.srcAddr, p.dstAddr, udp.size), udp))

        val back = IpUdp.parse(bytes)!!
        assertArrayEquals(p.srcAddr, back.srcAddr)
        assertArrayEquals(p.dstAddr, back.dstAddr)
        assertEquals(40000, back.srcPort)
        assertEquals(53, back.dstPort)
        assertArrayEquals(payload, back.payload)
    }

    @Test fun `IPv6 round trip with valid UDP checksum`() {
        val p = UdpPacket(ip("fd00:6765:7279:6f6e::1"), 51515, ip("fd00:6765:7279:6f6e::2"), 53, payload)
        val bytes = IpUdp.build(p)
        assertEquals(40 + 8 + payload.size, bytes.size)
        val udp = bytes.copyOfRange(40, bytes.size)
        assertEquals(0xffff, sum(pseudo(p.srcAddr, p.dstAddr, udp.size), udp))

        val back = IpUdp.parse(bytes)!!
        assertEquals(true, back.isIpv6)
        assertArrayEquals(p.srcAddr, back.srcAddr)
        assertEquals(51515, back.srcPort)
        assertArrayEquals(payload, back.payload)
    }

    @Test fun `reply swaps addresses and ports`() {
        val p = UdpPacket(ip("10.0.0.1"), 1234, ip("10.0.0.2"), 53, payload)
        val r = p.reply(byteArrayOf(1, 2, 3))
        assertArrayEquals(p.dstAddr, r.srcAddr)
        assertEquals(53, r.srcPort)
        assertArrayEquals(p.srcAddr, r.dstAddr)
        assertEquals(1234, r.dstPort)
    }

    @Test fun `parse respects the buffer length and ignores trailing bytes`() {
        val bytes = IpUdp.build(UdpPacket(ip("10.0.0.1"), 1234, ip("10.0.0.2"), 53, payload))
        val buf = bytes.copyOf(bytes.size + 100)
        assertArrayEquals(payload, IpUdp.parse(buf, bytes.size)!!.payload)
        assertNull(IpUdp.parse(bytes, bytes.size - 1))
    }

    @Test fun `ignores TCP, fragments and garbage`() {
        val v4 = IpUdp.build(UdpPacket(ip("10.0.0.1"), 1234, ip("10.0.0.2"), 53, payload))
        assertNull(IpUdp.parse(v4.copyOf().also { it[9] = 6 }))
        assertNull(IpUdp.parse(v4.copyOf().also { it[6] = 0x20 })) // more fragments
        assertNull(IpUdp.parse(v4.copyOf().also { it[7] = 1 })) // fragment offset
        val v6 = IpUdp.build(UdpPacket(ip("::1"), 1234, ip("::2"), 53, payload))
        assertNull(IpUdp.parse(v6.copyOf().also { it[6] = 6 }))
        assertNull(IpUdp.parse(byteArrayOf(0x10, 0, 0)))
        assertNull(IpUdp.parse(ByteArray(0)))
    }
}
