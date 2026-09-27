package com.example.frolovsistems.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frolovsistems.core.net.PayKind
import com.example.frolovsistems.core.net.PayrollDto
import com.example.frolovsistems.core.net.PayrollRowDto
import com.example.frolovsistems.core.net.PayrollSettingsDto
import com.example.frolovsistems.core.net.SalaryPaymentBody
import com.example.frolovsistems.core.net.SalaryPaymentDto
import com.example.frolovsistems.core.net.SalaryType
import com.example.frolovsistems.core.net.CashMethod
import com.example.frolovsistems.data.WorkersRepository
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.components.ChoiceChips
import com.example.frolovsistems.ui.components.CrmDialog
import com.example.frolovsistems.ui.components.DangerZone
import com.example.frolovsistems.ui.components.DatePickerField
import com.example.frolovsistems.ui.components.DialogField
import com.example.frolovsistems.ui.components.EmptyState
import com.example.frolovsistems.ui.components.ErrorBanner
import com.example.frolovsistems.ui.components.FormSection
import com.example.frolovsistems.ui.components.HintBlock
import com.example.frolovsistems.ui.components.ListContentPadding
import com.example.frolovsistems.ui.components.ListHeader
import com.example.frolovsistems.ui.components.ListItemSpacing
import com.example.frolovsistems.ui.components.ListWindow
import com.example.frolovsistems.ui.components.LoadingBox
import com.example.frolovsistems.ui.components.MoneyField
import com.example.frolovsistems.ui.components.PayMethodChips
import com.example.frolovsistems.ui.components.SoftCard
import com.example.frolovsistems.ui.components.StatTile
import com.example.frolovsistems.ui.components.StatusRecordCard
import com.example.frolovsistems.ui.components.formatMoney
import com.example.frolovsistems.ui.preview.PreviewScreen
import com.example.frolovsistems.ui.theme.Success
import com.example.frolovsistems.ui.theme.Warning
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val RU: Locale = Locale.forLanguageTag("ru")

private fun payrollMonthTitle(month: YearMonth): String =
    month.month.getDisplayName(TextStyle.FULL_STANDALONE, RU).replaceFirstChar { it.uppercase(RU) } +
        " ${month.year}"

/** «2026-09-20» → «20.09.2026»; не дата — как есть. */
private fun ruDate(iso: String): String =
    runCatching { LocalDate.parse(iso.take(10)).format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) }
        .getOrDefault(iso)

data class PayrollUiState(
    val loading: Boolean = true,
    val month: YearMonth = YearMonth.now(),
    val data: PayrollDto? = null,
    val error: String? = null,
    val message: String? = null,
    /** Открытая выплата: новая (id = 0) или правка существующей. */
    val payment: SalaryPaymentDto? = null,
    val datesOpen: Boolean = false,
    val busy: Boolean = false,
)

/** Действия экрана; превью передаёт пустую реализацию. */
interface PayrollActions {
    fun refresh() {}
    fun shiftMonth(delta: Long) {}
    fun startPayment(row: PayrollRowDto, kind: String) {}
    fun openPayment(payment: SalaryPaymentDto) {}
    fun updatePayment(payment: SalaryPaymentDto) {}
    fun closePayment() {}
    fun savePayment() {}
    fun deletePayment() {}
    fun openDates() {}
    fun closeDates() {}
    fun saveDates(advanceDate: String, salaryDate: String, settings: PayrollSettingsDto) {}
    fun resetDates() {}
    fun clearMessage() {}
}

