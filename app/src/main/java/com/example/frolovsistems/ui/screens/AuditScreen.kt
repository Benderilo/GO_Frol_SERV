package com.example.frolovsistems.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frolovsistems.core.net.AuditEntryDto
import com.example.frolovsistems.data.CrmRepository
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.components.EmptyState
import com.example.frolovsistems.ui.components.ErrorBanner
import com.example.frolovsistems.ui.components.LoadingBox
import com.example.frolovsistems.ui.components.SoftCard
import com.example.frolovsistems.ui.theme.Success
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.example.frolovsistems.ui.preview.PreviewScreen
import com.example.frolovsistems.ui.preview.PreviewData

/** Фильтры по виду записи. Пустое значение — показывать всё. */
private val auditFilters = listOf(
    "" to "Всё",
    "delete" to "Удаления",
    "create" to "Созданное",
    "update" to "Изменения",
)

data class AuditUiState(
    val loading: Boolean = true,
    val entries: List<AuditEntryDto> = emptyList(),
    val filter: String = "",
    val error: String? = null,
) {
    /** Фильтруем на месте: журнал приходит целиком, лишний запрос ни к чему. */
    val visible: List<AuditEntryDto>
        get() = if (filter.isEmpty()) entries else entries.filter { it.action == filter }
}

/**
 * Действия экрана. Их выполняет [AuditViewModel]; превью в Android Studio
 * передаёт пустую реализацию, и разметка рисуется без сети и базы.
 */
interface AuditActions {
    fun refresh() {}
    fun setFilter(value: String) {}
}

class AuditViewModel(
    private val crm: CrmRepository = ServiceLocator.crm,
) : ViewModel(), AuditActions {

    private val _state = MutableStateFlow(AuditUiState())
    val state: StateFlow<AuditUiState> = _state.asStateFlow()

    init { refresh() }

    override fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            crm.audit(limit = 300)
                .onSuccess { list -> _state.update { it.copy(loading = false, entries = list) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    override fun setFilter(value: String) = _state.update { it.copy(filter = value) }
}

@Composable
fun AuditScreen(
    refreshTick: Int = 0,
    viewModel: AuditViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AuditContent(
        state = state,
        actions = viewModel,
        refreshTick = refreshTick,
    )
}

/** Разметка экрана без ViewModel: её показывает превью в Android Studio. */
@Composable
fun AuditContent(
    state: AuditUiState,
    actions: AuditActions,
    refreshTick: Int = 0,
) {
    LaunchedEffect(refreshTick) { if (refreshTick > 0) actions.refresh() }

    // Записи приходят от новых к старым — разбиваем по дням в том же порядке.
    val byDay = remember(state.visible) { state.visible.groupBy { auditDay(it.createdAt) } }

    Column(Modifier.fillMaxSize()) {
        ListHeader {
            Column {
                Text("Журнал действий", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Кто и что менял в CRM. Записи хранятся на сервере.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                auditFilters.forEach { (value, label) ->
                    FilterChip(
                        selected = state.filter == value,
                        onClick = { actions.setFilter(value) },
                        label = { Text(label) },
                    )
                }
            }
            ErrorBanner(state.error)
        }

        ListWindow(Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = ListContentPadding,
                verticalArrangement = Arrangement.spacedBy(ListItemSpacing),
            ) {
                when {
                    state.loading && state.entries.isEmpty() -> item { LoadingBox() }
                    state.visible.isEmpty() -> item {
                        EmptyState(
                            title = "Записей нет",
                            subtitle = "Журнал наполняется по мере работы с клиентами, заказами и файлами",
                        )
                    }
                    else -> byDay.forEach { (day, entries) ->
                        item(key = "day-$day") {
                            Text(
                                day,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        items(entries, key = { it.id }) { entry -> AuditRow(entry) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditRow(entry: AuditEntryDto) {
    val look = actionLook(entry.action)
    SoftCard(contentPadding = CompactCardPadding) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .background(look.color.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(look.icon, contentDescription = null, tint = look.color, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${look.verb} · ${entityLabel(entry.entityType)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (entry.detail.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        entry.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                auditTime(entry.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private class ActionLook(val icon: ImageVector, val color: Color, val verb: String)

@Composable
private fun actionLook(action: String): ActionLook = when (action) {
    "create" -> ActionLook(Icons.Default.Add, Success, "Создано")
    "update" -> ActionLook(Icons.Default.Edit, MaterialTheme.colorScheme.primary, "Изменено")
    "delete" -> ActionLook(Icons.Default.Delete, MaterialTheme.colorScheme.error, "Удалено")
    "status" -> ActionLook(Icons.Default.SwapHoriz, MaterialTheme.colorScheme.tertiary, "Статус")
    else -> ActionLook(Icons.Default.Edit, MaterialTheme.colorScheme.onSurfaceVariant, action)
}

/**
 * Вид записи по-русски. Значения приходят от сервера как есть; неизвестное
 * показываем как пришло — новую сущность заведут раньше, чем этот список.
 */
private fun entityLabel(type: String): String = when (type) {
    "client" -> "клиент"
    "order" -> "заказ"
    "order_item" -> "позиция заказа"
    "request" -> "заявка"
    "payment" -> "платёж"
    "cash" -> "касса"
    "catalog" -> "справочник"
    "stock" -> "склад"
    "document" -> "документ"
    "company" -> "реквизиты"
    "folder" -> "папка"
    "file" -> "файл"
    else -> type
}

/** Заголовок дня: «сегодня», «вчера» или дата. */
private fun auditDay(iso: String): String {
    val date = runCatching {
        OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
    }.getOrNull() ?: return "без даты"

    val today = java.time.LocalDate.now()
    return when (date) {
        today -> "сегодня"
        today.minusDays(1) -> "вчера"
        else -> date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    }
}

/** Время записи в часовом поясе устройства: сервер пишет журнал в UTC. */
private fun auditTime(iso: String): String = runCatching {
    OffsetDateTime.parse(iso)
        .atZoneSameInstant(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))
}.getOrDefault("")

// ---------------------------------------------------------------------------
// Превью для Android Studio: Split или Design справа сверху. Данные — образцы
// из PreviewData, действия — пустые: экран рисуется без сети и базы.
// ---------------------------------------------------------------------------

@Preview(name = "Журнал действий", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun AuditContentPreview() = PreviewScreen {
    AuditContent(
        state = AuditUiState(loading = false, entries = PreviewData.audit),
        actions = object : AuditActions {},
    )
}
