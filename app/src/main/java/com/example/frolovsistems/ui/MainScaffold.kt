package com.example.frolovsistems.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Engineering
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import androidx.navigation.compose.rememberNavController
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.screens.AnalyticsScreen
import com.example.frolovsistems.ui.screens.AuditScreen
import com.example.frolovsistems.ui.screens.CalendarScreen
import com.example.frolovsistems.ui.screens.ClientDocumentScreen
import com.example.frolovsistems.ui.screens.ClientsScreen
import com.example.frolovsistems.ui.screens.CashScreen
import com.example.frolovsistems.ui.screens.CatalogScreen
import com.example.frolovsistems.ui.screens.CompanyScreen
import com.example.frolovsistems.ui.screens.DashboardScreen
import com.example.frolovsistems.ui.screens.DiagnosticsScreen
import com.example.frolovsistems.ui.screens.DocumentScreen
import com.example.frolovsistems.ui.screens.DocumentsScreen
import com.example.frolovsistems.ui.screens.FilesScreen
import com.example.frolovsistems.ui.screens.OrderDocumentScreen
import com.example.frolovsistems.ui.screens.OrdersScreen
import com.example.frolovsistems.ui.screens.ReportScreen
import com.example.frolovsistems.ui.screens.RequestsScreen
import com.example.frolovsistems.ui.screens.SettingsScreen
import com.example.frolovsistems.ui.screens.SiteEditorScreen
import com.example.frolovsistems.ui.screens.TasksScreen
import com.example.frolovsistems.ui.screens.WorkersScreen
import com.example.frolovsistems.ui.screens.AboutScreen
import kotlinx.coroutines.launch

/**
 * Разделы приложения. В нижней панели две ровные кнопки по бокам:
 * «Заказы» слева — она же стартовый экран — и «Аналитика» справа.
 * Остальные разделы и инструменты собраны в полуколесе (неоновая кнопка
 * в центре панели) и в боковом меню.
 */
enum class Section(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Summary("analytics", "Аналитика", Icons.Default.Insights),
    Clients("clients", "Клиенты", Icons.Default.People),
    Orders("orders?status={status}", "Заказы", Icons.Default.WorkOutline),
    Requests("requests?status={status}", "Заявки", Icons.Default.MarkEmailUnread),
    Stock("catalog", "Склад", Icons.Default.Inventory),
    ;

    /** Адрес без параметров — по нему переходит нижнее меню. */
    val baseRoute: String get() = route.substringBefore("?")
}

