package com.example.frolovsistems.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.frolovsistems.core.diagnostics.DiagEntry
import com.example.frolovsistems.core.diagnostics.Diagnostics
import com.example.frolovsistems.ui.components.EmptyState
import com.example.frolovsistems.ui.components.SoftCard
import com.example.frolovsistems.ui.theme.Warning
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Журнал ошибок и крашей: сетевые сбои с полным текстом («JSON token at
 * offset…» целиком, а не обрезком), непойманные исключения с трассировкой.
 * Нажатие на запись раскрывает её полностью; журнал можно переслать текстом.
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(Diagnostics.snapshot()) }
    var opened by remember { mutableStateOf<DiagEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Column(Modifier.weight(1f)) {
                Text("Диагностика", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Ошибки, краши и их полные тексты",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = {
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, Diagnostics.shareText())
                        },
                        "Журнал ошибок",
                    ),
                )
            }) {
                Icon(Icons.Default.Share, contentDescription = "Поделиться журналом")
            }
            IconButton(onClick = { confirmClear = true }) {
                Icon(Icons.Default.Delete, contentDescription = "Очистить журнал", tint = MaterialTheme.colorScheme.error)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (entries.isEmpty()) {
                item {
                    EmptyState(
                        title = "Ошибок нет",
                        subtitle = "Сетевые сбои и краши появятся здесь с полным текстом",
                    )
                }
            }

            items(entries, key = { "${it.time}-${it.tag}-${it.message.hashCode()}" }) { entry ->
                DiagRow(entry) { opened = entry }
            }
        }
    }

    opened?.let { entry ->
        AlertDialog(
            onDismissRequest = { opened = null },
            title = { Text("${entry.tag} · ${formatTime(entry.time, withDate = true)}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        entry.message,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (entry.stack.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Трассировка",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            entry.stack,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { opened = null }) { Text("Закрыть") } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить журнал?") },
            text = { Text("Записи удалятся с телефона безвозвратно.") },
            confirmButton = {
                Button(onClick = {
                    Diagnostics.clear()
                    entries = Diagnostics.snapshot()
                    confirmClear = false
                }) { Text("Очистить") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun DiagRow(entry: DiagEntry, onClick: () -> Unit) {
    SoftCard(onClick = onClick, contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(10.dp).background(levelColor(entry.level), CircleShape),
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.tag,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    entry.message.lineSequence().firstOrNull().orEmpty().take(120),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Text(
                formatTime(entry.time, withDate = false),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun levelColor(level: String): Color = when (level) {
    "error" -> MaterialTheme.colorScheme.error
    "warn" -> Warning
    else -> MaterialTheme.colorScheme.primary
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
private val dateTimeFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")

private fun formatTime(epochMilli: Long, withDate: Boolean): String {
    val time = Instant.ofEpochMilli(epochMilli).atZone(ZoneId.systemDefault())
    return if (withDate) {
        dateTimeFormat.format(time)
    } else {
        timeFormat.format(time)
    }
}
