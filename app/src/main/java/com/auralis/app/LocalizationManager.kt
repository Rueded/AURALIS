package com.auralis.app

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class AppLanguage(val code: String, val displayName: String) {
    ZH("zh", "中文"),
    EN("en", "English")
}

/**
 * 多语言方案：走标准的 Android 资源系统（res/values/strings.xml 是中文默认值，
 * res/values-en/strings.xml 是英文），界面里用 stringResource(R.string.xxx) /
 * context.getString(R.string.xxx) 取文案。
 *
 * 以后要加新语言（比如日语），只需要新建一个 res/values-ja/strings.xml，
 * 把已有的 key 都翻一遍，不用碰任何 Kotlin 代码。
 *
 * ⚠️ 这里之前用的是「在 Compose 树顶层用 CompositionLocalProvider(LocalContext provides ...)
 * 包一层」的做法，切标签页/按钮文字没问题，但 AlertDialog / Dialog 这些控件在 Compose 内部
 * 是单独开一个 Android Window 的，它们拿 Context 有些路径不是走 LocalContext.current，
 * 而是走 View 自带的 context（也就是 Activity 最初 attachBaseContext 时那个，没被替换过）——
 * 这就是"大部分弹窗打开都是英文"这个 bug 的根源：CompositionLocalProvider 只在纯 Compose 渲染
 * 路径上生效，盖不到这些另开 Window 的弹窗。
 *
 * 现在改成更底层、更可靠的方案：在 Activity 的 attachBaseContext() 阶段就把 Context 包好，
 * 这样整个 Activity（包括它开出来的所有 Dialog/Window）从一开始就是正确语言的 Resources，
 * 不存在"某些控件绕过了 Compose 包装"的问题。切换语言时调用 activity.recreate()，
 * Activity 重新走一遍 attachBaseContext → onCreate，界面全部按新语言重新渲染。
 */
object LocalizationManager {
    private const val PREFS_NAME = "MusicSyncPrefs"
    private const val KEY_LANGUAGE = "app_language"

    private val _language = MutableStateFlow(AppLanguage.ZH)
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    /** 直接读 SharedPreferences，不依赖 _language 这个 StateFlow——
     *  因为 attachBaseContext() 运行时机比 init() 还早，这时候 StateFlow 可能还没被赋过值。 */
    fun readSavedLanguage(context: Context): AppLanguage {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_LANGUAGE, null)
        return if (saved == AppLanguage.EN.code) AppLanguage.EN else AppLanguage.ZH
    }

    /** App 启动时调用一次，把上次选的语言同步进 [language] 这个 StateFlow（给用到它的地方用，比如设置页高亮当前选项）。 */
    fun init(context: Context) {
        _language.value = readSavedLanguage(context)
    }

    /**
     * 只负责把选择持久化。真正让界面切换语言的是调用方之后自己触发的 activity.recreate()——
     * 之所以不在这里自动 recreate，是因为这个函数在非 Activity 的 Context（比如 Service）下也可能被调用。
     */
    fun setLanguage(context: Context, lang: AppLanguage) {
        _language.value = lang
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_LANGUAGE, lang.code)
            .apply()
    }

    /**
     * 非 Compose 场景（Service、Toast 文案拼接等没有 stringResource 可用的地方）：
     * 传入任意 Context，返回一个按当前选中语言配置过 Locale 的 Context，
     * 用它的 .getString(R.string.xxx) 就能拿到跟界面上一致的语言版本。
     */
    fun localizedContext(base: Context): Context = wrapContext(base, _language.value)

    /**
     * 核心包装函数：给 [base] 套一层只改 getResources()/getTheme() 的 ContextWrapper，
     * 其余方法（包括 Activity/Owner 链相关的）全部透传给 base，避免切断
     * ActivityResultRegistryOwner、LifecycleOwner、SavedStateRegistryOwner 等的查找链路。
     * MainActivity.attachBaseContext() 和上面的 localizedContext() 都复用这同一份逻辑。
     *
     * ⚠️ 踩过的坑：getResources() 不能在这里只算一次然后缓存住返回——
     * AndroidManifest 里 Activity 声明了 configChanges="orientation|screenSize|..."，
     * 意思是横竖屏旋转时系统不会重建 Activity（也就不会重新走一遍 attachBaseContext），
     * 而是直接在原有的 Resources 上把 Configuration（包括 orientation）就地更新。
     * 如果这里用一份"创建时"就固定住的 Configuration 快照，横竖屏怎么转都会
     * 一直读到那份旧快照的 orientation，界面表现就是"横屏竖屏长得一模一样"。
     * 改成每次 getResources()/getTheme() 被调用时，都从 base 现在最新的
     * Configuration 重新拷贝一份（这样能拿到系统实时更新的 orientation 等信息），
     * 只在这份新鲜拷贝上覆盖 locale——这样横竖屏切换和语言切换两件事互不干扰。
     */
    fun wrapContext(base: Context, lang: AppLanguage): Context {
        fun buildConfigContext(): Context {
            val config = Configuration(base.resources.configuration)
            config.setLocale(Locale(lang.code))
            return base.createConfigurationContext(config)
        }
        return object : ContextWrapper(base) {
            override fun getResources(): Resources = buildConfigContext().resources
            override fun getTheme(): Resources.Theme = buildConfigContext().theme
        }
    }
}

/**
 * 保留这个包装器只是为了不用改动调用处的写法（setContent { LocalizedContent { ... } }）。
 * 真正的语言切换已经由 MainActivity.attachBaseContext() 在框架层做掉了，
 * 这里现在只是透传 content，不再需要额外包一层 CompositionLocalProvider。
 */
@Composable
fun LocalizedContent(content: @Composable () -> Unit) {
    content()
}