class PayrollViewModel(
    private val repo: WorkersRepository = ServiceLocator.workers,
) : ViewModel(), PayrollActions {

    private val _state = MutableStateFlow(PayrollUiState())
    val state: StateFlow<PayrollUiState> = _state.asStateFlow()

    init { refresh() }

    override fun refresh() {
        val month = _state.value.month
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            repo.payroll(month.toString())
                .onSuccess { data ->
                    // Пока грузилось, месяц могли перелистнуть — старый ответ не нужен.
                    _state.update { if (it.month == month) it.copy(loading = false, data = data) else it }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    override fun shiftMonth(delta: Long) {
        _state.update { it.copy(month = it.month.plusMonths(delta), data = null) }
        refresh()
    }

    override fun startPayment(row: PayrollRowDto, kind: String) {
        val left = if (kind == PayKind.ADVANCE) row.advanceLeftKop else row.salaryLeftKop
        val plan = if (kind == PayKind.ADVANCE) row.advancePlanKop else row.salaryPlanKop
        _state.update {
            it.copy(
                payment = SalaryPaymentDto(
                    workerId = row.workerId,
                    workerName = row.name,
                    month = it.month.toString(),
                    kind = kind,
                    amountKop = if (left > 0) left else plan,
                    paidOn = LocalDate.now().toString(),
                ),
            )
        }
    }

    override fun openPayment(payment: SalaryPaymentDto) = _state.update { it.copy(payment = payment) }
    override fun updatePayment(payment: SalaryPaymentDto) = _state.update { it.copy(payment = payment) }
    override fun closePayment() = _state.update { it.copy(payment = null) }

    override fun savePayment() {
        val p = _state.value.payment ?: return
        if (p.amountKop <= 0) {
            _state.update { it.copy(error = "Сумма выплаты должна быть больше нуля") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val body = SalaryPaymentBody.of(p)
            val result = if (p.id == 0L) repo.addSalaryPayment(body) else repo.updateSalaryPayment(p.id, body)
            result
                .onSuccess {
                    _state.update {
                        it.copy(
                            busy = false,
                            payment = null,
                            message = if (p.id == 0L) "${PayKind.label(p.kind)} записан, расход ушёл в кассу"
                            else "Выплата изменена",
                        )
                    }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message) } }
        }
    }

    override fun deletePayment() {
        val p = _state.value.payment ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            repo.deleteSalaryPayment(p.id)
                .onSuccess {
                    _state.update { it.copy(busy = false, payment = null, message = "Выплата удалена и из кассы") }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message) } }
        }
    }

    override fun openDates() = _state.update { it.copy(datesOpen = true) }
    override fun closeDates() = _state.update { it.copy(datesOpen = false) }

    override fun saveDates(advanceDate: String, salaryDate: String, settings: PayrollSettingsDto) {
        val data = _state.value.data ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            // Дни по умолчанию — только если их правда поменяли.
            if (settings != data.settings) {
                repo.savePayrollSettings(settings).onFailure { e ->
                    _state.update { it.copy(busy = false, error = e.message) }
                    return@launch
                }
            }
            // Даты месяца сохраняем, только если их сдвинули руками, — иначе
            // месяц продолжит следовать дням по умолчанию.
            val moved = advanceDate != data.advanceDate || salaryDate != data.salaryDate
            if (moved || data.datesCustom) {
                repo.setPayrollDates(data.month, advanceDate, salaryDate).onFailure { e ->
                    _state.update { it.copy(busy = false, error = e.message) }
                    return@launch
                }
            }
            _state.update { it.copy(busy = false, datesOpen = false, message = "Даты выплат сохранены") }
            refresh()
        }
    }

    override fun resetDates() {
        val data = _state.value.data ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            repo.setPayrollDates(data.month, "", "")
                .onSuccess {
                    _state.update { it.copy(busy = false, datesOpen = false, message = "Даты вернулись к обычным") }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message) } }
        }
    }

    override fun clearMessage() = _state.update { it.copy(message = null) }
}

@Composable
fun PayrollScreen(
    refreshTick: Int = 0,
    viewModel: PayrollViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PayrollContent(state = state, actions = viewModel, refreshTick = refreshTick)
}

