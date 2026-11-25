package com.example.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
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
import com.example.ui.theme.GlassChromeTint
import com.example.ui.theme.GlassCodeCanvas
import com.example.ui.theme.GlassDivider
import com.example.ui.theme.GlassElevatedTint
import com.example.ui.theme.GlassRadius
import com.example.ui.theme.GlassSurfaceTint
import com.example.ui.theme.GlassUserBubbleTint
import com.example.ui.theme.JarvisAmber
import com.example.ui.theme.JarvisBorder
import com.example.ui.theme.JarvisCyan
import com.example.ui.theme.JarvisEmerald
import com.example.ui.theme.JarvisOnPrimary
import com.example.ui.theme.JarvisRed
import com.example.ui.theme.JarvisSurface
import com.example.ui.theme.JarvisSurfaceVariant
import com.example.ui.theme.JarvisTextPrimary
import com.example.ui.theme.JarvisTextSecondary
import com.example.ui.theme.JarvisTextTertiary
import com.example.ui.theme.ReliableImeState
import com.example.ui.theme.floatingGlassChrome
import com.example.ui.theme.pressScale
import com.example.ui.theme.rememberPressScale
import com.example.ui.theme.rememberReliableImeState
import com.example.ui.theme.specularGlass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ChatBorder = BorderStroke(1.dp, JarvisBorder)

private val ChatTimeFormatter = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
}

/**
 * Layar Percakapan AI Liquid Glass berkinerja 60fps:
 * - Header terpadu 1 baris + Liquid Glass ModalBottomSheet untuk kontrol sekunder (zero clutter).
 * - Floating Pill ChatComposerFooter dengan IME & Floating Nav Bar clearance mulus.
 * - Isolasi state ketat: mengetik tidak me-recompose LazyColumn; Markdown di-memoize per segmen.
 * - Mode Preview HTML/SVG membuka HtmlPreviewOverlay di jendela Activity utama (hardware accelerated).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun JarvisChatScreen(
    viewModel: JarvisViewModel,
    imeState: ReliableImeState = rememberReliableImeState(),
    onOpenExternalPreview: ((CodePreviewPayload) -> Unit)? = null
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var showControlSheet by remember { mutableStateOf(false) }
    var showConfigDialog by remember { mutableStateOf(false) }
    var openConfigFreeTab by remember { mutableStateOf(false) }
    var showPermsDialog by remember { mutableStateOf(false) }
    var showCustomPermsDialog by remember { mutableStateOf(false) }
    var showVoiceCallDialog by remember { mutableStateOf(false) }
    var showSessionDialog by remember { mutableStateOf(false) }
    var showThinkingHistoryDialog by remember { mutableStateOf(false) }
    var showTerminalDialog by remember { mutableStateOf(false) }
    var showVmViewerDialog by remember { mutableStateOf(false) }
    var localCodeToPreview by remember { mutableStateOf<CodePreviewPayload?>(null) }

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
    val onOpenControls = remember { { showControlSheet = true } }

    val onPreviewCode = remember(onOpenExternalPreview, focusManager) {
        { payload: CodePreviewPayload ->
            focusManager.clearFocus()
            if (onOpenExternalPreview != null) {
                onOpenExternalPreview(payload)
            } else {
                localCodeToPreview = payload
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ChatCompactHeader(
                viewModel = viewModel,
                onOpenControls = onOpenControls,
                onOpenVmViewer = onOpenVmViewer,
                onOpenConfig = onOpenConfig
            )

            ChatAgentPlanSection()

            ChatMessagesList(
                viewModel = viewModel,
                imeState = imeState,
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
                imeState = imeState,
                checkAndRunAudioAction = checkAndRunAudioAction
            )
        }

        // Fallback overlay lokal jika JarvisChatScreen dipanggil langsung tanpa host MainActivity
        localCodeToPreview?.let { payload ->
            HtmlPreviewOverlay(
                htmlCode = payload.code,
                fileName = payload.fileName,
                language = payload.language,
                onFileNameChange = payload.onFileNameChange,
                onDismiss = { localCodeToPreview = null }
            )
        }
    }

    if (showControlSheet) {
        ChatControlSheet(
            viewModel = viewModel,
            checkAndRunAudioAction = checkAndRunAudioAction,
            onDismiss = { showControlSheet = false },
            onOpenConfig = { freeTab ->
                showControlSheet = false
                onOpenConfig(freeTab)
            },
            onOpenSessions = {
                showControlSheet = false
                onOpenSessions()
            },
            onOpenPerms = {
                showControlSheet = false
                onOpenPerms()
            },
            onOpenVmViewer = {
                showControlSheet = false
                onOpenVmViewer()
            },
            onOpenTerminal = {
                showControlSheet = false
                onOpenTerminal()
            },
            onOpenThinkingHistory = {
                showControlSheet = false
                onOpenThinkingHistory()
            },
            onStartVoiceCall = {
                showControlSheet = false
                onStartVoiceCall()
            }
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

/**
 * Satu strip kontrol Liquid Glass ringkas (menggantikan 2 baris header bertumpuk).
 * Menampilkan Model, Sesi, Kuota, serta akses langsung VM dan Bottom Sheet Kontrol.
 */
