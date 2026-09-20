package com.example.frolovsistems.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.delay

// Мягкий неон: холодное ядро и сиреневый ореол вокруг. Чистого белого нет
// нигде — на тёмном фоне оно режет глаз и делает вывеску «стеклянной».
private val NeonCore = Color(0xFFE8FAFF)
private val NeonBlue = Color(0xFF63D6FF)
private val NeonDeep = Color(0xFF6E7BFF)
private val SplashBg = Color(0xFF05070D)
private val SplashBgTop = Color(0xFF0C1226)

// Золото удара насквозь: горячее ядро и янтарный ореол.
private val GoldCore = Color(0xFFFFE9A8)
private val GoldHot = Color(0xFFFFC94D)
private val GoldDeep = Color(0xFFB8860B)

/** Название на вывеске. */
private const val TITLE = "ФРОЛОВ СИСТЕМЫ"

// Таймлайн в долях от общей длительности анимации.
// Одна молния — золотой разряд: резко простреливает название насквозь
// по прямой, и каждая буква зажигается от прохождения сквозь неё:
// золотая вспышка, затем ровный холодный неон. Рама — последней.
private const val LETTERS_START = 0.12f  // выход золотого разряда
private const val LETTERS_SPREAD = 0.14f // прокол сквозь все буквы — резко
private const val LETTER_SPAN = 0.30f    // розжиг буквы от вспышки до ровного неона
private const val STRIKE_SPAN = 0.03f    // мгновение прохождения сквозь букву
private const val GOLD_HOLD_SPAN = 0.16f // золото на буквах остывает к неону
private const val FRAME_START = 0.72f
private const val FRAME_SPAN = 0.22f
private const val SUBTITLE_START = 0.58f
private const val TOTAL_MS = 2600
private const val HOLD_MS = 700L

/**
 * Заставка «неоновая вывеска». Единственная молния — золотой разряд:
 * он резко простреливает название насквозь по одной прямой, ровно посередине
 * букв. Каждая буква вспыхивает золотом в момент прохождения сквозь неё
 * и остывает до холодного неона; когда всё горит, зажигается тонкая рама.
 *
 * Свечение собрано из слоёв теней и полупрозрачных обводок, без Modifier.blur:
 * так вывеска выглядит одинаково на любой версии Android.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    // Единые часы 0..1: по ним каждый элемент считает свою яркость.
    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        clock.animateTo(1f, tween(TOTAL_MS, easing = LinearEasing))
        delay(HOLD_MS)
        onFinished()
    }
    val t = clock.value

    // Куда бить молниям: центр каждой буквы. Храним координаты окна и вычитаем
    // начало заставки уже при отрисовке — порядок, в котором разметка сообщает
    // о себе, тогда не важен. До первой разметки разряды просто не рисуются.
    val letterCount = remember { TITLE.count { it != ' ' } }
    val targets = remember { mutableStateMapOf<Int, Offset>() }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .background(SplashBg)
            .onGloballyPositioned { rootOrigin = it.positionInWindow() },
        contentAlignment = Alignment.Center,
    ) {
        // Ночное небо: чуть светлее сверху, откуда приходят разряды.
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.verticalGradient(
                    0f to SplashBgTop,
                    0.6f to SplashBg,
                    startY = 0f,
                    endY = size.height,
                ),
            )
        }

        // Рама-«контур вывески»: три обводки разной толщины и прозрачности
        // дают неоновое свечение. Тонкие — вывеска, а не рамка для картины.
        val frame = phase(t, FRAME_START, FRAME_SPAN)
        Box(
            Modifier
                .padding(horizontal = 32.dp)
                .drawBehind { drawFrame(frame) }
                .padding(horizontal = 40.dp, vertical = 44.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // «ИП» — маленькая лампа: зажигается с первой буквой.
                NeonText(
                    text = "ИП",
                    glow = letterGlow(t, 0, letterCount),
                    style = MaterialTheme.typography.titleMedium,
                    spacing = 6.sp,
                )
                Spacer(Modifier.height(10.dp))
                NeonLineTitle(
                    t = t,
                    letterCount = letterCount,
                    onLetterPlaced = { index, center -> targets[index] = center },
                )
                Spacer(Modifier.height(14.dp))
                NeonText(
                    text = "электромонтаж • Балаково",
                    glow = phase(t, SUBTITLE_START, 0.3f) * 0.8f,
                    style = MaterialTheme.typography.bodyMedium,
                    spacing = 1.sp,
                )
            }
        }

        // Разряд рисуется поверх вывески: молния проходит перед буквами,
        // иначе прокола не видно.
        Canvas(Modifier.fillMaxSize()) {
            // Пока разметка не сообщила про все буквы, рисовать нечего:
            // линия идёт ровно по их середине, без неё — наугад.
            val letters = (0 until letterCount).map { targets[it] ?: return@Canvas }
                .map { it - rootOrigin }

            drawGoldStrike(t, letters, size.width)
        }

        // Тонкая линия-подсветка снизу: вспыхивает вместе с рамой.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp)
                .fillMaxWidth(0.45f)
                .height(1.dp)
                .drawBehind { drawRect(NeonBlue.copy(alpha = 0.3f * frame)) },
        )
    }
}

/** Название побуквенно: каждая буква ждёт своей молнии — и золотого удара. */
@Composable
private fun NeonLineTitle(
    t: Float,
    letterCount: Int,
    onLetterPlaced: (Int, Offset) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        var lit = 0
        TITLE.forEach { ch ->
            if (ch == ' ') {
                Spacer(Modifier.width(16.dp))
            } else {
                val index = lit
                NeonLetter(
                    text = ch.toString(),
                    glow = letterGlow(t, index, letterCount),
                    gold = letterGold(t, index, letterCount),
                    modifier = Modifier.onGloballyPositioned { coords ->
                        val size = coords.size
                        onLetterPlaced(
                            index,
                            coords.positionInWindow() + Offset(size.width / 2f, size.height / 2f),
                        )
                    },
                )
                lit++
            }
        }
    }
}

