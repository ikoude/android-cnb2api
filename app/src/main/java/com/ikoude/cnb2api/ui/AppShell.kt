package com.ikoude.cnb2api.ui

import android.app.Activity
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikoude.cnb2api.R
import com.ikoude.cnb2api.ui.ios.IosTab
import com.ikoude.cnb2api.ui.ios.IosTabBar
import com.ikoude.cnb2api.ui.ios.IosTheme
import com.ikoude.cnb2api.ui.ios.LocalIos
import com.ikoude.cnb2api.vm.MainViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild

private data class TabSpec(val id: String, val label: String, @DrawableRes val icon: Int)

@Composable
fun AppShell(vm: MainViewModel) {
    val notice by vm.notice.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val themeOverride by vm.themeOverride.collectAsStateWithLifecycle()
    val tabsOverride by vm.tabsOverride.collectAsStateWithLifecycle()
    val focusId by vm.focusAccount.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val focusManager = LocalFocusManager.current

    val allTabs = remember {
        listOf(
            TabSpec("dashboard", "仪表盘", R.drawable.ic_tab_dashboard),
            TabSpec("accounts", "账号", R.drawable.ic_tab_accounts),
            TabSpec("settings", "设置", R.drawable.ic_tab_settings),
            TabSpec("logs", "日志", R.drawable.ic_tab_logs),
            TabSpec("about", "关于", R.drawable.ic_tab_about),
        )
    }
    val orderSource = tabsOverride?.first ?: settings.tabOrder
    val hiddenSource = tabsOverride?.second ?: settings.hiddenTabs
    val order = remember(orderSource) {
        orderSource.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
    val hidden = remember(hiddenSource) {
        hiddenSource.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
    val ordered = remember(order) {
        order.mapNotNull { id -> allTabs.firstOrNull { it.id == id } } + allTabs.filter { it.id !in order }
    }.distinctBy { it.id }
    // 设置页永远可见，避免隐藏后无法恢复设置。
    val visible = ordered.filter { it.id == "settings" || it.id !in hidden }
    val visibleKey = visible.joinToString(",") { it.id }

    var currentTab by rememberSaveable { mutableStateOf("dashboard") }
    LaunchedEffect(visibleKey) {
        if (visible.none { it.id == currentTab }) currentTab = visible.first().id
    }
    val selectedIndex = visible.indexOfFirst { it.id == currentTab }.let { if (it >= 0) it else 0 }
    val pagerState = rememberPagerState(initialPage = selectedIndex, pageCount = { visible.size })

    // 左右滑动切换页签：只在滑动完全停下后同步 currentTab，
    // 避免快速点底栏 / 翻页动画中途 currentPage 抢占当前页。
    LaunchedEffect(pagerState, visibleKey) {
        snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
            .collect { (page, scrolling) ->
                if (!scrolling) {
                    val id = visible.getOrNull(page)?.id
                    if (id != null && id != currentTab) currentTab = id
                }
            }
    }
    LaunchedEffect(selectedIndex, visibleKey) {
        if (visible.isNotEmpty() && pagerState.currentPage != selectedIndex) {
            pagerState.animateScrollToPage(selectedIndex)
        }
    }
    LaunchedEffect(visibleKey) {
        if (visible.isNotEmpty() && pagerState.currentPage >= visible.size) {
            pagerState.scrollToPage(visible.lastIndex)
        }
    }

    // 切页时释放输入焦点，避免其它页面继续弹出上一页的输入法。
    LaunchedEffect(currentTab) {
        focusManager.clearFocus(force = true)
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.ime())
        }
    }

    LaunchedEffect(focusId) {
        if (focusId != null) {
            // 不在这里消费：等账号页进入组合后自己展开并消费，避免事件丢失。
            currentTab = "accounts"
        }
    }

    LaunchedEffect(notice) {
        if (notice.isNotBlank()) {
            Toast.makeText(context, notice, Toast.LENGTH_SHORT).show()
            vm.clearNotice()
        }
    }

    IosTheme(
        appearance = themeOverride?.first ?: settings.appearance,
        accent = themeOverride?.second ?: settings.accent,
    ) {
        val p = LocalIos.current
        val activity = view.context as? Activity
        androidx.compose.runtime.SideEffect {
            val window = activity?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !p.isDark
            controller.isAppearanceLightNavigationBars = !p.isDark
        }
        val hazeState = remember { HazeState() }
        val glassTint = if (p.isDark) Color(0xB8000000) else Color(0xB8FFFFFF)

        Box(Modifier.fillMaxSize().background(p.groupedBg).statusBarsPadding()) {
            Box(
                Modifier.fillMaxSize().haze(
                    hazeState,
                    HazeStyle(
                        tint = if (p.isDark) Color(0x55000000) else Color(0x44FFFFFF),
                        blurRadius = 28.dp,
                        noiseFactor = 0.03f,
                    ),
                ),
            ) {
                val stateHolder = rememberSaveableStateHolder()
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = visible.size > 1,
                    beyondViewportPageCount = 1,
                    key = { visible.getOrNull(it)?.id ?: it },
                    flingBehavior = PagerDefaults.flingBehavior(
                        state = pagerState,
                        pagerSnapDistance = PagerSnapDistance.atMost(1),
                    ),
                ) { page ->
                    visible.getOrNull(page)?.let { tab ->
                        stateHolder.SaveableStateProvider(tab.id) {
                            when (tab.id) {
                                "dashboard" -> DashboardScreen(vm)
                                "accounts" -> AccountsScreen(vm)
                                "settings" -> SettingsScreen(vm)
                                "logs" -> LogsScreen(vm)
                                "about" -> AboutScreen(vm)
                            }
                        }
                    }
                }
            }
            Box(
                Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth()
                    .hazeChild(
                        hazeState,
                        style = HazeStyle(tint = glassTint, blurRadius = 28.dp, noiseFactor = 0.03f),
                    )
                    .navigationBarsPadding(),
            ) {
                IosTabBar(
                    tabs = visible.map { IosTab(it.label, it.icon) },
                    selected = selectedIndex,
                    onSelect = { currentTab = visible[it].id },
                )
            }
        }
    }
}
