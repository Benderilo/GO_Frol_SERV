package com.example.frolovsistems.ui.preview

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import com.example.frolovsistems.core.net.AccountBalanceDto
import com.example.frolovsistems.core.net.AnalyticsClientsDto
import com.example.frolovsistems.core.net.AnalyticsDebtDto
import com.example.frolovsistems.core.net.AnalyticsDto
import com.example.frolovsistems.core.net.AnalyticsMonthDto
import com.example.frolovsistems.core.net.AnalyticsOrdersDto
import com.example.frolovsistems.core.net.AnalyticsPaymentsDto
import com.example.frolovsistems.core.net.AnalyticsRequestsDto
import com.example.frolovsistems.core.net.AnalyticsRevenueDto
import com.example.frolovsistems.core.net.AnalyticsTopClientDto
import com.example.frolovsistems.core.net.AuditEntryDto
import com.example.frolovsistems.core.net.CashOpDto
import com.example.frolovsistems.core.net.CatalogItemDto
import com.example.frolovsistems.core.net.ClientDto
import com.example.frolovsistems.core.net.CompanyDto
import com.example.frolovsistems.core.net.DocumentDto
import com.example.frolovsistems.core.net.FolderDto
import com.example.frolovsistems.core.net.FolderListingDto
import com.example.frolovsistems.core.net.OrderDto
import com.example.frolovsistems.core.net.ReportInfoDto
import com.example.frolovsistems.core.net.ReportParamDto
import com.example.frolovsistems.core.net.RequestDto
import com.example.frolovsistems.core.net.StatsDto
import com.example.frolovsistems.core.net.StoredFileDto
import com.example.frolovsistems.core.net.TaskDto
import com.example.frolovsistems.core.net.WorkerDto
import com.example.frolovsistems.ui.theme.FrolovTheme
import com.example.frolovsistems.ui.theme.ThemeMode
import java.time.LocalDate

/**
 * Обёртка превью экрана: тёмная тема приложения и фон, как на телефоне.
 * Превью в APK не попадают — ui-tooling подключён только к debug.
 */
@Composable
internal fun PreviewScreen(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    FrolovTheme(themeMode = themeMode) {
        Surface(color = MaterialTheme.colorScheme.background) { content() }
    }
}

/**
 * Образцы данных для превью в Android Studio: похожи на настоящие, чтобы
 * разметку правили на том, что увидит электрик, а не на пустых списках.
 * Даты — от сегодняшнего дня, чтобы календарь и сроки не устаревали.
 */
internal object PreviewData {

    private val today: LocalDate = LocalDate.now()
    private fun day(shift: Long): String = today.plusDays(shift).toString()
    private fun stamp(shift: Long, time: String = "10:30:00"): String = "${day(shift)}T${time}Z"

    val clients = listOf(
        ClientDto(
            id = 1, name = "Лопатин Сергей", phone = "+79372547288", email = "lopatin@mail.ru",
            address = "Ленина 93а", tag = "vip", note = "Коттедж, щит на 36 модулей",
            ordersCount = 4, revenueDoneKop = 18_500_000, createdAt = stamp(-120),
        ),
        ClientDto(
            id = 2, name = "ООО «Вокзальная 9»", phone = "+79171234567", address = "Вокзальная 9",
            inn = "6312345678", ordersCount = 3, revenueDoneKop = 4_200_000, createdAt = stamp(-60),
        ),
        ClientDto(
            id = 3, name = "Иванова Мария", phone = "+79275550011", address = "Садовая 14, кв. 7",
            ordersCount = 1, createdAt = stamp(-3),
        ),
    )

    val orders = listOf(
        OrderDto(
            id = 41, clientId = 2, clientName = "ООО «Вокзальная 9»", title = "Вокзальная 9",
            description = "Подключение 50 кВт к щиту", status = "new", priceKop = 500_000,
            createdAt = stamp(-17), itemsCount = 3,
        ),
        OrderDto(
            id = 40, clientId = 1, clientName = "Лопатин Сергей", title = "Коттедж, Ленина 93а",
            description = "Замена щита, УЗО на группы", status = "in_progress", priceKop = 4_800_000,
            paidKop = 2_000_000, dueDate = day(5), createdAt = stamp(-9), itemsCount = 12, photoCount = 4,
        ),
        OrderDto(
            id = 39, clientId = 3, clientName = "Иванова Мария", title = "Кухня, Садовая 14",
            description = "Демонтаж старой проводки, разводка под технику", status = "done",
            priceKop = 2_000_000, paidKop = 2_000_000, dueDate = day(-2), createdAt = stamp(-20),
            closedAt = stamp(-2), itemsCount = 8,
        ),
        OrderDto(
            id = 38, clientId = 2, clientName = "ООО «Вокзальная 9»", title = "Освещение склада",
            description = "Прожекторы 12 шт., кабель по лотку", status = "in_progress", priceKop = 3_150_000,
            dueDate = day(-1), createdAt = stamp(-30), itemsCount = 6,
        ),
    )

