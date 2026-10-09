package com.example.frolovsistems.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frolovsistems.core.net.AdvantageDto
import com.example.frolovsistems.core.net.PhotoBytes
import com.example.frolovsistems.core.net.PhotoDto
import com.example.frolovsistems.core.net.ServiceDto
import com.example.frolovsistems.core.net.SiteContentDto
import com.example.frolovsistems.data.SiteRepository
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.components.ErrorBanner
import com.example.frolovsistems.ui.components.LoadingBox
import com.example.frolovsistems.ui.components.PhotoViewerDialog
import com.example.frolovsistems.ui.components.RemotePhoto
import com.example.frolovsistems.ui.components.SoftCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.example.frolovsistems.ui.components.rememberFabScrollState
import com.example.frolovsistems.ui.components.CrmFab
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.tooling.preview.Preview
import com.example.frolovsistems.ui.preview.PreviewScreen
import com.example.frolovsistems.ui.preview.PreviewData

/** Доступные иконки услуг — те же, что понимает шаблон сайта. */
private val serviceIcons = listOf("bolt", "wrench", "home", "building", "shield", "doc")

data class SiteEditorUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val content: SiteContentDto = SiteContentDto(),
    val original: SiteContentDto = SiteContentDto(),
    val error: String? = null,
    val savedAt: Long = 0,
    /** Фото работ публикуются сразу, без кнопки «Опубликовать». */
    val works: List<PhotoDto> = emptyList(),
    /** Что сейчас происходит с фото, например «Загружаем 2 из 5»; null — ничего. */
    val worksBusy: String? = null,
) {
    val dirty: Boolean get() = content != original
}

/**
 * Действия экрана. Их выполняет [SiteEditorViewModel]; превью в Android Studio
 * передаёт пустую реализацию, и разметка рисуется без сети и базы.
 */
interface SiteEditorActions {
    fun load() {}
    fun edit(transform: (SiteContentDto) -> SiteContentDto) {}
    fun save() {}
    fun reset() {}
    fun discard() {}
    fun uploadWorks(photos: List<ByteArray>) {}
    fun captionWork(id: Long, caption: String) {}
    fun moveWork(id: Long, delta: Int) {}
    fun deleteWork(id: Long) {}
}

