---
name: android-liquid-glass-anti-slop
description: >
  Production-grade Liquid Glass & Anti-AI-Slop UI/UX design and 60fps motion skill for
  Native Android (Jetpack Compose + Material 3, Android 14+ / API 34+). Enforces a 2-tier
  hybrid Liquid Glass architecture (selective real backdrop blur on floating chrome +
  zero-blur-cost cached specular glass for scrolling cards), draw-phase spring animations,
  edge-to-edge IME inset safety, and Compose recomposition hygiene. Use whenever designing,
  scaffolding, restyling, or auditing any Android screen, chat UI, dashboard, or utility app.
---

# Android Liquid Glass, Smooth Motion & Anti-Slop UI Skill (Jetpack Compose + M3)

> **Referensi & Sumber Ekosistem:**
> Disintesis dari [rshzrh/ui-design-skills (ui-android)](https://github.com/rshzrh/ui-design-skills/blob/main/skills/ui-android/SKILL.md), [Anthropic frontend-design](https://github.com/anthropics/skills/blob/main/skills%2Ffrontend-design%2FSKILL.md), [miqdadbadjuber/anti-slop](https://github.com/miqdadbadjuber/anti-slop), [Impeccable](https://impeccable.style/), [Nutlope/hallmark](https://github.com/nutlope/hallmark), [Chris Banes Haze 2.x](https://github.com/chrisbanes/haze), dan [Android Compose Graphics & Blur Docs](https://developer.android.com/develop/ui/compose/graphics/blur/overview).

---

## 1. Core Philosophy: Tactile Liquid Glass Without Lag or Slop

Untouched AI coding agents fail at Liquid Glass on Android in two opposite ways:
1. **AI Slop**: Flat `#0D1117` backgrounds, identical `1.dp` gray borders on every box, clashing neon buttons, stacked 3-row headers, hardcoded `Color(0xFF...)`, and broken keyboard insets.
2. **Lag Nightmare**: Slapping `Modifier.blur()` or live backdrop blur onto every card inside a `LazyColumn`, animating background gradients in infinite loops, and triggering full-screen recompositions on every frame — dropping mid-range Android phones to 20 FPS.

**Non-Negotiable Rule:**
Never use buzzwords (*"clean"*, *"modern"*, *"sleek"*, *"premium"*) to justify UI choices. Every surface, specular border, blur budget, spring curve, and spacing value MUST come from explicit tokens in `ui/theme/`.

---

## 2. Two-Tier Hybrid Liquid Glass Architecture (60 FPS Budget)

To achieve authentic translucent Liquid Glass on mid-range Android 14+ hardware without frame drops, strictly separate surfaces into two tiers:

### Tier 1: Real Backdrop Glass (Max 2–3 Floating Chrome Surfaces Per Screen)
- Use **[Haze 2.x](https://github.com/chrisbanes/haze)** (`dev.chrisbanes.haze:haze`, `haze-blur`, `haze-glass` with mobile `HazePerformanceMode`) — with a clean translucent tinted scrim fallback for API < 31.
- Allowed **ONLY** on floating chrome over scrolling content:
  - Floating Pill Bottom Navigation Bar
  - Floating `ChatInputBar`
  - `ModalBottomSheet` / compact Floating Top Bar
- **FORBIDDEN** inside `LazyColumn` / `LazyVerticalGrid` items.

### Tier 2: Lightweight Specular Liquid Glass (All Cards & List Items — 0ms Blur Cost)
- Implement a reusable `Modifier.liquidGlassSurface(...)` backed by **`Modifier.drawWithCache`** (zero per-frame brush/path allocations):
  - **Translucent Tinted Fill**: `Color.White.copy(alpha = 0.06f..0.10f)` (standard card) or `0.11f..0.15f` (elevated/active pill).
  - **Specular Rim Light**: `0.85.dp` border drawn with a cached directional `Brush.linearGradient` — bright top-left highlight (`Color.White.copy(alpha = 0.26f)`) fading through translucent mid (`0.05f`) to soft bottom-right catchlight (`0.13f`).
  - **Inner Top Sheen**: Soft top-edge specular gradient fading to transparent within the top `20.dp–24.dp`.
  - **Organic Radii**: `20.dp–24.dp` for cards, `28.dp` / `CircleShape` for floating pills and chat input bars.

### Ambient Depth Canvas (Static Cached Background)
- Liquid Glass needs tonal depth behind it to feel refractive rather than muddy.
- Draw a **static** ambient mesh once via `Modifier.drawWithCache`: deep obsidian base (`#070A10`) + 2–3 low-alpha (`0.10f–0.16f`) radial color orbs matching the primary accent family.
- **FORBIDDEN**: Infinite `rememberInfiniteTransition` background blur/mesh animations that burn GPU continuously.

---

## 3. Smooth 60 FPS Motion & Spring Physics (`ui/theme/Motion.kt`)

Every animation must feel fluid, interruptible, and GPU-friendly:
1. **Physics Over Linear Curves**:
   - Tactile tap/press/toggle: `spring(dampingRatio = 0.82f, stiffness = 450f)`
   - Fluid layout/pill slide/expand: `spring(dampingRatio = 0.86f, stiffness = 280f)`
   - Tab switch: Lightweight fade + subtle scale (`0.985f -> 1f`, `180ms`) — never heavy slide-outs that drop frames.
2. **Draw-Phase Only (`graphicsLayer`)**:
   - All scale, alpha, rotation, and translation animations MUST use lambda-based `Modifier.graphicsLayer { ... }` so Compose skips Composition and Layout phases entirely.
3. **Tactile Press Feedback**:
   - Interactive glass pills, cards, and primary buttons should apply a subtle `0.97f` press scale driven by `MutableInteractionSource` + `animateFloatAsState` inside `graphicsLayer`.
4. **No Off-Screen Animations**:
   - Pause or dispose any running animation when a composable leaves the viewport or when the lifecycle hits `ON_STOP`.

---

## 4. Layout Craft, Copy & Anti-Slop Discipline

1. **Accent Discipline**: One primary accent hue for primary CTAs, active switches, and selected nav indicators. Semantic green/amber/red restricted to small status dots, quota bars, or subtle tonal badges — never giant neon-green form buttons.
2. **Header Economy**: Max 1 compact floating top bar per screen. Fold secondary selectors (model, session, logs, settings) into a tappable subtitle/pill that opens a glass `ModalBottomSheet`.
3. **Grouped Lists Over Card Soup**: Group related toggles inside a single Tier-2 glass container with hairline translucent dividers (`Color.White.copy(alpha = 0.08f)`) instead of stacking 5 separate bordered cards.
4. **Zero Overlap**:
   - Lists on screens with a floating bottom nav bar or FAB MUST include bottom `contentPadding` (`≥ 96.dp–104.dp`) so trailing switches and controls are never obscured.
   - Touch targets MUST be `≥ 48.dp × 48.dp`.
5. **Clean Content Rendering**:
   - Render real Markdown (bold, lists, inline code, fenced code blocks with copy button) — never expose raw `**` tokens or internal system instructions in chat bubbles.
   - Use **Sentence case** on all buttons and labels. Maintain WCAG AA contrast (`≥ 4.5:1` for text) on top of translucent glass surfaces.

---

## 5. Mandatory Edge-to-Edge & IME (Keyboard) Safety Rules

1. `AndroidManifest.xml`: `android:windowSoftInputMode="adjustResize"` on the main `Activity`.
2. `Activity`: Call `enableEdgeToEdge()` before `setContent`.
3. Root `Scaffold`: `contentWindowInsets = WindowInsets(0, 0, 0, 0)` when child screens manage IME/bottom insets.
4. Chat / Input Screens:
   - Hide the floating bottom `NavigationBar` smoothly when `WindowInsets.isImeVisible` is `true`.
   - Place the floating glass `ChatInputBar` below `LazyColumn(Modifier.weight(1f))` inside a `Column` with `Modifier.navigationBarsPadding().imePadding()`.
   - Auto-scroll the chat `LazyColumn` to the newest message when the keyboard rises or a new message arrives.

---

## 6. Jetpack Compose Anti-Lag Hygiene

1. **Lazy Lists Everywhere**: All catalogs (>15 items) and chats MUST use `LazyColumn` with `key = { it.id }` and `contentType`.
2. **Immutable & Scoped State**: Annotate models `@Immutable` / `@Stable`; never read rapidly changing state (timers, live logs, streaming tokens) at the root `Scaffold`.
3. **Throttle Streams**: Throttle LLM token streaming and live terminal output to `32ms–50ms` batches before emitting to `StateFlow`.
4. **Off-Main-Thread Work**: Filtering, sorting, Markdown parsing, JSON decoding, and disk I/O MUST run on `Dispatchers.Default` / `Dispatchers.IO` and use `collectAsStateWithLifecycle()`.

---

## 7. Pre-Ship Self-Audit Checklist

Print a PASS/FAIL table before declaring any Android UI task done:

| # | Audit Check | Pass Criteria |
|---|---|---|
| 1 | **2-Tier Blur Budget** | Real backdrop blur (Haze) used ONLY on 2–3 floating chrome surfaces; zero live blur inside `LazyColumn` items. |
| 2 | **Cached Specular Glass** | Cards/items use `drawWithCache` translucent fill + directional specular rim light (zero per-frame allocations). |
| 3 | **Draw-Phase Motion** | Spring animations and press scales run in `graphicsLayer { ... }` without triggering recomposition. |
| 4 | **Zero Hardcoded Styles** | No `Color(0xFF...)` or arbitrary `.dp` literals scattered outside `ui/theme/`. |
| 5 | **Keyboard / IME Safety** | Chat input docks flush above keyboard; floating nav hides cleanly when IME is visible. |
| 6 | **Lazy & Stable Lists** | All catalogs/chats use `LazyColumn` with `key` + `contentType` + `@Immutable`; filtering off main thread. |
| 7 | **Zero Overlap & Contrast** | Bottom `contentPadding` clears floating nav/FAB; touch targets `≥ 48.dp`; text contrast `≥ 4.5:1`. |
| 8 | **Clean Rendering** | Markdown rendered properly; internal AI system prompts hidden; `./gradlew assembleDebug` passes. |
