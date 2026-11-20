package com.example.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.service.GitHubToolManager
import com.example.service.InstalledGitHubTool
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dialog instalasi tool/skill langsung dari GitHub URL, owner/repo, npm, atau pip.
 * Mengikuti desain Graphite Precision: permukaan datar matte, border 1.dp hairline,
 * kontrol tersegmentasi bersih, dan tombol minimal 44.dp.
 */
@Composable
fun GitHubToolInstallDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sourceInput by remember { mutableStateOf("") }
    var aliasInput by remember { mutableStateOf("") }
    var runtimeChoice by remember { mutableStateOf("auto") } // auto | vm | termux
    var runSetupNow by remember { mutableStateOf(true) }
    var isInstalling by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var isResultError by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { if (!isInstalling) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("github_tool_install_dialog"),
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = null,
                            tint = JarvisCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Instal Tool dari GitHub",
                            color = JarvisTextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    IconButton(
                        onClick = { if (!isInstalling) onDismiss() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Tutup",
                            tint = JarvisTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Text(
                    text = "Tempel link GitHub (mis. remotion-dev/remotion, remotion-dev/skills), paket npm:<nama>, atau pip:<nama>. Tool & panduannya akan disimpan permanen agar AI dapat memakainya berkali-kali.",
                    color = JarvisTextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )

                OutlinedTextField(
                    value = sourceInput,
                    onValueChange = { sourceInput = it },
                    enabled = !isInstalling,
                    label = { Text("Link GitHub / owner/repo / paket", fontSize = 12.sp) },
                    placeholder = {
                        Text(
                            "https://github.com/remotion-dev/remotion",
                            color = JarvisTextTertiary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
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
                    value = aliasInput,
                    onValueChange = { aliasInput = it },
                    enabled = !isInstalling,
                    label = { Text("Nama singkat / alias (opsional)", fontSize = 12.sp) },
                    placeholder = { Text("mis. remotion", color = JarvisTextTertiary, fontSize = 12.sp) },
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
                    Text(
                        text = "Target Runtime",
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
                        listOf(
                            "auto" to "Otomatis",
                            "vm" to "VM Linux",
                            "termux" to "Termux"
                        ).forEach { (key, label) ->
                            val selected = runtimeChoice == key
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 38.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .clickable(enabled = !isInstalling) { runtimeChoice = key },
                                shape = RoundedCornerShape(7.dp),
                                color = if (selected) JarvisSurfaceVariant else Color.Transparent,
                                border = if (selected) BorderStroke(1.dp, JarvisBorderStrong) else null
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = label,
                                        color = if (selected) JarvisTextPrimary else JarvisTextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Jalankan setup dependensi sekarang",
                            color = JarvisTextPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Clone repo & instal paket npm/pip secara otomatis",
                            color = JarvisTextTertiary,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = runSetupNow,
                        enabled = !isInstalling,
                        onCheckedChange = { runSetupNow = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = JarvisOnPrimary,
                            checkedTrackColor = JarvisCyan,
                            uncheckedThumbColor = JarvisTextSecondary,
                            uncheckedTrackColor = JarvisSurfaceVariant,
                            uncheckedBorderColor = JarvisBorderStrong
                        )
                    )
                }

                if (!resultMessage.isNullOrBlank()) {
                    HorizontalDivider(color = JarvisBorder, thickness = 1.dp)
                    Text(
                        text = resultMessage ?: "",
                        color = if (isResultError) JarvisRed else JarvisEmerald,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !isInstalling,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, JarvisBorderStrong),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisTextSecondary)
                    ) {
                        Text("Tutup", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }

                    Button(
                        onClick = {
                            if (sourceInput.isBlank() || isInstalling) return@Button
                            isInstalling = true
                            resultMessage = null
                            isResultError = false
                            scope.launch(Dispatchers.IO) {
                                val res = GitHubToolManager.installFromSource(
                                    source = sourceInput,
                                    preferredRuntime = runtimeChoice,
                                    customAlias = aliasInput,
                                    runSetupNow = runSetupNow
                                )
                                withContext(Dispatchers.Main) {
                                    isInstalling = false
                                    isResultError = res.status != "ok"
                                    resultMessage = res.result ?: res.message ?: "Selesai."
                                    if (res.status == "ok") {
                                        Toast.makeText(context, "Tool GitHub berhasil disimpan", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        enabled = sourceInput.isNotBlank() && !isInstalling,
                        modifier = Modifier
                            .weight(1.4f)
                            .heightIn(min = 44.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = JarvisCyan,
                            contentColor = JarvisOnPrimary
                        ),
                        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp)
                    ) {
                        if (isInstalling) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = JarvisOnPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Memasang...", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        } else {
                            Text("Instal & Simpan", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Kartu bersih untuk menampilkan satu tool/skill GitHub yang sudah tersimpan permanen.
 */
@Composable
fun InstalledGitHubToolCard(
    tool: InstalledGitHubTool,
    onSetup: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = JarvisSurface,
        border = BorderStroke(1.dp, JarvisBorder)
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tool.name,
                        color = JarvisTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${tool.ecosystem} · ${tool.runtimeTarget.uppercase()} · ${if (tool.setupCompleted) "Siap dipakai" else "Perlu setup"} · ${tool.useCount}x dipakai",
                        color = if (tool.setupCompleted) JarvisEmerald else JarvisAmber,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Hapus tool GitHub",
                        tint = JarvisTextTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (tool.description.isNotBlank()) {
                Text(
                    text = tool.description,
                    color = JarvisTextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = tool.runTemplate,
                color = JarvisTextTertiary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (!tool.setupCompleted && tool.runtimeTarget != "knowledge") {
                HorizontalDivider(color = JarvisBorder, thickness = 1.dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onSetup,
                        modifier = Modifier.heightIn(min = 40.dp),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, JarvisBorderStrong),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisCyan)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Jalankan Setup", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
