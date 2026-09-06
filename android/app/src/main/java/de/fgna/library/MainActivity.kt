package de.fgna.library

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("library", MODE_PRIVATE) }
    private var pendingCapture: File? = null
    private var pendingRecognition: JSONObject? = null
    private var pendingMetadata: JSONObject? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deleteLegacyLocalModel()

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            addJavascriptInterface(BookSourceBridge(), "AndroidBookSource")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
            }
        }

        setContentView(webView)
        loadApp()
    }

    private fun loadApp() {
        webView.loadUrl("https://appassets.androidplatform.net/assets/www/mobile.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode == RESULT_OK) {
                val path = data?.getStringExtra(BookCameraActivity.EXTRA_OUTPUT_PATH)
                val file = path?.let(::File) ?: pendingCapture
                if (file != null && file.isFile && file.length() > 0L) {
                    identifyCapturedBook(file)
                } else {
                    evaluate("window.__bookScanResult && window.__bookScanResult(null, ${JSONObject.quote("Die Kamera hat kein Bild gespeichert.")});")
                }
            } else {
                val error = data?.getStringExtra(BookCameraActivity.EXTRA_ERROR) ?: "Kameraaufnahme abgebrochen."
                evaluate("window.__bookScanResult && window.__bookScanResult(null, ${JSONObject.quote(error)});")
            }
            return
        }

        if (requestCode == REQUEST_PICK_BOOK_IMAGE) {
            if (resultCode == RESULT_OK) {
                val uri = data?.data
                if (uri != null) importBookPhoto(uri)
                else evaluate("window.__bookScanResult && window.__bookScanResult(null, ${JSONObject.quote("Kein Foto ausgewählt.")});")
            } else {
                evaluate("window.__bookPhotoSelectionCancelled && window.__bookPhotoSelectionCancelled();")
            }
            return
        }

        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_IMPORT -> data?.data?.let(::importBooksJson)
            REQUEST_EXPORT -> data?.data?.let(::exportBooksJson)
        }
    }

    private fun importBooksJson(uri: Uri) {
        ioExecutor.execute {
            runCatching {
                val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("Could not read selected JSON file")
                validateJson(text)
                cacheFile().writeText(text, Charsets.UTF_8)
                prefs.edit().putBoolean(PREF_MANUAL_OVERRIDE, true).apply()
            }.onSuccess {
                runOnUiThread { loadApp() }
            }.onFailure { error ->
                showNativeError(error.message ?: "JSON import failed")
            }
        }
    }

    private fun exportBooksJson(uri: Uri) {
        ioExecutor.execute {
            runCatching {
                val text = activeJsonForExport()
                contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
                    ?: error("Could not write selected file")
            }.onFailure { error ->
                showNativeError(error.message ?: "JSON export failed")
            }
        }
    }

    private fun importBookPhoto(uri: Uri) {
        evaluate("window.__bookScanStatus && window.__bookScanStatus('running', null);")
        ioExecutor.execute {
            runCatching {
                val directory = File(cacheDir, "book-captures").apply { mkdirs() }
                val output = File(directory, "book-import-${System.currentTimeMillis()}.jpg")
                contentResolver.openInputStream(uri)?.use { input ->
                    output.outputStream().use { target -> input.copyTo(target) }
                } ?: error("Das ausgewählte Foto konnte nicht gelesen werden.")
                require(output.isFile && output.length() > 0L) { "Das ausgewählte Foto ist leer." }
                output
            }.onSuccess { file ->
                identifyCapturedBook(file)
            }.onFailure { error ->
                evaluate("window.__bookScanResult && window.__bookScanResult(null, ${JSONObject.quote(error.message ?: "Foto konnte nicht eingelesen werden")});")
            }
        }
    }

    private fun identifyCapturedBook(file: File) {
        pendingRecognition = null
        pendingMetadata = null
        evaluate("window.__bookScanStatus && window.__bookScanStatus('running', null);")
        ioExecutor.execute {
            runCatching {
                val raw = LocalBookInference.identify(file.absolutePath)
                val recognized = parseRecognition(raw)
                pendingRecognition = JSONObject(recognized.toString())
                evaluate("window.__bookScanStatus && window.__bookScanStatus('checking', null);")
                val enriched = BookMetadataEnricher.enrich(
                    recognized = recognized,
                    catalogJson = activeJsonForExport(),
                    userConfirmedIdentity = false,
                )
                ScannedMetadataPostProcessor.apply(recognized, enriched)
            }.onSuccess { result ->
                if (result.optBoolean("_identity_verified", false)) {
                    pendingMetadata = result
                    sendEncodedCallback("__bookScanResult", result)
                } else {
                    pendingMetadata = null
                    sendEncodedCallback("__bookIdentityResult", result)
                }
            }.onFailure { error ->
                pendingRecognition = null
                pendingMetadata = null
                evaluate("window.__bookScanResult && window.__bookScanResult(null, ${JSONObject.quote(error.message ?: "Bucherkennung fehlgeschlagen")});")
            }
        }
    }

    private fun enrichConfirmedBook(title: String, author: String) {
        val cleanTitle = title.trim()
        val cleanAuthor = author.trim()
        require(cleanTitle.isNotBlank()) { "Titel darf nicht leer sein." }

        val recognized = pendingRecognition?.let { JSONObject(it.toString()) } ?: JSONObject()
        recognized.put("title", cleanTitle)
        recognized.put("author", cleanAuthor)
        recognized.put("author_candidates", JSONArray().apply {
            if (cleanAuthor.isNotBlank()) put(cleanAuthor)
        })

        ioExecutor.execute {
            runCatching {
                val enriched = BookMetadataEnricher.enrich(
                    recognized = recognized,
                    catalogJson = activeJsonForExport(),
                    userConfirmedIdentity = true,
                )
                ScannedMetadataPostProcessor.apply(recognized, enriched)
            }.onSuccess { result ->
                pendingRecognition = JSONObject(recognized.toString())
                pendingMetadata = result
                sendEncodedCallback("__bookMetadataResult", result)
            }.onFailure { error ->
                pendingMetadata = null
                evaluate("window.__bookMetadataResult && window.__bookMetadataResult(null, ${JSONObject.quote(error.message ?: "Metadaten konnten nicht ergänzt werden")});")
            }
        }
    }

    private fun parseRecognition(raw: String): JSONObject {
        val cleaned = raw
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        require(start >= 0 && end > start) { "Das Modell hat kein gültiges JSON zurückgegeben." }
        val result = JSONObject(cleaned.substring(start, end + 1))
        require(result.optString("title").isNotBlank()) { "Auf dem Foto wurde kein sicherer Buchtitel erkannt." }

        val candidates = JSONArray()
        val seen = linkedSetOf<String>()
        val rawCandidates = result.optJSONArray("author_candidates")
        if (rawCandidates != null) {
            for (i in 0 until rawCandidates.length()) {
                val value = rawCandidates.optString(i).trim()
                if (value.isNotBlank() && seen.add(value)) candidates.put(value)
            }
        }
        val author = result.optString("author", "").trim()
        if (author.isNotBlank() && seen.add(author)) candidates.put(author)
        result.put("author_candidates", candidates)
        result.put("author", author.ifBlank { candidates.optString(0, "") })
        result.put("language", result.optString("language", "").trim())
        val confidence = result.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
        result.put("confidence", confidence)
        return result
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    inner class BookSourceBridge {
        @JavascriptInterface
        fun getDefaultBooksUrl(): String = BuildConfig.BOOKS_URL

        @JavascriptInterface
        fun isManualOverride(): Boolean = prefs.getBoolean(PREF_MANUAL_OVERRIDE, false)

        @JavascriptInterface
        fun getLanguage(): String = prefs.getString(PREF_LANGUAGE, "") ?: ""

        @JavascriptInterface
        fun setLanguage(value: String): Boolean {
            val lang = value.lowercase(Locale.ROOT)
            if (lang != "en" && lang != "de") return false
            prefs.edit().putString(PREF_LANGUAGE, lang).apply()
            runOnUiThread { loadApp() }
            return true
        }

        @JavascriptInterface
        fun isLlmServiceReady(): Boolean = LocalBookInference.isReady()

        @JavascriptInterface
        fun getLlmServiceModelName(): String = LocalBookInference.activeModelName()

        @JavascriptInterface
        fun importBooks() {
            runOnUiThread {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                    }, REQUEST_IMPORT
                )
            }
        }

        @JavascriptInterface
        fun exportBooks() {
            runOnUiThread {
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, "books.json")
                    }, REQUEST_EXPORT
                )
            }
        }

        @JavascriptInterface
        fun captureBook() {
            runOnUiThread {
                val directory = File(cacheDir, "book-captures").apply { mkdirs() }
                val output = File(directory, "book-${System.currentTimeMillis()}.jpg")
                pendingCapture = output
                pendingRecognition = null
                pendingMetadata = null
                startActivityForResult(
                    Intent(this@MainActivity, BookCameraActivity::class.java).apply {
                        putExtra(BookCameraActivity.EXTRA_OUTPUT_PATH, output.absolutePath)
                    }, REQUEST_CAPTURE,
                )
            }
        }

        @JavascriptInterface
        fun chooseBookPhoto() {
            runOnUiThread {
                pendingCapture = null
                pendingRecognition = null
                pendingMetadata = null
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                    }, REQUEST_PICK_BOOK_IMAGE,
                )
            }
        }

        @JavascriptInterface
        fun enrichBookMetadata(title: String, author: String) {
            runCatching { enrichConfirmedBook(title, author) }
                .onFailure { error ->
                    evaluate("window.__bookMetadataResult && window.__bookMetadataResult(null, ${JSONObject.quote(error.message ?: "Metadaten konnten nicht ergänzt werden")});")
                }
        }

        @JavascriptInterface
        fun addRecognizedBook(title: String, author: String, force: Boolean): String {
            return runCatching {
                val root = JSONObject(activeJsonForExport())
                val books = root.getJSONArray("books")
                val duplicate = findDuplicate(books, title, author)
                if (duplicate != null && !force) {
                    return@runCatching JSONObject()
                        .put("ok", false)
                        .put("duplicate", true)
                        .put("existingTitle", duplicate.optString("title"))
                        .put("existingAuthor", duplicate.optString("author"))
                        .toString()
                }

                val book = pendingMetadata?.let { JSONObject(it.toString()) } ?: newBook(title.trim(), author.trim())
                book.put("title", title.trim())
                book.put("author", author.trim())
                ensureCompleteSchema(book)
                books.put(book)
                saveCatalog(root)
                pendingRecognition = null
                pendingMetadata = null
                JSONObject().put("ok", true).put("duplicate", false).toString()
            }.getOrElse { error ->
                JSONObject().put("ok", false).put("error", error.message ?: "Buch konnte nicht gespeichert werden").toString()
            }
        }

        @JavascriptInterface
        fun findDuplicateBooks(): String {
            return runCatching {
                val books = JSONObject(activeJsonForExport()).getJSONArray("books")
                val groups = DuplicateMatcher.findGroups(books)
                var duplicateCount = 0
                for (i in 0 until groups.length()) {
                    duplicateCount += groups.getJSONObject(i).getJSONArray("entries").length() - 1
                }
                JSONObject()
                    .put("ok", true)
                    .put("groups", groups)
                    .put("groupCount", groups.length())
                    .put("duplicateCount", duplicateCount)
                    .toString()
            }.getOrElse { error ->
                JSONObject().put("ok", false).put("error", error.message ?: "Duplikatsuche fehlgeschlagen").toString()
            }
        }

        @JavascriptInterface
        fun deleteBookEntries(indicesJson: String): String {
            return runCatching {
                val indices = parseIndices(indicesJson)
                require(indices.isNotEmpty()) { "Keine Einträge ausgewählt." }

                val root = JSONObject(activeJsonForExport())
                val books = root.getJSONArray("books")
                indices.forEach { require(it < books.length()) { "Katalog wurde zwischenzeitlich geändert." } }
                indices.sortedDescending().forEach { books.remove(it) }
                saveCatalog(root)

                JSONObject().put("ok", true).put("deleted", indices.size).toString()
            }.getOrElse { error ->
                JSONObject().put("ok", false).put("error", error.message ?: "Einträge konnten nicht gelöscht werden").toString()
            }
        }

        @JavascriptInterface
        fun mergeBookEntries(indicesJson: String, mergedBookJson: String): String {
            return runCatching {
                val indices = parseIndices(indicesJson)
                require(indices.size >= 2) { "Mindestens zwei Einträge zum Zusammenführen auswählen." }
                val merged = JSONObject(mergedBookJson)
                require(merged.optString("title").isNotBlank()) { "Titel darf nicht leer sein." }
                ensureCompleteSchema(merged)

                val root = JSONObject(activeJsonForExport())
                val books = root.getJSONArray("books")
                indices.forEach { require(it < books.length()) { "Katalog wurde zwischenzeitlich geändert." } }
                indices.sortedDescending().forEach { books.remove(it) }
                books.put(merged)
                saveCatalog(root)

                JSONObject().put("ok", true).put("merged", indices.size).toString()
            }.getOrElse { error ->
                JSONObject().put("ok", false).put("error", error.message ?: "Einträge konnten nicht zusammengeführt werden").toString()
            }
        }

        @JavascriptInterface
        fun updateBookEntry(index: Int, bookJson: String): String {
            return runCatching {
                val root = JSONObject(activeJsonForExport())
                val books = root.getJSONArray("books")
                require(index in 0 until books.length()) { "Eintrag nicht gefunden." }

                val updated = JSONObject(bookJson)
                require(updated.optString("title").isNotBlank()) { "Titel darf nicht leer sein." }
                ensureCompleteSchema(updated)
                books.put(index, updated)
                saveCatalog(root)

                JSONObject().put("ok", true).toString()
            }.getOrElse { error ->
                JSONObject().put("ok", false).put("error", error.message ?: "Eintrag konnte nicht geändert werden").toString()
            }
        }

        @JavascriptInterface
        fun reloadLibrary() {
            runOnUiThread { loadApp() }
        }

        @JavascriptInterface
        fun useRemoteBooks() {
            prefs.edit().putBoolean(PREF_MANUAL_OVERRIDE, false).apply()
            runOnUiThread { loadApp() }
        }

        @JavascriptInterface
        fun requestBooks(url: String, requestId: String) {
            ioExecutor.execute {
                if (prefs.getBoolean(PREF_MANUAL_OVERRIDE, false)) {
                    val imported = runCatching { cacheFile().takeIf(File::isFile)?.readText(Charsets.UTF_8) }.getOrNull()
                    if (!imported.isNullOrBlank()) {
                        resolve(requestId, imported, null)
                        return@execute
                    }
                    prefs.edit().putBoolean(PREF_MANUAL_OVERRIDE, false).apply()
                }

                try {
                    val json = downloadAndValidate(url)
                    cacheFile().writeText(json, Charsets.UTF_8)
                    resolve(requestId, json, null)
                } catch (networkError: Exception) {
                    val cached = runCatching { cacheFile().takeIf(File::isFile)?.readText(Charsets.UTF_8) }.getOrNull()
                    if (!cached.isNullOrBlank()) {
                        resolve(requestId, cached, null)
                    } else {
                        resolve(requestId, null, networkError.message ?: "books.json sync failed")
                    }
                }
            }
        }

        private fun downloadAndValidate(value: String): String {
            val parsed = URL(value)
            require(parsed.protocol == "https" || parsed.protocol == "http") { "booksUrl must use http or https" }

            val connection = (parsed.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 12000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                useCaches = false
            }

            try {
                val status = connection.responseCode
                require(status in 200..299) { "books.json HTTP $status" }
                val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                validateJson(text)
                return text
            } finally {
                connection.disconnect()
            }
        }

        private fun resolve(requestId: String, json: String?, error: String?) {
            val base64 = json?.let {
                Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            }
            val script = "window.__bookSourceResolve(${JSONObject.quote(requestId)}, ${JSONObject.quote(base64)}, ${JSONObject.quote(error)});"
            runOnUiThread { webView.evaluateJavascript(script, null) }
        }
    }

    private fun sendEncodedCallback(functionName: String, payload: JSONObject) {
        val encoded = Base64.encodeToString(payload.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        evaluate("window.$functionName && window.$functionName(${JSONObject.quote(encoded)}, null);")
    }

    private fun parseIndices(indicesJson: String): LinkedHashSet<Int> {
        val requested = JSONArray(indicesJson)
        val indices = linkedSetOf<Int>()
        for (i in 0 until requested.length()) {
            val index = requested.optInt(i, -1)
            if (index >= 0) indices.add(index)
        }
        return indices
    }

    private fun saveCatalog(root: JSONObject) {
        cacheFile().writeText(root.toString(2), Charsets.UTF_8)
        prefs.edit().putBoolean(PREF_MANUAL_OVERRIDE, true).apply()
    }

    private fun findDuplicate(books: JSONArray, title: String, author: String): JSONObject? {
        val targetTitle = normalize(title)
        val targetAuthor = normalize(author)
        for (index in 0 until books.length()) {
            val book = books.optJSONObject(index) ?: continue
            if (normalize(book.optString("title")) != targetTitle) continue
            val existingAuthor = normalize(book.optString("author"))
            if (targetAuthor.isBlank() || existingAuthor.isBlank() || targetAuthor == existingAuthor) return book
        }
        return null
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .lowercase(Locale.ROOT)
        .replace("[^a-z0-9]+".toRegex(), " ")
        .trim()

    private fun newBook(title: String, author: String): JSONObject = JSONObject().apply {
        put("title", title)
        put("original_title", JSONObject.NULL)
        put("author", author)
        put("genre", JSONArray())
        put("language", "")
        put("keywords", JSONArray())
        put("summary", "")
        put("summary_en", JSONObject.NULL)
        put("read", JSONObject.NULL)
        put("year_published", JSONObject.NULL)
        put("main_idea", JSONObject.NULL)
        put("main_idea_en", JSONObject.NULL)
        put("openlibrary_work_id", JSONObject.NULL)
        put("wikipedia_url", JSONObject.NULL)
        put("original_language", JSONObject.NULL)
        put("country_of_origin", JSONObject.NULL)
        put("period", JSONObject.NULL)
        put("rating", JSONObject.NULL)
        put("mood", JSONArray())
        put("series", JSONObject.NULL)
    }

    private fun ensureCompleteSchema(book: JSONObject) {
        val defaults = newBook(book.optString("title"), book.optString("author"))
        val keys = defaults.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!book.has(key)) book.put(key, defaults.get(key))
        }
        book.remove("confidence")
        book.remove("_identity_verified")
        book.remove("_bibliographic_match")
        book.remove("_author_candidates")
        book.remove("_metadata_sources")
        book.remove("_metadata_diagnostics")
    }

    private fun validateJson(text: String) {
        JSONObject(text).getJSONArray("books")
    }

    private fun activeJsonForExport(): String {
        val cached = cacheFile().takeIf(File::isFile)?.readText(Charsets.UTF_8)
        if (!cached.isNullOrBlank()) {
            validateJson(cached)
            return cached
        }
        return assets.open("www/books.json").bufferedReader(Charsets.UTF_8).use { it.readText() }.also(::validateJson)
    }

    private fun cacheFile() = File(filesDir, "books-cache.json")

    private fun deleteLegacyLocalModel() {
        val directory = File(noBackupFilesDir, "local-book-model")
        runCatching { File(directory, "model.litertlm").delete() }
        runCatching { File(directory, "model.litertlm.part").delete() }
        runCatching { directory.delete() }
    }

    private fun showNativeError(message: String) {
        evaluate("alert(${JSONObject.quote(message)});")
    }

    private fun evaluate(script: String) {
        runOnUiThread { if (::webView.isInitialized) webView.evaluateJavascript(script, null) }
    }

    companion object {
        private const val REQUEST_IMPORT = 1001
        private const val REQUEST_EXPORT = 1002
        private const val REQUEST_CAPTURE = 1004
        private const val REQUEST_PICK_BOOK_IMAGE = 1005
        private const val PREF_MANUAL_OVERRIDE = "manual_books_override"
        private const val PREF_LANGUAGE = "ui_language"
    }
}
