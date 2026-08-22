package com.example.frolovsistems.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frolovsistems.core.net.ClientDto
import com.example.frolovsistems.core.net.OrderDto
import com.example.frolovsistems.data.CrmRepository
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.components.EmptyState
import com.example.frolovsistems.ui.components.ErrorBanner
import com.example.frolovsistems.ui.components.LoadingBox
import com.example.frolovsistems.ui.components.SoftCard
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ClientsUiState(
    val loading: Boolean = true,
    val query: String = "",
    val items: List<ClientDto> = emptyList(),
    val editing: ClientDto? = null,
    /** Код кабинета сервер отдаёт один раз — держим его до закрытия карточки. */
    val accessCode: String? = null,
    val accessBusy: Boolean = false,
    val clientOrders: List<OrderDto> = emptyList(),
    val error: String? = null,
)

@OptIn(FlowPreview::class)
class ClientsViewModel(
    private val crm: CrmRepository = ServiceLocator.crm,
) : ViewModel() {

    private val _state = MutableStateFlow(ClientsUiState())
    val state: StateFlow<ClientsUiState> = _state.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    init {
        refresh()
        viewModelScope.launch {
            // Поиск не дёргает сервер на каждую букву.
            queryFlow.debounce(350).distinctUntilChanged().collect { refresh() }
        }
    }

    fun onQuery(value: String) {
        _state.update { it.copy(query = value) }
        queryFlow.value = value
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            crm.clients(_state.value.query)
                .onSuccess { list -> _state.update { it.copy(loading = false, items = list) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun startCreate() =
        _state.update { it.copy(editing = ClientDto(), accessCode = null, clientOrders = emptyList()) }

    fun startEdit(client: ClientDto) {
        _state.update { it.copy(editing = client, accessCode = null, clientOrders = emptyList()) }
        if (client.id != 0L) loadClientOrders(client.id)
    }

    fun updateDraft(client: ClientDto) = _state.update { it.copy(editing = client) }

    fun cancelEdit() =
        _state.update { it.copy(editing = null, accessCode = null, clientOrders = emptyList()) }

    private fun loadClientOrders(clientId: Long) {
        viewModelScope.launch {
            crm.clientOrders(clientId)
                .onSuccess { list -> _state.update { it.copy(clientOrders = list) } }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    /** Выдаёт клиенту код входа в кабинет на сайте. */
    fun grantAccess(clientId: Long) {
        viewModelScope.launch {
            _state.update { it.copy(accessBusy = true, error = null) }
            crm.grantAccess(clientId)
                .onSuccess { granted ->
                    _state.update { st ->
                        st.copy(
                            accessBusy = false,
                            accessCode = granted.code,
                            editing = st.editing?.copy(portalEnabled = true),
                        )
                    }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(accessBusy = false, error = e.message) } }
        }
    }

    fun revokeAccess(clientId: Long) {
        viewModelScope.launch {
            _state.update { it.copy(accessBusy = true, error = null) }
            crm.revokeAccess(clientId)
                .onSuccess {
                    _state.update { st ->
                        st.copy(
                            accessBusy = false,
                            accessCode = null,
                            editing = st.editing?.copy(portalEnabled = false),
                        )
                    }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(accessBusy = false, error = e.message) } }
        }
    }

    fun saveDraft() {
        val draft = _state.value.editing ?: return
        if (draft.name.isBlank()) {
            _state.update { it.copy(error = "Укажите имя клиента") }
            return
        }
        viewModelScope.launch {
            val result = if (draft.id == 0L) crm.createClient(draft) else crm.updateClient(draft.id, draft)
            result
                .onSuccess {
                    _state.update { it.copy(editing = null) }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun delete(client: ClientDto) {
        viewModelScope.launch {
            crm.deleteClient(client.id)
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }
}

@Composable
fun ClientsScreen(viewModel: ClientsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ClientDto?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Клиенты", style = MaterialTheme.typography.headlineMedium)
            }
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQuery,
                    placeholder = { Text("Поиск по имени, телефону, почте") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { ErrorBanner(state.error) }

            when {
                state.loading && state.items.isEmpty() -> item { LoadingBox() }
                state.items.isEmpty() -> item {
                    EmptyState(
                        title = if (state.query.isBlank()) "Клиентов пока нет" else "Ничего не найдено",
                        subtitle = "Добавьте первого клиента кнопкой внизу справа",
                    )
                }
                else -> items(state.items, key = { it.id }) { client ->
                    SoftCard(onClick = { viewModel.startEdit(client) }) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(client.name, style = MaterialTheme.typography.titleMedium)
                                if (client.phone.isNotBlank()) {
                                    Text(
                                        client.phone,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (client.email.isNotBlank()) {
                                    Text(
                                        client.email,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            androidx.compose.material3.IconButton(onClick = { pendingDelete = client }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Удалить",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        if (client.note.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(client.note, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = state.editing == null,
            enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.8f),
            exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) {
            FloatingActionButton(onClick = viewModel::startCreate) {
                Icon(Icons.Default.Add, contentDescription = "Добавить клиента")
            }
        }
    }

    state.editing?.let { draft ->
        ClientEditorDialog(
            draft = draft,
            accessCode = state.accessCode,
            accessBusy = state.accessBusy,
            orders = state.clientOrders,
            onChange = viewModel::updateDraft,
            onDismiss = viewModel::cancelEdit,
            onSave = viewModel::saveDraft,
            onGrant = { viewModel.grantAccess(draft.id) },
            onRevoke = { viewModel.revokeAccess(draft.id) },
        )
    }

    pendingDelete?.let { client ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить клиента?") },
            text = { Text("«${client.name}» будет удалён безвозвратно.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.delete(client)
                    pendingDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ClientEditorDialog(
    draft: ClientDto,
    accessCode: String?,
    accessBusy: Boolean,
    orders: List<OrderDto>,
    onChange: (ClientDto) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onGrant: () -> Unit,
    onRevoke: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == 0L) "Новый клиент" else "Карточка клиента") },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding()
            ) {
                DialogField("Имя", draft.name) { onChange(draft.copy(name = it)) }
                DialogField("Телефон", draft.phone) { onChange(draft.copy(phone = it)) }
                DialogField("E-mail", draft.email) { onChange(draft.copy(email = it)) }
                DialogField("Адрес", draft.address) { onChange(draft.copy(address = it)) }
                DialogField("Метка", draft.tag) { onChange(draft.copy(tag = it)) }
                DialogField("Заметка", draft.note, lines = 3) { onChange(draft.copy(note = it)) }

                if (draft.id != 0L) {
                    Spacer(Modifier.height(8.dp))
                    PortalAccessSection(
                        client = draft,
                        code = accessCode,
                        busy = accessBusy,
                        onGrant = onGrant,
                        onRevoke = onRevoke,
                    )
                    ClientOrdersSection(orders)
                }
            }
        },
        confirmButton = { Button(onClick = onSave) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
internal fun DialogField(
    label: String,
    value: String,
    lines: Int = 1,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = lines == 1,
        minLines = lines,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}

/**
 * Доступ клиента в кабинет на сайте. Код виден только сразу после выдачи:
 * на сервере остаётся лишь его хеш, показать повторно нечего.
 */
@Composable
private fun PortalAccessSection(
    client: ClientDto,
    code: String?,
    busy: Boolean,
    onGrant: () -> Unit,
    onRevoke: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current

    Text("Кабинет на сайте", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(6.dp))

    if (client.phone.isBlank()) {
        Text(
            "Укажите телефон: по нему клиент входит в кабинет.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }

    Text(
        if (client.portalEnabled) "Доступ открыт" else "Доступ закрыт",
        style = MaterialTheme.typography.bodySmall,
        color = if (client.portalEnabled) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )

    AnimatedVisibility(
        visible = code != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Column(Modifier.padding(top = 8.dp)) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        code.orEmpty(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    IconButton(onClick = { clipboard.setText(AnnotatedString(code.orEmpty())) }) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Скопировать код",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Передайте код клиенту: он вводит его вместе с телефоном на странице /cabinet. " +
                    "Повторно код не показать — при утере выдайте новый.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onGrant,
            enabled = !busy,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.weight(1f),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(if (client.portalEnabled) "Новый код" else "Выдать код")
            }
        }
        if (client.portalEnabled) {
            TextButton(onClick = onRevoke, enabled = !busy) {
                Text("Закрыть", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Заказы клиента прямо в его карточке — не нужно искать их в списке заказов. */
@Composable
private fun ClientOrdersSection(orders: List<OrderDto>) {
    Spacer(Modifier.height(14.dp))
    Text("Заказы клиента", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(6.dp))

    if (orders.isEmpty()) {
        Text(
            "Заказов ещё нет.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    orders.forEach { order ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(order.title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    orderStatusLabel(order.status) +
                        if (order.photoCount > 0) " • ${photoCountLabel(order.photoCount)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(formatMoney(order.price), style = MaterialTheme.typography.titleSmall)
        }
    }
}
