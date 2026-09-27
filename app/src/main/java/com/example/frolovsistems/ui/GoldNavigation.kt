package com.example.frolovsistems.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import androidx.compose.ui.tooling.preview.Preview
import com.example.frolovsistems.ui.preview.PreviewScreen
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker1D
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.frolovsistems.ui.theme.GoldBrush
import com.example.frolovsistems.ui.theme.GoldCore
import com.example.frolovsistems.ui.theme.GoldDeep
import com.example.frolovsistems.ui.theme.GoldHot
import com.example.frolovsistems.ui.theme.GoldInk
import com.example.frolovsistems.ui.theme.NeonInk
import com.example.frolovsistems.ui.theme.NeonYellow
import com.example.frolovsistems.ui.theme.NeonYellowDeep
import com.example.frolovsistems.ui.theme.RingWhite
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Высота нижней панели вместе с выступом центральной кнопки. */
private val BarHeight = 116.dp

/**
 * Высота «шторки» — фона панели. Боковые кнопки лежат на ней целиком,
 * центральная чуть выступает: она же ось колеса, и колесо выходит из неё.
 * Всё, что у колеса ниже края шторки, прячется за панелью.
 */
private val CurtainHeight = 100.dp

/** Центр центральной кнопки от низа экрана. Здесь же ось колеса. */
private val BarAxis = 72.dp

/** Центр боковых кнопок — чуть ниже центральной: она выступает, они лежат на шторке. */
private val SideAxis = 64.dp

/** Подпись на тёмном затемнении — читается без привязки к теме. */
private val WheelLabel = Color(0xFFE8EAF2)

/** Тёмное «стекло» диска колеса: к центру светлее, к ободу гуще. */
private val WheelGlass = Color(0xFF2A2210)
private val WheelGlassDeep = Color(0xFF0E0B05)

/** Пункт золотого колеса. */
data class WheelTool(
    val icon: ImageVector,
    val label: String,
    val route: String,
    /** Красная отметка с числом — у «Заявок» количество новых. */
    val badge: Int = 0,
    /** Короткое пояснение под названием, пока пункт стоит наверху колеса. */
    val hint: String = "",
)

/** Ширина боковой кнопки панели. */
private val SideButtonWidth = 104.dp

/** Отступ боковых кнопок от краёв экрана. */
private val SideButtonMargin = 18.dp

/**
 * Кривая включения лампы: тлеет, вспыхивает, проваливается и загорается
 * ровно — так кнопки колеса «включаются», когда до них доходит ток.
 */
private val FlickerFrames = floatArrayOf(0.06f, 0.55f, 0.15f, 0.85f, 0.3f, 1f)

private fun flicker(p: Float): Float {
    if (p <= 0f) return FlickerFrames.first()
    if (p >= 1f) return 1f
    val seg = p * (FlickerFrames.size - 1)
    val i = seg.toInt()
    return FlickerFrames[i] + (FlickerFrames[i + 1] - FlickerFrames[i]) * (seg - i)
}

/**
 * Диск счётчика электроэнергии — так крутится центральная кнопка: всегда,
 * неспешно, а от нажатия рывком раскручивается и сама выбегает обратно
 * к обычному ходу. Угол читается только при отрисовке — вращение не
 * перестраивает экран. От него же бежит ток по проводам к боковым кнопкам.
 */
@Stable
private class MeterDisc {
    var angle by mutableFloatStateOf(0f)
    var boost = 0f

    fun kick() {
        boost = 900f
    }
}

@Composable
private fun rememberMeterDisc(fast: Boolean): MeterDisc {
    val disc = remember { MeterDisc() }
    // Пока колесо открыто, счётчик «под нагрузкой» — крутится втрое быстрее.
    val base by rememberUpdatedState(if (fast) 60f else 20f)
    LaunchedEffect(disc) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            // Пропущенные кадры (возврат из фона) не дают скачка.
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.064f)
            last = now
            disc.angle = (disc.angle + (base + disc.boost) * dt) % 360f
            if (disc.boost > 0f) {
                // Выбег: сначала быстро, дальше всё ленивее.
                disc.boost *= exp(-dt / 1.1f)
                if (disc.boost < 2f) disc.boost = 0f
            }
        }
    }
    return disc
}

/**
 * Нижняя панель: широкие кнопки по бокам — «Заказы» слева, «Аналитика»
 * справа — и неоновая кнопка по центру, ось колеса разделов. Центральная
 * крутится, как диск электросчётчика, и от неё по проводам к боковым
 * кнопкам бежит ток: чем быстрее диск, тем быстрее импульсы.
 */
@Composable
fun GoldenBottomBar(
    analyticsIcon: ImageVector,
    ordersIcon: ImageVector,
    analyticsSelected: Boolean,
    ordersSelected: Boolean,
    wheelOpen: Boolean,
    newRequests: Int,
    onAnalytics: () -> Unit,
    onWheel: () -> Unit,
    onOrders: () -> Unit,
) {
    val meter = rememberMeterDisc(fast = wheelOpen)
    // Шторка берёт цвет темы: на светлой белый контур не виден — неактивные
    // кнопки рисуются цветом текста темы, а провод — тёмным золотом.
    val ink = MaterialTheme.colorScheme.onSurface
    val lightBar = MaterialTheme.colorScheme.surfaceContainer.luminance() > 0.5f
    Box(Modifier.fillMaxWidth().height(BarHeight)) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(CurtainHeight),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            shadowElevation = 10.dp,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind { drawWires(meter.angle, lightBar) },
            ) {
                BarItem(
                    icon = ordersIcon,
                    label = "Заказы",
                    selected = ordersSelected,
                    ink = ink,
                    onClick = onOrders,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = SideButtonMargin, bottom = SideAxis - 26.dp),
                )
                BarItem(
                    icon = analyticsIcon,
                    label = "Аналитика",
                    selected = analyticsSelected,
                    ink = ink,
                    onClick = onAnalytics,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = SideButtonMargin, bottom = SideAxis - 26.dp),
                )
            }
        }
        NeonHub(
            wheelOpen = wheelOpen,
            newRequests = newRequests,
            angle = { meter.angle },
            onWheel = {
                meter.kick()
                onWheel()
            },
        )
    }
}

