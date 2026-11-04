# HGTV 设备身份读取（手机端小工具）

从本机「红果免费短剧」里读出 HGTV 需要的设备身份，导出成 `device-identity.txt`，让电视端 HGTV 免注册播放。

## 前提

- **必须 root（Magisk 授权）**。红果把身份存在自己的私有目录 `/data/data/com.phoenix.read/shared_prefs/`，Android 沙箱不允许普通应用读取，`adb root`、Shizuku、无障碍都绕不过去。
- 手机上已安装并至少打开过一次红果。

## 读的是什么

| 值 | 来源文件 |
| --- | --- |
| `device_id` / `install_id` | `/data/data/com.phoenix.read/shared_prefs/applog_stats.xml` |
| `cdid` | `.../shared_prefs/com.ss.android.deviceregister.utils.Cdid.xml` |

解析同时兼容 `<string name="x">v</string>` 和 `<long name="x" value="v" />` 两种存储形式，并对 `install_id` / `iid` / `installid` 做别名兼容。校验规则与 HGTV 的 `DeviceIdentityFile` 完全一致。

## 用法

1. 安装 APK，打开，允许 Magisk 的 root 请求。
2. 看到三个值和下方的导出内容后：
   - **另存为文件…** → 存到 U 盘（或任意目录），文件名保持 `device-identity.txt`；
   - 或 **分享文字** → 发到微信/QQ/邮件再传到电脑；
   - 或 **复制全部** → 手动粘贴。
3. 把文件放到电视的 **`/sdcard/hgtv/device-identity.txt`**（文件名必须是这个）。
4. 重新打开 HGTV，它会自动读取并跳过注册。

「写入本机 /sdcard/hgtv/」只在手机上也装了 HGTV 时才有意义。

## 注意

- 同一份身份**不要和手机上的红果同时播放**，服务端会把它判成设备异常。
- 身份彻底失效时 HGTV 会自动退回注册，不会一直卡住。
- 本工具不申请网络权限，读到的值不会离开设备。

## 构建

```bash
export JAVA_HOME="C:/Program Files/Java/jdk-17.0.20.1+1"
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。
