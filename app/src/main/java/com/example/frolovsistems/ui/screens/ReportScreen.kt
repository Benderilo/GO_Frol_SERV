package com.example.frolovsistems.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frolovsistems.core.net.CashDirection
import com.example.frolovsistems.core.net.PeriodReportDto
import com.example.frolovsistems.data.CrmRepository
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.components.EmptyState
import com.example.frolovsistems.ui.components.ErrorBanner
import com.example.frolovsistems.ui.components.LoadingBox
import com.example.frolovsistems.ui.components.SectionHeader
import com.example.frolovsistems.ui.components.SoftCard
import com.example.frolovsistems.ui.components.formatMoney
import com.example.frolovsistems.ui.components.formatQuantity
import com.example.frolovsistems.ui.theme.Success
import com.example.frolovsistems.ui.theme.Warning
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
    val exporting: Boolean = false,
    val period: Period = Period.thisMonth(),
    val report: PeriodReportDto? = null,
    val profitKop: Long = 0,
    val message: String? = null,
    val error: String? = null,
)

class ReportViewModel(
    private val crm: CrmRepository = ServiceLocator.crm,
) : ViewModel() {

    private val _state = MutableStateFlow(ReportUiState())
    val state: StateFlow<ReportUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        val period = _state.value.period
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, message = null) }
            crm.report(period.from, period.to)
                .onSuccess { data ->
                    _state.update {
                        it.copy(loading = false, report = data.report, profitKop = data.profitKop)
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun setPeriod(period: Period) {
        _state.update { it.copy(period = period) }
        refresh()
    }

    /** Собирает книгу на сервере и пишет её в выбранный файл. */
    fun exportTo(context: Context, uri: Uri) {
        val period = _state.value.period
        viewModelScope.launch {
            _state.update { it.copy(exporting = true, error = null, message = null) }
            crm.reportWorkbook(period.from, period.to)
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
 * Отчёт за период: касса, выручка, долги и расход материалов.
 * Считается из тех же таблиц, по которым работает приложение, — отдельного
 * отчётного хранилища нет, и разойтись отчёту с данными не с чем.
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

    LaunchedEffect(Unit) { viewModel.refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
                Text("Отчёт за период", style = MaterialTheme.typography.headlineMedium)
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

        item { PeriodChips(state.period, viewModel::setPeriod) }

        if (state.loading && state.report == null) {
            item { LoadingBox() }
        }

        state.report?.let { report ->
            item {
                SoftCard {
                    SectionHeader("Касса", periodLabel(state.period))
                    Spacer(Modifier.height(10.dp))
                    ReportRow("Было на начало", formatMoney(report.openingKop))
                    ReportRow("Пришло", formatMoney(report.incomeKop), Success)
                    ReportRow("Ушло", formatMoney(report.expenseKop), Warning)
                    Spacer(Modifier.height(4.dp))
                    ReportRow(
                        "Осталось",
                        formatMoney(report.closingKop),
                        emphasis = true,
                    )
                }
            }

            item {
                SoftCard {
                    SectionHeader("Заказы", "закрытые в периоде")
                    Spacer(Modifier.height(10.dp))
                    ReportRow("Закрыто заказов", report.ordersClosed.toString())
                    ReportRow("Выручка", formatMoney(report.revenueKop))
                    if (report.costKop > 0) {
                        ReportRow("Закупка", formatMoney(report.costKop), Warning)
                        ReportRow("Заработок", formatMoney(state.profitKop), Success, emphasis = true)
                    }
                }
            }

            if (report.debtKop > 0) {
                item {
                    SoftCard {
                        SectionHeader("Долги клиентов", "на сейчас, а не за период")
                        Spacer(Modifier.height(10.dp))
                        ReportRow("Заказов с долгом", report.debtOrders.toString())
                        ReportRow("Сумма долга", formatMoney(report.debtKop), Warning, emphasis = true)
                    }
                }
            }

            if (report.byCategory.isNotEmpty()) {
                item {
                    SoftCard {
                        SectionHeader("По статьям", "куда пришли и куда ушли деньги")
                        Spacer(Modifier.height(10.dp))
                        report.byCategory.forEach { c ->
                            ReportRow(
                                c.category,
                                (if (c.direction == CashDirection.IN) "+" else "−") +
                                    formatMoney(c.amountKop),
                                if (c.direction == CashDirection.IN) Success else Warning,
                            )
                        }
                    }
                }
            }

            if (report.materials.isNotEmpty()) {
                item {
                    SoftCard {
                        SectionHeader("Материалы", "израсходовано за период")
                        Spacer(Modifier.height(10.dp))
                        report.materials.forEach { m ->
                            ReportRow(
                                m.name,
                                "${formatQuantity(m.qtyMilli)} ${m.unit}" +
                                    if (m.costKop > 0) " • ${formatMoney(m.costKop)}" else "",
                            )
                        }
                    }
                }
            }

            if (report.incomeKop == 0L && report.expenseKop == 0L && report.ordersClosed == 0L) {
                item {
                    EmptyState(
                        "За этот период ничего не было",
                        "Ни движений по кассе, ни закрытых заказов",
                    )
                }
            }

            item {
                Button(
                    onClick = { saveLauncher.launch(reportFileName(state.period)) },
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

@Composable
private fun ReportRow(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    emphasis: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = if (emphasis) MaterialTheme.typography.titleSmall
            else MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

private fun periodLabel(period: Period): String =
    if (period.from.isBlank() && period.to.isBlank()) "за всё время"
    else "с ${period.from} по ${period.to}"

private fun reportFileName(period: Period): String =
    if (period.from.isBlank()) "отчёт.xlsx" else "отчёт-${period.from}—${period.to}.xlsx"