@Composable
private fun NeonLetter(text: String, glow: Float, gold: Float = 0f, modifier: Modifier = Modifier) {
    // Обычная лампа — холодный неон; в золотой удар — горячее золото.
    // Затухание тоже плавное: цвет и тень едут вместе, без «мигалки».
    val letterColor = lerp(NeonCore, GoldCore, gold).copy(alpha = glow)
    val glowColor = lerp(NeonBlue, GoldHot, gold)
    Text(
        text = text,
        modifier = modifier,
        color = letterColor,
        textAlign = TextAlign.Center,
        letterSpacing = 3.sp,
        style = MaterialTheme.typography.headlineMedium.copy(
            fontWeight = FontWeight.ExtraBold,
            shadow = Shadow(
                color = glowColor.copy(alpha = 0.75f * glow + 0.2f * gold),
                blurRadius = 34f * glow + 6f + 18f * gold,
            ),
        ),
    )
}

/** Текст с неоновым свечением: светлое ядро поверх мягкого ореола. */
@Composable
private fun NeonText(
    text: String,
    glow: Float,
    style: TextStyle,
    spacing: TextUnit,
) {
    Text(
        text = text,
        color = NeonCore.copy(alpha = glow),
        textAlign = TextAlign.Center,
        letterSpacing = spacing,
        style = style.copy(
            fontWeight = FontWeight.Bold,
            shadow = Shadow(
                color = NeonBlue.copy(alpha = 0.7f * glow),
                blurRadius = 28f * glow + 4f,
            ),
        ),
    )
}

/**
 * Рама вывески: широкий тусклый ореол, средняя линия и тонкое ядро.
 * Толщины подобраны так, чтобы читалась именно светящаяся трубка —
 * жирная обводка выглядит нарисованной рамкой, а не неоном.
 */
private fun DrawScope.drawFrame(glow: Float) {
    if (glow <= 0f) return
    val inset = 10.dp.toPx()
    val radius = CornerRadius(26.dp.toPx())
    val topLeft = Offset(inset, inset)
    val rectSize = Size(size.width - inset * 2, size.height - inset * 2)

    drawRoundRect(
        color = NeonDeep,
        topLeft = topLeft,
        size = rectSize,
        cornerRadius = radius,
        alpha = 0.16f * glow,
        style = Stroke(width = 14.dp.toPx()),
    )
    drawRoundRect(
        color = NeonBlue,
        topLeft = topLeft,
        size = rectSize,
        cornerRadius = radius,
        alpha = 0.34f * glow,
        style = Stroke(width = 4.dp.toPx()),
    )
    drawRoundRect(
        color = NeonCore,
        topLeft = topLeft,
        size = rectSize,
        cornerRadius = radius,
        alpha = 0.8f * glow,
        style = Stroke(width = 1.1.dp.toPx()),
    )
}

