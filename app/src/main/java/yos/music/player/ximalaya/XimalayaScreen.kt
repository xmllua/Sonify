package yos.music.player.ximalaya

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * 喜马拉雅页面：搜索专辑 -> 曲目列表 -> 点播放。
 *
 * 与酷狗流程完全独立（不经过 KugouRepository），只复用项目的 Compose / Coil / Media3。
 * 付费内容显示标记且不可点击，不绕过授权。
 */
@Composable
fun XimalayaPage(
    api: XimalayaApi,
    onPlay: (url: String, title: String, cover: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var keyword by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var albums by remember { mutableStateOf<List<XmAlbum>>(emptyList()) }
    var opened by remember { mutableStateOf<XmAlbum?>(null) }
    var tracks by remember { mutableStateOf<List<XmTrack>>(emptyList()) }

    fun doSearch() {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        scope.launch {
            loading = true
            error = null
            opened = null
            try {
                albums = api.searchAlbums(kw).albums
                if (albums.isEmpty()) error = "没有找到专辑"
            } catch (e: Exception) {
                error = e.message ?: "搜索失败"
            } finally {
                loading = false
            }
        }
    }

    fun openAlbum(a: XmAlbum) {
        scope.launch {
            loading = true
            error = null
            try {
                val page = api.albumTracks(a.albumId, pageSize = 60)
                tracks = page.tracks
                opened = page.album ?: a
                if (tracks.isEmpty()) error = "这个专辑没有可列出的声音"
            } catch (e: Exception) {
                error = e.message ?: "加载曲目失败"
            } finally {
                loading = false
            }
        }
    }

    fun play(t: XmTrack) {
        if (t.isPaid) {
            error = "《" + t.title + "》是付费内容，未登录无法播放"
            return
        }
        scope.launch {
            loading = true
            error = null
            try {
                val info = api.playInfo(t.trackId)
                val url = info.urls.firstOrNull { !it.isVipOnly }?.url ?: info.best?.url
                if (url.isNullOrEmpty()) {
                    error = "没有可用的免费音质"
                } else {
                    onPlay(url, info.title.ifEmpty { t.title }, info.cover)
                }
            } catch (e: Exception) {
                error = e.message ?: "获取播放地址失败"
            } finally {
                loading = false
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (opened != null) {
                TextButton(onClick = { opened = null; tracks = emptyList() }) {
                    Text("‹ 返回")
                }
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = opened?.title ?: "喜马拉雅",
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }

        if (opened == null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    placeholder = { Text("搜索有声书 / 节目", fontSize = 14.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { doSearch() }) { Text("搜索") }
            }
        }

        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        Spacer(Modifier.height(4.dp))

        if (opened == null) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(albums, key = { it.albumId }) { a ->
                    AlbumRow(a) { openAlbum(a) }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(tracks, key = { it.trackId }) { t ->
                    TrackRow(t) { play(t) }
                }
            }
        }
    }
}

@Composable
private fun AlbumRow(a: XmAlbum, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = a.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
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

@Composable
private fun TrackRow(t: XmTrack, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !t.isPaid, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                t.title.ifEmpty { "(无标题)" },
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (t.isPaid) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    mmss(t.duration),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (t.isPaid) {
                    Spacer(Modifier.width(8.dp))
                    Text("需付费", fontSize = 11.sp, color = Color(0xFFB26A00))
                }
            }
        }
        if (!t.isPaid) {
            Text("播放", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

private fun mmss(seconds: Int): String {
    if (seconds <= 0) return "--:--"
    val m = seconds / 60
    val s = seconds % 60
    return if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, s) else "%d:%02d".format(m, s)
}