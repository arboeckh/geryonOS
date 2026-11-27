package io.github.arboeckh.geryon.enforce.dns

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Hand-built DNS messages for tests. */
object DnsTestUtil {
    fun name(out: DataOutputStream, name: String) {
        for (label in name.split('.').filter { it.isNotEmpty() }) {
            out.writeByte(label.length)
            out.write(label.toByteArray())
        }
        out.writeByte(0)
    }

    /** A recursive query for [name] with an EDNS OPT record, like Android's resolver sends. */
    fun query(name: String, type: Int, id: Int = 0x1234, edns: Boolean = true): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeShort(id)
            writeShort(0x0100) // RD
            writeShort(1); writeShort(0); writeShort(0); writeShort(if (edns) 1 else 0)
            name(this, name)
            writeShort(type); writeShort(1)
            if (edns) {
                writeByte(0) // root
                writeShort(DnsMessage.TYPE_OPT); writeShort(1232); writeInt(0); writeShort(0)
            }
        }
        return bytes.toByteArray()
    }

    /**
     * An upstream-style response: CNAME + A in the answer section (compressed names),
     * an SOA-ish record in authority, and OPT in additional.
     */
    fun response(id: Int, ttl: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeShort(id)
            writeShort(0x8180)
            writeShort(1); writeShort(2); writeShort(1); writeShort(1)
            name(this, "www.example.com")
            writeShort(DnsMessage.TYPE_A); writeShort(1)
            // CNAME www.example.com -> edge.example.com
            writeShort(0xc00c); writeShort(5); writeShort(1); writeInt(ttl)
            val target = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeByte(4); write("edge".toByteArray()); writeShort(0xc010) } }.toByteArray()
            writeShort(target.size); write(target)
            // A edge.example.com (points at the CNAME rdata: 12 + 17 + 4 question, + 10 + 2 header)
            writeShort(0xc000 or 45); writeShort(DnsMessage.TYPE_A); writeShort(1); writeInt(ttl)
            writeShort(4); write(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
            // authority: TXT-ish opaque record at example.com
            writeShort(0xc010); writeShort(16); writeShort(1); writeInt(ttl)
            writeShort(3); write(byteArrayOf(2, 'h'.code.toByte(), 'i'.code.toByte()))
            // additional: OPT with DO flag in the TTL field
            writeByte(0); writeShort(DnsMessage.TYPE_OPT); writeShort(1232); writeInt(0x00008000); writeShort(0)
        }
        return bytes.toByteArray()
    }
}
