package com.example.frolovsistems.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frolovsistems.core.net.AdvantageDto
import com.example.frolovsistems.core.net.ServiceDto
import com.example.frolovsistems.core.net.SiteContentDto
import com.example.frolovsistems.data.SiteRepository
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.components.ErrorBanner
import com.example.frolovsistems.ui.components.LoadingBox
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
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SiteEditorContent(
        state = state,
        actions = viewModel,
    )
}

/** Разметка экрана без ViewModel: её показывает превью в Android Studio. */
@Composable
fun SiteEditorContent(
    state: SiteEditorUiState,
    actions: SiteEditorActions,
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
                            "Telegram",
                            state.content.contacts.telegram,
                            supporting = "Полная ссылка, например https://t.me/frolov",
                        ) { value ->
                            actions.edit { it.copy(contacts = it.contacts.copy(telegram = value)) }
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
