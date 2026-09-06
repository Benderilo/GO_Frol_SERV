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
    fun `умолчания ведут на ип-фролов рф по HTTPS`() {
        assertEquals("ип-фролов.рф", default.host)
        assertEquals("https://xn----dtbqnobdj9a.xn--p1ai", default.baseUrl)
        assertEquals("https://ип-фролов.рф", default.displayUrl)
        assertTrue(default.isValid)
    }

    /** Имя можно вписать и латиницей — в запрос уйдёт ровно то же самое. */
    @Test
    fun `punycode и кириллица дают один адрес запроса`() {
        val typed = default.withHostInput("xn----dtbqnobdj9a.xn--p1ai")
        assertEquals(default.baseUrl, typed.baseUrl)
    }

    /** Вставленная из браузера ссылка приходит с www — её тоже надо принять. */
    @Test
    fun `www у кириллического имени переводится в punycode`() {
        val config = default.withHostInput("https://www.ип-фролов.рф/")
        assertEquals("www.ип-фролов.рф", config.host)
        assertEquals("https://www.xn----dtbqnobdj9a.xn--p1ai", config.baseUrl)
    }

    /** Адрес читают на каждое нажатие клавиши, поэтому огрызок имени не должен ронять разбор. */
    @Test
    fun `недописанное имя не роняет разбор адреса`() {
        val partial = default.copy(host = "ип-")
        assertEquals("https://ип-", partial.displayUrl)
        assertTrue(partial.baseUrl.startsWith("https://"))
        assertEquals("https://", default.copy(host = "").baseUrl)
        assertFalse(default.copy(host = "").isValid)
    }

    /** У обычных адресов punycode ничего не трогает. */
    @Test
    fun `латинские адреса остаются как есть`() {
        assertEquals("http://195.19.195.169", plain.baseUrl)
        assertEquals(plain.baseUrl, plain.displayUrl)
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
