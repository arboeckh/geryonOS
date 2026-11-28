package io.github.arboeckh.geryon.enforce

import android.content.Context
import android.content.Intent

/**
 * A mechanism that carries out the policy on the device: the accessibility app blocker,
 * the DNS VPN, later Device Owner package suspension.
 *
 * Enforcers never decide anything themselves. They collect
 * [io.github.arboeckh.geryon.block.BlockRepository.policy] and ask the latest
 * [io.github.arboeckh.geryon.block.Policy] about each app or domain they see, re-checking
 * whatever is current whenever a new policy is published.
 */
interface Enforcer {
    val id: String
    val label: String
    /** What this enforcer covers, shown in setup. */
    val description: String

    fun status(context: Context): EnforcerStatus

    /** Where to send the user to turn it on, or null if it can't be turned on from here. */
    fun setupIntent(context: Context): Intent?

    /**
     * Device settings that stop this enforcer from working properly even though it is on,
     * e.g. a VPN lockdown that cuts off the internet. Shown on the home screen.
     */
    fun problems(context: Context): List<EnforcerProblem> = emptyList()
}

enum class EnforcerStatus { Running, NeedsSetup, Unsupported }

/** Something the user has to change in the phone's settings. [fix] opens the right screen. */
data class EnforcerProblem(val title: String, val body: String, val fix: Intent?, val fixLabel: String = "Open settings")
