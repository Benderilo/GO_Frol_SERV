package com.example.frolovsistems.ui.screens

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.material3.Switch
import androidx.compose.material.icons.filled.History
import com.example.frolovsistems.ui.components.SummaryRow
import com.example.frolovsistems.ui.components.PayMethodChips
import com.example.frolovsistems.ui.components.FormSection
import com.example.frolovsistems.ui.components.DoubleConfirmDialog
import com.example.frolovsistems.ui.components.DangerZone
import com.example.frolovsistems.ui.components.CrmDialog
import com.example.frolovsistems.ui.components.ChoiceChips
import com.example.frolovsistems.core.net.CashMethod
import androidx.compose.ui.text.style.TextOverflow
import com.example.frolovsistems.ui.components.ListWindow
import com.example.frolovsistems.ui.components.CompactCardPadding
import com.example.frolovsistems.ui.components.ListItemSpacing
import com.example.frolovsistems.ui.components.ListContentPadding
import com.example.frolovsistems.ui.components.ListHeader
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import com.example.frolovsistems.core.net.CatalogItemDto
import com.example.frolovsistems.core.net.CatalogKind
import com.example.frolovsistems.core.net.StockMoveDto
import com.example.frolovsistems.data.CrmRepository
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.components.CollapsibleFilters
import com.example.frolovsistems.ui.components.DialogField
import com.example.frolovsistems.ui.components.EmptyState
import com.example.frolovsistems.ui.components.ErrorBanner
import com.example.frolovsistems.ui.components.HintBlock
import com.example.frolovsistems.ui.components.LoadingBox
import com.example.frolovsistems.ui.components.MoneyField
import com.example.frolovsistems.ui.components.QuantityField
import com.example.frolovsistems.ui.components.SearchField
import com.example.frolovsistems.ui.components.StatusChip
import com.example.frolovsistems.ui.components.StatusRecordCard
import com.example.frolovsistems.ui.components.formatMoney
import com.example.frolovsistems.ui.components.formatQuantity
import com.example.frolovsistems.ui.components.formatShortDate
import com.example.frolovsistems.ui.theme.Success
import com.example.frolovsistems.ui.theme.Warning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.example.frolovsistems.ui.components.rememberFabScrollState
import com.example.frolovsistems.ui.components.CrmFab
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.tooling.preview.Preview
import com.example.frolovsistems.ui.preview.PreviewScreen
import com.example.frolovsistems.ui.preview.PreviewData

data class CatalogUiState(
    val loading: Boolean = true,
    val items: List<CatalogItemDto> = emptyList(),
    val query: String = "",
    /** Пустая строка — показывать все виды. */
    val kindFilter: String = "",
    val withArchived: Boolean = false,
    /** Позиция, открытая в диалоге правки; id = 0 — новая. */
    val editing: CatalogItemDto? = null,
    /** Позиция, у которой открыт склад. */
    val stockFor: CatalogItemDto? = null,
    val moves: List<StockMoveDto> = emptyList(),
    /** Журнал движений по всем материалам; null — окно журнала закрыто. */
    val journal: List<StockMoveDto>? = null,
    val busy: Boolean = false,
    val error: String? = null,
) {
    /** Вид позиций фильтрует сервер, текст ищем здесь на месте. */
    val visibleItems: List<CatalogItemDto>
        get() {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return items
            return items.filter {
                it.name.lowercase().contains(q) || it.note.lowercase().contains(q)
            }
        }
}

/**
 * Действия экрана. Их выполняет [CatalogViewModel]; превью в Android Studio
 * передаёт пустую реализацию, и разметка рисуется без сети и базы.
 */
