package com.example.service

import com.example.model.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

enum class PlanStepStatus { PENDING, IN_PROGRESS, DONE, FAILED }

data class AgentPlanStep(
    val id: Int,
    val title: String,
    val status: PlanStepStatus = PlanStepStatus.PENDING,
    val note: String = ""
)

/**
 * AgentWorkspaceTools — Toolkit bergaya AI Agent Komputer:
 * - Editor file terstruktur (read ber-nomor baris, write, edit search-and-replace, grep, tree)
 * - Web Fetcher (ambil halaman web / API & bersihkan HTML menjadi teks ringkas)
 * - Download File langsung ke workspace
 * - Task Planner / Checklist realtime yang tampil di UI Chat
 * - Wait/Delay aman antar langkah otomasi
 */
object AgentWorkspaceTools {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _planSteps = MutableStateFlow<List<AgentPlanStep>>(emptyList())
    val planSteps: StateFlow<List<AgentPlanStep>> = _planSteps.asStateFlow()

    fun clearPlan() {
        _planSteps.value = emptyList()
    }

    /**
     * Operasi file pintar ala coding agent komputer:
     * action = read | write | edit | grep | tree | info
     */
    suspend fun executeWorkspaceFile(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val action = params.optString("action", "read").lowercase().trim()
        val sessionId = params.optString("session", "main")
        val rawPath = params.optString("path", ".").trim()

        return@withContext try {
            when (action) {
                "read" -> {
                    val file = AgentTerminalManager.resolvePath(rawPath, sessionId)
                    if (!file.exists() || !file.isFile) {
                        return@withContext ToolResult("error", message = "File tidak ditemukan: ${file.absolutePath}")
                    }
                    if (file.length() > 2 * 1024 * 1024) {
                        return@withContext ToolResult("error", message = "File terlalu besar (${file.length() / 1024} KB). Gunakan parameter start_line & end_line atau terminal_exec (head/tail).")
                    }
                    val allLines = file.readLines(Charsets.UTF_8)
                    val total = allLines.size
                    val startLine = params.optInt("start_line", 1).coerceIn(1, total.coerceAtLeast(1))
                    val endLine = params.optInt("end_line", (startLine + 199)).coerceIn(startLine, total.coerceAtLeast(1))
                    val slice = if (total == 0) {
                        "(file kosong)"
                    } else {
                        (startLine..endLine).joinToString("\n") { ln ->
                            "${ln.toString().padStart(4, ' ')} | ${allLines[ln - 1]}"
                        }
                    }
                    val shown = if (slice.length > 14000) slice.take(14000) + "\n... [dipotong]" else slice
                    ToolResult(
                        "ok",
                        result = "📄 ${file.absolutePath} (baris $startLine..$endLine dari $total)\n$shown"
                    )
                }

                "write" -> {
                    val file = AgentTerminalManager.resolvePath(rawPath, sessionId)
                    val content = params.optString("content", "")
                    file.parentFile?.mkdirs()
                    file.writeText(content, Charsets.UTF_8)
                    val lineCount = if (content.isEmpty()) 0 else content.count { it == '\n' } + 1
                    ToolResult(
                        "ok",
                        result = "✅ File ditulis: ${file.absolutePath} ($lineCount baris, ${content.length} karakter)"
                    )
                }

                "edit", "replace" -> {
                    val file = AgentTerminalManager.resolvePath(rawPath, sessionId)
                    if (!file.exists() || !file.isFile) {
                        return@withContext ToolResult("error", message = "File tidak ditemukan: ${file.absolutePath}")
                    }
                    val findText = params.optString("find", params.optString("old_text", ""))
                    val replaceText = params.optString("replace", params.optString("new_text", ""))
                    val replaceAll = params.optBoolean("replace_all", false)
                    if (findText.isEmpty()) {
                        return@withContext ToolResult("error", message = "Parameter 'find' wajib diisi untuk action=edit.")
                    }
                    val original = file.readText(Charsets.UTF_8)
                    if (!original.contains(findText)) {
                        return@withContext ToolResult("error", message = "Teks target tidak ditemukan di ${file.name}. Baca file dulu dengan action='read'.")
                    }
                    val updated = if (replaceAll) {
                        original.replace(findText, replaceText)
                    } else {
                        original.replaceFirst(findText, replaceText)
                    }
                    file.writeText(updated, Charsets.UTF_8)
                    ToolResult(
                        "ok",
                        result = "✏️ File diperbarui: ${file.absolutePath} (mode=${if (replaceAll) "replace_all" else "replace_first"})"
                    )
                }

                "grep", "search" -> {
                    val dir = AgentTerminalManager.resolvePath(rawPath, sessionId)
                    val pattern = params.optString("query", params.optString("pattern", "")).trim()
                    if (pattern.isEmpty()) {
                        return@withContext ToolResult("error", message = "Parameter 'query' wajib diisi untuk action=grep.")
                    }
                    val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
                    val matches = mutableListOf<String>()
                    val maxMatches = params.optInt("max_results", 50).coerceIn(1, 150)

                    fun scan(target: File, depth: Int) {
                        if (matches.size >= maxMatches || depth > 6) return
                        if (target.isFile) {
                            if (target.length() > 512 * 1024) return
                            val ext = target.extension.lowercase()
                            if (ext in setOf("png", "jpg", "jpeg", "webp", "mp4", "apk", "zip", "jar", "so", "dex")) return
                            runCatching {
                                target.useLines(Charsets.UTF_8) { seq ->
                                    seq.forEachIndexed { idx, line ->
                                        if (matches.size >= maxMatches) return@forEachIndexed
                                        val hit = regex?.containsMatchIn(line)
                                            ?: line.contains(pattern, ignoreCase = true)
                                        if (hit) {
                                            val rel = target.relativeToOrSelf(dir).path.ifBlank { target.name }
                                            matches.add("$rel:${idx + 1}: ${line.trim().take(160)}")
                                        }
                                    }
                                }
                            }
                        } else if (target.isDirectory) {
                            if (target.name in setOf(".git", "node_modules", "build", ".gradle")) return
                            target.listFiles()?.sortedBy { it.name }?.forEach { scan(it, depth + 1) }
                        }
                    }

                    scan(dir, 0)
                    if (matches.isEmpty()) {
                        ToolResult("ok", result = "🔎 Tidak ada kecocokan untuk '$pattern' di ${dir.absolutePath}")
                    } else {
                        ToolResult(
                            "ok",
                            result = "🔎 Hasil grep '$pattern' (${matches.size} baris):\n" + matches.joinToString("\n")
                        )
                    }
                }

                "tree", "list" -> {
                    val dir = AgentTerminalManager.resolvePath(rawPath, sessionId)
                    if (!dir.exists()) {
                        return@withContext ToolResult("error", message = "Path tidak ditemukan: ${dir.absolutePath}")
                    }
                    val maxDepth = params.optInt("max_depth", 3).coerceIn(1, 6)
                    val lines = mutableListOf<String>()
                    fun walk(f: File, depth: Int, prefix: String) {
                        if (lines.size >= 120) return
                        val children = f.listFiles()
                            ?.filterNot { it.name == ".git" || it.name == "node_modules" }
                            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                            ?: return
                        for (child in children) {
                            if (lines.size >= 120) break
                            if (child.isDirectory) {
                                lines.add("$prefix📁 ${child.name}/")
                                if (depth < maxDepth) {
                                    walk(child, depth + 1, "$prefix  ")
                                }
                            } else {
                                val sizeStr = formatSize(child.length())
                                lines.add("$prefix📄 ${child.name} ($sizeStr)")
                            }
                        }
                    }
                    if (dir.isDirectory) {
                        walk(dir, 1, "")
                        val body = if (lines.isEmpty()) "(direktori kosong)" else lines.joinToString("\n")
                        ToolResult("ok", result = "🌳 ${dir.absolutePath}\n$body")
                    } else {
                        ToolResult("ok", result = "📄 ${dir.absolutePath} (${formatSize(dir.length())})")
                    }
                }

                else -> ToolResult(
                    "error",
                    message = "Aksi workspace_file '$action' tidak dikenal. Pilih: read, write, edit, grep, tree"
                )
            }
        } catch (e: Exception) {
            ToolResult("error", message = "Gagal operasi workspace_file ($action): ${e.message}")
        }
    }

