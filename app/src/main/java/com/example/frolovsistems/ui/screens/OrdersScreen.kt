package com.example.frolovsistems.ui.screens

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.filled.Engineering
import androidx.compose.material.icons.filled.CheckCircle
import com.example.frolovsistems.ui.components.WorkerPicker
import com.example.frolovsistems.ui.components.SummaryRow
import com.example.frolovsistems.ui.components.PayMethodChips
import com.example.frolovsistems.ui.components.FormSection
import com.example.frolovsistems.ui.components.DoubleConfirmDialog
import com.example.frolovsistems.ui.components.DangerZone
import com.example.frolovsistems.ui.components.CrmDialog
import com.example.frolovsistems.ui.components.ClientPickerField
import com.example.frolovsistems.core.net.WorkerDto
import com.example.frolovsistems.core.net.CashMethod
import com.example.frolovsistems.ui.components.ListWindow
import androidx.compose.ui.text.style.TextOverflow
import com.example.frolovsistems.ui.components.CompactCardPadding
import com.example.frolovsistems.ui.components.ListItemSpacing
import com.example.frolovsistems.ui.components.ListContentPadding
import com.example.frolovsistems.ui.components.ListHeader
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
import com.example.frolovsistems.ui.components.HintBlock
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
import com.example.frolovsistems.ui.components.rememberFabScrollState
import com.example.frolovsistems.ui.components.CrmFab
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.example.frolovsistems.ui.preview.PreviewScreen
import com.example.frolovsistems.ui.preview.PreviewData

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
    /** Рабочие — для выбора исполнителя в карточке заказа. */
    val workers: List<WorkerDto> = emptyList(),
    /** Идёт сохранение или завершение заказа — кнопки формы ждут ответа. */
    val saving: Boolean = false,
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

/**
 * Действия экрана. Их выполняет [OrdersViewModel]; превью в Android Studio
 * передаёт пустую реализацию, и разметка рисуется без сети и базы.
 */
interface OrdersActions {
    fun setFilter(status: String) {}
    fun onQuery(value: String) {}
    fun setSort(sort: String) {}
    fun refresh() {}
    fun startCreate() {}
    fun startEdit(order: OrderDto) {}
    fun updateDraft(order: OrderDto) {}
    fun cancelEdit() {}
    fun addOrderItem(item: OrderItemDto) {}
    fun updateOrderItem(item: OrderItemDto) {}
    fun deleteOrderItem(item: OrderItemDto) {}
    fun writeOffMaterials() {}
    fun dismissWriteOffMessage() {}
    fun dismissReminder() {}
    fun createReminder() {}
    fun addPayment(orderId: Long, amountKop: Long, note: String, method: String = CashMethod.CASH) {}
    fun deletePayment(payment: PaymentDto) {}
    fun uploadPhoto(orderId: Long, bytes: ByteArray, fileName: String) {}
    fun deletePhoto(photoId: Long) {}
    fun saveDraft() {}
    fun delete(order: OrderDto) {}
    fun createClient(name: String, phone: String, onDone: (ClientDto?) -> Unit) { onDone(null) }
    /**
     * Завершить заказ одним шагом: при желании принять остаток оплаты и
     * списать материалы, затем поставить «Завершён» и закрыть карточку.
     */
    fun finishOrder(payRemainder: Boolean, method: String, writeOff: Boolean) {}
}

