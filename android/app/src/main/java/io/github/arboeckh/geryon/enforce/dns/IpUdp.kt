package io.github.arboeckh.geryon.enforce.dns

import java.nio.ByteBuffer

/**
 * A UDP datagram as read from (or written to) the tunnel: addresses, ports and payload.
 * Addresses are raw bytes, 4 for IPv4 and 16 for IPv6.
 */
class UdpPacket(
    val srcAddr: ByteArray,
    val srcPort: Int,
    val dstAddr: ByteArray,
    val dstPort: Int,
    val payload: ByteArray,
) {
    val isIpv6 get() = srcAddr.size == 16

    /** A reply to this datagram: same flow with source and destination swapped. */
    fun reply(payload: ByteArray) = UdpPacket(dstAddr, dstPort, srcAddr, srcPort, payload)
}

/** Reads and writes the IPv4/IPv6 + UDP framing of tunnel packets. No options, no fragments. */
object IpUdp {
    private const val PROTO_UDP = 17
    private const val IPV4_HEADER = 20
    private const val IPV6_HEADER = 40
    private const val UDP_HEADER = 8

    /** Parses one tunnel packet. Returns null for anything that isn't a whole UDP datagram. */
    fun parse(buf: ByteArray, len: Int = buf.size): UdpPacket? {
        if (len < 1) return null
        return when (buf[0].toInt() ushr 4) {
            4 -> parseV4(buf, len)
            6 -> parseV6(buf, len)
            else -> null
        }
    }

    private fun parseV4(b: ByteArray, len: Int): UdpPacket? {
        if (len < IPV4_HEADER) return null
        val ihl = (b[0].toInt() and 0x0f) * 4
        val total = u16(b, 2)
        if (ihl < IPV4_HEADER || total > len || total < ihl + UDP_HEADER) return null
        if (b[9].toInt() and 0xff != PROTO_UDP) return null
        // More-fragments flag or a fragment offset: not a whole datagram.
        if (u16(b, 6) and 0x3fff != 0) return null
        return udp(b, ihl, total, b.copyOfRange(12, 16), b.copyOfRange(16, 20))
    }

    private fun parseV6(b: ByteArray, len: Int): UdpPacket? {
        if (len < IPV6_HEADER) return null
        // Extension headers are not followed; resolvers never send them for DNS.
        if (b[6].toInt() and 0xff != PROTO_UDP) return null
        val end = IPV6_HEADER + u16(b, 4)
        if (end > len || end < IPV6_HEADER + UDP_HEADER) return null
        return udp(b, IPV6_HEADER, end, b.copyOfRange(8, 24), b.copyOfRange(24, 40))
    }

    private fun udp(b: ByteArray, off: Int, end: Int, src: ByteArray, dst: ByteArray): UdpPacket? {
        val udpLen = u16(b, off + 4)
        if (udpLen < UDP_HEADER || off + udpLen > end) return null
        return UdpPacket(src, u16(b, off), dst, u16(b, off + 2), b.copyOfRange(off + UDP_HEADER, off + udpLen))
    }

    /** Serialises [p] as an IP packet ready to write to the tunnel. */
    fun build(p: UdpPacket): ByteArray {
        require(p.srcAddr.size == p.dstAddr.size && (p.srcAddr.size == 4 || p.srcAddr.size == 16))
        val udpLen = UDP_HEADER + p.payload.size
        val ipHeader = if (p.isIpv6) IPV6_HEADER else IPV4_HEADER
        val out = ByteBuffer.allocate(ipHeader + udpLen)
        if (p.isIpv6) {
            out.putInt(0x60000000).putShort(udpLen.toShort()).put(PROTO_UDP.toByte()).put(64)
            out.put(p.srcAddr).put(p.dstAddr)
        } else {
            out.put(0x45).put(0).putShort((IPV4_HEADER + udpLen).toShort())
            out.putShort(0).putShort(0x4000) // id, don't fragment
            out.put(64).put(PROTO_UDP.toByte()).putShort(0) // ttl, protocol, checksum (below)
            out.put(p.srcAddr).put(p.dstAddr)
        }
        out.putShort(p.srcPort.toShort()).putShort(p.dstPort.toShort()).putShort(udpLen.toShort()).putShort(0)
        out.put(p.payload)
        val a = out.array()

        if (!p.isIpv6) {
            val c = finish(sum(a, 0, IPV4_HEADER, 0))
            a[10] = (c ushr 8).toByte(); a[11] = c.toByte()
        }
        // UDP checksum over the pseudo-header: addresses, protocol, length.
        var s = sum(p.srcAddr, 0, p.srcAddr.size, 0)
        s = sum(p.dstAddr, 0, p.dstAddr.size, s)
        s += PROTO_UDP + udpLen
        s = sum(a, ipHeader, udpLen, s)
        var c = finish(s)
        if (c == 0) c = 0xffff // 0 means "no checksum" in UDP
        a[ipHeader + 6] = (c ushr 8).toByte(); a[ipHeader + 7] = c.toByte()
        return a
    }

    private fun sum(b: ByteArray, off: Int, len: Int, initial: Long): Long {
        var s = initial
        var i = off
        val end = off + len
        while (i + 1 < end) {
            s += u16(b, i)
            i += 2
        }
        if (i < end) s += (b[i].toInt() and 0xff) shl 8
        return s
    }

    private fun finish(sum: Long): Int {
        var s = sum
        while (s ushr 16 != 0L) s = (s and 0xffff) + (s ushr 16)
        return s.toInt().inv() and 0xffff
    }

    internal fun u16(b: ByteArray, off: Int) = ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)
}
