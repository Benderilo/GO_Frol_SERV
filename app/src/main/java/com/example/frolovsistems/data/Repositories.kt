package com.example.frolovsistems.data

import com.example.frolovsistems.core.net.AccessCodeDto
import com.example.frolovsistems.core.net.AnalyticsDto
import com.example.frolovsistems.core.net.ApiClient
import com.example.frolovsistems.core.net.ApiException
import com.example.frolovsistems.core.net.AuditEntryDto
import com.example.frolovsistems.core.net.CatalogItemDto
import com.example.frolovsistems.core.net.StockMoveDto
import com.example.frolovsistems.core.net.OrderItemDto
import com.example.frolovsistems.core.net.WriteOffResultDto
import com.example.frolovsistems.core.net.CashListDto
import com.example.frolovsistems.core.net.CashOpBody
import com.example.frolovsistems.core.net.CashOpDto
import com.example.frolovsistems.core.net.ReportResponseDto
import com.example.frolovsistems.core.net.ClientDto
import com.example.frolovsistems.core.net.CompanyDto
import com.example.frolovsistems.core.net.ClientDuplicateDto
import com.example.frolovsistems.core.net.HealthDto
import com.example.frolovsistems.core.net.ImportSummaryDto
import com.example.frolovsistems.core.net.OrderDto
import com.example.frolovsistems.core.net.PaymentDto
import com.example.frolovsistems.core.net.PhotoDto
import com.example.frolovsistems.core.net.RequestDto
import com.example.frolovsistems.core.net.SiteContentDto
import com.example.frolovsistems.core.net.StatsDto
import com.example.frolovsistems.core.net.TaskDto
import com.example.frolovsistems.core.prefs.AppPreferences
import com.example.frolovsistems.core.prefs.AppSettings
import com.example.frolovsistems.core.prefs.ServerConfig
import com.example.frolovsistems.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * Оборачивает вызов API в Result, чтобы экраны не ловили исключения руками.
 * ApiException уже содержит текст, понятный пользователю.
 */
suspend fun <T> apiCall(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: ApiException) {
    Result.failure(e)
} catch (e: Exception) {
    Result.failure(ApiException(0, "unknown", e.message ?: "Неизвестная ошибка"))
}

/** Сессия: вход, выход, настройки подключения и темы. */
class SessionRepository(
    private val api: ApiClient,
    private val settings: AppSettings,
) {
    val preferences: Flow<AppPreferences> = settings.preferences

    suspend fun login(login: String, password: String): Result<Unit> = apiCall {
        val response = api.login(login, password)
        settings.saveSession(response.token, response.user.login.ifBlank { login })
    }

    suspend fun logout() = settings.clearSession()

    suspend fun changePassword(current: String, new: String): Result<Unit> =
        apiCall { api.changePassword(current, new) }

    /** Пробный запрос к /health — используется кнопкой «Проверить соединение». */
    suspend fun ping(config: ServerConfig): Result<HealthDto> = apiCall { api.health(config) }

    suspend fun saveServer(config: ServerConfig) = settings.saveServer(config)
    suspend fun saveTheme(mode: ThemeMode) = settings.saveTheme(mode)
    suspend fun saveDynamicColor(enabled: Boolean) = settings.saveDynamicColor(enabled)
}

/** Контент публичной страницы. */
class SiteRepository(private val api: ApiClient) {
    suspend fun load(): Result<SiteContentDto> = apiCall { api.siteContent() }
    suspend fun save(content: SiteContentDto): Result<SiteContentDto> = apiCall { api.saveSiteContent(content) }
    suspend fun reset(): Result<SiteContentDto> = apiCall { api.resetSiteContent() }
}

/** Клиенты, заказы и заявки. */
class CrmRepository(private val api: ApiClient) {
    suspend fun stats(): Result<StatsDto> = apiCall { api.stats() }
    suspend fun analytics(): Result<AnalyticsDto> = apiCall { api.analytics() }

    suspend fun orderDocument(orderId: Long, kind: String): Result<String> =
        apiCall { api.orderDocument(orderId, kind) }

    suspend fun orderItems(orderId: Long): Result<List<OrderItemDto>> = apiCall { api.orderItems(orderId) }
    suspend fun addOrderItem(orderId: Long, item: OrderItemDto): Result<OrderItemDto> =
        apiCall { api.addOrderItem(orderId, item) }
    suspend fun updateOrderItem(id: Long, item: OrderItemDto): Result<OrderItemDto> =
        apiCall { api.updateOrderItem(id, item) }
    suspend fun deleteOrderItem(id: Long): Result<Unit> = apiCall { api.deleteOrderItem(id) }
    suspend fun writeOffOrder(orderId: Long): Result<WriteOffResultDto> = apiCall { api.writeOffOrder(orderId) }

    suspend fun cash(from: String = "", to: String = "", direction: String = ""): Result<CashListDto> =
        apiCall { api.cash(from, to, direction) }
    suspend fun addCashOp(body: CashOpBody): Result<CashOpDto> = apiCall { api.addCashOp(body) }
    suspend fun deleteCashOp(id: Long): Result<Unit> = apiCall { api.deleteCashOp(id) }

    suspend fun report(from: String = "", to: String = ""): Result<ReportResponseDto> =
        apiCall { api.report(from, to) }
    suspend fun reportWorkbook(from: String = "", to: String = ""): Result<ByteArray> =
        apiCall { api.reportWorkbook(from, to) }

