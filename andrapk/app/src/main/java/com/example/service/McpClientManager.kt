package com.example.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
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
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

enum class McpAuthMode {
    NONE,
    OAUTH,
    BEARER_TOKEN,
    OAUTH_OR_TOKEN
}

enum class McpConnectionState {
    DISCONNECTED,
    AUTHORIZING,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class McpRemoteTool(
    val serverId: String,
    val name: String,
    val description: String,
    val inputSchemaJson: String = "{}"
)

data class McpServerPreset(
    val id: String,
    val name: String,
    val category: String,
    val endpointUrl: String,
    val description: String,
    val authMode: McpAuthMode,
    val tokenHelpUrl: String = "",
    val tokenPlaceholder: String = "Tempel token / API key",
    val isBuiltInPreset: Boolean = true
)

data class McpServerConfig(
    val id: String,
    val name: String,
    val category: String,
    val endpointUrl: String,
    val description: String,
    val authMode: McpAuthMode,
    val tokenHelpUrl: String = "",
    val isBuiltInPreset: Boolean = true,
    val accessToken: String = "",
    val refreshToken: String = "",
    val tokenExpiresAt: Long = 0L,
    val oauthClientId: String = "",
    val oauthTokenEndpoint: String = "",
    val sessionId: String = "",
    val state: McpConnectionState = McpConnectionState.DISCONNECTED,
    val statusDetail: String = "Belum terhubung",
    val tools: List<McpRemoteTool> = emptyList(),
    val lastConnectedAt: Long = 0L
)

/**
 * McpClientManager — Klien MCP (Model Context Protocol) Streamable HTTP + SSE + OAuth 2.1 PKCE
 * dengan dukungan Dynamic Client Registration (RFC 7591 / RFC 8414 / RFC 9728) serta preset siap pakai
 * (Canva, GitHub, Notion, Figma, Linear, Supabase, Vercel, Atlassian, Stripe, Context7, DeepWiki, Cloudflare).
 *
 * Alur "Tinggal Pencet Connect -> Pilih Akun":
 * 1. Untuk server OAuth 2.1 (Canva, Notion, Figma, Linear, Supabase, Vercel, Atlassian, Stripe):
 *    - Otomatis menemukan Protected Resource Metadata + Authorization Server Metadata.
 *    - Otomatis mendaftarkan klien native via Dynamic Client Registration (redirect loopback 127.0.0.1).
 *    - Membuka browser HP ke halaman login resmi penyedia -> pengguna pilih akun & klik Izinkan.
 *    - Menangkap callback lokal, menukar kode PKCE menjadi access_token + refresh_token,
 *      lalu langsung memanggil `initialize` + `tools/list` dan mendaftarkan seluruh tool ke AI Agent.
 * 2. Untuk server tanpa login (Context7, DeepWiki, Cloudflare Docs): langsung terhubung dalam 1 ketukan.
 * 3. Untuk server berbasis token (GitHub PAT, Stripe Key, atau custom MCP): pengguna cukup tempel token sekali.
 */
object McpClientManager {

    private const val TAG = "McpClientManager"
    private const val PREFS_NAME = "jarvis_mcp_client_prefs"
    private const val KEY_SERVERS_JSON = "mcp_servers_v1"
    private const val PROTOCOL_VERSION = "2025-03-26"

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rpcIdCounter = AtomicInteger(1)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val builtInPresets: List<McpServerPreset> = listOf(
        McpServerPreset(
            id = "canva",
            name = "Canva",
            category = "Desain",
            endpointUrl = "https://mcp.canva.com/mcp",
            description = "Buat, cari, kelola, dan ekspor desain serta presentasi di akun Canva.",
            authMode = McpAuthMode.OAUTH,
            tokenHelpUrl = "https://www.canva.com"
        ),
        McpServerPreset(
            id = "github",
            name = "GitHub",
            category = "Kode & Repo",
            endpointUrl = "https://api.githubcopilot.com/mcp/",
            description = "Akses repository, issues, pull request, Actions, dan kode GitHub.",
            authMode = McpAuthMode.OAUTH_OR_TOKEN,
            tokenHelpUrl = "https://github.com/settings/tokens",
            tokenPlaceholder = "ghp_... atau github_pat_..."
        ),
        McpServerPreset(
            id = "notion",
            name = "Notion",
            category = "Produktivitas",
            endpointUrl = "https://mcp.notion.com/mcp",
            description = "Cari, baca, buat, dan perbarui halaman serta database Notion.",
            authMode = McpAuthMode.OAUTH,
            tokenHelpUrl = "https://www.notion.so"
        ),
        McpServerPreset(
            id = "figma",
            name = "Figma",
            category = "Desain",
            endpointUrl = "https://mcp.figma.com/mcp",
            description = "Baca struktur desain, komponen, token gaya, dan frame Figma.",
            authMode = McpAuthMode.OAUTH_OR_TOKEN,
            tokenHelpUrl = "https://www.figma.com/developers/api#access-tokens",
            tokenPlaceholder = "figd_..."
        ),
        McpServerPreset(
            id = "linear",
            name = "Linear",
            category = "Proyek",
            endpointUrl = "https://mcp.linear.app/mcp",
            description = "Kelola issue, siklus kerja, proyek, dan tim di Linear.",
            authMode = McpAuthMode.OAUTH_OR_TOKEN,
            tokenHelpUrl = "https://linear.app/settings/api",
            tokenPlaceholder = "lin_api_..."
        ),
        McpServerPreset(
            id = "supabase",
            name = "Supabase",
            category = "Database",
            endpointUrl = "https://mcp.supabase.com/mcp",
            description = "Kelola proyek Supabase, tabel SQL, migrasi, dan Edge Functions.",
            authMode = McpAuthMode.OAUTH_OR_TOKEN,
            tokenHelpUrl = "https://supabase.com/dashboard/account/tokens",
            tokenPlaceholder = "sbp_..."
        ),
        McpServerPreset(
            id = "vercel",
            name = "Vercel",
            category = "Deploy",
            endpointUrl = "https://mcp.vercel.com",
            description = "Pantau deployment, log build, domain, dan proyek di akun Vercel.",
            authMode = McpAuthMode.OAUTH,
            tokenHelpUrl = "https://vercel.com/account/tokens"
        ),
        McpServerPreset(
            id = "atlassian",
            name = "Atlassian (Jira & Confluence)",
            category = "Proyek",
            endpointUrl = "https://mcp.atlassian.com/v1/mcp",
            description = "Cari dan kelola tiket Jira serta dokumen Confluence.",
            authMode = McpAuthMode.OAUTH,
            tokenHelpUrl = "https://id.atlassian.com"
        ),
        McpServerPreset(
            id = "stripe",
            name = "Stripe",
            category = "Pembayaran",
            endpointUrl = "https://mcp.stripe.com",
            description = "Kelola produk, pelanggan, invoice, dan transaksi Stripe.",
            authMode = McpAuthMode.OAUTH_OR_TOKEN,
            tokenHelpUrl = "https://dashboard.stripe.com/apikeys",
            tokenPlaceholder = "rk_live_... atau sk_test_..."
        ),
        McpServerPreset(
            id = "context7",
            name = "Context7 Docs",
            category = "Dokumentasi",
            endpointUrl = "https://mcp.context7.com/mcp",
            description = "Ambil dokumentasi library & framework terbaru (React, Remotion, Android, dll.).",
            authMode = McpAuthMode.NONE
        ),
        McpServerPreset(
            id = "deepwiki",
            name = "DeepWiki",
            category = "Dokumentasi",
            endpointUrl = "https://mcp.deepwiki.com/mcp",
            description = "Pelajari arsitektur dan dokumentasi repository GitHub publik secara instan.",
            authMode = McpAuthMode.NONE
        ),
        McpServerPreset(
            id = "cloudflare_docs",
            name = "Cloudflare Docs",
            category = "Dokumentasi",
            endpointUrl = "https://docs.mcp.cloudflare.com/mcp",
            description = "Cari dokumentasi resmi Cloudflare Workers, Tunnel, R2, dan D1.",
            authMode = McpAuthMode.NONE
        )
    )

