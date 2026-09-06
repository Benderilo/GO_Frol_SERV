package com.example.frolovsistems.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frolovsistems.core.prefs.ServerConfig
import com.example.frolovsistems.data.CrmRepository
import com.example.frolovsistems.data.SessionRepository
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.components.DialogField
import com.example.frolovsistems.ui.components.ErrorBanner
import com.example.frolovsistems.ui.components.SectionHeader
import com.example.frolovsistems.ui.components.ServerFields
import com.example.frolovsistems.ui.components.SoftCard
import com.example.frolovsistems.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val server: ServerConfig = ServerConfig(),
    val savedServer: ServerConfig = ServerConfig(),
    val login: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val checking: Boolean = false,
    val pingResult: String? = null,
    val error: String? = null,
    /** Идёт удаление записей (завершённых или всей базы). */
    val dataBusy: Boolean = false,
    /** Итог операции над базой — показываем зелёной строкой. */
    val dataMessage: String? = null,
    val currentPassword: String = "",
    val newPassword: String = "",
    val passwordSaved: Boolean = false,
) {
    val serverDirty: Boolean get() = server != savedServer
}

class SettingsViewModel(
    private val session: SessionRepository = ServiceLocator.session,
    private val crm: CrmRepository = ServiceLocator.crm,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            session.preferences.collect { prefs ->
                _state.update { current ->
                    current.copy(
                        // Правки пользователя не затираем: подтягиваем только сохранённое.
                        server = if (current.serverDirty) current.server else prefs.server,
                        savedServer = prefs.server,
                        login = prefs.login,
                        themeMode = prefs.themeMode,
                        dynamicColor = prefs.dynamicColor,
                    )
                }
            }
        }
    }

    fun onServer(config: ServerConfig) = _state.update { it.copy(server = config, pingResult = null) }

    fun saveServer() {
        viewModelScope.launch {
            session.saveServer(_state.value.server)
            _state.update { it.copy(savedServer = it.server, error = null) }
        }
    }

    fun checkConnection() {
        viewModelScope.launch {
            _state.update { it.copy(checking = true, pingResult = null, error = null) }
            session.ping(_state.value.server)
                .onSuccess { health ->
                    _state.update {
                        it.copy(checking = false, pingResult = "Сервер отвечает • версия ${health.version}")
                    }
                }
                .onFailure { e -> _state.update { it.copy(checking = false, error = e.message) } }
        }
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { session.saveTheme(mode) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { session.saveDynamicColor(enabled) }

    fun onCurrentPassword(value: String) =
        _state.update { it.copy(currentPassword = value, passwordSaved = false) }

    fun onNewPassword(value: String) =
        _state.update { it.copy(newPassword = value, passwordSaved = false) }

    fun changePassword() {
        val current = _state.value
        if (current.newPassword.length < 6) {
            _state.update { it.copy(error = "Новый пароль должен быть не короче 6 символов") }
            return
        }
        viewModelScope.launch {
            session.changePassword(current.currentPassword, current.newPassword)
                .onSuccess {
                    _state.update {
                        it.copy(currentPassword = "", newPassword = "", passwordSaved = true, error = null)
                    }
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun logout() = viewModelScope.launch { session.logout() }

    fun dismissDataMessage() = _state.update { it.copy(dataMessage = null) }

    private fun dataFail(e: Throwable?) {
        _state.update { it.copy(dataBusy = false, error = e?.message) }
    }

    /**
     * Удаляет все завершённые заказы. Платежи, состав и фото заказа
     * сервер стирает каскадом вместе с ним — по одному вызову на заказ.
     */
    fun deleteDoneOrders() {
        viewModelScope.launch {
            _state.update { it.copy(dataBusy = true, dataMessage = null, error = null) }
            val done = crm.orders("done").getOrElse { e -> dataFail(e); return@launch }
            for (order in done) {
                val result = crm.deleteOrder(order.id)
                if (result.isFailure) {
                    dataFail(result.exceptionOrNull())
                    return@launch
                }
            }
            _state.update {
                it.copy(
                    dataBusy = false,
                    dataMessage = if (done.isEmpty()) {
                        "Завершённых заказов нет — нечего удалять"
                    } else {
                        "Удалено завершённых заказов: ${done.size}"
                    },
                )
            }
        }
    }

    /**
     * Полная очистка базы: задачи, заказы (каскадно платежи, состав, фото),
     * заявки, касса, склад и клиенты. Учётки и настройки сервера не трогаем.
     */
    fun wipeDatabase() {
        viewModelScope.launch {
            _state.update { it.copy(dataBusy = true, dataMessage = null, error = null) }

            // Порядок важен: сначала то, что ссылается на заказы и клиентов.
            val tasks = crm.tasks().getOrElse { e -> dataFail(e); return@launch }
            for (task in tasks) {
                val r = crm.deleteTask(task.id)
                if (r.isFailure) { dataFail(r.exceptionOrNull()); return@launch }
            }

            val orders = crm.orders().getOrElse { e -> dataFail(e); return@launch }
            for (order in orders) {
                val r = crm.deleteOrder(order.id)
                if (r.isFailure) { dataFail(r.exceptionOrNull()); return@launch }
            }

            val requests = crm.requests().getOrElse { e -> dataFail(e); return@launch }
            for (request in requests) {
                val r = crm.deleteRequest(request.id)
                if (r.isFailure) { dataFail(r.exceptionOrNull()); return@launch }
            }

            val cash = crm.cash().getOrElse { e -> dataFail(e); return@launch }
            for (op in cash.items) {
                val r = crm.deleteCashOp(op.id)
                if (r.isFailure) { dataFail(r.exceptionOrNull()); return@launch }
            }

            val catalog = crm.catalog(withArchived = true).getOrElse { e -> dataFail(e); return@launch }
            for (item in catalog) {
                val r = crm.deleteCatalogItem(item.id)
                if (r.isFailure) { dataFail(r.exceptionOrNull()); return@launch }
            }

            val clients = crm.clients().getOrElse { e -> dataFail(e); return@launch }
            for (client in clients) {
                val r = crm.deleteClient(client.id)
                if (r.isFailure) { dataFail(r.exceptionOrNull()); return@launch }
            }

            _state.update {
                it.copy(
                    dataBusy = false,
                    dataMessage = "База очищена: заказов ${orders.size}, клиентов ${clients.size}, " +
                        "заявок ${requests.size}, задач ${tasks.size}",
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onOpenTransfer: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Этап деструктивной операции: done → подтверждение, wipe1/2 → двойное.
    var cleanupStep by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
                Spacer(Modifier.size(4.dp))
                Text("Настройки", style = MaterialTheme.typography.headlineMedium)
            }
        }

        item { ErrorBanner(state.error) }

        item {
            SoftCard {
                SectionHeader("Подключение", "Адрес сервера, куда ходит приложение")
                Spacer(Modifier.height(14.dp))
                ServerFields(
                    config = state.server,
                    onChange = viewModel::onServer,
                    enabled = !state.checking,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = viewModel::saveServer,
                        enabled = state.serverDirty,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    ) { Text("Сохранить") }
                    OutlinedButton(
                        onClick = viewModel::checkConnection,
                        enabled = !state.checking,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (state.checking) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Проверить")
                        }
                    }
                }
                AnimatedVisibility(
                    visible = state.pingResult != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(36.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            state.pingResult.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }

        item {
            SoftCard {
                SectionHeader("Оформление приложения", "Тема интерфейса панели управления")
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChip("Система", Icons.Default.PhoneAndroid, ThemeMode.SYSTEM, state.themeMode, viewModel::setTheme)
                    ThemeChip("Светлая", Icons.Default.LightMode, ThemeMode.LIGHT, state.themeMode, viewModel::setTheme)
                    ThemeChip("Тёмная", Icons.Default.DarkMode, ThemeMode.DARK, state.themeMode, viewModel::setTheme)
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Цвета из обоев", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Material You вместо фирменной палитры",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.dynamicColor, onCheckedChange = viewModel::setDynamicColor)
                }
            }
        }

        item {
            SoftCard {
                SectionHeader("Учётная запись", state.login.ifBlank { "—" })
                Spacer(Modifier.height(14.dp))
                DialogField("Текущий пароль", state.currentPassword) { viewModel.onCurrentPassword(it) }
                DialogField("Новый пароль", state.newPassword) { viewModel.onNewPassword(it) }
                Button(
                    onClick = viewModel::changePassword,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Сменить пароль") }

                AnimatedVisibility(
                    visible = state.passwordSaved,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Text(
                        "Пароль обновлён",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }

        item {
            SoftCard {
                SectionHeader("База данных", "Перенос и очистка хранилища")
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onOpenTransfer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    ) { Text("Выгрузка Excel") }
                    OutlinedButton(
                        onClick = onOpenTransfer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    ) { Text("Загрузка Excel") }
                }

                if (state.dataMessage != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            state.dataMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::dismissDataMessage) { Text("Скрыть") }
                    }
                }

                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = { cleanupStep = "done" },
                    enabled = !state.dataBusy,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Удалить выполненные заказы")
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { cleanupStep = "wipe1" },
                    enabled = !state.dataBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.dataBusy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text("Очищаем…")
                    } else {
                        Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Очистить базу полностью")
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = viewModel::logout,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Выйти из аккаунта")
            }
        }

        item {
            Text(
                "Фролов CRM • панель управления сайтом",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    val errorButtonColors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
    )

    when (cleanupStep) {
        "done" -> AlertDialog(
            onDismissRequest = { cleanupStep = null },
            title = { Text("Удалить выполненные заказы?") },
            text = {
                Text(
                    "Будут удалены все заказы со статусом «Завершён» вместе с их платежами, " +
                        "составом и фотографиями. Отменить это действие нельзя.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        cleanupStep = null
                        viewModel.deleteDoneOrders()
                    },
                    colors = errorButtonColors,
                    shape = MaterialTheme.shapes.small,
                ) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { cleanupStep = null }) { Text("Отмена") } },
        )

        // Полная очистка спрашивается дважды: первый диалог — суть, второй — точка невозврата.
        "wipe1" -> AlertDialog(
            onDismissRequest = { cleanupStep = null },
            title = { Text("Очистить базу полностью?") },
            text = {
                Text(
                    "Будут удалены ВСЕ данные: клиенты, заказы, заявки с сайта, задачи, " +
                        "касса и склад. Перед очисткой сделайте выгрузку Excel — " +
                        "это единственный способ сохранить данные.",
                )
            },
            confirmButton = {
                Button(
                    onClick = { cleanupStep = "wipe2" },
                    colors = errorButtonColors,
                    shape = MaterialTheme.shapes.small,
                ) { Text("Продолжить") }
            },
            dismissButton = { TextButton(onClick = { cleanupStep = null }) { Text("Отмена") } },
        )

        "wipe2" -> AlertDialog(
            onDismissRequest = { cleanupStep = null },
            title = { Text("Последнее предупреждение") },
            text = {
                Text(
                    "База будет очищена безвозвратно: восстановить её не сможет ни приложение, " +
                        "ни сервер. Вы действительно хотите удалить всё?",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        cleanupStep = null
                        viewModel.wipeDatabase()
                    },
                    colors = errorButtonColors,
                    shape = MaterialTheme.shapes.small,
                ) { Text("Да, удалить всё") }
            },
            dismissButton = { TextButton(onClick = { cleanupStep = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ThemeChip(
    label: String,
    icon: ImageVector,
    mode: ThemeMode,
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    FilterChip(
        selected = current == mode,
        onClick = { onSelect(mode) },
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
    )
}
