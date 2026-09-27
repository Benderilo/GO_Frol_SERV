package com.example.frolovsistems.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Language
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.example.frolovsistems.ui.components.CrmDialog
import com.example.frolovsistems.ui.components.DangerZone
import com.example.frolovsistems.ui.components.FormSection
import com.example.frolovsistems.ui.components.HintBlock
import com.example.frolovsistems.ui.components.SummaryRow
import androidx.compose.ui.text.style.TextOverflow
import com.example.frolovsistems.ui.components.ListWindow
import com.example.frolovsistems.ui.components.CompactCardPadding
import com.example.frolovsistems.ui.components.ListItemSpacing
import com.example.frolovsistems.ui.components.ListContentPadding
import com.example.frolovsistems.ui.components.ListHeader
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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.tooling.preview.Preview
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
import com.example.frolovsistems.ui.preview.PreviewScreen
import com.example.frolovsistems.ui.preview.PreviewData

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
    /** Открытая карточка заявки; статус в ней — черновик до «Сохранить». */
    val opened: RequestDto? = null,
    val saving: Boolean = false,
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

/**
 * Действия экрана. Их выполняет [RequestsViewModel]; превью в Android Studio
 * передаёт пустую реализацию, и разметка рисуется без сети и базы.
 */
interface RequestsActions {
    fun onQuery(value: String) {}
    fun dismissNotice() {}
    fun createOrderFromRequest(request: RequestDto) {}
    fun setFilter(status: String) {}
    fun refresh() {}
    fun setStatus(request: RequestDto, status: String) {}
    fun delete(request: RequestDto) {}
    fun open(request: RequestDto) {}
    fun close() {}
    fun setOpenedStatus(status: String) {}
    fun saveOpened() {}
}

