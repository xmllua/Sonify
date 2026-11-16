package yos.music.player.ximalaya

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import yos.music.player.code.utils.others.YosDiagnostics

/**
 * 喜马拉雅直链解析器——给 Media3 数据源用的**阻塞**入口。
 *
 * 为什么需要它：队列里存的是 [XmUris.SCHEME] 占位符，真实直链只能在 ExoPlayer
 * 真要读字节时才知道。数据源是同步的 `open()`，所以这里用 runBlocking 包一层，
 * 与酷狗的 `resolvePlayUrlBlocking` 完全同构。
 *
 * 缓存策略与酷狗不同，原因在签名：xmcdn 直链带 `buy_key` / `sign`，实测有效期以小时计
 * （酷狗的签名 URL 30 分钟轮换）。这里按 **20 分钟**保守缓存，且在 seek 重开时
 * 直接命中——否则每次拖进度条都要多跑两次网络往返（跳板 302 + 详情），
 * 表现为"拖动后卡一下才出声"。
 */
object XimalayaUrlResolver {

    /** 直链缓存有效期：保守取 20 分钟。 */
    private const val TTL_MS = 20 * 60 * 1000L

    private class Entry(val url: String, val at: Long)

    private val cache = ConcurrentHashMap<Long, Entry>()

    /** 同一集并发 open 只解析一次（crossfade 预取 + 主播放会同时要）。 */
    private val locks = ConcurrentHashMap<Long, Any>()

    private val api by lazy { XimalayaApi() }

    /**
     * 解析某一集的 https 直链。
     *
     * 失败一律抛 [IOException]：数据源契约里只有 IOException 会被 ExoPlayer 当成
     * 可重试/可跳过的加载错误，抛 Runtime 异常会把一次网络失败升级成崩溃。
     */
    fun resolveBlocking(trackId: Long): String {
        val now = System.currentTimeMillis()
        cache[trackId]?.let { if (now - it.at < TTL_MS) return it.url }

        val lock = locks.getOrPut(trackId) { Any() }
        synchronized(lock) {
            // 双重检查：排队期间别的线程可能已经解析好了
            val again = cache[trackId]
            if (again != null && System.currentTimeMillis() - again.at < TTL_MS) return again.url

            val startedAt = System.currentTimeMillis()
            try {
                val url = runBlocking { resolveOnce(trackId) }
                cache[trackId] = Entry(url, System.currentTimeMillis())
                YosDiagnostics.log(
                    "XM_RESOLVE", "id" to trackId, "ok" to true,
                    "cost" to (System.currentTimeMillis() - startedAt)
                )
                return url
            } catch (e: IOException) {
                YosDiagnostics.log("XM_RESOLVE", "id" to trackId, "ok" to false, "why" to e.toString())
                throw e
            } catch (e: Exception) {
                YosDiagnostics.log("XM_RESOLVE", "id" to trackId, "ok" to false, "why" to e.toString())
                throw IOException("喜马拉雅直链解析失败 id=$trackId: ${e.message}", e)
            } finally {
                locks.remove(trackId)
            }
        }
    }

    /** 按候选档位从高到低逐个试，第一个拿得到直链的就是可用直链。 */
    private suspend fun resolveOnce(trackId: Long): String {
        val info = api.playInfo(trackId)
        // playable 由「服务端给没给 URL」决定（见 XimalayaApi.playInfo 的实测表），
        // 不拿 isFree/isPaid 猜；这里再判一次是因为 info.urls 可能为空。
        if (!info.playable || info.urls.isEmpty()) {
            throw IOException("这一集需要购买才能收听")
        }
        var lastLabel: String? = null
        for (candidate in info.urls) {
            // 直链零请求；跳板跟一次 302。返回 null = 这一档没有音频，换下一档
            val direct = api.resolveDirectUrl(candidate.url)
            if (!direct.isNullOrEmpty()) return direct
            lastLabel = candidate.label
        }
        throw IOException("所有音质档位都拿不到音频（最后尝试：${lastLabel ?: "-"}）")
    }

    /** 清空缓存（设置页/调试用）。 */
    fun clear() = cache.clear()
}
