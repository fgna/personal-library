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

        val sourcedSummary = result.optString("summary", "").trim()
        if (sourcedSummary.isBlank()) return result

        val localized = localizeGroundedText(
            title = result.optString("title"),
            author = result.optString("author"),
            sourceText = sourcedSummary,
        ) ?: return result

        val germanSummary = localized.optString("summary", "").trim()
        val mainIdea = localized.optString("main_idea", "").trim()
        if (germanSummary.isNotBlank()) result.put("summary", germanSummary)
        result.put("summary_en", JSONObject.NULL)
        if (mainIdea.isNotBlank()) result.put("main_idea", mainIdea)
        return result
    }

    private fun normalizeOcrTitle(value: String): String = value
        .trim()
        .replace(Regex("\\s+"), " ")
        .replace(Regex("(?<=\\d)(?=[A-Za-z])"), " ")
        .replace(Regex("(?<=[,.:;!?])(?=[A-Za-z])"), " ")

    private fun localizeGroundedText(title: String, author: String, sourceText: String): JSONObject? {
        val prompt = """
            Arbeite ausschließlich mit der folgenden verifizierten Quellenbeschreibung eines Buches.
            Erfinde keine Fakten und ergänze kein Weltwissen.

            Antworte ausschließlich mit genau einem JSON-Objekt ohne Markdown:
            {
              "summary": "eine knappe, gut lesbare deutsche Kurzbeschreibung in 2-4 Sätzen",
              "main_idea": "die zentrale Hauptthese/Kernidee auf Deutsch in genau einem kurzen Satz"
            }

            Regeln:
            - Übersetze bzw. verdichte den Quelltext ins Deutsche.
            - Alle Aussagen müssen durch den Quelltext gestützt sein.
            - Keine Formulierungen wie wahrscheinlich, vermutlich oder könnte.
            - Falls keine belastbare Kernidee ableitbar ist, setze main_idea auf einen leeren String.
            - summary darf keine zusätzlichen Fakten enthalten.

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
