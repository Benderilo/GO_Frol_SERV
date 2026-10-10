package com.example.frolovsistems.core.offline

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Имена сущностей для «оживления» ответов, созданных без сети: заказ,
 * собранный офлайн, должен показывать имя клиента сразу, а не после
 * отправки. Индекс пополняется из удачных списков «клиенты/рабочие/
 * заказы/склад» и живёт в кеше рядом с данными.
 */
@Serializable
data class NamesIndex(
    val clients: Map<Long, String> = emptyMap(),
    val workers: Map<Long, String> = emptyMap(),
    val orders: Map<Long, String> = emptyMap(),
    val catalog: Map<Long, String> = emptyMap(),
)

/**
 * «Фабрика» офлайн-ответов. Экраны приложения ждут от сервера готовые
 * объекты: созданный клиент, обновлённую задачу, список с итогами.
 * Когда сети нет, ответы собираются здесь — из тела самого запроса,
 * из сохранённого кеша и из индекса имён. Экраны не отличают такой
 * ответ от настоящего: у созданного есть отрицательный id, у правки —
 * свежие поля, у списка — ждущие отправки записи.
 *
 * Здесь же список GET-адресов учитывает очередь: список из кеша
 * дополняется созданным, правки вписываются в свои строки, удалённое
 * исчезает. Поэтому без сети приложение показывает то же, что увидит
 * сервер после отправки очереди.
 */
