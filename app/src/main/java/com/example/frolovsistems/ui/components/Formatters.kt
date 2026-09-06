package com.example.frolovsistems.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.frolovsistems.ui.theme.Success
import com.example.frolovsistems.ui.theme.Warning
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Формат «1 234 567 ₽» без зависимости от локали устройства.
 * На вход копейки: копейки печатаются только когда они есть, иначе
 * каждая круглая сумма тащила бы за собой бессмысленные «,00».
 */
fun formatMoney(kop: Long): String {
    val sign = if (kop < 0) "−" else ""
    val abs = if (kop < 0) -kop else kop
    val digits = groupThousands(abs / 100)
    val cents = abs % 100
    return if (cents == 0L) "$sign$digits ₽" else "$sign$digits,%02d ₽".format(cents)
}

/** Копейки в текст для поля ввода: «199999» → «1999.99», «150000» → «1500». */
fun moneyInput(kop: Long): String {
    if (kop == 0L) return ""
    val cents = kop % 100
    return if (cents == 0L) "${kop / 100}" else "${kop / 100}.%02d".format(cents)
}

/**
 * Рубли из поля ввода в копейки. Разбираем строкой, а не через Double:
 * 1999.99 в двоичной дроби не представимо точно, и последняя копейка
 * теряется ровно там, где её потом будут искать в сверке.
 */
fun parseMoneyKop(text: String): Long {
    val (whole, frac) = splitDecimal(text, fracDigits = 2)
    return whole * 100 + frac
}

/** Тысячные доли единицы в текст: 95250 → «95.25», 5000 → «5». */
fun formatQuantity(milli: Long): String {
    val sign = if (milli < 0) "−" else ""
    val abs = if (milli < 0) -milli else milli
    val frac = abs % 1000
    if (frac == 0L) return "$sign${abs / 1000}"
    return "$sign${abs / 1000}," + "%03d".format(frac).trimEnd('0')
}

/** То же для поля ввода — с точкой, как её набирают. */
fun quantityInput(milli: Long): String {
    if (milli == 0L) return ""
    val frac = milli % 1000
    if (frac == 0L) return "${milli / 1000}"
    return "${milli / 1000}." + "%03d".format(frac).trimEnd('0')
}

/** Количество из поля ввода в тысячные доли. Тем же способом, что и деньги. */
fun parseQuantityMilli(text: String): Long {
    val (whole, frac) = splitDecimal(text, fracDigits = 3)
    return whole * 1000 + frac
}

/**
 * Разбирает «1 999,50» на целую часть и дробную, приведённую к нужному
 * числу знаков. Лишние знаки отбрасываются, недостающие дописываются нулями:
 * «1999.5» с двумя знаками — это 50 копеек, а не 5.
 */
private fun splitDecimal(text: String, fracDigits: Int): Pair<Long, Long> {
    val clean = text.replace(',', '.').filter { it.isDigit() || it == '.' }
    if (clean.isBlank()) return 0L to 0L
    val parts = clean.split('.', limit = 2)
    val whole = parts[0].toLongOrNull() ?: 0L
    val frac = if (parts.size == 2 && parts[1].isNotEmpty()) {
        parts[1].padEnd(fracDigits, '0').take(fracDigits).toLongOrNull() ?: 0L
    } else {
        0L
    }
    return whole to frac
}

/** Разряды пробелами: 1234567 → «1 234 567». */
private fun groupThousands(value: Long): String =
    value.toString().reversed().chunked(3).joinToString(" ").reversed()

/** «26.08.2026» из RFC3339-строки сервера. */
fun formatShortDate(value: String): String = runCatching {
    OffsetDateTime.parse(value).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
}.getOrDefault("")

/** «1 заказ», «2 заказа», «5 заказов». */
fun pluralOrders(count: Long): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "$count заказов"
        mod10 == 1L -> "$count заказ"
        mod10 in 2..4 -> "$count заказа"
        else -> "$count заказов"
    }
}

/** «1 фото», «3 фото», «5 фотографий» — без библиотек склонения. */
fun photoCountLabel(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    val word = when {
        mod100 in 11..14 -> "фотографий"
        mod10 == 1 -> "фото"
        mod10 in 2..4 -> "фото"
        else -> "фотографий"
    }
    return "$count $word"
}

/** «26.08.2026» из строки dd.MM.yyyy или ISO; нераспознанное отдаёт null. */
fun parseLocalDate(value: String): java.time.LocalDate? {
    val text = value.trim()
    return runCatching { java.time.LocalDate.parse(text, DateTimeFormatter.ofPattern("dd.MM.yyyy")) }.getOrNull()
        ?: runCatching { java.time.LocalDate.parse(text.take(10)) }.getOrNull()
}

/** Дата из RFC3339-строки сервера («createdAt» и т.п.); null, если строки нет. */
fun dateOf(value: String): java.time.LocalDate? =
    runCatching { OffsetDateTime.parse(value).toLocalDate() }.getOrNull()

/** Срок истёк: дата в прошлом, а работа ещё не закрыта. */
fun isDeadlineOverdue(dueDate: String, status: String): Boolean {
    if (status != "new" && status != "in_progress") return false
    val date = parseLocalDate(dueDate) ?: return false
    return date.isBefore(java.time.LocalDate.now())
}

/** Статусы заказа — те же значения принимает сервер. */
val orderStatuses = listOf(
    "new" to "Новый",
    "in_progress" to "В работе",
    "done" to "Завершён",
    "canceled" to "Отменён",
)

fun orderStatusLabel(status: String): String =
    orderStatuses.firstOrNull { it.first == status }?.second ?: status

@Composable
fun orderStatusColor(status: String): Color = when (status) {
    "new" -> MaterialTheme.colorScheme.secondary
    "in_progress" -> Warning
    "done" -> Success
    else -> MaterialTheme.colorScheme.outline
}

fun requestStatusLabel(status: String): String = when (status) {
    "new" -> "Новая"
    "in_progress" -> "В работе"
    "done" -> "Обработана"
    "spam" -> "Спам"
    else -> status
}

@Composable
fun requestStatusColor(status: String): Color = when (status) {
    "new" -> MaterialTheme.colorScheme.primary
    "in_progress" -> Warning
    "done" -> Success
    else -> MaterialTheme.colorScheme.outline
}

/** Сегменты клиентов: типовая разбивка базы в CRM. */
val clientSegments = listOf(
    "VIP" to Color(0xFFD4A017),
    "Постоянный" to Color(0xFF2563EB),
    "Новый" to Color(0xFF16A34A),
    "Потенциальный" to Color(0xFF7C3AED),
    "Проблемный" to Color(0xFFDC2626),
)

/** Цвет сегмента по метке; обычный серый, если метка не из списка. */
fun clientSegmentColor(tag: String): Color =
    clientSegments.firstOrNull { it.first == tag }?.second ?: Color(0xFF64748B)
