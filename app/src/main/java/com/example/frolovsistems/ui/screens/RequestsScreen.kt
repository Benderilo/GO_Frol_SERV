package com.example.frolovsistems.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frolovsistems.core.net.ClientDto
import com.example.frolovsistems.core.net.OrderDto
import com.example.frolovsistems.core.net.RequestDto
import com.example.frolovsistems.data.CrmRepository
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.components.EmptyState
import com.example.frolovsistems.ui.components.ErrorBanner
import com.example.frolovsistems.ui.components.LoadingBox
import com.example.frolovsistems.ui.components.SearchField
import com.example.frolovsistems.ui.components.StatusChip
import com.example.frolovsistems.ui.components.StatusRecordCard
import com.example.frolovsistems.ui.components.CallButton
import com.example.frolovsistems.ui.components.CollapsibleFilters
import com.example.frolovsistems.ui.components.formatShortDate
import com.example.frolovsistems.ui.components.requestStatusColor
import com.example.frolovsistems.ui.components.requestStatusLabel
import com.example.frolovsistems.ui.theme.Success
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val requestStatuses = listOf(
    "new" to "Новые",
    "in_progress" to "В работе",
    "done" to "Обработанные",
    "spam" to "Спам",
)

data class RequestsUiState(
    val loading: Boolean = true,
    val filter: String = "",
    val query: String = "",
    val items: List<RequestDto> = emptyList(),
    val error: String? = null,
    /** Ид заявки, по которой прямо сейчас создаётся заказ; null — не создаётся. */
    val convertingId: Long? = null,
    /** Успешное действие — показываем зелёной плашкой поверх списка. */
    val notice: String? = null,
) {
    /** Фильтр по статусу делает сервер, а текстовый поиск — здесь на месте. */
    val visibleItems: List<RequestDto>
        get() {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return items
            return items.filter {
                it.name.lowercase().contains(q) ||
                    it.phone.lowercase().contains(q) ||
                    it.message.lowercase().contains(q)
            }
        }
}

