package de.fgna.library

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

object DuplicateMatcher {
    private data class Match(val score: Double, val reason: String)
    private data class PreparedBook(
        val index: Int,
        val book: JSONObject,
        val title: String,
        val titleTokens: Set<String>,
        val author: String,
        val authorTokens: Set<String>,
        val authorSurname: String,
        val workId: String?,
        val year: Int,
    )

    fun findGroups(books: JSONArray): JSONArray {
        val items = (0 until books.length()).mapNotNull { index ->
            books.optJSONObject(index)?.let { book ->
                val title = normalize(book.optString("title"))
                val author = normalize(book.optString("author"))
                PreparedBook(
                    index = index,
                    book = book,
                    title = title,
                    titleTokens = tokens(title),
                    author = author,
                    authorTokens = tokens(author),
                    authorSurname = author.split(' ').lastOrNull().orEmpty(),
                    workId = openLibraryWorkId(book),
                    year = book.optInt("year_published", -1),
                )
            }
        }
        val parent = IntArray(items.size) { it }
        val matches = mutableMapOf<Pair<Int, Int>, Match>()

        fun root(x: Int): Int {
            var n = x
            while (parent[n] != n) {
                parent[n] = parent[parent[n]]
                n = parent[n]
            }
            return n
        }
        fun union(a: Int, b: Int) {
            val ra = root(a)
            val rb = root(b)
            if (ra != rb) parent[rb] = ra
        }

        for (a in items.indices) {
            for (b in a + 1 until items.size) {
                val match = compare(items[a], items[b]) ?: continue
                matches[a to b] = match
                union(a, b)
            }
        }

        val clusters = linkedMapOf<Int, MutableList<Int>>()
        items.indices.forEach { clusters.getOrPut(root(it)) { mutableListOf() }.add(it) }
        val result = JSONArray()
        clusters.values.filter { it.size > 1 }.forEach { cluster ->
            var best = Match(0.0, "similar metadata")
            for (i in cluster.indices) for (j in i + 1 until cluster.size) {
                val a = minOf(cluster[i], cluster[j])
                val b = maxOf(cluster[i], cluster[j])
                val m = matches[a to b]
                if (m != null && m.score > best.score) best = m
            }
            val entries = JSONArray()
            cluster.forEach { position ->
                val item = items[position]
                val book = item.book
                entries.put(JSONObject().apply {
                    put("index", item.index)
                    put("title", book.optString("title"))
                    put("author", book.optString("author"))
                    put("year_published", nullable(book, "year_published"))
                    put("language", book.optString("language"))
                    put("openlibrary_work_id", nullable(book, "openlibrary_work_id"))
                    put("has_summary", book.optString("summary").isNotBlank())
                    put("book", JSONObject(book.toString()))
                })
            }
            val first = items[cluster.first()].book
            result.put(JSONObject().apply {
                put("title", first.optString("title"))
                put("author", first.optString("author"))
                put("confidence", best.score.coerceIn(0.0, 1.0))
                put("reason", best.reason)
                put("entries", entries)
            })
        }
        return result
    }

    private fun compare(a: PreparedBook, b: PreparedBook): Match? {
        if (a.workId != null && b.workId != null && a.workId == b.workId) {
            return Match(1.0, "same Open Library work ID")
        }

        if (a.title.isBlank() || b.title.isBlank()) return null

        val yearCompatible = a.year <= 0 || b.year <= 0 || kotlin.math.abs(a.year - b.year) <= 2
        if (!yearCompatible) return null

        val titleThreshold = if (a.author.isBlank() || b.author.isBlank()) 0.92 else 0.78
        if (!canReachSimilarity(a.title, a.titleTokens, b.title, b.titleTokens, titleThreshold)) return null

        if (a.author.isNotBlank() && b.author.isNotBlank() &&
            !canReachAuthorSimilarity(a, b, 0.58)
        ) return null

        val title = stringSimilarity(a.title, a.titleTokens, b.title, b.titleTokens)
        val author = authorSimilarity(a, b)

        val accepted = when {
            a.author.isBlank() || b.author.isBlank() -> title >= 0.92
            else -> title >= 0.78 && author >= 0.58
        }
        if (!accepted) return null

        val score = if (a.author.isBlank() || b.author.isBlank()) title * 0.92 else title * 0.72 + author * 0.28
        return Match(score, "title ${percent(title)}, author ${percent(author)}")
    }

