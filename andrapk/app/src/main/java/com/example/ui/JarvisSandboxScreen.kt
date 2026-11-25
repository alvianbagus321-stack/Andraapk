package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.UiElementInfo
import com.example.ui.theme.*

private val SandboxCardBorder = BorderStroke(1.dp, JarvisBorder)
private val SandboxResultBorder = BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.45f))

@Composable
fun JarvisSandboxScreen(viewModel: JarvisViewModel) {
    val lastResult by viewModel.lastActionResult.collectAsStateWithLifecycle()
    val isExecuting by viewModel.isActionExecuting.collectAsStateWithLifecycle()

    // Hanya state target lintas-kartu (dari Inspector -> Tap/Type) yang disimpan di sini
    var selectedTargetId by remember { mutableStateOf("") }
    var selectedTapX by remember { mutableStateOf("540") }
    var selectedTapY by remember { mutableStateOf("1200") }

    val onPickElement = remember {
        { elem: UiElementInfo ->
            selectedTargetId = elem.id
            selectedTapX = elem.bounds.centerX.toString()
            selectedTapY = elem.bounds.centerY.toString()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = GlassSpacing.FloatingNavClearance)
    ) {
        if (lastResult != null) {
            item(key = "sandbox_result", contentType = "banner") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(GlassRadius.Card),
                    colors = CardDefaults.cardColors(containerColor = GlassElevatedTint),
                    border = SandboxResultBorder
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = JarvisCyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Hasil Eksekusi", color = JarvisCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(lastResult ?: "", color = JarvisTextPrimary, fontSize = 12.5.sp, lineHeight = 17.sp)
                        }
                    }
                }
            }
        }

        item(key = "sandbox_inspector", contentType = "inspector") {
            SandboxInspectorCard(
                viewModel = viewModel,
                isExecuting = isExecuting,
                selectedElementId = selectedTargetId,
                onPickElement = onPickElement
            )
        }

        item(key = "sandbox_tap", contentType = "tap") {
            SandboxTapGestureCard(
                viewModel = viewModel,
                tapX = selectedTapX,
                onTapXChange = { selectedTapX = it },
                tapY = selectedTapY,
                onTapYChange = { selectedTapY = it },
                targetElementId = selectedTargetId,
                onTargetIdChange = { selectedTargetId = it }
            )
        }

        item(key = "sandbox_type", contentType = "type") {
            SandboxTextInputCard(
                viewModel = viewModel,
                targetElementId = selectedTargetId
            )
        }

        item(key = "sandbox_keys", contentType = "keys") {
            SandboxSystemKeysCard(viewModel = viewModel)
        }

        item(key = "sandbox_app", contentType = "app") {
            SandboxAppLauncherCard(viewModel = viewModel)
        }

        item(key = "sandbox_shell", contentType = "shell") {
            SandboxShellCard(
                viewModel = viewModel,
                isExecuting = isExecuting
            )
        }
    }
}

