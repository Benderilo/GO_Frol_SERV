package com.example.frolovsistems.core.offline

import com.example.frolovsistems.core.diagnostics.Diagnostics
import com.example.frolovsistems.core.net.ApiClient
import com.example.frolovsistems.core.net.ApiException
import com.example.frolovsistems.core.prefs.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Фоновая подкачка всего, что нужно для работы без сети.
 *
 * Кеш сам по себе знает только то, что человек уже открывал. Подкачка
 * заранее проходит по приложению теми же запросами, что и экраны (через
 * тот же [ApiClient] — поэтому адреса в кеше совпадают один в один):
 *
 *  1. списки всех разделов со вкладками — дёшево, каждый раз;
 *  2. карточки по одной: документы с печатными формами, заказы со строками,
 *     платежами и фото, клиенты с сальдо и заказами, движения склада,
 *     папки архива, табели рабочих.
 *
 * Второй шаг идёт постепенно, с паузами, и пропускает то, что уже
 * скачано и с тех пор не менялось (по updatedAt), — поэтому повторные
 * проходы почти ничего не грузят. Связь пропала — тихо останавливаемся
 * до следующего раза.
 */
class OfflinePrefetch(
    private val api: ApiClient,
    private val offline: Offline,
    private val settings: AppSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    /** Связь пропала посреди подкачки: продолжать бессмысленно. */
    private class Stop : Exception()

    /** Подкачка в фоне, не дожидаясь: для старта приложения и кнопки в настройках. */
    fun launch() {
        scope.launch { run() }
    }

    /**
     * Один проход. [budget] — сколько карточек обойти за раз: фоновой
     * задаче раз в полчаса хватит части, а открытое приложение докачает
     * остальное само.
     */
    suspend fun run(budget: Int = Int.MAX_VALUE, pauseMs: Long = 150L) = withContext(Dispatchers.IO) {
        if (!running.compareAndSet(false, true)) return@withContext
        offline.setPrefetching(true)
        try {
            val prefs = settings.preferences.first()
            if (!prefs.isAuthorized || !prefs.server.isValid) return@withContext
            Pass(prefs.server.baseUrl, budget, pauseMs).go()
        } catch (_: Stop) {
            // Сеть пропала — докачаем в следующий раз.
        } finally {
            offline.setPrefetching(false)
            running.set(false)
        }
    }

    private inner class Pass(val base: String, var left: Int, val pauseMs: Long) {

        /**
         * Запрос подкачки. Ответ из кеша при обрыве перехватчик выдаёт
         * как обычный, поэтому о пропаже сети узнаём по его статусу.
         * Ошибка сервера на отдельном адресе (404, 500) проход не рвёт.
         */
        suspend fun <T> get(block: suspend () -> T): T? {
            val result = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                null
            }
            if (offline.status.value.offline) throw Stop()
            return result
        }

        /**
         * Нужно ли качать карточку заново: не скачивали, сервер правил её
         * позже нашей копии, или копия просто старая.
         */
        fun stale(pathWithQuery: String, updatedAt: String = "", maxAgeHours: Long = 12): Boolean {
            val at = offline.cachedAt(base + pathWithQuery) ?: return true
            val changed = runCatching { OffsetDateTime.parse(updatedAt).toInstant().toEpochMilli() }.getOrNull()
            if (changed != null && changed > at) return true
            return System.currentTimeMillis() - at > TimeUnit.HOURS.toMillis(maxAgeHours)
        }

        /** Одна карточка из бюджета прохода — с паузой, чтобы не душить сервер. */
        suspend fun card(block: suspend () -> Unit): Boolean {
            if (left <= 0) return false
            left--
            block()
            delay(pauseMs)
            return true
        }

        suspend fun media(path: String) {
            if (path.isBlank() || path.startsWith("offline://")) return
            if (offline.cachedAt(base + path) != null) return
            get { api.mediaBytes(path) }
        }

        suspend fun go() {
            // --- 1. Списки разделов со всеми вкладками, как их запрашивают экраны.
            get { api.stats() }
            get { api.analytics() }
            get { api.company() }
            get { api.accounts() }
            get { api.reports() }
            get { api.siteContent() }
            val works = get { api.siteWorks() }.orEmpty()
            for (status in listOf("", "new", "in_progress", "done", "spam")) get { api.requests(status) }
            for (status in listOf("new", "in_progress", "done", "canceled")) get { api.orders(status) }
            val orders = get { api.orders() }.orEmpty()
            val clients = get { api.clients() }.orEmpty()
            for (filter in listOf("", "today", "overdue", "done")) get { api.tasks(filter) }
            val catalog = get { api.catalog() }.orEmpty()
            get { api.catalog(withArchived = true) }
            get { api.stockMoves(0) }
            val workers = get { api.workers(includeInactive = true) }.orEmpty()
            get { api.workers(includeInactive = false) }
            val documents = get { api.documents() }.orEmpty()

            val today = LocalDate.now()
            val monthStart = today.withDayOfMonth(1)
            val lastMonth = monthStart.minusMonths(1)
            for (direction in listOf("", "in", "out")) {
                get { api.cash(monthStart.toString(), today.toString(), direction) }
            }
            get { api.cash(lastMonth.toString(), monthStart.minusDays(1).toString(), "") }
            get { api.payroll(YearMonth.now().toString()) }
            get { api.payroll(YearMonth.now().minusMonths(1).toString()) }

            // --- 2. Карточки: документы первыми — без них не выставить счёт на объекте.
            for (doc in documents) {
                val cardStale = stale("/api/v1/admin/documents/${doc.id}", doc.updatedAt, maxAgeHours = 24)
                val printStale = stale("/api/v1/admin/documents/${doc.id}/print", doc.updatedAt, maxAgeHours = 24)
                if (!cardStale && !printStale) continue
                if (!card {
                        if (cardStale) get { api.document(doc.id) }
                        get { api.documentPrint(doc.id) }
                    }
                ) return
            }

            for (order in orders) {
                if (!stale("/api/v1/admin/orders/${order.id}/items", order.updatedAt)) continue
                if (!card {
                        get { api.orderItems(order.id) }
                        get { api.orderPayments(order.id) }
                        get { api.orderPhotos(order.id) }.orEmpty().forEach { media(it.thumbUrl) }
                    }
                ) return
            }

            for (client in clients) {
                if (!stale("/api/v1/admin/clients/${client.id}/balance", client.updatedAt)) continue
                if (!card {
                        get { api.clientBalance(client.id) }
                        get { api.clientOrders(client.id) }
                    }
                ) return
            }

            for (item in catalog) {
                if (!stale("/api/v1/admin/stock?itemId=${item.id}&limit=200", item.updatedAt)) continue
                if (!card { get { api.stockMoves(item.id) } }) return
            }

            val monthEnd = YearMonth.now().atEndOfMonth()
            for (worker in workers) {
                if (!card { get { api.workDays(monthStart.toString(), monthEnd.toString(), worker.id) } }) return
            }

            // Архив файлов: обходим дерево папок вширь. Сами файлы не качаем —
            // они тяжёлые, а без сети нужен прежде всего список.
            val folders = ArrayDeque<Long?>().apply { add(null) }
            val seen = HashSet<Long>()
            while (folders.isNotEmpty()) {
                val id = folders.removeFirst()
                var listing: com.example.frolovsistems.core.net.FolderListingDto? = null
                if (!card { listing = get { api.folderListing(id) } }) return
                listing?.folders?.forEach { if (seen.add(it.id)) folders.addLast(it.id) }
            }

            works.forEach { media(it.thumbUrl) }

            offline.markPrepared()
            Diagnostics.info("офлайн", "Подкачка для работы без сети прошла до конца")
        }
    }
}
