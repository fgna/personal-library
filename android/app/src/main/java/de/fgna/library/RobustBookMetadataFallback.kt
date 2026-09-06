package de.fgna.library

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.text.Normalizer
import java.util.Locale

/**
 * Conservative second-pass bibliographic lookup used only when the primary
 * BookMetadataEnricher found no trusted match. It never invents an author:
 * every accepted result must match the author supplied by vision or explicitly
 * confirmed by the user.
 */
internal object RobustBookMetadataFallback {
    private const val USER_AGENT = "PersonalLibrary/0.1"

    fun apply(recognized: JSONObject, enriched: JSONObject): JSONObject {
        val result = JSONObject(enriched.toString())
        if (result.optBoolean("_bibliographic_match", false)) return result

        val title = recognized.optString("title").trim()
        val author = recognized.optString("author").trim()
        if (title.isBlank() || author.isBlank()) return lowerUnverifiedConfidence(result)

        val openLibrary = runCatching { lookupOpenLibrary(title, author) }
            .getOrElse { JSONObject().put("source_error", it.message ?: "Open Library fallback failed") }
        if (openLibrary.optBoolean("trusted_match", false)) {
            merge(result, openLibrary)
            markMatch(result, "Open Library", openLibrary)
            return result
        }

        val google = runCatching { lookupGoogleBooks(title, author) }
            .getOrElse { JSONObject().put("source_error", it.message ?: "Google Books fallback failed") }
        if (google.optBoolean("trusted_match", false)) {
            merge(result, google)
            markMatch(result, "Google Books", google)
            return result
        }

        val diagnostics = result.optJSONObject("_metadata_diagnostics") ?: JSONObject().also {
            result.put("_metadata_diagnostics", it)
        }
        diagnostics.put("open_library", when {
            openLibrary.has("source_error") -> openLibrary.optString("source_error")
            else -> "no match after normalized fallback"
        })
        diagnostics.put("google_books", when {
            google.has("source_error") -> google.optString("source_error")
            else -> "no match after normalized fallback"
        })
        return lowerUnverifiedConfidence(result)
    }

    private fun lowerUnverifiedConfidence(result: JSONObject): JSONObject {
        if (!result.optBoolean("_identity_verified", false)) {
            result.put("confidence", result.optDouble("confidence", 0.0).coerceAtMost(0.60))
        }
        return result
    }

    private fun markMatch(result: JSONObject, sourceName: String, facts: JSONObject) {
        result.put("_bibliographic_match", true)
        result.put("_identity_verified", true)

        val sources = result.optJSONArray("_metadata_sources") ?: JSONArray().also {
            result.put("_metadata_sources", it)
        }
        if ((0 until sources.length()).none { sources.optString(it) == sourceName }) sources.put(sourceName)

        val diagnostics = result.optJSONObject("_metadata_diagnostics") ?: JSONObject().also {
            result.put("_metadata_diagnostics", it)
        }
        if (sourceName == "Open Library") {
            diagnostics.put("open_library", "fallback match ${facts.optString("openlibrary_work_id")}".trim())
        } else {
            diagnostics.put("google_books", "fallback match")
        }
    }

    private fun merge(result: JSONObject, facts: JSONObject) {
        facts.optString("canonical_title").trim().takeIf { it.isNotBlank() }?.let { result.put("title", it) }
        facts.optString("openlibrary_work_id").trim().takeIf { it.isNotBlank() }?.let { result.put("openlibrary_work_id", it) }
        facts.optInt("year_published", 0).takeIf { it > 0 }?.let { result.put("year_published", it) }
        facts.optString("description").trim().takeIf { it.isNotBlank() }?.let { result.put("summary", it) }

        val terms = linkedSetOf<String>()
        for (key in listOf("subjects", "categories")) {
            val array = facts.optJSONArray(key) ?: continue
            for (i in 0 until array.length()) {
                array.optString(i).trim().takeIf { it.isNotBlank() }?.let(terms::add)
            }
        }
        if (terms.isNotEmpty()) {
            result.put("keywords", JSONArray(terms.take(6)))
            val genres = mappedGenres(terms)
            if (genres.isNotEmpty()) result.put("genre", JSONArray(genres.take(3)))
        }
    }

