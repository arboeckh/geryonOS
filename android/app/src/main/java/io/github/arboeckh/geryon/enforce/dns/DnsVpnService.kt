package io.github.arboeckh.geryon.enforce.dns

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.arboeckh.geryon.R
import io.github.arboeckh.geryon.geryon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * A local VPN that only carries DNS. The system is told to use a fake DNS server whose
 * address is the only route into the tunnel; everything else bypasses it. Each query is
 * checked against the latest policy: blocked names get 0.0.0.0 / :: / NXDOMAIN, the rest
 * are forwarded to [DnsConfig.UPSTREAM] through a protected socket.
 */
class DnsVpnService : VpnService() {

    private var tunnel: Tunnel? = null

    /** The strict Private DNS hostname of the real network, kept current by [networkCallback]. */
    @Volatile private var privateDnsHost: String? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            privateDnsHost = lp.privateDnsServerName?.takeIf { lp.isPrivateDnsActive }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        goForeground()
        if (tunnel == null) {
            // Consent can be revoked (another VPN took over) while we were not running.
            if (VpnService.prepare(this) != null) {
                Log.w(TAG, "VPN consent missing, not starting")
                shutdown()
                return START_NOT_STICKY
            }
            val pfd = runCatching { establish() }.onFailure { Log.e(TAG, "Couldn't establish VPN", it) }.getOrNull()
            if (pfd == null) {
                shutdown()
                return START_NOT_STICKY
            }
            tunnel = Tunnel(pfd).also { it.start() }
            _running.value = true
            // A DNS-only VPN can't work under lockdown: everything outside the tunnel is dropped.
            _lockdown.value = isLockdownEnabled
            if (isLockdownEnabled) Log.w(TAG, "VPN lockdown is on: all non-DNS traffic will be blocked")
            // geryon is excluded from its own VPN, so its default network is the real one.
            runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback) }
        }
        return START_STICKY
    }

    private fun establish(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.dns_vpn_session))
            .setMtu(DnsConfig.MTU)
            .addAddress(DnsConfig.TUN_ADDRESS_V4, 32)
            .addDnsServer(DnsConfig.DNS_V4)
            .addRoute(DnsConfig.DNS_V4, 32)
            .addAddress(DnsConfig.TUN_ADDRESS_V6, 128)
            .addDnsServer(DnsConfig.DNS_V6)
            .addRoute(DnsConfig.DNS_V6, 128)
            .setBlocking(true)
            // Follow the default network and its metered state instead of looking metered.
            .setUnderlyingNetworks(null)
            .setMetered(false)
        // geryon's own traffic (list downloads) never needs the tunnel.
        runCatching { builder.addDisallowedApplication(packageName) }
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            builder.setConfigureIntent(PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE))
        }
        return builder.establish()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.dns_vpn_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.dns_vpn_notification_title))
            .setContentText(getString(R.string.dns_vpn_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: RuntimeException) {
            // E.g. a sticky restart the system won't let become foreground. The VPN still works.
            Log.w(TAG, "Couldn't go foreground", e)
        }
    }

    override fun onRevoke() {
        shutdown()
    }

    override fun onDestroy() {
        tunnel?.stop()
        tunnel = null
        _running.value = false
        _lockdown.value = false
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        super.onDestroy()
    }

    private fun shutdown() {
        tunnel?.stop()
        tunnel = null
        _running.value = false
        _lockdown.value = false
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Reads packets from one established interface until stopped, then closes it. */
    private inner class Tunnel(private val pfd: ParcelFileDescriptor) {
        @Volatile private var active = true

        @OptIn(ExperimentalCoroutinesApi::class)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(MAX_IN_FLIGHT))
        private val output = FileOutputStream(pfd.fileDescriptor)
        private val upstream: List<InetAddress> = DnsConfig.UPSTREAM.map { InetAddress.getByName(it) } // literals, no lookup
        private val thread = Thread(::readLoop, "geryon-dns-tun")

        fun start() = thread.start()

        fun stop() {
            active = false
            scope.cancel()
        }

        private fun readLoop() {
            val input = FileInputStream(pfd.fileDescriptor)
            val buf = ByteArray(32_767)
            val poll = StructPollfd().apply {
                fd = pfd.fileDescriptor
                events = OsConstants.POLLIN.toShort()
            }
            try {
                while (active) {
                    // Poll with a timeout so stop() is noticed without closing the fd under a read.
                    val ready = try {
                        Os.poll(arrayOf(poll), 500)
                    } catch (e: ErrnoException) {
                        if (e.errno == OsConstants.EINTR) continue else throw e
                    }
                    if (ready == 0) continue
                    if (poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) break
                    val len = input.read(buf)
                    if (len > 0) onPacket(buf, len)
                }
            } catch (e: Exception) {
                if (active) Log.e(TAG, "Tunnel read failed", e)
            } finally {
                active = false
                scope.cancel()
                runCatching { pfd.close() }
            }
        }

        private fun onPacket(buf: ByteArray, len: Int) {
            val packet = IpUdp.parse(buf, len) ?: return // e.g. DoT on 853 or TCP fallback: dropped
            if (packet.dstPort != 53) return
            when (val verdict = DnsFilter.handle(packet.payload, geryon.blocks.policy.value, systemHost = privateDnsHost)) {
                is DnsVerdict.Reply -> write(packet.reply(verdict.message))
                is DnsVerdict.Forward -> scope.launch { forward(packet, verdict) }
                DnsVerdict.Drop -> Unit
            }
        }

        private fun forward(packet: UdpPacket, verdict: DnsVerdict.Forward) {
            val answer = askUpstream(packet.payload)
            val reply = when {
                answer == null -> DnsMessage.response(verdict.query, DnsMessage.RCODE_SERVFAIL)
                verdict.maxTtl != null -> answer.also { DnsMessage.capTtls(it, verdict.maxTtl) }
                else -> answer
            }
            if (active) write(packet.reply(reply))
        }

        /** Tries each upstream in turn. A fresh socket per query follows network changes. */
        private fun askUpstream(query: ByteArray): ByteArray? {
            val id = DnsMessage.id(query)
            for (server in upstream) {
                if (!active) return null
                try {
                    DatagramSocket().use { socket ->
                        if (!protect(socket)) throw IOException("protect() failed")
                        socket.soTimeout = DnsConfig.UPSTREAM_TIMEOUT_MS
                        socket.connect(server, DnsConfig.UPSTREAM_PORT)
                        socket.send(DatagramPacket(query, query.size))
                        val buf = ByteArray(4096)
                        val reply = DatagramPacket(buf, buf.size)
                        while (true) {
                            socket.receive(reply)
                            val msg = buf.copyOf(reply.length)
                            if (DnsMessage.id(msg) == id) return msg
                        }
                    }
                } catch (e: IOException) {
                    // Timeout, or no route (e.g. IPv6 upstream on an IPv4-only network): next one.
                    Log.d(TAG, "Upstream $server failed: ${e.message}")
                }
            }
            return null
        }

        private fun write(packet: UdpPacket) {
            val bytes = IpUdp.build(packet)
            try {
                synchronized(output) { output.write(bytes) }
            } catch (e: IOException) {
                if (active) Log.w(TAG, "Tunnel write failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "DnsVpnService"
        private const val CHANNEL = "dns_vpn"
        private const val NOTIFICATION_ID = 1
        private const val MAX_IN_FLIGHT = 32
        private const val ACTION_STOP = "io.github.arboeckh.geryon.dns.STOP"

        private val _running = MutableStateFlow(false)

        /** True while the tunnel is up in this process. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _lockdown = MutableStateFlow(false)

        /** True while running under "Block connections without VPN", which cuts off non-DNS traffic. */
        val lockdown: StateFlow<Boolean> = _lockdown.asStateFlow()

        /** Starts the VPN. Consent must already be given ([VpnService.prepare] returns null). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DnsVpnService::class.java))
        }

        fun stop(context: Context) {
            if (!_running.value) return
            context.startService(Intent(context, DnsVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
