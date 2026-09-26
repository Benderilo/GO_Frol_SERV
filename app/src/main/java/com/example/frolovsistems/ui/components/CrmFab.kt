package com.example.frolovsistems.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.frolovsistems.ui.theme.GoldCore
import kotlinx.coroutines.delay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.frolovsistems.ui.theme.GoldBrush
import com.example.frolovsistems.ui.theme.GoldHot
import com.example.frolovsistems.ui.theme.GoldInk

/** Форма всех плавающих кнопок — та же, что у кнопок нижней панели. */
private val FabShape = RoundedCornerShape(18.dp)
private val FabSize = 58.dp

/**
 * Видимость плавающей кнопки от прокрутки списка: листаете вниз — кнопка
 * уезжает и не закрывает карточки, чуть назад вверх — возвращается.
 * Упёрлись в конец списка — тоже возвращается: там ей уже нечего закрывать,
 * а искать её прокруткой вверх было бы странно.
 *
 * Подключается к списку через `Modifier.nestedScroll(fabScroll.connection)`.
 */
@Stable
class FabScrollState(private val thresholdPx: Float) {
    var visible by mutableStateOf(true)
        private set

    /** Накопленный сдвиг в одну сторону: мелкая дрожь пальца кнопку не дёргает. */
    private var travel = 0f

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val dy = available.y
            if (dy == 0f) return Offset.Zero
            if ((dy < 0f) != (travel < 0f)) travel = 0f
            travel += dy
            if (travel < -thresholdPx) visible = false
            if (travel > thresholdPx) visible = true
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // Список не смог уехать ниже — значит, мы в самом конце.
            if (available.y < -0.5f) visible = true
            return Offset.Zero
        }
    }
}

@Composable
fun rememberFabScrollState(): FabScrollState {
    val threshold = with(LocalDensity.current) { 24.dp.toPx() }
    return remember(threshold) { FabScrollState(threshold) }
}

/** Задержка вылета: кнопка выезжает на уже отрисованную страницу, а не вместе с ней. */
private const val FabAppearDelayMs = 600L

/**
 * Плавающая кнопка приложения — одна на все экраны и одинаковая везде:
 * золотой скруглённый квадрат с молнией. Что именно она делает, говорит
 * маленький тёмный значок [icon] в углу («плюс», папка, загрузка…); у
 * быстрого заказа значка нет — там молния и есть действие. [busy] заменяет
 * молнию на крутилку и не пускает повторное нажатие.
 *
 * Поведение тоже общее: вылетает снизу чуть позже отрисовки экрана,
 * уезжает вниз при прокрутке списка и возвращается при прокрутке вверх.
 */
@Composable
fun CrmFab(
    icon: ImageVector?,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    busy: Boolean = false,
) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(FabAppearDelayMs)
        appeared = true
    }
    AnimatedVisibility(
        visible = appeared && visible,
        enter = slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it * 2 } + fadeIn(tween(200)),
        exit = slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { it * 2 } + fadeOut(tween(160)),
        modifier = modifier,
    ) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val press by animateFloatAsState(if (pressed) 0.93f else 1f, spring(stiffness = 900f), label = "fabPress")

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .graphicsLayer {
                    scaleX = press
                    scaleY = press
                }
                .size(FabSize)
                .shadow(12.dp, FabShape, ambientColor = GoldHot, spotColor = GoldHot)
                .background(GoldBrush, FabShape)
                .semantics { this.contentDescription = contentDescription }
                .clickable(interactionSource = interaction, indication = null) { if (!busy) onClick() },
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = GoldInk)
            } else {
                Icon(Icons.Default.Bolt, contentDescription = null, tint = GoldInk, modifier = Modifier.size(28.dp))
            }
            if (icon != null && icon != Icons.Default.Bolt) {
                // Значок действия — в правом нижнем углу, тёмная «пломба» на золоте.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(5.dp)
                        .size(20.dp)
                        .background(GoldInk, CircleShape),
                ) {
                    Icon(icon, contentDescription = null, tint = GoldCore, modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}