class SiteEditorViewModel(
    private val repository: SiteRepository = ServiceLocator.site,
) : ViewModel(), SiteEditorActions {

    private val _state = MutableStateFlow(SiteEditorUiState())
    val state: StateFlow<SiteEditorUiState> = _state.asStateFlow()

    init { load() }

    override fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            repository.load()
                .onSuccess { content ->
                    _state.update { it.copy(loading = false, content = content, original = content) }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
            repository.works()
                .onSuccess { works -> _state.update { it.copy(works = works) } }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    /** Серию грузим по одному снимку: так видно ход, а сбой одного не губит остальные. */
    override fun uploadWorks(photos: List<ByteArray>) {
        if (photos.isEmpty() || _state.value.worksBusy != null) return
        viewModelScope.launch {
            var failed = 0
            var lastError: String? = null
            photos.forEachIndexed { index, bytes ->
                _state.update { it.copy(worksBusy = "Загружаем ${index + 1} из ${photos.size}…") }
                repository.uploadWork(bytes, "work_${System.currentTimeMillis()}_$index.jpg")
                    .onSuccess { work -> _state.update { it.copy(works = it.works + work) } }
                    .onFailure { e ->
                        failed++
                        lastError = e.message
                    }
            }
            _state.update {
                it.copy(
                    worksBusy = null,
                    error = if (failed == 0) null else "Не загрузилось фото: $failed из ${photos.size}. $lastError",
                )
            }
        }
    }

    override fun captionWork(id: Long, caption: String) {
        viewModelScope.launch {
            repository.captionWork(id, caption)
                .onSuccess { saved ->
                    _state.update { s -> s.copy(works = s.works.map { if (it.id == id) saved else it }) }
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    override fun moveWork(id: Long, delta: Int) {
        viewModelScope.launch {
            repository.moveWork(id, delta)
                .onSuccess { works -> _state.update { it.copy(works = works) } }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    override fun deleteWork(id: Long) {
        viewModelScope.launch {
            repository.deleteWork(id)
                .onSuccess { _state.update { s -> s.copy(works = s.works.filterNot { it.id == id }) } }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    /** Единая точка правки: экраны присылают изменённую копию документа. */
    override fun edit(transform: (SiteContentDto) -> SiteContentDto) =
        _state.update { it.copy(content = transform(it.content), error = null) }

    override fun save() {
        val content = _state.value.content
        if (content.siteName.isBlank()) {
            _state.update { it.copy(error = "Название сайта не может быть пустым") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            repository.save(content)
                .onSuccess { saved ->
                    _state.update {
                        it.copy(
                            saving = false,
                            content = saved,
                            original = saved,
                            savedAt = System.currentTimeMillis(),
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(saving = false, error = e.message) } }
        }
    }

    override fun reset() {
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            repository.reset()
                .onSuccess { content ->
                    _state.update { it.copy(saving = false, content = content, original = content) }
                }
                .onFailure { e -> _state.update { it.copy(saving = false, error = e.message) } }
        }
    }

    override fun discard() = _state.update { it.copy(content = it.original) }
}

@Composable
fun SiteEditorScreen(
    viewModel: SiteEditorViewModel = viewModel(),
    onPreview: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SiteEditorContent(
        state = state,
        actions = viewModel,
        onPreview = onPreview,
    )
}

/** Разметка экрана без ViewModel: её показывает превью в Android Studio. */
@Composable
fun SiteEditorContent(
    state: SiteEditorUiState,
    actions: SiteEditorActions,
    onPreview: () -> Unit = {},
) {
    // Плавающая кнопка уезжает при прокрутке вниз и возвращается при прокрутке вверх.
    val fabScroll = rememberFabScrollState()
    var confirmReset by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        if (state.loading) {
            LoadingBox(Modifier.align(Alignment.Center))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().imePadding().nestedScroll(fabScroll.connection),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Сайт", style = MaterialTheme.typography.headlineMedium)
                            Text(
                                "Всё, что видят посетители. Ревизия ${state.content.revision}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = onPreview) {
                            Icon(Icons.Default.Visibility, contentDescription = "Посмотреть сайт")
                        }
                        IconButton(onClick = actions::load, enabled = !state.saving) {
                            Icon(Icons.Default.Refresh, contentDescription = "Перезагрузить")
                        }
                        IconButton(onClick = { confirmReset = true }, enabled = !state.saving) {
                            Icon(Icons.Default.RestartAlt, contentDescription = "Сбросить к заводским")
                        }
                    }
                }

                item { ErrorBanner(state.error) }

                item {
                    EditorSection("Основное", expandedByDefault = true) {
                        Field("Название сайта", state.content.siteName) { value ->
                            actions.edit { it.copy(siteName = value) }
                        }
                        Field("Слоган под названием", state.content.tagline) { value ->
                            actions.edit { it.copy(tagline = value) }
                        }
                        Field("Подпись в подвале", state.content.footerNote, lines = 2) { value ->
                            actions.edit { it.copy(footerNote = value) }
                        }
                    }
                }

                item {
                    EditorSection("Бегущая строка", expandedByDefault = true) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Показывать на сайте", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = state.content.ticker.enabled,
                                onCheckedChange = { enabled ->
                                    actions.edit { it.copy(ticker = it.ticker.copy(enabled = enabled)) }
                                },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Field(
                            label = "Текст строки",
                            value = state.content.ticker.text,
                            lines = 3,
                            supporting = "Разделяйте пункты знаком •",
                        ) { value ->
                            actions.edit { it.copy(ticker = it.ticker.copy(text = value)) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Скорость прокрутки: ${state.content.ticker.speedSec} с на круг",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = state.content.ticker.speedSec.toFloat(),
                            onValueChange = { value ->
                                actions.edit {
                                    it.copy(ticker = it.ticker.copy(speedSec = value.toInt().coerceAtLeast(5)))
                                }
                            },
                            valueRange = 8f..60f,
                        )
                    }
                }

                item {
                    EditorSection("Первый экран") {
                        Field("Плашка над заголовком", state.content.hero.badge) { value ->
                            actions.edit { it.copy(hero = it.hero.copy(badge = value)) }
                        }
                        Field("Заголовок", state.content.hero.title, lines = 2) { value ->
                            actions.edit { it.copy(hero = it.hero.copy(title = value)) }
                        }
                        Field("Подзаголовок", state.content.hero.subtitle, lines = 4) { value ->
                            actions.edit { it.copy(hero = it.hero.copy(subtitle = value)) }
                        }
                        Field("Главная кнопка", state.content.hero.primaryCta) { value ->
                            actions.edit { it.copy(hero = it.hero.copy(primaryCta = value)) }
                        }
                        Field("Вторая кнопка", state.content.hero.secondaryCta) { value ->
                            actions.edit { it.copy(hero = it.hero.copy(secondaryCta = value)) }
                        }
                    }
                }

                item {
                    EditorSection("Наши работы (${state.works.size})", expandedByDefault = true) {
                        WorksEditor(state = state, actions = actions)
                    }
                }

                item {
                    EditorSection("О компании") {
                        Field("Заголовок раздела", state.content.about.title) { value ->
                            actions.edit { it.copy(about = it.about.copy(title = value)) }
                        }
                        Field("Текст", state.content.about.text, lines = 6) { value ->
                            actions.edit { it.copy(about = it.about.copy(text = value)) }
                        }
                    }
                }

                item {
                    EditorSection("Услуги (${state.content.services.size})") {
                        state.content.services.forEachIndexed { index, service ->
                            ServiceEditor(
                                service = service,
                                onChange = { updated ->
                                    actions.edit { content ->
                                        content.copy(
                                            services = content.services.toMutableList()
                                                .also { it[index] = updated },
                                        )
                                    }
                                },
                                onDelete = {
                                    actions.edit { content ->
                                        content.copy(
                                            services = content.services.filterIndexed { i, _ -> i != index },
                                        )
                                    }
                                },
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        AddButton("Добавить услугу") {
                            actions.edit { it.copy(services = it.services + ServiceDto(title = "Новая услуга")) }
                        }
                    }
                }

                item {
                    EditorSection("Цифры (${state.content.advantages.size})") {
                        state.content.advantages.forEachIndexed { index, advantage ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedTextField(
                                    value = advantage.value,
                                    onValueChange = { value ->
                                        actions.edit { content ->
                                            content.copy(
                                                advantages = content.advantages.toMutableList()
                                                    .also { it[index] = advantage.copy(value = value) },
                                            )
                                        }
                                    },
                                    label = { Text("Значение") },
                                    singleLine = true,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.weight(0.8f),
                                )
                                OutlinedTextField(
                                    value = advantage.label,
                                    onValueChange = { value ->
                                        actions.edit { content ->
                                            content.copy(
                                                advantages = content.advantages.toMutableList()
                                                    .also { it[index] = advantage.copy(label = value) },
                                            )
                                        }
                                    },
                                    label = { Text("Подпись") },
                                    singleLine = true,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.weight(1.4f),
                                )
                                IconButton(onClick = {
                                    actions.edit { content ->
                                        content.copy(
                                            advantages = content.advantages.filterIndexed { i, _ -> i != index },
                                        )
                                    }
                                }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Удалить",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        AddButton("Добавить цифру") {
                            actions.edit { it.copy(advantages = it.advantages + AdvantageDto()) }
                        }
                    }
                }

                item {
                    EditorSection("Контакты", expandedByDefault = true) {
                        Field("Телефон", state.content.contacts.phone) { value ->
                            actions.edit { it.copy(contacts = it.contacts.copy(phone = value)) }
                        }
                        Field("E-mail", state.content.contacts.email) { value ->
                            actions.edit { it.copy(contacts = it.contacts.copy(email = value)) }
                        }
                        Field("Адрес", state.content.contacts.address, lines = 2) { value ->
                            actions.edit { it.copy(contacts = it.contacts.copy(address = value)) }
                        }
                        Field(
                            "Город",
                            state.content.contacts.city,
                            supporting = "Идёт в заголовок сайта и поисковую выдачу: «электромонтаж в <город>»",
                        ) { value ->
                            actions.edit { it.copy(contacts = it.contacts.copy(city = value)) }
                        }
                        Field("Часы работы", state.content.contacts.workHours, lines = 2) { value ->
                            actions.edit { it.copy(contacts = it.contacts.copy(workHours = value)) }
                        }
                        Field(
                            "Часы для поисковиков",
                            state.content.contacts.workHoursSchema,
                            supporting = "Формат schema.org, например Mo-Su 08:00-21:00 — по нему поисковики показывают часы работы в выдаче",
                        ) { value ->
                            actions.edit { it.copy(contacts = it.contacts.copy(workHoursSchema = value)) }
                        }
                        Field(
                            "Telegram",
                            state.content.contacts.telegram,
                            supporting = "Полная ссылка, например https://t.me/frolov",
                        ) { value ->
                            actions.edit { it.copy(contacts = it.contacts.copy(telegram = value)) }
                        }
                        Field(
                            "MAX",
                            state.content.contacts.max,
                            supporting = "Ссылка на бота: значок у формы заявки и в углу сайта. Пусто — значка нет",
                        ) { value ->
                            actions.edit { it.copy(contacts = it.contacts.copy(max = value)) }
                        }
                    }
                }

                item {
                    EditorSection("Оформление сайта") {
                        ColorField(
                            label = "Основной цвет",
                            value = state.content.appearance.accent,
                        ) { value ->
                            actions.edit { it.copy(appearance = it.appearance.copy(accent = value)) }
                        }
                        Spacer(Modifier.height(10.dp))
                        ColorField(
                            label = "Второй цвет градиента",
                            value = state.content.appearance.accentAlt,
                        ) { value ->
                            actions.edit { it.copy(appearance = it.appearance.copy(accentAlt = value)) }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("Тема сайта по умолчанию", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                "auto" to "Как в системе",
                                "light" to "Светлая",
                                "dark" to "Тёмная",
                            ).forEach { (mode, label) ->
                                AssistChip(
                                    onClick = {
                                        actions.edit {
                                            it.copy(appearance = it.appearance.copy(defaultMode = mode))
                                        }
                                    },
                                    label = { Text(label) },
                                    colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                                        containerColor = if (state.content.appearance.defaultMode == mode) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            Color.Transparent
                                        },
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Кнопка сохранения появляется, только когда есть что сохранять;
        // при прокрутке вниз уезжает, как и на остальных экранах.
        CrmFab(
            icon = Icons.Default.Check,
            contentDescription = "Опубликовать",
            onClick = actions::save,
            visible = state.dirty && !state.loading && fabScroll.visible,
            busy = state.saving,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )

        AnimatedVisibility(
            visible = state.dirty && !state.saving,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart).padding(20.dp),
        ) {
            TextButton(onClick = actions::discard) { Text("Отменить правки") }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Сбросить содержимое?") },
            text = { Text("Весь текст сайта вернётся к заводскому. Действие необратимо.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmReset = false
                        actions.reset()
                    },
                ) { Text("Сбросить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Отмена") }
            },
        )
    }
}

/** Сворачиваемая секция редактора. */
@Composable
private fun EditorSection(
    title: String,
    expandedByDefault: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(expandedByDefault) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(280),
        label = "sectionArrow",
    )

    SoftCard(contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.animateContentSize(tween(280))) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.rotate(arrowRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp), content = content)
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    lines: Int = 1,
    supporting: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = lines == 1,
        minLines = lines,
        maxLines = if (lines == 1) 1 else lines + 2,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    )
}

@Composable
private fun ColorField(label: String, value: String, onChange: (String) -> Unit) {
    val parsed = remember(value) { parseHexColor(value) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .size(44.dp)
                .background(parsed ?: MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
        )
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it.uppercase().take(7)) },
            label = { Text(label) },
            supportingText = { Text(if (parsed == null) "Формат #RRGGBB" else "Применится на сайте") },
            isError = parsed == null,
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ServiceEditor(
    service: ServiceDto,
    onChange: (ServiceDto) -> Unit,
    onDelete: () -> Unit,
) {
    SoftCard(contentPadding = PaddingValues(14.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                service.title.ifBlank { "Без названия" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error)
            }
        }
        OutlinedTextField(
            value = service.title,
            onValueChange = { onChange(service.copy(title = it)) },
            label = { Text("Название") },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        OutlinedTextField(
            value = service.description,
            onValueChange = { onChange(service.copy(description = it)) },
            label = { Text("Описание") },
            minLines = 2,
            maxLines = 4,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        OutlinedTextField(
            value = service.price,
            onValueChange = { onChange(service.copy(price = it)) },
            label = { Text("Цена (текстом)") },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        Text(
            "Иконка",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            serviceIcons.forEach { icon ->
                AssistChip(
                    onClick = { onChange(service.copy(icon = icon)) },
                    label = { Text(icon, style = MaterialTheme.typography.labelSmall) },
                    colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                        containerColor = if (service.icon == icon) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color.Transparent
                        },
                    ),
                )
            }
        }
    }
}

/**
 * Фото работ для витрины: лента миниатюр в том порядке, в каком их увидят
 * на сайте, и загрузка одного снимка или целой серии из галереи телефона.
 */
@Composable
private fun WorksEditor(state: SiteEditorUiState, actions: SiteEditorActions) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    var pickError by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Long?>(null) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    // Системный выбор нескольких снимков: разрешения на галерею не нужны.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_WORKS_PER_PICK),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            preparing = true
            val prepared = uris.mapNotNull { PhotoBytes.fromUri(context, it) }
            preparing = false
            pickError = if (prepared.size < uris.size) {
                "Не удалось подготовить ${uris.size - prepared.size} из ${uris.size} — пропущены"
            } else {
                null
            }
            actions.uploadWorks(prepared)
        }
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Показывать на сайте", style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = state.content.works.enabled,
            onCheckedChange = { enabled ->
                actions.edit { it.copy(works = it.works.copy(enabled = enabled)) }
            },
        )
    }
    Spacer(Modifier.height(8.dp))
    Field("Заголовок раздела", state.content.works.title) { value ->
        actions.edit { it.copy(works = it.works.copy(title = value)) }
    }
    Field("Подзаголовок", state.content.works.subtitle, lines = 2) { value ->
        actions.edit { it.copy(works = it.works.copy(subtitle = value)) }
    }

    Text(
        "На сайте фото идут горизонтальной лентой — её листают пальцем или стрелками. " +
            "Фото публикуются сразу после загрузки; нажмите на снимок, чтобы подписать, " +
            "передвинуть или удалить его.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))

    if (state.works.isNotEmpty()) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(state.works, key = { _, work -> work.id }) { index, work ->
                Box(
                    Modifier
                        .size(width = 150.dp, height = 112.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { editing = work.id },
                ) {
                    RemotePhoto(
                        path = work.thumbUrl,
                        contentDescription = work.caption.ifBlank { "Фото работы" },
                        modifier = Modifier.fillMaxSize(),
                    )
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    if (work.caption.isNotBlank()) {
                        Text(
                            work.caption,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.55f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    val busy = preparing || state.worksBusy != null
    OutlinedButton(
        onClick = {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        enabled = !busy,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text(state.worksBusy ?: "Готовим фото…")
        } else {
            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Добавить фото или серию фото")
        }
    }

    pickError?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    val editingIndex = state.works.indexOfFirst { it.id == editing }
    if (editingIndex >= 0) {
        val work = state.works[editingIndex]
        WorkDialog(
            work = work,
            index = editingIndex,
            count = state.works.size,
            onCaption = { actions.captionWork(work.id, it) },
            onMove = { delta -> actions.moveWork(work.id, delta) },
            onDelete = {
                actions.deleteWork(work.id)
                editing = null
            },
            onView = {
                viewerIndex = editingIndex
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    viewerIndex?.let { index ->
        PhotoViewerDialog(
            photos = state.works,
            initialIndex = index,
            onDismiss = { viewerIndex = null },
        )
    }
}

/** Карточка одного фото: подпись, место в ленте и удаление. */
@Composable
private fun WorkDialog(
    work: PhotoDto,
    index: Int,
    count: Int,
    onCaption: (String) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onView: () -> Unit,
    onDismiss: () -> Unit,
) {
    var caption by remember(work.id) { mutableStateOf(work.caption) }
    var confirmDelete by remember(work.id) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Фото ${index + 1} из $count") },
        text = {
            Column {
                RemotePhoto(
                    path = work.thumbUrl,
                    contentDescription = work.caption.ifBlank { "Фото работы" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClick = onView),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    label = { Text("Подпись на сайте") },
                    placeholder = { Text("Например: щит в частном доме") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Место в ленте",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onMove(-1) }, enabled = index > 0) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Левее")
                    }
                    IconButton(onClick = { onMove(1) }, enabled = index < count - 1) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Правее")
                    }
                }
                TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        if (confirmDelete) "Точно удалить с сайта?" else "Удалить фото",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (caption.trim() != work.caption) onCaption(caption.trim())
                    onDismiss()
                },
            ) { Text("Готово") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

/** Сколько снимков можно выбрать за раз: серия больше грузится слишком долго. */
private const val MAX_WORKS_PER_PICK = 20

@Composable
private fun AddButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(text)
    }
}

/** Разбирает строку вида #RRGGBB; возвращает null, если формат неверный. */
fun parseHexColor(hex: String): Color? {
    val clean = hex.removePrefix("#")
    if (clean.length != 6 || clean.any { it.digitToIntOrNull(16) == null }) return null
    val value = clean.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or value)
}

// ---------------------------------------------------------------------------
// Превью для Android Studio: Split или Design справа сверху. Данные — образцы
// из PreviewData, действия — пустые: экран рисуется без сети и базы.
// ---------------------------------------------------------------------------

@Preview(name = "Редактор сайта", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun SiteEditorContentPreview() = PreviewScreen {
    SiteEditorContent(
        state = SiteEditorUiState(
            loading = false,
            content = SiteContentDto(siteName = "Фролов Системс", tagline = "Электромонтаж под ключ"),
            original = SiteContentDto(siteName = "Фролов Системс", tagline = "Электромонтаж под ключ"),
        ),
        actions = object : SiteEditorActions {},
    )
}
