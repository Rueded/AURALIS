package com.auralis.app.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 统一的阴影/层级 token。
 *
 * 之前全项目只有 6 处用到 shadow/elevation，视觉上偏平——考虑到已经有 ReactiveBackground/
 * 专辑取色这套很讲究的动态背景系统，前景卡片几乎没有层级去衬托这套背景。
 * 这里定义一套统一的层级台阶，新增的卡片/弹窗/迷你播放器都从这里取值，而不是
 * 每个地方各自拍一个数字——保证整个 app 的"抬起感"是同一套语言，不是东一块西一块。
 */
object AuralisElevation {
    /** 贴着背景的元素——列表行默认态，几乎不抬起 */
    val flat: Dp = 0.dp

    /** 轻微抬起——歌曲行按下反馈、chip、tab 指示器 */
    val level1: Dp = 2.dp

    /** 标准卡片——迷你播放器、设置分组卡片 */
    val level2: Dp = 6.dp

    /** 明显浮起——底部弹出面板、下拉菜单 */
    val level3: Dp = 12.dp

    /** 最高层级——对话框、全屏播放器展开时的封面卡片 */
    val level4: Dp = 20.dp
}
