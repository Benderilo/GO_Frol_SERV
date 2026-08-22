package com.example.frolovsistems.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frolovsistems.core.net.ClientDto
import com.example.frolovsistems.core.net.OrderDto
import com.example.frolovsistems.data.CrmRepository
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.components.EmptyState
import com.example.frolovsistems.ui.components.ErrorBanner
import com.example.frolovsistems.ui.components.LoadingBox
import com.example.frolovsistems.ui.components.SoftCard
import com.example.frolovsistems.ui.components.StatusChip
import com.example.frolovsistems.ui.theme.Success
import com.example.frolovsistems.ui.theme.Warning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Статусы заказа — те же значения принимает сервер. */
val orderStatuses = listOf(
    "new" to "Новый",
    "in_progress" to "В работе",
    "done" to "Завершён",
    "canceled" to "Отменён",
)

data class OrdersUiState(
    val loading: Boolean = true,
    val filter: String = "",
    val items: List<OrderDto> = emptyList(),
    val clients: List<ClientDto> = emptyList(),
    val editing: OrderDto? = null,
    val error: String? = null,
)

class OrdersViewModel(
    private val crm: CrmRepository = ServiceLocator.crm,
) : ViewModel() {

    private val _state = MutableStateFlow(OrdersUiState())
    val state: StateFlow<OrdersUiState> = _state.asStateFlow()

    init { refresh() }

    fun setFilter(status: String) {
        _state.update { it.copy(filter = status) }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val orders = crm.orders(_state.value.filter)
            val clients = crm.clients()
            _state.update { current ->
                current.copy(
                    loading = false,
                    items = orders.getOrNull() ?: current.items,
                    clients = clients.getOrNull() ?: current.clients,
                    error = orders.exceptionOrNull()?.message ?: clients.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun startCreate() = _state.update { it.copy(editing = OrderDto()) }
    fun startEdit(order: OrderDto) = _state.update { it.copy(editing = order) }
    fun updateDraft(order: OrderDto) = _state.update { it.copy(editing = order) }
    fun cancelEdit() = _state.update { it.copy(editing = null) }

    fun saveDraft() {
        val draft = _state.value.editing ?: return
        if (draft.title.isBlank()) {
            _state.update { it.copy(error = "Укажите название заказа") }
            return
        }
        viewModelScope.launch {
            val result = if (draft.id == 0L) crm.createOrder(draft) else crm.updateOrder(draft.id, draft)
            result
                .onSuccess {
                    _state.update { it.copy(editing = null) }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun delete(order: OrderDto) {
        viewModelScope.launch {
            crm.deleteOrder(order.id)
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }
}
@Composable
fun OrdersScreen(viewModel: OrdersViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<OrderDto?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Заказы", style = MaterialTheme.typography.headlineMedium) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.filter.isEmpty(),
                        onClick = { viewModel.setFilter("") },
                        label = { Text("Все") },
                    )
                    orderStatuses.forEach { (value, label) ->
                        FilterChip(
                            selected = state.filter == value,
                            onClick = { viewModel.setFilter(value) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            item { ErrorBanner(state.error) }

            when {
                state.loading && state.items.isEmpty() -> item { LoadingBox() }
                state.items.isEmpty() -> item {
                    EmptyState(
                        title = "Заказов нет",
                        subtitle = "Создайте заказ кнопкой внизу справа",
                    )
                }
                else -> items(state.items, key = { it.id }) { order ->
                    SoftCard(onClick = { viewModel.startEdit(order) }) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                order.title,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            StatusChip(
                                text = orderStatusLabel(order.status),
                                color = orderStatusColor(order.status),
                            )
                        }
                        if (order.clientName.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                order.clientName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (order.description.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(order.description, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                formatMoney(order.price),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            IconButton(onClick = { pendingDelete = order }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Удалить",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = viewModel::startCreate,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = "Новый заказ")
        }
    }

    state.editing?.let { draft ->
        OrderEditorDialog(
            draft = draft,
            clients = state.clients,
            onChange = viewModel::updateDraft,
            onDismiss = viewModel::cancelEdit,
            onSave = viewModel::saveDraft,
        )
    }

    pendingDelete?.let { order ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить заказ?") },
            text = { Text("«${order.title}» будет удалён безвозвратно.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.delete(order)
                    pendingDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun OrderEditorDialog(
    draft: OrderDto,
    clients: List<ClientDto>,
    onChange: (OrderDto) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == 0L) "Новый заказ" else "Заказ №${draft.id}") },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding()
            ) {
                DialogField("Название", draft.title) { onChange(draft.copy(title = it)) }
                DialogField("Описание", draft.description, lines = 3) { onChange(draft.copy(description = it)) }

                OutlinedTextField(
                    value = if (draft.price == 0.0) "" else draft.price.toString(),
                    onValueChange = { text ->
                        val price = text.replace(',', '.').filter { it.isDigit() || it == '.' }
                        onChange(draft.copy(price = price.toDoubleOrNull() ?: 0.0))
                    },
                    label = { Text("Стоимость, ₽") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )

                DialogField("Срок (текстом)", draft.dueDate) { onChange(draft.copy(dueDate = it)) }

                Text("Статус", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    orderStatuses.forEach { (value, label) ->
                        FilterChip(
                            selected = draft.status == value,
                            onClick = { onChange(draft.copy(status = value)) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }

                if (clients.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Клиент", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    Column {
                        FilterChip(
                            selected = draft.clientId == null,
                            onClick = { onChange(draft.copy(clientId = null, clientName = "")) },
                            label = { Text("Без клиента") },
                        )
                        clients.take(20).forEach { client ->
                            FilterChip(
                                selected = draft.clientId == client.id,
                                onClick = { onChange(draft.copy(clientId = client.id, clientName = client.name)) },
                                label = { Text(client.name) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onSave) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

fun orderStatusLabel(status: String): String =
    orderStatuses.firstOrNull { it.first == status }?.second ?: status

@Composable
fun orderStatusColor(status: String): Color = when (status) {
    "new" -> MaterialTheme.colorScheme.secondary
    "in_progress" -> Warning
    "done" -> Success
    else -> MaterialTheme.colorScheme.outline
}