/**
 * Провода от центральной кнопки к боковым: тонкая золотая жила, контактные
 * площадки на концах и импульсы тока, бегущие от центра наружу. Ход
 * импульсов привязан к углу диска-счётчика — рывок кнопки гонит ток быстрее.
 */
private fun DrawScope.drawWires(meterAngle: Float, lightBar: Boolean) {
    // Провод идёт на высоте боковых кнопок — в середину их торца.
    val y = size.height - SideAxis.toPx()
    val cx = size.width / 2f
    val hubEdge = 40.dp.toPx()
    val sideEdge = (SideButtonMargin + SideButtonWidth).toPx() + 4.dp.toPx()
    val phase = meterAngle / 360f * 4f
    val pad = 2.2.dp.toPx()
    val glowR = 7.dp.toPx()
    // На светлой шторке светлое золото теряется — жила и искры темнее.
    val wire = if (lightBar) GoldDeep else GoldHot
    val spark = if (lightBar) GoldDeep else GoldCore
    for (side in intArrayOf(-1, 1)) {
        val from = cx + side * hubEdge
        val to = if (side < 0) sideEdge else size.width - sideEdge
        val len = abs(to - from)
        if (len < 12.dp.toPx()) continue
        drawLine(wire.copy(alpha = if (lightBar) 0.45f else 0.22f), Offset(from, y), Offset(to, y), strokeWidth = 1.dp.toPx())
        drawCircle(wire.copy(alpha = if (lightBar) 0.7f else 0.45f), pad, Offset(from, y))
        drawCircle(wire.copy(alpha = if (lightBar) 0.7f else 0.45f), pad, Offset(to, y))
        for (k in 0 until 2) {
            val t = ((phase + k * 0.5f) % 1f + 1f) % 1f
            val x = from + side * len * t
            // Импульс гаснет у концов — не «выпрыгивает» из площадки.
            val a = sin(PI.toFloat() * t)
            drawCircle(
                Brush.radialGradient(
                    listOf(NeonYellow.copy(alpha = 0.55f * a), Color.Transparent),
                    center = Offset(x, y),
                    radius = glowR,
                ),
                radius = glowR,
                center = Offset(x, y),
            )
            drawCircle(spark.copy(alpha = a), 1.6.dp.toPx(), Offset(x, y))
        }
    }
}

/**
 * Боковой пункт панели — широкая кнопка: иконка и под ней мелкая подпись
 * разрядкой. Неактивная — обводка цветом текста темы ([ink]), открытый
 * раздел и нажатие заливают её золотом.
 */
@Composable
private fun BarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    ink: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val gold by animateFloatAsState(
        if (selected || pressed) 1f else 0f,
        tween(340, easing = FastOutSlowInEasing),
        label = "barGold",
    )
    val press by animateFloatAsState(if (pressed) 0.93f else 1f, spring(stiffness = 900f), label = "barPress")
    RingIconButton(
        icon = icon,
        contentDescription = label,
        label = label,
        gold = { gold },
        ink = ink,
        width = SideButtonWidth,
        height = 52.dp,
        iconSize = 22.dp,
        modifier = modifier
            .graphicsLayer {
                scaleX = press
                scaleY = press
            }
            .clickable(interactionSource = interaction, indication = null) { if (!selected) onClick() },
    )
}

/**
 * Кнопка общего вида для панели и колеса — скруглённый прямоугольник:
 * обводка и иконка цвета [ink] (белые на тёмном колесе, цвет текста темы
 * на панели), а по мере [gold] (0..1) — золотая
 * заливка, тёмная иконка и мягкий ореол. С [label] под иконкой идёт
 * мелкая подпись разрядкой. Переход читается в фазе отрисовки, без
 * перекомпоновки — колесо крутится пальцем, и это важно.
 */
@Composable
private fun RingIconButton(
    icon: ImageVector,
    contentDescription: String?,
    gold: () -> Float,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    ink: Color = RingWhite,
    label: String? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(width, height)
            .drawBehind {
                val g = gold()
                val rad = this.size.minDimension / 2f
                // Углы — треть меньшей стороны: мягко, но читается как кнопка.
                val corner = CornerRadius(this.size.minDimension * 0.32f)
                if (g > 0f) {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(NeonYellow.copy(alpha = 0.38f * g), Color.Transparent),
                            center = center,
                            radius = maxOf(this.size.width, this.size.height) * 0.85f,
                        ),
                        radius = maxOf(this.size.width, this.size.height) * 0.85f,
                    )
                    drawRoundRect(
                        Brush.linearGradient(
                            listOf(GoldCore, GoldHot, GoldDeep),
                            start = Offset.Zero,
                            end = Offset(this.size.width, this.size.height),
                        ),
                        cornerRadius = corner,
                        alpha = g,
                    )
                }
                val stroke = 1.5.dp.toPx()
                drawRoundRect(
                    color = androidx.compose.ui.graphics.lerp(ink, GoldCore, g),
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(this.size.width - stroke, this.size.height - stroke),
                    cornerRadius = CornerRadius(corner.x - stroke / 2f),
                    style = Stroke(width = stroke),
                )
            },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Две иконки и две подписи внахлёст — цвета [ink] и тёмные на золоте:
            // плавный переход одной прозрачностью.
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = contentDescription,
                    tint = ink,
                    modifier = Modifier.size(iconSize).graphicsLayer { alpha = 1f - gold() },
                )
                Icon(
                    icon,
                    contentDescription = null,
                    tint = GoldInk,
                    modifier = Modifier.size(iconSize).graphicsLayer { alpha = gold() },
                )
            }
            if (label != null) {
                Spacer(Modifier.height(5.dp))
                Box(contentAlignment = Alignment.Center) {
                    val text = label.uppercase()
                    Text(
                        text,
                        color = ink,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        lineHeight = 10.sp,
                        maxLines = 1,
                        modifier = Modifier.graphicsLayer { alpha = 1f - gold() },
                    )
                    Text(
                        text,
                        color = GoldInk,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        lineHeight = 10.sp,
                        maxLines = 1,
                        modifier = Modifier.graphicsLayer { alpha = gold() },
                    )
                }
            }
        }
        content()
    }
}

