package com.example.frolovsistems.ui.screens

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

data class CashUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val items: List<CashOpDto> = emptyList(),
    val incomeKop: Long = 0,
    val expenseKop: Long = 0,
    val balanceKop: Long = 0,
    val period: Period = Period.thisMonth(),
    /** Пусто — оба направления. */
    val direction: String = "",
    val error: String? = null,
)

class CashViewModel(
    private val crm: CrmRepository = ServiceLocator.crm,
) : ViewModel() {

    private val _state = MutableStateFlow(CashUiState())
    val state: StateFlow<CashUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
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
        }
    }

    fun setPeriod(period: Period) {
        _state.update { it.copy(period = period) }
        refresh()
    }

    fun setDirection(direction: String) {
        _state.update { it.copy(direction = direction) }
        refresh()
    }

    fun add(body: CashOpBody) {
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

    fun delete(op: CashOpDto) {
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
    var adding by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<CashOpDto?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
                Text("Касса", style = MaterialTheme.typography.headlineMedium)
            }
        }

        item { ErrorBanner(state.error) }

        item {
            SoftCard {
                Text("Остаток на руках", style = MaterialTheme.typography.labelMedium)
                Text(
                    formatMoney(state.balanceKop),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (state.balanceKop >= 0) Success else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "За период: пришло ${formatMoney(state.incomeKop)}, " +
                        "ушло ${formatMoney(state.expenseKop)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { PeriodChips(state.period, viewModel::setPeriod) }

        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.direction.isBlank(),
                    onClick = { viewModel.setDirection("") },
                    label = { Text("Всё") },
                )
                FilterChip(
                    selected = state.direction == CashDirection.IN,
                    onClick = { viewModel.setDirection(CashDirection.IN) },
                    label = { Text("Приход") },
                )
                FilterChip(
                    selected = state.direction == CashDirection.OUT,
                    onClick = { viewModel.setDirection(CashDirection.OUT) },
                    label = { Text("Расход") },
                )
            }
        }

        item {
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

    adding?.let { direction ->
        CashDialog(
            direction = direction,
            busy = state.busy,
            onDismiss = { adding = null },
            onConfirm = { body ->
                adding = null
                viewModel.add(body)
            },
        )
    }

    pendingDelete?.let { op ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Убрать запись?") },
            text = {
                Text(
                    "${if (op.isIncome) "Приход" else "Расход"} ${formatMoney(op.amountKop)} " +
                        "исчезнет из кассы, и остаток пересчитается.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.delete(op)
                    pendingDelete = null
                }) { Text("Убрать") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Отмена") } },
        )
    }
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
                    StatusChip(CashMethod.label(op.method), MaterialTheme.colorScheme.outline)
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
