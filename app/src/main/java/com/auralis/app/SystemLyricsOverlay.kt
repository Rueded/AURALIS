package com.auralis.app

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 悬浮歌词外观设置在 SharedPreferences 里的 key 和默认值，设置页和悬浮窗共用同一份，避免两边写岔。 */
object LyricsOverlayPrefs {
    const val FILE = "MusicSyncPrefs"
    const val KEY_TEXT_SIZE = "lyrics_overlay_text_size"       // Float，sp
    const val KEY_WIDTH_PCT = "lyrics_overlay_width_pct"       // Int，占屏幕宽度百分比
    const val KEY_BG_ALPHA = "lyrics_overlay_bg_alpha"         // Int，0~100，背景不透明度
    const val KEY_TEXT_COLOR = "lyrics_overlay_text_color"     // Int，ARGB；0 表示跟随专辑主色
    const val KEY_STROKE = "lyrics_overlay_stroke"             // Float，描边宽度 dp，0 表示无描边
    const val KEY_LOCKED = "lyrics_overlay_locked"             // Boolean，锁定后不可拖动/不响应点击；长按悬浮窗锁定或解锁

    const val DEF_TEXT_SIZE = 16f
    const val DEF_WIDTH_PCT = 90
    const val DEF_BG_ALPHA = 82
    const val DEF_TEXT_COLOR = Color.WHITE
    const val DEF_STROKE = 0f
    const val COLOR_FOLLOW_ALBUM = 0
}

/**
 * 带描边的 TextView。描边不是 setShadowLayer（那只是发光），而是先用描边样式画一遍、
 * 再用填充样式画一遍字，边缘才是真正清晰的一圈轮廓，在亮/暗背景上都读得清。
 */
class StrokeTextView(context: Context) : TextView(context) {
    var strokeWidthPx: Float = 0f
    var strokeColor: Int = Color.BLACK
    private var drawing = false

    override fun invalidate() {
        // onDraw 里为了换色会调用 setTextColor，它内部会 invalidate；
        // 不拦住的话就会变成每一帧都触发下一帧重绘的死循环
        if (!drawing) super.invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (strokeWidthPx <= 0f) {
            super.onDraw(canvas)
            return
        }
        drawing = true
        val fillColor = currentTextColor
        val p = paint
        p.style = Paint.Style.STROKE
        p.strokeWidth = strokeWidthPx
        p.strokeJoin = Paint.Join.ROUND
        setTextColor(strokeColor)
        super.onDraw(canvas)
        p.style = Paint.Style.FILL
        setTextColor(fillColor)
        super.onDraw(canvas)
        drawing = false
    }
}

/**
 * 系统级悬浮歌词：用 TYPE_APPLICATION_OVERLAY 窗口把当前歌词画在所有应用上面，
 * 切到别的应用也能看。
 *
 * 为什么放在 PlaybackService 里管，而不是放在界面（Compose）里：
 * 应用退到后台以后 Activity 会 stop，Compose 的重组/LaunchedEffect 不会再被驱动，
 * 靠界面去加载歌词、刷新当前行都不可靠。PlaybackService 是前台服务，一直活着，
 * 又直接拿得到播放器，所以悬浮窗的"显示/隐藏、加载歌词、跟着进度换行"全部在这里自己闭环。
 *
 * 显示条件（全部满足才显示）：
 *  1. 设置里"悬浮歌词"开关打开（PlayerStateHolder.lyricsOverlayEnabled）
 *  2. 已授予"显示在其他应用上层"权限（Settings.canDrawOverlays）
 *  3. 应用当前不在前台（在应用里面时用原来的应用内歌词条，不重复显示）
 *  4. 正在播放，且当前这首歌有歌词
 *
 * 外观（字号/宽度/背景透明度/字色/描边）和锁定都从 SharedPreferences 读，每个刷新周期
 * 对比一次，变了才应用，所以设置页里拖滑块，悬浮窗实时跟着变。
 *
 * 用纯 View 而不是 ComposeView：Service 里没有 Activity 的 LifecycleOwner /
 * SavedStateRegistryOwner，给 ComposeView 手动补这些又脆又容易崩，普通 View 最稳。
 */