    suspend fun catalog(kind: String = "", withArchived: Boolean = false): Result<List<CatalogItemDto>> =
        apiCall { api.catalog(kind, withArchived) }
    suspend fun createCatalogItem(item: CatalogItemDto): Result<CatalogItemDto> =
        apiCall { api.createCatalogItem(item) }
    suspend fun updateCatalogItem(id: Long, item: CatalogItemDto): Result<CatalogItemDto> =
        apiCall { api.updateCatalogItem(id, item) }
    suspend fun deleteCatalogItem(id: Long): Result<Unit> = apiCall { api.deleteCatalogItem(id) }

    suspend fun stockMoves(itemId: Long = 0): Result<List<StockMoveDto>> =
        apiCall { api.stockMoves(itemId) }
    suspend fun addStockMove(itemId: Long, qtyMilli: Long, costKop: Long, note: String): Result<StockMoveDto> =
        apiCall { api.addStockMove(itemId, qtyMilli, costKop, note) }
    suspend fun deleteStockMove(id: Long): Result<Unit> = apiCall { api.deleteStockMove(id) }

    suspend fun company(): Result<CompanyDto> = apiCall { api.company() }
    suspend fun saveCompany(company: CompanyDto): Result<CompanyDto> = apiCall { api.saveCompany(company) }

    suspend fun clients(query: String = ""): Result<List<ClientDto>> = apiCall { api.clients(query) }
    suspend fun createClient(client: ClientDto): Result<ClientDto> = apiCall { api.createClient(client) }
    suspend fun updateClient(id: Long, client: ClientDto): Result<ClientDto> = apiCall { api.updateClient(id, client) }
    suspend fun deleteClient(id: Long): Result<Unit> = apiCall { api.deleteClient(id) }

    suspend fun orders(status: String = ""): Result<List<OrderDto>> = apiCall { api.orders(status) }
    suspend fun createOrder(order: OrderDto): Result<OrderDto> = apiCall { api.createOrder(order) }
    suspend fun updateOrder(id: Long, order: OrderDto): Result<OrderDto> = apiCall { api.updateOrder(id, order) }
    suspend fun deleteOrder(id: Long): Result<Unit> = apiCall { api.deleteOrder(id) }

    suspend fun requests(status: String = ""): Result<List<RequestDto>> = apiCall { api.requests(status) }

    // Платежи по заказу
    suspend fun orderPayments(orderId: Long): Result<List<PaymentDto>> =
        apiCall { api.orderPayments(orderId) }

    suspend fun addPayment(orderId: Long, amountKop: Long, note: String): Result<PaymentDto> =
        apiCall { api.addPayment(orderId, amountKop, note) }

    suspend fun deletePayment(id: Long): Result<Unit> = apiCall { api.deletePayment(id) }

    // Фотографии заказа
    suspend fun orderPhotos(orderId: Long): Result<List<PhotoDto>> = apiCall { api.orderPhotos(orderId) }
    suspend fun uploadPhoto(orderId: Long, bytes: ByteArray, fileName: String, caption: String = ""):
        Result<PhotoDto> = apiCall { api.uploadPhoto(orderId, bytes, fileName, caption) }
    suspend fun updatePhotoCaption(id: Long, caption: String): Result<PhotoDto> =
        apiCall { api.updatePhotoCaption(id, caption) }
    suspend fun deletePhoto(id: Long): Result<Unit> = apiCall { api.deletePhoto(id) }
    suspend fun mediaBytes(path: String): Result<ByteArray> = apiCall { api.mediaBytes(path) }

    // Выгрузка и загрузка Excel
    suspend fun exportWorkbook(): Result<ByteArray> = apiCall { api.exportWorkbook() }
    suspend fun importWorkbook(bytes: ByteArray, fileName: String): Result<ImportSummaryDto> =
        apiCall { api.importWorkbook(bytes, fileName) }

    // Доступ клиента в кабинет на сайте
    suspend fun grantAccess(clientId: Long): Result<AccessCodeDto> = apiCall { api.grantAccess(clientId) }
    suspend fun revokeAccess(clientId: Long): Result<Unit> = apiCall { api.revokeAccess(clientId) }
    suspend fun clientOrders(clientId: Long): Result<List<OrderDto>> = apiCall { api.clientOrders(clientId) }
    suspend fun setRequestStatus(id: Long, status: String): Result<RequestDto> =
        apiCall { api.updateRequestStatus(id, status) }
    suspend fun deleteRequest(id: Long): Result<Unit> = apiCall { api.deleteRequest(id) }

    // Задачи и напоминания
    suspend fun tasks(filter: String = ""): Result<List<TaskDto>> = apiCall { api.tasks(filter) }
    suspend fun createTask(task: TaskDto): Result<TaskDto> = apiCall { api.createTask(task) }
    suspend fun updateTask(id: Long, task: TaskDto): Result<TaskDto> = apiCall { api.updateTask(id, task) }
    suspend fun setTaskDone(id: Long, done: Boolean): Result<TaskDto> = apiCall { api.setTaskDone(id, done) }
    suspend fun deleteTask(id: Long): Result<Unit> = apiCall { api.deleteTask(id) }

    // Журнал действий и дубли клиентов
    suspend fun audit(limit: Int = 100): Result<List<AuditEntryDto>> = apiCall { api.audit(limit) }
    suspend fun clientDuplicates(): Result<List<ClientDuplicateDto>> = apiCall { api.clientDuplicates() }
    suspend fun mergeClients(keepId: Long, mergeIds: List<Long>): Result<Unit> =
        apiCall { api.mergeClients(keepId, mergeIds) }
}
