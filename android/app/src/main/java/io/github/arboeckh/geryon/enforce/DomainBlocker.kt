package io.github.arboeckh.geryon.enforce

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.Settings
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

    override fun problems(context: Context): List<EnforcerProblem> = buildList {
        // GrapheneOS lets users revoke the Network permission; without it nothing can be forwarded.
        if (context.checkSelfPermission(Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            add(
                EnforcerProblem(
                    "geryon has no network access",
                    "Allowed websites won't load while the website blocker is on. " +
                        "Turn on the Network permission for geryon.",
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    "Open app settings",
                ),
            )
        }
        if (DnsVpnService.running.value && DnsVpnService.lockdown) {
            add(
                EnforcerProblem(
                    "Internet is cut off by VPN lockdown",
                    "\"Block connections without VPN\" is on for geryon. geryon's VPN only filters DNS, " +
                        "so this blocks every connection. Turn it off in the VPN settings (Always-on can stay on).",
                    Intent(Settings.ACTION_VPN_SETTINGS),
                    "Open VPN settings",
                ),
            )
        }
        privateDnsHost(context)?.let { host ->
            add(
                EnforcerProblem(
                    "Private DNS skips the website blocker",
                    "Private DNS is set to $host, so websites go straight to it and aren't filtered. " +
                        "Set Private DNS to Automatic to use geryon's website blocking.",
                    Intent(Settings.ACTION_WIRELESS_SETTINGS),
                    "Open network settings",
                ),
            )
        }
    }

    fun stop(context: Context) = DnsVpnService.stop(context)

    /**
     * The strict Private DNS hostname, if one is set. geryon is excluded from its own VPN,
     * so its default network is the real one and carries the system's Private DNS setting.
     */
    fun privateDnsHost(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.privateDnsServerName?.takeIf { lp.isPrivateDnsActive }
    }
}
