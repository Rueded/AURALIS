package com.auralis.app.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.Dp

/**
 * 统一的 spring 动画预设。
 *
 * 之前项目里 tween 用了 56 次、spring 只有 3 次，大部分交互动画是固定时长的线性/缓动
 * 曲线，机械感偏重。spring 是物理弹簧模拟，减速更自然，是"跟手感"强的应用普遍偏爱的
 * 方式。这里定义几档语义化的弹簧参数，跟手势/交互相关的动画统一从这里取，不再各自
 * 散落调 tween 数值。
 *
 * 注意：不是所有 tween 都该换成 spring。像 crossfade 的音量渐变要跟着音频的真实
 * 秒数走，用的是线性时间轴，这类跟"真实时长"绑定的动画应该继续用 tween；
 * spring 只适合用在跟手势/交互直接相关、没有"必须精确对应多少秒"这种约束的动画上。
 */
object AuralisMotion {
    /** 按钮按下缩放这类快速、轻巧的反馈，几乎不过冲 */
    val snappy: SpringSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)

    /** 卡片/弹窗弹出，带一点点回弹但不夸张 */
    val gentle: SpringSpec<Float> = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)

    /** 拖拽释放后的回位——比如队列拖拽松手归位、下拉刷新回弹 */
    val bouncy: SpringSpec<Float> = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)

    /** 尺寸（Dp）版本，用于 animateDpAsState，参数跟 gentle 对齐 */
    val gentleDp: SpringSpec<Dp> = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)

    /** 尺寸（Dp）版本，用于 animateDpAsState，参数跟 snappy 对齐 */
    val snappyDp: SpringSpec<Dp> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)
}
