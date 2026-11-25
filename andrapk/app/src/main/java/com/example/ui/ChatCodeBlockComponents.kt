package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

sealed class ChatContentSegment {
    data class TextSegment(val text: String) : ChatContentSegment()
    data class CodeBlockSegment(
        val index: Int,
        val language: String,
        val suggestedFileName: String,
        val code: String,
        val isPreviewable: Boolean
    ) : ChatContentSegment()
}

data class CodePreviewPayload(
    val code: String,
    val fileName: String,
    val language: String = "html",
    val onFileNameChange: ((String) -> Unit)? = null
)

/**
 * Formatter Markdown ringan (O(1) fast-path untuk teks biasa) agar bubble chat
 * merender **bold**, *italic*, `inline code`, heading, dan bullet list dengan rapi
 * tanpa pernah menampilkan token mentah ** atau tag <thought> internal.
 */
object ChatMarkdownFormatter {
    private val THOUGHT_BLOCK_REGEX = Regex("<thought>[\\s\\S]*?</thought>", RegexOption.IGNORE_CASE)
    private val HEADING_REGEX = Regex("^#{1,4}\\s+(.+)$")
    private val BULLET_REGEX = Regex("^(\\s*)[-*]\\s+(.+)$")
    private val INLINE_TOKEN_REGEX = Regex("(`[^`\\n]+`|\\*\\*[^*\\n]+\\*\\*|(?<!\\*)\\*[^*\\n]+\\*(?!\\*))")

    fun format(rawText: String): AnnotatedString {
        if (rawText.isEmpty()) return AnnotatedString("")

        val cleaned = if (rawText.contains("<thought", ignoreCase = true)) {
            THOUGHT_BLOCK_REGEX.replace(rawText, "").trim()
        } else {
            rawText
        }
        if (cleaned.isEmpty()) return AnnotatedString("")

        val needsMarkdown = cleaned.contains('*') ||
            cleaned.contains('`') ||
            cleaned.contains('#') ||
            cleaned.contains("\n- ") ||
            cleaned.startsWith("- ")

        if (!needsMarkdown) return AnnotatedString(cleaned)

        val lines = cleaned.lines()
        return buildAnnotatedString {
            lines.forEachIndexed { idx, rawLine ->
                if (idx > 0) append('\n')
                val headingMatch = HEADING_REGEX.matchEntire(rawLine.trim())
                if (headingMatch != null) {
                    withStyle(
                        SpanStyle(
                            fontWeight = FontWeight.SemiBold,
                            color = JarvisTextPrimary,
                            fontSize = 14.5.sp
                        )
                    ) {
                        appendInlineMarkdown(headingMatch.groupValues[1])
                    }
                } else {
                    val bulletMatch = BULLET_REGEX.matchEntire(rawLine)
                    if (bulletMatch != null) {
                        val indent = bulletMatch.groupValues[1]
                        append(indent)
                        withStyle(SpanStyle(color = JarvisCyan, fontWeight = FontWeight.SemiBold)) {
                            append("• ")
                        }
                        appendInlineMarkdown(bulletMatch.groupValues[2])
                    } else {
                        appendInlineMarkdown(rawLine)
                    }
                }
            }
        }
    }

    private fun AnnotatedString.Builder.appendInlineMarkdown(line: String) {
        var cursor = 0
        for (match in INLINE_TOKEN_REGEX.findAll(line)) {
            val start = match.range.first
            if (start > cursor) {
                append(line.substring(cursor, start))
            }
            val token = match.value
            when {
                token.startsWith("`") && token.endsWith("`") && token.length >= 2 -> {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            color = JarvisCyan,
                            background = GlassInlineCodeBg,
                            fontSize = 12.sp
                        )
                    ) {
                        append(" ${token.substring(1, token.length - 1)} ")
                    }
                }
                token.startsWith("**") && token.endsWith("**") && token.length >= 4 -> {
                    withStyle(
                        SpanStyle(
                            fontWeight = FontWeight.SemiBold,
                            color = JarvisTextPrimary
                        )
                    ) {
                        append(token.substring(2, token.length - 2))
                    }
                }
                token.startsWith("*") && token.endsWith("*") && token.length >= 2 -> {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(token.substring(1, token.length - 1))
                    }
                }
                else -> append(token)
            }
            cursor = match.range.last + 1
        }
        if (cursor < line.length) {
            append(line.substring(cursor))
        }
    }
}

