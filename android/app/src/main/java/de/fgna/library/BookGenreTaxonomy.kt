package de.fgna.library

import org.json.JSONArray

internal object BookGenreTaxonomy {
    val allowed: List<String> = listOf(
        "Belletristik",
        "Krimi & Thriller",
        "Fantasy & Science-Fiction",
        "Biografie",
        "Geschichte",
        "Politik & Gesellschaft",
        "Wirtschaft",
        "Psychologie",
        "Philosophie",
        "Wissenschaft",
        "Gesundheit",
        "Ratgeber",
        "Reise",
        "Kinder & Jugend",
        "Religion",
        "Kunst & Kultur",
    )

    private val canonicalByLower = allowed.associateBy { it.lowercase() }
    private val aliases = mapOf(
        "sachbuch" to "Ratgeber",
        "self-help" to "Ratgeber",
        "self help" to "Ratgeber",
        "science fiction" to "Fantasy & Science-Fiction",
        "science-fiction" to "Fantasy & Science-Fiction",
        "fantasy" to "Fantasy & Science-Fiction",
        "thriller" to "Krimi & Thriller",
        "krimi" to "Krimi & Thriller",
        "politik" to "Politik & Gesellschaft",
        "gesellschaft" to "Politik & Gesellschaft",
        "kunst" to "Kunst & Kultur",
        "kultur" to "Kunst & Kultur",
    )

    fun sanitize(raw: JSONArray?, max: Int = 2): JSONArray {
        val out = JSONArray()
        if (raw == null) return out
        val seen = linkedSetOf<String>()
        for (i in 0 until raw.length()) {
            val canonical = canonical(raw.optString(i)) ?: continue
            if (seen.add(canonical)) out.put(canonical)
            if (out.length() >= max) break
        }
        return out
    }

    fun canonical(value: String): String? {
        val clean = value.trim()
        if (clean.isBlank()) return null
        val key = clean.lowercase()
        return canonicalByLower[key] ?: aliases[key]
    }

    fun promptList(): String = allowed.joinToString(", ")
}