/** Момент прохождения разряда сквозь букву: слева направо, с разбегом. */
private fun strikeStart(index: Int, letterCount: Int): Float =
    LETTERS_START + (index.toFloat() / letterCount) * LETTERS_SPREAD

/**
 * Сколько золота на букве. Золотой разряд несётся по прямой слева направо,
 * и буква вспыхивает золотом ровно тогда, когда он проходит сквозь неё;
 * дальше золото остывает обратно к холодному неону.
 */
private fun letterGold(t: Float, index: Int, letterCount: Int): Float {
    val pass = strikeStart(index, letterCount)
    // Прокол — мгновенный, остывание — плавное.
    val up = phase(t, pass, STRIKE_SPAN)
    if (up <= 0f) return 0f
    val down = 1f - phase(t, pass + STRIKE_SPAN, GOLD_HOLD_SPAN)
    return (up * down).coerceIn(0f, 1f)
}

/**
 * Золотой удар насквозь: одна прямая линия ровно по середине букв, от края
 * до края. Резко влетает, ведёт за собой горячую голову, и вся линия быстро
 * остывает — быстрее, чем тает золото на буквах.
 */
private fun DrawScope.drawGoldStrike(t: Float, letters: List<Offset>, width: Float) {
    val head = phase(t, LETTERS_START, LETTERS_SPREAD)
    if (head <= 0f) return
    // Линия гаснет быстрее, чем остывают буквы: след прокола — короче.
    val fade = 1f - phase(t, LETTERS_START + LETTERS_SPREAD, GOLD_HOLD_SPAN * 0.55f)
    if (fade <= 0.01f) return

    val y = letters.map { it.y }.average().toFloat()
    val x0 = -40f
    val x1 = width + 40f
    val headX = x0 + (x1 - x0) * head

    fun layer(color: Color, stroke: Float, alpha: Float) {
        if (alpha * fade <= 0.01f) return
        drawLine(
            color = color,
            start = Offset(x0, y),
            end = Offset(headX, y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
            alpha = alpha * fade,
        )
    }

    // Ореол, тело и горячее ядро — как у небесной молнии, но золотом.
    layer(GoldDeep, 12.dp.toPx(), 0.10f)
    layer(GoldHot, 4f.dp.toPx(), 0.30f)
    layer(GoldCore, 1.4f.dp.toPx(), 0.75f)

    // Голова разряда: слепящая точка, пока линия ещё бежит.
    if (head < 1f) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to GoldCore.copy(alpha = 0.9f * fade),
                0.4f to GoldHot.copy(alpha = 0.35f * fade),
                1f to Color.Transparent,
                center = Offset(headX, y),
                radius = 30.dp.toPx(),
            ),
            radius = 30.dp.toPx(),
            center = Offset(headX, y),
        )
    }
}

/** Линейная фаза элемента на общем таймлайне: 0 до старта, 1 после конца. */
private fun phase(t: Float, start: Float, span: Float): Float =
    ((t - start) / span).coerceIn(0f, 1f)

/**
 * Яркость буквы. Свет приходит с молнией: до удара буква тёмная, в момент
 * удара — вспышка ярче ровного свечения, потом лампа успокаивается и чуть
 * дышит. Прежнего дребезга старой люминесцентной трубки тут нет: он спорил
 * с мягким свечением и мешал читать название.
 */
private fun letterGlow(t: Float, index: Int, letterCount: Int): Float {
    // Свет появляется, когда разряд добежал до буквы, а не когда он вышел.
    val start = strikeStart(index, letterCount) + STRIKE_SPAN
    val p = phase(t, start, LETTER_SPAN)
    if (p <= 0f) return 0f

    // Вспышка удара: короткая, поверх основного розжига.
    val strike = 1f - phase(t, start, STRIKE_SPAN * 1.6f)
    // Розжиг: быстро до трети, дальше плавно до ровного свечения.
    val warmUp = if (p < 0.25f) p / 0.25f * 0.55f else 0.55f + (p - 0.25f) / 0.75f * 0.45f
    // Еле заметное дыхание уже зажжённой лампы.
    val breath = 1f - 0.05f * abs(sin((t - start) * 9f))

    return (warmUp * breath + strike * strike * 0.35f).coerceIn(0f, 1f)
}
