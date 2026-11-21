package com.timeout.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import androidx.navigation.toRoute
import com.timeout.app.R
import com.timeout.app.ui.screens.*
import com.timeout.app.ui.theme.DesignTokens
import com.timeout.app.ui.theme.LocalReducedMotion
import com.timeout.app.ui.theme.TimeOutEaseOut
import com.timeout.core.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.Serializable

@Serializable object Home
@Serializable object Today
@Serializable object Plans
@Serializable object Insights
@Serializable object Builder
@Serializable object AppPicker
@Serializable data class Setup(val fromDraft: Boolean = false)
@Serializable object Protection
@Serializable data class AppProtection(val packageName: String)
@Serializable data class Details(val id: String)
@Serializable object SettingsPage
@Serializable object About

private data class Destination(val route: Any, val label: String, val icon: Int)
private val destinations = listOf(Destination(Today, "Today", R.drawable.ic_home), Destination(Plans, "Plans", R.drawable.ic_calendar_today), Destination(Insights, "Insights", R.drawable.ic_bar_chart))

@Composable
fun TimeOutApp(vm: TimeOutViewModel, openProtectionSignal: Long) {
    val state by vm.state.collectAsStateWithLifecycle()
    val reducedMotion by vm.container.reducedMotion.collectAsStateWithLifecycle()
    // Separate the new graph's saved navigation state from earlier flat-graph installations.
    key("opaque-page-graph-v2") {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val snackbar = remember { SnackbarHostState() }
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
        var showTodayRequest by remember { mutableIntStateOf(0) }

        LifecycleStartEffect(vm) {
            vm.setUiActive(true)
            onStopOrDispose { vm.setUiActive(false) }
        }

        fun open(route: Any, from: NavBackStackEntry, prepare: () -> Boolean = { true }) {
            // Reject a second click from a page that already navigated away. Incoming pages
            // remain interactive; Back/gestures can reverse a transition immediately.
            if (nav.currentBackStackEntry?.id != from.id || !prepare()) return
            focus.clearFocus()
            keyboard?.hide()
            nav.navigate(route) { launchSingleTop = true }
        }

        fun back(from: NavBackStackEntry) {
            if (nav.currentBackStackEntry?.id != from.id) return
            focus.clearFocus()
            keyboard?.hide()
            nav.popBackStack()
        }

        fun newDraft(from: NavBackStackEntry, context: LifeContext, quick: Boolean) = open(Builder, from) {
            vm.beginDraft(if (quick) RuleKind.QUICK_BLOCK else if (context == LifeContext.PERSONAL) RuleKind.ALLOW_ONLY else RuleKind.BLOCK_DURING, context)
        }

        LaunchedEffect(vm) {
            vm.events.collectLatest { event ->
                when (event) {
                    is UiEvent.Message -> snackbar.showSnackbar(event.text)
                    is UiEvent.Saved -> {
                        focus.clearFocus()
                        keyboard?.hide()
                        if (event.pendingRuleId != null) {
                            nav.navigate(Details(event.pendingRuleId)) { popUpTo<Home>(); launchSingleTop = true }
                        } else {
                            showTodayRequest++
                            if (!nav.popBackStack<Home>(inclusive = false)) nav.navigate(Home) { launchSingleTop = true }
                        }
                        snackbar.showSnackbar(event.text)
                    }
                }
            }
        }
        LaunchedEffect(openProtectionSignal, state.loading) {
            if (openProtectionSignal > 0 && !state.loading) nav.navigate(Protection) { launchSingleTop = true }
        }

        CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                if (state.loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else NavHost(nav, startDestination = Home, modifier = Modifier.fillMaxSize(),
                    enterTransition = {
                        if (initialState.destination.id == targetState.destination.id) EnterTransition.None
                        else if (reducedMotion) fadeIn(tween(DesignTokens.tabEnterMs, DesignTokens.tabEnterDelayMs, TimeOutEaseOut))
                        else slideInHorizontally(tween(DesignTokens.screenChangeMs, easing = TimeOutEaseOut)) { it * direction }
                    },
                    exitTransition = {
                        if (initialState.destination.id == targetState.destination.id) ExitTransition.None
                        else if (reducedMotion) fadeOut(tween(DesignTokens.tabExitMs, easing = TimeOutEaseOut))
                        else slideOutHorizontally(tween(DesignTokens.screenChangeMs, easing = TimeOutEaseOut)) { -it * direction / 4 }
                    },
                    popEnterTransition = {
                        if (reducedMotion) fadeIn(tween(DesignTokens.tabEnterMs, DesignTokens.tabEnterDelayMs, TimeOutEaseOut))
                        else slideInHorizontally(tween(DesignTokens.screenPopMs, easing = TimeOutEaseOut)) { -it * direction / 4 }
                    },
                    popExitTransition = {
                        if (reducedMotion) fadeOut(tween(DesignTokens.tabExitMs, easing = TimeOutEaseOut))
                        else slideOutHorizontally(tween(DesignTokens.screenPopMs, easing = TimeOutEaseOut)) { it * direction }
                    }) {
                    composable<Home> { page ->
                        RootPage(entry?.id == page.id, snackbar, constrain = false) {
                            MainTabs(state, vm, showTodayRequest,
                                onSettings = { open(SettingsPage, page) },
                                onBlock = { newDraft(page, it, true) }, onRoutine = { newDraft(page, it, false) },
                                onProtection = { open(Protection, page) }, onApp = { open(AppProtection(it), page) },
                                onSetup = { open(Setup(), page) }, onRule = { open(Details(it.id), page) })
                        }
                    }
                    composable<Builder> { page -> RootPage(entry?.id == page.id, snackbar) {
                        val draft by vm.draft.collectAsStateWithLifecycle()
                        RuleEditorScreen(vm, state, draft, { back(page) }, { open(AppPicker, page) }, { open(Setup(true), page) })
                    } }
                    composable<AppPicker> { page -> RootPage(entry?.id == page.id, snackbar) {
                        val draft by vm.draft.collectAsStateWithLifecycle()
                        AppPickerScreen(state, draft, vm::toggleApp, vm::reloadApps) { back(page) }
                    } }
                    composable<Setup> { page -> RootPage(entry?.id == page.id, snackbar) {
                        SetupScreen(vm, state, page.toRoute<Setup>().fromDraft) { back(page) }
                    } }
                    composable<Protection> { page -> RootPage(entry?.id == page.id, snackbar) {
                        ProtectionScreen(state, { back(page) }, { open(Details(it.id), page) }, { open(Setup(), page) },
                            null, vm.clock, { open(AppProtection(it), page) })
                    } }
                    composable<AppProtection> { page -> RootPage(entry?.id == page.id, snackbar) {
                        ProtectionScreen(state, { back(page) }, { open(Details(it.id), page) }, { open(Setup(), page) },
                            page.toRoute<AppProtection>().packageName, vm.clock, { })
                    } }
                    composable<Details> { page -> RootPage(entry?.id == page.id, snackbar) {
                        RuleDetailScreen(vm, state, page.toRoute<Details>().id, { back(page) },
                            { rule -> open(Builder, page) { vm.beginDraft(rule.kind, rule.context, rule) } },
                            { rule -> open(Builder, page) {
                                vm.beginDraft(RuleKind.QUICK_BLOCK, rule.context)
                                vm.updateDraft { it.copy(packages = rule.packages.toSet()) }
                                true
                            } }, { open(Setup(), page) })
                    } }
                    composable<SettingsPage> { page -> RootPage(entry?.id == page.id, snackbar) {
                        SettingsScreen(vm, state, { back(page) }, { open(Setup(), page) }, { open(About, page) })
                    } }
                    composable<About> { page -> RootPage(entry?.id == page.id, snackbar) { AboutScreen { back(page) } } }
                }
            }
        }
    }
}

