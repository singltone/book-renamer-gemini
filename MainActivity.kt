package com.example.bookrenamer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Xml
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.util.Collections
import java.util.zip.ZipInputStream

// ---------- Дані ----------

data class Meta(
    val title: String?,
    val author: String?,
    val series: String?,
    val number: String?,
    val authorOrig: String? = null
)

data class Book(val uri: Uri, val name: String, val base: String, val ext: String, val meta: Meta) {
    val isSeries: Boolean
        get() = !meta.series.isNullOrBlank() || !meta.number.isNullOrBlank()
}

enum class Part(val label: String) {
    SERIES("Серія + номер"),
    TITLE("Назва"),
    AUTHOR("Автор")
}

data class Settings(
    val useSeriesName: Boolean = true,
    val useNumber: Boolean = true,
    val useTitle: Boolean = true,
    val useAuthor: Boolean = true,
    val origAuthor: Boolean = false,
    val online: Boolean = false,
    val ai: Boolean = false,
    val order: List<Part> = listOf(Part.SERIES, Part.TITLE, Part.AUTHOR),
    val sep: String = " - "
)

// ---------- Читання метаданих ----------

fun reorderFileAs(s: String): String {
    val i = s.indexOf(',')
    return if (i > 0) (s.substring(i + 1).trim() + " " + s.substring(0, i).trim()).trim() else s.trim()
}

fun parseOpf(bytes: ByteArray): Meta {
    var title: String? = null
    var author: String? = null
    var series: String? = null
    var number: String? = null
    var creatorId: String? = null
    var fileAs: String? = null
    var altScript: String? = null
    try {
        val p = Xml.newPullParser()
        p.setInput(bytes.inputStream(), null)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name.substringAfter(':')) {
                    "title" -> if (title == null) title = p.nextText().trim()
                    "creator" -> if (author == null) {
                        creatorId = p.getAttributeValue(null, "id")
                        fileAs = p.getAttributeValue(null, "opf:file-as")
                        author = p.nextText().trim()
                    }
                    "meta" -> {
                        val name = p.getAttributeValue(null, "name")
                        val prop = p.getAttributeValue(null, "property")
                        val refines = p.getAttributeValue(null, "refines")
                        val mine = creatorId != null && refines == "#$creatorId"
                        when {
                            name == "calibre:series" -> series = p.getAttributeValue(null, "content")
                            name == "calibre:series_index" -> number = p.getAttributeValue(null, "content")
                            prop == "belongs-to-collection" && series == null -> series = p.nextText().trim()
                            prop == "group-position" && number == null -> number = p.nextText().trim()
                            prop == "file-as" && mine -> fileAs = p.nextText().trim()
                            prop == "alternate-script" && mine -> {
                                val t = p.nextText().trim()
                                if (altScript == null) altScript = t
                            }
                        }
                    }
                }
            }
            ev = p.next()
        }
    } catch (e: Exception) {
        // повертаємо те, що встигли прочитати
    }
    val orig = altScript ?: fileAs?.let { reorderFileAs(it) }
    return Meta(title, author, series, number, orig?.takeIf { it.isNotBlank() && it != author })
}

fun parseEpub(ctx: Context, uri: Uri): Meta {
    ctx.contentResolver.openInputStream(uri)?.use { raw ->
        ZipInputStream(raw).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name.endsWith(".opf", true)) return parseOpf(zip.readBytes())
                e = zip.nextEntry
            }
        }
    }
    return Meta(null, null, null, null)
}