/** Экраны, открываемые поверх разделов — в нижнем меню их нет. */
const val SETTINGS_ROUTE = "settings"
const val COMPANY_ROUTE = "company"
const val CALENDAR_ROUTE = "calendar"
const val SITE_ROUTE = "site"
const val TRANSFER_ROUTE = "transfer"
const val CASH_ROUTE = "cash"
const val REPORT_ROUTE = "report"
const val DOCUMENTS_ROUTE = "documents"
const val DOCUMENT_ROUTE = "document/{orderId}/{kind}"
const val CLIENT_DOC_ROUTE = "clientdoc/{clientId}/{kind}"
const val DOC_VIEW_ROUTE = "docview/{id}"
const val TASKS_ROUTE = "tasks"
const val WORKERS_ROUTE = "workers"
const val FILES_ROUTE = "files"
const val AUDIT_ROUTE = "audit"
const val DIAG_ROUTE = "diagnostics"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(pendingSection: String? = null, onSectionOpened: () -> Unit = {}) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Копия базы на телефон — раз в сутки, при открытии приложения.
    // Ежедневный бэкап на компьютер (через adb backup или WorkManager Desktop)
    LaunchedEffect(Unit) { backupDatabaseOnOpen() }

    // Уведомление просит открыть конкретный раздел (сейчас — «Заявки»).
    LaunchedEffect(pendingSection) {
        if (pendingSection.isNullOrEmpty()) return@LaunchedEffect
        val target = Section.entries.firstOrNull { it.baseRoute == pendingSection }
        if (target != null) navController.switchTo(target.baseRoute)
        onSectionOpened()
    }

    // Бейдж с числом новых заявок: пересчитываем при каждой смене экрана.
    var newRequests by remember { mutableIntStateOf(0) }
    LaunchedEffect(currentDestination?.route) {
        ServiceLocator.crm.requests("new").onSuccess { newRequests = it.size }
    }

    // Кнопка «Обновить» в шапке: каждое нажатие увеличивает тик,
    // а открытый экран по нему перезагружает свои данные.
    var syncTick by remember { mutableIntStateOf(0) }

    // Разделы меню: список инструментов вырос, и плоский перечень перестал
    // читаться. Каждая группа сворачивается; открытый экран раскрывает свою.
    val drawerGroups = remember {
        listOf(
            DrawerGroup("Учёт", Icons.Default.AccountBalance, listOf(
                DrawerTool(Icons.Default.Description, "Документы", DOCUMENTS_ROUTE),
                DrawerTool(Icons.Default.AccountBalanceWallet, "Касса", CASH_ROUTE),
                DrawerTool(Icons.Default.Inventory, "Склад", Section.Stock.baseRoute),
                DrawerTool(Icons.Default.Assessment, "Отчёты", REPORT_ROUTE),
            )),
            DrawerGroup("Работа", Icons.Default.BusinessCenter, listOf(
                DrawerTool(Icons.Default.Schedule, "Задачи и напоминания", TASKS_ROUTE),
                DrawerTool(Icons.Default.Engineering, "Рабочие", WORKERS_ROUTE),
                DrawerTool(Icons.Default.FolderOpen, "Файлы", FILES_ROUTE),
                DrawerTool(Icons.Default.History, "Журнал действий", AUDIT_ROUTE),
            )),
            DrawerGroup("Настройки", Icons.Default.Tune, listOf(
                DrawerTool(Icons.Default.Language, "Редактор сайта", SITE_ROUTE),
                DrawerTool(Icons.Default.Badge, "Реквизиты ИП", COMPANY_ROUTE),
                DrawerTool(Icons.Default.BugReport, "Диагностика и ошибки", DIAG_ROUTE),
                DrawerTool(Icons.Default.Settings, "Настройки подключения", SETTINGS_ROUTE),
                DrawerTool(Icons.Default.Info, "О приложении", "about"),
            )),
        )
    }
    var expandedGroups by rememberSaveable { mutableStateOf(listOf<String>()) }

    // Раздел, внутри которого открыт текущий экран, раскрываем сам —
    // иначе пришлось бы искать пункт вслепую в свёрнутых группах.
    LaunchedEffect(currentDestination?.route) {
        val route = currentDestination?.route ?: return@LaunchedEffect
        val base = route.substringBefore("?")
        drawerGroups.forEach { group ->
            if (group.tools.any { it.route.substringBefore("?") == base } &&
                group.title !in expandedGroups
            ) {
                expandedGroups = expandedGroups + group.title
            }
        }
    }

    // Полуколесо над нижней панелью: только частые разделы, «Календарь»
    // строго посередине дуги — он крупнее остальных и светится неоном.
    // Полный набор инструментов остаётся в боковом меню.
    val wheelTools = remember(newRequests) {
        listOf(
            WheelTool(Section.Clients.icon, "Клиенты", Section.Clients.baseRoute,
                hint = "Карточки, сметы, акты сверки"),
            WheelTool(Section.Requests.icon, "Заявки", Section.Requests.baseRoute, newRequests,
                hint = if (newRequests > 0) "С сайта · новых: $newRequests" else "Обращения с сайта"),
            WheelTool(Icons.Default.Schedule, "Задачи", TASKS_ROUTE,
                hint = "Дела и напоминания"),
            WheelTool(Icons.Default.CalendarMonth, "Календарь", CALENDAR_ROUTE,
                hint = "Выезды и сроки по дням"),
            WheelTool(Icons.Default.AccountBalanceWallet, "Касса", CASH_ROUTE,
                hint = "Приход, расход, остаток"),
            WheelTool(Section.Stock.icon, "Склад", Section.Stock.baseRoute,
                hint = "Кабель, автоматы, остатки"),
            WheelTool(Icons.Default.Assessment, "Отчёты", REPORT_ROUTE,
                hint = "Итоги за период"),
        )
    }
    var wheelOpen by remember { mutableStateOf(false) }
    // Нажатие неоновой кнопки на открытом колесе — просьба закрыть его
    // с анимацией; убирает колесо с экрана оно само, по onDone.
    var wheelCloseRequests by remember { mutableIntStateOf(0) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                    Text("Фролов CRM", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Инструменты и мини-приложения",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))
                drawerGroups.forEach { group ->
                    val expanded = group.title in expandedGroups
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedGroups = if (expanded) {
                                    expandedGroups - group.title
                                } else {
                                    expandedGroups + group.title
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            group.icon,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            group.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "Свернуть" else "Развернуть",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AnimatedVisibility(
                        visible = expanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        Column {
                            group.tools.forEach { tool ->
                                NavigationDrawerItem(
                                    icon = { Icon(tool.icon, contentDescription = null) },
                                    label = { Text(tool.label) },
                                    selected = currentDestination?.route == tool.route,
                                    onClick = {
                                        scope.launch {
                                            drawerState.close()
                                            navController.switchTo(tool.route)
                                        }
                                    },
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Фролов CRM") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Меню")
                        }
                    },
                    actions = {
                        // Выгрузка/загрузка базы Excel — рядом с «Обновить» и «Настройки».
                        IconButton(onClick = {
                            navController.navigate(TRANSFER_ROUTE) { launchSingleTop = true }
                        }) {
                            Icon(Icons.Default.SwapVert, contentDescription = "Выгрузка и загрузка Excel")
                        }
                        IconButton(onClick = {
                            syncTick++
                            scope.launch {
                                ServiceLocator.crm.requests("new").onSuccess { newRequests = it.size }
                            }
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                        }
                        IconButton(onClick = {
                            navController.navigate(SETTINGS_ROUTE) { launchSingleTop = true }
                        }) {
                            Icon(Icons.Default.Settings, contentDescription = "Настройки")
                        }
                    },
                )
            },
            bottomBar = {
                GoldenBottomBar(
                    analyticsIcon = Section.Summary.icon,
                    ordersIcon = Section.Orders.icon,
                    analyticsSelected = currentDestination?.hierarchy
                        ?.any { it.route == Section.Summary.route } == true,
                    ordersSelected = currentDestination?.hierarchy
                        ?.any { it.route == Section.Orders.route } == true,
                    wheelOpen = wheelOpen,
                    newRequests = newRequests,
                    onAnalytics = { navController.switchTo(Section.Summary.baseRoute) },
                    onWheel = { if (wheelOpen) wheelCloseRequests++ else wheelOpen = true },
                    onOrders = { navController.switchTo(Section.Orders.baseRoute) },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                NavHost(
                    navController = navController,
                    // «Заказы» — главный раздел: приложение открывается сразу в них.
                    startDestination = Section.Orders.route,
                    // Разделы «переезжают» вбок — переходы читаются как навигация, а не как перерисовка.
                    enterTransition = { slideInHorizontally(tween(280)) { it / 8 } + fadeIn(tween(280)) },
                    exitTransition = { fadeOut(tween(160)) },
                    popEnterTransition = { slideInHorizontally(tween(280)) { -it / 8 } + fadeIn(tween(280)) },
                    popExitTransition = { fadeOut(tween(160)) },
                ) {
                    composable(Section.Summary.route) {
                        AnalyticsScreen(
                            // Плитки со сводными числами ведут в раздел с нужным фильтром.
                            onOpenSection = { route ->
                                navController.navigate(route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                }
                            },
                            refreshTick = syncTick,
                        )
                    }
                    composable(Section.Stock.route) { CatalogScreen(refreshTick = syncTick) }
                    composable(
                        DOCUMENT_ROUTE,
                        arguments = listOf(
                            navArgument("orderId") { type = NavType.LongType },
                            navArgument("kind") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        OrderDocumentScreen(
                            orderId = entry.arguments?.getLong("orderId") ?: 0L,
                            kind = entry.arguments?.getString("kind").orEmpty(),
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(
                        CLIENT_DOC_ROUTE,
                        arguments = listOf(
                            navArgument("clientId") { type = NavType.LongType },
                            navArgument("kind") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        ClientDocumentScreen(
                            clientId = entry.arguments?.getLong("clientId") ?: 0L,
                            kind = entry.arguments?.getString("kind").orEmpty(),
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(
                        DOC_VIEW_ROUTE,
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    ) { entry ->
                        DocumentScreen(
                            documentId = entry.arguments?.getLong("id") ?: 0L,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(DOCUMENTS_ROUTE) {
                        DocumentsScreen(
                            refreshTick = syncTick,
                            onOpenDocument = { id ->
                                navController.navigate("docview/$id") { launchSingleTop = true }
                            },
                        )
                    }
                    composable(CASH_ROUTE) {
                        CashScreen(onBack = { navController.popBackStack() })
                    }
                    composable(REPORT_ROUTE) {
                        ReportScreen(onBack = { navController.popBackStack() })
                    }
                    composable(TRANSFER_ROUTE) {
                        DashboardScreen(onBack = { navController.popBackStack() })
                    }
                    composable(SITE_ROUTE) { SiteEditorScreen() }
                    composable(Section.Clients.route) {
                        ClientsScreen(
                            refreshTick = syncTick,
                            onOpenClientDocument = { clientId, kind ->
                                navController.navigate("clientdoc/$clientId/$kind") { launchSingleTop = true }
                            },
                        )
                    }
                    composable(
                        Section.Orders.route,
                        arguments = listOf(navArgument("status") {
                            type = NavType.StringType
                            defaultValue = ""
                        }),
                    ) { entry ->
                        OrdersScreen(
                            initialStatus = entry.arguments?.getString("status").orEmpty(),
                            onOpenDocument = { orderId, kind ->
                                navController.navigate("document/$orderId/$kind")
                            },
                            refreshTick = syncTick,
                        )
                    }
                    composable(
                        Section.Requests.route,
                        arguments = listOf(navArgument("status") {
                            type = NavType.StringType
                            defaultValue = ""
                        }),
                    ) { entry ->
                        RequestsScreen(
                            initialStatus = entry.arguments?.getString("status").orEmpty(),
                            refreshTick = syncTick,
                        )
                    }
                    composable(COMPANY_ROUTE) {
                        CompanyScreen(onBack = { navController.popBackStack() })
                    }
                    composable(SETTINGS_ROUTE) {
                        SettingsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenTransfer = { navController.navigate(TRANSFER_ROUTE) },
                        )
                    }
                    composable(FILES_ROUTE) {
                        FilesScreen(refreshTick = syncTick)
                    }
                    composable(AUDIT_ROUTE) {
                        AuditScreen(refreshTick = syncTick)
                    }
                    composable(DIAG_ROUTE) {
                        DiagnosticsScreen(onBack = { navController.popBackStack() })
                    }
                    composable(TASKS_ROUTE) {
                        TasksScreen(refreshTick = syncTick)
                    }
                    composable(WORKERS_ROUTE) {
                        WorkersScreen(refreshTick = syncTick)
                    }
                    composable(CALENDAR_ROUTE) {
                        CalendarScreen(
                            onBack = { navController.popBackStack() },
                            refreshTick = syncTick,
                        )
                    }
                    composable("about") {
                        AboutScreen()
                    }
                }
            }
        }
            // Полуколесо рисуется поверх и панели, и содержимого — поэтому
            // живёт вне Scaffold, в общей коробке.
            if (wheelOpen) {
                GoldenWheelOverlay(
                    items = wheelTools,
                    currentRoute = currentDestination?.route,
                    onSelect = { route -> navController.switchTo(route) },
                    onDone = { wheelOpen = false },
                    closeRequests = wheelCloseRequests,
                    // Есть новые заявки — колесо открывается на них, нет — на календаре.
                    startRoute = if (newRequests > 0) Section.Requests.baseRoute else CALENDAR_ROUTE,
                )
            }
        }
    }
}

data class DrawerTool(val icon: ImageVector, val label: String, val route: String)

/** Раздел меню: заголовок и инструменты внутри, сворачивается до заголовка. */
data class DrawerGroup(
    val title: String,
    val icon: ImageVector,
    val tools: List<DrawerTool>,
)

/**
 * Переход по нижнему меню: состояние покинутого раздела сохраняется,
 * а стопка не растёт от переключений между вкладками.
 */
private fun NavHostController.switchTo(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Отметка уровня процесса, а не состояния экрана: поворот телефона
 * и возврат на вкладку не должны заново дёргать сеть.
 *
 * Ставится только при удаче. Если бы отмечалась сама попытка, одна осечка
 * сети при запуске отменяла бы копию до перезапуска приложения — а именно
 * в такой день она и нужнее всего.
 */
private var backupDoneForToday = false

/**
 * Копия базы при открытии приложения. Сама решает, нужна ли она сегодня,
 * и молчит при неудаче: показывать ошибку сети поверх экрана, который
 * человек только что открыл, — плохой размен. Состояние копий видно
 * на экране настроек, там же оно и предупреждает, если копии устарели.
 */
private suspend fun backupDatabaseOnOpen() {
    if (backupDoneForToday) return
    ServiceLocator.phoneBackups.backupIfNeeded()
        .onSuccess { backupDoneForToday = true }
}
