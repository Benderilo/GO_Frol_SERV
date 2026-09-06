package com.example.frolovsistems.core.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ------------------------------- Авторизация -------------------------------

@Serializable
data class LoginRequest(val login: String, val password: String)

@Serializable
data class LoginResponse(
    val token: String,
    val expiresAt: String = "",
    val user: UserDto = UserDto(),
)

@Serializable
data class UserDto(
    val id: Long = 0,
    val login: String = "",
    val displayName: String = "",
    val role: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

// ------------------------------ Контент сайта ------------------------------

@Serializable
data class SiteContentDto(
    val siteName: String = "",
    val tagline: String = "",
    val ticker: TickerDto = TickerDto(),
    val hero: HeroDto = HeroDto(),
    val about: AboutDto = AboutDto(),
    val services: List<ServiceDto> = emptyList(),
    val advantages: List<AdvantageDto> = emptyList(),
    val contacts: ContactsDto = ContactsDto(),
    val appearance: AppearanceDto = AppearanceDto(),
    val footerNote: String = "",
    val revision: Long = 0,
    val updatedAt: String = "",
)

@Serializable
data class TickerDto(
    val enabled: Boolean = true,
    val text: String = "",
    val speedSec: Int = 25,
)

@Serializable
data class HeroDto(
    val title: String = "",
    val subtitle: String = "",
    val primaryCta: String = "",
    val secondaryCta: String = "",
    val badge: String = "",
)

@Serializable
data class AboutDto(val title: String = "", val text: String = "")

@Serializable
data class ServiceDto(
    val icon: String = "bolt",
    val title: String = "",
    val description: String = "",
    val price: String = "",
)

@Serializable
data class AdvantageDto(val value: String = "", val label: String = "")

@Serializable
data class ContactsDto(
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val city: String = "",
    val telegram: String = "",
    val whatsapp: String = "",
    val workHours: String = "",
)

@Serializable
data class AppearanceDto(
    val accent: String = "#F5A524",
    val accentAlt: String = "#2563EB",
    val defaultMode: String = "auto",
)

// ------------------------------ Касса --------------------------------------

/** Направления движения денег и способы оплаты — те же значения у сервера. */
object CashDirection {
    const val IN = "in"
    const val OUT = "out"
}

object CashMethod {
    const val CASH = "cash"
    const val CARD = "card"
    const val ACCOUNT = "account"

    val all = listOf(CASH, CARD, ACCOUNT)

    fun label(method: String): String = when (method) {
        CASH -> "Наличные"
        CARD -> "Карта"
        ACCOUNT -> "Счёт"
        else -> method
    }
}

/**
 * Строка кассы. Сумма всегда положительная, знак несёт [direction]:
 * «расход −500» ввести нельзя, направление задаётся кнопкой.
 */
@Serializable
data class CashOpDto(
    val id: Long = 0,
    val direction: String = CashDirection.IN,
    val amountKop: Long = 0,
    val method: String = CashMethod.CASH,
    val category: String = "",
    val orderId: Long? = null,
    val clientId: Long? = null,
    val note: String = "",
    val happenedAt: String = "",
    val createdAt: String = "",
    val orderTitle: String = "",
    val clientName: String = "",
) {
    val isIncome: Boolean get() = direction == CashDirection.IN
}

/** Тело запроса на операцию — остальное проставляет сервер. */
@Serializable
data class CashOpBody(
    val direction: String = CashDirection.IN,
    val amountKop: Long = 0,
    val method: String = CashMethod.CASH,
    val category: String = "",
    val note: String = "",
    val happenedAt: String = "",
)

/** Список операций вместе с итогами: их считает сервер, а не экран. */
@Serializable
data class CashListDto(
    val items: List<CashOpDto> = emptyList(),
    val count: Int = 0,
    val incomeKop: Long = 0,
    val expenseKop: Long = 0,
    /** Остаток всегда общий, а не за выбранный период. */
    val balanceKop: Long = 0,
)

// ------------------------------ Отчёт --------------------------------------

@Serializable
data class CategoryTotalDto(
    val category: String = "",
    val direction: String = CashDirection.IN,
    val amountKop: Long = 0,
    val count: Long = 0,
)

@Serializable
data class MaterialUsageDto(
    val name: String = "",
    val unit: String = "",
    val qtyMilli: Long = 0,
    val costKop: Long = 0,
    val stockLeftMilli: Long = 0,
)

@Serializable
data class PeriodReportDto(
    val from: String = "",
    val to: String = "",
    val openingKop: Long = 0,
    val incomeKop: Long = 0,
    val expenseKop: Long = 0,
    val closingKop: Long = 0,
    val byCategory: List<CategoryTotalDto> = emptyList(),
    val byMethod: List<CategoryTotalDto> = emptyList(),
    val revenueKop: Long = 0,
    val ordersClosed: Long = 0,
    val costKop: Long = 0,
    val debtKop: Long = 0,
    val debtOrders: Long = 0,
    val materials: List<MaterialUsageDto> = emptyList(),
)

/** Ответ ручки отчёта: сам отчёт плюс заработок, посчитанный сервером. */
@Serializable
data class ReportResponseDto(
    val report: PeriodReportDto = PeriodReportDto(),
    val profitKop: Long = 0,
)

// --------------------------- Состав заказа ---------------------------------

/**
 * Строка состава заказа. Название и цены скопированы из справочника в момент
 * добавления: позицию потом переименуют или она подорожает, а в выданном
 * клиенту перечне должно остаться то, о чём договаривались.
 *
 * [stockMoveId] заполнен, если материал по строке уже списан со склада.
 */
@Serializable
data class OrderItemDto(
    val id: Long = 0,
    val orderId: Long = 0,
    val catalogId: Long? = null,
    val kind: String = CatalogKind.SERVICE,
    val name: String = "",
    val unit: String = "шт.",
    val qtyMilli: Long = 1000,
    val priceKop: Long = 0,
    val costKop: Long = 0,
    val sort: Long = 0,
    val stockMoveId: Long? = null,
    val createdAt: String = "",
    val totalKop: Long = 0,
    val totalCostKop: Long = 0,
) {
    val isMaterial: Boolean get() = kind == CatalogKind.MATERIAL
    val writtenOff: Boolean get() = stockMoveId != null
}

/** Тело запроса на строку — итоги и признак списания считает сервер. */
@Serializable
data class OrderItemUpsert(
    val catalogId: Long? = null,
    val kind: String = CatalogKind.SERVICE,
    val name: String = "",
    val unit: String = "шт.",
    val qtyMilli: Long = 1000,
    val priceKop: Long = 0,
    val costKop: Long = 0,
) {
    companion object {
        fun of(item: OrderItemDto) = OrderItemUpsert(
            catalogId = item.catalogId,
            kind = item.kind,
            name = item.name,
            unit = item.unit,
            qtyMilli = item.qtyMilli,
            priceKop = item.priceKop,
            costKop = item.costKop,
        )
    }
}

/** Ответ на списание материалов по заказу. */
@Serializable
data class WriteOffResultDto(
    val written: Int = 0,
    val items: List<OrderItemDto> = emptyList(),
)

// ------------------------- Справочник и склад ------------------------------

/** Виды позиций справочника — те же значения принимает сервер. */
object CatalogKind {
    const val SERVICE = "service"
    const val WORK = "work"
    const val MATERIAL = "material"

    val all = listOf(SERVICE, WORK, MATERIAL)

    fun label(kind: String): String = when (kind) {
        SERVICE -> "Услуга"
        WORK -> "Работа"
        MATERIAL -> "Материал"
        else -> kind
    }

    fun plural(kind: String): String = when (kind) {
        SERVICE -> "Услуги"
        WORK -> "Работы"
        MATERIAL -> "Материалы"
        else -> kind
    }
}

/**
 * Позиция справочника. [priceKop] — цена продажи, [costKop] — закупки;
 * разница между ними и есть заработок. [stockMilli] — остаток в тысячных
 * долях единицы, считается сервером из движений и есть только у материалов.
 */
@Serializable
data class CatalogItemDto(
    val id: Long = 0,
    val kind: String = CatalogKind.SERVICE,
    val name: String = "",
    val unit: String = "шт.",
    val priceKop: Long = 0,
    val costKop: Long = 0,
    val note: String = "",
    val archived: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = "",
    val stockMilli: Long = 0,
) {
    val isMaterial: Boolean get() = kind == CatalogKind.MATERIAL
}

/** Тело запроса на позицию — без остатка и дат, их ведёт сервер. */
@Serializable
data class CatalogUpsert(
    val kind: String = CatalogKind.SERVICE,
    val name: String = "",
    val unit: String = "шт.",
    val priceKop: Long = 0,
    val costKop: Long = 0,
    val note: String = "",
    val archived: Boolean = false,
) {
    companion object {
        fun of(item: CatalogItemDto) = CatalogUpsert(
            kind = item.kind,
            name = item.name,
            unit = item.unit,
            priceKop = item.priceKop,
            costKop = item.costKop,
            note = item.note,
            archived = item.archived,
        )
    }
}

/** Движение материала: [qtyMilli] положительное на приходе, отрицательное на списании. */
@Serializable
data class StockMoveDto(
    val id: Long = 0,
    val itemId: Long = 0,
    val orderId: Long? = null,
    val qtyMilli: Long = 0,
    val costKop: Long = 0,
    val note: String = "",
    val createdAt: String = "",
    val itemName: String = "",
    val unit: String = "",
    val orderTitle: String = "",
) {
    val isIncome: Boolean get() = qtyMilli > 0
}

/** Тело запроса на движение — остальное сервер заполняет сам. */
@Serializable
data class StockMoveBody(
    val qtyMilli: Long = 0,
    val costKop: Long = 0,
    val note: String = "",
)

// ------------------------------ Реквизиты ИП -------------------------------

/**
 * Реквизиты ИП: подставляются в печатные документы. Всё строками —
 * ИНН, БИК и счёт это идентификаторы, у них значимы ведущие нули.
 *
 * [updatedAt] заполняет сервер: присланное значение он отбрасывает.
 */
@Serializable
data class CompanyDto(
    val shortName: String = "",
    val fullName: String = "",
    val inn: String = "",
    val ogrnip: String = "",
    val address: String = "",
    val phone: String = "",
    val email: String = "",
    val site: String = "",
    val bankName: String = "",
    val bankBik: String = "",
    val bankAccount: String = "",
    val bankCorrAccount: String = "",
    val signerName: String = "",
    val signerTitle: String = "",
    val taxNote: String = "",
    val footerNote: String = "",
    val updatedAt: String = "",
) {
    /** Без наименования и ИНН документ выйдет обезличенным — печатать нельзя. */
    val readyForDocuments: Boolean
        get() = shortName.isNotBlank() && inn.isNotBlank()
}

// ---------------------------------- CRM ------------------------------------

@Serializable
data class ClientDto(
    val id: Long = 0,
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val note: String = "",
    val tag: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    /** Открыт ли клиенту вход в кабинет на сайте. */
    val portalEnabled: Boolean = false,
    val portalLastLogin: String = "",
    /** Считается сервером в списках; при сохранении игнорируется. */
    val ordersCount: Long = 0,
    val revenueDoneKop: Long = 0,
)

/**
 * Тело запроса на создание и правку. Отправляем ровно то, что человек
 * правит руками: дату создания, момент закрытия, сумму платежей и счётчики
 * считает сервер сам. Лишнее поле он отвергает вместе со всем запросом,
 * отвечая «Некорректный JSON», — из-за этого приложение с новыми полями
 * перестаёт создавать записи на сервере постарше.
 */
@Serializable
data class ClientUpsert(
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val note: String = "",
    val tag: String = "",
) {
    companion object {
        fun of(client: ClientDto) = ClientUpsert(
            name = client.name,
            phone = client.phone,
            email = client.email,
            address = client.address,
            note = client.note,
            tag = client.tag,
        )
    }
}

/** То же для заказа — пояснение у [ClientUpsert]. */
@Serializable
data class OrderUpsert(
    val clientId: Long? = null,
    val title: String = "",
    val description: String = "",
    val status: String = "new",
    val priceKop: Long = 0,
    val dueDate: String = "",
) {
    companion object {
        fun of(order: OrderDto) = OrderUpsert(
            clientId = order.clientId,
            title = order.title,
            description = order.description,
            status = order.status,
            priceKop = order.priceKop,
            dueDate = order.dueDate,
        )
    }
}

/** Ответ на выдачу доступа: код показывается один раз и больше не хранится. */
@Serializable
data class AccessCodeDto(val code: String = "", val phone: String = "")

@Serializable
data class OrderDto(
    val id: Long = 0,
    val clientId: Long? = null,
    val clientName: String = "",
    val title: String = "",
    val description: String = "",
    val status: String = "new",
    val priceKop: Long = 0,
    val dueDate: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    /** Момент перевода в «завершён» — пусто, пока заказ не закрыт. */
    val closedAt: String = "",
    /** Сколько денег пришло по заказу — сервер считает из платежей. */
    val paidKop: Long = 0,
    val photos: List<PhotoDto> = emptyList(),
    val photoCount: Int = 0,
    /** Сколько позиций в составе; 0 — цена вписана руками. */
    val itemsCount: Int = 0,
    /** Во сколько заказ обошёлся по закупке — считается из состава. */
    val costKop: Long = 0,
) {
    /** Остаток к оплате; отрицательный — оплачено больше цены (аванс). */
    val balanceKop: Long get() = priceKop - paidKop

    /** У заказа с составом цену определяют строки, а не поле ввода. */
    val hasItems: Boolean get() = itemsCount > 0
}

/** Поступление денег по заказу. */
@Serializable
data class PaymentDto(
    val id: Long = 0,
    val orderId: Long = 0,
    val amountKop: Long = 0,
    val note: String = "",
    val createdAt: String = "",
)

@Serializable
data class PaymentBody(val amountKop: Long, val note: String = "")

/** Снимок по заказу. url и thumbUrl приходят от сервера готовыми. */
@Serializable
data class PhotoDto(
    val id: Long = 0,
    val orderId: Long = 0,
    val token: String = "",
    val mime: String = "",
    val size: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val caption: String = "",
    val sort: Int = 0,
    val createdAt: String = "",
    val url: String = "",
    val thumbUrl: String = "",
)

@Serializable
data class PhotoCaptionBody(val caption: String)

@Serializable
data class RequestDto(
    val id: Long = 0,
    val name: String = "",
    val phone: String = "",
    val message: String = "",
    val source: String = "site",
    val status: String = "new",
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Serializable
data class StatusUpdate(val status: String)

@Serializable
data class StatsDto(
    val clients: Long = 0,
    val orders: Long = 0,
    val ordersActive: Long = 0,
    val ordersDone: Long = 0,
    val requestsNew: Long = 0,
    val revenueTotalKop: Long = 0,
    val revenueActiveKop: Long = 0,
    /** Задолженность клиентов по незакрытым заказам. */
    val debtOutstandingKop: Long = 0,
    /** Из неё — по заказам с прошедшим сроком. */
    val debtOverdueKop: Long = 0,
)

// ------------------------------- Аналитика ----------------------------------

@Serializable
data class AnalyticsDto(
    val generatedAt: String = "",
    val orders: AnalyticsOrdersDto = AnalyticsOrdersDto(),
    val revenue: AnalyticsRevenueDto = AnalyticsRevenueDto(),
    val payments: AnalyticsPaymentsDto = AnalyticsPaymentsDto(),
    val debt: AnalyticsDebtDto = AnalyticsDebtDto(),
    val clients: AnalyticsClientsDto = AnalyticsClientsDto(),
    val requests: AnalyticsRequestsDto = AnalyticsRequestsDto(),
    val monthly: List<AnalyticsMonthDto> = emptyList(),
    val topClients: List<AnalyticsTopClientDto> = emptyList(),
)

@Serializable
data class AnalyticsOrdersDto(
    val total: Long = 0,
    val new: Long = 0,
    val inProgress: Long = 0,
    val done: Long = 0,
    val canceled: Long = 0,
    /** Доля завершённых, %. */
    val completionRate: Double = 0.0,
    /** Средний чек завершённого заказа, копейки. */
    val avgPriceKop: Long = 0,
    /** Средний срок от создания до закрытия, дней. */
    val avgCycleDays: Double = 0.0,
)

@Serializable
data class AnalyticsRevenueDto(
    val totalKop: Long = 0,
    val activeKop: Long = 0,
    val avgOrderKop: Long = 0,
    val thisMonthKop: Long = 0,
    val lastMonthKop: Long = 0,
    /** Изменение к прошлому месяцу, %; 0, если в прошлом месяце выручки не было. */
    val growthPct: Double = 0.0,
)

/** Поступления денег — факт, в отличие от выручки по закрытым заказам. */
@Serializable
data class AnalyticsPaymentsDto(
    val totalKop: Long = 0,
    val thisMonthKop: Long = 0,
    val lastMonthKop: Long = 0,
    /** Изменение к прошлому месяцу, %; 0, если в прошлом месяце поступлений не было. */
    val growthPct: Double = 0.0,
)

/** Задолженность клиентов. */
@Serializable
data class AnalyticsDebtDto(
    val outstandingKop: Long = 0,
    val overdueKop: Long = 0,
    /** Сколько заказов ждут оплату. */
    val ordersWithDebt: Long = 0,
)

@Serializable
data class AnalyticsClientsDto(
    val total: Long = 0,
    val newThisMonth: Long = 0,
    val withOrders: Long = 0,
    /** Клиентов с двумя и более заказами. */
    val repeat: Long = 0,
    /** Доля повторных от клиентов с заказами, %. */
    val repeatRate: Double = 0.0,
)

@Serializable
data class AnalyticsRequestsDto(
    val total: Long = 0,
    val new: Long = 0,
    val inProgress: Long = 0,
    val done: Long = 0,
    val spam: Long = 0,
    /** Доля обработанных от неспамовых заявок, %. */
    val conversion: Double = 0.0,
)

/** Точка помесячного ряда. */
@Serializable
data class AnalyticsMonthDto(
    /** «2026-08». */
    val month: String = "",
    val ordersCreated: Long = 0,
    val ordersDone: Long = 0,
    val revenueKop: Long = 0,
    /** Сколько денег реально поступило за месяц, копейки. */
    val paymentsKop: Long = 0,
    val newClients: Long = 0,
    val requests: Long = 0,
)

@Serializable
data class AnalyticsTopClientDto(
    val clientId: Long = 0,
    val name: String = "",
    val ordersTotal: Long = 0,
    val revenueDoneKop: Long = 0,
    val revenueAllKop: Long = 0,
)

/**
 * Итог загрузки книги Excel — раздел на поле, в том же порядке, в каком
 * сервер пишет строки в базу. У всех полей значение по умолчанию: сервер
 * постарше отвечает без новых разделов, и разбор от этого не падает.
 */
@Serializable
data class ImportSummaryDto(
    val clients: CountPairDto = CountPairDto(),
    val catalog: CountPairDto = CountPairDto(),
    val orders: CountPairDto = CountPairDto(),
    val stockMoves: CountPairDto = CountPairDto(),
    val orderItems: CountPairDto = CountPairDto(),
    val requests: CountPairDto = CountPairDto(),
    /** Появилось, когда в книгу добавился лист «Платежи»; старые ответы без него. */
    val payments: CountPairDto = CountPairDto(),
    val cash: CountPairDto = CountPairDto(),
    val tasks: CountPairDto = CountPairDto(),
    /** Реквизиты ИП — одна запись, поэтому не пара счётчиков, а «обновлены или нет». */
    val company: Boolean = false,
    val warnings: List<String> = emptyList(),
) {
    /** Разделы для показа: подписью вперёд, чтобы экран не знал порядок полей. */
    val sections: List<Pair<String, CountPairDto>>
        get() = listOf(
            "Клиенты" to clients,
            "Склад" to catalog,
            "Заказы" to orders,
            "Движения склада" to stockMoves,
            "Состав заказов" to orderItems,
            "Заявки" to requests,
            "Платежи" to payments,
            "Касса" to cash,
            "Задачи" to tasks,
        )

    val totalCreated: Int get() = sections.sumOf { it.second.created }
    val totalUpdated: Int get() = sections.sumOf { it.second.updated }
}

@Serializable
data class CountPairDto(val created: Int = 0, val updated: Int = 0)

@Serializable
data class HealthDto(
    val status: String = "",
    val service: String = "",
    val version: String = "",
)

@Serializable
data class ListResponse<T>(
    val items: List<T> = emptyList(),
    val count: Int = 0,
)

@Serializable
data class TaskDto(
    val id: Long = 0,
    val clientId: Long? = null,
    val orderId: Long? = null,
    /** Ненулевой у подзадачи; глубина вложенности не ограничена. */
    val parentId: Long? = null,
    val clientName: String = "",
    val title: String = "",
    val note: String = "",
    val dueDate: String = "",
    val done: Boolean = false,
    val priority: String = "normal",
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Serializable
data class AuditEntryDto(
    val id: Long = 0,
    val entityType: String = "",
    val entityId: Long = 0,
    val action: String = "",
    val detail: String = "",
    val createdAt: String = "",
)

@Serializable
data class ClientDuplicateDto(
    val phone: String = "",
    val clientIds: List<Long> = emptyList(),
    val clientNames: List<String> = emptyList(),
)

@Serializable
data class ApiErrorBody(
    @SerialName("error") val code: String = "",
    val message: String = "",
)

@Serializable
data class TaskDoneBody(val done: Boolean)

@Serializable
data class MergeClientsBody(val keepId: Long, val mergeIds: List<Long>)
