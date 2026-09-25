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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frolovsistems.core.net.DocumentBody
import com.example.frolovsistems.core.net.DocumentCardDto
import com.example.frolovsistems.core.net.DocumentDto
import com.example.frolovsistems.core.net.DocumentLineBody
import com.example.frolovsistems.data.CrmRepository
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.components.DatePickerField
import com.example.frolovsistems.ui.components.ErrorBanner
import com.example.frolovsistems.ui.components.LoadingBox
import com.example.frolovsistems.ui.components.MoneyField
import com.example.frolovsistems.ui.components.QuantityField
import com.example.frolovsistems.ui.theme.Success
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Виды печатных форм — те же значения принимает сервер. */
object DocumentKind {
    const val INVOICE = "invoice"
    const val ACT = "act"
    const val ESTIMATE = "estimate"
    const val WAYBILL = "waybill"
    const val UPD = "upd"
    const val RECONCILIATION = "reconciliation"

    val all = listOf(INVOICE, ACT, ESTIMATE, WAYBILL, UPD, RECONCILIATION)

    fun label(kind: String): String = when (kind) {
        INVOICE -> "Счёт на оплату"
        ACT -> "Акт выполненных работ"
        ESTIMATE -> "Смета"
        WAYBILL -> "Накладная"
        UPD -> "УПД"
        RECONCILIATION -> "Акт сверки"
        else -> kind
    }
}

/** Статусы документа. */
object DocumentStatus {
    const val DRAFT = "draft"
    const val ISSUED = "issued"
    const val ANNULLED = "annulled"

    fun label(status: String): String = when (status) {
        DRAFT -> "черновик"
        ISSUED -> "проведён"
        ANNULLED -> "аннулирован"
        else -> status
    }
}

/** Цвет статуса документа — в теме приложения, как у заказов и заявок. */
@Composable
fun documentStatusColor(status: String): Color = when (status) {
    DocumentStatus.DRAFT -> MaterialTheme.colorScheme.outline
    DocumentStatus.ISSUED -> Success
    DocumentStatus.ANNULLED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}

data class DocumentUiState(
    val loading: Boolean = true,
    val doc: DocumentCardDto? = null,
    val html: String = "",
    /** Выполняется действие: проведение, аннулирование. */
    val busy: Boolean = false,
    val error: String? = null,
)

