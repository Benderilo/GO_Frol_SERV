package com.example.frolovsistems.ui.screens

import com.example.frolovsistems.ui.components.DoubleConfirmDialog
import androidx.compose.ui.text.style.TextOverflow
import com.example.frolovsistems.ui.components.ListWindow
import com.example.frolovsistems.ui.components.CompactCardPadding
import com.example.frolovsistems.ui.components.ListItemSpacing
import com.example.frolovsistems.ui.components.ListContentPadding
import com.example.frolovsistems.ui.components.ListHeader
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frolovsistems.core.net.CashDirection
import com.example.frolovsistems.core.net.CashMethod
import com.example.frolovsistems.core.net.CashOpBody
import com.example.frolovsistems.core.net.CashOpDto
import com.example.frolovsistems.data.CrmRepository
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.components.DialogField
import com.example.frolovsistems.ui.components.EmptyState
import com.example.frolovsistems.ui.components.ErrorBanner
import com.example.frolovsistems.ui.components.HintBlock
import com.example.frolovsistems.ui.components.LoadingBox
import com.example.frolovsistems.ui.components.MoneyField
import com.example.frolovsistems.ui.components.SoftCard
import com.example.frolovsistems.ui.components.StatusChip
import com.example.frolovsistems.ui.components.formatMoney
import com.example.frolovsistems.ui.components.formatShortDate
import com.example.frolovsistems.ui.theme.Success
import com.example.frolovsistems.ui.theme.Warning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.example.frolovsistems.ui.preview.PreviewScreen
import com.example.frolovsistems.ui.preview.PreviewData

data class CashUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val items: List<CashOpDto> = emptyList(),
    val incomeKop: Long = 0,
    val expenseKop: Long = 0,
    val balanceKop: Long = 0,
    /** Балансы счетов хранения: наличные, карта, расчётный счёт. */
    val accounts: List<com.example.frolovsistems.core.net.AccountBalanceDto> = emptyList(),
    val period: Period = Period.thisMonth(),
    /** Пусто — оба направления. */
    val direction: String = "",
    val error: String? = null,
)

/**
 * Действия экрана. Их выполняет [CashViewModel]; превью в Android Studio
 * передаёт пустую реализацию, и разметка рисуется без сети и базы.
 */
interface CashActions {
    fun refresh() {}
    fun setPeriod(period: Period) {}
    fun setDirection(direction: String) {}
    fun add(body: CashOpBody) {}
    fun transfer(body: com.example.frolovsistems.core.net.TransferBody) {}
    fun delete(op: CashOpDto) {}
}

class CashViewModel(
    private val crm: CrmRepository = ServiceLocator.crm,
) : ViewModel(), CashActions {

    private val _state = MutableStateFlow(CashUiState())
    val state: StateFlow<CashUiState> = _state.asStateFlow()

    init { refresh() }

    override fun refresh() {
        val s = _state.value
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            crm.cash(s.period.from, s.period.to, s.direction)
                .onSuccess { data ->
                    _state.update {
                        it.copy(
                            loading = false,
                            items = data.items,
                            incomeKop = data.incomeKop,
                            expenseKop = data.expenseKop,
                            balanceKop = data.balanceKop,
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
            crm.accounts()
                .onSuccess { data -> _state.update { it.copy(accounts = data.items) } }
        }
    }

    override fun setPeriod(period: Period) {
        _state.update { it.copy(period = period) }
        refresh()
    }

    override fun setDirection(direction: String) {
        _state.update { it.copy(direction = direction) }
        refresh()
    }

    override fun add(body: CashOpBody) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            crm.addCashOp(body)
                .onSuccess {
                    _state.update { st -> st.copy(busy = false) }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message) } }
        }
    }

    /** Перевод между счетами: сервер запишет пару связанных операций. */
    override fun transfer(body: com.example.frolovsistems.core.net.TransferBody) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            crm.transferCash(body)
                .onSuccess {
                    _state.update { st -> st.copy(busy = false) }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message) } }
        }
    }

    override fun delete(op: CashOpDto) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            crm.deleteCashOp(op.id)
                .onSuccess {
                    _state.update { st -> st.copy(busy = false) }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message) } }
        }
    }
}

/**
 * Касса: приход, расход и остаток. Оплаты по заказам сюда попадают сами —
 * это одна и та же книга денег, а не две.
 */
