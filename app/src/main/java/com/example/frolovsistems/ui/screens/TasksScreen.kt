package com.example.frolovsistems.ui.screens

import com.example.frolovsistems.ui.components.DoubleConfirmDialog
import com.example.frolovsistems.ui.components.DangerZone
import com.example.frolovsistems.ui.components.CrmDialog
import androidx.compose.ui.text.style.TextOverflow
import com.example.frolovsistems.ui.components.ListWindow
import com.example.frolovsistems.ui.components.CompactCardPadding
import com.example.frolovsistems.ui.components.ListItemSpacing
import com.example.frolovsistems.ui.components.ListContentPadding
import com.example.frolovsistems.ui.components.ListHeader
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frolovsistems.core.net.TaskDto
import com.example.frolovsistems.data.CrmRepository
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.components.DatePickerField
import com.example.frolovsistems.ui.components.DialogField
import com.example.frolovsistems.ui.components.EmptyState
import com.example.frolovsistems.ui.components.ErrorBanner
import com.example.frolovsistems.ui.components.HintBlock
import com.example.frolovsistems.ui.components.LoadingBox
import com.example.frolovsistems.ui.components.SoftCard
import com.example.frolovsistems.ui.components.StatusChip
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

private val taskFilters = listOf(
    "" to "Активные",
    "today" to "На сегодня",
    "overdue" to "Просроченные",
    "done" to "Выполненные",
)

/** Строка дерева задач: сама задача, глубина и счётчики подзадач. */
private data class TaskRow(
    val task: TaskDto,
    val depth: Int,
    val childCount: Int,
    val childrenDone: Int,
)

/**
 * Раскладывает плоский список в дерево без ограничения глубины:
 * подзадача идёт сразу под родителем. Задачу, чей родитель не пришёл
 * в текущей выборке (стоит серверный фильтр), показываем как корневую.
 */
private fun taskRows(items: List<TaskDto>): List<TaskRow> {
    val known = items.mapTo(HashSet()) { it.id }
    val byParent = items.groupBy { if (it.parentId != null && it.parentId in known) it.parentId else null }
    val rows = ArrayList<TaskRow>(items.size)
    fun append(task: TaskDto, depth: Int) {
        val kids = byParent[task.id].orEmpty()
        rows += TaskRow(task, depth, kids.size, kids.count { it.done })
        kids.forEach { append(it, depth + 1) }
    }
    byParent[null].orEmpty().forEach { append(it, 0) }
    return rows
}

data class TasksUiState(
    val loading: Boolean = true,
    val filter: String = "",
    val items: List<TaskDto> = emptyList(),
    val editing: TaskDto? = null,
    val error: String? = null,
)

/**
 * Действия экрана. Их выполняет [TasksViewModel]; превью в Android Studio
 * передаёт пустую реализацию, и разметка рисуется без сети и базы.
 */
interface TasksActions {
    fun refresh() {}
    fun setFilter(filter: String) {}
    fun startCreate(parentId: Long? = null) {}
    fun startEdit(task: TaskDto) {}
    fun updateDraft(task: TaskDto) {}
    fun cancelEdit() {}
    fun saveDraft() {}
    fun toggleDone(task: TaskDto) {}
    fun delete(task: TaskDto) {}
}