    val requests = listOf(
        RequestDto(
            id = 12, name = "Пётр", phone = "+79608887766", status = "new", createdAt = stamp(0, "08:12:00"),
            message = "Выбивает автомат на кухне, когда включаю духовку. Можно сегодня?",
        ),
        RequestDto(
            id = 11, name = "Анна Сергеевна", phone = "+79171112233", status = "in_progress",
            createdAt = stamp(-1), message = "Нужно перенести розетки в спальне, 4 точки.",
        ),
        RequestDto(
            id = 10, name = "ТСЖ «Солнечный»", phone = "+78462000000", status = "done", createdAt = stamp(-6),
            message = "Замер сопротивления изоляции в подъезде.",
        ),
    )

    val tasks = listOf(
        TaskDto(id = 1, title = "Купить УЗО 40А × 6", dueDate = day(0), priority = "high", clientName = "Лопатин Сергей"),
        TaskDto(id = 2, parentId = 1, title = "Сверить сечения с проектом", dueDate = day(0)),
        TaskDto(id = 3, title = "Позвонить в ТСЖ по допуску в щитовую", dueDate = day(1)),
        TaskDto(id = 4, title = "Сдать акт по Садовой 14", dueDate = day(-2), done = true, clientName = "Иванова Мария"),
    )

    val workers = listOf(
        WorkerDto(id = 1, name = "Андрей Колесов", phone = "+79270001122", position = "Электромонтажник", salaryKop = 350_000),
        WorkerDto(id = 2, name = "Дмитрий Орлов", position = "Подсобник", salaryKop = 200_000),
        WorkerDto(id = 3, name = "Наталья Фролова", position = "Бухгалтер", salaryType = "month", salaryKop = 4_500_000),
    )

    val catalog = listOf(
        CatalogItemDto(id = 1, kind = "material", name = "Кабель ВВГнг-LS 3×2,5", unit = "м", priceKop = 11_000, costKop = 8_200, stockMilli = 184_000),
        CatalogItemDto(id = 2, kind = "material", name = "Автомат C16 1P", unit = "шт.", priceKop = 42_000, costKop = 31_000, stockMilli = 24_000),
        CatalogItemDto(id = 3, kind = "material", name = "УЗО 40А 30мА", unit = "шт.", priceKop = 250_000, costKop = 190_000, stockMilli = 2_000),
        CatalogItemDto(id = 4, kind = "service", name = "Монтаж розетки", unit = "шт.", priceKop = 50_000),
        CatalogItemDto(id = 5, kind = "service", name = "Сборка щита до 24 модулей", unit = "шт.", priceKop = 900_000),
    )

    val cash = listOf(
        CashOpDto(id = 1, direction = "in", amountKop = 2_000_000, method = "card", category = "Оплата заказа",
            orderTitle = "Коттедж, Ленина 93а", clientName = "Лопатин Сергей", happenedAt = stamp(-1)),
        CashOpDto(id = 2, direction = "out", amountKop = 1_240_000, method = "cash", category = "Материалы",
            note = "Кабель, автоматы — Электрокомплект", happenedAt = stamp(-2)),
        CashOpDto(id = 3, direction = "in", amountKop = 2_000_000, method = "cash", category = "Оплата заказа",
            orderTitle = "Кухня, Садовая 14", clientName = "Иванова Мария", happenedAt = stamp(-2)),
        CashOpDto(id = 4, direction = "out", amountKop = 350_000, method = "cash", category = "Зарплата",
            note = "Андрей, выход", happenedAt = stamp(-3)),
    )

    val accounts = listOf(
        AccountBalanceDto(method = "cash", inKop = 2_000_000, outKop = 1_590_000, balanceKop = 410_000),
        AccountBalanceDto(method = "card", inKop = 2_000_000, outKop = 0, balanceKop = 2_000_000),
    )

    val documents = listOf(
        DocumentDto(id = 7, orderId = 40, clientId = 1, clientName = "Лопатин Сергей", orderTitle = "Коттедж, Ленина 93а",
            kind = "invoice", kindTitle = "Счёт на оплату", status = "issued", year = today.year, number = 14,
            title = "Счёт на оплату", totalKop = 4_800_000, docDate = day(-8)),
        DocumentDto(id = 8, orderId = 39, clientId = 3, clientName = "Иванова Мария", orderTitle = "Кухня, Садовая 14",
            kind = "act", kindTitle = "Акт выполненных работ", status = "issued", year = today.year, number = 9,
            title = "Акт выполненных работ", totalKop = 2_000_000, docDate = day(-2)),
        DocumentDto(id = 9, clientId = 2, clientName = "ООО «Вокзальная 9»", kind = "estimate", kindTitle = "Смета",
            status = "draft", title = "Смета", totalKop = 3_150_000, docDate = day(0)),
        DocumentDto(id = 6, clientId = 2, clientName = "ООО «Вокзальная 9»", kind = "reconciliation", kindTitle = "Акт сверки",
            status = "annulled", year = today.year, number = 2, title = "Акт сверки", docDate = day(-30)),
    )

