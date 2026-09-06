package com.example.frolovsistems.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frolovsistems.core.net.CompanyDto
import com.example.frolovsistems.data.CrmRepository
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.components.ErrorBanner
import com.example.frolovsistems.ui.components.LoadingBox
import com.example.frolovsistems.ui.components.SectionHeader
import com.example.frolovsistems.ui.components.SoftCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CompanyUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    /** Что правится на экране прямо сейчас. */
    val draft: CompanyDto = CompanyDto(),
    /** Что лежит на сервере — по нему видно, есть ли несохранённые правки. */
    val saved: CompanyDto = CompanyDto(),
    val savedMessage: String? = null,
    val error: String? = null,
) {
    val dirty: Boolean get() = draft != saved
}

class CompanyViewModel(
    private val crm: CrmRepository = ServiceLocator.crm,
) : ViewModel() {

    private val _state = MutableStateFlow(CompanyUiState())
    val state: StateFlow<CompanyUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            crm.company()
                .onSuccess { company ->
                    _state.update { it.copy(loading = false, draft = company, saved = company) }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun edit(change: (CompanyDto) -> CompanyDto) = _state.update {
        it.copy(draft = change(it.draft), savedMessage = null)
    }

    fun save() {
        val draft = _state.value.draft
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null, savedMessage = null) }
            crm.saveCompany(draft)
                .onSuccess { company ->
                    _state.update {
                        it.copy(
                            saving = false,
                            draft = company,
                            saved = company,
                            savedMessage = "Реквизиты сохранены",
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(saving = false, error = e.message) } }
        }
    }
}

/**
 * Реквизиты ИП. Заполняются один раз и дальше подставляются в документы,
 * которые уходят клиенту, — поэтому поля разложены по тем же группам,
 * в которых они стоят в счёте: шапка, регистрация, контакты, банк, подпись.
 */
