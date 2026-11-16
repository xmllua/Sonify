package yos.music.player.ximalaya

/**
 * 喜马拉雅公开接口的数据模型。
 *
 * 只覆盖**匿名可访问**的内容：搜索、专辑曲目、声音详情、分类精选。
 * 全部走移动端/网页公开端点，不需要登录，也不需要 `xm-sign`
 * （签名只用于付费/会员内容的授权端点，本模块不碰）。
 *
 * 实测结论（2026-11，逐条验证过，勿凭印象改）：
 *  - `revision/search` 的 docs 用 `id` / `cover_path` / `is_paid` / `tracks`，
 *    没有 `albumId` / `coverLarge`；
 *  - `mobile/v1/album/track` 的 list 用 `trackId` / `isPaid` / `isFree`，
 *    **付费专辑里的试听集是 isPaid=true 且 isFree=true**，能不能播只看 `isFree`；
 *  - `mobile/track/{id}` 的 `playUrl32/64` 不是音频，是
 *    `http://mobile.ximalaya.com/mobile/redirect/free/play/{id}/{q}` 的**明文 302 跳板**，
 *    必须跟随一次 Location 才拿到 CDN 直链，且跳板与 CDN 都是 http——
 *    本项目的 network_security_config 只放行 kugou 系域名，明文会被系统拦死
 *    （这正是"显示播放但没声音"的根因）。所以这里统一在解析阶段把 Location
 *    升级成 https 再交给播放器，见 [XimalayaApi.resolveDirectUrl]。
 */

/** 占位符 scheme：队列里先放它，由数据源在 open() 时惰性换成真实 https 直链。 */
object XmUris {
    const val SCHEME = "ximalaya"

    fun placeholder(trackId: Long): String = "$SCHEME://track/$trackId"
}

data class XmAlbum(
    val albumId: Long,
    val title: String,
    val cover: String,
    val playCount: Long = 0L,
    val trackCount: Int = 0,
    val isPaid: Boolean = false,
    val nickname: String = "",
    val intro: String = "",
)

data class XmTrack(
    val trackId: Long,
    val title: String,
    val albumId: Long,
    /** 秒 */
    val duration: Int,
    /** 服务端 `isPaid`：这一集属于付费内容（专辑整体付费时为 true）。 */
    val isPaid: Boolean,
    /**
     * 能否**匿名**播放（列表级预判）。
     *
     * 曲目列表端点不返回 URL，所以这里只能按 isPaid/isFree 粗判；
     * **最终判定在 [XimalayaApi.playInfo]——以「服务端给没给 URL」为准**。
     * 实测证明 `isFree` 与可播无关：免费集也可能是 `isFree=false`（如 899428355），
     * 而付费专辑的试听集反而是 `isFree=true`（如 323320228）。
     */
    val playable: Boolean,
    val albumTitle: String = "",
    val cover: String = "",
    val nickname: String = "",
    /** 创建时间，用于排序；接口返回毫秒 */
    val createdAt: Long = 0L,
)

/** 一个声音的候选直链（跳板 URL，需再解析一次）。 */
data class XmAudioUrl(
    val url: String,
    /** 展示名：AAC 48K / MP3 64k / MP3 32k … */
    val label: String,
)

data class XmPlayInfo(
    val trackId: Long,
    val title: String,
    val albumTitle: String,
    val nickname: String,
    val cover: String,
    val duration: Int,
    val playable: Boolean,
    val urls: List<XmAudioUrl>,
) {
    /** 首选档位（解析按 urls 顺序逐个试）。 */
    val best: XmAudioUrl? get() = urls.firstOrNull()
}

/** 搜索结果的一页。 */
data class XmAlbumPage(
    val albums: List<XmAlbum>,
    val page: Int,
    val hasMore: Boolean,
)

/** 专辑曲目的一页。 */
data class XmTrackPage(
    val tracks: List<XmTrack>,
    val album: XmAlbum?,
    val page: Int,
    val totalCount: Int,
    val hasMore: Boolean,
)

class XmException(
    message: String,
    /** 真：重试也没用（内容已下架、需要付费、接口结构变了）。 */
    val definitive: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)
