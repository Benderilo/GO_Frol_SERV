package com.example.frolovsistems.core.offline

import android.content.Context
import com.example.frolovsistems.core.diagnostics.Diagnostics
import com.example.frolovsistems.core.prefs.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Связь и очередь — это смотрит плашка под шапкой и экран настроек. */
data class OfflineStatus(
    /** Последняя попытка связи провалилась — работаем с сохранённым. */
    val offline: Boolean = false,
    /** Сколько записей ждёт отправки. */
    val pending: Int = 0,
    /** Очередь уходит на сервер прямо сейчас. */
    val flushing: Boolean = false,
    /** Фоновая подкачка данных для работы без сети идёт прямо сейчас. */
    val prefetching: Boolean = false,
    /** Когда подкачка последний раз прошла до конца, миллисекунды; 0 — ещё ни разу. */
    val preparedAt: Long = 0L,
)

/**
 * Офлайн-режим целиком: кеш чтения, очередь всех записей и перехватчик
 * OkHttp, который всем этим распоряжается.
 *
 * Перехватчик стоит под Ktor и видит каждый запрос одинаково, поэтому
 * экраны приложения вообще не знают про офлайн:
 *
 *  — удачный GET кладётся в кеш; при обрыве сети ответ подаётся из кеша,
 *    причём в списки вписано всё, что ждёт отправки: созданное дописано,
 *    правки наложены, удалённое убрано;
 *  — запись без сети не «падает», а принимается: создание, правка,
 *    удаление и загрузка файла становятся в очередь, а экран сразу
 *    получает ответ-синтез — созданный клиент с отрицательным номером,
 *    обновлённая задача, загруженное фото с адресом offline://.
 *
 * Отправка запускается сама, как только любой запрос до сервера дошёл:
 * раз связь вернулась, отложенное пора отдавать. Локальные номера на
 * серверные меняются на лету — и в путях, и в полях-ссылках тел, —
 * поэтому заказ, созданный для клиента, которого тоже создали без сети,
 * на сервере связывается с ним правильно.
 */
class Offline(private val context: Context, private val settings: AppSettings) {

    val cache = OfflineCache(context)
    val queue = OfflineQueue(context)

    /** Когда подкачка прошла до конца — переживает перезапуск. */
    private val preparedFile = File(context.filesDir, "offline-prepared.txt")

    private val _status = MutableStateFlow(OfflineStatus())
    val status: StateFlow<OfflineStatus> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushingNow = AtomicBoolean(false)
    private val flushMutex = Mutex()

    /** Помеченный этим запрос — повтор из очереди; его не кешируем и не ставим обратно в очередь. */
    private val replayMarker = "X-Frolov-Replay"

    init {
        // Счётчик очереди — сразу при старте: от него зависит, будит ли
        // первый удачный запрос отправку отложенного.
        scope.launch {
            refreshPending()
            val prepared = runCatching { preparedFile.readText().trim().toLong() }.getOrDefault(0L)
            _status.update { it.copy(preparedAt = prepared) }
        }
    }

    /** Короткий клиент без перехватчика: повторы из очереди идут в обход офлайн-логики. */
    private val replayClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val interceptor = Interceptor { chain ->
        val request = chain.request()

        if (request.header(replayMarker) != null) {
            return@Interceptor chain.proceed(request.newBuilder().removeHeader(replayMarker).build())
        }

        val path = request.url.encodedPath
        val cacheable = request.method == "GET" && cacheableGet(path)

        // Созданное без сети (номер ещё отрицательный) сервер не знает даже
        // при живой связи — его карточку и списки отдаёт очередь.
        if (cacheable && path.split('/').any { (it.toLongOrNull() ?: 0L) < 0 }) {
            serveFromCache(request)?.let { return@Interceptor it }
        }

        // Очередь ещё не ушла — новая запись встаёт за ней, а не обгоняет:
        // иначе старая правка из очереди затёрла бы свежую. Туда же идёт
        // запись, ссылающаяся на созданное без сети (номер ещё локальный).
        if (queueableWrite(request.method, path) && runCatching { mustGoThroughQueue(request) }.getOrDefault(false)) {
            val queued = acceptWrite(request, path)
            if (queued != null) {
                scope.launch { flush() }
                return@Interceptor queued
            }
        }

        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            // Ktor сам оборвал запрос по своему сроку и уже показал ошибку —
            // принимать такую запись в очередь нельзя: человек нажмёт ещё раз.
            if (chain.call().isCanceled()) throw e
            wentOffline()
            if (cacheable) {
                val hit = serveFromCache(request)
                if (hit != null) return@Interceptor hit
            }
            // Создание, оборвавшееся уже после отправки, сервер мог принять:
            // повтор из очереди задвоил бы платёж. Его отдаём ошибкой, как раньше.
            // Правка и удаление повторяются без вреда — их принимаем всегда.
            if (request.method != "POST" || notReachedServer(e)) {
                val queued = acceptWrite(request, path)
                if (queued != null) return@Interceptor queued
            }
            throw e
        }