/**
 * Центральная кнопка панели и ось колеса разделов: неоновый диск, который
 * крутится, как диск электросчётчика ([angle]), и медленно дышит аурой.
 */
@Composable
private fun BoxScope.NeonHub(
    wheelOpen: Boolean,
    newRequests: Int,
    angle: () -> Float,
    onWheel: () -> Unit,
) {
    // Медленное дыхание: вдох и выдох по три секунды, без толчков.
    val pulse by rememberInfiniteTransition(label = "hubBreath").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "hubBreathValue",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = BarAxis - 38.dp)
            .size(76.dp)
            .graphicsLayer {
                val scale = 1f + 0.035f * pulse
                scaleX = scale
                scaleY = scale
            }
            .drawBehind {
                // Неоновая аура вместо тени: кольца дышат вместе с кнопкой.
                val ringAlpha = 0.25f + 0.45f * (0.20f + 0.80f * pulse)
                val glowBrush = Brush.sweepGradient(
                    listOf(
                        NeonYellow.copy(alpha = ringAlpha),
                        NeonYellowDeep.copy(alpha = ringAlpha),
                        NeonYellow.copy(alpha = ringAlpha),
                    ),
                    center = Offset(size.width / 2f, size.height / 2f),
                )
                drawCircle(
                    brush = glowBrush,
                    radius = size.minDimension / 2f * (0.94f + 0.08f * pulse),
                    style = Stroke(width = 2.5.dp.toPx()),
                )
                drawCircle(
                    color = NeonYellow.copy(alpha = 0.10f + 0.20f * pulse),
                    radius = size.minDimension / 2f * (1.0f + 0.14f * pulse),
                    style = Stroke(width = 6.dp.toPx()),
                )
            }
            // Без серого квадрата-отклика: нажатие видно по рывку диска.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onWheel() },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(58.dp)
                .shadow(12.dp, CircleShape)
                .background(Brush.verticalGradient(listOf(NeonYellow, NeonYellowDeep)), CircleShape),
        ) {
            Icon(
                Icons.Default.DonutLarge,
                contentDescription = if (wheelOpen) "Закрыть колесо разделов" else "Колесо разделов",
                tint = NeonInk,
                modifier = Modifier.size(30.dp).graphicsLayer { rotationZ = angle() },
            )
        }
        if (newRequests > 0 && !wheelOpen) {
            Badge(modifier = Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = 5.dp)) {
                Text("$newRequests")
            }
        }
    }
}

/** Шаг между пунктами колеса, градусы: соседей легко различить и по ним легко попасть. */
private fun wheelStep(count: Int): Float = maxOf(36f, 260f / count.coerceAtLeast(1))

/** Угол в диапазон [-span/2; span/2): колесо бесконечное, за последним пунктом снова идёт первый. */
private fun wrapAngle(angle: Float, span: Float): Float {
    var x = (angle + span / 2f) % span
    if (x < 0f) x += span
    return x - span / 2f
}

/** Раскрутка колеса при появлении: из ступицы оно выходит на пол-оборота назад. */
private fun rollAngle(appear: Float): Float = 160f * (1f - appear)

