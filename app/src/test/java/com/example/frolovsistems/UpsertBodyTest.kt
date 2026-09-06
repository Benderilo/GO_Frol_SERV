package com.example.frolovsistems

import com.example.frolovsistems.core.net.ClientDto
import com.example.frolovsistems.core.net.ClientUpsert
import com.example.frolovsistems.core.net.OrderDto
import com.example.frolovsistems.core.net.OrderUpsert
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Сервер разбирает тело запроса с DisallowUnknownFields: одно незнакомое
 * поле — и весь запрос отвергается с «Некорректный JSON». Поэтому состав
 * тела закрепляем тестом, а не надеемся, что в DTO никто не добавит поле,
 * которое считает сам сервер.
 */
class UpsertBodyTest {

    /** Ровно те настройки, с которыми сериализует ApiClient. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `в заказе уезжают только правимые поля`() {
        val order = OrderDto(
            id = 42,
            clientId = 7,
            clientName = "ООО Ромашка",
            title = "Замена насоса",
            description = "по гарантии",
            status = "in_progress",
            priceKop = 1_500_000,
            dueDate = "2026-09-10",
            createdAt = "2026-09-01T10:00:00Z",
            updatedAt = "2026-09-02T10:00:00Z",
            closedAt = "2026-09-02T12:00:00Z",
            paidKop = 500_000,
            photoCount = 3,
        )
        val body = json.parseToJsonElement(
            json.encodeToString(OrderUpsert.serializer(), OrderUpsert.of(order)),
        ).jsonObject

        assertEquals(
            setOf("clientId", "title", "description", "status", "priceKop", "dueDate"),
            body.keys,
        )
        assertEquals("Замена насоса", body["title"]!!.toString().trim('"'))
        assertEquals("in_progress", body["status"]!!.toString().trim('"'))
    }

    @Test
    fun `в клиенте уезжают только правимые поля`() {
        val client = ClientDto(
            id = 3,
            name = "Иванов Иван",
            phone = "+79990000000",
            email = "i@example.ru",
            address = "Москва",
            note = "постоянный",
            tag = "опт",
            createdAt = "2026-09-01T10:00:00Z",
            updatedAt = "2026-09-02T10:00:00Z",
            portalEnabled = true,
            portalLastLogin = "2026-09-02T11:00:00Z",
            ordersCount = 12,
            revenueDoneKop = 24_000_000,
        )
        val body = json.parseToJsonElement(
            json.encodeToString(ClientUpsert.serializer(), ClientUpsert.of(client)),
        ).jsonObject

        assertEquals(
            setOf("name", "phone", "email", "address", "note", "tag"),
            body.keys,
        )
        assertEquals("Иванов Иван", body["name"]!!.toString().trim('"'))
    }
}
