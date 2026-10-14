package com.auralis.app

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.background
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.LocalSize
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 封面单独放在这个内存单例里，不走 Preferences——Preferences 只能存基础类型/字符串，
 * 存不了 Bitmap。这个决定是安全的：下面 WidgetContent 是被 currentState<Preferences>()
 * 这个「真正被 Compose 观察到的状态」触发重组的，重组这一刻顺带读一下这个内存变量，
 * 读到的必然是最新值——不需要它自己也是可观察状态，蹭一次别人触发的重组便车就够了。
 */
object AuralisWidgetState {
    var coverBitmap: Bitmap? = null
}

private val KEY_TITLE = stringPreferencesKey("title")
private val KEY_ARTIST = stringPreferencesKey("artist")
private val KEY_IS_PLAYING = booleanPreferencesKey("is_playing")
private val KEY_HAS_SESSION = booleanPreferencesKey("has_active_session")

/**
 * 小组件更新的触发入口。PlaybackService 里的 Player.Listener 每次状态变化时调这个。
 *
 * 关键点：真正让 Glance 重新渲染的，是下面 updateAppWidgetState() 这一步——把数据写进
 * Glance 官方的 Preferences 状态容器，而不是随便找个内存变量改一改就完事。
 * 之前的版本就是栽在这——数据改了、update() 也调了，但 Glance 内部用的是 Compose
 * 那套「只有被观察到的状态变化才重组」的机制，普通 var 的读写它根本感知不到，
 * 于是每次都被当成「没有变化」直接跳过重绘，界面永远停在第一次渲染的样子。
 */
object AuralisWidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun requestUpdate(context: Context, title: String, artist: String, isPlaying: Boolean, hasActiveSession: Boolean) {
        scope.launch {
            try {
                val manager = GlanceAppWidgetManager(context)
                val ids = manager.getGlanceIds(AuralisWidget::class.java)
                android.util.Log.d("AuralisWidget", "requestUpdate: 找到 ${ids.size} 个小组件实例，写入 isPlaying=$isPlaying title=$title")
                ids.forEach { id ->
                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
                        prefs.toMutablePreferences().apply {
                            this[KEY_TITLE] = title
                            this[KEY_ARTIST] = artist
                            this[KEY_IS_PLAYING] = isPlaying
                            this[KEY_HAS_SESSION] = hasActiveSession
                        }
                    }
                    AuralisWidget().update(context, id)
                }
            } catch (e: Exception) {
                android.util.Log.w("AuralisWidget", "小组件刷新失败: ${e.message}")
            }
        }
    }
}

class AuralisWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AuralisWidget()
}

// 响应式尺寸分档——重新改回离散分档，放弃了 SizeMode.Exact 那种连续实时测量的方式。
// Exact 模式需要 launcher 持续精确地上报当前真实尺寸，MIUI 这类深度定制的 launcher
// 对这类新版 widget host 特性支持一直不算完善，报回来的尺寸值出现异常（比如 NaN）
// 很可能就是"标题/艺术家文字整体消失"的根源——文字换算用到的字号一旦是非法值，
// Text 直接不渲染，但封面/按钮这类 Box 尺寸对异常值容错更好，还能显示，正好对得上
// 观察到的现象（封面和按钮之间空了一大截、文字整个不见）。
// 改成 Responsive 之后，launcher 只需要从这几个写死的尺寸里挑一个最贴近的，不需要
// 自己实时精确测量上报，兼容性好得多；每一档的封面/按钮/字号也都是写死的具体数值，
// 不再做连续数学运算，从根上排除算出 NaN 或者负数这类非法值的可能。
// 这次把范围拉得比第一次宽很多，一路覆盖到平板上那种大尺寸。
private val WIDGET_SIZE_TINY = androidx.compose.ui.unit.DpSize(180.dp, 40.dp)
private val WIDGET_SIZE_SMALL = androidx.compose.ui.unit.DpSize(250.dp, 60.dp)
private val WIDGET_SIZE_MEDIUM = androidx.compose.ui.unit.DpSize(280.dp, 90.dp)
private val WIDGET_SIZE_LARGE = androidx.compose.ui.unit.DpSize(350.dp, 130.dp)
private val WIDGET_SIZE_XL = androidx.compose.ui.unit.DpSize(500.dp, 180.dp)
private val WIDGET_SIZE_XXL = androidx.compose.ui.unit.DpSize(700.dp, 260.dp)
private val WIDGET_SIZE_HUGE = androidx.compose.ui.unit.DpSize(950.dp, 340.dp)

private data class WidgetDimens(
    val coverSize: androidx.compose.ui.unit.Dp,
    val buttonSize: androidx.compose.ui.unit.Dp,
    val iconSize: androidx.compose.ui.unit.Dp,
    val titleFontSize: androidx.compose.ui.unit.TextUnit,
    val artistFontSize: androidx.compose.ui.unit.TextUnit,
    val outerPadding: androidx.compose.ui.unit.Dp,
    val contentSpacing: androidx.compose.ui.unit.Dp,
    val buttonSpacing: androidx.compose.ui.unit.Dp
)

