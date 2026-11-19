package com.example.service

import android.os.Build
import android.os.Environment
import android.util.Log
import com.example.JarvisApp
import com.example.model.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Satu entri riwayat eksekusi di terminal internal AI Agent.
 */
data class TerminalEntry(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val source: String, // "AI" atau "USER"
    val cwd: String,
    val command: String,
    val output: String,
    val exitCode: Int,
    val durationMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Ringkasan status proses latar belakang yang berjalan di terminal AI Agent.
 */
data class BackgroundJobInfo(
    val jobId: String,
    val name: String,
    val command: String,
    val cwd: String,
    val isAlive: Boolean,
    val exitCode: Int?,
    val startedAt: Long,
    val lineCount: Int
)

/**
 * AgentTerminalManager — Terminal persisten multi-sesi milik AI Agent (ala AI Agent komputer).
 *
 * Fitur utama:
 * - Mempertahankan working directory (cwd) dan environment variables antar perintah per sesi.
 * - Workspace otomatis di /sdcard/JARVIS/workspace (atau fallback internal filesDir/workspace).
 * - Mendukung proses latar belakang (server lokal, watcher, script panjang) lengkap dengan
 *   pembacaan log realtime, input stdin interaktif, dan penghentian proses.
 * - Transkrip live (StateFlow) yang tampil langsung di layar Termux/Terminal dan bisa dipakai
 *   bersama antara AI Agent dan pengguna.
 */
object AgentTerminalManager {

    private const val TAG = "AgentTerminalManager"
    private const val DEFAULT_SESSION = "main"
    private const val MAX_TRANSCRIPT_ENTRIES = 120
    private const val MAX_JOB_BUFFER_LINES = 500
    private const val MAX_OUTPUT_CHARS = 18000

    private val sessionCwds = ConcurrentHashMap<String, String>()
    private val sessionEnvs = ConcurrentHashMap<String, MutableMap<String, String>>()
    private val backgroundJobs = ConcurrentHashMap<String, BackgroundJobHandle>()

    private val _entries = MutableStateFlow<List<TerminalEntry>>(emptyList())
    val entries: StateFlow<List<TerminalEntry>> = _entries.asStateFlow()

    private val _activeSession = MutableStateFlow(DEFAULT_SESSION)
    val activeSession: StateFlow<String> = _activeSession.asStateFlow()

    private val _currentCwds = MutableStateFlow<Map<String, String>>(emptyMap())
    val currentCwds: StateFlow<Map<String, String>> = _currentCwds.asStateFlow()

    private val _jobs = MutableStateFlow<List<BackgroundJobInfo>>(emptyList())
    val jobs: StateFlow<List<BackgroundJobInfo>> = _jobs.asStateFlow()

    private class BackgroundJobHandle(
        val jobId: String,
        val name: String,
        val command: String,
        val cwd: String,
        val process: Process,
        val startedAt: Long = System.currentTimeMillis()
    ) {
        val buffer = ArrayDeque<String>(MAX_JOB_BUFFER_LINES)
        @Volatile
        var exitCode: Int? = null

        @Synchronized
        fun appendLine(line: String) {
            if (buffer.size >= MAX_JOB_BUFFER_LINES) {
                buffer.removeFirst()
            }
            buffer.addLast(line)
        }

        @Synchronized
        fun readTail(maxLines: Int): String {
            if (buffer.isEmpty()) return ""
            val n = maxLines.coerceIn(1, MAX_JOB_BUFFER_LINES)
            val all = buffer.toList()
            return all.takeLast(n).joinToString("\n")
        }

        @Synchronized
        fun lineCount(): Int = buffer.size

        fun toInfo(): BackgroundJobInfo = BackgroundJobInfo(
            jobId = jobId,
            name = name,
            command = command,
            cwd = cwd,
            isAlive = process.isAlive,
            exitCode = exitCode,
            startedAt = startedAt,
            lineCount = lineCount()
        )
    }

    /** Direktori home default untuk workspace terminal AI Agent. */
    fun getWorkspaceDir(): File {
        val ctx = JarvisApp.instance
        val canUseExternal = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                Environment.getExternalStorageDirectory()?.canWrite() == true
            }
        } catch (_: Exception) {
            false
        }

        if (canUseExternal) {
            val extWorkspace = File(Environment.getExternalStorageDirectory(), "JARVIS/workspace")
            if (extWorkspace.exists() || extWorkspace.mkdirs()) {
                return extWorkspace
            }
        }
        return File(ctx.filesDir, "workspace").apply { if (!exists()) mkdirs() }
    }

    fun getCwd(sessionId: String = DEFAULT_SESSION): String {
        val sid = normalizeSessionId(sessionId)
        val existing = sessionCwds[sid]
        if (existing != null && File(existing).isDirectory) {
            return existing
        }
        val defaultDir = getWorkspaceDir().absolutePath
        sessionCwds[sid] = defaultDir
        publishCwds()
        return defaultDir
    }

    fun resolvePath(rawPath: String, sessionId: String = DEFAULT_SESSION): File {
        val trimmed = rawPath.trim()
        val base = File(getCwd(sessionId))
        return when {
            trimmed.isEmpty() || trimmed == "." -> base
            trimmed == "~" -> getWorkspaceDir()
            trimmed.startsWith("~/") -> File(getWorkspaceDir(), trimmed.removePrefix("~/"))
            trimmed.startsWith("/") -> File(trimmed)
            else -> File(base, trimmed)
        }.canonicalFile
    }

    fun selectSession(sessionId: String) {
        val sid = normalizeSessionId(sessionId)
        getCwd(sid)
        _activeSession.value = sid
    }

    fun clearTranscript(sessionId: String? = null) {
        if (sessionId == null) {
            _entries.value = emptyList()
        } else {
            val sid = normalizeSessionId(sessionId)
            _entries.value = _entries.value.filterNot { it.sessionId == sid }
        }
    }

    fun resetSession(sessionId: String = DEFAULT_SESSION): ToolResult {
        val sid = normalizeSessionId(sessionId)
        val home = getWorkspaceDir().absolutePath
        sessionCwds[sid] = home
        sessionEnvs.remove(sid)
        publishCwds()
        appendEntry(
            TerminalEntry(
                sessionId = sid,
                source = "SYSTEM",
                cwd = home,
                command = "reset",
                output = "Sesi terminal '$sid' direset ke $home",
                exitCode = 0,
                durationMs = 0L
            )
        )
        return ToolResult("ok", result = "Terminal sesi '$sid' berhasil direset. CWD: $home")
    }

    /**
     * Eksekusi perintah dalam sesi terminal persisten.
     * Menjaga perubahan `cd` dan `export VAR=val` untuk perintah berikutnya di sesi yang sama.
     */
    suspend fun exec(
        command: String,
        sessionId: String = DEFAULT_SESSION,
        timeoutSec: Int = 30,
        source: String = "AI",
        asAdb: Boolean = false
    ): ToolResult = withContext(Dispatchers.IO) {
        val cleanCmd = command.trim()
        if (cleanCmd.isEmpty()) {
            return@withContext ToolResult("error", message = "Perintah terminal tidak boleh kosong.")
        }

        val sid = normalizeSessionId(sessionId)
        val currentDirPath = getCwd(sid)
        val startTime = System.currentTimeMillis()

        // Jalur ADB level-shell (Shizuku dulu -> Termux-ADB fallback) bila diminta as_adb=true
        if (asAdb) {
            val adbResult = if (AdbShizukuManager.isShizukuBinderAlive() && AdbShizukuManager.shizukuPermissionGranted()) {
                AdbShizukuManager.executeShizukuShell(cleanCmd)
            } else {
                val out = AdbShizukuManager.termuxAdbShellWithOutput(cleanCmd, timeoutSec.coerceIn(5, 60) * 1000L)
                if (out != null) {
                    ToolResult("ok", result = out.trim().ifBlank { "(sukses, tanpa output)" })
                } else {
                    ToolResult(
                        "error",
                        message = "Mode as_adb gagal: Shizuku dan Termux-ADB tidak aktif. Jalankan Shizuku atau termux/setup_adb.sh."
                    )
                }
            }
            val elapsed = System.currentTimeMillis() - startTime
            val outText = adbResult.result ?: adbResult.message ?: ""
            appendEntry(
                TerminalEntry(
                    sessionId = sid,
                    source = source,
                    cwd = "[ADB] $currentDirPath",
                    command = cleanCmd,
                    output = outText,
                    exitCode = if (adbResult.status == "ok") 0 else 1,
                    durationMs = elapsed
                )
            )
            return@withContext adbResult
        }

        val workDir = File(currentDirPath).let { if (it.isDirectory) it else getWorkspaceDir() }
        val marker = "__JARVIS_TERM_${System.nanoTime()}__"
        val cwdMarker = "${marker}_CWD:"
        val envMarker = "${marker}_ENV_START"
        val envEndMarker = "${marker}_ENV_END"

        // Rekonstruksi export env yang disimpan di sesi ini
        val savedEnvMap = sessionEnvs.computeIfAbsent(sid) { ConcurrentHashMap() }
        val envPrefix = buildString {
            for ((k, v) in savedEnvMap) {
                if (k.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                    val escaped = v.replace("'", "'\"'\"'")
                    append("export $k='$escaped'\n")
                }
            }
        }

        // Jalankan perintah lalu tangkap exit code, CWD baru, dan env kustom
        val wrappedScript = buildString {
            append(envPrefix)
            append(rewritePythonAliases(cleanCmd))
            append('\n')
            append("__j_ec=$?\n")
            append("printf '\\n${cwdMarker}%s\\n' \"\$PWD\"\n")
            append("printf '${envMarker}\\n'\n")
            append("export -p 2>/dev/null | head -n 60 || true\n")
            append("printf '${envEndMarker}\\n'\n")
            append("exit \$__j_ec\n")
        }

        return@withContext try {
            val builder = createShellProcessBuilder(wrappedScript, workDir)
            val process = builder.start()

            val rawOutput = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val readerThread = Thread {
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (rawOutput.length < MAX_OUTPUT_CHARS + 4096) {
                            rawOutput.appendLine(line)
                        }
                    }
                } catch (_: Exception) {
                }
            }
            readerThread.start()

            val cappedTimeout = timeoutSec.coerceIn(3, 120).toLong()
            val finished = process.waitFor(cappedTimeout, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                readerThread.join(500)
                val elapsed = System.currentTimeMillis() - startTime
                val partial = stripInternalMarkers(rawOutput.toString(), cwdMarker, envMarker, envEndMarker).first
                val msg = "Timeout setelah ${cappedTimeout}s. Output parsial:\n$partial\n\nTip: Untuk proses jangka panjang (server/watcher), gunakan tool 'terminal_bg_start'."
                appendEntry(
                    TerminalEntry(
                        sessionId = sid,
                        source = source,
                        cwd = currentDirPath,
                        command = cleanCmd,
                        output = msg,
                        exitCode = 124,
                        durationMs = elapsed
                    )
                )
                return@withContext ToolResult("error", errorCode = "TERMINAL_TIMEOUT", message = msg)
            }

            readerThread.join(1000)
            val exitCode = process.exitValue()
            val elapsed = System.currentTimeMillis() - startTime

            val (cleanOutput, newCwd, parsedExports) = parseWrappedOutput(
                rawOutput.toString(),
                cwdMarker,
                envMarker,
                envEndMarker
            )

            if (!newCwd.isNullOrBlank() && File(newCwd).isDirectory) {
                sessionCwds[sid] = File(newCwd).canonicalPath
                publishCwds()
            }

            // Simpan hanya variabel user baru/yang diubah (hindari mengotori PATH/PWD sistem)
            val ignoredKeys = setOf("PWD", "OLDPWD", "SHLVL", "_", "PATH", "HOME", "PREFIX", "LD_LIBRARY_PATH", "TMPDIR", "TERM")
            for ((k, v) in parsedExports) {
                if (k !in ignoredKeys) {
                    savedEnvMap[k] = v
                }
            }

            val finalCwd = getCwd(sid)
            val cappedOutput = if (cleanOutput.length > MAX_OUTPUT_CHARS) {
                cleanOutput.take(MAX_OUTPUT_CHARS) + "\n... [Output dipotong: ${cleanOutput.length} karakter]"
            } else {
                cleanOutput
            }

            appendEntry(
                TerminalEntry(
                    sessionId = sid,
                    source = source,
                    cwd = finalCwd,
                    command = cleanCmd,
                    output = cappedOutput.ifBlank { "(exit $exitCode, tanpa output)" },
                    exitCode = exitCode,
                    durationMs = elapsed
                )
            )

            val header = "[session=$sid | cwd=$finalCwd | exit=$exitCode | ${elapsed}ms]"
            val body = cappedOutput.ifBlank { "(tanpa output)" }
            if (exitCode == 0) {
                ToolResult("ok", result = "$header\n$body")
            } else {
                ToolResult(
                    status = "error",
                    result = "$header\n$body",
                    errorCode = "EXIT_$exitCode",
                    message = "$header\n$body"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Terminal exec error: ${e.message}", e)
            val elapsed = System.currentTimeMillis() - startTime
            val errMsg = "Gagal menjalankan perintah terminal: ${e.message}"
            appendEntry(
                TerminalEntry(
                    sessionId = sid,
                    source = source,
                    cwd = currentDirPath,
                    command = cleanCmd,
                    output = errMsg,
                    exitCode = -1,
                    durationMs = elapsed
                )
            )
            ToolResult("error", errorCode = "TERMINAL_EXCEPTION", message = errMsg)
        }
    }

    /**
     * Memulai proses latar belakang (mis. python3 -m http.server, script monitor, server node).
     */
    fun startBackground(
        command: String,
        name: String = "",
        sessionId: String = DEFAULT_SESSION
    ): ToolResult {
        val cleanCmd = command.trim()
        if (cleanCmd.isEmpty()) {
            return ToolResult("error", message = "Perintah background tidak boleh kosong.")
        }
        val sid = normalizeSessionId(sessionId)
        val cwd = getCwd(sid)
        val workDir = File(cwd).let { if (it.isDirectory) it else getWorkspaceDir() }
        val jobId = "bg_" + UUID.randomUUID().toString().substring(0, 6)
        val displayName = name.trim().ifBlank { cleanCmd.take(28) }

        return try {
            val builder = createShellProcessBuilder(rewritePythonAliases(cleanCmd), workDir)
            val savedEnv = sessionEnvs[sid]
            if (savedEnv != null) {
                builder.environment().putAll(savedEnv)
            }
            val proc = builder.start()
            val handle = BackgroundJobHandle(
                jobId = jobId,
                name = displayName,
                command = cleanCmd,
                cwd = cwd,
                process = proc
            )
            backgroundJobs[jobId] = handle
            publishJobs()

            // Thread pembaca stdout/stderr berkelanjutan
            Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(proc.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        handle.appendLine(line ?: "")
                    }
                } catch (_: Exception) {
                } finally {
                    handle.exitCode = runCatching { proc.waitFor() }.getOrNull()
                    publishJobs()
                }
            }.apply {
                isDaemon = true
                start()
            }

            appendEntry(
                TerminalEntry(
                    sessionId = sid,
                    source = "AI",
                    cwd = cwd,
                    command = "[BG:$jobId] $cleanCmd",
                    output = "Proses latar belakang dimulai (job_id=$jobId).",
                    exitCode = 0,
                    durationMs = 0L
                )
            )

            ToolResult(
                status = "ok",
                result = "🚀 Proses latar belakang berjalan!\n- job_id: $jobId\n- nama: $displayName\n- cwd: $cwd\nGunakan 'terminal_bg_read' {\"job_id\":\"$jobId\"} untuk membaca log, 'terminal_bg_input' untuk mengirim input, atau 'terminal_bg_stop' untuk menghentikan."
            )
        } catch (e: Exception) {
            ToolResult("error", message = "Gagal memulai proses latar belakang: ${e.message}")
        }
    }

    /** Membaca output terbaru dari proses latar belakang. */
    fun readBackground(jobId: String, tailLines: Int = 60): ToolResult {
        val handle = backgroundJobs[jobId.trim()]
            ?: return ToolResult("error", message = "Job '$jobId' tidak ditemukan. Gunakan 'terminal_bg_list' untuk melihat daftar job.")
        val status = if (handle.process.isAlive) "RUNNING" else "EXITED (code=${handle.exitCode})"
        val output = handle.readTail(tailLines).ifBlank { "(belum ada output)" }
        publishJobs()
        return ToolResult(
            status = "ok",
            result = "[job=$jobId | ${handle.name} | status=$status | total_lines=${handle.lineCount()}]\n$output"
        )
    }

    /** Mengirim teks stdin ke proses latar belakang yang sedang berjalan. */
    fun sendInputToBackground(jobId: String, input: String, appendNewline: Boolean = true): ToolResult {
        val handle = backgroundJobs[jobId.trim()]
            ?: return ToolResult("error", message = "Job '$jobId' tidak ditemukan.")
        if (!handle.process.isAlive) {
            return ToolResult("error", message = "Job '$jobId' sudah selesai (exit=${handle.exitCode}).")
        }
        return try {
            val writer = OutputStreamWriter(handle.process.outputStream)
            writer.write(input)
            if (appendNewline && !input.endsWith("\n")) {
                writer.write("\n")
            }
            writer.flush()
            ToolResult("ok", result = "⌨️ Input dikirim ke job '$jobId': \"$input\"")
        } catch (e: Exception) {
            ToolResult("error", message = "Gagal mengirim stdin ke '$jobId': ${e.message}")
        }
    }

    /** Menghentikan proses latar belakang. */
    fun stopBackground(jobId: String): ToolResult {
        val id = jobId.trim()
        if (id == "all") {
            var stopped = 0
            for (h in backgroundJobs.values) {
                if (h.process.isAlive) {
                    runCatching { h.process.destroyForcibly() }
                    stopped++
                }
            }
            publishJobs()
            return ToolResult("ok", result = "🛑 $stopped proses latar belakang dihentikan.")
        }
        val handle = backgroundJobs[id]
            ?: return ToolResult("error", message = "Job '$id' tidak ditemukan.")
        return try {
            if (handle.process.isAlive) {
                handle.process.destroy()
                if (!handle.process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                    handle.process.destroyForcibly()
                }
            }
            publishJobs()
            ToolResult("ok", result = "🛑 Job '$id' (${handle.name}) telah dihentikan.")
        } catch (e: Exception) {
            ToolResult("error", message = "Gagal menghentikan job '$id': ${e.message}")
        }
    }

    /** Daftar seluruh proses latar belakang (aktif & selesai). */
    fun listBackgroundJobs(): ToolResult {
        publishJobs()
        val list = _jobs.value
        if (list.isEmpty()) {
            return ToolResult("ok", result = "Tidak ada proses latar belakang di terminal saat ini.")
        }
        val formatted = list.joinToString("\n") { j ->
            val st = if (j.isAlive) "🟢 RUNNING" else "⚪ EXITED(${j.exitCode ?: "?"})"
            "- [${j.jobId}] $st | ${j.name} | lines=${j.lineCount} | cmd: ${j.command.take(60)}"
        }
        return ToolResult("ok", result = "📋 Daftar Background Jobs (${list.size}):\n$formatted")
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private fun normalizeSessionId(sessionId: String): String {
        val clean = sessionId.trim().replace(Regex("[^a-zA-Z0-9_-]"), "")
        return clean.ifBlank { DEFAULT_SESSION }
    }

    private fun createShellProcessBuilder(script: String, workDir: File): ProcessBuilder {
        val context = JarvisApp.instance
        val termuxBash = File("/data/data/com.termux/files/usr/bin/bash")
        val shellBin = if (termuxBash.exists() && termuxBash.canExecute()) {
            termuxBash.absolutePath
        } else {
            "/system/bin/sh"
        }

        val pathEnv = listOf(
            "/data/data/com.termux/files/usr/bin",
            "/data/data/com.termux/files/usr/bin/applets",
            "/system/bin",
            "/system/xbin",
            "/vendor/bin",
            "/sbin",
            "/product/bin",
            "/apex/com.android.runtime/bin",
            "/apex/com.android.art/bin"
        ).joinToString(":")

        val builder = ProcessBuilder(shellBin, "-c", script)
            .directory(workDir)
            .redirectErrorStream(true)

        val env = builder.environment()
        env["PATH"] = pathEnv + ":" + (env["PATH"] ?: "")
        env["HOME"] = getWorkspaceDir().absolutePath
        env["JARVIS_WORKSPACE"] = getWorkspaceDir().absolutePath
        env["PREFIX"] = "/data/data/com.termux/files/usr"
        env["LD_LIBRARY_PATH"] = "/data/data/com.termux/files/usr/lib"
        env["TMPDIR"] = context.cacheDir.absolutePath
        env["TERM"] = "xterm-256color"
        return builder
    }

    private fun rewritePythonAliases(cmd: String): String {
        val termuxPy3 = File("/data/data/com.termux/files/usr/bin/python3")
        val termuxPy = File("/data/data/com.termux/files/usr/bin/python")
        val pyBin = when {
            termuxPy3.exists() -> termuxPy3.absolutePath
            termuxPy.exists() -> termuxPy.absolutePath
            else -> return cmd
        }
        return when {
            cmd.startsWith("python3 ") || cmd == "python3" -> cmd.replaceFirst("python3", pyBin)
            cmd.startsWith("python ") || cmd == "python" -> cmd.replaceFirst("python", pyBin)
            else -> cmd
        }
    }

    private fun stripInternalMarkers(
        raw: String,
        cwdMarker: String,
        envMarker: String,
        envEndMarker: String
    ): Pair<String, String?> {
        val idx = raw.indexOf(cwdMarker)
        val output = if (idx >= 0) raw.substring(0, idx).trimEnd() else raw.trimEnd()
        return output to null
    }

    private fun parseWrappedOutput(
        raw: String,
        cwdMarker: String,
        envMarker: String,
        envEndMarker: String
    ): Triple<String, String?, Map<String, String>> {
        val cwdIdx = raw.indexOf(cwdMarker)
        if (cwdIdx < 0) {
            return Triple(raw.trim(), null, emptyMap())
        }
        val userOutput = raw.substring(0, cwdIdx).trim()
        val afterCwd = raw.substring(cwdIdx + cwdMarker.length)
        val newlineIdx = afterCwd.indexOf('\n')
        val newCwd = if (newlineIdx >= 0) afterCwd.substring(0, newlineIdx).trim() else afterCwd.trim()

        val exports = mutableMapOf<String, String>()
        val envStartIdx = afterCwd.indexOf(envMarker)
        val envEndIdx = afterCwd.indexOf(envEndMarker)
        if (envStartIdx >= 0 && envEndIdx > envStartIdx) {
            val envBlock = afterCwd.substring(envStartIdx + envMarker.length, envEndIdx)
            val exportRegex = Regex("""^(?:export\s+|declare -x\s+)([A-Za-z_][A-Za-z0-9_]*)=(.*)$""")
            for (line in envBlock.lineSequence()) {
                val m = exportRegex.find(line.trim()) ?: continue
                val key = m.groupValues[1]
                var value = m.groupValues[2].trim()
                if ((value.startsWith("'") && value.endsWith("'")) ||
                    (value.startsWith("\"") && value.endsWith("\""))
                ) {
                    if (value.length >= 2) {
                        value = value.substring(1, value.length - 1)
                    }
                }
                exports[key] = value
            }
        }
        return Triple(userOutput, newCwd, exports)
    }

    private fun appendEntry(entry: TerminalEntry) {
        val updated = (_entries.value + entry).takeLast(MAX_TRANSCRIPT_ENTRIES)
        _entries.value = updated
    }

    private fun publishCwds() {
        _currentCwds.value = sessionCwds.toMap()
    }

    private fun publishJobs() {
        _jobs.value = backgroundJobs.values
            .sortedByDescending { it.startedAt }
            .map { it.toInfo() }
    }
}
