package com.auralis.app

import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * 在自定义 Dialog() 的 content 里调用这个，给对话框窗口本身加上"背后模糊"的
 * 毛玻璃效果——用的是安卓 12（S，API 31）才有的窗口级模糊 API
 * （Window.attributes.blurBehindRadius），直接让系统合成器去模糊窗口后面的真实
 * 像素，效果和性能都比在 Compose 里自己截图再算模糊靠谱得多。
 *
 * 低于 API 31 的设备这个函数直接什么都不做——对话框本身该有的半透明背板不受影响，
 * 只是少了"背后模糊"这一层，不会报错也不会显示异常，优雅降级。
 *
 * 用法：
 * ```
 * Dialog(onDismissRequest = { ... }) {
 *     ApplyDialogBlur()
 *     // ...对话框的实际内容...
 * }
 * ```
 *
 * ⚠️ 这里用到的 DialogWindowProvider 是 Compose 内部实现细节，不是公开稳定 API，
 * 理论上有跟着 Compose 版本升级而失效的风险——如果哪天升级 Compose 之后这个模糊效果
 * 不生效了，大概率是这个内部类的访问方式变了，不是这块逻辑本身的问题，需要对照
 * 当时的 Compose 版本重新确认一下 DialogWindowProvider 还在不在这个包路径下。
 */
@Composable
fun ApplyDialogBlur(radius: Int = 50) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val view = LocalView.current
    DisposableEffect(view) {
        val dialogWindowProvider = view.parent as? DialogWindowProvider
        val window = dialogWindowProvider?.window
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = radius }
        }
        onDispose {}
    }
}