    /**
     * Mengambil konten URL dan membersihkannya dari tag HTML agar mudah dibaca AI Agent.
     */
    suspend fun executeWebFetch(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val url = params.optString("url", "").trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext ToolResult("error", message = "URL harus diawali http:// atau https://")
        }
        val maxChars = params.optInt("max_chars", 8000).coerceIn(500, 20000)

        return@withContext try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AndraControlAgent/2.60")
                .get()
                .build()
            val resp = httpClient.newCall(req).execute()
            val code = resp.code
            val contentType = resp.header("Content-Type") ?: ""
            val raw = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                return@withContext ToolResult("error", message = "HTTP $code saat mengambil $url:\n${raw.take(800)}")
            }

            val cleaned = if (contentType.contains("html", ignoreCase = true) || raw.trimStart().startsWith("<")) {
                htmlToReadableText(raw)
            } else {
                raw.trim()
            }

            val truncated = if (cleaned.length > maxChars) {
                cleaned.take(maxChars) + "\n... [dipotong dari ${cleaned.length} karakter]"
            } else {
                cleaned
            }
            ToolResult("ok", result = "🌐 Web Fetch ($code) $url\n\n$truncated")
        } catch (e: Exception) {
            ToolResult("error", message = "Gagal mengambil URL '$url': ${e.message}")
        }
    }

    /**
     * Mengunduh file dari URL langsung ke workspace terminal atau path tertentu.
     */
    suspend fun executeDownloadFile(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val url = params.optString("url", "").trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext ToolResult("error", message = "URL download tidak valid.")
        }
        val sessionId = params.optString("session", "main")
        val fallbackName = url.substringBefore("?").substringAfterLast("/").ifBlank { "download_${System.currentTimeMillis()}.bin" }
        val savePath = params.optString("path", params.optString("filename", fallbackName)).trim()
        val targetFile = AgentTerminalManager.resolvePath(savePath, sessionId)

        return@withContext try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "AndraControlAgent/2.60")
                .get()
                .build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                return@withContext ToolResult("error", message = "Download gagal (HTTP ${resp.code})")
            }
            val body = resp.body ?: return@withContext ToolResult("error", message = "Body respons kosong.")
            targetFile.parentFile?.mkdirs()
            val bytes = body.bytes()
            targetFile.writeBytes(bytes)
            ToolResult(
                "ok",
                result = "⬇️ File berhasil diunduh!\n- Path: ${targetFile.absolutePath}\n- Ukuran: ${formatSize(bytes.size.toLong())}"
            )
        } catch (e: Exception) {
            ToolResult("error", message = "Gagal mengunduh file: ${e.message}")
        }
    }

    /**
     * Mengelola rencana langkah (checklist) AI Agent yang tampil realtime di layar Chat.
     */
    fun executeAgentPlan(params: JSONObject): ToolResult {
        val action = params.optString("action", "get").lowercase().trim()
        return when (action) {
            "set", "create" -> {
                val arr: JSONArray? = params.optJSONArray("steps")
                if (arr == null || arr.length() == 0) {
                    return ToolResult("error", message = "Parameter 'steps' (array string) wajib diisi untuk action=set.")
                }
                val steps = mutableListOf<AgentPlanStep>()
                for (i in 0 until arr.length()) {
                    val item = arr.opt(i)
                    when (item) {
                        is JSONObject -> {
                            val st = parsePlanStatus(item.optString("status", if (i == 0) "in_progress" else "pending"))
                            steps.add(
                                AgentPlanStep(
                                    id = i + 1,
                                    title = item.optString("title", "Langkah ${i + 1}"),
                                    status = st,
                                    note = item.optString("note", "")
                                )
                            )
                        }
                        else -> {
                            steps.add(
                                AgentPlanStep(
                                    id = i + 1,
                                    title = item?.toString() ?: "Langkah ${i + 1}",
                                    status = if (i == 0) PlanStepStatus.IN_PROGRESS else PlanStepStatus.PENDING
                                )
                            )
                        }
                    }
                }
                _planSteps.value = steps
                ToolResult("ok", result = "📋 Rencana kerja diset (${steps.size} langkah):\n" + formatPlan(steps))
            }

            "update" -> {
                val id = params.optInt("id", params.optInt("step", -1))
                val statusStr = params.optString("status", "done")
                val note = params.optString("note", "")
                val current = _planSteps.value.toMutableList()
                val idx = current.indexOfFirst { it.id == id }
                if (idx < 0) {
                    return ToolResult("error", message = "Langkah #$id tidak ditemukan di rencana saat ini.")
                }
                current[idx] = current[idx].copy(
                    status = parsePlanStatus(statusStr),
                    note = note.ifBlank { current[idx].note }
                )
                _planSteps.value = current
                ToolResult("ok", result = "✅ Rencana diperbarui:\n" + formatPlan(current))
            }

            "clear" -> {
                _planSteps.value = emptyList()
                ToolResult("ok", result = "🧹 Daftar rencana dibersihkan.")
            }

            else -> {
                val cur = _planSteps.value
                if (cur.isEmpty()) ToolResult("ok", result = "Belum ada rencana kerja aktif.")
                else ToolResult("ok", result = "📋 Rencana kerja saat ini:\n" + formatPlan(cur))
            }
        }
    }

    /** Menunggu beberapa detik secara aman tanpa memblokir thread UI. */
    suspend fun executeWaitSeconds(params: JSONObject): ToolResult {
        val sec = params.optDouble("seconds", 2.0).coerceIn(0.2, 30.0)
        delay((sec * 1000).toLong())
        return ToolResult("ok", result = "⏱️ Menunggu ${sec}s selesai.")
    }

    private fun parsePlanStatus(raw: String): PlanStepStatus = when (raw.lowercase().trim()) {
        "done", "completed", "ok" -> PlanStepStatus.DONE
        "in_progress", "running", "active" -> PlanStepStatus.IN_PROGRESS
        "failed", "error" -> PlanStepStatus.FAILED
        else -> PlanStepStatus.PENDING
    }

    private fun formatPlan(steps: List<AgentPlanStep>): String = steps.joinToString("\n") { s ->
        val icon = when (s.status) {
            PlanStepStatus.DONE -> "[✓]"
            PlanStepStatus.IN_PROGRESS -> "[→]"
            PlanStepStatus.FAILED -> "[✗]"
            PlanStepStatus.PENDING -> "[ ]"
        }
        val extra = if (s.note.isNotBlank()) " — ${s.note}" else ""
        "$icon ${s.id}. ${s.title}$extra"
    }

    private fun htmlToReadableText(html: String): String {
        return html
            .replace(Regex("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<(br|p|div|li|tr|h[1-6])[^>]*>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