/**
 * Parser berkinerja tinggi (O(1) fast-path untuk pesan teks biasa) yang memisahkan
 * teks percakapan biasa dan blok kode markdown ke dalam segmen terpisah.
 * Mendukung penamaan otomatis dari header fence seperti ```html:index.html atau title="app.py".
 */
object ChatMessageParser {
    private val FENCE_OPEN_REGEX = Regex("^\\s*```([^\\r\\n]*)")
    private val FENCE_CLOSE_REGEX = Regex("^\\s*```\\s*$")
    private val ATTR_FILENAME_REGEX = Regex("(?:title|filename|file|name)\\s*=\\s*[\"']?([^\"'\\s]+)[\"']?", RegexOption.IGNORE_CASE)
    private val COMMENT_FILENAME_REGEX = Regex(
        "^(?:<!--|//|#|--)\\s*(?:file(?:name)?:\\s*)?([A-Za-z0-9_.-]+\\.[A-Za-z0-9]{1,8})\\s*(?:-->)?\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val SAFE_FILE_TOKEN_REGEX = Regex("^[A-Za-z0-9_.-]+\\.[A-Za-z0-9]{1,10}$")

    fun parse(rawText: String): List<ChatContentSegment> {
        if (rawText.isEmpty()) return emptyList()

        // Fast-path: hindari alokasi parser bila pesan tidak mengandung fence atau HTML/SVG mentah
        val hasFence = rawText.contains("```")
        val hasRawWeb = !hasFence && (
            rawText.contains("<!doctype", ignoreCase = true) ||
                rawText.contains("<html", ignoreCase = true) ||
                (rawText.contains("<svg", ignoreCase = true) && rawText.contains("</svg>", ignoreCase = true))
            )

        if (!hasFence && !hasRawWeb) {
            return listOf(ChatContentSegment.TextSegment(rawText))
        }

        if (!hasFence && hasRawWeb) {
            return splitRawWebDocument(rawText)
        }

        val segments = mutableListOf<ChatContentSegment>()
        val usedFileNames = mutableSetOf<String>()
        var blockIndex = 0

        val lines = rawText.lines()
        val textBuffer = StringBuilder()
        var inCodeBlock = false
        var currentHeader = ""
        val codeBuffer = StringBuilder()

        fun flushTextBuffer() {
            val prose = textBuffer.toString().trim('\n', '\r')
            if (prose.isNotBlank()) {
                segments.add(ChatContentSegment.TextSegment(prose))
            }
            textBuffer.setLength(0)
        }

        fun flushCodeBlock() {
            val codeText = codeBuffer.toString().trimEnd('\n', '\r')
            val (lang, explicitName) = parseFenceHeader(currentHeader)
            val inferredName = explicitName ?: extractFileNameFromFirstLine(codeText)
            val normalizedLang = normalizeLanguage(lang, inferredName, codeText)
            val baseName = inferredName ?: defaultFileNameForLanguage(normalizedLang)
            val uniqueName = deduplicateFileName(baseName, usedFileNames)
            val previewable = isPreviewableCode(normalizedLang, codeText)

            segments.add(
                ChatContentSegment.CodeBlockSegment(
                    index = blockIndex++,
                    language = normalizedLang,
                    suggestedFileName = uniqueName,
                    code = codeText,
                    isPreviewable = previewable
                )
            )
            codeBuffer.setLength(0)
            currentHeader = ""
        }

        for (line in lines) {
            if (!inCodeBlock) {
                val openMatch = FENCE_OPEN_REGEX.find(line)
                if (openMatch != null) {
                    val trailing = openMatch.groupValues[1]
                    // Tangani kasus inline ```code``` dalam satu baris bila ada penutup di baris yang sama
                    val inlineCloseIdx = trailing.indexOf("```")
                    if (inlineCloseIdx >= 0) {
                        if (textBuffer.isNotEmpty()) textBuffer.append('\n')
                        textBuffer.append(line)
                    } else {
                        flushTextBuffer()
                        inCodeBlock = true
                        currentHeader = trailing.trim()
                        codeBuffer.setLength(0)
                    }
                } else {
                    if (textBuffer.isNotEmpty()) textBuffer.append('\n')
                    textBuffer.append(line)
                }
            } else {
                if (FENCE_CLOSE_REGEX.matches(line)) {
                    flushCodeBlock()
                    inCodeBlock = false
                } else {
                    if (codeBuffer.isNotEmpty()) codeBuffer.append('\n')
                    codeBuffer.append(line)
                }
            }
        }

        if (inCodeBlock) {
            // Fence belum tertutup (mis. output terpotong): tetap tampilkan rapi sebagai box kode
            flushCodeBlock()
        } else {
            flushTextBuffer()
        }

        return if (segments.isEmpty()) listOf(ChatContentSegment.TextSegment(rawText)) else segments
    }

    private fun splitRawWebDocument(rawText: String): List<ChatContentSegment> {
        val doctypeIdx = rawText.indexOf("<!doctype", ignoreCase = true)
        val htmlIdx = rawText.indexOf("<html", ignoreCase = true)
        val svgIdx = rawText.indexOf("<svg", ignoreCase = true)

        val isSvgOnly = doctypeIdx < 0 && htmlIdx < 0 && svgIdx >= 0
        val startIdx = when {
            doctypeIdx >= 0 -> doctypeIdx
            htmlIdx >= 0 -> htmlIdx
            svgIdx >= 0 -> svgIdx
            else -> return listOf(ChatContentSegment.TextSegment(rawText))
        }

        val closeTag = if (isSvgOnly) "</svg>" else "</html>"
        val endTagIdx = rawText.lastIndexOf(closeTag, ignoreCase = true)
        val endIdx = if (endTagIdx > startIdx) endTagIdx + closeTag.length else rawText.length

        val before = rawText.substring(0, startIdx).trim()
        val code = rawText.substring(startIdx, endIdx).trim()
        val after = if (endIdx < rawText.length) rawText.substring(endIdx).trim() else ""

        val result = mutableListOf<ChatContentSegment>()
        if (before.isNotBlank()) result.add(ChatContentSegment.TextSegment(before))
        result.add(
            ChatContentSegment.CodeBlockSegment(
                index = 0,
                language = if (isSvgOnly) "svg" else "html",
                suggestedFileName = if (isSvgOnly) "graphic.svg" else "index.html",
                code = code,
                isPreviewable = true
            )
        )
        if (after.isNotBlank()) result.add(ChatContentSegment.TextSegment(after))
        return result
    }

    private fun parseFenceHeader(header: String): Pair<String, String?> {
        if (header.isBlank()) return "" to null

        // Cek atribut eksplisit seperti title="index.html" atau filename="script.py"
        val attrMatch = ATTR_FILENAME_REGEX.find(header)
        val attrFileName = attrMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }

        val tokens = header.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return "" to attrFileName

        val firstToken = tokens[0]
        // Pola ```html:landing.html atau ```python:bot.py
        val colonIdx = firstToken.indexOf(':')
        if (colonIdx > 0 && colonIdx < firstToken.lastIndex) {
            val lang = firstToken.substring(0, colonIdx).trim()
            val name = firstToken.substring(colonIdx + 1).trim()
            return lang to (attrFileName ?: name.takeIf { it.isNotBlank() })
        }

        // Pola ```html: landing.html (spasi setelah titik dua)
        val cleanedFirstToken = firstToken.trimEnd(':', ',', ';')

        // Pola ```index.html (langsung nama file pada token pertama)
        if (SAFE_FILE_TOKEN_REGEX.matches(cleanedFirstToken)) {
            val ext = cleanedFirstToken.substringAfterLast('.', "")
            return ext to (attrFileName ?: cleanedFirstToken)
        }

        // Pola ```python script.py atau ```html: landing.html (token kedua berupa nama file)
        val secondFileName = tokens.drop(1).firstOrNull { token ->
            !token.contains('=') && SAFE_FILE_TOKEN_REGEX.matches(token.trim('"', '\''))
        }?.trim('"', '\'')

        return cleanedFirstToken to (attrFileName ?: secondFileName)
    }