@Composable
fun CompanyScreen(
    onBack: () -> Unit = {},
    viewModel: CompanyViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
                Text("Реквизиты ИП", style = MaterialTheme.typography.headlineMedium)
            }
        }

        item { ErrorBanner(state.error) }

        if (state.loading) {
            item { LoadingBox() }
            return@LazyColumn
        }

        // Пока не заполнено главное, печатать документ нечем. Говорим об этом
        // здесь, а не в тот момент, когда человек уже нажал «Скачать счёт».
        if (!state.draft.readyForDocuments) {
            item {
                SoftCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Column {
                            Text(
                                "Документы пока не сформировать",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "Нужны хотя бы наименование и ИНН — они печатаются в шапке.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item {
            SoftCard {
                SectionHeader("Наименование", "Как ИП называется в шапке документа")
                Spacer(Modifier.height(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field(
                        value = state.draft.shortName,
                        onChange = { v -> viewModel.edit { it.copy(shortName = v) } },
                        label = "Краткое наименование",
                        placeholder = "ИП Фролов А. В.",
                        enabled = !state.saving,
                    )
                    Field(
                        value = state.draft.fullName,
                        onChange = { v -> viewModel.edit { it.copy(fullName = v) } },
                        label = "Полное наименование",
                        placeholder = "Индивидуальный предприниматель Фролов Алексей Владимирович",
                        enabled = !state.saving,
                        singleLine = false,
                    )
                }
            }
        }

        item {
            SoftCard {
                SectionHeader("Регистрация", "ИНН и ОГРНИП — как в свидетельстве")
                Spacer(Modifier.height(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field(
                        value = state.draft.inn,
                        onChange = { v -> viewModel.edit { it.copy(inn = digits(v, 12)) } },
                        label = "ИНН",
                        placeholder = "12 цифр",
                        enabled = !state.saving,
                        keyboard = KeyboardType.Number,
                    )
                    Field(
                        value = state.draft.ogrnip,
                        onChange = { v -> viewModel.edit { it.copy(ogrnip = digits(v, 15)) } },
                        label = "ОГРНИП",
                        placeholder = "15 цифр",
                        enabled = !state.saving,
                        keyboard = KeyboardType.Number,
                    )
                    Field(
                        value = state.draft.address,
                        onChange = { v -> viewModel.edit { it.copy(address = v) } },
                        label = "Адрес",
                        placeholder = "Индекс, город, улица, дом",
                        enabled = !state.saving,
                        singleLine = false,
                    )
                }
            }
        }

        item {
            SoftCard {
                SectionHeader("Контакты", "Печатаются в подвале, по ним с вами свяжутся")
                Spacer(Modifier.height(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field(
                        value = state.draft.phone,
                        onChange = { v -> viewModel.edit { it.copy(phone = v) } },
                        label = "Телефон",
                        placeholder = "+7 900 000-00-00",
                        enabled = !state.saving,
                        keyboard = KeyboardType.Phone,
                    )
                    Field(
                        value = state.draft.email,
                        onChange = { v -> viewModel.edit { it.copy(email = v) } },
                        label = "Почта",
                        placeholder = "info@ип-фролов.рф",
                        enabled = !state.saving,
                        keyboard = KeyboardType.Email,
                    )
                    Field(
                        value = state.draft.site,
                        onChange = { v -> viewModel.edit { it.copy(site = v) } },
                        label = "Сайт",
                        placeholder = "ип-фролов.рф",
                        enabled = !state.saving,
                        keyboard = KeyboardType.Uri,
                    )
                }
            }
        }

        item {
            SoftCard {
                SectionHeader("Банк", "Нужен, чтобы по счёту можно было заплатить")
                Spacer(Modifier.height(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field(
                        value = state.draft.bankName,
                        onChange = { v -> viewModel.edit { it.copy(bankName = v) } },
                        label = "Банк",
                        placeholder = "Наименование банка",
                        enabled = !state.saving,
                        singleLine = false,
                    )
                    Field(
                        value = state.draft.bankBik,
                        onChange = { v -> viewModel.edit { it.copy(bankBik = digits(v, 9)) } },
                        label = "БИК",
                        placeholder = "9 цифр",
                        enabled = !state.saving,
                        keyboard = KeyboardType.Number,
                    )
                    Field(
                        value = state.draft.bankAccount,
                        onChange = { v -> viewModel.edit { it.copy(bankAccount = digits(v, 20)) } },
                        label = "Расчётный счёт",
                        placeholder = "20 цифр",
                        enabled = !state.saving,
                        keyboard = KeyboardType.Number,
                    )
                    Field(
                        value = state.draft.bankCorrAccount,
                        onChange = { v -> viewModel.edit { it.copy(bankCorrAccount = digits(v, 20)) } },
                        label = "Корреспондентский счёт",
                        placeholder = "20 цифр, если банк его указывает",
                        enabled = !state.saving,
                        keyboard = KeyboardType.Number,
                    )
                }
            }
        }

        item {
            SoftCard {
                SectionHeader("Подпись и приписки", "Чем документ заканчивается")
                Spacer(Modifier.height(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field(
                        value = state.draft.signerName,
                        onChange = { v -> viewModel.edit { it.copy(signerName = v) } },
                        label = "Кто подписывает",
                        placeholder = "Фролов А. В.",
                        enabled = !state.saving,
                    )
                    Field(
                        value = state.draft.signerTitle,
                        onChange = { v -> viewModel.edit { it.copy(signerTitle = v) } },
                        label = "Должность",
                        placeholder = "Индивидуальный предприниматель",
                        enabled = !state.saving,
                    )
                    Field(
                        value = state.draft.taxNote,
                        onChange = { v -> viewModel.edit { it.copy(taxNote = v) } },
                        label = "Про налог",
                        placeholder = "НДС не облагается",
                        enabled = !state.saving,
                    )
                    Field(
                        value = state.draft.footerNote,
                        onChange = { v -> viewModel.edit { it.copy(footerNote = v) } },
                        label = "Приписка в конце",
                        placeholder = "Условия оплаты, сроки, гарантия",
                        enabled = !state.saving,
                        singleLine = false,
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = viewModel::save,
                    enabled = state.dirty && !state.saving,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.saving) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(if (state.dirty) "Сохранить" else "Всё сохранено")
                    }
                }
                AnimatedVisibility(
                    visible = state.savedMessage != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            state.savedMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }
    }
}

/** Оставляет только цифры и обрезает по длине: ИНН, БИК и счёт — фиксированные. */
private fun digits(raw: String, max: Int): String = raw.filter { it.isDigit() }.take(max)

/** Поле формы. Вынесено отдельно: их тут двадцать, и повторять обвязку незачем. */
@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String,
    enabled: Boolean,
    singleLine: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = singleLine,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}
