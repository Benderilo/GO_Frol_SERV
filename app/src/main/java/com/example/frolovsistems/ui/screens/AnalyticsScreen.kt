package com.example.frolovsistems.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingFlat
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frolovsistems.core.net.AnalyticsDto
import com.example.frolovsistems.core.net.AnalyticsMonthDto
import com.example.frolovsistems.data.CrmRepository
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.components.CollapsibleCard
import com.example.frolovsistems.ui.components.ErrorBanner
import com.example.frolovsistems.ui.components.LoadingBox
import com.example.frolovsistems.ui.components.StatTile
import com.example.frolovsistems.ui.components.formatMoney
import com.example.frolovsistems.ui.components.pluralOrders
import com.example.frolovsistems.ui.theme.Success
import com.example.frolovsistems.ui.theme.Warning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/** Куда ведёт нажатие на плитку со сводным числом. */
object SummaryTargets {
    const val CLIENTS = "clients"
    const val NEW_REQUESTS = "requests?status=new"
    const val ACTIVE_ORDERS = "orders?status=in_progress"
    const val DONE_ORDERS = "orders?status=done"
}

data class AnalyticsUiState(
    val loading: Boolean = true,
    val data: AnalyticsDto? = null,
    val error: String? = null,
)

class AnalyticsViewModel(
    private val crm: CrmRepository = ServiceLocator.crm,
) : ViewModel() {

    private val _state = MutableStateFlow(AnalyticsUiState())
    val state: StateFlow<AnalyticsUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            crm.analytics()
                .onSuccess { data -> _state.update { it.copy(loading = false, data = data) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }
}

@Composable
fun AnalyticsScreen(
    onOpenSection: (String) -> Unit = {},
    refreshTick: Int = 0,
    viewModel: AnalyticsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Какие разделы свёрнуты: при входе закрыто всё, кроме верхних плиток,
    // чтобы экран открывался компактным. Выбор переживает возврат на экран.
    var collapsed by rememberSaveable {
        mutableStateOf(
            setOf(
                "revenue", "money",
                "chartRevenue", "chartPayments", "chartOrders", "chartClients",
                "orderStatus", "requests", "clients", "topClients",
            ),
        )
    }
    fun isExpanded(key: String) = key !in collapsed
    fun toggle(key: String) {
        collapsed = if (key in collapsed) collapsed - key else collapsed + key
    }

    // Обновляемся при каждом входе на вкладку.
    LaunchedEffect(Unit) { viewModel.refresh() }
    // Кнопка «Обновить» живёт в общей шапке и присылает сюда новый тик.
    LaunchedEffect(refreshTick) { if (refreshTick > 0) viewModel.refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
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
                        text = state.data?.let { "данные на ${formatGeneratedAt(it.generatedAt)}" }
                            ?: "загрузка…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item { ConnectionStatusBar(error = state.error, hasData = state.data != null) }

        item { ErrorBanner(state.error) }

        if (state.loading && state.data == null) {
            item { LoadingBox() }
        }

        state.data?.let { data ->
            // Плитки ведут в нужный раздел с уже выставленным фильтром:
            // сводное число без перехода к самим записям бесполезно.
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        value = data.clients.total.toString(),
                        label = "Клиентов",
                        onClick = { onOpenSection(SummaryTargets.CLIENTS) },
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = data.requests.new.toString(),
                        label = "Новых заявок",
                        accent = Warning,
                        onClick = { onOpenSection(SummaryTargets.NEW_REQUESTS) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        value = data.orders.inProgress.toString(),
                        label = "В работе",
                        onClick = { onOpenSection(SummaryTargets.ACTIVE_ORDERS) },
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = data.orders.done.toString(),
                        label = "Завершено",
                        accent = Success,
                        onClick = { onOpenSection(SummaryTargets.DONE_ORDERS) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item { RevenueSummaryCard(data, isExpanded("revenue")) { toggle("revenue") } }
            item { MoneySummaryCard(data, isExpanded("money")) { toggle("money") } }

            item {
                ChartCard(
                    title = "Выручка по месяцам",
                    subtitle = "закрытые заказы, ₽",
                    empty = data.monthly.all { it.revenueKop <= 0L },
                    expanded = isExpanded("chartRevenue"),
                    onToggle = { toggle("chartRevenue") },
                ) {
                    MoneyBarChart(data.monthly) { it.revenueKop }
                }
            }

            item {
                ChartCard(
                    title = "Поступления по месяцам",
                    subtitle = "реально пришедшие деньги, ₽",
                    empty = data.monthly.all { it.paymentsKop <= 0L },
                    expanded = isExpanded("chartPayments"),
                    onToggle = { toggle("chartPayments") },
                ) {
                    MoneyBarChart(data.monthly) { it.paymentsKop }
                }
            }

            item {
                ChartCard(
                    title = "Заказы по месяцам",
                    subtitle = "создано и завершено",
                    empty = data.monthly.all { it.ordersCreated == 0L && it.ordersDone == 0L },
                    expanded = isExpanded("chartOrders"),
                    onToggle = { toggle("chartOrders") },
                ) {
                    OrdersGroupedChart(data.monthly)
                }
            }

            item {
                ChartCard(
                    title = "Клиенты и заявки",
                    subtitle = "новые за месяц",
                    empty = data.monthly.all { it.newClients == 0L && it.requests == 0L },
                    expanded = isExpanded("chartClients"),
                    onToggle = { toggle("chartClients") },
                ) {
                    ClientsRequestsLineChart(data.monthly)
                }
            }

            item { OrderStatusCard(data, isExpanded("orderStatus")) { toggle("orderStatus") } }
            item { RequestsCard(data, isExpanded("requests")) { toggle("requests") } }
            item { ClientsCard(data, isExpanded("clients")) { toggle("clients") } }

            if (data.topClients.isNotEmpty()) {
                item { TopClientsCard(data, isExpanded("topClients")) { toggle("topClients") } }
            }
        }
    }
}

// ------------------------------ Карточки ------------------------------------

/**
 * Полоска состояния связи над разделами: две лампочки — интернет
 * на телефоне и отвечает ли сервер. Сервер считаем доступным, если
 * последнее обновление прошло без ошибки.
 */
@Composable
private fun ConnectionStatusBar(error: String?, hasData: Boolean) {
    val context = LocalContext.current
    // Считаем при каждой рекомпозиции: после обновления/ошибки экран и так перерисуется.
    val networkUp = isNetworkAvailable(context)
    val serverUp = hasData && error == null
    val serverFailed = error != null

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (networkUp) {
            StatusLamp("Сеть доступна", Success)
        } else {
            StatusLamp("Нет сети", MaterialTheme.colorScheme.error)
        }
        when {
            serverUp -> StatusLamp("Сервер доступен", Success)
            serverFailed -> StatusLamp("Сервер не отвечает", MaterialTheme.colorScheme.error)
            else -> StatusLamp("Проверяем сервер…", MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusLamp(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

private fun isNetworkAvailable(context: Context): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val network = manager.activeNetwork ?: return false
    val caps = manager.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** Главная карточка: сколько заработали и куда движемся. */
@Composable
private fun RevenueSummaryCard(data: AnalyticsDto, expanded: Boolean, onToggle: () -> Unit) {
    CollapsibleCard("Выручка", "по завершённым заказам", expanded, onToggle) {
        Spacer(Modifier.height(8.dp))
        Text(
            formatMoney(data.revenue.totalKop),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.ExtraBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Ещё ${formatMoney(data.revenue.activeKop)} в работе",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        GrowthChip(
            thisMonth = data.revenue.thisMonthKop,
            lastMonth = data.revenue.lastMonthKop,
            growthPct = data.revenue.growthPct,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryTile(
                value = formatMoney(data.revenue.avgOrderKop),
                label = "Средний чек",
                accent = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
            )
            SummaryTile(
                value = formatMoney(data.revenue.thisMonthKop),
                label = "В этом месяце",
                accent = Success,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Карточка денег: факт поступлений и что ещё должны клиенты. */
@Composable
private fun MoneySummaryCard(data: AnalyticsDto, expanded: Boolean, onToggle: () -> Unit) {
    CollapsibleCard("Деньги", "поступления и долги", expanded, onToggle) {
        Spacer(Modifier.height(8.dp))
        Text(
            formatMoney(data.payments.totalKop),
            style = MaterialTheme.typography.displaySmall,
            color = Success,
            fontWeight = FontWeight.ExtraBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "поступило за всё время",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        GrowthChip(
            thisMonth = data.payments.thisMonthKop,
            lastMonth = data.payments.lastMonthKop,
            growthPct = data.payments.growthPct,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryTile(
                value = formatMoney(data.payments.thisMonthKop),
                label = "В этом месяце",
                accent = Success,
                modifier = Modifier.weight(1f),
            )
            SummaryTile(
                value = formatMoney(data.debt.outstandingKop),
                label = "Должны клиенты",
                accent = Warning,
                modifier = Modifier.weight(1f),
            )
        }
        if (data.debt.overdueKop > 0.0) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Просрочено к оплате: ${formatMoney(data.debt.overdueKop)} " +
                    "(${data.debt.ordersWithDebt} ${pluralOrders(data.debt.ordersWithDebt)} ждут оплату)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
            )
        } else if (data.debt.ordersWithDebt > 0) {
            Spacer(Modifier.height(12.dp))
            Text(
                "По ${data.debt.ordersWithDebt} ${pluralOrders(data.debt.ordersWithDebt)} ещё ждут оплату — просрочки нет",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Плитка со сводным числом внутри карточки выручки. */
@Composable
private fun SummaryTile(
    value: String,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.10f))
            .padding(12.dp),
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = accent,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Динамика месяца к месяцу: рост, падение или не с чем сравнивать. */
@Composable
private fun GrowthChip(thisMonth: Long, lastMonth: Long, growthPct: Double) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            lastMonth <= 0 && thisMonth > 0 -> {
                Icon(Icons.Default.TrendingUp, null, tint = Success, modifier = Modifier.size(18.dp))
                Text(
                    "за прошлый месяц выручки не было",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            growthPct > 0.05 -> {
                Icon(Icons.Default.TrendingUp, null, tint = Success, modifier = Modifier.size(18.dp))
                Text(
                    "+${formatPercent(growthPct)} к прошлому месяцу",
                    style = MaterialTheme.typography.bodySmall,
                    color = Success,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            growthPct < -0.05 -> {
                Icon(Icons.Default.TrendingDown, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Text(
                    "−${formatPercent(-growthPct)} к прошлому месяцу",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            else -> {
                Icon(Icons.Default.TrendingFlat, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                Text(
                    "на уровне прошлого месяца",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Обёртка для графиков: заголовок, подзаголовок и заглушка без данных. */
@Composable
private fun ChartCard(
    title: String,
    subtitle: String,
    empty: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    CollapsibleCard(title, subtitle, expanded, onToggle) {
        Spacer(Modifier.height(14.dp))
        if (empty) {
            Text(
                "Пока нет данных за последние 12 месяцев",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            content()
        }
    }
}

/** Статусы заказов: полосы долей + средний чек и процент завершения. */
@Composable
private fun OrderStatusCard(data: AnalyticsDto, expanded: Boolean, onToggle: () -> Unit) {
    CollapsibleCard("Заказы по статусам", "всего ${data.orders.total}", expanded, onToggle) {
        Spacer(Modifier.height(12.dp))
        DistributionRow("Новые", data.orders.new, data.orders.total, MaterialTheme.colorScheme.secondary)
        DistributionRow("В работе", data.orders.inProgress, data.orders.total, Warning)
        DistributionRow("Завершены", data.orders.done, data.orders.total, Success)
        DistributionRow("Отменены", data.orders.canceled, data.orders.total, MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryTile(
                value = formatPercent(data.orders.completionRate),
                label = "Завершено",
                accent = Success,
                modifier = Modifier.weight(1f),
            )
            SummaryTile(
                value = formatMoney(data.orders.avgPriceKop),
                label = "Средний чек",
                accent = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
            )
            SummaryTile(
                value = formatCycleDays(data.orders.avgCycleDays),
                label = "Средний срок",
                accent = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Заявки с сайта: конверсия в обработанные и статусы. */
@Composable
private fun RequestsCard(data: AnalyticsDto, expanded: Boolean, onToggle: () -> Unit) {
    CollapsibleCard("Заявки с сайта", "всего ${data.requests.total}", expanded, onToggle) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                formatPercent(data.requests.conversion),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.ExtraBold,
            )
            Column(Modifier.padding(bottom = 6.dp)) {
                Text("обработано", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "не считая спама",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        DistributionRow("Новые", data.requests.new, data.requests.total, MaterialTheme.colorScheme.primary)
        DistributionRow("В работе", data.requests.inProgress, data.requests.total, Warning)
        DistributionRow("Обработаны", data.requests.done, data.requests.total, Success)
        DistributionRow("Спам", data.requests.spam, data.requests.total, MaterialTheme.colorScheme.outline)
    }
}

/** База клиентов несколькими числами. */
@Composable
private fun ClientsCard(data: AnalyticsDto, expanded: Boolean, onToggle: () -> Unit) {
    CollapsibleCard("Клиенты", null, expanded, onToggle) {
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryTile(
                value = data.clients.total.toString(),
                label = "Всего",
                accent = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            SummaryTile(
                value = data.clients.newThisMonth.toString(),
                label = "Новых за месяц",
                accent = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
            )
            SummaryTile(
                value = data.clients.withOrders.toString(),
                label = "С заказами",
                accent = Success,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryTile(
                value = data.clients.repeat.toString(),
                label = "Повторные",
                accent = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
            )
            SummaryTile(
                value = formatPercent(data.clients.repeatRate),
                label = "Повторные, %",
                accent = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Топ клиентов по выручке завершённых заказов. */
@Composable
private fun TopClientsCard(data: AnalyticsDto, expanded: Boolean, onToggle: () -> Unit) {
    val max = data.topClients.maxOf { it.revenueDoneKop }.coerceAtLeast(1L)
    CollapsibleCard("Топ клиентов", "по выручке завершённых заказов", expanded, onToggle) {
        Spacer(Modifier.height(12.dp))
        data.topClients.forEachIndexed { index, client ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.EmojiEvents,
                    contentDescription = null,
                    tint = when (index) {
                        0 -> Color(0xFFD4A017)
                        1 -> MaterialTheme.colorScheme.outline
                        2 -> Color(0xFFB87333)
                        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    },
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(client.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${pluralOrders(client.ordersTotal)} • ${formatMoney(client.revenueDoneKop)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth((client.revenueDoneKop.toFloat() / max).coerceIn(0.02f, 1f))
                                .height(5.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
                if (client.revenueAllKop > client.revenueDoneKop) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "+${formatMoney(client.revenueAllKop - client.revenueDoneKop)} в работе",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(86.dp),
                    )
                }
            }
        }
    }
}

/** Строка распределения: подпись, счётчик и анимированная полоса доли. */
@Composable
private fun DistributionRow(label: String, count: Long, total: Long, color: Color) {
    val fraction = if (total > 0) count.toFloat() / total else 0f
    val animated by animateFloatAsState(fraction, tween(700), label = "distribution")
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "$count • ${formatPercent(fraction * 100.0)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(animated.coerceIn(0f, 1f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(color.copy(alpha = 0.85f)),
            )
        }
    }
}

// ------------------------------- Графики ------------------------------------

/** Столбики денег по месяцам: выручка или поступления — зависит от selector. */
@Composable
private fun MoneyBarChart(
    points: List<AnalyticsMonthDto>,
    values: (AnalyticsMonthDto) -> Long,
) {
    val measurer = rememberTextMeasurer()
    val accent = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val valueStyle = TextStyle(fontSize = 8.sp, color = labelColor)
    val monthStyle = TextStyle(fontSize = 9.sp, color = labelColor)

    Canvas(Modifier.fillMaxWidth().height(190.dp)) {
        if (points.isEmpty()) return@Canvas
        val max = points.maxOf(values).coerceAtLeast(1L)
        val chartTop = 18.dp.toPx()
        val chartBottom = size.height - 18.dp.toPx()
        val chartHeight = chartBottom - chartTop
        val slot = size.width / points.size
        val barWidth = slot * 0.44f

        points.forEachIndexed { i, p ->
            val value = values(p)
            val barHeight = value.toFloat() / max * chartHeight
            val x = i * slot + (slot - barWidth) / 2f
            val y = chartBottom - barHeight

            if (value > 0L) {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(accent, accent.copy(alpha = 0.25f)),
                        startY = y,
                        endY = chartBottom,
                    ),
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
                val label = measurer.measure(compactMoneyShort(value), valueStyle)
                drawText(
                    label,
                    topLeft = Offset(i * slot + (slot - label.size.width) / 2f, y - label.size.height - 2.dp.toPx()),
                )
            }

            val month = measurer.measure(monthShort(p.month), monthStyle)
            drawText(
                month,
                topLeft = Offset(i * slot + (slot - month.size.width) / 2f, chartBottom + 5.dp.toPx()),
            )
        }
    }
}

/** Сгруппированные столбики: создано (синий) и завершено (зелёный). */
@Composable
private fun OrdersGroupedChart(points: List<AnalyticsMonthDto>) {
    val measurer = rememberTextMeasurer()
    val createdColor = MaterialTheme.colorScheme.secondary
    val doneColor = Success
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val valueStyle = TextStyle(fontSize = 8.sp, color = labelColor)
    val monthStyle = TextStyle(fontSize = 9.sp, color = labelColor)

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            ChartLegend(color = createdColor, label = "создано")
            ChartLegend(color = doneColor, label = "завершено")
        }
        Canvas(Modifier.fillMaxWidth().height(180.dp)) {
            if (points.isEmpty()) return@Canvas
            val max = maxOf(
                points.maxOf { it.ordersCreated },
                points.maxOf { it.ordersDone },
            ).coerceAtLeast(1L).toFloat()
            val chartTop = 14.dp.toPx()
            val chartBottom = size.height - 18.dp.toPx()
            val chartHeight = chartBottom - chartTop
            val slot = size.width / points.size
            val barWidth = slot * 0.28f

            points.forEachIndexed { i, p ->
                val createdH = (p.ordersCreated / max) * chartHeight
                val doneH = (p.ordersDone / max) * chartHeight
                val left = i * slot + slot / 2 - barWidth - 1.5.dp.toPx()
                val right = i * slot + slot / 2 + 1.5.dp.toPx()

                if (p.ordersCreated > 0) {
                    val y = chartBottom - createdH
                    drawRoundRect(
                        color = createdColor.copy(alpha = 0.85f),
                        topLeft = Offset(left, y),
                        size = Size(barWidth, createdH),
                        cornerRadius = CornerRadius(3.dp.toPx()),
                    )
                    val value = measurer.measure(p.ordersCreated.toString(), valueStyle)
                    drawText(value, topLeft = Offset(left + barWidth / 2 - value.size.width / 2f, y - value.size.height - 2.dp.toPx()))
                }
                if (p.ordersDone > 0) {
                    val y = chartBottom - doneH
                    drawRoundRect(
                        color = doneColor.copy(alpha = 0.85f),
                        topLeft = Offset(right, y),
                        size = Size(barWidth, doneH),
                        cornerRadius = CornerRadius(3.dp.toPx()),
                    )
                    val value = measurer.measure(p.ordersDone.toString(), valueStyle)
                    drawText(value, topLeft = Offset(right + barWidth / 2 - value.size.width / 2f, y - value.size.height - 2.dp.toPx()))
                }

                val label = measurer.measure(monthShort(p.month), monthStyle)
                drawText(
                    label,
                    topLeft = Offset(i * slot + (slot - label.size.width) / 2f, chartBottom + 5.dp.toPx()),
                )
            }
        }
    }
}

/** Две линии: новые клиенты и заявки по месяцам. */
@Composable
private fun ClientsRequestsLineChart(points: List<AnalyticsMonthDto>) {
    val measurer = rememberTextMeasurer()
    val clientsColor = MaterialTheme.colorScheme.primary
    val requestsColor = MaterialTheme.colorScheme.secondary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val monthStyle = TextStyle(fontSize = 9.sp, color = labelColor)
    val maxStyle = TextStyle(fontSize = 8.sp, color = labelColor)

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            ChartLegend(color = clientsColor, label = "клиенты")
            ChartLegend(color = requestsColor, label = "заявки")
        }
        Canvas(Modifier.fillMaxWidth().height(180.dp)) {
            if (points.isEmpty()) return@Canvas
            val max = maxOf(
                points.maxOf { it.newClients },
                points.maxOf { it.requests },
            ).coerceAtLeast(1L).toFloat()
            val chartTop = 8.dp.toPx()
            val chartBottom = size.height - 18.dp.toPx()
            val chartHeight = chartBottom - chartTop

            // Сетка: три уровня с подписью максимума.
            for (step in 1..3) {
                val y = chartBottom - chartHeight * step / 3f
                drawLine(
                    color = gridColor.copy(alpha = 0.5f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                )
                if (step == 3) {
                    val label = measurer.measure(max.toLong().toString(), maxStyle)
                    drawText(label, topLeft = Offset(0f, y - label.size.height - 2.dp.toPx()))
                }
            }

            fun linePath(selector: (AnalyticsMonthDto) -> Long): Path {
                val path = Path()
                points.forEachIndexed { i, p ->
                    val x = (i + 0.5f) / points.size * size.width
                    val y = chartBottom - selector(p) / max * chartHeight
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                return path
            }

            listOf(
                linePath { it.requests } to requestsColor,
                linePath { it.newClients } to clientsColor,
            ).forEach { (path, color) ->
                drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }

            // Точки на линиях: заметны на стыке месяцев.
            points.forEachIndexed { i, p ->
                val x = (i + 0.5f) / points.size * size.width
                val yClients = chartBottom - p.newClients / max * chartHeight
                val yRequests = chartBottom - p.requests / max * chartHeight
                drawCircle(clientsColor, radius = 2.5.dp.toPx(), center = Offset(x, yClients))
                drawCircle(requestsColor, radius = 2.5.dp.toPx(), center = Offset(x, yRequests))

                val label = measurer.measure(monthShort(p.month), monthStyle)
                drawText(
                    label,
                    topLeft = Offset(i * slotWidth(size.width, points.size) + (slotWidth(size.width, points.size) - label.size.width) / 2f, chartBottom + 5.dp.toPx()),
                )
            }
        }
    }
}

private fun slotWidth(width: Float, count: Int): Float = if (count == 0) width else width / count

@Composable
private fun ChartLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------ Оформление ----------------------------------

// ------------------------------ Форматирование -------------------------------

private val monthShortNames = mapOf(
    "01" to "янв", "02" to "фев", "03" to "мар", "04" to "апр",
    "05" to "май", "06" to "июн", "07" to "июл", "08" to "авг",
    "09" to "сен", "10" to "окт", "11" to "ноя", "12" to "дек",
)

private fun monthShort(month: String): String =
    monthShortNames[month.takeLast(2)] ?: month

/** «165к» / «1,2М» — короткие подписи над столбиками. На вход копейки. */
private fun compactMoneyShort(kop: Long): String = compactRubles(kop / 100.0)

private fun compactRubles(value: Double): String = when {
    value >= 1_000_000 -> {
        val m = value / 1_000_000
        if (m >= 100 || m == m.toLong().toDouble()) "${m.toLong()}М"
        else "${(m * 10).roundToLong() / 10.0}".replace('.', ',') + "М"
    }
    value >= 1_000 -> "${(value / 1_000).toLong()}к"
    else -> value.toLong().toString()
}

/** «12 дн.» — средний срок заказа; «—», если завершённых нет. */
private fun formatCycleDays(value: Double): String =
    if (value <= 0.0) "—" else "${value.roundToLong()} дн."

/** «66,7%» — с запятой и без хвостовых нулей. */
private fun formatPercent(value: Double): String {
    val rounded = (value * 10).roundToLong() / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        "${rounded.toLong()}%"
    } else {
        rounded.toString().replace('.', ',') + "%"
    }
}

private val generatedAtFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

private fun formatGeneratedAt(value: String): String = runCatching {
    OffsetDateTime.parse(value).format(generatedAtFormatter)
}.getOrDefault("—")
