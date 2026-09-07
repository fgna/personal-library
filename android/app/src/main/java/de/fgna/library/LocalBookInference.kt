package de.fgna.library

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import de.fgna.androidllmservice.ILlmCallback
import de.fgna.androidllmservice.ILlmService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object LocalBookInference {
    private const val TIMEOUT_SECONDS = 180L
    @Volatile private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    fun isReady(): Boolean = runCatching { withService { it.isModelReady } }.getOrDefault(false)

    fun activeModelName(): String = runCatching { withService { it.activeModelName } }.getOrDefault("")

    fun identify(imagePath: String): String {
        val image = File(imagePath)
        require(image.isFile && image.length() > 0L) { "Bilddatei fehlt." }
        return withService { service ->
            val initial = generateWithImage(service, image, identifyPrompt())
            if (!needsAuthorRecovery(initial)) {
                initial
            } else {
                val recovery = runCatching {
                    generateWithImage(service, image, authorRecoveryPrompt())
                }.getOrNull()
                if (recovery.isNullOrBlank()) initial else mergeAuthorRecovery(initial, recovery)
            }
        }
    }

    fun enrich(prompt: String): String =
        withService { service -> awaitResult { callback -> service.generate(prompt, callback) } }

    private fun generateWithImage(service: ILlmService, image: File, prompt: String): String =
        ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            awaitResult { callback -> service.generateWithImage(prompt, descriptor, callback) }
        }

    private fun needsAuthorRecovery(raw: String): Boolean {
        val parsed = parseObject(raw) ?: return false
        val author = parsed.optString("author", "").trim()
        val candidates = stringValues(parsed.optJSONArray("author_candidates"))
        val confidence = parsed.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
        return author.isBlank() ||
            candidates.size != 1 ||
            confidence < 0.98 ||
            looksLikeReviewAttribution(author)
    }

    private fun mergeAuthorRecovery(initialRaw: String, recoveryRaw: String): String {
        val initial = parseObject(initialRaw) ?: return initialRaw
        val recovery = parseObject(recoveryRaw) ?: return initialRaw
        val recoveredAuthor = recovery.optString("author", "").trim()

        val names = linkedSetOf<String>()
        if (recoveredAuthor.isNotBlank()) names += recoveredAuthor
        stringValues(recovery.optJSONArray("author_candidates")).forEach(names::add)
        stringValues(initial.optJSONArray("author_candidates")).forEach(names::add)
        initial.optString("author", "").trim().takeIf { it.isNotBlank() }?.let(names::add)

        initial.put("author_candidates", JSONArray(names.toList()))
        if (recoveredAuthor.isNotBlank()) initial.put("author", recoveredAuthor)
        return initial.toString()
    }

    private fun parseObject(raw: String): JSONObject? = runCatching {
        val clean = raw
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start < 0 || end <= start) return@runCatching null
        JSONObject(clean.substring(start, end + 1))
    }.getOrNull()

    private fun stringValues(values: JSONArray?): List<String> {
        if (values == null) return emptyList()
        val result = linkedSetOf<String>()
        for (i in 0 until values.length()) {
            values.optString(i).trim().takeIf { it.isNotBlank() }?.let(result::add)
        }
        return result.toList()
    }

    private fun looksLikeReviewAttribution(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        if (',' in value) return true
        return listOf(
            "daily mail", "sunday times", "new york times", "financial times",
            "guardian", "telegraph", "independent", "washington post", "wall street journal",
            "magazine", "review", "zeitung", "press"
        ).any(lower::contains)
    }

    private fun <T> withService(block: (ILlmService) -> T): T {
        val context = checkNotNull(appContext) { "Android LLM Service context not initialized." }
        val latch = CountDownLatch(1)
        val serviceRef = AtomicReference<ILlmService?>()
        val errorRef = AtomicReference<Throwable?>()
        lateinit var connection: ServiceConnection
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                serviceRef.set(ILlmService.Stub.asInterface(binder))
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
            override fun onNullBinding(name: ComponentName?) {
                errorRef.set(IllegalStateException("Android LLM Service returned null binding."))
                latch.countDown()
            }
        }
        val intent = Intent("de.fgna.androidllmservice.BIND").apply {
            component = ComponentName("de.fgna.androidllmservice", "de.fgna.androidllmservice.LlmBinderService")
        }
        check(context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) { "Android LLM Service ist nicht verfügbar." }
        try {
            check(latch.await(15, TimeUnit.SECONDS)) { "Zeitüberschreitung beim Verbinden mit Android LLM Service." }
            errorRef.get()?.let { throw it }
            val service = checkNotNull(serviceRef.get()) { "Android LLM Service konnte nicht verbunden werden." }
            check(service.isModelReady) { "Im Android LLM Service ist kein Modell bereit." }
            return block(service)
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    private fun awaitResult(start: (ILlmCallback) -> Unit): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>()
        val error = AtomicReference<Throwable?>()
        start(object : ILlmCallback.Stub() {
            override fun onSuccess(text: String?, initializationMillis: Long, generationMillis: Long, coldStart: Boolean) {
                result.set(text.orEmpty())
                latch.countDown()
            }
            override fun onError(code: String?, message: String?) {
                error.set(IllegalStateException(listOfNotNull(code, message).joinToString(": ")))
                latch.countDown()
            }
        })
        check(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "LLM-Anfrage hat zu lange gedauert." }
        error.get()?.let { throw it }
        return result.get().orEmpty().trim()
    }

    private fun identifyPrompt(): String = """
        Du liest ein Foto eines einzelnen physischen Buches oder Buchrückens.
        Arbeite wie OCR: Extrahiere ausschließlich Text und Personennamen, die auf dem Foto wirklich sichtbar sind.
        Erfinde keine Namen oder Metadaten und ergänze insbesondere keinen Autor aus deinem Weltwissen.

        Antworte ausschließlich mit genau einem JSON-Objekt ohne Markdown:
        {
          "title": "sichtbarer Buchtitel in normaler Schreibweise",
          "author": "plausibelster sichtbarer Autor oder leerer String",
          "author_candidates": ["alle auf dem Buch lesbaren Personennamen"],
          "language": "Sprache der fotografierten konkreten Ausgabe oder leerer String",
          "confidence": 0.0
        }

        Regeln:
        - Lies zuerst systematisch alle Textbereiche des Buches: oberen Rand, Autorenzeile über dem Titel, Titel, Untertitel sowie Zitat-/Rezensionszeilen unten.
        - Führe vor der Antwort einen zweiten visuellen Kontrollblick unmittelbar oberhalb, unterhalb und neben dem Haupttitel durch. Kleine Autorenzeilen dürfen nicht von größeren Zitat- oder Rezensionsnamen verdrängt werden.
        - title muss der eigentliche Buchtitel sein, nicht Verlag, Werbespruch, Zitat, Reihenlogo oder Unterzeile einer Rezension.
        - Gib title in üblicher Schreibweise zurück. Übernimm reine GROSSSCHREIBUNG des Covers nicht, wenn normale Groß-/Kleinschreibung eindeutig ist.
        - author_candidates enthält ALLE tatsächlich lesbaren Personennamen auf dem Buch, unabhängig davon, ob sie Autor, Rezensent oder zitierte Person sind. Die Liste ist eine reine Sichtbarkeitsliste.
        - Kopiere Namen buchstabengetreu vom Foto. Erfinde oder vervollständige keine Namen. Ein Name, der nicht sichtbar ist, darf niemals in author_candidates oder author stehen.
        - Bevorzuge für author einen Namen, der typografisch als Autorenzeile direkt oberhalb oder nahe beim Titel steht.
        - Namen aus Rezensionen, Zitaten, Presseangaben oder Empfehlungen dürfen in author_candidates stehen, aber NICHT als author gewählt werden. Warnsignale sind Anführungszeichen sowie Zusätze wie Zeitung, Magazin, Daily Mail, Sunday Times, New York Times o. Ä.
        - Wenn mehrere Namen sichtbar sind und ihre Rolle unklar ist, setze author auf den plausibelsten sichtbaren Kandidaten oder leer; erfinde niemals eine weitere Alternative.
        - language bezeichnet die Sprache dieser fotografierten Ausgabe. Nutze dafür ALLE sichtbaren sprachlichen Hinweise auf dem Cover oder Buchrücken, insbesondere Untertitel, Klappentext-Fragmente, Reihen-/Verlagszusätze und sonstige lesbare Wörter; ein Eigenname oder sprachneutraler Titel allein reicht nicht.
        - Wenn die Sprache der konkreten Ausgabe anhand des sichtbaren Texts nicht belastbar bestimmbar ist, setze language auf einen leeren String. Rate nicht anhand von Autor, Originalwerk oder Weltwissen.
        - confidence liegt zwischen 0 und 1 und bewertet gemeinsam die Sicherheit von Titel und author. Bei unklarem Autor muss confidence deutlich sinken.
        - Wenn kein Titel sicher lesbar ist, setze title auf einen leeren String.
    """.trimIndent()

    private fun authorRecoveryPrompt(): String = """
        Dies ist ein zweiter visueller OCR-Kontrolllauf für dasselbe Foto eines physischen Buches.
        Ignoriere vollständig dein Weltwissen darüber, wer ein bestimmtes Buch geschrieben hat.
        Verwende ausschließlich Buchstaben und typografische Rollen, die du auf dem Foto tatsächlich sehen kannst.

        Ziel: Prüfe besonders sorgfältig die kleine Autorenzeile in unmittelbarer Nähe des Haupttitels. Lies auch alle anderen sichtbaren Personennamen, aber unterscheide Autorenzeilen von Namen in Zitaten, Rezensionen, Presseangaben und Empfehlungen.

        Antworte ausschließlich mit genau einem JSON-Objekt ohne Markdown:
        {
          "author": "sichtbarer Name aus der eigentlichen Autorenzeile oder leerer String",
          "author_candidates": ["alle tatsächlich sichtbaren Personennamen"],
          "confidence": 0.0
        }

        Regeln:
        - Untersuche gezielt den Bereich direkt oberhalb, unterhalb und neben dem Haupttitel, auch wenn die Schrift dort kleiner ist als Zitat- oder Werbetext.
        - Ein Name in oder neben einem Zitat, in Anführungszeichen oder zusammen mit einer Zeitung/Magazin/Pressequelle ist ein Rezensent oder Empfehlungsgeber und darf nicht als author gewählt werden.
        - author muss buchstabengetreu auf dem Foto sichtbar sein. Erfinde, korrigiere oder vervollständige keinen Namen aus Wissen über das Buch.
        - author_candidates ist nur eine Liste sichtbarer Namen; dort dürfen auch Rezensenten stehen.
        - Wenn keine eigentliche Autorenzeile sicher erkennbar ist, setze author auf einen leeren String.
        - confidence bewertet nur die visuelle Sicherheit der Autorenzuordnung.
    """.trimIndent()
}
