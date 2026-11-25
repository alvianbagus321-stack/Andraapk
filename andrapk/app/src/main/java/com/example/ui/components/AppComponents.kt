package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.GlassElevatedTint
import com.example.ui.theme.GlassRadius
import com.example.ui.theme.GlassSurfaceTint
import com.example.ui.theme.JarvisAmber
import com.example.ui.theme.JarvisBorder
import com.example.ui.theme.JarvisCyan
import com.example.ui.theme.JarvisEmerald
import com.example.ui.theme.JarvisSurfaceVariant
import com.example.ui.theme.JarvisTextPrimary
import com.example.ui.theme.JarvisTextSecondary
import com.example.ui.theme.ambientDepth
import com.example.ui.theme.specularGlass

/**
 * Komponen bersama Liquid Glass — tenang, presisi, dan ringan di GPU (60fps).
 */

/**
 * Kanvas kedalaman ambient statis (obsidian + orb radial redup, digambar sekali).
 */
@Composable
fun AuroraBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .ambientDepth()
    ) {
        content()
    }
}

/**
 * Kartu kaca spekular Tier-2: isian kaca gelap translusen agar kedalaman obsidian
 * tetap terasa, dipadukan rim gradien terarah dan sheen atas via drawWithCache
 * (nol biaya blur, nol alokasi per frame).
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    containerColor: Color = GlassSurfaceTint,
    cornerRadius: Dp = GlassRadius.Card,
    accentBrush: Brush? = null,
    active: Boolean = false,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(containerColor)
            .specularGlass(
                cornerRadius = cornerRadius,
                active = active,
                rimBrush = accentBrush
            )
    ) {
        content()
    }
}

/**
 * Header bagian yang bersih dengan hierarki tipografi natural (Sentence case).
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    accent: Color = JarvisCyan,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = JarvisTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.1).sp
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    color = JarvisTextSecondary,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        }
    }
}

/**
 * Lencana status ringkas dengan titik indikator semantik.
 */
@Composable
fun StatusPill(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    activeColor: Color = JarvisEmerald
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = if (active) activeColor.copy(alpha = 0.14f) else JarvisSurfaceVariant.copy(alpha = 0.72f),
        border = BorderStroke(
            0.85.dp,
            if (active) activeColor.copy(alpha = 0.36f) else JarvisBorder
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (active) activeColor else JarvisTextSecondary.copy(alpha = 0.6f))
            )
            Text(
                text = text,
                color = if (active) activeColor else JarvisTextSecondary,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.1.sp,
                maxLines = 1
            )
        }
    }
}

/**
 * Titik indikator status solid & tajam.
 */
@Composable
fun StatusDot(
    active: Boolean,
    modifier: Modifier = Modifier,
    activeColor: Color = JarvisEmerald,
    inactiveColor: Color = JarvisAmber,
    size: Dp = 7.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) activeColor else inactiveColor)
    )
}

/**
 * Ikon identitas aplikasi bergaya Liquid Glass presisi.
 */
@Composable
fun AppLogoMark(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 18.dp
) {
    val radius = (size.value * 0.34f).dp
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .background(GlassElevatedTint)
            .specularGlass(cornerRadius = radius, fillAlpha = 0.08f, active = true),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Bolt,
            contentDescription = "Andra Control",
            modifier = Modifier.size(iconSize),
            tint = JarvisCyan
        )
    }
}

/**
 * Kartu metrik ringkas dengan rim spekular Liquid Glass.
 */
@Composable
fun MetricTile(
    icon: ImageVector,
    label: String,
    value: String,
    detail: String,
    modifier: Modifier = Modifier,
    accent: Color = JarvisCyan
) {
    GlassCard(
        modifier = modifier,
        cornerRadius = GlassRadius.Card
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent.copy(alpha = 0.85f),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    color = JarvisTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                color = JarvisTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = detail,
                color = JarvisTextSecondary,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
