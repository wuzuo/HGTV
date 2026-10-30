# 应用内更新与发布

更新源为 GitHub 本仓库公开 Releases：`wuzuo/HGTV`。

电视先请求 `https://api.github.com/repos/wuzuo/HGTV/releases/latest`，获取最新正式发行版标签，再读取该标签附件中的 `update.json`，比较内部版本号，并按清单的标签和附件名从 GitHub 下载 APK。清单标签必须与接口返回标签一致。GitHub 的 `latest` 接口只返回已发布的非预发布版本，因此稳定通道规则不需要额外过滤。仅允许 HTTPS 和 `api.github.com`、`github.com`、`*.githubusercontent.com`（附件跳转域名）。此流程由 App 实现；最终覆盖安装由安卓系统执行。

## 新包名过渡

本轮 TV 更名为「HGTV」，安装标识改为 `io.github.wuzuo.hgtv`，此前正式版 0.1.7 / 48 仍是旧包名 `com.hgtv.tv`。新包名首次正式版需用户手动单独安装，不能依赖旧 App 的检查更新完成跨包名升级。本机数据与登录态不自动继承，账户需重新登录；当前设置没有备份恢复入口，尚不能把底层备份实现作为现成的用户迁移步骤。

新包的更新校验、打包与上传入口只接受新包名。统一入口读取历史版本时允许这两个已知包名以规划版本并保留旧签名基线，拒绝无关包名；首次过渡的自动更新说明会明确提示单独安装。新包名正式发布后继续同包名更新，不另开旧包名 APK 版本线。

## 发布流程（推荐：推标签自动发布）

`.github/workflows/release-tv.yml` 在收到 `kotlin-v*` 标签时自动完成：单元测试与 lint → 用仓库密钥签名的 release APK → 从实际 APK 生成 `update.json` → 建 GitHub Release 并上传 APK／说明／清单 → 设为 Latest → 另建任务打包 Windows ZIP 追加到同一 Release。

1. 在 `kotlin-tv/app/build.gradle.kts` 调整 `versionName` 并递增 `versionCode`。正式发布的 `versionCode` 必须超过线上最高版本；调试过程中不递增，用文件名区分。允许跳过未公开的版本号。
2. 更新 `kotlin-tv/RELEASE_NOTES.md`（面向用户的中文说明，最多 1500 字）。CI 优先用它作为更新说明；文件缺失时退回最近一次提交信息。
3. 提交并推送到 `main`。
4. 打标签并推送，标签必须与 `versionName` 完全一致（纯三段语义版本）：

```sh
git tag -a kotlin-v0.1.12 -m "HGTV 0.1.12"
git push origin kotlin-v0.1.12
```

5. 等 `Release TV APK` 工作流变绿。发布成功后，较旧版本在应用内检查更新即可看到新版本；推送代码、合并 main 或生成普通构建构件都不等于发布。
6. 在较旧版本的设备上实际检查更新，核对下载、校验与覆盖安装。

标签格式限定为 `kotlin-v<主>.<次>.<修订>`，带后缀的版本号会在工作流第一步被拒绝，避免清单标签与 Release 标签不一致。需要重跑某次发布时，用 `workflow_dispatch` 填入已存在的标签即可，脚本对同名同内容附件会跳过、对不同内容报错，不会覆盖已发布文件。

### 需要的仓库 Secrets

只在这条发布工作流使用，普通构建与校验工作流不需要任何密钥：

| Secret | 内容 |
| --- | --- |
| `HGTV_KEYSTORE_BASE64` | 签名密钥库文件的 base64，单行无换行（`printf '%s' "$(base64 -w0 hgtv-release.jks)"`） |
| `HGTV_STORE_PASSWORD` | 密钥库口令 |
| `HGTV_KEY_ALIAS` | 密钥别名，当前为 `hgtv` |
| `HGTV_KEY_PASSWORD` | 密钥口令 |

当前发布签名为 `hgtv-release.jks`（别名 `hgtv`，RSA 2048，JKS，有效期至 2054-03-17，证书 SHA-256 `EF:1A:9E:7D:69:A9:F3:68:8F:C5:9D:9D:D5:96:65:FA:31:3D:A5:61:E8:2D:6C:E4:B6:FF:74:05:6F:79:FC:26`），2026-10-30 因旧库口令遗失重新生成。当时 `io.github.wuzuo.hgtv` 尚无任何对外发布（仓库无标签、无 Release），因此未影响已安装用户；旧库 `guoapp-release.jks` 对应旧包名 `com.hgtv.tv`，已废弃。