class TasksViewModel(
    private val crm: CrmRepository = ServiceLocator.crm,
) : ViewModel(), TasksActions {

    private val _state = MutableStateFlow(TasksUiState())
    val state: StateFlow<TasksUiState> = _state.asStateFlow()

    init { refresh() }

    override fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            crm.tasks(_state.value.filter)
                .onSuccess { list -> _state.update { it.copy(loading = false, items = list) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    override fun setFilter(filter: String) {
        _state.update { it.copy(filter = filter) }
        refresh()
    }

    override fun startCreate(parentId: Long?) =
        _state.update { it.copy(editing = TaskDto(parentId = parentId)) }
    override fun startEdit(task: TaskDto) = _state.update { it.copy(editing = task) }
    override fun updateDraft(task: TaskDto) = _state.update { it.copy(editing = task) }
    override fun cancelEdit() = _state.update { it.copy(editing = null) }

    override fun saveDraft() {
        val draft = _state.value.editing ?: return
        if (draft.title.isBlank()) {
            _state.update { it.copy(error = "Укажите текст задачи") }
            return
        }
        viewModelScope.launch {
            val result = if (draft.id == 0L) crm.createTask(draft) else crm.updateTask(draft.id, draft)
            result
                .onSuccess {
                    _state.update { it.copy(editing = null) }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    override fun toggleDone(task: TaskDto) {
        viewModelScope.launch {
            crm.setTaskDone(task.id, !task.done)
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    override fun delete(task: TaskDto) {
        viewModelScope.launch {
            crm.deleteTask(task.id)
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }
}

@Composable
fun TasksScreen(
    refreshTick: Int = 0,
    viewModel: TasksViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TasksContent(
        state = state,
        actions = viewModel,
        refreshTick = refreshTick,
    )
}

/** Разметка экрана без ViewModel: её показывает превью в Android Studio. */
@Composable
fun TasksContent(
    state: TasksUiState,
    actions: TasksActions,
    refreshTick: Int = 0,
) {
    // Плавающая кнопка уезжает при прокрутке вниз и возвращается при прокрутке вверх.
    val fabScroll = rememberFabScrollState()

    LaunchedEffect(Unit) { actions.refresh() }
    // Кнопка «Обновить» в общей шапке.
    LaunchedEffect(refreshTick) { if (refreshTick > 0) actions.refresh() }

    // Плоский список от сервера раскладываем в дерево: подзадачи идут под родителями.
    val rows = remember(state.items) { taskRows(state.items) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ListHeader {
                Text("Задачи", style = MaterialTheme.typography.headlineMedium)
                HintBlock(
                    "Новая задача — плюс внизу экрана. Задачу можно привязать к клиенту " +
                        "или заказу, а подзадачи заводятся внутри открытой задачи. " +
                        "Готовая отмечается галочкой.",
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    taskFilters.forEach { (value, label) ->
                        FilterChip(
                            selected = state.filter == value,
                            onClick = { actions.setFilter(value) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            ListWindow(Modifier.weight(1f)) {
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
                                title = "Задач нет",
                                subtitle = "Добавьте напоминание кнопкой внизу справа",
                            )
                        }
                        else -> {
                            items(rows, key = { it.task.id }) { row ->
                                TaskCard(
                                    row = row,
                                    onToggle = { actions.toggleDone(row.task) },
                                    onEdit = { actions.startEdit(row.task) },
                                    onAddSubtask = { actions.startCreate(parentId = row.task.id) },
                                )
                            }
                        }
                    }
                }
            }
        }

        CrmFab(
            icon = Icons.Default.Add,
            contentDescription = "Добавить задачу",
            onClick = { actions.startCreate() },
            visible = state.editing == null && fabScroll.visible,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }

    state.editing?.let { draft ->
        TaskEditorDialog(
            draft = draft,
            parentTitle = state.items.firstOrNull { it.id == draft.parentId }?.title,
            onChange = actions::updateDraft,
            onDismiss = actions::cancelEdit,
            onSave = actions::saveDraft,
            onDelete = {
                actions.cancelEdit()
                actions.delete(draft)
            },
        )
    }

}

@Composable
private fun TaskCard(
    row: TaskRow,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onAddSubtask: () -> Unit,
) {
    val task = row.task
    // Глубокая вложенность не ломает верстку: дальше 4 уровней отступ не растёт.
    SoftCard(
        onClick = onEdit,
        modifier = Modifier.padding(start = (row.depth.coerceAtMost(4) * 16).dp),
        contentPadding = CompactCardPadding,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = task.done,
                onCheckedChange = { onToggle() },
            )
            Column(Modifier.weight(1f)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.titleSmall,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null,
                    color = if (task.done) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (task.clientName.isNotBlank() || task.dueDate.isNotBlank() || row.childCount > 0) {
                    Spacer(Modifier.height(2.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (task.dueDate.isNotBlank()) {
                            Text(
                                "срок ${task.dueDate.take(10)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isOverdue(task)) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        if (task.clientName.isNotBlank()) {
                            StatusChip(
                                text = task.clientName,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        if (row.childCount > 0) {
                            StatusChip(
                                text = "Подзадачи ${row.childrenDone}/${row.childCount}",
                                color = if (row.childrenDone == row.childCount) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
            // Подзадач можно добавлять к любой задаче — вложенность не ограничена.
            IconButton(onClick = onAddSubtask, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Добавить подзадачу",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (task.note.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                task.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun isOverdue(task: TaskDto): Boolean {
    if (task.done || task.dueDate.isBlank()) return false
    return runCatching {
        java.time.LocalDate.parse(task.dueDate.take(10)).isBefore(java.time.LocalDate.now())
    }.getOrDefault(false)
}

@Composable
private fun TaskEditorDialog(
    draft: TaskDto,
    parentTitle: String?,
    onChange: (TaskDto) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit = {},
) {
    CrmDialog(
        title = when {
                    draft.id == 0L && parentTitle != null -> "Подзадача"
                    draft.id == 0L -> "Новая задача"
                    else -> "Задача"
                },
        onDismiss = onDismiss,
        confirmText = "Сохранить",
        onConfirm = onSave,
    ) {
        Column {
            if (parentTitle != null) {
                Text(
                    "Внутри задачи «$parentTitle»",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            DialogField("Что нужно сделать", draft.title) { onChange(draft.copy(title = it)) }
            DialogField("Заметка", draft.note, lines = 2) { onChange(draft.copy(note = it)) }
            DatePickerField("Срок", draft.dueDate, iso = true) {
                onChange(draft.copy(dueDate = it))
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("normal" to "Обычно", "high" to "Важно", "low" to "Не срочно").forEach { (value, label) ->
                    FilterChip(
                        selected = draft.priority == value,
                        onClick = { onChange(draft.copy(priority = value)) },
                        label = { Text(label) },
                    )
                }
            }
        }
        if (draft.id != 0L) {
            DangerZone(
                actionLabel = "Удалить задачу",
                what = "задачу «${draft.title}»",
                consequences = "Задача пропадёт из списка и календаря. Подзадачи останутся, " +
                    "но поднимутся на уровень выше.",
                onConfirm = onDelete,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Превью для Android Studio: Split или Design справа сверху. Данные — образцы
// из PreviewData, действия — пустые: экран рисуется без сети и базы.
// ---------------------------------------------------------------------------

@Preview(name = "Задачи", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun TasksContentPreview() = PreviewScreen {
    TasksContent(
        state = TasksUiState(loading = false, items = PreviewData.tasks),
        actions = object : TasksActions {},
    )
}
