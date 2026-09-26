package com.example.frolovsistems.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.frolovsistems.core.net.CashMethod
import com.example.frolovsistems.core.net.ClientDto
import com.example.frolovsistems.core.net.WorkerDto

/**
 * Окно формы одного вида на всё приложение: шапка с названием и крестиком,
 * прокручиваемое тело и закреплённая снизу панель действий — главная
 * кнопка всегда на виду, сколько бы полей ни было выше.
 *
 * Шире стандартного диалога: на телефоне поля не ужимаются в колонку.
 */
@Composable
fun CrmDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    confirmEnabled: Boolean = true,
    busy: Boolean = false,
    dismissText: String = "Отмена",
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 720.dp)
                .imePadding(),
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(onClick = onDismiss, enabled = !busy) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss, enabled = !busy) { Text(dismissText) }
                    Button(onClick = onConfirm, enabled = confirmEnabled && !busy) { Text(confirmText) }
                }
            }
        }
    }
}

/** Раздел формы: мелкий заголовок разрядкой и поля под ним. */
@Composable
fun FormSection(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
        content()
    }
}

/** Ряд чипов выбора одного значения, прокручивается по горизонтали. */
@Composable
fun <T> ChoiceChips(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

/** Способ оплаты: наличные, карта, счёт. */
@Composable
fun PayMethodChips(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    ChoiceChips(
        options = CashMethod.all.map { it to CashMethod.label(it) },
        selected = selected,
        onSelect = onSelect,
        modifier = modifier,
    )
}

/**
 * Исполнитель заказа: «Сам» или один из рабочих в строю. Неактивных не
 * показываем, но уже назначенного оставляем в списке — иначе его не видно.
 */
@Composable
fun WorkerPicker(
    workers: List<WorkerDto>,
    selectedId: Long?,
    onSelect: (WorkerDto?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = workers.filter { it.active || it.id == selectedId }
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = selectedId == null,
            onClick = { onSelect(null) },
            label = { Text("Сам") },
        )
        shown.forEach { worker ->
            FilterChip(
                selected = worker.id == selectedId,
                onClick = { onSelect(worker) },
                label = { Text(worker.name) },
            )
        }
    }
}

/**
 * Поле выбора клиента: показывает выбранного, по нажатию открывает поиск.
 * Нового клиента можно завести прямо там — имя и телефон, без выхода из формы.
 *
 * [createClient] получает имя и телефон и должен вернуть созданного клиента
 * (или null при ошибке) в колбэк.
 */
