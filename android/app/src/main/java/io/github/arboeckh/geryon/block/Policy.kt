package io.github.arboeckh.geryon.block

/**
 * An immutable snapshot of everything needed to answer "is this blocked?".
 * Enforcers hold the latest one and call [decide]; the repository publishes a new
 * snapshot whenever the catalogue, config or state changes.
 *
 * The stack, top wins:
 *  1. Hard categories: always block.
 *  2. Grants: allow a target until its timer ends.
 *  3. Active profiles, most recently started first.
 *  4. The base profile (unless paused).
 *  5. Default: allow.
 *
 * Within one profile a rule naming a target beats a rule naming a category,
 * and if both Block and Allow match at the same level, Block wins.
 *
 * Timers are checked against [decide]'s `now`, so a late cleanup of expired
 * grants never lets anything through.
 */
class Policy(
    val index: CatalogIndex,
    val config: BlockConfig,
    val state: BlockState,
) {
    fun decide(subject: Subject, now: Long = System.currentTimeMillis()): Decision {
        val match = index.match(subject)
        if (match.isEmpty) return Decision.Allowed

        match.categories.firstOrNull { index.categories[it]?.hard == true }?.let { id ->
            return Decision(true, Layer.Hard, id, index.categories[id]?.name)
        }

        state.grants
            .filter { it.until > now && it.targetId in match.targets }
            .maxByOrNull { it.until }
            ?.let { g -> return Decision(false, Layer.Grant, g.targetId, index.targets[g.targetId]?.name, g.until) }

        state.activeProfiles
            .filter { it.until == null || it.until > now }
            .sortedByDescending { it.startedAt }
            .forEach { active ->
                val profile = config.profile(active.profileId) ?: return@forEach
                evaluate(profile, match)?.let { blocked ->
                    return Decision(blocked, Layer.Profile, profile.id, profile.name, active.until)
                }
            }

        if (!state.basePaused) {
            config.profile(config.baseProfileId)?.let { base ->
                evaluate(base, match)?.let { blocked ->
                    return Decision(blocked, Layer.Base, base.id, base.name)
                }
            }
        }

        return Decision.Allowed
    }

    fun isBlocked(subject: Subject, now: Long = System.currentTimeMillis()) = decide(subject, now).blocked

    /** Returns true for block, false for allow, null if the profile has no opinion. */
    private fun evaluate(profile: Profile, match: Match): Boolean? {
        val targetRules = profile.rules.filter { (it.selector as? Selector.OfTarget)?.id in match.targets }
        resolve(targetRules)?.let { return it }
        val categoryRules = profile.rules.filter { (it.selector as? Selector.OfCategory)?.id in match.categories }
        return resolve(categoryRules)
    }

    private fun resolve(rules: List<Rule>): Boolean? = when {
        rules.isEmpty() -> null
        rules.any { it.effect == Effect.Block } -> true
        else -> false
    }
}