    private fun extractFileNameFromFirstLine(code: String): String? {
        val firstLine = code.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        val match = COMMENT_FILENAME_REGEX.find(firstLine) ?: return null
        return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun normalizeLanguage(rawLang: String, fileName: String?, code: String): String {
        val cleaned = rawLang.trim().trimEnd(':', ',', ';').lowercase(Locale.ROOT)
        if (cleaned.isNotBlank()) {
            return when (cleaned) {
                "htm", "html5", "xhtml" -> "html"
                "py" -> "python"
                "js" -> "javascript"
                "ts" -> "typescript"
                "kt", "kts" -> "kotlin"
                "sh", "shell", "zsh" -> "bash"
                "yml" -> "yaml"
                "md" -> "markdown"
                else -> cleaned
            }
        }
        val ext = fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        if (ext.isNotBlank()) {
            return normalizeLanguage(ext, null, code)
        }
        return when {
            code.contains("<!doctype", true) || code.contains("<html", true) -> "html"
            code.contains("<svg", true) && code.contains("</svg>", true) -> "svg"
            code.trimStart().startsWith("{") && code.trimEnd().endsWith("}") -> "json"
            else -> "code"
        }
    }

    fun defaultFileNameForLanguage(language: String): String {
        return when (language.lowercase(Locale.ROOT)) {
            "html", "htm" -> "index.html"
            "svg" -> "graphic.svg"
            "css" -> "styles.css"
            "javascript", "js" -> "app.js"
            "typescript", "ts" -> "app.ts"
            "tsx" -> "Component.tsx"
            "jsx" -> "Component.jsx"
            "python", "py" -> "script.py"
            "kotlin", "kt" -> "Main.kt"
            "java" -> "Main.java"
            "json" -> "data.json"
            "xml" -> "layout.xml"
            "bash", "sh", "shell" -> "script.sh"
            "sql" -> "query.sql"
            "yaml", "yml" -> "config.yaml"
            "markdown", "md" -> "document.md"
            "go" -> "main.go"
            "rust", "rs" -> "main.rs"
            "cpp", "c++" -> "main.cpp"
            "c" -> "main.c"
            "php" -> "index.php"
            "dart" -> "main.dart"
            else -> "snippet.txt"
        }
    }

    fun defaultExtensionForLanguage(language: String): String {
        return defaultFileNameForLanguage(language).substringAfterLast('.', "txt")
    }

    private fun deduplicateFileName(baseName: String, used: MutableSet<String>): String {
        val sanitized = baseName.substringAfterLast('/').substringAfterLast('\\').trim().ifBlank { "snippet.txt" }
        val lower = sanitized.lowercase(Locale.ROOT)
        if (used.add(lower)) return sanitized

        val dotIdx = sanitized.lastIndexOf('.')
        val stem = if (dotIdx > 0) sanitized.substring(0, dotIdx) else sanitized
        val ext = if (dotIdx > 0) sanitized.substring(dotIdx) else ""
        var counter = 2
        while (true) {
            val candidate = "${stem}_${counter}${ext}"
            if (used.add(candidate.lowercase(Locale.ROOT))) return candidate
            counter++
        }
    }

    fun isPreviewableCode(language: String, code: String): Boolean {
        val lang = language.trim().trimEnd(':', ',', ';').lowercase(Locale.ROOT)
        if (lang == "html" || lang == "htm" || lang == "html5" || lang == "xhtml" || lang == "svg") return true
        if (code.contains("<!doctype", true) || code.contains("<html", true)) return true
        if (lang == "xml" && (code.contains("<svg", true) || code.contains("<html", true))) return true
        if (lang == "code" || lang == "text" || lang == "txt" || lang == "web") {
            return (code.contains("<svg", true) && code.contains("</svg>", true)) ||
                (code.contains("<style", true) && code.contains("<div", true)) ||
                (code.contains("<body", true) && code.contains("</body>", true))
        }
        return false
    }

    /**
     * Memastikan potongan SVG atau fragmen HTML tetap ter-render responsif dan rapi di WebView,
     * sementara kode asli tetap utuh saat disalin atau diunduh.
     */
    fun buildPreviewHtmlDocument(code: String, language: String): String {
        val trimmed = code.trim()
        val hasFullHtmlDoc = trimmed.contains("<!doctype", ignoreCase = true) ||
            trimmed.contains("<html", ignoreCase = true)
        if (hasFullHtmlDoc) return trimmed

        val isSvg = language.equals("svg", ignoreCase = true) ||
            (trimmed.startsWith("<svg", ignoreCase = true) && trimmed.contains("</svg>", ignoreCase = true))

        if (isSvg) {
            return """
                <!DOCTYPE html>
                <html lang="id">
                <head>
                  <meta charset="UTF-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                  <style>
                    html, body {
                      margin: 0;
                      padding: 16px;
                      min-height: 100vh;
                      box-sizing: border-box;
                      display: flex;
                      align-items: center;
                      justify-content: center;
                      background: #0F1218;
                    }
                    svg {
                      max-width: 100%;
                      height: auto;
                    }
                  </style>
                </head>
                <body>
                  $trimmed
                </body>
                </html>
            """.trimIndent()
        }

        return """
            <!DOCTYPE html>
            <html lang="id">
            <head>
              <meta charset="UTF-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <style>
                body {
                  margin: 0;
                  padding: 16px;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                }
              </style>
            </head>
            <body>
              $trimmed
            </body>
            </html>
        """.trimIndent()
    }
}

/**
 * Helper penyimpanan file kode ke folder Download/AndraControl secara aman
 * menggunakan MediaStore.Downloads (Android 10+) tanpa izin penyimpanan tambahan.
 */
object CodeFileDownloader {
    fun sanitizeFileName(rawName: String, language: String): String {
        val base = rawName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('.')

        if (base.isBlank()) {
            return ChatMessageParser.defaultFileNameForLanguage(language)
        }
        return if (base.contains('.')) {
            base
        } else {
            val ext = ChatMessageParser.defaultExtensionForLanguage(language)
            "$base.$ext"
        }
    }

