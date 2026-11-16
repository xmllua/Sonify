package yos.music.player.ximalaya

import android.net.Uri
import yos.music.player.code.MediaController
import yos.music.player.data.libraries.YosMediaItem

/**
 * 把喜马拉雅的一个声音包装成项目内部的 [YosMediaItem]，交给 [MediaController.playNext] 播放。
 *
 * 要点：
 * - `uri` 直接放音频直链（MP3/AAC），ExoPlayer 可直接拉流，不经酷狗的 `kugou://` 占位符。
 * - `mediaId` 用 `xm-` 前缀而不是 `kugou-online-`：`KugouRepository.withPlayableUri()`
 *   只对 `kugou-online-` 前缀做占位符回填，用别的前缀能保证直链原样保留。
 * - 其余字段留空，不影响播放；`duration` 单位与项目一致（秒）。
 */
fun XmPlayInfo.toYosMediaItem(url: String): YosMediaItem = YosMediaItem(
    uri = Uri.parse(url),
    mediaId = "xm-$trackId",
    mimeType = guessMime(url),
    title = title,
    writer = null,
    compilation = null,
    composer = null,
    artists = nickname,
    album = albumTitle.ifEmpty { null },
    albumArtists = null,
    thumb = cover.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) },
    trackNumber = null,
    discNumber = null,
    genre = null,
    recordingDay = null,
    recordingMonth = null,
    recordingYear = null,
    releaseYear = null,
    artistId = null,
    albumId = null,
    genreId = null,
    author = nickname,
    addDate = null,
    duration = duration.toLong(),
    modifiedDate = null,
    cdTrackNumber = null
)

/** 按直链后缀猜 MIME；喜马拉雅常见 mp3 与 aac（.m4a / .aac）。 */
private fun guessMime(url: String): String {
    val path = url.substringBefore('?').lowercase()
    return when {
        path.endsWith(".m4a") -> "audio/mp4"
        path.endsWith(".aac") -> "audio/aac"
        path.endsWith(".flac") -> "audio/flac"
        else -> "audio/mpeg"
    }
}

/**
 * 播放一个喜马拉雅声音。返回给 UI 的提示文案。
 *
 * 放在这里而不是页面里，是为了让页面只依赖一个简单函数，
 * 也方便以后换成"加入队列"等别的语义。
 */
suspend fun playXimalaya(info: XmPlayInfo, url: String): String {
    val item = info.toYosMediaItem(url)
    return when (MediaController.playNext(item)) {
        MediaController.PlayNextOutcome.StartedPlayback -> "正在播放：${info.title}"
        MediaController.PlayNextOutcome.Added -> "已加入播放队列：${info.title}"
        MediaController.PlayNextOutcome.Cancelled -> "播放已取消"
        MediaController.PlayNextOutcome.Failed -> "播放失败，请稍后重试"
        else -> "已提交播放"
    }
}