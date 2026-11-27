package io.github.arboeckh.geryon.enforce

import android.content.Context
import android.content.Intent
import io.github.arboeckh.geryon.enforce.dns.DnsVpnService
import io.github.arboeckh.geryon.enforce.dns.VpnSetupActivity

/** Blocks domains for every app and browser through a local, DNS-only VPN. */
object DomainBlocker : Enforcer {
    override val id = "domain_blocker"
    override val label = "Website blocker"
    override val description =
        "Blocks websites in every app and browser with a local VPN that only filters DNS. " +
            "Adult and malware sites are also filtered by Cloudflare's family resolver."

    override fun status(context: Context): EnforcerStatus =
        if (DnsVpnService.running.value) EnforcerStatus.Running else EnforcerStatus.NeedsSetup

    /** Asks for VPN consent if needed and starts the service; finishes on its own. */
    override fun setupIntent(context: Context): Intent =
        Intent(context, VpnSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun stop(context: Context) = DnsVpnService.stop(context)
}
