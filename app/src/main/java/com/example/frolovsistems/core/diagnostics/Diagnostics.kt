package com.example.frolovsistems.core.diagnostics

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Одна запись журнала: что случилось, где и когда. Stack — только у крашей
 * и неожиданных исключений; сетевые ошибки обходятся сообщением.
 */
@Serializable
data class DiagEntry(
    val time: Long,
    val level: String,   // error | warn | info
    val tag: String,     // «сеть», «краш», «пара слов откуда»
    val message: String,
    val stack: String = "",
)

/**
 * Журнал ошибок и крашей приложения. Пишется из трёх мест: сетевые сбои —
 * в apiCall, краши — через перехватчик непойманных исключений, прочее —
 * напрямую. Ошибки сразу уходят на диск, поэтому краш не стирает историю:
 * после перезапуска журнал поднимается из файла.
 *
 * Это не журнал действий пользователя — тот живёт на сервере. Здесь только
 * то, что помешало работе: причина, время и полный текст ошибки.
 */
object Diagnostics {

    private const val LIMIT = 400
    private val entries = ArrayDeque<DiagEntry>()
    private var logFile: File? = null
    private val json = Json { ignoreUnknownKeys = true }

    /** Вызывается один раз при старте приложения. */
    fun init(context: Context) {
        logFile = File(context.filesDir, "diagnostics.log")
        runCatching {
            logFile?.forEachLine { line ->
                runCatching { json.decodeFromString<DiagEntry>(line) }
                    .getOrNull()
                    ?.let { entries.addLast(it) }
            }
            while (entries.size > LIMIT) entries.removeFirst()
        }
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) =
        append("error", tag, message, throwable?.stackTraceToString().orEmpty())

    fun warn(tag: String, message: String) = append("warn", tag, message, "")

    fun info(tag: String, message: String) = append("info", tag, message, "")

    private fun append(level: String, tag: String, message: String, stack: String) {
        val entry = DiagEntry(
            time = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message.take(2000),
            stack = stack.take(8000),
        )
        entries.addLast(entry)
        while (entries.size > LIMIT) entries.removeFirst()
        // Ошибки — сразу на диск: вслед за ними приложение может упасть.
        if (level == "error") {
            runCatching { logFile?.appendText(json.encodeToString(entry) + "\n") }
        }
    }

    /** От новых к старым. */
    fun snapshot(): List<DiagEntry> = entries.toList().asReversed()

    fun clear() {
        entries.clear()
        runCatching { logFile?.delete() }
    }

    /** Весь журнал одним текстом — для «Поделиться». */
    fun shareText(): String {
        val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault())
        return buildString {
            appendLine("Журнал ошибок Фролов CRM")
            appendLine("Устройство: Android, время выгрузки: ${fmt.format(Instant.now())}")
            appendLine()
            val all = snapshot()
            if (all.isEmpty()) {
                appendLine("Записей нет.")
            } else {
                all.forEach { e ->
                    appendLine("[${fmt.format(Instant.ofEpochMilli(e.time))}] ${e.level.uppercase()} · ${e.tag}")
                    appendLine(e.message)
                    if (e.stack.isNotBlank()) appendLine(e.stack)
                    appendLine()
                }
            }
        }
    }
}
