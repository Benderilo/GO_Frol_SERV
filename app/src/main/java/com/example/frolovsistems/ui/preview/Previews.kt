package com.example.frolovsistems.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.frolovsistems.core.prefs.ServerConfig
import com.example.frolovsistems.ui.components.EmptyState
import com.example.frolovsistems.ui.components.ErrorBanner
import com.example.frolovsistems.ui.components.SectionHeader
import com.example.frolovsistems.ui.components.ServerFields
import com.example.frolovsistems.ui.components.SoftCard
import com.example.frolovsistems.ui.components.StatTile
import com.example.frolovsistems.ui.components.StatusChip
import com.example.frolovsistems.ui.screens.LoginActions
import com.example.frolovsistems.ui.screens.LoginContent
import com.example.frolovsistems.ui.screens.LoginUiState
import com.example.frolovsistems.ui.theme.FrolovTheme
import com.example.frolovsistems.ui.theme.Success
import com.example.frolovsistems.ui.theme.ThemeMode
import com.example.frolovsistems.ui.theme.Warning

/*
 * Превью не попадают в APK — компилятор Compose вырезает их при релизной сборке,
 * а библиотека ui-tooling подключена только к debug.
 *
 * Как смотреть: откройте файл, справа сверху переключитесь на Split или Design.
 * Кнопка «молния» на карточке превью включает интерактивный режим (можно нажимать
 * и печатать), кнопка «play» — запуск этого превью на устройстве.
 */

// ---------------------------------------------------------------------------
// Экран входа целиком — в светлой и тёмной теме
// ---------------------------------------------------------------------------

@Preview(name = "Вход · светлая", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun LoginLightPreview() {
    FrolovTheme(themeMode = ThemeMode.LIGHT) {
        Surface(color = MaterialTheme.colorScheme.background) {
            LoginContent(
                state = LoginUiState(login = "admin", password = "secret"),
                actions = LoginActions(),
            )
        }
    }
}

@Preview(name = "Вход · тёмная", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun LoginDarkPreview() {
    FrolovTheme(themeMode = ThemeMode.DARK) {
        Surface(color = MaterialTheme.colorScheme.background) {
            LoginContent(
                state = LoginUiState(login = "admin", password = "secret"),
                actions = LoginActions(),
            )
        }
    }
}

@Preview(name = "Вход · настройки сервера открыты", showBackground = true, widthDp = 400, heightDp = 1100)
@Composable
private fun LoginWithServerFieldsPreview() {
    FrolovTheme(themeMode = ThemeMode.DARK) {
        Surface(color = MaterialTheme.colorScheme.background) {
            LoginContent(
                state = LoginUiState(
                    login = "admin",
                    showServerFields = true,
                    pingResult = "Сервер отвечает • версия 1.0",
                ),
            )
        }
    }
}

@Preview(name = "Вход · ошибка", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun LoginErrorPreview() {
    FrolovTheme(themeMode = ThemeMode.LIGHT) {
        Surface(color = MaterialTheme.colorScheme.background) {
            LoginContent(
                state = LoginUiState(
                    login = "admin",
                    password = "123",
                    error = "Неверный логин или пароль",
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Галерея компонентов — удобно править дизайн-систему, видя всё сразу
// ---------------------------------------------------------------------------

@Preview(name = "Компоненты · светлая", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun ComponentsLightPreview() {
    FrolovTheme(themeMode = ThemeMode.LIGHT) { ComponentGallery() }
}

@Preview(name = "Компоненты · тёмная", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun ComponentsDarkPreview() {
    FrolovTheme(themeMode = ThemeMode.DARK) { ComponentGallery() }
}

@Composable
private fun ComponentGallery() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(value = "128", label = "Клиентов", modifier = Modifier.weight(1f))
                StatTile(
                    value = "7",
                    label = "Новых заявок",
                    accent = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                )
            }

            SoftCard {
                SectionHeader("Карточка", "Обычное состояние")
                Text("Содержимое карточки", style = MaterialTheme.typography.bodyMedium)
            }

            SoftCard(onClick = {}, highlighted = true) {
                SectionHeader("Карточка", "Подсвеченная и нажимаемая")
                Text("Нажмите в интерактивном режиме", style = MaterialTheme.typography.bodyMedium)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip("Новая", MaterialTheme.colorScheme.primary)
                StatusChip("В работе", Warning)
                StatusChip("Готово", Success)
            }

            ErrorBanner("Сервер недоступен: нет соединения")

            SoftCard {
                SectionHeader("Подключение", "Поля из экрана настроек")
                ServerFields(config = ServerConfig(), onChange = {})
            }

            EmptyState(
                title = "Заявок нет",
                subtitle = "Обращения с формы на сайте появятся здесь",
            )
        }
    }
}
