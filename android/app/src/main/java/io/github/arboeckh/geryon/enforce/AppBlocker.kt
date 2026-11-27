package io.github.arboeckh.geryon.enforce

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import io.github.arboeckh.geryon.block.Policy
import io.github.arboeckh.geryon.block.Subject
import io.github.arboeckh.geryon.geryon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Blocks apps by watching which app comes to the front. Needs the accessibility service turned on. */
object AppBlocker : Enforcer {
    override val id = "app_blocker"
    override val label = "App blocker"
    override val description = "Closes blocked apps when they open. Uses an accessibility service."

    override fun status(context: Context): EnforcerStatus {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = ComponentName(context, AppBlockerService::class.java)
        val on = enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        return if (on) EnforcerStatus.Running else EnforcerStatus.NeedsSetup
    }

    override fun setupIntent(context: Context) =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

class AppBlockerService : AccessibilityService() {

    private var scope: CoroutineScope? = null

    /** The app in front, as last reported. Null while geryon itself is in front. */
    private var foreground: String? = null

    /** Packages whose windows don't mean the user switched apps (shade, keyboards). */
    private var passthrough: Set<String> = emptySet()

    override fun onServiceConnected() {
        passthrough = buildSet {
            add("com.android.systemui")
            getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach { add(it.packageName) }
        }
        scope = MainScope().also { s ->
            // A new policy (grant ended, profile started, ...) applies to whatever is open now.
            s.launch { geryon.blocks.policy.collect { check(foreground, it) } }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        when (pkg) {
            packageName -> foreground = null
            in passthrough -> return
            else -> {
                foreground = pkg
                check(pkg, geryon.blocks.policy.value)
            }
        }
    }

    private fun check(pkg: String?, policy: Policy) {
        if (pkg == null) return
        val subject = Subject.App(pkg)
        val decision = policy.decide(subject)
        if (!decision.blocked) return

        val name = policy.index.match(subject).targets.firstNotNullOfOrNull { policy.index.targets[it]?.name } ?: pkg
        performGlobalAction(GLOBAL_ACTION_HOME)
        startActivity(BlockedActivity.intent(this, name, decision))
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }
}