    private fun lookupOpenLibrary(title: String, author: String): JSONObject {
        var best: JSONObject? = null
        var bestScore = -1
        for (variant in titleVariants(title)) {
            val queries = listOf(
                "https://openlibrary.org/search.json?title=${enc(variant)}&author=${enc(author)}&limit=50&fields=key,title,author_name,first_publish_year,subject,language,isbn,edition_count",
                "https://openlibrary.org/search.json?q=${enc("$variant $author")}&limit=50&fields=key,title,author_name,first_publish_year,subject,language,isbn,edition_count",
            )
            for (url in queries) {
                val docs = getJson(url).optJSONArray("docs") ?: JSONArray()
                for (i in 0 until docs.length()) {
                    val doc = docs.optJSONObject(i) ?: continue
                    if (!authorMatches(author, doc.optJSONArray("author_name"))) continue
                    val score = flexibleTitleScore(title, variant, doc.optString("title"))
                    if (score > bestScore) {
                        best = doc
                        bestScore = score
                    }
                }
                if (bestScore >= 100) break
            }
            if (bestScore >= 100) break
        }
        val match = best ?: return JSONObject()
        if (bestScore < 55) return JSONObject()

        val facts = JSONObject().put("trusted_match", true)
        val canonical = match.optString("title").trim()
        if (canonical.isNotBlank()) facts.put("canonical_title", canonical)
        val key = match.optString("key")
        val workId = key.removePrefix("/works/").takeIf { key.startsWith("/works/") }.orEmpty()
        if (workId.isNotBlank()) facts.put("openlibrary_work_id", workId)
        match.optInt("first_publish_year", 0).takeIf { it > 0 }?.let { facts.put("year_published", it) }
        match.optJSONArray("subject")?.let { facts.put("subjects", it) }

        if (workId.isNotBlank()) {
            runCatching { getJson("https://openlibrary.org/works/$workId.json") }.getOrNull()?.let { work ->
                val description = descriptionText(work.opt("description"))
                if (description.isNotBlank()) facts.put("description", description.take(4000))
                val subjects = work.optJSONArray("subjects")
                if (subjects != null && subjects.length() > 0) facts.put("subjects", subjects)
            }
        }
        return facts
    }

    private fun lookupGoogleBooks(title: String, author: String): JSONObject {
        var best: JSONObject? = null
        var bestScore = -1
        val variants = titleVariants(title).take(5)
        for (variant in variants) {
            // One request per variant; stop as soon as a strong match appears to avoid rate limiting.
            val q = "intitle:$variant inauthor:$author"
            val items = getJson("https://www.googleapis.com/books/v1/volumes?q=${enc(q)}&maxResults=20&printType=books")
                .optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val info = items.optJSONObject(i)?.optJSONObject("volumeInfo") ?: continue
                if (!authorMatches(author, info.optJSONArray("authors"))) continue
                val score = flexibleTitleScore(title, variant, info.optString("title"))
                if (score > bestScore) {
                    best = info
                    bestScore = score
                }
            }
            if (bestScore >= 100) break
        }
        val info = best ?: return JSONObject()
        if (bestScore < 55) return JSONObject()