@Composable
private fun SandboxInspectorCard(
    viewModel: JarvisViewModel,
    isExecuting: Boolean,
    selectedElementId: String,
    onPickElement: (UiElementInfo) -> Unit
) {
    val inspectedElements by viewModel.inspectedElements.collectAsStateWithLifecycle()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sandbox_inspector_card"),
        shape = RoundedCornerShape(GlassRadius.Card),
        colors = CardDefaults.cardColors(containerColor = GlassSurfaceTint),
        border = SandboxCardBorder
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Inspeksi Hirarki UI", style = MaterialTheme.typography.titleMedium, color = JarvisTextPrimary)
                    Text("Pindai node interaktif via AccessibilityService", style = MaterialTheme.typography.bodySmall, color = JarvisTextSecondary)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Button(
                    onClick = { viewModel.inspectScreen() },
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisOnPrimary),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(38.dp),
                    enabled = !isExecuting
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Pindai", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            if (inspectedElements.isNotEmpty()) {
                Text(
                    text = "Ditemukan ${inspectedElements.size} node interaktif. Ketuk item untuk menargetkan:",
                    color = JarvisTextSecondary,
                    fontSize = 11.sp
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(JarvisSurfaceVariant)
                        .padding(6.dp)
                ) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(
                            items = inspectedElements,
                            key = { "${it.id}_${it.bounds.left}_${it.bounds.top}" },
                            contentType = { "ui_node" }
                        ) { elem ->
                            ElementItemRow(
                                element = elem,
                                isSelected = selectedElementId == elem.id,
                                onSelect = { onPickElement(elem) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SandboxTapGestureCard(
    viewModel: JarvisViewModel,
    tapX: String,
    onTapXChange: (String) -> Unit,
    tapY: String,
    onTapYChange: (String) -> Unit,
    targetElementId: String,
    onTargetIdChange: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sandbox_tap_card"),
        shape = RoundedCornerShape(GlassRadius.Card),
        colors = CardDefaults.cardColors(containerColor = GlassSurfaceTint),
        border = SandboxCardBorder
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Ketuk & Gestur", style = MaterialTheme.typography.titleMedium, color = JarvisTextPrimary)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = tapX,
                    onValueChange = onTapXChange,
                    label = { Text("X", fontSize = 11.sp) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true
                )
                OutlinedTextField(
                    value = tapY,
                    onValueChange = onTapYChange,
                    label = { Text("Y", fontSize = 11.sp) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true
                )
                Button(
                    onClick = {
                        val x = tapX.toFloatOrNull() ?: 540f
                        val y = tapY.toFloatOrNull() ?: 1200f
                        viewModel.testTapCoordinates(x, y)
                    },
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisOnPrimary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Tap (X,Y)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = targetElementId,
                    onValueChange = onTargetIdChange,
                    label = { Text("Target Element ID / ViewID", fontSize = 11.sp) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true
                )
                Button(
                    onClick = {
                        if (targetElementId.isNotBlank()) {
                            viewModel.testTapElement(targetElementId)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisSurfaceElevated, contentColor = JarvisTextPrimary),
                    shape = RoundedCornerShape(8.dp),
                    enabled = targetElementId.isNotBlank()
                ) {
                    Text("Tap ID", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Swipe: (500, 1500) -> (500, 500)", color = JarvisTextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                OutlinedButton(
                    onClick = { viewModel.testSwipe(500f, 1500f, 500f, 500f, 300L) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisTextPrimary),
                    border = SandboxCardBorder,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.SwipeUp, contentDescription = null, tint = JarvisCyan, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Swipe Up", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SandboxTextInputCard(
    viewModel: JarvisViewModel,
    targetElementId: String
) {
    var textInput by remember { mutableStateOf("Minecraft") }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sandbox_type_card"),
        shape = RoundedCornerShape(GlassRadius.Card),
        colors = CardDefaults.cardColors(containerColor = GlassSurfaceTint),
        border = SandboxCardBorder
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Input Teks (ACTION_SET_TEXT)", style = MaterialTheme.typography.titleMedium, color = JarvisTextPrimary)
            Text("Mengisi bidang teks langsung melalui AccessibilityNodeInfo tanpa simulasi keyboard.", style = MaterialTheme.typography.bodySmall, color = JarvisTextSecondary)

            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                label = { Text("Teks yang akan diisi", fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                singleLine = true
            )

            Button(
                onClick = { viewModel.testTypeText(targetElementId.ifBlank { null }, textInput) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                colors = ButtonDefaults.buttonColors(containerColor = JarvisEmerald, contentColor = JarvisOnPrimary),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Keyboard, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Terapkan Teks", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
            }
        }
    }
}

@Composable
private fun SandboxSystemKeysCard(viewModel: JarvisViewModel) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sandbox_keys_card"),
        shape = RoundedCornerShape(GlassRadius.Card),
        colors = CardDefaults.cardColors(containerColor = GlassSurfaceTint),
        border = SandboxCardBorder
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Tombol Sistem & Navigasi", style = MaterialTheme.typography.titleMedium, color = JarvisTextPrimary)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.testPressKey("HOME") },
                    modifier = Modifier.weight(1f).height(40.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisTextPrimary),
                    border = SandboxCardBorder,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("HOME", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = { viewModel.testPressKey("BACK") },
                    modifier = Modifier.weight(1f).height(40.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisTextPrimary),
                    border = SandboxCardBorder,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("BACK", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = { viewModel.testPressKey("RECENTS") },
                    modifier = Modifier.weight(1f).height(40.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisTextPrimary),
                    border = SandboxCardBorder,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("RECENTS", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SandboxAppLauncherCard(viewModel: JarvisViewModel) {
    var openAppPackage by remember { mutableStateOf("com.google.android.youtube") }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sandbox_app_card"),
        shape = RoundedCornerShape(GlassRadius.Card),
        colors = CardDefaults.cardColors(containerColor = GlassSurfaceTint),
        border = SandboxCardBorder
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Peluncur Aplikasi", style = MaterialTheme.typography.titleMedium, color = JarvisTextPrimary)

            OutlinedTextField(
                value = openAppPackage,
                onValueChange = { openAppPackage = it },
                label = { Text("Package Name", fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PresetAppChip("YouTube", "com.google.android.youtube") { openAppPackage = it }
                PresetAppChip("Chrome", "com.android.chrome") { openAppPackage = it }
                PresetAppChip("Settings", "com.android.settings") { openAppPackage = it }
            }

            Button(
                onClick = { viewModel.testOpenApp(openAppPackage) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                colors = ButtonDefaults.buttonColors(containerColor = JarvisSurfaceElevated, contentColor = JarvisTextPrimary),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, tint = JarvisCyan, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Buka Aplikasi", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
            }
        }
    }
}

@Composable
private fun SandboxShellCard(
    viewModel: JarvisViewModel,
    isExecuting: Boolean
) {
    var shellCommand by remember { mutableStateOf("uname -a") }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sandbox_shell_card"),
        shape = RoundedCornerShape(GlassRadius.Card),
        colors = CardDefaults.cardColors(containerColor = GlassSurfaceTint),
        border = SandboxCardBorder
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Terminal, contentDescription = null, tint = JarvisCyan, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Eksekusi Shell / Shizuku", style = MaterialTheme.typography.titleMedium, color = JarvisTextPrimary)
            }
            Text("Jalankan perintah shell lokal atau Shizuku secara langsung untuk pengujian.", style = MaterialTheme.typography.bodySmall, color = JarvisTextSecondary)

            OutlinedTextField(
                value = shellCommand,
                onValueChange = { shellCommand = it },
                label = { Text("Perintah shell", fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PresetAppChip("Kernel", "uname -a") { shellCommand = it }
                PresetAppChip("Storage", "df -h /sdcard") { shellCommand = it }
                PresetAppChip("Paket APK", "pm list packages -3") { shellCommand = it }
            }

            Button(
                onClick = { viewModel.testExecuteShell(shellCommand) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisOnPrimary),
                shape = RoundedCornerShape(8.dp),
                enabled = !isExecuting && shellCommand.isNotBlank()
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Jalankan Perintah", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
            }
        }
    }
}

@Composable
fun ElementItemRow(
    element: UiElementInfo,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onSelect),
        color = if (isSelected) JarvisCyan.copy(alpha = 0.16f) else JarvisSurface,
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = element.id,
                    color = if (isSelected) JarvisCyan else JarvisTextPrimary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val label = element.text.ifBlank { element.contentDescription }
                if (label.isNotBlank()) {
                    Text(text = "\"$label\"", color = JarvisTextSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(
                text = "(${element.bounds.centerX}, ${element.bounds.centerY})",
                color = JarvisTextSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun PresetAppChip(label: String, packageName: String, onSelect: (String) -> Unit) {
    SuggestionChip(
        onClick = { onSelect(packageName) },
        label = { Text(label, fontSize = 11.sp) },
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = JarvisSurfaceVariant,
            labelColor = JarvisTextPrimary
        ),
        border = SuggestionChipDefaults.suggestionChipBorder(enabled = true, borderColor = JarvisBorder)
    )
}
