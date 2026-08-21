package com.example.frolovsistems.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.frolovsistems.core.prefs.ServerConfig
import com.example.frolovsistems.data.SessionRepository
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.components.ErrorBanner
import com.example.frolovsistems.ui.components.ServerFields
import com.example.frolovsistems.ui.components.SoftCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val login: String = "admin",
    val password: String = "",
    val server: ServerConfig = ServerConfig(),
    val serverLoaded: Boolean = false,
    val showServerFields: Boolean = false,
    val loading: Boolean = false,
    val checking: Boolean = false,
    val error: String? = null,
    val pingResult: String? = null,
)

class LoginViewModel(
    private val session: SessionRepository = ServiceLocator.session,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val prefs = session.preferences.first()
            _state.update {
                it.copy(
                    server = prefs.server,
                    login = prefs.login.ifBlank { it.login },
                    serverLoaded = true,
                )
            }
        }
    }

    fun onLogin(value: String) = _state.update { it.copy(login = value, error = null) }
    fun onPassword(value: String) = _state.update { it.copy(password = value, error = null) }
    fun onServer(config: ServerConfig) = _state.update { it.copy(server = config, pingResult = null) }
    fun toggleServerFields() = _state.update { it.copy(showServerFields = !it.showServerFields) }

    fun checkConnection() {
        val config = _state.value.server
        viewModelScope.launch {
            _state.update { it.copy(checking = true, pingResult = null, error = null) }
            session.saveServer(config)
            session.ping(config)
                .onSuccess { health ->
                    _state.update {
                        it.copy(checking = false, pingResult = "Сервер отвечает • версия ${health.version}")
                    }
                }
                .onFailure { e -> _state.update { it.copy(checking = false, error = e.message) } }
        }
    }

    fun submit() {
        val current = _state.value
        if (current.login.isBlank() || current.password.isBlank()) {
            _state.update { it.copy(error = "Введите логин и пароль") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            session.saveServer(current.server)
            session.login(current.login.trim(), current.password)
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
            // При успехе состояние авторизации меняется в настройках, экран сменится сам.
        }
    }
}

@Composable
fun LoginScreen(viewModel: LoginViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        AnimatedGlow()

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 22.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(24.dp))
            BrandMark()
            Spacer(Modifier.height(18.dp))

            Text("Фролов Системы", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Панель управления сайтом и CRM",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))

            SoftCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
                OutlinedTextField(
                    value = state.login,
                    onValueChange = viewModel::onLogin,
                    label = { Text("Логин") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::onPassword,
                    label = { Text("Пароль") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    visualTransformation = if (passwordVisible) {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Скрыть пароль" else "Показать пароль",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = viewModel::submit,
                    enabled = !state.loading,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (state.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("Войти", style = MaterialTheme.typography.labelLarge)
                    }
                }

                Spacer(Modifier.height(6.dp))

                TextButton(
                    onClick = viewModel::toggleServerFields,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(if (state.showServerFields) "Скрыть подключение" else "Настройки подключения")
                }

                AnimatedVisibility(
                    visible = state.showServerFields,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column {
                        Spacer(Modifier.height(4.dp))
                        ServerFields(
                            config = state.server,
                            onChange = viewModel::onServer,
                            enabled = !state.loading && !state.checking,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = viewModel::checkConnection,
                            enabled = !state.checking,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (state.checking) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Проверить соединение")
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = state.pingResult != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Row(
                        Modifier.padding(top = 12.dp),
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

                Spacer(Modifier.height(10.dp))
                ErrorBanner(state.error)
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Первый вход: логин и пароль задаются на сервере в /etc/frolov-crm.env",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Фирменный знак с лёгким «дыханием» — тот же жёлто-синий градиент, что и на сайте. */
@Composable
private fun BrandMark() {
    val transition = rememberInfiniteTransition(label = "brand")
    val scale by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(2600), RepeatMode.Reverse),
        label = "brandScale",
    )

    Box(
        Modifier
            .size(78.dp)
            .scale(scale)
            .background(
                Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary),
                ),
                MaterialTheme.shapes.large,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Bolt,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(40.dp),
        )
    }
}

/** Мягкое цветное свечение на фоне экрана входа. */
@Composable
private fun AnimatedGlow() {
    val transition = rememberInfiniteTransition(label = "glow")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000), RepeatMode.Reverse),
        label = "glowShift",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f),
                        Color.Transparent,
                    ),
                    radius = 900f + shift * 400f,
                )
            )
    )
}
