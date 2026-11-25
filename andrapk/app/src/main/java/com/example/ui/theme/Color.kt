package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// ==========================================================
// LIQUID GLASS & GRAPHITE PRECISION COLOR TOKENS
// Seluruh warna terpusat di ui/theme/ — nol Color(0xFF...) tersebar di screen.
// ==========================================================

// --- Kanvas kedalaman obsidian & orb ambient statis ---
val DepthObsidian = Color(0xFF070A10)
val AmbientOrbSecondary = Color(0xFF24395C)
val AmbientOrbBottom = Color(0xFF131D2B)

// --- Permukaan bertingkat (solid fallback & translucent Liquid Glass) ---
val JarvisBackground = Color(0xFF070A10)
val JarvisSurface = Color(0xFF111620)
val JarvisSurfaceVariant = Color(0xFF1A202C)
val JarvisSurfaceHighlight = Color(0xFF232B3A)
val JarvisSurfaceElevated = Color(0xFF161C28)

// --- Translucent Liquid Glass Tints (Tier-1 Floating Chrome & Tier-2 Specular Cards) ---
val GlassSurfaceTint = Color(0x94101622)
val GlassElevatedTint = Color(0xBA151D2C)
val GlassChromeTint = Color(0xD60C111B)
val GlassUserBubbleTint = Color(0xA6152642)
val GlassCodeCanvas = Color(0xCC090D14)
val GlassCodeHeader = Color(0xCC131924)
val GlassCodeText = Color(0xFFDCE4EE)
val GlassInlineCodeBg = Color(0x265B9CF6)

// --- Specular rim & hairline dividers ---
val JarvisBorder = Color(0x14FFFFFF)
val JarvisBorderStrong = Color(0x24FFFFFF)
val GlassDivider = Color(0x14FFFFFF)
val GlassRimBright = Color(0x45FFFFFF)
val GlassRimActive = Color(0x57FFFFFF)
val GlassRimMid = Color(0x0DFFFFFF)
val GlassRimCatch = Color(0x21FFFFFF)
val GlassSheenTop = Color(0x0FFFFFFF)

// --- Aksen primer tunggal (cobalt baja, bebas neon berlebih) ---
val JarvisCyan = Color(0xFF5B9CF6)
val JarvisCyanDark = Color(0xFF3B82F6)
val JarvisOnPrimary = Color(0xFF071324)
val JarvisTeal = Color(0xFF94A3B8)
val AuroraViolet = Color(0xFF7FB2F8)
val AuroraIndigo = Color(0xFF64748B)
val AuroraDeep = Color(0xFF151920)

// --- Warna semantik status (khusus indikator status & peringatan nyata) ---
val JarvisEmerald = Color(0xFF34C77B)
val JarvisAmber = Color(0xFFE5A93B)
val JarvisRed = Color(0xFFE55B64)

// --- Tipografi kontras tinggi (WCAG AA >= 4.5:1 di atas kaca gelap) ---
val JarvisTextPrimary = Color(0xFFF0F4FA)
val JarvisTextSecondary = Color(0xFF9AA4B5)
val JarvisTextTertiary = Color(0xFF6D788A)

// --- Token Material 3 ColorScheme internal ---
val PrimaryContainerDark = Color(0xFF17263C)
val OnPrimaryContainerDark = Color(0xFFD6E6FF)
val SecondaryOnDark = Color(0xFF0E131A)
val SecondaryContainerOnDark = Color(0xFFDCE2EC)
val TertiaryOnDark = Color(0xFF051B10)
val TertiaryContainerDark = Color(0xFF112C20)
val OnTertiaryContainerDark = Color(0xFFC5F3DA)
val SurfaceContainerLowestDark = Color(0xFF070A10)
val SurfaceContainerLowDark = Color(0xFF0E131C)
val ErrorOnDark = Color(0xFF22070A)
val ErrorContainerDark = Color(0xFF331418)
val OnErrorContainerDark = Color(0xFFFFD9DC)
