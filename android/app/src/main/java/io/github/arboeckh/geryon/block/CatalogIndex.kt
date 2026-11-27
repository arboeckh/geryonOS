package io.github.arboeckh.geryon.block

/** Targets and categories a subject belongs to. */
data class Match(val targets: Set<String>, val categories: Set<String>) {
    val isEmpty get() = targets.isEmpty() && categories.isEmpty()

    companion object {
        val None = Match(emptySet(), emptySet())
    }
}

/**
 * Lookup tables over a [Catalog] so enforcers can classify apps and domains quickly.
 *
 * [extraCategoryDomains] attaches large external blocklists (millions of domains) to
 * categories without putting them in the catalogue file: category id → domain suffixes.
 */
class CatalogIndex(
    val catalog: Catalog,
    extraCategoryDomains: Map<String, Set<String>> = emptyMap(),
) {
    val targets: Map<String, Target> = catalog.targets.associateBy { it.id }
    val categories: Map<String, Category> = catalog.categories.associateBy { it.id }

    private val packageToTargets: Map<String, Set<String>> = buildMap<String, MutableSet<String>> {
        for (t in catalog.targets) for (p in t.packages) getOrPut(p) { mutableSetOf() } += t.id
    }

    private val domainToTargets: Map<String, Set<String>> = buildMap<String, MutableSet<String>> {
        for (t in catalog.targets) for (d in t.domains) getOrPut(normalizeDomain(d)) { mutableSetOf() } += t.id
    }

    private val targetToCategories: Map<String, Set<String>> = buildMap<String, MutableSet<String>> {
        for (c in catalog.categories) for (t in c.targets) getOrPut(t) { mutableSetOf() } += c.id
    }

    /** Loose domains per category: catalogue entries plus external lists. */
    private val categoryDomains: Map<String, Set<String>> = buildMap {
        for (c in catalog.categories) {
            val extra = extraCategoryDomains[c.id].orEmpty()
            val own = c.domains.mapTo(HashSet()) { normalizeDomain(it) }
            if (own.isNotEmpty() || extra.isNotEmpty()) put(c.id, if (extra.isEmpty()) own else own + extra)
        }
    }

    private val hardCategoryIds: Set<String> = catalog.categories.filter { it.hard }.mapTo(HashSet()) { it.id }

    /** Targets that sit in a hard category can never be granted. */
    fun isGrantable(targetId: String): Boolean =
        targetId in targets && targetToCategories[targetId].orEmpty().none { it in hardCategoryIds }

    fun grantableTargets(): List<Target> = catalog.targets.filter { isGrantable(it.id) }

    fun match(subject: Subject): Match = when (subject) {
        is Subject.App -> {
            val t = packageToTargets[subject.packageName].orEmpty()
            if (t.isEmpty()) Match.None else Match(t, categoriesOf(t))
        }
        is Subject.Domain -> matchDomain(subject.name)
    }

    private fun matchDomain(raw: String): Match {
        val suffixes = suffixesOf(normalizeDomain(raw))
        if (suffixes.isEmpty()) return Match.None

        val targets = HashSet<String>()
        for (s in suffixes) domainToTargets[s]?.let { targets += it }

        val categories = HashSet(categoriesOf(targets))
        for ((categoryId, domains) in categoryDomains) {
            if (categoryId !in categories && suffixes.any { it in domains }) categories += categoryId
        }
        return if (targets.isEmpty() && categories.isEmpty()) Match.None else Match(targets, categories)
    }

    private fun categoriesOf(targets: Set<String>): Set<String> =
        targets.flatMapTo(HashSet()) { targetToCategories[it].orEmpty() }

    companion object {
        fun normalizeDomain(d: String): String = d.trim().trimEnd('.').lowercase()

        /** "a.b.example.com" → [a.b.example.com, b.example.com, example.com, com]. */
        fun suffixesOf(domain: String): List<String> {
            if (domain.isEmpty()) return emptyList()
            val out = ArrayList<String>(4)
            var i = 0
            while (true) {
                out += domain.substring(i)
                val dot = domain.indexOf('.', i)
                if (dot < 0) break
                i = dot + 1
            }
            return out
        }
    }
}
