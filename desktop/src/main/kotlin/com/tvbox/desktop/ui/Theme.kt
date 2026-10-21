package com.tvbox.desktop.ui

import androidx.compose.material.MaterialTheme
import androidx.compose.material.Typography
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * A 影院模式配色，与 Android 端 TvColors.Cinema 完全一致：
 * bg #0A0A0C / surface #141416 / accent #E8B872。
 */
object AppColors {
    val bg = Color(0xFF0A0A0C)
    val surface = Color(0xFF141416)
    val surfaceHigh = Color(0xFF1C1C1F)
    val text = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFFCFC7BD)
    val textMuted = Color(0xFF8D857C)
    val accent = Color(0xFFE8B872)
    val accentOn = Color(0xFF1A1206)
    val divider = Color(0xFF26262A)
    val danger = Color(0xFFE5484D)
    val success = Color(0xFF6FD08C)
    val warning = Color(0xFFE0A458)
    val videoBg = Color(0xFF000000)
}

object AppText {
    val brand = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Black, color = AppColors.text, letterSpacing = 1.sp)
    val hero = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, color = AppColors.text)
    val title = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = AppColors.text)
    val section = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.accent, letterSpacing = 2.sp)
    val body = TextStyle(fontSize = 13.sp, color = AppColors.textSecondary)
    val bodyStrong = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AppColors.text)
    val caption = TextStyle(fontSize = 11.sp, color = AppColors.textMuted)
    val mono = TextStyle(fontSize = 11.sp, color = AppColors.textMuted, fontFamily = FontFamily.Monospace)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = darkColors(
            primary = AppColors.accent,
            primaryVariant = AppColors.accent,
            secondary = AppColors.accent,
            background = AppColors.bg,
            surface = AppColors.surface,
            onPrimary = AppColors.accentOn,
            onSecondary = AppColors.accentOn,
            onBackground = AppColors.text,
            onSurface = AppColors.text,
            error = AppColors.danger,
        ),
        typography = Typography(
            body1 = AppText.body,
            body2 = AppText.body,
            h6 = AppText.title,
            subtitle1 = AppText.bodyStrong,
            caption = AppText.caption,
        ),
        content = content,
    )
}
