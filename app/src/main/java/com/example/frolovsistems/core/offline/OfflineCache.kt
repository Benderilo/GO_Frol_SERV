package com.example.frolovsistems.core.offline

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.security.MessageDigest

/** Ответ, поднятый из офлайн-кеша. */
class CachedBody(
    val bytes: ByteArray,
    val contentType: String,
    /** Когда ответ был скачан с сервера, миллисекунды. */
    val savedAt: Long,
)

/**
 * Кеш прочитанных данных для работы без сети: списки и карточки (JSON),
 * печатные формы (HTML) и снимки заказов с витрины.
 *
 * Один файл — один ответ. Первая строка файла — мета в JSON, дальше тело
 * как есть; имя файла — хеш адреса, поэтому ключом служит весь URL
 * с параметрами фильтров: список «заказы в работе» и «все заказы» —
 * это разные копии. Рядом живёт индекс «путь → файл», чтобы фабрика
 * офлайн-ответов могла найти список клиентов или заказов, не зная
 * параметров последнего запроса.
 *
 * Место не резиновое, поэтому при превышении лимита выкидываем самое
 * давно не читанное: JSON-ответы копеечные, основное место занимают
 * снимки, и терять их при возврате сети не страшно — скачаются заново.
 *
 * Любая ошибка здесь не должна ломать сам запрос: кеш — попытка
 * помочь при обрыве связи, а не вторая база данных.
 */
class OfflineCache(root: File) {

    constructor(context: Context) : this(context.filesDir)

