package com.lenscode.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Match Compose clipping to the XR compositor so neither adds extra rounding. */
object LensCodeCorners {
    val Panel = 10.dp
    val Card = 8.dp
    val Control = 6.dp
}

/** One selectable app-wide color scheme — see [applyAppTheme]. */
enum class ThemeId(val label: String) {
    CURSOR("Cursor"),
    DRACULA("Dracula"),
    SOLARIZED_DARK("Solarized Dark"),
    DEEP_BLACK("Deep Black"),
}

/** App-chrome colors for one theme — see [LensCodeColors]. */
data class LensCodePalette(
    val text: Color, val muted: Color, val blue: Color, val green: Color, val red: Color,
    val amber: Color, val purple: Color, val cyan: Color, val eyebrow: Color, val idle: Color,
    val activeDock: Color,
    val panelGradTop: Color, val panelGradBottom: Color, val panelGlow: Color,
    val panelBorder: Color, val panelBorderTop: Color, val sheenWhite: Color, val sheenBlue: Color,
    val cardBorder: Color, val cardBg: Color, val cardGradTop: Color,
    val dockIdle: Color, val dockActiveGradTop: Color, val dockActiveGradBottom: Color,
    val dockActiveBorder: Color, val dockBg: Color, val logoBg: Color, val logoBorder: Color,
)

/** Code-editor syntax colors for one theme — see [VsCodeColors]. */
data class CodePalette(
    val editorBg: Color, val sidebarBg: Color, val tabBarBg: Color, val activeTabBg: Color,
    val lineNumber: Color, val hoverRow: Color,
    val keyword: Color, val stringLit: Color, val comment: Color, val number: Color,
    val typeName: Color, val plain: Color,
)

// The app's original look — a dark, cool-cyan "glass" aesthetic close to
// Cursor's own default dark theme (and to plain VSCode Dark+, which
// CURSOR_CODE's syntax colors are lifted directly from).
val CURSOR_PALETTE = LensCodePalette(
    text = Color(0xFFEEF7FF), muted = Color(0xFFB0C1D0), blue = Color(0xFF91CFFF),
    green = Color(0xFF59E58C), red = Color(0xFFFF7F95), amber = Color(0xFFFFD166),
    purple = Color(0xFFB494FF), cyan = Color(0xFF7DE9FF), eyebrow = Color(0xFF9EB8CC),
    idle = Color(0xFF8B9DAD), activeDock = Color(0xFFE0F7FF),
    panelGradTop = Color(0xD0223342), panelGradBottom = Color(0xE6101B26),
    panelGlow = Color(0x187DDFFF), panelBorder = Color(0x246DA3BD), panelBorderTop = Color(0x65E0F4FF),
    sheenWhite = Color(0x12FFFFFF), sheenBlue = Color(0x0C86D8EF),
    cardBorder = Color(0x386F96AB), cardBg = Color(0xBA101F2C), cardGradTop = Color(0xCC263947),
    dockIdle = Color(0xFFB0C3D1), dockActiveGradTop = Color(0xD03B687E), dockActiveGradBottom = Color(0xD01A344A),
    dockActiveBorder = Color(0xB09BDEFA), dockBg = Color(0xCF132330),
    logoBg = Color(0xB3192A37), logoBorder = Color(0x527C9CAD),
)

val CURSOR_CODE = CodePalette(
    editorBg = Color(0xE6101A24), sidebarBg = Color(0x9C172733), tabBarBg = Color(0xAD1C2D3A),
    activeTabBg = Color(0xC52D4454), lineNumber = Color(0xFF8A9FAF), hoverRow = Color(0x553D6278),
    keyword = Color(0xFF569CD6), stringLit = Color(0xFFCE9178), comment = Color(0xFF6A9955),
    number = Color(0xFFB5CEA8), typeName = Color(0xFF4EC9B0), plain = Color(0xFFD4D4D4),
)

