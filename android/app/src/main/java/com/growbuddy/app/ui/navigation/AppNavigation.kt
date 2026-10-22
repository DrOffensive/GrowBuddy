package com.growbuddy.app.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.growbuddy.app.AlertCounts
import com.growbuddy.app.R
import com.growbuddy.app.data.remote.ConfigState
import com.growbuddy.app.ui.alerts.AlertsScreen
import com.growbuddy.app.ui.common.appContainer
import com.growbuddy.app.ui.common.appString
import com.growbuddy.app.ui.dashboard.DashboardScreen
import com.growbuddy.app.ui.dashboard.LiveViewScreen
import com.growbuddy.app.ui.history.HistoryScreen
import com.growbuddy.app.ui.journal.GrowLogScreen
import com.growbuddy.app.ui.journal.LogDetailScreen
import com.growbuddy.app.ui.journal.LogWizardScreen
import com.growbuddy.app.ui.plants.PlantDetailScreen
import com.growbuddy.app.ui.plants.PlantsScreen
import com.growbuddy.app.ui.schedule.ScheduleEditorScreen
import com.growbuddy.app.ui.schedule.ScheduleScreen
import com.growbuddy.app.ui.sensors.SensorDetailScreen
import com.growbuddy.app.ui.sensors.SensorsScreen
import com.growbuddy.app.ui.settings.SettingsScreen
import com.growbuddy.app.ui.setup.SetupScreen
import com.growbuddy.app.ui.splash.SplashScreen
import com.growbuddy.app.ui.strains.StrainsScreen
import com.growbuddy.app.ui.theme.Palette
import com.growbuddy.app.ui.timelapse.TimelapseScreen
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

@Serializable data object StrainsRoute
@Serializable data object GrowLogGraph
@Serializable data object GrowLogRoute
@Serializable data class LogDetailRoute(val id: String)
@Serializable data class LogWizardRoute(val plantId: String? = null)
@Serializable data object DashboardRoute
@Serializable data object ScheduleRoute
@Serializable data class ScheduleEditRoute(val editId: Long = -1, val copyFromId: Long = -1) // -1 = none
@Serializable data object TimelapseRoute
@Serializable data object LiveViewRoute
@Serializable data object AlertsRoute
@Serializable data class HistoryRoute(val sensorId: Long)
@Serializable data object PlantsGraph
@Serializable data object PlantsRoute
@Serializable data class PlantDetailRoute(val id: String)
@Serializable data object SettingsGraph
@Serializable data object SettingsRoute
@Serializable data object SensorsRoute
@Serializable data class SensorDetailRoute(val id: Long)

private data class Tab(@DrawableRes val icon: Int, @StringRes private val labelRes: Int, val route: Any) {
    val label: String get() = appString(labelRes)
}

// 0.1.6 order: strains, grow log, home (centre), plants, settings.
private val tabs = listOf(
    Tab(R.drawable.ic_nav_strains, R.string.strains_title, StrainsRoute),
    Tab(R.drawable.ic_nav_growlog, R.string.plant_grow_log, GrowLogGraph),
    Tab(R.drawable.ic_nav_home, R.string.dash_title, DashboardRoute),
    Tab(R.drawable.ic_nav_plants, R.string.common_plants, PlantsGraph),
    Tab(R.drawable.ic_nav_settings, R.string.settings_title, SettingsGraph),
)

private const val SPLASH_MS = 1_200L

/** Root: splash, then first-run setup until a server is configured, then the tabbed app. */
@Composable
fun GrowBuddyRoot(deepLink: String? = null, onDeepLinkOpened: () -> Unit = {}) {
    val configState by appContainer().apiProvider.configState.collectAsStateWithLifecycle()
    var splashDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SPLASH_MS)
        splashDone = true
    }
    Box(Modifier.fillMaxSize().background(Palette.backgroundBrush)) {
        when {
            !splashDone || configState == ConfigState.Loading -> SplashScreen()
            configState == ConfigState.Missing -> SetupScreen()
            else -> MainShell(deepLink, onDeepLinkOpened)
        }
    }
}