interface CatalogActions {
    fun onQuery(value: String) {}
    fun refresh() {}
    fun setKind(kind: String) {}
    fun toggleArchived() {}
    fun startCreate() {}
    fun startEdit(item: CatalogItemDto) {}
    fun changeDraft(item: CatalogItemDto) {}
    fun cancelEdit() {}
    fun save() {}
    fun delete(item: CatalogItemDto) {}
    fun openStock(item: CatalogItemDto) {}
    fun closeStock() {}
    fun addMove(qtyMilli: Long, costKop: Long, note: String, payMethod: String = "") {}
    fun openJournal() {}
    fun closeJournal() {}
    fun deleteMove(move: StockMoveDto) {}
}

class CatalogViewModel(
    private val crm: CrmRepository = ServiceLocator.crm,
) : ViewModel(), CatalogActions {

    private val _state = MutableStateFlow(CatalogUiState())
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()

    init { refresh() }

    override fun onQuery(value: String) = _state.update { it.copy(query = value) }

    override fun refresh() {
        viewModelScope.launch {
            val current = _state.value
            _state.update { it.copy(loading = true, error = null) }
            crm.catalog(current.kindFilter, current.withArchived)
                .onSuccess { list -> _state.update { it.copy(loading = false, items = list) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    override fun setKind(kind: String) {
        _state.update { it.copy(kindFilter = kind) }
        refresh()
    }

    override fun toggleArchived() {
        _state.update { it.copy(withArchived = !it.withArchived) }
        refresh()
    }

    override fun startCreate() = _state.update {
        // Новая позиция наследует вид из фильтра: если человек смотрит
        // материалы, он почти наверняка добавляет материал.
        it.copy(editing = CatalogItemDto(kind = it.kindFilter.ifBlank { CatalogKind.MATERIAL }))
    }

    override fun startEdit(item: CatalogItemDto) = _state.update { it.copy(editing = item) }
    override fun changeDraft(item: CatalogItemDto) = _state.update { it.copy(editing = item) }
    override fun cancelEdit() = _state.update { it.copy(editing = null) }

    override fun save() {
        val draft = _state.value.editing ?: return
        if (draft.name.isBlank()) {
            _state.update { it.copy(error = "Впишите наименование") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val result = if (draft.id == 0L) {
                crm.createCatalogItem(draft)
            } else {
                crm.updateCatalogItem(draft.id, draft)
            }
            result
                .onSuccess {
                    _state.update { st -> st.copy(busy = false, editing = null) }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message) } }
        }
    }

    override fun delete(item: CatalogItemDto) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            crm.deleteCatalogItem(item.id)
                .onSuccess {
                    _state.update { st -> st.copy(busy = false, editing = null) }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message) } }
        }
    }

    override fun openStock(item: CatalogItemDto) {
        _state.update { it.copy(stockFor = item, moves = emptyList(), error = null) }
        viewModelScope.launch {
            crm.stockMoves(item.id)
                .onSuccess { list -> _state.update { it.copy(moves = list) } }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    override fun closeStock() = _state.update { it.copy(stockFor = null, moves = emptyList()) }

    /** Приход — положительное количество, списание — отрицательное. */
    override fun addMove(qtyMilli: Long, costKop: Long, note: String, payMethod: String) {
        val item = _state.value.stockFor ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            crm.addStockMove(item.id, qtyMilli, costKop, note, payMethod)
                .onSuccess { reloadStock(item.id) }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message) } }
        }
    }

    override fun deleteMove(move: StockMoveDto) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            crm.deleteStockMove(move.id)
                .onSuccess {
                    reloadStock(move.itemId)
                    if (_state.value.journal != null) loadJournal()
                }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message) } }
        }
    }

    override fun openJournal() {
        _state.update { it.copy(journal = emptyList(), error = null) }
        viewModelScope.launch { loadJournal() }
    }

    override fun closeJournal() = _state.update { it.copy(journal = null) }

    private suspend fun loadJournal() {
        crm.stockMoves(0)
            .onSuccess { list -> _state.update { it.copy(journal = list) } }
            .onFailure { e -> _state.update { it.copy(error = e.message) } }
    }

    /** После движения перечитываем и список, и остаток открытой позиции. */
    private suspend fun reloadStock(itemId: Long) {
        crm.stockMoves(itemId).onSuccess { list -> _state.update { it.copy(moves = list) } }
        val current = _state.value
        crm.catalog(current.kindFilter, current.withArchived).onSuccess { list ->
            _state.update { st ->
                st.copy(
                    busy = false,
                    items = list,
                    stockFor = list.firstOrNull { it.id == itemId } ?: st.stockFor,
                )
            }
        }
        _state.update { it.copy(busy = false) }
    }
}

