package de.fgna.library

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.ParcelFileDescriptor
import de.fgna.androidllmservice.ILlmCallback
import de.fgna.androidllmservice.ILlmService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object LocalBookInference {
    private const val TIMEOUT_SECONDS = 180L
    @Volatile private var appContext: Context? = null

    private enum class RequestKind { TEXT, IMAGE }

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
                val recoveries = focusedAuthorRecoveries(service, image)
                if (recoveries.isEmpty()) initial else mergeAuthorRecoveries(initial, recoveries)
            }
        }
    }

    fun enrich(prompt: String): String =
        withService { service ->
            awaitResult(RequestKind.TEXT) { callback -> service.generate(prompt, callback) }
        }

    private fun generateWithImage(service: ILlmService, image: File, prompt: String): String =
        ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            awaitResult(RequestKind.IMAGE) { callback -> service.generateWithImage(prompt, descriptor, callback) }
        }

    private fun focusedAuthorRecoveries(service: ILlmService, image: File): List<String> {
        val crops = createAuthorCrops(image)
        if (crops.isEmpty()) {
            return listOfNotNull(
                runCatching { generateWithImage(service, image, authorRecoveryPrompt("gesamtes Cover")) }.getOrNull()
            ).filter { it.isNotBlank() }
        }

        return try {
            buildList {
                val top = crops.firstOrNull { it.first == "oberer Coverbereich" }
                if (top != null) {
                    runCatching { generateWithImage(service, top.second, authorRecoveryPrompt(top.first)) }
                        .getOrNull()?.takeIf { it.isNotBlank() }?.let(::add)
                }
                val bottom = crops.firstOrNull { it.first == "unterer Coverbereich" }
                if (bottom != null) {
                    runCatching { generateWithImage(service, bottom.second, authorRecoveryPrompt(bottom.first)) }
                        .getOrNull()?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        } finally {
            crops.forEach { (_, file) -> file.delete() }
        }
    }

    private fun createAuthorCrops(image: File): List<Pair<String, File>> {
        val context = appContext ?: return emptyList()
        val bitmap = BitmapFactory.decodeFile(image.absolutePath) ?: return emptyList()
        return try {
            if (bitmap.width < 64 || bitmap.height < 64) return emptyList()
            val cropHeight = (bitmap.height * 0.45f).toInt().coerceIn(1, bitmap.height)
            val top = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, cropHeight)
            val bottomY = (bitmap.height - cropHeight).coerceAtLeast(0)
            val bottom = Bitmap.createBitmap(bitmap, 0, bottomY, bitmap.width, cropHeight)
            listOfNotNull(
                writeCrop(context.cacheDir, "author-top", top)?.let { "oberer Coverbereich" to it },
                writeCrop(context.cacheDir, "author-bottom", bottom)?.let { "unterer Coverbereich" to it },
            ).also {
                if (top !== bitmap) top.recycle()
                if (bottom !== bitmap) bottom.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeCrop(cacheDir: File, prefix: String, bitmap: Bitmap): File? = runCatching {
        val directory = File(cacheDir, "book-author-crops").apply { mkdirs() }
        val file = File(directory, "$prefix-${System.nanoTime()}.jpg")
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output)) { "Autor-Crop konnte nicht gespeichert werden." }
        }
        require(file.length() > 0L) { "Autor-Crop ist leer." }
        file
    }.getOrNull()

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

    private fun mergeAuthorRecoveries(initialRaw: String, recoveryRaws: List<String>): String {
        val initial = parseObject(initialRaw) ?: return initialRaw
        val recoveries = recoveryRaws.mapNotNull(::parseObject)
        if (recoveries.isEmpty()) return initialRaw

        val names = linkedSetOf<String>()
        recoveries.forEach { recovery ->
            val recoveredAuthor = recovery.optString("author", "").trim()
            if (recoveredAuthor.isNotBlank()) names += recoveredAuthor
            stringValues(recovery.optJSONArray("author_candidates")).forEach(names::add)
        }
        stringValues(initial.optJSONArray("author_candidates")).forEach(names::add)
        initial.optString("author", "").trim().takeIf { it.isNotBlank() }?.let(names::add)
        initial.put("author_candidates", JSONArray(names.toList()))

        val focusedAuthor = recoveries
            .asSequence()
            .map { it.optString("author", "").trim() }
            .firstOrNull { it.isNotBlank() && !looksLikeReviewAttribution(it) }
        if (!focusedAuthor.isNullOrBlank()) {
            initial.put("author", focusedAuthor)
            initial.put("confidence", recoveries.firstOrNull {
                it.optString("author", "").trim() == focusedAuthor
            }?.optDouble("confidence", initial.optDouble("confidence", 0.0)) ?: initial.optDouble("confidence", 0.0))
        }
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
        check(context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            "Android LLM Service ist nicht installiert oder nicht verfügbar."
        }
        try {
            check(latch.await(15, TimeUnit.SECONDS)) {
                "Zeitüberschreitung beim Verbinden mit Android LLM Service."
            }
            errorRef.get()?.let { throw it }
            val service = checkNotNull(serviceRef.get()) {
                "Android LLM Service konnte nicht verbunden werden."
            }
            check(service.isModelReady) {
                "Im Android LLM Service ist kein Modell bereit. Bitte dort zuerst ein Modell auswählen oder importieren."
            }
            return block(service)
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    private fun awaitResult(kind: RequestKind, start: (ILlmCallback) -> Unit): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>()
        val error = AtomicReference<Throwable?>()
        start(object : ILlmCallback.Stub() {
            override fun onSuccess(text: String?, initializationMillis: Long, generationMillis: Long, coldStart: Boolean) {
                result.set(text.orEmpty())
                latch.countDown()
            }

            override fun onError(code: String?, message: String?) {
                error.set(IllegalStateException(serviceErrorMessage(kind, code, message)))
                latch.countDown()
            }
        })
        check(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            if (kind == RequestKind.IMAGE) {
                "Die Bildanalyse im Android LLM Service hat zu lange gedauert."
            } else {
                "Die LLM-Anfrage hat zu lange gedauert."
            }
        }
        error.get()?.let { throw it }
        return result.get().orEmpty().trim()
    }

    private fun serviceErrorMessage(kind: RequestKind, code: String?, message: String?): String {
        val cleanCode = code.orEmpty().trim().uppercase(Locale.ROOT)
        val cleanMessage = message.orEmpty().trim()
        return when (cleanCode) {
            "MODEL_NOT_READY" ->
                "Im Android LLM Service ist kein Modell bereit. Bitte dort zuerst ein Modell auswählen oder importieren."
            "INVALID_REQUEST" ->
                "Ungültige Anfrage an Android LLM Service${detail(cleanMessage)}"
            "INFERENCE_FAILED" -> if (kind == RequestKind.IMAGE) {
                "Bildinferenz fehlgeschlagen. Das aktive Modell unterstützt möglicherweise keine Bildverarbeitung oder konnte diese Bildanfrage nicht ausführen${detail(cleanMessage)}"
            } else {
                "LLM-Inferenz fehlgeschlagen${detail(cleanMessage)}"
            }
            else -> {
                val prefix = if (cleanCode.isBlank()) "Android LLM Service Fehler" else "Android LLM Service Fehler $cleanCode"
                "$prefix${detail(cleanMessage)}"
            }
        }
    }

    private fun detail(message: String): String = if (message.isBlank()) "." else ": $message"

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

    private fun authorRecoveryPrompt(region: String): String = """
        Dies ist ein fokussierter visueller OCR-Kontrolllauf auf dem $region eines physischen Buchcovers.
        Der Ausschnitt wurde absichtlich vergrößert, damit kleine Namen lesbar werden.
        Ignoriere vollständig dein Weltwissen darüber, wer ein bestimmtes Buch geschrieben hat.
        Verwende ausschließlich Buchstaben und typografische Rollen, die du in DIESEM Ausschnitt tatsächlich sehen kannst.

        Antworte ausschließlich mit genau einem JSON-Objekt ohne Markdown:
        {
          "author": "sichtbarer Name aus einer eigentlichen Autorenzeile oder leerer String",
          "author_candidates": ["alle tatsächlich sichtbaren Personennamen in diesem Ausschnitt"],
          "confidence": 0.0
        }

        Regeln:
        - Suche systematisch nach einer eigenständigen Autorenzeile, besonders an Außenrändern sowie oberhalb oder unterhalb des Titels.
        - Ein Name in oder neben einem Zitat, in Anführungszeichen oder zusammen mit einer Zeitung/Magazin/Pressequelle ist ein Rezensent oder Empfehlungsgeber und darf nicht als author gewählt werden.
        - Kurze Lobzeilen oder Rezensionen sind keine Autorenzeilen, auch wenn der Personenname typografisch auffällig ist.
        - author muss buchstabengetreu in diesem Ausschnitt sichtbar sein. Erfinde, korrigiere oder vervollständige keinen Namen aus Wissen über das Buch.
        - author_candidates ist nur eine Liste sichtbarer Namen; dort dürfen auch Rezensenten stehen.
        - Wenn keine eigentliche Autorenzeile sicher erkennbar ist, setze author auf einen leeren String.
        - confidence bewertet nur die visuelle Sicherheit der Autorenzuordnung in diesem Ausschnitt.
    """.trimIndent()
}
