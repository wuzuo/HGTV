# HGTV

非官方红果短剧客户端，包含三个端：**Android TV**、**Android 手机** 与 **Windows 桌面**。三端共用同一份内容解析核心，TV 与手机共用播放层，界面各自实现。

> 本项目与红果官方无隶属关系。非官方接口可能随平台调整失效。

## 目录结构

| 目录 | 内容 |
| --- | --- |
| `kotlin-tv/` | Android TV 客户端（Kotlin + Android View + Media3），最低 Android 6.0 / API 23 |
| `kotlin-tv/app-phone/` | Android 手机客户端（Kotlin + Compose + Media3），最低 Android 7.0 / API 24 |
| `kotlin-tv/playback/` | TV 与手机共用的播放层：取流、档位筛选、解码能力探测、设备身份读取 |
| `desktop-windows-flutter/` | Windows 客户端（Flutter + media_kit / libmpv），Windows 10/11 x64 便携版 |
| `bridge/` | Windows 端使用的本机回环服务（Kotlin/JVM）：站点解析、签名、解密与账号逻辑，只监听 `127.0.0.1` 的随机端口 |
| `kotlin-tv/core/` | 共享内容核心，TV、手机与 `bridge` 都通过 Gradle 依赖它 |
| `scripts/` | 各端构建脚本与 TV 发布工具 |
| `.github/workflows/` | TV / 手机 APK 发布、Windows 便携包构建 |

## 当前版本

| 端 | 版本 | 说明 |
| --- | --- | --- |
| Android TV | 0.1.24 / versionCode 65 | 安装标识 `io.github.wuzuo.hgtv`，namespace `com.hgtv.tv` |
| Android 手机 | 0.1.0 / versionCode 1 | 安装标识 `io.github.wuzuo.hgtv.phone`，namespace `com.hgtv.phone`，可与 TV 端同装 |
| Windows | 0.1.0 | 便携 ZIP，由 CI 构件提供，未接入自动更新 |


## Android TV

[构建说明](kotlin-tv/README.md) · [在线更新与发布](kotlin-tv/AUTO_UPDATES.md) · [评论与弹幕](kotlin-tv/SOCIAL.md) · [界面材质与主题](kotlin-tv/UI_THEME.md)

### 找剧与发现

- 首页读取官方推荐流，可切换漫剧、真人剧，试播作品第一集；上下换剧、换一批、观看全集或加入账户收藏。切回本次已浏览的来源时恢复列表与选中作品；退出应用后再进来接着上次刷到的位置继续，按账号与来源分别记录（登录态确认前不会用临时游客身份覆盖已保存的位置）。
- 首页正文顶部显示最近一条本机播放历史（剧名、第几集、看到哪），按一次确定直接接着播那一集；整集已看完时改为从下一集开始。本机没有记录时不占位。
- 找剧支持全部、真人剧、漫剧、AI 剧，按主题、设定、背景、排序、受众、上新时间等分组组合筛选；进入时筛选默认收起，详情返回保留条件与位置。
- 关键词搜索提供遥控器三栏布局：左侧 A–Z/0–9 键盘、删除/清空与中文/手机输入，中间联想词与最近历史，右侧剧集结果。
- 综合、真人剧、漫剧、AI 剧热播榜，榜单内可再按题材筛选，六列海报网格连续浏览并保留原榜名次。
- 详情页提供简介、选集、集数分组、相关作品与前后季浏览；海报卡片右下角叠加「全 N 集 / 更新至 N 集」角标。

### 追剧与账号

- 本机收藏、观看历史、断点续播与整剧已看标记；本机收藏可检查新增集数。
- 红果扫码登录后可读取并增删账户收藏、查看账户历史、云端续播；账户预约区分已上线与待上线。
- 本机记录与账户云端记录分开保存。退出或切换账号会清除旧账号的云端列表，不删除本机历史。

### 播放