class DocumentViewModel(
    private val crm: CrmRepository = ServiceLocator.crm,
) : ViewModel() {

    private val _state = MutableStateFlow(DocumentUiState())
    val state: StateFlow<DocumentUiState> = _state.asStateFlow()

    fun open(documentId: Long) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            load(documentId)
        }
    }

    /**
     * Вход из карточки заказа: документ вида на заказ один — открываем
     * проведённый, иначе черновик, а если нет ни того ни другого — заводим
     * черновик. Повторное нажатие «Счёт» не плодит копии.
     */
    fun openByOrder(orderId: Long, kind: String) {
        openOrCreate(
            list = { crm.documents(kind = kind, orderId = orderId) },
            pick = { docs ->
                docs.firstOrNull { it.status == DocumentStatus.ISSUED }
                    ?: docs.firstOrNull { it.status == DocumentStatus.DRAFT }
            },
            create = { DocumentBody(kind = kind, orderId = orderId) },
        )
    }

    /**
     * Вход из карточки клиента — смета и акт сверки. Их у клиента может быть
     * сколько угодно, поэтому возвращаемся только к недописанному черновику;
     * проведённые живут в журнале, а новое нажатие заводит новый документ.
     */
    fun openByClient(clientId: Long, kind: String) {
        openOrCreate(
            list = { crm.documents(kind = kind, clientId = clientId) },
            pick = { docs -> docs.firstOrNull { it.status == DocumentStatus.DRAFT } },
            create = { DocumentBody(kind = kind, clientId = clientId) },
        )
    }

    /**
     * Общий вход «найти или завести». Если журнал не загрузился, черновик
     * не заводим: иначе каждая осечка сети плодила бы дубликаты. Ошибку
     * показываем текстом сервера — он объясняет, чего не хватает
     * (например, незаполненных реквизитов ИП).
     */
    private fun openOrCreate(
        list: suspend () -> Result<List<DocumentDto>>,
        pick: (List<DocumentDto>) -> DocumentDto?,
        create: () -> DocumentBody,
    ) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val docs = list().getOrElse { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "Не удалось загрузить документы") }
                return@launch
            }
            val existing = pick(docs)
            if (existing != null) {
                load(existing.id)
                return@launch
            }
            crm.createDocument(create()).fold(
                onSuccess = { card -> load(card.id) },
                onFailure = { e ->
                    _state.update { it.copy(loading = false, error = e.message ?: "Не удалось завести документ") }
                },
            )
        }
    }

    private suspend fun load(id: Long) {
        crm.document(id).fold(
            onSuccess = { doc ->
                // Карточка есть, а печатная форма не собралась — документ всё
                // равно показываем с кнопками, а причину — в плашке.
                val print = crm.documentPrint(id)
                _state.update {
                    it.copy(
                        loading = false,
                        doc = doc,
                        html = print.getOrDefault(""),
                        error = print.exceptionOrNull()?.let { e ->
                            "Печатная форма не собралась: ${e.message}"
                        },
                    )
                }
            },
            onFailure = { e ->
                _state.update { it.copy(loading = false, error = e.message) }
            },
        )
    }

    /** Проводит черновик: номер закрепляется, содержимое замораживается. */
    fun issue() {
        val id = _state.value.doc?.id ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            crm.issueDocument(id).fold(
                onSuccess = {
                    _state.update { it.copy(busy = false) }
                    load(id)
                },
                onFailure = { e -> _state.update { it.copy(busy = false, error = e.message) } },
            )
        }
    }

    /** Аннулирует проведённый документ: номер больше не действует. */
    fun annul() {
        val id = _state.value.doc?.id ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            crm.annulDocument(id).fold(
                onSuccess = {
                    _state.update { it.copy(busy = false) }
                    load(id)
                },
                onFailure = { e -> _state.update { it.copy(busy = false, error = e.message) } },
            )
        }
    }

    /** Удаляет черновик — журнал хранит только проведённые и аннулированные. */
    fun delete(onDeleted: () -> Unit) {
        val id = _state.value.doc?.id ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            crm.deleteDocument(id).fold(
                onSuccess = {
                    _state.update { it.copy(busy = false) }
                    onDeleted()
                },
                onFailure = { e -> _state.update { it.copy(busy = false, error = e.message) } },
            )
        }
    }

    /**
     * Правка черновика: дату документа, период сверки и строки сметы.
     * Суммы и форматирование пересчитает сервер — сюда приходят сырые значения.
     */
    fun saveDraft(docDate: String, periodFrom: String, periodTo: String, lines: List<DocumentLineBody>?) {
        val id = _state.value.doc?.id ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            crm.updateDocument(
                id,
                DocumentBody(
                    docDate = docDate,
                    periodFrom = periodFrom,
                    periodTo = periodTo,
                    lines = lines,
                ),
            ).fold(
                onSuccess = {
                    _state.update { it.copy(busy = false) }
                    load(id)
                },
                onFailure = { e -> _state.update { it.copy(busy = false, error = e.message) } },
            )
        }
    }

    /** Смета становится заказом: строки переезжают в состав, клиент — в карточку. */
    fun convertToOrder() {
        val doc = _state.value.doc ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            crm.documentToOrder(doc.id).fold(
                onSuccess = { card ->
                    _state.update { it.copy(busy = false) }
                    load(card.document.id)
                },
                onFailure = { e -> _state.update { it.copy(busy = false, error = e.message) } },
            )
        }
    }
}

/**
 * Печатная форма документа. Страницу собирает сервер, здесь она только
 * показывается и уходит в системный диалог печати — он же сохраняет её в PDF
 * и делится файлом. Своего PDF-движка в приложении нет и не нужно: системный
 * умеет и печать на принтер, и сохранение в файл.
 *
 * Черновик можно провести и удалить, проведённый — аннулировать: номер
 * при этом не переиспользуется, а печатная форма получает штамп.
 */
@Composable
fun DocumentScreen(
    documentId: Long,
    onBack: () -> Unit = {},
    viewModel: DocumentViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(documentId) { viewModel.open(documentId) }
    DocumentBody(state, viewModel, onBack)
}

/** Вход из карточки заказа: находит документ вида или заводит черновик. */
@Composable
fun OrderDocumentScreen(
    orderId: Long,
    kind: String,
    onBack: () -> Unit = {},
    viewModel: DocumentViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(orderId, kind) { viewModel.openByOrder(orderId, kind) }
    DocumentBody(state, viewModel, onBack)
}

/** Вход из карточки клиента: смета или акт сверки. */
@Composable
fun ClientDocumentScreen(
    clientId: Long,
    kind: String,
    onBack: () -> Unit = {},
    viewModel: DocumentViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(clientId, kind) { viewModel.openByClient(clientId, kind) }
    DocumentBody(state, viewModel, onBack)
}

