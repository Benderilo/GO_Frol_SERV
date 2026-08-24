package com.example.frolovsistems.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import androidx.navigation.compose.rememberNavController
import com.example.frolovsistems.ui.screens.ClientsScreen
import com.example.frolovsistems.ui.screens.DashboardScreen
import com.example.frolovsistems.ui.screens.OrdersScreen
import com.example.frolovsistems.ui.screens.RequestsScreen
import com.example.frolovsistems.ui.screens.SettingsScreen
import com.example.frolovsistems.ui.screens.SiteEditorScreen

/**
 * Разделы нижней навигации. Их ровно пять: Material 3 рассчитан на 3–5 пунктов,
 * при шести на узком экране крайний пункт ужимается до нечитаемого.
 * Настройки поэтому живут не здесь, а под шестерёнкой на «Сводке».
 */
enum class Section(val route: String, val label: String, val icon: ImageVector) {
    Dashboard("dashboard", "Сводка", Icons.Default.Dashboard),
    Site("site", "Сайт", Icons.Default.Language),
    Clients("clients", "Клиенты", Icons.Default.People),
    Orders("orders?status={status}", "Заказы", Icons.Default.WorkOutline),
    Requests("requests?status={status}", "Заявки", Icons.Default.MarkEmailUnread),
    ;

    /** Адрес без параметров — по нему переходит нижнее меню. */
    val baseRoute: String get() = route.substringBefore("?")
}

/** Настройки открываются поверх разделов, в нижнем меню их нет. */
const val SETTINGS_ROUTE = "settings"

@Composable
fun MainScaffold() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 0.dp,
            ) {
                Section.entries.forEach { section ->
                    val selected = currentDestination?.hierarchy?.any { it.route == section.route } == true
                    val scale by animateFloatAsState(
                        targetValue = if (selected) 1.15f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow,
                        ),
                        label = "navIconScale",
                    )

                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (!selected) {
                                navController.navigate(section.baseRoute) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                section.icon,
                                contentDescription = section.label,
                                modifier = Modifier.scale(scale),
                            )
                        },
                        label = {
                            AnimatedVisibility(
                                visible = selected,
                                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                                exit = fadeOut() + scaleOut(targetScale = 0.8f),
                            ) {
                                Text(section.label, style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        alwaysShowLabel = false,
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(
                navController = navController,
                startDestination = Section.Dashboard.route,
                // Разделы «переезжают» вбок — переходы читаются как навигация, а не как перерисовка.
                enterTransition = { slideInHorizontally(tween(280)) { it / 8 } + fadeIn(tween(280)) },
                exitTransition = { fadeOut(tween(160)) },
                popEnterTransition = { slideInHorizontally(tween(280)) { -it / 8 } + fadeIn(tween(280)) },
                popExitTransition = { fadeOut(tween(160)) },
            ) {
                composable(Section.Dashboard.route) {
                    DashboardScreen(
                        onOpenSettings = { navController.navigate(SETTINGS_ROUTE) },
                        // Плитки со сводными числами ведут в раздел с нужным фильтром.
                        onOpenSection = { route ->
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                            }
                        },
                    )
                }
                composable(Section.Site.route) { SiteEditorScreen() }
                composable(Section.Clients.route) { ClientsScreen() }
                composable(
                    Section.Orders.route,
                    arguments = listOf(navArgument("status") {
                        type = NavType.StringType
                        defaultValue = ""
                    }),
                ) { entry ->
                    OrdersScreen(initialStatus = entry.arguments?.getString("status").orEmpty())
                }
                composable(
                    Section.Requests.route,
                    arguments = listOf(navArgument("status") {
                        type = NavType.StringType
                        defaultValue = ""
                    }),
                ) { entry ->
                    RequestsScreen(initialStatus = entry.arguments?.getString("status").orEmpty())
                }
                composable(SETTINGS_ROUTE) {
                    SettingsScreen(onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
