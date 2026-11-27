package io.github.arboeckh.geryon.enforce.dns

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.arboeckh.geryon.R

/**
 * Invisible step that turns the DNS VPN on: asks for the notification permission (for the
 * foreground notice), then the system's VPN consent, then starts [DnsVpnService].
 * Self-contained, so the UI only has to start it.
 */
class VpnSetupActivity : ComponentActivity() {

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        askVpnConsent() // works either way; without the permission the notice is just hidden
    }

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            DnsVpnService.start(this)
        } else {
            Toast.makeText(this, R.string.dns_vpn_consent_denied, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated (e.g. rotation) while a request is showing: its result still arrives.
        if (savedInstanceState != null) return

        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askVpnConsent()
        }
    }

    private fun askVpnConsent() {
        val request = VpnService.prepare(this)
        if (request == null) {
            DnsVpnService.start(this)
            finish()
            return
        }
        try {
            consent.launch(request)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.dns_vpn_unsupported, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
