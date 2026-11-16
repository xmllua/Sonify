package yos.music.player.ximalaya

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavController
import yos.music.player.ui.navigation.popBackStackSafely

/**
 * 喜马拉雅页的路由入口。
 *
 * 只做两件事：建一个稳定的 [XimalayaApi] 实例、把返回接到导航栈。
 * 页面本体不碰 NavController，这样它可以被首页板块直接嵌入而不带导航副作用。
 */
@Composable
fun XimalayaRoute(navController: NavController) {
    val api = remember { XimalayaApi() }
    XimalayaPage(
        api = api,
        onBack = { navController.popBackStackSafely() },
    )
}
