package com.example.frolovsistems

import com.example.frolovsistems.core.net.ClientDto
import com.example.frolovsistems.core.net.ListResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ответ /admin/clients с боевого формата сервера обязан разбираться как есть.
 * Фикстура снята с живого сервера: три клиента, все поля, включая реквизиты.
 */
class ClientDecodeTest {

    /** Ровно те настройки, с которыми сериализует ApiClient. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `список клиентов с сервера разбирается`() {
        val payload = """
            {"count":3,"items":[
            {"id":1,"name":"Зоя Петровна","phone":"+7 900 111-22-33","email":"","address":"","note":"","tag":"","createdAt":"2026-09-20T14:09:29Z","updatedAt":"2026-09-20T14:09:29Z","inn":"645800000000","kpp":"","bankName":"","bankAccount":"","portalEnabled":false,"portalLastLogin":"","ordersCount":0,"revenueDoneKop":0},
            {"id":2,"name":"ООО Ромашка+«Сад»","phone":"+7 900 111-22-33","email":"","address":"","note":"","tag":"","createdAt":"2026-09-20T14:09:29Z","updatedAt":"2026-09-20T14:09:29Z","inn":"645800000000","kpp":"","bankName":"","bankAccount":"","portalEnabled":false,"portalLastLogin":"","ordersCount":0,"revenueDoneKop":0},
            {"id":3,"name":"Иван","phone":"","email":"","address":"","note":"","tag":"","createdAt":"2026-09-20T14:09:29Z","updatedAt":"2026-09-20T14:09:29Z","inn":"","kpp":"","bankName":"","bankAccount":"","portalEnabled":false,"portalLastLogin":"","ordersCount":0,"revenueDoneKop":0}]}
        """.trimIndent()

        val decoded = json.decodeFromString<ListResponse<ClientDto>>(payload)
        assertEquals(3, decoded.items.size)
        assertEquals("Зоя Петровна", decoded.items[0].name)
        assertEquals("645800000000", decoded.items[0].inn)
        assertEquals(0L, decoded.items[2].revenueDoneKop)
    }
}
