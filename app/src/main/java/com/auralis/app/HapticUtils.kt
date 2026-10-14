package com.auralis.app

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * 统一的触感反馈入口。
 *
 * 原生 Compose 的 LocalHapticFeedback 只暴露 LongPress/TextHandleMove 两种类型，
 * 太粗糙——这里直接用 View.performHapticFeedback 拿到安卓原生更丰富的反馈类型
 * （CONFIRM/REJECT/CLOCK_TICK 等），跟 Spotify/Apple Music 那种"点哪里都有细微回应"
 * 的质感对齐。每个方法名是语义化的场景，不是原始常量名，调用的地方不用关心
 * 具体用了哪个安卓常量。
 *
 * CONFIRM/REJECT 这两个常量是 API 27（O_MR1）才有的，低版本设备上退化成效果相近的
 * 替代常量，不会崩，只是没那么精确对应"确认/否定"这个语义。
 */
class AuralisHaptics(private val view: View) {
    /** 常规点击——播放/暂停、上一首/下一首、普通按钮、tab 切换 */
    fun tap() {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /** 切换类操作——收藏、单曲循环、随机播放这类"开/关"状态翻转 */
    fun toggle() {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** 正向确认反馈——同步完成、添加到歌单成功、备份成功 */
    fun confirm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    /** 否定/删除/失败类反馈——移除歌曲、同步失败、取消操作 */
    fun reject() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /** 拖拽/滑动经过一个"档位"时的细微反馈——队列拖拽换位、seekbar 拖到整数刻度 */
    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** 长按触发进入拖拽/多选/上下文菜单模式，语义跟安卓系统长按一致 */
    fun longPress() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** 拖拽手势开始时的起始反馈——比如开始拖动播放队列里的某一项 */
    fun gestureStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /** 拖拽手势结束/松手时的收尾反馈 */
    fun gestureEnd() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }
}

@Composable
fun rememberAuralisHaptics(): AuralisHaptics {
    val view = LocalView.current
    return remember(view) { AuralisHaptics(view) }
}
