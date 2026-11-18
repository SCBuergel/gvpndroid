package org.gnosisvpn.stub.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.gnosisvpn.stub.R

/** The prototype's color roles (~/mobile-UI tools/build.py), light and dark. */
data class Pal(
    val dark: Boolean,
    val bg: Color, val surfLo: Color, val surf: Color, val surfHi: Color, val surfTop: Color,
    val on: Color, val onV: Color, val outline: Color, val outlineV: Color,
    val primary: Color, val primaryT: Color, val onPrimary: Color, val pc: Color, val onPc: Color,
    val sc: Color, val onSc: Color, val scIc: Color,
    val err: Color, val ec: Color, val onEc: Color,
    val warn: Color, val wc: Color, val onWc: Color,
    val okT: Color, val okc: Color, val onOkc: Color,
    val lav: Color, val lavc: Color, val onLavc: Color,
    val inv: Color, val onInv: Color, val scrim: Color,
    val sync: Color, val syncHi: Color, val syncLine: Color, val syncNode: Color,
    val sigOk: Color, val track: Color,
    val sysBg: Color, val sysCard: Color,
) {
    fun container(t: Tone) = when (t) { Tone.OK -> okc; Tone.WARN -> wc; Tone.BAD -> ec; Tone.LAV -> lavc; Tone.INFO -> surf }
    fun onContainer(t: Tone) = when (t) { Tone.OK -> onOkc; Tone.WARN -> onWc; Tone.BAD -> onEc; Tone.LAV -> onLavc; Tone.INFO -> on }
    fun accent(t: Tone) = when (t) { Tone.OK -> okT; Tone.WARN -> warn; Tone.BAD -> err; Tone.LAV -> lav; Tone.INFO -> onV }
    fun signal(t: Tone?) = when (t) { Tone.OK -> sigOk; Tone.WARN -> warn; Tone.BAD -> err; else -> track }
}

private fun c(hex: String): Color {
    if (hex.startsWith("rgba")) {
        val p = hex.substringAfter("(").substringBefore(")").split(",").map { it.trim() }
        return Color(p[0].toInt(), p[1].toInt(), p[2].toInt(), (p[3].toFloat() * 255).toInt())
    }
    return Color(android.graphics.Color.parseColor(hex))
}

val LightPal = Pal(
    false,
    bg = c("#f8f8fb"), surfLo = c("#f1f1f5"), surf = c("#ebebf0"), surfHi = c("#e5e5eb"), surfTop = c("#dfdfe6"),
    on = c("#1a1b20"), onV = c("#4a4a55"), outline = c("#787782"), outlineV = c("#cbcad4"),
    primary = c("#3f6900"), primaryT = c("#3f6900"), onPrimary = c("#ffffff"), pc = c("#c3ee84"), onPc = c("#112000"),
    sc = c("#e3e0f7"), onSc = c("#1c1a33"), scIc = c("#5a49d6"),
    err = c("#ba1a1a"), ec = c("#ffdad6"), onEc = c("#410002"),
    warn = c("#8a5100"), wc = c("#ffe2bf"), onWc = c("#2b1700"),
    okT = c("#2f6b00"), okc = c("#d9f2c4"), onOkc = c("#0e2100"),
    lav = c("#5a49d6"), lavc = c("#e6e1ff"), onLavc = c("#1a0f5c"),
    inv = c("#2f3036"), onInv = c("#f1f0f7"), scrim = c("rgba(10,10,20,.36)"),
    sync = c("#6f9f08"), syncHi = c("#4f7a00"), syncLine = c("rgba(111,159,8,.24)"), syncNode = c("rgba(111,159,8,.5)"),
    sigOk = c("#6f9f08"), track = c("#dedde6"),
    sysBg = c("#f3f1f8"), sysCard = c("#ffffff"),
)

val DarkPal = Pal(
    true,
    bg = c("#121318"), surfLo = c("#1a1b21"), surf = c("#1f2026"), surfHi = c("#292a31"), surfTop = c("#34353c"),
    on = c("#e4e2ec"), onV = c("#c5c3d0"), outline = c("#8f8e99"), outlineV = c("#3f3f48"),
    primary = c("#9fd34a"), primaryT = c("#a6d95a"), onPrimary = c("#1b3500"), pc = c("#2d4d00"), onPc = c("#c3ee84"),
    sc = c("#383650"), onSc = c("#e3e0f7"), scIc = c("#c6bfff"),
    err = c("#ffb4ab"), ec = c("#5c1313"), onEc = c("#ffdad6"),
    warn = c("#f6b866"), wc = c("#3d2a0c"), onWc = c("#ffe2bf"),
    okT = c("#9fd34a"), okc = c("#1d3410"), onOkc = c("#d0f0b2"),
    lav = c("#c6bfff"), lavc = c("#2f2768"), onLavc = c("#e6e1ff"),
    inv = c("#e4e2ec"), onInv = c("#2f3036"), scrim = c("rgba(0,0,0,.55)"),
    sync = c("#8DC60C"), syncHi = c("#D4FF5A"), syncLine = c("rgba(141,198,12,.22)"), syncNode = c("rgba(141,198,12,.5)"),
    sigOk = c("#8DC60C"), track = c("#34353c"),
    sysBg = c("#121318"), sysCard = c("#1f2026"),
)

