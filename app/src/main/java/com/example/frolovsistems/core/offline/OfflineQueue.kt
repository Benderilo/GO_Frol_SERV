package com.example.frolovsistems.core.offline

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Загрузка файла формой: бинар лежит рядом с записью очереди отдельным
 * файлом, а здесь — куда грузить, имя поля и текстовые поля той же формы
 * (подпись снимка, папка архива). При отправке форма собирается заново.
 */
@Serializable
data class UploadSpec(
    /** Куда грузить, от корня API: /api/v1/admin/orders/5/photos. */
    val path: String,
    /** Имя поля файла в форме: photo или file. */
    val fileField: String,
    val fileName: String,
    val mime: String,
    /** Текстовые поля той же формы: caption, folderId, name. */
    val fields: Map<String, String> = emptyMap(),
)

/**
 * Запись, сделанная без сети. Хранится до отправки: тем же методом,
 * по тому же адресу, с тем же телом. Создания несут отрицательный
 * [localId] — экраны показывают сущность сразу, а при отправке локальный
 * номер меняется на настоящий серверный.
 */
@Serializable
data class QueuedWrite(
    val id: Long,
    /** json — правка телом, upload — файл формой. */
    val kind: String = KIND_JSON,
    val method: String,
    val path: String,
    val body: String? = null,
    val upload: UploadSpec? = null,
    /** Отрицательный id созданной без сети сущности; null — правка существующей. */
    val localId: Long? = null,
    /**
     * Ключ идемпотентности создания: сервер с его поддержкой не создаст
     * дубль при повторе отправки. Текущий сервер ключ просто не читает —
     * от дублей страхует другое: отправка останавливается на любой
     * неоднозначной ошибке и ждёт решения человека.
     */
    val idemKey: String? = null,
    val queuedAt: Long,
    /** Последний отказ сервера; пусто, пока не пробовали или пока вышло. */
    val error: String = "",
    /**
     * Создание оборвалось на ответе: сервер мог его уже принять. Такую
     * запись отправка пропускает, пока человек не нажмёт «Повторить»
     * или не уберёт её, — иначе повтор задвоил бы платёж или заказ.
     */
    val needsReview: Boolean = false,
) {
    companion object {
        const val KIND_JSON = "json"
        const val KIND_UPLOAD = "upload"
    }
}

/**
 * Очередь всех записей без сети: создания, правки, удаления и загрузки
 * файлов. Порядок строгий — правки одной сущности обязаны уйти в том
 * порядке, в каком сделаны, — поэтому имя файла это порядковый номер.
 *
 * Создание без сети — полноценная запись: очередь выдаёт ему локальный
 * отрицательный id, экран рисует сущность как настоящую, а отправка
 * подменяет локальные номера на серверные везде, где они встретились
 * (в путях и в полях-ссылках тел последующих записей).
 */
class OfflineQueue(root: File) {

    constructor(context: Context) : this(context.filesDir)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir = File(root, "offline-queue").apply { mkdirs() }

    /** От старых к новым — в этом порядке очередь отправляется. */
    @Synchronized
    fun list(): List<QueuedWrite> =
        dir.listFiles { file -> file.extension == "json" }
            ?.sortedBy { it.name }
            ?.mapNotNull { file ->
                runCatching { json.decodeFromString<QueuedWrite>(file.readText()) }.getOrNull()
            }
            .orEmpty()

    /**
     * Правка телом. Создания (POST) получают локальный id — очередной
     * отрицательный номер, чтобы не пересечься с серверными.
     */
    @Synchronized
    fun addWrite(method: String, path: String, body: String?): QueuedWrite {
        val op = newOp(method, path, body)
        File(dir, metaName(op.id)).writeText(json.encodeToString(op))
        return op
    }

    /** Загрузка файла: бинар — рядом отдельным файлом, в метке — форма. */
    @Synchronized
    fun addUpload(spec: UploadSpec, bytes: ByteArray): QueuedWrite {
        val op = newOp("POST", spec.path, body = null).let {
            it.copy(kind = QueuedWrite.KIND_UPLOAD, upload = spec)
        }
        File(dir, binName(op.id)).writeBytes(bytes)
        File(dir, metaName(op.id)).writeText(json.encodeToString(op))
        return op
    }