    @Serializable
    private data class Meta(val savedAt: Long, val contentType: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val dir = File(root, "offline-cache").apply { mkdirs() }
    private val indexFile = File(root, "offline-cache-index.json")
    private val namesFile = File(root, "offline-names.json")

    /** Путь без схемы и хоста → имя файла с ответом. */
    private val pathIndex = mutableMapOf<String, String>()

    /** Имена сущностей для офлайн-ответов, см. [NamesIndex]. */
    private var namesData = NamesIndex()

    @Volatile
    private var loaded = false

    val names: NamesIndex
        get() {
            ensureLoaded()
            return namesData
        }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                indexFile.takeIf { it.exists() }?.readLines()?.forEach { line ->
                    val (path, file) = line.split('\u0001', limit = 2)
                    if (file.isNotBlank()) pathIndex[path] = file
                }
            }
            namesFile.takeIf { it.exists() }?.let {
                namesData = runCatching {
                    json.decodeFromString(NamesIndex.serializer(), it.readText())
                }.getOrDefault(NamesIndex())
            }
            loaded = true
        }
    }

    /** Копия из кеша или null, если адрес ещё не скачивали. */
    // Запросы идут параллельно из потоков OkHttp: индекс и имена общие,
    // поэтому все входы в кеш — под одним замком.
    @Synchronized
    fun get(key: String): CachedBody? = runCatching {
        ensureLoaded()
        val file = File(dir, fileName(key))
        if (!file.exists()) return null
        val raw = file.readBytes()
        val split = raw.indexOf('\n'.code.toByte())
        if (split <= 0) return null
        val meta = json.decodeFromString(Meta.serializer(), raw.copyOfRange(0, split).decodeToString())
        // Читанное — свежее: чем чаще снимок смотрят, тем позже его выкинет чистка.
        file.setLastModified(System.currentTimeMillis())
        CachedBody(
            bytes = raw.copyOfRange(split + 1, raw.size),
            contentType = meta.contentType,
            savedAt = meta.savedAt,
        )
    }.getOrNull()

    /**
     * Когда адрес скачан в последний раз, миллисекунды; null — не скачивали.
     * Читает только первую строку: фоновой подкачке не нужно само тело.
     */
    @Synchronized
    fun savedAt(key: String): Long? = runCatching {
        val file = File(dir, fileName(key))
        if (!file.exists()) return null
        val head = file.bufferedReader().use { it.readLine() } ?: return null
        json.decodeFromString(Meta.serializer(), head).savedAt
    }.getOrNull()

    @Synchronized
    fun put(key: String, path: String, contentType: String, bytes: ByteArray) {
        runCatching {
            ensureLoaded()
            val meta = json.encodeToString(
                Meta.serializer(),
                Meta(System.currentTimeMillis(), contentType),
            )
            val name = fileName(key)
            File(dir, name).writeBytes((meta + "\n").encodeToByteArray() + bytes)
            pathIndex[path] = name
            saveIndex()
            noteNames(path, bytes)
            evictIfNeeded()
        }
    }

    /** Тело кеша по пути без параметров — для фабрики офлайн-ответов. */
    @Synchronized
    fun findCachedByPath(path: String): String? = runCatching {
        ensureLoaded()
        val name = pathIndex[path] ?: return null
        val raw = File(dir, name).readBytes()
        val split = raw.indexOf('\n'.code.toByte())
        if (split <= 0) null else raw.copyOfRange(split + 1, raw.size).decodeToString()
    }.getOrNull()

    /** Сколько места занимает кеш — для подписи в настройках. */
    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        pathIndex.clear()
        namesData = NamesIndex()
        indexFile.delete()
        namesFile.delete()
        saveIndex()
    }

    /**
     * Имена из удачных списков: у созданного без сети заказа должно быть
     * имя клиента, хотя сервер его ещё не собирал. Индекс небольшой —
     * он про справочники, которые меняются редко.
     */
    private fun noteNames(path: String, bytes: ByteArray) {
        val family = when {
            path.endsWith("/api/v1/admin/clients") -> "clients"
            path.endsWith("/api/v1/admin/workers") -> "workers"
            path.endsWith("/api/v1/admin/orders") -> "orders"
            path.endsWith("/api/v1/admin/catalog") -> "catalog"
            else -> return
        }
        runCatching {
            val items = (json.parseToJsonElement(bytes.decodeToString()) as? JsonObject)
                ?.get("items") as? JsonArray ?: return
            val map = HashMap<Long, String>()
            items.forEach { item ->
                val obj = item as? JsonObject ?: return@forEach
                val id = (obj["id"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                val name = when (family) {
                    "orders" -> "title"
                    else -> "name"
                }
                val value = (obj[name] as? JsonPrimitive)?.content ?: return@forEach
                if (value.isNotBlank()) map[id] = value
            }
            if (map.isEmpty()) return
            namesData = when (family) {
                "clients" -> namesData.copy(clients = namesData.clients + map)
                "workers" -> namesData.copy(workers = namesData.workers + map)
                "orders" -> namesData.copy(orders = namesData.orders + map)
                else -> namesData.copy(catalog = namesData.catalog + map)
            }
            namesFile.writeText(json.encodeToString(NamesIndex.serializer(), namesData))
        }
    }

    private fun saveIndex() {
        runCatching {
            // Разделитель U+0001 не встречается ни в путях, ни в именах файлов.
            indexFile.writeText(pathIndex.entries.joinToString("\n") { "${it.key}\u0001${it.value}" })
        }
    }

    /** Выкидывает самое давно не читанное, пока не влезем в девять десятых лимита. */
    private fun evictIfNeeded() {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= CAP) return
        val removed = mutableSetOf<String>()
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= CAP * 9 / 10) break
            total -= file.length()
            removed.add(file.name)
            file.delete()
        }
        if (removed.isNotEmpty()) {
            pathIndex.entries.removeAll { it.value in removed }
            saveIndex()
        }
    }

    private fun fileName(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.encodeToByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Всего держим на кеш: JSON лёгкий, место съедают снимки. */
        private const val CAP = 192L * 1024 * 1024

        /** Ответ больше — не кешируем: книги Excel и снимки базы не для этого. */
        internal const val MAX_ENTRY = 12L * 1024 * 1024
    }
}