private fun smoothstep(from: Float, to: Float, x: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * Колесо разделов: над центральной кнопкой панели раскрывается полудиск,
 * по его ободу стоят разделы. Колесо крутится пальцем — за любую точку
 * экрана, по дуге вокруг оси, — после броска докатывается по инерции и
 * встаёт так, чтобы один раздел оказался точно наверху, под золотой
 * меткой. Верхний раздел крупнее, его название крупно подписано над осью;
 * при смене верхнего раздела телефон отзывается лёгким щелчком.
 *
 * Колесо бесконечное: за последним разделом снова идёт первый, лишние
 * пункты прячутся под панелью. Открывается колесо с [startRoute] наверху;
 * если его не задали — с текущим разделом, а если и его в колесе нет —
 * с тем, что посередине списка.
 *
 * Касание раздела открывает его, касание названия над осью — верхний
 * раздел. Касание вне диска, «Назад» или кнопка в оси закрывают колесо.
 * [onSelect] срабатывает в момент выбора, [onDone] — после анимации
 * закрытия: родитель убирает колесо с экрана по [onDone].
 */
@Composable
fun GoldenWheelOverlay(
    items: List<WheelTool>,
    currentRoute: String?,
    onSelect: (String) -> Unit,
    onDone: () -> Unit,
    /** Растёт при каждом нажатии неоновой кнопки панели на открытом колесе. */
    closeRequests: Int = 0,
    /** Раздел, который встаёт наверх при открытии; нет его в колесе — текущий. */
    startRoute: String? = null,
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val count = items.size
    val step = wheelStep(count)
    val span = step * count
    val base = currentRoute?.substringBefore("?")
    val startBase = startRoute?.substringBefore("?")
    val startIndex = items.indexOfFirst { it.route.substringBefore("?") == startBase }
        .takeIf { it >= 0 }
        ?: items.indexOfFirst { it.route.substringBefore("?") == base }.takeIf { it >= 0 }
        ?: (count / 2)

    // В превью Android Studio анимаций нет — колесо рисуется сразу раскрытым
    // и включённым, иначе на картинке было бы пусто.
    val inPreview = LocalInspectionMode.current
    val appear = remember { Animatable(if (inPreview) 1f else 0f) }
    // Включение колеса 0..1: искра бежит по ободу от верхней точки в обе
    // стороны, кнопки загораются с мерцанием лампы, когда ток до них доходит.
    val ignite = remember { Animatable(if (inPreview) 1f else 0f) }
    // Поворот колеса в градусах: пункт i стоит на угле i·step − rotation
    // от верхней точки, по часовой стрелке.
    var rotation by remember { mutableFloatStateOf(startIndex * step) }
    val focused by remember(count, step) {
        derivedStateOf { Math.floorMod((rotation / step).roundToInt(), count) }
    }
    val scope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var closing by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    fun closeWheel(action: () -> Unit = {}) {
        if (closing) return
        closing = true
        action()
        scope.launch {
            appear.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
            onDone()
        }
    }

    // Докатить колесо: после броска — по инерции до ближайшего раздела,
    // по касанию раздела — точно к нему.
    fun settle(velocity: Float = 0f, target: Float? = null) {
        settleJob?.cancel()
        val aim = target ?: run {
            val thrown = rotation + (velocity * 0.18f).coerceIn(-span / 2f, span / 2f)
            (thrown / step).roundToInt() * step
        }
        settleJob = scope.launch {
            animate(
                initialValue = rotation,
                targetValue = aim,
                initialVelocity = velocity,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
            ) { value, _ -> rotation = value }
        }
    }

    fun select(index: Int) {
        if (closing) return
        val angle = wrapAngle(index * step - rotation, span)
        if (abs(angle) > 92f) return
        if (abs(angle) > 1f) settle(target = rotation + angle)
        closeWheel { onSelect(items[index].route) }
    }

    LaunchedEffect(Unit) {
        launch { appear.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 170f)) }
        // Ток подают, когда колесо уже почти раскрылось.
        delay(220)
        ignite.animateTo(1f, tween(1000, easing = LinearEasing))
    }
    // Щелчок на каждом новом разделе наверху — колесо ощущается «зубчатым».
    LaunchedEffect(Unit) {
        snapshotFlow { focused }.drop(1).collect {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }
    BackHandler(enabled = !closing) { closeWheel() }
    // Панель под колесом остаётся живой: её кнопка в центре закрывает
    // колесо, а «Заказы» и «Аналитика» уводят в раздел — колесо уходит следом.
    val initialCloseRequests = remember { closeRequests }
    LaunchedEffect(closeRequests) { if (closeRequests != initialCloseRequests) closeWheel() }
    val initialRoute = remember { currentRoute }
    LaunchedEffect(currentRoute) { if (currentRoute != initialRoute) closeWheel() }

    val ambient = rememberInfiniteTransition(label = "wheelAmbient")
    // Дыхание неона у золотой метки: мягкий вдох-выдох, без резких вспышек.
    val breath by ambient.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "wheelBreathValue",
    )
    // Ход тока: импульсы бегут по ободу и по дорожкам фона. Цикл —
    // целое число длин пути, поэтому на стыке цикла импульс не прыгает.
    val flow by ambient.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing)),
        label = "wheelFlow",
    )

    val density = LocalDensity.current
    // Карточки не заезжают под строку состояния и шапку приложения.
    val topInset = with(density) { WindowInsets.statusBars.getTop(this).toDp() } + 64.dp

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val radiusDp = minOf(maxWidth / 2 - 50.dp, 168.dp)
        val button = 54.dp
        val px = with(density) {
            object {
                val radius = radiusDp.toPx()
                val disc = radiusDp.toPx() + 52.dp.toPx()
                val cx = widthPx / 2f
                // Ось — центр неоновой кнопки панели: она и есть ступица
                // колеса. Ниже края шторки ничего не затемняем и не
                // перехватываем — кнопки панели видны и нажимаются,
                // центральная открывает и закрывает колесо.
                val cy = heightPx - BarAxis.toPx()
                val barTop = heightPx - CurtainHeight.toPx()
                val buttonHalf = button.toPx() / 2f
                val dead = 28.dp.toPx()
                // Дырка в окне колеса под центральную кнопку: её верхушка
                // выступает над шторкой и иначе срезалась бы колесом.
                val hubHole = 46.dp.toPx()
                // Карточки-превью: над колесом, между шапкой и ободом.
                val cardW = minOf(maxWidth.toPx() * 0.62f, 280.dp.toPx())
                val cardH = minOf(cardW * 1.08f, (cy - disc - topInset.toPx() - 24.dp.toPx()) * 0.92f)
                    .coerceAtLeast(150.dp.toPx())
                val cardCy = (topInset.toPx() + cy - disc) / 2f
                // Сдвиг соседней карточки на один раздел — для свайпа по карточкам.
                val cardSpacing = cardW * 2.1f * Math.toRadians(CardStepDeg.toDouble()).toFloat()
            }
        }
        // Центр масштабирования и раскрутки — ось; окно колеса кончается у края шторки.
        val origin = TransformOrigin(px.cx / widthPx, px.cy / px.barTop)

        // Угол пункта от верхней точки, по часовой стрелке.
        fun angleOf(index: Int): Float = wrapAngle(index * step - rotation, span)

        // Накал кнопки: искра доходит до неё по ободу — и лампа загорается.
        fun litOf(index: Int): Float {
            val ig = ignite.value
            if (ig >= 1f) return 1f
            val reach = abs(angleOf(index)).coerceAtMost(180f) / 180f * 0.55f
            return flicker(((ig - reach) / 0.25f).coerceIn(0f, 1f))
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(maxHeight - CurtainHeight)
                .clip(remember(px.cx, px.cy, px.hubHole) { HoleShape(Offset(px.cx, px.cy), px.hubHole) })
                .drawWithCache {
                    val traces = circuitTraces(size)
                    onDrawBehind {
                        val p = appear.value.coerceIn(0f, 1f)
                        drawRect(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.45f * p),
                                    Color.Black.copy(alpha = 0.88f * p),
                                ),
                            ),
                        )
                        drawCircuit(traces, p * ignite.value, flow, rotation)
                    }
                }
                // Касание вне диска закрывает колесо; по самому диску — нет,
                // чтобы промах мимо кнопки не выбрасывал из колеса.
                .pointerInput(px.cx, px.cy) {
                    detectTapGestures { pos ->
                        val dist = hypot(pos.x - px.cx, pos.y - px.cy)
                        if (dist > px.disc || dist < px.hubHole) closeWheel()
                    }
                }
                // Вращение пальцем за любую точку экрана: колесо поворачивается
                // на тот же угол, на который палец обошёл ось.
                .pointerInput(px.cx, px.cy, step) {
                    val tracker = VelocityTracker1D(isDataDifferential = false)
                    // Палец лёг выше колеса — листают карточки: колесо едет
                    // вслед за пальцем по горизонтали, карточка за карточкой.
                    var swipingCards = false
                    detectDragGestures(
                        onDragStart = { start ->
                            settleJob?.cancel()
                            tracker.resetTracking()
                            swipingCards = hypot(start.x - px.cx, start.y - px.cy) > px.disc
                        },
                        onDragEnd = { if (!closing) settle(velocity = tracker.calculateVelocity()) },
                        onDragCancel = { if (!closing) settle() },
                    ) { change, _ ->
                        change.consume()
                        if (closing) return@detectDragGestures
                        val prev = change.previousPosition
                        val cur = change.position
                        if (swipingCards) {
                            rotation -= (cur.x - prev.x) / px.cardSpacing * step
                            tracker.addDataPoint(change.uptimeMillis, rotation)
                            return@detectDragGestures
                        }
                        // У самой оси угол скачет от малейшего движения.
                        if (hypot(cur.x - px.cx, cur.y - px.cy) < px.dead) return@detectDragGestures
                        val a0 = atan2(prev.y - px.cy, prev.x - px.cx)
                        val a1 = atan2(cur.y - px.cy, cur.x - px.cx)
                        var delta = Math.toDegrees((a1 - a0).toDouble()).toFloat()
                        if (delta > 180f) delta -= 360f
                        if (delta < -180f) delta += 360f
                        rotation -= delta
                        tracker.addDataPoint(change.uptimeMillis, rotation)
                    }
                },
        ) {
          // Карточки-превью разделов: большая по центру дублирует верхнюю
          // кнопку колеса, соседние выглядывают по бокам. Едут вместе с
          // колесом — позиция каждой считается из того же угла поворота.
          items.forEachIndexed { index, tool ->
              PreviewCard(
                  tool = tool,
                  current = tool.route.substringBefore("?") == base,
                  width = with(density) { px.cardW.toDp() },
                  height = with(density) { px.cardH.toDp() },
                  offset = { d ->
                      val a = Math.toRadians((d * CardStepDeg).toDouble())
                      val ring = px.cardW * 2.1f
                      IntOffset(
                          (px.cx - px.cardW / 2f + ring * sin(a).toFloat()).roundToInt(),
                          (px.cardCy - px.cardH / 2f + ring * (1f - cos(a).toFloat()) * 0.55f).roundToInt(),
                      )
                  },
                  distance = { angleOf(index) / step },
                  power = { ((ignite.value - 0.25f) / 0.5f).coerceIn(0f, 1f) * appear.value.coerceIn(0f, 1f) },
                  onClick = { select(index) },
              )
          }
          // Колесо выходит из центральной кнопки: растёт из ступицы и
          // раскручивается, кнопки разделов разматываются по спирали.
          // Закрытие — то же в обратную сторону, колесо сворачивается в кнопку.
          Box(
              Modifier
                  .fillMaxSize()
                  .graphicsLayer {
                      val s = appear.value.coerceAtLeast(0f)
                      scaleX = s
                      scaleY = s
                      transformOrigin = origin
                  },
          ) {
            // Диск: стекло, обод, засечки, которые едут вместе с пальцем,
            // и золотая метка верхней точки.
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = -rollAngle(appear.value)
                        transformOrigin = origin
                    },
            ) {
                val c = Offset(px.cx, px.cy)
                val r = px.radius
                val disc = px.disc
                clipRect(bottom = px.barTop) {
                    // Ореол вокруг диска.
                    val halo = disc + 70.dp.toPx()
                    drawCircle(
                        Brush.radialGradient(
                            0.55f to GoldHot.copy(alpha = 0.16f),
                            1f to Color.Transparent,
                            center = c,
                            radius = halo,
                        ),
                        radius = halo,
                        center = c,
                    )
                    // Стекло диска.
                    drawCircle(
                        Brush.radialGradient(
                            listOf(WheelGlass.copy(alpha = 0.97f), WheelGlassDeep.copy(alpha = 0.97f)),
                            center = c,
                            radius = disc,
                        ),
                        radius = disc,
                        center = c,
                    )
                    // Свет под верхней точкой — там, куда встаёт выбранный раздел.
                    val top = Offset(px.cx, px.cy - r)
                    drawCircle(
                        Brush.radialGradient(
                            listOf(NeonYellow.copy(alpha = 0.30f * breath), Color.Transparent),
                            center = top,
                            radius = 62.dp.toPx(),
                        ),
                        radius = 62.dp.toPx(),
                        center = top,
                    )
                    // Дорожка, по которой едут кнопки, и внутреннее кольцо у оси.
                    drawCircle(
                        color = GoldHot.copy(alpha = 0.16f),
                        radius = r,
                        center = c,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                    drawCircle(
                        color = GoldHot.copy(alpha = 0.12f),
                        radius = r * 0.5f,
                        center = c,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                    // Золотой обод — провод, по которому подают ток. Пока колесо
                    // включается, он тусклый, а свет бежит по нему от верхней
                    // точки в обе стороны; впереди — искра.
                    val rimR = disc - 1.5.dp.toPx()
                    val ig = ignite.value
                    val litSweep = 180f * (ig / 0.55f).coerceIn(0f, 1f)
                    drawCircle(GoldDeep.copy(alpha = 0.45f), rimR, c, style = Stroke(width = 1.dp.toPx()))
                    if (litSweep > 0f) {
                        val rimBrush = Brush.sweepGradient(
                            listOf(GoldDeep, GoldCore, GoldHot, GoldCore, GoldDeep),
                            center = c,
                        )
                        val tl = Offset(px.cx - rimR, px.cy - rimR)
                        val sz = Size(rimR * 2f, rimR * 2f)
                        for (dir in floatArrayOf(1f, -1f)) {
                            drawArc(
                                rimBrush, 270f, dir * litSweep, false, tl, sz,
                                style = Stroke(width = 2.5.dp.toPx()),
                            )
                        }
                    }
                    if (ig in 0.001f..0.6f) {
                        val sparkR = 16.dp.toPx()
                        for (dir in floatArrayOf(1f, -1f)) {
                            val a = Math.toRadians((270f + dir * litSweep).toDouble())
                            val at = Offset(px.cx + rimR * cos(a).toFloat(), px.cy + rimR * sin(a).toFloat())
                            drawCircle(
                                Brush.radialGradient(
                                    listOf(Color.White.copy(alpha = 0.9f), NeonYellow.copy(alpha = 0.5f), Color.Transparent),
                                    center = at,
                                    radius = sparkR,
                                ),
                                radius = sparkR,
                                center = at,
                            )
                            drawCircle(Color.White, 2.5.dp.toPx(), at)
                        }
                    }
                    // Когда колесо включилось, по ободу непрерывно бежит ток:
                    // импульсы едут с колесом и сами по себе.
                    val current = ((ig - 0.6f) / 0.4f).coerceIn(0f, 1f)
                    if (current > 0f) {
                        val glowR = 8.dp.toPx()
                        for (k in 0 until 8) {
                            val deg = 270f + flow * 360f * 2f + k * 45f - rotation
                            val a = Math.toRadians(deg.toDouble())
                            val at = Offset(px.cx + rimR * cos(a).toFloat(), px.cy + rimR * sin(a).toFloat())
                            drawCircle(
                                Brush.radialGradient(
                                    listOf(NeonYellow.copy(alpha = 0.6f * current), Color.Transparent),
                                    center = at,
                                    radius = glowR,
                                ),
                                radius = glowR,
                                center = at,
                            )
                            drawCircle(GoldCore.copy(alpha = current), 1.8.dp.toPx(), at)
                        }
                    }
                    // Засечки: четыре на шаг, длинная — напротив раздела.
                    val tickStep = step / 4f
                    val outer = disc - 8.dp.toPx()
                    val jMin = kotlin.math.ceil((rotation - 90f) / tickStep).toInt()
                    val jMax = kotlin.math.floor((rotation + 90f) / tickStep).toInt()
                    for (j in jMin..jMax) {
                        val rel = j * tickStep - rotation
                        val major = Math.floorMod(j, 4) == 0
                        val fade = 1f - smoothstep(60f, 90f, abs(rel))
                        val inner = outer - (if (major) 12.dp else 6.dp).toPx()
                        val a = Math.toRadians((rel - 90f).toDouble())
                        val ca = cos(a).toFloat()
                        val sa = sin(a).toFloat()
                        drawLine(
                            color = GoldHot.copy(alpha = (if (major) 0.6f else 0.28f) * fade),
                            start = Offset(px.cx + inner * ca, px.cy + inner * sa),
                            end = Offset(px.cx + outer * ca, px.cy + outer * sa),
                            strokeWidth = (if (major) 2.dp else 1.dp).toPx(),
                        )
                    }
                }
                // Основание полудиска — тонкая золотая черта, гаснущая к краям.
                drawLine(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, GoldHot.copy(alpha = 0.55f), Color.Transparent),
                        startX = px.cx - disc,
                        endX = px.cx + disc,
                    ),
                    start = Offset(px.cx - disc, px.barTop),
                    end = Offset(px.cx + disc, px.barTop),
                    strokeWidth = 1.5.dp.toPx(),
                )
                // Метка верхней точки: неоновый уголок на ободе.
                val notchY = px.cy - disc + 1.dp.toPx()
                val w = 9.dp.toPx()
                val notch = Path().apply {
                    moveTo(px.cx - w, notchY)
                    lineTo(px.cx + w, notchY)
                    lineTo(px.cx, notchY + w * 1.1f)
                    close()
                }
                drawCircle(
                    NeonYellow.copy(alpha = 0.35f * breath),
                    radius = w * 1.6f,
                    center = Offset(px.cx, notchY + w * 0.4f),
                )
                drawPath(notch, NeonYellow)
            }

            // Название верхнего раздела над осью — сменяется, пока колесо крутится.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(62.dp)
                    .offset { IntOffset(0, (px.cy - px.radius * 0.48f - 31.dp.toPx()).roundToInt()) }
                    .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) },
            ) {
                AnimatedContent(
                    targetState = focused,
                    transitionSpec = {
                        (fadeIn(tween(160)) + slideInVertically(tween(160)) { it / 3 }) togetherWith
                            (fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 3 })
                    },
                    label = "wheelTitle",
                ) { index ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { select(index) },
                    ) {
                        Text(
                            items[index].label,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = GoldCore,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                        Text(
                            items[index].hint.ifBlank { "крутите колесо" },
                            fontSize = 12.sp,
                            color = WheelLabel.copy(alpha = 0.62f),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }

            items.forEachIndexed { index, tool ->
                WheelItem(
                    tool = tool,
                    selected = tool.route.substringBefore("?") == base,
                    button = button,
                    position = {
                        // Кнопки едут вместе с диском и докатываются тем же углом.
                        val rad = Math.toRadians((angleOf(index) - 90f - rollAngle(appear.value)).toDouble())
                        val r = px.radius
                        IntOffset(
                            (px.cx + r * cos(rad).toFloat() - px.buttonHalf).roundToInt(),
                            (px.cy + r * sin(rad).toFloat() - px.buttonHalf).roundToInt(),
                        )
                    },
                    // Близость к верхней точке 0..1 и видимость 0..1 (края уходят под панель).
                    focus = { (1f - abs(angleOf(index)) / step).coerceIn(0f, 1f) },
                    visibility = { 1f - smoothstep(58f, 94f, abs(angleOf(index))) },
                    lit = { litOf(index) },
                    onClick = { select(index) },
                )
            }
          }
        }
    }
}

