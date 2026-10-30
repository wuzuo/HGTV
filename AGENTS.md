# Repository workflow

## 提交与分支

- 后续 Git 提交标题和提交说明使用中文，清楚描述具体修改内容及必要原因；代码标识符、命令和版本号可保留原文。
- 提交标题使用 Conventional Commits 类型前缀，例如 `feat:`、`fix:`、`docs:`、`test:`，前缀后的描述及提交正文继续使用中文。
- 稳定分支是 `main`。功能与修复从最新 `main` 拉 `feature-<简短描述>` 或 `fix-<简短描述>`，不使用分组前缀；验证后通过 PR 合回 `main`，未经用户明确授权不要直接向 `main` 推送功能改动。

## 工程范围

- 本仓库只维护两个客户端：`kotlin-tv/`（Android TV）与 `desktop-windows-flutter/`（Windows 桌面）。
- `kotlin-tv/core/` 是共享内容核心，`bridge/` 是 Windows 端使用的本机回环服务；两者都由 `kotlin-tv/gradlew.bat` 驱动，`bridge` 是独立 Gradle 根。
- 改动 `kotlin-tv/core` 或 `bridge` 时，两端都要跑对应检查，不假设只有一端受影响。
- 历史 React Native、Mac、Compose Desktop Windows 与旧 Node 服务代码已从本仓库移除，不要重新引入。

## 标识与版本

- TV 安装标识为 `io.github.wuzuo.hgtv`，Kotlin namespace 为 `com.hgtv.tv`；Windows 可执行文件为 `hgtv_windows.exe`。保持包名与签名连续性，不得随意更换。
- 每次正式发布递增 `versionCode`；调试时保持 `versionCode`/`versionName` 不变，用文件名区分测试包。当前 TV 版本为 0.1.11（versionCode 52），只在下次正式发布时递增。
- 应用内更新只读取 `wuzuo/HGTV` 的 Releases。源码推送或 CI 构件都不是发布，流程见 `kotlin-tv/AUTO_UPDATES.md`。

## 检查与验证

- Kotlin 改动通常需要 `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`；仅文档改动不需要 Android 构建。
- Flutter 改动在 `desktop-windows-flutter` 下执行 `flutter analyze` 与 `flutter test`；涉及真实接口时可用 `HGTV_LIVE_BRIDGE=1` 单独跑 `test/live_bridge_test.dart`。
- 用户偏好自行安装 APK 与实机验收，然后发截图。不要操作模拟器或安装测试包，除非用户明确再次要求。
- 本机缺少 Visual Studio C++ 工具链时无法执行 `flutter build windows`，此时以 CI 的 Windows 工作流构建结果为准，并如实说明未在本机验证。

## 文档

- 增删改用户可见功能时同步更新 `README.md` 与对应端的 README，只描述已实现行为，区分未验证或不可用的能力；文档更新与代码改动放在同一次提交。

## 发布

- 用户无需每次指定 TV 版本。发布使用统一入口 `python3 scripts/release-tv.py --publish`，自动核对两平台正式版本、实际 `versionCode`、已有标签和草稿；默认递增补丁版本，`versionCode` 超过源码及已发布包的最大值。
- 无参数运行仅预演，只有用户明确要求正式发布时才加 `--publish`，不为脚本内部步骤重复确认。功能仍先走 PR 和用户验收；中断任务用 `--publish --resume <版本>` 继续，不另分配版本、不覆盖旧标签或附件。

## 安全

- APK、构建产物、账号会话、手机号、验证码、密码与签名密钥不得提交。凭据不得出现在日志或文档中。
