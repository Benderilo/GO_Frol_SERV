package com.example.frolovsistems.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType

/**
 * Поле суммы: человек вводит рубли, наружу уходят копейки.
 *
 * Текст поле держит у себя, а не выводит из числа. Иначе набор ломается на
 * середине: «1999.» после разбора превращается в 199900 копеек, обратно
 * печатается как «1999», и дописать копейки становится невозможно.
 */
@Composable
fun MoneyField(
    kop: Long,
    onKopChange: (Long) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "0",
) {
    DecimalField(
        value = kop,
        onValueChange = onKopChange,
        toText = ::moneyInput,
        fromText = ::parseMoneyKop,
        label = label,
        placeholder = placeholder,
        modifier = modifier,
        enabled = enabled,
    )
}

/**
 * Поле количества: человек вводит единицы, наружу уходят тысячные доли.
 * По той же причине, что и деньги, — чтобы остаток после сотни списаний
 * оставался круглым.
 */
@Composable
fun QuantityField(
    milli: Long,
    onMilliChange: (Long) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "0",
) {
    DecimalField(
        value = milli,
        onValueChange = onMilliChange,
        toText = ::quantityInput,
        fromText = ::parseQuantityMilli,
        label = label,
        placeholder = placeholder,
        modifier = modifier,
        enabled = enabled,
    )
}

@Composable
private fun DecimalField(
    value: Long,
    onValueChange: (Long) -> Unit,
    toText: (Long) -> String,
    fromText: (String) -> Long,
    label: String,
    placeholder: String,
    modifier: Modifier,
    enabled: Boolean,
) {
    var text by remember { mutableStateOf(toText(value)) }

    // Значение могло смениться снаружи — например, открыли другую позицию
    // в том же диалоге. Свой текст перетираем только если он больше не
    // соответствует числу: иначе набор «1999.» сбрасывался бы на каждой
    // перерисовке.
    if (fromText(text) != value) {
        text = toText(value)
    }

    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            // Оставляем цифры и один разделитель: минус здесь не нужен,
            // направление задаётся кнопкой «приход» или «списание».
            val cleaned = raw.replace(',', '.').filter { it.isDigit() || it == '.' }
            text = cleaned.substringBefore('.') +
                if (cleaned.contains('.')) "." + cleaned.substringAfter('.').filter { it.isDigit() } else ""
            onValueChange(fromText(text))
        },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}