/**
 * Кнопка на ободе колеса — только иконка. Всё, что меняется при вращении,
 * — положение, размер, золото, прозрачность — читается в фазах раскладки
 * и отрисовки, поэтому кручение пальцем не перестраивает экран на каждом
 * кадре. Верхняя кнопка ([focus] = 1) и нажатая — золотые, остальные —
 * белая обводка; текущий раздел отмечен золотой точкой над кнопкой.
 */
@Composable
private fun WheelItem(
    tool: WheelTool,
    selected: Boolean,
    button: androidx.compose.ui.unit.Dp,
    position: () -> IntOffset,
    focus: () -> Float,
    visibility: () -> Float,
    lit: () -> Float,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, tween(140), label = "wheelPress")

    RingIconButton(
        icon = tool.icon,
        contentDescription = tool.label,
        // Золото — только у включённой лампы: пока ток не дошёл, кнопка белая.
        gold = { maxOf(focus() * lit(), press) },
        width = button,
        height = button,
        iconSize = 26.dp,
        modifier = Modifier
            .offset { position() }
            .graphicsLayer {
                val v = visibility()
                val f = focus()
                // Верхняя кнопка заметно крупнее: к верху кнопка «наливается».
                val s = (0.86f + 0.44f * f * f) * (0.6f + 0.4f * v) * (1f - 0.08f * press)
                scaleX = s
                scaleY = s
                alpha = v * (0.12f + 0.88f * lit())
            }
            .drawBehind {
                if (selected) {
                    drawCircle(
                        GoldCore,
                        radius = 2.5.dp.toPx(),
                        center = Offset(size.width / 2f, -7.dp.toPx()),
                    )
                }
            }
            .clickable(interactionSource = interaction, indication = null) { onClick() },
    ) {
        if (tool.badge > 0) {
            Badge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-4).dp),
            ) { Text("${tool.badge}") }
        }
    }
}