object OfflineFabricate {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneId.of("UTC"))

    private fun now(): String = stampFormat.format(Instant.now())

    // ------------------------- Распознавание записи ---------------------------

    /** Что за запись уходит на сервер — по методу и адресу. */
    sealed interface Shape {
        val family: String

        data class Create(override val family: String) : Shape
        data class Update(override val family: String, val id: Long?) : Shape
        data class Delete(override val family: String, val id: Long) : Shape
        data class Upload(override val family: String) : Shape
        /** Составные действия: проведение, аннулирование, выплата, перевод. */
        data class Special(override val family: String, val action: String, val arg: String) : Shape
    }

    fun classify(method: String, rawPath: String): Shape? {
        if (!rawPath.startsWith("/api/v1/admin/")) return null
        val tail = rawPath.removePrefix("/api/v1/admin/")
        val p = tail.split('/')
        val first = p.getOrNull(0).orEmpty()
        val last = p.lastOrNull().orEmpty()
        val id = p.getOrNull(1)?.toLongOrNull()

        return when {
            tail == "company" && method == "PUT" -> Shape.Update("company", null)
            tail == "site" && method == "PUT" -> Shape.Update("site", null)
            tail == "cash/transfer" -> Shape.Special("cash", "transfer", "")
            tail == "payroll/settings" -> Shape.Update("payroll-settings", null)
            tail.startsWith("payroll/dates/") ->
                Shape.Special("payroll", "dates", p.lastOrNull().orEmpty())
            tail.startsWith("payroll/payments") -> when (method) {
                "POST" -> Shape.Create("salary-payments")
                "PUT" -> Shape.Update("salary-payments", id)
                "DELETE" -> id?.let { Shape.Delete("salary-payments", it) }
                else -> null
            }

            first == "clients" -> when {
                p.getOrNull(2) == "access" -> Shape.Update("client-access", id)
                p.getOrNull(2) == "merge" -> null
                method == "POST" -> Shape.Create("clients")
                method == "PUT" -> Shape.Update("clients", id)
                method == "DELETE" -> id?.let { Shape.Delete("clients", it) }
                else -> null
            }

            first == "orders" -> when {
                last == "items" && method == "POST" -> Shape.Create("order-items")
                last == "payments" && method == "POST" -> Shape.Create("payments")
                last == "photos" && method == "POST" -> Shape.Upload("photos")
                last == "writeoff" -> Shape.Special("orders", "writeoff", id.toString())
                method == "POST" -> Shape.Create("orders")
                method == "PUT" -> Shape.Update("orders", id)
                method == "DELETE" -> id?.let { Shape.Delete("orders", it) }
                else -> null
            }

            first == "order-items" -> when (method) {
                "PUT" -> Shape.Update("order-items", id)
                "DELETE" -> id?.let { Shape.Delete("order-items", it) }
                else -> null
            }

            first == "requests" -> when {
                p.size == 3 -> null // заказ по заявке — только с сетью
                method == "PATCH" -> Shape.Update("requests", id)
                method == "DELETE" -> id?.let { Shape.Delete("requests", it) }
                else -> null
            }

            first == "tasks" -> when {
                method == "POST" -> Shape.Create("tasks")
                // Отметка «готово» уходит PATCH, полная правка — PUT: обе меняют задачу.
                method == "PUT" || method == "PATCH" -> Shape.Update("tasks", id)
                method == "DELETE" -> id?.let { Shape.Delete("tasks", it) }
                else -> null
            }

            first == "workers" -> when {
                p.getOrNull(2) == "days" ->
                    if (method == "DELETE") id?.let { Shape.Delete("workdays", it) }
                    else Shape.Update("workdays", id)
                last == "payout" -> Shape.Special("cash", "payout", id.toString())
                method == "POST" -> Shape.Create("workers")
                method == "PUT" -> Shape.Update("workers", id)
                method == "DELETE" -> id?.let { Shape.Delete("workers", it) }
                else -> null
            }

            first == "catalog" -> when {
                last == "stock" && method == "POST" -> Shape.Create("stock")
                method == "POST" -> Shape.Create("catalog")
                method == "PUT" -> Shape.Update("catalog", id)
                method == "DELETE" -> id?.let { Shape.Delete("catalog", it) }
                else -> null
            }

            first == "cash" -> when (method) {
                "POST" -> Shape.Create("cash")
                "DELETE" -> id?.let { Shape.Delete("cash", it) }
                else -> null
            }

            first == "payments" && method == "DELETE" ->
                id?.let { Shape.Delete("payments", it) }

            first == "photos" -> when (method) {
                "PATCH" -> Shape.Update("photos", id)
                "DELETE" -> id?.let { Shape.Delete("photos", it) }
                else -> null
            }

            first == "site" -> when {
                tail == "site/works" && method == "POST" -> Shape.Upload("site-works")
                tail == "site/reset" -> null
                p.getOrNull(1) == "works" && last == "move" ->
                    Shape.Special("site-works", "move", id.toString())
                p.getOrNull(1) == "works" -> when (method) {
                    "PATCH" -> Shape.Update("site-works", id)
                    "DELETE" -> id?.let { Shape.Delete("site-works", it) }
                    else -> null
                }

                else -> null
            }

            first == "documents" -> when {
                last == "issue" -> Shape.Special("documents", "issue", id.toString())
                last == "annul" -> Shape.Special("documents", "annul", id.toString())
                last == "order" || last == "send-max" -> null
                method == "POST" -> Shape.Create("documents")
                // Черновик правится PATCH (так шлёт ApiClient.updateDocument).
                method == "PUT" || method == "PATCH" -> Shape.Update("documents", id)
                method == "DELETE" -> id?.let { Shape.Delete("documents", it) }
                else -> null
            }

            first == "folders" -> when (method) {
                "POST" -> Shape.Create("folders")
                "PUT" -> Shape.Update("folders", id)
                "DELETE" -> id?.let { Shape.Delete("folders", it) }
                else -> null
            }

            first == "files" -> when {
                method == "POST" -> Shape.Upload("files")
                method == "PUT" -> Shape.Update("files", id)
                method == "DELETE" -> id?.let { Shape.Delete("files", it) }
                else -> null
            }

            first == "stock" && method == "DELETE" ->
                id?.let { Shape.Delete("stock", it) }

            else -> null
        }
    }

    // --------------------------- Синтез ответа --------------------------------

    /**
     * Ответ на запись без сети: экран получает «как будто сервер ответил».
     * Создания собираются из тела запроса, правки — из кеша, сложные
     * действия — из того и другого.
     */
    fun responseFor(op: QueuedWrite, cache: OfflineCache): String {
        val shape = classify(op.method, op.path) ?: return "{}"
        val names = cache.names
        return when (shape) {
            is Shape.Create -> createEntity(op, shape, names).toString()
            is Shape.Upload -> uploadEntity(op, shape, names).toString()
            is Shape.Delete -> "{}"
            is Shape.Update -> updateEntity(op, shape, cache, names).toString()
            is Shape.Special -> specialEntity(op, shape, cache, names).toString()
        }
    }

    /** Основа ответа: тело запроса как объект. */
    private fun bodyObject(op: QueuedWrite): JsonObject =
        op.body?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())

    private fun JsonObject.withId(id: Long?): JsonObject {
        val map = toMutableMap()
        if (id != null) map["id"] = JsonPrimitive(id)
        return JsonObject(map)
    }

    private fun JsonObject.withStamps(): JsonObject = JsonObject(
        toMutableMap().apply {
            putIfAbsent("createdAt", JsonPrimitive(now()))
            putIfAbsent("updatedAt", JsonPrimitive(now()))
        },
    )

    private fun clientName(names: NamesIndex, id: Long?): String = id?.let { names.clients[it] }.orEmpty()
    private fun workerName(names: NamesIndex, id: Long?): String = id?.let { names.workers[it] }.orEmpty()
    private fun orderTitle(names: NamesIndex, id: Long?): String = id?.let { names.orders[it] }.orEmpty()

    private fun longField(obj: JsonObject, field: String): Long? =
        (obj[field] as? JsonPrimitive)?.longOrNull

    private fun stringField(obj: JsonObject, field: String): String =
        (obj[field] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.with(field: String, value: String): JsonObject {
        if (value.isBlank()) return this
        return JsonObject(toMutableMap().apply { put(field, JsonPrimitive(value)) })
    }

    private fun JsonObject.with(field: String, value: Long): JsonObject =
        JsonObject(toMutableMap().apply { put(field, JsonPrimitive(value)) })

    private fun createEntity(op: QueuedWrite, shape: Shape.Create, names: NamesIndex): JsonObject {
        var out = bodyObject(op).withId(op.localId)
        when (shape.family) {
            "orders" -> out = out
                .with("clientName", clientName(names, longField(out, "clientId")))
                .with("workerName", workerName(names, longField(out, "workerId")))

            "cash" -> out = out.with("clientName", clientName(names, longField(out, "clientId")))

            "order-items" -> {
                val qty = longField(out, "qtyMilli") ?: 1000L
                val price = longField(out, "priceKop") ?: 0L
                val cost = longField(out, "costKop") ?: 0L
                out = out
                    .with("orderId", op.path.split("/").getOrNull(5)?.toLongOrNull() ?: 0L)
                    .with("totalKop", qty * price / 1000)
                    .with("totalCostKop", qty * cost / 1000)
            }

            "payments" -> out = out.with(
                "orderId",
                op.path.split("/").getOrNull(5)?.toLongOrNull() ?: 0L,
            )

            "documents" -> {
                val lines = (out["lines"] as? JsonArray)
                val total = lines?.sumOf { line ->
                    val l = line as? JsonObject ?: return@sumOf 0L
                    val qty = longField(l, "qtyMilli") ?: 0L
                    val price = longField(l, "priceKop") ?: 0L
                    qty * price / 1000
                } ?: 0L
                out = out
                    .with("totalKop", total)
                    .with("clientName", clientName(names, longField(out, "clientId")))
                    .with("orderTitle", orderTitle(names, longField(out, "orderId")))
                    .with("kindTitle", kindTitle(stringField(out, "kind")))
                    .with("status", "draft")
            }

            "stock" -> out = out
                .with("itemId", op.path.split("/").getOrNull(5)?.toLongOrNull() ?: 0L)
                .with("itemName", op.path.split("/").getOrNull(5)?.toLongOrNull()?.let { names.catalog[it] }.orEmpty())

            "salary-payments" -> out = out.with(
                "workerName",
                workerName(names, longField(out, "workerId")),
            )

            "tasks" -> out = out.with("clientName", clientName(names, longField(out, "clientId")))
        }
        return out.withStamps()
    }

    private fun uploadEntity(op: QueuedWrite, shape: Shape.Upload, names: NamesIndex): JsonObject {
        val spec = op.upload ?: return JsonObject(emptyMap())
        val orderId = op.path.split("/").getOrNull(5)?.toLongOrNull() ?: 0L
        val local = "offline://${op.id}"
        return when (shape.family) {
            "photos" -> JsonObject(buildMap {
                put("id", JsonPrimitive(op.localId))
                put("orderId", JsonPrimitive(orderId))
                put("token", JsonPrimitive(local))
                put("mime", JsonPrimitive(spec.mime))
                put("size", JsonPrimitive(0L))
                put("caption", JsonPrimitive(spec.fields["caption"].orEmpty()))
                put("createdAt", JsonPrimitive(now()))
                put("url", JsonPrimitive(local))
                put("thumbUrl", JsonPrimitive(local))
            })

            "site-works" -> JsonObject(buildMap {
                put("id", JsonPrimitive(op.localId))
                put("orderId", JsonPrimitive(0L))
                put("token", JsonPrimitive(local))
                put("mime", JsonPrimitive(spec.mime))
                put("size", JsonPrimitive(0L))
                put("caption", JsonPrimitive(spec.fields["caption"].orEmpty()))
                put("createdAt", JsonPrimitive(now()))
                put("url", JsonPrimitive(local))
                put("thumbUrl", JsonPrimitive(local))
            })

            else -> JsonObject(buildMap {
                put("id", JsonPrimitive(op.localId))
                spec.fields["folderId"]?.toLongOrNull()?.let { put("folderId", JsonPrimitive(it)) }
                put("name", JsonPrimitive(spec.fields["name"] ?: spec.fileName))
                put("mime", JsonPrimitive(spec.mime))
                put("size", JsonPrimitive(0L))
                put("createdAt", JsonPrimitive(now()))
            })
        }
    }

    /** Правка: берём сущность из кеша и накладываем тело запроса. */
    private fun updateEntity(op: QueuedWrite, shape: Shape.Update, cache: OfflineCache, names: NamesIndex): JsonObject {
        val id = shape.id ?: op.localId
        return when (shape.family) {
            "workdays" -> JsonObject(buildMap {
                put("id", JsonPrimitive(id))
                put("workerId", JsonPrimitive(id))
                put("workerName", JsonPrimitive(workerName(names, id)))
                put("workDate", JsonPrimitive(op.path.split("/").lastOrNull().orEmpty()))
                put("note", JsonPrimitive(stringField(bodyObject(op), "note")))
            })

            "site" -> bodyObject(op).withStamps()
            "company" -> bodyObject(op).withStamps()
            "payroll-settings" -> bodyObject(op)
            "client-access" -> JsonObject(emptyMap())

            else -> {
                val cached = findCachedEntity(cache, shape.family, id)
                val base = cached ?: createEntity(
                    op,
                    Shape.Create(shape.family),
                    names,
                ).withId(id)
                mergePatch(base, bodyObject(op)).withStamps()
            }
        }
    }

    private fun specialEntity(op: QueuedWrite, shape: Shape.Special, cache: OfflineCache, names: NamesIndex): JsonObject {
        val arg = shape.arg.toLongOrNull()
        return when (shape.family to shape.action) {
            "cash" to "transfer" -> bodyObject(op).let {
                JsonObject(buildMap {
                    put("id", JsonPrimitive(op.localId))
                    put("direction", JsonPrimitive("out"))
                    put("amountKop", JsonPrimitive(longField(it, "amountKop") ?: 0L))
                    put("method", JsonPrimitive(stringField(it, "from")))
                    // Перевод не меняет итогов кассы — по этой метке его и пропускаем.
                    put("category", JsonPrimitive(TRANSFER))
                    put("note", JsonPrimitive(stringField(it, "note")))
                    put("createdAt", JsonPrimitive(now()))
                })
            }

            "cash" to "payout" -> bodyObject(op).let {
                JsonObject(buildMap {
                    put("id", JsonPrimitive(op.localId))
                    put("direction", JsonPrimitive("out"))
                    put("amountKop", JsonPrimitive(longField(it, "amountKop") ?: 0L))
                    put("method", JsonPrimitive("cash"))
                    put("category", JsonPrimitive("salary"))
                    put("note", JsonPrimitive("Выплата за ${stringField(it, "month")}"))
                    put("createdAt", JsonPrimitive(now()))
                })
            }

            "orders" to "writeoff" -> JsonObject(buildMap {
                put("written", JsonPrimitive(0))
                put("items", JsonArray(emptyList()))
            })

            "documents" to "issue", "documents" to "annul" -> {
                val status = if (shape.action == "issue") "issued" else "annulled"
                findCachedEntity(cache, "documents", arg)
                    ?.let { mergePatch(it, JsonObject(mapOf("status" to JsonPrimitive(status)))) }
                    ?: JsonObject(
                        buildMap {
                            put("id", JsonPrimitive(arg))
                            put("status", JsonPrimitive(status))
                        },
                    )
            }

            "site-works" to "move" -> cachedSiteWorks(cache)

            "payroll" to "dates" -> bodyObject(op).let {
                JsonObject(buildMap {
                    put("month", JsonPrimitive(shape.arg))
                    put("advanceDate", JsonPrimitive(stringField(it, "advanceDate")))
                    put("salaryDate", JsonPrimitive(stringField(it, "salaryDate")))
                    put("datesCustom", JsonPrimitive(true))
                })
            }

            else -> JsonObject(emptyMap())
        }
    }

    private fun kindTitle(kind: String): String = when (kind) {
        "invoice" -> "Счёт"
        "act" -> "Акт"
        "estimate" -> "Смета"
        "waybill" -> "Накладная"
        "upd" -> "УПД"
        "reconciliation" -> "Акт сверки"
        else -> kind
    }

    /** Наложение правки: поля тела заменяют поля кеша, остальное живёт дальше. */
    private fun mergePatch(base: JsonObject, patch: JsonObject): JsonObject {
        val map = base.toMutableMap()
        patch.forEach { (k, v) -> if (v !is JsonPrimitive || v.contentOrNull != null || v.isString) map[k] = v }
        return JsonObject(map)
    }

    // ---------------- Списки из кеша с учётом очереди -------------------------

    /**
     * Ответ списка из кеша, в который вписаны ждущие отправки записи:
     * созданное дописано, правки наложены, удалённое убрано. Так список
     * без сети совпадает с тем, что окажется на сервере после отправки.
     */
    fun patchGetResponse(getPath: String, cachedBytes: ByteArray, queue: List<QueuedWrite>, cache: OfflineCache): ByteArray {
        val root = runCatching { json.parseToJsonElement(cachedBytes.decodeToString()).jsonObject }
            .getOrNull() ?: return cachedBytes
        val family = familyOfGet(getPath) ?: return cachedBytes
        val ops = queue.mapNotNull { op -> classify(op.method, op.path)?.let { op to it } }
        val names = cache.names
        val patched = when (family) {
            "files" -> patchFolderListing(root, ops, names)
            "accounts" -> patchAccounts(root, ops)
            "payroll" -> patchPayroll(root, getPath, ops, names, cache)
            "documents-card" -> patchDocumentCard(root, getPath, ops)
            "cash" -> patchCashList(root, getPath, ops, names, cache)
            else -> patchItemsList(root, getPath, family, ops, names, cache)
        } ?: return cachedBytes
        return patched.toString().encodeToByteArray()
    }

    /** Семейство GET-адреса — какие записи очереди к нему относятся. */
    private fun familyOfGet(path: String): String? {
        if (!path.contains("/api/v1/")) return null
        val tail = path.removePrefix(if (path.startsWith("/api/v1/admin/")) "/api/v1/admin/" else "/api/v1/")
        val p = tail.substringBefore('?').split('/')
        return when {
            p.getOrNull(0) == "admin" && p.size == 1 -> null
            tail.startsWith("clients") && p.getOrNull(2) == "orders" -> "orders-of-client"
            tail.startsWith("clients") -> "clients"
            tail.startsWith("orders") && p.getOrNull(2) == "items" -> "order-items"
            tail.startsWith("orders") && p.getOrNull(2) == "payments" -> "payments"
            tail.startsWith("orders") && p.getOrNull(2) == "photos" -> "photos"
            tail.startsWith("orders") -> "orders"
            tail.startsWith("requests") -> "requests"
            tail.startsWith("tasks") -> "tasks"
            tail.startsWith("workers") -> "workers"
            tail.startsWith("workdays") -> "workdays"
            tail.startsWith("catalog") -> "catalog"
            tail.startsWith("cash") -> "cash"
            tail.startsWith("accounts") -> "accounts"
            tail.startsWith("stock") -> "stock"
            tail.startsWith("documents") && p.size > 1 -> "documents-card"
            tail.startsWith("documents") -> "documents"
            tail.startsWith("site/works") -> "site-works"
            tail.startsWith("files") -> "files"
            tail.startsWith("payroll") -> "payroll"
            else -> null
        }
    }

    /** Простой список {items:[…], count} одного семейства. */
    private fun patchItemsList(
        root: JsonObject,
        getPath: String,
        family: String,
        ops: List<Pair<QueuedWrite, Shape>>,
        names: NamesIndex,
        cache: OfflineCache,
    ): JsonObject? {
        val items = (root["items"] as? JsonArray) ?: return null
        val list = items.mapNotNull { it as? JsonObject }.toMutableList()
        val query = getPath.substringAfter('?', "")

        ops.filter { (op, shape) -> shape.family == family || belongsToList(family, shape) }
            .forEach { (op, shape) ->
                when (shape) {
                    is Shape.Delete -> list.removeAll { longField(it, "id") == shape.id }
                    is Shape.Update -> {
                        val id = shape.id
                        val at = list.indexOfFirst { longField(it, "id") == id }
                        if (at >= 0) {
                            list[at] = mergePatch(list[at], bodyObject(op)).withStamps()
                        } else if (id != null && id < 0) {
                            list.add(updateEntity(op, shape, cache, names))
                        }
                    }

                    is Shape.Create -> {
                        if (matchesFilter(query, op) && sameParent(family, getPath, op)) {
                            list.add(0, createEntity(op, shape, names))
                        }
                    }

                    is Shape.Upload -> if (matchesFilter(query, op) && sameParent(family, getPath, op)) {
                        list.add(0, uploadEntity(op, shape, names))
                    }

                    is Shape.Special -> if (belongsToList(family, shape)) {
                        list.add(0, specialEntity(op, shape, cache, names))
                    }
                }
            }
        val map = root.toMutableMap()
        map["items"] = JsonArray(list)
        map["count"] = JsonPrimitive(list.size)
        return JsonObject(map)
    }

    /**
     * Вложенные списки — строки, платежи и фото заказа, движения позиции
     * склада: созданное попадает только в список своего родителя, а не
     * во все заказы подряд.
     */
    private fun sameParent(family: String, getPath: String, op: QueuedWrite): Boolean {
        val opParent = op.path.split('/').getOrNull(5)
        return when (family) {
            "order-items", "payments", "photos" ->
                getPath.substringBefore('?').split('/').getOrNull(5) == opParent
            "stock" -> {
                val item = queryParam(getPath, "itemId")
                item.isBlank() || item == opParent
            }
            else -> true
        }
    }

    private fun queryParam(getPath: String, key: String): String =
        getPath.substringAfter('?', "").split('&')
            .firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "").orEmpty()

    /** Чужие семейства, которые всё равно попадают в этот список. */
    private fun belongsToList(family: String, shape: Shape): Boolean = when (family) {
        "cash" -> shape is Shape.Special && (shape.action == "payout" || shape.action == "transfer")
        else -> false
    }

    /**
     * Фильтр вкладки: сервер уже отфильтровал список, и новая запись
     * попадает в него, только если проходит тот же фильтр (status, kind).
     * Неизвестные фильтры не мешают — запись дописывается.
     */
    private fun matchesFilter(query: String, op: QueuedWrite): Boolean {
        if (op.kind == QueuedWrite.KIND_UPLOAD) return true
        val body = bodyObject(op)
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            if (value.isBlank()) continue
            val field = when (key) {
                "status", "kind", "direction", "clientId", "orderId" -> key
                else -> continue
            }
            val actual = (body[field] as? JsonPrimitive)?.contentOrNull ?: return true
            if (actual != value) return false
        }
        return true
    }

    /** Касса: список и итоги — приход, расход и остаток двигаются сразу. */
    private fun patchCashList(root: JsonObject, getPath: String, ops: List<Pair<QueuedWrite, Shape>>, names: NamesIndex, cache: OfflineCache): JsonObject? {
        val patched = patchItemsList(root, getPath, "cash", ops, names, cache) ?: return null
        val items = (patched["items"] as? JsonArray) ?: return patched
        var income = (patched["incomeKop"] as? JsonPrimitive)?.longOrNull ?: 0L
        var expense = (patched["expenseKop"] as? JsonPrimitive)?.longOrNull ?: 0L
        var balance = (patched["balanceKop"] as? JsonPrimitive)?.longOrNull ?: 0L

        // Сервер уже посчитал итоги по своему списку — двигаем их только
        // на разницу: дописанное очередью прибавляем, удалённое вычитаем.
        fun shift(o: JsonObject, sign: Int) {
            if (stringField(o, "category") == TRANSFER) return
            val amount = (longField(o, "amountKop") ?: 0L) * sign
            if (stringField(o, "direction") == "in") {
                income += amount
                balance += amount
            } else {
                expense += amount
                balance -= amount
            }
        }
        val before = (root["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val beforeIds = before.mapNotNull { longField(it, "id") }.toSet()
        val afterObjs = items.mapNotNull { it as? JsonObject }
        val afterIds = afterObjs.mapNotNull { longField(it, "id") }.toSet()
        afterObjs.filter { longField(it, "id") !in beforeIds }.forEach { shift(it, +1) }
        before.filter { longField(it, "id") !in afterIds }.forEach { shift(it, -1) }
        return JsonObject(
            patched.toMutableMap().apply {
                put("incomeKop", JsonPrimitive(income))
                put("expenseKop", JsonPrimitive(expense))
                put("balanceKop", JsonPrimitive(balance))
            },
        )
    }

    /** Балансы счетов: ждущие операции денег двигают и их. */
    private fun patchAccounts(root: JsonObject, ops: List<Pair<QueuedWrite, Shape>>): JsonObject? {
        val items = (root["items"] as? JsonArray) ?: return null
        val balances = items.mapNotNull { it as? JsonObject }
            .associateBy({ stringField(it, "method") }, { it.toMutableMap() }).toMutableMap()
        var total = (root["balanceKop"] as? JsonPrimitive)?.longOrNull ?: 0L

        ops.forEach { (op, shape) ->
            val movement: Triple<Long, String, String>? = when {
                shape is Shape.Special && shape.action == "payout" ->
                    Triple(longField(bodyObject(op), "amountKop") ?: 0L, "out", "cash")

                shape is Shape.Special && shape.action == "transfer" -> {
                    val b = bodyObject(op)
                    val sum = longField(b, "amountKop") ?: 0L
                    adjust(balances, stringField(b, "from"), -sum)
                    adjust(balances, stringField(b, "to"), sum)
                    null // обе половины учтены выше
                }

                shape is Shape.Create && shape.family == "cash" -> {
                    val b = bodyObject(op)
                    Triple(
                        longField(b, "amountKop") ?: 0L,
                        stringField(b, "direction"),
                        stringField(b, "method"),
                    )
                }

                else -> null
            }
            if (movement != null) {
                val (sum, direction, method) = movement
                val signed = if (direction == "in") sum else -sum
                adjust(balances, method, signed)
                total += signed
            }
        }
        val map = root.toMutableMap()
        map["items"] = JsonArray(balances.values.map { JsonObject(it) })
        map["balanceKop"] = JsonPrimitive(total)
        return JsonObject(map)
    }

    private fun adjust(balances: MutableMap<String, MutableMap<String, JsonElement>>, method: String, signed: Long) {
        val row = balances.getOrPut(method) { mutableMapOf("method" to JsonPrimitive(method)) }
        if (signed >= 0) {
            row["inKop"] = JsonPrimitive(((row["inKop"] as? JsonPrimitive)?.longOrNull ?: 0L) + signed)
        } else {
            row["outKop"] = JsonPrimitive(((row["outKop"] as? JsonPrimitive)?.longOrNull ?: 0L) - signed)
        }
        row["balanceKop"] = JsonPrimitive(
            ((row["inKop"] as? JsonPrimitive)?.longOrNull ?: 0L) -
                ((row["outKop"] as? JsonPrimitive)?.longOrNull ?: 0L),
        )
    }

    /** Ведомость: созданные офлайн выплаты дописываются в строки рабочих. */
    private fun patchPayroll(root: JsonObject, getPath: String, ops: List<Pair<QueuedWrite, Shape>>, names: NamesIndex, cache: OfflineCache): JsonObject? {
        val rows = (root["rows"] as? JsonArray) ?: return null
        val month = getPath.substringAfter("month=", "").substringBefore('&')
        val out = rows.map { row ->
            val r = row as? JsonObject ?: return@map row
            val workerId = longField(r, "workerId")
            val payments = (r["payments"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.toMutableList()
                ?: mutableListOf()
            ops.forEach { (op, shape) ->
                when {
                    shape is Shape.Create && shape.family == "salary-payments" &&
                        longField(bodyObject(op), "workerId") == workerId ->
                        payments.add(0, createEntity(op, shape, names))

                    shape is Shape.Update && shape.family == "salary-payments" -> {
                        val at = payments.indexOfFirst { longField(it, "id") == shape.id }
                        if (at >= 0) payments[at] = mergePatch(payments[at], bodyObject(op))
                    }

                    shape is Shape.Delete && shape.family == "salary-payments" ->
                        payments.removeAll { longField(it, "id") == shape.id }

                    else -> Unit
                }
            }
            JsonObject(r.toMutableMap().apply { put("payments", JsonArray(payments)) })
        }
        val map = root.toMutableMap()
        // Перенесённые даты выплат месяца — прямо в ведомость.
        ops.firstOrNull { (_, shape) ->
            shape is Shape.Special && shape.action == "dates" && shape.arg == month
        }?.let { (op, _) ->
            val b = bodyObject(op)
            map["advanceDate"] = JsonPrimitive(stringField(b, "advanceDate"))
            map["salaryDate"] = JsonPrimitive(stringField(b, "salaryDate"))
            map["datesCustom"] = JsonPrimitive(true)
        }
        map["rows"] = JsonArray(out)
        return JsonObject(map)
    }

    /** Карточка документа из кеша: правки и проведение вписываются в неё. */
    private fun patchDocumentCard(root: JsonObject, getPath: String, ops: List<Pair<QueuedWrite, Shape>>): JsonObject {
        val id = getPath.substringAfter("/documents/").substringBefore('?').toLongOrNull()
        var card = root
        ops.forEach { (op, shape) ->
            val opId = when (shape) {
                is Shape.Update -> shape.id
                is Shape.Special -> shape.arg.toLongOrNull()
                else -> null
            }
            if (opId == id) {
                when (shape) {
                    is Shape.Update -> card = mergePatch(card, bodyObject(op))
                    is Shape.Special -> when (shape.action) {
                        "issue" -> card = mergePatch(card, JsonObject(mapOf("status" to JsonPrimitive("issued"))))
                        "annul" -> card = mergePatch(card, JsonObject(mapOf("status" to JsonPrimitive("annulled"))))
                    }

                    else -> Unit
                }
            }
        }
        return card
    }

    /** Файловый архив: папки и файлы — два списка в одном ответе. */
    private fun patchFolderListing(root: JsonObject, ops: List<Pair<QueuedWrite, Shape>>, names: NamesIndex): JsonObject {
        val map = root.toMutableMap()
        for ((arrayName, family) in listOf("folders" to "folders", "files" to "files")) {
            val items = (root[arrayName] as? JsonArray) ?: continue
            val list = items.mapNotNull { it as? JsonObject }.toMutableList()
            ops.filter { it.second.family == family }.forEach { (op, shape) ->
                when (shape) {
                    is Shape.Delete -> list.removeAll { longField(it, "id") == shape.id }
                    is Shape.Update -> {
                        val at = list.indexOfFirst { longField(it, "id") == shape.id }
                        if (at >= 0) list[at] = mergePatch(list[at], bodyObject(op)).withStamps()
                    }

                    is Shape.Create -> if (family == "folders") list.add(0, createEntity(op, shape, names))
                    is Shape.Upload -> if (family == "files") list.add(0, uploadEntity(op, shape, names))
                    is Shape.Special -> Unit
                }
            }
            map[arrayName] = JsonArray(list)
        }
        return JsonObject(map)
    }

    private fun cachedSiteWorks(cache: OfflineCache): JsonObject =
        cache.findCachedByPath("/api/v1/admin/site/works")
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: JsonObject(mapOf("items" to JsonArray(emptyList()), "count" to JsonPrimitive(0)))

    /** Поиск сущности в кеше списков — источник правок без сети. */
    private fun findCachedEntity(cache: OfflineCache, family: String, id: Long?): JsonObject? {
        if (id == null) return null
        val path = when (family) {
            "clients" -> "/api/v1/admin/clients"
            "orders" -> "/api/v1/admin/orders"
            "requests" -> "/api/v1/admin/requests"
            "tasks" -> "/api/v1/admin/tasks"
            "workers" -> "/api/v1/admin/workers"
            "catalog" -> "/api/v1/admin/catalog"
            "documents" -> "/api/v1/admin/documents"
            "site-works" -> "/api/v1/admin/site/works"
            else -> return null
        }
        val cached = cache.findCachedByPath(path) ?: return null
        val root = runCatching { json.parseToJsonElement(cached).jsonObject }.getOrNull() ?: return null
        val items = root["items"] as? JsonArray ?: return null
        return items.mapNotNull { it as? JsonObject }.firstOrNull { longField(it, "id") == id }
    }

    // ------------------- Ответы, которых в кеше нет дословно -------------------

    /**
     * Список с фильтрами из общего списка того же адреса. Экран заказа
     * ищет «счёт этого заказа» запросом documents?kind=invoice&orderId=5 —
     * такого адреса в кеше может не быть, зато есть весь журнал: фильтруем
     * его здесь так же, как это сделал бы сервер.
     *
     * Фильтровать умеем только по полям строк (kind, status, clientId…)
     * и поиску q. Фильтр, которого в строках нет (период кассы, «на сегодня»
     * у задач), честно не воспроизвести — тогда null, и экран узнает,
     * что этих данных без сети нет.
     */
    fun filterList(listBytes: ByteArray, filters: List<Pair<String, String>>): ByteArray? {
        val root = runCatching { json.parseToJsonElement(listBytes.decodeToString()).jsonObject }.getOrNull()
            ?: return null
        val items = (root["items"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        for ((key, _) in filters) {
            // Пустые ссылки (orderId у документа без заказа) сервер может не присылать вовсе.
            val known = key == "q" || key.endsWith("Id") || items.isEmpty() || items.any { it.containsKey(key) }
            if (!known) return null
        }
        val out = items.filter { item ->
            filters.all { (key, value) ->
                if (key == "q") {
                    item.values.any { v ->
                        (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.contains(value, ignoreCase = true) == true
                    }
                } else {
                    (item[key] as? JsonPrimitive)?.contentOrNull == value
                }
            }
        }
        val map = root.toMutableMap()
        map["items"] = JsonArray(out)
        map["count"] = JsonPrimitive(out.size)
        return JsonObject(map).toString().encodeToByteArray()
    }

    /**
     * Адрес созданного без сети: карточка /documents/-3, её печатная форма
     * и вложенные списки /orders/-1/items. На сервере их ещё нет, поэтому
     * ответ собирается из очереди: карточка — из записи создания и правок
     * после неё, вложенный список — пустой (ждущие строки впишет
     * [patchGetResponse]). Возвращает тело и его тип, либо null.
     */
    fun localResponse(getPath: String, queue: List<QueuedWrite>, cache: OfflineCache): Pair<ByteArray, String>? {
        val path = getPath.substringBefore('?')
        if (!path.startsWith("/api/v1/admin/")) return null
        val p = path.removePrefix("/api/v1/admin/").split('/')
        val id = p.getOrNull(1)?.toLongOrNull() ?: return null
        if (id >= 0) return null
        val family = p[0]
        return when {
            p.size == 2 -> {
                val (op, shape) = queue.firstNotNullOfOrNull { op ->
                    val shape = classify(op.method, op.path) as? Shape.Create
                    if (op.localId == id && shape?.family == family) op to shape else null
                } ?: return null
                var entity = createEntity(op, shape, cache.names)
                queue.forEach { later ->
                    val upd = classify(later.method, later.path) as? Shape.Update
                    if (upd?.family == family && upd.id == id) entity = mergePatch(entity, bodyObject(later))
                }
                entity.toString().encodeToByteArray() to "application/json"
            }

            family == "documents" && p.getOrNull(2) == "print" ->
                OFFLINE_PRINT.encodeToByteArray() to "text/html; charset=utf-8"

            p.size == 3 -> """{"items":[],"count":0}""".encodeToByteArray() to "application/json"
            else -> null
        }
    }

    /** Печатную форму собирает сервер — у документа, созданного без сети, её пока нет. */
    private const val OFFLINE_PRINT =
        "<!doctype html><html><head><meta charset=\"utf-8\"></head>" +
            "<body style=\"font-family:sans-serif;padding:24px;color:#555\">" +
            "<p>Документ создан без сети. Печатная форма появится, " +
            "когда он уйдёт на сервер.</p></body></html>"

    // ------------------------ Ссылки локальных номеров -------------------------

    /**
     * Поле-ссылка на сущность: clientId, orderId, folderId, catalogId…
     * Отрицательное значение в таком поле — всегда локальный номер.
     */
    private fun isRefField(name: String): Boolean = name.length > 2 && name.endsWith("Id")

    private fun remapElement(element: JsonElement, ids: Map<Long, Long>): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.mapValues { (key, value) ->
                val n = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                if (isRefField(key) && n != null && n < 0) ids[n]?.let { JsonPrimitive(it) } ?: value
                else remapElement(value, ids)
            },
        )
        is JsonArray -> JsonArray(element.map { remapElement(it, ids) })
        else -> element
    }

    private fun remapPath(path: String, ids: Map<Long, Long>): String =
        path.split('/').joinToString("/") { seg ->
            val n = seg.toLongOrNull()
            if (n != null && n < 0) ids[n]?.toString() ?: seg else seg
        }

    /** Заменяет локальные отрицательные id на серверные: в пути и в теле, включая вложенные строки. */
    fun remap(op: QueuedWrite, ids: Map<Long, Long>): Pair<String, String?> {
        val body = op.body?.let { text ->
            val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return@let text
            remapElement(element, ids).toString()
        }
        return remapPath(op.path, ids) to body
    }

    /** Поля формы загрузки тоже могут нести локальный id (папка архива). */
    fun remapUpload(spec: UploadSpec, ids: Map<Long, Long>): UploadSpec =
        spec.copy(
            path = remapPath(spec.path, ids),
            fields = spec.fields.mapValues { (key, value) ->
                val n = value.toLongOrNull()
                if (isRefField(key) && n != null && n < 0) ids[n]?.toString() ?: value else value
            },
        )

    /**
     * Остались ли в записи локальные номера, которых сервер не знает.
     * Такую запись отправлять нельзя: связанное создание ещё не дошло
     * или было отклонено.
     */
    fun hasLocalIds(path: String, body: String?, upload: UploadSpec?): Boolean {
        if (path.split('/').any { (it.toLongOrNull() ?: 0L) < 0 }) return true
        if (upload != null && upload.fields.any { (k, v) -> isRefField(k) && (v.toLongOrNull() ?: 0L) < 0 }) return true
        val element = body?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } ?: return false
        return hasLocalRef(element)
    }

    private fun hasLocalRef(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.any { (key, value) ->
            val n = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            (isRefField(key) && n != null && n < 0) || hasLocalRef(value)
        }
        is JsonArray -> element.any { hasLocalRef(it) }
        else -> false
    }

    /** Метка операции-перевода в синтезе: итогов кассы она не двигает. */
    private const val TRANSFER = "transfer"

    // ------------------------------ Multipart ---------------------------------

    /** Разобранная форма: файл и текстовые поля. */
    data class ParsedForm(
        val fileField: String,
        val fileName: String,
        val mime: String,
        val bytes: ByteArray,
        val fields: Map<String, String>,
    )

    /** Разбирает multipart-форму, которой приложение грузит файл. */
    fun parseMultipart(contentType: String?, body: ByteArray): ParsedForm? {
        val boundary = Regex("boundary=(.+?)(?:;|$)").find(contentType.orEmpty())?.groupValues?.get(1)
            ?.trim('"') ?: return null
        val delim = "--$boundary".encodeToByteArray()
        var fileField = ""; var fileName = ""; var mime = "application/octet-stream"
        var fileBytes: ByteArray? = null
        val fields = mutableMapOf<String, String>()

        var start = indexOf(body, delim, 0)
        while (start >= 0) {
            val contentStart = start + delim.size
            val next = indexOf(body, delim, contentStart)
            if (next < 0) break
            // Внутри: \r\n…заголовки…\r\n\r\n…тело…\r\n
            var part = body.copyOfRange(contentStart, next)
            part = trimCrLf(part)
            if (part.isNotEmpty() && part[0] != '-'.code.toByte()) {
                val split = indexOf(part, "\r\n\r\n".encodeToByteArray(), 0)
                if (split >= 0) {
                    val headers = part.copyOfRange(0, split).decodeToString()
                    val content = part.copyOfRange(split + 4, part.size)
                    val name = Regex("name=\"([^\"]+)\"").find(headers)?.groupValues?.get(1) ?: continue
                    val file = Regex("filename=\"([^\"]*)\"").find(headers)?.groupValues?.get(1)
                    if (file != null) {
                        fileField = name
                        fileName = file
                        mime = Regex("(?i)content-type:\\s*(.+)").find(headers)?.groupValues?.get(1)?.trim()
                            ?: "application/octet-stream"
                        fileBytes = content
                    } else {
                        fields[name] = content.decodeToString()
                    }
                }
            }
            start = next
        }
        return fileBytes?.let {
            ParsedForm(fileField, fileName, mime, it, fields)
        }
    }

    /** Собирает форму заново при отправке. */
    fun buildForm(spec: UploadSpec, bytes: ByteArray): RequestBody {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        builder.addFormDataPart(
            spec.fileField,
            spec.fileName,
            bytes.toRequestBody(spec.mime.ifBlank { "application/octet-stream" }.toMediaType()),
        )
        spec.fields.forEach { (name, value) -> builder.addFormDataPart(name, value) }
        return builder.build()
    }

    private fun trimCrLf(bytes: ByteArray): ByteArray {
        var from = 0
        var to = bytes.size
        while (from < to && (bytes[from] == '\r'.code.toByte() || bytes[from] == '\n'.code.toByte())) from++
        while (to > from && (bytes[to - 1] == '\r'.code.toByte() || bytes[to - 1] == '\n'.code.toByte())) to--
        return bytes.copyOfRange(from, to)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return -1
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