自本次起必须持续使用同一密钥库并妥善保管私钥与口令：换新密钥会导致已安装用户无法覆盖升级，私钥丢失则无法再为已发布版本签名后续包。`GITHUB_TOKEN` 由 Actions 自带，工作流已声明 `contents: write`。密钥只在构建步骤内解码到 `$RUNNER_TEMP`，步骤结束后删除。

## 手动分步发布（排查用）

CI 不可用时可在本地按同样的三步走，产物结构与 CI 一致：

```sh
export HGTV_KEYSTORE=/绝对路径/密钥库.jks
export HGTV_STORE_PASSWORD=... HGTV_KEY_ALIAS=... HGTV_KEY_PASSWORD=...
./scripts/build-kotlin.sh          # 生成 outputs/releases/<tag>/ 下的 APK + update.json
python3 scripts/publish-tv-release.py --directory outputs/releases/kotlin-v0.1.12 --publish
```

`build-kotlin.sh` 从实际 APK 提取版本、包名、最低系统，并计算大小与 SHA256；直接运行 Gradle 只产出 APK，不产出清单。上传前可用不加 `--publish` 的模式做本地校验：它只比对清单与文件，不读凭据、不发请求。

## 本地统一入口（可选）

`python3 scripts/release-tv.py` 仍是完整的本地流程：默认只读预演，`--publish` 才动仓库。它会规划版本、建 `feature-release-<版本>` 分支与 PR、构建并核对签名、打标签、上传 GitHub Release。与推标签方式二选一即可，两者都以 GitHub Release 为唯一发布目标。

一次配置凭据（只需 `GITHUB_TOKEN`，需要本仓库 Contents 读写）：

```sh
python3 scripts/publish-tv-release.py --configure   # 保存到 ~/.config/hgtv/publish.json，权限 0600
```

也可用环境变量 `GITHUB_TOKEN` 提供，环境变量优先。Token 由维护者自行在 GitHub 创建，脚本不会申请或扩大权限，也不会把它发给下载域名。

## 清单格式

以下只展示结构。版本、大小、SHA256 必须来自实际 APK，示例值不得直接发布。

```json
{
  "schema": 1,
  "packageName": "io.github.wuzuo.hgtv",
  "versionCode": 23,
  "versionName": "0.0.7-search-input",
  "minSdk": 23,
  "tag": "kotlin-v0.0.7",
  "apk": {
    "name": "hgtv-kotlin-0.0.7-search-input-android6.apk",
    "size": 12345678,
    "sha256": "填写实际64位小写SHA256"
  },
  "notes": "面向用户的本次更新说明"
}
```

- versionName、versionCode 必须与 APK 内部版本完全一致，不能只改文件名或清单。
- tag 必须以 `kotlin-v` 开头，且与 Release 标签一致。
- APK 名称必须符合 `hgtv-kotlin-版本标识-android6.apk`；仍兼容 `-android8.apk`。后缀表示最低系统，不是两条独立产品线。文件名和标签允许数字、英文字母、点号、加号和短横线。
- apk.name 与实际附件名一致；size 是字节数，sha256 是实际文件的 SHA256 小写十六进制值。
- 发布时同时提供对应源码或源码链接，保留 GPL 许可和第三方声明。

## 设备行为与边界

- 前台浏览时定期检查，播放时不打扰；默认自动检查下载，设置中可关闭或手动检查。
- 下载后核对大小、SHA256、实际包名、版本及签名，再调用系统安装器。
- 普通电视需要确认安装，并按系统要求允许此应用安装 APK，不能静默安装。
- 相同签名和包名允许覆盖升级并保留数据。签名不匹配时不要通过卸载旧版来强行升级，否则可能丢失本机记录和登录态。
- 仓库和 Release 必须允许匿名访问；App 不保存任何平台凭据，不支持从私有仓库下载。网络不可达不影响已有播放功能。GitHub 未认证接口按来源 IP 限流，同一出口 IP 下的大量设备可能偶发「无法检查更新」，稍后重试即可。
- 自动更新只读取 GitHub；Windows 端尚未接入自动更新，只能从同一 Release 的 ZIP 附件或 Actions 构件手动下载。

## 验证范围

`python3 scripts/test-release-tv.py` 与 `python3 scripts/test-publish-tv-release.py` 为离线回归，不访问平台、不上传。它们覆盖版本规划、清单身份校验、附件摘要冲突、草稿不会被误用等边界。真实发布仍需一次人工触发，并在电视上完成一次从旧版到新版的检查更新与覆盖安装。
