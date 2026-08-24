package com.example.frolovsistems.core.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.frolovsistems.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "frolov_settings")

/** Как приложение достучится до сервера. Всё это меняется в настройках. */
data class ServerConfig(
    val scheme: String = "https",
    val host: String = "v3002851.hosted-by-vdsina.ru",
    val port: Int = 443,
    val timeoutSec: Int = 20,
) {
    /** Базовый адрес без завершающего слэша, например http://195.19.195.169 */
    val baseUrl: String
        get() {
            val defaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
            return if (defaultPort) "$scheme://$host" else "$scheme://$host:$port"
        }

    val isValid: Boolean get() = host.isNotBlank() && port in 1..65535

    /**
     * Приводит к порядку то, что человек ввёл в поле адреса.
     * «http://195.19.195.169:8080/api/» → схема http, хост 195.19.195.169, порт 8080.
     * Без этого введённая вместе со схемой строка склеилась бы в http://http://…
     */
    fun withHostInput(raw: String): ServerConfig {
        var text = raw.trim()
        var newScheme = scheme
        var newPort = port

        SCHEME_PREFIX.find(text)?.let { match ->
            newScheme = match.groupValues[1].lowercase()
            newPort = if (newScheme == "https") 443 else 80
            text = text.removeRange(match.range)
        }

        // Отбрасываем путь, параметры и якорь — нужен только хост.
        text = text.substringBefore('/').substringBefore('?').substringBefore('#')

        // Порт в хвосте берём только у обычных адресов: у IPv6 двоеточий много.
        if (text.count { it == ':' } == 1) {
            val (hostPart, portPart) = text.split(':', limit = 2)
            portPart.toIntOrNull()?.takeIf { it in 1..65535 }?.let {
                newPort = it
                text = hostPart
            }
        }

        return copy(scheme = newScheme, host = text.trim(), port = newPort)
    }

    private companion object {
        val SCHEME_PREFIX = Regex("^(https?)://", RegexOption.IGNORE_CASE)
    }
}

data class AppPreferences(
    val server: ServerConfig = ServerConfig(),
    val token: String = "",
    val login: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
) {
    val isAuthorized: Boolean get() = token.isNotBlank()
}

/** Хранилище настроек поверх DataStore. */
class AppSettings(private val context: Context) {

    private object Keys {
        val SCHEME = stringPreferencesKey("scheme")
        val HOST = stringPreferencesKey("host")
        val PORT = intPreferencesKey("port")
        val TIMEOUT = intPreferencesKey("timeout")
        val TOKEN = stringPreferencesKey("token")
        val LOGIN = stringPreferencesKey("login")
        val THEME = stringPreferencesKey("theme")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
    }

    val preferences: Flow<AppPreferences> = context.dataStore.data.map { p ->
        val defaults = ServerConfig()
        AppPreferences(
            server = ServerConfig(
                scheme = p[Keys.SCHEME] ?: defaults.scheme,
                host = p[Keys.HOST] ?: defaults.host,
                port = p[Keys.PORT] ?: defaults.port,
                timeoutSec = p[Keys.TIMEOUT] ?: defaults.timeoutSec,
            ),
            token = p[Keys.TOKEN].orEmpty(),
            login = p[Keys.LOGIN].orEmpty(),
            themeMode = runCatching { ThemeMode.valueOf(p[Keys.THEME] ?: ThemeMode.SYSTEM.name) }
                .getOrDefault(ThemeMode.SYSTEM),
            dynamicColor = p[Keys.DYNAMIC] ?: false,
        )
    }

    suspend fun saveServer(config: ServerConfig) {
        context.dataStore.edit { p ->
            p[Keys.SCHEME] = config.scheme
            p[Keys.HOST] = config.host.trim()
            p[Keys.PORT] = config.port
            p[Keys.TIMEOUT] = config.timeoutSec
        }
    }

    suspend fun saveSession(token: String, login: String) {
        context.dataStore.edit { p ->
            p[Keys.TOKEN] = token
            p[Keys.LOGIN] = login
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit { p ->
            p.remove(Keys.TOKEN)
        }
    }

    suspend fun saveTheme(mode: ThemeMode) {
        context.dataStore.edit { p -> p[Keys.THEME] = mode.name }
    }

    suspend fun saveDynamicColor(enabled: Boolean) {
        context.dataStore.edit { p -> p[Keys.DYNAMIC] = enabled }
    }
}
