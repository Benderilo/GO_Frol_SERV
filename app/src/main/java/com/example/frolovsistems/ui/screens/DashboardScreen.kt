package com.example.frolovsistems.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frolovsistems.core.net.ImportSummaryDto
import com.example.frolovsistems.core.net.RequestDto
import com.example.frolovsistems.core.net.StatsDto
import com.example.frolovsistems.data.CrmRepository
import com.example.frolovsistems.data.SessionRepository
import com.example.frolovsistems.data.SiteRepository
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.components.EmptyState
import com.example.frolovsistems.ui.components.ErrorBanner
import com.example.frolovsistems.ui.components.LoadingBox
import com.example.frolovsistems.ui.components.SectionHeader
import com.example.frolovsistems.ui.components.SoftCard
import com.example.frolovsistems.ui.components.StatTile
import com.example.frolovsistems.ui.components.StatusChip
import com.example.frolovsistems.ui.theme.Success
import com.example.frolovsistems.ui.theme.Warning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Куда ведёт нажатие на плитку со сводным числом. */
object DashboardTargets {
    const val CLIENTS = "clients"
    const val NEW_REQUESTS = "requests?status=new"
    const val ACTIVE_ORDERS = "orders?status=in_progress"
    const val DONE_ORDERS = "orders?status=done"
}

data class DashboardUiState(
    val loading: Boolean = true,
    val stats: StatsDto = StatsDto(),
    val requests: List<RequestDto> = emptyList(),
    val query: String = "",
    val siteName: String = "",
    val siteRevision: Long = 0,
    val baseUrl: String = "",
    val error: String? = null,
    val transferBusy: Boolean = false,
    val transferMessage: String? = null,
    val importSummary: ImportSummaryDto? = null,
) {
    /** Заявки отфильтрованы на месте: их немного, лишний запрос к серверу не нужен. */
    val visibleRequests: List<RequestDto>
        get() {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return requests
            return requests.filter {
                it.name.lowercase().contains(q) ||
                    it.phone.lowercase().contains(q) ||
                    it.message.lowercase().contains(q)
            }
        }
}

class DashboardViewModel(
    private val crm: CrmRepository = ServiceLocator.crm,
    private val site: SiteRepository = ServiceLocator.site,
    private val session: SessionRepository = ServiceLocator.session,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Адрес показываем в карточке ошибки: сразу видно, куда стучится приложение.
            session.preferences.collect { prefs ->
                _state.update { it.copy(baseUrl = prefs.server.baseUrl) }
            }
        }
        refresh()
    }

    fun onQuery(value: String) = _state.update { it.copy(query = value) }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }

            // Запросы идут параллельно: последовательно недоступный сервер
            // держал бы экран втрое дольше таймаута.
            val (stats, requests, content) = coroutineScope {
                val statsJob = async { crm.stats() }
                val requestsJob = async { crm.requests() }
                val contentJob = async { site.load() }
                Triple(statsJob.await(), requestsJob.await(), contentJob.await())
            }

            val error = listOf(stats, requests, content)
                .firstOrNull { it.isFailure }
                ?.exceptionOrNull()?.message

            _state.update { current ->
                current.copy(
                    loading = false,
                    stats = stats.getOrNull() ?: current.stats,
                    requests = requests.getOrNull() ?: current.requests,
                    siteName = content.getOrNull()?.siteName ?: current.siteName,
                    siteRevision = content.getOrNull()?.revision ?: current.siteRevision,
                    error = error,
                )
            }
        }
    }

    /** Скачивает книгу и пишет её в выбранный пользователем файл. */
    fun exportTo(context: Context, target: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(transferBusy = true, transferMessage = null, error = null) }
            crm.exportWorkbook()
                .onSuccess { bytes ->
                    val written = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openOutputStream(target)?.use { it.write(bytes) }
                                ?: error("файл недоступен для записи")
                        }
                    }
                    _state.update {
                        it.copy(
                            transferBusy = false,
                            transferMessage = written.fold(
                                onSuccess = { _ -> "Выгружено ${bytes.size / 1024} КБ" },
                                onFailure = { e -> "Не удалось сохранить файл: ${e.message}" },
                            ),
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(transferBusy = false, error = e.message) } }
        }
    }

    /** Читает выбранный файл и отправляет его на сервер. */
    fun importFrom(context: Context, source: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(transferBusy = true, transferMessage = null, error = null) }

            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(source)?.use { it.readBytes() } }
                    .getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                _state.update {
                    it.copy(transferBusy = false, error = "Не удалось прочитать выбранный файл")
                }
                return@launch
            }

            crm.importWorkbook(bytes, "import.xlsx")
                .onSuccess { summary ->
                    _state.update { it.copy(transferBusy = false, importSummary = summary) }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(transferBusy = false, error = e.message) } }
        }
    }

    fun dismissTransferMessage() = _state.update { it.copy(transferMessage = null) }
    fun dismissImportSummary() = _state.update { it.copy(importSummary = null) }
}

