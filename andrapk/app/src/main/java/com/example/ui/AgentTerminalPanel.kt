package com.example.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.service.AgentPlanStep
import com.example.service.AgentTerminalManager
import com.example.service.AgentWorkspaceTools
import com.example.service.PlanStepStatus
import com.example.service.ScreenshotManager
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Konsol Terminal AI Agent Persisten (dipakai di tab Termux maupun Modal Terminal di Chat).
 * Menampilkan output realtime dari perintah yang dijalankan AI maupun user, daftar job background,
 * direktori kerja (cwd), serta input perintah interaktif.
 */
@Composable
fun AgentTerminalConsoleCard(
    modifier: Modifier = Modifier,
    maxConsoleHeightDp: Int = 260
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by AgentTerminalManager.entries.collectAsState()
    val activeSession by AgentTerminalManager.activeSession.collectAsState()
    val cwds by AgentTerminalManager.currentCwds.collectAsState()
    val jobs by AgentTerminalManager.jobs.collectAsState()

    val currentCwd = remember(activeSession, cwds) {
        cwds[activeSession] ?: AgentTerminalManager.getCwd(activeSession)
    }

    var cmdInput by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    var useAdbMode by remember { mutableStateOf(false) }
    val consoleScroll = rememberScrollState()

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            consoleScroll.animateScrollTo(consoleScroll.maxValue)
        }
    }

    fun runCommand(raw: String) {
        val clean = raw.trim()
        if (clean.isEmpty() || isRunning) return
        cmdInput = ""
        isRunning = true
        scope.launch(Dispatchers.IO) {
            AgentTerminalManager.exec(
                command = clean,
                sessionId = activeSession,
                timeoutSec = 30,
                source = "USER",
                asAdb = useAdbMode
            )
            withContext(Dispatchers.Main) {
                isRunning = false
            }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = JarvisSurface),
        border = BorderStroke(1.dp, JarvisBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header
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
                        Icons.Default.Terminal,
                        contentDescription = null,
                        tint = JarvisCyan,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Terminal AI Agent (Live)",
                            color = JarvisTextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Text(
                            text = "Sesi: $activeSession • ${currentCwd.takeLast(38)}",
                            color = JarvisTextSecondary,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Toggle ADB Mode
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (useAdbMode) JarvisAmber.copy(alpha = 0.18f) else JarvisSurfaceVariant,
                        border = BorderStroke(1.dp, if (useAdbMode) JarvisAmber else JarvisBorder),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { useAdbMode = !useAdbMode }
                    ) {
                        Text(
                            text = if (useAdbMode) "ADB" else "SH",
                            color = if (useAdbMode) JarvisAmber else JarvisTextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            AgentTerminalManager.resetSession(activeSession)
                            Toast.makeText(context, "Sesi terminal direset", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.RestartAlt,
                            contentDescription = "Reset CWD",
                            tint = JarvisTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(
                        onClick = { AgentTerminalManager.clearTranscript() },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = "Bersihkan log",
                            tint = JarvisTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Active background jobs strip
            val activeJobs = remember(jobs) { jobs.filter { it.isAlive } }
            if (activeJobs.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = JarvisSurfaceVariant,
                    border = BorderStroke(1.dp, JarvisEmerald.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Proses Latar Belakang Aktif (${activeJobs.size})",
                            color = JarvisEmerald,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        activeJobs.forEach { job ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "[${job.jobId}] ${job.name} (${job.lineCount} baris)",
                                    color = JarvisTextPrimary,
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "STOP",
                                    color = JarvisRed,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable {
                                            AgentTerminalManager.stopBackground(job.jobId)
                                            Toast.makeText(context, "Job ${job.jobId} dihentikan", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Console Viewport
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = maxConsoleHeightDp.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(JarvisBackground)
                    .border(1.dp, JarvisBorder, RoundedCornerShape(10.dp))
                    .verticalScroll(consoleScroll)
                    .padding(10.dp)
            ) {
                if (entries.isEmpty()) {
                    Text(
                        text = "Terminal AI siap digunakan.\nWorkspace: $currentCwd\n\nSemua perintah 'terminal_exec' yang dijalankan AI maupun input manual kamu tampil realtime di sini.",
                        color = JarvisTextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    )
                } else {
                    val visibleEntries = remember(entries) { entries.takeLast(60) }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        visibleEntries.forEach { entry ->
                            val sourceColor = when (entry.source) {
                                "AI" -> JarvisCyan
                                "USER" -> JarvisEmerald
                                else -> JarvisAmber
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = entry.source,
                                        color = sourceColor,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = "$ ${entry.command}",
                                        color = JarvisTextPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Text(
                                        text = if (entry.exitCode == 0) "OK ${entry.durationMs}ms" else "ERR(${entry.exitCode})",
                                        color = if (entry.exitCode == 0) JarvisEmerald else JarvisRed,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                if (entry.output.isNotBlank()) {
                                    Text(
                                        text = entry.output.take(1500),
                                        color = JarvisTextSecondary,
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 14.5.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Quick Command Chips + Quick Agent Screenshot test
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val quickCmds = remember { listOf("pwd", "ls -la", "df -h", "uname -a", "python3 --version") }
                quickCmds.forEach { qc ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = JarvisSurfaceVariant,
                        border = BorderStroke(1.dp, JarvisBorder),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = !isRunning) { runCommand(qc) }
                    ) {
                        Text(
                            text = qc,
                            color = JarvisTextSecondary,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = JarvisCyan.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !isRunning) {
                            scope.launch(Dispatchers.IO) {
                                val (shot, err) = ScreenshotManager.captureForAgent(
                                    context = context,
                                    hideOverlay = true,
                                    annotateGrid = true,
                                    saveToFile = true
                                )
                                withContext(Dispatchers.Main) {
                                    val msg = if (shot != null) {
                                        "Screenshot grid disimpan: ${shot.savedPath?.substringAfterLast('/') ?: "OK"}"
                                    } else {
                                        "Gagal: ${err ?: "error"}"
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                ) {
                    Text(
                        text = "Tes Screenshot Grid",
                        color = JarvisCyan,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Interactive Input Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = cmdInput,
                    onValueChange = { cmdInput = it },
                    placeholder = {
                        Text(
                            text = if (useAdbMode) "Perintah ADB shell..." else "Ketik perintah bash / python...",
                            color = JarvisTextSecondary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = JarvisTextPrimary
                    ),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = JarvisCyan,
                        unfocusedBorderColor = JarvisBorder,
                        focusedContainerColor = JarvisSurfaceVariant,
                        unfocusedContainerColor = JarvisSurfaceVariant
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { runCommand(cmdInput) })
                )

                IconButton(
                    onClick = { runCommand(cmdInput) },
                    enabled = cmdInput.isNotBlank() && !isRunning,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (cmdInput.isNotBlank() && !isRunning) JarvisCyan else JarvisSurfaceVariant)
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = JarvisCyan
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Jalankan",
                            tint = if (cmdInput.isNotBlank()) JarvisBackground else JarvisTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dialog Terminal AI Live yang dapat dibuka langsung dari layar Chat.
 */
@Composable
fun AgentTerminalModalDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            color = JarvisBackground,
            border = BorderStroke(1.dp, JarvisBorder)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Konsol Terminal AI",
                        color = JarvisTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Tutup",
                            tint = JarvisTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                AgentTerminalConsoleCard(maxConsoleHeightDp = 340)
            }
        }
    }
}

/**
 * Banner checklist rencana tugas realtime (diisi AI lewat tool 'agent_plan').
 */
@Composable
fun AgentPlanBanner(
    steps: List<AgentPlanStep>,
    onClear: () -> Unit
) {
    if (steps.isEmpty()) return
    val doneCount = steps.count { it.status == PlanStepStatus.DONE }
    Surface(
        color = JarvisSurface,
        border = BorderStroke(1.dp, JarvisBorder),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Checklist,
                        contentDescription = null,
                        tint = JarvisCyan,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Rencana Agent ($doneCount/${steps.size})",
                        color = JarvisTextPrimary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Tutup rencana",
                    tint = JarvisTextSecondary,
                    modifier = Modifier
                        .size(15.dp)
                        .clickable { onClear() }
                )
            }

            steps.take(6).forEach { step ->
                val (iconColor, statusTag) = when (step.status) {
                    PlanStepStatus.DONE -> JarvisEmerald to "OK"
                    PlanStepStatus.IN_PROGRESS -> JarvisAmber to "..."
                    PlanStepStatus.FAILED -> JarvisRed to "ERR"
                    PlanStepStatus.PENDING -> JarvisTextSecondary to "o"
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(iconColor)
                    )
                    Text(
                        text = "${step.id}. ${step.title}",
                        color = if (step.status == PlanStepStatus.DONE) JarvisTextSecondary else JarvisTextPrimary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = statusTag,
                        color = iconColor,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

/**
 * Thumbnail gambar lokal (mis. screenshot hasil tangkapan AI Agent) langsung di dalam ChatBubble
 * dengan dialog full-screen saat diketuk — tanpa ketergantungan library Coil.
 */
@Composable
fun LocalImagePreviewCard(
    filePath: String,
    caption: String? = null
) {
    var showFullScreen by remember { mutableStateOf(false) }
    val thumbBitmap by produceState<Bitmap?>(initialValue = null, key1 = filePath) {
        value = withContext(Dispatchers.IO) {
            decodeSampledBitmap(filePath, maxDim = 640)
        }
    }

    val bmp = thumbBitmap ?: return
    val thumbImageBitmap = remember(bmp) { bmp.asImageBitmap() }

    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, JarvisBorder, RoundedCornerShape(10.dp))
                .clickable { showFullScreen = true }
        ) {
            Image(
                bitmap = thumbImageBitmap,
                contentDescription = caption ?: "Screenshot AI",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth()
            )
            Surface(
                color = JarvisBackground.copy(alpha = 0.78f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.ZoomIn,
                        contentDescription = null,
                        tint = JarvisCyan,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = caption ?: "Lihat penuh",
                        color = JarvisTextPrimary,
                        fontSize = 9.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    if (showFullScreen) {
        val fullBitmap by produceState<Bitmap?>(initialValue = bmp, key1 = "full_$filePath") {
            value = withContext(Dispatchers.IO) {
                decodeSampledBitmap(filePath, maxDim = 1600) ?: bmp
            }
        }
        Dialog(
            onDismissRequest = { showFullScreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f))
                    .clickable { showFullScreen = false },
                contentAlignment = Alignment.Center
            ) {
                fullBitmap?.let { fb ->
                    val fullImageBitmap = remember(fb) { fb.asImageBitmap() }
                    Image(
                        bitmap = fullImageBitmap,
                        contentDescription = caption ?: "Screenshot Penuh",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    )
                }
                IconButton(
                    onClick = { showFullScreen = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .clip(CircleShape)
                        .background(JarvisSurface.copy(alpha = 0.85f))
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Tutup",
                        tint = JarvisTextPrimary
                    )
                }
            }
        }
    }
}

private fun decodeSampledBitmap(path: String, maxDim: Int): Bitmap? {
    return try {
        val file = File(path)
        if (!file.exists() || !file.isFile) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val rawMax = maxOf(opts.outWidth, opts.outHeight)
        var sample = 1
        while (rawMax / sample > maxDim) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
    } catch (_: Exception) {
        null
    }
}