// 每一档对应一组写死的具体数值，不搞连续插值——保证任何情况下都是合法、经过肉眼确认过的数字
private fun dimensFor(size: androidx.compose.ui.unit.DpSize): WidgetDimens = when {
    size.height >= WIDGET_SIZE_HUGE.height - 20.dp -> WidgetDimens(160.dp, 100.dp, 56.dp, 32.sp, 24.sp, 24.dp, 28.dp, 14.dp)
    size.height >= WIDGET_SIZE_XXL.height - 20.dp -> WidgetDimens(130.dp, 80.dp, 44.dp, 26.sp, 19.sp, 20.dp, 24.dp, 12.dp)
    size.height >= WIDGET_SIZE_XL.height - 20.dp -> WidgetDimens(96.dp, 60.dp, 34.dp, 21.sp, 16.sp, 16.dp, 18.dp, 10.dp)
    size.height >= WIDGET_SIZE_LARGE.height - 20.dp -> WidgetDimens(72.dp, 46.dp, 26.dp, 18.sp, 14.sp, 12.dp, 14.dp, 8.dp)
    size.height >= WIDGET_SIZE_MEDIUM.height - 20.dp -> WidgetDimens(56.dp, 38.dp, 21.dp, 16.sp, 13.sp, 10.dp, 12.dp, 6.dp)
    size.height >= WIDGET_SIZE_SMALL.height - 10.dp -> WidgetDimens(48.dp, 36.dp, 20.dp, 14.sp, 12.sp, 8.dp, 10.dp, 4.dp)
    else -> WidgetDimens(32.dp, 28.dp, 16.dp, 13.sp, 11.sp, 6.dp, 8.dp, 4.dp)
}

class AuralisWidget : GlanceAppWidget() {
    // 声明这个小组件用 Preferences 作为状态容器——这是 Glance 唯一保证「数据变了、界面就一定跟着变」的机制
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    // 之前用 SizeMode.Exact 想做连续缩放，但 MIUI 这类 launcher 对这个模式的实时尺寸
    // 上报支持不稳定，大概率是标题文字整体消失的根源（详见上面 WIDGET_SIZE_* 那段注释）。
    // 换回 Responsive：只需要 launcher 从这七档写死的尺寸里选一个最贴近的，不需要
    // 自己实时精确测量，兼容性更好，每一档的具体数值也都是写死、经过检查的合法值。
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            WIDGET_SIZE_TINY, WIDGET_SIZE_SMALL, WIDGET_SIZE_MEDIUM, WIDGET_SIZE_LARGE,
            WIDGET_SIZE_XL, WIDGET_SIZE_XXL, WIDGET_SIZE_HUGE
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            WidgetContent(context, prefs)
        }
    }

    @Composable
    private fun WidgetContent(context: Context, prefs: Preferences) {
        val title = prefs[KEY_TITLE] ?: ""
        val artist = prefs[KEY_ARTIST] ?: ""
        val isPlaying = prefs[KEY_IS_PLAYING] ?: false
        val hasActiveSession = prefs[KEY_HAS_SESSION] ?: false
        val cover = AuralisWidgetState.coverBitmap

        // 查表拿这一档对应的写死数值，不再做连续数学运算——避免了 LocalSize 异常值
        // 传导到字号计算上导致文字整体不渲染的问题
        val dimens = dimensFor(LocalSize.current)
        val coverSize = dimens.coverSize
        val buttonSize = dimens.buttonSize
        val iconSize = dimens.iconSize
        val titleFontSize = dimens.titleFontSize
        val artistFontSize = dimens.artistFontSize
        val outerPadding = dimens.outerPadding
        val contentSpacing = dimens.contentSpacing
        val buttonSpacing = dimens.buttonSpacing

        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(day = androidx.compose.ui.graphics.Color(0xFF1A1A1A), night = androidx.compose.ui.graphics.Color(0xFF1A1A1A)))
                .cornerRadius(20.dp)
                .padding(outerPadding),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            // 「打开 App」的可点击区域只包住封面+文字这一段，不能跟后面播放控制按钮的
            // clickable 互相嵌套——RemoteViews 对嵌套可点击区域支持不可靠，容易把子按钮的
            // 点击事件吃掉。拆成互不嵌套的兄弟节点最稳妥。
            Row(
                modifier = GlanceModifier
                    .defaultWeight()
                    .clickable(actionStartActivity(ComponentName(context.packageName, "com.auralis.app.MainActivity"))),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Box(
                    modifier = GlanceModifier
                        .size(coverSize)
                        .cornerRadius(12.dp)
                        .background(androidx.compose.ui.graphics.Color(0xFF333333))
                ) {
                    if (cover != null) {
                        Image(
                            provider = ImageProvider(cover),
                            contentDescription = null,
                            modifier = GlanceModifier.fillMaxSize().cornerRadius(12.dp)
                        )
                    }
                }

                Spacer(modifier = GlanceModifier.width(contentSpacing))

                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = title.ifBlank { context.getString(R.string.widget_no_song) },
                        maxLines = 1,
                        style = TextStyle(
                            fontSize = titleFontSize,
                            fontWeight = FontWeight.Medium,
                            color = ColorProvider(day = androidx.compose.ui.graphics.Color.White, night = androidx.compose.ui.graphics.Color.White)
                        )
                    )
                    if (hasActiveSession && artist.isNotBlank()) {
                        Spacer(modifier = GlanceModifier.height(2.dp))
                        Text(
                            text = artist,
                            maxLines = 1,
                            style = TextStyle(
                                fontSize = artistFontSize,
                                color = ColorProvider(day = androidx.compose.ui.graphics.Color(0xFFAAAAAA), night = androidx.compose.ui.graphics.Color(0xFFAAAAAA))
                            )
                        )
                    }
                }
            }

            Spacer(modifier = GlanceModifier.width(contentSpacing * 0.6f))

            WidgetIconButton(
                icon = "prev",
                contentDescription = context.getString(R.string.widget_action_previous),
                onClick = actionRunCallback<SkipPreviousAction>(),
                buttonSize = buttonSize,
                iconSize = iconSize
            )
            Spacer(modifier = GlanceModifier.width(buttonSpacing))
            WidgetIconButton(
                icon = if (isPlaying) "pause" else "play",
                contentDescription = context.getString(if (isPlaying) R.string.widget_action_pause else R.string.widget_action_play),
                onClick = actionRunCallback<PlayPauseAction>(),
                buttonSize = buttonSize,
                iconSize = iconSize
            )
            Spacer(modifier = GlanceModifier.width(buttonSpacing))
            WidgetIconButton(
                icon = "next",
                contentDescription = context.getString(R.string.widget_action_next),
                onClick = actionRunCallback<SkipNextAction>(),
                buttonSize = buttonSize,
                iconSize = iconSize
            )
        }
    }

    @Composable
    private fun WidgetIconButton(
        icon: String, contentDescription: String, onClick: androidx.glance.action.Action,
        buttonSize: androidx.compose.ui.unit.Dp, iconSize: androidx.compose.ui.unit.Dp
    ) {
        // 之前用 ◀◀/▶▶ 这两个字符文字符号——那其实是"快退/快进"的符号，跟"上一首/下一首"
        // 语义对不上，而且纯文字符号在不同厂商字体下渲染效果参差不齐。
        // 换成矢量图标，跟 app 其他地方用的 Material Icons 是同一套标准图形（play_arrow/
        // pause/skip_next/skip_previous），不再依赖系统字体怎么画这几个符号。
        val iconRes = when (icon) {
            "play" -> R.drawable.ic_widget_play
            "pause" -> R.drawable.ic_widget_pause
            "prev" -> R.drawable.ic_widget_skip_previous
            "next" -> R.drawable.ic_widget_skip_next
            else -> R.drawable.ic_widget_play
        }
        Box(
            modifier = GlanceModifier
                .size(buttonSize)
                .cornerRadius(buttonSize / 2)
                .background(androidx.compose.ui.graphics.Color(0xFF2E2E2E))
                .clickable(onClick),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(iconRes),
                contentDescription = contentDescription,
                modifier = GlanceModifier.size(iconSize)
            )
        }
    }
}

