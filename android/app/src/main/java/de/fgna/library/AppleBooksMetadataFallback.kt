package de.fgna.library

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.text.Normalizer
import java.util.Locale

internal object AppleBooksMetadataFallback {
    private const val USER_AGENT = "PersonalLibrary/0.1"

    fun apply(recognized: JSONObject, input: JSONObject): JSONObject {
        val result = JSONObject(input.toString())
        if (!result.optBoolean("_identity_verified", false)) return result
        if (result.optString("summary", "").trim().isNotBlank()) return result

        val title = result.optString("title").trim().ifBlank { recognized.optString("title").trim() }
        val author = result.optString("author").trim().ifBlank { recognized.optString("author").trim() }
        if (title.isBlank() || author.isBlank()) return result

        val diagnostics = result.optJSONObject("_metadata_diagnostics") ?: JSONObject().also {
            result.put("_metadata_diagnostics", it)
        }

        val facts = runCatching { lookup(title, author) }
            .getOrElse { JSONObject().put("source_error", it.message ?: "Apple Books lookup failed") }

        if (!facts.optBoolean("trusted_match", false)) {
            diagnostics.put(
                "apple_books",
                if (facts.has("source_error")) facts.optString("source_error") else "no matching book with description",
            )
            return result
        }

        val description = facts.optString("description").trim()
        if (description.isNotBlank()) result.put("summary", description)
        if (result.optInt("year_published", 0) <= 0) {
            facts.optInt("year_published", 0).takeIf { it > 0 }?.let { result.put("year_published", it) }
        }

        val sources = result.optJSONArray("_metadata_sources") ?: JSONArray().also {
            result.put("_metadata_sources", it)
        }
        if ((0 until sources.length()).none { sources.optString(it) == "Apple Books" }) sources.put("Apple Books")
        diagnostics.put("apple_books", "match")
        return result
    }

    private fun lookup(title: String, author: String): JSONObject {
        val term = enc("$title $author")
        val root = getJson("https://itunes.apple.com/search?term=$term&media=ebook&entity=ebook&country=us&limit=20")
        val items = root.optJSONArray("results") ?: JSONArray()

        var best: JSONObject? = null
        var bestScore = -1
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (!nameMatches(author, item.optString("artistName"))) continue
            val score = titleScore(title, item.optString("trackName"))
            if (score < 0) continue
            val description = cleanDescription(item.optString("description"))
            if (description.isBlank()) continue
            if (score > bestScore) {
                best = item
                bestScore = score
            }
        }

        val item = best ?: return JSONObject()
        return JSONObject().apply {
            put("trusted_match", true)
            put("description", cleanDescription(item.optString("description")).take(4000))
            Regex("(?:18|19|20)\\d{2}")
                .find(item.optString("releaseDate"))
                ?.value
                ?.toIntOrNull()
                ?.let { put("year_published", it) }
        }
    }

    private fun titleScore(expected: String, candidate: String): Int {
        val wanted = normalize(expected)
        val actual = normalize(candidate)
        if (wanted.isBlank() || actual.isBlank()) return -1
        if (wanted == actual) return 100
        if (compact(wanted) == compact(actual)) return 95

        val wantedTokens = wanted.split(' ').filter { it.length > 1 }
        val actualTokens = actual.split(' ').filter { it.length > 1 }.toSet()
        if (wantedTokens.size < 2) return -1
        if (actual.startsWith("$wanted ")) return 90
        if (wantedTokens.all(actualTokens::contains)) return 80
        return -1
    }

    private fun nameMatches(expected: String, candidate: String): Boolean {
        val wanted = normalize(expected)
        val actual = normalize(candidate)
        if (wanted.isBlank() || actual.isBlank()) return false
        if (compact(wanted) == compact(actual)) return true

        val wantedTokens = wanted.split(' ').filter { it.length > 1 }.toSet()
        if (wantedTokens.size < 2) return false
        val actualTokens = actual.split(' ').filter { it.length > 1 }.toSet()
        return wantedTokens.all(actualTokens::contains)
    }

    private fun cleanDescription(value: String): String = value
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace(Regex("\\s+"), " ")
        .trim()

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
            require(status in 200..299) { "Apple Books HTTP $status" }
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .lowercase(Locale.ROOT)
        .replace("[^a-z0-9]+".toRegex(), " ")
        .trim()

    private fun compact(value: String): String = value.replace(" ", "")
}
