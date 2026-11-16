package yos.music.player.ximalaya

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * 喜马拉雅二级页：套上统一的 Scaffold，内容交给 [XimalayaPage]。
 *
 * 单独包一层是为了让导航图只依赖这个稳定入口；页面内部怎么改都不影响路由。
 * 播放走 [playXimalaya]，直接进项目的 MediaController，不需要外部回调。
 */
@Composable
fun XimalayaRoute(modifier: Modifier = Modifier) {
    val api = remember { XimalayaApi() }
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        XimalayaPage(
            api = api,
            modifier = Modifier.padding(padding),
        )
    }
}