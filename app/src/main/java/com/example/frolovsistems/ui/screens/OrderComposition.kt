package com.example.frolovsistems.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.frolovsistems.core.net.CatalogItemDto
import com.example.frolovsistems.core.net.CatalogKind
import com.example.frolovsistems.core.net.OrderItemDto
import com.example.frolovsistems.ui.components.DialogField
import com.example.frolovsistems.ui.components.MoneyField
import com.example.frolovsistems.ui.components.QuantityField
import com.example.frolovsistems.ui.components.StatusChip
import com.example.frolovsistems.ui.components.formatMoney
import com.example.frolovsistems.ui.components.formatQuantity
import com.example.frolovsistems.ui.theme.Success
import com.example.frolovsistems.ui.theme.Warning

/**
 * Состав заказа: из чего сложилась его цена. Строки добавляются выбором из
 * справочника или вписываются руками; итог по заказу считает сервер из них.
 *
 * Материалы можно списать со склада одной кнопкой. Списание отдельное, а не
 * при добавлении строки: заказ часто собирают заранее, а материал везут потом,
 * и склад не должен уходить в минус из-за наброска.
 */
@Composable
fun CompositionSection(
    orderId: Long,
    items: List<OrderItemDto>,
    catalog: List<CatalogItemDto>,
    busy: Boolean,
    writeOffMessage: String?,
    onAdd: (OrderItemDto) -> Unit,
    onUpdate: (OrderItemDto) -> Unit,
    onDelete: (OrderItemDto) -> Unit,
    onWriteOff: () -> Unit,
    onDismissWriteOff: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    var editingLine by remember { mutableStateOf<OrderItemDto?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Состав заказа", style = MaterialTheme.typography.labelMedium)
            if (items.isNotEmpty()) {
                Text(
                    formatMoney(items.sumOf { it.totalKop }),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        if (orderId == 0L) {
            Text(
                "Сохраните заказ — потом можно будет набрать состав.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        if (items.isEmpty()) {
            Text(
                "Пока пусто: цена берётся из поля выше. Добавьте позиции — и она сложится из них.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items.forEach { line ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(line.name, style = MaterialTheme.typography.bodyMedium)
                        if (line.writtenOff) {
                            StatusChip("списано", Success)
                        }
                    }
                    Text(
                        "${formatQuantity(line.qtyMilli)} ${line.unit} × ${formatMoney(line.priceKop)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(formatMoney(line.totalKop), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { editingLine = line }, enabled = !busy) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Изменить строку",
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = { onDelete(line) }, enabled = !busy) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Убрать строку",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { picking = true },
                enabled = !busy,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("Добавить")
            }
            if (items.any { it.isMaterial && !it.writtenOff }) {
                Button(
                    onClick = onWriteOff,
                    enabled = !busy,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Inventory, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Списать")
                }
            }
        }

        AnimatedVisibility(
            visible = writeOffMessage != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    writeOffMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                TextButton(onClick = onDismissWriteOff) { Text("Понятно") }
            }
        }

        // Себестоимость показываем только когда она заполнена: у заказа
        // из одних услуг закупки нет, и строка «0 ₽» сбивала бы с толку.
        val cost = items.sumOf { it.totalCostKop }
        if (cost > 0) {
            Text(
                "Закупка ${formatMoney(cost)} • заработок ${formatMoney(items.sumOf { it.totalKop } - cost)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }

    if (picking) {
        CatalogPickerDialog(
            catalog = catalog,
            onDismiss = { picking = false },
            onPick = { chosen ->
                picking = false
                onAdd(
                    OrderItemDto(
                        catalogId = chosen.id,
                        kind = chosen.kind,
                        name = chosen.name,
                        unit = chosen.unit,
                        qtyMilli = 1000,
                        priceKop = chosen.priceKop,
                        costKop = chosen.costKop,
                    ),
                )
            },
            onManual = {
                picking = false
                editingLine = OrderItemDto(orderId = orderId)
            },
        )
    }

    editingLine?.let { line ->
        LineDialog(
            line = line,
            busy = busy,
            onDismiss = { editingLine = null },
            onConfirm = { edited ->
                editingLine = null
                if (edited.id == 0L) onAdd(edited) else onUpdate(edited)
            },
        )
    }
}

/** Выбор позиции из справочника. Материалы сверху — за ними следят. */
@Composable
private fun CatalogPickerDialog(
    catalog: List<CatalogItemDto>,
    onDismiss: () -> Unit,
    onPick: (CatalogItemDto) -> Unit,
    onManual: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Что добавить") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                if (catalog.isEmpty()) {
                    Text(
                        "Справочник пуст. Можно вписать строку руками, а позиции завести " +
                            "в разделе «Склад» — дальше они будут подставляться сами.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                catalog.forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                buildString {
                                    append(CatalogKind.label(item.kind))
                                    append(" • ")
                                    append(formatMoney(item.priceKop))
                                    append(" за ")
                                    append(item.unit)
                                    if (item.isMaterial) {
                                        append(" • на складе ")
                                        append(formatQuantity(item.stockMilli))
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (item.isMaterial && item.stockMilli <= 0) Warning
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onPick(item) }) { Text("Выбрать") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onManual) { Text("Вписать руками") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Правка одной строки: количество, цена, закупка. */
@Composable
private fun LineDialog(
    line: OrderItemDto,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (OrderItemDto) -> Unit,
) {
    var draft by remember(line.id) { mutableStateOf(line) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (line.id == 0L) "Новая строка" else "Строка заказа") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (draft.writtenOff) {
                    Text(
                        "Материал по строке уже списан. После правки он спишется заново " +
                            "в новом количестве.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                // Вид у строки из справочника не меняем: он пришёл вместе
                // с позицией, и смена вида рассинхронизировала бы склад.
                if (draft.catalogId == null) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CatalogKind.all.forEach { kind ->
                            FilterChip(
                                selected = draft.kind == kind,
                                onClick = { draft = draft.copy(kind = kind) },
                                label = { Text(CatalogKind.label(kind)) },
                                enabled = !busy,
                            )
                        }
                    }
                    DialogField("Наименование", draft.name) { draft = draft.copy(name = it) }
                    DialogField("Единица измерения", draft.unit) { draft = draft.copy(unit = it) }
                } else {
                    Text(draft.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${CatalogKind.label(draft.kind)} • ${draft.unit}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                QuantityField(
                    milli = draft.qtyMilli,
                    onMilliChange = { draft = draft.copy(qtyMilli = it) },
                    label = "Количество, ${draft.unit}",
                    enabled = !busy,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                MoneyField(
                    kop = draft.priceKop,
                    onKopChange = { draft = draft.copy(priceKop = it) },
                    label = "Цена за ${draft.unit}, ₽",
                    enabled = !busy,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                MoneyField(
                    kop = draft.costKop,
                    onKopChange = { draft = draft.copy(costKop = it) },
                    label = "Закупка за ${draft.unit}, ₽",
                    enabled = !busy,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Итого", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        formatMoney(lineTotalKop(draft.qtyMilli, draft.priceKop)),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(draft) },
                enabled = !busy && draft.name.isNotBlank() && draft.qtyMilli > 0,
            ) { Text(if (line.id == 0L) "Добавить" else "Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/**
 * Тот же счёт, что и на сервере: тысячные доли на копейки дают миллионные,
 * поэтому делим на 1000 с округлением. Повторяем здесь, чтобы итог строки
 * менялся прямо при наборе, а не после сохранения.
 */
internal fun lineTotalKop(qtyMilli: Long, priceKop: Long): Long {
    val product = qtyMilli * priceKop
    return if (product < 0) -((-product + 500) / 1000) else (product + 500) / 1000
}