    fun resolveMimeType(fileName: String, language: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext.ifBlank { language.lowercase(Locale.ROOT) }) {
            "html", "htm" -> "text/html"
            "svg" -> "image/svg+xml"
            "css" -> "text/css"
            "js", "mjs" -> "application/javascript"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "md" -> "text/markdown"
            "csv" -> "text/csv"
            else -> "text/plain"
        }
    }

    suspend fun saveCodeToDownloads(
        context: Context,
        fileName: String,
        code: String,
        language: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val safeName = sanitizeFileName(fileName, language)
            val mimeType = resolveMimeType(safeName, language)
            val bytes = code.toByteArray(Charsets.UTF_8)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/AndraControl")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Gagal membuat berkas di folder Downloads")
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(bytes)
                        out.flush()
                    } ?: throw IllegalStateException("Gagal membuka aliran tulis berkas")
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
                "Downloads/AndraControl/$safeName"
            } else {
                @Suppress("DEPRECATION")
                val publicDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "AndraControl"
                )
                try {
                    if (!publicDir.exists()) publicDir.mkdirs()
                    val outFile = File(publicDir, safeName)
                    outFile.writeBytes(bytes)
                    "Downloads/AndraControl/$safeName"
                } catch (_: SecurityException) {
                    val appDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                    if (!appDir.exists()) appDir.mkdirs()
                    val fallbackFile = File(appDir, safeName)
                    fallbackFile.writeBytes(bytes)
                    fallbackFile.absolutePath
                }
            }
        }
    }
}

