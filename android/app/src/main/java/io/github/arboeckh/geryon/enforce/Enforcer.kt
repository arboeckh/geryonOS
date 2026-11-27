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
}

enum class EnforcerStatus { Running, NeedsSetup, Unsupported }
