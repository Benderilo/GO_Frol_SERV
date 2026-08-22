package com.example.frolovsistems

import com.example.frolovsistems.core.prefs.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор адреса — самое частое место, где связь с сервером ломается,
 * поэтому проверяем варианты, которые реально вводят руками.
 */
class ServerConfigTest {

    private val default = ServerConfig()

    @Test
    fun `голый IP оставляет схему и порт как были`() {
        val config = default.withHostInput("195.19.195.169")
        assertEquals("195.19.195.169", config.host)
        assertEquals("http", config.scheme)
        assertEquals(80, config.port)
        assertEquals("http://195.19.195.169", config.baseUrl)
    }

    @Test
    fun `вставленная ссылка со схемой не склеивается дважды`() {
        val config = default.withHostInput("http://195.19.195.169")
        assertEquals("195.19.195.169", config.host)
        assertEquals("http://195.19.195.169", config.baseUrl)
    }

    @Test
    fun `https переключает схему и подставляет 443`() {
        val config = default.withHostInput("https://crm.example.ru")
        assertEquals("https", config.scheme)
        assertEquals(443, config.port)
        assertEquals("https://crm.example.ru", config.baseUrl)
    }

    @Test
    fun `порт из ссылки попадает в настройки`() {
        val config = default.withHostInput("http://192.168.0.30:8080")
        assertEquals("192.168.0.30", config.host)
        assertEquals(8080, config.port)
        assertEquals("http://192.168.0.30:8080", config.baseUrl)
    }

    @Test
    fun `путь и параметры отбрасываются`() {
        val config = default.withHostInput("http://195.19.195.169/api/v1/health?x=1")
        assertEquals("195.19.195.169", config.host)
        assertEquals("http://195.19.195.169", config.baseUrl)
    }

    @Test
    fun `пробелы по краям срезаются`() {
        assertEquals("195.19.195.169", default.withHostInput("  195.19.195.169  ").host)
    }

    @Test
    fun `нестандартный порт остаётся в адресе`() {
        val config = default.copy(port = 8080)
        assertEquals("http://195.19.195.169:8080", config.baseUrl)
    }

    @Test
    fun `пустой хост и нулевой порт считаются некорректными`() {
        assertFalse(default.copy(host = "").isValid)
        assertFalse(default.copy(port = 0).isValid)
        assertTrue(default.isValid)
    }
}
