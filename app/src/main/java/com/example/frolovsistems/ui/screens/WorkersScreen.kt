package com.example.frolovsistems.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frolovsistems.core.net.SalaryType
import com.example.frolovsistems.core.net.WorkerDto
import com.example.frolovsistems.data.WorkersRepository
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.components.CallButton
import com.example.frolovsistems.ui.components.DialogField
import com.example.frolovsistems.ui.components.EmptyState
import com.example.frolovsistems.ui.components.ErrorBanner
import com.example.frolovsistems.ui.components.HintBlock
import com.example.frolovsistems.ui.components.LoadingBox
import com.example.frolovsistems.ui.components.MoneyField
import com.example.frolovsistems.ui.components.SoftCard
import com.example.frolovsistems.ui.components.StatusChip
import com.example.frolovsistems.ui.components.formatMoney
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import com.example.frolovsistems.ui.components.rememberFabScrollState
import com.example.frolovsistems.ui.components.CrmFab
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.tooling.preview.Preview
import com.example.frolovsistems.ui.preview.PreviewScreen
import com.example.frolovsistems.ui.preview.PreviewData

private val RU: Locale = Locale.forLanguageTag("ru")

private fun monthTitle(month: YearMonth): String {
    val name = month.month.getDisplayName(TextStyle.FULL_STANDALONE, RU)
        .replaceFirstChar { it.uppercase(RU) }
    return "$name ${month.year}"
}

/** Ставка со склонением периода: «1 500 ₽/день» или «60 000 ₽/мес». */
private fun salaryLabel(worker: WorkerDto): String =
    formatMoney(worker.salaryKop) + if (worker.salaryType == SalaryType.MONTH) "/мес" else "/день"

data class WorkersUiState(
    val loading: Boolean = true,
    val items: List<WorkerDto> = emptyList(),
    val editing: WorkerDto? = null,
    /** Рабочий, чья карточка с днями открыта шторкой; null — шторка закрыта. */
    val selected: WorkerDto? = null,
    val sheetMonth: YearMonth = YearMonth.now(),
    /** Отмеченные дни выбранного месяца, строки «YYYY-MM-DD». */
    val sheetDays: Set<String> = emptySet(),
    val sheetLoading: Boolean = false,
    /** Сумма в диалоге выплаты; null — диалог закрыт. */
    val payoutKop: Long? = null,
    val payoutBusy: Boolean = false,
    val error: String? = null,
    /** Короткое подтверждение внизу экрана («Выплата ушла в Кассу»). */
    val message: String? = null,
) {
    /** Дни выбранного месяца: в наборе лежат только они, так что это размер. */
    val daysWorked: Int get() = sheetDays.size

    /**
     * Начисление за выбранный месяц: подённым — дни на ставку,
     * окладнику — оклад целиком, даже если отметок нет.
     */
    val accruedKop: Long
        get() {
            val worker = selected ?: return 0
            return if (worker.salaryType == SalaryType.MONTH) {
                worker.salaryKop
            } else {
                worker.salaryKop * daysWorked
            }
        }
}

/**
 * Действия экрана. Их выполняет [WorkersViewModel]; превью в Android Studio
 * передаёт пустую реализацию, и разметка рисуется без сети и базы.
 */
interface WorkersActions {
    fun refresh() {}
    fun startCreate() {}
    fun startEdit(worker: WorkerDto) {}
    fun updateDraft(worker: WorkerDto) {}
    fun cancelEdit() {}
    fun saveDraft() {}
    fun delete(worker: WorkerDto) {}
    fun openWorker(worker: WorkerDto) {}
    fun closeWorker() {}
    fun shiftMonth(delta: Long) {}
    fun toggleDay(date: LocalDate) {}
    fun requestPayout() {}
    fun updatePayout(amountKop: Long) {}
    fun cancelPayout() {}
    fun confirmPayout() {}
    fun clearMessage() {}
}