/** Дорожка платы на фоне колеса: путь, его длина и направление тока по ней. */
private class CircuitTrace(
    val path: Path,
    val measure: PathMeasure,
    val length: Float,
    val points: List<Offset>,
    val dir: Float,
    val speed: Int,
)

/**
 * Дорожки печатной платы по краям экрана — электрический «цех» за колесом,
 * как фоновые шестерни у механического меню. Точки заданы долями экрана:
 * прямые участки с изломом под 45°, как разводят дорожки на платах.
 */
private fun circuitTraces(size: Size): List<CircuitTrace> {
    val specs = listOf(
        listOf(0f to 0.18f, 0.22f to 0.18f, 0.30f to 0.26f, 0.30f to 0.46f),
        listOf(1f to 0.12f, 0.74f to 0.12f, 0.66f to 0.20f, 0.66f to 0.40f),
        listOf(0.12f to 0f, 0.12f to 0.07f, 0.40f to 0.07f, 0.46f to 0.13f, 0.46f to 0.30f),
        listOf(0.86f to 0f, 0.86f to 0.30f, 0.94f to 0.38f, 1f to 0.38f),
        listOf(0f to 0.56f, 0.10f to 0.56f, 0.16f to 0.62f, 0.16f to 0.82f),
        listOf(1f to 0.64f, 0.88f to 0.64f, 0.82f to 0.70f, 0.82f to 0.84f),
        listOf(0.56f to 0f, 0.56f to 0.04f, 0.64f to 0.04f),
        listOf(0f to 0.34f, 0.07f to 0.34f, 0.12f to 0.39f),
    )
    return specs.mapIndexed { i, spec ->
        val points = spec.map { (x, y) -> Offset(x * size.width, y * size.height) }
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        CircuitTrace(path, measure, measure.length, points, if (i % 2 == 0) 1f else -1f, 1 + i % 2)
    }
}