- 遥控器方向键选剧、选集、快进快退，确认键播放／暂停；播放页 ↓ 选集、↑ 更多，返回键先收起控制层。
- 清晰度、倍速、画面模式与自动连播可调并记忆；定时停止支持按分钟、本集播完或再播完指定集数。
- 按电视解码能力自动选流，超出上限的清晰度自动降档；虚报能力导致失败时学习并降档重试。
- 整集只剩 BVC2（H.266）编码时，先换一台注册身份按低档机口径重取一次，服务端通常会给回 HEVC；
  仍拿不到时 Windows 端改用本机 FFmpeg 软解成 HLS 继续播放（起播多等十几秒，需要本机装了
  ffmpeg，或用环境变量 `HGTV_FFMPEG` 指向它），电视端没有 VVC 解码器，会提示换一集。
- 播放中监测音画偏移并自动重同步。
- 可选：把自己设备上官方客户端的播放身份放进 `device-identity.txt`，即可完全免掉每次播放的设备注册；
  不放也能正常播，只是每播一集要多注册一次。TV 上直接放到 `/sdcard/hgtv/device-identity.txt`，
  Windows 上放到 `hgtv_windows.exe` 旁边的程序目录，或 `%USERPROFILE%\.hgtv\device-identity.txt`，
  路径与格式见[开发笔记](docs/DEVELOPMENT_NOTES.md)。读到配置、以及配置在使用中失效，两端都会
  明确提示一次（TV 为浮层提示，Windows 为底部状态栏 + 提示条），不用再靠现象猜。
  Windows 的「设置 → 设备身份」还能随时看当前读到哪个文件、或该放到哪里。
  - 需要存储权限才能读这个目录：Android 11+ 要在系统设置里给 HGTV 开启「所有文件访问」，
    Android 6–10 则在首次启动时授权存储权限。没有权限时系统会把目录静默隐藏，文件放着也读不到，
    所以 TV 端首次启动会说明一次并给出直达入口，设置页的「设备身份配置」也能随时看当前状态
    （已读取／需授权／未放置）。

### 界面与弹幕

- 全站采用新拟态（Soft UI）材质，设置 → 界面主题提供 14 套材质，切换即时生效不重启。
- 真实视频时间轴的弹幕，暂停冻结、快进快退后重新定位；显示方式（只显一行 / 上方显示 / 全屏显示 / 隐藏）、速度、字号与透明度可调并记住；同句重复聚合、热点弹幕中央突出，表情标签按随包图片显示。
- 本集评论支持连续加载、游标续读、去重与失败重试；评论和弹幕均只读，未提供发送、回复或点赞。

## Android 手机

独立 APK，与 TV 端同装不冲突。界面用 Compose 重写（TV 那套遥控器焦点与手写 `LinearLayout` 在触摸屏上没有可复用的部分），协议层与播放层直接复用。

- 首页推荐瀑布网格，触底自动翻页；详情页显示剧集信息与全部选集。
- 榜单页分综合、真人剧、漫剧、AI 剧四榜，海报角标标注名次（前三名高亮），一次取回完整一百名不分页；四榜各自缓存、切走再切回不重复请求，也各自记住滚动位置。
- 播放页全屏横屏、保持常亮，一集播完自动进下一集；失败时给出可重试提示。
- 取流与 TV 端共用同一套判定：接口元数据选档 → MP4 头真实尺寸复核 → 逐级降档 → 严格判定全灭时按静态探测兜底。
- 最高取 1080P；设备身份、签名、AES 解密全部走 `kotlin-tv/core`，与 TV 端同一份实现。
- 另有搜索（联想词、最近历史、结果分页）、「我的」（本机收藏与观看记录、登录）、清晰度切换、弹幕与评论、后台播放与画中画。
- 播放手势按屏幕方向分工：竖屏单击暂停/继续（不注册双击，点一下立即响应）、上滑下一集下滑上一集（画面跟手）、横滑调进度；横屏单击显隐控制层、双击暂停。
- 播放时通知栏不挂常驻控制条；只有退到后台、声音还在继续时才用它做前台服务保活。

## Windows

[构建与使用说明](desktop-windows-flutter/README.md)

