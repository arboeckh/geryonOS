package io.github.arboeckh.geryon.enforce.dns

/** Addresses used by the DNS VPN. Change the upstream here. */
object DnsConfig {
    /**
     * Where allowed queries go. Cloudflare for Families (malware + adult filtering), so
     * those are covered without shipping huge lists. Tried in order until one answers.
     * Plain Cloudflare would be 1.1.1.1 / 1.0.0.1 / 2606:4700:4700::1111 / ::1001.
     */
    val UPSTREAM = listOf(
        "1.1.1.3",
        "1.0.0.3",
        "2606:4700:4700::1113",
        "2606:4700:4700::1003",
    )

    const val UPSTREAM_PORT = 53
    const val UPSTREAM_TIMEOUT_MS = 2_000

    /**
     * The tunnel's own addresses and the fake DNS servers announced to the system. Only the
     * fake servers are routed into the tunnel, so all other traffic bypasses it.
     */
    const val TUN_ADDRESS_V4 = "10.111.222.1"
    const val DNS_V4 = "10.111.222.2"
    const val TUN_ADDRESS_V6 = "fd00:6765:7279:6f6e::1"
    const val DNS_V6 = "fd00:6765:7279:6f6e::2"

    const val MTU = 1500
}