/** Разметка без ViewModel: её показывает превью. */
@Composable
fun PayrollContent(
    state: PayrollUiState,
    actions: PayrollActions,
    refreshTick: Int = 0,
) {
    LaunchedEffect(refreshTick) { if (refreshTick > 0) actions.refresh() }
    val data = state.data

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ListHeader {
                Text("Зарплаты", style = MaterialTheme.typography.headlineMedium)
                HintBlock(
                    "Аванс — начисленное за 1–15 число (окладнику — половина оклада), " +
                        "зарплата — остальное. Выходы отмечаются в разделе «Рабочие». " +
                        "Каждая выплата сразу ложится расходом в кассу.",
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { actions.shiftMonth(-1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Предыдущий месяц")
                    }
                    Text(
                        payrollMonthTitle(state.month),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    IconButton(onClick = { actions.shiftMonth(1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Следующий месяц")
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
                        data == null && state.loading -> item { LoadingBox() }
                        data == null -> item {
                            TextButton(onClick = actions::refresh) { Text("Повторить") }
                        }
                        else -> {
                            item { PayrollSummary(data) }
                            item { PayDatesCard(data, onEdit = actions::openDates) }
                            if (data.rows.isEmpty()) {
                                item {
                                    EmptyState(
                                        title = "Сотрудников нет",
                                        subtitle = "Добавьте рабочих в разделе «Рабочие»",
                                    )
                                }
                            }
                            items(data.rows, key = { it.workerId }) { row ->
                                PayrollRowCard(
                                    row = row,
                                    onPay = { kind -> actions.startPayment(row, kind) },
                                    onOpenPayment = actions::openPayment,
                                )
                            }
                        }
                    }
                }
            }
        }

        state.message?.let { message ->
            LaunchedEffect(message) {
                delay(3000)
                actions.clearMessage()
            }
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp),
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

    state.payment?.let { payment ->
        PaymentDialog(
            payment = payment,
            busy = state.busy,
            error = state.error,
            onChange = actions::updatePayment,
            onDismiss = actions::closePayment,
            onSave = actions::savePayment,
            onDelete = actions::deletePayment,
        )
    }

    if (state.datesOpen && data != null) {
        PayDatesDialog(
            data = data,
            busy = state.busy,
            error = state.error,
            onDismiss = actions::closeDates,
            onSave = actions::saveDates,
            onReset = actions::resetDates,
        )
    }
}

/** Четыре плитки: сколько начислено, выдано и что осталось выдать к каждой дате. */
@Composable
private fun PayrollSummary(data: PayrollDto) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(formatMoney(data.accruedKop), "Начислено за месяц", modifier = Modifier.weight(1f))
            StatTile(formatMoney(data.paidKop), "Уже выдано", accent = Success, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                formatMoney(data.advanceLeftKop),
                "Аванс к ${ruDate(data.advanceDate)}",
                accent = if (data.advanceLeftKop > 0) Warning else Success,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                formatMoney(data.salaryLeftKop),
                "Зарплата к ${ruDate(data.salaryDate)}",
                accent = if (data.salaryLeftKop > 0) Warning else Success,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Даты выплат месяца; нажатие — перенести даты или сменить обычные дни. */
@Composable
private fun PayDatesCard(data: PayrollDto, onEdit: () -> Unit) {
    SoftCard(onClick = onEdit) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Event, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text("Даты выплат", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Аванс ${ruDate(data.advanceDate)} · зарплата ${ruDate(data.salaryDate)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (data.datesCustom) "Перенесены вручную для этого месяца"
                    else "Обычно: аванс ${data.settings.advanceDay}-го, зарплата ${data.settings.salaryDay}-го следующего месяца",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (data.datesCustom) Warning else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Изменить", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Карточка сотрудника: начисление, аванс и зарплата — сколько положено,
 * выдано и осталось, кнопки выдачи и список выплат (нажатие — правка).
 */
@Composable
private fun PayrollRowCard(
    row: PayrollRowDto,
    onPay: (String) -> Unit,
    onOpenPayment: (SalaryPaymentDto) -> Unit,
) {
    val accent = when {
        row.balanceKop < 0 -> MaterialTheme.colorScheme.error
        row.balanceKop == 0L && row.accruedKop > 0 -> Success
        row.balanceKop == 0L -> MaterialTheme.colorScheme.outline
        else -> Warning
    }
    StatusRecordCard(accent = accent, onClick = {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(
                    row.position.ifBlank { null },
                    if (row.salaryType == SalaryType.MONTH) "оклад ${formatMoney(row.salaryKop)}"
                    else "${formatMoney(row.salaryKop)}/день · выходов ${row.days}",
                    if (!row.active) "не работает" else null,
                ).joinToString(" · ")
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatMoney(row.accruedKop), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    when {
                        row.balanceKop < 0 -> "переплата ${formatMoney(-row.balanceKop)}"
                        row.balanceKop == 0L -> "рассчитан"
                        else -> "долг ${formatMoney(row.balanceKop)}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PayPart(
                title = if (row.salaryType == SalaryType.MONTH) "Аванс" else "Аванс · ${row.advanceDays} дн.",
                plan = row.advancePlanKop, paid = row.advancePaidKop, left = row.advanceLeftKop,
                modifier = Modifier.weight(1f),
            )
            PayPart(
                title = "Зарплата",
                plan = row.salaryPlanKop, paid = row.salaryPaidKop, left = row.salaryLeftKop,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Главная кнопка — то, что выдавать следующим.
            val advanceFirst = row.advanceLeftKop > 0
            if (advanceFirst) {
                Button(onClick = { onPay(PayKind.ADVANCE) }, modifier = Modifier.weight(1f)) { Text("Выдать аванс") }
                OutlinedButton(onClick = { onPay(PayKind.SALARY) }, modifier = Modifier.weight(1f)) { Text("Зарплату") }
            } else {
                OutlinedButton(onClick = { onPay(PayKind.ADVANCE) }, modifier = Modifier.weight(1f)) { Text("Аванс") }
                if (row.salaryLeftKop > 0) {
                    Button(onClick = { onPay(PayKind.SALARY) }, modifier = Modifier.weight(1f)) { Text("Выдать зарплату") }
                } else {
                    FilledTonalButton(onClick = { onPay(PayKind.SALARY) }, modifier = Modifier.weight(1f)) { Text("Зарплата") }
                }
            }
        }
        if (row.payments.isNotEmpty()) {
            Spacer(Modifier.size(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            row.payments.forEach { p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPayment(p) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(ruDate(p.paidOn), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        PayKind.label(p.kind) + if (p.note.isNotBlank()) " · ${p.note}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(formatMoney(p.amountKop), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Блок «аванс» или «зарплата»: положено, выдано, осталось. */
@Composable
private fun PayPart(title: String, plan: Long, paid: Long, left: Long, modifier: Modifier = Modifier) {
    val done = left == 0L && plan > 0
    Surface(
        shape = MaterialTheme.shapes.small,
        color = (if (done) Success else if (left > 0) Warning else MaterialTheme.colorScheme.outline).copy(alpha = 0.08f),
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatMoney(plan), style = MaterialTheme.typography.titleSmall)
            if (paid > 0) {
                Text("выдано ${formatMoney(paid)}", style = MaterialTheme.typography.labelSmall, color = Success)
            }
            Text(
                if (left > 0) "осталось ${formatMoney(left)}" else if (plan > 0 || paid > 0) "выдан" else "—",
                style = MaterialTheme.typography.labelSmall,
                color = if (left > 0) Warning else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (left > 0) FontWeight.Bold else null,
            )
        }
    }
}

@Composable
private fun PaymentDialog(
    payment: SalaryPaymentDto,
    busy: Boolean,
    error: String?,
    onChange: (SalaryPaymentDto) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    val isNew = payment.id == 0L
    CrmDialog(
        title = if (isNew) "${PayKind.label(payment.kind)}: ${payment.workerName}" else "Выплата: ${payment.workerName}",
        subtitle = "За ${runCatching { payrollMonthTitle(YearMonth.parse(payment.month)).lowercase(RU) }.getOrDefault(payment.month)}",
        onDismiss = onDismiss,
        confirmText = if (isNew) "Выплатить" else "Сохранить",
        confirmEnabled = payment.amountKop > 0 && payment.paidOn.isNotBlank(),
        busy = busy,
        onConfirm = onSave,
    ) {
        ErrorBanner(error)
        FormSection("Что выдаём") {
            ChoiceChips(
                options = listOf(PayKind.ADVANCE to "Аванс", PayKind.SALARY to "Зарплата"),
                selected = payment.kind,
                onSelect = { onChange(payment.copy(kind = it)) },
            )
            MoneyField(
                kop = payment.amountKop,
                onKopChange = { onChange(payment.copy(amountKop = it)) },
                label = "Сумма, ₽",
                modifier = Modifier.fillMaxWidth(),
            )
            DatePickerField("Дата выплаты", payment.paidOn, iso = true, modifier = Modifier.fillMaxWidth()) {
                onChange(payment.copy(paidOn = it))
            }
        }
        FormSection("Откуда деньги") {
            PayMethodChips(selected = payment.method, onSelect = { onChange(payment.copy(method = it)) })
        }
        DialogField("Комментарий", payment.note) { onChange(payment.copy(note = it)) }
        Text(
            if (isNew) "Выплата запишется расходом в кассе со статьёй «Зарплата»."
            else "Строка в кассе поправится вместе с выплатой.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!isNew) {
            DangerZone(
                actionLabel = "Удалить выплату",
                what = "выплату ${formatMoney(payment.amountKop)} для «${payment.workerName}»",
                consequences = "Выплата исчезнет из ведомости, а расход — из кассы.",
                onConfirm = onDelete,
                enabled = !busy,
            )
        }
    }
}

/**
 * Даты выплат: перенос для этого месяца (праздники, выходные) и обычные
 * дни, которые действуют для всех остальных месяцев.
 */
@Composable
private fun PayDatesDialog(
    data: PayrollDto,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (String, String, PayrollSettingsDto) -> Unit,
    onReset: () -> Unit,
) {
    var advance by remember(data) { mutableStateOf(data.advanceDate) }
    var salary by remember(data) { mutableStateOf(data.salaryDate) }
    var advanceDay by remember(data) { mutableStateOf(data.settings.advanceDay.toString()) }
    var salaryDay by remember(data) { mutableStateOf(data.settings.salaryDay.toString()) }
    val advDay = advanceDay.toIntOrNull()
    val salDay = salaryDay.toIntOrNull()
    val daysOk = advDay in 1..31 && salDay in 1..31

    CrmDialog(
        title = "Даты выплат",
        subtitle = payrollMonthTitle(YearMonth.parse(data.month)),
        onDismiss = onDismiss,
        confirmText = "Сохранить",
        confirmEnabled = daysOk && advance.isNotBlank() && salary.isNotBlank(),
        busy = busy,
        onConfirm = { onSave(advance, salary, PayrollSettingsDto(advDay ?: 20, salDay ?: 5)) },
    ) {
        ErrorBanner(error)
        FormSection("Этот месяц") {
            DatePickerField("Аванс", advance, iso = true, modifier = Modifier.fillMaxWidth()) { advance = it }
            DatePickerField("Зарплата", salary, iso = true, modifier = Modifier.fillMaxWidth()) { salary = it }
            if (data.datesCustom) {
                TextButton(onClick = onReset, enabled = !busy) { Text("Вернуть обычные даты") }
            }
        }
        FormSection("Обычно, каждый месяц") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = advanceDay,
                    onValueChange = { v -> advanceDay = v.filter(Char::isDigit).take(2) },
                    label = { Text("Аванс, число") },
                    singleLine = true,
                    isError = advDay !in 1..31,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = salaryDay,
                    onValueChange = { v -> salaryDay = v.filter(Char::isDigit).take(2) },
                    label = { Text("Зарплата, число") },
                    singleLine = true,
                    isError = salDay !in 1..31,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "Аванс — в расчётном месяце, зарплата — в следующем. 31-е в коротком месяце — последний день.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Превью для Android Studio.
// ---------------------------------------------------------------------------

@Preview(name = "Зарплаты", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun PayrollContentPreview() = PreviewScreen {
    PayrollContent(
        state = PayrollUiState(
            loading = false,
            month = YearMonth.of(2026, 9),
            data = PayrollDto(
                month = "2026-09", advanceDate = "2026-09-20", salaryDate = "2026-10-05",
                accruedKop = 6_900_000, paidKop = 700_000, advanceLeftKop = 3_000_000, salaryLeftKop = 3_200_000,
                rows = listOf(
                    PayrollRowDto(
                        workerId = 1, name = "Иван Петров", position = "Монтажник", salaryKop = 300_000,
                        days = 3, advanceDays = 2, accruedKop = 900_000, advancePlanKop = 600_000,
                        salaryPlanKop = 300_000, advancePaidKop = 700_000, salaryLeftKop = 200_000, balanceKop = 200_000,
                        payments = listOf(SalaryPaymentDto(id = 1, kind = PayKind.ADVANCE, amountKop = 700_000, paidOn = "2026-09-19")),
                    ),
                    PayrollRowDto(
                        workerId = 2, name = "Пётр Сидоров", salaryType = SalaryType.MONTH, salaryKop = 6_000_000,
                        accruedKop = 6_000_000, advancePlanKop = 3_000_000, salaryPlanKop = 3_000_000,
                        advanceLeftKop = 3_000_000, salaryLeftKop = 3_000_000, balanceKop = 6_000_000,
                    ),
                ),
            ),
        ),
        actions = object : PayrollActions {},
    )
}
