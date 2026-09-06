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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlinx.coroutines.delay

// Мягкий неон: холодное ядро и сиреневый ореол вокруг. Чистого белого нет
// нигде — на тёмном фоне оно режет глаз и делает вывеску «стеклянной».
private val NeonCore = Color(0xFFE8FAFF)
private val NeonBlue = Color(0xFF63D6FF)
private val NeonDeep = Color(0xFF6E7BFF)
private val SplashBg = Color(0xFF05070D)
private val SplashBgTop = Color(0xFF0C1226)

/** Название на вывеске. */
private const val TITLE = "ФРОЛОВ СИСТЕМЫ"

// Таймлайн в долях от общей длительности анимации:
// буквы зажигаются каскадом от разрядов, рама — последней.
private const val LETTERS_START = 0.05f  // первая молния
private const val LETTERS_SPREAD = 0.46f // разбег между первой и последней
private const val LETTER_SPAN = 0.3f     // сколько разгорается одна буква
private const val STRIKE_SPAN = 0.03f    // ветка добегает до буквы почти мгновенно
private const val AFTERGLOW_SPAN = 0.11f // след ветки тает после удара
private const val FRAME_START = 0.72f
private const val FRAME_SPAN = 0.22f
private const val SUBTITLE_START = 0.58f
private const val TOTAL_MS = 2600
private const val HOLD_MS = 700L

