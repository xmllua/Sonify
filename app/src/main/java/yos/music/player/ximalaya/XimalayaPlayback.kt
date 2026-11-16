package yos.music.player.ximalaya

import android.net.Uri
import yos.music.player.code.MediaController
import yos.music.player.data.libraries.YosMediaItem

/**
 * 喜马拉雅 → 项目播放器的桥。
 *
 * ## 三个曾经踩过的坑（都在真机上验证过）
 * 1. **uri 必须是可播的**。旧实现直接塞跳板 URL（http 明文），ExoPlayer 被系统
 *    拦掉 → 界面显示在播、一点声都没有。现在塞的是 [XmUris.placeholder]，
 *    真实 https 直链由数据源在 `open()` 时惰性解析（与酷狗的 `kugou://` 同一个机制）。
 * 2. **mediaId 不能用 `kugou-online-` 前缀**。项目里所有 `kugou-online-<hash>`
 *    都会被当成酷狗 hash 去查音源/歌词/上报，喜马拉雅的 trackId 走到那些路径
 *    
 *    上只会得到"查不到"。用 `xm-<trackId>` 前缀，所有酷狗专属分支自然跳过。
 * 3. **不能只调 playNext**。`playNext` 是「下一首播放」的语义：队列空时它确实起播，
 *    但它不进播放页/迷你条所依赖的队列快照，用户看到的是"点了但播放器没反应"。
 *    整张专辑作为队列交给 [MediaController.prepare] 才是对的：迷你条、播放页、
 *    上一首/下一首、通知栏全都自然接上。
 */
fun XmTrack.toYosMediaItem(album: XmAlbum? = null): YosMediaItem = YosMediaItem(
    // 占位符：真实直链在数据源 open() 时才解析，队列快照可以安全落盘
    uri = Uri.parse(XmUris.placeholder(trackId)),
    mediaId = "xm-$trackId",
    mimeType = null,
    title = title,
    writer = null,
    compilation = null,
    composer = null,
    artists = nickname.ifEmpty { album?.nickname ?: "" },
    album = albumTitle.ifEmpty { album?.title ?: "" },
    albumArtists = null,
    thumb = cover.ifEmpty { album?.cover.orEmpty() }
        .takeIf { it.isNotEmpty() }
        ?.let { Uri.parse(it) },
    trackNumber = null,
    discNumber = null,
    genre = null,
    recordingDay = null,
    recordingMonth = null,
    recordingYear = null,
    releaseYear = null,
    artistId = null,
    albumId = albumId,
    genreId = null,
    author = nickname.ifEmpty { album?.nickname },
    addDate = null,
    duration = duration.toLong(),
    modifiedDate = null,
    cdTrackNumber = null,
)

/**
 * 播放专辑里的某一集：整张专辑入队，从被点击处起播。
 *
 * 只把**可免费播放**的集放进队列：付费集（`isFree=false`）放进去只会在轮到时
 * 报一个 726 错误再被跳歌兜底跳过，既白跑一次网络又让"下一首"变成"下一首又跳了"。
 * 因此队列只含 `playable=true` 的集，索引按过滤后的列表算。
 */
suspend fun playXimalayaTrack(
    track: XmTrack,
    siblings: List<XmTrack>,
    album: XmAlbum? = null,
): String {
    if (!track.playable) {
        return "《${track.title}》是付费内容，需要购买才能收听"
    }
    val playable = siblings.filter { it.playable }
    // 兜底：调用方只给了一集（例如搜索结果直接点播）
    val list = (if (playable.isEmpty()) listOf(track) else playable)
        .map { it.toYosMediaItem(album) }
    val target = list.firstOrNull { it.mediaId == "xm-${track.trackId}" } ?: list.first()
    MediaController.prepare(target, list)
    return "正在播放：${track.title}"
}
