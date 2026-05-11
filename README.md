# 🌌 Auralis 音澜

**Auralis 音澜** 是一款专为音乐发烧友打造的 Android 本地高保真无损音乐播放器。基于 Kotlin 和 Jetpack Compose 纯声明式 UI 构建。

Auralis 的使命是彻底打破 Android 底层音频接口的妥协：从底层元数据的“真值”解析，到突破系统的“源码直通”输出，再到无限拓展的云端智能补全。Auralis 接管了从“解码”到“输出”的全链路，为你呈现本地无损音乐最真实的生命力。

## ✨ 核心特性 (Key Features)

### 🔊 终极听觉引擎 (Audiophile Engine)
* **🔌 全局自适应 USB 源码直通 (Dynamic Bit-Perfect Output)**
  彻底绕过 Android 系统的 AudioFlinger 混音器。深度适配 Android 14+ 规范，实时抓取底层数据库真值（如 24bit/192kHz），并与外接 USB DAC 进行最高规格的“硬件握手”，点亮小金标，拒绝 SRC 劣化。
* **🛠️ 突破系统限制的“真值”解析 (True-Bit Extraction)**
  不再受限于系统 `MediaMetadataRetriever` 经常错报 48kHz 的通病。深度结合 `MediaExtractor` 与底层数据库（`jaudiotagger`），精准挖掘并展现真实的母带级采样率与位深。
* **⚡ 全新 Media3 1.10.0 驱动与 Offload 硬件卸载**
  全面拥抱新一代播放内核。完美支持高码率 FLAC/WAV/DSD 的超大内存防卡顿缓冲，并在非直通状态下智能开启 `Audio Offload`，实现丝滑的无缝播放与极致省电。

### ☁️ 云端智能与元数据 (Cloud Intelligence & Metadata)
* **🌊 三级瀑布流智能封面引擎 (Waterfall Cloud-Meta)**
  无惧残缺的本地元数据。优先获取 Apple Music (iTunes) API 的 `800x800` 顶级无损原图，网易云音乐 API 无缝兜底，自研极光流体算法渲染终极防线。
* **🎤 全网双擎滚动歌词 (Dual-Engine Synced Lyrics)**
  网易云 API 优先，酷狗音乐无缝降级。在线抓取后强制压缩为 `.webp` 与 `.lrc` 永久持久化至本地，一次联网，终身离线秒开。

### ⚡ 工业级性能架构 (Industrial Performance)
* **🚄 双层防抖缓存引擎 (Ultra-Fast Caching)**
  内置自研 `AudioCache` 单例。采用 `LruCache` (RAM) + 磁盘静态文件双层缓存。智能过滤系统虚构的脏图，通过 `RGB_565` 强效节省 50% 内存。即使面对上千首无损曲库，列表滚动依然绝对丝滑。
* **🛡️ 强悍的双赛道并发防御 (Dual-Track Concurrency)**
  在毫秒级的快速切歌中，彻底消灭时序竞争乱象（Race Condition）。采用协程作用域斩杀机制配合 `500ms` 智能防抖，彻底杜绝歌词串台、接口风控与脏数据写入。

### 🎨 现代发烧级美学 (Aesthetic Interface)
* **🎛️ 沉浸式发烧工作台 (Audiophile Dashboard)**
  抛弃简陋的弹窗，全面重构大圆角、毛玻璃与半透明呼吸感 (Glassmorphism) 的全屏设置与播放界面。内置 ReplayGain 动态响度平衡、专业 EQ 均衡器与 A-B 循环。
* **🏆 发烧级音质分级与专属配色 (Audiophile Tagging)**
  严苛的音质漏斗算法。从 DSD、DXD 到 Master、Hi-Res+。为顶级格式定制了兼顾深色/浅色模式的高对比度 UI 标签（如 DSD 的“翡翠极光绿”，DXD 的“深空幻紫”）。
* **📊 极客级详细信息面板 (Geek-Level Info)**
  一键查看硬核音频档案：编码格式、动态回放增益 (ReplayGain)、空间音频声道检测 (Spatial Audio/AV3A)、文件修改时间以及精准的听歌足迹记录。

## 🛠️ 技术栈 (Tech Stack)

* **UI 框架**: Jetpack Compose (100% 声明式 UI)
* **语言**: Kotlin
* **播放内核**: AndroidX Media3 (ExoPlayer 1.10.0)
* **异步与并发**: Kotlin Coroutines & Flow (Dispatchers.IO)
* **本地存储**: Room Database
* **网络请求**: OkHttp & Gson
* **音频解析**: MediaExtractor, MediaMetadataRetriever, jaudiotagger

## 🚀 核心架构亮点 (Architecture Highlight)

Auralis 解决了一个长久以来困扰 Android 音视频开发者的痛点：**底层数据源污染与多线程读写竞争**。

1. **零 IO 浪费**：只用一次 IO 操作，即可同步完成数据库真值拯救、动态变动参数解析与多级图库提取。
2. **时序竞争防御**：利用 Compose `LaunchedEffect` 的协程取消机制，将高频切歌产生的大量废弃网络请求瞬间斩杀；在无损歌曲深度扫描完成前，利用 `Mutex` 锁严格拒绝脏数据写入磁盘。

## 📜 许可证 (License)

本项目采用 [MIT License](LICENSE) 许可证。