@Composable
private fun MainShell(deepLink: String?, onDeepLinkOpened: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    // growbuddy://alerts (notification tap), growbuddy://log (home widget quick log)
    LaunchedEffect(deepLink) {
        when (deepLink) {
            "alerts" -> nav.navigate(AlertsRoute) { launchSingleTop = true }
            "log" -> nav.navigate(LogWizardRoute()) { launchSingleTop = true }
            null -> return@LaunchedEffect
            else -> Unit // unknown link: just open the app
        }
        onDeepLinkOpened()
    }
    val alertCounts by appContainer().alertCounts.collectAsStateWithLifecycle()
    // The log wizard and the full-screen live view take the whole screen: no bottom bar.
    val fullScreen = backStack?.destination?.let { it.hasRoute(LogWizardRoute::class) || it.hasRoute(LiveViewRoute::class) } == true
    val liveView = backStack?.destination?.hasRoute(LiveViewRoute::class) == true
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        if (!liveView) OfflineBar()
        NavHost(nav, startDestination = DashboardRoute, modifier = Modifier.weight(1f)) {
            composable<StrainsRoute> { StrainsScreen() }
            navigation<GrowLogGraph>(startDestination = GrowLogRoute) {
                composable<GrowLogRoute> {
                    GrowLogScreen(
                        onOpen = { nav.navigate(LogDetailRoute(it)) },
                        onNewLog = { nav.navigate(LogWizardRoute()) },
                        onSchedule = { nav.navigate(ScheduleRoute) },
                    )
                }
                composable<LogDetailRoute> { entry ->
                    LogDetailScreen(entry.toRoute<LogDetailRoute>().id, onBack = { nav.popBackStack() })
                }
            }
            composable<DashboardRoute> {
                DashboardScreen(
                    onOpenPlant = { nav.navigate(PlantDetailRoute(it)) },
                    onOpenHistory = { nav.navigate(HistoryRoute(it)) },
                    onOpenAlerts = { nav.navigate(AlertsRoute) },
                    onOpenTimelapse = { nav.navigate(TimelapseRoute) },
                    onOpenLiveView = { nav.navigate(LiveViewRoute) },
                )
            }
            composable<LiveViewRoute> { LiveViewScreen(onClose = { nav.popBackStack() }) }
            composable<TimelapseRoute> { TimelapseScreen(onBack = { nav.popBackStack() }) }
            composable<ScheduleRoute> {
                ScheduleScreen(
                    onBack = { nav.popBackStack() },
                    onEdit = { edit, copy -> nav.navigate(ScheduleEditRoute(edit ?: -1, copy ?: -1)) },
                )
            }
            composable<ScheduleEditRoute> { entry ->
                val route = entry.toRoute<ScheduleEditRoute>()
                ScheduleEditorScreen(
                    editId = route.editId.takeIf { it >= 0 },
                    copyFromId = route.copyFromId.takeIf { it >= 0 },
                    onClose = { nav.popBackStack() },
                )
            }
            composable<AlertsRoute> { AlertsScreen(onBack = { nav.popBackStack() }) }
            composable<HistoryRoute> { entry ->
                HistoryScreen(entry.toRoute<HistoryRoute>().sensorId, onBack = { nav.popBackStack() })
            }
            navigation<PlantsGraph>(startDestination = PlantsRoute) {
                composable<PlantsRoute> { PlantsScreen(onOpen = { nav.navigate(PlantDetailRoute(it)) }) }
                composable<PlantDetailRoute> { entry ->
                    PlantDetailScreen(
                        id = entry.toRoute<PlantDetailRoute>().id,
                        onBack = { nav.popBackStack() },
                        onAddLog = { nav.navigate(LogWizardRoute(it)) },
                        onOpenLog = { nav.navigate(LogDetailRoute(it)) },
                    )
                }
            }
            composable<LogWizardRoute> { entry ->
                LogWizardScreen(entry.toRoute<LogWizardRoute>().plantId, onClose = { nav.popBackStack() })
            }
            navigation<SettingsGraph>(startDestination = SettingsRoute) {
                composable<SettingsRoute> {
                    SettingsScreen(
                        onSensors = { nav.navigate(SensorsRoute) },
                        onAlerts = { nav.navigate(AlertsRoute) },
                        onSchedule = { nav.navigate(ScheduleRoute) },
                    )
                }
                composable<SensorsRoute> {
                    SensorsScreen(onOpen = { nav.navigate(SensorDetailRoute(it)) }, onBack = { nav.popBackStack() })
                }
                composable<SensorDetailRoute> { entry ->
                    SensorDetailScreen(id = entry.toRoute<SensorDetailRoute>().id, onBack = { nav.popBackStack() })
                }
            }
        }
        if (!fullScreen) BottomBar(
            alerts = alertCounts,
            isSelected = { route -> backStack?.destination?.hierarchy?.any { it.hasRoute(route::class) } == true },
            onSelect = { route ->
                nav.navigate(route) {
                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
        )
    }
}

/** Shown while the server is unreachable: data comes from the offline cache, writes are queued. */
@Composable
private fun OfflineBar() {
    val container = appContainer()
    val offline by container.apiProvider.offline.collectAsStateWithLifecycle()
    val outbox by container.outbox.state.collectAsStateWithLifecycle()
    val pending = outbox.pending.size
    if (!offline && pending == 0) return
    Text(
        listOfNotNull(
            stringResource(R.string.nav_offline).takeIf { offline },
            if (pending > 0) pluralStringResource(R.plurals.nav_pending, pending, pending) else null,
        ).joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = Palette.TextPrimary,
        modifier = Modifier.fillMaxWidth().background(Palette.Warning.copy(alpha = 0.35f)).padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

private val BarHeight = 76.dp
private val HomeRise = 10.dp // the 0.1.6 home tab stands above the bar

/** 0.1.6 bottom bar: five icons, no labels, raised centre tab for Home. */
@Composable
private fun BottomBar(alerts: AlertCounts, isSelected: (Any) -> Boolean, onSelect: (Any) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(BarHeight + HomeRise)) {
            val slot = maxWidth / tabs.size
            // Bar background (the strip above it stays transparent except for the home tab).
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(BarHeight).background(Palette.NavBar))
            // Raised centre tab behind the home icon.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .width(slot * 0.82f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .background(Palette.NavCenter),
            ) {
                // Active alerts in the tab's top corners: warnings left, criticals right.
                if (alerts.warning > 0) HomeBadge(alerts.warning, Palette.Warning, Modifier.align(Alignment.TopStart))
                if (alerts.critical > 0) HomeBadge(alerts.critical, Palette.Danger, Modifier.align(Alignment.TopEnd))
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(BarHeight), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEach { tab ->
                    val selected = isSelected(tab.route)
                    val home = tab.route == DashboardRoute
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Tab,
                                onClickLabel = tab.label,
                            ) { onSelect(tab.route) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painterResource(tab.icon),
                            contentDescription = tab.label,
                            modifier = Modifier
                                .offset(y = if (home) -HomeRise / 2 else 0.dp)
                                .size(if (home) 50.dp else 46.dp),
                            colorFilter = ColorFilter.tint(if (selected) Palette.NavSelected else Palette.TextPrimary),
                        )
                    }
                }
            }
        }
        // System navigation bar area, in the bar colour.
        Box(Modifier.fillMaxWidth().background(Palette.NavBar).navigationBarsPadding())
    }
}

@Composable
private fun HomeBadge(count: Int, color: Color, modifier: Modifier) {
    Text(
        if (count > 99) "99+" else count.toString(),
        color = color,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp),
        modifier = modifier.padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

