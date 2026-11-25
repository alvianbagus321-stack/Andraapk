package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.service.JarvisCompanionService
import com.example.ui.CodePreviewPayload
import com.example.ui.HtmlPreviewOverlay
import com.example.ui.JarvisChatScreen
import com.example.ui.JarvisDashboardScreen
import com.example.ui.JarvisSandboxScreen
import com.example.ui.JarvisTermuxScreen
import com.example.ui.JarvisToolsScreen
import com.example.ui.JarvisViewModel
import com.example.ui.components.AppLogoMark
import com.example.ui.components.AuroraBackdrop
import com.example.ui.components.StatusPill
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: JarvisViewModel by viewModels()

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                JarvisCompanionService.startMediaProjection(this, result.resultCode, result.data!!)
                Toast.makeText(this, "Tangkap layar aktif", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error starting media projection service", e)
                Toast.makeText(this, "Gagal mengaktifkan tangkap layar: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Izin tangkap layar dibatalkan", Toast.LENGTH_SHORT).show()
        }
    }

    private val multiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val grantedCount = results.values.count { it }
        if (grantedCount > 0) {
            Toast.makeText(this, "$grantedCount izin diperbarui", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch(Dispatchers.IO) {
            com.example.quiz.QuizAnalyzer.loadPersisted()
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        if (intent?.getBooleanExtra("auto_request_projection", false) == true) {
            requestMediaProjection()
        }

        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            multiPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }

        setContent {
            MyApplicationTheme {
                JarvisMainApp(
                    viewModel = viewModel,
                    onRequestMediaProjection = { requestMediaProjection() }
                )
            }
        }
    }

    private fun requestMediaProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                multiPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error launching screen capture intent", e)
            Toast.makeText(this, "Gagal membuka dialog izin: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun JarvisMainApp(
    viewModel: JarvisViewModel,
    onRequestMediaProjection: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val visitedTabs = remember { mutableStateListOf(0) }
    val focusManager = LocalFocusManager.current
    val imeState = rememberReliableImeState()
    var activeCodePreview by remember { mutableStateOf<CodePreviewPayload?>(null) }

    val navItems = remember {
        listOf(
            Triple("nav_dashboard", Icons.Default.Dashboard to "Dashboard", 0),
            Triple("nav_sandbox", Icons.Default.TouchApp to "Sandbox", 1),
            Triple("nav_termux", Icons.Default.Terminal to "Termux", 2),
            Triple("nav_chat", Icons.AutoMirrored.Filled.Chat to "Asisten", 3),
            Triple("nav_tools", Icons.Default.Build to "Tools", 4)
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { _ ->
        AuroraBackdrop {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    FloatingGlassTopBar(viewModel = viewModel)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        ActiveTabHost(
                            visitedTabs = visitedTabs,
                            selectedTab = selectedTab
                        ) { tabIndex ->
                            when (tabIndex) {
                                0 -> JarvisDashboardScreen(
                                    viewModel = viewModel,
                                    onRequestMediaProjection = onRequestMediaProjection
                                )
                                1 -> JarvisSandboxScreen(viewModel = viewModel)
                                2 -> JarvisTermuxScreen(viewModel = viewModel)
                                3 -> JarvisChatScreen(
                                    viewModel = viewModel,
                                    imeState = imeState,
                                    onOpenExternalPreview = { payload ->
                                        focusManager.clearFocus()
                                        activeCodePreview = payload
                                    }
                                )
                                4 -> JarvisToolsScreen()
                            }
                        }
                    }
                }

                // Tier-1 Floating Pill Bottom Navigation Bar — otomatis sembunyi saat keyboard atau Mode Preview terbuka
                AnimatedVisibility(
                    visible = !imeState.isVisible && activeCodePreview == null,
                    enter = fadeIn(tween(160)) + slideInVertically(tween(200)) { it / 2 },
                    exit = fadeOut(tween(110)) + slideOutVertically(tween(150)) { it / 2 },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(GlassRadius.Pill))
                            .floatingGlassChrome(cornerRadius = GlassRadius.Pill)
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        navItems.forEach { (tag, iconAndLabel, index) ->
                            val (icon, label) = iconAndLabel
                            GlassNavItem(
                                icon = icon,
                                label = label,
                                selected = selectedTab == index,
                                onClick = {
                                    if (selectedTab != index) {
                                        focusManager.clearFocus()
                                        if (index !in visitedTabs) {
                                            visitedTabs.add(index)
                                        }
                                        selectedTab = index
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag(tag)
                            )
                        }
                    }
                }

                // Mode Preview HTML/SVG layar penuh di dalam Activity Window (bebas bug Dialog+WebView)
                activeCodePreview?.let { payload ->
                    HtmlPreviewOverlay(
                        htmlCode = payload.code,
                        fileName = payload.fileName,
                        language = payload.language,
                        onFileNameChange = payload.onFileNameChange,
                        onDismiss = { activeCodePreview = null }
                    )
                }
            }
        }
    }
}