// Dracula Official — dracula-theme.com's published palette; syntax roles
// (keyword=pink, string=yellow, number=purple, type=cyan) match the real
// "Dracula Official" VSCode extension.
val DRACULA_PALETTE = LensCodePalette(
    text = Color(0xFFF8F8F2), muted = Color(0xFFA9B1D6), blue = Color(0xFF6272A4),
    green = Color(0xFF50FA7B), red = Color(0xFFFF5555), amber = Color(0xFFFFB86C),
    purple = Color(0xFFBD93F9), cyan = Color(0xFF8BE9FD), eyebrow = Color(0xFF9580FF),
    idle = Color(0xFF6272A4), activeDock = Color(0xFFF8F8F2),
    panelGradTop = Color(0xD0343746), panelGradBottom = Color(0xE621222C),
    panelGlow = Color(0x18BD93F9), panelBorder = Color(0x246272A4), panelBorderTop = Color(0x65BD93F9),
    sheenWhite = Color(0x12FFFFFF), sheenBlue = Color(0x0C8BE9FD),
    cardBorder = Color(0x386272A4), cardBg = Color(0xBA282A36), cardGradTop = Color(0xCC343746),
    dockIdle = Color(0xFFA9B1D6), dockActiveGradTop = Color(0xD0454759), dockActiveGradBottom = Color(0xD028293A),
    dockActiveBorder = Color(0xB0BD93F9), dockBg = Color(0xCF21222C),
    logoBg = Color(0xB3282A36), logoBorder = Color(0x526272A4),
)

val DRACULA_CODE = CodePalette(
    editorBg = Color(0xE6282A36), sidebarBg = Color(0x9C21222C), tabBarBg = Color(0xAD282A36),
    activeTabBg = Color(0xC544475A), lineNumber = Color(0xFF6272A4), hoverRow = Color(0x5544475A),
    keyword = Color(0xFFFF79C6), stringLit = Color(0xFFF1FA8C), comment = Color(0xFF6272A4),
    number = Color(0xFFBD93F9), typeName = Color(0xFF8BE9FD), plain = Color(0xFFF8F8F2),
)

// Solarized (Ethan Schoonover, ethanschoonover.com/solarized) — base03/base02
// backgrounds, base01 secondary text, full accent ring. Syntax roles here
// match the widely-used "Solarized Dark"/"Solarized Light" VSCode themes.
val SOLARIZED_DARK_PALETTE = LensCodePalette(
    text = Color(0xFF93A1A1), muted = Color(0xFF586E75), blue = Color(0xFF268BD2),
    green = Color(0xFF859900), red = Color(0xFFDC322F), amber = Color(0xFFB58900),
    purple = Color(0xFF6C71C4), cyan = Color(0xFF2AA198), eyebrow = Color(0xFF839496),
    idle = Color(0xFF586E75), activeDock = Color(0xFFEEE8D5),
    panelGradTop = Color(0xD0073642), panelGradBottom = Color(0xE6002B36),
    panelGlow = Color(0x182AA198), panelBorder = Color(0x24586E75), panelBorderTop = Color(0x652AA198),
    sheenWhite = Color(0x12FFFFFF), sheenBlue = Color(0x0C268BD2),
    cardBorder = Color(0x38586E75), cardBg = Color(0xBA002B36), cardGradTop = Color(0xCC073642),
    dockIdle = Color(0xFF839496), dockActiveGradTop = Color(0xD00A4A5C), dockActiveGradBottom = Color(0xD0002B36),
    dockActiveBorder = Color(0xB02AA198), dockBg = Color(0xCF002B36),
    logoBg = Color(0xB3073642), logoBorder = Color(0x52586E75),
)

val SOLARIZED_DARK_CODE = CodePalette(
    editorBg = Color(0xE6002B36), sidebarBg = Color(0x9C073642), tabBarBg = Color(0xAD073642),
    activeTabBg = Color(0xC50A4A5C), lineNumber = Color(0xFF586E75), hoverRow = Color(0x55073642),
    keyword = Color(0xFF859900), stringLit = Color(0xFF2AA198), comment = Color(0xFF586E75),
    number = Color(0xFFD33682), typeName = Color(0xFFB58900), plain = Color(0xFF839496),
)