class RequestsViewModel(
    private val crm: CrmRepository = ServiceLocator.crm,
) : ViewModel() {

    private val _state = MutableStateFlow(RequestsUiState())
    val state: StateFlow<RequestsUiState> = _state.asStateFlow()

    init { refresh() }

    fun onQuery(value: String) = _state.update { it.copy(query = value) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /**
     * Цепочка «заявка → клиент → заказ» одной кнопкой:
     * клиент ищется по телефону, нет — создаётся; заказ собирается из текста
     * заявки; сама заявка уходит «в работу», чтобы не висела новой.
     */
    fun createOrderFromRequest(request: RequestDto) {
        viewModelScope.launch {
            _state.update { it.copy(convertingId = request.id, error = null, notice = null) }
            val fail: (Throwable) -> Unit = { e ->
                _state.update { it.copy(convertingId = null, error = e.message) }
            }

            // Сравниваем телефоны по последним 10 цифрам — 8/ +7/ скобки не мешают.
            val wantedDigits = request.phone.filter(Char::isDigit).takeLast(10)
            val client = crm.clients(request.phone).getOrElse { emptyList() }
                .firstOrNull { it.phone.filter(Char::isDigit).takeLast(10) == wantedDigits && wantedDigits.isNotEmpty() }
                ?: crm.createClient(
                    ClientDto(
                        name = request.name,
                        phone = request.phone,
                        note = "Из заявки №${request.id}",
                    ),
                ).getOrElse { e -> fail(e); return@launch }

            val title = request.message.lineSequence().firstOrNull().orEmpty().take(80).ifBlank {
                "Заказ по заявке №${request.id}"
            }
            val order = crm.createOrder(
                OrderDto(clientId = client.id, title = title, description = request.message),
            ).getOrElse { e -> fail(e); return@launch }

            // Третий шаг не критичен: заказ создан, даже если статус заявки не сменился.
            crm.setRequestStatus(request.id, "in_progress")
            _state.update {
                it.copy(
                    convertingId = null,
                    notice = "Создан заказ №${order.id} для «${client.name}»",
                )
            }
            refresh()
        }
    }

    fun setFilter(status: String) {
        _state.update { it.copy(filter = status) }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            crm.requests(_state.value.filter)
                .onSuccess { list -> _state.update { it.copy(loading = false, items = list) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun setStatus(request: RequestDto, status: String) {
        viewModelScope.launch {
            crm.setRequestStatus(request.id, status)
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun delete(request: RequestDto) {
        viewModelScope.launch {
            crm.deleteRequest(request.id)
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestsScreen(
    initialStatus: String = "",
    refreshTick: Int = 0,
    viewModel: RequestsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Со сводки сюда приходят с уже выбранным фильтром.
    LaunchedEffect(initialStatus) {
        if (initialStatus.isNotEmpty()) viewModel.setFilter(initialStatus)
    }
    // Обновляемся при каждом входе на вкладку.
    LaunchedEffect(Unit) { viewModel.refresh() }
    // Кнопка «Обновить» в общей шапке: новый тик — новая загрузка.
    LaunchedEffect(refreshTick) { if (refreshTick > 0) viewModel.refresh() }
    var pendingDelete by remember { mutableStateOf<RequestDto?>(null) }

    PullToRefreshBox(
        isRefreshing = state.loading,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Заявки", style = MaterialTheme.typography.headlineMedium)
                    if (state.query.isNotBlank()) {
                        Text(
                            "${state.visibleItems.size} из ${state.items.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            SearchField(
                query = state.query,
                onQuery = viewModel::onQuery,
                placeholder = "Поиск по имени, телефону, тексту",
            )
        }

        item {
            // Статусов больше, чем влезает в узкий экран, — строку можно прокручивать.
            // По умолчанию чипы свернуты в одну строку-заголовок.
            var filtersExpanded by rememberSaveable { mutableStateOf(false) }
            // Со сводки сюда приходят с уже выбранным фильтром — разворачиваем, чтобы было видно.
            LaunchedEffect(state.filter) { if (state.filter.isNotEmpty()) filtersExpanded = true }
            CollapsibleFilters(
                activeLabel = requestStatuses.firstOrNull { it.first == state.filter }?.second,
                expanded = filtersExpanded,
                onToggle = { filtersExpanded = !filtersExpanded },
            ) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.filter.isEmpty(),
                        onClick = { viewModel.setFilter("") },
                        label = { Text("Все") },
                    )
                    requestStatuses.forEach { (value, label) ->
                        FilterChip(
                            selected = state.filter == value,
                            onClick = { viewModel.setFilter(value) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        item { ErrorBanner(state.error) }

        // Подтверждение успешных действий — например, «Создан заказ №…».
        item {
            state.notice?.let { notice ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = Success.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Success)
                        Text(
                            notice,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = viewModel::dismissNotice) {
                            Icon(Icons.Default.Close, contentDescription = "Скрыть")
                        }
                    }
                }
            }
        }

        val visible = state.visibleItems
        when {
            state.loading && state.items.isEmpty() -> item { LoadingBox() }
            visible.isEmpty() -> item {
                EmptyState(
                    title = if (state.query.isBlank()) "Заявок нет" else "Ничего не найдено",
                    subtitle = "Обращения с формы на сайте появятся здесь автоматически",
                )
            }
            else -> items(visible, key = { it.id }) { request ->
                RequestCard(
                    request = request,
                    busy = state.convertingId == request.id,
                    onStatus = { status -> viewModel.setStatus(request, status) },
                    onCreateOrder = { viewModel.createOrderFromRequest(request) },
                    onDelete = { pendingDelete = request },
                )
            }
        }
    }
    }

    pendingDelete?.let { request ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить заявку?") },
            text = { Text("Заявка от «${request.name}» будет удалена безвозвратно.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.delete(request)
                    pendingDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun RequestCard(
    request: RequestDto,
    busy: Boolean,
    onStatus: (String) -> Unit,
    onCreateOrder: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    StatusRecordCard(
        accent = requestStatusColor(request.status),
        onClick = { expanded = !expanded },
    ) {
        Column(Modifier.animateContentSize(tween(260))) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(request.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        request.phone,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(
                    text = requestStatusLabel(request.status),
                    color = requestStatusColor(request.status),
                )
            }

            if (request.message.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    request.message,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                )
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Получена: ${formatShortDate(request.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                CallButton(request.phone, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onCreateOrder,
                    enabled = !busy,
                    shape = MaterialTheme.shapes.small,
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(if (busy) "Создаём заказ…" else "Создать заказ")
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    requestStatuses.forEach { (value, label) ->
                        FilterChip(
                            selected = request.status == value,
                            onClick = { onStatus(value) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("Удалить заявку", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
