package io.github.arboeckh.geryon

import android.app.Application
import android.content.Context
import io.github.arboeckh.geryon.block.BlockRepository
import io.github.arboeckh.geryon.enforce.AppBlocker
import io.github.arboeckh.geryon.enforce.Enforcer

class GeryonApp : Application() {
    val blocks: BlockRepository by lazy { BlockRepository(this) }

    /** Every mechanism that carries out the policy. The UI lists these with their setup state. */
    val enforcers: List<Enforcer> by lazy { listOf(AppBlocker) }
}

val Context.geryon: GeryonApp get() = applicationContext as GeryonApp