/**
 * Плата с током: тусклые дорожки и площадки, по дорожкам бегут импульсы.
 * Ток течёт и сам ([flow]), и от колеса ([wheelAngle]): провернул колесо —
 * импульсы по дорожкам поехали, как ведомые шестерни от главной.
 */
private fun DrawScope.drawCircuit(traces: List<CircuitTrace>, power: Float, flow: Float, wheelAngle: Float) {
    if (power <= 0f) return
    val line = GoldHot.copy(alpha = 0.10f * power)
    val node = GoldHot.copy(alpha = 0.22f * power)
    val stroke = 1.2.dp.toPx()
    val glowR = 7.dp.toPx()
    val perDegree = 3.dp.toPx()
    for (t in traces) {
        drawPath(t.path, line, style = Stroke(width = stroke))
        t.points.forEach { drawCircle(node, 2.4.dp.toPx(), it, style = Stroke(width = stroke)) }
        drawCircle(node, 3.2.dp.toPx(), t.points.last())
        if (t.length <= 0f) continue
        for (k in 0 until 2) {
            val raw = flow * t.speed * t.length + wheelAngle * perDegree * t.dir + k * t.length / 2f
            val d = ((raw % t.length) + t.length) % t.length
            val at = t.measure.getPosition(if (t.dir > 0) d else t.length - d)
            drawCircle(
                Brush.radialGradient(
                    listOf(NeonYellow.copy(alpha = 0.45f * power), Color.Transparent),
                    center = at,
                    radius = glowR,
                ),
                radius = glowR,
                center = at,
            )
            drawCircle(GoldCore.copy(alpha = 0.9f * power), 1.6.dp.toPx(), at)
        }
    }
}

/** Окно колеса с круглой дыркой под центральную кнопку панели. */
private class HoleShape(private val center: Offset, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(
            Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addOval(Rect(center, radius))
            },
        )
}

/** Шаг кольца карточек: соседняя карточка — на столько градусов по кольцу. */
private const val CardStepDeg = 28f

