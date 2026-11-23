package com.example.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.service.McpAuthMode
import com.example.service.McpClientManager
import com.example.service.McpConnectionState
import com.example.service.McpServerConfig
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Kartu bersih untuk satu preset atau server MCP eksternal (Canva, GitHub, Notion, Figma, dll.).
 * Desain Graphite Precision: 1 permukaan matte datar, border hairline 1.dp, metadata tanpa tumpukan pill,
 * dan tombol sentuh >= 44.dp.
 */
@Composable
fun McpServerPresetCard(
    server: McpServerConfig,
    onConnectClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onDeleteCustomClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isConnected = server.state == McpConnectionState.CONNECTED
    val isBusy = server.state == McpConnectionState.AUTHORIZING || server.state == McpConnectionState.CONNECTING

    val dotColor = when (server.state) {
        McpConnectionState.CONNECTED -> JarvisEmerald
        McpConnectionState.AUTHORIZING, McpConnectionState.CONNECTING -> JarvisAmber
        McpConnectionState.ERROR -> JarvisRed
        McpConnectionState.DISCONNECTED -> JarvisTextTertiary
    }

    val authMeta = when (server.authMode) {
        McpAuthMode.NONE -> "Tanpa login"
        McpAuthMode.OAUTH -> "Login akun (OAuth)"
        McpAuthMode.BEARER_TOKEN -> "Token / API key"
        McpAuthMode.OAUTH_OR_TOKEN -> "Login akun atau Token"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("mcp_preset_card_${server.id}"),
        shape = RoundedCornerShape(14.dp),
        color = JarvisSurface,
        border = BorderStroke(1.dp, if (isConnected) JarvisBorderStrong else JarvisBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.75.dp,
                            color = JarvisAmber
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = server.name,
                            color = JarvisTextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${server.category} · $authMeta",
                            color = JarvisTextTertiary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (!server.isBuiltInPreset && onDeleteCustomClick != null) {
                    IconButton(
                        onClick = onDeleteCustomClick,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Hapus server MCP kustom",
                            tint = JarvisTextTertiary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Text(
                text = server.description,
                color = JarvisTextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = server.statusDetail,
                color = when (server.state) {
                    McpConnectionState.CONNECTED -> JarvisEmerald
                    McpConnectionState.ERROR -> JarvisRed
                    McpConnectionState.AUTHORIZING, McpConnectionState.CONNECTING -> JarvisAmber
                    McpConnectionState.DISCONNECTED -> JarvisTextTertiary
                },
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            HorizontalDivider(color = JarvisBorder, thickness = 1.dp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isConnected) {
                    OutlinedButton(
                        onClick = onRefreshClick,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 42.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, JarvisBorderStrong),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisTextPrimary)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Sinkronkan", fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = onDisconnectClick,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 42.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, JarvisRed.copy(alpha = 0.45f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisRed)
                    ) {
                        Text("Putuskan", fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                } else {
                    Button(
                        onClick = onConnectClick,
                        enabled = !isBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .testTag("mcp_connect_btn_${server.id}"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = JarvisCyan,
                            contentColor = JarvisOnPrimary
                        ),
                        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp)
                    ) {
                        Text(
                            text = when {
                                isBusy -> "Menghubungkan..."
                                server.authMode == McpAuthMode.NONE -> "Hubungkan"
                                server.authMode == McpAuthMode.OAUTH -> "Hubungkan Akun ${server.name}"
                                else -> "Hubungkan ${server.name}"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dialog koneksi akun / token untuk preset MCP terpilih.
 * - OAuth: 1 ketukan tombol "Login dengan Akun <Nama>" membuka browser untuk memilih akun yang diinginkan.
 * - Token: kolom token rapi + tombol "Buat Token" ke halaman resmi penyedia.
 */
@Composable
fun McpConnectDialog(
    server: McpServerConfig,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tokenInput by remember(server.id) { mutableStateOf(server.accessToken) }
    var isWorking by remember { mutableStateOf(false) }
    var feedbackMsg by remember { mutableStateOf<String?>(null) }
    var isFeedbackError by remember { mutableStateOf(false) }

    val supportsOAuth = server.authMode == McpAuthMode.OAUTH || server.authMode == McpAuthMode.OAUTH_OR_TOKEN
    val supportsToken = server.authMode == McpAuthMode.BEARER_TOKEN || server.authMode == McpAuthMode.OAUTH_OR_TOKEN

    Dialog(
        onDismissRequest = { if (!isWorking) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("mcp_connect_dialog"),
            shape = RoundedCornerShape(16.dp),
            color = JarvisSurface,
            border = BorderStroke(1.dp, JarvisBorderStrong)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Hubungkan ${server.name}",
                            color = JarvisTextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = server.endpointUrl,
                            color = JarvisTextTertiary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = { if (!isWorking) onDismiss() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Tutup",
                            tint = JarvisTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Text(
                    text = server.description,
                    color = JarvisTextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )

                if (supportsOAuth) {
                    Button(
                        onClick = {
                            if (isWorking) return@Button
                            isWorking = true
                            feedbackMsg = "Membuka halaman login ${server.name} di browser..."
                            isFeedbackError = false
                            scope.launch(Dispatchers.IO) {
                                val res = McpClientManager.connectWithOAuthAccount(context, server.id)
                                withContext(Dispatchers.Main) {
                                    isWorking = false
                                    isFeedbackError = res.status != "ok"
                                    feedbackMsg = res.result ?: res.message
                                    if (res.status == "ok") {
                                        Toast.makeText(context, "${server.name} terhubung", Toast.LENGTH_SHORT).show()
                                        onDismiss()
                                    }
                                }
                            }
                        },
                        enabled = !isWorking,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 46.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = JarvisCyan,
                            contentColor = JarvisOnPrimary
                        ),
                        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp)
                    ) {
                        if (isWorking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = JarvisOnPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Menunggu login akun...", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        } else {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Login dengan Akun ${server.name}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Text(
                        text = "Browser akan terbuka agar kamu dapat memilih akun ${server.name} dan menyetujui akses MCP.",
                        color = JarvisTextTertiary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }

                if (supportsOAuth && supportsToken) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f), color = JarvisBorder)
                        Text(
                            text = "atau pakai token",
                            color = JarvisTextTertiary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f), color = JarvisBorder)
                    }
                }

                if (supportsToken) {
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        enabled = !isWorking,
                        label = { Text("Personal Access Token / API Key", fontSize = 12.sp) },
                        placeholder = {
                            Text(
                                McpClientManager.builtInPresets.firstOrNull { it.id == server.id }?.tokenPlaceholder
                                    ?: "Tempel Bearer token",
                                color = JarvisTextTertiary,
                                fontSize = 12.sp
                            )
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = JarvisCyan,
                            unfocusedBorderColor = JarvisBorder,
                            focusedTextColor = JarvisTextPrimary,
                            unfocusedTextColor = JarvisTextPrimary,
                            cursorColor = JarvisCyan,
                            focusedContainerColor = JarvisBackground,
                            unfocusedContainerColor = JarvisBackground
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (server.tokenHelpUrl.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        val i = Intent(Intent.ACTION_VIEW, Uri.parse(server.tokenHelpUrl)).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(i)
                                    }
                                },
                                enabled = !isWorking,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 44.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, JarvisBorderStrong),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisTextSecondary)
                            ) {
                                Text("Ambil Token", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }

                        Button(
                            onClick = {
                                if (tokenInput.isBlank() || isWorking) return@Button
                                isWorking = true
                                feedbackMsg = null
                                isFeedbackError = false
                                scope.launch(Dispatchers.IO) {
                                    val res = McpClientManager.connectWithTokenOrDirect(server.id, tokenInput)
                                    withContext(Dispatchers.Main) {
                                        isWorking = false
                                        isFeedbackError = res.status != "ok"
                                        feedbackMsg = res.result ?: res.message
                                        if (res.status == "ok") {
                                            Toast.makeText(context, "${server.name} terhubung", Toast.LENGTH_SHORT).show()
                                            onDismiss()
                                        }
                                    }
                                }
                            },
                            enabled = tokenInput.isNotBlank() && !isWorking,
                            modifier = Modifier
                                .weight(1.3f)
                                .heightIn(min = 44.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (supportsOAuth) JarvisSurfaceVariant else JarvisCyan,
                                contentColor = if (supportsOAuth) JarvisTextPrimary else JarvisOnPrimary
                            ),
                            border = if (supportsOAuth) BorderStroke(1.dp, JarvisBorderStrong) else null,
                            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp)
                        ) {
                            Text("Sambung Token", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                if (!feedbackMsg.isNullOrBlank()) {
                    HorizontalDivider(color = JarvisBorder, thickness = 1.dp)
                    Text(
                        text = feedbackMsg ?: "",
                        color = if (isFeedbackError) JarvisRed else JarvisEmerald,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

/**
 * Dialog untuk menambahkan Server MCP Kustom (remote HTTPS atau lokal 127.0.0.1).
 */
@Composable
fun AddCustomMcpServerDialog(
    onDismiss: () -> Unit,
    onAdded: (McpServerConfig) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var authMode by remember { mutableStateOf(McpAuthMode.OAUTH_OR_TOKEN) }
    var token by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(16.dp),
            color = JarvisSurface,
            border = BorderStroke(1.dp, JarvisBorderStrong)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Tambah Server MCP Kustom",
                        color = JarvisTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Tutup", tint = JarvisTextSecondary, modifier = Modifier.size(18.dp))
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nama Server MCP", fontSize = 12.sp) },
                    placeholder = { Text("mis. Server MCP Pribadi", color = JarvisTextTertiary, fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = JarvisCyan,
                        unfocusedBorderColor = JarvisBorder,
                        focusedTextColor = JarvisTextPrimary,
                        unfocusedTextColor = JarvisTextPrimary,
                        cursorColor = JarvisCyan,
                        focusedContainerColor = JarvisBackground,
                        unfocusedContainerColor = JarvisBackground
                    )
                )

                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("URL Endpoint Streamable HTTP", fontSize = 12.sp) },
                    placeholder = { Text("https://example.com/mcp", color = JarvisTextTertiary, fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = JarvisCyan,
                        unfocusedBorderColor = JarvisBorder,
                        focusedTextColor = JarvisTextPrimary,
                        unfocusedTextColor = JarvisTextPrimary,
                        cursorColor = JarvisCyan,
                        focusedContainerColor = JarvisBackground,
                        unfocusedContainerColor = JarvisBackground
                    )
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Metode Autentikasi", color = JarvisTextTertiary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(JarvisBackground)
                            .border(1.dp, JarvisBorder, RoundedCornerShape(10.dp))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(
                            McpAuthMode.NONE to "Tanpa Auth",
                            McpAuthMode.OAUTH_OR_TOKEN to "OAuth / Token",
                            McpAuthMode.BEARER_TOKEN to "Token"
                        ).forEach { (mode, label) ->
                            val selected = authMode == mode
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 38.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .clickable { authMode = mode },
                                shape = RoundedCornerShape(7.dp),
                                color = if (selected) JarvisSurfaceVariant else Color.Transparent,
                                border = if (selected) BorderStroke(1.dp, JarvisBorderStrong) else null
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = label,
                                        color = if (selected) JarvisTextPrimary else JarvisTextSecondary,
                                        fontSize = 11.5.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                if (authMode != McpAuthMode.NONE) {
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("Bearer Token (opsional jika pakai OAuth)", fontSize = 12.sp) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = JarvisCyan,
                            unfocusedBorderColor = JarvisBorder,
                            focusedTextColor = JarvisTextPrimary,
                            unfocusedTextColor = JarvisTextPrimary,
                            cursorColor = JarvisCyan,
                            focusedContainerColor = JarvisBackground,
                            unfocusedContainerColor = JarvisBackground
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, JarvisBorderStrong),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisTextSecondary)
                    ) {
                        Text("Batal", fontSize = 13.sp)
                    }
                    Button(
                        onClick = {
                            if (name.isBlank() || endpoint.isBlank()) return@Button
                            val created = McpClientManager.addCustomServer(
                                name = name,
                                endpointUrl = endpoint,
                                authMode = authMode,
                                bearerToken = token
                            )
                            onAdded(created)
                        },
                        enabled = name.isNotBlank() && endpoint.startsWith("http"),
                        modifier = Modifier
                            .weight(1.3f)
                            .heightIn(min = 44.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = JarvisCyan,
                            contentColor = JarvisOnPrimary
                        ),
                        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp)
                    ) {
                        Text("Simpan Server", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
