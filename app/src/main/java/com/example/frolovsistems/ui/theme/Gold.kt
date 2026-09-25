package com.example.frolovsistems.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Золотая палитра — та же, что разряд на заставке: нижняя панель, колесо
// разделов и плавающие кнопки экранов воспринимаются одним фирменным
// стилем, а не набором случайных жёлтых кнопок.
internal val GoldCore = Color(0xFFFFE9A8)
internal val GoldHot = Color(0xFFFFC94D)
internal val GoldDeep = Color(0xFFB8860B)
internal val GoldInk = Color(0xFF2B1D00)

// Неон центральной кнопки панели и быстрого заказа.
internal val NeonYellow = Color(0xFFFFE066)
internal val NeonYellowDeep = Color(0xFFFFC400)
internal val NeonInk = Color(0xFF231A00)

/** Обводка и иконка неактивной кнопки — чистый белый, чуть приглушённый. */
internal val RingWhite = Color(0xFFF1F2F6)

/** Градиент золотой кнопки: светлое ребро, горячая середина, тёмный край. */
internal val GoldBrush = Brush.linearGradient(listOf(GoldCore, GoldHot, GoldDeep))
