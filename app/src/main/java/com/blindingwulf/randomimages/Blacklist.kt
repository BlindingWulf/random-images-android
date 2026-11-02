package com.blindingwulf.randomimages

/**
 * The default content blacklist. Terms are appended to every search request
 * as exclusions (`-term`), invisible in the search box and status line; the
 * user can switch them off with the Filter chip or replace the list entirely
 * from the menu.
 *
 * The spellings were verified against the live e621 API (2026-11-03): every
 * canonical tag is listed, and so are the common aliases, because e621
 * resolves aliases inside negation too (`-scat` removes canonical `feces`
 * posts). Terms with no content on the sites (zero matching posts when
 * probed) are deliberately absent — a dead term only inflates the query.
 *
 * Not included by default: vore, unbirth, size (macro/micro) and inflation —
 * fetish categories some users search for on purpose, so they stay out of a
 * list that is on by default.
 */
object Blacklist {

    val DEFAULT = listOf(
        // minors / ageplay
        "child", "child_(aged)", "toddler", "baby", "infant",
        "loli", "lolicon", "shota", "age_play", "bestiality",
        // scat / mess
        "scat", "feces", "diaper", "diapers", "urine", "vomit",
        "flatulence", "menstruation",
        // gore
        "gore", "blood", "guro", "decapitation", "dismemberment",
        "internal_injury", "torture", "necrophilia",
    )

    /**
     * The provider-facing query: the user's own terms first, then the
     * blacklist as negative tags. Exclusions are joined with a space, which
     * both e621 (tag syntax) and FurAffinity (`-word` in the q parameter,
     * verified live) understand. Returns the query untouched when the list
     * is empty, so a disabled or emptied blacklist cannot change behaviour.
     */
    fun compose(query: String, terms: List<String>): String {
        if (terms.isEmpty()) return query
        val seen = HashSet<String>()
        val negatives = terms
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("-") && seen.add(it.lowercase()) }
            .joinToString(" ") { "-$it" }
        return if (negatives.isEmpty()) query else "$query $negatives"
    }
}
