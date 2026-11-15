package yos.music.player.ximalaya

/**
 * 喜马拉雅公开接口的数据模型。
 *
 * 只覆盖匿名可访问的免费内容：搜索、专辑曲目、声音详情三块。接口来自移动端公开端点，
 * 不需要登录，也不需要 xm-sign 签名（签名只用于付费/会员内容的授权端点）。
 */
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
    val isPaid: Boolean,
    /** 创建时间，用于排序；接口返回毫秒 */
    val createdAt: Long = 0L,
)

/**
 * 一个声音的可播放信息。
 *
 * 音质优先级（高 → 低）：aacv224 > hq(64k 以上) > playUrl64 > playUrl32。
 * 免费内容通常四档都有；`hqNeedVip` 为真时高音质可能不可用，此时退回 mp3 档。
 */
data class XmPlayInfo(
    val trackId: Long,
    val title: String,
    val albumTitle: String,
    val nickname: String,
    val cover: String,
    val duration: Int,
    val isPaid: Boolean,
    val hqNeedVip: Boolean,
    val urls: List<XmAudioUrl>,
) {
    /** 最高可用音质。 */
    val best: XmAudioUrl? get() = urls.firstOrNull()
}

data class XmAudioUrl(
    val url: String,
    /** 展示名：AAC 224k / MP3 64k / MP3 32k … */
    val label: String,
    val isVipOnly: Boolean = false,
    val sizeBytes: Long = 0L,
)

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