        wentOnline()
        if (cacheable && response.isSuccessful) {
            runCatching {
                val declared = response.body.contentLength()
                if (declared <= OfflineCache.MAX_ENTRY) {
                    val peek = response.peekBody(OfflineCache.MAX_ENTRY)
                    val bytes = peek.bytes()
                    // Чанковый ответ без длины мог обрезаться по лимиту — такой не годится.
                    val truncated = declared < 0 && bytes.size >= OfflineCache.MAX_ENTRY
                    if (!truncated) {
                        cache.put(
                            keyOf(request.url),
                            path,
                            response.header("Content-Type") ?: "application/octet-stream",
                            bytes,
                        )
                    }
                }
            }
        }
        response
    }

    /**
     * Ответ из кеша с учётом очереди: JSON-списки дополняются ждущими
     * записями, карточки получают правки. Печатные формы (HTML) и снимки
     * отдаются как лежат — вписывать записи туда некуда.
     */
    private fun serveFromCache(request: Request): Response? {
        val hit = cache.get(keyOf(request.url)) ?: derivedFromCache(request) ?: return null
        val patched = if (hit.contentType.contains("json")) {
            runCatching {
                OfflineFabricate.patchGetResponse(
                    // С параметрами: по ним вкладки фильтруют дописанное, а ведомость берёт месяц.
                    pathWithQuery(request),
                    hit.bytes,
                    queue.list(),
                    cache,
                )
            }.getOrDefault(hit.bytes)
        } else {
            hit.bytes
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("Offline cache")
            .headers(
                Headers.headersOf(
                    "Content-Type", hit.contentType,
                    "X-Frolov-Cache-Time", hit.savedAt.toString(),
                ),
            )
            .body(patched.toResponseBody(hit.contentType.toMediaTypeOrNull()))
            .build()
    }

    /**
     * Запись без сети: принимает её в очередь и отвечает синтезом —
     * экран работает как при живом сервере. Возвращает null, если такая
     * запись офлайн не поддерживается: тогда ошибка сети пойдёт наружу.
     */
    private fun acceptWrite(request: Request, path: String): Response? {
        if (!queueableWrite(request.method, path)) return null

        if (isUploadForm(request)) {
            val raw = request.body ?: return null
            val bytes = Buffer().also { raw.writeTo(it) }.readByteArray()
            val form = OfflineFabricate.parseMultipart(contentTypeOf(request), bytes) ?: return null
            val op = queue.addUpload(
                UploadSpec(
                    path = path,
                    fileField = form.fileField,
                    fileName = form.fileName,
                    mime = form.mime,
                    fields = form.fields,
                ),
                form.bytes,
            )
            refreshPending()
            return synthetic(request, op)
        }

        val body = request.body?.let { raw -> Buffer().also { raw.writeTo(it) }.readUtf8() }
        val op = queue.addWrite(request.method, path, body)
        refreshPending()
        return synthetic(request, op)
    }

    /** Ответ-синтез на принятую запись: экран не отличит его от настоящего. */
    private fun synthetic(request: Request, op: QueuedWrite): Response {
        val text = runCatching { OfflineFabricate.responseFor(op, cache) }.getOrDefault("{}")
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("Queued offline")
            .headers(Headers.headersOf("Content-Type", "application/json"))
            .body(text.toResponseBody("application/json".toMediaTypeOrNull()))
            .build()
    }

    /**
     * Запрос точно не дошёл до сервера: адрес не нашёлся, соединение
     * не открылось. Остальные обрывы (таймаут ответа, сброс посреди
     * обмена) неоднозначны — сервер мог запись уже выполнить.
     */
    private fun notReachedServer(e: IOException): Boolean = when (e) {
        is UnknownHostException, is ConnectException, is NoRouteToHostException -> true
        is SocketTimeoutException -> e.message.orEmpty().contains("connect", ignoreCase = true)
        else -> false
    }

    /**
     * Запись идёт через очередь, даже если сеть есть: в очереди ждут
     * более ранние правки (порядок важен), или запись ссылается на
     * созданное без сети, чей настоящий номер знает только очередь.
     */
    private fun mustGoThroughQueue(request: Request): Boolean {
        if (queue.list().any { !it.needsReview && it.error.isBlank() }) return true
        if (request.url.encodedPath.split('/').any { (it.toLongOrNull() ?: 0L) < 0 }) return true
        val raw = request.body ?: return false
        val bytes = Buffer().also { raw.writeTo(it) }.readByteArray()
        if (isUploadForm(request)) {
            // Файл в папку, созданную без сети: номер папки — в поле формы.
            val form = OfflineFabricate.parseMultipart(contentTypeOf(request), bytes) ?: return false
            return form.fields.any { (k, v) -> k.endsWith("Id") && (v.toLongOrNull() ?: 0L) < 0 }
        }
        return OfflineFabricate.hasLocalIds("", bytes.decodeToString(), null)
    }

    /** Загружаемые файлы уходят формой — их распознаём по типу тела. */
    private fun isUploadForm(request: Request): Boolean =
        contentTypeOf(request)?.startsWith("multipart/form-data") == true

    /** Тип тела: Ktor кладёт его в заголовок, но OkHttp знает его и по самому телу. */
    private fun contentTypeOf(request: Request): String? =
        request.header("Content-Type") ?: request.body?.contentType()?.toString()

    /** Ключ кеша — весь адрес с параметрами: «заказы в работе» и «все заказы» лежат порознь. */
    private fun keyOf(url: HttpUrl): String =
        url.scheme + "://" + url.host + withPort(url) + url.encodedPath +
            (url.encodedQuery?.let { "?$it" } ?: "")

    /**
     * Ответ, которого нет в кеше дословно, но который можно собрать:
     * созданное без сети — из очереди, список с фильтрами — из общего
     * списка того же адреса (оставляем только limit, как без фильтров).
     */
    private fun derivedFromCache(request: Request): CachedBody? {
        val now = System.currentTimeMillis()
        OfflineFabricate.localResponse(pathWithQuery(request), queue.list(), cache)?.let { (bytes, type) ->
            return CachedBody(bytes, type, now)
        }
        val url = request.url
        val filters = url.queryParameterNames
            .filter { it != "limit" }
            .mapNotNull { name -> url.queryParameter(name)?.takeIf { it.isNotBlank() }?.let { name to it } }
        if (filters.isEmpty()) return null
        val base = url.newBuilder().query(null)
            .apply { url.queryParameter("limit")?.let { addQueryParameter("limit", it) } }
            .build()
        val full = cache.get(keyOf(base)) ?: return null
        val bytes = OfflineFabricate.filterList(full.bytes, filters) ?: return null
        return CachedBody(bytes, full.contentType, full.savedAt)
    }

    /** Когда адрес последний раз скачан с сервера; null — не скачивали. Для фоновой подкачки. */
    fun cachedAt(url: String): Long? = url.toHttpUrlOrNull()?.let { cache.savedAt(keyOf(it)) }

    /** Подкачка идёт или закончилась — для строки в настройках. */
    fun setPrefetching(active: Boolean) = _status.update { it.copy(prefetching = active) }

    /** Подкачка прошла до конца: всё для работы без сети скачано. */
    fun markPrepared() {
        val now = System.currentTimeMillis()
        runCatching { preparedFile.writeText(now.toString()) }
        _status.update { it.copy(preparedAt = now) }
    }

    /** Очистка кеша: подготовленное для работы без сети придётся скачать заново. */
    fun clearCache() {
        cache.clear()
        preparedFile.delete()
        _status.update { it.copy(preparedAt = 0L) }
    }

    private fun withPort(url: okhttp3.HttpUrl): String =
        if (url.port == 80 || url.port == 443) "" else ":${url.port}"

    private fun pathWithQuery(request: Request): String =
        request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")

    private fun wentOffline() = _status.update { it.copy(offline = true) }

    private fun wentOnline() {
        _status.update { it.copy(offline = false) }
        // Связь вернулась и очередь не пуста — пора отправлять отложенное.
        if (_status.value.pending > 0 && !flushingNow.get()) scope.launch { flush() }
    }

    private fun refreshPending() = _status.update { it.copy(pending = queue.list().size) }

    /** Бинар загрузки из очереди — так снимок виден без сети до отправки. */
    fun uploadBytes(opId: Long): ByteArray? = queue.uploadBytes(opId)

    /** «Повторить» запись, ждущую решения: она уйдёт при ближайшей отправке. */
    fun retry(opId: Long) {
        queue.retry(opId)
        refreshPending()
    }

    /**
     * Отдаёт очередь на сервер по одной, в порядке создания.
     *
     * Локальные номера меняются на серверные по мере создания: первая
     * запись создаёт клиента, вторая — заказ уже с настоящим номером
     * клиента в теле. Таблица номеров хранится в файле очереди, поэтому
     * заказ найдёт своего клиента и после обрыва посреди отправки.
     * Сеть снова пропала — останавливаемся и ждём следующего окна.
     *
     * Отказ сервера на отдельной записи не стопорит остальные: запись
     * остаётся в списке с текстом ошибки, а человек решит — повторить
     * или убрать. Записи, ссылающиеся на ещё не созданное, ждут его.
     * Создание, при котором соединение оборвалось на ответе, помечается
     * и больше само не отправляется: повтор мог бы задвоить деньги.
     *
     * Сеть — только на фоновом потоке: вызывают отсюда и экраны.
     */
    suspend fun flush() = withContext(Dispatchers.IO) {
        if (!flushingNow.compareAndSet(false, true)) return@withContext
        try {
            flushMutex.withLock {
                _status.update { it.copy(flushing = true) }
                refreshPending()
                val prefs = settings.preferences.first()
                if (!prefs.isAuthorized || _status.value.pending == 0) return@withLock

                val realIds = HashMap(queue.knownIds())
                val attempted = HashSet<Long>()
                var sent = 0
                var lostNetwork = false

                // Внешний круг подбирает записи, добавленные во время отправки.
                rounds@ while (true) {
                    val batch = queue.list().filter { it.id !in attempted }
                    if (batch.isEmpty()) break
                    for (op in batch) {
                        attempted += op.id
                        if (op.needsReview) continue

                        val spec = op.upload?.let { OfflineFabricate.remapUpload(it, realIds) }
                        val (path, body) = if (spec != null) spec.path to null else OfflineFabricate.remap(op, realIds)

                        if (OfflineFabricate.hasLocalIds(path, body, spec)) {
                            if (op.error != WAITS_FOR_PARENT) queue.setError(op.id, WAITS_FOR_PARENT)
                            continue
                        }

                        val request = buildReplay(op, path, body, spec, prefs.server.baseUrl, prefs.token)
                        if (request == null) {
                            queue.setError(op.id, "Файл для загрузки потерян — уберите запись")
                            continue
                        }

                        try {
                            val unauthorized = replayClient.newCall(request).execute().use { response ->
                                when {
                                    response.isSuccessful -> {
                                        val local = op.localId
                                        val real = realIdFrom(response.body.string())
                                        if (local != null && real != null) {
                                            // Сначала в файл, потом убираем запись: упади
                                            // приложение между ними — номер не потеряется.
                                            queue.rememberId(local, real)
                                            realIds[local] = real
                                        }
                                        queue.remove(op.id)
                                        sent++
                                        if (op.error.isNotBlank()) Diagnostics.info(
                                            "офлайн",
                                            "Очередь: ${op.describe()} — вышла после повтора",
                                        )
                                        false
                                    }

                                    response.code == 401 -> {
                                        // Токен истёк: остальные записи ждут того же отказа,
                                        // а приложение само попросит вход при следующем действии.
                                        queue.setError(op.id, "Сессия истекла — войдите заново")
                                        true
                                    }

                                    else -> {
                                        val text = runCatching { response.body.string() }.getOrNull()
                                            .orEmpty().take(200)
                                        queue.setError(op.id, "Сервер отклонил (${response.code}): $text")
                                        Diagnostics.warn(
                                            "офлайн",
                                            "Очередь: ${op.describe()} отклонена — ${response.code}",
                                        )
                                        false
                                    }
                                }
                            }
                            if (unauthorized) break@rounds
                        } catch (e: IOException) {
                            if (!notReachedServer(e) && op.method == "POST") {
                                // Создание могло дойти и примениться, а ответ потерялся.
                                // Повтор вслепую задвоил бы платёж или заказ — отдаём человеку.
                                queue.setError(
                                    op.id,
                                    "Сеть оборвалась при отправке. Проверьте на сервере: если запись " +
                                        "появилась — уберите её, если нет — нажмите «Повторить».",
                                    needsReview = true,
                                )
                                Diagnostics.warn("офлайн", "Очередь: ${op.describe()} — обрыв на отправке, ждёт решения")
                            }
                            lostNetwork = true
                            break@rounds
                        }
                        refreshPending()
                    }
                }

                if (lostNetwork) wentOffline() else _status.update { it.copy(offline = false) }
                if (sent > 0) Diagnostics.info("офлайн", "Отправлено записей из очереди: $sent")
            }
        } finally {
            refreshPending()
            _status.update { it.copy(flushing = false) }
            flushingNow.set(false)
        }
    }

    /** Повтор записи из очереди: тем же методом, по уже перемапленному адресу. */
    private fun buildReplay(
        op: QueuedWrite,
        path: String,
        body: String?,
        spec: UploadSpec?,
        baseUrl: String,
        token: String,
    ): Request? {
        val payload = if (spec != null) {
            val bytes = queue.uploadBytes(op.id) ?: return null
            OfflineFabricate.buildForm(spec, bytes)
        } else {
            body?.toRequestBody("application/json".toMediaTypeOrNull())
        }
        return Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", "Bearer $token")
            .header(replayMarker, "1")
            .apply { op.idemKey?.let { header("Idempotency-Key", it) } }
            .method(if (spec != null) "POST" else op.method, payload)
            .build()
    }

    /** Настоящий номер созданного — поле id в ответе сервера. */
    private fun realIdFrom(responseText: String): Long? = runCatching {
        val element = Json.parseToJsonElement(responseText)
        ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.longOrNull
    }.getOrNull()

    companion object {

        /** Текст у записи, которая ждёт отправки связанного создания. */
        private const val WAITS_FOR_PARENT =
            "Ждёт связанную запись: создание выше в очереди ещё не ушло на сервер"

        /**
         * Какие GET-ответы держим на телефоне. Списки и карточки — да;
         * тяжёлые бинарные выгрузки (книги Excel, снимок базы, файлы
         * архива, APK) — нет: для работы без сети они не нужны, а места
         * занимают как сотня списков.
         */
        fun cacheableGet(path: String): Boolean = when {
            path.startsWith("/media/") -> true
            path == "/api/v1/site" -> true
            !path.startsWith("/api/v1/admin/") -> false
            path.startsWith("/api/v1/admin/app/") -> false
            path == "/api/v1/admin/backup" -> false
            path == "/api/v1/admin/export.xlsx" -> false
            path.endsWith("/export") -> false
            path.endsWith("/raw") -> false
            else -> true
        }

        /**
         * Какие записи принимаются без сети. Не принимаются те, что без
         * живого сервера бессмысленны или опасны: вход и смена пароля,
         * выгрузки и загрузка книги Excel, выдача одноразового кода
         * доступа в кабинет, отправка документа клиенту в MAX, «заказ
         * по заявке» и «смета в заказ» (сервер возвращает уже готовый
         * объект со своим номером), слияние дублей и сброс сайта.
         */
        fun queueableWrite(method: String, path: String): Boolean {
            if (method != "POST" && method != "PUT" && method != "PATCH" && method != "DELETE") return false
            if (!path.startsWith("/api/v1/admin/")) return false
            return when {
                path.startsWith("/api/v1/admin/app/") -> false
                path == "/api/v1/admin/export.xlsx" || path == "/api/v1/admin/import" -> false
                path == "/api/v1/admin/backup" -> false
                path.endsWith("/export") || path.endsWith("/raw") -> false
                path.endsWith("/send-max") -> false
                path.endsWith("/order") && (path.contains("/requests/") || path.contains("/documents/")) -> false
                path == "/api/v1/admin/clients/merge" -> false
                path.endsWith("/access") -> false
                path == "/api/v1/admin/site/reset" -> false
                else -> true
            }
        }
    }
}