/** Each destination owns an opaque full-window background, independent insets and scroll state. */
@Composable
private fun PageSurface(active: Boolean, constrain: Boolean = true, content: @Composable () -> Unit) {
    val inactiveInput = if (active) Modifier else Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }.semantics { hideFromAccessibility() }
    CompositionLocalProvider(LocalDestinationActive provides active) {
        Surface(Modifier.fillMaxSize().then(inactiveInput), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box((if (constrain) Modifier.widthIn(max = 840.dp) else Modifier).fillMaxSize()) { content() }
            }
        }
    }
}

@Composable
private fun RootPage(active: Boolean, snackbar: SnackbarHostState, constrain: Boolean = true, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPageSnackbar provides if (active) snackbar else null) { PageSurface(active, constrain, content) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTabs(state: AppUiState, vm: TimeOutViewModel, showTodayRequest: Int,
    onSettings: () -> Unit, onBlock: (LifeContext) -> Unit, onRoutine: (LifeContext) -> Unit,
    onProtection: () -> Unit, onApp: (String) -> Unit, onSetup: () -> Unit, onRule: (Rule) -> Unit) {
    val tabs = rememberNavController()
    val current by tabs.currentBackStackEntryAsState()
    val selected = when {
        current?.destination?.hasRoute<Plans>() == true -> 1
        current?.destination?.hasRoute<Insights>() == true -> 2
        else -> 0
    }
    val snackbar = LocalPageSnackbar.current

    fun select(route: Any) {
        tabs.navigate(route) { popUpTo(tabs.graph.findStartDestination().id) { saveState = true }; restoreState = true; launchSingleTop = true }
    }
    LaunchedEffect(showTodayRequest) { if (showTodayRequest > 0) select(Today) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        Scaffold(containerColor = MaterialTheme.colorScheme.background,
            topBar = { TopAppBar(title = {
                if (selected == 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Symbol(R.drawable.ic_brand_mark, tint = MaterialTheme.colorScheme.primary)
                    Text("Time Out", style = MaterialTheme.typography.titleLarge)
                } else Text(destinations[selected].label, style = MaterialTheme.typography.titleLarge)
            }, actions = { IconButton(onSettings) { Symbol(R.drawable.ic_settings, description = "Open settings") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
            bottomBar = { if (!wide) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                destinations.forEachIndexed { index, destination -> NavigationBarItem(selected == index, { if (selected != index) select(destination.route) },
                    icon = { Symbol(destination.icon) }, label = { Text(destination.label) }) }
            } }, snackbarHost = { snackbar?.let { SnackbarHost(it) } }) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide) NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    Spacer(Modifier.height(16.dp))
                    destinations.forEachIndexed { index, destination -> NavigationRailItem(selected == index, { if (selected != index) select(destination.route) },
                        icon = { Symbol(destination.icon) }, label = { Text(destination.label) }) }
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    NavHost(tabs, startDestination = Today, modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
                        enterTransition = { fadeIn(tween(DesignTokens.tabEnterMs, DesignTokens.tabEnterDelayMs, TimeOutEaseOut)) },
                        exitTransition = { fadeOut(tween(DesignTokens.tabExitMs, easing = TimeOutEaseOut)) },
                        popEnterTransition = { fadeIn(tween(DesignTokens.tabEnterMs, DesignTokens.tabEnterDelayMs, TimeOutEaseOut)) },
                        popExitTransition = { fadeOut(tween(DesignTokens.tabExitMs, easing = TimeOutEaseOut)) }) {
                        composable<Today> { page -> PageSurface(current?.id == page.id, constrain = false) {
                            TodayScreen(state, vm.clock, { if (current?.id == page.id) onBlock(it) }, { if (current?.id == page.id) onRoutine(it) },
                                onProtection, onSetup, onRule, onApp)
                        } }
                        composable<Plans> { page -> PageSurface(current?.id == page.id, constrain = false) { PlansScreen(state, onRoutine, onRule) } }
                        composable<Insights> { page -> PageSurface(current?.id == page.id, constrain = false) { InsightsScreen(state) { onBlock(LifeContext.STUDY) } } }
                    }
                }
            }
        }
    }
}