@Composable
fun ClientPickerField(
    clients: List<ClientDto>,
    selectedId: Long?,
    selectedName: String,
    onPick: (ClientDto?) -> Unit,
    modifier: Modifier = Modifier,
    allowNone: Boolean = false,
    enabled: Boolean = true,
    createClient: ((name: String, phone: String, onDone: (ClientDto?) -> Unit) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val selected = clients.firstOrNull { it.id == selectedId }
    val name = selected?.name ?: selectedName.takeIf { selectedId != null }
    Surface(
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled) { open = true },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(
                    name ?: "Выбрать клиента",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (name != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val phone = selected?.phone.orEmpty()
                if (phone.isNotBlank()) {
                    Text(phone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Default.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (open) {
        ClientPickerDialog(
            clients = clients,
            allowNone = allowNone,
            createClient = createClient,
            onPick = {
                onPick(it)
                open = false
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun ClientPickerDialog(
    clients: List<ClientDto>,
    allowNone: Boolean,
    createClient: ((String, String, (ClientDto?) -> Unit) -> Unit)?,
    onPick: (ClientDto?) -> Unit,
    onDismiss: () -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newPhone by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    CrmDialog(
        title = if (adding) "Новый клиент" else "Клиент",
        onDismiss = { if (adding) adding = false else onDismiss() },
        confirmText = if (adding) "Создать и выбрать" else "Готово",
        confirmEnabled = !adding || newName.isNotBlank(),
        busy = busy,
        dismissText = if (adding) "Назад" else "Отмена",
        onConfirm = {
            if (!adding) {
                onDismiss()
            } else {
                busy = true
                createClient?.invoke(newName.trim(), newPhone.trim()) { created ->
                    busy = false
                    if (created != null) onPick(created)
                }
            }
        },
    ) {
        if (adding) {
            DialogField("Имя", newName) { newName = it }
            DialogField("Телефон", newPhone) { newPhone = it }
        } else {
            SearchField(query = search, onQuery = { search = it }, placeholder = "Имя или телефон")
            // Сразу подставляем найденное в «нового»: не нашли — одно касание до создания.
            if (createClient != null) {
                TextButton(onClick = {
                    newName = if (search.any(Char::isLetter)) search else ""
                    newPhone = if (search.any(Char::isLetter)) "" else search
                    adding = true
                }) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Новый клиент")
                }
            }
            val q = search.trim()
            val found = remember(clients, q) {
                if (q.isEmpty()) {
                    clients
                } else {
                    clients.filter { it.name.contains(q, ignoreCase = true) || it.phone.contains(q) }
                }
            }
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                if (allowNone) {
                    item {
                        PickRow(title = "Без клиента", subtitle = "") { onPick(null) }
                    }
                }
                items(found, key = { it.id }) { client ->
                    PickRow(title = client.name, subtitle = client.phone) { onPick(client) }
                }
                if (found.isEmpty()) {
                    item {
                        Text(
                            if (q.isEmpty()) "Список клиентов пуст" else "Никого не нашлось",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PickRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Опасная зона внизу формы: всё, что удаляет данные, собрано здесь, в
 * красной рамке, и срабатывает только после двух предупреждений.
 * Приложением пользуются вдвоём — лишний шаг дешевле потерянного заказа.
 */
@Composable
fun DangerZone(
    actionLabel: String,
    what: String,
    consequences: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var confirming by remember { mutableStateOf(false) }
    val danger = MaterialTheme.colorScheme.error
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(danger.copy(alpha = 0.06f))
            .border(1.dp, danger.copy(alpha = 0.45f), MaterialTheme.shapes.small)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.WarningAmber, contentDescription = null, tint = danger, modifier = Modifier.size(18.dp))
            Text("ОПАСНАЯ ЗОНА", style = MaterialTheme.typography.labelMedium, color = danger, fontWeight = FontWeight.Bold)
        }
        Text(consequences, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(
            onClick = { confirming = true },
            enabled = enabled,
            border = BorderStroke(1.dp, danger),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = danger),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(actionLabel) }
    }
    if (confirming) {
        DoubleConfirmDialog(
            what = what,
            consequences = consequences,
            onConfirm = {
                confirming = false
                onConfirm()
            },
            onDismiss = { confirming = false },
        )
    }
}

/**
 * Удаление в два шага. Первый — обычный вопрос «удалить?». Второй —
 * последнее предупреждение: кнопка оживает только после отметки
 * «понимаю, что это навсегда». Случайно пройти оба шага не выйдет.
 */
@Composable
fun DoubleConfirmDialog(
    what: String,
    consequences: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var step by remember { mutableIntStateOf(1) }
    var understood by remember { mutableStateOf(false) }
    val danger = MaterialTheme.colorScheme.error
    val dangerButton = ButtonDefaults.buttonColors(containerColor = danger, contentColor = MaterialTheme.colorScheme.onError)

    if (step == 1) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Default.WarningAmber, contentDescription = null, tint = danger) },
            title = { Text("Удалить $what?") },
            text = { Text(consequences) },
            confirmButton = {
                Button(onClick = { step = 2 }, colors = dangerButton) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Default.WarningAmber, contentDescription = null, tint = danger) },
            title = { Text("Точно удалить?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Это последнее предупреждение: $what исчезнет безвозвратно, отменить удаление нельзя.")
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { understood = !understood },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = understood, onCheckedChange = { understood = it })
                        Text("Понимаю, удалить навсегда", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Button(onClick = onConfirm, enabled = understood, colors = dangerButton) { Text("Удалить навсегда") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        )
    }
}

/** Строка «подпись — значение» для сводок в формах. */
@Composable
fun SummaryRow(label: String, value: String, modifier: Modifier = Modifier, emphasize: Boolean = false) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasize) FontWeight.Bold else null,
        )
    }
}
