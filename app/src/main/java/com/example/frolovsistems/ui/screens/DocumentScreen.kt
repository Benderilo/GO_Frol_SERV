package com.example.frolovsistems.ui.screens

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frolovsistems.data.CrmRepository
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.components.ErrorBanner
import com.example.frolovsistems.ui.components.LoadingBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Виды печатных форм — те же значения принимает сервер. */
object DocumentKind {
    const val INVOICE = "invoice"
    const val ACT = "act"

    fun label(kind: String): String = when (kind) {
        INVOICE -> "Счёт на оплату"
        ACT -> "Акт выполненных работ"
        else -> kind
    }
}

data class DocumentUiState(
    val loading: Boolean = true,
    val html: String = "",
    val error: String? = null,
)

class DocumentViewModel(
    private val crm: CrmRepository = ServiceLocator.crm,
) : ViewModel() {

    private val _state = MutableStateFlow(DocumentUiState())
    val state: StateFlow<DocumentUiState> = _state.asStateFlow()

    fun load(orderId: Long, kind: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            crm.orderDocument(orderId, kind)
                .onSuccess { html -> _state.update { it.copy(loading = false, html = html) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }
}

/**
 * Печатная форма по заказу. Страницу собирает сервер, здесь она только
 * показывается и уходит в системный диалог печати — он же сохраняет её в PDF
 * и делится файлом. Своего PDF-движка в приложении нет и не нужно: системный
 * умеет и печать на принтер, и сохранение в файл.
 */
@Composable
fun DocumentScreen(
    orderId: Long,
    kind: String,
    onBack: () -> Unit = {},
    viewModel: DocumentViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(orderId, kind) { viewModel.load(orderId, kind) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Column(Modifier.weight(1f)) {
                Text(DocumentKind.label(kind), style = MaterialTheme.typography.titleMedium)
                Text(
                    "заказ № $orderId",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ErrorBanner(state.error, modifier = Modifier.padding(horizontal = 16.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading -> LoadingBox()
                state.html.isNotBlank() -> AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            // Документ пришёл готовой страницей: скрипты ему не нужны,
                            // и включать их значило бы дать им доступ к тому, что здесь
                            // показано, без всякой на то причины.
                            settings.javaScriptEnabled = false
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            webView = this
                        }
                    },
                    update = { view ->
                        view.loadDataWithBaseURL(null, state.html, "text/html", "UTF-8", null)
                        webView = view
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (state.html.isNotBlank()) {
            Button(
                onClick = { webView?.let { printDocument(context, it, kind, orderId) } },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Печать и сохранение в PDF")
            }
        }
    }
}

/**
 * Отдаёт страницу системному диалогу печати. В нём же выбирается
 * «Сохранить как PDF» — отдельной кнопки для файла не нужно.
 */
private fun printDocument(context: Context, webView: WebView, kind: String, orderId: Long) {
    val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
    val name = when (kind) {
        DocumentKind.INVOICE -> "Счёт по заказу $orderId"
        DocumentKind.ACT -> "Акт по заказу $orderId"
        else -> "Документ по заказу $orderId"
    }
    manager.print(
        name,
        webView.createPrintDocumentAdapter(name),
        PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .build(),
    )
}
