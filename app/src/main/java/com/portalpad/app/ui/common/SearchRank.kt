package com.portalpad.app.ui.common

/**
 * Word-prefix search ranking shared by the keyboard-relay suggestion chips,
 * the phone app drawer, and the dock's add-apps sheet.
 *
 * Matching rules (field-tuned 2026-07-09, Hulu "mod" case):
 *  - Tokens are split on whitespace and common punctuation, then NORMALIZED to
 *    letters/digits only — so "M.O.D.O.K.," becomes the token "modok", which
 *    startsWith("mod"): initialisms behave like normal words.
 *  - Leading ARTICLES ("a", "an", "the") don't count as the first word:
 *    "A Modern Musketeer" ranks as if it began with "Modern".
 *  - Tiers: 0 = first non-article word starts with the query;
 *           1 = any word starts with the query;
 *           2 = no word-prefix match.
 *  - Ordering within a tier preserves the source order (the app's own
 *    relevance ranking is respected inside each tier).
 */
object SearchRank {

    private val ARTICLES = setOf("a", "an", "the")

    private fun norm(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    private fun tokens(title: String): List<String> =
        // NOTE: '.' is deliberately NOT a split character — dots vanish inside
        // tokens via norm(), which is what turns "M.O.D.O.K." into "modok"
        // (splitting on '.' shattered it into single letters; caught by the
        // build-time sanity test on the Hulu field case).
        title.split(' ', ',', ':', ';', '/', '-', '(', ')', '·', '|')
            .map { norm(it) }
            .filter { it.isNotEmpty() }

    /** Tier of [title] against [query]; see class doc. Empty query → tier 0. */
    fun tier(title: String, query: String): Int {
        val q = norm(query)
        if (q.isEmpty()) return 0
        val t = tokens(title)
        if (t.isEmpty()) return 2
        val firstIdx = t.indexOfFirst { it !in ARTICLES }.let { if (it < 0) 0 else it }
        if (t[firstIdx].startsWith(q)) return 0
        if (t.any { it.startsWith(q) }) return 1
        return 2
    }

    /** Sort key for within-tier ALPHABETICAL ordering: normalized tokens with
     *  a leading article dropped — "The Boss Baby" files under B, library
     *  style, consistent with how tiers treat articles. */
    private fun sortKey(title: String): String {
        val t = tokens(title)
        val fromIdx = t.indexOfFirst { it !in ARTICLES }.let { if (it < 0) 0 else it }
        return t.drop(fromIdx).joinToString(" ")
    }

    /**
     * Rank [items] by tier; ALPHABETICAL within each tier (article-skipped).
     * Non-matches (tier 2) are DROPPED when the matching tiers already hold
     * >= [dropAt] entries (a clean list can afford to shed the "WATCH WITH
     * LIVE TV" promo tile), and kept at the BOTTOM otherwise (a fuzzy query
     * must never face an empty box). Used for the relay's suggestion chips.
     */
    fun <T> rank(items: List<T>, query: String, dropAt: Int = 3, title: (T) -> String): List<T> {
        if (norm(query).isEmpty()) return items
        val tiered = items.map { it to tier(title(it), query) }
        val matches = tiered.filter { it.second < 2 }
            .sortedWith(compareBy({ it.second }, { sortKey(title(it.first)) }))
            .map { it.first }
        val rest = tiered.filter { it.second == 2 }
            .sortedBy { sortKey(title(it.first)) }
            .map { it.first }
        return if (matches.size >= dropAt) matches else matches + rest
    }

    /**
     * Strict variant for APP LISTS (drawer / dock add-sheet): word-prefix
     * matches only, ranked, whenever any exist; else a plain normalized
     * substring fallback (inner-word fragments still find apps); else empty —
     * the caller may chain its own legacy fallback (e.g. package-name search).
     */
    fun <T> filterApps(items: List<T>, query: String, title: (T) -> String): List<T> {
        val q = norm(query)
        if (q.isEmpty()) return items
        val tiered = items.map { it to tier(title(it), query) }
        val prefix = tiered.filter { it.second < 2 }
            .sortedWith(compareBy({ it.second }, { sortKey(title(it.first)) }))
            .map { it.first }
        if (prefix.isNotEmpty()) return prefix
        return items.filter { norm(title(it)).contains(q) }
    }
}
