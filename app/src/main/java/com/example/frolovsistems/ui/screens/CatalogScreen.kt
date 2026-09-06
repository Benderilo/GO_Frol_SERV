package com.example.frolovsistems.ui.screens

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

class CatalogViewModel(
    private val crm: CrmRepository = ServiceLocator.crm,
) : ViewModel() {

    private val _state = MutableStateFlow(CatalogUiState())
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()

    init { refresh() }

    fun onQuery(value: String) = _state.update { it.copy(query = value) }

    fun refresh() {
        viewModelScope.launch {
            val current = _state.value
            _state.update { it.copy(loading = true, error = null) }
            crm.catalog(current.kindFilter, current.withArchived)
                .onSuccess { list -> _state.update { it.copy(loading = false, items = list) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun setKind(kind: String) {
        _state.update { it.copy(kindFilter = kind) }
        refresh()
    }

    fun toggleArchived() {
        _state.update { it.copy(withArchived = !it.withArchived) }
        refresh()
    }

    fun startCreate() = _state.update {
        // Новая позиция наследует вид из фильтра: если человек смотрит
        // материалы, он почти наверняка добавляет материал.
        it.copy(editing = CatalogItemDto(kind = it.kindFilter.ifBlank { CatalogKind.MATERIAL }))
    }

    fun startEdit(item: CatalogItemDto) = _state.update { it.copy(editing = item) }
    fun changeDraft(item: CatalogItemDto) = _state.update { it.copy(editing = item) }
    fun cancelEdit() = _state.update { it.copy(editing = null) }

    fun save() {
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

    fun delete(item: CatalogItemDto) {
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

    fun openStock(item: CatalogItemDto) {
        _state.update { it.copy(stockFor = item, moves = emptyList(), error = null) }
        viewModelScope.launch {
            crm.stockMoves(item.id)
                .onSuccess { list -> _state.update { it.copy(moves = list) } }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun closeStock() = _state.update { it.copy(stockFor = null, moves = emptyList()) }

    /** Приход — положительное количество, списание — отрицательное. */
    fun addMove(qtyMilli: Long, costKop: Long, note: String) {
        val item = _state.value.stockFor ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            crm.addStockMove(item.id, qtyMilli, costKop, note)
                .onSuccess { reloadStock(item.id) }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message) } }
        }
    }

    fun deleteMove(move: StockMoveDto) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            crm.deleteStockMove(move.id)
                .onSuccess { reloadStock(move.itemId) }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message) } }
        }
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

    LaunchedEffect(Unit) { viewModel.refresh() }
    // Кнопка «Обновить» в общей шапке.
    LaunchedEffect(refreshTick) { if (refreshTick > 0) viewModel.refresh() }

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
            item {
                Text("Склад", style = MaterialTheme.typography.headlineMedium)
            }

            item { ErrorBanner(state.error) }

            item {
                SearchField(
                    query = state.query,
                    onQuery = viewModel::onQuery,
                    placeholder = "Поиск по названию и заметке",
                )
            }

            item {
                var filtersExpanded by rememberSaveable { mutableStateOf(false) }
                val activeLabel = listOfNotNull(
                    if (state.kindFilter.isBlank()) null else CatalogKind.plural(state.kindFilter),
                    if (state.withArchived) "С архивом" else null,
                ).joinToString(", ").ifBlank { null }
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
                            onClick = { viewModel.setKind("") },
                            label = { Text("Все") },
                        )
                        CatalogKind.all.forEach { kind ->
                            FilterChip(
                                selected = state.kindFilter == kind,
                                onClick = { viewModel.setKind(kind) },
                                label = { Text(CatalogKind.plural(kind)) },
                            )
                        }
                        FilterChip(
                            selected = state.withArchived,
                            onClick = { viewModel.toggleArchived() },
                            label = { Text("С архивом") },
                        )
                    }
                }
            }

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
                        onEdit = { viewModel.startEdit(item) },
                        onStock = { viewModel.openStock(item) },
                    )
                }
            }
        }
        }

        FloatingActionButton(
            onClick = viewModel::startCreate,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = "Добавить позицию")
        }
    }

    state.editing?.let { draft ->
        CatalogItemDialog(
            draft = draft,
            busy = state.busy,
            onChange = viewModel::changeDraft,
            onDismiss = viewModel::cancelEdit,
            onSave = viewModel::save,
            onDelete = { viewModel.delete(draft) },
        )
    }

    state.stockFor?.let { item ->
        StockDialog(
            item = item,
            moves = state.moves,
            busy = state.busy,
            onDismiss = viewModel::closeStock,
            onAdd = viewModel::addMove,
            onDeleteMove = viewModel::deleteMove,
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
        item.archived -> MaterialTheme.colorScheme.outline
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
                        StatusChip("архив", MaterialTheme.colorScheme.outline)
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
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == 0L) "Новая позиция" else "Позиция справочника") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CatalogKind.all.forEach { kind ->
                        FilterChip(
                            selected = draft.kind == kind,
                            onClick = { onChange(draft.copy(kind = kind)) },
                            label = { Text(CatalogKind.label(kind)) },
                            enabled = !busy,
                        )
                    }
                }

                DialogField("Наименование", draft.name) { onChange(draft.copy(name = it)) }
                DialogField("Единица измерения", draft.unit) { onChange(draft.copy(unit = it)) }

                MoneyField(
                    kop = draft.priceKop,
                    onKopChange = { onChange(draft.copy(priceKop = it)) },
                    label = "Цена продажи, ₽",
                    enabled = !busy,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                MoneyField(
                    kop = draft.costKop,
                    onKopChange = { onChange(draft.copy(costKop = it)) },
                    label = "Цена закупки, ₽",
                    enabled = !busy,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                DialogField("Заметка", draft.note, lines = 2) { onChange(draft.copy(note = it)) }

                FilterChip(
                    selected = draft.archived,
                    onClick = { onChange(draft.copy(archived = !draft.archived)) },
                    label = { Text("Архивная — не показывать в списке") },
                    enabled = !busy,
                )
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = !busy && draft.name.isNotBlank()) { Text("Сохранить") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (draft.id != 0L) {
                    TextButton(onClick = { confirmDelete = true }, enabled = !busy) {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить «${draft.name}»?") },
            text = {
                Text(
                    "Если по позиции уже есть движения склада, сервер удалить не даст — " +
                        "тогда отметьте её архивной, чтобы убрать из списка, а история останется.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun StockDialog(
    item: CatalogItemDto,
    moves: List<StockMoveDto>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onAdd: (Long, Long, String) -> Unit,
    onDeleteMove: (StockMoveDto) -> Unit,
) {
    var qtyMilli by remember { mutableStateOf(0L) }
    var costKop by remember { mutableStateOf(0L) }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "На складе ${formatQuantity(item.stockMilli)} ${item.unit}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (item.stockMilli > 0) Success else Warning,
                )
                Spacer(Modifier.height(12.dp))

                QuantityField(
                    milli = qtyMilli,
                    onMilliChange = { qtyMilli = it },
                    label = "Количество, ${item.unit}",
                    enabled = !busy,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                MoneyField(
                    kop = costKop,
                    onKopChange = { costKop = it },
                    label = "Сумма, ₽ (необязательно)",
                    enabled = !busy,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                DialogField("Заметка", note) { note = it }

                // Направление задаётся кнопкой, а не знаком в поле: минус
                // в количестве легко не заметить и списать вместо прихода.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onAdd(qtyMilli, costKop, note.trim())
                            qtyMilli = 0L
                            costKop = 0L
                            note = ""
                        },
                        enabled = !busy && qtyMilli > 0L,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    ) { Text("Приход") }
                    OutlinedButton(
                        onClick = {
                            onAdd(-qtyMilli, costKop, note.trim())
                            qtyMilli = 0L
                            costKop = 0L
                            note = ""
                        },
                        enabled = !busy && qtyMilli > 0L,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    ) { Text("Списание") }
                }

                if (moves.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("История", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    moves.forEach { move ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${if (move.isIncome) "+" else "−"}" +
                                        "${formatQuantity(if (move.isIncome) move.qtyMilli else -move.qtyMilli)} " +
                                        item.unit,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (move.isIncome) Success else Warning,
                                )
                                Text(
                                    buildString {
                                        append(formatShortDate(move.createdAt))
                                        if (move.costKop > 0) {
                                            append(" • ")
                                            append(formatMoney(move.costKop))
                                        }
                                        if (move.orderTitle.isNotBlank()) {
                                            append(" • ")
                                            append(move.orderTitle)
                                        }
                                        if (move.note.isNotBlank()) {
                                            append(" • ")
                                            append(move.note)
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onDeleteMove(move) }, enabled = !busy) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Убрать запись",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