/** Дальше этого числа шагов от центра карточки не рисуем. */
private const val VisibleCards = 2.4f

/**
 * Карточка-превью раздела. Карточки стоят на большом кольце, центр которого
 * спрятан внизу за экраном: центральная — наверху кольца, большая и
 * золотая по кромке, боковые уходят вбок, наклоняются по касательной,
 * проседают и гаснут. [distance] — сколько шагов карточка от центра (с
 * дробью, пока колесо крутится); всё движение — в фазах раскладки и
 * отрисовки. Загорается вместе с колесом ([power]) — когда подан ток.
 */
@Composable
private fun PreviewCard(
    tool: WheelTool,
    current: Boolean,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    offset: (Float) -> IntOffset,
    distance: () -> Float,
    power: () -> Float,
    onClick: () -> Unit,
) {
    // Ближние карточки поверх дальних. Порядок меняется ступенькой, а не
    // каждый кадр, — карточка перестраивается, только когда меняет место.
    val layer by remember { derivedStateOf { -(abs(distance()) * 4f).roundToInt().toFloat() } }
    val shape = RoundedCornerShape(28.dp)

    Box(
        modifier = Modifier
            .zIndex(layer)
            .offset { offset(distance()) }
            .size(width, height)
            .graphicsLayer {
                val d = distance()
                val near = 1f - minOf(abs(d), 1f)
                val edge = (1f - abs(d) / VisibleCards).coerceIn(0f, 1f)
                val pw = power()
                val s = (0.74f + 0.26f * near) * (0.92f + 0.08f * pw)
                scaleX = s
                scaleY = s
                rotationZ = d * CardStepDeg * 0.45f
                alpha = edge * edge * pw
                translationY = 36.dp.toPx() * (1f - pw)
            }
            .drawBehind {
                val near = 1f - minOf(abs(distance()), 1f)
                // Ореол у центральной карточки — свет из-под кромки.
                if (near > 0f) {
                    drawRoundRect(
                        Brush.radialGradient(
                            listOf(NeonYellow.copy(alpha = 0.22f * near), Color.Transparent),
                            center = center,
                            radius = size.maxDimension * 0.75f,
                        ),
                        topLeft = Offset(-24.dp.toPx(), -24.dp.toPx()),
                        size = Size(size.width + 48.dp.toPx(), size.height + 48.dp.toPx()),
                        cornerRadius = CornerRadius(40.dp.toPx()),
                    )
                }
            }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xF2261E0D), Color(0xF20D0B06))))
            .drawBehind {
                val near = 1f - minOf(abs(distance()), 1f)
                val stroke = 1.5.dp.toPx()
                drawRoundRect(
                    color = androidx.compose.ui.graphics.lerp(RingWhite.copy(alpha = 0.35f), GoldCore, near),
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(28.dp.toPx() - stroke / 2f),
                    style = Stroke(width = stroke),
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
    ) {
        if (current) {
            Text(
                "ОТКРЫТ СЕЙЧАС",
                color = GoldCore,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 18.dp),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 18.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(76.dp)
                    .shadow(14.dp, RoundedCornerShape(24.dp), ambientColor = GoldHot, spotColor = GoldHot)
                    .background(GoldBrush, RoundedCornerShape(24.dp)),
            ) {
                Icon(tool.icon, contentDescription = null, tint = GoldInk, modifier = Modifier.size(38.dp))
                if (tool.badge > 0) {
                    Badge(
                        modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp),
                    ) { Text("${tool.badge}") }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                tool.label,
                color = GoldCore,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            if (tool.hint.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    tool.hint,
                    color = WheelLabel.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .border(1.dp, GoldHot.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            ) {
                Text(
                    "ОТКРЫТЬ",
                    color = GoldHot,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = GoldHot,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Превью для Android Studio: нижняя панель отдельно и вместе с колесом.
// ---------------------------------------------------------------------------

private val previewWheelTools = listOf(
    WheelTool(Icons.Default.People, "Клиенты", "clients", hint = "Карточки, сметы, акты сверки"),
    WheelTool(Icons.Default.MarkEmailUnread, "Заявки", "requests", badge = 2, hint = "Новых: 2"),
    WheelTool(Icons.Default.Schedule, "Задачи", "tasks", hint = "Дела и напоминания"),
    WheelTool(Icons.Default.CalendarMonth, "Календарь", "calendar", hint = "Выезды и сроки по дням"),
    WheelTool(Icons.Default.AccountBalanceWallet, "Касса", "cash", hint = "Приход, расход, остаток"),
    WheelTool(Icons.Default.Inventory, "Склад", "catalog", hint = "Кабель, автоматы, остатки"),
    WheelTool(Icons.Default.Assessment, "Отчёты", "report", hint = "Итоги за период"),
)

@Preview(name = "Нижняя панель", showBackground = true, widthDp = 411, heightDp = 160)
@Composable
private fun GoldenBottomBarPreview() = PreviewScreen {
    Box(Modifier.fillMaxWidth().height(160.dp)) {
        Box(Modifier.align(Alignment.BottomCenter)) {
            GoldenBottomBar(
                analyticsIcon = Icons.Default.Insights,
                ordersIcon = Icons.Default.WorkOutline,
                analyticsSelected = false,
                ordersSelected = true,
                wheelOpen = false,
                newRequests = 2,
                onAnalytics = {},
                onWheel = {},
                onOrders = {},
            )
        }
    }
}

@Preview(name = "Колесо разделов", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun GoldenWheelPreview() = PreviewScreen {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.BottomCenter)) {
            GoldenBottomBar(
                analyticsIcon = Icons.Default.Insights,
                ordersIcon = Icons.Default.WorkOutline,
                analyticsSelected = false,
                ordersSelected = true,
                wheelOpen = true,
                newRequests = 2,
                onAnalytics = {},
                onWheel = {},
                onOrders = {},
            )
        }
        GoldenWheelOverlay(
            items = previewWheelTools,
            currentRoute = "calendar",
            onSelect = {},
            onDone = {},
        )
    }
}
