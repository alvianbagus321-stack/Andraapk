package com.example.ui

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.service.VirtualComputerManager
import com.example.service.VmApprovalRequest
import com.example.service.VmDistro
import com.example.service.VmMode
import com.example.service.VmState
import com.example.service.VmStatus
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kartu persetujuan inline di layar Chat saat AI meminta pembuatan Komputer Virtual.
 * Mengikuti disiplin Graphite Precision: satu bidang permukaan matte, garis hairline 1.dp,
 * metadata tanpa tumpukan pill, dan tombol sentuh minimal 44.dp.
 */
@Composable
fun VmApprovalChatCard(
    request: VmApprovalRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("vm_approval_chat_card"),
        shape = RoundedCornerShape(14.dp),
        color = JarvisSurface,
        border = BorderStroke(1.dp, JarvisBorderStrong)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Computer,
                    contentDescription = null,
                    tint = JarvisCyan,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Permintaan Komputer Virtual",
                    color = JarvisTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "AI meminta izin membuat lingkungan Linux terisolasi yang dapat diakses penuh dan dipantau langsung dari perangkat ini.",
                color = JarvisTextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = JarvisBorder, thickness = 1.dp)
            Spacer(modifier = Modifier.height(10.dp))

            VmSpecRow(label = "Sistem", value = "${request.distro.displayName} · ~${request.distro.estimatedDiskMb} MB")
            VmSpecRow(label = "Mode", value = "${request.mode.displayName} · ~${request.mode.estimatedRamMb} MB RAM")
            VmSpecRow(
                label = "Browser",
                value = if (request.includeBrowser) "Chromium (dengan akses DOM & layar live)" else "Tanpa browser"
            )
            if (request.extraPackages.isNotEmpty()) {
                VmSpecRow(label = "Paket tambahan", value = request.extraPackages.joinToString(", "))
            }
            VmSpecRow(label = "Alasan", value = request.reason)

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = JarvisBorder, thickness = 1.dp)
            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Terisolasi di container Termux lokal · 0% RAM/CPU saat dihentikan",
                color = JarvisTextTertiary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .testTag("vm_approval_reject_btn"),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, JarvisBorderStrong),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisTextSecondary)
                ) {
                    Text(
                        text = "Tolak",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }

                Button(
                    onClick = onApprove,
                    modifier = Modifier
                        .weight(1.35f)
                        .heightIn(min = 44.dp)
                        .testTag("vm_approval_approve_btn"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = JarvisCyan,
                        contentColor = JarvisOnPrimary
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp)
                ) {
                    Text(
                        text = "Setujui & Jalankan",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun VmSpecRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = JarvisTextTertiary,
            fontSize = 11.5.sp,
            modifier = Modifier.width(96.dp),
            maxLines = 1
        )
        Text(
            text = value,
            color = JarvisTextPrimary,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Bar status ringkas di atas kolom input Chat ketika Komputer Virtual sedang disiapkan atau aktif.
 */
@Composable
fun VmActiveStatusBar(
    status: VmStatus,
    onOpenViewer: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dotColor = when (status.state) {
        VmState.RUNNING -> JarvisEmerald
        VmState.PROVISIONING -> JarvisAmber
        VmState.ERROR -> JarvisRed
        else -> JarvisTextTertiary
    }
    val stateLabel = when (status.state) {
        VmState.RUNNING -> "${status.distro.displayName} · Aktif"
        VmState.PROVISIONING -> status.statusDetail
        VmState.ERROR -> "VM bermasalah"
        else -> "VM berhenti"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("vm_active_status_bar"),
        color = JarvisSurfaceVariant
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(color = JarvisBorder, thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenViewer() }
                        .padding(vertical = 4.dp)
                ) {
                    if (status.state == VmState.PROVISIONING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.75.dp,
                            color = JarvisAmber
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stateLabel,
                        color = JarvisTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(
                        onClick = onOpenViewer,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 36.dp)
                    ) {
                        Text(
                            text = if (status.mode == VmMode.DESKTOP_GUI) "Lihat Layar" else "Buka Terminal",
                            color = JarvisCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }

                    if (status.state == VmState.RUNNING) {
                        IconButton(
                            onClick = onStop,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Hentikan VM",
                                tint = JarvisRed,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Penampil Komputer Virtual penuh (Layar Live noVNC + Terminal VM Interaktif).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VirtualComputerViewerDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    // Catatan performa: `logs` TIDAK dikoleksi di root dialog — hanya di body tab
    // yang menampilkannya, agar stream log deras tidak me-recompose header/tab/WebView.
    val status by VirtualComputerManager.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var activeTab by remember {
        mutableIntStateOf(if (status.mode == VmMode.DESKTOP_GUI) 0 else 1)
    }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var terminalInput by remember { mutableStateOf("") }
    var isExecBusy by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("vm_viewer_dialog"),
            color = JarvisBackground
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header kontrol VM
                Surface(
                    color = JarvisSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                val dotColor = when (status.state) {
                                    VmState.RUNNING -> JarvisEmerald
                                    VmState.PROVISIONING -> JarvisAmber
                                    VmState.ERROR -> JarvisRed
                                    else -> JarvisTextTertiary
                                }
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Komputer Virtual",
                                        color = JarvisTextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${status.distro.displayName} · ${status.statusDetail}",
                                        color = JarvisTextSecondary,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                if (activeTab == 0 && status.state == VmState.RUNNING) {
                                    IconButton(
                                        onClick = { webViewRef?.reload() },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = "Muat ulang layar",
                                            tint = JarvisTextSecondary,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                }
                                if (status.state == VmState.RUNNING) {
                                    IconButton(
                                        onClick = { VirtualComputerManager.stopVmAsync() },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.PowerSettingsNew,
                                            contentDescription = "Hentikan VM",
                                            tint = JarvisRed,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Tutup",
                                        tint = JarvisTextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        // Segmented switcher (Layar Live | Terminal VM)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 10.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(JarvisBackground)
                                .border(1.dp, JarvisBorder, RoundedCornerShape(10.dp))
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            VmSegmentItem(
                                label = "Layar Live",
                                selected = activeTab == 0,
                                onClick = { activeTab = 0 },
                                modifier = Modifier.weight(1f)
                            )
                            VmSegmentItem(
                                label = "Terminal VM",
                                selected = activeTab == 1,
                                onClick = { activeTab = 1 },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        HorizontalDivider(color = JarvisBorder, thickness = 1.dp)
                    }
                }

                // Konten Utama
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(JarvisBackground)
                ) {
                    if (activeTab == 0) {
                        if (status.state == VmState.RUNNING && status.mode == VmMode.DESKTOP_GUI) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        setBackgroundColor(android.graphics.Color.parseColor("#0B0D10"))
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.useWideViewPort = true
                                        settings.loadWithOverviewMode = true
                                        settings.builtInZoomControls = true
                                        settings.displayZoomControls = false
                                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                                        webViewClient = WebViewClient()
                                        webChromeClient = WebChromeClient()
                                        loadUrl(status.novncUrl)
                                        webViewRef = this
                                    }
                                },
                                update = { view ->
                                    webViewRef = view
                                }
                            )
                        } else {
                            VmOfflineOrProvisioningBody(
                                status = status,
                                onStart = { VirtualComputerManager.startOrProvisionVmAsync(context) }
                            )
                        }
                    } else {
                        VmTerminalConsoleBody(
                            status = status,
                            input = terminalInput,
                            isBusy = isExecBusy,
                            onInputChange = { terminalInput = it },
                            onSend = {
                                val cmd = terminalInput.trim()
                                if (cmd.isNotEmpty() && !isExecBusy) {
                                    terminalInput = ""
                                    isExecBusy = true
                                    scope.launch(Dispatchers.IO) {
                                        VirtualComputerManager.execInVm(cmd, source = "USER")
                                        isExecBusy = false
                                    }
                                }
                            }
                        )
                    }
                }

                // Strip aksi cepat bawah (hanya di tab Layar Live saat RUNNING)
                if (activeTab == 0 && status.state == VmState.RUNNING && status.mode == VmMode.DESKTOP_GUI) {
                    Surface(
                        color = JarvisSurface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            HorizontalDivider(color = JarvisBorder, thickness = 1.dp)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                VmQuickActionButton(label = "Chromium") {
                                    scope.launch(Dispatchers.IO) {
                                        VirtualComputerManager.performDesktopAction("open_browser", url = "https://www.google.com")
                                    }
                                }
                                VmQuickActionButton(label = "Terminal") {
                                    scope.launch(Dispatchers.IO) {
                                        VirtualComputerManager.performDesktopAction("open_terminal")
                                    }
                                }
                                VmQuickActionButton(label = "Ctrl+C") {
                                    scope.launch(Dispatchers.IO) {
                                        VirtualComputerManager.performDesktopAction("key", key = "ctrl+c")
                                    }
                                }
                                VmQuickActionButton(label = "Screenshot") {
                                    scope.launch(Dispatchers.IO) {
                                        VirtualComputerManager.captureVmScreenshot()
                                    }
                                }
                                VmQuickActionButton(label = "Reconnect") {
                                    webViewRef?.loadUrl(status.novncUrl)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VmSegmentItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .heightIn(min = 38.dp)
            .clip(RoundedCornerShape(7.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(7.dp),
        color = if (selected) JarvisSurfaceVariant else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, JarvisBorderStrong) else null
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = label,
                color = if (selected) JarvisTextPrimary else JarvisTextSecondary,
                fontSize = 12.5.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun VmQuickActionButton(
    label: String,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 40.dp),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, JarvisBorder),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = JarvisSurfaceVariant,
            contentColor = JarvisTextPrimary
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun VmOfflineOrProvisioningBody(
    status: VmStatus,
    onStart: () -> Unit
) {
    val logs by VirtualComputerManager.logs.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (status.state == VmState.PROVISIONING) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.5.dp,
                color = JarvisCyan
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = status.statusDetail,
                color = JarvisTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Pemasangan pertama memerlukan koneksi internet dan waktu sekitar 1–3 menit.",
                color = JarvisTextSecondary,
                fontSize = 12.sp
            )
        } else {
            Icon(
                imageVector = Icons.Default.Computer,
                contentDescription = null,
                tint = JarvisTextTertiary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (status.mode == VmMode.TERMINAL_ONLY && status.state == VmState.RUNNING) {
                    "Mode Terminal Linux aktif"
                } else {
                    "Komputer Virtual belum aktif"
                },
                color = JarvisTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = status.lastError ?: "Nyalakan Komputer Virtual untuk melihat layar desktop Linux secara langsung di sini.",
                color = if (status.lastError != null) JarvisRed else JarvisTextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            if (status.state != VmState.RUNNING) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onStart,
                    modifier = Modifier.heightIn(min = 44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = JarvisCyan,
                        contentColor = JarvisOnPrimary
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp)
                ) {
                    Text(
                        text = "Mulai Komputer Virtual",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (logs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(20.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp),
                shape = RoundedCornerShape(10.dp),
                color = JarvisSurface,
                border = BorderStroke(1.dp, JarvisBorder)
            ) {
                LazyColumn(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(logs.takeLast(12), key = { it.id }) { entry ->
                        Text(
                            text = "[${entry.source}] ${entry.message}",
                            color = if (entry.isError) JarvisRed else JarvisTextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VmTerminalConsoleBody(
    status: VmStatus,
    input: String,
    isBusy: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val logs by VirtualComputerManager.logs.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            // scrollToItem (non-animasi): log VM bisa deras saat provisioning;
            // scroll animasi beruntun membuat frame drop di perangkat kelas bawah.
            listState.scrollToItem(logs.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (logs.isEmpty()) {
                item {
                    Text(
                        text = "Belum ada log terminal VM. Jalankan perintah Linux di bawah atau biarkan AI mengakses Komputer Virtual.",
                        color = JarvisTextTertiary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            } else {
                items(logs, key = { it.id }) { entry ->
                    val prefixColor = when {
                        entry.isError -> JarvisRed
                        entry.source == "AI" -> JarvisCyan
                        entry.source == "USER" -> JarvisEmerald
                        else -> JarvisTextTertiary
                    }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${timeFmt.format(Date(entry.timestamp))} · ${entry.source}",
                            color = prefixColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = entry.message,
                            color = if (entry.isError) JarvisRed else JarvisTextPrimary,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = JarvisBorder, thickness = 1.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(JarvisSurface)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                enabled = status.state == VmState.RUNNING && !isBusy,
                placeholder = {
                    Text(
                        text = if (status.state == VmState.RUNNING) "Jalankan perintah Linux (mis. ls -la)" else "Nyalakan VM untuk menjalankan perintah",
                        color = JarvisTextTertiary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp
                ),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = JarvisBorderStrong,
                    unfocusedBorderColor = JarvisBorder,
                    focusedTextColor = JarvisTextPrimary,
                    unfocusedTextColor = JarvisTextPrimary,
                    cursorColor = JarvisCyan,
                    focusedContainerColor = JarvisBackground,
                    unfocusedContainerColor = JarvisBackground
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() })
            )

            IconButton(
                onClick = onSend,
                enabled = status.state == VmState.RUNNING && input.isNotBlank() && !isBusy,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (status.state == VmState.RUNNING && input.isNotBlank() && !isBusy) JarvisCyan
                        else JarvisSurfaceVariant
                    )
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = JarvisCyan
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Kirim perintah",
                        tint = if (status.state == VmState.RUNNING && input.isNotBlank()) JarvisOnPrimary else JarvisTextTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Panel manajemen Komputer Virtual di layar Termux / Alat.
 * Clean single-surface Graphite Precision card, zero pill clutter.
 */
@Composable
fun VirtualComputerControlCard(
    onOpenViewer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val status by VirtualComputerManager.status.collectAsStateWithLifecycle()
    var showDestroyConfirm by remember { mutableStateOf(false) }

    val stateColor = when (status.state) {
        VmState.RUNNING -> JarvisEmerald
        VmState.PROVISIONING, VmState.AWAITING_APPROVAL -> JarvisAmber
        VmState.ERROR -> JarvisRed
        VmState.STOPPED -> JarvisTextTertiary
    }
    val stateText = when (status.state) {
        VmState.RUNNING -> "Aktif"
        VmState.PROVISIONING -> "Menyiapkan"
        VmState.AWAITING_APPROVAL -> "Menunggu izin"
        VmState.ERROR -> "Bermasalah"
        VmState.STOPPED -> "Berhenti"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("virtual_computer_control_card"),
        shape = RoundedCornerShape(14.dp),
        color = JarvisSurface,
        border = BorderStroke(1.dp, JarvisBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                    Icon(
                        imageVector = Icons.Default.Computer,
                        contentDescription = null,
                        tint = JarvisCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Komputer Virtual AI",
                        color = JarvisTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(stateColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stateText,
                        color = stateColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }

            Text(
                text = "Lingkungan Linux ARM64 terisolasi via Termux proot-distro dengan desktop Openbox + noVNC, Chromium CDP, dan dukungan penuh untuk model Vision maupun Non-Vision.",
                color = JarvisTextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )

            HorizontalDivider(color = JarvisBorder, thickness = 1.dp)

            // Distro selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Distribusi Linux",
                    color = JarvisTextTertiary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(JarvisBackground)
                        .border(1.dp, JarvisBorder, RoundedCornerShape(10.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    VmSegmentItem(
                        label = "Debian (~750 MB)",
                        selected = status.distro == VmDistro.DEBIAN,
                        onClick = {
                            if (status.state == VmState.STOPPED || status.state == VmState.ERROR) {
                                VirtualComputerManager.updatePreferredConfig(distro = VmDistro.DEBIAN)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    VmSegmentItem(
                        label = "Ubuntu (~900 MB)",
                        selected = status.distro == VmDistro.UBUNTU,
                        onClick = {
                            if (status.state == VmState.STOPPED || status.state == VmState.ERROR) {
                                VirtualComputerManager.updatePreferredConfig(distro = VmDistro.UBUNTU)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Mode selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Mode Operasi",
                    color = JarvisTextTertiary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(JarvisBackground)
                        .border(1.dp, JarvisBorder, RoundedCornerShape(10.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    VmSegmentItem(
                        label = "Desktop GUI",
                        selected = status.mode == VmMode.DESKTOP_GUI,
                        onClick = {
                            if (status.state == VmState.STOPPED || status.state == VmState.ERROR) {
                                VirtualComputerManager.updatePreferredConfig(mode = VmMode.DESKTOP_GUI)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    VmSegmentItem(
                        label = "Terminal CLI",
                        selected = status.mode == VmMode.TERMINAL_ONLY,
                        onClick = {
                            if (status.state == VmState.STOPPED || status.state == VmState.ERROR) {
                                VirtualComputerManager.updatePreferredConfig(mode = VmMode.TERMINAL_ONLY)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Chromium toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Sertakan Browser Chromium",
                        color = JarvisTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Mendukung tampilan web penuh & kontrol DOM (CDP)",
                        color = JarvisTextTertiary,
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = status.includeBrowser,
                    enabled = status.state == VmState.STOPPED || status.state == VmState.ERROR,
                    onCheckedChange = { checked ->
                        VirtualComputerManager.updatePreferredConfig(includeBrowser = checked)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = JarvisOnPrimary,
                        checkedTrackColor = JarvisCyan,
                        uncheckedThumbColor = JarvisTextSecondary,
                        uncheckedTrackColor = JarvisSurfaceVariant,
                        uncheckedBorderColor = JarvisBorderStrong
                    )
                )
            }

            if (!status.lastError.isNullOrBlank()) {
                Text(
                    text = status.lastError ?: "",
                    color = JarvisRed,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )
            } else {
                Text(
                    text = status.statusDetail,
                    color = JarvisTextTertiary,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (status.state == VmState.RUNNING) {
                    Button(
                        onClick = onOpenViewer,
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
                        Text(
                            text = if (status.mode == VmMode.DESKTOP_GUI) "Lihat Layar Live" else "Buka Terminal VM",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                    OutlinedButton(
                        onClick = { VirtualComputerManager.stopVmAsync() },
                        modifier = Modifier
                            .weight(0.9f)
                            .heightIn(min = 44.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, JarvisRed.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisRed)
                    ) {
                        Text("Hentikan", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                } else {
                    Button(
                        onClick = { VirtualComputerManager.startOrProvisionVmAsync(context) },
                        enabled = status.state != VmState.PROVISIONING,
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
                        Text(
                            text = if (status.state == VmState.PROVISIONING) "Menyiapkan..." else "Mulai VM",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenViewer,
                        modifier = Modifier
                            .weight(0.9f)
                            .heightIn(min = 44.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, JarvisBorderStrong),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisTextPrimary)
                    ) {
                        Text("Log & Layar", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
            }

            if (status.isApproved && status.state != VmState.PROVISIONING) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { showDestroyConfirm = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Hapus Rootfs VM",
                            color = JarvisTextTertiary,
                            fontSize = 11.5.sp
                        )
                    }
                }
            }
        }
    }

    if (showDestroyConfirm) {
        AlertDialog(
            onDismissRequest = { showDestroyConfirm = false },
            containerColor = JarvisSurface,
            shape = RoundedCornerShape(14.dp),
            title = {
                Text(
                    text = "Hapus Komputer Virtual?",
                    color = JarvisTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = "Seluruh sistem ${status.distro.displayName} dan file di dalam container VM akan dihapus untuk membebaskan ruang penyimpanan. File di /sdcard tidak terpengaruh.",
                    color = JarvisTextSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDestroyConfirm = false
                        VirtualComputerManager.destroyVmAsync()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisRed, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Hapus VM", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDestroyConfirm = false },
                    border = BorderStroke(1.dp, JarvisBorderStrong),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Batal", color = JarvisTextPrimary, fontSize = 12.5.sp)
                }
            }
        )
    }
}
