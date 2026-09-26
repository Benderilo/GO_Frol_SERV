package com.example.frolovsistems.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// Фирменные цвета совпадают с сайтом: янтарный акцент + синий.
val Amber100 = Color(0xFFFFECC7)
val Amber400 = Color(0xFFF7B94A)
val Amber600 = Color(0xFFF5A524)
val Amber800 = Color(0xFF8A5A05)
val Amber900 = Color(0xFF4A2F00)

val Blue100 = Color(0xFFDBE7FF)
val Blue300 = Color(0xFF8FB4FF)
val Blue600 = Color(0xFF2563EB)
val Blue800 = Color(0xFF1B3F92)
val Blue900 = Color(0xFF0E2358)

val Teal300 = Color(0xFF6FD9C2)
val Teal600 = Color(0xFF0E9F84)

val DangerLight = Color(0xFFDC2626)
val DangerDark = Color(0xFFFF6B6B)
val SuccessLight = Color(0xFF16A34A)
val SuccessDark = Color(0xFF4ADE80)
val WarningLight = Color(0xFFD97706)
val WarningDark = Color(0xFFFBBF24)

// Цвета статусов — по текущей теме: насыщенные на светлом фоне, светлые на
// тёмном. Тема определяется по фону, поэтому работает и при ручном выборе.
private val isDarkScheme: Boolean
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.background.luminance() < 0.5f

val Danger: Color
    @Composable @ReadOnlyComposable
    get() = if (isDarkScheme) DangerDark else DangerLight
val Success: Color
    @Composable @ReadOnlyComposable
    get() = if (isDarkScheme) SuccessDark else SuccessLight
val Warning: Color
    @Composable @ReadOnlyComposable
    get() = if (isDarkScheme) WarningDark else WarningLight

// Светлая палитра
val LightBackground = Color(0xFFF6F7FB)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceContainer = Color(0xFFF1F3F9)
val LightSurfaceContainerHigh = Color(0xFFE9ECF5)
val LightSurfaceVariant = Color(0xFFE4E7F0)
val LightOnSurface = Color(0xFF0F172A)
val LightOnSurfaceVariant = Color(0xFF5A6478)
val LightOutline = Color(0xFFB9C0D0)
val LightOutlineVariant = Color(0xFFDDE2EC)

// Тёмная палитра
val DarkBackground = Color(0xFF0B1020)
val DarkSurface = Color(0xFF101830)
val DarkSurfaceContainer = Color(0xFF141D38)
val DarkSurfaceContainerHigh = Color(0xFF1A2444)
val DarkSurfaceVariant = Color(0xFF232E52)
val DarkOnSurface = Color(0xFFEEF2FF)
val DarkOnSurfaceVariant = Color(0xFF98A3BF)
val DarkOutline = Color(0xFF3A4668)
val DarkOutlineVariant = Color(0xFF26314F)
