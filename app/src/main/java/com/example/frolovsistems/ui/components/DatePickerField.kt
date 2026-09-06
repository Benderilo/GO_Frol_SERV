package com.example.frolovsistems.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Дата с выбором из календаря вместо набора руками. Поле остаётся
 * редактируемым — вписать текстом тоже можно, — а иконка календаря
 * справа открывает выбор. Пустое значение разрешено: «без срока».
 *
 * [iso]=true — строка «2026-09-10» (так хранят срок задач),
 * иначе привычная «10.09.2026».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerField(
    label: String,
    value: String,
    iso: Boolean = false,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    val format = if (iso) {
        DateTimeFormatter.ISO_LOCAL_DATE
    } else {
        DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
    var open by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        trailingIcon = {
            Row {
                if (value.isNotBlank()) {
                    IconButton(onClick = { onChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Очистить")
                    }
                }
                IconButton(onClick = { open = true }) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = "Выбрать дату")
                }
            }
        },
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
    )

    if (open) {
        // DatePicker работает в миллисекундах UTC — переводим без учёта зоны,
        // чтобы дата не «съезжала» на день у полуночников.
        val initialMillis = remember(value) {
            parseLocalDate(value)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        }
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onChange(date.format(format))
                    }
                    open = false
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Отмена") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}
