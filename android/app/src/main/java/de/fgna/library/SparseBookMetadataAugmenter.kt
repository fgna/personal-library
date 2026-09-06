package de.fgna.library

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.text.Normalizer
import java.util.Locale

/**
 * Adds grounded metadata only after title/author have already been bibliographically
 * confirmed by the primary lookup or by an explicit user correction.
 *
 * Source order:
 * 1. richer Open Library work/edition duplicate for the same title + author
 * 2. Crossref as a free bibliographic fallback for subjects/year/abstract
 *
 * No source is allowed to change the confirmed author.
 */
internal object SparseBookMetadataAugmenter {
    private const val USER_AGENT = "PersonalLibrary/0.1"

    fun apply(recognized: JSONObject, input: JSONObject): JSONObject {
        val result = JSONObject(input.toString())
        if (!result.optBoolean("_identity_verified", false)) return result

        val title = result.optString("title").trim().ifBlank { recognized.optString("title").trim() }
        val author = result.optString("author").trim().ifBlank { recognized.optString("author").trim() }
        if (title.isBlank() || author.isBlank()) return result

        val needsGenre = result.optJSONArray("genre")?.length() ?: 0 == 0
        val needsSummary = result.optString("summary").isBlank()
        val needsYear = result.optInt("year_published", 0) <= 0
        if (!needsGenre && !needsSummary && !needsYear) return result

        val diagnostics = result.optJSONObject("_metadata_diagnostics") ?: JSONObject().also {
            result.put("_metadata_diagnostics", it)
        }

        val richerOpenLibrary = runCatching { lookupRicherOpenLibrary(title, author) }
            .getOrElse { error -> JSONObject().put("source_error", error.message ?: "Open Library enrichment failed") }

        if (richerOpenLibrary.optBoolean("trusted_match", false)) {
            mergeGrounded(result, richerOpenLibrary)
            addSource(result, "Open Library")
            diagnostics.put(
                "open_library",
                "rich match ${richerOpenLibrary.optString("openlibrary_work_id")}".trim(),
            )
        } else if (richerOpenLibrary.has("source_error")) {
            diagnostics.put("open_library_enrichment", richerOpenLibrary.optString("source_error"))
        } else {
            diagnostics.put("open_library_enrichment", "no richer matching work")
        }

        val stillNeedsGenre = result.optJSONArray("genre")?.length() ?: 0 == 0
        val stillNeedsSummary = result.optString("summary").isBlank()
        val stillNeedsYear = result.optInt("year_published", 0) <= 0

        if (stillNeedsGenre || stillNeedsSummary || stillNeedsYear) {
            val crossref = runCatching { lookupCrossref(title, author) }
                .getOrElse { error -> JSONObject().put("source_error", error.message ?: "Crossref lookup failed") }
            if (crossref.optBoolean("trusted_match", false)) {
                mergeGrounded(result, crossref)
                addSource(result, "Crossref")
                diagnostics.put("crossref", "match")
            } else if (crossref.has("source_error")) {
                diagnostics.put("crossref", crossref.optString("source_error"))
            } else {
                diagnostics.put("crossref", "no match")
            }
        } else {
            diagnostics.put("crossref", "not needed")
        }

        diagnostics.put(
            "description",
            if (result.optString("summary").isBlank()) "missing after free-source enrichment" else "available",
        )
        return result
    }

    private fun lookupRicherOpenLibrary(title: String, author: String): JSONObject {
        var best: JSONObject? = null
        var bestScore = Int.MIN_VALUE

        for (variant in titleVariants(title)) {
            val urls = listOf(
                "https://openlibrary.org/search.json?title=${enc(variant)}&author=${enc(author)}&limit=50&fields=key,title,author_name,first_publish_year,subject,language,isbn,edition_count,first_sentence",
                "https://openlibrary.org/search.json?q=${enc("$variant $author")}&limit=50&fields=key,title,author_name,first_publish_year,subject,language,isbn,edition_count,first_sentence",
            )
            for (url in urls) {
                val docs = getJson(url).optJSONArray("docs") ?: JSONArray()
                for (i in 0 until docs.length()) {
                    val doc = docs.optJSONObject(i) ?: continue
                    if (!authorsMatch(author, doc.optJSONArray("author_name"))) continue
                    val titleScore = titleScore(title, variant, doc.optString("title"))
                    if (titleScore < 0) continue

                    val subjects = doc.optJSONArray("subject")?.length() ?: 0
                    val editions = doc.optInt("edition_count", 0).coerceAtMost(30)
                    val isbn = doc.optJSONArray("isbn")?.length() ?: 0
                    val firstSentence = doc.optJSONArray("first_sentence")?.length() ?: 0
                    val completeness =
                        subjects.coerceAtMost(12) * 8 +
                        editions * 2 +
                        if (isbn > 0) 5 else 0 +
                        if (firstSentence > 0) 12 else 0
                    val score = titleScore * 100 + completeness
                    if (score > bestScore) {
                        best = doc
                        bestScore = score
                    }
                }
            }
        }

        val match = best ?: return JSONObject()
        val canonical = match.optString("title").trim()
        val key = match.optString("key")
        val workId = if (key.startsWith("/works/")) key.removePrefix("/works/") else ""

        val facts = JSONObject()
            .put("trusted_match", true)
            .put("source", "openlibrary")
        if (canonical.isNotBlank()) facts.put("canonical_title", canonical)
        if (workId.isNotBlank()) facts.put("openlibrary_work_id", workId)
        match.optInt("first_publish_year", 0).takeIf { it > 0 }?.let { facts.put("year_published", it) }
        match.optJSONArray("subject")?.takeIf { it.length() > 0 }?.let { facts.put("subjects", it) }

        val firstSentence = match.optJSONArray("first_sentence")?.optString(0).orEmpty().trim()
        if (firstSentence.isNotBlank()) facts.put("description", firstSentence)

        if (workId.isNotBlank()) {
            val work = runCatching { getJson("https://openlibrary.org/works/$workId.json") }.getOrNull()
            if (work != null) {
                val description = descriptionText(work.opt("description"))
                if (description.isNotBlank()) facts.put("description", description.take(4000))
                work.optJSONArray("subjects")?.takeIf { it.length() > 0 }?.let { facts.put("subjects", it) }
            }
            enrichFromEditions(facts, workId)
        }
        return facts
    }