    val audit = listOf(
        AuditEntryDto(id = 5, entityType = "order", entityId = 41, action = "create", detail = "Вокзальная 9", createdAt = stamp(0, "09:40:00")),
        AuditEntryDto(id = 4, entityType = "document", entityId = 8, action = "issue", detail = "Акт выполненных работ №9", createdAt = stamp(0, "09:05:00")),
        AuditEntryDto(id = 3, entityType = "payment", entityId = 12, action = "create", detail = "20 000 ₽", createdAt = stamp(-1)),
        AuditEntryDto(id = 2, entityType = "client", entityId = 3, action = "update", detail = "Иванова Мария", createdAt = stamp(-1, "16:20:00")),
    )

    val files = FolderListingDto(
        folders = listOf(
            FolderDto(id = 1, name = "Проекты щитов", folderCount = 2, fileCount = 11),
            FolderDto(id = 2, name = "Фото объектов", fileCount = 48),
        ),
        files = listOf(
            StoredFileDto(id = 1, name = "Однолинейная схема Ленина 93а.pdf", mime = "application/pdf", size = 842_000, createdAt = stamp(-4)),
            StoredFileDto(id = 2, name = "Прайс Электрокомплект.xlsx", mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", size = 96_000, createdAt = stamp(-10)),
            StoredFileDto(id = 3, name = "Щит до сборки.jpg", mime = "image/jpeg", size = 2_400_000, createdAt = stamp(-9)),
        ),
    )

    val company = CompanyDto(
        shortName = "ИП Фролов А. В.", fullName = "Индивидуальный предприниматель Фролов Алексей Викторович",
        inn = "631234567890", ogrnip = "321631200012345", address = "г. Самара, ул. Ленина, 1",
        phone = "+79370000000", email = "frolov@example.ru", bankName = "АО «Т-Банк»",
        bankBik = "044525974", bankAccount = "40802810000000000001", bankCorrAccount = "30101810145250000974",
        signerName = "Фролов А. В.", signerTitle = "Индивидуальный предприниматель",
        taxNote = "НДС не облагается (УСН)",
    )

    val stats = StatsDto(
        clients = 28, orders = 64, ordersActive = 5, ordersDone = 57, requestsNew = 2,
        revenueTotalKop = 184_000_000, revenueActiveKop = 12_450_000,
        debtOutstandingKop = 3_950_000, debtOverdueKop = 3_150_000,
    )

    val analytics = AnalyticsDto(
        generatedAt = stamp(0),
        orders = AnalyticsOrdersDto(total = 64, new = 1, inProgress = 4, done = 57, canceled = 2,
            completionRate = 0.89, avgPriceKop = 2_870_000, avgCycleDays = 6.5),
        revenue = AnalyticsRevenueDto(totalKop = 184_000_000, activeKop = 12_450_000, avgOrderKop = 2_870_000,
            thisMonthKop = 9_300_000, lastMonthKop = 7_800_000, growthPct = 19.2),
        payments = AnalyticsPaymentsDto(totalKop = 180_000_000, thisMonthKop = 8_000_000, lastMonthKop = 7_100_000, growthPct = 12.7),
        debt = AnalyticsDebtDto(outstandingKop = 3_950_000, overdueKop = 3_150_000, ordersWithDebt = 2),
        clients = AnalyticsClientsDto(total = 28, newThisMonth = 3, withOrders = 24, repeat = 9, repeatRate = 0.37),
        requests = AnalyticsRequestsDto(total = 90, new = 2, inProgress = 1, done = 80, spam = 7, conversion = 0.62),
        monthly = (5 downTo 0).map { back ->
            val month = today.minusMonths(back.toLong())
            AnalyticsMonthDto(
                month = "%d-%02d".format(month.year, month.monthValue),
                ordersCreated = 6L + back % 3, ordersDone = 5L + back % 2,
                revenueKop = 6_500_000L + (5 - back) * 550_000L,
                paymentsKop = 6_000_000L + (5 - back) * 500_000L,
                newClients = 2L + back % 2, requests = 12L + back,
            )
        },
        topClients = listOf(
            AnalyticsTopClientDto(clientId = 1, name = "Лопатин Сергей", ordersTotal = 4, revenueDoneKop = 18_500_000, revenueAllKop = 23_300_000),
            AnalyticsTopClientDto(clientId = 2, name = "ООО «Вокзальная 9»", ordersTotal = 3, revenueDoneKop = 4_200_000, revenueAllKop = 11_850_000),
        ),
    )

    val reports = listOf(
        ReportInfoDto(id = "revenue", title = "Выручка по месяцам", description = "Заказы, оплаты и долг за период",
            params = listOf(ReportParamDto(kind = "period", label = "Период"))),
        ReportInfoDto(id = "client", title = "Отчёт по клиенту", description = "Все заказы и оплаты одного клиента",
            params = listOf(ReportParamDto(kind = "client", label = "Клиент"), ReportParamDto(kind = "period", label = "Период"))),
        ReportInfoDto(id = "materials", title = "Расход материалов", description = "Что и сколько списано со склада"),
    )
}