@Composable
private fun ChatCompactHeader(
    viewModel: JarvisViewModel,
    onOpenControls: () -> Unit,
    onOpenVmViewer: () -> Unit,
    onOpenConfig: (Boolean) -> Unit
) {
    val isThinking by viewModel.isChatAiThinking.collectAsStateWithLifecycle()
    val baseUrl by viewModel.chatBaseUrl.collectAsStateWithLifecycle()
    val modelName by viewModel.chatModelName.collectAsStateWithLifecycle()
    val currentSession by viewModel.currentSession.collectAsStateWithLifecycle()
    val permissionMode by ToolManager.permissionMode.collectAsStateWithLifecycle()
    val vmStatus by com.example.service.VirtualComputerManager.status.collectAsStateWithLifecycle()
    val vmPendingApproval by com.example.service.VirtualComputerManager.pendingApproval.collectAsStateWithLifecycle()

    val termCountFlow = remember {
        com.example.service.AgentTerminalManager.entries
            .map { it.size }
            .distinctUntilChanged()
    }
    val termCount by termCountFlow.collectAsStateWithLifecycle(initialValue = 0)

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

    val permLabel = when (permissionMode) {
        AiPermissionMode.SANDBOXED -> "Sandbox"
        AiPermissionMode.LOW_RISK -> "Terbatas"
        AiPermissionMode.FULL_ACCESS -> "Penuh"
        AiPermissionMode.CUSTOM -> "Kustom"
    }

    val pillInteraction = remember { MutableInteractionSource() }
    val pillScale by rememberPressScale(pillInteraction, pressedScale = 0.985f)
    val vmInteraction = remember { MutableInteractionSource() }
    val vmScale by rememberPressScale(vmInteraction)
    val tuneInteraction = remember { MutableInteractionSource() }
    val tuneScale by rememberPressScale(tuneInteraction)

    val vmRunning = vmStatus.state == VmState.RUNNING
    val vmPending = vmPendingApproval != null || vmStatus.state == VmState.PROVISIONING

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Kiri: Kapsul Info Model + Sesi + Kuota (ketuk untuk membuka Control Sheet)
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .pressScale(pillScale)
                .clip(RoundedCornerShape(GlassRadius.Card))
                .specularGlass(
                    cornerRadius = GlassRadius.Card,
                    fillColor = GlassSurfaceTint,
                    active = isThinking
                )
                .clickable(
                    interactionSource = pillInteraction,
                    indication = null,
                    onClick = onOpenControls
                )
                .padding(horizontal = 13.dp, vertical = 7.dp)
                .testTag("chat_open_sessions_pill"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isThinking) JarvisAmber else JarvisEmerald)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (modelName.isNotBlank()) modelName else "Pilih model AI",
                    color = JarvisTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = currentSession?.title ?: "Sesi Utama",
                        color = JarvisTextSecondary,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(text = "·", color = JarvisTextTertiary, fontSize = 10.sp)
                    Text(
                        text = permLabel,
                        color = JarvisTextSecondary,
                        fontSize = 10.5.sp,
                        maxLines = 1
                    )
                    chatFreeQuota?.let { fq ->
                        val badgeColor = when {
                            fq.isBlocked -> JarvisRed
                            fq.periodRemainingFraction <= 0.2f -> JarvisAmber
                            else -> JarvisEmerald
                        }
                        Text(text = "·", color = JarvisTextTertiary, fontSize = 10.sp)
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
                    }
                }
            }
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = "Buka kontrol percakapan",
                tint = JarvisTextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }

        // Kanan 1: Pintasan Komputer Virtual (VM) — target sentuh >= 48dp
        Box(
            modifier = Modifier
                .size(48.dp)
                .pressScale(vmScale)
                .clip(RoundedCornerShape(GlassRadius.Control))
                .specularGlass(
                    cornerRadius = GlassRadius.Control,
                    fillColor = when {
                        vmRunning -> JarvisEmerald.copy(alpha = 0.16f)
                        vmPending -> JarvisAmber.copy(alpha = 0.16f)
                        else -> GlassSurfaceTint
                    },
                    active = vmRunning || vmPending
                )
                .clickable(
                    interactionSource = vmInteraction,
                    indication = null,
                    onClick = onOpenVmViewer
                )
                .testTag("chat_vm_viewer_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Computer,
                contentDescription = "Komputer Virtual",
                tint = when {
                    vmRunning -> JarvisEmerald
                    vmPending -> JarvisAmber
                    else -> JarvisTextSecondary
                },
                modifier = Modifier.size(20.dp)
            )
        }

        // Kanan 2: Kontrol & Sesi Sheet — target sentuh >= 48dp
        Box(
            modifier = Modifier
                .size(48.dp)
                .pressScale(tuneScale)
                .clip(RoundedCornerShape(GlassRadius.Control))
                .specularGlass(
                    cornerRadius = GlassRadius.Control,
                    fillColor = if (termCount > 0) JarvisCyan.copy(alpha = 0.14f) else GlassSurfaceTint,
                    active = termCount > 0
                )
                .clickable(
                    interactionSource = tuneInteraction,
                    indication = null,
                    onClick = onOpenControls
                )
                .testTag("chat_controls_sheet_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = "Kontrol dan alat percakapan",
                tint = if (termCount > 0) JarvisCyan else JarvisTextPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Liquid Glass ModalBottomSheet yang mengkonsolidasikan semua kontrol sekunder percakapan
 * agar layar chat utama tetap bersih, lapang, dan fokus pada isi obrolan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatControlSheet(
    viewModel: JarvisViewModel,
    checkAndRunAudioAction: (() -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onOpenConfig: (Boolean) -> Unit,
    onOpenSessions: () -> Unit,
    onOpenPerms: () -> Unit,
    onOpenVmViewer: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenThinkingHistory: () -> Unit,
    onStartVoiceCall: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val modelName by viewModel.chatModelName.collectAsStateWithLifecycle()
    val currentSession by viewModel.currentSession.collectAsStateWithLifecycle()
    val permissionMode by ToolManager.permissionMode.collectAsStateWithLifecycle()
    val autoReadEnabled by JarvisVoiceManager.autoReadEnabled.collectAsStateWithLifecycle()
    val isHotwordEnabled by viewModel.isHotwordEnabled.collectAsStateWithLifecycle()
    val vmStatus by com.example.service.VirtualComputerManager.status.collectAsStateWithLifecycle()
    val termCountFlow = remember {
        com.example.service.AgentTerminalManager.entries
            .map { it.size }
            .distinctUntilChanged()
    }
    val termCount by termCountFlow.collectAsStateWithLifecycle(initialValue = 0)

    val permLabel = when (permissionMode) {
        AiPermissionMode.SANDBOXED -> "Sandbox (Aman)"
        AiPermissionMode.LOW_RISK -> "Terbatas"
        AiPermissionMode.FULL_ACCESS -> "Akses Penuh"
        AiPermissionMode.CUSTOM -> "Izin Kustom"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = GlassChromeTint,
        contentColor = JarvisTextPrimary,
        shape = RoundedCornerShape(topStart = GlassRadius.Sheet, topEnd = GlassRadius.Sheet),
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = JarvisTextTertiary.copy(alpha = 0.6f))
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Kontrol Asisten",
                color = JarvisTextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            // Grup 1: Model, Sesi & Izin
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(GlassRadius.Card))
                    .specularGlass(cornerRadius = GlassRadius.Card, fillColor = GlassSurfaceTint)
            ) {
                ControlSheetRow(
                    icon = Icons.Default.Settings,
                    iconTint = JarvisCyan,
                    title = "Model & Endpoint API",
                    subtitle = modelName.ifBlank { "Pilih penyedia atau model gratis" },
                    onClick = { onOpenConfig(false) },
                    modifier = Modifier.testTag("chat_config_button")
                )
                HorizontalDivider(color = GlassDivider, thickness = 0.8.dp)
                ControlSheetRow(
                    icon = Icons.Default.Forum,
                    iconTint = JarvisTextPrimary,
                    title = "Sesi Percakapan",
                    subtitle = currentSession?.title ?: "Kelola atau ganti sesi",
                    onClick = onOpenSessions
                )
                HorizontalDivider(color = GlassDivider, thickness = 0.8.dp)
                ControlSheetRow(
                    icon = Icons.Default.Security,
                    iconTint = JarvisEmerald,
                    title = "Mode Izin AI",
                    subtitle = permLabel,
                    onClick = onOpenPerms
                )
            }

            // Grup 2: Aksi Cepat Sesi
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ControlSheetQuickAction(
                    icon = Icons.Default.Add,
                    label = "Sesi Baru",
                    tint = JarvisCyan,
                    onClick = {
                        viewModel.createNewSession()
                        Toast.makeText(context, "Sesi baru dibuat", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_quick_new_session")
                )
                ControlSheetQuickAction(
                    icon = Icons.Default.DeleteSweep,
                    label = "Bersihkan",
                    tint = JarvisRed,
                    onClick = {
                        viewModel.clearChat()
                        onDismiss()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_clear_button")
                )
            }

            // Grup 3: Ruang Kerja & Alat Eksekusi
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(GlassRadius.Card))
                    .specularGlass(cornerRadius = GlassRadius.Card, fillColor = GlassSurfaceTint)
            ) {
                ControlSheetRow(
                    icon = Icons.Default.Computer,
                    iconTint = if (vmStatus.state == VmState.RUNNING) JarvisEmerald else JarvisTextSecondary,
                    title = "Komputer Virtual (Linux VM)",
                    subtitle = if (vmStatus.state == VmState.RUNNING) "Aktif · Ketuk untuk membuka layar" else "Lihat status atau jalankan VM",
                    onClick = onOpenVmViewer
                )
                HorizontalDivider(color = GlassDivider, thickness = 0.8.dp)
                ControlSheetRow(
                    icon = Icons.Default.Terminal,
                    iconTint = if (termCount > 0) JarvisCyan else JarvisTextSecondary,
                    title = "Terminal AI",
                    subtitle = if (termCount > 0) "$termCount entri eksekusi tercatat" else "Konsol perintah bersama AI",
                    onClick = onOpenTerminal,
                    modifier = Modifier.testTag("chat_terminal_button")
                )
                HorizontalDivider(color = GlassDivider, thickness = 0.8.dp)
                ControlSheetRow(
                    icon = Icons.Default.Psychology,
                    iconTint = JarvisAmber,
                    title = "Riwayat Penalaran",
                    subtitle = "Inspeksi alur berpikir & pemanggilan tool",
                    onClick = onOpenThinkingHistory,
                    modifier = Modifier.testTag("chat_thinking_history_button")
                )
                HorizontalDivider(color = GlassDivider, thickness = 0.8.dp)
                ControlSheetRow(
                    icon = Icons.Default.GraphicEq,
                    iconTint = JarvisCyan,
                    title = "Mode Percakapan Suara",
                    subtitle = "Obrolan suara dua arah secara langsung",
                    onClick = onStartVoiceCall,
                    modifier = Modifier.testTag("chat_voice_call_button")
                )
            }

            // Grup 4: Preferensi Suara
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(GlassRadius.Card))
                    .specularGlass(cornerRadius = GlassRadius.Card, fillColor = GlassSurfaceTint)
            ) {
                ControlSheetToggleRow(
                    icon = if (autoReadEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    iconTint = if (autoReadEnabled) JarvisCyan else JarvisTextSecondary,
                    title = "Baca Balasan Otomatis",
                    subtitle = "Bacakan jawaban AI lewat suara (TTS)",
                    checked = autoReadEnabled,
                    onCheckedChange = {
                        viewModel.toggleAutoRead()
                        Toast.makeText(
                            context,
                            if (!autoReadEnabled) "Baca otomatis aktif" else "Baca otomatis nonaktif",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.testTag("chat_auto_read_toggle")
                )
                HorizontalDivider(color = GlassDivider, thickness = 0.8.dp)
                ControlSheetToggleRow(
                    icon = Icons.Default.RecordVoiceOver,
                    iconTint = if (isHotwordEnabled) JarvisEmerald else JarvisTextSecondary,
                    title = "Asisten Suara Latar Belakang",
                    subtitle = "Dengarkan kata pemicu di latar belakang",
                    checked = isHotwordEnabled,
                    onCheckedChange = {
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
                    modifier = Modifier.testTag("chat_background_hotword_toggle")
                )
            }
        }
    }
}

@Composable
private fun ControlSheetRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconTint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = JarvisTextPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = JarvisTextSecondary,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = JarvisTextTertiary,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ControlSheetToggleRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconTint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = JarvisTextPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                color = JarvisTextSecondary,
                fontSize = 11.5.sp
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = JarvisOnPrimary,
                checkedTrackColor = JarvisCyan,
                uncheckedThumbColor = JarvisTextSecondary,
                uncheckedTrackColor = JarvisSurfaceVariant
            )
        )
    }
}

