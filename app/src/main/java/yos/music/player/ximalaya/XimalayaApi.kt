package yos.music.player.ximalaya

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 喜马拉雅的三个公开端点。全部匿名可用，实测 2026-11。
 *
 * 1. 搜索：`www.ximalaya.com/revision/search`（`core=album` 搜专辑）
 * 2. 曲目：`mobile.ximalaya.com/mobile/v1/album/track`
 * 3. 详情：`mobile.ximalaya.com/mobile/track/{id}`（含 playUrl32/64/AAC224/downloadUrl）
 *
 * 付费内容（`isPaid=true`）不取直链，直接抛 [XmException] 让调用方提示，不绕过授权。
 */
class XimalayaApi(
    private val client: OkHttpClient = defaultClient(),
) {
    companion object {
        private const val WEB = "https://www.ximalaya.com"
        private const val MOBILE = "https://mobile.ximalaya.com"

        // 移动端 UA：网页 UA 在检索接口会被降级，用 App 的 UA 拿到的字段更全
        private const val UA_APP =
            "ting_3.4.10.3_BRAND-Model_Android13_arm64_1.3.24"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // ---------------------------------------------------------------- 搜索

    suspend fun searchAlbums(keyword: String, page: Int = 1, pageSize: Int = 20): XmAlbumPage =
        withContext(Dispatchers.IO) {
            val kw = keyword.trim()
            if (kw.isEmpty() || kw.length > 200) {
                throw XmException("请输入 1..200 字的关键词", definitive = true)
            }
            val url = buildString {
                append(WEB).append("/revision/search")
                append("?kw=").append(enc(kw))
                append("&core=album&page=").append(page)
                append("&rows=").append(pageSize)
                append("&device=iPhone&condition=relation&spellchecker=true")
            }
            val root = getJson(url, web = true)
            val docs = root.optJSONObject("data")
                ?.optJSONObject("result")
                ?.optJSONObject("response")
                ?.optJSONArray("docs")
                ?: throw XmException("搜索接口结构变化，无法解析", definitive = true)

            val albums = ArrayList<XmAlbum>(docs.length())
            for (i in 0 until docs.length()) {
                val o = docs.optJSONObject(i) ?: continue
                val id = o.optLong("albumId", 0L).takeIf { it > 0 }
                    ?: o.optLong("id", 0L)
                if (id <= 0L) continue
                albums += XmAlbum(
                    albumId = id,
                    title = o.optString("title").ifEmpty { o.optString("albumTitle") },
                    cover = fixUrl(o.optString("coverLarge").ifEmpty { o.optString("cover") }),
                    playCount = o.optLong("playCount", 0L),
                    trackCount = o.optInt("trackCount", 0),
                    isPaid = o.optBoolean("isPaid", false),
                    nickname = o.optString("nickname"),
                    intro = o.optString("intro"),
                )
            }
            XmAlbumPage(albums, page, albums.size >= pageSize)
        }

    // ---------------------------------------------------------------- 曲目

    suspend fun albumTracks(
        albumId: Long,
        page: Int = 1,
        pageSize: Int = 30,
    ): XmTrackPage = withContext(Dispatchers.IO) {
        val url = buildString {
            append(MOBILE).append("/mobile/v1/album/track")
            append("?albumId=").append(albumId)
            append("&pageId=").append(page)
            append("&pageSize=").append(pageSize)
            append("&device=android")
        }
        val root = getJson(url, web = false)
        // 该端点用 ret=0 表示成功（与其余接口的 ret=200 不同）
        val ret = root.optInt("ret", -1)
        if (ret != 0 && ret != 200) {
            throw XmException(describe(root), definitive = ret == 924 || ret == 404)
        }
        val data = root.optJSONObject("data")
            ?: throw XmException("曲目接口结构变化，无法解析", definitive = true)

        val list = data.optJSONArray("list") ?: JSONArray()
        val tracks = ArrayList<XmTrack>(list.length())
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val tid = o.optLong("trackId", 0L).takeIf { it > 0 } ?: o.optLong("id", 0L)
            if (tid <= 0L) continue
            tracks += XmTrack(
                trackId = tid,
                title = o.optString("title"),
                albumId = albumId,
                duration = o.optInt("duration", 0),
                isPaid = o.optBoolean("isPaid", false),
                createdAt = o.optLong("createdAt", 0L),
            )
        }
        val album = data.optJSONObject("album")
        val total = data.optInt("totalCount", tracks.size)
        XmTrackPage(
            tracks = tracks,
            album = album?.let {
                XmAlbum(
                    albumId = albumId,
                    title = it.optString("title"),
                    cover = fixUrl(it.optString("coverLarge").ifEmpty { it.optString("cover") }),
                    playCount = it.optLong("playCount", 0L),
                    trackCount = total,
                    isPaid = it.optBoolean("isPaid", false),
                    nickname = it.optString("nickname"),
                )
            },
            page = page,
            totalCount = total,
            hasMore = page * pageSize < total,
        )
    }

    // ---------------------------------------------------------------- 详情/直链

    suspend fun playInfo(trackId: Long): XmPlayInfo = withContext(Dispatchers.IO) {
        val root = getJson("$MOBILE/mobile/track/$trackId?device=android", web = false)
        val paid = root.optBoolean("isPaid", false)
        if (paid) {
            throw XmException("这是付费内容，未登录无法播放", definitive = true)
        }
        val hqNeedVip = root.optBoolean("hqNeedVip", false)

        val urls = ArrayList<XmAudioUrl>(4)
        fun add(field: String, label: String, sizeField: String, vipOnly: Boolean) {
            val u = root.optString(field)
            if (u.isNullOrEmpty()) return
            urls += XmAudioUrl(
                url = fixUrl(u),
                label = label,
                isVipOnly = vipOnly,
                sizeBytes = root.optLong(sizeField, 0L),
            )
        }
        // 高音质优先。hqNeedVip 时 AAC 224 可能取到但播放 403，仍列出来供用户自选
        add("playPathAacv224", "AAC 224k", "playPathAacv224Size", hqNeedVip)
        add("playPathHq", "MP3 高品", "playHqSize", hqNeedVip)
        add("playUrl64", "MP3 64k", "playUrl64Size", false)
        add("playUrl32", "MP3 32k", "playUrl32Size", false)

        if (urls.isEmpty()) {
            throw XmException("没有可用的音频地址（可能已下架或需登录）", definitive = true)
        }
        XmPlayInfo(
            trackId = trackId,
            title = root.optString("title"),
            albumTitle = root.optString("albumTitle"),
            nickname = root.optString("nickname"),
            cover = fixUrl(root.optString("coverLarge").ifEmpty { root.optString("coverMiddle") }),
            duration = root.optInt("duration", 0),
            isPaid = paid,
            hqNeedVip = hqNeedVip,
            urls = urls,
        )
    }

    // ---------------------------------------------------------------- 内部

    private fun getJson(url: String, web: Boolean): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Connection", "close")
            .header("User-Agent", UA_APP)
            .apply { if (web) header("Referer", "$WEB/") }
            .build()
        val body = try {
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw XmException("HTTP ${r.code}", definitive = r.code in 400..499)
                r.body?.string() ?: throw XmException("响应为空")
            }
        } catch (e: IOException) {
            throw XmException("网络错误：${e.message ?: "连接失败"}", cause = e)
        }
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            throw XmException("响应不是合法 JSON", definitive = true, cause = e)
        }
    }

    private fun describe(o: JSONObject): String = when (o.optInt("ret", -1)) {
        924 -> "内容已下架"
        404 -> "内容不存在"
        401 -> "需要登录"
        else -> o.optString("msg").ifEmpty { "接口返回错误" }
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /** 接口里封面常以 `//imagev2.xmcdn.com/...` 形式返回，补上 scheme。 */
    private fun fixUrl(u: String): String = when {
        u.isNullOrEmpty() -> ""
        u.startsWith("//") -> "https:$u"
        else -> u
    }
}