    private fun enrichFromEditions(facts: JSONObject, workId: String) {
        val entries = runCatching {
            getJson("https://openlibrary.org/works/$workId/editions.json?limit=50").optJSONArray("entries")
        }.getOrNull() ?: return

        val subjects = linkedSetOf<String>()
        var description = facts.optString("description").trim()
        var earliestYear = facts.optInt("year_published", 0).takeIf { it > 0 }

        for (i in 0 until entries.length()) {
            val edition = entries.optJSONObject(i) ?: continue
            edition.optJSONArray("subjects")?.let { array ->
                for (j in 0 until array.length()) {
                    array.optString(j).trim().takeIf { it.isNotBlank() }?.let(subjects::add)
                }
            }
            if (description.isBlank()) {
                description = descriptionText(edition.opt("description"))
                if (description.isBlank()) description = descriptionText(edition.opt("notes"))
            }
            val year = Regex("(?:18|19|20)\\d{2}")
                .find(edition.optString("publish_date"))
                ?.value
                ?.toIntOrNull()
            if (year != null && (earliestYear == null || year < earliestYear)) earliestYear = year
        }

        if (facts.optJSONArray("subjects")?.length() ?: 0 == 0 && subjects.isNotEmpty()) {
            facts.put("subjects", JSONArray(subjects.toList()))
        }
        if (facts.optString("description").isBlank() && description.isNotBlank()) {
            facts.put("description", description.take(4000))
        }
        if (earliestYear != null && earliestYear > 0) facts.put("year_published", earliestYear)
    }

    private fun lookupCrossref(title: String, author: String): JSONObject {
        val url = "https://api.crossref.org/works?query.title=${enc(title)}&query.author=${enc(author)}&rows=20"
        val items = getJson(url)
            .optJSONObject("message")
            ?.optJSONArray("items")
            ?: JSONArray()

        var best: JSONObject? = null
        var bestScore = -1
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val candidateTitle = item.optJSONArray("title")?.optString(0).orEmpty()
            val tScore = titleScore(title, title, candidateTitle)
            if (tScore < 0) continue
            if (!crossrefAuthorMatches(author, item.optJSONArray("author"))) continue
            val type = item.optString("type")
            val typeBonus = if (type in setOf("book", "monograph", "reference-book", "book-chapter")) 10 else 0
            val subjectBonus = (item.optJSONArray("subject")?.length() ?: 0).coerceAtMost(5)
            val abstractBonus = if (item.optString("abstract").isNotBlank()) 10 else 0
            val score = tScore * 10 + typeBonus + subjectBonus + abstractBonus
            if (score > bestScore) {
                best = item
                bestScore = score
            }
        }

        val item = best ?: return JSONObject()
        val facts = JSONObject()
            .put("trusted_match", true)
            .put("source", "crossref")

        item.optJSONArray("title")?.optString(0)?.trim()?.takeIf { it.isNotBlank() }?.let {
            facts.put("canonical_title", it)
        }
        item.optJSONArray("subject")?.takeIf { it.length() > 0 }?.let { facts.put("subjects", it) }

        val rawAbstract = item.optString("abstract").trim()
        if (rawAbstract.isNotBlank()) {
            val clean = rawAbstract
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (clean.isNotBlank()) facts.put("description", clean.take(4000))
        }

