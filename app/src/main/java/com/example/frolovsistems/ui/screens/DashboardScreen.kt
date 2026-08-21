package com.example.frolovsistems.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frolovsistems.core.net.RequestDto
import com.example.frolovsistems.core.net.StatsDto
import com.example.frolovsistems.data.CrmRepository
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardUiState(
    val loading: Boolean = true,
    val stats: StatsDto = StatsDto(),
    val latestRequests: List<RequestDto> = emptyList(),
    val siteName: String = "",
    val siteRevision: Long = 0,
    val error: String? = null,
)

class DashboardViewModel(
    private val crm: CrmRepository = ServiceLocator.crm,
    private val site: SiteRepository = ServiceLocator.site,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }

            val stats = crm.stats()
            val requests = crm.requests()
            val content = site.load()

            val error = listOf(stats, requests, content)
                .firstOrNull { it.isFailure }
                ?.exceptionOrNull()?.message

            _state.update { current ->
                current.copy(
                    loading = false,
                    stats = stats.getOrNull() ?: current.stats,
                    latestRequests = requests.getOrNull()?.take(5) ?: current.latestRequests,
                    siteName = content.getOrNull()?.siteName ?: current.siteName,
                    siteRevision = content.getOrNull()?.revision ?: current.siteRevision,
                    error = error,
                )
            }
        }
    }
}

@Composable
fun DashboardScreen(viewModel: DashboardViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Сводка", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = state.siteName.ifBlank { "Загрузка…" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RefreshButton(loading = state.loading, onClick = viewModel::refresh)
            }
        }

        item { ErrorBanner(state.error) }

        if (state.loading && state.siteName.isBlank()) {
            item { LoadingBox() }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        value = state.stats.clients.toString(),
                        label = "Клиентов",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = state.stats.requestsNew.toString(),
                        label = "Новых заявок",
                        accent = MaterialTheme.colorScheme.secondary,
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
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = state.stats.ordersDone.toString(),
                        label = "Заказов завершено",
                        accent = Success,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                SoftCard {
                    SectionHeader("Выручка", "по данным заказов")
                    Spacer(Modifier.height(10.dp))
                    MoneyRow("Завершённые заказы", state.stats.revenueTotal, Success)
                    Spacer(Modifier.height(6.dp))
                    MoneyRow("В работе", state.stats.revenueActive, Warning)
                }
            }

            item {
                Text(
                    "Последние заявки",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (state.latestRequests.isEmpty()) {
                item {
                    EmptyState(
                        title = "Заявок пока нет",
                        subtitle = "Здесь появятся обращения с формы на сайте",
                    )
                }
            } else {
                items(state.latestRequests, key = { it.id }) { request ->
                    SoftCard {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(request.name, style = MaterialTheme.typography.titleMedium)
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
}

@Composable
private fun MoneyRow(label: String, value: Double, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = formatMoney(value),
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Bold,
        )
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
fun requestStatusColor(status: String): androidx.compose.ui.graphics.Color = when (status) {
    "new" -> MaterialTheme.colorScheme.primary
    "in_progress" -> Warning
    "done" -> Success
    else -> MaterialTheme.colorScheme.outline
}