fun parseFb2(ctx: Context, uri: Uri): Meta {
    var title: String? = null
    var first: String? = null
    var last: String? = null
    var series: String? = null
    var number: String? = null
    var inAuthor = false
    var authorDone = false
    var sFirst: String? = null
    var sLast: String? = null
    var sInAuthor = false
    var sDone = false
    var sect = 0 // 1 = title-info, 2 = src-title-info (оригінал)
    try {
        ctx.contentResolver.openInputStream(uri)?.use { raw ->
            val p = Xml.newPullParser()
            p.setInput(raw, null)
            var ev = p.eventType
            loop@ while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "title-info" -> sect = 1
                        "src-title-info" -> sect = 2
                        "document-info", "publish-info", "body" -> break@loop
                        "author" -> {
                            if (sect == 1 && !authorDone) inAuthor = true
                            if (sect == 2 && !sDone) sInAuthor = true
                        }
                        "first-name" -> {
                            if (sect == 1 && inAuthor && first == null) first = p.nextText().trim()
                            else if (sect == 2 && sInAuthor && sFirst == null) sFirst = p.nextText().trim()
                        }
                        "last-name" -> {
                            if (sect == 1 && inAuthor && last == null) last = p.nextText().trim()
                            else if (sect == 2 && sInAuthor && sLast == null) sLast = p.nextText().trim()
                        }
                        "book-title" -> if (sect == 1 && title == null) title = p.nextText().trim()
                        "sequence" -> if (sect == 1 && series == null) {
                            series = p.getAttributeValue(null, "name")
                            number = p.getAttributeValue(null, "number")
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (p.name) {
                        "author" -> {
                            if (inAuthor) { inAuthor = false; authorDone = true }
                            if (sInAuthor) { sInAuthor = false; sDone = true }
                        }
                        "title-info", "src-title-info" -> sect = 0
                    }
                }
                ev = p.next()
            }
        }
    } catch (e: Exception) {
        // повертаємо те, що встигли прочитати
    }
    val author = listOfNotNull(first, last).joinToString(" ").ifBlank { null }
    val orig = listOfNotNull(sFirst, sLast).joinToString(" ").ifBlank { null }
    return Meta(title, author, series, number, orig)
}

val numberRegex = Regex("(?i)(?:книга|кн\\.?|book|bk|#|том|т\\.)\\s*(\\d{1,3})")

fun metaFromName(base: String): Meta {
    val n = numberRegex.find(base)?.groupValues?.get(1)
    return Meta(base, null, null, n)
}

fun loadBook(ctx: Context, uri: Uri, name: String): Book? {
    val dot = name.lastIndexOf('.')
    val ext = if (dot > 0) name.substring(dot + 1).lowercase() else ""
    val base = if (dot > 0) name.substring(0, dot) else name
    if (ext !in setOf("epub", "fb2", "pdf", "djvu", "djv")) return null
    val meta = try {
        when (ext) {
            "epub" -> parseEpub(ctx, uri)
            "fb2" -> parseFb2(ctx, uri)
            else -> metaFromName(base)
        }
    } catch (e: Exception) {
        Meta(null, null, null, null)
    }
    return Book(uri, name, base, ext, meta)
}

fun displayName(ctx: Context, uri: Uri): String {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) return c.getString(0) ?: ""
    }
    return uri.lastPathSegment ?: ""
}

// Повертає список пар: Pair(Uri_файлу, DocumentFile_батьківська_тека)
data class FileItem(val uri: Uri, val name: String, val parentDir: DocumentFile)

fun listFilesWithParents(ctx: Context, treeUri: Uri, recursive: Boolean): List<FileItem> {
    val res = mutableListOf<FileItem>()
    val rootDir = DocumentFile.fromTreeUri(ctx, treeUri) ?: return res

    fun walk(currentDir: DocumentFile) {
        for (item in currentDir.listFiles()) {
            if (item.isDirectory && recursive) {
                walk(item)
            } else if (item.isFile) {
                val name = item.name ?: continue
                res.add(FileItem(item.uri, name, currentDir))
            }
        }
    }
    walk(rootDir)
    return res
}

