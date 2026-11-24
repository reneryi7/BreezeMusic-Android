# BreezeMusic · 微风音乐 (Android)

<p align="center">
  <strong>耳畔微风起 · 帧享真无损</strong><br>
  一款基于 <strong>Kotlin + Jetpack Compose + AndroidX Media3</strong> 打造的轻量级 Android 高解析音乐客户端（支持手机与平板独立双端）
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Version-v1.4.1-00E5FF?style=flat-square" alt="Version" />
  <img src="https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026--34)-3898FF?style=flat-square" alt="Platform" />
  <img src="https://img.shields.io/badge/Language-Kotlin%201.9-7F52FF?style=flat-square" alt="Kotlin" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose%20%2B%20120fps%20Spring-0EA5E9?style=flat-square" alt="Compose" />
  <img src="https://img.shields.io/badge/Audio-Media3%20%2B%2064bit%20Pure%20LTI%20DSP-10B981?style=flat-square" alt="Audio DSP" />
  <img src="https://img.shields.io/badge/License-GPL--3.0-blue?style=flat-square" alt="License" />
</p>

---

## 一、项目简介

**BreezeMusic Android（微风音乐安卓端）** 是一款采用 **Kotlin + Jetpack Compose (Material 3) + AndroidX Media3** 原生构建的轻量、纯粹、高解析 Android 音乐客户端，提供 **手机端 (`phone`)** 与 **平板与大屏端 (`tablet`)** 双构建变体（独立包名与独立签名，支持同设备共存）。

客户端将第三方概念版（`appid = 3116`、`clientver = 11440`）的协议通信、加解密签名与音源解析能力 **100% 纯 Kotlin 端侧直连化**，不依赖任何 Node.js / Python / Go 中转服务器；并在此基础上自研了 **64-bit 纯线性双二阶 (Pure LTI Biquad) 母带级 DSP 引擎、五款高定动态播放背景、全局 120fps 欠阻尼弹性物理动效、KRC 逐字流光与跨应用穿透桌面歌词、Android 16 平板自适应布局及全链路安全加固架构**。