// Deep Black — true-black (#000000) surfaces for max contrast/OLED power
// draw, accented with Apple's iOS/macOS system color set (well-known,
// deliberately chosen for clean legibility against pure black rather than
// picked arbitrarily).
val DEEP_BLACK_PALETTE = LensCodePalette(
    text = Color(0xFFFFFFFF), muted = Color(0xFF8A8A8E), blue = Color(0xFF0A84FF),
    green = Color(0xFF30D158), red = Color(0xFFFF453A), amber = Color(0xFFFFD60A),
    purple = Color(0xFFBF5AF2), cyan = Color(0xFF64D2FF), eyebrow = Color(0xFF8A8A8E),
    idle = Color(0xFF636366), activeDock = Color(0xFFFFFFFF),
    panelGradTop = Color(0xD0101010), panelGradBottom = Color(0xE6000000),
    panelGlow = Color(0x1864D2FF), panelBorder = Color(0x24636366), panelBorderTop = Color(0x6564D2FF),
    sheenWhite = Color(0x10FFFFFF), sheenBlue = Color(0x0A64D2FF),
    cardBorder = Color(0x38636366), cardBg = Color(0xBA000000), cardGradTop = Color(0xCC0D0D0D),
    dockIdle = Color(0xFF8A8A8E), dockActiveGradTop = Color(0xD01C1C1E), dockActiveGradBottom = Color(0xD0000000),
    dockActiveBorder = Color(0xB064D2FF), dockBg = Color(0xCF000000),
    logoBg = Color(0xB3000000), logoBorder = Color(0x52636366),
)

val DEEP_BLACK_CODE = CodePalette(
    editorBg = Color(0xE6000000), sidebarBg = Color(0x9C0A0A0A), tabBarBg = Color(0xAD0D0D0D),
    activeTabBg = Color(0xC51C1C1E), lineNumber = Color(0xFF636366), hoverRow = Color(0x551C1C1E),
    keyword = Color(0xFF0A84FF), stringLit = Color(0xFF30D158), comment = Color(0xFF8A8A8E),
    number = Color(0xFFFFD60A), typeName = Color(0xFFBF5AF2), plain = Color(0xFFFFFFFF),
)

fun lensCodePaletteFor(id: ThemeId): LensCodePalette = when (id) {
    ThemeId.CURSOR -> CURSOR_PALETTE
    ThemeId.DRACULA -> DRACULA_PALETTE
    ThemeId.SOLARIZED_DARK -> SOLARIZED_DARK_PALETTE
    ThemeId.DEEP_BLACK -> DEEP_BLACK_PALETTE
}

fun codePaletteFor(id: ThemeId): CodePalette = when (id) {
    ThemeId.CURSOR -> CURSOR_CODE
    ThemeId.DRACULA -> DRACULA_CODE
    ThemeId.SOLARIZED_DARK -> SOLARIZED_DARK_CODE
    ThemeId.DEEP_BLACK -> DEEP_BLACK_CODE
}

/**
 * Smoked optical glass: cool neutral surfaces, luminous edges and ice accents
 * by default ("Cursor") — or whichever theme [applyAppTheme] last selected
 * (Settings → App Theme). Every field is individually mutable-state-backed
 * (not a swapped object reference), so every existing `LensCodeColors.X` read
 * across the app recomposes automatically on a theme change with zero
 * call-site changes — the alternative (a CompositionLocal / theme object
 * threaded through every composable) would have meant touching every file
 * that references these colors, which is most of ui/.
 */
object LensCodeColors {
    var Text by mutableStateOf(CURSOR_PALETTE.text); private set
    var Muted by mutableStateOf(CURSOR_PALETTE.muted); private set
    var Blue by mutableStateOf(CURSOR_PALETTE.blue); private set
    var Green by mutableStateOf(CURSOR_PALETTE.green); private set
    var Red by mutableStateOf(CURSOR_PALETTE.red); private set
    var Amber by mutableStateOf(CURSOR_PALETTE.amber); private set
    var Purple by mutableStateOf(CURSOR_PALETTE.purple); private set
    var Cyan by mutableStateOf(CURSOR_PALETTE.cyan); private set
    var Eyebrow by mutableStateOf(CURSOR_PALETTE.eyebrow); private set
    var Idle by mutableStateOf(CURSOR_PALETTE.idle); private set
    var ActiveDock by mutableStateOf(CURSOR_PALETTE.activeDock); private set