// Функція для розкладання файлів за теками форматів
fun sortFilesByFormat(ctx: Context, rootUri: Uri, recursive: Boolean, onLog: (String) -> Unit): Int {
    val rootDir = DocumentFile.fromTreeUri(ctx, rootUri) ?: return 0
    val allFiles = listFilesWithParents(ctx, rootUri, recursive)
    val createdFoldersCache = mutableMapOf<String, DocumentFile>()
    var movedCount = 0

    for (item in allFiles) {
        val fileName = item.name
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0) continue
        val ext = fileName.substring(dot + 1).uppercase()
        
        // Дозволяємо сортувати будь-які файли (або змінити фільтр за потреби)
        val targetFolderName = if (ext.isBlank()) "NO_EXT" else ext

        // Перевіряємо, чи файл вже лежить у цільовій теці формату в корені
        if (item.parentDir.uri == rootDir.uri) continue
        if (item.parentDir.name?.uppercase() == targetFolderName && item.parentDir.parentFile?.uri == rootDir.uri) continue

        // Знаходимо або створюємо теку формату в корені
        val targetDir = createdFoldersCache.getOrPut(targetFolderName) {
            rootDir.findFile(targetFolderName)?.takeIf { it.isDirectory }
                ?: rootDir.createDirectory(targetFolderName)!!
        }

        // Уникаємо конфліктів імен у цільовій теці
        var safeName = fileName
        var counter = 1
        val baseName = fileName.substringBeforeLast('.')
        while (targetDir.findFile(safeName) != null) {
            safeName = "${baseName}_$counter.$ext"
            counter++
        }
        if (safeName != fileName) {
            try {
                DocumentsContract.renameDocument(ctx.contentResolver, item.uri, safeName)
            } catch (e: Exception) {
                // пропускаємо при помилці перейменування конфлікту
            }
        }

        // Переміщуємо файл через DocumentsContract
        try {
            val moved = DocumentsContract.moveDocument(
                ctx.contentResolver,
                item.uri,
                item.parentDir.uri,
                targetDir.uri
            )
            if (moved != null) {
                movedCount++
                onLog("[$targetFolderName] <- $fileName")
            }
        } catch (e: Exception) {
            // помилка переміщення
        }
    }
    return movedCount
}

// Старий метод для звичайного пошуку книжок (для перейменування)
fun listFiles(ctx: Context, tree: Uri, recursive: Boolean): List<Pair<Uri, String>> {
    val res = mutableListOf<Pair<Uri, String>>()
    fun walk(docId: String) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val dirs = mutableListOf<String>()
        ctx.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1)
                val mime = c.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (recursive) dirs.add(id)
                } else {
                    res.add(DocumentsContract.buildDocumentUriUsingTree(tree, id) to name)
                }
            }
        }
        dirs.forEach { walk(it) }
    }
    walk(DocumentsContract.getTreeDocumentId(tree))
    return res
}

// ---------- Побудова нового імені ----------

fun pad(n: String?): String? {
    if (n == null) return null
    val t = n.trim()
    val d = t.replace(',', '.').toDoubleOrNull() ?: return t
    return if (d == Math.floor(d)) "%02d".format(d.toInt()) else t
}

fun clean(s: String): String =
    s.replace(Regex("[\\\\/:*?\"<>|]"), "").replace(Regex("\\s+"), " ").trim()

// ---------- ШІ та інтернет-пошук ----------

data class AiResult(val title: String?, val author: String?, val conf: String)

