package com.example.frolovsistems.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.frolovsistems.core.prefs.ServerConfig

/**
 * Поля подключения к серверу: протокол, хост, порт, таймаут.
 * Используются и на экране входа, и в настройках.
 */
@Composable
fun ServerFields(
    config: ServerConfig,
    onChange: (ServerConfig) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("http", "https").forEach { scheme ->
                FilterChip(
                    selected = config.scheme == scheme,
                    onClick = {
                        // Порт подставляем стандартный, если он ещё не менялся руками.
                        val port = when {
                            scheme == "https" && config.port == 80 -> 443
                            scheme == "http" && config.port == 443 -> 80
                            else -> config.port
                        }
                        onChange(config.copy(scheme = scheme, port = port))
                    },
                    label = { Text(scheme) },
                    enabled = enabled,
                )
            }
        }

        OutlinedTextField(
            value = config.host,
            onValueChange = { onChange(config.copy(host = it.trim())) },
            label = { Text("Адрес сервера") },
            placeholder = { Text("195.19.195.169") },
            singleLine = true,
            enabled = enabled,
            shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = if (config.port == 0) "" else config.port.toString(),
                onValueChange = { text ->
                    val port = text.filter { it.isDigit() }.take(5).toIntOrNull() ?: 0
                    onChange(config.copy(port = port))
                },
                label = { Text("Порт") },
                singleLine = true,
                enabled = enabled,
                shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(120.dp),
            )
            OutlinedTextField(
                value = config.timeoutSec.toString(),
                onValueChange = { text ->
                    val value = text.filter { it.isDigit() }.take(3).toIntOrNull() ?: 0
                    onChange(config.copy(timeoutSec = value))
                },
                label = { Text("Таймаут, с") },
                singleLine = true,
                enabled = enabled,
                shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(140.dp),
            )
        }

        Text(
            text = config.baseUrl,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