/**
 * Box pemisah kode terdedikasi dengan desain Graphite Precision:
 * - Header menampilkan bahasa + nama file yang bisa diketuk untuk diberi nama/rename
 * - Tombol cepat Salin (Copy) & Unduh (Download) di header
 * - Area kode monospace gelap dengan nomor baris + horizontal scroll
 * - Footer menyediakan tombol Preview Live untuk HTML/SVG serta tombol lipat/perluas kode panjang
 */
@Composable
fun CodeBlockCard(
    segment: ChatContentSegment.CodeBlockSegment,
    fileName: String,
    onRename: (String) -> Unit,
    onPreview: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var copiedRecently by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var isExpanded by remember(segment.index, segment.code.length) { mutableStateOf(false) }

    LaunchedEffect(copiedRecently) {
        if (copiedRecently) {
            delay(1600L)
            copiedRecently = false
        }
    }

    val allLines = remember(segment.code) { segment.code.lines() }
    val totalLines = allLines.size
    val collapseThreshold = 18
    val canCollapse = totalLines > collapseThreshold

    val displayedCode = remember(allLines, isExpanded, canCollapse) {
        if (canCollapse && !isExpanded) {
            allLines.take(collapseThreshold).joinToString("\n")
        } else {
            segment.code
        }
    }
    val lineNumbersText = remember(totalLines, isExpanded, canCollapse) {
        val visibleCount = if (canCollapse && !isExpanded) collapseThreshold else totalLines
        (1..visibleCount).joinToString("\n")
    }
    val langBadge = remember(segment.language) {
        segment.language.uppercase(Locale.ROOT).take(10)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GlassRadius.Card))
            .background(GlassCodeCanvas)
            .specularGlass(cornerRadius = GlassRadius.Card, fillAlpha = 0.04f)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header bar: badge bahasa + nama file (editable) + Preview + Copy + Download
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GlassCodeHeader)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = langBadge,
                    color = if (segment.isPreviewable) JarvisCyan else JarvisTextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.width(6.dp))

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(12.dp)
                        .background(JarvisBorderStrong)
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Nama file kode (ketuk untuk memberi nama / rename)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { showRenameDialog = true }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = fileName,
                        color = JarvisTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Ubah nama file kode",
                        tint = JarvisTextSecondary,
                        modifier = Modifier.size(12.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Tombol cepat Preview langsung di header untuk HTML/SVG
                if (segment.isPreviewable) {
                    IconButton(
                        onClick = { onPreview(fileName) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = "Preview live",
                            tint = JarvisCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Tombol Salin Kode
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(fileName, segment.code))
                        copiedRecently = true
                        Toast.makeText(context, "$fileName disalin", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (copiedRecently) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "Salin kode",
                        tint = if (copiedRecently) JarvisEmerald else JarvisTextSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                }

                // Tombol Unduh File Kode
                IconButton(
                    onClick = {
                        if (isSaving) return@IconButton
                        isSaving = true
                        coroutineScope.launch {
                            val result = CodeFileDownloader.saveCodeToDownloads(
                                context = context,
                                fileName = fileName,
                                code = segment.code,
                                language = segment.language
                            )
                            isSaving = false
                            result.onSuccess { savedPath ->
                                Toast.makeText(context, "Tersimpan: $savedPath", Toast.LENGTH_SHORT).show()
                            }.onFailure { err ->
                                Toast.makeText(
                                    context,
                                    "Gagal mengunduh: ${err.message ?: "Error"}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    enabled = !isSaving,
                    modifier = Modifier.size(32.dp)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 1.6.dp,
                            color = JarvisCyan
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Unduh kode",
                            tint = JarvisTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = JarvisBorder, thickness = 1.dp)

            // Area isi kode monospace dengan nomor baris + horizontal scroll
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(JarvisBackground)
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    text = lineNumbersText,
                    color = JarvisTextTertiary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.5.sp,
                    modifier = Modifier
                        .drawBehind {
                            val strokePx = 1.dp.toPx()
                            drawLine(
                                color = JarvisBorder,
                                start = Offset(size.width - strokePx / 2f, 0f),
                                end = Offset(size.width - strokePx / 2f, size.height),
                                strokeWidth = strokePx
                            )
                        }
                        .padding(start = 10.dp, end = 9.dp)
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp)
                ) {
                    Text(
                        text = displayedCode,
                        color = GlassCodeText,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.5.sp,
                        softWrap = false
                    )
                }
            }

            // Footer aksi: tombol Preview Live (untuk HTML/SVG) dan/atau Lihat Semua baris
            if (segment.isPreviewable || canCollapse) {
                HorizontalDivider(color = GlassDivider, thickness = 1.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(GlassCodeHeader)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (canCollapse) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { isExpanded = !isExpanded }
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = JarvisTextSecondary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = if (isExpanded) "Ringkas kode" else "Lihat semua ($totalLines baris)",
                                    color = JarvisTextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else {
                            Text(
                                text = "$totalLines baris · Siap preview",
                                color = JarvisTextTertiary,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (segment.isPreviewable) {
                        val previewInteraction = remember { MutableInteractionSource() }
                        Button(
                            onClick = { onPreview(fileName) },
                            interactionSource = previewInteraction,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = JarvisCyan,
                                contentColor = JarvisOnPrimary
                            ),
                            shape = RoundedCornerShape(GlassRadius.Small),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                            modifier = Modifier
                                .heightIn(min = 36.dp)
                                .pressScale(previewInteraction)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = "Preview kode",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Preview",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }

    if (showRenameDialog) {
        RenameCodeFileDialog(
            currentName = fileName,
            language = segment.language,
            onConfirm = { newName ->
                val sanitized = CodeFileDownloader.sanitizeFileName(newName, segment.language)
                onRename(sanitized)
                showRenameDialog = false
                Toast.makeText(context, "Nama kode diubah: $sanitized", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showRenameDialog = false }
        )
    }
}

@Composable
fun RenameCodeFileDialog(
    currentName: String,
    language: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var draftName by remember(currentName) { mutableStateOf(currentName) }
    val defaultExt = remember(language) { ChatMessageParser.defaultExtensionForLanguage(language) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = JarvisSurface,
        shape = RoundedCornerShape(14.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.DriveFileRenameOutline,
                    contentDescription = null,
                    tint = JarvisCyan,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Beri Nama File Kode",
                    color = JarvisTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Nama ini ditampilkan pada box kode dan otomatis dipakai saat mengunduh file.",
                    color = JarvisTextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.5.sp
                )
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it },
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = "contoh: landing.$defaultExt",
                            color = JarvisTextTertiary,
                            fontSize = 12.5.sp
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = JarvisCyan,
                        unfocusedBorderColor = JarvisBorderStrong,
                        focusedTextColor = JarvisTextPrimary,
                        unfocusedTextColor = JarvisTextPrimary,
                        cursorColor = JarvisCyan,
                        focusedContainerColor = JarvisBackground,
                        unfocusedContainerColor = JarvisBackground
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (draftName.isNotBlank()) {
                        onConfirm(draftName)
                    }
                },
                enabled = draftName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = JarvisCyan,
                    contentColor = JarvisOnPrimary
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Simpan", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, JarvisBorder)
            ) {
                Text("Batal", color = JarvisTextSecondary, fontSize = 12.sp)
            }
        }
    )
}