fun stripTags(html: String): String =
    html.replace(Regex("(?s)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&#160;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

fun extractText(ctx: Context, uri: Uri, ext: String, maxChars: Int = 6000): String {
    val sb = StringBuilder()
    try {
        ctx.contentResolver.openInputStream(uri)?.use { raw ->
            if (ext == "epub") {
                ZipInputStream(raw).use { zip ->
                    var e = zip.nextEntry
                    while (e != null && sb.length < maxChars) {
                        val n = e.name.lowercase()
                        if (n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm")) {
                            val text = stripTags(zip.readBytes().toString(Charsets.UTF_8))
                            if (text.length >= 20) sb.append(text.take(2500)).append("\n\n")
                        }
                        e = zip.nextEntry
                    }
                }
            } else if (ext == "fb2") {
                val p = Xml.newPullParser()
                p.setInput(raw, null)
                var inBody = false
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT && sb.length < maxChars) {
                    if (ev == XmlPullParser.START_TAG && p.name == "body") inBody = true
                    if (ev == XmlPullParser.TEXT && inBody) {
                        val t = p.text.trim()
                        if (t.isNotEmpty()) sb.append(t).append(' ')
                    }
                    ev = p.next()
                }
            }
        }
    } catch (e: Exception) {}
    return sb.toString().take(maxChars)
}

fun askClaude(apiKey: String, excerpt: String, fileName: String): AiResult? {
    if (excerpt.isBlank()) return null
    return try {
        val prompt = "Нижче уривок із книги (назва файлу: $fileName). Визначи, що це за книга. " +
            "Відповідай ТІЛЬКИ одним JSON-об'єктом без пояснень: " +
            "{\"title\": \"...\", \"author\": \"...\", \"confidence\": \"high|medium|low\"}. " +
            "Назву й автора пиши мовою цього видання (мовою уривка). " +
            "Якщо не знаєш цю книгу, постав confidence low і залиш поля порожніми.\n\n" + excerpt
        val body = org.json.JSONObject()
            .put("model", "claude-haiku-5-5")
            .put("max_tokens", 300)
            .put(
                "messages",
                org.json.JSONArray().put(
                    org.json.JSONObject().put("role", "user").put("content", prompt)
                )
            )
        val c = java.net.URL("https://api.anthropic.com/v1/messages").openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.doOutput = true
        c.setRequestProperty("x-api-key", apiKey.trim())
        c.setRequestProperty("anthropic-version", "2023-06-01")
        c.setRequestProperty("content-type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        if (c.responseCode !in 200..299) return null
        val resp = c.inputStream.bufferedReader().use { it.readText() }
        val reply = org.json.JSONObject(resp).getJSONArray("content").getJSONObject(0).getString("text")
        val j = org.json.JSONObject(reply.substring(reply.indexOf('{'), reply.lastIndexOf('}') + 1))
        val conf = j.optString("confidence", "low")
        if (conf == "low") null
        else AiResult(
            j.optString("title").trim().ifBlank { null },
            j.optString("author").trim().ifBlank { null },
            conf
        )
    } catch (e: Exception) {
        null
    }
}

fun httpGet(url: String): String {
    val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 8000
    c.setRequestProperty("User-Agent", "BookRenamer/1.0")
    return c.inputStream.bufferedReader().use { it.readText() }
}

fun lookupTitle(query: String): String? {
    if (query.isBlank()) return null
    val q = java.net.URLEncoder.encode(query, "UTF-8")
    try {
        val json = org.json.JSONObject(httpGet("https://www.googleapis.com/books/v1/volumes?q=$q&maxResults=1&printType=books"))
        val t = json.optJSONArray("items")?.optJSONObject(0)?.optJSONObject("volumeInfo")?.optString("title")
        if (!t.isNullOrBlank()) return t.trim()
    } catch (e: Exception) {}
    try {
        val json = org.json.JSONObject(httpGet("https://openlibrary.org/search.json?q=$q&limit=1&fields=title"))
        val t = json.optJSONArray("docs")?.optJSONObject(0)?.optString("title")
        if (!t.isNullOrBlank()) return t.trim()
    } catch (e: Exception) {}
    return null
}

fun onlineQuery(b: Book): String =
    (b.base.replace(Regex("\\(\\d+\\)"), " ").replace(Regex("[_\\-]+"), " ") + " " + (b.meta.author ?: ""))
        .replace(Regex("\\s+"), " ").trim()

val junkRegex = Regex("(?i)^(urn:uuid:)?[0-9a-f-]{16,}$")

fun looksLikeJunk(t: String): Boolean {
    val x = t.trim()
    if (junkRegex.matches(x)) return true
    return x.lowercase() in setOf("unknown", "untitled", "title", "без назви", "невідомо", "невідомий")
}

fun titleIsBad(b: Book): Boolean {
    val t = b.meta.title
    return t.isNullOrBlank() || looksLikeJunk(t)
}

fun newName(b: Book, s: Settings, onlineTitle: String? = null): String {
    val parts = s.order.mapNotNull { p ->
        when (p) {
            Part.SERIES -> if (!b.isSeries) null else listOfNotNull(
                if (s.useSeriesName) b.meta.series?.takeIf { it.isNotBlank() } else null,
                if (s.useNumber) pad(b.meta.number) else null
            ).joinToString(" ").ifBlank { null }
            Part.TITLE -> if (s.useTitle) (b.meta.title?.takeIf { it.isNotBlank() && !looksLikeJunk(it) } ?: onlineTitle ?: b.base) else null
            Part.AUTHOR -> if (!s.useAuthor) null else {
                val orig = if (