// ── 播放控制的三个 Action：在 app 自己的进程里跑，短命地连一次 MediaController 发命令 ──
@androidx.media3.common.util.UnstableApi
private suspend fun withMediaController(context: Context, block: (MediaController) -> Unit) {
    // context 是 BroadcastReceiver 专用的受限 Context，不能直接 bindService，必须用 applicationContext
    val appContext = context.applicationContext
    // MediaController 要求创建/使用/释放全程在同一线程（默认主线程），用 withContext(Main) 收拢
    withContext(Dispatchers.Main) {
        val sessionToken = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val controller = withTimeout(4000L) {
            MediaController.Builder(appContext, sessionToken).buildAsync().await()
        }
        try {
            block(controller)
        } finally {
            controller.release()
        }
    }
}

@androidx.media3.common.util.UnstableApi
class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        try {
            withMediaController(context) { controller ->
                if (controller.isPlaying) controller.pause() else controller.play()
            }
        } catch (e: Exception) {
            android.util.Log.e("AuralisWidget", "播放/暂停按钮执行失败", e)
        }
        // 不在这里立即刷新——命令是异步发出的，这一刻 PlaybackService 大概率还没真正切换状态。
        // 真正的刷新交给 PlaybackService.updateWidgetState()，只有状态真的变了才会触发。
    }
}

@androidx.media3.common.util.UnstableApi
class SkipNextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        try {
            withMediaController(context) { controller ->
                if (controller.hasNextMediaItem()) controller.seekToNext()
            }
        } catch (e: Exception) {
            android.util.Log.e("AuralisWidget", "下一首按钮执行失败", e)
        }
    }
}

@androidx.media3.common.util.UnstableApi
class SkipPreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        try {
            withMediaController(context) { controller ->
                if (controller.hasPreviousMediaItem()) controller.seekToPrevious()
            }
        } catch (e: Exception) {
            android.util.Log.e("AuralisWidget", "上一首按钮执行失败", e)
        }
    }
}