class SystemLyricsOverlay(
    private val context: Context,
    private val playerProvider: () -> Player?
) {
    private val tag = "SystemLyricsOverlay"
    private val prefs = context.getSharedPreferences(LyricsOverlayPrefs.FILE, Context.MODE_PRIVATE)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loopJob: Job? = null

    private var rootView: FrameLayout? = null
    private var textBox: LinearLayout? = null
    private var currentLineView: StrokeTextView? = null
    private var nextLineView: StrokeTextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // 已经为哪首歌尝试过加载歌词了（成功与否都算），避免没有歌词的歌被反复请求
    private var attemptedPath = ""
    private var loadJob: Job? = null

    private var lastCurrentText = ""
    private var lastNextText = ""

    private data class Style(
        val textSizeSp: Float,
        val widthPct: Int,
        val bgAlpha: Int,
        val textColor: Int,
        val strokeDp: Float,
        val locked: Boolean
    )
    private var appliedStyle: Style? = null

    fun start() {
        if (loopJob != null) return
        loopJob = scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: Exception) {
                    Log.w(tag, "悬浮歌词刷新异常: ${e.message}")
                }
                delay(250L)
            }
        }
    }

    fun stop() {
        loopJob?.cancel(); loopJob = null
        loadJob?.cancel(); loadJob = null
        removeView()
        scope.cancel()
    }

    // ── 主循环 ────────────────────────────────────────────────────────────

    private fun tick() {
        val player = playerProvider()
        val path = player?.currentMediaItem?.mediaId.orEmpty()

        val enabled = PlayerStateHolder.lyricsOverlayEnabled.value
        val hasPermission = canDraw()
        val inForeground = AuralisApp.isInForeground.value

        // 开关关了/没权限/在应用里面/没有歌：直接收起（也不去加载歌词，省电省流量）
        if (!enabled || !hasPermission || inForeground || player == null || path.isEmpty()) {
            removeView()
            return
        }

        ensureLyricsLoaded(path)

        val loadedPath = PlayerStateHolder.lrcLoadedForPath.value
        val lines = if (loadedPath == path) PlayerStateHolder.lrcLines.value else emptyList()
        if (lines.isEmpty() || !player.isPlaying) {
            removeView()
            return
        }

        val pos = player.currentPosition
        val idx = lines.indexOfLast { it.timeMs <= pos }
        // 还没唱到第一句：显示 ♪，下一行预告第一句
        val cur = if (idx >= 0) lines[idx].text else "♪"
        val nextText = if (idx >= 0) {
            // 下一行：跳过和当前行同一时间戳的双语配对行
            val curTime = lines[idx].timeMs
            lines.drop(idx + 1).firstOrNull { it.timeMs > curTime }?.text.orEmpty()
        } else lines.first().text

        showOrUpdate(cur.ifBlank { "♪" }, nextText)
        applyStyleIfChanged()
    }

    /** 歌词不是靠界面加载的：这里独立加载（本地 .lrc → 在线），加载完写进全局 PlayerStateHolder。 */
    private fun ensureLyricsLoaded(path: String) {
        if (attemptedPath == path) return
        val loadedPath = PlayerStateHolder.lrcLoadedForPath.value
        // 界面那边已经给这首歌放了非空歌词，就不重复加载
        if (loadedPath == path && PlayerStateHolder.lrcLines.value.isNotEmpty()) {
            attemptedPath = path
            return
        }
        attemptedPath = path
        loadJob?.cancel()
        loadJob = scope.launch {
            delay(600L) // 快速连切歌时去抖
            val player = playerProvider()
            if (player?.currentMediaItem?.mediaId != path) return@launch
            val meta = player.currentMediaItem?.mediaMetadata
            var title = meta?.title?.toString().orEmpty()
            var artist = meta?.artist?.toString().orEmpty()
            val onlineEnabled = prefs.getBoolean("enable_online_lyrics", true)
            val lines = withContext(Dispatchers.IO) {
                try {
                    // 跟全屏播放器一样，标题/歌手以数据库为准，不信可能过期的元数据
                    AppDatabase.getDatabase(context).songDao().getSongByPath(path)?.let {
                        title = it.title; artist = it.artist
                    }
                } catch (_: Exception) {}
                if (onlineEnabled) {
                    OnlineLyricsRepository.getLyrics(path, title, artist, context).lines
                } else {
                    LrcParser.parse(path)
                }
            }
            // 加载期间如果已经切歌了，丢弃结果，防止旧歌词覆盖新歌
            if (playerProvider()?.currentMediaItem?.mediaId == path && lines.isNotEmpty()) {
                PlayerStateHolder.updateLyrics(path, lines)
            }
        }
    }

    // ── 窗口 ──────────────────────────────────────────────────────────────

    private fun canDraw(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    private fun showOrUpdate(current: String, next: String) {
        if (rootView == null) createView()
        if (current != lastCurrentText) { currentLineView?.text = current; lastCurrentText = current }
        if (next != lastNextText) {
            nextLineView?.text = next
            nextLineView?.visibility = if (next.isBlank()) View.GONE else View.VISIBLE
            lastNextText = next
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    private fun readStyle(): Style {
        val colorPref = prefs.getInt(LyricsOverlayPrefs.KEY_TEXT_COLOR, LyricsOverlayPrefs.DEF_TEXT_COLOR)
        val resolvedColor = if (colorPref == LyricsOverlayPrefs.COLOR_FOLLOW_ALBUM) {
            // 跟随专辑主色：没取到色（还没加载完）就先用白色
            PlayerStateHolder.dominantColor.value
                ?.ensureContrastOn(androidx.compose.ui.graphics.Color(0xFF1A1A1A), 7f)?.toArgb() ?: Color.WHITE
        } else colorPref
        return Style(
            textSizeSp = prefs.getFloat(LyricsOverlayPrefs.KEY_TEXT_SIZE, LyricsOverlayPrefs.DEF_TEXT_SIZE),
            widthPct = prefs.getInt(LyricsOverlayPrefs.KEY_WIDTH_PCT, LyricsOverlayPrefs.DEF_WIDTH_PCT),
            bgAlpha = prefs.getInt(LyricsOverlayPrefs.KEY_BG_ALPHA, LyricsOverlayPrefs.DEF_BG_ALPHA),
            textColor = resolvedColor,
            strokeDp = prefs.getFloat(LyricsOverlayPrefs.KEY_STROKE, LyricsOverlayPrefs.DEF_STROKE),
            locked = prefs.getBoolean(LyricsOverlayPrefs.KEY_LOCKED, false)
        )
    }

    /** 外观/锁定有变化才应用，没变化就什么都不做（刷新周期里每次都会调用，必须很便宜）。 */
    private fun applyStyleIfChanged() {
        val style = readStyle()
        if (style == appliedStyle) return
        val root = rootView ?: return
        val lp = layoutParams ?: return

        // 背景不透明度
        (textBox?.background as? GradientDrawable)?.setColor(Color.argb((style.bgAlpha * 255 / 100).coerceIn(0, 255), 0x1A, 0x1A, 0x1A))

        // 字号：当前行用设定值，下一行固定缩到 75%
        currentLineView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.textSizeSp)
        nextLineView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, (style.textSizeSp * 0.75f).coerceAtLeast(9f))

        // 字色 + 描边：描边颜色按字色亮度自动选黑/白，保证对比度
        val lum = (0.299 * Color.red(style.textColor) + 0.587 * Color.green(style.textColor) + 0.114 * Color.blue(style.textColor)) / 255.0
        val edgeColor = if (lum > 0.5) Color.BLACK else Color.WHITE
        val strokePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, style.strokeDp, context.resources.displayMetrics)
        currentLineView?.apply { setTextColor(style.textColor); strokeColor = edgeColor; strokeWidthPx = strokePx; invalidate() }
        nextLineView?.apply {
            setTextColor(Color.argb(0xB0, Color.red(style.textColor), Color.green(style.textColor), Color.blue(style.textColor)))
            strokeColor = edgeColor; strokeWidthPx = strokePx * 0.7f; invalidate()
        }
        // 描边是画在字外面的，给文字留一点内边距，不然边缘会被裁掉
        val pad = strokePx.toInt()
        currentLineView?.setPadding(pad, pad, pad, pad)

        // 宽度
        lp.width = (context.resources.displayMetrics.widthPixels * style.widthPct / 100f).toInt()

        // 锁定：不再加 FLAG_NOT_TOUCHABLE——那样悬浮窗收不到任何触摸，也就没法"长按解锁"了。
        // 锁定状态靠手势层处理（不能拖动、点击无效、长按解锁），外观上只把整体调暗一点作提示。
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        textBox?.alpha = if (style.locked) 0.6f else 1f

        try { windowManager.updateViewLayout(root, lp) } catch (_: Exception) {}
        appliedStyle = style
    }

    private fun createView() {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(Color.argb(0xD0, 0x1A, 0x1A, 0x1A))
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(dp(16), dp(10), dp(16), dp(10))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val curTv = StrokeTextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, LyricsOverlayPrefs.DEF_TEXT_SIZE)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        val nextTv = StrokeTextView(context).apply {
            setTextColor(Color.argb(0xAA, 0xFF, 0xFF, 0xFF))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        }
        box.addView(curTv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(nextTv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val root = FrameLayout(context)
        root.addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val widthPct = prefs.getInt(LyricsOverlayPrefs.KEY_WIDTH_PCT, LyricsOverlayPrefs.DEF_WIDTH_PCT)
        val lp = WindowManager.LayoutParams(
            (context.resources.displayMetrics.widthPixels * widthPct / 100f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // NOT_FOCUSABLE：不抢别的应用的焦点和输入法；NOT_TOUCH_MODAL：窗口外的触摸照常传给下面的应用
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = prefs.getInt("lyrics_overlay_x", 0)
            y = prefs.getInt("lyrics_overlay_y", dp(96))
        }

        attachGestures(root, lp)

        try {
            windowManager.addView(root, lp)
            rootView = root
            textBox = box
            currentLineView = curTv
            nextLineView = nextTv
            layoutParams = lp
            lastCurrentText = ""; lastNextText = ""
            appliedStyle = null // 新建的窗口，外观要重新套一遍
        } catch (e: Exception) {
            // 权限刚被撤销、或系统拒绝时不能崩服务
            Log.w(tag, "添加悬浮窗失败: ${e.message}")
        }
    }

    /**
     * 手势：
     *  - 拖动：换位置（锁定时无效）
     *  - 点一下：回到全屏播放器（锁定时无效）
     *  - 长按：锁定 / 解锁（来回切换，不用再去应用设置里关）
     */
    private fun attachGestures(view: View, lp: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        val handler = Handler(Looper.getMainLooper())
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var moved = false
        var longPressed = false

        val longPressRunnable = Runnable {
            longPressed = true
            val nowLocked = !prefs.getBoolean(LyricsOverlayPrefs.KEY_LOCKED, false)
            prefs.edit().putBoolean(LyricsOverlayPrefs.KEY_LOCKED, nowLocked).apply()
            appliedStyle = null // 让下一个刷新周期立刻重新套一遍外观（调暗/恢复）
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            Log.i(tag, "长按悬浮窗 → ${if (nowLocked) "已锁定" else "已解锁"}")
            Toast.makeText(
                context,
                context.getString(if (nowLocked) R.string.lyrics_overlay_locked_toast else R.string.lyrics_overlay_unlocked_toast),
                Toast.LENGTH_SHORT
            ).show()
        }

        view.setOnTouchListener { _, ev ->
            val locked = prefs.getBoolean(LyricsOverlayPrefs.KEY_LOCKED, false)
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = lp.x; startY = lp.y
                    moved = false; longPressed = false
                    handler.postDelayed(longPressRunnable, longPressMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (!moved && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        moved = true
                        handler.removeCallbacks(longPressRunnable) // 手指动了就不算长按
                    }
                    if (moved && !locked && !longPressed) {
                        lp.x = startX + dx.toInt()
                        lp.y = (startY + dy.toInt()).coerceAtLeast(0)
                        try { windowManager.updateViewLayout(view, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    when {
                        longPressed -> Unit // 这次手势已经用来锁定/解锁了，抬手不再触发别的
                        moved -> if (!locked) {
                            prefs.edit().putInt("lyrics_overlay_x", lp.x).putInt("lyrics_overlay_y", lp.y).apply()
                        }
                        !locked -> {
                            try {
                                context.startActivity(Intent(context, MainActivity::class.java).apply {
                                    action = "OPEN_PLAYER_FULLSCREEN"
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                })
                            } catch (e: Exception) { Log.w(tag, "打开播放器失败: ${e.message}") }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }
    }

    private fun removeView() {
        val v = rootView ?: return
        try { windowManager.removeViewImmediate(v) } catch (_: Exception) {}
        rootView = null; textBox = null; currentLineView = null; nextLineView = null; layoutParams = null
        lastCurrentText = ""; lastNextText = ""; appliedStyle = null
    }
}