    /** Бинар загрузки — для показа снимка из очереди без сети. */
    @Synchronized
    fun uploadBytes(opId: Long): ByteArray? = File(dir, binName(opId)).takeIf { it.exists() }?.readBytes()

    @Synchronized
    fun remove(id: Long) {
        File(dir, metaName(id)).delete()
        File(dir, binName(id)).delete()
    }

    /** Запоминает отказ сервера, чтобы показать в списке очереди. */
    @Synchronized
    fun setError(id: Long, error: String, needsReview: Boolean = false) {
        val file = File(dir, metaName(id))
        val op = runCatching { json.decodeFromString<QueuedWrite>(file.readText()) }.getOrNull()
            ?: return
        file.writeText(json.encodeToString(op.copy(error = error.take(300), needsReview = needsReview)))
    }

    /** «Повторить»: человек проверил сервер — запись снова уходит при отправке. */
    @Synchronized
    fun retry(id: Long) {
        val file = File(dir, metaName(id))
        val op = runCatching { json.decodeFromString<QueuedWrite>(file.readText()) }.getOrNull()
            ?: return
        file.writeText(json.encodeToString(op.copy(error = "", needsReview = false)))
    }

    /**
     * Настоящие номера созданного без сети: «локальный → серверный».
     * Живут в файле, а не в памяти отправки: клиент мог уйти на сервер,
     * а заказ для него — нет, и следующая отправка должна знать номер.
     */
    @Synchronized
    fun knownIds(): Map<Long, Long> = runCatching {
        idsFile.takeIf { it.exists() }?.readLines()?.mapNotNull { line ->
            val (local, real) = line.split(' ').takeIf { it.size == 2 } ?: return@mapNotNull null
            (local.toLongOrNull() ?: return@mapNotNull null) to (real.toLongOrNull() ?: return@mapNotNull null)
        }?.toMap()
    }.getOrNull().orEmpty()

    @Synchronized
    fun rememberId(local: Long, real: Long) {
        // Локальные номера не повторяются, поэтому старые пары можно
        // отбрасывать: тысячи с запасом хватает на любые незакрытые экраны.
        // Номера идут вниз: −1, −2… — самые свежие пары имеют наименьший ключ.
        val all = (knownIds() + (local to real)).entries.sortedBy { it.key }.take(1000)
        idsFile.writeText(all.joinToString("\n") { "${it.key} ${it.value}" })
    }

    private fun newOp(method: String, path: String, body: String?): QueuedWrite {
        val (opSeq, localSeq) = readCounters()
        // Номера только растут, даже когда очередь опустела: иначе новый
        // снимок получил бы адрес offline://1 старого, а новый клиент —
        // номер −1, на который ещё ссылается застрявшая правка.
        val id = maxOf(opSeq, list().maxOfOrNull { it.id } ?: 0L) + 1L
        val localId = if (method == "POST") {
            minOf(localSeq, list().mapNotNull { it.localId }.minOrNull() ?: 0L, knownIds().keys.minOrNull() ?: 0L) - 1L
        } else {
            null
        }
        writeCounters(id, localId ?: localSeq)
        return QueuedWrite(
            id = id,
            kind = QueuedWrite.KIND_JSON,
            method = method,
            path = path,
            body = body,
            localId = localId,
            idemKey = if (method == "POST") UUID.randomUUID().toString() else null,
            queuedAt = System.currentTimeMillis(),
        )
    }

    private val countersFile = File(dir, "counters.txt")
    private val idsFile = File(dir, "ids.txt")

    /** Последний выданный номер записи и последний локальный (отрицательный) номер. */
    private fun readCounters(): Pair<Long, Long> = runCatching {
        val (op, local) = countersFile.readText().trim().split(' ')
        op.toLong() to local.toLong()
    }.getOrDefault(0L to 0L)

    private fun writeCounters(opSeq: Long, localSeq: Long) {
        runCatching { countersFile.writeText("$opSeq $localSeq") }
    }

