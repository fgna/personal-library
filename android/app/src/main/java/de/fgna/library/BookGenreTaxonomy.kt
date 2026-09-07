package de.fgna.library

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

internal object BookGenreTaxonomy {
    private const val MAX_GENRES = 3

    fun allowedFromActiveCatalog(): List<String> {
        val app = LibraryApplication.instance
        val cached = File(app.filesDir, "books-cache.json")
        val text = if (cached.isFile) {
            cached.readText(Charsets.UTF_8)
        } else {
            app.assets.open("www/books.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        return allowedFromCatalog(text)
    }

    fun allowedFromCatalog(catalogJson: String): List<String> {
        val books = JSONObject(catalogJson).getJSONArray("books")
        val values = linkedSetOf<String>()
        for (bookIndex in 0 until books.length()) {
            val genres = books.optJSONObject(bookIndex)?.optJSONArray("genre") ?: continue
            for (genreIndex in 0 until genres.length()) {
                val value = genres.optString(genreIndex).trim()
                if (value.isNotBlank()) values.add(value)
            }
        }
        return values.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun sanitize(raw: JSONArray?, allowed: List<String> = allowedFromActiveCatalog(), max: Int = MAX_GENRES): JSONArray {
        val out = JSONArray()
        if (raw == null || allowed.isEmpty()) return out
        val canonicalByLower = allowed.associateBy { it.lowercase(Locale.ROOT) }
        val seen = linkedSetOf<String>()
        for (i in 0 until raw.length()) {
            val clean = raw.optString(i).trim()
            val canonical = canonicalByLower[clean.lowercase(Locale.ROOT)] ?: continue
            if (seen.add(canonical)) out.put(canonical)
            if (out.length() >= max) break
        }
        return out
    }

    fun merge(
        primary: JSONArray?,
        secondary: JSONArray?,
        allowed: List<String> = allowedFromActiveCatalog(),
        max: Int = MAX_GENRES,
    ): JSONArray {
        val out = JSONArray()
        val seen = linkedSetOf<String>()
        for (source in listOf(primary, secondary)) {
            val sanitized = sanitize(source, allowed, max)
            for (i in 0 until sanitized.length()) {
                val value = sanitized.optString(i)
                if (seen.add(value)) out.put(value)
                if (out.length() >= max) return out
            }
        }
        return out
    }

    fun promptList(allowed: List<String> = allowedFromActiveCatalog()): String {
        val out = JSONArray()
        allowed.forEach { out.put(it) }
        return out.toString()
    }
}
