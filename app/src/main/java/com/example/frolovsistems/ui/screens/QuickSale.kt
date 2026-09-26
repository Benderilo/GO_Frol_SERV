package com.example.frolovsistems.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.frolovsistems.core.net.CashMethod
import com.example.frolovsistems.core.net.ClientDto
import com.example.frolovsistems.core.net.WorkerDto
import com.example.frolovsistems.ui.components.ChoiceChips
import com.example.frolovsistems.ui.components.ClientPickerField
import com.example.frolovsistems.ui.components.CrmDialog
import com.example.frolovsistems.ui.components.CrmFab
import com.example.frolovsistems.ui.components.DatePickerField
import com.example.frolovsistems.ui.components.DialogField
import com.example.frolovsistems.ui.components.FormSection
import com.example.frolovsistems.ui.components.MoneyField
import com.example.frolovsistems.ui.components.PayMethodChips
import com.example.frolovsistems.ui.components.WorkerPicker
import com.example.frolovsistems.ui.components.formatMoney

/**
 * Кнопка быстрого заказа — общая плавающая кнопка без значка действия:
 * молния здесь и есть действие.
 */
@Composable
fun GalaxyFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    CrmFab(
        icon = null,
        contentDescription = "Быстро оформить заказ",
        onClick = onClick,
        visible = visible,
        modifier = modifier,
    )
}

/** Что собрал быстрый заказ: из этого получается заказ и, если надо, оплата. */
data class QuickOrderDraft(
    val clientId: Long,
    val title: String,
    val priceKop: Long,
    val workerId: Long? = null,
    val status: String = "new",
    val dueDate: String = "",
    /** Деньги взяты сразу — оплата на всю сумму проводится вместе с заказом. */
    val paid: Boolean = false,
    val payMethod: String = CashMethod.CASH,
)

/** Статусы, с которыми заказ может родиться: отменённый заводить незачем. */
private val quickStatuses = listOf("new" to "Новый", "in_progress" to "В работе", "done" to "Готово")

/**
 * Быстрый заказ: клиент, что сделали, сколько — и готово. По желанию
 * здесь же исполнитель, срок и оплата на месте. Состав, фото и документы —
 * в карточке заказа, сюда их не тащим, чтобы форма оставалась на один экран.
 */
@Composable
fun QuickSaleDialog(
    clients: List<ClientDto>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (QuickOrderDraft) -> Unit,
    workers: List<WorkerDto> = emptyList(),
    createClient: (name: String, phone: String, onDone: (ClientDto?) -> Unit) -> Unit = { _, _, _ -> },
) {
    var client by remember { mutableStateOf<ClientDto?>(null) }
    var title by remember { mutableStateOf("") }
    var priceKop by remember { mutableStateOf(0L) }
    var workerId by remember { mutableStateOf<Long?>(null) }
    var status by remember { mutableStateOf("new") }
    var dueDate by remember { mutableStateOf("") }
    var paid by remember { mutableStateOf(false) }
    var method by remember { mutableStateOf(CashMethod.CASH) }

    val ready = client != null && title.isNotBlank() && priceKop > 0
    CrmDialog(
        title = "Быстрый заказ",
        subtitle = "Клиент, работа и сумма — остальное потом в карточке",
        onDismiss = onDismiss,
        confirmText = if (paid && priceKop > 0) "Оформить · ${formatMoney(priceKop)}" else "Оформить",
        confirmEnabled = ready,
        busy = busy,
        onConfirm = {
            client?.let {
                onCreate(
                    QuickOrderDraft(
                        clientId = it.id,
                        title = title.trim(),
                        priceKop = priceKop,
                        workerId = workerId,
                        status = status,
                        dueDate = dueDate,
                        paid = paid,
                        payMethod = method,
                    ),
                )
            }
        },
    ) {
        ClientPickerField(
            clients = clients,
            selectedId = client?.id,
            selectedName = client?.name.orEmpty(),
            onPick = { client = it },
            enabled = !busy,
            createClient = createClient,
        )
        Column {
            DialogField("Что сделали", title) { title = it }
            MoneyField(
                kop = priceKop,
                onKopChange = { priceKop = it },
                label = "Сумма, ₽",
                modifier = Modifier.padding(bottom = 8.dp),
            )
            DatePickerField("Срок (необязательно)", dueDate) { dueDate = it }
        }

        if (workers.isNotEmpty()) {
            FormSection("Исполнитель") {
                WorkerPicker(workers = workers, selectedId = workerId, onSelect = { workerId = it?.id })
            }
        }

        FormSection("Статус") {
            ChoiceChips(options = quickStatuses, selected = status, onSelect = { status = it })
        }

        // Оплата на месте: самый частый случай у электрика — сделал и сразу взял деньги.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { paid = !paid }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Оплачено сразу", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Приход на всю сумму появится в кассе и в заказе",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = paid, onCheckedChange = { paid = it })
            }
            if (paid) PayMethodChips(selected = method, onSelect = { method = it })
        }
    }
}

@Preview
@Composable
private fun QuickSaleDialog_Preview() {
    QuickSaleDialog(
        clients = emptyList(),
        busy = false,
        onDismiss = {},
        onCreate = {},
        workers = listOf(WorkerDto(id = 1, name = "Пётр")),
    )
}