/**
 * Item navigasi kaca pada Floating Pill Bottom Bar:
 * - Touch target >= 48dp
 * - Feedback sentuh 0.97f (TactileSpring) di fase draw (graphicsLayer)
 * - Indikator pil aktif di fase draw (drawBehind — nol layout pass).
 */
@Composable
private fun GlassNavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(interaction)
    val selectionFraction = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = FluidSpring,
        label = "nav_selection"
    )
    val tint by animateColorAsState(
        targetValue = if (selected) JarvisTextPrimary else JarvisTextSecondary,
        label = "nav_tint"
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) JarvisCyan else JarvisTextSecondary,
        label = "nav_icon_tint"
    )

    Column(
        modifier = modifier
            .heightIn(min = 50.dp)
            .clip(RoundedCornerShape(GlassRadius.Card))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .graphicsLayer {
                scaleX = pressScale.value
                scaleY = pressScale.value
            }
            .drawBehind {
                val fraction = selectionFraction.value
                if (fraction > 0.01f) {
                    drawRoundRect(
                        color = JarvisCyan.copy(alpha = 0.16f * fraction),
                        cornerRadius = CornerRadius(GlassRadius.Card.toPx())
                    )
                }
            }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconTint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 10.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Custom Layout untuk perpindahan tab instan (0ms stutter):
 * - Tab baru baru di-compose saat pertama kali dikunjungi (startup tetap ringan).
 * - Tab yang sudah dikunjungi menjaga scroll position & remember cache,
 *   tetapi hanya tab aktif yang di-measure dan di-place.
 * - Transisi tab: fade + scale 0.985 -> 1f (180ms) murni di fase draw.
 */
@Composable
private fun ActiveTabHost(
    visitedTabs: List<Int>,
    selectedTab: Int,
    content: @Composable (Int) -> Unit
) {
    val activeSlotIndex = visitedTabs.indexOf(selectedTab).coerceAtLeast(0)
    Layout(
        content = {
            visitedTabs.forEach { tab ->
                key(tab) {
                    val isActive = tab == selectedTab
                    val fade = animateFloatAsState(
                        targetValue = if (isActive) 1f else 0f,
                        animationSpec = tween(durationMillis = TabTransitionDurationMs),
                        label = "tab_fade"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = fade.value
                                val scale = 0.985f + 0.015f * fade.value
                                scaleX = scale
                                scaleY = scale
                            }
                    ) {
                        content(tab)
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { measurables, constraints ->
        val activePlaceable = measurables.getOrNull(activeSlotIndex)?.measure(constraints)
        layout(constraints.maxWidth, constraints.maxHeight) {
            activePlaceable?.place(0, 0)
        }
    }
}

/**
 * Tier-1 Compact Floating Glass Top Bar: satu baris ringkas mengambang di bawah status bar.
 */
@Composable
private fun FloatingGlassTopBar(viewModel: JarvisViewModel) {
    val isServerRunning by viewModel.isServerRunning.collectAsStateWithLifecycle()
    val onToggleServer = remember(viewModel) { { _: Boolean -> viewModel.toggleServer() } }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(GlassRadius.Card))
                .floatingGlassChrome(cornerRadius = GlassRadius.Card)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                AppLogoMark(size = 34.dp, iconSize = 18.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Andra Control",
                        color = JarvisTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.2).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Otomasi & asisten perangkat",
                        color = JarvisTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusPill(
                    text = if (isServerRunning) "Online" else "Offline",
                    active = isServerRunning
                )
                Switch(
                    checked = isServerRunning,
                    onCheckedChange = onToggleServer,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = JarvisCyan,
                        checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = JarvisTextSecondary,
                        uncheckedTrackColor = JarvisSurfaceVariant,
                        uncheckedBorderColor = JarvisBorderStrong
                    ),
                    modifier = Modifier
                        .scale(0.84f)
                        .testTag("server_toggle_switch")
                )
            }
        }
    }
}