class WorkersViewModel(
    private val repo: WorkersRepository = ServiceLocator.workers,
) : ViewModel(), WorkersActions {

    private val _state = MutableStateFlow(WorkersUiState())
    val state: StateFlow<WorkersUiState> = _state.asStateFlow()

    init { refresh() }

    override fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            repo.workers(includeInactive = true)
                .onSuccess { list -> _state.update { it.copy(loading = false, items = list) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    // ------------------------------ Редактор ---------------------------------

    override fun startCreate() = _state.update { it.copy(editing = WorkerDto()) }
    override fun startEdit(worker: WorkerDto) = _state.update { it.copy(editing = worker) }
    override fun updateDraft(worker: WorkerDto) = _state.update { it.copy(editing = worker) }
    override fun cancelEdit() = _state.update { it.copy(editing = null) }

    override fun saveDraft() {
        val draft = _state.value.editing ?: return
        if (draft.name.isBlank()) {
            _state.update { it.copy(error = "Укажите имя рабочего") }
            return
        }
        viewModelScope.launch {
            val result = if (draft.id == 0L) {
                repo.createWorker(draft)
            } else {
                repo.updateWorker(draft.id, draft)
            }
            result
                .onSuccess { saved ->
                    _state.update {
                        it.copy(
                            editing = null,
                            // Если карточка этого рабочего открыта — подменяем данные в ней.
                            selected = if (it.selected?.id == saved.id) saved else it.selected,
                        )
                    }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    override fun delete(worker: WorkerDto) {
        viewModelScope.launch {
            repo.deleteWorker(worker.id)
                .onSuccess {
                    _state.update { if (it.selected?.id == worker.id) it.copy(selected = null) else it }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    // --------------------------- Карточка рабочего ---------------------------

    override fun openWorker(worker: WorkerDto) {
        _state.update {
            it.copy(
                selected = worker,
                sheetMonth = YearMonth.now(),
                sheetDays = emptySet(),
                sheetLoading = true,
                payoutKop = null,
            )
        }
        loadDays()
    }

    override fun closeWorker() = _state.update { it.copy(selected = null, payoutKop = null) }

    override fun shiftMonth(delta: Long) {
        _state.update {
            it.copy(sheetMonth = it.sheetMonth.plusMonths(delta), sheetLoading = true)
        }
        loadDays()
    }

    private fun loadDays() {
        val worker = _state.value.selected ?: return
        val month = _state.value.sheetMonth
        viewModelScope.launch {
            repo.workDays(month.atDay(1).toString(), month.atEndOfMonth().toString(), worker.id)
                .onSuccess { list ->
                    _state.update {
                        it.copy(
                            sheetLoading = false,
                            sheetDays = list.mapTo(HashSet()) { day -> day.workDate.take(10) },
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(sheetLoading = false, error = e.message) } }
        }
    }

    /**
     * Переключает отметку дня. Подсветка меняется сразу, без ожидания сервера;
     * при ошибке отметку возвращаем, как была.
     */
    override fun toggleDay(date: LocalDate) {
        val worker = _state.value.selected ?: return
        val iso = date.toString()
        val worked = iso in _state.value.sheetDays
        _state.update {
            it.copy(sheetDays = if (worked) it.sheetDays - iso else it.sheetDays + iso)
        }
        viewModelScope.launch {
            val failure = if (worked) {
                repo.removeWorkDay(worker.id, iso).exceptionOrNull()
            } else {
                repo.setWorkDay(worker.id, iso).exceptionOrNull()
            }
            if (failure != null) {
                _state.update {
                    it.copy(
                        sheetDays = if (worked) it.sheetDays + iso else it.sheetDays - iso,
                        error = failure.message,
                    )
                }
            }
        }
    }

    // ------------------------------- Выплата ---------------------------------

    override fun requestPayout() = _state.update { it.copy(payoutKop = it.accruedKop) }
    override fun updatePayout(amountKop: Long) = _state.update { it.copy(payoutKop = amountKop) }
    override fun cancelPayout() = _state.update { it.copy(payoutKop = null) }

    override fun confirmPayout() {
        val worker = _state.value.selected ?: return
        val amount = _state.value.payoutKop ?: return
        if (amount <= 0) {
            _state.update { it.copy(error = "Сумма выплаты должна быть больше нуля") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(payoutBusy = true) }
            repo.payout(worker.id, amount, _state.value.sheetMonth.toString())
                .onSuccess {
                    _state.update {
                        it.copy(payoutKop = null, payoutBusy = false, message = "Выплата ушла в Кассу")
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(payoutBusy = false, error = e.message) }
                }
        }
    }

    override fun clearMessage() = _state.update { it.copy(message = null) }
}

@Composable
fun WorkersScreen(
    refreshTick: Int = 0,
    viewModel: WorkersViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WorkersContent(
        state = state,
        actions = viewModel,
        refreshTick = refreshTick,
    )
}

/** Разметка экрана без ViewModel: её показывает превью в Android Studio. */
@Composable
fun WorkersContent(
    state: WorkersUiState,
    actions: WorkersActions,
    refreshTick: Int = 0,
) {
    // Плавающая кнопка уезжает при прокрутке вниз и возвращается при прокрутке вверх.
    val fabScroll = rememberFabScrollState()
    var pendingDelete by remember { mutableStateOf<WorkerDto?>(null) }

    LaunchedEffect(Unit) { actions.refresh() }
    // Кнопка «Обновить» в общей шапке.
    LaunchedEffect(refreshTick) { if (refreshTick > 0) actions.refresh() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(fabScroll.connection),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Рабочие", style = MaterialTheme.typography.headlineMedium)
            }
            item {
                HintBlock(
                    "Нажмите на рабочего — откроется месяц с отметками дней и начислением. " +
                        "Меню «⋮» на карточке — правка и удаление.",
                )
            }
            item { ErrorBanner(state.error) }
            if (state.error != null && state.items.isEmpty() && !state.loading) {
                item {
                    TextButton(onClick = actions::refresh) { Text("Повторить") }
                }
            }

            when {
                state.loading && state.items.isEmpty() -> item { LoadingBox() }
                state.items.isEmpty() -> item {
                    EmptyState(
                        title = "Рабочих нет",
                        subtitle = "Добавьте первого кнопкой внизу справа",
                    )
                }
                else -> {
                    items(state.items, key = { it.id }) { worker ->
                        WorkerCard(
                            worker = worker,
                            onOpen = { actions.openWorker(worker) },
                            onEdit = { actions.startEdit(worker) },
                            onDelete = { pendingDelete = worker },
                        )
                    }
                }
            }
        }

        CrmFab(
            icon = Icons.Default.Add,
            contentDescription = "Добавить рабочего",
            onClick = actions::startCreate,
            visible = state.editing == null && state.selected == null && fabScroll.visible,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )

        // Подтверждение действия внизу — живёт пару секунд и гаснет само.
        state.message?.let { message ->
            LaunchedEffect(message) {
                delay(3000)
                actions.clearMessage()
            }
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(20.dp),
            ) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }

    state.editing?.let { draft ->
        WorkerEditorDialog(
            draft = draft,
            onChange = actions::updateDraft,
            onDismiss = actions::cancelEdit,
            onSave = actions::saveDraft,
        )
    }

    state.selected?.let { worker ->
        WorkerSheet(
            worker = worker,
            month = state.sheetMonth,
            days = state.sheetDays,
            daysWorked = state.daysWorked,
            accruedKop = state.accruedKop,
            onShiftMonth = actions::shiftMonth,
            onToggleDay = actions::toggleDay,
            onPayout = actions::requestPayout,
            onDismiss = actions::closeWorker,
        )
    }

    state.payoutKop?.let { amount ->
        val worker = state.selected ?: return@let
        AlertDialog(
            onDismissRequest = { if (!state.payoutBusy) actions.cancelPayout() },
            title = { Text("Выплата: ${worker.name}") },
            text = {
                Column {
                    Text(
                        "За ${monthTitle(state.sheetMonth).lowercase(RU)} " +
                            "деньги уйдут расходом в кассу.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    MoneyField(
                        kop = amount,
                        onKopChange = actions::updatePayout,
                        label = "Сумма выплаты, ₽",
                        enabled = !state.payoutBusy,
                    )
                }
            },
            confirmButton = {
                Button(onClick = actions::confirmPayout, enabled = !state.payoutBusy) {
                    Text("Выплатить")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = actions::cancelPayout,
                    enabled = !state.payoutBusy,
                ) { Text("Отмена") }
            },
        )
    }

    pendingDelete?.let { worker ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить рабочего?") },
            text = { Text("«${worker.name}» будет удалён вместе с отметками дней.") },
            confirmButton = {
                Button(onClick = {
                    actions.delete(worker)
                    pendingDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun WorkerCard(
    worker: WorkerDto,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    SoftCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Неактивный приглушён: он в списке для истории, но не «в строю».
            Column(Modifier.weight(1f).alpha(if (worker.active) 1f else 0.55f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        worker.name,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!worker.active) {
                        StatusChip(text = "Неактивен", color = MaterialTheme.colorScheme.outline)
                    }
                }
                if (worker.position.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        worker.position,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    salaryLabel(worker),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Действия",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Изменить") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text("Удалить") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
        if (worker.phone.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            CallButton(worker.phone, Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkerSheet(
    worker: WorkerDto,
    month: YearMonth,
    days: Set<String>,
    daysWorked: Int,
    accruedKop: Long,
    onShiftMonth: (Long) -> Unit,
    onToggleDay: (LocalDate) -> Unit,
    onPayout: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        ) {
            Text(worker.name, style = MaterialTheme.typography.titleLarge)
            Text(
                listOf(worker.position, salaryLabel(worker))
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            WorkDaysGrid(
                month = month,
                worked = days,
                onShift = onShiftMonth,
                onToggle = onToggleDay,
            )

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Отработано дней: $daysWorked", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Начислено: ${formatMoney(accruedKop)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onPayout,
                enabled = accruedKop > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Выплатить ${formatMoney(accruedKop)}")
            }
        }
    }
}

/** Сетка месяца с отметками дней: тап по числу ставит или снимает рабочий день. */
@Composable
private fun WorkDaysGrid(
    month: YearMonth,
    worked: Set<String>,
    onShift: (Long) -> Unit,
    onToggle: (LocalDate) -> Unit,
) {
    val today = LocalDate.now()
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = { onShift(-1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Предыдущий месяц")
            }
            Text(monthTitle(month), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { onShift(1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Следующий месяц")
            }
        }

        Row(Modifier.fillMaxWidth()) {
            listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { name ->
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        // Первый день сетки: понедельник недели, в которую попадает 1-е число.
        val first = month.atDay(1)
        val gridStart = first.minusDays((first.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())

        repeat(6) { week ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { weekday ->
                    val date = gridStart.plusDays((week * 7 + weekday).toLong())
                    val inMonth = YearMonth.from(date) == month
                    val isWorked = inMonth && date.toString() in worked
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .padding(2.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                if (isWorked) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.Transparent
                                },
                            )
                            .clickable(enabled = inMonth) { onToggle(date) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${date.dayOfMonth}",
                            style = MaterialTheme.typography.labelMedium,
                            color = when {
                                isWorked -> MaterialTheme.colorScheme.onPrimary
                                !inMonth -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                date == today -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkerEditorDialog(
    draft: WorkerDto,
    onChange: (WorkerDto) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == 0L) "Новый рабочий" else "Рабочий") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).imePadding()) {
                DialogField("Имя", draft.name) { onChange(draft.copy(name = it)) }
                DialogField("Телефон", draft.phone) { onChange(draft.copy(phone = it)) }
                DialogField("Должность", draft.position) { onChange(draft.copy(position = it)) }
                Spacer(Modifier.height(4.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(SalaryType.DAY to "За день", SalaryType.MONTH to "Оклад")
                        .forEachIndexed { index, (value, label) ->
                            SegmentedButton(
                                selected = draft.salaryType == value,
                                onClick = { onChange(draft.copy(salaryType = value)) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                            ) { Text(label) }
                        }
                }
                Spacer(Modifier.height(8.dp))
                MoneyField(
                    kop = draft.salaryKop,
                    onKopChange = { onChange(draft.copy(salaryKop = it)) },
                    label = if (draft.salaryType == SalaryType.MONTH) "Оклад, ₽" else "Ставка за день, ₽",
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = draft.active,
                        onCheckedChange = { onChange(draft.copy(active = it)) },
                    )
                    Text("Активен", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { Button(onClick = onSave) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

// ---------------------------------------------------------------------------
// Превью для Android Studio: Split или Design справа сверху. Данные — образцы
// из PreviewData, действия — пустые: экран рисуется без сети и базы.
// ---------------------------------------------------------------------------

@Preview(name = "Рабочие", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun WorkersContentPreview() = PreviewScreen {
    WorkersContent(
        state = WorkersUiState(loading = false, items = PreviewData.workers),
        actions = object : WorkersActions {},
    )
}
