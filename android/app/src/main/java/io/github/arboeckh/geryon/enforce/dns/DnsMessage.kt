package io.github.arboeckh.geryon.enforce.dns

import java.nio.ByteBuffer

/** The single question of a DNS query, plus the raw bytes needed to answer it. */
class DnsQuery(
    val id: Int,
    val flags: Int,
    /** Lowercase, no trailing dot. */
    val name: String,
    val type: Int,
    val qclass: Int,
    /** The question section exactly as sent (name, type, class), copied into replies. */
    val questionBytes: ByteArray,
)

/** Just enough DNS (RFC 1035) to read queries, answer blocked ones and adjust TTLs. */
object DnsMessage {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_OPT = 41
    const val CLASS_IN = 1

    const val RCODE_NOERROR = 0
    const val RCODE_FORMERR = 1
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3

    private const val HEADER = 12
    private const val FLAG_QR = 0x8000
    private const val FLAG_RD = 0x0100
    private const val FLAG_RA = 0x0080
    private const val OPCODE_MASK = 0x7800

    /** Header id, or -1 if [msg] is too short to have one. */
    fun id(msg: ByteArray) = if (msg.size >= 2) IpUdp.u16(msg, 0) else -1

    /**
     * Parses a standard query with exactly one question.
     * Returns null for responses, other opcodes, or anything malformed.
     */
    fun parseQuery(msg: ByteArray): DnsQuery? {
        if (msg.size < HEADER) return null
        val flags = IpUdp.u16(msg, 2)
        if (flags and FLAG_QR != 0 || flags and OPCODE_MASK != 0) return null
        if (IpUdp.u16(msg, 4) != 1) return null
        val name = StringBuilder()
        val end = readName(msg, HEADER, name) ?: return null
        if (end + 4 > msg.size) return null
        return DnsQuery(
            id = IpUdp.u16(msg, 0),
            flags = flags,
            name = name.toString(),
            type = IpUdp.u16(msg, end),
            qclass = IpUdp.u16(msg, end + 2),
            questionBytes = msg.copyOfRange(HEADER, end + 4),
        )
    }

    /**
     * Answers [q] as blocked: 0.0.0.0 / :: for A / AAAA, NXDOMAIN for anything else.
     * [ttl] is in seconds.
     */
    fun blockedResponse(q: DnsQuery, ttl: Int): ByteArray {
        val address = when {
            q.qclass != CLASS_IN -> null
            q.type == TYPE_A -> ByteArray(4)
            q.type == TYPE_AAAA -> ByteArray(16)
            else -> null
        }
        if (address == null) return response(q, RCODE_NXDOMAIN)

        val out = ByteBuffer.allocate(HEADER + q.questionBytes.size + 12 + address.size)
        header(out, q, RCODE_NOERROR, answers = 1)
        out.put(q.questionBytes)
        out.putShort(0xc00c.toShort()) // pointer to the question's name
        out.putShort(q.type.toShort()).putShort(CLASS_IN.toShort()).putInt(ttl)
        out.putShort(address.size.toShort()).put(address)
        return out.array()
    }

    /** An answerless response with [rcode], e.g. SERVFAIL when upstream is unreachable. */
    fun response(q: DnsQuery, rcode: Int): ByteArray {
        val out = ByteBuffer.allocate(HEADER + q.questionBytes.size)
        header(out, q, rcode, answers = 0)
        out.put(q.questionBytes)
        return out.array()
    }

    /** FORMERR for a query we couldn't read, echoing its id. Null if there isn't even an id. */
    fun formatError(msg: ByteArray): ByteArray? {
        if (msg.size < 4) return null
        val flags = IpUdp.u16(msg, 2)
        if (flags and FLAG_QR != 0) return null
        val out = ByteBuffer.allocate(HEADER)
        out.putShort(IpUdp.u16(msg, 0).toShort())
        out.putShort((FLAG_QR or (flags and (OPCODE_MASK or FLAG_RD)) or FLAG_RA or RCODE_FORMERR).toShort())
        return out.array()
    }

    private fun header(out: ByteBuffer, q: DnsQuery, rcode: Int, answers: Int) {
        out.putShort(q.id.toShort())
        out.putShort((FLAG_QR or (q.flags and (OPCODE_MASK or FLAG_RD)) or FLAG_RA or rcode).toShort())
        out.putShort(1).putShort(answers.toShort()).putShort(0).putShort(0)
    }

    /**
     * Lowers every TTL in the answer and authority sections of [msg] to at most [maxTtl]
     * seconds, in place. EDNS OPT records are left alone (their TTL field holds flags).
     * Returns false if the message couldn't be walked; it is then left partly or not changed.
     */
    fun capTtls(msg: ByteArray, maxTtl: Int): Boolean {
        if (msg.size < HEADER) return false
        val qd = IpUdp.u16(msg, 4)
        val an = IpUdp.u16(msg, 6)
        val ns = IpUdp.u16(msg, 8)
        var pos = HEADER
        repeat(qd) {
            pos = skipName(msg, pos) ?: return false
            pos += 4
        }
        repeat(an + ns) {
            pos = skipName(msg, pos) ?: return false
            if (pos + 10 > msg.size) return false
            val type = IpUdp.u16(msg, pos)
            val ttlAt = pos + 4
            if (type != TYPE_OPT) {
                val ttl = ByteBuffer.wrap(msg, ttlAt, 4).int.toLong() and 0xffffffffL
                if (ttl > maxTtl) ByteBuffer.wrap(msg, ttlAt, 4).putInt(maxTtl)
            }
            pos += 10 + IpUdp.u16(msg, pos + 8)
            if (pos > msg.size) return false
        }
        return true
    }

    /** Reads a possibly compressed name at [start] into [out]. Returns the offset after it. */
    internal fun readName(msg: ByteArray, start: Int, out: StringBuilder): Int? {
        var pos = start
        var end = -1
        var jumps = 0
        while (true) {
            if (pos >= msg.size) return null
            val len = msg[pos].toInt() and 0xff
            when {
                len == 0 -> return if (end >= 0) end else pos + 1
                len and 0xc0 == 0xc0 -> {
                    if (pos + 1 >= msg.size || ++jumps > 16) return null
                    if (end < 0) end = pos + 2
                    pos = ((len and 0x3f) shl 8) or (msg[pos + 1].toInt() and 0xff)
                }
                len and 0xc0 != 0 -> return null
                else -> {
                    if (pos + 1 + len > msg.size) return null
                    if (out.isNotEmpty()) out.append('.')
                    for (i in pos + 1..pos + len) {
                        val c = (msg[i].toInt() and 0xff).toChar()
                        out.append(if (c in 'A'..'Z') c + 32 else c)
                    }
                    if (out.length > 255) return null
                    pos += 1 + len
                }
            }
        }
    }

    private fun skipName(msg: ByteArray, start: Int): Int? {
        var pos = start
        while (true) {
            if (pos >= msg.size) return null
            val len = msg[pos].toInt() and 0xff
            when {
                len == 0 -> return pos + 1
                len and 0xc0 == 0xc0 -> return if (pos + 2 <= msg.size) pos + 2 else null
                len and 0xc0 != 0 -> return null
                else -> pos += 1 + len
            }
        }
    }
}
