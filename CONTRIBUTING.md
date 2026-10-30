# 开发流程

本仓库只维护 Android TV（`kotlin-tv/`）与 Windows（`desktop-windows-flutter/`）两个客户端，两者共用 `kotlin-tv/core/` 内容核心与 `bridge/` 本机回环服务。

## 环境

| 目标 | 需要 |
| --- | --- |
| Android TV | JDK 17、Android SDK 36（`platforms;android-36`、`build-tools;36.0.0`） |
| Windows | JDK 17（含 `jlink`）、Flutter 3.47+、Visual Studio 2022「使用 C++ 的桌面开发」 |
| 发布脚本 | Python 3.11+ |

Gradle Wrapper 在 `kotlin-tv/gradlew`（Windows 为 `kotlin-tv/gradlew.bat`），`bridge` 是独立 Gradle 根，通过 `-p bridge` 复用同一个 Wrapper。

## 分支

- 稳定分支 `main`。
- 功能从最新 `main` 拉 `feature-<简短描述>`，修复拉 `fix-<简短描述>`，不使用分组前缀。
- 实现并验证后通过 PR 合回 `main`；未经明确授权不要直接向 `main` 推功能改动。

## 检查

Android TV：

```bash
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows 客户端：

```powershell
cd desktop-windows-flutter
flutter pub get
flutter analyze
flutter test
```

Windows 打包（会先编译 bridge，再跑 `flutter build windows --release`，最后用 `jlink` 生成精简运行时并压缩）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build-windows-flutter.ps1
```

需要真实接口的联调测试默认跳过，手动开启：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:HGTV_BRIDGE_DIR = 'bridge\build\install\bridge\lib'
$env:HGTV_LIVE_BRIDGE = '1'
flutter test test/live_bridge_test.dart
```

## 提交

提交标题使用 Conventional Commits 前缀（`feat:`、`fix:`、`docs:`、`test:`、`ci:`），前缀后的描述与正文用中文，说明改了什么、为什么改。

## 文档

改动用户可见行为时同步更新 `README.md` 与对应端的 README；只写已实现的能力，未验证的部分要明确标注。

## 发布

TV 使用统一入口：

```bash
python3 scripts/release-tv.py            # 只读预演
python3 scripts/release-tv.py --publish  # 正式发布
```

细节见 [应用内更新与发布](kotlin-tv/AUTO_UPDATES.md)。

## 不要提交

APK、构建产物、账号会话、手机号、验证码、密码与签名密钥都不得进入仓库；凭据不要出现在日志或文档里。
