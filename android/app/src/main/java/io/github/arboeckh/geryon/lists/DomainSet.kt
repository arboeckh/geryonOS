package io.github.arboeckh.geryon.lists

import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * A read-only set of domains packed into one byte array, sorted for binary search.
 * About (name length + 4) bytes per entry and two objects in total, so a 700k-entry
 * list costs ~15 MB instead of the ~70 MB a HashSet<String> would.
 *
 * Only lowercase ASCII names are stored (IDNs appear in lists as punycode).
 */
class DomainSet private constructor(
    private val data: ByteArray,
    /** Start of entry i is offsets[i]; offsets[size] is data.size. */
    private val offsets: IntArray,
) : AbstractSet<String>() {

    override val size: Int get() = offsets.size - 1

    override fun contains(element: String): Boolean {
        var lo = 0
        var hi = size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compareEntry(mid, element)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return true
            }
        }
        return false
    }

    override fun iterator(): Iterator<String> = object : Iterator<String> {
        var i = 0
        override fun hasNext() = i < size
        override fun next(): String {
            if (!hasNext()) throw NoSuchElementException()
            val s = String(data, offsets[i], offsets[i + 1] - offsets[i], Charsets.US_ASCII)
            i++
            return s
        }
    }

    /** Entry [i] compared to [key], byte by byte, as unsigned values. */
    private fun compareEntry(i: Int, key: String): Int {
        val start = offsets[i]
        val len = offsets[i + 1] - start
        val n = minOf(len, key.length)
        for (k in 0 until n) {
            val d = (data[start + k].toInt() and 0xff) - key[k].code
            if (d != 0) return d
        }
        return len - key.length
    }

    /** Writes the packed form; [read] loads it back without re-sorting. */
    fun write(out: OutputStream) {
        val d = DataOutputStream(out)
        d.writeInt(MAGIC)
        d.writeInt(size)
        d.writeInt(data.size)
        val bb = ByteBuffer.allocate(offsets.size * 4)
        bb.asIntBuffer().put(offsets)
        d.write(bb.array())
        d.write(data)
        d.flush()
    }

    companion object {
        private const val MAGIC = 0x47445331 // "GDS1"
        private const val MAX_NAME = 253

        val EMPTY = DomainSet(ByteArray(0), IntArray(1))

        fun read(input: InputStream): DomainSet {
            val d = DataInputStream(input)
            if (d.readInt() != MAGIC) throw IOException("Not a domain set")
            val count = d.readInt()
            val dataLen = d.readInt()
            if (count < 0 || dataLen < 0) throw IOException("Corrupt domain set")
            val raw = ByteArray((count + 1) * 4)
            d.readFully(raw)
            val offsets = IntArray(count + 1)
            ByteBuffer.wrap(raw).asIntBuffer().get(offsets)
            val data = ByteArray(dataLen)
            d.readFully(data)
            if (offsets[0] != 0 || offsets[count] != dataLen) throw IOException("Corrupt domain set")
            return DomainSet(data, offsets)
        }

        /**
         * Parses a blocklist in any common text format, one entry per line:
         * plain domains, wildcards (`*.example.com`), hosts files (`0.0.0.0 example.com`)
         * and adblock rules (`||example.com^`). Comments, exceptions and invalid names are skipped.
         */
        fun parse(reader: BufferedReader): DomainSet {
            val b = Builder()
            while (true) {
                val line = reader.readLine() ?: break
                parseLine(line)?.let(b::add)
            }
            return b.build()
        }

        fun of(vararg domains: String): DomainSet = Builder().apply { domains.forEach { parseLine(it)?.let(this::add) } }.build()

        /** The domain on one list line, normalised, or null if there is none. */
        internal fun parseLine(raw: String): String? {
            var s = raw.trim()
            if (s.isEmpty() || s[0] == '#' || s[0] == '!' || s.startsWith("@@")) return null
            s = s.substringBefore('#').trim()

            if (s.startsWith("||")) {
                s = s.substring(2).substringBefore('^').substringBefore('$')
            } else if (s.indexOfAny(charArrayOf(' ', '\t')) > 0) {
                // hosts format: "<ip> <name> [aliases]"
                val parts = s.split(' ', '\t').filter { it.isNotEmpty() }
                if (parts.size < 2 || !looksLikeIp(parts[0])) return null
                s = parts[1]
            }
            s = s.removePrefix("*.").removePrefix(".").trimEnd('.').lowercase()

            if (s.isEmpty() || s.length > MAX_NAME || s in HOSTS_NOISE || looksLikeIp(s)) return null
            for (c in s) if (!(c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_')) return null
            if (s.startsWith('.') || s.contains("..")) return null
            return s
        }

        private val HOSTS_NOISE = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback")

        private fun looksLikeIp(s: String) = s.all { it.isDigit() || it == '.' } || s.contains(':')
    }

    /**
     * Collects names into growable primitive arrays (no String per entry is kept), then
     * sorts an index over them and packs the deduplicated result.
     */
    class Builder {
        private var data = ByteArray(1 shl 16)
        private var used = 0
        private var starts = IntArray(1 shl 12)
        private var count = 0

        /** [domain] must already be normalised: lowercase ASCII. */
        fun add(domain: String) {
            if (used + domain.length > data.size) data = data.copyOf(maxOf(data.size * 2, used + domain.length))
            if (count + 1 >= starts.size) starts = starts.copyOf(starts.size * 2)
            starts[count++] = used
            for (c in domain) data[used++] = c.code.toByte()
            starts[count] = used
        }

        fun build(): DomainSet {
            if (count == 0) return EMPTY
            val order = IntArray(count) { it }
            sortIndices(order)

            val packed = ByteArray(used)
            val offsets = IntArray(count + 1)
            var n = 0
            var pos = 0
            var prev = -1
            for (i in order) {
                if (prev >= 0 && compare(prev, i) == 0) continue
                val len = starts[i + 1] - starts[i]
                System.arraycopy(data, starts[i], packed, pos, len)
                offsets[n++] = pos
                pos += len
                prev = i
            }
            offsets[n] = pos
            return DomainSet(
                if (pos == packed.size) packed else packed.copyOf(pos),
                if (n == count) offsets else offsets.copyOf(n + 1),
            )
        }

        private fun compare(a: Int, b: Int): Int {
            val sa = starts[a]
            val la = starts[a + 1] - sa
            val sb = starts[b]
            val lb = starts[b + 1] - sb
            val n = minOf(la, lb)
            for (k in 0 until n) {
                val d = (data[sa + k].toInt() and 0xff) - (data[sb + k].toInt() and 0xff)
                if (d != 0) return d
            }
            return la - lb
        }

        /** Bottom-up merge sort on primitive ints (no boxing), stable, O(n log n). */
        private fun sortIndices(a: IntArray) {
            var src = a
            var dst = IntArray(a.size)
            var width = 1
            while (width < a.size) {
                var lo = 0
                while (lo < a.size) {
                    val mid = minOf(lo + width, a.size)
                    val hi = minOf(lo + 2 * width, a.size)
                    var i = lo
                    var j = mid
                    var k = lo
                    while (i < mid && j < hi) dst[k++] = if (compare(src[i], src[j]) <= 0) src[i++] else src[j++]
                    while (i < mid) dst[k++] = src[i++]
                    while (j < hi) dst[k++] = src[j++]
                    lo = hi
                }
                val t = src; src = dst; dst = t
                width *= 2
            }
            if (src !== a) System.arraycopy(src, 0, a, 0, a.size)
        }
    }
}
