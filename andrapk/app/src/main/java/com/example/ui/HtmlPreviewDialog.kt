package com.example.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private class PreviewWebViewHolder(var instance: WebView? = null)

/**
 * Helper kompatibilitas untuk mendeteksi kode HTML dari teks chat.
 */
object HtmlCodeExtractor {
    private val MARKDOWN_HTML_REGEX = Regex("```(?:html|htm|svg|xml)?[^\\n]*\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE)

    fun extractHtml(text: String): String? {
        if (!text.contains('<')) return null
        val matches = MARKDOWN_HTML_REGEX.findAll(text)
        for (match in matches) {
            val code = match.groupValues[1].trim()
            if (code.contains("<html", ignoreCase = true) ||
                code.contains("<!DOCTYPE", ignoreCase = true) ||
                code.contains("<svg", ignoreCase = true) ||
                code.contains("<div", ignoreCase = true) ||
                code.contains("<style", ignoreCase = true) ||
                code.contains("<body", ignoreCase = true) ||
                code.contains("<h1", ignoreCase = true) ||
                code.contains("<p>", ignoreCase = true)
            ) {
                return code
            }
        }

        if (text.contains("<!DOCTYPE html>", ignoreCase = true) || text.contains("<html", ignoreCase = true)) {
            val startIdx = text.indexOf("<!DOCTYPE", ignoreCase = true).let { if (it >= 0) it else text.indexOf("<html", ignoreCase = true) }
            val endIdx = text.lastIndexOf("</html>", ignoreCase = true)
            if (startIdx >= 0) {
                return if (endIdx > startIdx) {
                    text.substring(startIdx, endIdx + 7).trim()
                } else {
                    text.substring(startIdx).trim()
                }
            }
        }

        return null
    }
}

/**
 * Kartu banner cadangan jika ada HTML mentah di luar blok kode terstruktur.
 */
@Composable
fun HtmlPreviewBanner(
    htmlCode: String,
    onOpenPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        color = JarvisSurfaceVariant,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, JarvisBorderStrong)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = JarvisCyan.copy(alpha = 0.14f),
                modifier = Modifier.size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Language,
                        contentDescription = "HTML Web",
                        tint = JarvisCyan,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Kode HTML Terdeteksi",
                    color = JarvisTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${htmlCode.length} karakter · Siap di-preview",
                    color = JarvisTextSecondary,
                    fontSize = 10.5.sp
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Button(
                onClick = onOpenPreview,
                colors = ButtonDefaults.buttonColors(
                    containerColor = JarvisCyan,
                    contentColor = JarvisOnPrimary
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    Icons.Default.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "Preview",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Overlay Mode Preview Live di dalam Window Activity utama (bebas bug Dialog+WebView
 * di Android 14–16 / OEM ROM, mendukung penuh hardware acceleration, keyboard form HTML,
 * dan tombol Back sistem).
 */
@Composable
fun HtmlPreviewOverlay(
    htmlCode: String,
    fileName: String = "index.html",
    language: String = "html",
    onFileNameChange: ((String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    BackHandler(enabled = true) {
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DepthObsidian)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = JarvisSurface
        ) {
            HtmlPreviewContent(
                htmlCode = htmlCode,
                fileName = fileName,
                language = language,
                onFileNameChange = onFileNameChange,
                onDismiss = onDismiss
            )
        }
    }
}

/**
 * Wrapper Dialog kompatibel jika dipanggil dari layar lain di luar Chat.
 */
@Composable
fun HtmlPreviewDialog(
    htmlCode: String,
    fileName: String = "index.html",
    language: String = "html",
    onFileNameChange: ((String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
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
                .padding(8.dp),
            shape = RoundedCornerShape(14.dp),
            color = JarvisSurface,
            border = BorderStroke(1.dp, JarvisBorderStrong)
        ) {
            HtmlPreviewContent(
                htmlCode = htmlCode,
                fileName = fileName,
                language = language,
                onFileNameChange = onFileNameChange,
                onDismiss = onDismiss
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlPreviewContent(
    htmlCode: String,
    fileName: String,
    language: String,
    onFileNameChange: ((String) -> Unit)?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Live View, 1: Source Code
    var currentFileName by remember(fileName) { mutableStateOf(fileName) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var copiedRecently by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var isPageLoading by remember { mutableStateOf(true) }
    var webViewInitError by remember { mutableStateOf<String?>(null) }

    val webViewHolder = remember { PreviewWebViewHolder() }

    val renderableHtml = remember(htmlCode, language) {
        ChatMessageParser.buildPreviewHtmlDocument(htmlCode, language)
    }
    val lineCount = remember(htmlCode) { htmlCode.lines().size }
    val lineNumbersText = remember(lineCount) { (1..lineCount).joinToString("\n") }

    LaunchedEffect(copiedRecently) {
        if (copiedRecently) {
            delay(1600L)
            copiedRecently = false
        }
    }

    fun copyCurrentCode() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(currentFileName, htmlCode))
        copiedRecently = true
        Toast.makeText(context, "$currentFileName disalin ke clipboard", Toast.LENGTH_SHORT).show()
    }

    fun downloadCurrentCode() {
        if (isSaving) return
        isSaving = true
        coroutineScope.launch {
            val result = CodeFileDownloader.saveCodeToDownloads(
                context = context,
                fileName = currentFileName,
                code = htmlCode,
                language = language
            )
            isSaving = false
            result.onSuccess { savedPath ->
                Toast.makeText(context, "Tersimpan: $savedPath", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(context, "Gagal mengunduh: ${err.message ?: "Error"}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(JarvisSurfaceVariant)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = JarvisCyan.copy(alpha = 0.14f),
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Language,
                        contentDescription = null,
                        tint = JarvisCyan,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showRenameDialog = true }
                    .padding(vertical = 2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = currentFileName,
                        color = JarvisTextPrimary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Ubah nama file",
                        tint = JarvisTextSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                }
                Text(
                    text = "Mode Preview · $lineCount baris",
                    color = JarvisTextSecondary,
                    fontSize = 10.5.sp
                )
            }

            if (selectedTab == 0 && webViewInitError == null) {
                IconButton(
                    onClick = {
                        isPageLoading = true
                        webViewHolder.instance?.loadDataWithBaseURL(
                            "https://localhost/",
                            renderableHtml,
                            "text/html",
                            "UTF-8",
                            null
                        )
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Muat ulang preview",
                        tint = JarvisCyan,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Tutup preview",
                    tint = JarvisTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        HorizontalDivider(color = JarvisBorder, thickness = 1.dp)

        // Switcher Tampilan Live vs Source Code
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(JarvisSurface)
        ) {
            PreviewModeTab(
                label = "Tampilan Live",
                icon = Icons.Default.Visibility,
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                modifier = Modifier.weight(1f)
            )
            PreviewModeTab(
                label = "Source Code",
                icon = Icons.Default.Code,
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                modifier = Modifier.weight(1f)
            )
        }

        HorizontalDivider(color = JarvisBorder, thickness = 1.dp)

        if (selectedTab == 0 && isPageLoading && webViewInitError == null) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = JarvisCyan,
                trackColor = JarvisSurface
            )
        }

        // Area konten utama
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(if (selectedTab == 0 && webViewInitError == null) Color.White else JarvisBackground)
        ) {
            if (selectedTab == 0) {
                val initErr = webViewInitError
                if (initErr != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = JarvisAmber,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "WebView tidak tersedia di perangkat ini",
                            color = JarvisTextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = initErr,
                            color = JarvisTextSecondary,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = { selectedTab = 1 },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = JarvisCyan,
                                contentColor = JarvisOnPrimary
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Lihat Source Code", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    AndroidView(
                        factory = { ctx ->
                            try {
                                WebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    setBackgroundColor(android.graphics.Color.WHITE)
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.databaseEnabled = true
                                    settings.allowFileAccess = true
                                    settings.allowContentAccess = true
                                    settings.javaScriptCanOpenWindowsAutomatically = true
                                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                    settings.loadWithOverviewMode = true
                                    settings.useWideViewPort = true
                                    settings.builtInZoomControls = true
                                    settings.displayZoomControls = false
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            isPageLoading = false
                                        }
                                    }
                                    webChromeClient = WebChromeClient()
                                    tag = renderableHtml
                                    webViewHolder.instance = this
                                    loadDataWithBaseURL(
                                        "https://localhost/",
                                        renderableHtml,
                                        "text/html",
                                        "UTF-8",
                                        null
                                    )
                                }
                            } catch (t: Throwable) {
                                webViewInitError = t.localizedMessage ?: "Gagal memuat WebView"
                                android.view.View(ctx)
                            }
                        },
                        update = { view ->
                            val wv = view as? WebView ?: return@AndroidView
                            webViewHolder.instance = wv
                            if (wv.tag != renderableHtml) {
                                isPageLoading = true
                                wv.tag = renderableHtml
                                wv.loadDataWithBaseURL(
                                    "https://localhost/",
                                    renderableHtml,
                                    "text/html",
                                    "UTF-8",
                                    null
                                )
                            }
                        },
                        onRelease = { view ->
                            val wv = view as? WebView
                            if (webViewHolder.instance === wv) {
                                webViewHolder.instance = null
                            }
                            wv?.apply {
                                stopLoading()
                                destroy()
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 10.dp)
                ) {
                    Text(
                        text = lineNumbersText,
                        color = JarvisTextTertiary,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(start = 12.dp, end = 10.dp)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState())
                            .padding(end = 12.dp)
                    ) {
                        Text(
                            text = htmlCode,
                            color = GlassCodeText,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 17.sp,
                            softWrap = false
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = JarvisBorder, thickness = 1.dp)

        // Footer aksi: Salin & Unduh langsung dari Mode Preview
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(JarvisSurfaceVariant)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { copyCurrentCode() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(9.dp),
                border = BorderStroke(1.dp, if (copiedRecently) JarvisEmerald else JarvisBorderStrong),
                contentPadding = PaddingValues(vertical = 9.dp)
            ) {
                Icon(
                    imageVector = if (copiedRecently) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = null,
                    tint = if (copiedRecently) JarvisEmerald else JarvisTextPrimary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (copiedRecently) "Tersalin" else "Salin Kode",
                    color = if (copiedRecently) JarvisEmerald else JarvisTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Button(
                onClick = { downloadCurrentCode() },
                enabled = !isSaving,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = JarvisCyan,
                    contentColor = JarvisOnPrimary
                ),
                shape = RoundedCornerShape(9.dp),
                contentPadding = PaddingValues(vertical = 9.dp)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.8.dp,
                        color = JarvisOnPrimary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isSaving) "Menyimpan..." else "Unduh File",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    if (showRenameDialog) {
        RenameCodeFileDialog(
            currentName = currentFileName,
            language = language,
            onConfirm = { newName ->
                val sanitized = CodeFileDownloader.sanitizeFileName(newName, language)
                currentFileName = sanitized
                onFileNameChange?.invoke(sanitized)
                showRenameDialog = false
                Toast.makeText(context, "Nama file diubah: $sanitized", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showRenameDialog = false }
        )
    }
}

@Composable
private fun PreviewModeTab(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(top = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) JarvisCyan else JarvisTextSecondary,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = if (selected) JarvisTextPrimary else JarvisTextSecondary,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (selected) JarvisCyan else Color.Transparent)
        )
    }
}