@Composable
private fun DocumentBody(
    state: DocumentUiState,
    viewModel: DocumentViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val doc = state.doc
    var webView by remember { mutableStateOf<WebView?>(null) }
    var confirmAnnul by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    doc?.kindTitle?.ifBlank { DocumentKind.label(doc.kind) }
                        ?: DocumentKind.label(doc?.kind ?: ""),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    doc?.let { documentSubtitle(it) } ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (doc != null) {
                Text(
                    DocumentStatus.label(doc.status),
                    style = MaterialTheme.typography.labelMedium,
                    color = documentStatusColor(doc.status),
                    modifier = Modifier.padding(end = 12.dp),
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
                        // Перезагружаем страницу только когда она сменилась: иначе
                        // каждое «Сохраняем…» сбрасывало бы масштаб и прокрутку.
                        if (view.tag != state.html) {
                            view.tag = state.html
                            view.loadDataWithBaseURL(null, state.html, "text/html", "UTF-8", null)
                        }
                        webView = view
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (doc != null && !state.loading) {
            val canPrint = state.html.isNotBlank() && webView != null
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (doc.status) {
                        DocumentStatus.DRAFT -> {
                            Button(
                                onClick = { viewModel.issue() },
                                enabled = !state.busy,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (state.busy) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text("Провести")
                            }
                            OutlinedButton(
                                onClick = { webView?.let { printDocument(context, it, doc) } },
                                enabled = canPrint,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text("Печать")
                            }
                            TextButton(onClick = { confirmDelete = true }, enabled = !state.busy) {
                                Text("Удалить")
                            }
                        }

                        DocumentStatus.ISSUED -> {
                            Button(
                                onClick = { webView?.let { printDocument(context, it, doc) } },
                                enabled = canPrint,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text("Печать и сохранение в PDF")
                            }
                            TextButton(onClick = { confirmAnnul = true }, enabled = !state.busy) {
                                Text("Аннулировать")
                            }
                        }

                        else -> {
                            // Аннулированный: печатать можно (со штампом), менять — нет.
                            Button(
                                onClick = { webView?.let { printDocument(context, it, doc) } },
                                enabled = canPrint,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text("Печать")
                            }
                        }
                    }
                }

                // Черновик можно поправить: дату — у любого вида, строки — у сметы,
                // период — у сверки. Проведённый документ неизменен: поправить
                // значит аннулировать и завести новый.
                if (doc.status == DocumentStatus.DRAFT) {
                    TextButton(
                        onClick = { showEdit = true },
                        enabled = !state.busy,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) { Text("Изменить черновик") }
                }

                // Смета без заказа превращается в заказ одним нажатием:
                // строки переезжают в состав, номер сметы остаётся в журнале.
                if (doc.kind == DocumentKind.ESTIMATE && doc.orderId == null &&
                    doc.status != DocumentStatus.ANNULLED
                ) {
                    TextButton(
                        onClick = { viewModel.convertToOrder() },
                        enabled = !state.busy,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) { Text("Превратить в заказ") }
                }
            }
        }
    }

    if (showEdit && doc != null) {
        DraftEditDialog(
            doc = doc,
            busy = state.busy,
            onDismiss = { showEdit = false },
            onSave = { docDate, from, to, lines ->
                showEdit = false
                viewModel.saveDraft(docDate, from, to, lines)
            },
        )
    }

    if (confirmAnnul && doc != null) {
        AlertDialog(
            onDismissRequest = { confirmAnnul = false },
            title = { Text("Аннулировать ${doc.kindTitle.lowercase()}?") },
            text = {
                Text(
                    "Документ помечается недействительным, номер ${doc.number} больше " +
                        "не используется и не достанется новому документу. Действие нельзя отменить.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmAnnul = false
                    viewModel.annul()
                }) { Text("Аннулировать") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAnnul = false }) { Text("Отмена") }
            },
        )
    }

    if (confirmDelete && doc != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить черновик?") },
            text = { Text("Черновик ещё не проводился — его можно убрать без следов.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(onBack)
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Отмена") }
            },
        )
    }
}

private fun documentSubtitle(doc: DocumentCardDto): String {
    val parts = mutableListOf<String>()
    if (doc.number > 0) {
        parts += "№ ${doc.number}"
    }
    if (doc.docDate.isNotBlank()) {
        parts += doc.docDate
    }
    if (doc.orderId != null && doc.orderId > 0) {
        parts += "заказ № ${doc.orderId}"
    } else if (doc.clientName.isNotBlank()) {
        parts += doc.clientName
    }
    return parts.joinToString(" · ")
}