> [!NOTE]
> - 桌面端同系列客户端请参阅姐妹仓库：[**BreezeMusic-Desktop (Windows)**](https://github.com/reneryi7/BreezeMusic-Desktop)。
> - 界面预览截图位已预留，后续可在发布后补充浅/深色主题、沉浸播放页、64-bit 母带调音台与平板双栏等实机截图。

---

## 二、核心特性与功能亮点

### 1. 纯端侧直连、曲库生态与双端双向分享
* **零后端端侧直连**：纯 Kotlin 实现概念版协议签名、RSA/AES 加解密与设备风控注册，所有请求由手机/平板直连官方网关，内置多通道容灾路由。
* **完整曲库生态与双向同步**：覆盖每日推荐（支持右上角真实历史日期回溯、近 7 日真实快照与当前播放一键居中定位）、私人 FM（点击卡片直达待播队列，支持「平衡 / 熟悉 / 尝鲜」单按钮循环三套专属渐变配色与 4.5s 撤销负反馈）、新歌速递（华语 / 欧美 / 日韩 / 粤语四大精准语种 + 吸顶时间轴）、官方巅峰排行榜、随心听电台广场、实时热搜榜与主搜索多引擎歌词搜歌（自动剥离词曲头并高亮匹配唱词）、歌手/专辑详情、无上限极速音乐云盘（本地缓存秒开与真实音质码率校准）、微风本机听歌排行（周/月/总榜领奖台与歌手榜）、自建歌单云端编辑与队列级平滑拖拽排序/长按批量多选、以及歌曲/歌单/专辑实时评论区。
* **多平台在线设备检测与安全下线**：账号管理页实时识别 Windows / macOS / 手机 / 平板 / 车载 / TV / Web 等全平台在线登录设备并支持安全远程下线。
* **酷狗双端双向分享与解析**：支持标准版与概念版双向唤醒分享，内置 `KugouShareLinkParser` 零网络离线解码与短链安全解析，支持单曲即刻播放与外部歌单预览/一键批量导入。
* **账号权益状态同步与低功耗守护**：支持手机短信验证码登录（自动识别同手机号下多账号切换）与二维码扫码登录，支持账号权益状态同步、CSCC 真实听歌时长心跳上报及基于系统 `JobScheduler` 的低功耗定时同步任务。

### 2. 高解析音源、硬件级 `STREAMINFO` 探测与 64-bit 纯线性母带 DSP
* **多音质智能解析与回退**：自动解析 Hi-Res / SQ 无损 FLAC / HQ 高品 / 标准音质并平滑降级，支持移动网络省流保护、Media3 `SimpleCache` 边听边存与四维分项缓存统计/清理。
* **FLAC `STREAMINFO` 硬件级真实参数探测**：遵循 [RFC 9639](https://www.rfc-editor.org/info/rfc9639) 通过轻量 HTTP `Range` 读取流头直接解析 **FLAC `STREAMINFO`**、OGG Vorbis 头与 MP3 帧头，精准还原 `44.1 / 48.0 / 96.0 / 192.0 kHz` 采样率与 `16 / 24-bit` 位深。
* **64-bit 纯线性时不变 (`Pure LTI Biquad`) 母带级 DSP (`ConceptMasteringAudioProcessor`)**：
  * 全链路 64-bit 双精度 Direct Form I 滤波器网络（`THD = 0.000%`、`IMD = 0.000%`），配合 0 dB 平直频段短路加速与防重叠增益协同，彻底根除开启音效后的电流杂音与音量骤降。
  * **声场调音工作台**：超重低音下潜、6 档低频分频谐振、3D 声场宽度与舞台纵深、Bauer 耳机交叉馈送、Schroeder 空间声学混响矩阵、人声通透与 6.2 kHz 齿音抑制（De-Esser）及极高频空气泛音。
  * **发烧级 10 段全参数均衡器 (PEQ)**：支持 10 段独立增益（`-10 dB ~ +10 dB`，`0.1 dB` 步进微调）、中心频点与 `Q` 值连续调节、5 种滤波器拓扑（`Peak / Low-Shelf / High-Shelf / Notch / Band-Pass`）、高通/低通滤波（`HPF / LPF`）、一键 A/B 对比、5 大参考曲线与 `A / B / C` 自定义记忆槽（存取、单槽删除与全清）。
* **进阶播放控制**：支持高潮副歌速刷（Climax 30s）、KTV 伴奏切换、跨曲无缝淡入淡出（Crossfade）、ReplayGain 响度均衡、0.5x–2.0x 倍速与升降调调节、三档音频焦点混音/闪避及有线耳机多击线控定制。

### 3. 五款高定动态播放背景、KRC 逐字歌词与 2K 海报
* **五款高定播放背景 × 播放/暂停连续动量 (`playMomentum`)**：内置 **封面虚化 (`400×400` 统一规格) / 三色无封面流体极光 / 实时三频音频律动光晕 / 暗夜星尘 / 深邃 OLED 纯黑**；三大动态背景采用连续相位积分器，播放与暂停切换时平滑减速驻留、零位置跳变。
* **端侧 KRC 逐字流光歌词与全局穿透桌面歌词**：内置 KRC XOR + Zlib 解密引擎，支持字内卡拉 OK 扫光、双语译文与日/韩罗马音、`O(1)` 前奏/间奏三点呼吸波点、光学景深模糊与 `2×3` 六大四字配色网格；系统级跨应用悬浮桌面歌词支持双行/三行自适应、封面取色随动、物理点击穿透锁定、熄屏零唤醒休眠及通知栏/流体云快捷开关。
* **2K 超清歌词海报与 1000P 高清封面预览**：支持多选歌词段落生成 2K PNG 海报并保存至相册，支持歌曲/专辑/歌单封面与歌手头像 1000P 原图预览导出。

### 4. 全局 120fps 弹性物理动效、低功耗守护与平板自适应架构
* **`ConceptMotion` 五维弹簧与无边框美学**：打造五维欠阻尼物理弹簧与 Android 12+ GPU `RenderEffect` 方向性运动虚化；设置中心全面无边框化，单层 96 dp 极速启动页配双套深浅主题独立持久化。
* **手机与平板独立双端自适应**：基于 `WindowSizeClass` 与 Android 16 大屏规范，平板横屏支持发现/我的双栏平铺、设置页 Master-Detail 左右分栏与合并式黑胶/歌词舞台；平板竖屏与分屏支持左侧垂直居中悬浮胶囊导航栏与 90° 全屏横屏画布翻转。
* **全链路低功耗与心流专注舱**：暂停态主线程 0 唤醒、后台自适应节流、熄屏停帧零耗电；内置心流专注舱（番茄钟 + 程序化环境白噪音混音）。

### 5. 全链路安全与本地隐私加固 (`v1.3.5 ~ v1.3.6`)
* **硬件级 AES-256-GCM 加密存储**：账号登录凭证与内部 256-bit IPC 能力令牌均基于 **AndroidKeyStore 256-bit AES-GCM**（随机 IV + AAD 绑定）加密持久化，遵循 **Fail-Closed**（密钥异常绝不明文落盘），支持老版本密文无感平滑迁移与多账号单条故障隔离；全面禁云备份与 Android 12+ 换机/USB 数据提取（`allowBackup="false"` + `data_extraction_rules.xml`）。
* **敏感窗口纵深防护**：登录页与安全验证弹窗（含独立 Dialog 窗口）接入引用计数式安全窗口守卫，统一启用 `FLAG_SECURE`（防截屏、防录屏与防后台缩略图泄露）、Android 12+ 隐藏悬浮窗与全版本 `filterTouchesWhenObscured` 防点击劫持；WebView 验证码弹窗使用 `WebViewAssetLoader` + `WebMessageListener` + 严格 CSP 沙箱隔离。
* **全链路网络安全与更新供应链校验**：核心登录、用户信息、更新与验证域名系统级强制 TLS，OkHttp 双拦截器阻断携带凭证的 HTTP 与跨域请求；API、Coil、ExoPlayer 自定义流源、下载器与分享解析全链路接入公网 DNS 防重绑定 (`publicOnlyDns`)、逐跳重定向校验与图像解压炸弹防护；在线更新强制权威源或跨独立镜像双共识，下载链接强钉扎官方 Releases 路径。

---

## 三、技术栈总览

| 层级 | 核心技术与方案 |
| :--- | :--- |
| **开发语言与并发** | Kotlin 1.9+ · Kotlin Coroutines & StateFlow / SharedFlow · 前后台自适应节流 |
| **声明式 UI 与动效** | Jetpack Compose (BOM 2024.06) · Material 3 · `WindowSizeClass` 平板自适应 · `ConceptMotion` 五维弹簧 & `RenderEffect` 方向虚化 |
| **音频播放与母带 DSP** | AndroidX Media3 (`ExoPlayer` · `MediaSessionService` · `SimpleCache`) · 自研 64-bit Pure LTI Biquad `ConceptMasteringAudioProcessor` |
| **网络、协议与图像** | OkHttp 4.12 · Retrofit 2.11 · Gson · 纯端侧 `KugouSigner` · Coil Compose 2.6（缩略图降维 & 400P 对齐）· 24-bin 三色特征提取 |
| **安全与二进制引擎** | AndroidKeyStore (AES-256-GCM) · `SecurityGuards` 全链路纵深防护 · 自研零依赖 `AudioMetadataEmbedder` (FLAC / MP3 / OGG) |

---

## 四、下载安装与快速开始

### 方式一：普通用户直接安装（推荐）

前往本仓库右侧的 [**Releases**](https://github.com/reneryi7/BreezeMusic-Android/releases) 页面下载最新正式版 APK（支持 **Android 8.0 及以上，API 26–34**）：

| 安装包变体 | 文件名示例 | 包名与特性说明 |
| :--- | :--- | :--- |
| **手机版（推荐手机用户）** | `BreezeMusic-phone-v1.4.1.apk` | 包名 `com.concept.music`，竖屏交互优化，支持由历史手机版直接覆盖升级 |
| **平板版（推荐平板/大屏用户）** | `BreezeMusic-tablet-v1.4.1.apk` | 独立包名 `com.concept.music.tablet` 与独立签名，大屏双栏与自由旋转，可与手机版共存 |

> [!TIP]
> **安装与校验提示**
> 安装前可核对 Release 页面公布的 **SHA-256 校验值** 确认安装包完整未被篡改；如系统提示「未知来源应用」或安全提醒，确认校验值无误后允许安装即可。

### 方式二：开发者从源码克隆构建

#### 1. 环境准备
* **JDK 版本**：JDK 17（Android Studio 内置 JBR 17 即可）
* **Android SDK**：`minSdk = 26` (Android 8.0)，`compileSdk = 34` (Android 14)
* **Gradle**：项目内置 Gradle Wrapper (`./gradlew` / `gradlew.bat`)，无需手动安装

#### 2. 克隆源码并编译双端 APK（开箱即编译）

```bash
# 克隆仓库
git clone https://github.com/reneryi7/BreezeMusic-Android.git
cd BreezeMusic-Android

# 编译手机版与平板版 Debug 安装包
./gradlew :app:assemblePhoneDebug :app:assembleTabletDebug

# 编译手机版与平板版 Release 安装包（启用 R8 代码优化与资源压缩）
./gradlew :app:assemblePhoneRelease :app:assembleTabletRelease
```

编译完成后，双端 APK 产物路径分别为：
* 手机版：`app/build/outputs/apk/phone/release/app-phone-release.apk`
* 平板版：`app/build/outputs/apk/tablet/release/app-tablet-release.apk`

> [!NOTE]
> **关于签名配置与无密钥开箱编译**
> 为保障发布安全，仓库不包含正式签名密钥（`app/keystore/*.jks` 与 `keystore.properties` 已被 `.gitignore` 严格排除）。**外部开发者克隆后无需任何额外配置即可直接运行上述构建命令**，脚本在未检测到外部正式签名凭据时会自动回退至本机默认 `debug` 签名生成可直接安装的 APK。
> 如需使用自定义正式证书签名发布，可在根目录创建 `keystore.properties`（或通过环境变量 `PHONE_KEYSTORE_PATH` / `TABLET_KEYSTORE_PATH` 等）注入证书路径与口令，并可在发布/CI 中设置 `REQUIRE_RELEASE_SIGNING=true` 强制校验正式签名。

#### 3. 运行双变体单元测试套件

```bash
# macOS / Linux
./gradlew :app:testPhoneDebugUnitTest :app:testTabletDebugUnitTest

# Windows PowerShell / CMD
.\gradlew.bat :app:testPhoneDebugUnitTest :app:testTabletDebugUnitTest
```

---

## 五、项目目录结构

```text
BreezeMusic-Android/
├── .github/
│   └── ISSUE_TEMPLATE/                              # Bug 反馈与功能建议表单模板
├── app/
│   ├── build.gradle.kts                             # 模块级 Gradle 构建配置 (v1.4.1, phone/tablet 双变体独立签名与安全回退)
│   ├── proguard-rules.pro                           # Release 混淆、R8 精简与日志剥离规则
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml                  # 应用组件、媒体前台服务、桌面小组件、禁备份与权限声明
│       │   ├── assets/verification/                 # 本地安全验证 WASM 与严格 CSP 隔离页面
│       │   ├── java/com/concept/music/
│       │   │   ├── ConceptMusicApp.kt               # Application 入口与安全 Coil 图像管线
│       │   │   ├── MainActivity.kt                  # 宿主 Activity：单层 96dp 启动页、高刷申请与同平面导航/抽屉联动
│       │   │   ├── data/
│       │   │   │   ├── kugou/                       # 酷狗概念版 (appid=3116) 纯端侧协议层、AES-256 会话存储与安全传输网关
│       │   │   │   ├── model/                       # 领域数据模型 (Song / Lyric / Playlist / Comment / Discover / Library / Search)
│       │   │   │   ├── repository/                  # 本地播放历史、微风本机听歌统计、SWR/云盘快照与搜索历史仓库
│       │   │   │   ├── share/                       # 酷狗双端双向分享与零网络离线链接解析器
│       │   │   │   └── vip/                         # 全局仓库门面 (VipManager) 与听歌等级管理
│       │   │   ├── service/                         # Media3 前台播放、64-bit DSP、缓存管理、桌面歌词、心流舱、权限中心与元数据内嵌
│       │   │   ├── ui/                              # Jetpack Compose 声明式界面（account / comment / components / home / library / navigation / search / settings / theme / viewmodel）
│       │   │   └── util/
│       │   │       └── SecurityGuards.kt            # 全链路安全守卫（反 SSRF、更新钉扎、路径防穿越、图像炸弹防护、加密 IPC 令牌与敏感窗口守卫）
│       │   └── res/                                 # Android 资源、network_security_config、data_extraction_rules 与双套启动页图标
│       ├── test/java/com/concept/music/             # JVM 单元测试套件（SecurityHardeningTest / UiAlignmentRegressionTest / AudioEnhancementTest 等）
│       └── androidTest/java/com/concept/music/      # 仪器化测试（加密存储与 WebView WASM 验证）
├── gradle/wrapper/                                  # Gradle 8.7 Wrapper
├── build.gradle.kts                                 # 根项目构建脚本
├── settings.gradle.kts                              # 仓库与模块配置
├── CHANGELOG.md                                     # 版本更新日志 (Keep a Changelog)
├── THIRD_PARTY_NOTICES.md                           # 第三方开源组件与上游协议致谢声明
└── LICENSE                                          # GNU General Public License v3.0
```

---

## 六、使用小贴士

* **关于音乐下载与歌词保存模块**：为保持界面默认纯净合规，下载功能默认处于关闭与隐藏状态（设置页与歌曲菜单均不显示下载入口）。如个人本地学习测试确需使用，可前往 **「设置 → 权限管理」** 手动开启 **「音乐下载与歌词保存」** 开关，解锁后可在 **「设置 → 下载」** 中通过系统目录选择器自定义音频与 `.lrc` 歌词保存路径（默认自动内嵌 1000P 高清封面、歌曲标签与时间轴歌词，并可按需开启同步生成独立 `.lrc` 文件）。
* **版本更新记录**：所有历史版本的完整变更明细均同步维护在 [CHANGELOG.md](CHANGELOG.md) 中，并与应用内 **「设置 → 关于 → 版本更新日志」** 及 [GitHub Releases](https://github.com/reneryi7/BreezeMusic-Android/releases) 保持一致。

---

## 七、免责与版权声明

1. **非商业学习性质**：本项目（**BreezeMusic Android / 微风音乐安卓端**）系完全开源、非营利性质的个人计算机软件工程与移动开发技术探索项目，旨在实践 Kotlin 协程并发、Jetpack Compose 声明式 UI、AndroidX Media3 实时音频数字信号处理（DSP）、端侧安全加固及平板自适应架构设计。本项目仅供个人学习、技术交流与编程研究使用，严禁用于任何商业牟利、付费售卖或违规分发。
2. **音乐版权归属**：本应用与任何商业音乐平台及原厂均无从属或官方关联，自身不存储、不托管、不分发任何受版权保护的音频、歌词或封面文件。应用内展示及播放内容均由用户端直连公开网络接口获取，其著作权与邻接权均归原版权方及唱片公司所有。请支持正版音乐，自觉遵守当地法律法规与相关服务条款；任何个人学习测试产生的本地文件请于 24 小时内自行删除。

---

## 八、开源致谢

* [MakcRe/KuGouMusicApi](https://github.com/MakcRe/KuGouMusicApi)：提供了详实的酷狗音乐与酷狗概念版协议研究基础（MIT License）。
* [lisalee23042605/kgmusicbot](https://github.com/lisalee23042605/kgmusicbot)：提供了多音质下载与音频封面/歌词标签内嵌的实现参考。
* [AndroidX Media3](https://developer.android.com/media/media3)、[Jetpack Compose](https://developer.android.com/jetpack/compose)、[OkHttp](https://square.github.io/okhttp/) & [Coil](https://coil-kt.github.io/coil/)：提供了现代化的 Android 媒体播放、声明式 UI、网络与图像管线生态支持。
* 更多上游开源依赖与协议说明请参见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

---

## 九、开源许可证 (License)

本项目基于 **[GNU General Public License v3.0 (GPL-3.0)](LICENSE)** 协议开源，与桌面端 [BreezeMusic-Desktop](https://github.com/reneryi7/BreezeMusic-Desktop) 保持完全一致。任何人均可自由学习、修改与分享，但任何基于本项目的衍生作品或二次分发版本均须保持相同的 GPL-3.0 开源协议，且不得闭源用于商业牟利。