        return JSONObject().apply {
            put("trusted_match", true)
            info.optString("title").trim().takeIf { it.isNotBlank() }?.let { put("canonical_title", it) }
            info.optString("description").trim().takeIf { it.isNotBlank() }?.let { put("description", it.take(4000)) }
            info.optJSONArray("categories")?.let { put("categories", it) }
            val date = info.optString("publishedDate").trim()
            date.take(4).toIntOrNull()?.takeIf { it > 0 }?.let { put("year_published", it) }
        }
    }

    private fun titleVariants(value: String): List<String> {
        val clean = value.trim().replace(Regex("\\s+"), " ")
        val variants = linkedSetOf<String>()
        if (clean.isBlank()) return emptyList()
        variants += clean

        val punctuationBase = clean
            .substringBefore(':')
            .substringBefore(" — ")
            .substringBefore(" - ")
            .trim()
        if (punctuationBase.isNotBlank()) variants += punctuationBase

        val words = clean.split(' ').filter { it.isNotBlank() }
        // OCR often splits one compound word (Bullet Proof vs Bulletproof).
        for (i in 0 until words.lastIndex) {
            val copy = words.toMutableList()
            copy[i] = copy[i] + copy[i + 1]
            copy.removeAt(i + 1)
            variants += copy.joinToString(" ")
        }

        // Covers frequently show title + subtitle without punctuation. Search progressively
        // shorter prefixes, but never fewer than three words for long titles.
        if (words.size >= 6) {
            for (count in listOf(7, 6, 5, 4, 3)) {
                if (count < words.size) variants += words.take(count).joinToString(" ")
            }
        }
        return variants.toList().take(12)
    }

    private fun flexibleTitleScore(original: String, variant: String, candidate: String): Int {
        val o = normalize(original)
        val v = normalize(variant)
        val c = normalize(candidate)
        if (c.isBlank()) return -1
        if (o == c) return 120
        if (compact(o) == compact(c)) return 115
        if (v == c) return 110
        if (compact(v) == compact(c)) return 105

        val oTokens = o.split(' ').filter { it.length > 1 }.toSet()
        val cTokens = c.split(' ').filter { it.length > 1 }.toSet()
        if (oTokens.isEmpty() || cTokens.isEmpty()) return -1
        val common = oTokens.intersect(cTokens).size
        val coverage = common.toDouble() / minOf(oTokens.size, cTokens.size)
        val prefix = compact(c).startsWith(compact(v)) || compact(v).startsWith(compact(c))
        return when {
            coverage >= 0.85 -> 90 + common.coerceAtMost(9)
            coverage >= 0.70 && common >= 3 -> 75 + common
            prefix && common >= 3 -> 65 + common
            coverage >= 0.60 && common >= 4 -> 55 + common
            else -> -1
        }
    }

    private fun authorMatches(author: String, names: JSONArray?): Boolean {
        if (author.isBlank() || names == null) return false
        val wanted = compact(normalize(author))
        for (i in 0 until names.length()) {
            val candidate = compact(normalize(names.optString(i)))
            if (wanted.isNotBlank() && wanted == candidate) return true
        }
        return false
    }

    private fun mappedGenres(terms: Set<String>): List<String> {
        val normalized = terms.map(::normalize)
        val result = linkedSetOf<String>()
        if (normalized.any { "psychology" in it || "psychologie" in it }) result += "Psychologie"
        if (normalized.any { "biography" in it || "autobiography" in it }) result += "Biografie"
        if (normalized.any { "business" in it || "career" in it || "economics" in it || "work" == it }) result += "Wirtschaft"
        if (normalized.any { "self help" in it || "selfhelp" in it }) result += "Sachbuch"
        if (normalized.any { "history" in it }) result += "Geschichte"
        if (normalized.any { "fiction" in it || "novel" in it }) result += "Belletristik"
        if (normalized.any { "philosophy" in it }) result += "Philosophie"
        if (normalized.any { "science" in it }) result += "Wissenschaft"
        return result.toList()
    }

    private fun descriptionText(raw: Any?): String = when (raw) {
        is String -> raw
        is JSONObject -> raw.optString("value")
        else -> ""
    }.trim()

    private fun getJson(value: String): JSONObject {
        val connection = (URL(value).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
            useCaches = false
        }
        try {
            val status = connection.responseCode
            require(status in 200..299) { "Metadata source HTTP $status" }
            return JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun compact(value: String): String = value.replace(" ", "")
    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .lowercase(Locale.ROOT)
        .replace("[^a-z0-9]+".toRegex(), " ")
        .trim()
}
