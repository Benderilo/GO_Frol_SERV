package com.example.frolovsistems.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.components.LoadingBox
import kotlinx.coroutines.flow.map

/**
 * Витрина глазами посетителя: сайт открывается прямо в приложении, чтобы
 * после правок в редакторе сразу увидеть результат. Ссылки на другие сайты,
 * звонки и мессенджеры уходят в системные приложения.
 */
@Composable
fun SitePreviewScreen(onEdit: () -> Unit = {}) {
    val context = LocalContext.current
    val baseUrl by remember {
        ServiceLocator.settings.preferences.map { it.server.baseUrl }
    }.collectAsStateWithLifecycle(initialValue = null)
    val displayUrl by remember {
        ServiceLocator.settings.preferences.map { it.server.displayUrl }
    }.collectAsStateWithLifecycle(initialValue = "")

    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }

    // «Назад» сначала листает историю сайта и только потом закрывает экран.
    BackHandler(enabled = canGoBack) { webView?.goBack() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Сайт-витрина", style = MaterialTheme.typography.titleMedium)
                Text(
                    displayUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Редактор сайта")
            }
            IconButton(onClick = { webView?.reload() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Обновить")
            }
            IconButton(
                onClick = {
                    val url = webView?.url ?: baseUrl ?: return@IconButton
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Открыть в браузере")
            }
        }
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        val url = baseUrl
        if (url == null) {
            Box(Modifier.fillMaxSize()) { LoadingBox(Modifier.align(Alignment.Center)) }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        // Сайт свой: лента работ и форма заявки без скриптов не работают.
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val target = request.url
                                val sameSite = target.scheme in setOf("http", "https") &&
                                    target.host == Uri.parse(url).host
                                if (sameSite) return false
                                // tel:, mailto:, MAX, Telegram — пусть открывает система.
                                runCatching {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, target))
                                }
                                return true
                            }

                            override fun onPageStarted(view: WebView, pageUrl: String?, favicon: Bitmap?) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView, pageUrl: String?) {
                                loading = false
                                canGoBack = view.canGoBack()
                            }
                        }
                        loadUrl(url)
                        webView = this
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}
