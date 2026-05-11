# 🌌 Auralis 音澜

**Auralis 音澜** 是一款专为音乐发烧友打造的 Android 本地高保真无损音乐播放器。基于 Kotlin 和 Jetpack Compose 构建，拥有极客级别的底层元数据解析能力与极佳的丝滑列表体验。

Auralis 致力于打破 Android 底层音频接口的妥协，为你呈现音乐文件最真实的“面貌”。

## ✨ 核心特性 (Key Features)

*   **🛡️ 突破系统限制的“真值”解析 (True-Bit Extraction)**
    不再受限于 Android 系统 `MediaMetadataRetriever` 经常错报 48kHz 的通病。Auralis 深度结合 `MediaExtractor` 与底层数据库真值（如 `jaudiotagger`），精准挖掘并展现真实的 24bit/192kHz 母带级采样率与位深。
*   **🏆 发烧级音质分级与专属配色 (Audiophile Tagging)**
    严苛的音质漏斗算法。从 DSD、DXD 到 Master、Hi-Res+，再到 Studio 和 CD。为顶级格式定制了兼顾深色/浅色模式的高对比度 UI 标签（如 DSD 的“翡翠极光绿”，DXD 的“深空幻紫”）。
*   **⚡ 工业级双层防抖缓存引擎 (Ultra-Fast Caching Engine)**
    内置自研的 `AudioCache` 引擎。采用 `LruCache` (RAM) + 磁盘静态文件双层缓存，配合 `Mutex` 并发锁防御多线程时序竞争。即使面对上千首无损曲库，列表滚动依然绝对丝滑，不卡顿主线程。
*   **🎨 智能封面提取与防毒 (Smart Album Art)**
    多级精准图片提取策略。优先读取物理内嵌原图，智能过滤系统虚构的 "Unknown" 专辑脏图，并通过 `RGB_565` 强效节省 50% 内存。
*   **📊 极客级详细信息面板 (Geek-Level Detailed Info)**
    一键查看硬核音频档案：编码格式、动态回放增益 (ReplayGain)、空间音频声道检测 (Spatial Audio/AV3A)、文件修改时间以及精准的听歌足迹（播放次数与上次播放）。

## 🛠️ 技术栈 (Tech Stack)

*   **UI 框架**: Jetpack Compose (100% 声明式 UI)
*   **语言**: Kotlin
*   **异步处理**: Kotlin Coroutines & Flow (Dispatchers.IO 高效并发)
*   **本地存储**: Room Database
*   **音频解析**: MediaExtractor, MediaMetadataRetriever, jaudiotagger (或同类底层解析库)
*   **图片加载**: 原生 Bitmap 高效解码与 WEBP 压缩


## 🚀 核心架构亮点 (Architecture Highlight)

Auralis 解决了一个长久以来困扰 Android 音视频开发者的痛点：**底层数据源污染与多线程读写竞争**。
项目中极其自豪的 `AudioCache` 单例，实现了：
1. 时序竞争防御：在无损歌曲深度扫描完成前，拒绝写入磁盘缓存产生脏数据。
2. 资源极致利用：只用一次 IO 操作，同步完成数据库真值拯救、动态变动参数解析与多级图库提取。

## 📜 许可证 (License)

[MIT License](LICENSE)