    /** Локаль корня: имена файлов должны сортироваться одинаково на любом телефоне. */
    private fun metaName(id: Long) = String.format(Locale.ROOT, "%08d.json", id)
    private fun binName(id: Long) = String.format(Locale.ROOT, "%08d.bin", id)
}

/**
 * Человекочитаемое описание записи — для списка очереди в настройках.
 * Адреса API знает только это место: экранам достаются готовые фразы.
 */
fun QueuedWrite.describe(): String {
    if (kind == QueuedWrite.KIND_UPLOAD) {
        val what = when {
            path.contains("/site/works") -> "фото для витрины сайта"
            path.contains("/orders/") -> "фото заказа"
            path.contains("/files") -> "файл в архив"
            else -> "файл"
        }
        return "Загрузка: $what"
    }
    val tail = path.removePrefix("/api/v1/admin/")
    val parts = tail.split('/')
    val first = parts.firstOrNull().orEmpty()
    val id = parts.getOrNull(1)
    val last = parts.lastOrNull().orEmpty()

    val what = when {
        // Создание по «голому» адресу семейства: POST /clients, POST /orders…
        parts.size == 1 -> when (first) {
            "clients" -> "клиент"
            "orders" -> "заказ"
            "tasks" -> "задача"
            "workers" -> "рабочий"
            "catalog" -> "позиция склада"
            "cash" -> "операция кассы"
            "documents" -> "документ"
            "folders" -> "папка архива"
            "files" -> "файл архива"
            else -> tail
        }
        tail == "site" -> "контент сайта"
        tail == "company" -> "реквизиты ИП"
        tail == "payroll/settings" -> "настройки зарплат"
        tail == "cash/transfer" -> "перевод между счетами"
        last == "writeoff" -> "списание материалов заказа №$id"
        last == "issue" -> "проведение документа №$id"
        last == "annul" -> "аннулирование документа №$id"
        last == "move" -> "порядок фото витрины"
        last == "payout" -> "выплата рабочему №$id"
        last == "days" -> "рабочий день $last"
        last == "done" -> "отметка задачи №$id"
        last == "access" -> "доступ клиента в кабинет"
        tail.startsWith("payroll/dates") -> "даты выплат за ${parts.last()}"
        parts.firstOrNull() == "clients" && parts.size == 2 -> "клиент №$id"
        parts.firstOrNull() == "clients" && parts.size == 3 -> "доступ клиента в кабинет"
        parts.firstOrNull() == "orders" && parts.size == 2 -> "заказ №$id"
        parts.firstOrNull() == "orders" && last == "items" -> "строка состава заказа №$id"
        parts.firstOrNull() == "orders" && last == "payments" -> "платёж по заказу №$id"
        parts.firstOrNull() == "orders" && last == "photos" -> "фото заказа №$id"
        parts.firstOrNull() == "order-items" -> "строка состава заказа №$id"
        parts.firstOrNull() == "requests" -> "заявка №$id"
        parts.firstOrNull() == "tasks" -> "задача №$id"
        parts.firstOrNull() == "workers" && parts.size >= 4 -> "рабочий день ${parts.last()}"
        parts.firstOrNull() == "workers" -> "рабочий №$id"
        parts.firstOrNull() == "catalog" && last == "stock" -> "движение склада №$id"
        parts.firstOrNull() == "catalog" -> "позиция склада №$id"
        parts.firstOrNull() == "cash" -> "операция кассы №$id"
        parts.firstOrNull() == "payments" -> "платёж по заказу №$id"
        parts.firstOrNull() == "photos" -> if (method == "DELETE") "фото заказа" else "подпись фото"
        parts.firstOrNull() == "site" -> "фото витрины"
        parts.firstOrNull() == "payroll" && parts.getOrNull(1) == "payments" -> "выплата зарплаты №${parts.last()}"
        parts.firstOrNull() == "stock" -> "движение склада №$id"
        parts.firstOrNull() == "folders" -> "папка архива"
        parts.firstOrNull() == "files" -> "файл архива"
        parts.firstOrNull() == "documents" -> "документ №$id"
        else -> tail
    }
    return when (method) {
        "POST" -> "Создание: $what"
        "DELETE" -> "Удаление: $what"
        "PATCH" -> "Изменение: $what"
        else -> "Изменение: $what"
    }
}
