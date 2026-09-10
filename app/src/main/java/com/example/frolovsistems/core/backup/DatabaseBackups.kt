package com.example.frolovsistems.core.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Копия базы, лежащая на телефоне. */
data class PhoneBackup(
    val uri: Uri,
    val name: String,
    val size: Long,
    /** Момент сохранения, миллисекунды. */
    val savedAt: Long,
) {
    val savedOn: LocalDate
        get() = Instant.ofEpochMilli(savedAt).atZone(ZoneId.systemDefault()).toLocalDate()
}

/**
 * Копии базы на телефоне.
 *
 * Лежат в «Загрузки/Фролов CRM» через MediaStore, а не в личном каталоге
 * приложения: из личного каталога копия исчезнет вместе с приложением —
 * ровно тогда, когда она и понадобится. В «Загрузках» файлы переживают
 * переустановку, видны в проводнике и их можно переслать куда угодно.
 *
 * Разрешений это не требует: своя запись в «Загрузки» доступна приложению
 * и без них, а чужие файлы нам и не нужны — мы управляем только своими.
 */
object DatabaseBackups {

    /** Подпапка в «Загрузках». */
    const val FOLDER = "Фролов CRM"

    /** Сколько копий держим на телефоне. */
    const val KEEP = 14

    /** Как назвать место в подсказках на экране. */
    const val LOCATION = "Загрузки/$FOLDER"

    private const val PREFIX = "frolov-"
    private const val SUFFIX = ".db.gz"
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    private val collection: Uri
        get() = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private val relativePath: String
        get() = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/"

    /** Копии, сохранённые этим приложением, — от новых к старым. */
    fun list(context: Context): List<PhoneBackup> {
        val columns = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        // Приложение видит в «Загрузках» только собственные файлы, поэтому
        // отбор по имени — не защита, а лишь способ не подобрать своё же,
        // но постороннее (например, выгруженную книгу Excel).
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%$FOLDER%", "$PREFIX%$SUFFIX")

        val out = ArrayList<PhoneBackup>()
        runCatching {
            context.contentResolver.query(
                collection, columns, selection, args,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idAt = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameAt = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeAt = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateAt = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idAt)
                    out += PhoneBackup(
                        uri = android.content.ContentUris.withAppendedId(collection, id),
                        name = cursor.getString(nameAt).orEmpty(),
                        size = cursor.getLong(sizeAt),
                        // DATE_ADDED хранится в секундах.
                        savedAt = cursor.getLong(dateAt) * 1000L,
                    )
                }
            }
        }
        return out
    }

    /** Сегодняшняя копия уже есть — при открытии приложения делать нечего. */
    fun savedToday(context: Context): Boolean {
        val newest = list(context).firstOrNull() ?: return false
        return newest.savedOn == LocalDate.now()
    }

    /**
     * Записывает копию. Пока файл пишется, он помечен как незавершённый:
     * оборванная запись не должна попасться на глаза как готовая копия
     * и тем более сойти за сегодняшнюю.
     */
    fun save(context: Context, bytes: ByteArray): PhoneBackup {
        val name = PREFIX + java.time.LocalDateTime.now().format(stamp) + SUFFIX
        val resolver = context.contentResolver

        val pending = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/gzip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, pending)
            ?: error("не удалось создать файл в $LOCATION")

        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("файл недоступен для записи")
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }

        resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)

        return PhoneBackup(uri, name, bytes.size.toLong(), System.currentTimeMillis())
    }

    /** Оставляет [keep] самых свежих копий, остальные удаляет. Вернёт, сколько убрано. */
    fun prune(context: Context, keep: Int = KEEP): Int {
        val extra = list(context).drop(keep)
        var removed = 0
        for (backup in extra) {
            val ok = runCatching { context.contentResolver.delete(backup.uri, null, null) }
                .getOrDefault(0)
            if (ok > 0) removed++
        }
        return removed
    }
}