    private fun openLibraryWorkId(book: JSONObject): String? {
        if (!book.has("openlibrary_work_id") || book.isNull("openlibrary_work_id")) return null
        val raw = book.optString("openlibrary_work_id", "").trim()
        if (raw.isBlank()) return null
        if (raw.equals("null", true) || raw.equals("none", true) || raw.equals("undefined", true)) return null
        val id = raw.substringAfterLast('/').uppercase(Locale.ROOT)
        return id.takeIf { OPEN_LIBRARY_WORK_ID.matches(it) }
    }

    private fun canReachAuthorSimilarity(a: PreparedBook, b: PreparedBook, threshold: Double): Boolean {
        if (a.author == b.author) return true
        if (a.authorSurname.length >= 3 && a.authorSurname == b.authorSurname) return true
        return canReachSimilarity(a.author, a.authorTokens, b.author, b.authorTokens, threshold)
    }

    private fun authorSimilarity(a: PreparedBook, b: PreparedBook): Double {
        if (a.author.isBlank() || b.author.isBlank()) return 0.0
        if (a.author == b.author) return 1.0
        val surnameBoost = if (a.authorSurname.length >= 3 && a.authorSurname == b.authorSurname) 0.88 else 0.0
        return max(stringSimilarity(a.author, a.authorTokens, b.author, b.authorTokens), surnameBoost)
    }

    private fun canReachSimilarity(
        a: String,
        aTokens: Set<String>,
        b: String,
        bTokens: Set<String>,
        threshold: Double,
    ): Boolean {
        if (a == b) return true

        if (tokenJaccard(aTokens, bTokens) >= threshold) return true
        if (threshold <= 0.9 && (a.contains(b) || b.contains(a)) && minOf(a.length, b.length) >= 8) return true

        val maxLength = max(a.length, b.length).coerceAtLeast(1)
        val maxDistance = floor((1.0 - threshold) * maxLength).toInt()
        if (kotlin.math.abs(a.length - b.length) > maxDistance) return false
        return levenshteinAtMost(a, b, maxDistance) <= maxDistance
    }

    private fun stringSimilarity(a: String, aTokens: Set<String>, b: String, bTokens: Set<String>): Double {
        if (a == b) return 1.0
        val token = tokenJaccard(aTokens, bTokens)
        val distance = levenshtein(a, b)
        val chars = 1.0 - distance.toDouble() / max(a.length, b.length).coerceAtLeast(1)
        val containment = if ((a.contains(b) || b.contains(a)) && minOf(a.length, b.length) >= 8) 0.9 else 0.0
        return max(max(token, chars), containment).coerceIn(0.0, 1.0)
    }

    private fun tokens(value: String): Set<String> = value.split(' ').filter { it.isNotBlank() }.toSet()

    private fun tokenJaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val small = if (a.size <= b.size) a else b
        val large = if (a.size <= b.size) b else a
        var intersection = 0
        small.forEach { if (it in large) intersection++ }
        val union = a.size + b.size - intersection
        return intersection.toDouble() / union
    }

    private fun levenshteinAtMost(a: String, b: String, maxDistance: Int): Int {
        if (maxDistance < 0) return maxDistance + 1
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        if (kotlin.math.abs(a.length - b.length) > maxDistance) return maxDistance + 1

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            var rowMin = current[0]
            for (j in b.indices) {
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (a[i] == b[j]) 0 else 1,
                )
                if (current[j + 1] < rowMin) rowMin = current[j + 1]
            }
            if (rowMin > maxDistance) return maxDistance + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (a[i] == b[j]) 0 else 1,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .lowercase(Locale.ROOT)
        .replace("&", " and ")
        .replace("[^a-z0-9]+".toRegex(), " ")
        .replace("\\s+".toRegex(), " ")
        .trim()

    private fun nullable(book: JSONObject, key: String): Any = if (!book.has(key) || book.isNull(key)) JSONObject.NULL else book.get(key)
    private fun percent(value: Double) = "${(value * 100).toInt()}%"

    private val OPEN_LIBRARY_WORK_ID = Regex("^OL[0-9]+W$")
}
