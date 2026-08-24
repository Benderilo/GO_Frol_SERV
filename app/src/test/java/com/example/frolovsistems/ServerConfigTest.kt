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

    /** Умолчания приложения: новый сервер работает по HTTPS. */
    private val default = ServerConfig()

    /** Явно незащищённая настройка — нужна там, где проверяется разбор http. */
    private val plain = ServerConfig(scheme = "http", host = "195.19.195.169", port = 80)

    @Test
    fun `умолчания ведут на новый сервер по HTTPS`() {
        assertEquals("https://v3002851.hosted-by-vdsina.ru", default.baseUrl)
        assertTrue(default.isValid)
    }

    @Test
    fun `голый IP оставляет схему и порт как были`() {
        val config = plain.withHostInput("195.19.195.169")
        assertEquals("195.19.195.169", config.host)
        assertEquals("http", config.scheme)
        assertEquals(80, config.port)
        assertEquals("http://195.19.195.169", config.baseUrl)
    }

    @Test
    fun `вставленная ссылка со схемой не склеивается дважды`() {
        val config = plain.withHostInput("http://195.19.195.169")
        assertEquals("195.19.195.169", config.host)
        assertEquals("http://195.19.195.169", config.baseUrl)
    }

    @Test
    fun `https переключает схему и подставляет 443`() {
        val config = plain.withHostInput("https://crm.example.ru")
        assertEquals("https", config.scheme)
        assertEquals(443, config.port)
        assertEquals("https://crm.example.ru", config.baseUrl)
    }

    @Test
    fun `порт из ссылки попадает в настройки`() {
        val config = plain.withHostInput("http://192.168.0.30:8080")
        assertEquals("192.168.0.30", config.host)
        assertEquals(8080, config.port)
        assertEquals("http://192.168.0.30:8080", config.baseUrl)
    }

    @Test
    fun `путь и параметры отбрасываются`() {
        val config = plain.withHostInput("http://195.19.195.169/api/v1/health?x=1")
        assertEquals("195.19.195.169", config.host)
        assertEquals("http://195.19.195.169", config.baseUrl)
    }

    @Test
    fun `пробелы по краям срезаются`() {
        assertEquals("195.19.195.169", plain.withHostInput("  195.19.195.169  ").host)
    }

    @Test
    fun `нестандартный порт остаётся в адресе`() {
        val config = plain.copy(port = 8080)
        assertEquals("http://195.19.195.169:8080", config.baseUrl)
    }

    @Test
    fun `пустой хост и нулевой порт считаются некорректными`() {
        assertFalse(plain.copy(host = "").isValid)
        assertFalse(plain.copy(port = 0).isValid)
        assertTrue(default.isValid)
    }
}