@Composable
fun DashboardScreen(
    onOpenSettings: () -> Unit = {},
    onOpenSection: (String) -> Unit = {},
    viewModel: DashboardViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showTransfer by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        ),
    ) { uri -> if (uri != null) viewModel.exportTo(context, uri) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.importFrom(context, uri) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Сводка", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = state.siteName.ifBlank { "Загрузка…" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Выгрузка и загрузка стоят слева от обновления.
                IconButton(onClick = { showTransfer = true }, enabled = !state.transferBusy) {
                    if (state.transferBusy) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.SwapVert, contentDescription = "Выгрузка и загрузка Excel")
                    }
                }
                RefreshButton(loading = state.loading, onClick = viewModel::refresh)
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Настройки подключения")
                }
            }
        }

        item {
            Column {
                ErrorBanner(state.error)
                if (state.error != null) {
                    Spacer(Modifier.height(10.dp))
                    ConnectionHelpCard(baseUrl = state.baseUrl, onOpenSettings = onOpenSettings)
                }
                AnimatedVisibility(
                    visible = state.transferMessage != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    TransferMessage(
                        text = state.transferMessage.orEmpty(),
                        onDismiss = viewModel::dismissTransferMessage,
                    )
                }
            }
        }

        if (state.loading && state.siteName.isBlank()) {
            item { LoadingBox() }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        value = state.stats.clients.toString(),
                        label = "Клиентов",
                        onClick = { onOpenSection(DashboardTargets.CLIENTS) },
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = state.stats.requestsNew.toString(),
                        label = "Новых заявок",
                        accent = MaterialTheme.colorScheme.secondary,
                        onClick = { onOpenSection(DashboardTargets.NEW_REQUESTS) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        value = state.stats.ordersActive.toString(),
                        label = "Заказов в работе",
                        accent = Warning,
                        onClick = { onOpenSection(DashboardTargets.ACTIVE_ORDERS) },
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = state.stats.ordersDone.toString(),
                        label = "Заказов завершено",
                        accent = Success,
                        onClick = { onOpenSection(DashboardTargets.DONE_ORDERS) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Заявки", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${state.visibleRequests.size} из ${state.requests.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQuery,
                    placeholder = { Text("Поиск по имени, телефону, тексту") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onQuery("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Очистить")
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val visible = state.visibleRequests
            if (visible.isEmpty()) {
                item {
                    EmptyState(
                        title = if (state.query.isBlank()) "Заявок пока нет" else "Ничего не найдено",
                        subtitle = "Здесь появятся обращения с формы на сайте",
                    )
                }
            } else {
                // Список живёт в том же LazyColumn, что и вся сводка: элементы
                // переиспользуются, как в RecyclerView, и прокрутка остаётся одна.
                items(visible, key = { it.id }) { request ->
                    SoftCard(highlighted = request.status == "new") {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                request.name,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            StatusChip(
                                text = requestStatusLabel(request.status),
                                color = requestStatusColor(request.status),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            request.phone,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (request.message.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(request.message, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (state.siteRevision > 0) {
                item {
                    Text(
                        "Ревизия контента сайта: ${state.siteRevision}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showTransfer) {
        TransferDialog(
            onDismiss = { showTransfer = false },
            onExport = {
                showTransfer = false
                exportLauncher.launch(defaultExportName())
            },
            onImport = {
                showTransfer = false
                importLauncher.launch(importMimeTypes)
            },
        )
    }

    state.importSummary?.let { summary ->
        ImportSummaryDialog(summary = summary, onDismiss = viewModel::dismissImportSummary)
    }
}

/** Часть форматов Excel устройства отдают под другим типом — принимаем оба. */
private val importMimeTypes = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/octet-stream",
)

private fun defaultExportName(): String {
    val now = java.time.LocalDate.now()
    return "frolov-crm-$now.xlsx"
}

@Composable
private fun TransferDialog(
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Данные в Excel") },
        text = {
            Column {
                Text(
                    "Выгрузка соберёт клиентов, заказы и заявки в одну книгу. " +
                        "Загрузка добавит записи из книги и обновит совпадающие по id — " +
                        "ничего не удаляется.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onExport,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Выгрузить базу")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onImport,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Загрузить из файла")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun ImportSummaryDialog(summary: ImportSummaryDto, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Загрузка завершена") },
        text = {
            Column {
                SummaryRow("Клиенты", summary.clients.created, summary.clients.updated)
                SummaryRow("Заказы", summary.orders.created, summary.orders.updated)
                SummaryRow("Заявки", summary.requests.created, summary.requests.updated)

                if (summary.warnings.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Замечания (${summary.warnings.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    summary.warnings.take(8).forEach {
                        Text(
                            "• $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Понятно") } },
    )
}

@Composable
private fun SummaryRow(label: String, created: Int, updated: Int) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            "добавлено $created, обновлено $updated",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TransferMessage(text: String, onDismiss: () -> Unit) {
    SoftCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Скрыть")
            }
        }
    }
}

/** Подсказка при обрыве связи: показывает адрес и ведёт прямо в настройки. */
@Composable
private fun ConnectionHelpCard(baseUrl: String, onOpenSettings: () -> Unit) {
    SoftCard {
        SectionHeader("Нет связи с сервером", baseUrl.ifBlank { "адрес не задан" })
        Spacer(Modifier.height(8.dp))
        Text(
            "Проверьте адрес и порт, а также что служба на сервере запущена.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onOpenSettings, shape = MaterialTheme.shapes.small) {
            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Открыть настройки подключения")
        }
    }
}

@Composable
private fun RefreshButton(loading: Boolean, onClick: () -> Unit) {
    // Иконка «доворачивается» на каждое обновление — видно, что запрос ушёл.
    val rotation by animateFloatAsState(
        targetValue = if (loading) 360f else 0f,
        animationSpec = tween(700),
        label = "refreshRotation",
    )
    IconButton(onClick = onClick, enabled = !loading) {
        Icon(
            Icons.Default.Refresh,
            contentDescription = "Обновить",
            modifier = Modifier.rotate(rotation),
        )
    }
}

/** Формат «1 234 567 ₽» без зависимости от локали устройства. */
fun formatMoney(value: Double): String {
    val rounded = value.toLong()
    val digits = rounded.toString().reversed().chunked(3).joinToString(" ").reversed()
    return "$digits ₽"
}

fun requestStatusLabel(status: String): String = when (status) {
    "new" -> "Новая"
    "in_progress" -> "В работе"
    "done" -> "Обработана"
    "spam" -> "Спам"
    else -> status
}

@Composable
fun requestStatusColor(status: String): Color = when (status) {
    "new" -> MaterialTheme.colorScheme.primary
    "in_progress" -> Warning
    "done" -> Success
    else -> MaterialTheme.colorScheme.outline
}
