package com.example.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.AiPermissionMode
import com.example.model.ChatMessage
import com.example.model.ChatSender
import com.example.service.JarvisVoiceManager
import com.example.service.ToolManager
import com.example.service.VmState
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private val ChatBorder = BorderStroke(1.dp, JarvisBorder)
private val ChatBorderStrong = BorderStroke(1.dp, JarvisBorderStrong)

private val ChatTimeFormatter = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
}

/**
 * Layar Percakapan AI dengan arsitektur isolasi state ketat (60fps):
 * - Input teks & lampiran terisolasi di ChatComposerFooter sehingga mengetik TIDAK PERNAH me-recompose LazyColumn.
 * - Status berpikir & live thought terisolasi di ChatLiveProgressBanner.
 * - Header & sub-header terisolasi masing-masing; dialog hanya mengoleksi StateFlow saat dibuka.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvisChatScreen(viewModel: JarvisViewModel) {
    val context = LocalContext.current

    var showConfigDialog by remember { mutableStateOf(false) }
    var openConfigFreeTab by remember { mutableStateOf(false) }
    var showPermsDialog by remember { mutableStateOf(false) }
    var showCustomPermsDialog by remember { mutableStateOf(false) }
    var showVoiceCallDialog by remember { mutableStateOf(false) }
    var showSessionDialog by remember { mutableStateOf(false) }
    var showThinkingHistoryDialog by remember { mutableStateOf(false) }
    var showTerminalDialog by remember { mutableStateOf(false) }
    var showVmViewerDialog by remember { mutableStateOf(false) }
    var codeToPreview by remember { mutableStateOf<CodePreviewPayload?>(null) }

    var pendingAudioAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingAudioAction?.invoke()
        } else {
            Toast.makeText(context, "Izin mikrofon diperlukan untuk fitur suara", Toast.LENGTH_SHORT).show()
        }
        pendingAudioAction = null
    }

    val checkAndRunAudioAction = remember(context) {
        { action: () -> Unit ->
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                action()
            } else {
                pendingAudioAction = action
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val onOpenConfig = remember {
        { freeTab: Boolean ->
            openConfigFreeTab = freeTab
            showConfigDialog = true
        }
    }
    val onOpenPerms = remember { { showPermsDialog = true } }
    val onOpenVmViewer = remember { { showVmViewerDialog = true } }
    val onStartVoiceCall = remember(viewModel, checkAndRunAudioAction) {
        {
            checkAndRunAudioAction {
                showVoiceCallDialog = true
                viewModel.startVoiceCall()
            }
        }
    }
    val onOpenSessions = remember { { showSessionDialog = true } }
    val onOpenTerminal = remember { { showTerminalDialog = true } }
    val onOpenThinkingHistory = remember { { showThinkingHistoryDialog = true } }
    val onPreviewCode = remember { { payload: CodePreviewPayload -> codeToPreview = payload } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JarvisBackground)
    ) {
        ChatTopHeader(
            viewModel = viewModel,
            checkAndRunAudioAction = checkAndRunAudioAction,
            onOpenConfig = onOpenConfig,
            onOpenPerms = onOpenPerms,
            onOpenVmViewer = onOpenVmViewer,
            onStartVoiceCall = onStartVoiceCall
        )

        ChatSubHeader(
            viewModel = viewModel,
            onOpenSessions = onOpenSessions,
            onOpenTerminal = onOpenTerminal,
            onOpenThinkingHistory = onOpenThinkingHistory
        )

        ChatAgentPlanSection()

        ChatMessagesList(
            viewModel = viewModel,
            onPreviewCode = onPreviewCode,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )

        ChatVmInlineSection(
            viewModel = viewModel,
            onOpenViewer = onOpenVmViewer
        )

        ChatLiveProgressBanner(viewModel = viewModel)

        ChatComposerFooter(
            viewModel = viewModel,
            checkAndRunAudioAction = checkAndRunAudioAction
        )
    }

    ChatDialogsHost(
        viewModel = viewModel,
        showVoiceCallDialog = showVoiceCallDialog,
        onDismissVoiceCall = {
            showVoiceCallDialog = false
            viewModel.endVoiceCall()
        },
        showConfigDialog = showConfigDialog,
        openConfigFreeTab = openConfigFreeTab,
        onDismissConfig = { showConfigDialog = false },
        codeToPreview = codeToPreview,
        onDismissCodePreview = { codeToPreview = null },
        showVmViewerDialog = showVmViewerDialog,
        onDismissVmViewer = { showVmViewerDialog = false },
        showPermsDialog = showPermsDialog,
        onDismissPerms = { showPermsDialog = false },
        onOpenCustomPerms = {
            showPermsDialog = false
            showCustomPermsDialog = true
        },
        showCustomPermsDialog = showCustomPermsDialog,
        onDismissCustomPerms = { showCustomPermsDialog = false },
        showSessionDialog = showSessionDialog,
        onDismissSession = { showSessionDialog = false },
        showThinkingHistoryDialog = showThinkingHistoryDialog,
        onDismissThinkingHistory = { showThinkingHistoryDialog = false },
        showTerminalDialog = showTerminalDialog,
        onDismissTerminal = { showTerminalDialog = false }
    )
}

@Composable
private fun ChatTopHeader(
    viewModel: JarvisViewModel,
    checkAndRunAudioAction: (() -> Unit) -> Unit,
    onOpenConfig: (Boolean) -> Unit,
    onOpenPerms: () -> Unit,
    onOpenVmViewer: () -> Unit,
    onStartVoiceCall: () -> Unit
) {
    val context = LocalContext.current
    val isThinking by viewModel.isChatAiThinking.collectAsStateWithLifecycle()
    val baseUrl by viewModel.chatBaseUrl.collectAsStateWithLifecycle()
    val modelName by viewModel.chatModelName.collectAsStateWithLifecycle()
    val permissionMode by ToolManager.permissionMode.collectAsStateWithLifecycle()
    val autoReadEnabled by JarvisVoiceManager.autoReadEnabled.collectAsStateWithLifecycle()
    val isHotwordEnabled by viewModel.isHotwordEnabled.collectAsStateWithLifecycle()
    val vmStatus by com.example.service.VirtualComputerManager.status.collectAsStateWithLifecycle()
    val vmPendingApproval by com.example.service.VirtualComputerManager.pendingApproval.collectAsStateWithLifecycle()

    val quotaRevision by com.example.data.FreeQuotaManager.revision.collectAsStateWithLifecycle()
    val orHighTier by com.example.data.FreeQuotaManager.openRouterHighTier.collectAsStateWithLifecycle()
    val enforceLimit by com.example.data.FreeQuotaManager.enforceLocalLimit.collectAsStateWithLifecycle()
    var chatQuotaTickMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val chatFreeQuota = remember(baseUrl, modelName, quotaRevision, orHighTier, enforceLimit, chatQuotaTickMs) {
        com.example.data.FreeQuotaManager.getStatus(baseUrl, modelName, chatQuotaTickMs)
    }
    LaunchedEffect(baseUrl, modelName, chatFreeQuota?.isBlocked) {
        if (chatFreeQuota?.isBlocked == true) {
            while (true) {
                kotlinx.coroutines.delay(1_000L)
                chatQuotaTickMs = System.currentTimeMillis()
            }
        }
    }

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
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isThinking) JarvisAmber else JarvisEmerald)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { onOpenConfig(false) }
                        ) {
                            Text(
                                text = if (modelName.isNotBlank()) modelName else "Pilih model",
                                color = JarvisTextPrimary,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = JarvisTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            chatFreeQuota?.let { fq ->
                                val badgeColor = when {
                                    fq.isBlocked -> JarvisRed
                                    fq.periodRemainingFraction <= 0.2f -> JarvisAmber
                                    else -> JarvisEmerald
                                }
                                Text(
                                    text = if (fq.isBlocked) {
                                        "Jeda ${com.example.data.FreeQuotaManager.formatCountdown(fq.activeWaitMs)}"
                                    } else {
                                        "${fq.periodRemaining}/${fq.periodLimit}"
                                    },
                                    color = badgeColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .clickable { onOpenConfig(true) }
                                        .testTag("chat_free_quota_badge")
                                )
                                Text(text = "·", color = JarvisTextTertiary, fontSize = 10.sp)
                            }
                            val permLabel = when (permissionMode) {
                                AiPermissionMode.SANDBOXED -> "Sandbox"
                                AiPermissionMode.LOW_RISK -> "Terbatas"
                                AiPermissionMode.FULL_ACCESS -> "Akses penuh"
                                AiPermissionMode.CUSTOM -> "Izin kustom"
                            }
                            Text(
                                text = permLabel,
                                color = JarvisTextSecondary,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable(onClick = onOpenPerms)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val vmRunning = vmStatus.state == VmState.RUNNING
                    val vmPending = vmPendingApproval != null || vmStatus.state == VmState.PROVISIONING
                    IconButton(
                        onClick = onOpenVmViewer,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    vmRunning -> JarvisEmerald.copy(alpha = 0.14f)
                                    vmPending -> JarvisAmber.copy(alpha = 0.14f)
                                    else -> Color.Transparent
                                }
                            )
                            .testTag("chat_vm_viewer_button")
                    ) {
                        Icon(
                            Icons.Default.Computer,
                            contentDescription = "Komputer Virtual",
                            tint = when {
                                vmRunning -> JarvisEmerald
                                vmPending -> JarvisAmber
                                else -> JarvisTextSecondary
                            },
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            checkAndRunAudioAction {
                                val newState = viewModel.toggleHotword(context)
                                Toast.makeText(
                                    context,
                                    if (newState) "Asisten suara latar belakang aktif"
                                    else "Asisten suara latar belakang nonaktif",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isHotwordEnabled) JarvisEmerald.copy(alpha = 0.14f) else Color.Transparent)
                            .testTag("chat_background_hotword_toggle")
                    ) {
                        Icon(
                            Icons.Default.RecordVoiceOver,
                            contentDescription = "Asisten suara latar belakang",
                            tint = if (isHotwordEnabled) JarvisEmerald else JarvisTextSecondary,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    IconButton(
                        onClick = onStartVoiceCall,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("chat_voice_call_button")
                    ) {
                        Icon(
                            Icons.Default.GraphicEq,
                            contentDescription = "Mode percakapan suara",
                            tint = JarvisTextSecondary,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            viewModel.toggleAutoRead()
                            Toast.makeText(
                                context,
                                if (!autoReadEnabled) "Baca otomatis aktif" else "Baca otomatis nonaktif",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (autoReadEnabled) JarvisCyan.copy(alpha = 0.14f) else Color.Transparent)
                            .testTag("chat_auto_read_toggle")
                    ) {
                        Icon(
                            imageVector = if (autoReadEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = "Baca balasan otomatis",
                            tint = if (autoReadEnabled) JarvisCyan else JarvisTextSecondary,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    IconButton(
                        onClick = { onOpenConfig(false) },
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("chat_config_button")
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Pengaturan model dan API",
                            tint = JarvisTextSecondary,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    IconButton(
                        onClick = { viewModel.clearChat() },
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("chat_clear_button")
                    ) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = "Bersihkan percakapan",
                            tint = JarvisTextSecondary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
            HorizontalDivider(color = JarvisBorder, thickness = 1.dp)
        }
    }
}

@Composable
private fun ChatSubHeader(
    viewModel: JarvisViewModel,
    onOpenSessions: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenThinkingHistory: () -> Unit
) {
    val context = LocalContext.current
    val currentSession by viewModel.currentSession.collectAsStateWithLifecycle()
    val terminalEntries by com.example.service.AgentTerminalManager.entries.collectAsStateWithLifecycle()
    val termCount = terminalEntries.size

    Surface(
        color = JarvisBackground,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = JarvisSurface,
                    border = ChatBorder,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpenSessions)
                        .testTag("chat_open_sessions_pill")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Forum,
                            contentDescription = null,
                            tint = JarvisTextSecondary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = currentSession?.title ?: "Sesi Percakapan",
                            color = JarvisTextPrimary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = JarvisTextSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = JarvisSurface,
                        border = ChatBorder,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                viewModel.createNewSession()
                                Toast.makeText(context, "Sesi baru dibuat", Toast.LENGTH_SHORT).show()
                            }
                            .testTag("chat_quick_new_session")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Sesi baru",
                                tint = JarvisTextPrimary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Sesi",
                                color = JarvisTextPrimary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (termCount > 0) JarvisCyan.copy(alpha = 0.12f) else JarvisSurface,
                        border = if (termCount > 0) BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.45f)) else ChatBorder,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onOpenTerminal)
                            .testTag("chat_terminal_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Terminal,
                                contentDescription = "Terminal AI",
                                tint = if (termCount > 0) JarvisCyan else JarvisTextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (termCount > 0) "Term ($termCount)" else "Terminal",
                                color = if (termCount > 0) JarvisCyan else JarvisTextSecondary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = JarvisSurface,
                        border = ChatBorder,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onOpenThinkingHistory)
                            .testTag("chat_thinking_history_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Psychology,
                                contentDescription = "Log penalaran",
                                tint = JarvisTextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Log Pikir",
                                color = JarvisTextSecondary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = JarvisBorder, thickness = 1.dp)
        }
    }
}

@Composable
private fun ChatAgentPlanSection() {
    val agentPlanSteps by com.example.service.AgentWorkspaceTools.planSteps.collectAsStateWithLifecycle()
    val onClearPlan = remember { { com.example.service.AgentWorkspaceTools.clearPlan() } }
    AgentPlanBanner(
        steps = agentPlanSteps,
        onClear = onClearPlan
    )
}

@Composable
private fun ChatMessagesList(
    viewModel: JarvisViewModel,
    onPreviewCode: (CodePreviewPayload) -> Unit,
    modifier: Modifier = Modifier
) {
    val messages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp)
    ) {
        items(
            items = messages,
            key = { it.id },
            contentType = { it.sender }
        ) { msg ->
            ChatBubble(
                message = msg,
                onPreviewCode = onPreviewCode
            )
        }
    }
}

@Composable
private fun ChatVmInlineSection(
    viewModel: JarvisViewModel,
    onOpenViewer: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val vmStatus by com.example.service.VirtualComputerManager.status.collectAsStateWithLifecycle()
    val vmPendingApproval by com.example.service.VirtualComputerManager.pendingApproval.collectAsStateWithLifecycle()

    vmPendingApproval?.let { req ->
        VmApprovalChatCard(
            request = req,
            onApprove = {
                onOpenViewer()
                com.example.service.VirtualComputerManager.approveAndStart(context) { ok, msg ->
                    if (ok && !viewModel.isChatAiThinking.value) {
                        viewModel.sendChatMessage(
                            userText = "Saya sudah menyetujui dan Komputer Virtual (${req.distro.displayName}) sekarang sudah aktif. Silakan lanjutkan tugasmu di Komputer Virtual."
                        )
                    } else if (!ok) {
                        coroutineScope.launch(Dispatchers.Main) {
                            Toast.makeText(context, msg.take(120), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onReject = {
                com.example.service.VirtualComputerManager.rejectApproval("Ditolak oleh pengguna di Chat")
                if (!viewModel.isChatAiThinking.value) {
                    viewModel.sendChatMessage(
                        userText = "Saya menolak permintaan pembuatan Komputer Virtual untuk saat ini. Tolong selesaikan tanpa Komputer Virtual atau tanyakan alternatif lain."
                    )
                }
            }
        )
    }

    if (vmPendingApproval == null && (vmStatus.state == VmState.RUNNING || vmStatus.state == VmState.PROVISIONING)) {
        VmActiveStatusBar(
            status = vmStatus,
            onOpenViewer = onOpenViewer,
            onStop = {
                coroutineScope.launch(Dispatchers.IO) {
                    com.example.service.VirtualComputerManager.stopVm()
                }
            }
        )
    }
}

/**
 * Satu status bar ringkas & presisi saat AI memproses (menggantikan 2 banner animasi ganda sebelumnya).
 */
