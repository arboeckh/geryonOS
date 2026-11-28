package io.github.arboeckh.geryon.enforce.dns

import io.github.arboeckh.geryon.block.Layer
import io.github.arboeckh.geryon.block.Policy
import io.github.arboeckh.geryon.block.Subject

/** What to do with one DNS query read from the tunnel. */
sealed interface DnsVerdict {
    /** Send [message] straight back. */
    class Reply(val message: ByteArray) : DnsVerdict

    /**
     * Pass the query upstream and relay the answer, lowering its TTLs to [maxTtl] seconds
     * if set, so a policy change (grant ending, profile starting) shows up quickly.
     */
    class Forward(val query: DnsQuery, val maxTtl: Int?) : DnsVerdict

    /** Not a query; ignore it. */
    data object Drop : DnsVerdict
}

/**
 * Decides queries against the latest [Policy]. Pure, so it is unit-tested on the JVM.
 * The service evaluates every query afresh, so there is no cache to flush on policy changes.
 */
object DnsFilter {
    /** Hard categories never unblock, but their lists do change. */
    const val HARD_BLOCK_TTL = 300

    /** Blocked by a profile: short, so a grant takes effect right away. */
    const val BLOCK_TTL = 5

    /** Allowed now but named by the catalogue, so a profile or expiring grant could block it soon. */
    const val POLICY_TTL = 10

    /**
     * [systemHost] is a name the phone itself depends on (the strict Private DNS server).
     * It and its subdomains are always forwarded, even if a list blocks them, so geryon can
     * never cut the phone off from its own DNS.
     */
    fun handle(
        message: ByteArray,
        policy: Policy,
        now: Long = System.currentTimeMillis(),
        systemHost: String? = null,
    ): DnsVerdict {
        val q = DnsMessage.parseQuery(message)
            ?: return DnsMessage.formatError(message)?.let { DnsVerdict.Reply(it) } ?: DnsVerdict.Drop

        if (systemHost != null && isSameOrSubdomain(q.name, systemHost)) return DnsVerdict.Forward(q, null)

        val subject = Subject.Domain(q.name)
        val d = policy.decide(subject, now)
        if (d.blocked) {
            return DnsVerdict.Reply(DnsMessage.blockedResponse(q, if (d.layer == Layer.Hard) HARD_BLOCK_TTL else BLOCK_TTL))
        }
        val maxTtl = when (d.layer) {
            Layer.Grant -> d.until?.let { ((it - now) / 1000).toInt().coerceIn(1, POLICY_TTL) } ?: POLICY_TTL
            Layer.Profile, Layer.Base -> POLICY_TTL
            // Allowed by default: only cap if the domain belongs to some target or category.
            Layer.Default, Layer.Hard -> if (policy.index.match(subject).isEmpty) null else POLICY_TTL
        }
        return DnsVerdict.Forward(q, maxTtl)
    }

    internal fun isSameOrSubdomain(name: String, host: String): Boolean {
        val n = name.trimEnd('.').lowercase()
        val h = host.trimEnd('.').lowercase()
        return n == h || n.endsWith(".$h")
    }
}
