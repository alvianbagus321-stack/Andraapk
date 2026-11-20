package com.example.service

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import com.example.JarvisApp
import com.example.model.CustomTool
import com.example.model.ToolResult
import com.example.model.ToolRiskLevel
import com.example.model.ToolScriptType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

data class InstalledGitHubTool(
    val id: String,
    val name: String,
    val source: String,
    val repoUrl: String,
    val repoOwner: String,
    val repoName: String,
    val branch: String = "main",
    val subdir: String = "",
    val ecosystem: String, // NODE_JS, PYTHON, SKILL_MD, SHELL_CLI, GO, RUST, PACKAGE
    val runtimeTarget: String, // "vm", "termux", "knowledge"
    val installDir: String,
    val description: String,
    val setupCommand: String,
    val runTemplate: String,
    val skillSummary: String,
    val docsFiles: List<String> = emptyList(),
    val setupCompleted: Boolean = false,
    val installedAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = 0L,
    val useCount: Int = 0
)

/**
 * GitHubToolManager — Instalasi otomatis, penyimpanan permanen, dan eksekusi berulang
 * untuk tools, CLI, framework, maupun Agent Skills (SKILL.md) dari GitHub, npm, atau pip
 * (contoh: Remotion, remotion-dev/skills, yt-dlp, Playwright, Marp, MCP server, dll.).
 *
 * Fitur utama:
 * - Menerima link GitHub lengkap, format `owner/repo`, `npm:<pkg>`, `pip:<pkg>`, atau nama tool.
 * - Membaca metadata GitHub API + README.md + SKILL.md (termasuk folder skills/<nama>/SKILL.md)
 *   + package.json / pyproject.toml / requirements.txt untuk mendeteksi ekosistem & cara jalan.
 * - Otomatis memilih runtime terbaik:
 *   • `vm` (Komputer Virtual Linux) untuk paket berat (Remotion, Chromium, Playwright, FFmpeg, Node penuh)
 *   • `termux` (Workspace/Termux) untuk CLI Python/Node/Bash ringan
 *   • `knowledge` untuk repositori Agent Skill murni (`SKILL.md` / rules)
 * - Disimpan permanen di `/sdcard/JARVIS/github_tools/registry.json` + SharedPreferences,
 *   serta otomatis didaftarkan ke `ToolManager` dan disuntikkan ke System Prompt AI
 *   agar selalu bisa dipakai berkali-kali di sesi chat mana pun.
 */
object GitHubToolManager {

    private const val TAG = "GitHubToolManager"
    private const val PREFS_NAME = "jarvis_github_tools_prefs"
    private const val KEY_REGISTRY_JSON = "installed_github_tools_v1"

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _installedTools = MutableStateFlow<List<InstalledGitHubTool>>(emptyList())
    val installedTools: StateFlow<List<InstalledGitHubTool>> = _installedTools.asStateFlow()

    @Volatile
    private var initialized = false

    private val heavyVmKeywords = listOf(
        "remotion", "playwright", "puppeteer", "chromium", "chrome",
        "electron", "ffmpeg", "canvas", "sharp", "headless", "docker", "blender"
    )

    fun init(context: Context = JarvisApp.instance) {
        if (initialized) return
        initialized = true
        ioScope.launch {
            loadRegistry(context)
        }
    }

