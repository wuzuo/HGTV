# HGTV 设备身份读取（手机端小工具）

从本机「红果免费短剧」里读出 HGTV 需要的设备身份，导出成 `device-identity.txt`，让电视端 HGTV 免注册播放。

## 前提

- **必须 root（Magisk 或 KernelSU 授权）**。红果把身份存在自己的私有目录 `/data/data/com.phoenix.read/shared_prefs/`，Android 沙箱不允许普通应用读取，`adb root`、Shizuku、无障碍都绕不过去。
- **不需要存储权限**。读的是 root 之后的私有目录，和「读取文件权限」无关。
- 手机上已安装并至少打开过一次红果。

## 读的是什么

| 值 | 来源文件 |
| --- | --- |
| `device_id` / `install_id` | `/data/data/com.phoenix.read/shared_prefs/applog_stats.xml` |
| `cdid` | `.../shared_prefs/com.ss.android.deviceregister.utils.Cdid.xml` |

解析同时兼容 `<string name="x">v</string>` 和 `<long name="x" value="v" />` 两种存储形式，并对 `install_id` / `iid` / `installid` 做别名兼容。校验规则与 HGTV 的 `DeviceIdentityFile` 完全一致。

1.1.0 起不再写死包名：会把本机所有 `shared_prefs` 里出现 `cdid` / `device_id` 的文件都抓回来，按「像不像红果」排序后挑一份，包名从文件路径反推。所以红果换包名、装在应用分身（其它 user）里、身份存在 `/data/user_de/0/...`，都能读到。

## 用法

1. 安装 APK，打开，允许 root 授权。
2. 看到三个值和下方的导出内容后：
   - **另存为文件…** → 存到 U 盘（或任意目录），文件名保持 `device-identity.txt`；
   - 或 **分享文字** → 发到微信/QQ/邮件再传到电脑；
   - 或 **复制全部** → 手动粘贴。
3. 把文件放到电视的 **`/sdcard/hgtv/device-identity.txt`**（文件名必须是这个）。
4. 重新打开 HGTV，它会自动读取并跳过注册。

「写入本机 /sdcard/hgtv/」只在手机/盒子上也装了 HGTV 时才有意义 —— 它和读取走同一条 root 通道，写到真实的共享目录。

## 读不到怎么办

先点 **查看诊断信息**，里面是 root 侧脚本的原始输出（同时落在 `<app 缓存>/last-dump.txt`）。

| 提示 | 含义 |
| --- | --- |
| 执行 su 失败 / 没有拿到 root 权限 | root 没授权，或在 root 管理器里被拒绝 |
| 一个配置文件都没扫到 | root 正常，但没有任何 `shared_prefs` 可读 —— 见下面「挂载命名空间」 |
| 找到了配置文件…但没有完整设备信息 | 红果装了但没播过，打开红果播一集再读 |
| 只有非红果候选（会带提示） | 读到的是番茄小说之类的同族 App，能试但不保证有效 |

### 为什么以前提示「没有找到红果的数据目录」

实测（Android 15 + KernelSU 的盒子）root 之后，命令仍然留在**调用方应用的挂载命名空间**里，那里的 `/data/data` 被隔离成只剩应用自己的目录，红果的 prefs 直接返回 `No such file or directory` —— 看着像「红果没装」，其实是看不见。

1.1.0 的修法：把脚本写到缓存目录，然后依次尝试

1. `su -c "nsenter -t 1 -m sh <脚本>"`（切到 init 的挂载命名空间，覆盖上面这种情况）；
2. `su --mount-master -c "sh <脚本>"`（Magisk 的等价写法）；
3. `su -c "sh <脚本>"`（老设备 / 没有 nsenter 的 toybox）。

取第一个能解析出设备身份的结果。

## 注意

- 同一份身份**不要和手机上的红果同时播放**，服务端会把它判成设备异常。
- 身份彻底失效时 HGTV 会自动退回注册，不会一直卡住。
- 本工具不申请网络权限，读到的值不会离开设备。

## 构建

```bash
export JAVA_HOME="C:/Program Files/Java/jdk-17.0.20.1+1"
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`，对外发布时另存为 `outputs/device-identity-reader/hgtv-device-identity-reader-<版本>.apk`。

`app/src/test/resources/dump_phoenix_72932.txt` 是红果 7.2.9.32 在 Android 15 盒子上的真实抓取样本（已把设备身份值脱敏），改解析逻辑时先跑它。