class OrdersViewModel(
    private val crm: CrmRepository = ServiceLocator.crm,
) : ViewModel(), OrdersActions {

    private val _state = MutableStateFlow(OrdersUiState())
    val state: StateFlow<OrdersUiState> = _state.asStateFlow()

    init { refresh() }

    override fun setFilter(status: String) {
        _state.update { it.copy(filter = status) }
        refresh()
    }

    override fun onQuery(value: String) = _state.update { it.copy(query = value) }

    override fun setSort(sort: String) = _state.update { it.copy(sort = sort) }

    override fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val orders = crm.orders(_state.value.filter)
            val clients = crm.clients()
            val workers = ServiceLocator.workers.workers(includeInactive = true)
            _state.update { current ->
                current.copy(
                    loading = false,
                    items = orders.getOrNull() ?: current.items,
                    clients = clients.getOrNull() ?: current.clients,
                    workers = workers.getOrNull() ?: current.workers,
                    error = orders.exceptionOrNull()?.message ?: clients.exceptionOrNull()?.message,
                )
            }
        }
    }

    override fun startCreate() = _state.update {
        it.copy(editing = OrderDto(), photos = emptyList(), payments = emptyList(),
            orderItems = emptyList(), reminderMessage = null)
    }

    override fun startEdit(order: OrderDto) {
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

    override fun updateDraft(order: OrderDto) = _state.update { it.copy(editing = order) }
    override fun cancelEdit() = _state.update {
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

    override fun addOrderItem(item: OrderItemDto) {
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

    override fun updateOrderItem(item: OrderItemDto) {
        val orderId = _state.value.editing?.id ?: return
        viewModelScope.launch {
            _state.update { it.copy(itemsBusy = true, error = null, writeOffMessage = null) }
            crm.updateOrderItem(item.id, item)
                .onSuccess { reloadItems(orderId) }
                .onFailure { e -> _state.update { it.copy(itemsBusy = false, error = e.message) } }
        }
    }

    override fun deleteOrderItem(item: OrderItemDto) {
        val orderId = _state.value.editing?.id ?: return
        viewModelScope.launch {
            _state.update { it.copy(itemsBusy = true, error = null, writeOffMessage = null) }
            crm.deleteOrderItem(item.id)
                .onSuccess { reloadItems(orderId) }
                .onFailure { e -> _state.update { it.copy(itemsBusy = false, error = e.message) } }
        }
    }

    override fun writeOffMaterials() {
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

    override fun dismissWriteOffMessage() = _state.update { it.copy(writeOffMessage = null) }

    override fun dismissReminder() = _state.update { it.copy(reminderMessage = null) }

    /**
     * Напоминание по заказу одной кнопкой: задача с именем заказа и его сроком.
     * Такая задача сама появляется в разделе «Задачи» и в календаре на дату срока.
     */
    override fun createReminder() {
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

    override fun addPayment(orderId: Long, amountKop: Long, note: String, method: String) {
        viewModelScope.launch {
            crm.addPayment(orderId, amountKop, note, method)
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

    override fun deletePayment(payment: PaymentDto) {
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

    override fun uploadPhoto(orderId: Long, bytes: ByteArray, fileName: String) {
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

    override fun deletePhoto(photoId: Long) {
        viewModelScope.launch {
            crm.deletePhoto(photoId)
                .onSuccess {
                    _state.update { st -> st.copy(photos = st.photos.filterNot { it.id == photoId }) }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    /**
     * Новый заказ после создания не закрывается, а открывается целиком:
     * состав, оплата, фото и документы привязываются к уже сохранённому
     * заказу, и так весь путь от заявки до закрытия проходит в одном окне.
     */
    override fun saveDraft() {
        val draft = _state.value.editing ?: return
        if (draft.title.isBlank()) {
            _state.update { it.copy(error = "Укажите название заказа") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            val creating = draft.id == 0L
            val result = if (creating) crm.createOrder(draft) else crm.updateOrder(draft.id, draft)
            result
                .onSuccess { saved ->
                    _state.update { it.copy(saving = false) }
                    if (creating) startEdit(saved) else _state.update { it.copy(editing = null) }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(saving = false, error = e.message) } }
        }
    }

    override fun delete(order: OrderDto) {
        viewModelScope.launch {
            crm.deleteOrder(order.id)
                .onSuccess {
                    _state.update { if (it.editing?.id == order.id) it.copy(editing = null) else it }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    override fun createClient(name: String, phone: String, onDone: (ClientDto?) -> Unit) {
        viewModelScope.launch {
            crm.createClient(ClientDto(name = name, phone = phone)).fold(
                onSuccess = { created ->
                    _state.update { it.copy(clients = listOf(created) + it.clients) }
                    onDone(created)
                },
                onFailure = { e ->
                    _state.update { it.copy(error = e.message) }
                    onDone(null)
                },
            )
        }
    }

    override fun finishOrder(payRemainder: Boolean, method: String, writeOff: Boolean) {
        val draft = _state.value.editing ?: return
        if (draft.id == 0L) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            val remainder = draft.balanceKop
            if (payRemainder && remainder > 0) {
                crm.addPayment(draft.id, remainder, "Оплата остатка при завершении", method).onFailure { e ->
                    _state.update { it.copy(saving = false, error = e.message) }
                    return@launch
                }
            }
            if (writeOff) {
                crm.writeOffOrder(draft.id).onFailure { e ->
                    _state.update { it.copy(saving = false, error = "Списание не прошло: ${e.message}") }
                    reloadItems(draft.id)
                    return@launch
                }
            }
            crm.updateOrder(draft.id, draft.copy(status = "done"))
                .onSuccess {
                    _state.update { it.copy(saving = false, editing = null) }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(saving = false, error = e.message) } }
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
    OrdersContent(
        state = state,
        actions = viewModel,
        initialStatus = initialStatus,
        onOpenDocument = onOpenDocument,
        refreshTick = refreshTick,
    )
}

/** Разметка экрана без ViewModel: её показывает превью в Android Studio. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersContent(
    state: OrdersUiState,
    actions: OrdersActions,
    initialStatus: String = "",
    onOpenDocument: (Long, String) -> Unit = { _, _ -> },
    refreshTick: Int = 0,
) {
    // Плавающая кнопка уезжает при прокрутке вниз и возвращается при прокрутке вверх.
    val fabScroll = rememberFabScrollState()

    // Со сводки сюда приходят с уже выбранным фильтром.
    LaunchedEffect(initialStatus) {
        if (initialStatus.isNotEmpty()) actions.setFilter(initialStatus)
    }
    // Обновляемся при каждом входе на вкладку.
    LaunchedEffect(Unit) { actions.refresh() }
    // Кнопка «Обновить» в общей шапке.
    LaunchedEffect(refreshTick) { if (refreshTick > 0) actions.refresh() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ListHeader {
                Text("Заказы", style = MaterialTheme.typography.headlineMedium)
                HintBlock(
                    "Новый заказ — плюс внизу экрана. Откройте карточку заказа, чтобы " +
                        "добавить состав, фото и оплату, распечатать счёт, акт, накладную " +
                        "или УПД (раздел «Документы» в карточке) и списать материалы со склада.",
                )
                SearchField(
                    query = state.query,
                    onQuery = actions::onQuery,
                    placeholder = "Поиск по названию, клиенту, описанию",
                )
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
                            onClick = { actions.setFilter("") },
                            label = { Text("Все") },
                        )
                        orderStatuses.forEach { (value, label) ->
                            FilterChip(
                                selected = state.filter == value,
                                onClick = { actions.setFilter(value) },
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
                            onClick = { actions.setSort("date") },
                            label = { Text("Новые сверху") },
                        )
                        FilterChip(
                            selected = state.sort == "price",
                            onClick = { actions.setSort("price") },
                            label = { Text("Дорогие сверху") },
                        )
                    }
                }
            }

            ListWindow(Modifier.weight(1f)) {
            PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = actions::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().nestedScroll(fabScroll.connection),
                    contentPadding = ListContentPadding,
                    verticalArrangement = Arrangement.spacedBy(ListItemSpacing),
                ) {
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
                                OrderRow(order = order, onOpen = { actions.startEdit(order) })
                            }
                        }
                    }
                }
            }
            }
        }

        CrmFab(
            icon = Icons.Default.Add,
            contentDescription = "Новый заказ",
            onClick = actions::startCreate,
            visible = fabScroll.visible,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }

    if (state.editing != null) {
        OrderEditorDialog(state = state, actions = actions, onOpenDocument = onOpenDocument)
    }
}

/** Путь заказа: три шага по порядку. «Отменён» — в стороне, отдельным чипом. */
private val orderSteps = listOf("new" to "Новый", "in_progress" to "В работе", "done" to "Завершён")

/**
 * Карточка заказа — от заведения до закрытия в одном окне. Сверху путь
 * заказа и, пока он не закрыт, кнопка «Завершить»; ниже данные, состав,
 * оплата, фото и документы; в самом низу — опасная зона с удалением.
 */
@Composable
private fun OrderEditorDialog(
    state: OrdersUiState,
    actions: OrdersActions,
    onOpenDocument: (Long, String) -> Unit,
) {
    val draft = state.editing ?: return
    val isNew = draft.id == 0L
    var finishing by remember { mutableStateOf(false) }
    val change: (OrderDto) -> Unit = actions::updateDraft

    CrmDialog(
        title = if (isNew) "Новый заказ" else "Заказ №${draft.id}",
        subtitle = if (isNew) "Заполните главное — остальное откроется после создания"
            else listOf(draft.clientName, draft.workerName).filter { it.isNotBlank() }.joinToString(" · "),
        onDismiss = actions::cancelEdit,
        confirmText = if (isNew) "Создать" else "Сохранить",
        confirmEnabled = draft.title.isNotBlank(),
        busy = state.saving,
        onConfirm = actions::saveDraft,
    ) {
        ErrorBanner(state.error)

        OrderProgress(status = draft.status, onStatus = { change(draft.copy(status = it)) })
        if (!isNew && draft.status != "done" && draft.status != "canceled") {
            FinishCard(order = draft, onFinish = { finishing = true })
        }

        FormSection("Заказ") {
            Column {
                DialogField("Что сделать", draft.title) { change(draft.copy(title = it)) }
                DialogField("Описание", draft.description, lines = 2) { change(draft.copy(description = it)) }
            }
            ClientPickerField(
                clients = state.clients,
                selectedId = draft.clientId,
                selectedName = draft.clientName,
                onPick = { c -> change(draft.copy(clientId = c?.id, clientName = c?.name.orEmpty())) },
                allowNone = true,
                createClient = actions::createClient,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // У заказа с составом цену определяют строки: править её руками
                // значило бы спорить с суммой, которую считает сервер.
                if (draft.hasItems) {
                    Column(Modifier.weight(1f)) {
                        Text(formatMoney(draft.priceKop), style = MaterialTheme.typography.titleMedium)
                        Text(
                            "итого по составу",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    MoneyField(
                        kop = draft.priceKop,
                        onKopChange = { change(draft.copy(priceKop = it)) },
                        label = "Стоимость, ₽",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            // Срок — на всю ширину: у поля две кнопки, в половине дата не помещается.
            DatePickerField("Срок", draft.dueDate) { change(draft.copy(dueDate = it)) }
        }

        if (state.workers.isNotEmpty()) {
            FormSection("Исполнитель") {
                WorkerPicker(
                    workers = state.workers,
                    selectedId = draft.workerId,
                    onSelect = { w -> change(draft.copy(workerId = w?.id, workerName = w?.name.orEmpty())) },
                )
            }
        }

        if (isNew) {
            Text(
                "После создания заказ откроется целиком: состав и материалы, оплата, фото, документы.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@CrmDialog
        }

        CompositionSection(
            orderId = draft.id,
            items = state.orderItems,
            catalog = state.catalog,
            busy = state.itemsBusy,
            writeOffMessage = state.writeOffMessage,
            onAdd = actions::addOrderItem,
            onUpdate = actions::updateOrderItem,
            onDelete = actions::deleteOrderItem,
            onWriteOff = actions::writeOffMaterials,
            onDismissWriteOff = actions::dismissWriteOffMessage,
        )

        PaymentSection(
            order = draft,
            payments = state.payments,
            onAdd = { amountKop, note, method -> actions.addPayment(draft.id, amountKop, note, method) },
            onDelete = actions::deletePayment,
        )

        PhotoSection(
            orderId = draft.id,
            photos = state.photos,
            uploading = state.uploading,
            onUpload = { bytes, fileName -> actions.uploadPhoto(draft.id, bytes, fileName) },
            onDelete = actions::deletePhoto,
        )

        FormSection("Документы") {
            // Четыре вида — двумя ровными рядами, чтобы подписи не ужались.
            listOf(listOf("invoice" to "Счёт", "act" to "Акт"), listOf("waybill" to "Накладная", "upd" to "УПД"))
                .forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (kind, label) ->
                            OutlinedButton(
                                onClick = { onOpenDocument(draft.id, kind) },
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.weight(1f),
                            ) { Text(label) }
                        }
                    }
                }
        }

        FormSection("Напоминание") {
            OutlinedButton(
                onClick = actions::createReminder,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Задача на дату срока") }
            state.reminderMessage?.let { message ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message, style = MaterialTheme.typography.bodySmall, color = Success, modifier = Modifier.weight(1f))
                    TextButton(onClick = actions::dismissReminder) { Text("Скрыть") }
                }
            }
        }

        DangerZone(
            actionLabel = "Удалить заказ",
            what = "заказ «${draft.title}»",
            consequences = "Уйдут состав, фото, документы и задачи заказа. Полученные деньги останутся " +
                "в кассе без привязки к заказу, списанные материалы на склад не вернутся.",
            onConfirm = { actions.delete(draft) },
        )
    }

    if (finishing) {
        FinishOrderDialog(
            order = draft,
            hasUnwritten = state.orderItems.any { it.isMaterial && !it.writtenOff && it.catalogId != null },
            onDismiss = { finishing = false },
            onConfirm = { pay, method, writeOff ->
                finishing = false
                actions.finishOrder(pay, method, writeOff)
            },
        )
    }
}

/** Путь заказа ступеньками: пройденные закрашены, текущая выделена. */
@Composable
private fun OrderProgress(status: String, onStatus: (String) -> Unit) {
    val current = orderSteps.indexOfFirst { it.first == status }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        orderSteps.forEachIndexed { index, (value, label) ->
            val reached = current >= index
            val color = if (reached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            Column(
                Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onStatus(value) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(color, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (index == current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (index == current) FontWeight.Bold else null,
                )
            }
        }
        FilterChip(
            selected = status == "canceled",
            onClick = { onStatus(if (status == "canceled") "new" else "canceled") },
            label = { Text("Отменён", style = MaterialTheme.typography.labelSmall) },
        )
    }
}

/** Что осталось до закрытия: долг по оплате и кнопка «Завершить». */
@Composable
private fun FinishCard(order: OrderDto, onFinish: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val left = order.balanceKop
            Text(
                if (left > 0) "К оплате ${formatMoney(left)}" else "Оплачено полностью",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "Завершение примет остаток и спишет материалы",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onFinish, shape = MaterialTheme.shapes.small) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Завершить")
        }
    }
}

/**
 * Завершение заказа: что сделать заодно. По умолчанию — всё, что ещё не
 * сделано: принять остаток и списать материалы. Снятая галочка — пропустить.
 */
@Composable
private fun FinishOrderDialog(
    order: OrderDto,
    hasUnwritten: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (pay: Boolean, method: String, writeOff: Boolean) -> Unit,
) {
    val left = order.balanceKop
    var pay by remember { mutableStateOf(left > 0) }
    var method by remember { mutableStateOf(CashMethod.CASH) }
    var writeOff by remember { mutableStateOf(hasUnwritten) }
    CrmDialog(
        title = "Завершить заказ",
        subtitle = order.title,
        onDismiss = onDismiss,
        confirmText = "Завершить",
        onConfirm = { onConfirm(pay && left > 0, method, writeOff && hasUnwritten) },
    ) {
        SummaryRow("Стоимость", formatMoney(order.priceKop))
        SummaryRow("Оплачено", formatMoney(order.paidKop))
        SummaryRow("Остаток", formatMoney(left.coerceAtLeast(0L)), emphasize = true)
        if (left > 0) {
            CheckRow("Принять остаток ${formatMoney(left)}", pay) { pay = it }
            if (pay) PayMethodChips(selected = method, onSelect = { method = it })
        }
        if (hasUnwritten) {
            CheckRow("Списать материалы со склада", writeOff) { writeOff = it }
        }
        Text(
            "Заказ получит статус «Завершён». Если что-то пойдёт не так, статус не изменится.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
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
    var deletingPhoto by remember { mutableStateOf<Long?>(null) }
    deletingPhoto?.let { id ->
        DoubleConfirmDialog(
            what = "фото",
            consequences = "Снимок исчезнет из заказа и из кабинета клиента.",
            onConfirm = {
                onDelete(id)
                deletingPhoto = null
            },
            onDismiss = { deletingPhoto = null },
        )
    }

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
                        onClick = { deletingPhoto = photo.id },
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
/**
 * Компактная карточка заказа — три строки: название и статус; клиент и
 * описание; сумма, оплата, срок и удаление. Остальное — в самой карточке.
 */
@Composable
private fun OrderRow(order: OrderDto, onOpen: () -> Unit) {
    StatusRecordCard(accent = orderStatusColor(order.status), onClick = onOpen) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                order.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StatusChip(text = orderStatusLabel(order.status), color = orderStatusColor(order.status))
        }
        val subtitle = listOf(order.clientName, order.description).filter { it.isNotBlank() }.joinToString(" · ")
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatMoney(order.priceKop),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (order.priceKop > 0) PaymentChip(order)
            Spacer(Modifier.weight(1f))
            if (order.photoCount > 0) {
                Icon(
                    Icons.Default.PhotoLibrary,
                    contentDescription = photoCountLabel(order.photoCount),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${order.photoCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Срок важнее даты создания: есть срок — показываем его, нет — когда заведён.
            if (order.dueDate.isNotBlank()) {
                val overdue = isDeadlineOverdue(order.dueDate, order.status)
                val tint = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(14.dp), tint = tint)
                Text(
                    if (overdue) "истёк ${order.dueDate}" else order.dueDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    maxLines = 1,
                )
            } else if (order.createdAt.isNotBlank()) {
                Text(
                    formatShortDate(order.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (order.workerName.isNotBlank()) {
                Icon(
                    Icons.Default.Engineering,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    order.workerName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PaymentChip(order: OrderDto) {
    val (text, color) = when {
        order.paidKop >= order.priceKop -> "Оплачен" to Success
        order.paidKop > 0 -> "Оплачено ${formatMoney(order.paidKop)}" to Warning
        else -> "Ждёт оплаты" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    StatusChip(text = text, color = color)
}

/**
 * Оплата заказа: сколько пришло и сколько осталось, история платежей.
 * Новый платёж подставляет остаток — чаще всего вносят именно его.
 */
@Composable
private fun PaymentSection(
    order: OrderDto,
    payments: List<PaymentDto>,
    onAdd: (Long, String, String) -> Unit,
    onDelete: (PaymentDto) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PaymentDto?>(null) }
    val balance = order.balanceKop

    FormSection(
        "Оплата",
        trailing = {
            TextButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Внести")
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    formatMoney(order.paidKop),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (balance <= 0) Success else MaterialTheme.colorScheme.primary,
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
                    color = if (balance <= 0) Success else Warning,
                )
                Text(
                    if (balance <= 0) "долгов нет" else "осталось",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        payments.forEach { payment ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(formatMoney(payment.amountKop), style = MaterialTheme.typography.titleSmall, color = Success)
                    Text(
                        listOf(payment.note, formatShortDate(payment.createdAt)).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { pendingDelete = payment }, modifier = Modifier.size(32.dp)) {
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
            onConfirm = { amountKop, note, method ->
                showAddDialog = false
                onAdd(amountKop, note, method)
            },
        )
    }

    pendingDelete?.let { payment ->
        DoubleConfirmDialog(
            what = "платёж ${formatMoney(payment.amountKop)}",
            consequences = "Поступление уйдёт из заказа и из кассы, долг клиента вырастет на эту сумму.",
            onConfirm = {
                onDelete(payment)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** Внесение оплаты: сумма с подстановкой остатка, способ и заметка. */
@Composable
private fun AddPaymentDialog(
    suggested: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long, String, String) -> Unit,
) {
    var amountKop by remember { mutableStateOf(suggested.coerceAtLeast(0L)) }
    var note by remember { mutableStateOf("") }
    var method by remember { mutableStateOf(CashMethod.CASH) }

    CrmDialog(
        title = "Внести оплату",
        onDismiss = onDismiss,
        confirmText = "Внести",
        confirmEnabled = amountKop > 0L,
        onConfirm = { onConfirm(amountKop, note.trim(), method) },
    ) {
        MoneyField(kop = amountKop, onKopChange = { amountKop = it }, label = "Сумма, ₽")
        PayMethodChips(selected = method, onSelect = { method = it })
        DialogField("Заметка (необязательно)", note) { note = it }
    }
}

// ---------------------------------------------------------------------------
// Превью для Android Studio: Split или Design справа сверху. Данные — образцы
// из PreviewData, действия — пустые: экран рисуется без сети и базы.
// ---------------------------------------------------------------------------

@Preview(name = "Заказы", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun OrdersContentPreview0() = PreviewScreen {
    OrdersContent(
        state = OrdersUiState(loading = false, items = PreviewData.orders, clients = PreviewData.clients),
        actions = object : OrdersActions {},
    )
}

@Preview(name = "Заказы · карточка", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun OrdersContentPreview1() = PreviewScreen {
    OrdersContent(
        state = OrdersUiState(
            loading = false,
            items = PreviewData.orders,
            clients = PreviewData.clients,
            editing = PreviewData.orders[1],
            catalog = PreviewData.catalog,
        ),
        actions = object : OrdersActions {},
    )
}
