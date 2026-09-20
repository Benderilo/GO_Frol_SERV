package com.example.frolovsistems.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frolovsistems.core.net.ClientDto
import com.example.frolovsistems.core.net.ReportCellDto
import com.example.frolovsistems.core.net.ReportColumnDto
import com.example.frolovsistems.core.net.ReportInfoDto
import com.example.frolovsistems.core.net.ReportSectionDto
import com.example.frolovsistems.data.CrmRepository
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.components.EmptyState
import com.example.frolovsistems.ui.components.ErrorBanner
import com.example.frolovsistems.ui.components.LoadingBox
import com.example.frolovsistems.ui.components.SectionHeader
import com.example.frolovsistems.ui.components.SoftCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Отрезок времени для отчёта. Даты в том же виде, в котором их ждёт сервер:
 * «2026-09-01». Границы включительные с обеих сторон.
 */
data class Period(val label: String, val from: String, val to: String) {
    companion object {
        fun thisMonth(): Period {
            val now = LocalDate.now()
            return Period("Этот месяц", now.withDayOfMonth(1).toString(), now.toString())
        }

        fun lastMonth(): Period {
            val first = LocalDate.now().withDayOfMonth(1).minusMonths(1)
            return Period("Прошлый месяц", first.toString(), first.plusMonths(1).minusDays(1).toString())
        }

        fun thisQuarter(): Period {
            val now = LocalDate.now()
            val first = now.withDayOfMonth(1).minusMonths(((now.monthValue - 1) % 3).toLong())
            return Period("Квартал", first.toString(), now.toString())
        }

        fun thisYear(): Period {
            val now = LocalDate.now()
            return Period("Год", now.withDayOfYear(1).toString(), now.toString())
        }

        fun allTime(): Period = Period("Всё время", "", "")

        fun presets(): List<Period> =
            listOf(thisMonth(), lastMonth(), thisQuarter(), thisYear(), allTime())
    }
}

data class ReportUiState(
    val loading: Boolean = true,
    val reports: List<ReportInfoDto> = emptyList(),
    /** Выбранный отчёт; null — показываем список. */
    val selected: ReportInfoDto? = null,
    val period: Period = Period.thisMonth(),
    val clients: List<ClientDto> = emptyList(),
    /** Клиент для отчётов с параметром «клиент»; null — все клиенты. */
    val client: ClientDto? = null,
    val result: com.example.frolovsistems.core.net.ReportResultDto? = null,
    val exporting: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    fun wantsPeriod(): Boolean = selected?.params?.any { it.kind == "period" } == true
    fun wantsClient(): Boolean = selected?.params?.any { it.kind == "client" } == true
}

