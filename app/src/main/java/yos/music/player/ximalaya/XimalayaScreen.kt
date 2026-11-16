package yos.music.player.ximalaya

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.ui.widgets.basic.Title

/**
 * 喜马拉雅页：分类精选 / 搜索 → 专辑曲目 → 点播。
 *
 * ## 为什么用 [Title] 而不是自建 Scaffold + Column
 * 项目的页面骨架、大标题、返回手势、滚动联动全都收在 [Title] 里；自建 Scaffold
 * 的结果是没有大标题、返回键没接、状态栏留白不对——上一版就是这样，看起来像个
 * 塞进设置页的外挂面板。改用 [Title] 后它与「搜索」「资料库」是同一套视觉语言。
 *
 * ## 播放语义
 * 点一集 = **整张专辑入队**并从该集起播（见 [playXimalayaTrack]），而不是只播一条：
 * 这样迷你播放条、播放页、上一首/下一首、通知栏才都认得这个队列。
 * 付费集（`isFree=false`）在列表里可点但只弹提示——不放它们进队列，
 * 否则每播到一集就报错跳歌，听感变成"一直在跳"。
 */
@Composable
fun XimalayaPage(
    api: XimalayaApi,
    onBack: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var keyword by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    var hotWords by remember { mutableStateOf<List<String>>(emptyList()) }
    var albums by remember { mutableStateOf<List<XmAlbum>>(emptyList()) }
    var opened by remember { mutableStateOf<XmAlbum?>(null) }
    var tracks by remember { mutableStateOf<List<XmTrack>>(emptyList()) }
    var tracksLoading by remember { mutableStateOf(false) }

    fun doSearch(kw: String) {
        val q = kw.trim()
        if (q.isEmpty() || searching) return
        keyword = q
        scope.launch {
            searching = true
            error = null
            opened = null
            tracks = emptyList()
            try {
                val page = api.searchAlbums(q, pageSize = 30)
                albums = page.albums
                if (albums.isEmpty()) error = "没有找到专辑，换个关键词试试"
            } catch (e: Exception) {
                error = e.message ?: "搜索失败"
            } finally {
                searching = false
            }
        }
    }

    fun openAlbum(a: XmAlbum) {
        scope.launch {
            tracksLoading = true
            error = null
            notice = null
            try {
                val page = api.albumTracks(a.albumId, pageSize = 60)
                tracks = page.tracks
                opened = page.album ?: a
                if (tracks.isEmpty()) error = "这个专辑没有可列出的声音"
            } catch (e: Exception) {
                error = e.message ?: "加载曲目失败"
            } finally {
                tracksLoading = false
            }
        }
    }

    fun play(t: XmTrack) {
        notice = null
        val album = opened
        if (!t.playable) {
            error = "《${t.title}》需要购买后才能收听"
            return
        }
        error = null
        scope.launch {
            // 整张专辑入队：播放器、迷你条、播放页从这里开始认得这个队列
            notice = playXimalayaTrack(track = t, siblings = tracks, album = album)
        }
    }

    // 首次进入拉分类精选与热搜词（都失败也不打断页面）
    LaunchedEffect(Unit) {
        if (albums.isEmpty()) {
            searching = true
            runCatching { api.categoryAlbums(1001, 1, 12) }
                .onSuccess { albums = it }
                .onFailure { error = it.message ?: "加载精选失败" }
            searching = false
        }
        runCatching { api.hotWords() }.onSuccess { hotWords = it }
    }

    Title(
        title = stringResource(id = R.string.page_ximalaya_title),
        subTitle = stringResource(id = R.string.page_ximalaya_sub),
        onBack = onBack,
        titleHorizontalPadding = 20.dp,
        content = {
            item("XmSearch") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = keyword,
                            onValueChange = { keyword = it },
                            placeholder = {
                                Text(
                                    text = stringResource(id = R.string.page_ximalaya_hint),
                                    fontSize = 14.sp
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { doSearch(keyword) }),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { doSearch(keyword) }
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = stringResource(id = R.string.page_ximalaya_search),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    if (hotWords.isNotEmpty() && opened == null && albums.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        // 热词只是“点一下就搜”的快捷入口，不做成独立列表页
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            hotWords.take(3).forEach { word ->
                                Text(
                                    text = word,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .clickable { doSearch(word) }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                    if (searching || tracksLoading) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(id = R.string.page_ximalaya_loading),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    error?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                        )
                    }
                    notice?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            val current = opened
            if (current == null) {
                item("XmSectionHeader") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        Text(
                            text = if (keyword.isEmpty()) {
                                stringResource(id = R.string.page_ximalaya_featured)
                            } else {
                                stringResource(id = R.string.page_ximalaya_results, keyword)
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            lineHeight = 20.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
                items(albums, key = { "album-${it.albumId}" }) { a ->
                    AlbumRow(a) { openAlbum(a) }
                }
            } else {
                item("XmAlbumHeader") {
                    AlbumHeader(current, total = tracks.size)
                }
                items(tracks, key = { "track-${it.trackId}" }) { t ->
                    TrackRow(t) { play(t) }
                }
            }
        }
    )
}

@Composable
private fun AlbumHeader(album: XmAlbum, total: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = album.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = album.title.ifEmpty { "(无标题)" },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = listOfNotNull(
                        album.nickname.takeIf { it.isNotEmpty() },
                        if (total > 0) "$total 集" else null,
                        if (album.isPaid) "付费专辑" else null,
                    ).joinToString(" · "),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun AlbumRow(a: XmAlbum, onClick: () -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = a.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    a.title.ifEmpty { "(无标题)" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (a.nickname.isNotEmpty()) {
                        Text(
                            a.nickname,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (a.trackCount > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${a.trackCount} 集",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (a.isPaid) {
                        Spacer(Modifier.width(6.dp))
                        Text("付费", fontSize = 11.sp, color = Color(0xFFB26A00))
                    }
                }
            }
        }
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
    }
}

@Composable
private fun TrackRow(t: XmTrack, onClick: () -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                // 付费集也可点：点了给一句明确提示，比“点不动”更好懂
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    t.title.ifEmpty { "(无标题)" },
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (t.playable) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        mmss(t.duration),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!t.playable) {
                        Spacer(Modifier.width(8.dp))
                        Text("需购买", fontSize = 11.sp, color = Color(0xFFB26A00))
                    }
                }
            }
            Text(
                text = if (t.playable) "播放" else "付费",
                fontSize = 13.sp,
                color = if (t.playable) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
    }
}

private fun mmss(seconds: Int): String {
    if (seconds <= 0) return "--:--"
    val m = seconds / 60
    val s = seconds % 60
    return if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, s) else "%d:%02d".format(m, s)
}
