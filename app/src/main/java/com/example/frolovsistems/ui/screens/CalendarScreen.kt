package com.example.frolovsistems.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frolovsistems.core.net.OrderDto
import com.example.frolovsistems.core.net.RequestDto
import com.example.frolovsistems.core.net.TaskDto
import com.example.frolovsistems.data.CrmRepository
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.components.EmptyState
import com.example.frolovsistems.ui.components.ErrorBanner
import com.example.frolovsistems.ui.components.LoadingBox
import com.example.frolovsistems.ui.components.StatusChip
import com.example.frolovsistems.ui.components.StatusRecordCard
import com.example.frolovsistems.ui.components.CallButton
import com.example.frolovsistems.ui.components.dateOf
import com.example.frolovsistems.ui.components.orderStatusColor
import com.example.frolovsistems.ui.components.orderStatusLabel
import com.example.frolovsistems.ui.components.parseLocalDate
import com.example.frolovsistems.ui.components.requestStatusColor
import com.example.frolovsistems.ui.components.requestStatusLabel
import com.example.frolovsistems.ui.components.formatMoney
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

/** Тип события календаря — по нему работают фильтры сверху. */
enum class EventKind(val label: String, val single: String) {
    ORDER("Заказы", "Заказ"),
    REQUEST("Заявки с сайта", "Заявка"),
    TASK("Задачи", "Задача"),
}

/** Одна запись календаря: под какое число её ставить и чем её открывать. */
data class CalendarEvent(
    val kind: EventKind,
    val id: Long,
    val title: String,
    val subtitle: String,
    val statusLabel: String,
    val date: LocalDate,
    val order: OrderDto? = null,
    val request: RequestDto? = null,
    val task: TaskDto? = null,
)

enum class CalendarMode(val label: String) { YEAR("Год"), MONTH("Месяц"), DAY("День") }

data class CalendarUiState(
    val loading: Boolean = true,
    val orders: List<OrderDto> = emptyList(),
    val requests: List<RequestDto> = emptyList(),
    val tasks: List<TaskDto> = emptyList(),
    /** Какие типы событий видны — фильтры сверху, включены все. */
    val hidden: Set<EventKind> = emptySet(),
    val mode: CalendarMode = CalendarMode.MONTH,
    val selectedDate: LocalDate = LocalDate.now(),
    /** Месяц и год, которые крутят стрелки; по умолчанию — текущие. */
    val focusMonth: YearMonth = YearMonth.now(),
    val focusYear: Int = LocalDate.now().year,
    val opened: CalendarEvent? = null,
    val error: String? = null,
)