class ReportViewModel(
    private val crm: CrmRepository = ServiceLocator.crm,
) : ViewModel() {

    private val _state = MutableStateFlow(ReportUiState())
    val state: StateFlow<ReportUiState> = _state.asStateFlow()

    init { loadReports() }

    fun loadReports() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            crm.reports()
                .onSuccess { list -> _state.update { it.copy(loading = false, reports = list) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun select(report: ReportInfoDto) {
        _state.update { it.copy(selected = report, result = null, client = null) }
        if (report.params.any { it.kind == "client" } && _state.value.clients.isEmpty()) {
            viewModelScope.launch {
                crm.clients().onSuccess { list -> _state.update { it.copy(clients = list) } }
            }
        }
        run()
    }

    fun back() = _state.update { it.copy(selected = null, result = null, message = null, error = null) }

    fun setPeriod(period: Period) {
        _state.update { it.copy(period = period) }
        run()
    }

    fun setClient(client: ClientDto?) {
        _state.update { it.copy(client = client) }
        run()
    }

    private fun run() {
        val selected = _state.value.selected ?: return
        val period = _state.value.period
        val clientId = _state.value.client?.id ?: 0L
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, message = null) }
            crm.runReport(selected.id, period.from, period.to, clientId)
                .onSuccess { result -> _state.update { it.copy(loading = false, result = result) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    /** Собирает книгу на сервере и пишет её в выбранный файл. */
    fun exportTo(context: Context, uri: Uri) {
        val selected = _state.value.selected ?: return
        val period = _state.value.period
        val clientId = _state.value.client?.id ?: 0L
        viewModelScope.launch {
            _state.update { it.copy(exporting = true, error = null, message = null) }
            crm.reportWorkbook(selected.id, period.from, period.to, clientId)
                .onSuccess { bytes ->
                    val written = runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    }
                    _state.update {
                        if (written.isSuccess) {
                            it.copy(exporting = false, message = "Отчёт сохранён")
                        } else {
                            it.copy(exporting = false, error = "Не удалось записать файл")
                        }
                    }
                }
                .onFailure { e -> _state.update { it.copy(exporting = false, error = e.message) } }
        }
    }
}

/**
 * Отчёты из реестра сервера. Экран один на все отчёты: список → параметры
 * (период, клиент) → секции таблицами → выгрузка в Excel. Новый отчёт
 * на сервере появляется здесь без единой правки экрана.
 */
@Composable
fun ReportScreen(
    onBack: () -> Unit = {},
    viewModel: ReportViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        ),
    ) { uri -> if (uri != null) viewModel.exportTo(context, uri) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (state.selected == null) onBack() else viewModel.back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
                Column {
                    Text(
                        state.selected?.title ?: "Отчёты",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    state.result?.subtitle?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item { ErrorBanner(state.error) }

        state.message?.let { text ->
            item {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }

        val selected = state.selected
        if (selected == null) {
            if (state.loading && state.reports.isEmpty()) {
                item { LoadingBox() }
            }
            if (state.reports.isEmpty() && !state.loading) {
                item { EmptyState("Отчётов нет", "Сервер не вернул список отчётов") }
            }
            items(state.reports.size, key = { state.reports[it].id }) { index ->
                val report = state.reports[index]
                SoftCard(onClick = { viewModel.select(report) }) {
                    Text(report.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        report.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (report.params.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Спрашивает: " + report.params.joinToString(", ") { it.label },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        } else {
            if (state.wantsPeriod()) {
                item { PeriodChips(state.period, viewModel::setPeriod) }
            }
            if (state.wantsClient()) {
                item { ClientPicker(state.client, state.clients) { viewModel.setClient(it) } }
            }

            if (state.loading && state.result == null) {
                item { LoadingBox() }
            }

            state.result?.let { result ->
                if (result.sections.all { it.rows.isEmpty() }) {
                    item { EmptyState("Данных нет", "За выбранный период нечего показать") }
                }
                result.sections.forEach { section ->
                    item { SectionCard(section) }
                }
                item {
                    Button(
                        onClick = {
                            saveLauncher.launch(
                                reportFileName(selected.id, state.period),
                            )
                        },
                        enabled = !state.exporting,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.exporting) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Выгрузить в Excel")
                        }
                    }
                }
            }
        }
    }
}

/** Секция отчёта: заголовок, строки и итог. */
@Composable
private fun SectionCard(section: ReportSectionDto) {
    SoftCard {
        SectionHeader(section.title, "")
        Spacer(Modifier.height(10.dp))
        section.rows.forEach { row -> ReportRowCells(section.columns, row) }
        if (section.total.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            ReportRowCells(section.columns, section.total, emphasized = true)
        }
        if (section.note.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                section.note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Универсальная строка таблицы. Две колонки — подпись и значение, как
 * раньше; больше — первая ячейка заголовком, остальные парами «подпись:
 * значение»: на узком экране таблица в шесть колонок не помещается.
 */
@Composable
private fun ReportRowCells(
    columns: List<ReportColumnDto>,
    cells: List<ReportCellDto>,
    emphasized: Boolean = false,
) {
    if (cells.isEmpty()) return
    val valueStyle = if (emphasized) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium
    val valueWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal

    if (columns.size <= 2) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                cells.getOrNull(0)?.text.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val value = cells.getOrNull(1) ?: cells.getOrNull(0)
            Text(
                value?.text.orEmpty(),
                style = valueStyle,
                fontWeight = valueWeight,
                color = cellColor(value),
            )
        }
        return
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            cells.firstOrNull()?.text.orEmpty(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (i in 1 until columns.size) {
                val cell = cells.getOrNull(i) ?: continue
                Text(
                    buildString {
                        append(columns[i].label.takeIf { it.isNotBlank() } ?: "")
                        if (cell.text.isNotBlank()) append(" ")
                        append(cell.text)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (columns[i].kind == "money") cellColor(cell, default = MaterialTheme.colorScheme.onSurface) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Деньги в минусе — предупреждение; иначе обычный цвет текста. */
@Composable
private fun cellColor(cell: ReportCellDto?, default: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface): androidx.compose.ui.graphics.Color {
    if (cell == null) return default
    if (cell.kind == "money" && cell.valueKop < 0) {
        return MaterialTheme.colorScheme.error
    }
    return default
}

/** Выбор клиента для отчётов вроде сверки; null — все клиенты. */
@Composable
private fun ClientPicker(selected: ClientDto?, clients: List<ClientDto>, onPick: (ClientDto?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onPick(null) },
            label = { Text("Все клиенты") },
        )
        FilterChip(
            selected = selected != null,
            onClick = { open = true },
            label = { Text(selected?.name ?: "Один клиент") },
        )
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Клиент") },
            text = {
                LazyColumn {
                    items(clients.size, key = { clients[it].id }) { index ->
                        val client = clients[index]
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                open = false
                                onPick(client)
                            }.padding(vertical = 8.dp),
                        ) {
                            Text(client.name, style = MaterialTheme.typography.bodyMedium)
                            if (client.phone.isNotBlank()) {
                                Text(
                                    client.phone,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { open = false }) { Text("Отмена") } },
        )
    }
}

/** Строка полосы периодов — одна и та же в кассе и в отчёте. */
@Composable
fun PeriodChips(selected: Period, onSelect: (Period) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Period.presets().forEach { period ->
            FilterChip(
                selected = selected.label == period.label,
                onClick = { onSelect(period) },
                label = { Text(period.label) },
            )
        }
    }
}

private fun reportFileName(id: String, period: Period): String =
    if (period.from.isBlank()) "$id.xlsx" else "$id-${period.from}—${period.to}.xlsx"
