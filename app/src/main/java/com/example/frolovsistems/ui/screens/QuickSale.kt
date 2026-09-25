package com.example.frolovsistems.ui.screens

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
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
import com.example.frolovsistems.ui.components.CrmFab
import kotlinx.coroutines.delay

/**
 * Кнопка быстрого заказа — того же вида, что плавающие кнопки остальных
 * экранов, только с молнией. Выезжает не вместе с контентом, а чуть позже,
 * на уже отрисованную страницу; при прокрутке вниз уезжает, как и они.
 */
@Composable
fun GalaxyFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(600)
        appeared = true
    }
    CrmFab(
        icon = Icons.Default.Bolt,
        contentDescription = "Быстро оформить заказ",
        onClick = onClick,
        visible = appeared && visible,
        modifier = modifier,
    )
}

/**
 * Быстрый заказ в три касания: клиент, что сделали, сколько. Полная карточка
 * с составом, фото и сроками — в разделе «Заказы», здесь только завести.
 * Нового клиента можно создать прямо в выборе — имя и телефон, без выхода.
 */
@Composable
fun QuickSaleDialog(
    clients: List<ClientDto>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (clientId: Long, title: String, priceKop: Long) -> Unit,
    createClient: (name: String, phone: String, onDone: (ClientDto?) -> Unit) -> Unit = { _, _, _ -> },
) {
    var picked by remember { mutableStateOf<ClientDto?>(null) }
    var picking by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var priceKop by remember { mutableStateOf(0L) }
    var newClientBusy by remember { mutableStateOf(false) }

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
        var newName by remember { mutableStateOf("") }
        var newPhone by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { if (!newClientBusy) picking = false },
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
                            if (search.isBlank()) "Список клиентов пуст" else "Никого не нашлось",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(Modifier.height(240.dp)) {
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

                    Spacer(Modifier.height(4.dp))
                    Text("Клиента нет в списке?", style = MaterialTheme.typography.labelMedium)
                    DialogField("Имя нового клиента", newName) { newName = it }
                    DialogField("Телефон", newPhone) { newPhone = it }
                    Button(
                        onClick = {
                            newClientBusy = true
                            createClient(newName.trim(), newPhone.trim()) { created ->
                                newClientBusy = false
                                if (created != null) {
                                    picked = created
                                    picking = false
                                }
                            }
                        },
                        enabled = !newClientBusy && newName.trim().isNotBlank(),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (newClientBusy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        }
                        Text("Создать и выбрать")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { picking = false }, enabled = !newClientBusy) { Text("Отмена") }
            },
        )
    }
}