@Composable
fun CashScreen(
    onBack: () -> Unit = {},
    viewModel: CashViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CashContent(
        state = state,
        actions = viewModel,
        onBack = onBack,
    )
}

/** Разметка экрана без ViewModel: её показывает превью в Android Studio. */
@Composable
fun CashContent(
    state: CashUiState,
    actions: CashActions,
    onBack: () -> Unit = {},
) {
    var adding by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<CashOpDto?>(null) }
    var showTransfer by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { actions.refresh() }

    Column(Modifier.fillMaxSize()) {
        ListHeader {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
                Text("Касса", style = MaterialTheme.typography.headlineMedium)
            }
            ErrorBanner(state.error)
            HintBlock(
                "Записать приход или расход — кнопки «Приход» и «Расход» внизу экрана; " +
                        "статья помогает потом разобраться, куда ушли деньги. Наличные, карта " +
                        "и счёт хранятся раздельно: их балансы — в карточке остатка сверху, " +
                        "а перевод между ними — кнопкой «Перевести между счетами».",
            )
            PeriodChips(state.period, actions::setPeriod)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.direction.isBlank(),
                    onClick = { actions.setDirection("") },
                    label = { Text("Всё") },
                )
                FilterChip(
                    selected = state.direction == CashDirection.IN,
                    onClick = { actions.setDirection(CashDirection.IN) },
                    label = { Text("Приход") },
                )
                FilterChip(
                    selected = state.direction == CashDirection.OUT,
                    onClick = { actions.setDirection(CashDirection.OUT) },
                    label = { Text("Расход") },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { adding = CashDirection.IN },
                    enabled = !state.busy,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                ) { Text("Приход") }
                OutlinedButton(
                    onClick = { adding = CashDirection.OUT },
                    enabled = !state.busy,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                ) { Text("Расход") }
            }
        }

        ListWindow(Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = ListContentPadding,
                verticalArrangement = Arrangement.spacedBy(ListItemSpacing),
            ) {
                item {
                    SoftCard {
                        Text("Остаток на руках", style = MaterialTheme.typography.labelMedium)
                        Text(
                            formatMoney(state.balanceKop),
                            style = MaterialTheme.typography.headlineMedium,
                            color = if (state.balanceKop >= 0) Success else MaterialTheme.colorScheme.error,
                        )
                        if (state.accounts.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            state.accounts.forEach { acc ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        accountLabel(acc.method),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        formatMoney(acc.balanceKop),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "За период: пришло ${formatMoney(state.incomeKop)}, " +
                                "ушло ${formatMoney(state.expenseKop)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { showTransfer = true },
                            enabled = !state.busy,
                            shape = MaterialTheme.shapes.small,
                        ) { Text("Перевести между счетами") }
                    }
                }

                when {
                    state.loading && state.items.isEmpty() -> item { LoadingBox() }
                    state.items.isEmpty() -> item {
                        EmptyState("За период операций нет", "Приход и расход появятся здесь")
                    }
                    else -> items(state.items, key = { it.id }) { op ->
                        CashRow(op = op, busy = state.busy, onDelete = { pendingDelete = op })
                    }
                }
            }
        }
    }

    adding?.let { direction ->
        CashDialog(
            direction = direction,
            busy = state.busy,
            onDismiss = { adding = null },
            onConfirm = { body ->
                adding = null
                actions.add(body)
            },
        )
    }

    pendingDelete?.let { op ->
        DoubleConfirmDialog(
            what = "${if (op.isIncome) "приход" else "расход"} ${formatMoney(op.amountKop)}",
            consequences = buildString {
                append("Запись исчезнет из кассы, остаток пересчитается.")
                if (op.orderId != null && op.isIncome) append(" Долг по заказу вырастет на эту сумму.")
                if (op.pairId != null) append(" Это перевод между счетами — уберутся обе его половины.")
            },
            onConfirm = {
                actions.delete(op)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    if (showTransfer) {
        TransferDialog(
            busy = state.busy,
            onDismiss = { showTransfer = false },
            onSave = { body ->
                showTransfer = false
                actions.transfer(body)
            },
        )
    }
}

fun accountLabel(method: String): String = when (method) {
    CashMethod.CASH -> "Наличные"
    CashMethod.CARD -> "Карта"
    CashMethod.ACCOUNT -> "Расчётный счёт"
    else -> method
}

/**
 * Перевод между счетами хранения. Сервер записывает две связанные операции,
 * поэтому деньги не «теряются» между наличными, картой и счётом.
 */
@Composable
private fun TransferDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (com.example.frolovsistems.core.net.TransferBody) -> Unit,
) {
    var from by remember { mutableStateOf(CashMethod.CASH) }
    var to by remember { mutableStateOf(CashMethod.ACCOUNT) }
    var amountKop by remember { mutableStateOf(0L) }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Перевод между счетами") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Откуда", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(CashMethod.CASH, CashMethod.CARD, CashMethod.ACCOUNT).forEach { m ->
                        FilterChip(
                            selected = from == m,
                            onClick = { if (m != to) from = m },
                            label = { Text(accountLabel(m)) },
                        )
                    }
                }
                Text("Куда", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(CashMethod.CASH, CashMethod.CARD, CashMethod.ACCOUNT).forEach { m ->
                        FilterChip(
                            selected = to == m,
                            onClick = { if (m != from) to = m },
                            label = { Text(accountLabel(m)) },
                        )
                    }
                }
                MoneyField(kop = amountKop, onKopChange = { amountKop = it }, label = "Сумма, ₽")
                DialogField("Заметка", note) { note = it }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        com.example.frolovsistems.core.net.TransferBody(
                            from = from, to = to, amountKop = amountKop, note = note,
                        ),
                    )
                },
                enabled = !busy && amountKop > 0,
            ) { Text("Перевести") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } },
    )
}

