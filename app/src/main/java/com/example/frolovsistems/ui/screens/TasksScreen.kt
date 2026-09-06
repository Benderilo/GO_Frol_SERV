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
import com.example.frolovsistems.ui.components.LoadingBox
import com.example.frolovsistems.ui.components.SoftCard
import com.example.frolovsistems.ui.components.StatusChip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

class TasksViewModel(
    private val crm: CrmRepository = ServiceLocator.crm,
) : ViewModel() {

    private val _state = MutableStateFlow(TasksUiState())
    val state: StateFlow<TasksUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            crm.tasks(_state.value.filter)
                .onSuccess { list -> _state.update { it.copy(loading = false, items = list) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun setFilter(filter: String) {
        _state.update { it.copy(filter = filter) }
        refresh()
    }

    fun startCreate(parentId: Long? = null) =
        _state.update { it.copy(editing = TaskDto(parentId = parentId)) }
    fun startEdit(task: TaskDto) = _state.update { it.copy(editing = task) }
    fun updateDraft(task: TaskDto) = _state.update { it.copy(editing = task) }
    fun cancelEdit() = _state.update { it.copy(editing = null) }

    fun saveDraft() {
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

    fun toggleDone(task: TaskDto) {
        viewModelScope.launch {
            crm.setTaskDone(task.id, !task.done)
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun delete(task: TaskDto) {
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
    var pendingDelete by remember { mutableStateOf<TaskDto?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }
    // Кнопка «Обновить» в общей шапке.
    LaunchedEffect(refreshTick) { if (refreshTick > 0) viewModel.refresh() }

    // Плоский список от сервера раскладываем в дерево: подзадачи идут под родителями.
    val rows = remember(state.items) { taskRows(state.items) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Задачи", style = MaterialTheme.typography.headlineMedium)
            }
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    taskFilters.forEach { (value, label) ->
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
                        title = "Задач нет",
                        subtitle = "Добавьте напоминание кнопкой внизу справа",
                    )
                }
                else -> {
                    items(rows, key = { it.task.id }) { row ->
                        TaskCard(
                            row = row,
                            onToggle = { viewModel.toggleDone(row.task) },
                            onEdit = { viewModel.startEdit(row.task) },
                            onDelete = { pendingDelete = row.task },
                            onAddSubtask = { viewModel.startCreate(parentId = row.task.id) },
                        )
                    }
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = state.editing == null,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) {
            FloatingActionButton(onClick = viewModel::startCreate) {
                Icon(Icons.Default.Add, contentDescription = "Добавить задачу")
            }
        }
    }

    state.editing?.let { draft ->
        TaskEditorDialog(
            draft = draft,
            parentTitle = state.items.firstOrNull { it.id == draft.parentId }?.title,
            onChange = viewModel::updateDraft,
            onDismiss = viewModel::cancelEdit,
            onSave = viewModel::saveDraft,
        )
    }

    pendingDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить задачу?") },
            text = { Text("«${task.title}» будет удалена безвозвратно.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.delete(task)
                    pendingDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun TaskCard(
    row: TaskRow,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddSubtask: () -> Unit,
) {
    val task = row.task
    // Глубокая вложенность не ломает верстку: дальше 4 уровней отступ не растёт.
    SoftCard(
        onClick = onEdit,
        modifier = Modifier.padding(start = (row.depth.coerceAtMost(4) * 16).dp),
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
                                    MaterialTheme.colorScheme.outline
                                },
                            )
                        }
                    }
                }
            }
            // Подзадач можно добавлять к любой задаче — вложенность не ограничена.
            IconButton(onClick = onAddSubtask) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Добавить подзадачу",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error)
            }
        }
        if (task.note.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(task.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    draft.id == 0L && parentTitle != null -> "Подзадача"
                    draft.id == 0L -> "Новая задача"
                    else -> "Задача"
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).imePadding()) {
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
        },
        confirmButton = { Button(onClick = onSave) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
