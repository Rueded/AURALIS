markdown_content = """<div align="center">
  <h1>✨ AURALIS ✨</h1>
  <p><b>发烧级音质 · 流体美学 · 跨屏互联</b></p>
  <p><i>重塑本地音乐体验的纯粹之作</i></p>
</div>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android_8.0+-3DDC84?style=for-the-badge&logo=android" alt="Platform" />
  <img src="https://img.shields.io/badge/Kotlin-100%25-7F52FF?style=for-the-badge&logo=kotlin" alt="Kotlin" />
  <img src="https://img.shields.io/badge/UI-Jetpack_Compose-4285F4?style=for-the-badge&logo=android" alt="Compose" />
  <img src="https://img.shields.io/badge/Audio-Media3_ExoPlayer-FF0000?style=for-the-badge&logo=google" alt="Media3" />
</p>

## 🌌 寻声之旅，始于 AURALIS

**AURALIS (极光)** 是一款专为音乐发烧友和极简主义者打造的本地 Android 音乐播放器。
它不仅拥有 **Bit-perfect USB DAC 源码直通** 的硬核音频引擎，更将 **Jetpack Compose 流体美学** 发挥到极致。从每一次心跳般的律动背景，到指尖划过的丝滑动画，AURALIS 致力于让每一次聆听都成为一场视听盛宴。

---

## 💎 核心特性 (Core Features)

### 🎧 发烧级音频架构 (Audiophile Engine)
* **USB Bit-perfect 源码直通**：绕过 Android SRC (采样率转换) 限制，直接向外接 DAC 输出最高 32-bit / 384kHz 的原生音频流 (需 Android 14+)。
* **动态 ReplayGain 音量标准化**：内置智能音量平衡算法，自动计算并应用增益，告别切歌时的音量忽大忽小。
* **全格式硬核解析**：完美支持 `FLAC`, `WAV`, `APE`, `ALAC`, `DSD (DSF/DFF)`, `DXD` 等无损格式。
* **金标音质认证**：根据文件采样率与位深，智能点亮 `Hi-Res`, `Master`, `DSD`, `DXD`, `Studio` 等专属动态金标。

### 🎨 极致流体美学 (Fluid Aesthetics)
* **自适应专辑取色主题**：基于 Palette 算法实时提取专辑封面主色，UI 界面与播放器完美融入当前音乐氛围。
* **实时音频律动背景**：六大高保真视觉效果随乐而动：
  * `星云耀斑 (Stardust)` / `极细 EQ (Classic EQ)` / `流体漫游 (Fluid)` / `深邃地平线 (Horizon)` / `呼吸律动 (Breathing)`。
* **沉浸式全屏歌词**：支持字体大小自由调节、时间轴精准拖拽跳转，毛玻璃与律动光效交织，提供演唱会般的沉浸感。

### ☁️ 智能元数据补全 (Smart Metadata)
* **神级封面引擎**：自动从 Apple Music (iTunes API) / 网易云抓取 800x800 高清专辑原图，并使用 WEBP 高效压缩持久化缓存。
* **多源在线歌词匹配**：本地无 LRC 时，自动从网易云、酷狗双引擎精准匹配并下载双语/逐字歌词。
* **内嵌标签深度读取**：内置 `jaudiotagger`，精准读取音轨详细信息（位深、采样率、内嵌封面），智能补全未知歌手。

### ⚡ 无缝跨屏互联 (Seamless Sync)
* **PC 音频无线接收器**：开启后可将手机化身为 PC 的零延迟外置音箱 (结合 ADB 端口转发，实现无损推流)。
* **局域网曲库同步**：一键从电脑端极速同步无损音乐与 LRC 歌词，增量更新，拒绝重复下载，后台通知栏实时进度。
* **智能查重与幽灵清道夫**：极简查重算法一键清理重复音频；自动清理已删除的物理文件（幽灵数据），保持曲库绝对纯净。

---

## 🛠️ 技术栈 (Tech Stack)

* **Language**: 100% Kotlin
* **UI Framework**: Jetpack Compose (Material Design 3)
* **Audio Engine**: AndroidX Media3 (ExoPlayer) + Custom AudioSink Interceptor (用于无损频谱捕获)
* **Database**: Room Database + Flow 响应式架构
* **Network**: OkHttp3 + Coroutines
* **Metadata**: Jaudiotagger (Audio Metadata & Tags)
* **Image Processing**: Android Palette API + Native Canvas Drawing

---

## 🚀 极速上手 (Getting Started)

### 环境要求
* Android Studio Iguana | 2023.2.1 或更高版本
* Android SDK 34+
* JDK 17+

### 🌟 隐藏玩法：如何开启 PC 音频无线接收功能？
AURALIS 可以作为电脑的外接声卡，播放电脑上的声音：
1. 在 AURALIS 的「设置」-「连接与同步」中开启 **PC 有线音箱模式**。
2. 使用数据线连接手机与电脑，开启手机的 **USB 调试** 模式。
3. 在电脑终端（终端/CMD）运行以下命令进行端口转发：

```

```text
Fetched content: Successfully generated AURALIS_README.md

```bash
   adb reverse tcp:8899 tcp:8899

```

4. 在电脑端启动音频推流脚本，此时电脑的音频即可通过 8899 端口极低延迟无损传输至手机播放！

---

## 📜 隐私与开源协议 (License & Privacy)

* 本项目致力于提供纯粹的本地播放体验，**绝不主动收集或上传任何用户隐私数据**。
* 本项目基于 [MIT License](https://www.google.com/search?q=LICENSE) 协议开源。欢迎提交 Issue 与 Pull Request 共同完善！

---

with open("/mnt/data/AURALIS_README.md", "w", encoding="utf-8") as f:
f.write(markdown_content)

print("Fetched content: Successfully generated AURALIS_README.md")

```
