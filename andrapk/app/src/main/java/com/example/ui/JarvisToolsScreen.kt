package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.*
import com.example.service.ToolManager
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import org.json.JSONObject

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.example.service.McpAuthMode
import com.example.service.McpClientManager
import com.example.service.McpConnectionState
import com.example.service.McpServerConfig

private val ToolCardBorderActive = BorderStroke(1.dp, JarvisBorder)
private val ToolCardBorderMuted = BorderStroke(1.dp, JarvisBorder.copy(alpha = 0.45f))

private data class ToolsFilterCounts(
    val total: Int,
    val active: Int,
    val builtin: Int,
    val custom: Int,
    val termux: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvisToolsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val allTools by ToolManager.tools.collectAsStateWithLifecycle()
    val installedGitHubTools by com.example.service.GitHubToolManager.installedTools.collectAsStateWithLifecycle()
    val mcpServers by McpClientManager.servers.collectAsStateWithLifecycle()
    val permissionMode by ToolManager.permissionMode.collectAsStateWithLifecycle()
    val customPerms by ToolManager.customPermissions.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("all") } // all, mcp, github, active, builtin, custom, termux

    var showGuideDialog by remember { mutableStateOf(false) }
    var showAddEditDialog by remember { mutableStateOf(false) }
    var showGitHubInstallDialog by remember { mutableStateOf(false) }
    var showAddCustomMcpDialog by remember { mutableStateOf(false) }
    var mcpConnectTargetId by remember { mutableStateOf<String?>(null) }
    var toolToEdit by remember { mutableStateOf<CustomTool?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showCustomPermsDialog by remember { mutableStateOf(false) }
    var testToolTarget by remember { mutableStateOf<CustomTool?>(null) }

    // Hitung semua metrik filter dalam 1 pass yang di-remember (bukan 5x iterasi setiap frame)
    val counts = remember(allTools) {
        var active = 0
        var builtin = 0
        var custom = 0
        var termux = 0
        for (t in allTools) {
            if (t.isEnabled) active++
            if (t.isBuiltIn) builtin++ else custom++
            if (t.category.contains("Termux", ignoreCase = true) || t.id.startsWith("termux")) termux++
        }
        ToolsFilterCounts(
            total = allTools.size,
            active = active,
            builtin = builtin,
            custom = custom,
            termux = termux
        )
    }

    val connectedMcpCount = remember(mcpServers) {
        mcpServers.count { it.state == McpConnectionState.CONNECTED }
    }

    val filteredMcpServers = remember(mcpServers, searchQuery, selectedFilter) {
        if (selectedFilter != "mcp") emptyList()
        else if (searchQuery.isBlank()) mcpServers
        else mcpServers.filter { srv ->
            srv.name.contains(searchQuery, ignoreCase = true) ||
                srv.category.contains(searchQuery, ignoreCase = true) ||
                srv.description.contains(searchQuery, ignoreCase = true) ||
                srv.id.contains(searchQuery, ignoreCase = true)
        }
    }

    val filteredTools = remember(allTools, searchQuery, selectedFilter) {
        val q = searchQuery.trim()
        allTools.filter { tool ->
            val matchesQuery = q.isEmpty() ||
                tool.name.contains(q, ignoreCase = true) ||
                tool.id.contains(q, ignoreCase = true) ||
                tool.description.contains(q, ignoreCase = true) ||
                tool.category.contains(q, ignoreCase = true)

            val matchesFilter = when (selectedFilter) {
                "mcp" -> tool.category.startsWith("MCP", ignoreCase = true) || tool.id.startsWith("mcp_")
                "active" -> tool.isEnabled
                "builtin" -> tool.isBuiltIn
                "custom" -> !tool.isBuiltIn
                "github" -> tool.category.contains("GitHub", ignoreCase = true) || tool.id.startsWith("github_tool") || tool.id.startsWith("gh_")
                "termux" -> tool.category.contains("Termux", ignoreCase = true) || tool.id.startsWith("termux")
                else -> true
            }

            matchesQuery && matchesFilter
        }
    }

    // Callback stabil agar item LazyColumn tidak recompose sia-sia
    val onToggleTool = remember { { toolId: String -> ToolManager.toggleTool(toolId) } }
    val onEditTool = remember {
        { tool: CustomTool ->
            toolToEdit = tool
            showAddEditDialog = true
        }
    }
    val onDeleteTool = remember(context) {
        { tool: CustomTool ->
            val deleted = ToolManager.deleteTool(tool.id)
            if (deleted) {
                Toast.makeText(context, "Tool '${tool.name}' dihapus", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val onTestRunTool = remember { { tool: CustomTool -> testToolTarget = tool } }

    val onPermissionModeSelected = remember {
        { mode: AiPermissionMode ->
            ToolManager.setPermissionMode(mode)
            if (mode == AiPermissionMode.CUSTOM) {
                showCustomPermsDialog = true
            }
        }
    }
    val onOpenCustomPerms = remember { { showCustomPermsDialog = true } }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = JarvisBackground,
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (selectedFilter == "mcp") {
                        showAddCustomMcpDialog = true
                    } else {
                        toolToEdit = null
                        showAddEditDialog = true
                    }
                },
                containerColor = JarvisCyan,
                contentColor = JarvisOnPrimary,
                elevation = FloatingActionButtonDefaults.elevation(2.dp, 4.dp),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.testTag("fab_add_tool")
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = if (selectedFilter == "mcp") "Tambah server MCP kustom" else "Tambah tool baru"
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Top Bar: Title & Action Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (selectedFilter == "mcp") "MCP Hub & Preset" else "Katalog Tools",
                        style = MaterialTheme.typography.titleLarge,
                        color = JarvisTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (selectedFilter == "mcp") {
                            "$connectedMcpCount terhubung dari ${mcpServers.size} server MCP"
                        } else {
                            "${counts.active} aktif dari ${counts.total} tersedia"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = JarvisTextSecondary,
                        maxLines = 1
                    )
                }

                IconButton(
                    onClick = { selectedFilter = if (selectedFilter == "mcp") "all" else "mcp" },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selectedFilter == "mcp" || connectedMcpCount > 0) JarvisCyan.copy(alpha = 0.12f) else Color.Transparent)
                        .testTag("btn_open_mcp_tab")
                ) {
                    Icon(
                        Icons.Default.Extension,
                        contentDescription = "Server & Preset MCP",
                        tint = if (selectedFilter == "mcp" || connectedMcpCount > 0) JarvisCyan else JarvisTextSecondary,
                        modifier = Modifier.size(19.dp)
                    )
                }

                IconButton(
                    onClick = { showGitHubInstallDialog = true },
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("btn_install_github_tool")
                ) {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = "Instal tool dari GitHub",
                        tint = JarvisTextSecondary,
                        modifier = Modifier.size(19.dp)
                    )
                }

                IconButton(
                    onClick = { showGuideDialog = true },
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("btn_tool_guide")
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = "Panduan dan template tool",
                        tint = JarvisTextSecondary,
                        modifier = Modifier.size(19.dp)
                    )
                }

                IconButton(
                    onClick = { showImportDialog = true },
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("btn_import_tools")
                ) {
                    Icon(
                        Icons.Default.FileUpload,
                        contentDescription = "Impor tool",
                        tint = JarvisTextSecondary,
                        modifier = Modifier.size(19.dp)
                    )
                }

                IconButton(
                    onClick = {
                        val json = ToolManager.exportToolsToJson()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Custom Tools", json))
                        Toast.makeText(context, "Custom tools disalin ke clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("btn_export_tools")
                ) {
                    Icon(
                        Icons.Default.FileDownload,
                        contentDescription = "Ekspor tool",
                        tint = JarvisTextSecondary,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            // AI Permission Mode Card (disembunyikan di tab MCP agar fokus ke koneksi akun preset)
            if (selectedFilter != "mcp") {
                PermissionModeCard(
                    currentMode = permissionMode,
                    onModeSelected = onPermissionModeSelected,
                    onOpenCustomSettings = onOpenCustomPerms
                )
                Spacer(modifier = Modifier.height(10.dp))
            } else {
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Search Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        if (selectedFilter == "mcp") "Cari preset MCP (Canva, GitHub, Notion, Figma)..."
                        else "Cari nama, ID, atau kategori tool...",
                        fontSize = 12.5.sp,
                        color = JarvisTextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = JarvisTextSecondary, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Hapus pencarian", tint = JarvisTextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_search_tools"),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = JarvisBorderStrong,
                    unfocusedBorderColor = JarvisBorder,
                    focusedTextColor = JarvisTextPrimary,
                    unfocusedTextColor = JarvisTextPrimary,
                    cursorColor = JarvisCyan,
                    focusedContainerColor = JarvisSurface,
                    unfocusedContainerColor = JarvisSurface
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Filter Chips
            val chipColors = FilterChipDefaults.filterChipColors(
                containerColor = JarvisSurface,
                labelColor = JarvisTextSecondary,
                selectedContainerColor = JarvisSurfaceElevated,
                selectedLabelColor = JarvisTextPrimary
            )
            val filterChips = remember(counts, installedGitHubTools.size, connectedMcpCount, mcpServers.size) {
                listOf(
                    "all" to "Semua (${counts.total})",
                    "mcp" to "MCP ($connectedMcpCount/${mcpServers.size})",
                    "github" to "GitHub (${installedGitHubTools.size})",
                    "active" to "Aktif (${counts.active})",
                    "builtin" to "Bawaan (${counts.builtin})",
                    "custom" to "Kustom (${counts.custom})",
                    "termux" to "Termux (${counts.termux})"
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                filterChips.forEach { (key, label) ->
                    val isSel = selectedFilter == key
                    FilterChip(
                        selected = isSel,
                        onClick = { selectedFilter = key },
                        shape = RoundedCornerShape(8.dp),
                        colors = chipColors,
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSel,
                            borderColor = JarvisBorder,
                            selectedBorderColor = if (key == "mcp") JarvisCyan else JarvisBorderStrong
                        ),
                        label = {
                            Text(
                                text = label,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Main Content List
            if (selectedFilter == "mcp") {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = GlassSpacing.FloatingNavClearance)
                ) {
                    item(key = "mcp_hub_banner", contentType = "mcp_header") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(GlassRadius.Card),
                            color = GlassSurfaceTint,
                            border = ToolCardBorderActive
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Hubungkan Akun & Server MCP",
                                        color = JarvisTextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Ketuk Hubungkan pada preset, pilih akun Anda di browser, lalu AI langsung bisa memakai seluruh tools-nya.",
                                        color = JarvisTextSecondary,
                                        fontSize = 11.5.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                                OutlinedButton(
                                    onClick = { showAddCustomMcpDialog = true },
                                    modifier = Modifier.heightIn(min = 38.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, JarvisBorderStrong),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisTextPrimary),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Kustom", fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }

                    if (filteredMcpServers.isEmpty()) {
                        item(key = "mcp_empty", contentType = "empty") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Tidak ada server MCP yang cocok dengan pencarian.",
                                    color = JarvisTextSecondary,
                                    fontSize = 12.5.sp
                                )
                            }
                        }
                    } else {
                        items(
                            items = filteredMcpServers,
                            key = { "mcp_srv_${it.id}" },
                            contentType = { "mcp_server_card" }
                        ) { server ->
                            McpServerPresetCard(
                                server = server,
                                onConnectClick = {
                                    if (server.authMode == McpAuthMode.NONE) {
                                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            val res = McpClientManager.connectWithTokenOrDirect(server.id)
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                Toast.makeText(
                                                    context,
                                                    (res.result ?: res.message ?: "Selesai").take(120),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    } else {
                                        mcpConnectTargetId = server.id
                                    }
                                },
                                onRefreshClick = {
                                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        val res = McpClientManager.connectWithTokenOrDirect(server.id)
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            Toast.makeText(
                                                context,
                                                (res.result ?: res.message ?: "Sinkronisasi selesai").take(120),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                },
                                onDisconnectClick = {
                                    McpClientManager.disconnectServer(server.id)
                                    Toast.makeText(context, "Koneksi ${server.name} diputuskan", Toast.LENGTH_SHORT).show()
                                },
                                onDeleteCustomClick = if (!server.isBuiltInPreset) {
                                    {
                                        McpClientManager.removeCustomServer(server.id)
                                        Toast.makeText(context, "Server ${server.name} dihapus", Toast.LENGTH_SHORT).show()
                                    }
                                } else null
                            )
                        }
                    }

                    if (filteredTools.isNotEmpty()) {
                        item(key = "mcp_tools_section_header", contentType = "section_label") {
                            Text(
                                text = "Tools MCP Aktif (${filteredTools.size})",
                                color = JarvisTextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                            )
                        }
                        items(
                            items = filteredTools,
                            key = { "mcp_tool_${it.id}" },
                            contentType = { "tool_card" }
                        ) { tool ->
                            ToolItemCard(
                                tool = tool,
                                onToggle = onToggleTool,
                                onEdit = onEditTool,
                                onDelete = onDeleteTool,
                                onTestRun = onTestRunTool
                            )
                        }
                    }
                }
            } else if (filteredTools.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Build,
                            contentDescription = null,
                            tint = JarvisTextTertiary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tidak ada tool yang cocok",
                            color = JarvisTextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = GlassSpacing.FloatingNavClearance)
                ) {
                    if (selectedFilter == "github" && installedGitHubTools.isNotEmpty()) {
                        items(
                            items = installedGitHubTools,
                            key = { "gh_repo_${it.id}" },
                            contentType = { "github_card" }
                        ) { ghTool ->
                            InstalledGitHubToolCard(
                                tool = ghTool,
                                onSetup = {
                                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        val res = com.example.service.GitHubToolManager.setupToolById(ghTool.id)
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            Toast.makeText(
                                                context,
                                                (res.result ?: res.message ?: "Setup selesai").take(120),
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                },
                                onDelete = {
                                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        com.example.service.GitHubToolManager.removeTool(ghTool.id)
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            Toast.makeText(context, "Tool '${ghTool.name}' dihapus", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        }
                    }

                    items(
                        items = filteredTools,
                        key = { it.id },
                        contentType = { "tool_card" }
                    ) { tool ->
                        ToolItemCard(
                            tool = tool,
                            onToggle = onToggleTool,
                            onEdit = onEditTool,
                            onDelete = onDeleteTool,
                            onTestRun = onTestRunTool
                        )
                    }
                }
            }
        }
    }

    // Guide & Template Dialog (?)
    if (showGuideDialog) {
        ToolGuideAndTemplatesDialog(
            onDismiss = { showGuideDialog = false },
            onApplyTemplate = { template ->
                showGuideDialog = false
                toolToEdit = template.copy(
                    id = "custom_" + template.id.removePrefix("template_") + "_" + System.currentTimeMillis() % 1000
                )
                showAddEditDialog = true
            }
        )
    }

    // Add / Edit Tool Dialog
    if (showAddEditDialog) {
        AddEditToolDialog(
            initialTool = toolToEdit,
            onDismiss = { showAddEditDialog = false },
            onSave = { savedTool ->
                ToolManager.addOrUpdateTool(savedTool)
                Toast.makeText(context, "Tool '${savedTool.name}' disimpan", Toast.LENGTH_SHORT).show()
                showAddEditDialog = false
            }
        )
    }

    // Import Dialog
    if (showImportDialog) {
        ImportToolsDialog(
            onDismiss = { showImportDialog = false },
            onImport = { jsonStr ->
                val result = ToolManager.importToolsFromJson(jsonStr)
                result.onSuccess { count ->
                    Toast.makeText(context, "Berhasil mengimpor $count tool", Toast.LENGTH_SHORT).show()
                    showImportDialog = false
                }.onFailure { err ->
                    Toast.makeText(context, "Gagal mengimpor: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    // Custom Permissions Dialog
    if (showCustomPermsDialog) {
        CustomPermissionsDialog(
            settings = customPerms,
            onDismiss = { showCustomPermsDialog = false },
            onSave = { updated ->
                ToolManager.updateCustomPermissions(updated)
                Toast.makeText(context, "Setelan izin kustom disimpan", Toast.LENGTH_SHORT).show()
                showCustomPermsDialog = false
            }
        )
    }

    // Test Run Tool Dialog
    testToolTarget?.let { tool ->
        TestRunToolDialog(
            tool = tool,
            onDismiss = { testToolTarget = null }
        )
    }

    // Install Tool from GitHub Dialog
    if (showGitHubInstallDialog) {
        GitHubToolInstallDialog(
            onDismiss = { showGitHubInstallDialog = false }
        )
    }

    // MCP Preset / Account Connect Dialog
    mcpConnectTargetId?.let { targetId ->
        val liveServer = mcpServers.firstOrNull { it.id == targetId }
        if (liveServer != null) {
            McpConnectDialog(
                server = liveServer,
                onDismiss = { mcpConnectTargetId = null }
            )
        } else {
            mcpConnectTargetId = null
        }
    }

    // Add Custom MCP Server Dialog
    if (showAddCustomMcpDialog) {
        AddCustomMcpServerDialog(
            onDismiss = { showAddCustomMcpDialog = false },
            onAdded = { created ->
                showAddCustomMcpDialog = false
                selectedFilter = "mcp"
                if (created.authMode == McpAuthMode.NONE || created.accessToken.isNotBlank()) {
                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val res = McpClientManager.connectWithTokenOrDirect(created.id)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                (res.result ?: res.message ?: "Server MCP disimpan").take(120),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } else {
                    mcpConnectTargetId = created.id
                }
            }
        )
    }
}

/**
 * Card displaying and switching AI Permission Modes
 */
@Composable
fun PermissionModeCard(
    currentMode: AiPermissionMode,
    onModeSelected: (AiPermissionMode) -> Unit,
    onOpenCustomSettings: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = JarvisSurface,
        shape = RoundedCornerShape(12.dp),
        border = ToolCardBorderActive
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    tint = when (currentMode) {
                        AiPermissionMode.SANDBOXED -> JarvisTeal
                        AiPermissionMode.LOW_RISK -> JarvisAmber
                        AiPermissionMode.FULL_ACCESS -> JarvisRed
                        AiPermissionMode.CUSTOM -> JarvisCyan
                    },
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Mode Izin AI",
                    color = JarvisTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )

                if (currentMode == AiPermissionMode.CUSTOM) {
                    TextButton(
                        onClick = onOpenCustomSettings,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(14.dp), tint = JarvisCyan)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Atur", color = JarvisCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PermissionModeOption(
                    title = "Sandboxed",
                    subtitle = "Baca saja",
                    isSelected = currentMode == AiPermissionMode.SANDBOXED,
                    activeColor = JarvisTeal,
                    modifier = Modifier.weight(1f),
                    onClick = { onModeSelected(AiPermissionMode.SANDBOXED) }
                )
                PermissionModeOption(
                    title = "Low Risk",
                    subtitle = "Aman",
                    isSelected = currentMode == AiPermissionMode.LOW_RISK,
                    activeColor = JarvisAmber,
                    modifier = Modifier.weight(1f),
                    onClick = { onModeSelected(AiPermissionMode.LOW_RISK) }
                )
                PermissionModeOption(
                    title = "Full Access",
                    subtitle = "Penuh",
                    isSelected = currentMode == AiPermissionMode.FULL_ACCESS,
                    activeColor = JarvisRed,
                    modifier = Modifier.weight(1f),
                    onClick = { onModeSelected(AiPermissionMode.FULL_ACCESS) }
                )
                PermissionModeOption(
                    title = "Custom",
                    subtitle = "Kustom",
                    isSelected = currentMode == AiPermissionMode.CUSTOM,
                    activeColor = JarvisCyan,
                    modifier = Modifier.weight(1f),
                    onClick = { onModeSelected(AiPermissionMode.CUSTOM) }
                )
            }
        }
    }
}

@Composable
fun PermissionModeOption(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        color = if (isSelected) JarvisSurfaceElevated else JarvisSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            1.dp,
            if (isSelected) activeColor.copy(alpha = 0.65f) else JarvisBorder
        )
    ) {
        Column(
            modifier = Modifier.padding(vertical = 7.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                color = if (isSelected) JarvisTextPrimary else JarvisTextSecondary,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1
            )
            Text(
                text = subtitle,
                color = if (isSelected) activeColor else JarvisTextTertiary,
                fontSize = 9.5.sp,
                maxLines = 1
            )
        }
    }
}

/**
 * Single Tool Item Card — Graphite Precision non-slop layout (flat surface, single metadata line, >=36dp hit targets).
 */
@Composable
fun ToolItemCard(
    tool: CustomTool,
    onToggle: (String) -> Unit,
    onEdit: (CustomTool) -> Unit,
    onDelete: (CustomTool) -> Unit,
    onTestRun: (CustomTool) -> Unit
) {
    val riskColor = when (tool.riskLevel) {
        ToolRiskLevel.SAFE -> JarvisEmerald
        ToolRiskLevel.LOW -> JarvisAmber
        ToolRiskLevel.HIGH -> JarvisRed
    }
    val riskLabel = when (tool.riskLevel) {
        ToolRiskLevel.SAFE -> "Aman"
        ToolRiskLevel.LOW -> "Rendah"
        ToolRiskLevel.HIGH -> "Tinggi"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = JarvisSurface,
        shape = RoundedCornerShape(12.dp),
        border = if (tool.isEnabled) ToolCardBorderActive else ToolCardBorderMuted
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (tool.createdByAi) "${tool.name} · AI" else tool.name,
                        color = if (tool.isEnabled) JarvisTextPrimary else JarvisTextSecondary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = tool.id,
                        color = JarvisTextTertiary,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Switch(
                    checked = tool.isEnabled,
                    onCheckedChange = { onToggle(tool.id) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = JarvisCyan,
                        checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = JarvisTextSecondary,
                        uncheckedTrackColor = JarvisSurfaceVariant,
                        uncheckedBorderColor = JarvisBorderStrong
                    ),
                    modifier = Modifier.testTag("switch_${tool.id}")
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = tool.description,
                color = JarvisTextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = JarvisBorder, thickness = 1.dp)
            Spacer(modifier = Modifier.height(6.dp))

            // Single clean metadata line + crisp action buttons (zero pill sandwich)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(riskColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${tool.scriptType.name} · ${tool.category} · $riskLabel",
                    color = JarvisTextTertiary,
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = { onTestRun(tool) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Uji coba tool",
                        tint = JarvisEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = { onEdit(tool) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit tool",
                        tint = JarvisTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                if (!tool.isBuiltIn) {
                    IconButton(
                        onClick = { onDelete(tool) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Hapus tool",
                            tint = JarvisRed,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Help & Templates Dialog accessible via the (?) button
 */
@Composable
fun ToolGuideAndTemplatesDialog(
    onDismiss: () -> Unit,
    onApplyTemplate: (CustomTool) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            shape = RoundedCornerShape(16.dp),
            color = JarvisSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(JarvisSurfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.HelpOutline, contentDescription = null, tint = JarvisCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Panduan & Template Pembuatan Tools",
                        color = JarvisTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Tutup", tint = JarvisTextSecondary)
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp)
                ) {
                    // Section 1: Konsep Tool
                    Text(
                        "1. Bagaimana Tool Bekerja?",
                        color = JarvisCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Tool adalah fungsi otomasi yang dapat dipanggil oleh pengguna atau AI. Tool mengeksekusi perintah pada Android secara lokal melalui Aksesibilitas, Shell Termux/Shizuku, atau Intent.",
                        color = JarvisTextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Section 2: AI Bisa Buat Tool Sendiri
                    Surface(
                        color = JarvisCyan.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = JarvisCyan, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Fitur Spesial: AI Membuat Tool Sendiri",
                                    color = JarvisCyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Kamu cukup meminta AI di Chat Window: contohnya 'Tolong buatkan tool untuk mengecek suhu baterai hp' atau 'Buatkan tool kunci layar'. AI akan secara otonom menyusun perintah, parameter, dan mendaftarkannya ke daftar tools ini!",
                                color = JarvisTextPrimary,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Section 3: Placeholder Parameter
                    Text(
                        "2. Format Placeholder Parameter",
                        color = JarvisCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Gunakan kurung kurawal {nama_parameter} di dalam perintah. Nilai parameter yang dikirim oleh pemanggil akan disisipkan otomatis:\nContoh: 'am start -a android.intent.action.VIEW -d {url}'",
                        color = JarvisTextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Section 4: Galeri Template Siap Pakai
                    Text(
                        "3. Template Siap Pakai (Klik untuk Menggunakan):",
                        color = JarvisEmerald,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    ToolManager.toolTemplates.forEach { template ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            color = JarvisSurfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(template.name, color = JarvisTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Text(template.description, color = JarvisTextSecondary, fontSize = 10.sp)
                                    }

                                    Button(
                                        onClick = { onApplyTemplate(template) },
                                        colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisBackground),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text("Pakai Template", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                Surface(
                                    color = JarvisBackground,
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = template.command,
                                        color = JarvisCyan,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Add or Edit Tool Dialog
 */
@Composable
fun AddEditToolDialog(
    initialTool: CustomTool?,
    onDismiss: () -> Unit,
    onSave: (CustomTool) -> Unit
) {
    val isEditing = initialTool != null && !initialTool.id.startsWith("template_")

    var id by remember { mutableStateOf(initialTool?.id ?: "tool_${System.currentTimeMillis() % 10000}") }
    var name by remember { mutableStateOf(initialTool?.name ?: "") }
    var description by remember { mutableStateOf(initialTool?.description ?: "") }
    var category by remember { mutableStateOf(initialTool?.category ?: "Custom") }
    var scriptType by remember { mutableStateOf(initialTool?.scriptType ?: ToolScriptType.SHELL) }
    var command by remember { mutableStateOf(initialTool?.command ?: "") }
    var parametersSchema by remember { mutableStateOf(initialTool?.parametersSchema ?: "{}") }
    var riskLevel by remember { mutableStateOf(initialTool?.riskLevel ?: ToolRiskLevel.LOW) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            shape = RoundedCornerShape(16.dp),
            color = JarvisSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(JarvisSurfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (isEditing) Icons.Default.Edit else Icons.Default.Add, contentDescription = null, tint = JarvisCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (isEditing) "Edit Tool: ${initialTool?.name}" else "Tambah Tool Baru",
                        color = JarvisTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Batal", tint = JarvisTextSecondary)
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // ID
                    OutlinedTextField(
                        value = id,
                        onValueChange = { id = it },
                        label = { Text("Tool ID (unik)", fontSize = 11.sp) },
                        enabled = !isEditing || !(initialTool?.isBuiltIn ?: false),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = JarvisCyan,
                            unfocusedBorderColor = JarvisBorder,
                            focusedTextColor = JarvisTextPrimary,
                            unfocusedTextColor = JarvisTextPrimary
                        )
                    )

                    // Nama Tool
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nama Tool", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = JarvisCyan,
                            unfocusedBorderColor = JarvisBorder,
                            focusedTextColor = JarvisTextPrimary,
                            unfocusedTextColor = JarvisTextPrimary
                        )
                    )

                    // Deskripsi
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Deskripsi Fungsi", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = JarvisCyan,
                            unfocusedBorderColor = JarvisBorder,
                            focusedTextColor = JarvisTextPrimary,
                            unfocusedTextColor = JarvisTextPrimary
                        )
                    )

                    // Tipe Script Selector
                    Text("Tipe Script:", color = JarvisTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(ToolScriptType.SHELL, ToolScriptType.ACCESSIBILITY, ToolScriptType.INTENT).forEach { type ->
                            FilterChip(
                                selected = scriptType == type,
                                onClick = { scriptType = type },
                                label = { Text(type.name, fontSize = 10.sp) }
                            )
                        }
                    }

                    // Command / Perintah
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("Perintah / Command Script", fontSize = 11.sp) },
                        placeholder = { Text("Contoh: dumpsys battery | grep level", color = JarvisTextSecondary) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = JarvisCyan,
                            unfocusedBorderColor = JarvisBorder,
                            focusedTextColor = JarvisTextPrimary,
                            unfocusedTextColor = JarvisTextPrimary
                        )
                    )

                    // Parameter Schema (JSON)
                    OutlinedTextField(
                        value = parametersSchema,
                        onValueChange = { parametersSchema = it },
                        label = { Text("Parameter Schema (JSON)", fontSize = 11.sp) },
                        placeholder = { Text("""{"param": "contoh"}""", color = JarvisTextSecondary) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = JarvisCyan,
                            unfocusedBorderColor = JarvisBorder,
                            focusedTextColor = JarvisTextPrimary,
                            unfocusedTextColor = JarvisTextPrimary
                        )
                    )

                    // Tingkat Risiko
                    Text("Tingkat Risiko (Security Level):", color = JarvisTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(ToolRiskLevel.SAFE, ToolRiskLevel.LOW, ToolRiskLevel.HIGH).forEach { risk ->
                            FilterChip(
                                selected = riskLevel == risk,
                                onClick = { riskLevel = risk },
                                label = { Text(risk.name, fontSize = 10.sp) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Tombol Simpan
                    Button(
                        onClick = {
                            if (name.isBlank() || command.isBlank()) return@Button
                            onSave(
                                CustomTool(
                                    id = id.trim(),
                                    name = name.trim(),
                                    description = description.trim(),
                                    category = category.trim(),
                                    scriptType = scriptType,
                                    command = command.trim(),
                                    parametersSchema = parametersSchema.trim().ifBlank { "{}" },
                                    riskLevel = riskLevel,
                                    isEnabled = initialTool?.isEnabled ?: true,
                                    isBuiltIn = initialTool?.isBuiltIn ?: false,
                                    createdByAi = initialTool?.createdByAi ?: false,
                                    createdAt = initialTool?.createdAt ?: System.currentTimeMillis()
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisBackground),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Simpan Tool", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Custom AI Permissions Dialog
 */
@Composable
fun CustomPermissionsDialog(
    settings: CustomPermissionSettings,
    onDismiss: () -> Unit,
    onSave: (CustomPermissionSettings) -> Unit
) {
    var readScreen by remember { mutableStateOf(settings.allowReadScreen) }
    var tapSwipe by remember { mutableStateOf(settings.allowTapSwipe) }
    var typeText by remember { mutableStateOf(settings.allowTypeText) }
    var openApp by remember { mutableStateOf(settings.allowOpenApp) }
    var shell by remember { mutableStateOf(settings.allowShellCommands) }
    var createTools by remember { mutableStateOf(settings.allowCreateTools) }
    var systemKeys by remember { mutableStateOf(settings.allowSystemKeys) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = JarvisSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = JarvisCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Atur Izin Kustom AI",
                        color = JarvisTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                PermissionToggleRow("Baca Layar & Visi", "Membaca teks dan elemen UI di layar", readScreen) { readScreen = it }
                PermissionToggleRow("Gestur Tap & Swipe", "Mengetuk dan mengusap layar otomatis", tapSwipe) { tapSwipe = it }
                PermissionToggleRow("Ketik Teks Otomatis", "Mengetikkan teks ke input field", typeText) { typeText = it }
                PermissionToggleRow("Buka Aplikasi", "Membuka aplikasi lain dari sistem", openApp) { openApp = it }
                PermissionToggleRow("Perintah Shell / Shizuku", "Mengeksekusi perintah terminal shell", shell) { shell = it }
                PermissionToggleRow("AI Membuat Tool Sendiri", "Mengizinkan AI mendaftarkan tool baru", createTools) { createTools = it }
                PermissionToggleRow("Tombol Sistem (Back/Home)", "Menekan tombol navigasi sistem", systemKeys) { systemKeys = it }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Batal", color = JarvisTextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onSave(
                                CustomPermissionSettings(
                                    allowReadScreen = readScreen,
                                    allowTapSwipe = tapSwipe,
                                    allowTypeText = typeText,
                                    allowOpenApp = openApp,
                                    allowShellCommands = shell,
                                    allowCreateTools = createTools,
                                    allowSystemKeys = systemKeys
                                )
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisBackground)
                    ) {
                        Text("Terapkan", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = JarvisTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = JarvisTextSecondary, fontSize = 10.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = JarvisCyan,
                checkedTrackColor = JarvisCyanDark.copy(alpha = 0.5f),
                uncheckedThumbColor = JarvisTextSecondary,
                uncheckedTrackColor = JarvisSurfaceVariant
            )
        )
    }
}

/**
 * Import Tools Dialog
 */
@Composable
fun ImportToolsDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var jsonInput by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = JarvisSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Import Custom Tools (JSON)",
                    color = JarvisTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Tempelkan JSON array konfigurasi tool yang ingin diimpor ke dalam aplikasi:",
                    color = JarvisTextSecondary,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = jsonInput,
                    onValueChange = { jsonInput = it },
                    placeholder = { Text("[{\"id\": \"tool_1\", \"name\": \"...\"}]", color = JarvisTextSecondary, fontSize = 11.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = JarvisCyan,
                        unfocusedBorderColor = JarvisBorder,
                        focusedTextColor = JarvisTextPrimary,
                        unfocusedTextColor = JarvisTextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Batal", color = JarvisTextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onImport(jsonInput) },
                        colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisBackground)
                    ) {
                        Text("Import", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Quick Test Run Tool Dialog
 */
@Composable
fun TestRunToolDialog(
    tool: CustomTool,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var paramsInput by remember { mutableStateOf(tool.parametersSchema) }
    var executionOutput by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = JarvisSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlayCircle, contentDescription = null, tint = JarvisEmerald)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Test Eksekusi: ${tool.name}",
                        color = JarvisTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("Command: ${tool.command}", color = JarvisCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = paramsInput,
                    onValueChange = { paramsInput = it },
                    label = { Text("Parameter (JSON)", fontSize = 10.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = JarvisCyan,
                        unfocusedBorderColor = JarvisBorder,
                        focusedTextColor = JarvisTextPrimary,
                        unfocusedTextColor = JarvisTextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        coroutineScope.launch {
                            isRunning = true
                            executionOutput = "Mengeksekusi ${tool.name}..."
                            val jsonObj = try { JSONObject(paramsInput) } catch (_: Exception) { JSONObject() }
                            val res = ToolManager.executeCustomTool(tool, jsonObj)
                            executionOutput = if (res.status == "ok") {
                                "✅ STATUS OK:\n${res.result ?: "Berhasil dijalankan."}"
                            } else {
                                "❌ ERROR (${res.errorCode ?: "FAIL"}):\n${res.message ?: "Gagal mengeksekusi tool"}"
                            }
                            isRunning = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisEmerald, contentColor = Color.White),
                    enabled = !isRunning
                ) {
                    Text(if (isRunning) "Menjalankan..." else "Jalankan Sekarang", fontWeight = FontWeight.Bold)
                }

                executionOutput?.let { out ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = JarvisBackground,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = out,
                            color = JarvisTextPrimary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Tutup", color = JarvisTextSecondary)
                }
            }
        }
    }
}
