package yos.music.player.ui.pages.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import yos.music.player.R
import yos.music.player.ui.UI
import yos.music.player.ui.toUI
import yos.music.player.ui.widgets.basic.ImageQuality
import yos.music.player.ui.widgets.basic.ShadowImage
import yos.music.player.ui.widgets.basic.calculateAdaptiveImageLayout
import yos.music.player.ximalaya.XmAlbum
import yos.music.player.ximalaya.XimalayaApi

/**
 * 首页「喜马拉雅精选」板块。
 *
 * ## 三个设计约束
 * 1. **不阻塞首屏**。数据在板块自己的 LaunchedEffect 里拉，失败就整块不渲染
 *    （返回 null），而不是把首页拖进 loading 或显示一个"加载失败"的占位——外站
 *    服务的可用性不该影响本地/酷狗内容的呈现。
 * 2. **只做入口，不在这里播放**。点卡片进 [UI.Settings.Ximalaya] 页选具体某一集。
 *    在首页让用户直接点播"某张有声书专辑"是没有意义的：专辑有几百集，
 *    总得让他选从哪一集开始。
 * 3. 视觉与「本周新发行」的方卡一致（150dp 宽 + 7dp 圆角 + 两行标题），
 *    不引入新的卡片语言。
 *
 * 数据用 `remember` 缓存，切 Tab 回来不重新请求；下拉刷新走 [refreshKey]
 * （Discovery 的 refreshAll 会递增它）。
 */
@Composable
internal fun XimalayaHomeSection(
    navController: NavController,
    refreshKey: Int = 0,
) {
    var albums by remember { mutableStateOf<List<XmAlbum>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) {
        failed = false
        runCatching {
            val api = XimalayaApi()
            api.categoryAlbums(categoryId = 1001, page = 1, rows = 10)
        }.onSuccess { albums = it }
            .onFailure { failed = true }
    }

    // 拉不到 / 拉到空 → 整块不渲染（不是显示空标题）
    if (failed || albums.isEmpty()) return

    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        // Header 点击进完整页面（与其它板块同构：20sp Bold + chevron）
        DiscoverySectionHeader(
            title = stringResource(id = R.string.home_ximalaya_title),
            onClick = { navController.toUI(UI.Settings.Ximalaya) },
        )
        Box(Modifier.fillMaxWidth()) {
            val railLayout = calculateAdaptiveImageLayout(
                containerWidth = LocalConfiguration.current.screenWidthDp.dp,
                startPadding = 20.dp,
                endPadding = 20.dp
            )
            val pagerState = rememberPagerState(pageCount = { albums.size })
            HorizontalPager(
                state = pagerState,
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    pagerSnapDistance = XimalayaRailSnapDistance
                ),
                pageSize = PageSize.Fixed(railLayout.pageSize),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp),
                key = { albums[it].albumId },
                beyondViewportPageCount = 0
            ) { page ->
                XimalayaAlbumCard(
                    album = albums[page],
                    modifier = Modifier.width(railLayout.itemSize),
                ) {
                    navController.toUI(UI.Settings.Ximalaya)
                }
            }
        }
    }
}

/** 与 [DiscoveryRailSnapDistance] 同语义：吸附边界交给 Pager 自己夹紧。 */
private val XimalayaRailSnapDistance = PagerSnapDistance.atMost(Int.MAX_VALUE)

@Composable
private fun XimalayaAlbumCard(
    album: XmAlbum,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            ShadowImage(
                dataLambda = { album.cover.takeIf { it.isNotEmpty() } },
                contentDescription = album.title,
                modifier = Modifier.fillMaxSize(),
                shadowAlpha = 0f,
                cornerRadius = 7.dp,
                imageQuality = ImageQuality.HIGH,
                matchDisplaySize = true
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = album.title.ifEmpty { "(无标题)" },
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = listOfNotNull(
                album.nickname.takeIf { it.isNotEmpty() },
                album.trackCount.takeIf { it > 0 }?.let { "$it 集" },
            ).joinToString(" · "),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
