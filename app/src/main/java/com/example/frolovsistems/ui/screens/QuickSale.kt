package com.example.frolovsistems.ui.screens

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.example.frolovsistems.core.net.ClientDto
import com.example.frolovsistems.ui.components.DialogField
import com.example.frolovsistems.ui.components.MoneyField
import com.example.frolovsistems.ui.components.SearchField

/** Неоново-жёлтый — фирменный цвет быстрого действия. */
private val NeonYellow = Color(0xFFFFE066)
private val NeonYellowDeep = Color(0xFFFFC400)

/**
 * Кнопка быстрого заказа: вылетает «галактикой» — из точки раскручивается
 * и оседает в кнопку, а дальше дышит жёлтым неоном, приглашая нажать.
 */
@Composable
fun GalaxyFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Появление: один раз — оборот с затуханием и пружинный рост из нуля.
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    val spin by animateFloatAsState(
        targetValue = if (appeared) 0f else 720f,
        animationSpec = tween(durationMillis = 1100, easing = CubicBezierEasing(0.16f, 0f, 0.10f, 1f)),
        label = "galaxySpin",
    )
    val born by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.05f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "galaxyBorn",
    )

    // Жизнь после появления: кольцо и тень пульсируют в такт.
    val pulse = rememberInfiniteTransition(label = "neonPulse")
    val glow by pulse.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow",
    )
    val breathe by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )

    Box(
        modifier = modifier
            .size(72.dp)
            .graphicsLayer {
                rotationZ = spin
                val scale = born * breathe
                scaleX = scale
                scaleY = scale
                alpha = born
            }
            .drawBehind {
                // Неоновая аура вместо тени: кольца дышат в такт пульсу,
                // ярче — ближе к кнопке, мягкий шлейф — шире.
                val ringAlpha = 0.25f + 0.45f * glow
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
                    radius = size.minDimension / 2f * (0.94f + 0.08f * glow),
                    style = Stroke(width = 2.5.dp.toPx()),
                )
                drawCircle(
                    color = NeonYellow.copy(alpha = 0.10f + 0.20f * glow),
                    radius = size.minDimension / 2f * (1.0f + 0.14f * glow),
                    style = Stroke(width = 6.dp.toPx()),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        FloatingActionButton(
            onClick = onClick,
            containerColor = NeonYellow,
            contentColor = Color(0xFF231A00),
            shape = CircleShape,
            modifier = Modifier.size(58.dp),
        ) {
            Icon(
                Icons.Default.Bolt,
                contentDescription = "Быстро оформить заказ",
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

/**
 * Быстрый заказ в три касания: клиент, что сделали, сколько. Полная карточка
 * с составом, фото и сроками — в разделе «Заказы», здесь только завести.
 */
@Composable
fun QuickSaleDialog(
    clients: List<ClientDto>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (clientId: Long, title: String, priceKop: Long) -> Unit,
) {
    var picked by remember { mutableStateOf<ClientDto?>(null) }
    var picking by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var priceKop by remember { mutableStateOf(0L) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Быстрый заказ") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Оформить продажу за минуту: клиент, работа и сумма. " +
                        "Состав, фото и срок добавите в карточке заказа.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedButton(
                    onClick = { picking = true },
                    enabled = !busy,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(picked?.name ?: "Выбрать клиента")
                }

                DialogField("Что сделали", title) { title = it }
                MoneyField(kop = priceKop, onKopChange = { priceKop = it }, label = "Сумма, ₽")

                if (busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Оформляем…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { picked?.let { onCreate(it.id, title, priceKop) } },
                enabled = !busy && picked != null && title.isNotBlank() && priceKop > 0,
            ) { Text("Оформить") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } },
    )

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Клиент") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchField(query = search, onQuery = { search = it }, placeholder = "Поиск по имени")
                    val found = if (search.isBlank()) {
                        clients
                    } else {
                        clients.filter { it.name.contains(search, ignoreCase = true) }
                    }
                    if (found.isEmpty()) {
                        Text(
                            "Никого не нашлось",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(Modifier.height(280.dp)) {
                            items(found.size, key = { found[it].id }) { index ->
                                val client = found[index]
                                Column(
                                    Modifier.fillMaxWidth()
                                        .clickable {
                                            picked = client
                                            picking = false
                                        }
                                        .padding(vertical = 8.dp),
                                ) {
                                    Text(client.name, style = MaterialTheme.typography.bodyMedium)
                                    if (client.phone.isNotBlank()) {
                                        Text(
                                            client.phone,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Отмена") } },
        )
    }
}
