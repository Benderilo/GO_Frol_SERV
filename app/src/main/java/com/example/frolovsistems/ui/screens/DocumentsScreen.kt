package com.example.frolovsistems.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frolovsistems.core.net.ClientDto
import com.example.frolovsistems.core.net.DocumentBody
import com.example.frolovsistems.core.net.DocumentDto
import com.example.frolovsistems.core.net.OrderDto
import com.example.frolovsistems.data.CrmRepository
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.components.EmptyState
import com.example.frolovsistems.ui.components.ErrorBanner
import com.example.frolovsistems.ui.components.HintBlock
import com.example.frolovsistems.ui.components.LoadingBox
import com.example.frolovsistems.ui.components.SearchField
import com.example.frolovsistems.ui.components.SoftCard
import com.example.frolovsistems.ui.components.formatMoney
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Фильтры журнала: вид и статус. Пустое значение — показывать всё. */
private val kindFilters = listOf("") + DocumentKind.all
private val statusFilters = listOf(
    "" to "Все",
    DocumentStatus.DRAFT to "Черновики",
    DocumentStatus.ISSUED to "Действующие",
    DocumentStatus.ANNULLED to "Аннулированные",
)

data class DocumentsUiState(
    val loading: Boolean = true,
    val documents: List<DocumentDto> = emptyList(),
    val kind: String = "",
    val status: String = "",
    val query: String = "",
    val error: String? = null,
)

class DocumentsViewModel(
    private val crm: CrmRepository = ServiceLocator.crm,
) : ViewModel() {

    private val _state = MutableStateFlow(DocumentsUiState())
    val state: StateFlow<DocumentsUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            crm.documents(kind = _state.value.kind, status = _state.value.status, query = _state.value.query)
                .onSuccess { list -> _state.update { it.copy(loading = false, documents = list) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun setKind(kind: String) {
        _state.update { it.copy(kind = kind) }
        refresh()
    }

    fun setStatus(status: String) {
        _state.update { it.copy(status = status) }
        refresh()
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        refresh()
    }
}

/**
 * Журнал печатных документов: счёта, акты, сметы, накладные, УПД и акты
 * сверки — в одном месте, с фильтрами по виду и статусу. Нажатие открывает
 * печатную форму с действиями по статусу.
 */
@Composable
fun DocumentsScreen(
    refreshTick: Int = 0,
    onOpenDocument: (Long) -> Unit = {},
    viewModel: DocumentsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showNewDialog by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTick) { if (refreshTick > 0) viewModel.refresh() }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Новый документ") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column {
                    Text("Документы", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Счета, акты, сметы, накладные, УПД и сверки. " +
                            "Номер закрепляется при проведении.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                HintBlock(
                    "Новый документ — кнопка «Новый документ» внизу: выберите вид, затем " +
                        "заказ или клиента. Счёт, акт, накладную и УПД удобнее открывать из " +
                        "карточки заказа, смету и акт сверки — из карточки клиента. Черновик " +
                        "можно изменить и «Провести»: номер закрепится, а содержимое заморозится. " +
                        "Проведённый документ печатается и сохраняется в PDF, отменяется аннулированием.",
                )
            }
            item {
                SearchField(
                    query = state.query,
                    onQuery = viewModel::setQuery,
                    placeholder = "Номер, клиент или название",
                )
            }
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    statusFilters.forEach { (value, label) ->
                        FilterChip(
                            selected = state.status == value,
                            onClick = { viewModel.setStatus(value) },
                            label = { Text(label) },
                        )
                    }
                }
            }
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    kindFilters.forEach { kind ->
                        FilterChip(
                            selected = state.kind == kind,
                            onClick = { viewModel.setKind(kind) },
                            label = { Text(if (kind.isEmpty()) "Все виды" else DocumentKind.label(kind)) },
                        )
                    }
                }
            }
            item { ErrorBanner(state.error) }

            when {
                state.loading && state.documents.isEmpty() -> item { LoadingBox() }
                state.documents.isEmpty() -> item {
                    EmptyState(
                        title = "Документов нет",
                        subtitle = "Создайте первый кнопкой «Новый документ» — или счёт прямо из карточки заказа",
                    )
                }
                else -> items(state.documents, key = { it.id }) { doc ->
                    DocumentRow(doc) { onOpenDocument(doc.id) }
                }
            }
        }
    }

    if (showNewDialog) {
        NewDocumentDialog(
            onDismiss = { showNewDialog = false },
            onCreated = { id ->
                showNewDialog = false
                viewModel.refresh()
                onOpenDocument(id)
            },
        )
    }
}