@Composable
private fun CashRow(op: CashOpDto, busy: Boolean, onDelete: () -> Unit) {
    SoftCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusChip(
                        if (op.isIncome) "приход" else "расход",
                        if (op.isIncome) Success else Warning,
                    )
                    StatusChip(CashMethod.label(op.method), MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    op.category.ifBlank { if (op.isIncome) "поступление" else "трата" },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    buildString {
                        append(formatShortDate(op.happenedAt))
                        if (op.orderTitle.isNotBlank()) {
                            append(" • ")
                            append(op.orderTitle)
                        }
                        if (op.clientName.isNotBlank()) {
                            append(" • ")
                            append(op.clientName)
                        }
                        if (op.note.isNotBlank()) {
                            append(" • ")
                            append(op.note)
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                (if (op.isIncome) "+" else "−") + formatMoney(op.amountKop),
                style = MaterialTheme.typography.titleSmall,
                color = if (op.isIncome) Success else Warning,
            )
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Убрать",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun CashDialog(
    direction: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (CashOpBody) -> Unit,
) {
    val income = direction == CashDirection.IN
    var amountKop by remember { mutableStateOf(0L) }
    var method by remember { mutableStateOf(CashMethod.CASH) }
    var category by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (income) "Приход" else "Расход") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MoneyField(
                    kop = amountKop,
                    onKopChange = { amountKop = it },
                    label = "Сумма, ₽",
                    enabled = !busy,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                Text("Способ", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CashMethod.all.forEach { value ->
                        FilterChip(
                            selected = method == value,
                            onClick = { method = value },
                            label = { Text(CashMethod.label(value)) },
                            enabled = !busy,
                        )
                    }
                }

                DialogField(
                    if (income) "Статья (за что пришло)" else "Статья (на что ушло)",
                    category,
                ) { category = it }
                DialogField("Заметка", note) { note = it }

                // Статья свободной строкой: список того, на что уходят деньги,
                // у каждого свой, и фиксированный набор его не покроет.
                Text(
                    "Статью пишите как удобно — по ней потом соберётся отчёт.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        CashOpBody(
                            direction = direction,
                            amountKop = amountKop,
                            method = method,
                            category = category.trim(),
                            note = note.trim(),
                        ),
                    )
                },
                enabled = !busy && amountKop > 0,
            ) { Text("Записать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

// ---------------------------------------------------------------------------
// Превью для Android Studio: Split или Design справа сверху. Данные — образцы
// из PreviewData, действия — пустые: экран рисуется без сети и базы.
// ---------------------------------------------------------------------------

@Preview(name = "Касса", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun CashContentPreview() = PreviewScreen {
    CashContent(
        state = CashUiState(
            loading = false,
            items = PreviewData.cash,
            incomeKop = 4_000_000,
            expenseKop = 1_590_000,
            balanceKop = 2_410_000,
            accounts = PreviewData.accounts,
        ),
        actions = object : CashActions {},
    )
}
