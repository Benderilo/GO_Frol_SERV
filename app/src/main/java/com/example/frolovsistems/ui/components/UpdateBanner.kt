package com.example.frolovsistems.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.frolovsistems.core.update.AppUpdater
import com.example.frolovsistems.core.update.UpdateState
import kotlinx.coroutines.launch

/**
 * Полоска под шапкой: «Доступна версия 2.2 — Обновить». Скачивание идёт
 * прямо в ней, с полосой хода; по готовности открывается установщик.
 */
@Composable
fun UpdateBanner() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by AppUpdater.state.collectAsStateWithLifecycle()

    // Последнее непустое состояние держим, чтобы полоска уезжала с текстом, а не пустой.
    var lastShown by remember { mutableStateOf<UpdateState>(UpdateState.None) }
    if (state != UpdateState.None) lastShown = state

    AnimatedVisibility(
        visible = state != UpdateState.None,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        val current = lastShown
        Surface(color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = when (current) {
                                is UpdateState.Available -> "Есть обновление: версия ${current.release.versionName}"
                                is UpdateState.Downloading ->
                                    "Скачиваем обновление… ${(current.progress * 100).toInt()}%"
                                is UpdateState.Ready -> "Обновление скачано"
                                is UpdateState.Failed -> current.message
                                UpdateState.None -> ""
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val notes = (current as? UpdateState.Available)?.release?.notes.orEmpty()
                        if (notes.isNotBlank()) {
                            Text(
                                notes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    when (current) {
                        is UpdateState.Available, is UpdateState.Failed -> TextButton(
                            onClick = { scope.launch { AppUpdater.download(context) } },
                        ) { Text(if (current is UpdateState.Failed) "Повторить" else "Обновить") }
                        is UpdateState.Ready -> TextButton(onClick = { AppUpdater.install(context) }) {
                            Text("Установить")
                        }
                        else -> Unit
                    }
                    if (current !is UpdateState.Downloading) {
                        IconButton(onClick = AppUpdater::dismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Позже")
                        }
                    }
                }
                if (current is UpdateState.Downloading) {
                    LinearProgressIndicator(
                        progress = { current.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