- 首页推荐、搜索、找剧（类型 + 题材）、热播榜、详情选集、相关作品、本集评论与整剧剧评。
- 首页顶部「继续观看」显示本机最近一条记录（剧名、第几集、进度），点一下直接接着播；本机页观看记录在前、本机收藏在后。
- 480P/720P/1080P、0.5×–2× 倍速、自动下一集（无感切换，提前预加载）、断点续播、全屏与选集侧栏。
- 迷你播放：窗口缩成约 420×258 的置顶小窗，可拖动画面移动、双击还原。
- 弹幕与 TV 端同一套规则：30 秒窗口预取、重复内容聚合成「原文 ×N」、最热一条中央突出，四种显示方式、三档速度、字号与透明度可调并记忆；表情用系统 Segoe UI Emoji 字形绘制。
- 扫码登录与短信登录，账户历史、收藏、预约与进度同步；登录态用 Windows DPAPI 绑定当前用户加密保存。
- 浅色 / 深色 / 跟随系统三种主题。

便携 ZIP 自带精简 JRE（jlink）与 libmpv，不需要安装 Java、VLC 或 .NET 运行时。

## 构建

**Android TV**（需要 JDK 17 与 Android SDK 36）：

```bash
./kotlin-tv/gradlew -p kotlin-tv :app:assembleDebug
```

**Android 手机**（同一套 JDK / SDK）：

```bash
./kotlin-tv/gradlew -p kotlin-tv :app-phone:assembleDebug
```

`playback` 模块同时被两个 app 依赖，改动它要跑 `:playback:testDebugUnitTest` 与两端的编译。

**Windows**（需要 JDK 17、Flutter 3.47、Visual Studio 2022「使用 C++ 的桌面开发」）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build-windows-flutter.ps1
```

脚本先 `gradlew -p bridge installDist` 编译 `bridge.jar`，再 `flutter build windows --release`，最后用 `jlink` 生成精简运行时并打包为 `outputs\windows-flutter\HGTV-windows-flutter-x64-<版本>.zip`。

## 发布

TV 发布走标签：改好 `versionName` / `versionCode` 与 `kotlin-tv/RELEASE_NOTES.md` 后推到 main，再推 `kotlin-v<版本>` 标签，`Release TV APK` 工作流会自动签名打包、生成 `update.json`、建 GitHub Release 并设为 Latest，同时把 Windows ZIP 挂到同一个 Release。本地备用链是 `python3 scripts/release-tv.py`（默认只读预演，`--publish` 才动仓库）。所需 Secrets、手动分步与限制见 [应用内更新与发布](kotlin-tv/AUTO_UPDATES.md)。

应用内更新读取 GitHub Releases（仓库常量在 `kotlin-tv/core/src/main/kotlin/com/hgtv/core/AppUpdates.kt`）。Windows 暂未接入自动更新，从同一 Release 的 ZIP 附件下载。

手机端单独走 `phone-v<版本>` 标签，由 `Release Phone APK` 工作流构建签名 APK 并建独立 Release（附件为 APK 与 `sha256sum.txt`）。手机端没有应用内更新，`versionCode` 取 CI 构建序号。两条标签线互不影响，TV 的 `kotlin-v*` 不会触发手机构建，反之亦然。

## 开发约定

- 功能与修复从 `main` 拉 `feature-<描述>` 或 `fix-<描述>` 分支，验证后走 PR 合回 `main`。
- 三端共用 `kotlin-tv/core`，TV 与手机另共用 `kotlin-tv/playback`，改动共享层时所有依赖方都要跑检查。
- 修改用户可见行为时同步更新对应 README，只描述已实现的能力。
- 详细流程见 [开发流程](CONTRIBUTING.md)，自动化协作约定见 [AGENTS.md](AGENTS.md)。

## 许可证

以 [GPL-3.0](LICENSE) 发布，版权与第三方许可证声明见 [第三方声明](kotlin-tv/NOTICE.md)。内容协议与加密处理移植自 drpys（22261ad），AndroidX Media3 / OkHttp 为 Apache-2.0，Bouncy Castle 为 MIT。