/**
 * Склад: услуги, работы и материалы. У материалов есть приход,
 * списание и остаток, который считается из движений, а не хранится числом.
 * Разметка общая с «Клиентами» и «Заказами»: шапка, поиск, фильтры, FAB.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    refreshTick: Int = 0,
    viewModel: CatalogViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CatalogContent(
        state = state,
        actions = viewModel,
        refreshTick = refreshTick,
    )
}

/** Разметка экрана без ViewModel: её показывает превью в Android Studio. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogContent(
    state: CatalogUiState,
    actions: CatalogActions,
    refreshTick: Int = 0,
) {
    // Плавающая кнопка уезжает при прокрутке вниз и возвращается при прокрутке вверх.
    val fabScroll = rememberFabScrollState()

    LaunchedEffect(Unit) { actions.refresh() }
    // Кнопка «Обновить» в общей шапке.
    LaunchedEffect(refreshTick) { if (refreshTick > 0) actions.refresh() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ListHeader {
                Text("Склад", style = MaterialTheme.typography.headlineMedium)
                HintBlock(
                    "Новая позиция — плюс внизу экрана. Приход и списание материала — " +
                        "в карточке позиции; там же виден остаток. Материалы, добавленные " +
                        "в состав заказа, списываются кнопкой «Списать» в карточке заказа " +
                        "или проведением накладной.",
                )
                ErrorBanner(state.error)
                SearchField(
                    query = state.query,
                    onQuery = actions::onQuery,
                    placeholder = "Поиск по названию и заметке",
                )
                var filtersExpanded by rememberSaveable { mutableStateOf(false) }
                val activeLabel = listOfNotNull(
                    if (state.kindFilter.isBlank()) null else CatalogKind.plural(state.kindFilter),
                    if (state.withArchived) "С архивом" else null,
                ).joinToString(", ").ifBlank { null }
                OutlinedButton(
                    onClick = actions::openJournal,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Движения склада и деньги")
                }
                CollapsibleFilters(
                    activeLabel = activeLabel,
                    expanded = filtersExpanded,
                    onToggle = { filtersExpanded = !filtersExpanded },
                ) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.kindFilter.isBlank(),
                            onClick = { actions.setKind("") },
                            label = { Text("Все") },
                        )
                        CatalogKind.all.forEach { kind ->
                            FilterChip(
                                selected = state.kindFilter == kind,
                                onClick = { actions.setKind(kind) },
                                label = { Text(CatalogKind.plural(kind)) },
                            )
                        }
                        FilterChip(
                            selected = state.withArchived,
                            onClick = { actions.toggleArchived() },
                            label = { Text("С архивом") },
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
                        when {
                            state.loading && state.items.isEmpty() -> item { LoadingBox() }
                            state.items.isEmpty() -> item {
                                EmptyState(
                                    "Склад пуст",
                                    "Добавьте услуги, работы и материалы с ценами — из них будут собираться заказы",
                                )
                            }
                            state.visibleItems.isEmpty() -> item {
                                EmptyState("Ничего не найдено", "Попробуйте изменить запрос")
                            }
                            else -> items(state.visibleItems, key = { it.id }) { item ->
                                CatalogCard(
                                    item = item,
                                    onEdit = { actions.startEdit(item) },
                                    onStock = { actions.openStock(item) },
                                )
                            }
                        }
                    }
                }
            }
        }

        CrmFab(
            icon = Icons.Default.Add,
            contentDescription = "Добавить позицию",
            onClick = actions::startCreate,
            visible = fabScroll.visible,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }

    state.editing?.let { draft ->
        CatalogItemDialog(
            draft = draft,
            busy = state.busy,
            onChange = actions::changeDraft,
            onDismiss = actions::cancelEdit,
            onSave = actions::save,
            onDelete = { actions.delete(draft) },
        )
    }

    state.stockFor?.let { item ->
        StockDialog(
            item = item,
            moves = state.moves,
            busy = state.busy,
            error = state.error,
            onDismiss = actions::closeStock,
            onAdd = actions::addMove,
            onDeleteMove = actions::deleteMove,
        )
    }

    state.journal?.let { journal ->
        StockJournalDialog(
            moves = journal,
            busy = state.busy,
            onDismiss = actions::closeJournal,
            onDeleteMove = actions::deleteMove,
        )
    }
}

@Composable
private fun CatalogCard(
    item: CatalogItemDto,
    onEdit: () -> Unit,
    onStock: () -> Unit,
) {
    // Материал без остатка тянет на себя внимание красной полосой.
    val accent = when {
        item.archived -> MaterialTheme.colorScheme.onSurfaceVariant
        item.isMaterial && item.stockMilli <= 0 -> MaterialTheme.colorScheme.error
        item.isMaterial -> Success
        else -> kindColor(item.kind)
    }
    StatusRecordCard(accent = accent, onClick = onEdit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusChip(CatalogKind.label(item.kind), kindColor(item.kind))
                    if (item.archived) {
                        StatusChip("архив", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(item.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    buildString {
                        append(formatMoney(item.priceKop))
                        append(" за ")
                        append(item.unit)
                        if (item.costKop > 0) {
                            append(" • закупка ")
                            append(formatMoney(item.costKop))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.note.isNotBlank()) {
                    Text(
                        item.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (item.isMaterial) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${formatQuantity(item.stockMilli)} ${item.unit}",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (item.stockMilli > 0) Success else Warning,
                    )
                    Text(
                        "на складе",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = onStock, shape = MaterialTheme.shapes.small) {
                        Icon(Icons.Default.Inventory, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Склад")
                    }
                }
            }
        }
    }
}

@Composable
private fun kindColor(kind: String) = when (kind) {
    CatalogKind.MATERIAL -> MaterialTheme.colorScheme.primary
    CatalogKind.WORK -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.secondary
}

@Composable
private fun CatalogItemDialog(
    draft: CatalogItemDto,
    busy: Boolean,
    onChange: (CatalogItemDto) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    CrmDialog(
        title = if (draft.id == 0L) "Новая позиция" else draft.name.ifBlank { "Позиция" },
        subtitle = if (draft.isMaterial && draft.id != 0L) "На складе ${formatQuantity(draft.stockMilli)} ${draft.unit}" else null,
        onDismiss = onDismiss,
        confirmText = "Сохранить",
        confirmEnabled = draft.name.isNotBlank(),
        busy = busy,
        onConfirm = onSave,
    ) {
        ChoiceChips(
            options = CatalogKind.all.map { it to CatalogKind.label(it) },
            selected = draft.kind,
            onSelect = { onChange(draft.copy(kind = it)) },
        )
        Column {
            DialogField("Наименование", draft.name) { onChange(draft.copy(name = it)) }
            DialogField("Единица измерения", draft.unit) { onChange(draft.copy(unit = it)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MoneyField(
                kop = draft.priceKop,
                onKopChange = { onChange(draft.copy(priceKop = it)) },
                label = "Продажа, ₽",
                enabled = !busy,
                modifier = Modifier.weight(1f),
            )
            MoneyField(
                kop = draft.costKop,
                onKopChange = { onChange(draft.copy(costKop = it)) },
                label = "Закупка, ₽",
                enabled = !busy,
                modifier = Modifier.weight(1f),
            )
        }
        DialogField("Заметка", draft.note, lines = 2) { onChange(draft.copy(note = it)) }
        FilterChip(
            selected = draft.archived,
            onClick = { onChange(draft.copy(archived = !draft.archived)) },
            label = { Text("Архивная — не показывать в списке") },
            enabled = !busy,
        )
        if (draft.id != 0L) {
            DangerZone(
                actionLabel = "Удалить позицию",
                what = "«${draft.name}»",
                consequences = "Позиция исчезнет из справочника. Если по ней уже были приходы или " +
                    "списания, сервер удалить не даст — тогда отметьте её архивной: из списка " +
                    "уйдёт, а история склада останется.",
                onConfirm = onDelete,
                enabled = !busy,
            )
        }
    }
}

/**
 * Склад по одной позиции: остаток, приход или списание и история.
 * Приход с суммой по умолчанию оплачивается из кассы — закупка сразу видна
 * в деньгах, и считать её второй раз вручную не нужно.
 */
