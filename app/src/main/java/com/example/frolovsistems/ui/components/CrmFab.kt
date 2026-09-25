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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.frolovsistems.ui.theme.GoldBrush
import com.example.frolovsistems.ui.theme.GoldHot
import com.example.frolovsistems.ui.theme.GoldInk
import com.example.frolovsistems.ui.theme.RingWhite

/** Форма всех плавающих кнопок — та же, что у кнопок нижней панели. */
private val FabShape = RoundedCornerShape(18.dp)

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

/**
 * Плавающая кнопка приложения — одна на все экраны. Скруглённый
 * прямоугольник: основная — золотая с тёмной иконкой, [secondary] —
 * белая обводка, как у неактивных кнопок панели. С [label] кнопка
 * вытягивается и подписывается. [busy] заменяет иконку на крутилку
 * и не пускает повторное нажатие.
 *
 * Появляется и прячется сама: выезжает снизу, уезжает вниз.
 */
@Composable
fun CrmFab(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    secondary: Boolean = false,
    label: String? = null,
    busy: Boolean = false,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it * 2 } + fadeIn(tween(200)),
        exit = slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { it * 2 } + fadeOut(tween(160)),
        modifier = modifier,
    ) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val press by animateFloatAsState(if (pressed) 0.93f else 1f, spring(stiffness = 900f), label = "fabPress")
        val content = if (secondary) RingWhite else GoldInk

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .graphicsLayer {
                    scaleX = press
                    scaleY = press
                }
                .then(
                    if (secondary) {
                        Modifier
                            .shadow(6.dp, FabShape)
                            .background(Color(0xFF151A2A), FabShape)
                            .border(1.5.dp, RingWhite, FabShape)
                    } else {
                        Modifier
                            .shadow(12.dp, FabShape, ambientColor = GoldHot, spotColor = GoldHot)
                            .background(GoldBrush, FabShape)
                    },
                )
                .clickable(interactionSource = interaction, indication = null) { if (!busy) onClick() }
                .height(58.dp)
                .defaultMinSize(minWidth = 58.dp)
                .padding(horizontal = if (label != null) 20.dp else 17.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = content)
            } else {
                Icon(icon, contentDescription = contentDescription, tint = content, modifier = Modifier.size(24.dp))
            }
            if (label != null) {
                Spacer(Modifier.width(10.dp))
                Text(label, color = content, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
