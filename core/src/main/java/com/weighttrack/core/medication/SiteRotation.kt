package com.weighttrack.core.medication

/**
 * Which site to use next.
 *
 * Rotating matters: injecting the same spot week after week thickens the tissue under it, and
 * absorption from a thickened site is slower and less predictable, which shows up as a week that
 * works less well for no reason anybody can see. The app cannot check somebody's skin, but it can
 * remember where the last few went, which is the part people actually lose track of.
 */
object SiteRotation {

    /**
     * The site to suggest, given every site used before, newest first.
     *
     * The one used longest ago wins, and one never used at all counts as longest ago, so a new
     * person is walked around the whole rotation before anywhere is repeated. Ties are broken by
     * the rotation order taken from the last site used, so following the suggestion moves along
     * the list rather than jumping about.
     */
    fun next(recentFirst: List<InjectionSite>): InjectionSite {
        val sites = InjectionSite.entries
        val last = recentFirst.firstOrNull() ?: return sites.first()
        // How long ago each site was used, counted in injections rather than in days: the gap
        // that matters is how many have gone in elsewhere since, not how much time has passed.
        val lastUsedAt = HashMap<InjectionSite, Int>()
        recentFirst.forEachIndexed { index, site -> lastUsedAt.putIfAbsent(site, index) }
        val startsAfter = sites.indexOf(last) + 1
        return sites.indices
            .map { sites[(startsAfter + it) % sites.size] }
            .maxByOrNull { lastUsedAt[it] ?: Int.MAX_VALUE }
            ?: sites.first()
    }
}