val LocalPal = staticCompositionLocalOf { LightPal }

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(res: Int, w: Int) = Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
val Inter = FontFamily(listOf(400, 500, 600, 700).map { variable(R.font.inter, it) })
val Mono = FontFamily(listOf(400, 500, 600).map { variable(R.font.jetbrains_mono, it) })
val Dots = FontFamily(listOf(variable(R.font.doto, 900)))

/** The prototype's type scale (app.css): .hl .hs .t-l .t-m .lbl-m .bm .bs .lead. */
object T {
    val hl = TextStyle(fontFamily = Inter, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em)
    val hs = TextStyle(fontFamily = Inter, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em)
    val tl = TextStyle(fontFamily = Inter, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em)
    val tm = TextStyle(fontFamily = Inter, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val lbl = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.02.em)
    val bm = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp)
    val bs = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 16.sp)
    val lead = TextStyle(fontFamily = Inter, fontSize = 16.sp, lineHeight = 24.sp)
    val note = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.sp)
    val ql = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.sp)
    val sh = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val mono = TextStyle(fontFamily = Mono, letterSpacing = (-0.02).em)
    val big = TextStyle(fontFamily = Mono, fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.03).em)
}

/** Theme mode: "system", "light" or "dark" (Demo controls → Phone). */
@Composable
fun GnosisTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val p = if (dark) DarkPal else LightPal
    val scheme = if (dark) darkColorScheme(
        primary = p.primary, onPrimary = p.onPrimary, primaryContainer = p.pc, onPrimaryContainer = p.onPc,
        secondaryContainer = p.sc, onSecondaryContainer = p.onSc, background = p.bg, onBackground = p.on,
        surface = p.bg, onSurface = p.on, surfaceVariant = p.surfHi, onSurfaceVariant = p.onV,
        surfaceContainerLowest = p.bg, surfaceContainerLow = p.surfLo, surfaceContainer = p.surf, surfaceContainerHigh = p.surfHi, surfaceContainerHighest = p.surfTop,
        outline = p.outline, outlineVariant = p.outlineV, error = p.err, errorContainer = p.ec, onErrorContainer = p.onEc,
        inverseSurface = p.inv, inverseOnSurface = p.onInv, scrim = p.scrim,
    ) else lightColorScheme(
        primary = p.primary, onPrimary = p.onPrimary, primaryContainer = p.pc, onPrimaryContainer = p.onPc,
        secondaryContainer = p.sc, onSecondaryContainer = p.onSc, background = p.bg, onBackground = p.on,
        surface = p.bg, onSurface = p.on, surfaceVariant = p.surfHi, onSurfaceVariant = p.onV,
        surfaceContainerLowest = p.bg, surfaceContainerLow = p.surfLo, surfaceContainer = p.surf, surfaceContainerHigh = p.surfHi, surfaceContainerHighest = p.surfTop,
        outline = p.outline, outlineVariant = p.outlineV, error = p.err, errorContainer = p.ec, onErrorContainer = p.onEc,
        inverseSurface = p.inv, inverseOnSurface = p.onInv, scrim = p.scrim,
    )
    val base = Typography()
    val typo = Typography(
        bodyLarge = base.bodyLarge.copy(fontFamily = Inter), bodyMedium = base.bodyMedium.copy(fontFamily = Inter),
        bodySmall = base.bodySmall.copy(fontFamily = Inter), labelLarge = base.labelLarge.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontFamily = Inter), titleLarge = base.titleLarge.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
        titleMedium = base.titleMedium.copy(fontFamily = Inter), headlineSmall = base.headlineSmall.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
    )
    // system bar icons follow the app's theme (it can differ from the system's: Demo controls → Phone)
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) androidx.compose.runtime.SideEffect {
        (view.context as? android.app.Activity)?.window?.let { w ->
            androidx.core.view.WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        }
    }
    CompositionLocalProvider(LocalPal provides p) {
        MaterialTheme(colorScheme = scheme, typography = typo, content = content)
    }
}
