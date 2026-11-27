package io.github.arboeckh.geryon

import android.app.Application
import android.content.Context
import io.github.arboeckh.geryon.block.BlockRepository
import io.github.arboeckh.geryon.enforce.AppBlocker
import io.github.arboeckh.geryon.enforce.DomainBlocker
import io.github.arboeckh.geryon.enforce.Enforcer
import io.github.arboeckh.geryon.lists.BlocklistSync

class GeryonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BlocklistSync.start(this)
    }

    val blocks: BlockRepository by lazy { BlockRepository(this) }

    /** Every mechanism that carries out the policy. The UI lists these with their setup state. */
    val enforcers: List<Enforcer> by lazy { listOf(AppBlocker, DomainBlocker) }
}

val Context.geryon: GeryonApp get() = applicationContext as GeryonApp
