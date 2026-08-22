package com.example.frolovsistems.ui.components

import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.example.frolovsistems.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Кеш уже загруженных картинок на время жизни процесса.
 * Файлы отдаются по авторизованному адресу, поэтому обычный загрузчик
 * из библиотеки не подошёл бы — заголовок с токеном ставим сами.
 */
private object PhotoCache {
    private const val MAX_ENTRIES = 48
    private val entries = ConcurrentHashMap<String, ImageBitmap>()
    private val order = ArrayDeque<String>()

    fun get(key: String): ImageBitmap? = entries[key]

    @Synchronized
    fun put(key: String, value: ImageBitmap) {
        if (entries.put(key, value) == null) {
            order.addLast(key)
            while (order.size > MAX_ENTRIES) {
                entries.remove(order.removeFirst())
            }
        }
    }
}

private sealed interface PhotoState {
    data object Loading : PhotoState
    data class Ready(val image: ImageBitmap) : PhotoState
    data object Failed : PhotoState
}

/**
 * Показывает снимок с сервера по относительному адресу вида /media/<token>.
 */
@Composable
fun RemotePhoto(
    path: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val cached = remember(path) { PhotoCache.get(path) }
    var state by remember(path) {
        mutableStateOf(if (cached != null) PhotoState.Ready(cached) else PhotoState.Loading)
    }

    LaunchedEffect(path) {
        if (state is PhotoState.Ready || path.isBlank()) return@LaunchedEffect
        val result = ServiceLocator.crm.mediaBytes(path)
        state = result.fold(
            onSuccess = { bytes ->
                val bitmap = withContext(Dispatchers.Default) {
                    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                }
                if (bitmap == null) {
                    PhotoState.Failed
                } else {
                    val image = bitmap.asImageBitmap()
                    PhotoCache.put(path, image)
                    PhotoState.Ready(image)
                }
            },
            onFailure = { PhotoState.Failed },
        )
    }

    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = state, animationSpec = tween(240), label = "photo") { current ->
            when (current) {
                is PhotoState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }

                is PhotoState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.BrokenImage,
                        contentDescription = "Снимок не загрузился",
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }

                is PhotoState.Ready -> Image(
                    bitmap = current.image,
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