@Composable
private fun ControlSheetQuickAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val scale by rememberPressScale(interactionSource)

    Row(
        modifier = modifier
            .height(48.dp)
            .pressScale(scale)
            .clip(RoundedCornerShape(GlassRadius.Control))
            .specularGlass(
                cornerRadius = GlassRadius.Control,
                fillColor = tint.copy(alpha = 0.12f),
                active = true
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
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
    imeState: ReliableImeState,
    onPreviewCode: (CodePreviewPayload) -> Unit,
    modifier: Modifier = Modifier
) {
    val messages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Gulir otomatis ke pesan terbaru saat pesan baru masuk atau keyboard naik
    LaunchedEffect(messages.size, imeState.isVisible, imeState.bottomInsetPx > 0) {
        if (messages.isNotEmpty()) {
            val lastIndex = messages.size - 1
            if (imeState.isVisible) {
                listState.animateScrollToItem(lastIndex)
            } else {
                listState.scrollToItem(lastIndex)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 14.dp)
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
            onStop = { com.example.service.VirtualComputerManager.stopVmAsync() }
        )
    }
}

/**
 * Status bar Liquid Glass ringkas saat AI memproses.
 */
@Composable
private fun ChatLiveProgressBanner(viewModel: JarvisViewModel) {
    val isThinking by viewModel.isChatAiThinking.collectAsStateWithLifecycle()
    if (!isThinking) return

    val statusText by viewModel.chatStatusText.collectAsStateWithLifecycle()
    val liveThought by viewModel.liveThought.collectAsStateWithLifecycle()
    var showThought by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(GlassRadius.Control))
            .specularGlass(
                cornerRadius = GlassRadius.Control,
                fillColor = GlassElevatedTint,
                active = true
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
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
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!liveThought.isNullOrBlank()) {
                Text(
                    text = if (showThought) "Tutup" else "Penalaran",
                    color = JarvisCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showThought = !showThought }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = JarvisRed.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, JarvisRed.copy(alpha = 0.5f)),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { viewModel.stopAiExecution() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Hentikan AI", tint = JarvisRed, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("Stop", color = JarvisRed, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (showThought && !liveThought.isNullOrBlank()) {
            HorizontalDivider(color = GlassDivider, thickness = 0.75.dp)
            Text(
                text = liveThought.orEmpty(),
                color = JarvisTextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 15.5.sp,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GlassCodeCanvas)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

/**
 * Floating Pill Composer (Tier-1 Liquid Glass Chrome):
 * - Isolasi state ketat: mengetik tidak pernah me-recompose LazyColumn.
 * - Otomatis naik presisi di atas keyboard via navigationBarsPadding().imePadding().
 * - Saat keyboard tersembunyi, melayang rapi di atas Floating Pill Bottom Nav Bar.
 */
@Composable
private fun ChatComposerFooter(
    viewModel: JarvisViewModel,
    imeState: ReliableImeState,
    checkAndRunAudioAction: (() -> Unit) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
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

    val navBarBottomDp = with(density) {
        WindowInsets.navigationBars.getBottom(this).toDp()
    }
    val imeBottomDp = with(density) {
        imeState.bottomInsetPx.toDp()
    }
    val composerBottomPadding = if (imeState.isVisible) {
        if (imeState.bottomInsetPx > 0) {
            maxOf(imeBottomDp, navBarBottomDp) + 8.dp
        } else {
            8.dp
        }
    } else {
        navBarBottomDp + 84.dp
    }

    val attachInteraction = remember { MutableInteractionSource() }
    val attachScale by rememberPressScale(attachInteraction)
    val micInteraction = remember { MutableInteractionSource() }
    val micScale by rememberPressScale(micInteraction)
    val sendInteraction = remember { MutableInteractionSource() }
    val sendScale by rememberPressScale(sendInteraction)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = composerBottomPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (selectedAttachmentUri != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(GlassRadius.Control))
                    .specularGlass(
                        cornerRadius = GlassRadius.Control,
                        fillColor = GlassElevatedTint,
                        active = true
                    )
                    .padding(horizontal = 12.dp, vertical = 7.dp),
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
                    modifier = Modifier.size(32.dp)
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

        // Tier-1 Floating Pill Composer Chrome
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(GlassRadius.Pill))
                .floatingGlassChrome(cornerRadius = GlassRadius.Pill)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tombol Lampiran (>= 48dp touch area)
            IconButton(
                onClick = { filePickerLauncher.launch("*/*") },
                interactionSource = attachInteraction,
                modifier = Modifier
                    .size(44.dp)
                    .pressScale(attachScale)
                    .testTag("chat_attach_file_button")
            ) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = "Lampirkan file atau foto",
                    tint = JarvisTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Input teks transparan menyatu di dalam kapsul kaca
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = {
                    Text(
                        text = if (isThinking) "Memproses — ketuk stop untuk batal" else "Tulis pesan atau instruksi...",
                        color = JarvisTextTertiary,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("chat_input_textfield"),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedTextColor = JarvisTextPrimary,
                    unfocusedTextColor = JarvisTextPrimary,
                    cursorColor = JarvisCyan
                ),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { dispatchCurrentMessage() })
            )

            // Tombol Mikrofon
            val micBg by animateColorAsState(
                targetValue = if (isVoiceListening) JarvisRed else Color.Transparent,
                label = "mic_bg"
            )
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .pressScale(micScale)
                    .clip(CircleShape)
                    .background(micBg)
                    .clickable(
                        interactionSource = micInteraction,
                        indication = null
                    ) {
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
                    }
                    .testTag("chat_mic_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isVoiceListening) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = "Input suara",
                    tint = if (isVoiceListening) Color.White else JarvisTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Tombol Kirim / Hentikan AI
            val canSend = inputText.isNotBlank() || selectedAttachmentUri != null
            if (isThinking) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .pressScale(sendScale)
                        .clip(CircleShape)
                        .background(JarvisRed)
                        .clickable(
                            interactionSource = sendInteraction,
                            indication = null
                        ) { viewModel.stopAiExecution() }
                        .testTag("chat_stop_ai_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Hentikan eksekusi AI",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .pressScale(sendScale)
                        .clip(CircleShape)
                        .background(if (canSend) JarvisCyan else GlassElevatedTint)
                        .clickable(
                            interactionSource = sendInteraction,
                            indication = null,
                            enabled = canSend
                        ) { dispatchCurrentMessage() }
                        .testTag("chat_send_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Kirim pesan",
                        tint = if (canSend) JarvisOnPrimary else JarvisTextTertiary,
                        modifier = Modifier.size(18.dp)
                    )
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
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Izinkan Hapus", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { ToolManager.denyPendingDeletion() },
                    shape = RoundedCornerShape(10.dp),
                    border = ChatBorder
                ) {
                    Text("Tolak", color = JarvisTextPrimary, fontSize = 12.sp)
                }
            },
            containerColor = JarvisSurface,
            shape = RoundedCornerShape(GlassRadius.Card)
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
            containerColor = JarvisSurface,
            shape = RoundedCornerShape(GlassRadius.Card)
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
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(GlassRadius.Control))
                    .specularGlass(cornerRadius = GlassRadius.Control, fillColor = GlassElevatedTint)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = message.text,
                    color = JarvisTextSecondary,
                    fontSize = 11.5.sp
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
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .specularGlass(
                        cornerRadius = 10.dp,
                        fillColor = GlassElevatedTint,
                        active = true
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "A",
                    color = JarvisCyan,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            modifier = if (!isUser && hasCodeBlocks) {
                Modifier.weight(1f)
            } else {
                Modifier.widthIn(max = 324.dp)
            },
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            // Tier-2 Specular Liquid Glass Bubble (0ms blur cost, 60fps scrolling)
            Column(
                modifier = (if (!isUser && hasCodeBlocks) Modifier.fillMaxWidth() else Modifier)
                    .clip(RoundedCornerShape(GlassRadius.Card))
                    .specularGlass(
                        cornerRadius = GlassRadius.Card,
                        fillColor = if (isUser) GlassUserBubbleTint else GlassSurfaceTint,
                        active = isUser
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
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
                    Box(
                        modifier = Modifier
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .specularGlass(cornerRadius = 10.dp, fillColor = GlassElevatedTint)
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    segments.forEach { segment ->
                        when (segment) {
                            is ChatContentSegment.TextSegment -> {
                                val formattedText = remember(segment.text) {
                                    ChatMarkdownFormatter.format(segment.text)
                                }
                                if (formattedText.isNotBlank()) {
                                    Text(
                                        text = formattedText,
                                        color = JarvisTextPrimary,
                                        fontSize = 14.sp,
                                        lineHeight = 20.5.sp
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
                        color = JarvisEmerald.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(0.85.dp, JarvisEmerald.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
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
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(3.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 6.dp)
            ) {
                Text(
                    text = timeStr,
                    color = JarvisTextTertiary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Pesan Asisten", message.text))
                            Toast.makeText(context, "Pesan disalin", Toast.LENGTH_SHORT).show()
                        }
                        .padding(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Salin pesan",
                        tint = JarvisTextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                }
                if (!isUser) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { JarvisVoiceManager.speak(message.text) }
                            .padding(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Dengarkan jawaban",
                            tint = JarvisTextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .specularGlass(
                        cornerRadius = 14.dp,
                        fillColor = GlassUserBubbleTint,
                        active = true
                    ),
                contentAlignment = Alignment.Center
            ) {
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

/**
 * Kartu penalaran ringkas & dapat dilipat di dalam gelembung AI (Liquid Glass Tier-2).
 */
@Composable
fun ThinkingTraceCard(
    thinking: String,
    actionToolName: String? = null
) {
    var isExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(GlassRadius.Control))
            .specularGlass(
                cornerRadius = GlassRadius.Control,
                fillColor = GlassElevatedTint
            )
            .clickable { isExpanded = !isExpanded }
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("chat_thinking_trace_card")
    ) {
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
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (!actionToolName.isNullOrBlank()) "Penalaran · $actionToolName" else "Proses Berpikir",
                    color = JarvisTextPrimary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Tutup" else "Lihat detail",
                tint = JarvisTextSecondary,
                modifier = Modifier.size(17.dp)
            )
        }

        if (isExpanded) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(GlassCodeCanvas)
                        .padding(9.dp)
                ) {
                    Text(
                        text = thinking,
                        color = JarvisTextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.5.sp
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
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Proses Berpikir", thinking))
                                Toast.makeText(context, "Proses berpikir disalin", Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