@Composable
private fun StockDialog(
    item: CatalogItemDto,
    moves: List<StockMoveDto>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onAdd: (Long, Long, String, String) -> Unit,
    onDeleteMove: (StockMoveDto) -> Unit,
) {
    var income by remember { mutableStateOf(true) }
    var qtyMilli by remember { mutableStateOf(0L) }
    var costKop by remember { mutableStateOf(0L) }
    var note by remember { mutableStateOf("") }
    var payFromCash by remember { mutableStateOf(true) }
    var method by remember { mutableStateOf(CashMethod.CASH) }
    var pendingDelete by remember { mutableStateOf<StockMoveDto?>(null) }
    val pays = income && costKop > 0 && payFromCash

    CrmDialog(
        title = item.name,
        subtitle = "На складе ${formatQuantity(item.stockMilli)} ${item.unit}",
        onDismiss = onDismiss,
        dismissText = "Закрыть",
        confirmText = if (income) "Записать приход" else "Списать",
        confirmEnabled = qtyMilli > 0L,
        busy = busy,
        onConfirm = {
            onAdd(if (income) qtyMilli else -qtyMilli, costKop, note.trim(), if (pays) method else "")
            qtyMilli = 0L
            costKop = 0L
            note = ""
        },
    ) {
        ErrorBanner(error)
        // Направление — выбором, а не знаком в поле: минус легко не заметить.
        ChoiceChips(
            options = listOf(true to "Приход", false to "Списание"),
            selected = income,
            onSelect = { income = it },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuantityField(
                milli = qtyMilli,
                onMilliChange = {
                    qtyMilli = it
                    // Подсказываем сумму закупки по цене из справочника.
                    if (income && item.costKop > 0) costKop = (it * item.costKop + 500) / 1000
                },
                label = "Кол-во, ${item.unit}",
                enabled = !busy,
                modifier = Modifier.weight(1f),
            )
            MoneyField(
                kop = costKop,
                onKopChange = { costKop = it },
                label = if (income) "Сумма закупки, ₽" else "Сумма, ₽",
                enabled = !busy,
                modifier = Modifier.weight(1f),
            )
        }
        if (income && costKop > 0) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { payFromCash = !payFromCash },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Оплачено из кассы", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Расход «Материалы» на ${formatMoney(costKop)} появится в кассе",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = payFromCash, onCheckedChange = { payFromCash = it })
                }
                if (payFromCash) PayMethodChips(selected = method, onSelect = { method = it })
            }
        }
        DialogField("Заметка", note) { note = it }

        if (moves.isNotEmpty()) {
            FormSection("История") {
                moves.forEach { move -> StockMoveRow(move, showItem = false, busy = busy) { pendingDelete = move } }
            }
        }
    }

    pendingDelete?.let { move -> ConfirmDeleteMove(move, onDeleteMove) { pendingDelete = null } }
}