/**
 * Отдаёт страницу системному диалогу печати. В нём же выбирается
 * «Сохранить как PDF» — отдельной кнопки для файла не нужно.
 */
private fun printDocument(context: Context, webView: WebView, doc: DocumentCardDto) {
    val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
    val number = if (doc.number > 0) " № ${doc.number}" else ""
    val name = "${doc.kindTitle.ifBlank { DocumentKind.label(doc.kind) }}$number"
    manager.print(
        name,
        webView.createPrintDocumentAdapter(name),
        PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .build(),
    )
}

/** Черновик строки для правки: сырые значения, без форматирования. */
private data class LineDraft(
    val name: String = "",
    val unit: String = "",
    val qtyMilli: Long = 1000,
    val priceKop: Long = 0,
)

/**
 * Правка черновика. Дату меняет любой вид; строки табличной части — смета
 * (счёт, акт, накладная и УПД собираются из состава заказа); период — акт
 * сверки (сервер пересчитает таблицу взаиморасчётов).
 */
@Composable
private fun DraftEditDialog(
    doc: DocumentCardDto,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (docDate: String, periodFrom: String, periodTo: String, lines: List<DocumentLineBody>?) -> Unit,
) {
    var docDate by remember { mutableStateOf(doc.docDate) }
    var periodFrom by remember { mutableStateOf(doc.periodFrom) }
    var periodTo by remember { mutableStateOf(doc.periodTo) }
    var lines by remember {
        mutableStateOf(
            doc.content.lines.map { LineDraft(it.name, it.unit, it.qtyMilli, it.priceKop) },
        )
    }
    val editLines = doc.kind == DocumentKind.ESTIMATE
    val editPeriod = doc.kind == DocumentKind.RECONCILIATION

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Черновик: ${doc.kindTitle.ifBlank { DocumentKind.label(doc.kind) }}") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DatePickerField(label = "Дата документа", value = docDate, iso = true) { docDate = it }

                if (editPeriod) {
                    DatePickerField(label = "Период с", value = periodFrom, iso = true) { periodFrom = it }
                    DatePickerField(label = "Период по", value = periodTo, iso = true) { periodTo = it }
                }

                if (editLines) {
                    Text("Строки сметы", style = MaterialTheme.typography.labelMedium)
                    lines.forEachIndexed { index, line ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = line.name,
                                onValueChange = { v ->
                                    lines = lines.toMutableList().also { it[index] = line.copy(name = v) }
                                },
                                label = { Text("Работа или материал ${index + 1}") },
                                singleLine = true,
                                shape = MaterialTheme.shapes.small,
                                enabled = !busy,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                QuantityField(
                                    milli = line.qtyMilli,
                                    onMilliChange = { v ->
                                        lines = lines.toMutableList().also { it[index] = line.copy(qtyMilli = v) }
                                    },
                                    label = "Кол-во",
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = line.unit,
                                    onValueChange = { v ->
                                        lines = lines.toMutableList().also { it[index] = line.copy(unit = v) }
                                    },
                                    label = { Text("Ед.") },
                                    singleLine = true,
                                    shape = MaterialTheme.shapes.small,
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                )
                                MoneyField(
                                    kop = line.priceKop,
                                    onKopChange = { v ->
                                        lines = lines.toMutableList().also { it[index] = line.copy(priceKop = v) }
                                    },
                                    label = "Цена, ₽",
                                    enabled = !busy,
                                    modifier = Modifier.weight(1.4f),
                                )
                            }
                            TextButton(
                                onClick = { lines = lines.filterIndexed { i, _ -> i != index } },
                                enabled = !busy,
                            ) { Text("Убрать строку") }
                        }
                    }
                    OutlinedButton(
                        onClick = { lines = lines + LineDraft() },
                        enabled = !busy,
                        shape = MaterialTheme.shapes.small,
                    ) { Text("Добавить строку") }
                }

                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Сохраняем…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val bodyLines = if (editLines) {
                        lines.filter { it.name.isNotBlank() }.map {
                            DocumentLineBody(name = it.name, unit = it.unit, qtyMilli = it.qtyMilli, priceKop = it.priceKop)
                        }
                    } else {
                        null
                    }
                    onSave(docDate, periodFrom, periodTo, bodyLines)
                },
                enabled = !busy,
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } },
    )
}
