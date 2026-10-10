package com.example.frolovsistems.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.theme.Warning

/**
 * Полоска под шапкой о работе без сети: «нет сети — данные из кеша»
 * или «отправляем отложенные правки». Появляется сама при первом
 * обрыве и уходит, когда всё отправлено.
 */
@Composable
fun OfflineBanner() {
    val status by ServiceLocator.offline.status.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = status.offline || status.pending > 0,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (status.offline) Icons.Default.CloudOff else Icons.Default.CloudSync,
                    contentDescription = null,
                    tint = if (status.offline) Warning else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = when {
                        status.offline && status.pending > 0 ->
                            "Нет сети — данные из кеша; правок ждёт отправки: ${status.pending}"
                        status.offline ->
                            "Нет сети — показываете сохранённые данные"
                        status.flushing ->
                            "Отправляем отложенные правки: ${status.pending}"
                        else ->
                            "Правок ждёт отправки: ${status.pending}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
