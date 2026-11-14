package hu.billman.konsolessh.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import hu.billman.konsolessh.R
import hu.billman.konsolessh.session.ConnectionStatus
import hu.billman.konsolessh.session.TabState
import hu.billman.konsolessh.ui.theme.KonsoleColors
import hu.billman.konsolessh.ui.theme.Monospace

/**
 * The main screen: top bar with the menu, the tab strip with new tab and
 * zoom, the pages, and the key bar. The pages and the key bar are slots;
 * the screen arranges them and draws the bars from [tabs] and [pager].
 */
@Composable
fun MainScreen(
    tabs: List<TabState>,
    pages: List<PageKey>,
    pager: PagerState,
    actions: MainScreenActions,
    page: @Composable (PageKey) -> Unit,
    keybar: @Composable () -> Unit,
) {
    val safe = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KonsoleColors.Background)
            // Bottom: the larger of the navigation bar and the keyboard.
            .windowInsetsPadding(safe.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom).union(IME)),
    ) {
        Column(
            modifier = Modifier.background(KonsoleColors.Toolbar).windowInsetsPadding(safe.only(WindowInsetsSides.Top)),
        ) {
            TopBar(actions)
            TabStrip(tabs, pages, pager.currentPage, actions)
        }
        HorizontalPager(
            state = pager,
            key = { pages.getOrNull(it)?.key ?: it },
            // Keeps a few SSH tabs' views alive while swiping.
            beyondViewportPageCount = OFFSCREEN_PAGES,
            modifier = Modifier.weight(1f),
        ) { index -> pages.getOrNull(index)?.let { page(it) } }
        keybar()
    }
}

@Composable
private fun TopBar(actions: MainScreenActions) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = 12.dp),
    ) {
        AppIcon()
        Text(
            text = stringResource(R.string.app_name),
            color = KonsoleColors.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.content_desc_more),
                    tint = KonsoleColors.TextPrimary,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                listOf(
                    R.string.close_tab to actions.onCloseCurrentTab,
                    R.string.manage_connections to actions.onManageConnections,
                    R.string.action_language to actions.onLanguage,
                    R.string.menu_problem_report to actions.onProblemReport,
                    R.string.exit to actions.onExit,
                ).forEach { (label, action) ->
                    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                        menuOpen = false
                        action()
                    })
                }
            }
        }
    }
}

/** The launcher icon; an adaptive icon, which painterResource cannot draw, so it is rasterised once. */
@Composable
private fun AppIcon() {
    val context = LocalContext.current
    val icon = remember { ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap()?.asImageBitmap() }
    if (icon != null) {
        Image(icon, contentDescription = stringResource(R.string.app_name), modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun TabStrip(tabs: List<TabState>, pages: List<PageKey>, currentPage: Int, actions: MainScreenActions) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(42.dp).background(KonsoleColors.Background),
    ) {
        Row(modifier = Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState())) {
            tabs.forEach { tab ->
                val index = pages.indexOf(PageKey.Tab(tab.id))
                TabItem(
                    tab = tab,
                    selected = index == currentPage,
                    onClick = { actions.onSelectPage(index) },
                    onLongClick = { actions.onRenameTab(tab) },
                    onClose = { actions.onCloseTab(tab.id) },
                )
            }
        }
        BarButton(R.drawable.ic_add, R.string.content_desc_new_tab, KonsoleColors.Accent, actions.onNewTab)
        BarButton(R.drawable.ic_zoom_out, R.string.content_desc_zoom_out, ZOOM_TINT) { actions.onZoom(-1f) }
        BarButton(R.drawable.ic_zoom_in, R.string.content_desc_zoom_in, ZOOM_TINT) { actions.onZoom(1f) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabItem(
    tab: TabState,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .drawBehind {
                if (selected) {
                    val height = INDICATOR_HEIGHT.toPx()
                    drawRect(
                        color = KonsoleColors.Accent,
                        topLeft = Offset(0f, size.height - height),
                        size = Size(size.width, height),
                    )
                }
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxHeight().padding(start = 10.dp, end = 4.dp),
        ) {
            if (tab.status != ConnectionStatus.NONE) {
                Text("●", color = statusColor(tab.status), fontSize = 9.sp, modifier = Modifier.padding(end = 6.dp))
            }
            Text(
                text = tab.title,
                color = if (selected) KonsoleColors.TextPrimary else KonsoleColors.TextSecondary,
                fontFamily = Monospace,
                fontSize = 13.sp,
                maxLines = 1,
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.width(24.dp).fillMaxHeight().combinedClickable(onClick = onClose),
            ) {
                Text("✕", color = KonsoleColors.TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun BarButton(icon: Int, description: Int, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(42.dp)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = stringResource(description),
            tint = tint,
            modifier = Modifier.padding(if (icon == R.drawable.ic_add) 0.dp else 9.dp),
        )
    }
}

private val IME @Composable get() = WindowInsets.ime
private val ZOOM_TINT = Color(0xFFAAAAAA)
private val INDICATOR_HEIGHT = 3.dp
private const val OFFSCREEN_PAGES = 5
