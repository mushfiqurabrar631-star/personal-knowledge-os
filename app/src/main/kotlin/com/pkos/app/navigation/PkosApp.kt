package com.pkos.app.navigation

import kotlinx.coroutines.launch
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.pkos.app.R
import com.pkos.app.di.LocalAppContainer
import com.pkos.app.feature.ask.AskAiScreen
import com.pkos.app.feature.home.HomeActions
import com.pkos.app.feature.home.HomeRoute as HomeRouteScreen
import com.pkos.app.feature.inbox.InboxRoute as InboxRouteScreen
import com.pkos.app.feature.item.ItemDetailRoute as ItemDetailRouteScreen
import com.pkos.app.feature.onboarding.OnboardingScreen
import com.pkos.app.feature.search.SearchRoute as SearchRouteScreen
import com.pkos.app.feature.settings.BackupSyncRoute as BackupSyncRouteScreen
import com.pkos.app.feature.settings.IntegrationsScreen
import com.pkos.app.feature.settings.SettingsActions
import com.pkos.app.feature.settings.SettingsRoute as SettingsRouteScreen
import com.pkos.app.feature.topics.TopicsRoute as TopicsRouteScreen
import com.pkos.app.ui.theme.Motion
import com.pkos.core.data.model.InboxFilter
import kotlin.reflect.KClass

private data class Tab(val route: Any, val routeClass: KClass<*>, val label: Int, val icon: ImageVector, val selectedIcon: ImageVector, val tag: String)

private val leftTabs = listOf(
    Tab(HomeRoute, HomeRoute::class, R.string.nav_home, Icons.Outlined.Home, Icons.Filled.Home, "tab_home"),
    Tab(InboxRoute, InboxRoute::class, R.string.nav_inbox, Icons.Outlined.Inbox, Icons.Filled.Inbox, "tab_inbox"),
)
private val rightTabs = listOf(
    Tab(SearchRoute, SearchRoute::class, R.string.nav_search, Icons.Outlined.Search, Icons.Filled.Search, "tab_search"),
    Tab(SettingsRoute, SettingsRoute::class, R.string.nav_settings, Icons.Outlined.Settings, Icons.Filled.Settings, "tab_settings"),
)
private val topLevel = (leftTabs + rightTabs).map { it.routeClass }

/**
 * App shell: bottom navigation Home · Inbox · [Save] · Search · Settings with the capture action centred
 * (spec 2.3 #7). Topics, Ask AI, Integrations and Backup & Sync are pushed screens.
 */