/**
 * Журнал склада: все приходы и списания по всем материалам, свежие сверху,
 * и итог в деньгах — сколько закуплено, сколько из этого прошло через кассу
 * и на сколько материалов ушло в заказы.
 */
@Composable
private fun StockJournalDialog(
    moves: List<StockMoveDto>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onDeleteMove: (StockMoveDto) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<StockMoveDto?>(null) }
    val bought = moves.filter { it.isIncome }.sumOf { it.costKop }
    val paid = moves.filter { it.paidFromCash }.sumOf { it.costKop }
    val used = moves.filter { !it.isIncome }.sumOf { it.costKop }

    CrmDialog(
        title = "Движения склада",
        subtitle = "Последние ${moves.size} записей",
        onDismiss = onDismiss,
        dismissText = "Закрыть",
        confirmText = "Готово",
        onConfirm = onDismiss,
    ) {
        FormSection("Деньги") {
            SummaryRow("Закуплено материалов", formatMoney(bought))
            SummaryRow("Из них оплачено из кассы", formatMoney(paid))
            SummaryRow("Списано в заказы", formatMoney(used), emphasize = true)
        }
        FormSection("Записи") {
            if (moves.isEmpty()) {
                Text(
                    "Движений пока нет",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            moves.forEach { move -> StockMoveRow(move, showItem = true, busy = busy) { pendingDelete = move } }
        }
    }

    pendingDelete?.let { move -> ConfirmDeleteMove(move, onDeleteMove) { pendingDelete = null } }
}

/** Строка движения: количество со знаком, дата, сумма, заказ, отметка оплаты. */
@Composable
private fun StockMoveRow(move: StockMoveDto, showItem: Boolean, busy: Boolean, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val qty = formatQuantity(if (move.isIncome) move.qtyMilli else -move.qtyMilli)
                Text(
                    "${if (move.isIncome) "+" else "−"}$qty ${move.unit}",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (move.isIncome) Success else Warning,
                )
                if (showItem) {
                    Text(
                        move.itemName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (move.paidFromCash) StatusChip("из кассы", MaterialTheme.colorScheme.tertiary)
            }
            Text(
                listOf(
                    formatShortDate(move.createdAt),
                    if (move.costKop > 0) formatMoney(move.costKop) else "",
                    move.orderTitle.takeIf { it.isNotBlank() }?.let { "заказ «$it»" }.orEmpty(),
                    move.note,
                ).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete, enabled = !busy, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Убрать запись",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ConfirmDeleteMove(move: StockMoveDto, onDelete: (StockMoveDto) -> Unit, onDismiss: () -> Unit) {
    val qty = formatQuantity(if (move.isIncome) move.qtyMilli else -move.qtyMilli)
    DoubleConfirmDialog(
        what = if (move.isIncome) "приход $qty ${move.unit}" else "списание $qty ${move.unit}",
        consequences = buildString {
            append("Остаток «${move.itemName}» пересчитается")
            append(if (move.isIncome) " — станет меньше." else " — материал вернётся на склад.")
            if (move.paidFromCash) append(" Расход ${formatMoney(move.costKop)} в кассе тоже уберётся.")
            if (move.orderTitle.isNotBlank()) append(" Строка в заказе «${move.orderTitle}» останется, но будет не списана.")
        },
        onConfirm = {
            onDelete(move)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

// ---------------------------------------------------------------------------
// Превью для Android Studio: Split или Design справа сверху. Данные — образцы
// из PreviewData, действия — пустые: экран рисуется без сети и базы.
// ---------------------------------------------------------------------------

@Preview(name = "Склад", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun CatalogContentPreview() = PreviewScreen {
    CatalogContent(
        state = CatalogUiState(loading = false, items = PreviewData.catalog),
        actions = object : CatalogActions {},
    )
}
