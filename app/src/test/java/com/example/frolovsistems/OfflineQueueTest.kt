package com.example.frolovsistems

import com.example.frolovsistems.core.offline.OfflineCache
import com.example.frolovsistems.core.offline.OfflineFabricate
import com.example.frolovsistems.core.offline.OfflineQueue
import com.example.frolovsistems.core.offline.QueuedWrite
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Очередь и подмешивание её в списки из кеша: номера, связи между
 * созданным без сети, итоги кассы и вложенные списки заказа.
 */
class OfflineQueueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `локальные номера не повторяются после опустевшей очереди`() {
        val queue = OfflineQueue(tmp.root)
        val first = queue.addWrite("POST", "/api/v1/admin/clients", "{}")
        queue.remove(first.id)
        val second = queue.addWrite("POST", "/api/v1/admin/clients", "{}")

        assertTrue(second.id > first.id)
        assertTrue(second.localId!! < first.localId!!)
        // И после перезапуска приложения — счётчики в файле.
        val third = OfflineQueue(tmp.root).addWrite("POST", "/api/v1/admin/clients", "{}")
        assertTrue(third.localId!! < second.localId!!)
    }

    @Test
    fun `таблица настоящих номеров переживает перезапуск`() {
        OfflineQueue(tmp.root).rememberId(-1, 41)
        assertEquals(mapOf(-1L to 41L), OfflineQueue(tmp.root).knownIds())
    }

    @Test
    fun `повтор снимает пометку ждёт решения`() {
        val queue = OfflineQueue(tmp.root)
        val op = queue.addWrite("POST", "/api/v1/admin/orders/5/payments", """{"amountKop":100}""")
        queue.setError(op.id, "обрыв", needsReview = true)
        assertTrue(queue.list().single().needsReview)

        queue.retry(op.id)
        val after = queue.list().single()
        assertFalse(after.needsReview)
        assertEquals("", after.error)
    }

    @Test
    fun `remap меняет номера во вложенных строках и папке`() {
        val op = QueuedWrite(
            id = 1,
            method = "POST",
            path = "/api/v1/admin/documents",
            body = """{"clientId":-2,"lines":[{"catalogId":-4,"qtyMilli":1000}],"note":"-7"}""",
            queuedAt = 0,
        )
        val (_, body) = OfflineFabricate.remap(op, mapOf(-2L to 41L, -4L to 9L))
        assertTrue(body!!.contains("\"clientId\":41"))
        assertTrue(body.contains("\"catalogId\":9"))
        assertTrue(body.contains("\"note\":\"-7\"")) // строки — не ссылки
        assertFalse(OfflineFabricate.hasLocalIds("/api/v1/admin/documents", body, null))
    }

    @Test
    fun `запись с неизвестным локальным номером не отправляется`() {
        assertTrue(OfflineFabricate.hasLocalIds("/api/v1/admin/orders/-3", null, null))
        assertTrue(OfflineFabricate.hasLocalIds("/api/v1/admin/orders", """{"clientId":-2}""", null))
        assertFalse(OfflineFabricate.hasLocalIds("/api/v1/admin/payroll/dates/2026-10", """{"workerId":7}""", null))
    }

    @Test
    fun `итоги кассы сдвигаются только на ждущие операции`() {
        val cache = OfflineCache(tmp.root)
        val cached = """{"items":[
            {"id":1,"direction":"in","amountKop":1000},
            {"id":2,"direction":"out","amountKop":300}
        ],"incomeKop":1000,"expenseKop":300,"balanceKop":700}"""
        val queue = listOf(
            QueuedWrite(1, method = "POST", path = "/api/v1/admin/cash",
                body = """{"direction":"in","amountKop":500}""", localId = -1, queuedAt = 0),
            QueuedWrite(2, method = "DELETE", path = "/api/v1/admin/cash/2", queuedAt = 0),
            QueuedWrite(3, method = "POST", path = "/api/v1/admin/cash/transfer",
                body = """{"from":"cash","to":"card","amountKop":200}""", localId = -2, queuedAt = 0),
        )
        val out = parse(OfflineFabricate.patchGetResponse("/api/v1/admin/cash", cached.encodeToByteArray(), queue, cache))

        assertEquals(1500L, out["incomeKop"]!!.jsonPrimitive.long)
        assertEquals(0L, out["expenseKop"]!!.jsonPrimitive.long)
        assertEquals(1500L, out["balanceKop"]!!.jsonPrimitive.long)
    }

    @Test
    fun `строка заказа попадает только в свой заказ`() {
        val cache = OfflineCache(tmp.root)
        val cached = """{"items":[],"count":0}""".encodeToByteArray()
        val queue = listOf(
            QueuedWrite(1, method = "POST", path = "/api/v1/admin/orders/7/items",
                body = """{"name":"Автомат","qtyMilli":1000,"priceKop":50000}""", localId = -1, queuedAt = 0),
        )
        val other = parse(OfflineFabricate.patchGetResponse("/api/v1/admin/orders/5/items", cached, queue, cache))
        val own = parse(OfflineFabricate.patchGetResponse("/api/v1/admin/orders/7/items", cached, queue, cache))

        assertEquals(0, other["items"]!!.jsonArray.size)
        assertEquals(1, own["items"]!!.jsonArray.size)
    }

    @Test
    fun `фильтр вкладки учитывается`() {
        val cache = OfflineCache(tmp.root)
        val cached = """{"items":[],"count":0}""".encodeToByteArray()
        val queue = listOf(
            QueuedWrite(1, method = "POST", path = "/api/v1/admin/orders",
                body = """{"title":"Щит","status":"new"}""", localId = -1, queuedAt = 0),
        )
        val done = parse(OfflineFabricate.patchGetResponse("/api/v1/admin/orders?status=done&limit=500", cached, queue, cache))
        val fresh = parse(OfflineFabricate.patchGetResponse("/api/v1/admin/orders?status=new&limit=500", cached, queue, cache))

        assertEquals(0, done["items"]!!.jsonArray.size)
        assertEquals(1, fresh["items"]!!.jsonArray.size)
    }

    private fun parse(bytes: ByteArray): JsonObject = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
}