@Composable
private fun DocumentRow(doc: DocumentDto, onClick: () -> Unit) {
    SoftCard(
        onClick = onClick,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val look = kindLook(doc.kind)
            Box(
                Modifier.size(34.dp).background(look.color.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(look.icon, contentDescription = null, tint = look.color, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (doc.number > 0) {
                        "${doc.kindTitle.ifBlank { DocumentKind.label(doc.kind) }} № ${doc.number}"
                    } else {
                        "${doc.kindTitle.ifBlank { DocumentKind.label(doc.kind) }} — черновик"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    listOfNotNull(
                        doc.docDate.takeIf { it.isNotBlank() },
                        doc.clientName.takeIf { it.isNotBlank() },
                        doc.orderTitle.takeIf { it.isNotBlank() && doc.clientName.isBlank() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatMoney(doc.totalKop),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    DocumentStatus.label(doc.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = documentStatusColor(doc.status),
                )
            }
        }
    }
}

private data class KindLook(val icon: ImageVector, val color: Color)

@Composable
private fun kindLook(kind: String): KindLook = when (kind) {
    DocumentKind.INVOICE -> KindLook(Icons.Default.Receipt, MaterialTheme.colorScheme.primary)
    DocumentKind.ACT -> KindLook(Icons.Default.CheckCircle, MaterialTheme.colorScheme.primary)
    DocumentKind.ESTIMATE -> KindLook(Icons.Default.Calculate, MaterialTheme.colorScheme.tertiary)
    DocumentKind.WAYBILL -> KindLook(Icons.Default.LocalShipping, MaterialTheme.colorScheme.tertiary)
    DocumentKind.UPD -> KindLook(Icons.Default.Description, MaterialTheme.colorScheme.secondary)
    DocumentKind.RECONCILIATION -> KindLook(Icons.Default.Balance, MaterialTheme.colorScheme.secondary)
    else -> KindLook(Icons.Default.Description, MaterialTheme.colorScheme.outline)
}

// ------------------------------ Новый документ ------------------------------

/**
 * Создание черновика. Счёт, акт, накладную и УПД собирает заказ; смета
 * и акт сверки живут от клиента — смета до сделки, сверка по взаиморасчётам.
 */
@Composable
private fun NewDocumentDialog(
    onDismiss: () -> Unit,
    onCreated: (Long) -> Unit,
    crm: CrmRepository = ServiceLocator.crm,
) {
    var kind by remember { mutableStateOf(DocumentKind.ESTIMATE) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }
    var orders by remember { mutableStateOf<List<OrderDto>>(emptyList()) }
    var pickingClients by remember { mutableStateOf(false) }
    var pickingOrders by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val fromOrder = kind in listOf(DocumentKind.INVOICE, DocumentKind.ACT, DocumentKind.WAYBILL, DocumentKind.UPD)

    LaunchedEffect(Unit) {
        crm.clients().onSuccess { clients = it }
        crm.orders().onSuccess { orders = it }
    }

    fun create(orderId: Long? = null, clientId: Long? = null) {
        busy = true
        error = null
        scope.launch {
            crm.createDocument(
                DocumentBody(kind = kind, orderId = orderId, clientId = clientId),
            ).fold(
                onSuccess = { card ->
                    busy = false
                    onCreated(card.id)
                },
                onFailure = { e ->
                    busy = false
                    error = e.message
                },
            )
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Новый документ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Вид документа", style = MaterialTheme.typography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DocumentKind.all.forEach { k ->
                        FilterChip(
                            selected = kind == k,
                            onClick = { kind = k },
                            label = { Text(DocumentKind.label(k)) },
                        )
                    }
                }

                Text(
                    if (fromOrder) {
                        "Документ собирается из заказа: состав, цены и клиент возьмутся из карточки."
                    } else {
                        if (kind == DocumentKind.RECONCILIATION) {
                            "Акт сверки строится по взаиморасчётам с клиентом за период."
                        } else {
                            "Смету удобно составлять до заказа; строки добавляются на следующем экране."
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                TextButton(
                    onClick = { if (fromOrder) pickingOrders = true else pickingClients = true },
                    enabled = !busy,
                ) {
                    Text(if (fromOrder) "Выбрать заказ" else "Выбрать клиента")
                }

                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Создаём черновик…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") }
        },
    )

    if (pickingOrders) {
        PickDialog(
            title = "Заказ для ${DocumentKind.label(kind).lowercase()}",
            items = orders.map { PickItem(it.id, "№ ${it.id} · ${it.title}", it.clientName) },
            onDismiss = { pickingOrders = false },
            onPick = { id ->
                pickingOrders = false
                create(orderId = id)
            },
        )
    }
    if (pickingClients) {
        PickDialog(
            title = "Клиент",
            items = clients.map { PickItem(it.id, it.name, it.phone) },
            onDismiss = { pickingClients = false },
            onPick = { id ->
                pickingClients = false
                create(clientId = id)
            },
        )
    }
}

/** Строка выбора: заголовок и серая подпись. */
private data class PickItem(val id: Long, val title: String, val subtitle: String)

@Composable
private fun PickDialog(
    title: String,
    items: List<PickItem>,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (items.isEmpty()) {
                Text("Список пуст", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(items, key = { it.id }) { item ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable { onPick(item.id) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(item.title, style = MaterialTheme.typography.bodyMedium)
                            if (item.subtitle.isNotBlank()) {
                                Text(
                                    item.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