        val year = crossrefYear(item)
        if (year > 0) facts.put("year_published", year)
        return facts
    }

    private fun crossrefYear(item: JSONObject): Int {
        for (key in listOf("published-print", "published", "issued", "created")) {
            val parts = item.optJSONObject(key)?.optJSONArray("date-parts")?.optJSONArray(0)
            val year = parts?.optInt(0, 0) ?: 0
            if (year > 0) return year
        }
        return 0
    }

    private fun mergeGrounded(result: JSONObject, facts: JSONObject) {
        if (!facts.optBoolean("trusted_match", false)) return

        facts.optString("openlibrary_work_id").trim().takeIf { it.isNotBlank() }?.let {
            result.put("openlibrary_work_id", it)
        }
        if (result.optInt("year_published", 0) <= 0) {
            facts.optInt("year_published", 0).takeIf { it > 0 }?.let { result.put("year_published", it) }
        }
        if (result.optString("summary").isBlank()) {
            facts.optString("description").trim().takeIf { it.isNotBlank() }?.let { result.put("summary", it) }
        }

        val existingGenres = result.optJSONArray("genre") ?: JSONArray()
        if (existingGenres.length() == 0) {
            val subjects = facts.optJSONArray("subjects") ?: JSONArray()
            val mapped = mappedGenres(subjects)
            if (mapped.isNotEmpty()) result.put("genre", JSONArray(mapped.take(3)))
        }

        if (result.optJSONArray("keywords")?.length() ?: 0 == 0) {
            val subjects = facts.optJSONArray("subjects") ?: JSONArray()
            val keywords = JSONArray()
            for (i in 0 until minOf(subjects.length(), 6)) {
                subjects.optString(i).trim().takeIf { it.isNotBlank() }?.let(keywords::put)
            }
            if (keywords.length() > 0) result.put("keywords", keywords)
        }
    }

    private fun addSource(result: JSONObject, source: String) {
        val sources = result.optJSONArray("_metadata_sources") ?: JSONArray().also {
            result.put("_metadata_sources", it)
        }
        if ((0 until sources.length()).none { sources.optString(it) == source }) sources.put(source)
    }

    private fun mappedGenres(subjects: JSONArray): List<String> {
        val terms = buildList {
            for (i in 0 until subjects.length()) {
                subjects.optString(i).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }.map(::normalize)
        val result = linkedSetOf<String>()
        if (terms.any { "psychology" in it || "psychologie" in it }) result += "Psychologie"
        if (terms.any { "biography" in it || "autobiography" in it || "biografie" in it }) result += "Biografie"
        if (terms.any { "business" in it || "career" in it || "economics" in it || "management" in it }) result += "Wirtschaft"
        if (terms.any { "self help" in it || "selfhelp" in it || "conduct of life" in it }) result += "Sachbuch"
        if (terms.any { "history" in it || "geschichte" in it }) result += "Geschichte"
        if (terms.any { "fiction" in it || "novel" in it || "roman" in it }) result += "Belletristik"
        if (terms.any { "philosophy" in it || "philosophie" in it }) result += "Philosophie"
        if (terms.any { "science" in it || "wissenschaft" in it }) result += "Wissenschaft"
        if (terms.any { "family" in it || "families" in it }) result += "Familie"
        return result.toList()
    }

    private fun titleVariants(value: String): List<String> {
        val clean = value.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank()) return emptyList()
        val values = linkedSetOf(clean)
        val words = clean.split(' ').filter { it.isNotBlank() }
        for (i in 0 until words.lastIndex) {
            val copy = words.toMutableList()
            copy[i] = copy[i] + copy[i + 1]
            copy.removeAt(i + 1)
            values += copy.joinToString(" ")
        }
        if (words.size >= 6) {
            for (count in listOf(7, 6, 5, 4, 3)) {
                if (count < words.size) values += words.take(count).joinToString(" ")
            }
        }
        return values.take(12)
    }

    private fun titleScore(original: String, variant: String, candidate: String): Int {
        val originalNormalized = normalize(original)
        val variantNormalized = normalize(variant)
        val candidateNormalized = normalize(candidate)
        if (candidateNormalized.isBlank()) return -1
        if (candidateNormalized == originalNormalized) return 6
        if (compact(candidateNormalized) == compact(originalNormalized)) return 5
        if (candidateNormalized == variantNormalized) return 5
        if (compact(candidateNormalized) == compact(variantNormalized)) return 4

        val a = originalNormalized.split(' ').filter { it.length > 1 }.toSet()
        val b = candidateNormalized.split(' ').filter { it.length > 1 }.toSet()
        if (a.isEmpty() || b.isEmpty()) return -1
        val common = a.intersect(b).size
        val coverage = common.toDouble() / minOf(a.size, b.size)
        return when {
            coverage >= 0.85 -> 3
            coverage >= 0.70 && common >= 3 -> 2
            coverage >= 0.60 && common >= 4 -> 1
            else -> -1
        }
    }

    private fun authorsMatch(author: String, names: JSONArray?): Boolean {
        if (names == null) return false
        val wanted = compact(normalize(author))
        for (i in 0 until names.length()) {
            if (compact(normalize(names.optString(i))) == wanted) return true
        }
        return false
    }

    private fun crossrefAuthorMatches(author: String, authors: JSONArray?): Boolean {
        if (authors == null) return false
        val wanted = compact(normalize(author))
        for (i in 0 until authors.length()) {
            val item = authors.optJSONObject(i) ?: continue
            val candidate = listOf(item.optString("given"), item.optString("family"))
                .filter { it.isNotBlank() }
                .joinToString(" ")
            if (compact(normalize(candidate)) == wanted) return true
        }
        return false
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
