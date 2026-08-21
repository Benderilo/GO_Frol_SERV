package com.example.frolovsistems.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Режим темы, который пользователь выбирает в настройках приложения. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val LightColors = lightColorScheme(
    primary = Amber600,
    onPrimary = Color.White,
    primaryContainer = Amber100,
    onPrimaryContainer = Amber900,
    secondary = Blue600,
    onSecondary = Color.White,
    secondaryContainer = Blue100,
    onSecondaryContainer = Blue900,
    tertiary = Teal600,
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = Danger,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Amber400,
    onPrimary = Color(0xFF231600),
    primaryContainer = Amber800,
    onPrimaryContainer = Amber100,
    secondary = Blue300,
    onSecondary = Color(0xFF00214A),
    secondaryContainer = Blue800,
    onSecondaryContainer = Blue100,
    tertiary = Teal300,
    onTertiary = Color(0xFF00201A),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DangerDark,
    onError = Color(0xFF3A0908),
)

@Composable
fun FrolovTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val target = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> DarkColors
        else -> LightColors
    }

    // Плавное перетекание цветов при смене темы вместо резкого переключения.
    val scheme = target.animated()

    MaterialTheme(
        colorScheme = scheme,
        typography = FrolovTypography,
        shapes = FrolovShapes,
        content = content,
    )
}

@Composable
private fun ColorScheme.animated(): ColorScheme {
    val spec = tween<Color>(durationMillis = 420)

    @Composable
    fun Color.anim(label: String) = animateColorAsState(this, spec, label = label).value

    return copy(
        primary = primary.anim("primary"),
        onPrimary = onPrimary.anim("onPrimary"),
        primaryContainer = primaryContainer.anim("primaryContainer"),
        onPrimaryContainer = onPrimaryContainer.anim("onPrimaryContainer"),
        secondary = secondary.anim("secondary"),
        onSecondary = onSecondary.anim("onSecondary"),
        secondaryContainer = secondaryContainer.anim("secondaryContainer"),
        onSecondaryContainer = onSecondaryContainer.anim("onSecondaryContainer"),
        background = background.anim("background"),
        onBackground = onBackground.anim("onBackground"),
        surface = surface.anim("surface"),
        onSurface = onSurface.anim("onSurface"),
        surfaceVariant = surfaceVariant.anim("surfaceVariant"),
        onSurfaceVariant = onSurfaceVariant.anim("onSurfaceVariant"),
        surfaceContainer = surfaceContainer.anim("surfaceContainer"),
        surfaceContainerHigh = surfaceContainerHigh.anim("surfaceContainerHigh"),
        outline = outline.anim("outline"),
        outlineVariant = outlineVariant.anim("outlineVariant"),
    )
}