@Composable
private fun ChatLiveProgressBanner(viewModel: JarvisViewModel) {
    val isThinking by viewModel.isChatAiThinking.collectAsStateWithLifecycle()
    if (!isThinking) return

    val statusText by viewModel.chatStatusText.collectAsStateWithLifecycle()
    val liveThought by viewModel.liveThought.collectAsStateWithLifecycle()
    var showThought by remember { mutableStateOf(false) }

    Surface(
        color = JarvisSurface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(color = JarvisBorder, thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = JarvisCyan
                )
                Text(
                    text = statusText.ifBlank { "AI sedang memproses..." },
                    color = JarvisTextPrimary,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (!liveThought.isNullOrBlank()) {
                    Text(
                        text = if (showThought) "Sembunyikan" else "Penalaran",
                        color = JarvisCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { showThought = !showThought }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = JarvisRed.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, JarvisRed.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .clickable { viewModel.stopAiExecution() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Hentikan AI", tint = JarvisRed, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Hentikan", color = JarvisRed, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (showThought && !liveThought.isNullOrBlank()) {
                Text(
                    text = liveThought.orEmpty(),
                    color = JarvisTextSecondary,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 14.5.sp,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(JarvisBackground)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/**
 * Composer teks terisolasi — perubahan inputText saat mengetik hanya me-recompose komponen ini.
 */
@Composable
private fun ChatComposerFooter(
    viewModel: JarvisViewModel,
    checkAndRunAudioAction: (() -> Unit) -> Unit
) {
    val context = LocalContext.current
    val isThinking by viewModel.isChatAiThinking.collectAsStateWithLifecycle()
    val isVoiceListening by JarvisVoiceManager.isListening.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf("") }
    var selectedAttachmentUri by remember { mutableStateOf<String?>(null) }
    var selectedAttachmentName by remember { mutableStateOf<String?>(null) }
    var selectedAttachmentMimeType by remember { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedAttachmentUri = uri.toString()
            selectedAttachmentMimeType = context.contentResolver.getType(uri) ?: "*/*"
            var name = "Lampiran"
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0) {
                            name = cursor.getString(nameIdx)
                        }
                    }
                }
            } catch (_: Exception) {
            }
            selectedAttachmentName = name
            Toast.makeText(context, "Terlampir: $name", Toast.LENGTH_SHORT).show()
        }
    }

    fun dispatchCurrentMessage() {
        if ((inputText.isNotBlank() || selectedAttachmentUri != null) && !isThinking) {
            viewModel.sendChatMessage(
                userText = inputText,
                attachmentUri = selectedAttachmentUri,
                attachmentName = selectedAttachmentName,
                attachmentMimeType = selectedAttachmentMimeType
            )
            inputText = ""
            selectedAttachmentUri = null
            selectedAttachmentName = null
            selectedAttachmentMimeType = null
        }
    }

    Surface(
        color = JarvisSurface,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (selectedAttachmentUri != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = JarvisSurfaceVariant,
                    border = BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (selectedAttachmentMimeType?.startsWith("image/") == true) Icons.Default.Image else Icons.Default.InsertDriveFile,
                                contentDescription = "Lampiran",
                                tint = JarvisCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = selectedAttachmentName ?: "Lampiran dipilih",
                                color = JarvisTextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = {
                                selectedAttachmentUri = null
                                selectedAttachmentName = null
                                selectedAttachmentMimeType = null
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Hapus lampiran",
                                tint = JarvisTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = JarvisBorder, thickness = 1.dp)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("chat_attach_file_button")
                ) {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = "Lampirkan file atau foto",
                        tint = JarvisTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = {
                        Text(
                            if (isThinking) "Sedang memproses — ketuk hentikan untuk batal" else "Tulis pesan atau instruksi...",
                            color = JarvisTextTertiary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_input_textfield"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = JarvisBorderStrong,
                        unfocusedBorderColor = JarvisBorder,
                        focusedTextColor = JarvisTextPrimary,
                        unfocusedTextColor = JarvisTextPrimary,
                        cursorColor = JarvisCyan,
                        focusedContainerColor = JarvisSurfaceVariant,
                        unfocusedContainerColor = JarvisSurfaceVariant
                    ),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { dispatchCurrentMessage() })
                )

                Spacer(modifier = Modifier.width(6.dp))

                val micButtonColor by animateColorAsState(
                    targetValue = if (isVoiceListening) JarvisRed else JarvisSurfaceVariant,
                    label = "mic_color"
                )
                IconButton(
                    onClick = {
                        if (isVoiceListening) {
                            JarvisVoiceManager.stopListening()
                        } else {
                            checkAndRunAudioAction {
                                JarvisVoiceManager.startListening(
                                    context = context,
                                    onResult = { spoken ->
                                        inputText = if (inputText.isBlank()) spoken else "$inputText $spoken"
                                    },
                                    onError = { err ->
                                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(micButtonColor)
                        .border(1.dp, if (isVoiceListening) JarvisRed else JarvisBorder, RoundedCornerShape(12.dp))
                        .testTag("chat_mic_button")
                ) {
                    Icon(
                        imageVector = if (isVoiceListening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Input suara",
                        tint = if (isVoiceListening) Color.White else JarvisTextSecondary,
                        modifier = Modifier.size(19.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                val canSend = inputText.isNotBlank() || selectedAttachmentUri != null
                if (isThinking) {
                    FloatingActionButton(
                        onClick = { viewModel.stopAiExecution() },
                        modifier = Modifier
                            .size(42.dp)
                            .testTag("chat_stop_ai_button"),
                        containerColor = JarvisRed,
                        contentColor = Color.White,
                        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "Hentikan eksekusi AI",
                            modifier = Modifier.size(19.dp)
                        )
                    }
                } else {
                    FloatingActionButton(
                        onClick = { dispatchCurrentMessage() },
                        modifier = Modifier
                            .size(42.dp)
                            .testTag("chat_send_button"),
                        containerColor = if (canSend) JarvisCyan else JarvisSurfaceVariant,
                        contentColor = if (canSend) JarvisOnPrimary else JarvisTextTertiary,
                        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Kirim pesan",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatDialogsHost(
    viewModel: JarvisViewModel,
    showVoiceCallDialog: Boolean,
    onDismissVoiceCall: () -> Unit,
    showConfigDialog: Boolean,
    openConfigFreeTab: Boolean,
    onDismissConfig: () -> Unit,
    codeToPreview: CodePreviewPayload?,
    onDismissCodePreview: () -> Unit,
    showVmViewerDialog: Boolean,
    onDismissVmViewer: () -> Unit,
    showPermsDialog: Boolean,
    onDismissPerms: () -> Unit,
    onOpenCustomPerms: () -> Unit,
    showCustomPermsDialog: Boolean,
    onDismissCustomPerms: () -> Unit,
    showSessionDialog: Boolean,
    onDismissSession: () -> Unit,
    showThinkingHistoryDialog: Boolean,
    onDismissThinkingHistory: () -> Unit,
    showTerminalDialog: Boolean,
    onDismissTerminal: () -> Unit
) {
    val isVoiceCallActive by JarvisVoiceManager.isVoiceCallActive.collectAsStateWithLifecycle()
    val pendingDeletionReq by ToolManager.pendingDeletionRequest.collectAsStateWithLifecycle()

    if (showVoiceCallDialog || isVoiceCallActive) {
        JarvisVoiceCallDialog(
            onDismiss = onDismissVoiceCall,
            onSendMessage = { spokenInput -> viewModel.sendChatMessage(spokenInput) }
        )
    }

    pendingDeletionReq?.let { req ->
        AlertDialog(
            onDismissRequest = { ToolManager.denyPendingDeletion() },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = JarvisRed)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = req.title,
                        color = JarvisTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            text = {
                Text(
                    text = req.details,
                    color = JarvisTextSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { ToolManager.confirmPendingDeletion() },
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisRed, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Izinkan Hapus", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { ToolManager.denyPendingDeletion() },
                    shape = RoundedCornerShape(8.dp),
                    border = ChatBorder
                ) {
                    Text("Tolak", color = JarvisTextPrimary, fontSize = 12.sp)
                }
            },
            containerColor = JarvisSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showConfigDialog) {
        val currentAiConfig by viewModel.aiConfig.collectAsStateWithLifecycle()
        AiConfigDialog(
            currentConfig = currentAiConfig,
            onSave = { key, url, model, provider ->
                viewModel.updateAiConfig(key, url, model, provider)
                onDismissConfig()
            },
            onDismiss = onDismissConfig,
            initialFilterFree = openConfigFreeTab
        )
    }

    codeToPreview?.let { payload ->
        HtmlPreviewDialog(
            htmlCode = payload.code,
            fileName = payload.fileName,
            language = payload.language,
            onFileNameChange = payload.onFileNameChange,
            onDismiss = onDismissCodePreview
        )
    }

    if (showVmViewerDialog) {
        VirtualComputerViewerDialog(onDismiss = onDismissVmViewer)
    }

    if (showPermsDialog) {
        val permissionMode by ToolManager.permissionMode.collectAsStateWithLifecycle()
        AlertDialog(
            onDismissRequest = onDismissPerms,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = JarvisCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Pilih Mode Izin AI", color = JarvisTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Tentukan batasan akses otonom AI terhadap perangkat:",
                        color = JarvisTextSecondary,
                        fontSize = 12.sp
                    )
                    PermissionModeCard(
                        currentMode = permissionMode,
                        onModeSelected = { mode ->
                            ToolManager.setPermissionMode(mode)
                            if (mode == AiPermissionMode.CUSTOM) {
                                onOpenCustomPerms()
                            } else {
                                onDismissPerms()
                            }
                        },
                        onOpenCustomSettings = onOpenCustomPerms
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissPerms) {
                    Text("Tutup", color = JarvisCyan)
                }
            },
            containerColor = JarvisSurface
        )
    }

    if (showCustomPermsDialog) {
        val customPerms by ToolManager.customPermissions.collectAsStateWithLifecycle()
        CustomPermissionsDialog(
            settings = customPerms,
            onDismiss = onDismissCustomPerms,
            onSave = { updated ->
                ToolManager.updateCustomPermissions(updated)
                onDismissCustomPerms()
            }
        )
    }

    if (showSessionDialog) {
        val sessions by viewModel.chatSessions.collectAsStateWithLifecycle()
        val currentSession by viewModel.currentSession.collectAsStateWithLifecycle()
        JarvisSessionManagerDialog(
            sessions = sessions,
            currentSessionId = currentSession?.id ?: "",
            onSelectSession = { id -> viewModel.switchSession(id) },
            onCreateNewSession = { title -> viewModel.createNewSession(title) },
            onDeleteSession = { id -> viewModel.deleteSession(id) },
            onRenameSession = { id, newTitle -> viewModel.renameSession(id, newTitle) },
            onClearCurrentSession = { viewModel.clearChat() },
            onDismiss = onDismissSession
        )
    }

    if (showThinkingHistoryDialog) {
        val messages by viewModel.chatMessages.collectAsStateWithLifecycle()
        JarvisThinkingHistoryDialog(
            messages = messages,
            onDismiss = onDismissThinkingHistory
        )
    }

    if (showTerminalDialog) {
        AgentTerminalModalDialog(onDismiss = onDismissTerminal)
    }
}

@Composable
fun ChatBubble(
    message: ChatMessage,
    onPreviewCode: (CodePreviewPayload) -> Unit = {}
) {
    val context = LocalContext.current
    val isUser = message.sender == ChatSender.USER
    val isSystem = message.sender == ChatSender.SYSTEM

    val timeStr = remember(message.timestamp) {
        ChatTimeFormatter.get()?.format(Date(message.timestamp)).orEmpty()
    }
    val segments = remember(message.text) { ChatMessageParser.parse(message.text) }
    val hasCodeBlocks = remember(segments) {
        segments.any { it is ChatContentSegment.CodeBlockSegment }
    }
    val detectedFallbackHtml = remember(message.text, hasCodeBlocks) {
        if (hasCodeBlocks) null else HtmlCodeExtractor.extractHtml(message.text)
    }
    val customCodeFileNames = remember(message.id) { mutableStateMapOf<Int, String>() }

    if (isSystem) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = JarvisSurfaceVariant,
                shape = RoundedCornerShape(10.dp),
                border = ChatBorder
            ) {
                Text(
                    text = message.text,
                    color = JarvisTextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Surface(
                modifier = Modifier.size(26.dp),
                shape = RoundedCornerShape(8.dp),
                color = JarvisSurfaceVariant,
                border = ChatBorder
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "A",
                        color = JarvisTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            modifier = if (!isUser && hasCodeBlocks) {
                Modifier.weight(1f)
            } else {
                Modifier.widthIn(max = 316.dp)
            },
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                modifier = if (!isUser && hasCodeBlocks) Modifier.fillMaxWidth() else Modifier,
                shape = RoundedCornerShape(
                    topStart = 14.dp,
                    topEnd = 14.dp,
                    bottomStart = if (isUser) 14.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 14.dp
                ),
                color = if (isUser) JarvisSurfaceElevated else JarvisSurface,
                border = if (isUser) ChatBorderStrong else ChatBorder
            ) {
                Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                    if (!isUser && !message.thinkingProcess.isNullOrBlank()) {
                        ThinkingTraceCard(
                            thinking = message.thinkingProcess,
                            actionToolName = message.actionToolName
                        )
                    }

                    val isLocalImageFile = message.attachmentMimeType?.startsWith("image/") == true &&
                        !message.attachmentUri.isNullOrBlank() &&
                        !message.attachmentUri.startsWith("content://")

                    if (isLocalImageFile) {
                        LocalImagePreviewCard(
                            filePath = message.attachmentUri!!,
                            caption = message.attachmentName
                        )
                    } else if (!message.attachmentName.isNullOrBlank() || !message.attachmentUri.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = JarvisSurfaceVariant,
                            border = ChatBorder,
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (message.attachmentMimeType?.startsWith("image/") == true) Icons.Default.Image else Icons.Default.InsertDriveFile,
                                    contentDescription = "Lampiran",
                                    tint = JarvisCyan,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = message.attachmentName ?: "Lampiran",
                                    color = JarvisTextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        segments.forEach { segment ->
                            when (segment) {
                                is ChatContentSegment.TextSegment -> {
                                    if (segment.text.isNotBlank()) {
                                        Text(
                                            text = segment.text,
                                            color = JarvisTextPrimary,
                                            fontSize = 13.5.sp,
                                            lineHeight = 19.sp
                                        )
                                    }
                                }
                                is ChatContentSegment.CodeBlockSegment -> {
                                    val resolvedName = customCodeFileNames[segment.index] ?: segment.suggestedFileName
                                    CodeBlockCard(
                                        segment = segment,
                                        fileName = resolvedName,
                                        onRename = { newName ->
                                            customCodeFileNames[segment.index] = newName
                                        },
                                        onPreview = { currentName ->
                                            onPreviewCode(
                                                CodePreviewPayload(
                                                    code = segment.code,
                                                    fileName = currentName,
                                                    language = segment.language,
                                                    onFileNameChange = { renamed ->
                                                        customCodeFileNames[segment.index] = renamed
                                                    }
                                                )
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (detectedFallbackHtml != null) {
                        HtmlPreviewBanner(
                            htmlCode = detectedFallbackHtml,
                            onOpenPreview = {
                                onPreviewCode(
                                    CodePreviewPayload(
                                        code = detectedFallbackHtml,
                                        fileName = "index.html",
                                        language = "html"
                                    )
                                )
                            }
                        )
                    }

                    if (message.actionResult != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            color = JarvisSurfaceVariant,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, JarvisEmerald.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = JarvisEmerald,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = message.actionResult,
                                    color = JarvisEmerald,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                Text(
                    text = timeStr,
                    color = JarvisTextTertiary,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Pesan Asisten", message.text))
                            Toast.makeText(context, "Pesan disalin", Toast.LENGTH_SHORT).show()
                        }
                        .padding(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Salin pesan",
                        tint = JarvisTextSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                }
                if (!isUser) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { JarvisVoiceManager.speak(message.text) }
                            .padding(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Dengarkan jawaban",
                            tint = JarvisTextSecondary,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(6.dp))
            Surface(
                modifier = Modifier.size(26.dp),
                shape = CircleShape,
                color = JarvisSurfaceVariant,
                border = ChatBorder
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = "Pengguna",
                        tint = JarvisTextPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

/**
 * Kartu penalaran ringkas & dapat dilipat di dalam gelembung AI (tanpa pendaran neon).
 */
@Composable
fun ThinkingTraceCard(
    thinking: String,
    actionToolName: String? = null
) {
    var isExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = JarvisSurfaceVariant,
        border = ChatBorder,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { isExpanded = !isExpanded }
            .testTag("chat_thinking_trace_card")
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = null,
                        tint = JarvisAmber,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (!actionToolName.isNullOrBlank()) "Penalaran · $actionToolName" else "Proses Berpikir",
                        color = JarvisTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Tutup" else "Lihat detail",
                    tint = JarvisTextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }

            if (isExpanded) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = JarvisBackground,
                        border = ChatBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = thinking,
                            color = JarvisTextSecondary,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = "Salin penalaran",
                            color = JarvisCyan,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Proses Berpikir", thinking))
                                    Toast.makeText(context, "Proses berpikir disalin", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}