@Composable
fun PkosApp(
    startWithOnboarding: Boolean,
    onOnboardingFinished: () -> Unit,
    navController: NavHostController = rememberNavController(),
    openItemId: String? = null,
    onOpenItemConsumed: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val openLabel = androidx.compose.ui.res.stringResource(com.pkos.app.R.string.capture_open)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(openItemId) {
        if (openItemId != null && !startWithOnboarding) { navController.navigate(ItemDetailRoute(openItemId)); onOpenItemConsumed() }
    }
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val showBottomBar = destination != null && topLevel.any { cls -> destination.hierarchy.any { it.hasRoute(cls) } }
    var showCapture by rememberSaveable { mutableStateOf(false) }
    var inboxFilter by remember { mutableStateOf(InboxFilter.ALL) }
    val counts by LocalAppContainer.current.knowledge.counts().collectAsStateWithLifecycle(initialValue = null)

    fun openTab(route: Any) = navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar, Modifier.testTag("snackbar")) },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(Modifier.selectableGroup().testTag("bottom_bar")) {
                    leftTabs.forEach { TabItem(it, destination.hierarchy.any { d -> d.hasRoute(it.routeClass) }, badge = if (it.routeClass == InboxRoute::class) counts?.inbox else null) { inboxFilter = InboxFilter.ALL; openTab(it.route) } }
                    CaptureButton { showCapture = true }
                    rightTabs.forEach { TabItem(it, destination.hierarchy.any { d -> d.hasRoute(it.routeClass) }, badge = null) { openTab(it.route) } }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (startWithOnboarding) OnboardingRoute else HomeRoute,
            modifier = Modifier.padding(padding),
            enterTransition = { fadeIn(tween(Motion.MEDIUM)) },
            exitTransition = { fadeOut(tween(Motion.SHORT)) },
            popEnterTransition = { fadeIn(tween(Motion.MEDIUM)) },
            popExitTransition = { fadeOut(tween(Motion.SHORT)) },
        ) {
            composable<OnboardingRoute>(enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None }) {
                OnboardingScreen(onFinished = {
                    onOnboardingFinished()
                    navController.navigate(HomeRoute) { popUpTo(OnboardingRoute) { inclusive = true } }
                })
            }
            composable<HomeRoute> {
                HomeRouteScreen(
                    HomeActions(
                        openSearch = { openTab(SearchRoute) },
                        openCapture = { showCapture = true },
                        openInbox = { f -> inboxFilter = f; openTab(InboxRoute) },
                        openItem = { navController.navigate(ItemDetailRoute(it)) },
                        openTopics = { navController.navigate(TopicsRoute) },
                        openAskAi = { navController.navigate(AskAiRoute) },
                        openBackupSync = { navController.navigate(BackupSyncRoute) },
                        openSettings = { openTab(SettingsRoute) },
                    ),
                )
            }
            composable<InboxRoute> { InboxRouteScreen(initialFilter = inboxFilter, openItem = { navController.navigate(ItemDetailRoute(it)) }) }
            composable<SearchRoute> { SearchRouteScreen(openItem = { navController.navigate(ItemDetailRoute(it)) }) }
            composable<SettingsRoute> {
                SettingsRouteScreen(
                    SettingsActions(
                        openIntegrations = { navController.navigate(IntegrationsRoute) },
                        openBackupSync = { navController.navigate(BackupSyncRoute) },
                        onReplayOnboarding = { navController.navigate(OnboardingRoute) { popUpTo(0) { inclusive = true } } },
                    ),
                )
            }
            composable<TopicsRoute> { TopicsRouteScreen(onBack = { navController.popBackStack() }) }
            composable<AskAiRoute> { AskAiScreen(onBack = { navController.popBackStack() }, openIntegrations = { navController.navigate(IntegrationsRoute) }) }
            composable<IntegrationsRoute> {
                IntegrationsScreen(LocalAppContainer.current.integrations.current(), onBack = { navController.popBackStack() })
            }
            composable<BackupSyncRoute> { BackupSyncRouteScreen(onBack = { navController.popBackStack() }) }
            composable<ItemDetailRoute> { entry -> ItemDetailRouteScreen(entry.toRoute<ItemDetailRoute>().id, onBack = { navController.popBackStack() }) }
        }
    }
    com.pkos.app.capture.CaptureFlow(visible = showCapture, onDismiss = { showCapture = false }) { result ->
        scope.launch {
            val r = snackbar.showSnackbar(
                com.pkos.app.capture.CaptureMessages.snackbar(context, result),
                actionLabel = if (result is com.pkos.core.data.capture.CaptureResult.Saved) openLabel else null,
                withDismissAction = true,
                duration = androidx.compose.material3.SnackbarDuration.Long,
            )
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed && result is com.pkos.core.data.capture.CaptureResult.Saved) {
                navController.navigate(ItemDetailRoute(result.itemId))
            }
        }
    }
}

@Composable
private fun RowScope.TabItem(tab: Tab, selected: Boolean, badge: Int?, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            if (badge != null && badge > 0) BadgedBox(badge = { Badge { Text("$badge") } }) { Icon(if (selected) tab.selectedIcon else tab.icon, null) }
            else Icon(if (selected) tab.selectedIcon else tab.icon, null)
        },
        label = { Text(stringResource(tab.label), maxLines = 1) },
        modifier = Modifier.testTag(tab.tag),
    )
}

/** Prominent centre action — opens the capture sheet instead of navigating. */
@Composable
private fun RowScope.CaptureButton(onClick: () -> Unit) {
    val label = stringResource(R.string.nav_capture)
    Box(Modifier.weight(1f).align(Alignment.CenterVertically), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Surface(
                onClick = onClick,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = MaterialTheme.shapes.large,
                shadowElevation = 2.dp,
                modifier = Modifier.size(width = 64.dp, height = 48.dp).semantics { role = Role.Button; contentDescription = label }.testTag("tab_capture"),
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Add, null, modifier = Modifier.size(28.dp)) }
            }
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
