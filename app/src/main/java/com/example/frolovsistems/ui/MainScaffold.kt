package com.example.frolovsistems.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
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
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.example.frolovsistems.ui.screens.ClientsScreen
import com.example.frolovsistems.ui.screens.CashScreen
import com.example.frolovsistems.ui.screens.CatalogScreen
import com.example.frolovsistems.ui.screens.CompanyScreen
import com.example.frolovsistems.ui.screens.DashboardScreen
import com.example.frolovsistems.ui.screens.DocumentScreen
import com.example.frolovsistems.ui.screens.FilesScreen
import com.example.frolovsistems.ui.screens.OrdersScreen
import com.example.frolovsistems.ui.screens.ReportScreen
import com.example.frolovsistems.ui.screens.RequestsScreen
import com.example.frolovsistems.ui.screens.SettingsScreen
import com.example.frolovsistems.ui.screens.SiteEditorScreen
import com.example.frolovsistems.ui.screens.TasksScreen
import kotlinx.coroutines.launch

/**
 * Разделы нижней навигации. Их ровно пять: Material 3 рассчитан на 3–5 пунктов,
 * при шести на узком экране крайний пункт ужимается до нечитаемого.
 * Редактор сайта и настройки живут поверх разделов: первый — под иконкой
 * глобуса на «Сводке», вторые — под шестерёнкой.
 */
enum class Section(
    val route: String,
    val label: String,
    val icon: ImageVector,
    /** false — раздел живёт в бургер-меню, а не в нижней навигации. */
    val inBottomBar: Boolean = true,
) {
    Summary("analytics", "Сводка", Icons.Default.Insights),
    Clients("clients", "Клиенты", Icons.Default.People),
    Orders("orders?status={status}", "Заказы", Icons.Default.WorkOutline),
    Requests("requests?status={status}", "Заявки", Icons.Default.MarkEmailUnread),
    Stock("catalog", "Склад", Icons.Default.Inventory, inBottomBar = false),
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
const val DOCUMENT_ROUTE = "document/{orderId}/{kind}"
const val TASKS_ROUTE = "tasks"
const val FILES_ROUTE = "files"
const val AUDIT_ROUTE = "audit"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(pendingSection: String? = null, onSectionOpened: () -> Unit = {}) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

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

    // Мини-приложения и инструменты вне нижнего меню.
    val drawerTools = listOf(
        DrawerTool(Icons.Default.FolderOpen, "Файлы", FILES_ROUTE),
        DrawerTool(Icons.Default.Inventory, "Склад", Section.Stock.baseRoute),
        DrawerTool(Icons.Default.AccountBalanceWallet, "Касса", CASH_ROUTE),
        DrawerTool(Icons.Default.Assessment, "Отчёт за период", REPORT_ROUTE),
        DrawerTool(Icons.Default.Language, "Редактор сайта", SITE_ROUTE),
        DrawerTool(Icons.Default.Schedule, "Задачи и напоминания", TASKS_ROUTE),
        DrawerTool(Icons.Default.History, "Журнал действий", AUDIT_ROUTE),
        DrawerTool(Icons.Default.Badge, "Реквизиты ИП", COMPANY_ROUTE),
        DrawerTool(Icons.Default.Settings, "Настройки подключения", SETTINGS_ROUTE),
    )

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
                drawerTools.forEach { tool ->
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
        },
    ) {
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
                // Скруглённая шапка панели и лёгкая тень отделяют навигацию
                // от содержимого — без сплошной линии-разделителя.
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    shadowElevation = 10.dp,
                ) {
                    NavigationBar(
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp,
                    ) {
                        // Все пункты одного размера, включая календарь: он стоит
                        // посередине, но ничем не крупнее соседей — иначе панель
                        // «прыгает» при переходе между разделами.
                        val barSections = Section.entries.filter { it.inBottomBar }
                        barSections.forEachIndexed { index, section ->
                            if (index == barSections.size / 2) {
                                BottomNavItem(
                                    icon = Icons.Default.CalendarMonth,
                                    label = "Календарь",
                                    selected = currentDestination?.route == CALENDAR_ROUTE,
                                    onClick = { navController.switchTo(CALENDAR_ROUTE) },
                                )
                            }
                            BottomNavItem(
                                icon = section.icon,
                                label = section.label,
                                selected = currentDestination?.hierarchy?.any { it.route == section.route } == true,
                                badge = if (section == Section.Requests) newRequests else 0,
                                onClick = { navController.switchTo(section.baseRoute) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                NavHost(
                    navController = navController,
                    startDestination = Section.Summary.route,
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
                        DocumentScreen(
                            orderId = entry.arguments?.getLong("orderId") ?: 0L,
                            kind = entry.arguments?.getString("kind").orEmpty(),
                            onBack = { navController.popBackStack() },
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
                    composable(Section.Clients.route) { ClientsScreen(refreshTick = syncTick) }
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
                    composable(TASKS_ROUTE) {
                        TasksScreen(refreshTick = syncTick)
                    }
                    composable(CALENDAR_ROUTE) {
                        CalendarScreen(
                            onBack = { navController.popBackStack() },
                            refreshTick = syncTick,
                        )
                    }
                }
            }
        }
    }
}

data class DrawerTool(val icon: ImageVector, val label: String, val route: String)

/**
 * Пункт нижней панели. Выделение — мягкая «таблетка» под иконкой (индикатор
 * Material 3 с приглушённым цветом) и подпись, которая появляется только у
 * выбранного пункта. Размер иконки постоянный: ничто не растёт при нажатии.
 */
@Composable
private fun RowScope.BottomNavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    badge: Int = 0,
) {
    NavigationBarItem(
        selected = selected,
        onClick = { if (!selected) onClick() },
        icon = {
            BadgedBox(
                badge = { if (badge > 0) Badge { Text("$badge") } },
            ) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
            }
        },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        alwaysShowLabel = false,
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        ),
    )
}

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
