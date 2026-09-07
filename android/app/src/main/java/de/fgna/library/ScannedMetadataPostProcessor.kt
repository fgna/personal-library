package de.fgna.library

import org.json.JSONObject
import java.util.Locale

internal object ScannedMetadataPostProcessor {
    fun apply(recognized: JSONObject, enriched: JSONObject): JSONObject {
        val normalizedRecognized = JSONObject(recognized.toString()).apply {
            val title = normalizeOcrTitle(optString("title", ""))
            if (title.isNotBlank()) put("title", title)
        }
        val normalizedEnriched = JSONObject(enriched.toString()).apply {
            val title = normalizeOcrTitle(optString("title", ""))
            if (title.isNotBlank()) put("title", title)
        }

        val fallback = RobustBookMetadataFallback.apply(normalizedRecognized, normalizedEnriched)
        val result = SparseBookMetadataAugmenter.apply(normalizedRecognized, fallback)

        val visibleLanguage = normalizeLanguage(recognized.optString("language", "").trim())
        result.put("language", visibleLanguage)

        val sourcedGenres = BookGenreTaxonomy.sanitize(result.optJSONArray("genre"))
        result.put("genre", sourcedGenres)

        val sourcedSummary = result.optString("summary", "").trim()
        if (sourcedSummary.isBlank()) return result

        val localized = localizeGroundedText(
            title = result.optString("title"),
            author = result.optString("author"),
            sourceText = sourcedSummary,
        ) ?: return result

        val germanSummary = localized.optString("summary", "").trim()
        val mainIdea = normalizeMainIdea(localized.optString("main_idea", ""))
        val localizedGenres = BookGenreTaxonomy.sanitize(localized.optJSONArray("genres"))
        val genres = BookGenreTaxonomy.merge(sourcedGenres, localizedGenres)

        if (germanSummary.isNotBlank()) result.put("summary", germanSummary)
        result.put("summary_en", JSONObject.NULL)
        result.put("genre", genres)
        result.put("main_idea", if (mainIdea.isNotBlank()) mainIdea else JSONObject.NULL)
        return result
    }

    private fun normalizeOcrTitle(value: String): String = value
        .trim()
        .replace(Regex("\\s+"), " ")
        .replace(Regex("(?<=\\d)(?=[A-Za-z])"), " ")
        .replace(Regex("(?<=[,.:;!?])(?=[A-Za-z])"), " ")

    private fun normalizeMainIdea(value: String): String {
        val clean = value.trim()
        if (clean.isBlank()) return ""
        val lower = clean.lowercase(Locale.ROOT)
        val metaStarters = listOf(
            "das buch ",
            "dieses buch ",
            "der autor ",
            "die autorin ",
            "der text ",
            "in diesem buch ",
            "in dem buch ",
        )
        return if (metaStarters.any { lower.startsWith(it) }) "" else clean
    }

    private fun localizeGroundedText(title: String, author: String, sourceText: String): JSONObject? {
        val prompt = """
            Arbeite ausschließlich mit der folgenden verifizierten Quellenbeschreibung eines Buches.
            Erfinde keine Fakten und ergänze kein Weltwissen.

            Antworte ausschließlich mit genau einem JSON-Objekt ohne Markdown:
            {
              "summary": "eine knappe, gut lesbare deutsche Kurzbeschreibung in 2-4 Sätzen",
              "main_idea": "die zentrale Hauptthese/Kernidee auf Deutsch in genau einem kurzen Satz",
              "genres": ["null bis zwei Kategorien aus dem erlaubten Katalog"]
            }

            Regeln:
            - Übersetze bzw. verdichte den Quelltext ins Deutsche.
            - Alle Aussagen und Genre-Zuordnungen müssen durch den Quelltext gestützt sein.
            - Keine Formulierungen wie wahrscheinlich, vermutlich oder könnte.
            - main_idea muss die inhaltliche These direkt aussprechen, als eigenständige Aussage.
            - main_idea darf NICHT mit Formulierungen wie "Das Buch", "Dieses Buch", "Der Autor", "Die Autorin", "Der Text" oder "In diesem Buch" beginnen.
            - Falls keine belastbare Kernidee ableitbar ist, setze main_idea auf einen leeren String.
            - summary darf keine zusätzlichen Fakten enthalten.
            - genres darf höchstens zwei Werte enthalten.
            - Für genres sind ausschließlich diese exakten Werte erlaubt: ${BookGenreTaxonomy.promptList()}.
            - Erfinde keine weitere Genre-Bezeichnung. Wenn keine Kategorie belastbar passt, gib ein leeres Array zurück.

            Titel: $title
            Autor: $author
            Quellenbeschreibung:
            ${sourceText.take(3500)}
        """.trimIndent()

        return runCatching {
            val raw = LocalBookInference.enrich(prompt)
                .replace("```json", "", ignoreCase = true)
                .replace("```", "")
                .trim()
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return@runCatching null
            JSONObject(raw.substring(start, end + 1))
        }.getOrNull()
    }

    private fun normalizeLanguage(value: String): String = when (value.trim().lowercase(Locale.ROOT)) {
        "de", "deu", "ger", "deutsch", "german" -> "Deutsch"
        "en", "eng", "english", "englisch" -> "English"
        "fr", "fra", "fre", "français", "französisch", "french" -> "Französisch"
        "es", "spa", "español", "spanisch", "spanish" -> "Spanisch"
        "it", "ita", "italiano", "italienisch", "italian" -> "Italienisch"
        else -> ""
    }
}
