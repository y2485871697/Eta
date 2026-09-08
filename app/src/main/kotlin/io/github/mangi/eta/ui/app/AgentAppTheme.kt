package io.github.mangi.eta.ui.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearancePaletteStyle
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.theme.platformDynamicColors

@Composable
fun AgentAppTheme(
    appearance: AppearanceSettings,
    applyInterfaceScale: Boolean,
    onResolvedDarkModeChange: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (appearance.themeMode) {
        AppearanceThemeMode.SYSTEM -> systemDark
        AppearanceThemeMode.LIGHT -> false
        AppearanceThemeMode.DARK -> true
    }
    val colorSchemeMode = when {
        !appearance.monetEnabled && appearance.themeMode == AppearanceThemeMode.LIGHT -> ColorSchemeMode.Light
        !appearance.monetEnabled && appearance.themeMode == AppearanceThemeMode.DARK -> ColorSchemeMode.Dark
        !appearance.monetEnabled -> ColorSchemeMode.System
        appearance.themeMode == AppearanceThemeMode.LIGHT -> ColorSchemeMode.MonetLight
        appearance.themeMode == AppearanceThemeMode.DARK -> ColorSchemeMode.MonetDark
        else -> ColorSchemeMode.MonetSystem
    }
    val systemSeedColor = if (
        appearance.monetEnabled && appearance.accentColor == AppearanceAccentColor.SYSTEM
    ) {
        platformDynamicColors(isDark).primary
    } else {
        null
    }
    val keyColor = when {
        !appearance.monetEnabled -> null
        appearance.accentColor == AppearanceAccentColor.SYSTEM -> systemSeedColor
        else -> appearance.accentColor.seedColor()
    }
    val controller = remember(appearance, colorSchemeMode, keyColor, isDark) {
        ThemeController(
            colorSchemeMode = colorSchemeMode,
            keyColor = keyColor,
            colorSpec = ThemeColorSpec.Spec2025,
            paletteStyle = appearance.paletteStyle.toMiuixPaletteStyle(),
            isDark = isDark,
        )
    }
    val colors = controller.currentColors()
    val themedColors = remember(colors, isDark, appearance.monetEnabled, appearance.pureBlackEnabled) {
        if (appearance.monetEnabled && appearance.pureBlackEnabled && isDark) {
            colors.copy(
                background = Color.Black,
                surface = Color.Black,
            )
        } else {
            colors
        }
    }

    LaunchedEffect(isDark) { onResolvedDarkModeChange(isDark) }

    MiuixTheme(colors = themedColors) {
        val platformDensity = LocalDensity.current
        val appDensity = remember(platformDensity, appearance.interfaceScale, applyInterfaceScale) {
            if (applyInterfaceScale) {
                Density(
                    density = platformDensity.density * appearance.interfaceScale,
                    fontScale = platformDensity.fontScale,
                )
            } else {
                platformDensity
            }
        }
        val miuixColors = MiuixTheme.colorScheme
        val materialColors = if (isDark) {
            darkColorScheme(
                primary = miuixColors.primary,
                onPrimary = miuixColors.onPrimary,
                primaryContainer = miuixColors.primaryContainer,
                onPrimaryContainer = miuixColors.onPrimaryContainer,
                secondary = miuixColors.secondary,
                onSecondary = miuixColors.onSecondary,
                secondaryContainer = miuixColors.secondaryContainer,
                onSecondaryContainer = miuixColors.onSecondaryContainer,
                background = miuixColors.background,
                onBackground = miuixColors.onBackground,
                surface = miuixColors.surface,
                onSurface = miuixColors.onSurface,
                surfaceVariant = miuixColors.surfaceVariant,
                onSurfaceVariant = miuixColors.onSurfaceSecondary,
                error = miuixColors.error,
                onError = miuixColors.onError,
                errorContainer = miuixColors.errorContainer,
                onErrorContainer = miuixColors.onErrorContainer,
                outline = miuixColors.outline,
            )
        } else {
            lightColorScheme(
                primary = miuixColors.primary,
                onPrimary = miuixColors.onPrimary,
                primaryContainer = miuixColors.primaryContainer,
                onPrimaryContainer = miuixColors.onPrimaryContainer,
                secondary = miuixColors.secondary,
                onSecondary = miuixColors.onSecondary,
                secondaryContainer = miuixColors.secondaryContainer,
                onSecondaryContainer = miuixColors.onSecondaryContainer,
                background = miuixColors.background,
                onBackground = miuixColors.onBackground,
                surface = miuixColors.surface,
                onSurface = miuixColors.onSurface,
                surfaceVariant = miuixColors.surfaceVariant,
                onSurfaceVariant = miuixColors.onSurfaceSecondary,
                error = miuixColors.error,
                onError = miuixColors.onError,
                errorContainer = miuixColors.errorContainer,
                onErrorContainer = miuixColors.onErrorContainer,
                outline = miuixColors.outline,
            )
        }

        CompositionLocalProvider(
            LocalAppearanceSettings provides appearance,
            LocalBlurEnabled provides appearance.blurEnabled,
            LocalTopBarBlurStyle provides appearance.topBarBlurStyle,
            LocalPlatformDensity provides platformDensity,
            LocalDensity provides appDensity,
        ) {
            // MaterialTheme 仅向 markdown-renderer-m3 提供与 Miuix 一致的颜色上下文。
            MaterialTheme(
                colorScheme = materialColors,
                content = content,
            )
        }
    }
}

private fun AppearancePaletteStyle.toMiuixPaletteStyle(): ThemePaletteStyle = when (this) {
    AppearancePaletteStyle.TONAL_SPOT -> ThemePaletteStyle.TonalSpot
    AppearancePaletteStyle.NEUTRAL -> ThemePaletteStyle.Neutral
    AppearancePaletteStyle.VIBRANT -> ThemePaletteStyle.Vibrant
    AppearancePaletteStyle.EXPRESSIVE -> ThemePaletteStyle.Expressive
    AppearancePaletteStyle.RAINBOW -> ThemePaletteStyle.Rainbow
    AppearancePaletteStyle.FRUIT_SALAD -> ThemePaletteStyle.FruitSalad
    AppearancePaletteStyle.MONOCHROME -> ThemePaletteStyle.Monochrome
    AppearancePaletteStyle.FIDELITY -> ThemePaletteStyle.Fidelity
    AppearancePaletteStyle.CONTENT -> ThemePaletteStyle.Content
}

private fun AppearanceAccentColor.seedColor(): Color = when (this) {
    AppearanceAccentColor.SYSTEM, AppearanceAccentColor.BLUE -> Color(0xFF3482FF)
    AppearanceAccentColor.PURPLE -> Color(0xFF6750A4)
    AppearanceAccentColor.PINK -> Color(0xFFB0006D)
    AppearanceAccentColor.RED -> Color(0xFFBA1A1A)
    AppearanceAccentColor.ORANGE -> Color(0xFFB65D00)
    AppearanceAccentColor.YELLOW -> Color(0xFF7D5700)
    AppearanceAccentColor.GREEN -> Color(0xFF006D3B)
    AppearanceAccentColor.TEAL -> Color(0xFF006A6A)
}
