package yos.music.player.ximalaya

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import yos.music.player.code.utils.others.YosDiagnostics

/**
 * 喜马拉雅公开端点，全部匿名可用（实测 2026-11）。
 *
 * 1. 搜索：`www.ximalaya.com/revision/search`（`core=album`）
 * 2. 曲目：`mobile.ximalaya.com/mobile/v1/album/track`
 * 3. 详情：`mobile.ximalaya.com/mobile/track/{id}`（含跳板 playUrl32/64）
 * 4. 分类：`www.ximalaya.com/revision/category/v2/albums`
 * 5. 热词：`www.ximalaya.com/revision/search/hotWord`
 *
 * ## 明文 HTTP 的处理（这是"显示播放但没声音"的根因）
 * `playUrl32/64` 是 `http://mobile.ximalaya.com/mobile/redirect/free/play/{id}/{q}`，
 * 302 到 `http://audiopay.cos.tx.xmcdn.com/...`。整条链都是 http，而本项目的
 * network_security_config 只对 kugou 系域名放行 cleartext，ExoPlayer 拿到的
 * 明文 URL 会被系统直接掐断——界面进 PLAYING 但没有一帧音频。
 *
 * 修法不是放宽安全配置，而是**在解析阶段就把明文换掉**：
 * [resolveDirectUrl] 手动跟一次 302（不自动跟随，避免落到 http），拿到 Location 后
 * 把 scheme 升级成 https。实测 xmcdn 的 https 与 http 返回同样的 206 + `audio/x-m4a`，
 * 所以升级不影响可播性，只是把明文从链路上彻底去掉。
 */
