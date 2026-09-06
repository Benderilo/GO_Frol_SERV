package com.example.frolovsistems.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frolovsistems.core.net.CatalogItemDto
import com.example.frolovsistems.core.net.CatalogKind
import com.example.frolovsistems.core.net.OrderItemDto
import com.example.frolovsistems.core.net.ClientDto
import com.example.frolovsistems.core.net.OrderDto
import com.example.frolovsistems.core.net.PaymentDto
import com.example.frolovsistems.core.net.PhotoBytes
import com.example.frolovsistems.core.net.PhotoDto
import com.example.frolovsistems.core.net.TaskDto
import com.example.frolovsistems.data.CrmRepository
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.components.CollapsibleFilters
import com.example.frolovsistems.ui.components.DatePickerField
import com.example.frolovsistems.ui.components.DialogField
import com.example.frolovsistems.ui.components.EmptyState
import com.example.frolovsistems.ui.components.ErrorBanner
import com.example.frolovsistems.ui.components.LoadingBox
import com.example.frolovsistems.ui.components.PhotoViewerDialog
import com.example.frolovsistems.ui.components.RemotePhoto
import com.example.frolovsistems.ui.components.SearchField
import com.example.frolovsistems.ui.components.SoftCard
import com.example.frolovsistems.ui.components.StatusChip
import com.example.frolovsistems.ui.components.StatusRecordCard
import com.example.frolovsistems.ui.components.MoneyField
import com.example.frolovsistems.ui.components.formatMoney
import com.example.frolovsistems.ui.components.formatShortDate
import com.example.frolovsistems.ui.components.isDeadlineOverdue
import com.example.frolovsistems.ui.components.orderStatusColor
import com.example.frolovsistems.ui.components.orderStatusLabel
import com.example.frolovsistems.ui.components.orderStatuses
import com.example.frolovsistems.ui.components.parseLocalDate
import com.example.frolovsistems.ui.components.photoCountLabel
import com.example.frolovsistems.ui.theme.Success
import com.example.frolovsistems.ui.theme.Warning
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OrdersUiState(
    val loading: Boolean = true,
    val filter: String = "",
    val query: String = "",
    /** "date" — новые сверху, "price" — дорогие сверху. */
    val sort: String = "date",
    val items: List<OrderDto> = emptyList(),
    val clients: List<ClientDto> = emptyList(),
    val editing: OrderDto? = null,
    val photos: List<PhotoDto> = emptyList(),
    val payments: List<PaymentDto> = emptyList(),
    /** Состав открытого заказа и справочник, из которого его пополняют. */
    val orderItems: List<OrderItemDto> = emptyList(),
    val catalog: List<CatalogItemDto> = emptyList(),
    val itemsBusy: Boolean = false,
    val writeOffMessage: String? = null,
    /** Подтверждение созданного напоминания — показывается в редакторе заказа. */
    val reminderMessage: String? = null,
    val uploading: Boolean = false,
    val error: String? = null,
) {
    /** Локальный поиск и сортировка: фильтр статуса делает сервер. */
    val visibleItems: List<OrderDto>
        get() {
            val q = query.trim().lowercase()
            val filtered = if (q.isEmpty()) items else items.filter {
                it.title.lowercase().contains(q) ||
                    it.clientName.lowercase().contains(q) ||
                    it.description.lowercase().contains(q)
            }
            return when (sort) {
                "price" -> filtered.sortedByDescending { it.priceKop }
                else -> filtered.sortedByDescending { it.createdAt }
            }
        }
}

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

    fun onQuery(value: String) = _state.update { it.copy(query = value) }

    fun setSort(sort: String) = _state.update { it.copy(sort = sort) }

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

    fun startCreate() = _state.update {
        it.copy(editing = OrderDto(), photos = emptyList(), payments = emptyList(),
            orderItems = emptyList(), reminderMessage = null)
    }

    fun startEdit(order: OrderDto) {
        _state.update {
            it.copy(editing = order, photos = order.photos, payments = emptyList(),
                orderItems = emptyList(), writeOffMessage = null, reminderMessage = null)
        }
        // Список заказов приходит без снимков — подтягиваем их отдельно.
        if (order.id != 0L) {
            loadPhotos(order.id)
            loadPayments(order.id)
            loadOrderItems(order.id)
        }
        loadCatalog()
    }

    fun updateDraft(order: OrderDto) = _state.update { it.copy(editing = order) }
    fun cancelEdit() = _state.update {
        it.copy(editing = null, photos = emptyList(), payments = emptyList(),
            orderItems = emptyList(), writeOffMessage = null, reminderMessage = null)
    }

    private fun loadOrderItems(orderId: Long) {
        viewModelScope.launch {
            crm.orderItems(orderId)
                .onSuccess { list -> _state.update { applyItems(it, list) } }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    /** Справочник нужен, чтобы добавлять строки выбором, а не набором руками. */
    private fun loadCatalog() {
        if (_state.value.catalog.isNotEmpty()) return
        viewModelScope.launch {
            crm.catalog().onSuccess { list -> _state.update { it.copy(catalog = list) } }
        }
    }

    /**
     * Итог заказа считает сервер из строк. Здесь повторяем тот же счёт по
     * свежему составу, чтобы сумма в карточке менялась сразу, а не после
     * перезагрузки списка.
     */
    private fun applyItems(state: OrdersUiState, items: List<OrderItemDto>): OrdersUiState {
        val editing = state.editing ?: return state.copy(orderItems = items, itemsBusy = false)
        val updated = if (items.isEmpty()) {
            editing.copy(itemsCount = 0, costKop = 0)
        } else {
            editing.copy(
                priceKop = items.sumOf { it.totalKop },
                costKop = items.sumOf { it.totalCostKop },
                itemsCount = items.size,
            )
        }
        return state.copy(orderItems = items, editing = updated, itemsBusy = false)
    }

    fun addOrderItem(item: OrderItemDto) {
        val orderId = _state.value.editing?.id ?: return
        if (orderId == 0L) {
            _state.update { it.copy(error = "Сначала сохраните заказ — состав добавляется к существующему") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(itemsBusy = true, error = null, writeOffMessage = null) }
            crm.addOrderItem(orderId, item)
                .onSuccess { reloadItems(orderId) }
                .onFailure { e -> _state.update { it.copy(itemsBusy = false, error = e.message) } }
        }
    }

    fun updateOrderItem(item: OrderItemDto) {
        val orderId = _state.value.editing?.id ?: return
        viewModelScope.launch {
            _state.update { it.copy(itemsBusy = true, error = null, writeOffMessage = null) }
            crm.updateOrderItem(item.id, item)
                .onSuccess { reloadItems(orderId) }
                .onFailure { e -> _state.update { it.copy(itemsBusy = false, error = e.message) } }
        }
    }

    fun deleteOrderItem(item: OrderItemDto) {
        val orderId = _state.value.editing?.id ?: return
        viewModelScope.launch {
            _state.update { it.copy(itemsBusy = true, error = null, writeOffMessage = null) }
            crm.deleteOrderItem(item.id)
                .onSuccess { reloadItems(orderId) }
                .onFailure { e -> _state.update { it.copy(itemsBusy = false, error = e.message) } }
        }
    }

    fun writeOffMaterials() {
        val orderId = _state.value.editing?.id ?: return
        viewModelScope.launch {
            _state.update { it.copy(itemsBusy = true, error = null, writeOffMessage = null) }
            crm.writeOffOrder(orderId)
                .onSuccess { result ->
                    _state.update { st ->
                        applyItems(st, result.items).copy(
                            writeOffMessage = when (result.written) {
                                0 -> "Списывать было нечего — материалы уже на заказе"
                                1 -> "Списана 1 позиция"
                                else -> "Списано позиций: ${result.written}"
                            },
                        )
                    }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(itemsBusy = false, error = e.message) } }
        }
    }

    fun dismissWriteOffMessage() = _state.update { it.copy(writeOffMessage = null) }

    fun dismissReminder() = _state.update { it.copy(reminderMessage = null) }

    /**
     * Напоминание по заказу одной кнопкой: задача с именем заказа и его сроком.
     * Такая задача сама появляется в разделе «Задачи» и в календаре на дату срока.
     */
    fun createReminder() {
        val order = _state.value.editing ?: return
        if (order.id == 0L) return
        viewModelScope.launch {
            _state.update { it.copy(reminderMessage = null, error = null) }
            // Срок хранится текстом; в ISO-формат задачи переводим, только если он распознаётся.
            val dueIso = parseLocalDate(order.dueDate)?.toString().orEmpty()
            crm.createTask(
                TaskDto(
                    orderId = order.id,
                    clientId = order.clientId,
                    title = order.title.ifBlank { "Заказ №${order.id}" },
                    dueDate = dueIso,
                ),
            )
                .onSuccess { task ->
                    _state.update {
                        it.copy(reminderMessage = "Задача «${task.title}» добавлена в напоминания")
                    }
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    /** После правки состава перечитываем строки и обновляем список заказов. */
    private suspend fun reloadItems(orderId: Long) {
        crm.orderItems(orderId)
            .onSuccess { list -> _state.update { applyItems(it, list) } }
            .onFailure { e -> _state.update { it.copy(itemsBusy = false, error = e.message) } }
        refresh()
    }

    private fun loadPhotos(orderId: Long) {
        viewModelScope.launch {
            crm.orderPhotos(orderId)
                .onSuccess { list -> _state.update { it.copy(photos = list) } }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    private fun loadPayments(orderId: Long) {
        viewModelScope.launch {
            crm.orderPayments(orderId)
                .onSuccess { list -> _state.update { it.copy(payments = list) } }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun addPayment(orderId: Long, amountKop: Long, note: String) {
        viewModelScope.launch {
            crm.addPayment(orderId, amountKop, note)
                .onSuccess { payment ->
                    _state.update { st ->
                        st.copy(
                            payments = listOf(payment) + st.payments,
                            // Оплаченное в карточке обновляем сразу, без перезагрузки.
                            editing = st.editing?.copy(paidKop = st.editing.paidKop + payment.amountKop),
                            items = st.items.map {
                                if (it.id == orderId) it.copy(paidKop = it.paidKop + payment.amountKop) else it
                            },
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun deletePayment(payment: PaymentDto) {
        viewModelScope.launch {
            crm.deletePayment(payment.id)
                .onSuccess {
                    _state.update { st ->
                        st.copy(
                            payments = st.payments.filterNot { it.id == payment.id },
                            editing = st.editing?.copy(paidKop = st.editing.paidKop - payment.amountKop),
                            items = st.items.map {
                                if (it.id == payment.orderId) it.copy(paidKop = it.paidKop - payment.amountKop) else it
                            },
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun uploadPhoto(orderId: Long, bytes: ByteArray, fileName: String) {
        viewModelScope.launch {
            _state.update { it.copy(uploading = true, error = null) }
            crm.uploadPhoto(orderId, bytes, fileName)
                .onSuccess { photo ->
                    _state.update { it.copy(uploading = false, photos = it.photos + photo) }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(uploading = false, error = e.message) } }
        }
    }

    fun deletePhoto(photoId: Long) {
        viewModelScope.launch {
            crm.deletePhoto(photoId)
                .onSuccess {
                    _state.update { st -> st.copy(photos = st.photos.filterNot { it.id == photoId }) }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(
    initialStatus: String = "",
    onOpenDocument: (Long, String) -> Unit = { _, _ -> },
    refreshTick: Int = 0,
    viewModel: OrdersViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Со сводки сюда приходят с уже выбранным фильтром.
    LaunchedEffect(initialStatus) {
        if (initialStatus.isNotEmpty()) viewModel.setFilter(initialStatus)
    }
    // Обновляемся при каждом входе на вкладку.
    LaunchedEffect(Unit) { viewModel.refresh() }
    // Кнопка «Обновить» в общей шапке.
    LaunchedEffect(refreshTick) { if (refreshTick > 0) viewModel.refresh() }
    var pendingDelete by remember { mutableStateOf<OrderDto?>(null) }

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Заказы", style = MaterialTheme.typography.headlineMedium) }

            item {
                SearchField(
                    query = state.query,
                    onQuery = viewModel::onQuery,
                    placeholder = "Поиск по названию, клиенту, описанию",
                )
            }

            item {
                // Статусы и сортировка свернуты в одну строку-заголовок; чипы
                // разворачиваются по нажатию и прокручиваются по горизонтали.
                var filtersExpanded by rememberSaveable { mutableStateOf(false) }
                // Со сводки сюда приходят с уже выбранным фильтром — показываем чипы сразу.
                LaunchedEffect(state.filter) { if (state.filter.isNotEmpty()) filtersExpanded = true }
                val activeLabel = listOfNotNull(
                    orderStatuses.firstOrNull { it.first == state.filter }?.second,
                    if (state.sort == "price") "Дорогие сверху" else null,
                ).joinToString(", ").ifBlank { null }
                CollapsibleFilters(
                    activeLabel = activeLabel,
                    expanded = filtersExpanded,
                    onToggle = { filtersExpanded = !filtersExpanded },
                ) {
                    // Статусы и сортировка — в одной строке, прокручивается по горизонтали.
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
                        Icon(
                            Icons.Default.SwapVert,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilterChip(
                            selected = state.sort == "date",
                            onClick = { viewModel.setSort("date") },
                            label = { Text("Новые сверху") },
                        )
                        FilterChip(
                            selected = state.sort == "price",
                            onClick = { viewModel.setSort("price") },
                            label = { Text("Дорогие сверху") },
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
                state.visibleItems.isEmpty() -> item {
                    EmptyState(
                        title = "Ничего не найдено",
                        subtitle = "Попробуйте изменить запрос",
                    )
                }
                else -> {
                    item {
                        Text(
                            "${state.visibleItems.size} из ${state.items.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(state.visibleItems, key = { it.id }) { order ->
                    StatusRecordCard(
                        accent = orderStatusColor(order.status),
                        onClick = { viewModel.startEdit(order) },
                    ) {
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
                            Text(
                                order.description,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                            )
                        }
                        if (order.dueDate.isNotBlank()) {
                            val overdue = isDeadlineOverdue(order.dueDate, order.status)
                            Spacer(Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    Icons.Default.Schedule,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = if (overdue) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    (if (overdue) "Срок истёк: " else "Срок: ") + order.dueDate,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (overdue) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (order.photoCount > 0) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    Icons.Default.PhotoLibrary,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    photoCountLabel(order.photoCount),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        formatMoney(order.priceKop),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    if (order.priceKop > 0) {
                                        PaymentChip(order)
                                    }
                                }
                                if (order.createdAt.isNotBlank()) {
                                    Text(
                                        "создан ${formatShortDate(order.createdAt)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
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
            photos = state.photos,
            payments = state.payments,
            orderItems = state.orderItems,
            catalog = state.catalog,
            itemsBusy = state.itemsBusy,
            writeOffMessage = state.writeOffMessage,
            reminderMessage = state.reminderMessage,
            uploading = state.uploading,
            onChange = viewModel::updateDraft,
            onDismiss = viewModel::cancelEdit,
            onSave = viewModel::saveDraft,
            onUpload = { bytes, name -> viewModel.uploadPhoto(draft.id, bytes, name) },
            onDeletePhoto = viewModel::deletePhoto,
            onAddPayment = { amountKop, note -> viewModel.addPayment(draft.id, amountKop, note) },
            onDeletePayment = viewModel::deletePayment,
            onAddItem = viewModel::addOrderItem,
            onUpdateItem = viewModel::updateOrderItem,
            onDeleteItem = viewModel::deleteOrderItem,
            onWriteOff = viewModel::writeOffMaterials,
            onDismissWriteOff = viewModel::dismissWriteOffMessage,
            onCreateReminder = viewModel::createReminder,
            onDismissReminder = viewModel::dismissReminder,
            onOpenDocument = onOpenDocument,
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
    photos: List<PhotoDto>,
    payments: List<PaymentDto>,
    orderItems: List<OrderItemDto>,
    catalog: List<CatalogItemDto>,
    itemsBusy: Boolean,
    writeOffMessage: String?,
    reminderMessage: String?,
    uploading: Boolean,
    onChange: (OrderDto) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onUpload: (ByteArray, String) -> Unit,
    onDeletePhoto: (Long) -> Unit,
    onAddPayment: (Long, String) -> Unit,
    onDeletePayment: (PaymentDto) -> Unit,
    onAddItem: (OrderItemDto) -> Unit,
    onUpdateItem: (OrderItemDto) -> Unit,
    onDeleteItem: (OrderItemDto) -> Unit,
    onWriteOff: () -> Unit,
    onDismissWriteOff: () -> Unit,
    onCreateReminder: () -> Unit,
    onDismissReminder: () -> Unit,
    onOpenDocument: (Long, String) -> Unit,
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

                // У заказа с составом цену определяют строки: править её руками
                // здесь означало бы спорить с суммой, которую считает сервер.
                if (draft.hasItems) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Итого по составу", style = MaterialTheme.typography.bodyMedium)
                        Text(formatMoney(draft.priceKop), style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    MoneyField(
                        kop = draft.priceKop,
                        onKopChange = { onChange(draft.copy(priceKop = it)) },
                        label = "Стоимость, ₽",
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                DatePickerField("Срок", draft.dueDate) { onChange(draft.copy(dueDate = it)) }

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

                Spacer(Modifier.height(14.dp))
                CompositionSection(
                    orderId = draft.id,
                    items = orderItems,
                    catalog = catalog,
                    busy = itemsBusy,
                    writeOffMessage = writeOffMessage,
                    onAdd = onAddItem,
                    onUpdate = onUpdateItem,
                    onDelete = onDeleteItem,
                    onWriteOff = onWriteOff,
                    onDismissWriteOff = onDismissWriteOff,
                )

                // Документы печатаются по сохранённому заказу: у нового ещё
                // нет ни номера, ни состава, из которого их собирать.
                if (draft.id != 0L) {
                    Spacer(Modifier.height(14.dp))
                    Text("Документы", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onOpenDocument(draft.id, "invoice") },
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1f),
                        ) { Text("Счёт") }
                        OutlinedButton(
                            onClick = { onOpenDocument(draft.id, "act") },
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1f),
                        ) { Text("Акт") }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text("Напоминание", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Задача появится в разделе «Задачи» и в календаре на дату срока.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = onCreateReminder,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Создать напоминание") }
                    reminderMessage?.let { message ->
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = Success,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onDismissReminder) { Text("Скрыть") }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                PaymentSection(
                    order = draft,
                    payments = payments,
                    onAdd = onAddPayment,
                    onDelete = onDeletePayment,
                )

                Spacer(Modifier.height(14.dp))
                PhotoSection(
                    orderId = draft.id,
                    photos = photos,
                    uploading = uploading,
                    onUpload = onUpload,
                    onDelete = onDeletePhoto,
                )

                if (clients.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Клиент", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    // Клиентов может быть много: список живёт в прокрутке,
                    // выбранный поднимается наверх, чтобы его было видно.
                    val sorted = remember(clients, draft.clientId) {
                        clients.sortedByDescending { it.id == draft.clientId }
                    }
                    Column(
                        Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        FilterChip(
                            selected = draft.clientId == null,
                            onClick = { onChange(draft.copy(clientId = null, clientName = "")) },
                            label = { Text("Без клиента") },
                        )
                        sorted.forEach { client ->
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

/**
 * Снимки по заказу: сетка превью, добавление из галереи, удаление.
 * До сохранения заказа фото прикреплять некуда — сервер требует id.
 */
@Composable
private fun PhotoSection(
    orderId: Long,
    photos: List<PhotoDto>,
    uploading: Boolean,
    onUpload: (ByteArray, String) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickError by remember { mutableStateOf<String?>(null) }
    // Какой снимок показан на весь экран; null — просмотр закрыт.
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    // Системный выбор изображения: с Android 13 разрешения для него не нужны.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = PhotoBytes.fromUri(context, uri)
            if (bytes == null) {
                pickError = "Не удалось подготовить снимок — попробуйте другой файл"
            } else {
                pickError = null
                onUpload(bytes, "photo_${System.currentTimeMillis()}.jpg")
            }
        }
    }

    Text("Фотографии", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(6.dp))

    if (orderId == 0L) {
        Text(
            "Сохраните заказ, чтобы прикрепить фотографии.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    if (photos.isNotEmpty()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            photos.forEachIndexed { index, photo ->
                Box(Modifier.size(96.dp)) {
                    RemotePhoto(
                        path = photo.thumbUrl,
                        contentDescription = photo.caption.ifBlank { "Фото по заказу" },
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { viewerIndex = index },
                    )
                    IconButton(
                        onClick = { onDelete(photo.id) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(28.dp)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                MaterialTheme.shapes.extraSmall,
                            ),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Удалить фото",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    OutlinedButton(
        onClick = {
            picker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        enabled = !uploading,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (uploading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text("Загружаем…")
        } else {
            Icon(Icons.Default.AddAPhoto, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(if (photos.isEmpty()) "Добавить фото" else "Добавить ещё")
        }
    }

    pickError?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    viewerIndex?.let { index ->
        PhotoViewerDialog(
            photos = photos,
            initialIndex = index,
            onDismiss = { viewerIndex = null },
        )
    }
}

/**
 * Значок состояния оплаты на карточке заказа: оплачен, частично
 * или ждёт оплаты. Появляется только у заказов с ценой.
 */
@Composable
private fun PaymentChip(order: OrderDto) {
    val (text, color) = when {
        order.paidKop >= order.priceKop -> "Оплачен" to Success
        order.paidKop > 0 -> "Оплачено ${formatMoney(order.paidKop)}" to Warning
        else -> "Ждёт оплаты" to MaterialTheme.colorScheme.outline
    }
    StatusChip(text = text, color = color)
}

/**
 * Учёт оплат по заказу: сколько пришло, сколько осталось, история
 * поступлений и внесение нового платежа.
 */
@Composable
private fun PaymentSection(
    order: OrderDto,
    payments: List<PaymentDto>,
    onAdd: (Long, String) -> Unit,
    onDelete: (PaymentDto) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PaymentDto?>(null) }

    Text("Оплата", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(6.dp))

    if (order.id == 0L) {
        Text(
            "Сохраните заказ, чтобы вносить оплаты.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val balance = order.balanceKop
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                formatMoney(order.paidKop),
                style = MaterialTheme.typography.titleMedium,
                color = if (balance <= 0.0) Success else MaterialTheme.colorScheme.primary,
            )
            Text(
                "оплачено из ${formatMoney(order.priceKop)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                formatMoney(balance.coerceAtLeast(0L)),
                style = MaterialTheme.typography.titleMedium,
                color = if (balance <= 0.0) Success else Warning,
            )
            Text(
                if (balance <= 0.0) "долгов нет" else "осталось",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { showAddDialog = true },
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text("Внести оплату")
    }

    if (payments.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        payments.forEach { payment ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        formatMoney(payment.amountKop),
                        style = MaterialTheme.typography.titleSmall,
                        color = Success,
                    )
                    val note = payment.note.ifBlank { "без заметки" }
                    Text(
                        "$note • ${formatShortDate(payment.createdAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { pendingDelete = payment }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Удалить платёж",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddPaymentDialog(
            suggested = balance.coerceAtLeast(0L),
            onDismiss = { showAddDialog = false },
            onConfirm = { amountKop, note ->
                showAddDialog = false
                onAdd(amountKop, note)
            },
        )
    }

    pendingDelete?.let { payment ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить платёж?") },
            text = { Text("Поступление ${formatMoney(payment.amountKop)} будет убрано из истории заказа.") },
            confirmButton = {
                Button(onClick = {
                    onDelete(payment)
                    pendingDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Отмена") } },
        )
    }
}

/** Диалог внесения оплаты: сумма с подстановкой остатка и заметка. */
@Composable
private fun AddPaymentDialog(
    suggested: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long, String) -> Unit,
) {
    // Подставляем остаток к оплате: чаще всего вносят именно его.
    var amountKop by remember { mutableStateOf(suggested.coerceAtLeast(0L)) }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Внести оплату") },
        text = {
            Column {
                MoneyField(
                    kop = amountKop,
                    onKopChange = { amountKop = it },
                    label = "Сумма, ₽",
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                DialogField("Заметка (необязательно)", note) { note = it }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(amountKop, note.trim()) }, enabled = amountKop > 0L) {
                Text("Внести")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
