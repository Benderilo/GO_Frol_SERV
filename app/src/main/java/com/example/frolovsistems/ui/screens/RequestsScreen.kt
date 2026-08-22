package com.example.frolovsistems.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frolovsistems.core.net.RequestDto
import com.example.frolovsistems.data.CrmRepository
import com.example.frolovsistems.di.ServiceLocator
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

private val requestStatuses = listOf(
    "new" to "Новые",
    "in_progress" to "В работе",
    "done" to "Обработанные",
    "spam" to "Спам",
)

data class RequestsUiState(
    val loading: Boolean = true,
    val filter: String = "",
    val items: List<RequestDto> = emptyList(),
    val error: String? = null,
)

class RequestsViewModel(
    private val crm: CrmRepository = ServiceLocator.crm,
) : ViewModel() {

    private val _state = MutableStateFlow(RequestsUiState())
    val state: StateFlow<RequestsUiState> = _state.asStateFlow()

    init { refresh() }

    fun setFilter(status: String) {
        _state.update { it.copy(filter = status) }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            crm.requests(_state.value.filter)
                .onSuccess { list -> _state.update { it.copy(loading = false, items = list) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun setStatus(request: RequestDto, status: String) {
        viewModelScope.launch {
            crm.setRequestStatus(request.id, status)
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun delete(request: RequestDto) {
        viewModelScope.launch {
            crm.deleteRequest(request.id)
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }
}

@Composable
fun RequestsScreen(viewModel: RequestsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<RequestDto?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Заявки с сайта", style = MaterialTheme.typography.headlineMedium)
                IconButton(onClick = viewModel::refresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                }
            }
        }

        item {
            // Статусов больше, чем влезает в узкий экран, — строку можно прокручивать.
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.filter.isEmpty(),
                    onClick = { viewModel.setFilter("") },
                    label = { Text("Все") },
                )
                requestStatuses.forEach { (value, label) ->
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
                    title = "Заявок нет",
                    subtitle = "Обращения с формы на сайте появятся здесь автоматически",
                )
            }
            else -> items(state.items, key = { it.id }) { request ->
                RequestCard(
                    request = request,
                    onStatus = { status -> viewModel.setStatus(request, status) },
                    onDelete = { pendingDelete = request },
                )
            }
        }
    }

    pendingDelete?.let { request ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить заявку?") },
            text = { Text("Заявка от «${request.name}» будет удалена безвозвратно.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.delete(request)
                    pendingDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun RequestCard(
    request: RequestDto,
    onStatus: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    SoftCard(
        onClick = { expanded = !expanded },
        highlighted = request.status == "new",
    ) {
        Column(Modifier.animateContentSize(tween(260))) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(request.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        request.phone,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(
                    text = requestStatusLabel(request.status),
                    color = requestStatusColor(request.status),
                )
            }

            if (request.message.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    request.message,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                )
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Получена: ${request.createdAt}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    requestStatuses.forEach { (value, label) ->
                        FilterChip(
                            selected = request.status == value,
                            onClick = { onStatus(value) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("Удалить заявку", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