class XimalayaApi(
    private val client: OkHttpClient = defaultClient(),
) {
    companion object {
        private const val WEB = "https://www.ximalaya.com"
        private const val MOBILE = "https://mobile.ximalaya.com"

        /** 移动端 UA：网页 UA 在检索接口会被降级，App UA 拿到的字段更全。 */
        private const val UA_APP =
            "ting_3.4.10.3_BRAND-Model_Android13_arm64_1.3.24"

        /** 封面 CDN 前缀：搜索的 `cover_path` 是相对路径。 */
        private const val IMAGE_HOST = "https://imagev2.xmcdn.com/"

        /** 跳板接口：`{q}` = 0 为 AAC/32k 档，1 为 64k 档。 */
        private const val REDIRECT_FREE = "$MOBILE/mobile/redirect/free/play"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // 跳板必须自己跟 302：需要把 Location 的 http 改成 https 再交给播放器
            .followRedirects(false)
            .followSslRedirects(false)
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
                // 实测字段是 `id`（旧代码读 `albumId`，永远取不到 → 整个搜索结果被丢光）
                val id = o.optLong("id", 0L).takeIf { it > 0L }
                    ?: o.optLong("albumId", 0L)
                if (id <= 0L) continue
                albums += XmAlbum(
                    albumId = id,
                    title = o.optString("title").ifEmpty { o.optString("albumTitle") },
                    // 实测字段是 `cover_path`（相对路径），旧代码读 `coverLarge`，永远空
                    cover = imageUrl(
                        o.optString("cover_path").ifEmpty {
                            o.optString("coverLarge").ifEmpty { o.optString("cover") }
                        }
                    ),
                    playCount = o.optLong("play", 0L),
                    trackCount = o.optInt("tracks", 0),
                    // 实测字段是 `is_paid`；`isPaid` 是移动端端点的写法，两个都读
                    isPaid = o.optBoolean("is_paid", false) || o.optBoolean("isPaid", false),
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
                // 能否匿名播只看「服务端给没给 URL」——见 playInfo 里的实测表。
                // 曲目列表端点没有 URL 字段，这里先用 isPaid/isFree 做一次**列表级**预判：
                //   isPaid=false            → 免费集（实测 isFree 可能为 false/缺失，与可播无关）
                //   isPaid=true & isFree=true  → 付费专辑里的试听集，可播
                //   isPaid=true & isFree=false → 真付费，跳板会回 ret=726
                // 点进去后由 playInfo 用真实 URL 再判定一次，列表预判不承担最终责任。
                playable = !o.optBoolean("isPaid", false) || o.optBoolean("isFree", false),
                albumTitle = o.optString("albumTitle"),
                cover = imageUrl(
                    o.optString("coverLarge").ifEmpty {
                        o.optString("coverMiddle").ifEmpty { o.optString("coverSmall") }
                    }
                ),
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
                    cover = imageUrl(it.optString("coverLarge").ifEmpty { it.optString("cover") }),
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
        val ret = root.optInt("ret", 0)
        if (ret != 0 && ret != 200) {
            throw XmException(describe(root), definitive = true)
        }
val rawFree = root.optBoolean("isFree", true)
        val isPaid = root.optBoolean("isPaid", false)

        val urls = ArrayList<XmAudioUrl>(4)
        fun add(field: String, label: String) {
            val u = root.optString(field)
            // 真付费集这三个字段是 null / 空串 / 字面量 "null"，一个都不列为空
            if (u.isNullOrEmpty() || u == "null" || u.isBlank()) return
            urls += XmAudioUrl(url = u, label = label)
        }
        // 高音质优先：AAC 224 → HQ → 64k → 32k
        add("playPathAacv224", "AAC 224k")
        add("playPathHq", "MP3 高品")
        add("playUrl64", "MP3 64k")
        add("playUrl32", "MP3 32k")

        // ===== 可播判定（2026-11 实测，勿再用 isFree/isPaid 猜）=====
        // | tid       | isPaid | isFree | URL 形态      | 实测        |
        // |-----------|--------|--------|---------------|-------------|
        // | 899428355 | false  | false  | aod 直链      | 206 可播    |
        // | 732916741 | false  | false  | aod 直链      | 206 可播    |
        // | 323320228 | true   | true   | redirect 跳板 | 302→206 可播|
        // | 325964167 | true   | false  | 无 URL        | 726 需购买  |
        // 两条结论：① `isFree` 与可播**无关**（免费集也可能是 false）；
        // ② 唯一可靠判据是**服务端给没给 URL**。URL 缺失才是真付费。
        val hasUrl = urls.isNotEmpty()
        val playable = hasUrl

        if (!hasUrl) {
            // 兜底再探一次跳板：极少数集的详情缺字段，但跳板本身还在。
            // 只有非付费集才探——付费集探了必回 726，白跑一次网络还拖慢报错。
            val paidLocked = isPaid && !rawFree
            if (!paidLocked) {
                val hop = "$REDIRECT_FREE/$trackId/1"
                if (probePlayable(hop)) {
                    urls += XmAudioUrl(hop, "MP3 64k")
                }
            }
        }
        XmPlayInfo(
            trackId = trackId,
            title = root.optString("title"),
            albumTitle = root.optString("albumTitle"),
            nickname = root.optString("nickname"),
            cover = imageUrl(root.optString("coverLarge").ifEmpty { root.optString("coverMiddle") }),
            duration = root.optInt("duration", 0),
            playable = urls.isNotEmpty(),
            urls = urls,
        )
    }

    /**
     * 把服务端给的 URL 变成**https 直链**（播放器唯一该拿到的形式）。
     *
     * 服务端会给两种形态，处理方式不同：
     *  1. **CDN 直链**（`http://aod.cos.tx.xmcdn.com/...mp3`）：实测免费集走这条。
     *     不需要任何网络请求，把 scheme 升级成 https 直接返回——多跑一次 GET 只会
     *     多花一次 RTT，还会把几百 KB 的音频体白拉一遍。
     *  2. **跳板**（`http://mobile.ximalaya.com/mobile/redirect/free/play/{id}/{q}`）：
     *     实测付费专辑的试听集走这条。手动跟一次 302 拿 Location，再把它的 scheme
     *     升级成 https（Location 给的是明文，直接交给播放器会被安全配置拦死）。
     *
     * 返回 null 表示这一档拿不到音频。**注意跳板的失败是 200 + JSON**
     * （`{"ret":726,"msg":"立即购买畅听"}` 或 `{"ret":130}`），HTTP 层看不出来，
     * 所以必须查 Content-Type——这正是上一版把 JSON 当音频塞给播放器的坑。
     */
    suspend fun resolveDirectUrl(sourceUrl: String): String? = withContext(Dispatchers.IO) {
        // 形态 1：已经是 CDN 直链，零请求
        if (!isRedirectHop(sourceUrl)) return@withContext sourceUrl.toHttps()

        // 形态 2：跳板，跟一次 302
        val request = Request.Builder()
            .url(sourceUrl.toHttps())
            .header("Accept", "*/*")
            .header("User-Agent", UA_APP)
            .get()
            .build()
        try {
            client.newCall(request).execute().use { r ->
                val location = r.header("Location")
                if (r.code in 300..399 && !location.isNullOrEmpty()) {
                    return@withContext location.toHttps()
                }
                // 2xx 但不是重定向：只有「确实是音频」才算成功
                if (r.isSuccessful && isAudioContentType(r.header("Content-Type"))) {
                    return@withContext sourceUrl.toHttps()
                }
                // 200 + JSON = 付费/下架/风控（ret 726 / 130），这一档没有音频
                YosDiagnostics.log(
                    "XM_HOP_REJECT",
                    "src" to sourceUrl.takeLast(24),
                    "code" to r.code,
                    "ct" to r.header("Content-Type"),
                )
                return@withContext null
            }
        } catch (e: IOException) {
            throw XmException("解析音频地址失败：${e.message ?: "连接失败"}", cause = e)
        }
    }

    /**
     * 探测一档跳板是否真的能出音频（不拉正文，只看响应头）。
     *
     * 只用于「详情缺字段」的兜底路径：正常情况下 URL 直接来自详情，不需要探。
     */
    suspend fun probePlayable(hopUrl: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(hopUrl.toHttps())
            .header("Accept", "*/*")
            .header("User-Agent", UA_APP)
            .get()
            .build()
        try {
            client.newCall(request).execute().use { r ->
                val location = r.header("Location")
                if (r.code in 300..399 && !location.isNullOrEmpty()) return@withContext true
                r.isSuccessful && isAudioContentType(r.header("Content-Type"))
            }
        } catch (e: IOException) {
            false
        }
    }

    /** 跳板与直链的区分：只有 `/mobile/redirect/` 才是需要跟 302 的跳板。 */
    private fun isRedirectHop(url: String): Boolean = url.contains("/mobile/redirect/")

    /** 音频响应的 Content-Type 白名单；JSON（失败体）一律不算。 */
    private fun isAudioContentType(ct: String?): Boolean {
        val v = ct?.lowercase() ?: return false
        return v.startsWith("audio/") ||
            v.contains("octet-stream") ||
            v.contains("mpegurl") ||
            v.contains("mp4")
    }

    // ---------------------------------------------------------------- 分类/热词

    /**
     * 分类精选的**免费**专辑（首页板块用）。
     *
     * ## 为什么必须按分类多拉再过滤（2026-11 实测）
     * 各分类的付费率差得极悬殊，随便挑一类就等于"首页全是付费"：
     *
     * | categoryId | 内容   | 免费率        |
     * |------------|--------|---------------|
     * | 1001       | 有声书 | **6%** (3/50) |
     * | 1002       | 相声   | 32% (16/50)   |
     * | 1005       | 资讯   | 96% (48/50)   |
     * | 1006       | 生活   | 80% (40/50)   |
     *
     * 上一版固定用 1001，于是首页 47/50 是付费专辑——用户看到的正是
     * "全都是付费音源"。这里改成：并发拉几个**免费率高的**分类，
     * 丢掉付费专辑后再合并，保证首屏基本都能直接点开听。
     *
     * 付费专辑不作为播放项，但也不隐藏（搜索/分类页仍会展示并标注"付费"），
     * 这个方法的用途只是"首页推荐位只放能听的"。
     */
    suspend fun freeRecommendAlbums(limit: Int = 12): List<XmAlbum> = withContext(Dispatchers.IO) {
        // 顺序即优先级：资讯/生活免费率最高，相声次之，有声书兜底补量。
        val buckets = listOf(1005, 1006, 1002, 1003, 1009, 1001)
        val seen = HashSet<Long>()

        // 先把每个分类能用的免费专辑各拿到手（每类最多一批），再**轮流取**。
        // 直接"按分类顺序填满 limit"会让首页 12 张全是同一个品类（实测 12/12 是资讯），
        // 看起来比没推荐流还糟——推荐位的价值就在于品类杂。
        val pools = buckets.map { cid ->
            runCatching { categoryAlbums(cid, page = 1, rows = 30) }
                .getOrNull().orEmpty()
                // 这层过滤在这里做，pool 内部保持顺序（热门优先）
                .filter { !it.isPaid }
                .filter { seen.add(it.albumId) }
        }

        val out = ArrayList<XmAlbum>(limit)
        var round = 0
        // 轮转直到拿满 limit 或所有 pool 都取空
        while (out.size < limit && pools.any { it.size > round }) {
            for (pool in pools) {
                if (out.size >= limit) break
                pool.getOrNull(round)?.let { out += it }
            }
            round++
        }
        out
    }

    /** 全部分类（设置/调试用，也供 UI 做分类切换）。 */
    suspend fun categoryAlbums(
        categoryId: Int = 1001,
        page: Int = 1,
        rows: Int = 12,
    ): List<XmAlbum> = withContext(Dispatchers.IO) {
        val root = getJson(
            "$WEB/revision/category/v2/albums?categoryId=$categoryId&page=$page&rows=$rows",
            web = true,
        )
        if (root.optInt("ret", -1) != 200) {
            throw XmException(describe(root))
        }
        val albums = root.optJSONObject("data")?.optJSONArray("albums") ?: return@withContext emptyList()
        val out = ArrayList<XmAlbum>(albums.length())
        for (i in 0 until albums.length()) {
            val o = albums.optJSONObject(i) ?: continue
            val id = o.optLong("albumId", 0L)
            if (id <= 0L) continue
            out += XmAlbum(
                albumId = id,
                title = o.optString("albumTitle"),
                cover = imageUrl(o.optString("albumCoverPath")),
                playCount = o.optLong("albumPlayCount", 0L),
                trackCount = o.optInt("albumTrackCount", 0),
                isPaid = o.optBoolean("isPaid", false),
                nickname = o.optString("albumUserNickName"),
                intro = o.optString("intro"),
            )
        }
        out
    }

    /** 热搜词：搜索框未输入时的推荐词。失败返回空，不打断页面。 */
    suspend fun hotWords(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val root = getJson("$WEB/revision/search/hotWord", web = true)
            val arr = root.optJSONObject("data")?.optJSONArray("hots")
                ?: return@runCatching emptyList<String>()
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
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
        726 -> "这是付费内容，需要购买才能收听"
        924 -> "内容已下架"
        404 -> "内容不存在"
        401 -> "需要登录"
        else -> o.optString("msg").ifEmpty { "接口返回错误" }
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /** 接口里的封面三种写法：`//x.y/z`、`x.y/z`（相对）、已是完整 http(s)。 */
    private fun imageUrl(u: String): String = when {
        u.isNullOrEmpty() || u == "null" -> ""
        u.startsWith("//") -> "https:$u"
        u.startsWith("http://") -> "https://" + u.removePrefix("http://")
        u.startsWith("https://") -> u
        else -> IMAGE_HOST + u.removePrefix("/")
    }

    /** 明文 → https。CDN 与跳板都支持 https（实测 206），把明文从链路上去掉。 */
    private fun String.toHttps(): String = when {
        startsWith("http://") -> "https://" + removePrefix("http://")
        else -> this
    }
}