    private fun getBaseDir(context: Context = JarvisApp.instance): File {
        val canUseExternal = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                Environment.getExternalStorageDirectory()?.canWrite() == true
            }
        } catch (_: Exception) {
            false
        }
        val base = if (canUseExternal) {
            File(Environment.getExternalStorageDirectory(), "JARVIS/github_tools")
        } else {
            File(context.filesDir, "github_tools")
        }
        if (!base.exists()) base.mkdirs()
        return base
    }

    private fun getRegistryFile(context: Context = JarvisApp.instance): File =
        File(getBaseDir(context), "registry.json")

    private fun loadRegistry(context: Context = JarvisApp.instance) {
        try {
            val file = getRegistryFile(context)
            val rawJson = when {
                file.exists() && file.length() > 2 -> file.readText(Charsets.UTF_8)
                else -> context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_REGISTRY_JSON, null)
            }
            if (!rawJson.isNullOrBlank()) {
                val arr = JSONArray(rawJson)
                val parsed = mutableListOf<InstalledGitHubTool>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val docsArr = obj.optJSONArray("docsFiles")
                    val docs = mutableListOf<String>()
                    if (docsArr != null) {
                        for (j in 0 until docsArr.length()) {
                            val d = docsArr.optString(j, "").trim()
                            if (d.isNotEmpty()) docs.add(d)
                        }
                    }
                    val id = obj.optString("id", "").trim()
                    if (id.isEmpty()) continue
                    parsed.add(
                        InstalledGitHubTool(
                            id = id,
                            name = obj.optString("name", id),
                            source = obj.optString("source", ""),
                            repoUrl = obj.optString("repoUrl", ""),
                            repoOwner = obj.optString("repoOwner", ""),
                            repoName = obj.optString("repoName", ""),
                            branch = obj.optString("branch", "main"),
                            subdir = obj.optString("subdir", ""),
                            ecosystem = obj.optString("ecosystem", "SHELL_CLI"),
                            runtimeTarget = obj.optString("runtimeTarget", "termux"),
                            installDir = obj.optString("installDir", ""),
                            description = obj.optString("description", ""),
                            setupCommand = obj.optString("setupCommand", ""),
                            runTemplate = obj.optString("runTemplate", ""),
                            skillSummary = obj.optString("skillSummary", ""),
                            docsFiles = docs,
                            setupCompleted = obj.optBoolean("setupCompleted", false),
                            installedAt = obj.optLong("installedAt", System.currentTimeMillis()),
                            lastUsedAt = obj.optLong("lastUsedAt", 0L),
                            useCount = obj.optInt("useCount", 0)
                        )
                    )
                }
                _installedTools.value = parsed
                // Pastikan setiap GitHub tool yang tersimpan juga terdaftar di ToolManager
                parsed.forEach { syncToToolManager(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gagal memuat registry GitHub tools: ${e.message}")
        }
    }

    private fun saveRegistry(context: Context = JarvisApp.instance) {
        try {
            val arr = JSONArray()
            for (t in _installedTools.value) {
                val obj = JSONObject().apply {
                    put("id", t.id)
                    put("name", t.name)
                    put("source", t.source)
                    put("repoUrl", t.repoUrl)
                    put("repoOwner", t.repoOwner)
                    put("repoName", t.repoName)
                    put("branch", t.branch)
                    put("subdir", t.subdir)
                    put("ecosystem", t.ecosystem)
                    put("runtimeTarget", t.runtimeTarget)
                    put("installDir", t.installDir)
                    put("description", t.description)
                    put("setupCommand", t.setupCommand)
                    put("runTemplate", t.runTemplate)
                    put("skillSummary", t.skillSummary)
                    put("docsFiles", JSONArray(t.docsFiles))
                    put("setupCompleted", t.setupCompleted)
                    put("installedAt", t.installedAt)
                    put("lastUsedAt", t.lastUsedAt)
                    put("useCount", t.useCount)
                }
                arr.put(obj)
            }
            val serialized = arr.toString(2)
            runCatching { getRegistryFile(context).writeText(serialized, Charsets.UTF_8) }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_REGISTRY_JSON, serialized)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Gagal menyimpan registry GitHub tools: ${e.message}")
        }
    }

    private fun upsertTool(tool: InstalledGitHubTool) {
        val current = _installedTools.value.toMutableList()
        val idx = current.indexOfFirst { it.id.equals(tool.id, ignoreCase = true) }
        if (idx >= 0) {
            current[idx] = tool
        } else {
            current.add(0, tool)
        }
        _installedTools.value = current
        saveRegistry()
        syncToToolManager(tool)
    }

    private fun syncToToolManager(tool: InstalledGitHubTool) {
        val registeredId = "gh_${tool.id}"
        val statusTag = if (tool.setupCompleted) "siap" else "perlu setup runtime"
        val customTool = CustomTool(
            id = registeredId,
            name = "${tool.name} (GitHub)",
            description = "${tool.description.take(180)} [ekosistem=${tool.ecosystem}, runtime=${tool.runtimeTarget}, status=$statusTag, template=${tool.runTemplate}]",
            category = "GitHub & Skills",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "github_tool_invoke:${tool.id}",
            parametersSchema = """{"command": "${tool.runTemplate.replace("\"", "'")}", "args": "", "workdir": ""}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = false,
            createdByAi = true,
            createdAt = tool.installedAt
        )
        ToolManager.addOrUpdateTool(customTool)
    }

    /**
     * Ringkasan ringkas untuk disuntikkan otomatis ke System Prompt AI pada setiap chat,
     * agar AI selalu ingat seluruh tool & skill GitHub yang sudah pernah dipasang.
     */
    fun buildSystemPromptSummary(): String {
        if (!initialized) init()
        val list = _installedTools.value
        if (list.isEmpty()) return ""
        return buildString {
            appendLine("TOOLS & SKILLS GITHUB TERPASANG PERMANEN (${list.size}):")
            list.take(20).forEach { t ->
                val stateStr = if (t.setupCompleted) "SIAP" else "DOCS_TERSIMPAN/PERLU_SETUP"
                appendLine("- id=${t.id} | ${t.name} (${t.ecosystem}, runtime=${t.runtimeTarget}, status=$stateStr)")
                appendLine("  • sumber: ${t.repoUrl.ifBlank { t.source }} | run: ${t.runTemplate}")
                if (t.skillSummary.isNotBlank()) {
                    appendLine("  • panduan singkat: ${t.skillSummary.replace("\n", " ").take(240)}")
                }
            }
            append("Gunakan `github_tool_read_docs` bila butuh panduan SKILL.md/README lengkap, dan `github_tool_run` untuk menjalankannya berulang kali.")
        }.trim()
    }

    // =========================================================================
    // Parsing Sumber & GitHub HTTP Fetch
    // =========================================================================

    private data class ParsedSource(
        val kind: String, // "github", "npm", "pip", "search"
        val owner: String = "",
        val repo: String = "",
        val branch: String = "",
        val subdir: String = "",
        val pkgName: String = "",
        val rawQuery: String = ""
    )

    private fun parseSource(raw: String): ParsedSource {
        val clean = raw.trim()
            .removePrefix("npx skills add ")
            .removePrefix("git clone ")
            .trim()
            .trimEnd('/')

        if (clean.startsWith("npm:", ignoreCase = true) || clean.startsWith("npx:", ignoreCase = true)) {
            val pkg = clean.substringAfter(":").trim()
            return ParsedSource(kind = "npm", pkgName = pkg, rawQuery = clean)
        }
        if (clean.startsWith("pip:", ignoreCase = true) || clean.startsWith("pypi:", ignoreCase = true)) {
            val pkg = clean.substringAfter(":").trim()
            return ParsedSource(kind = "pip", pkgName = pkg, rawQuery = clean)
        }

        // Full GitHub URL: https://github.com/owner/repo(/tree/branch/subdir)?
        val ghUrlRegex = Regex(
            """(?:https?://)?(?:www\.)?github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?(?:/(?:tree|blob)/([^/]+)(?:/(.*))?)?$""",
            RegexOption.IGNORE_CASE
        )
        ghUrlRegex.find(clean)?.let { m ->
            return ParsedSource(
                kind = "github",
                owner = m.groupValues[1],
                repo = m.groupValues[2],
                branch = m.groupValues.getOrNull(3).orEmpty(),
                subdir = m.groupValues.getOrNull(4).orEmpty().trimEnd('/'),
                rawQuery = clean
            )
        }

        // Shorthand: owner/repo
        val shortRegex = Regex("""^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$""")
        shortRegex.find(clean)?.let { m ->
            return ParsedSource(
                kind = "github",
                owner = m.groupValues[1],
                repo = m.groupValues[2].removeSuffix(".git"),
                rawQuery = clean
            )
        }

        return ParsedSource(kind = "search", rawQuery = clean)
    }

    private fun httpGetText(url: String): Pair<Int, String> {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "AndraControlAgent/2.62")
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                Pair(resp.code, resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            Pair(-1, e.message ?: "network error")
        }
    }

    private fun slugify(raw: String): String {
        return raw.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(40)
            .ifBlank { "gh_tool_${System.currentTimeMillis() % 10000}" }
    }

    private fun isHeavyVmRepo(name: String, description: String, packageJson: String): Boolean {
        val hay = "$name $description $packageJson".lowercase(Locale.US)
        return heavyVmKeywords.any { hay.contains(it) }
    }

    // =========================================================================
    // 1. Install & Auto-Setup dari GitHub / npm / pip / nama tool
    // =========================================================================

    suspend fun installFromSource(
        source: String,
        preferredRuntime: String = "auto", // auto | vm | termux | knowledge
        customAlias: String = "",
        runSetupNow: Boolean = true
    ): ToolResult = withContext(Dispatchers.IO) {
        if (!initialized) init()
        val rawSource = source.trim()
        if (rawSource.isEmpty()) {
            return@withContext ToolResult("error", message = "Parameter 'source' wajib diisi (contoh: https://github.com/remotion-dev/remotion, remotion-dev/skills, npm:remotion, atau nama tool).")
        }

        var parsed = parseSource(rawSource)

        // Jika pengguna hanya menyebut nama (mis. "remotion"), cari repo resminya di GitHub Search API
        if (parsed.kind == "search") {
            val encoded = URLEncoder.encode(parsed.rawQuery, "UTF-8")
            val (code, body) = httpGetText("https://api.github.com/search/repositories?q=$encoded&sort=stars&order=desc&per_page=3")
            if (code == 200) {
                val items = runCatching { JSONObject(body).optJSONArray("items") }.getOrNull()
                if (items != null && items.length() > 0) {
                    val top = items.getJSONObject(0)
                    val fullName = top.optString("full_name", "")
                    val parts = fullName.split("/")
                    if (parts.size == 2) {
                        parsed = ParsedSource(
                            kind = "github",
                            owner = parts[0],
                            repo = parts[1],
                            branch = top.optString("default_branch", "main"),
                            rawQuery = rawSource
                        )
                    }
                }
            }
            if (parsed.kind == "search") {
                return@withContext ToolResult(
                    "error",
                    message = "Tidak menemukan repository GitHub untuk '$rawSource'. Kirim link GitHub langsung (https://github.com/owner/repo) atau format npm:<paket> / pip:<paket>."
                )
            }
        }

        // Jalur paket langsung npm:<pkg> atau pip:<pkg>
        if (parsed.kind == "npm" || parsed.kind == "pip") {
            return@withContext installDirectPackage(parsed, preferredRuntime, customAlias, runSetupNow)
        }

        // Jalur Repository GitHub
        val owner = parsed.owner
        val repo = parsed.repo
        val repoUrl = "https://github.com/$owner/$repo"

        // 1. Ambil metadata repo via GitHub API
        val (metaCode, metaBody) = httpGetText("https://api.github.com/repos/$owner/$repo")
        var description = "Tool dari GitHub ($owner/$repo)"
        var defaultBranch = parsed.branch.ifBlank { "main" }
        var language = ""
        if (metaCode == 200) {
            runCatching {
                val obj = JSONObject(metaBody)
                description = obj.optString("description", "").ifBlank { description }
                if (parsed.branch.isBlank()) {
                    defaultBranch = obj.optString("default_branch", "main").ifBlank { "main" }
                }
                language = obj.optString("language", "")
            }
        }

        val slug = slugify(customAlias.ifBlank { if (repo.equals("skills", ignoreCase = true)) "${owner}_skills" else repo })
        val localDir = File(getBaseDir(), slug).apply { mkdirs() }
        val docsDir = File(localDir, "docs").apply { mkdirs() }

        // 2. Inspeksi isi root repo (atau subdir) untuk deteksi ekosistem & file SKILL.md / README.md
        val contentsPath = if (parsed.subdir.isNotBlank()) "/${parsed.subdir}" else ""
        val (treeCode, treeBody) = httpGetText("https://api.github.com/repos/$owner/$repo/contents$contentsPath?ref=$defaultBranch")
        val rootFiles = mutableMapOf<String, String>() // name -> download_url
        val rootDirs = mutableListOf<String>()
        if (treeCode == 200) {
            runCatching {
                val arr = JSONArray(treeBody)
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val name = item.optString("name", "")
                    val type = item.optString("type", "")
                    val dlUrl = item.optString("download_url", "")
                    if (type == "file" && name.isNotBlank()) {
                        rootFiles[name] = dlUrl
                    } else if (type == "dir" && name.isNotBlank()) {
                        rootDirs.add(name)
                    }
                }
            }
        }

        val savedDocs = mutableListOf<String>()
        var readmeText = ""
        var skillText = ""
        var packageJsonText = ""

        // Unduh README.md
        val readmeKey = rootFiles.keys.firstOrNull { it.equals("README.md", ignoreCase = true) }
        val readmeUrl = readmeKey?.let { rootFiles[it] }
            ?: "https://raw.githubusercontent.com/$owner/$repo/$defaultBranch/README.md"
        val (rCode, rBody) = httpGetText(readmeUrl)
        if (rCode == 200 && rBody.isNotBlank()) {
            readmeText = rBody
            val rf = File(docsDir, "README.md")
            rf.writeText(rBody.take(80_000), Charsets.UTF_8)
            savedDocs.add(rf.absolutePath)
        }

        // Unduh SKILL.md di root (jika ada)
        val skillKey = rootFiles.keys.firstOrNull { it.equals("SKILL.md", ignoreCase = true) }
        if (skillKey != null && !rootFiles[skillKey].isNullOrBlank()) {
            val (sCode, sBody) = httpGetText(rootFiles[skillKey]!!)
            if (sCode == 200 && sBody.isNotBlank()) {
                skillText = sBody
                val sf = File(docsDir, "SKILL.md")
                sf.writeText(sBody.take(80_000), Charsets.UTF_8)
                savedDocs.add(sf.absolutePath)
            }
        }

        // Cek folder `skills/` (pola resmi `remotion-dev/skills` & Agent Skills!)
        val hasSkillsDir = rootDirs.any { it.equals("skills", ignoreCase = true) }
        val discoveredSkillNames = mutableListOf<String>()
        if (hasSkillsDir) {
            val skillsSubPath = if (parsed.subdir.isNotBlank()) "${parsed.subdir}/skills" else "skills"
            val (skCode, skBody) = httpGetText("https://api.github.com/repos/$owner/$repo/contents/$skillsSubPath?ref=$defaultBranch")
            if (skCode == 200) {
                runCatching {
                    val arr = JSONArray(skBody)
                    for (i in 0 until arr.length().coerceAtMost(12)) {
                        val item = arr.getJSONObject(i)
                        val skName = item.optString("name", "")
                        val skType = item.optString("type", "")
                        if (skType == "dir" && skName.isNotBlank()) {
                            val rawSkillUrl = "https://raw.githubusercontent.com/$owner/$repo/$defaultBranch/$skillsSubPath/$skName/SKILL.md"
                            val (fCode, fBody) = httpGetText(rawSkillUrl)
                            if (fCode == 200 && fBody.isNotBlank()) {
                                discoveredSkillNames.add(skName)
                                val outSf = File(docsDir, "skill_${slugify(skName)}.md")
                                outSf.writeText(fBody.take(60_000), Charsets.UTF_8)
                                savedDocs.add(outSf.absolutePath)
                                if (skillText.isBlank()) skillText = fBody
                            }
                        }
                    }
                }
            }
        }

        // Unduh package.json jika ada
        val pkgJsonUrl = rootFiles["package.json"]
        if (!pkgJsonUrl.isNullOrBlank()) {
            val (pCode, pBody) = httpGetText(pkgJsonUrl)
            if (pCode == 200) {
                packageJsonText = pBody
                File(docsDir, "package.json").writeText(pBody.take(40_000), Charsets.UTF_8)
            }
        }

        // 3. Deteksi Ekosistem, Setup Command, dan Run Template
        val hasPackageJson = rootFiles.containsKey("package.json")
        val hasPython = rootFiles.containsKey("pyproject.toml") ||
            rootFiles.containsKey("requirements.txt") ||
            rootFiles.containsKey("setup.py")
        val hasGo = rootFiles.containsKey("go.mod")
        val hasRust = rootFiles.containsKey("Cargo.toml")
        val hasShell = rootFiles.keys.any { it.endsWith(".sh", ignoreCase = true) } || rootFiles.containsKey("Makefile")
        val isPureSkillRepo = (skillText.isNotBlank() || discoveredSkillNames.isNotEmpty()) &&
            !hasPackageJson && !hasPython && !hasGo && !hasRust

        val ecosystem = when {
            isPureSkillRepo -> "SKILL_MD"
            hasPackageJson -> "NODE_JS"
            hasPython -> "PYTHON"
            hasGo -> "GO"
            hasRust -> "RUST"
            hasShell -> "SHELL_CLI"
            language.equals("Python", ignoreCase = true) -> "PYTHON"
            language.equals("TypeScript", ignoreCase = true) || language.equals("JavaScript", ignoreCase = true) -> "NODE_JS"
            else -> "SHELL_CLI"
        }

        val needsVm = isHeavyVmRepo("$owner/$repo", description, packageJsonText)
        val runtimeTarget = when (preferredRuntime.lowercase().trim()) {
            "vm" -> "vm"
            "termux" -> "termux"
            "knowledge" -> "knowledge"
            else -> when {
                isPureSkillRepo -> "knowledge"
                needsVm -> "vm"
                VirtualComputerManager.status.value.state == VmState.RUNNING -> "vm"
                else -> "termux"
            }
        }

        val (setupCmd, runTemplate) = deriveCommands(
            ecosystem = ecosystem,
            repoName = repo,
            packageJsonText = packageJsonText,
            rootFiles = rootFiles.keys
        )

        val targetInstallDir = if (runtimeTarget == "vm") "/root/github_tools/$slug" else localDir.absolutePath
        val skillSummary = buildCompactSummary(
            repoFullName = "$owner/$repo",
            description = description,
            discoveredSkills = discoveredSkillNames,
            skillText = skillText,
            readmeText = readmeText
        )

        var toolRecord = InstalledGitHubTool(
            id = slug,
            name = repo.replace('-', ' ').replace('_', ' ')
                .split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } },
            source = rawSource,
            repoUrl = repoUrl,
            repoOwner = owner,
            repoName = repo,
            branch = defaultBranch,
            subdir = parsed.subdir,
            ecosystem = ecosystem,
            runtimeTarget = runtimeTarget,
            installDir = targetInstallDir,
            description = description,
            setupCommand = setupCmd,
            runTemplate = runTemplate,
            skillSummary = skillSummary,
            docsFiles = savedDocs,
            setupCompleted = isPureSkillRepo
        )

        // 4. Jalankan clone & instalasi dependensi jika diminta dan runtime tersedia
        var setupOutputNote = ""
        if (isPureSkillRepo) {
            setupOutputNote = "Repositori Skill AI (${discoveredSkillNames.size.coerceAtLeast(1)} file SKILL.md) berhasil diunduh & disimpan permanen."
        } else if (runSetupNow) {
            val setupRes = performRuntimeSetup(toolRecord)
            if (setupRes.status == "ok") {
                toolRecord = toolRecord.copy(setupCompleted = true)
                setupOutputNote = setupRes.result ?: "Setup selesai."
            } else {
                setupOutputNote = setupRes.message ?: "Setup runtime tertunda."
            }
        } else {
            setupOutputNote = "Metadata & dokumentasi tersimpan. Panggil github_tool_setup untuk instalasi paket runtime."
        }

        upsertTool(toolRecord)

        val responseText = buildString {
            appendLine("Tool GitHub '${toolRecord.name}' (id: ${toolRecord.id}) berhasil disimpan permanen!")
            appendLine("- Sumber: $repoUrl (branch: $defaultBranch)")
            appendLine("- Ekosistem: ${toolRecord.ecosystem} | Runtime: ${toolRecord.runtimeTarget}")
            appendLine("- Status Setup: ${if (toolRecord.setupCompleted) "SIAP DIPAKAI" else "PERLU SETUP RUNTIME"}")
            appendLine("- Direktori: ${toolRecord.installDir}")
            appendLine("- Template Eksekusi: ${toolRecord.runTemplate}")
            if (savedDocs.isNotEmpty()) {
                appendLine("- Dokumentasi tersimpan (${savedDocs.size} file): ${savedDocs.joinToString(", ") { File(it).name }}")
            }
            if (setupOutputNote.isNotBlank()) {
                appendLine()
                appendLine("Hasil Setup:")
                appendLine(setupOutputNote.take(1800))
            }
            if (!toolRecord.setupCompleted && toolRecord.runtimeTarget == "vm") {
                appendLine()
                append("CATATAN: Tool ini membutuhkan lingkungan Linux penuh (Node.js/Chromium/FFmpeg) di Komputer Virtual. Panggil `vm_request_create` terlebih dahulu agar pengguna menyetujui VM, lalu panggil `github_tool_setup` dengan {\"id\": \"${toolRecord.id}\"}.")
            }
        }.trim()

        ToolResult("ok", result = responseText)
    }

    private suspend fun installDirectPackage(
        parsed: ParsedSource,
        preferredRuntime: String,
        customAlias: String,
        runSetupNow: Boolean
    ): ToolResult {
        val pkg = parsed.pkgName.trim()
        if (pkg.isEmpty()) {
            return ToolResult("error", message = "Nama paket tidak boleh kosong.")
        }
        val slug = slugify(customAlias.ifBlank { pkg.substringAfterLast("/") })
        val isNpm = parsed.kind == "npm"
        val ecosystem = if (isNpm) "NODE_JS" else "PYTHON"
        val needsVm = isHeavyVmRepo(pkg, "", "")
        val runtimeTarget = when (preferredRuntime.lowercase().trim()) {
            "vm" -> "vm"
            "termux" -> "termux"
            else -> if (needsVm || VirtualComputerManager.status.value.state == VmState.RUNNING) "vm" else "termux"
        }

        val localDir = File(getBaseDir(), slug).apply { mkdirs() }
        val installDir = if (runtimeTarget == "vm") "/root/github_tools/$slug" else localDir.absolutePath
        val setupCmd = if (isNpm) {
            "mkdir -p '$installDir' && cd '$installDir' && ([ -f package.json ] || npm init -y) && npm install $pkg"
        } else {
            "mkdir -p '$installDir' && pip3 install --break-system-packages $pkg || pip install $pkg"
        }
        val runTemplate = if (isNpm) "npx $pkg {args}" else "$pkg {args}"

        var record = InstalledGitHubTool(
            id = slug,
            name = pkg,
            source = parsed.rawQuery,
            repoUrl = if (isNpm) "https://www.npmjs.com/package/$pkg" else "https://pypi.org/project/$pkg",
            repoOwner = parsed.kind,
            repoName = pkg,
            ecosystem = ecosystem,
            runtimeTarget = runtimeTarget,
            installDir = installDir,
            description = "Paket ${if (isNpm) "npm/Node.js" else "Python/pip"}: $pkg",
            setupCommand = setupCmd,
            runTemplate = runTemplate,
            skillSummary = "Paket $pkg (${parsed.kind}). Jalankan via: $runTemplate",
            setupCompleted = false
        )

        var setupNote = ""
        if (runSetupNow) {
            val res = performRuntimeSetup(record)
            if (res.status == "ok") {
                record = record.copy(setupCompleted = true)
                setupNote = res.result ?: "Paket berhasil dipasang."
            } else {
                setupNote = res.message ?: "Instalasi paket tertunda."
            }
        }
        upsertTool(record)

        return ToolResult(
            "ok",
            result = "Paket '${record.name}' (id: ${record.id}) disimpan permanen.\n" +
                "- Runtime: ${record.runtimeTarget} | Status: ${if (record.setupCompleted) "SIAP" else "PERLU SETUP"}\n" +
                "- Template: ${record.runTemplate}\n" +
                if (setupNote.isNotBlank()) "\n$setupNote" else ""
        )
    }

    private fun deriveCommands(
        ecosystem: String,
        repoName: String,
        packageJsonText: String,
        rootFiles: Set<String>
    ): Pair<String, String> {
        if (ecosystem == "SKILL_MD") {
            return Pair("", "github_tool_read_docs")
        }
        if (ecosystem == "NODE_JS") {
            val binName = runCatching {
                val obj = JSONObject(packageJsonText)
                val bin = obj.opt("bin")
                when (bin) {
                    is String -> obj.optString("name", repoName).substringAfterLast("/")
                    is JSONObject -> bin.keys().asSequence().firstOrNull() ?: repoName
                    else -> obj.optString("name", repoName).substringAfterLast("/")
                }
            }.getOrDefault(repoName)

            val isRemotion = repoName.contains("remotion", ignoreCase = true) ||
                packageJsonText.contains("@remotion/cli", ignoreCase = true)

            val setup = if (isRemotion) {
                "if ! command -v node >/dev/null 2>&1; then apt-get update -y && apt-get install -y nodejs npm ffmpeg; fi; npm install --no-audit --no-fund"
            } else {
                "if ! command -v node >/dev/null 2>&1; then apt-get update -y && apt-get install -y nodejs npm; fi; npm install --no-audit --no-fund"
            }
            val runTpl = if (isRemotion) "npx remotion {args}" else "npx $binName {args}"
            return Pair(setup, runTpl)
        }
        if (ecosystem == "PYTHON") {
            val setup = when {
                rootFiles.contains("requirements.txt") -> "pip3 install --break-system-packages -r requirements.txt || pip install -r requirements.txt"
                rootFiles.contains("pyproject.toml") || rootFiles.contains("setup.py") -> "pip3 install --break-system-packages -e . || pip install -e ."
                else -> "python3 --version"
            }
            val entry = rootFiles.firstOrNull { it == "main.py" || it == "cli.py" || it == "app.py" }
            val runTpl = if (entry != null) "python3 $entry {args}" else "python3 -m ${repoName.replace('-', '_')} {args}"
            return Pair(setup, runTpl)
        }
        if (ecosystem == "GO") {
            return Pair("go build -o ./tool_bin .", "./tool_bin {args}")
        }
        if (ecosystem == "RUST") {
            return Pair("cargo build --release", "./target/release/$repoName {args}")
        }
        val shFile = rootFiles.firstOrNull { it.endsWith(".sh", ignoreCase = true) }
        return if (shFile != null) {
            Pair("chmod +x *.sh", "bash $shFile {args}")
        } else {
            Pair("ls -la", "ls -la")
        }
    }

    private fun buildCompactSummary(
        repoFullName: String,
        description: String,
        discoveredSkills: List<String>,
        skillText: String,
        readmeText: String
    ): String {
        val sb = StringBuilder()
        if (description.isNotBlank()) sb.append(description.trim())
        if (discoveredSkills.isNotEmpty()) {
            sb.append(" | Skills: ").append(discoveredSkills.joinToString(", "))
        }
        val primaryDoc = skillText.ifBlank { readmeText }
        if (primaryDoc.isNotBlank()) {
            val cleaned = primaryDoc
                .replace(Regex("```[\\s\\S]*?```"), " ")
                .replace(Regex("#+\\s*"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(260)
            if (cleaned.isNotBlank()) {
                sb.append(" — ").append(cleaned)
            }
        }
        return sb.toString().ifBlank { repoFullName }.take(400)
    }

    // =========================================================================
    // 2. Setup Runtime (Clone + Install Dependencies di VM atau Termux)
    // =========================================================================

    suspend fun setupToolById(id: String, forceRuntime: String = ""): ToolResult = withContext(Dispatchers.IO) {
        if (!initialized) init()
        val tool = findTool(id)
            ?: return@withContext ToolResult("error", message = "Tool GitHub '$id' tidak ditemukan di registry.")
        val updated = if (forceRuntime.isNotBlank()) tool.copy(runtimeTarget = forceRuntime.lowercase().trim()) else tool
        val res = performRuntimeSetup(updated)
        if (res.status == "ok") {
            upsertTool(updated.copy(setupCompleted = true))
        } else {
            upsertTool(updated)
        }
        res
    }

    private suspend fun performRuntimeSetup(tool: InstalledGitHubTool): ToolResult {
        if (tool.runtimeTarget == "knowledge" || tool.ecosystem == "SKILL_MD") {
            return ToolResult("ok", result = "Skill '${tool.name}' siap dipakai (dokumentasi SKILL.md tersimpan lokal).")
        }

        val cloneOrPrepCmd = if (tool.repoOwner in listOf("npm", "pip")) {
            tool.setupCommand
        } else {
            val cloneTarget = tool.installDir
            val cloneUrl = "${tool.repoUrl}.git"
            buildString {
                append("mkdir -p '").append(File(cloneTarget).parent ?: "/root/github_tools").append("' && ")
                append("if [ -d '").append(cloneTarget).append("/.git' ]; then ")
                append("cd '").append(cloneTarget).append("' && git pull --ff-only || true; ")
                append("else rm -rf '").append(cloneTarget).append("' && git clone --depth 1 '").append(cloneUrl).append("' '").append(cloneTarget).append("'; fi && ")
                append("cd '").append(cloneTarget).append("' && ")
                append(tool.setupCommand.ifBlank { "ls -la" })
            }
        }

        return if (tool.runtimeTarget == "vm") {
            if (VirtualComputerManager.status.value.state != VmState.RUNNING) {
                return ToolResult(
                    "error",
                    errorCode = "VM_NOT_RUNNING",
                    message = "Komputer Virtual belum aktif. Panggil `vm_request_create` (jika belum disetujui) atau `vm_control` action='start', lalu jalankan `github_tool_setup` untuk '${tool.id}'."
                )
            }
            VirtualComputerManager.execInVm(
                command = cloneOrPrepCmd,
                workdir = "/root",
                timeoutSec = 240,
                source = "AI"
            )
        } else {
            AgentTerminalManager.exec(
                command = cloneOrPrepCmd,
                sessionId = "gh_${tool.id}",
                timeoutSec = 180,
                source = "AI"
            )
        }
    }

    // =========================================================================
    // 3. Jalankan Berulang Kali (`github_tool_run`), Baca Docs, List, Hapus
    // =========================================================================

    fun findTool(idOrName: String): InstalledGitHubTool? {
        if (!initialized) init()
        val q = idOrName.trim().removePrefix("gh_")
        return _installedTools.value.firstOrNull { it.id.equals(q, ignoreCase = true) }
            ?: _installedTools.value.firstOrNull { it.name.equals(q, ignoreCase = true) }
            ?: _installedTools.value.firstOrNull { it.repoName.equals(q, ignoreCase = true) }
    }

    suspend fun runInstalledTool(
        id: String,
        command: String = "",
        args: String = "",
        workdir: String = "",
        timeoutSec: Int = 120
    ): ToolResult = withContext(Dispatchers.IO) {
        if (!initialized) init()
        val tool = findTool(id)
            ?: return@withContext ToolResult("error", message = "Tool GitHub '$id' belum terpasang. Pasang dulu dengan `github_tool_install`.")

        if (tool.runtimeTarget == "knowledge" || tool.ecosystem == "SKILL_MD") {
            return@withContext readToolDocs(tool.id, fileName = "", query = args.ifBlank { command })
        }

        val finalCmd = when {
            command.isNotBlank() -> command.trim()
            tool.runTemplate.contains("{args}") -> tool.runTemplate.replace("{args}", args.trim()).trim()
            args.isNotBlank() -> "${tool.runTemplate} ${args.trim()}".trim()
            else -> tool.runTemplate
        }

        val targetDir = workdir.ifBlank { tool.installDir }

        val execRes = if (tool.runtimeTarget == "vm") {
            if (VirtualComputerManager.status.value.state != VmState.RUNNING) {
                return@withContext ToolResult(
                    "error",
                    errorCode = "VM_NOT_RUNNING",
                    message = "Tool '${tool.name}' dikonfigurasi berjalan di Komputer Virtual, tetapi VM sedang tidak aktif. Minta izin dengan `vm_request_create` atau nyalakan dengan `vm_control` action='start'."
                )
            }
            VirtualComputerManager.execInVm(
                command = finalCmd,
                workdir = targetDir,
                timeoutSec = timeoutSec,
                source = "AI"
            )
        } else {
            val wrapped = "mkdir -p '$targetDir' && cd '$targetDir' && $finalCmd"
            AgentTerminalManager.exec(
                command = wrapped,
                sessionId = "gh_${tool.id}",
                timeoutSec = timeoutSec,
                source = "AI"
            )
        }

        upsertTool(
            tool.copy(
                lastUsedAt = System.currentTimeMillis(),
                useCount = tool.useCount + 1
            )
        )
        execRes
    }

    suspend fun readToolDocs(
        id: String,
        fileName: String = "",
        query: String = ""
    ): ToolResult = withContext(Dispatchers.IO) {
        if (!initialized) init()
        val tool = findTool(id)
            ?: return@withContext ToolResult("error", message = "Tool GitHub '$id' tidak ditemukan.")

        if (tool.docsFiles.isEmpty()) {
            return@withContext ToolResult(
                "ok",
                result = "Tool: ${tool.name} (${tool.repoUrl})\nDeskripsi: ${tool.description}\nRingkasan: ${tool.skillSummary}\nTemplate: ${tool.runTemplate}"
            )
        }

        val targetFile = if (fileName.isNotBlank()) {
            tool.docsFiles.map { File(it) }.firstOrNull {
                it.name.equals(fileName, ignoreCase = true) || it.name.contains(fileName, ignoreCase = true)
            }
        } else {
            tool.docsFiles.map { File(it) }.firstOrNull { it.name.contains("skill", ignoreCase = true) }
                ?: File(tool.docsFiles.first())
        }

        if (targetFile == null || !targetFile.exists()) {
            return@withContext ToolResult(
                "error",
                message = "File dokumentasi '$fileName' tidak ditemukan. Tersedia: ${tool.docsFiles.joinToString(", ") { File(it).name }}"
            )
        }

        val rawText = runCatching { targetFile.readText(Charsets.UTF_8) }.getOrDefault("")
        val output = if (query.isNotBlank()) {
            val matchingLines = rawText.lines().mapIndexedNotNull { idx, line ->
                if (line.contains(query, ignoreCase = true)) "${idx + 1}: $line" else null
            }
            if (matchingLines.isEmpty()) {
                "Tidak ditemukan '$query' di ${targetFile.name}. Cuplikan awal:\n${rawText.take(3500)}"
            } else {
                "Hasil pencarian '$query' di ${targetFile.name} (${matchingLines.size} baris):\n" +
                    matchingLines.take(80).joinToString("\n")
            }
        } else {
            rawText.take(8000) + if (rawText.length > 8000) "\n... [dipotong dari ${rawText.length} karakter; gunakan parameter 'query' untuk bagian spesifik]" else ""
        }

        val allNames = tool.docsFiles.joinToString(", ") { File(it).name }
        ToolResult(
            "ok",
            result = "Dokumentasi '${tool.name}' (${targetFile.name} | file tersedia: $allNames):\n\n$output"
        )
    }

    fun listInstalledToolsReport(): ToolResult {
        if (!initialized) init()
        val list = _installedTools.value
        if (list.isEmpty()) {
            return ToolResult(
                "ok",
                result = "Belum ada tool/skill GitHub yang terpasang. Gunakan `github_tool_install` dengan link GitHub (contoh: https://github.com/remotion-dev/remotion atau remotion-dev/skills)."
            )
        }
        val sb = StringBuilder()
        sb.appendLine("Daftar Tool & Skill GitHub Terpasang Permanen (${list.size}):")
        list.forEachIndexed { i, t ->
            val st = if (t.setupCompleted) "SIAP" else "PERLU SETUP"
            sb.appendLine("${i + 1}. [${t.id}] ${t.name} — ${t.ecosystem} · runtime=${t.runtimeTarget} · $st · dipakai ${t.useCount}x")
            sb.appendLine("   Sumber: ${t.repoUrl.ifBlank { t.source }} | Run: ${t.runTemplate}")
        }
        return ToolResult("ok", result = sb.toString().trim())
    }

    suspend fun removeTool(id: String): ToolResult = withContext(Dispatchers.IO) {
        if (!initialized) init()
        val tool = findTool(id)
            ?: return@withContext ToolResult("error", message = "Tool GitHub '$id' tidak ditemukan.")

        val updated = _installedTools.value.filterNot { it.id.equals(tool.id, ignoreCase = true) }
        _installedTools.value = updated
        saveRegistry()
        ToolManager.deleteTool("gh_${tool.id}")

        // Bersihkan folder lokal docs/workspace
        runCatching { File(getBaseDir(), tool.id).deleteRecursively() }
        if (tool.runtimeTarget == "vm" && VirtualComputerManager.status.value.state == VmState.RUNNING) {
            runCatching {
                VirtualComputerManager.execInVm("rm -rf '${tool.installDir}'", timeoutSec = 20, source = "SYSTEM")
            }
        }

        ToolResult("ok", result = "Tool GitHub '${tool.name}' (${tool.id}) berhasil dihapus dari registry permanen.")
    }
}