/**
 * Заставка «неоновая вывеска». По небу пробегает молния и бьёт в букву —
 * от удара буква и загорается; сам разряд гаснет не сразу, а оставляет
 * тлеющий след. Когда горят все буквы, зажигается тонкая рама.
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

        // Разряды рисуются поверх вывески: молния проходит перед буквами,
        // а не за ними, иначе удара не видно.
        Canvas(Modifier.fillMaxSize()) {
            // Пока разметка не сообщила про все буквы, рисовать нечего:
            // молния идёт по их вершинам, и половина пути была бы наугад.
            val letters = (0 until letterCount).map { targets[it] ?: return@Canvas }
                .map { it - rootOrigin }
            val sky = skyPoints(letters, letters.first().y - 110.dp.toPx(), size.width)

            drawSkyBolt(t, sky, letterCount)
            letters.forEachIndexed { index, target ->
                drawBranch(t, index, letterCount, sky[index * 2 + 2], target)
            }
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

/** Название побуквенно: каждая буква ждёт своей молнии. */
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
private fun NeonLetter(text: String, glow: Float, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = NeonCore.copy(alpha = glow),
        textAlign = TextAlign.Center,
        letterSpacing = 3.sp,
        style = MaterialTheme.typography.headlineMedium.copy(
            fontWeight = FontWeight.ExtraBold,
            shadow = Shadow(
                color = NeonBlue.copy(alpha = 0.75f * glow),
                blurRadius = 34f * glow + 6f,
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

/**
 * Ломаная молнии по небу: входит из-за левого края, проходит над каждой буквой
 * и уходит за правый. Точки над буквами стоят ровно по их середине — от них
 * потом бьют ветки вниз, и удар приходится в букву, а не рядом.
 *
 * Излом считается по номеру точки, а не случайно: иначе молния перерисовывалась
 * бы заново в каждом кадре и дрожала.
 */
private fun skyPoints(letters: List<Offset>, skyY: Float, width: Float): List<Offset> {
    val points = ArrayList<Offset>(letters.size * 2 + 2)
    // Заход и уход — от края экрана, с сильным наклоном: пологая линия
    // на полэкрана читалась бы натянутым проводом, а не разрядом.
    points += Offset(-40f, skyY - 150f + jitter(1) * 70f)
    letters.forEachIndexed { index, letter ->
        val prevX = if (index == 0) -40f else letters[index - 1].x
        // Между буквами молния заламывается сильнее, над буквой — почти ровная:
        // так видно, что она именно бежит, а не висит натянутой ниткой.
        points += Offset((prevX + letter.x) / 2f, skyY + jitter(index * 13 + 3) * 52f)
        points += Offset(letter.x, skyY + jitter(index * 17 + 5) * 16f)
    }
    points += Offset(width + 40f, skyY - 150f + jitter(97) * 70f)
    return points
}

/**
 * Молния, бегущая по небу. Голова движется по тому же расписанию, по которому
 * загораются буквы, поэтому она всегда над той буквой, в которую сейчас ударит.
 * След за головой тускнеет постепенно и держится на небе ещё какое-то время
 * после того, как разряд ушёл за край.
 */
private fun DrawScope.drawSkyBolt(t: Float, points: List<Offset>, letterCount: Int) {
    val segments = points.size - 1
    // Голова в «номерах точек»: над буквой i она в точке 2i+2. Заход начинается
    // на одну букву раньше первого удара — молния должна успеть влететь в кадр.
    val letterFrac = (t - LETTERS_START) / LETTERS_SPREAD * letterCount
    val head = (letterFrac * 2f + 2f).coerceIn(0f, segments.toFloat())
    if (head <= 0f) return

    // Ушла за край — гаснет вся целиком, но не мгновенно.
    val gone = (letterFrac - letterCount) / (letterCount * 0.6f)
    val fade = (1f - gone).coerceIn(0f, 1f).let { it * it }
    if (fade <= 0.01f) return

    drawTrail(points, head, NeonDeep, 6.dp.toPx(), 0.09f * fade)
    drawTrail(points, head, NeonBlue, 2.4f.dp.toPx(), 0.26f * fade)
    drawTrail(points, head, NeonCore, 1f.dp.toPx(), 0.6f * fade)
}

/**
 * Ветка от неба вниз, в букву: короткий разряд, вспышка в точке удара
 * и медленно тающий след.
 */
private fun DrawScope.drawBranch(t: Float, index: Int, letterCount: Int, from: Offset, to: Offset) {
    val local = t - strikeStart(index, letterCount)
    if (local < 0f || local > STRIKE_SPAN + AFTERGLOW_SPAN) return

    // Пока разряд бежит — виден его кончик; дальше горит весь след и гаснет.
    val running = (local / STRIKE_SPAN).coerceAtMost(1f)
    val fade = if (local <= STRIKE_SPAN) {
        1f
    } else {
        // Квадрат вместо линейного спада: сначала след почти не тускнеет,
        // а исчезает под конец — так он и держится на небе после вспышки.
        val left = 1f - (local - STRIKE_SPAN) / AFTERGLOW_SPAN
        left * left
    }
    if (fade <= 0.01f) return

    val points = branchPoints(index, from, to)
    // Ореол, тело и ядро: чем тоньше линия, тем она ярче.
    drawBolt(points, running, NeonDeep, 7.dp.toPx(), 0.1f * fade)
    drawBolt(points, running, NeonBlue, 3.dp.toPx(), 0.28f * fade)
    drawBolt(points, running, NeonCore, 1.1.dp.toPx(), 0.7f * fade)

    // Вспышка в точке удара: разгорается за время разряда и гаснет со следом.
    val flash = running * fade
    if (flash > 0.01f) {
        val radius = 26.dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                0f to NeonCore.copy(alpha = 0.42f * flash),
                0.45f to NeonBlue.copy(alpha = 0.2f * flash),
                1f to Color.Transparent,
                center = to,
                radius = radius,
            ),
            radius = radius,
            center = to,
        )
    }
}

/** Момент удара по букве: разряды идут слева направо, с разбегом. */
private fun strikeStart(index: Int, letterCount: Int): Float =
    LETTERS_START + (index.toFloat() / letterCount) * LETTERS_SPREAD

/** Ломаная ветки: от точки на небе [from] вниз к букве [to]. */
private fun branchPoints(index: Int, from: Offset, to: Offset): List<Offset> {
    val segments = 4
    val seed = index * 37 + 11
    val points = ArrayList<Offset>(segments + 1)
    points += from
    for (i in 1 until segments) {
        val k = i.toFloat() / segments
        // Излом сужается к концу — у самой буквы разряд почти прямой.
        val spread = (1f - k) * 34f + 5f
        points += Offset(
            from.x + (to.x - from.x) * k + jitter(seed + i * 7) * spread,
            from.y + (to.y - from.y) * k,
        )
    }
    points += to
    return points
}

/**
 * След за головой молнии: ближние к голове звенья горят в полную силу, дальние
 * тускнеют, но не до нуля — остаточное свечение и есть тот след, который тухнет
 * не сразу. Совсем в ноль его уводит уже общее затухание в [drawSkyBolt].
 */
private fun DrawScope.drawTrail(
    points: List<Offset>,
    head: Float,
    color: Color,
    width: Float,
    alpha: Float,
) {
    if (points.size < 2 || alpha <= 0.004f) return
    val tail = 7f
    for (i in 0 until points.size - 1) {
        if (i >= head) break
        val part = (head - i).coerceAtMost(1f)
        val glow = 0.3f + 0.7f * (1f - ((head - i) / tail)).coerceIn(0f, 1f)
        drawLine(
            color = color,
            start = points[i],
            end = points[i] + (points[i + 1] - points[i]) * part,
            strokeWidth = width,
            cap = StrokeCap.Round,
            alpha = alpha * glow,
        )
    }
}

/** Псевдослучайное значение −1..1 по семени: одно и то же при каждом кадре. */
private fun jitter(seed: Int): Float {
    val v = sin(seed * 12.9898f) * 43758.547f
    return (v - floor(v)) * 2f - 1f
}

/**
 * Рисует ломаную до доли [reveal] её длины: так молния «сбегает» сверху вниз,
 * а не появляется целиком.
 */
private fun DrawScope.drawBolt(
    points: List<Offset>,
    reveal: Float,
    color: Color,
    width: Float,
    alpha: Float,
) {
    if (points.size < 2 || alpha <= 0.004f) return
    val segments = points.size - 1
    val edge = reveal * segments
    for (i in 0 until segments) {
        if (i >= edge) break
        val part = (edge - i).coerceAtMost(1f)
        val from = points[i]
        val to = points[i] + (points[i + 1] - points[i]) * part
        drawLine(
            color = color,
            start = from,
            end = to,
            strokeWidth = width,
            cap = StrokeCap.Round,
            alpha = alpha,
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