    var PanelGradTop by mutableStateOf(CURSOR_PALETTE.panelGradTop); private set
    var PanelGradBottom by mutableStateOf(CURSOR_PALETTE.panelGradBottom); private set
    var PanelGlow by mutableStateOf(CURSOR_PALETTE.panelGlow); private set
    var PanelBorder by mutableStateOf(CURSOR_PALETTE.panelBorder); private set
    var PanelBorderTop by mutableStateOf(CURSOR_PALETTE.panelBorderTop); private set
    var SheenWhite by mutableStateOf(CURSOR_PALETTE.sheenWhite); private set
    var SheenBlue by mutableStateOf(CURSOR_PALETTE.sheenBlue); private set

    var CardBorder by mutableStateOf(CURSOR_PALETTE.cardBorder); private set
    var CardBg by mutableStateOf(CURSOR_PALETTE.cardBg); private set
    var CardGradTop by mutableStateOf(CURSOR_PALETTE.cardGradTop); private set

    var DockIdle by mutableStateOf(CURSOR_PALETTE.dockIdle); private set
    var DockActiveGradTop by mutableStateOf(CURSOR_PALETTE.dockActiveGradTop); private set
    var DockActiveGradBottom by mutableStateOf(CURSOR_PALETTE.dockActiveGradBottom); private set
    var DockActiveBorder by mutableStateOf(CURSOR_PALETTE.dockActiveBorder); private set
    var DockBg by mutableStateOf(CURSOR_PALETTE.dockBg); private set

    var LogoBg by mutableStateOf(CURSOR_PALETTE.logoBg); private set
    var LogoBorder by mutableStateOf(CURSOR_PALETTE.logoBorder); private set

    fun apply(p: LensCodePalette) {
        Text = p.text; Muted = p.muted; Blue = p.blue; Green = p.green; Red = p.red
        Amber = p.amber; Purple = p.purple; Cyan = p.cyan; Eyebrow = p.eyebrow; Idle = p.idle
        ActiveDock = p.activeDock
        PanelGradTop = p.panelGradTop; PanelGradBottom = p.panelGradBottom; PanelGlow = p.panelGlow
        PanelBorder = p.panelBorder; PanelBorderTop = p.panelBorderTop
        SheenWhite = p.sheenWhite; SheenBlue = p.sheenBlue
        CardBorder = p.cardBorder; CardBg = p.cardBg; CardGradTop = p.cardGradTop
        DockIdle = p.dockIdle; DockActiveGradTop = p.dockActiveGradTop; DockActiveGradBottom = p.dockActiveGradBottom
        DockActiveBorder = p.dockActiveBorder; DockBg = p.dockBg
        LogoBg = p.logoBg; LogoBorder = p.logoBorder
    }

    /** Per-agent accent, matching .logo.claude/.codex/.hermes/.pi — provider branding, kept theme-independent on purpose. */
    fun agentAccent(provider: String): Color = when (provider.lowercase()) {
        "claude" -> Color(0xFFFF955C)
        "codex" -> Color(0xFFE7FBFF)
        "hermes" -> Color(0xFFDDE5ED)
        "pi" -> Color(0xFFD8B4FE)
        else -> Text
    }

    /**
     * Paseo's real agent lifecycle statuses (docs/paseo-migration-plan.md,
     * Phase 3) — initializing/idle/running/error/closed. `requiresAttention`
     * is a separate flag on AgentSummary, not a status value; the agent strip
     * layers its own pulse on top of this color rather than folding it in here.
     */
    fun agentStatusColor(status: String): Color = when (status) {
        "running" -> Green
        "error" -> Red
        "initializing" -> Cyan
        "closed" -> Muted
        "idle" -> Idle
        else -> Idle
    }
}

/** Applies one theme's colors to both app chrome ([LensCodeColors]) and the code editor ([VsCodeColors]). */
fun applyAppTheme(id: ThemeId) {
    LensCodeColors.apply(lensCodePaletteFor(id))
    VsCodeColors.apply(codePaletteFor(id))
}
