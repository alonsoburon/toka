package com.toka.app.ui.theme

import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Paleta de la app. Los nombres accesibles (`Pink`, `SurfaceBg`, …) son propiedades
 * composables que leen el palette activo, así que la misma pantalla sirve para claro y
 * oscuro sin condicionales repartidos por el código.
 */
data class TokaColors(
    val pink: Color,
    val blue: Color,
    val lime: Color,
    val violet: Color,
    val amber: Color,
    val teal: Color,
    val rose: Color,
    val surfaceBg: Color,
    val cardBg: Color,
    val overdueBg: Color,
    val dueSoonBg: Color,
    val doneBg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val completeGreen: Color,
    val skipRed: Color,
    val skipBg: Color,
    val completeBg: Color,
)

val LightTokaColors = TokaColors(
    pink = Color(0xFFF472B6),
    blue = Color(0xFF60A5FA),
    lime = Color(0xFFA3E635),
    violet = Color(0xFFA78BFA),
    amber = Color(0xFFFBBF24),
    teal = Color(0xFF2DD4BF),
    rose = Color(0xFFFB7185),
    surfaceBg = Color(0xFFF8F8FC),
    cardBg = Color(0xFFFFFFFF),
    overdueBg = Color(0xFFFCE4EC),
    dueSoonBg = Color(0xFFFFF8E1),
    doneBg = Color(0xFFF1F8E9),
    textPrimary = Color(0xFF1A1A2E),
    textSecondary = Color(0xFF6B7280),
    textMuted = Color(0xFF9CA3AF),
    completeGreen = Color(0xFF10B981),
    skipRed = Color(0xFFEF4444),
    skipBg = Color(0xFFFEE2E2),
    completeBg = Color(0xFFD1FAE5),
)

val DarkTokaColors = TokaColors(
    pink = Color(0xFFF9A8D4),
    blue = Color(0xFF93C5FD),
    lime = Color(0xFFBEF264),
    violet = Color(0xFFC4B5FD),
    amber = Color(0xFFFCD34D),
    teal = Color(0xFF5EEAD4),
    rose = Color(0xFFFDA4AF),
    surfaceBg = Color(0xFF121218),
    cardBg = Color(0xFF1E1E28),
    overdueBg = Color(0xFF3B1F2B),
    dueSoonBg = Color(0xFF3A2F1A),
    doneBg = Color(0xFF1F2E1B),
    textPrimary = Color(0xFFF3F3F8),
    textSecondary = Color(0xFFB6B8C2),
    textMuted = Color(0xFF8A8C99),
    completeGreen = Color(0xFF34D399),
    skipRed = Color(0xFFF87171),
    skipBg = Color(0xFF43222A),
    completeBg = Color(0xFF123A2E),
)

val LocalTokaColors = staticCompositionLocalOf { LightTokaColors }

val Pink: Color @Composable get() = LocalTokaColors.current.pink
val Blue: Color @Composable get() = LocalTokaColors.current.blue
val Lime: Color @Composable get() = LocalTokaColors.current.lime
val Violet: Color @Composable get() = LocalTokaColors.current.violet
val Amber: Color @Composable get() = LocalTokaColors.current.amber
val Teal: Color @Composable get() = LocalTokaColors.current.teal
val Rose: Color @Composable get() = LocalTokaColors.current.rose

val SurfaceBg: Color @Composable get() = LocalTokaColors.current.surfaceBg
val CardBg: Color @Composable get() = LocalTokaColors.current.cardBg
val OverdueBg: Color @Composable get() = LocalTokaColors.current.overdueBg
val DueSoonBg: Color @Composable get() = LocalTokaColors.current.dueSoonBg
val DoneBg: Color @Composable get() = LocalTokaColors.current.doneBg

val TextPrimary: Color @Composable get() = LocalTokaColors.current.textPrimary
val TextSecondary: Color @Composable get() = LocalTokaColors.current.textSecondary
val TextMuted: Color @Composable get() = LocalTokaColors.current.textMuted

val CompleteGreen: Color @Composable get() = LocalTokaColors.current.completeGreen
val SkipRed: Color @Composable get() = LocalTokaColors.current.skipRed
val SkipBg: Color @Composable get() = LocalTokaColors.current.skipBg
val CompleteBg: Color @Composable get() = LocalTokaColors.current.completeBg

@Composable
fun personColors(): List<Color> = listOf(Pink, Blue, Lime, Violet, Amber, Teal, Rose)

@Composable
fun colorForPerson(index: Int): Color {
    val colors = personColors()
    return colors[index % colors.size]
}

/**
 * El color real de una persona viene del backend como hex ("#a78bfa"). Esto lo
 * convierte a Color de Compose; si viniera vacío o inválido, cae en gris.
 */
fun parseHexColor(hex: String?): Color {
    if (hex.isNullOrBlank()) return Color.Gray
    return try {
        Color(AndroidColor.parseColor(hex))
    } catch (_: Exception) {
        Color.Gray
    }
}