class CalendarViewModel(
    private val crm: CrmRepository = ServiceLocator.crm,
) : ViewModel() {

    private val _state = MutableStateFlow(CalendarUiState())
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val orders = crm.orders()
            val requests = crm.requests()
            val tasks = crm.tasks()
            _state.update { cur ->
                cur.copy(
                    loading = false,
                    orders = orders.getOrNull() ?: cur.orders,
                    requests = requests.getOrNull() ?: cur.requests,
                    tasks = tasks.getOrNull() ?: cur.tasks,
                    error = orders.exceptionOrNull()?.message
                        ?: requests.exceptionOrNull()?.message
                        ?: tasks.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun toggleKind(kind: EventKind) = _state.update {
        it.copy(hidden = if (kind in it.hidden) it.hidden - kind else it.hidden + kind)
    }

    fun setMode(mode: CalendarMode) = _state.update { it.copy(mode = mode) }

    fun selectDate(date: LocalDate) = _state.update {
        it.copy(selectedDate = date, focusMonth = YearMonth.from(date), focusYear = date.year)
    }

    fun shiftMonth(delta: Long) = _state.update {
        val month = it.focusMonth.plusMonths(delta)
        it.copy(focusMonth = month, focusYear = month.year)
    }

    fun shiftDay(delta: Long) = _state.update {
        val date = it.selectedDate.plusDays(delta)
        it.copy(selectedDate = date, focusMonth = YearMonth.from(date))
    }

    fun shiftYear(delta: Int) = _state.update { it.copy(focusYear = it.focusYear + delta) }

    fun open(event: CalendarEvent?) = _state.update { it.copy(opened = event) }

    /** Действия из диалога события: довести заявку/заказ/задачу до следующего статуса. */
    fun setRequestStatus(event: CalendarEvent, status: String) {
        viewModelScope.launch {
            crm.setRequestStatus(event.id, status)
                .onSuccess { _state.update { it.copy(opened = null) }; refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun setOrderStatus(event: CalendarEvent, status: String) {
        val order = event.order ?: return
        viewModelScope.launch {
            crm.updateOrder(order.id, order.copy(status = status))
                .onSuccess { _state.update { it.copy(opened = null) }; refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun setTaskDone(event: CalendarEvent, done: Boolean) {
        viewModelScope.launch {
            crm.setTaskDone(event.id, done)
                .onSuccess { _state.update { it.copy(opened = null) }; refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }
}

/** Дата, под которую ставится событие: срок, а если его нет — день создания. */
private fun orderDate(order: OrderDto): LocalDate? =
    parseLocalDate(order.dueDate) ?: dateOf(order.createdAt)

private fun requestDate(request: RequestDto): LocalDate? = dateOf(request.createdAt)

private fun taskDate(task: TaskDto): LocalDate? =
    parseLocalDate(task.dueDate) ?: dateOf(task.createdAt)

private val RU: Locale = Locale.forLanguageTag("ru")

private fun monthTitle(month: YearMonth): String {
    val name = month.month.getDisplayName(TextStyle.FULL_STANDALONE, RU)
        .replaceFirstChar { it.uppercase(RU) }
    return "$name ${month.year}"
}

private fun dayTitle(date: LocalDate): String {
    val dow = date.dayOfWeek.getDisplayName(TextStyle.FULL, RU)
    val month = date.month.getDisplayName(TextStyle.FULL, RU)
    return "${date.dayOfMonth} $month, $dow"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onBack: () -> Unit = {},
    refreshTick: Int = 0,
    viewModel: CalendarViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refresh() }
    // Кнопка «Обновить» в общей шапке.
    LaunchedEffect(refreshTick) { if (refreshTick > 0) viewModel.refresh() }

    // События собираются из трёх списков; ищем один раз, а не при каждой перерисовке.
    val allEvents = remember(state.orders, state.requests, state.tasks) {
        buildList {
            state.orders.forEach { order ->
                orderDate(order)?.let { date ->
                    add(
                        CalendarEvent(
                            kind = EventKind.ORDER,
                            id = order.id,
                            title = order.title,
                            subtitle = order.clientName,
                            statusLabel = orderStatusLabel(order.status),
                            date = date,
                            order = order,
                        ),
                    )
                }
            }
            state.requests.forEach { request ->
                requestDate(request)?.let { date ->
                    add(
                        CalendarEvent(
                            kind = EventKind.REQUEST,
                            id = request.id,
                            title = request.name,
                            subtitle = request.message,
                            statusLabel = requestStatusLabel(request.status),
                            date = date,
                            request = request,
                        ),
                    )
                }
            }
            state.tasks.forEach { task ->
                taskDate(task)?.let { date ->
                    add(
                        CalendarEvent(
                            kind = EventKind.TASK,
                            id = task.id,
                            title = task.title,
                            subtitle = task.clientName,
                            statusLabel = if (task.done) "Выполнена" else "К сроку",
                            date = date,
                            task = task,
                        ),
                    )
                }
            }
        }
    }

    val visibleEvents = allEvents.filter { it.kind !in state.hidden }
    val byDay = visibleEvents.groupBy { it.date }
    val today = LocalDate.now()

    // День, чьи события показаны шторкой снизу; null — шторка закрыта.
    var sheetDate by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(state.mode) { sheetDate = null }

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
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                    Text(
                        "Календарь",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    CalendarMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = state.mode == mode,
                            onClick = { viewModel.setMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = CalendarMode.entries.size,
                            ),
                        ) { Text(mode.label) }
                    }
                }
            }

            item {
                // Фильтры: чип выключен — события типа прячутся и из сетки, и из списков.
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EventKind.entries.forEach { kind ->
                        FilterChip(
                            selected = kind !in state.hidden,
                            onClick = { viewModel.toggleKind(kind) },
                            label = { Text(kind.label) },
                        )
                    }
                }
            }

            item { ErrorBanner(state.error) }

            if (state.loading && allEvents.isEmpty()) {
                item { LoadingBox() }
            } else {
                when (state.mode) {
                    CalendarMode.MONTH -> {
                        item {
                            MonthGrid(
                                month = state.focusMonth,
                                events = byDay,
                                selected = state.selectedDate,
                                today = today,
                                onShift = viewModel::shiftMonth,
                                onSelect = { date ->
                                    viewModel.selectDate(date)
                                    sheetDate = date
                                },
                            )
                        }
                        item {
                            Text(
                                "Нажмите на день — все его события откроются шторкой снизу",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    CalendarMode.DAY -> {
                        item {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                IconButton(onClick = { viewModel.shiftDay(-1) }) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Предыдущий день")
                                }
                                Text(dayTitle(state.selectedDate), style = MaterialTheme.typography.titleMedium)
                                IconButton(onClick = { viewModel.shiftDay(1) }) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Следующий день")
                                }
                            }
                        }
                        dayEventsItems(byDay[state.selectedDate].orEmpty(), viewModel, state.selectedDate)
                    }
                    CalendarMode.YEAR -> {
                        item {
                            YearGrid(
                                year = state.focusYear,
                                events = visibleEvents,
                                onShift = viewModel::shiftYear,
                                onOpenMonth = { month ->
                                    viewModel.selectDate(state.selectedDate.withYear(month.year)
                                        .withMonth(month.monthValue))
                                    viewModel.setMode(CalendarMode.MONTH)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // Шторка по нажатию на день: все события суток, прокручивается внутри,
    // когда их много. Открытая из строки запись всплывает диалогом поверх шторки.
    sheetDate?.let { date ->
        val events = remember(byDay, date) { byDay[date].orEmpty().sortedBy { it.kind.ordinal } }
        ModalBottomSheet(
            onDismissRequest = { sheetDate = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.5f)) {
                Text(
                    dayTitle(date),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                )
                if (events.isEmpty()) {
                    EmptyState(
                        title = "На этот день ничего нет",
                        subtitle = "Заявки с сайта попадают сюда автоматически, " +
                            "заказы — по сроку, задачи — по дате напоминания",
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(events, key = { "${it.kind}-${it.id}" }) { event ->
                            EventRow(event) { viewModel.open(event) }
                        }
                    }
                }
            }
        }
    }

    state.opened?.let { event ->
        EventDialog(
            event = event,
            onDismiss = { viewModel.open(null) },
            onRequestStatus = { viewModel.setRequestStatus(event, it) },
            onOrderStatus = { viewModel.setOrderStatus(event, it) },
            onTaskDone = { viewModel.setTaskDone(event, it) },
        )
    }
}

/** Список событий дня — общий для режимов «Месяц» и «День». */
private fun androidx.compose.foundation.lazy.LazyListScope.dayEventsItems(
    events: List<CalendarEvent>,
    viewModel: CalendarViewModel,
    selectedDate: LocalDate,
) {
    item {
        Text(
            dayTitle(selectedDate),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (events.isEmpty()) {
        item {
            EmptyState(
                title = "На этот день ничего нет",
                subtitle = "Заявки с сайта попадают сюда автоматически, заказы — по сроку, задачи — по дате напоминания",
            )
        }
    } else {
        items(events.sortedBy { it.kind.ordinal }, key = { "${it.kind}-${it.id}" }) { event ->
            EventRow(event) { viewModel.open(event) }
        }
    }
}

/** Цвет события: от статуса записи, чтобы календарь читался как списки. */
@Composable
private fun eventColor(event: CalendarEvent): Color = when (event.kind) {
    EventKind.ORDER -> orderStatusColor(event.order?.status.orEmpty())
    EventKind.REQUEST -> requestStatusColor(event.request?.status.orEmpty())
    EventKind.TASK -> if (event.task?.done == true) {
        MaterialTheme.colorScheme.outline
    } else {
        MaterialTheme.colorScheme.tertiary
    }
}

@Composable
private fun EventRow(event: CalendarEvent, onClick: () -> Unit) {
    StatusRecordCard(accent = eventColor(event), onClick = onClick) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(event.title, style = MaterialTheme.typography.titleSmall)
                if (event.subtitle.isNotBlank()) {
                    Text(
                        event.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    event.kind.single,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                StatusChip(text = event.statusLabel, color = eventColor(event))
            }
        }
    }
}

/** Сетка месяца: 6 недель по 7 дней, неделя с понедельника. */
@Composable
private fun MonthGrid(
    month: YearMonth,
    events: Map<LocalDate, List<CalendarEvent>>,
    selected: LocalDate,
    today: LocalDate,
    onShift: (Long) -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
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
                    DayCell(
                        date = date,
                        inMonth = YearMonth.from(date) == month,
                        isToday = date == today,
                        isSelected = date == selected,
                        dayEvents = events[date].orEmpty().take(3),
                        onClick = { onSelect(date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    dayEvents: List<CalendarEvent>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(
                when {
                    isSelected -> MaterialTheme.colorScheme.primaryContainer
                    else -> Color.Transparent
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = if (isToday) {
                    Modifier
                        .size(24.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                } else {
                    Modifier
                },
            ) {
                Text(
                    "${date.dayOfMonth}",
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        isToday -> MaterialTheme.colorScheme.onPrimary
                        !inMonth -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            // До трёх точек — больше в ячейку всё равно не влезает.
            if (dayEvents.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    dayEvents.forEach { event ->
                        Box(
                            Modifier
                                .size(5.dp)
                                .background(eventColor(event), CircleShape),
                        )
                    }
                }
            }
        }
    }
}

/** Год: 12 мини-карточек с числом событий; тап открывает месяц. */
@Composable
private fun YearGrid(
    year: Int,
    events: List<CalendarEvent>,
    onShift: (Int) -> Unit,
    onOpenMonth: (YearMonth) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = { onShift(-1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Прошлый год")
            }
            Text("$year", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { onShift(1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Следующий год")
            }
        }
        Spacer(Modifier.height(8.dp))

        val counts = events.groupingBy { YearMonth.from(it.date) }.eachCount()
        val current = YearMonth.now()

        repeat(4) { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                repeat(3) { col ->
                    val month = YearMonth.of(year, row * 3 + col + 1)
                    val count = counts[month] ?: 0
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(4.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(
                                if (month == current) {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                },
                            )
                            .clickable { onOpenMonth(month) }
                            .padding(12.dp),
                    ) {
                        Text(
                            month.month.getDisplayName(TextStyle.SHORT_STANDALONE, RU)
                                .replaceFirstChar { it.uppercase(RU) },
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (count > 0) "$count соб." else "—",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (count > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Диалог события: детали и кнопки «довести до следующего шага». */
@Composable
private fun EventDialog(
    event: CalendarEvent,
    onDismiss: () -> Unit,
    onRequestStatus: (String) -> Unit,
    onOrderStatus: (String) -> Unit,
    onTaskDone: (Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(event.title) },
        text = {
            Column {
                Text(
                    "${event.kind.label} • ${event.statusLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (event.subtitle.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(event.subtitle, style = MaterialTheme.typography.bodyMedium)
                }
                event.order?.let { order ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        formatMoney(order.priceKop),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                event.request?.let { request ->
                    if (request.phone.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        CallButton(request.phone, Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            // Главная кнопка диалога — следующий логичный шаг по цепочке.
            when (event.kind) {
                EventKind.REQUEST -> when (event.request?.status) {
                    "new" -> Button(onClick = { onRequestStatus("in_progress") }) { Text("Взять в работу") }
                    "in_progress" -> Button(onClick = { onRequestStatus("done") }) { Text("Обработана") }
                    else -> TextButton(onClick = { onRequestStatus("spam") }) { Text("В спам") }
                }
                EventKind.ORDER -> when (event.order?.status) {
                    "new" -> Button(onClick = { onOrderStatus("in_progress") }) { Text("В работу") }
                    "in_progress" -> Button(onClick = { onOrderStatus("done") }) { Text("Завершить") }
                    else -> TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
                EventKind.TASK -> {
                    if (event.task?.done == true) {
                        TextButton(onClick = { onTaskDone(false) }) { Text("Вернуть в работу") }
                    } else {
                        Button(onClick = { onTaskDone(true) }) { Text("Выполнено") }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