class RequestsViewModel(
    private val crm: CrmRepository = ServiceLocator.crm,
) : ViewModel(), RequestsActions {

    private val _state = MutableStateFlow(RequestsUiState())
    val state: StateFlow<RequestsUiState> = _state.asStateFlow()

    init { refresh() }

    override fun onQuery(value: String) = _state.update { it.copy(query = value) }

    override fun dismissNotice() = _state.update { it.copy(notice = null) }

    /**
     * Цепочка «заявка → клиент → заказ» одной кнопкой:
     * клиент ищется по телефону, нет — создаётся; заказ собирается из текста
     * заявки; сама заявка уходит «в работу», чтобы не висела новой.
     */
    override fun createOrderFromRequest(request: RequestDto) {
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
                    opened = null,
                    notice = "Создан заказ №${order.id} для «${client.name}»",
                )
            }
            refresh()
        }
    }

    override fun setFilter(status: String) {
        _state.update { it.copy(filter = status) }
        refresh()
    }

    override fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            crm.requests(_state.value.filter)
                .onSuccess { list -> _state.update { it.copy(loading = false, items = list) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    override fun setStatus(request: RequestDto, status: String) {
        viewModelScope.launch {
            crm.setRequestStatus(request.id, status)
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    override fun delete(request: RequestDto) {
        viewModelScope.launch {
            crm.deleteRequest(request.id)
                .onSuccess { _state.update { it.copy(opened = null) }; refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    override fun open(request: RequestDto) = _state.update { it.copy(opened = request, error = null) }

    override fun close() = _state.update { it.copy(opened = null, saving = false) }

    override fun setOpenedStatus(status: String) =
        _state.update { it.copy(opened = it.opened?.copy(status = status)) }

    override fun saveOpened() {
        val draft = _state.value.opened ?: return
        val saved = _state.value.items.firstOrNull { it.id == draft.id }
        if (saved?.status == draft.status) return close()
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            crm.setRequestStatus(draft.id, draft.status)
                .onSuccess { _state.update { it.copy(saving = false, opened = null) }; refresh() }
                .onFailure { e -> _state.update { it.copy(saving = false, error = e.message) } }
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
    RequestsContent(
        state = state,
        actions = viewModel,
        initialStatus = initialStatus,
        refreshTick = refreshTick,
    )
}

/** Разметка экрана без ViewModel: её показывает превью в Android Studio. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestsContent(
    state: RequestsUiState,
    actions: RequestsActions,
    initialStatus: String = "",
    refreshTick: Int = 0,
) {
    // Со сводки сюда приходят с уже выбранным фильтром.
    LaunchedEffect(initialStatus) {
        if (initialStatus.isNotEmpty()) actions.setFilter(initialStatus)
    }
    // Обновляемся при каждом входе на вкладку.
    LaunchedEffect(Unit) { actions.refresh() }
    // Кнопка «Обновить» в общей шапке: новый тик — новая загрузка.
    LaunchedEffect(refreshTick) { if (refreshTick > 0) actions.refresh() }

    Column(Modifier.fillMaxSize()) {
        ListHeader {
            Column {
                Text("Заявки с сайта", style = MaterialTheme.typography.headlineMedium)
                if (state.query.isNotBlank()) {
                    Text(
                        "${state.visibleItems.size} из ${state.items.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HintBlock(
                "Сюда сами приходят обращения с формы на сайте. Откройте заявку, чтобы " +
                    "позвонить клиенту, сменить статус или одной кнопкой сделать из неё заказ.",
            )
            SearchField(
                query = state.query,
                onQuery = actions::onQuery,
                placeholder = "Поиск по имени, телефону, тексту",
            )
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
                        onClick = { actions.setFilter("") },
                        label = { Text("Все") },
                    )
                    requestStatuses.forEach { (value, label) ->
                        FilterChip(
                            selected = state.filter == value,
                            onClick = { actions.setFilter(value) },
                            label = { Text(label) },
                        )
                    }
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
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = ListContentPadding,
                    verticalArrangement = Arrangement.spacedBy(ListItemSpacing),
                ) {
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
                                    IconButton(onClick = actions::dismissNotice) {
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
                            RequestRow(request = request, onOpen = { actions.open(request) })
                        }
                    }
                }
            }
        }
    }

    if (state.opened != null) {
        RequestDialog(state = state, actions = actions)
    }
}

/** Строка списка — как у заказов: кто, статус, суть, откуда и когда. Нажатие открывает карточку. */
@Composable
private fun RequestRow(request: RequestDto, onOpen: () -> Unit) {
    StatusRecordCard(accent = requestStatusColor(request.status), onClick = onOpen) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                request.name.ifBlank { "Заявка №${request.id}" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StatusChip(text = requestStatusLabel(request.status), color = requestStatusColor(request.status))
        }
        if (request.message.isNotBlank()) {
            Text(
                request.message,
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
                request.phone,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Default.Language,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                listOfNotNull("с сайта", formatShortDate(request.createdAt).ifBlank { null }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Путь заявки: три шага по порядку. «Спам» — в стороне, отдельным чипом. */
private val requestSteps = listOf("new" to "Новая", "in_progress" to "В работе", "done" to "Обработана")

/**
 * Карточка заявки — того же вида, что карточка заказа: сверху путь заявки
 * и, пока по ней нет заказа, кнопка «Создать заказ»; ниже обращение и
 * звонок; в самом низу — опасная зона с удалением.
 */
@Composable
private fun RequestDialog(state: RequestsUiState, actions: RequestsActions) {
    val draft = state.opened ?: return
    val converting = state.convertingId == draft.id

    CrmDialog(
        title = "Заявка №${draft.id}",
        subtitle = listOfNotNull("С сайта", formatShortDate(draft.createdAt).ifBlank { null }).joinToString(" · "),
        onDismiss = actions::close,
        confirmText = "Сохранить",
        busy = state.saving || converting,
        onConfirm = actions::saveOpened,
    ) {
        ErrorBanner(state.error)

        RequestProgress(status = draft.status, onStatus = actions::setOpenedStatus)
        if (draft.status == "new" || draft.status == "in_progress") {
            CreateOrderCard(busy = converting, onCreate = { actions.createOrderFromRequest(draft) })
        }

        FormSection("Обращение") {
            SummaryRow("Имя", draft.name.ifBlank { "—" })
            SummaryRow("Телефон", draft.phone.ifBlank { "—" })
            if (draft.message.isNotBlank()) {
                Text(draft.message, style = MaterialTheme.typography.bodyMedium)
            }
            if (draft.phone.isNotBlank()) CallButton(draft.phone, Modifier.fillMaxWidth())
        }

        DangerZone(
            actionLabel = "Удалить заявку",
            what = "заявку от «${draft.name.ifBlank { "№${draft.id}" }}»",
            consequences = "Обращение с сайта исчезнет из списка. Созданный по нему заказ останется.",
            onConfirm = { actions.delete(draft) },
        )
    }
}

/** Путь заявки ступеньками: пройденные закрашены, текущая выделена. */
@Composable
private fun RequestProgress(status: String, onStatus: (String) -> Unit) {
    val current = requestSteps.indexOfFirst { it.first == status }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        requestSteps.forEachIndexed { index, (value, label) ->
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
            selected = status == "spam",
            onClick = { onStatus(if (status == "spam") "new" else "spam") },
            label = { Text("Спам", style = MaterialTheme.typography.labelSmall) },
        )
    }
}

/** Следующий шаг по заявке: заказ одной кнопкой — клиент найдётся или заведётся сам. */
@Composable
private fun CreateOrderCard(busy: Boolean, onCreate: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Сделать заказ", style = MaterialTheme.typography.titleSmall)
            Text(
                "Клиент найдётся по телефону или заведётся, заявка уйдёт в работу",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onCreate, enabled = !busy, shape = MaterialTheme.shapes.small) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.size(6.dp))
            Text("Заказ")
        }
    }
}

// ---------------------------------------------------------------------------
// Превью для Android Studio: Split или Design справа сверху. Данные — образцы
// из PreviewData, действия — пустые: экран рисуется без сети и базы.
// ---------------------------------------------------------------------------

@Preview(name = "Заявки", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun RequestsContentPreview() = PreviewScreen {
    RequestsContent(
        state = RequestsUiState(loading = false, items = PreviewData.requests),
        actions = object : RequestsActions {},
    )
}

@Preview(name = "Заявки · карточка", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun RequestDialogPreview() = PreviewScreen {
    RequestsContent(
        state = RequestsUiState(
            loading = false,
            items = PreviewData.requests,
            opened = PreviewData.requests.first(),
        ),
        actions = object : RequestsActions {},
    )
}
