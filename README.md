readme_content = """<div align="center">

# 🌌 AURALIS 

**为极致发烧而生 · 纯粹、深邃的本地音乐播放器**

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0+-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-UI-4285F4.svg?logo=android)](https://developer.android.com/jetpack/compose)
[![Media3 ExoPlayer](https://img.shields.io/badge/Media3-ExoPlayer-3DDC84.svg?logo=android)](https://developer.android.com/guide/topics/media/media3)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

AURALIS 是一款专为**“金耳朵”**与**“像素眼”**打造的 Android 本地无损音乐播放器。
它不仅拥有绕过安卓底层 SRC 的真 32-bit Float 音频引擎，更搭载了极度考究的光学级音频视觉律动（Visualizer）。
拒绝光污染与廉价感，用最克制的光影，还原音乐的呼吸。

[**⬇️ 下载最新 APK**](#) | [**📖 查看更新日志**](#) | [**🐛 报告问题**](#)

</div>

<br/>

## ✨ 核心特性 (Key Features)

### 🎵 绝对无损的音频架构 (Audiophile Engine)
* **32-bit Float 音频管线**：底层 `AudioSink` 强制开启 32-bit 浮点运算，完美保留 24-bit/32-bit 极致高频泛音与动态范围，拒绝 16-bit 截断。
* **USB DAC 源码直通 (Bit-perfect)**：支持 Android 14+ 的原生 Mixer Bypass 技术。绕过系统 SRC，让外部解码器接管原始采样率，体验 LHDC 与独立 DAC 的最强推力。
* **ReplayGain 智能响度标准化**：内置智能音量包络追踪，自动平衡不同年代歌曲的响度差异，告别“切歌忽大忽小”。
* **全格式解析支持**：全面支持 FLAC, WAV, APE, ALAC, 以及发烧级的 **DSD, DXD**。精准识别 Spatial Audio (多声道) 与 Audio Vivid，并打上专属金标。

### 🌌 旗舰级光影律动 (Cinematic Visualizer)
抛弃系统自带的死板 Visualizer，AURALIS 采用自研**套壳拦截器 (`VisualizerInterceptingAudioSink`)**，在不损耗音质的前提下抓取音频底层数据，并结合**发烧级包络跟随器 (Envelope Follower)** 实现了 6 款顶级光学视效：
1. **星云耀斑 (Flare)**：真随机粒子引擎 + 多重层叠渲染。动态生成 4/6/8 角星芒，带真实的镜头十字耀斑与内粗外细的衍射衰减效果。
2. **极细 EQ (Pro)**：对标百万级混音台的底置频谱，底层柔和辉光 + 顶层 1.5px 极锐利主体线，横竖屏自动适配计算。
3. **深邃地平线 (Horizon)**：极简的高级直线与微弱上下漫射的极光，搭配正弦波细线交织，克制而不失张力。
4. **流体漫游 (Fluid)**：Apple Music 风格的丝滑果冻光晕，冷暖色相偏移交融，随重低音瞬间爆发并 Q 弹回落。
5. **呼吸与静态**：为偏爱极简的用户保留的轻量级优雅渐变。

### 🎨 现代化交互与极简美学 (Material You & Compose)
* **全局动态色彩**：采用 Palette API 从高清专辑封面中提取主色，并动态计算 HSV 提升饱和度，实现沉浸式的全屏氛围。
* **自定义主题库**：内置黑曜石、深海、极光、玫瑰等高级预设（Auralis Preset），并支持滑动无极调节色相 (Custom Hue)。
* **多形态响应式布局**：完美适配竖屏手机与横屏平板。在高度受限的横屏设备上自动触发“紧凑模式”，保证封面与歌词的完美排列。
* **120Hz 丝滑动画**：从封面展开到音量拉条，全程遵循物理惯性动画。

### 🛠️ 极客级曲库管理 (Geek Tools)
* **智能歌词与封面刮削**：本地匹配失败时，自动并发调用 Apple Music (iTunes)、网易云音乐、酷狗 API 抓取 **800x800 高清封面**和精准 LRC 歌词。
* **PC 有线音箱模式**：手机瞬间化身 PC 零延迟高保真音箱。支持反向 TCP 端口穿透，通过 USB 线缆直接接收电脑音频流！
* **Wi-Fi 极速同步**：同一局域网下，快速对比电脑端与手机端音乐，自动筛选缺失曲目，后台常驻级极速下载。
* **幽灵清道夫与智能查重**：不仅自动清理不存在的幽灵文件，更能跨目录、跨格式精准揪出“同名同歌手”的重复文件，一键物理清理。

<br/>

## 📸 界面一览 (Screenshots)

<div align="center">
  <img src="https://via.placeholder.com/250x500.png?text=Home+Screen" width="22%"/> &nbsp;
  <img src="https://via.placeholder.com/250x500.png?text=Full+Screen+Player" width="22%"/> &nbsp;
  <img src="https://via.placeholder.com/250x500.png?text=Stardust+Visualizer" width="22%"/> &nbsp;
  <img src="https://via.placeholder.com/250x500.png?text=Settings+%26+Theme" width="22%"/>
</div>

> *注：实际效果包含 60fps+ 的平滑律动过渡与自适应色彩变幻。*

<br/>

## 🏗️ 技术栈 (Tech Stack)

* **UI 框架**: Jetpack Compose (Material Design 3)
* **音频引擎**: AndroidX Media3 (ExoPlayer)
* **音频解码**: MediaExtractor + jaudiotagger (高精度元数据/ReplayGain解析)
* **本地数据库**: Room Database (支持协程 Flow 响应式监听)
* **网络与 API**: OkHttp3 + Coroutines
* **架构设计**: 单一真实数据源 (Single Source of Truth) + MVVM 思想

<br/>

## 🚀 编译与运行 (Build & Run)

1. 克隆本项目到本地：
2. 使用 **Android Studio (Ladybug 2024.2.1 或以上版本)** 打开项目。
3. 确保安装了 Java 17，并在 Gradle 设置中配置正确。
4. 连接 Android 设备（建议 Android 8.0 以上，体验 Bit-perfect 需 Android 14+）。
5. 点击 `Run` 编译并安装。

## 🤝 贡献与反馈 (Contributing)

AURALIS 诞生于对“不妥协音质”和“极致 UI”的追求。如果你也对 Android 音频底层或者酷炫的 Compose 动画有想法，非常欢迎提交 PR 或 Issue！

* 发现 Bug 或有新的想法？ 👉 [提交 Issue](https://www.google.com/search?q=%23)
* 想为视觉库新增炫酷代码？ 👉 [发起 Pull Request](https://www.google.com/search?q=%23)

## 📜 许可证 (License)

本项目采用 [MIT License](https://www.google.com/search?q=LICENSE) 许可协议。
"""

with open('/mnt/data/README.md', 'w', encoding='utf-8') as f:
f.write(readme_content)

print("README.md generated successfully.")

```