    private val _servers = MutableStateFlow<List<McpServerConfig>>(defaultConfigs())
    val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()

    @Volatile
    private var initialized = false

    @Volatile
    private var activeCallbackSocket: ServerSocket? = null

    private fun defaultConfigs(): List<McpServerConfig> = builtInPresets.map { p ->
        McpServerConfig(
            id = p.id,
            name = p.name,
            category = p.category,
            endpointUrl = p.endpointUrl,
            description = p.description,
            authMode = p.authMode,
            tokenHelpUrl = p.tokenHelpUrl,
            isBuiltInPreset = true
        )
    }

    fun init(context: Context = JarvisApp.instance) {
        if (initialized) return
        initialized = true
        ioScope.launch {
            loadPersistedServers(context)
        }
    }

    private fun loadPersistedServers(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_SERVERS_JSON, null)
            val savedById = mutableMapOf<String, McpServerConfig>()
            if (!raw.isNullOrBlank()) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "").trim()
                    if (id.isEmpty()) continue
                    val toolsArr = obj.optJSONArray("tools")
                    val tools = mutableListOf<McpRemoteTool>()
                    if (toolsArr != null) {
                        for (j in 0 until toolsArr.length()) {
                            val t = toolsArr.optJSONObject(j) ?: continue
                            val tName = t.optString("name", "").trim()
                            if (tName.isNotEmpty()) {
                                tools.add(
                                    McpRemoteTool(
                                        serverId = id,
                                        name = tName,
                                        description = t.optString("description", ""),
                                        inputSchemaJson = t.optString("inputSchemaJson", "{}")
                                    )
                                )
                            }
                        }
                    }
                    val hasTokenOrConnected = obj.optString("accessToken", "").isNotBlank() ||
                        obj.optString("state", "") == McpConnectionState.CONNECTED.name
                    val restoredState = if (hasTokenOrConnected && tools.isNotEmpty()) {
                        McpConnectionState.CONNECTED
                    } else {
                        McpConnectionState.DISCONNECTED
                    }
                    savedById[id] = McpServerConfig(
                        id = id,
                        name = obj.optString("name", id),
                        category = obj.optString("category", "Kustom"),
                        endpointUrl = obj.optString("endpointUrl", ""),
                        description = obj.optString("description", ""),
                        authMode = runCatching {
                            McpAuthMode.valueOf(obj.optString("authMode", McpAuthMode.OAUTH_OR_TOKEN.name))
                        }.getOrDefault(McpAuthMode.OAUTH_OR_TOKEN),
                        tokenHelpUrl = obj.optString("tokenHelpUrl", ""),
                        isBuiltInPreset = obj.optBoolean("isBuiltInPreset", false),
                        accessToken = obj.optString("accessToken", ""),
                        refreshToken = obj.optString("refreshToken", ""),
                        tokenExpiresAt = obj.optLong("tokenExpiresAt", 0L),
                        oauthClientId = obj.optString("oauthClientId", ""),
                        oauthTokenEndpoint = obj.optString("oauthTokenEndpoint", ""),
                        sessionId = obj.optString("sessionId", ""),
                        state = restoredState,
                        statusDetail = if (restoredState == McpConnectionState.CONNECTED) {
                            "Terhubung · ${tools.size} tools"
                        } else {
                            "Belum terhubung"
                        },
                        tools = tools,
                        lastConnectedAt = obj.optLong("lastConnectedAt", 0L)
                    )
                }
            }

            // Gabungkan preset bawaan (selalu up-to-date) + state tersimpan + server kustom pengguna
            val merged = mutableListOf<McpServerConfig>()
            for (preset in builtInPresets) {
                val existing = savedById.remove(preset.id)
                if (existing != null) {
                    merged.add(
                        existing.copy(
                            name = preset.name,
                            category = preset.category,
                            endpointUrl = existing.endpointUrl.ifBlank { preset.endpointUrl },
                            description = preset.description,
                            authMode = preset.authMode,
                            tokenHelpUrl = preset.tokenHelpUrl,
                            isBuiltInPreset = true
                        )
                    )
                } else {
                    merged.add(
                        McpServerConfig(
                            id = preset.id,
                            name = preset.name,
                            category = preset.category,
                            endpointUrl = preset.endpointUrl,
                            description = preset.description,
                            authMode = preset.authMode,
                            tokenHelpUrl = preset.tokenHelpUrl,
                            isBuiltInPreset = true
                        )
                    )
                }
            }
            // Tambahkan server kustom milik pengguna
            merged.addAll(savedById.values.filter { !it.isBuiltInPreset })

            _servers.value = merged
            merged.filter { it.state == McpConnectionState.CONNECTED }.forEach { syncServerToolsToToolManager(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Gagal memuat konfigurasi MCP: ${e.message}")
        }
    }

    private fun savePersistedServers(context: Context = JarvisApp.instance) {
        try {
            val arr = JSONArray()
            for (s in _servers.value) {
                val tArr = JSONArray()
                for (t in s.tools) {
                    tArr.put(JSONObject().apply {
                        put("name", t.name)
                        put("description", t.description)
                        put("inputSchemaJson", t.inputSchemaJson)
                    })
                }
                arr.put(JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("category", s.category)
                    put("endpointUrl", s.endpointUrl)
                    put("description", s.description)
                    put("authMode", s.authMode.name)
                    put("tokenHelpUrl", s.tokenHelpUrl)
                    put("isBuiltInPreset", s.isBuiltInPreset)
                    put("accessToken", s.accessToken)
                    put("refreshToken", s.refreshToken)
                    put("tokenExpiresAt", s.tokenExpiresAt)
                    put("oauthClientId", s.oauthClientId)
                    put("oauthTokenEndpoint", s.oauthTokenEndpoint)
                    put("sessionId", s.sessionId)
                    put("state", s.state.name)
                    put("tools", tArr)
                    put("lastConnectedAt", s.lastConnectedAt)
                })
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SERVERS_JSON, arr.toString())
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Gagal menyimpan konfigurasi MCP: ${e.message}")
        }
    }

    private fun updateServer(serverId: String, transform: (McpServerConfig) -> McpServerConfig) {
        val current = _servers.value.toMutableList()
        val idx = current.indexOfFirst { it.id.equals(serverId, ignoreCase = true) }
        if (idx >= 0) {
            current[idx] = transform(current[idx])
            _servers.value = current
            savePersistedServers()
        }
    }

    fun getServer(serverId: String): McpServerConfig? {
        if (!initialized) init()
        val q = serverId.trim()
        return _servers.value.firstOrNull { it.id.equals(q, ignoreCase = true) }
            ?: _servers.value.firstOrNull { it.name.equals(q, ignoreCase = true) }
    }

    fun addCustomServer(
        name: String,
        endpointUrl: String,
        authMode: McpAuthMode = McpAuthMode.OAUTH_OR_TOKEN,
        bearerToken: String = "",
        description: String = ""
    ): McpServerConfig {
        if (!initialized) init()
        val cleanName = name.trim().ifBlank { "Custom MCP" }
        val cleanUrl = endpointUrl.trim()
        val slug = cleanName.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(32)
            .ifBlank { "custom_${System.currentTimeMillis() % 10000}" }
        val uniqueId = if (_servers.value.any { it.id == slug }) "${slug}_${System.currentTimeMillis() % 1000}" else slug

        val cfg = McpServerConfig(
            id = uniqueId,
            name = cleanName,
            category = "Kustom",
            endpointUrl = cleanUrl,
            description = description.ifBlank { "Server MCP kustom ($cleanUrl)" },
            authMode = authMode,
            isBuiltInPreset = false,
            accessToken = bearerToken.trim()
        )
        _servers.value = _servers.value + cfg
        savePersistedServers()
        return cfg
    }

    fun removeCustomServer(serverId: String): Boolean {
        val target = getServer(serverId) ?: return false
        removeServerToolsFromToolManager(target)
        if (target.isBuiltInPreset) {
            disconnectServer(target.id)
            return true
        }
        _servers.value = _servers.value.filterNot { it.id == target.id }
        savePersistedServers()
        return true
    }

    fun disconnectServer(serverId: String) {
        val target = getServer(serverId) ?: return
        removeServerToolsFromToolManager(target)
        updateServer(target.id) {
            it.copy(
                accessToken = "",
                refreshToken = "",
                tokenExpiresAt = 0L,
                sessionId = "",
                state = McpConnectionState.DISCONNECTED,
                statusDetail = "Terputus",
                tools = emptyList()
            )
        }
    }

    private fun syncServerToolsToToolManager(server: McpServerConfig) {
        // Bersihkan tool lama dari server ini terlebih dahulu agar selalu sinkron
        val prefix = "mcp_${server.id}_"
        ToolManager.tools.value
            .filter { !it.isBuiltIn && it.id.startsWith(prefix) }
            .forEach { ToolManager.deleteTool(it.id) }

        server.tools.take(40).forEach { remote ->
            val safeName = remote.name.lowercase(Locale.US).replace(Regex("[^a-z0-9_]+"), "_").trim('_')
            val toolId = "${prefix}${safeName}".take(64)
            val customTool = CustomTool(
                id = toolId,
                name = "${server.name}: ${remote.name}",
                description = "[MCP ${server.name}] ${remote.description.ifBlank { remote.name }.take(200)}",
                category = "MCP · ${server.name}",
                scriptType = ToolScriptType.CUSTOM_LOGIC,
                command = "mcp_remote:${server.id}:${remote.name}",
                parametersSchema = remote.inputSchemaJson.ifBlank { "{}" },
                riskLevel = ToolRiskLevel.LOW,
                isEnabled = true,
                isBuiltIn = false,
                createdByAi = false
            )
            ToolManager.addOrUpdateTool(customTool)
        }
    }

    private fun removeServerToolsFromToolManager(server: McpServerConfig) {
        val prefix = "mcp_${server.id}_"
        ToolManager.tools.value
            .filter { !it.isBuiltIn && it.id.startsWith(prefix) }
            .forEach { ToolManager.deleteTool(it.id) }
    }

    /**
     * Ringkasan MCP server terhubung untuk disuntikkan otomatis ke System Prompt AI.
     */
    fun buildSystemPromptSummary(): String {
        if (!initialized) init()
        val connected = _servers.value.filter { it.state == McpConnectionState.CONNECTED }
        if (connected.isEmpty()) return ""
        return buildString {
            appendLine("SERVER MCP EKSTERNAL TERHUBUNG (${connected.size}):")
            connected.forEach { s ->
                val toolNames = s.tools.take(15).joinToString(", ") { it.name }
                val extra = if (s.tools.size > 15) " (+${s.tools.size - 15} lainnya)" else ""
                appendLine("- [${s.id}] ${s.name} (${s.tools.size} tools): $toolNames$extra")
            }
            append("Kamu dapat langsung memanggil tool MCP memakai `mcp_call_tool` ({\"server_id\": \"...\", \"tool_name\": \"...\", \"arguments\": {...}}) atau melihat detail parameter lewat `mcp_list_tools`.")
        }.trim()
    }

    // =========================================================================
    // Koneksi Langsung (Tanpa Login / Bearer Token) & OAuth 2.1 PKCE Login Akun
    // =========================================================================

    /**
     * Hubungkan server MCP menggunakan token (atau tanpa login untuk server publik seperti Context7/DeepWiki).
     */
    suspend fun connectWithTokenOrDirect(
        serverId: String,
        bearerToken: String? = null
    ): ToolResult = withContext(Dispatchers.IO) {
        if (!initialized) init()
        val server = getServer(serverId)
            ?: return@withContext ToolResult("error", message = "Server MCP '$serverId' tidak ditemukan.")

        val effectiveToken = bearerToken?.trim()?.ifBlank { null } ?: server.accessToken
        updateServer(server.id) {
            it.copy(
                accessToken = effectiveToken,
                state = McpConnectionState.CONNECTING,
                statusDetail = "Menghubungkan ke ${server.name}..."
            )
        }

        val fresh = getServer(server.id) ?: return@withContext ToolResult("error", message = "Server tidak ditemukan.")
        initializeAndFetchTools(fresh)
    }

    /**
     * Alur OAuth 2.1 PKCE + Dynamic Client Registration (RFC 7591 / RFC 8414 / RFC 9728):
     * Pengguna cukup menekan "Hubungkan" -> browser terbuka ke halaman login akun Canva / Notion / Figma /
     * Linear / Supabase / Vercel / Atlassian / Stripe -> pilih akun & Izinkan -> otomatis kembali terhubung!
     */
    suspend fun connectWithOAuthAccount(
        context: Context,
        serverId: String
    ): ToolResult = withContext(Dispatchers.IO) {
        if (!initialized) init()
        val server = getServer(serverId)
            ?: return@withContext ToolResult("error", message = "Server MCP '$serverId' tidak ditemukan.")

        updateServer(server.id) {
            it.copy(
                state = McpConnectionState.AUTHORIZING,
                statusDetail = "Menemukan layanan login OAuth ${server.name}..."
            )
        }

        try {
            // 1. Discovery OAuth endpoints sesuai standar MCP Authorization (RFC 9728 + RFC 8414)
            val discovery = discoverOAuthMetadata(server.endpointUrl)
                ?: run {
                    val fallbackMsg = "Server ${server.name} tidak menyediakan OAuth otomatis (Dynamic Client Registration). Gunakan opsi Token / API Key pada dialog Connect."
                    updateServer(server.id) {
                        it.copy(state = McpConnectionState.ERROR, statusDetail = "OAuth tidak tersedia — pakai Token")
                    }
                    return@withContext ToolResult("error", errorCode = "OAUTH_DISCOVERY_UNAVAILABLE", message = fallbackMsg)
                }

            // 2. Siapkan loopback callback HTTP lokal di 127.0.0.1:<port> (RFC 8252 Native App Redirect)
            runCatching { activeCallbackSocket?.close() }
            val callbackSocket = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
            activeCallbackSocket = callbackSocket
            callbackSocket.soTimeout = 180_000 // 3 menit untuk login akun
            val callbackPort = callbackSocket.localPort
            val redirectUri = "http://127.0.0.1:$callbackPort/callback"

            // 3. Dynamic Client Registration (RFC 7591) jika belum punya client_id
            var clientId = server.oauthClientId
            if (clientId.isBlank() && discovery.registrationEndpoint.isNotBlank()) {
                val regBody = JSONObject().apply {
                    put("client_name", "Andra Control Android")
                    put("redirect_uris", JSONArray().put(redirectUri))
                    put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
                    put("response_types", JSONArray().put("code"))
                    put("token_endpoint_auth_method", "none")
                }.toString()
                val regReq = Request.Builder()
                    .url(discovery.registrationEndpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .post(regBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                httpClient.newCall(regReq).execute().use { resp ->
                    val txt = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        clientId = runCatching { JSONObject(txt).optString("client_id", "") }.getOrDefault("")
                    } else {
                        Log.w(TAG, "DCR gagal (${resp.code}): $txt")
                    }
                }
            }

            if (clientId.isBlank()) {
                runCatching { callbackSocket.close() }
                val err = "Registrasi OAuth otomatis ditolak oleh ${server.name}. Silakan sambungkan memakai Personal Access Token / API Key."
                updateServer(server.id) {
                    it.copy(state = McpConnectionState.ERROR, statusDetail = "Gunakan Token / API Key")
                }
                return@withContext ToolResult("error", errorCode = "OAUTH_DCR_FAILED", message = err)
            }

            // 4. Buat PKCE code_verifier & S256 code_challenge + state
            val verifier = generateRandomUrlSafe(48)
            val challenge = sha256Base64Url(verifier)
            val state = generateRandomUrlSafe(24)

            val scopeParam = discovery.defaultScope.takeIf { it.isNotBlank() }?.let {
                "&scope=${URLEncoder.encode(it, "UTF-8")}"
            } ?: ""
            val resourceParam = discovery.resourceUri.takeIf { it.isNotBlank() }?.let {
                "&resource=${URLEncoder.encode(it, "UTF-8")}"
            } ?: ""

            val authUrl = buildString {
                append(discovery.authorizationEndpoint)
                append(if (discovery.authorizationEndpoint.contains("?")) "&" else "?")
                append("response_type=code")
                append("&client_id=").append(URLEncoder.encode(clientId, "UTF-8"))
                append("&redirect_uri=").append(URLEncoder.encode(redirectUri, "UTF-8"))
                append("&code_challenge=").append(URLEncoder.encode(challenge, "UTF-8"))
                append("&code_challenge_method=S256")
                append("&state=").append(URLEncoder.encode(state, "UTF-8"))
                append(scopeParam)
                append(resourceParam)
            }

            updateServer(server.id) {
                it.copy(
                    oauthClientId = clientId,
                    oauthTokenEndpoint = discovery.tokenEndpoint,
                    state = McpConnectionState.AUTHORIZING,
                    statusDetail = "Menunggu login akun di browser..."
                )
            }

            // 5. Buka browser HP agar pengguna memilih akun & klik Izinkan
            withContext(Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }

            // 6. Tunggu redirect callback dari browser pada 127.0.0.1:<callbackPort>
            val callbackQuery = waitForLoopbackCallback(callbackSocket, server.name)
            runCatching { callbackSocket.close() }
            if (activeCallbackSocket === callbackSocket) activeCallbackSocket = null

            if (callbackQuery == null) {
                updateServer(server.id) {
                    it.copy(state = McpConnectionState.DISCONNECTED, statusDetail = "Login dibatalkan / timeout")
                }
                return@withContext ToolResult("error", message = "Login akun ${server.name} dibatalkan atau melewati batas waktu.")
            }

            val returnedError = callbackQuery["error"]
            if (!returnedError.isNullOrBlank()) {
                val desc = callbackQuery["error_description"] ?: returnedError
                updateServer(server.id) {
                    it.copy(state = McpConnectionState.ERROR, statusDetail = "Ditolak: $desc")
                }
                return@withContext ToolResult("error", message = "Otorisasi ${server.name} ditolak: $desc")
            }

            val returnedState = callbackQuery["state"]
            val authCode = callbackQuery["code"]
            if (authCode.isNullOrBlank() || returnedState != state) {
                updateServer(server.id) {
                    it.copy(state = McpConnectionState.ERROR, statusDetail = "Verifikasi state OAuth gagal")
                }
                return@withContext ToolResult("error", message = "Kode otorisasi OAuth tidak valid.")
            }

            // 7. Tukar authorization code dengan access_token via PKCE
            updateServer(server.id) {
                it.copy(state = McpConnectionState.CONNECTING, statusDetail = "Menyimpan sesi akun ${server.name}...")
            }
            val formBuilder = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", authCode)
                .add("redirect_uri", redirectUri)
                .add("client_id", clientId)
                .add("code_verifier", verifier)
            if (discovery.resourceUri.isNotBlank()) {
                formBuilder.add("resource", discovery.resourceUri)
            }

            val tokenReq = Request.Builder()
                .url(discovery.tokenEndpoint)
                .header("Accept", "application/json")
                .post(formBuilder.build())
                .build()

            var accessToken = ""
            var refreshToken = ""
            var expiresIn = 3600L
            httpClient.newCall(tokenReq).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val err = "Gagal menukar token OAuth (${resp.code}): ${txt.take(240)}"
                    updateServer(server.id) { it.copy(state = McpConnectionState.ERROR, statusDetail = "Gagal tukar token") }
                    return@withContext ToolResult("error", message = err)
                }
                val json = JSONObject(txt)
                accessToken = json.optString("access_token", "")
                refreshToken = json.optString("refresh_token", "")
                expiresIn = json.optLong("expires_in", 3600L)
            }

            if (accessToken.isBlank()) {
                updateServer(server.id) { it.copy(state = McpConnectionState.ERROR, statusDetail = "Token kosong") }
                return@withContext ToolResult("error", message = "Access token tidak diterima dari ${server.name}.")
            }

            updateServer(server.id) {
                it.copy(
                    accessToken = accessToken,
                    refreshToken = refreshToken.ifBlank { it.refreshToken },
                    tokenExpiresAt = System.currentTimeMillis() + (expiresIn * 1000L),
                    oauthClientId = clientId,
                    oauthTokenEndpoint = discovery.tokenEndpoint,
                    state = McpConnectionState.CONNECTING,
                    statusDetail = "Mengambil daftar tool ${server.name}..."
                )
            }

            val updated = getServer(server.id) ?: return@withContext ToolResult("error", message = "Server tidak ditemukan.")
            initializeAndFetchTools(updated)
        } catch (e: Exception) {
            Log.e(TAG, "OAuth connect gagal untuk ${server.id}", e)
            updateServer(server.id) {
                it.copy(state = McpConnectionState.ERROR, statusDetail = "Gagal: ${e.message?.take(60)}")
            }
            ToolResult("error", message = "Gagal menghubungkan ${server.name}: ${e.message}")
        }
    }

    private data class OAuthDiscovery(
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val registrationEndpoint: String,
        val defaultScope: String,
        val resourceUri: String
    )

    private fun discoverOAuthMetadata(mcpUrl: String): OAuthDiscovery? {
        val parsedUrl = runCatching { URL(mcpUrl) }.getOrNull() ?: return null
        val origin = "${parsedUrl.protocol}://${parsedUrl.authority}"
        val pathPart = parsedUrl.path.trimEnd('/')

        var resourceMetadataUrl = ""
        var resourceUri = mcpUrl

        // Probe awal POST initialize untuk membaca header WWW-Authenticate (RFC 9728)
        runCatching {
            val probeBody = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$PROTOCOL_VERSION","capabilities":{},"clientInfo":{"name":"andra-control","version":"2.63"}}}"""
            val req = Request.Builder()
                .url(mcpUrl)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .post(probeBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val wwwAuth = resp.header("WWW-Authenticate").orEmpty()
                val match = Regex("""resource_metadata="([^"]+)"""").find(wwwAuth)
                if (match != null) {
                    resourceMetadataUrl = match.groupValues[1]
                }
            }
        }

        // Ambil Protected Resource Metadata
        val prmCandidates = buildList {
            if (resourceMetadataUrl.isNotBlank()) add(resourceMetadataUrl)
            if (pathPart.isNotBlank()) add("$origin/.well-known/oauth-protected-resource$pathPart")
            add("$origin/.well-known/oauth-protected-resource")
        }

        var authServerIssuer = origin
        var defaultScope = ""
        for (url in prmCandidates) {
            val json = fetchJsonObject(url)
            if (json != null) {
                val authServers = json.optJSONArray("authorization_servers")
                if (authServers != null && authServers.length() > 0) {
                    authServerIssuer = authServers.optString(0, origin).trimEnd('/')
                }
                val res = json.optString("resource", "")
                if (res.isNotBlank()) resourceUri = res
                val scopes = json.optJSONArray("scopes_supported")
                if (scopes != null && scopes.length() > 0) {
                    val list = mutableListOf<String>()
                    for (i in 0 until scopes.length().coerceAtMost(8)) {
                        val s = scopes.optString(i, "").trim()
                        if (s.isNotEmpty()) list.add(s)
                    }
                    defaultScope = list.joinToString(" ")
                }
                break
            }
        }

        // Ambil Authorization Server Metadata (RFC 8414 / OIDC)
        val asCandidates = listOf(
            "$authServerIssuer/.well-known/oauth-authorization-server",
            "$authServerIssuer/.well-known/openid-configuration",
            "$origin/.well-known/oauth-authorization-server"
        )
        for (url in asCandidates) {
            val meta = fetchJsonObject(url) ?: continue
            val authEp = meta.optString("authorization_endpoint", "")
            val tokenEp = meta.optString("token_endpoint", "")
            val regEp = meta.optString("registration_endpoint", "")
            if (authEp.isNotBlank() && tokenEp.isNotBlank()) {
                return OAuthDiscovery(
                    authorizationEndpoint = authEp,
                    tokenEndpoint = tokenEp,
                    registrationEndpoint = regEp,
                    defaultScope = defaultScope,
                    resourceUri = resourceUri
                )
            }
        }
        return null
    }

    private fun fetchJsonObject(url: String): JSONObject? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "AndraControlMCP/2.63")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) null else JSONObject(body)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun waitForLoopbackCallback(serverSocket: ServerSocket, serverName: String): Map<String, String>? {
        return try {
            while (!serverSocket.isClosed) {
                val client = serverSocket.accept()
                client.use { sock ->
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                    val requestLine = reader.readLine() ?: return@use
                    // Contoh: GET /callback?code=xxx&state=yyy HTTP/1.1
                    val pathAndQuery = requestLine.split(" ").getOrNull(1).orEmpty()
                    if (!pathAndQuery.startsWith("/callback")) {
                        val notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        sock.getOutputStream().write(notFound.toByteArray())
                        sock.getOutputStream().flush()
                        return@use
                    }
                    val queryStr = pathAndQuery.substringAfter("?", "")
                    val params = mutableMapOf<String, String>()
                    queryStr.split("&").forEach { pair ->
                        val eq = pair.indexOf('=')
                        if (eq > 0) {
                            val k = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
                            val v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                            params[k] = v
                        }
                    }

                    val html = """
                        <!doctype html>
                        <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                        <title>Terhubung ke $serverName</title>
                        <style>
                          body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#0B0D10;color:#ECEFF4;font-family:system-ui,-apple-system,sans-serif;padding:24px;}
                          .card{max-width:380px;background:#13161B;border:1px solid #252A33;border-radius:14px;padding:24px;text-align:center;}
                          h1{font-size:18px;margin:0 0 8px;color:#34C77B;}
                          p{font-size:13px;line-height:1.5;color:#8B94A3;margin:0;}
                        </style></head>
                        <body><div class="card"><h1>Otorisasi $serverName Diterima</h1><p>Akun berhasil ditautkan. Silakan kembali ke aplikasi Andra Control.</p></div></body></html>
                    """.trimIndent()
                    val bodyBytes = html.toByteArray(Charsets.UTF_8)
                    val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
                    sock.getOutputStream().write(header.toByteArray(Charsets.UTF_8))
                    sock.getOutputStream().write(bodyBytes)
                    sock.getOutputStream().flush()
                    return params
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Loopback OAuth callback selesai/timeout: ${e.message}")
            null
        }
    }

    private fun generateRandomUrlSafe(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun sha256Base64Url(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    // =========================================================================
    // Protokol JSON-RPC MCP Streamable HTTP + SSE (initialize, tools/list, tools/call)
    // =========================================================================

    private suspend fun refreshTokenIfNeeded(server: McpServerConfig): McpServerConfig {
        if (server.refreshToken.isBlank() || server.oauthTokenEndpoint.isBlank() || server.oauthClientId.isBlank()) {
            return server
        }
        val nearExpiry = server.tokenExpiresAt > 0L && System.currentTimeMillis() > (server.tokenExpiresAt - 60_000L)
        if (!nearExpiry) return server

        return try {
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", server.refreshToken)
                .add("client_id", server.oauthClientId)
                .build()
            val req = Request.Builder()
                .url(server.oauthTokenEndpoint)
                .header("Accept", "application/json")
                .post(form)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return server
                val json = JSONObject(resp.body?.string().orEmpty())
                val newAccess = json.optString("access_token", "")
                if (newAccess.isBlank()) return server
                val newRefresh = json.optString("refresh_token", "").ifBlank { server.refreshToken }
                val exp = json.optLong("expires_in", 3600L)
                val updated = server.copy(
                    accessToken = newAccess,
                    refreshToken = newRefresh,
                    tokenExpiresAt = System.currentTimeMillis() + exp * 1000L
                )
                updateServer(server.id) { updated }
                updated
            }
        } catch (_: Exception) {
            server
        }
    }

    private suspend fun initializeAndFetchTools(initialServer: McpServerConfig): ToolResult {
        val server = refreshTokenIfNeeded(initialServer)

        // 1. initialize
        val initParams = JSONObject().apply {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", JSONObject())
            put("clientInfo", JSONObject().apply {
                put("name", "andra-control")
                put("version", "2.63")
            })
        }
        val (initCode, initSession, initJson, initErr) = sendJsonRpc(server, "initialize", initParams, expectResponse = true)
        if (initCode == 401 || initCode == 403) {
            updateServer(server.id) {
                it.copy(state = McpConnectionState.ERROR, statusDetail = "Perlu login akun atau token valid")
            }
            return ToolResult(
                "error",
                errorCode = "MCP_UNAUTHORIZED",
                message = "Autentikasi ke ${server.name} diperlukan (HTTP $initCode). Gunakan tombol Login Akun (OAuth) atau masukkan Token yang valid."
            )
        }
        if (initErr != null) {
            updateServer(server.id) {
                it.copy(state = McpConnectionState.ERROR, statusDetail = initErr.take(70))
            }
            return ToolResult("error", message = "Gagal inisialisasi MCP ${server.name}: $initErr")
        }

        val sessionId = initSession.ifBlank { server.sessionId }
        val serverWithSession = server.copy(sessionId = sessionId)

        // 2. notifications/initialized (fire-and-forget sesuai spesifikasi MCP)
        runCatching {
            sendJsonRpc(serverWithSession, "notifications/initialized", JSONObject(), expectResponse = false)
        }

        // 3. tools/list
        val (_, _, listJson, listErr) = sendJsonRpc(serverWithSession, "tools/list", JSONObject(), expectResponse = true)
        if (listErr != null || listJson == null) {
            updateServer(server.id) {
                it.copy(state = McpConnectionState.ERROR, statusDetail = "Gagal membaca daftar tool")
            }
            return ToolResult("error", message = "Terhubung ke ${server.name}, tetapi tools/list gagal: ${listErr ?: "respons kosong"}")
        }

        val toolsArr = listJson.optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
        val remoteTools = mutableListOf<McpRemoteTool>()
        for (i in 0 until toolsArr.length()) {
            val t = toolsArr.optJSONObject(i) ?: continue
            val tName = t.optString("name", "").trim()
            if (tName.isEmpty()) continue
            val schemaStr = t.optJSONObject("inputSchema")?.toString() ?: "{}"
            remoteTools.add(
                McpRemoteTool(
                    serverId = server.id,
                    name = tName,
                    description = t.optString("description", ""),
                    inputSchemaJson = schemaStr
                )
            )
        }

        val connectedServer = serverWithSession.copy(
            state = McpConnectionState.CONNECTED,
            statusDetail = "Terhubung · ${remoteTools.size} tools",
            tools = remoteTools,
            lastConnectedAt = System.currentTimeMillis()
        )
        updateServer(server.id) { connectedServer }
        syncServerToolsToToolManager(connectedServer)

        val preview = remoteTools.take(10).joinToString(", ") { it.name }
        return ToolResult(
            "ok",
            result = "Berhasil terhubung ke MCP ${server.name} (${remoteTools.size} tools tersedia).\n" +
                if (preview.isNotBlank()) "Tools: $preview" else "Siap digunakan."
        )
    }

    private data class RpcOutcome(
        val httpCode: Int,
        val sessionId: String,
        val json: JSONObject?,
        val error: String?
    )

    private fun sendJsonRpc(
        server: McpServerConfig,
        method: String,
        params: JSONObject,
        expectResponse: Boolean
    ): RpcOutcome {
        return try {
            val payload = JSONObject().apply {
                put("jsonrpc", "2.0")
                if (expectResponse) put("id", rpcIdCounter.getAndIncrement())
                put("method", method)
                if (params.length() > 0 || method == "initialize" || method == "tools/call") {
                    put("params", params)
                }
            }.toString()

            val reqBuilder = Request.Builder()
                .url(server.endpointUrl)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                .header("User-Agent", "AndraControlMCP/2.63")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))

            if (server.accessToken.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${server.accessToken}")
            }
            if (server.sessionId.isNotBlank()) {
                reqBuilder.header("Mcp-Session-Id", server.sessionId)
            }

            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val code = resp.code
                val respSession = resp.header("Mcp-Session-Id").orEmpty()
                val contentType = resp.header("Content-Type").orEmpty().lowercase(Locale.US)
                val bodyStr = resp.body?.string().orEmpty()

                if (!expectResponse && (code == 202 || code == 204 || code in 200..299)) {
                    return RpcOutcome(code, respSession, null, null)
                }
                if (!resp.isSuccessful) {
                    return RpcOutcome(code, respSession, null, "HTTP $code: ${bodyStr.take(300)}")
                }

                // Parse baik JSON murni maupun SSE Streamable HTTP (`data: {...}`)
                val parsedJson = parseJsonOrSse(bodyStr, contentType)
                    ?: return RpcOutcome(code, respSession, null, "Respons MCP tidak berisi JSON valid: ${bodyStr.take(240)}")

                val rpcErr = parsedJson.optJSONObject("error")
                if (rpcErr != null) {
                    val errMsg = rpcErr.optString("message", rpcErr.toString())
                    return RpcOutcome(code, respSession, parsedJson, "RPC Error: $errMsg")
                }

                RpcOutcome(code, respSession, parsedJson, null)
            }
        } catch (e: Exception) {
            RpcOutcome(-1, "", null, e.message ?: "Kesalahan jaringan MCP")
        }
    }

    private fun parseJsonOrSse(body: String, contentType: String): JSONObject? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        if (!contentType.contains("event-stream") && (trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return runCatching {
                if (trimmed.startsWith("[")) JSONArray(trimmed).optJSONObject(0) else JSONObject(trimmed)
            }.getOrNull()
        }
        // Ekstrak event `data:` terakhir yang memiliki result atau error
        var best: JSONObject? = null
        val dataBuf = StringBuilder()
        for (line in trimmed.lines()) {
            if (line.startsWith("data:")) {
                val part = line.removePrefix("data:").trim()
                if (part.isNotEmpty() && part != "[DONE]") {
                    if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                    dataBuf.append(part)
                }
            } else if (line.isBlank() && dataBuf.isNotEmpty()) {
                runCatching { JSONObject(dataBuf.toString()) }.getOrNull()?.let { obj ->
                    if (obj.has("result") || obj.has("error")) best = obj
                }
                dataBuf.clear()
            }
        }
        if (dataBuf.isNotEmpty()) {
            runCatching { JSONObject(dataBuf.toString()) }.getOrNull()?.let { obj ->
                if (obj.has("result") || obj.has("error")) best = obj
            }
        }
        return best ?: runCatching { JSONObject(trimmed) }.getOrNull()
    }

    // =========================================================================
    // Eksekusi Tool MCP untuk AI Agent
    // =========================================================================

    suspend fun callRemoteTool(
        serverId: String,
        toolName: String,
        arguments: JSONObject = JSONObject()
    ): ToolResult = withContext(Dispatchers.IO) {
        if (!initialized) init()
        val rawServer = getServer(serverId)
            ?: return@withContext ToolResult("error", message = "Server MCP '$serverId' tidak ditemukan. Cek daftar server lewat `mcp_list_servers`.")

        if (rawServer.state != McpConnectionState.CONNECTED) {
            // Jika server tanpa auth (mis. Context7 / DeepWiki), coba auto-connect sekali
            if (rawServer.authMode == McpAuthMode.NONE) {
                val autoRes = connectWithTokenOrDirect(rawServer.id)
                if (autoRes.status != "ok") return@withContext autoRes
            } else {
                return@withContext ToolResult(
                    "error",
                    errorCode = "MCP_NOT_CONNECTED",
                    message = "Server MCP '${rawServer.name}' belum terhubung. Minta pengguna membuka Katalog Tools -> tab MCP lalu menekan 'Hubungkan' pada ${rawServer.name}."
                )
            }
        }

        val server = refreshTokenIfNeeded(getServer(rawServer.id) ?: rawServer)
        val params = JSONObject().apply {
            put("name", toolName)
            put("arguments", arguments)
        }

        var outcome = sendJsonRpc(server, "tools/call", params, expectResponse = true)
        // Jika session kadaluwarsa (HTTP 404/400 session invalid), lakukan re-initialize otomatis 1x
        if (outcome.error != null && (outcome.httpCode == 404 || outcome.error.contains("session", ignoreCase = true))) {
            val reInit = initializeAndFetchTools(server.copy(sessionId = ""))
            if (reInit.status == "ok") {
                val refreshed = getServer(server.id) ?: server
                outcome = sendJsonRpc(refreshed, "tools/call", params, expectResponse = true)
            }
        }

        if (outcome.error != null || outcome.json == null) {
            return@withContext ToolResult("error", message = "Gagal memanggil ${server.name}/$toolName: ${outcome.error ?: "respons kosong"}")
        }

        val resultObj = outcome.json.optJSONObject("result") ?: JSONObject()
        val isError = resultObj.optBoolean("isError", false)
        val contentArr = resultObj.optJSONArray("content")
        val sb = StringBuilder()
        var imageB64: String? = null

        if (contentArr != null && contentArr.length() > 0) {
            for (i in 0 until contentArr.length()) {
                val item = contentArr.optJSONObject(i) ?: continue
                when (item.optString("type", "text")) {
                    "text" -> sb.appendLine(item.optString("text", ""))
                    "image" -> {
                        val data = item.optString("data", "")
                        if (data.isNotBlank() && imageB64 == null) imageB64 = data
                        sb.appendLine("[Gambar diterima dari MCP ${server.name}]")
                    }
                    "resource" -> {
                        val res = item.optJSONObject("resource")
                        sb.appendLine(res?.optString("text", res.toString()) ?: item.toString())
                    }
                    else -> sb.appendLine(item.toString())
                }
            }
        } else {
            sb.append(resultObj.toString())
        }

        val finalOut = sb.toString().trim().take(12_000).ifBlank { "(sukses tanpa output teks)" }
        val extra = mutableMapOf<String, Any?>()
        if (!imageB64.isNullOrBlank()) extra["screenshot_b64"] = imageB64

        if (isError) {
            ToolResult("error", message = finalOut, extra = extra)
        } else {
            ToolResult("ok", result = finalOut, extra = extra)
        }
    }

    fun listServersReport(): ToolResult {
        if (!initialized) init()
        val list = _servers.value
        val sb = StringBuilder()
        sb.appendLine("Daftar Server MCP (${list.count { it.state == McpConnectionState.CONNECTED }} terhubung dari ${list.size}):")
        list.forEach { s ->
            val st = when (s.state) {
                McpConnectionState.CONNECTED -> "TERHUBUNG (${s.tools.size} tools)"
                McpConnectionState.AUTHORIZING -> "MENUNGGU LOGIN"
                McpConnectionState.CONNECTING -> "MENGHUBUNGKAN"
                McpConnectionState.ERROR -> "ERROR (${s.statusDetail})"
                McpConnectionState.DISCONNECTED -> "BELUM TERHUBUNG"
            }
            sb.appendLine("- [${s.id}] ${s.name} (${s.category}) — $st · ${s.endpointUrl}")
        }
        return ToolResult("ok", result = sb.toString().trim())
    }

    fun listServerToolsReport(serverId: String): ToolResult {
        if (!initialized) init()
        val server = getServer(serverId)
            ?: return ToolResult("error", message = "Server MCP '$serverId' tidak ditemukan.")
        if (server.tools.isEmpty()) {
            return ToolResult(
                "ok",
                result = "Server MCP '${server.name}' belum memiliki daftar tool (status: ${server.statusDetail}). Hubungkan dulu dari Katalog Tools -> tab MCP."
            )
        }
        val sb = StringBuilder()
        sb.appendLine("Tools MCP ${server.name} (${server.tools.size}):")
        server.tools.take(40).forEachIndexed { idx, t ->
            sb.appendLine("${idx + 1}. ${t.name}: ${t.description.take(180)}")
            sb.appendLine("   schema: ${t.inputSchemaJson.take(300)}")
        }
        return ToolResult("ok", result = sb.toString().trim())
    }
}
