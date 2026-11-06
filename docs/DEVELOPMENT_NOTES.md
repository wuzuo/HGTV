# 开发与排查笔记

本文件保存仍适用于当前代码的排查结论，替代旧交付报告和按日期堆积的实验记录。涉及接口行为的结论有时效性，改动前对照源码和真实反馈；不将自动化测试当成设备验收。

## 项目基线

- 当前原生工程在 `kotlin-tv/`；`main` 是统一 Android 6+ 基线，不分别维护 Android 6、Android 8 产品线。
- minSdk 23、compileSdk/targetSdk 36、JDK 17；原生构建和发布入口见 [构建说明](../kotlin-tv/README.md)。
- 保留上游 Git 历史、旧 RN/Node 源码、许可证和第三方声明。历史文档可从 Git 恢复，不必保留在根目录。
- 每项需求从最新 main 开短期分支。未合并的功能需要到对应分支读取文档，不能把测试分支描述成已发布功能。
- 只由用户操作电视、模拟器并反馈；自动化检查和打包由开发侧执行。

## 内容与播放

- 搜索使用 `ContentRepository.searchAll()` 的网站搜索，覆盖短剧和漫剧。首页分类与搜索独立；不要重新引入已失败的漫剧搜索链路。
- 首页、搜索正常但播放超时时，先核对播放请求路由、设备标识和响应是否为空。
- 当前播放使用独立的随机设备标识，同一 Repository 实例内保持稳定；不附带搜索签名头。参考来源和许可见 [NOTICE](../kotlin-tv/NOTICE.md)。不要把其他社区项目的播放路由直接替换进来而不验证本项目已有效的链路。
- 保留响应大小限制、请求取消、媒体解密、视频轨道检查和原有清晰度筛选。空响应或平台拒绝不应伪装成空列表或成功播放。
- 只有明确的前后台页面、会话代次允许异步结果更新 UI；离开页面或切换账号后丢弃旧结果。

## 登录与账户

- 红果官网扫码流程可用；业务登录以账号资料存在有效身份为准。匿名设备注册成功或短信发出均不等于账号已登录。
- 短信曾被滑块和会话过期拦截。不要枚举验证码、重复发送、猜测多个验码端点；错误状态要明确显示。
- Android 的 OnDismissListener 异步派发曾导致 busy 未及时清除，后续保存任务被跳过。主动关闭 QR 时先解除旧监听，再同步释放操作状态，防止旧回调干扰后继任务。
- AccountStore 使用 Android Keystore AES-GCM；账号凭据不进入片单备份、文档、Git 或日志。
- 云端历史限制最近 50 部；本机续播与云端续播来源分开，退出、换号立即清空云端资料并使旧请求失效。

## 预约与相关季

- 预约列表为 `/reading/user/subscribe/list/v0/`，`tab_type=13`，`is_online=true/false` 分别取两类；单类失败不能伪装成无记录。
- 已上线通常提供 subscribe_data / video_data 嵌套资料；待上线实际返回外层 name、cover、item_desc、item_id、expected_rec_text。不得因缺少嵌套对象而把整批待上线丢掉。
- 没有可播放 series_id 的预约条目使用带 `reservation:` 前缀的内部显示键，不把它拿去播放。
- 相关季接口为 `/reading/bookapi/plan/v:version/`，`scene=10`、`from=detail_page_more_related`。相关 cell 为 show_type 547；content_type 1004 是短剧，1007 是待上线，即使 disable_play=false 也不能播放。原著小说不在当前展示范围。
- 预约提交必须使用目标季身份，不误用其他预约项编号。请求接受不等于账户列表已确认，不提前显示成功；详见 [相关作品说明](../kotlin-tv/RELATED_WORKS.md)。

## Android 6 兼容

- Base64 使用已有 Bouncy Castle 实现，避免调用 Android 6 不具备的 java.util.Base64。
- 有界封面队列使用旧系统具备的带容量构造方法。
- Android 6 使用 NetworkRequest 监听网络；Android 7+ 才调用默认网络监听。
- Android 8+ 使用逐应用安装权限流程；旧系统交给系统安装器。读取 minSdkVersion 等较新 API 必须做版本判断。
- 分发 APK 保持签名连续，并兼容 Android 6 的 v1 签名；覆盖前核对包名、内部版本和签名。

## 更新与测试

- versionCode 按所有分发包递增，测试包也算；versionName、分支名和 Release 标签没有自动关联。
- update.json 是本项目自定义更新协议，运行 scripts/build-kotlin.sh 会从实际 APK 自动生成配套清单；普通 Gradle 构建不生成。版本、附件名、大小、SHA256 必须与实际 APK 一致。
- 旧更新源或旧最低系统更新逻辑的已安装 APK，可能需要手动覆盖一次才能切换本仓库更新源。推送或合并源码不会修改已安装 APK。
- 当前构建步骤见 [构建说明](../kotlin-tv/README.md)，发布步骤见 [在线更新说明](../kotlin-tv/AUTO_UPDATES.md)。只有文档改动不需要重新构建 Android；检查相对链接和 Git diff 即可。

## 电视排行榜

排行榜使用 RankingScreen 独立页面，四类官网榜单各读取完整五页后替换内存缓存，榜内题材过滤保留原名次。数据来源、边界和遥控器验收见 [TV_RANKINGS.md](../kotlin-tv/TV_RANKINGS.md)。不将筛选后的作品重新排序或冒充官方题材榜；尚未接入手机推荐榜、预约榜等其他榜单。

## 收藏与历史来源

收藏/观看历史各有本机、账户子页签；LibrarySources 仅保存界面偏好，不迁移记录。账户收藏读取 video_shelf_info，由 AccountController 的会话代次隔离，失败保留同账号旧数据，退出和切换立即清空。顶部删除了独立账户历史入口，预约/设置内部索引变为 5/6。详情及快捷操作中分开提供本机/账户收藏；账户写入使用 video/update，确认账号、先读后写再回读，未确认时不自动重试。协议及尚未验证的边界见 [ACCOUNT_SYNC.md](ACCOUNT_SYNC.md)。

## 找剧入口

顶部原搜索页改为找剧。空关键词走官网 `/category/{real-drama|comic-drama|ai-drama}/{topic?}` 的服务端分类，题材来自 selectorList，必须核对 categoryRoute 和分页。提交关键词仍用跨类型 searchAll，不继承分类条件，不以本页过滤假装全局搜索筛选。返回分类恢复 DramaDiscoveryState 的页码、作品、题材及焦点；滚动键包含类型和题材。详情见 [DRAMA_DISCOVERY.md](../kotlin-tv/DRAMA_DISCOVERY.md)。

## 电视交互整理（2026-10-22）

已实施，待用户实机确认：调整剧集卡片长按弹窗，顺序为「继续观看/直接播放」「查看剧集详情」「加入/取消账户收藏」「加入/取消本机收藏」。两种收藏在弹窗内相邻，顶部页签保持原样，不增加收藏子菜单。移除「加入/移出稍后看」选项；暂时保留已有本机稍后看数据，不做数据清除。保留本机历史管理操作，菜单按动作绑定回调，避免顺序调整误触其他操作。

详情主操作行提供续播、选集，账户/本机收藏与简介放第二行。选集直达当前组及当前集，显示当前集与本机上次保存集；不推断跳过的集数已看。历史卡片补齐总集数与位置，预约卡片补充上线状态/时间。设置分为播放偏好、账户、数据管理、应用更新四组。共享按钮以橙字表示已选，边框表示焦点；首页准备好播放后收起底部状态条，加载、暂停和错误仍显示。找剧的题材展开、返回分类恢复以及账户读取失败保留旧列表已有实现，保留原行为。测试版 0.0.13-tv-usability / versionCode 35，仍在 feature-home-drama-feed 验收。

## 正式播放下一集准备与队列接续

本次修复尚未正式发布。原实现为每次预加载创建新 ContentRepository，导致播放设备标识和会话与当前播放不同；预加载传输使用更短的 8/15/20 秒超时，转为正式播放后仍沿用该限制。切集发生在预加载未结束时，PreparedSlot.take 会清除未完成的槽位，随后取消准备任务并重新获取同一集。可控延迟用例在 API 23/26 复现该取消重来行为；不能单凭此测试断定真实设备上每次重试均由这一原因引起。

现保持按倍速折算后剩余 30 秒内启动，只准备下一集。当前播放至少稳定 1.5 秒且已有 5 秒缓冲才启动，末尾不足 5 秒时要求剩余部分均已缓冲；仍遵守自动连播、前台播放、非计费网络和重试间隔限制。播放模型读取复用当前 Repository，视频传输使用继承正常超时及匿名 CookieJar 的独立连接池/调度器，关闭投机连接不能关闭主客户端。

准备先消费 32 KiB，再按 MP4 索引长度补齐；不再等待 RemoteVideo.warm 的固定 2 MiB 下载。索引完成后转移 RemoteVideo 与视频客户端所有权，追加到同一 ExoPlayer，后续媒体缓冲由播放器负责。RemoteVideo.warm 作为底层有界缓存能力继续保留。

切到仍在准备的同一集时，将原 Future、RequestScope 和传输资源交给正式播放工作，后台等待结果；失败后可退回正常获取，取消时先取消父工作，再取消准备请求并清理槽位。过期的 OpenedStream 也会关闭其视频资源，避免取消与完成交错时泄漏。匹配仍以集 ID/清晰度为准，不在 UI 线程等待。

已入队后的自然切换保存上一集完成位置、更新标题/集号/清晰度和本机进度，移除旧媒体并关闭旧资源，该路径不停止或重新 prepare 播放器。手动队列切集不消耗定时停止的已播完集数；本集播完停止、退出、错误及更换清晰度仍清理待播资源。真实网络、CDN 和解码器行为仍需设备验收，不能保证完全无缝。

## 首页官方推荐流与换剧队列（0.1.0 / versionCode 38）

新功能在 feature-home-recommendations，带入 main 已合并的测试竞态修复。入口、请求会话、账号隔离及实测边界见 [HOME_FEED.md](../kotlin-tv/HOME_FEED.md)。推荐与分类来源分开；换一批继续服务端游标，账号变化清空旧游标。上下换剧命中预缓冲时在同一播放器队列 seek，队列每项结尾暂停，不自动滚动作品。不要将成功携带 Cookie 等同于已验证手机个性化偏好，也不要将试播进度上报作为隐含功能加入。

版本码 39：修复首页 PublicFeedBackend 公共内容客户端关闭跳转导致 HTTP 301；只对带账号的推荐请求关闭跳转。首页漫剧/真人剧改走 App 分类推荐 landpage/v，sort 为空，按类型隔离 session、offset 和最近 540 个去重 ID；找剧分类保持不变。原生内容跳转通过本地 HTTP 301→200 回归验证；两类推荐及视频头准备通过匿名只读探针验证。

版本码40：首页试播复用明确首集元数据，跳过一次详情请求；分类数据缺首集标识时保持详情回退，不能把推荐片段 vid 当首集。公开连接池跨首页访问保留2分钟、最多3连接，每个页面独立取消请求。边界与验证见 HOME_FEED.md。

- 更新源为 GitHub Releases：先匿名请求 `/releases/latest`，再按 tag 读取 update.json；清单标签须与 Latest 一致，`latest` 接口本身已排除草稿与预发布。附件从 github.com 下载并跳转到 `*.githubusercontent.com`，只允许这两个域名族。源码同步不等于发布，需单独上传附件并设为 Latest。GitHub 未认证接口按 IP 限流，必要时可稍后重试。

- 搜索网页现改为客户端加载，/search/<keyword> 不再包含 _ROUTER_DATA。全部剧集搜索应读取官方 GET /api/search/result?query=<encoded>&page=N，响应直接包含 searchList、pageNum、totalPages、hasMore；video_data 为对象。不要把 HTML 页面或缺少 searchList 的响应当作空结果。已用“大佬”验证前两页各 10 条。

## 找剧组合筛选（2026-10-24）

- TV 找剧使用官网 App 的 `POST /reading/distribution/category/landpage/v/`，与首页兼容目录和 Mac 使用的网页分类分开。查询版本参数在此请求内设为 73532，沿用本项目签名器；匿名实测全部、真人、漫剧、AI 均返回剧集。
- 条件放在 `select_items`：`genre`、`category_dim_theme`、`category_dim_role`、`category_dim_epoch`、`sort`、`gender`、`online_time`、`creation_status`，各值为数组；空数组代表不限制，不能将 `gender=["0"]` 当成全部，它代表女频。选项 ID 与分组来自 `selector_rows`，不从旧网页分类路径猜测。
- 首批 `offset=0`、`need_selector_panel=true`、`req_type=default`；续批使用响应的 `next_offset` 和 `session_id`，`req_type=only_content`。最多 100 批；偏移不前进或续页会话缺失时报错，不按本地条数伪造游标。会话不进入日志或文档样例。
- 卡片来自 `data.video_data`，读取 `series_id`、`title`、`cover`、`episode_cnt`、`video_desc` 和 `category_schema`；加载失败保留列表，改变条件或离开页面使旧回调作废。组合条件变化从首批开始，切类型清空旧条件。
- HGTV 1.2.3 正式包的分类协议与其公开 main 的旧网页分类实现不同。本轮仅静态核对公开包的接口字段，并直接向平台验证协议，未移植它的实现代码；不以公开 main 的单行分类作为 1.2.3 功能边界。

### 分类场景与 TV 返回行为修复

- `req_scene` 必须与类型匹配：漫剧 `comic_series`、AI 剧 `ai_series`，全部与真人沿用 `default`，首批与续批保持一致。仅传 `select_items.genre` 而场景仍为 default，会出现类型标签错配；此前仅验证返回非空，未充分核对标签语义，本次增加类型场景回归和真实筛选续页核验。
- 用户提供的官方包经静态读取为 com.phoenix.read 6.5.5.32，包含旧版分类入口；未安装或运行。不同官方版本和服务端分组可能变化，不承诺完全复刻所有版本的标签。
- 分类页取消常驻加载更多／回到顶部，普通续页保持网格焦点，失败后才显示手动重试。返回键统一调用列表回顶、筛选区滚动复位与导航焦点复位；不只是把焦点移到顶部导航。
- 返回详情后从条件模型重画选中状态，保存筛选行横向／纵向位置与页头偏移，确保已选项可见。页头滚动步长在当前页面内固定，避免海报随可用高度变化时反复改变偏移。

## 播放信息空响应与连续切集诊断

2026-10-26 使用用户截图作品匿名读取相邻三集：第 11 集两次 HTTP 200 均为空，第 12/13 集首次为空、第二次有模型，随后视频头范围读取成功。说明真实播放信息接口存在间歇性空响应，并非仅由播放器队列产生；不能据此断定平台内部是限流、缓存或其他机制。

进一步验证 v1 multi_video_model 的同次三集读取，返回 3 个与请求 ID 匹配的模型。正式播放因此顺带请求后两集，将匹配的后续模型保存在当前 Repository 内，最多 6 条、60 秒有效，领取后移除；部分缺失时回退到单集读取，批量响应不得用其他集的第一条数据代替当前集。缓存不落盘，重试不会持续复用已经领取的旧地址。

空响应的内部读取上限调整为三次，退避 700/1500 毫秒，瞬时连接故障仍只重试一次。每次更新请求时间戳，设备身份、业务参数及集号保持不变；时间戳对照样本不足以确认其是根因，不能宣称单改时间戳即可解决所有空响应。UI 和安全摘要日志保留已知错误类别，倒计时不再覆盖失败原因，不显示媒体地址、响应正文或账号信息。上述改动与前序修复尚未正式发布，实际设备效果仍需持续验收。

### 优化效果的设备验收口径（2026-10-27）

已有匿名探针记录一次三集模型读取成功、后两集额外模型请求各为 0；这证明该样本减少了模型请求，不能换算为设备播放成功率。受控交接回归证明不再取消同一在途任务并重来，也不能代表真实网络、视频传输及解码均成功。单集和批量探针都曾收到 HTTP 200 空响应，批量读取不保证消除该故障。目前没有设备端优化前后的成功率统计，不能断言实际故障率已降低。

先固定使用 `hgtv-tv-0.1.9-next-model-batch-debug.apk`，按首页试播、详情正式播放、手动下一集、自然连播分别记录；不要把这四类混成一个成功率。每次用户进入播放或发生一次切集算一次观察，内部重试不增加分母。记录是否出现首帧、是否显示过错误、是否自动恢复、是否需要手动重试及最终是否放弃；出现错误后自动恢复的观察仍属于“出现过错误”，不能记为全程无错。错误只抄界面的安全类别，不收集媒体地址、响应正文或账号信息。首页快速划走、主动退出及用户取消单独标记，不算播放失败。

每类可先累计 30 次作为排查样本，记录设备/API、网络类型、测试包文件名及大致测试时段。30 次仅是初步排查量，不构成改善的统计证明；没有旧版同口径数据时，只报告当前包的计数，不给出下降百分比。若需要前后对照，应在相同设备、网络和相近时段交替测试明确标注的旧包与新包，分别汇总；仍需考虑平台随时间波动，不能根据一次短测判断根因。

当前没有新增设备端持久计数或上传。先用上述观察表和已有安全错误提示确定故障集中在哪条路径；若仍无法定位，再补充有界、无标识的请求次数、缓存命中、交接和首帧结果诊断，同时提供用户可读取的入口。只有底层请求计数、没有首帧结果和取消分类，仍不足以衡量用户实际成功率。

### 首页与全屏匿名复核（2026-10-27）

使用当前核心代码执行 JVM 只读探针，不登录、不上报历史、不运行 Android 播放器，仅读取播放模型、32 KiB 起始数据并补齐 MP4 索引、解析轨道。首页沿用默认的 12/20/35 秒超时和无 CookieJar 配置，读取推荐页实际返回的 3 条和漫剧页前 6 条：9 条中 8 条准备成功，1 条三次空响应后抛出 EmptyPlaybackResponseException；8 条成功中有 6 条经过内部重试。共发出 18 次模型请求，其中 10 次 HTTP 200 为零字节。这个样本确认首页仍可在准备阶段失败，不是仅凭体感推断。

全屏补测沿用正式 ContentRepository 的 MemoryCookies 与 15/30/45 秒超时，读取此前截图作品第 9–20 集，每三集的首集同时获取后两集模型：12 集均完成索引准备，8 集领取缓存而没有额外模型请求；4 次批量读取共发出 9 次请求，其中 5 次 HTTP 200 零字节，3 次批量读取经内部重试恢复。本轮没有复现全屏准备最终失败，但不代表全屏没有错误。另有一轮未启用 CookieJar 的全屏对照，不与正式配置样本合并统计。

上述结果不包含 UI 外层自动恢复、首帧渲染、设备解码筛选或完整视频传输，不是实机播放成功率，也没有优化前同口径对照。结论仍是批量缓存确实减少后续请求，而播放接口间歇性空响应仍存在；未据此继续增加重试或更换设备身份。记录及可复核探针保存在已忽略的 outputs/diagnostics，分别为 playback-path-verification-20261027.json 和 fullscreen-cookie-verification-20261027.json。核心及 app 单元检查通过，沿用当前源码对应的 211+158 项缓存结果；没有安装 APK 或操作模拟器。

### 空响应原因的对照调查（2026-10-27，尚未找到可验证的根治修改）

固定同一匿名 Repository 的设备身份、同一分集和请求体，交替发送不带内部重试的请求，记录 HTTP 状态、字节数及模型是否匹配；不输出请求 URL、签名值、Cookie 或正文。每组对照内部保持身份一致，各组重新建立匿名 Repository，不能把不同组直接合并成故障率。以下都是桌面 JVM 网络样本，不能代表电视端所有错误。

- 传输对照每种 6 次：HTTP/2 复用连接空响应 3 次、新连接 2 次；HTTP/1.1 复用连接 3 次、新连接 3 次。空响应的返回头明确包含 Content-Length: 0，同种协议下也能获得有效模型。没有依据通过强制协议或关闭连接池消除问题。
- 签名对照每种 8 次：原始请求、仅采用签名器可签名查询参数、添加现有签名器请求头，三种均空响应 5 次。此组固定原播放 UA，不能据此断言缺签名是根因。
- 路径对照每种 6 次：当前 multi_video_model 路径取得有效模型 4 次；已有参考资料中的 video_model 单集路径，无签名及有签名均 6 次为空。没有将应用切到该路径。参考源码的历史说明不是当前接口可用性的证据。
- UA 对照每种 8 次：当前硬编码 UA、与查询设备参数一致的现有 VendorConstants UA、匹配 UA 并签名，分别空响应 6/7/2 次。后一候选在小样本看起来较好，追加 16 对交替复核后，原请求有效模型 7/16，匹配 UA 并签名仅 6/16，未重复出现改善；因此没有把这一候选作为修复。

已确认的直接失败链是播放模型 HTTP 200 正文为空，有限内部重试耗尽后准备失败；空响应发生在媒体下载和设备解码之前。但为什么远端返回空正文仍未查明，不能由这些结果推断限流、WAF、缓存或服务节点问题，也不能认定实机的所有首页/全屏错误都属于同一类。此次未找到能够消除空响应的可靠客户端修改，未增加重试、替换设备身份或接口，不能将调查写成“已解决”。进一步实机归因需要具体安全错误类别；平台内部原因需要相应服务端证据。

对照数据及探针保存在已忽略的 outputs/diagnostics 下，以 empty-response-transport-cause、empty-response-signing-cause、empty-response-route-cause、empty-response-user-agent-cause、empty-response-signing-confirm 为前缀、20261027.json 为日期后缀。保留这批反例，避免后续根据一次成功样本再次引入未验证的协议、签名或 UA 修改。

### 播放全面返空：未注册设备身份的复现与修复（2026-10-27）

用户截图《天谴赊刀人》第 1 集显示「播放信息接口返回空响应」。当前生产逻辑匿名复查：首页推荐及漫剧合计 6 条，每条内部三次读取均为空。搜索该标题的请求本轮返回 HTTP 错误，未取得截图作品的确切分集 ID；没有将其他作品的验证冒充该作品实机复测。

本轮增加了此前未覆盖的设备注册对照。先沿用原参数、UA、接口和请求体，注册一次匿名设备；随后在禁用 CookieJar 的播放客户端中，固定同一分集、交替验证原随机身份/注册身份，以及各自有无显式设备 Cookie：原身份两组各 5 次全部 HTTP 200 零字节，注册身份两组各 5 次全部返回对应模型。这定位了当前样本的设备未注册问题，Cookie 本身不是该对照的决定因素；并非根据历史参考注释直接推断，也不能由此反推此前所有间歇性故障均同因。

删除播放链路随机生成 iid/device_id 的逻辑。PlaybackDeviceSession 首次播放使用现有匿名注册协议获取合法设备 ID/安装 ID，只在进程内保留，首页与正式播放共用。注册串行合并、成功后不轮换；请求只携带白名单设备 Cookie，不转发账号 Cookie。注册失败不再用随机身份继续请求模型，提供独立安全错误类别；注册受 RequestScope 取消控制。保留原播放接口、UA、请求体、批量缓存和有界重试预算，不增加内部重试。进程结束后身份消失，新进程需要重新注册，未新增持久凭证或迁移账户数据。

修复后的最终核心代码在线复核：首页实际返回的 5 条推荐/漫剧以及同一作品相邻 6 集，共 11 次模型、索引和少量解密数据读取全部成功；只注册 1 次，共 7 次模型请求、0 次空响应，4 个后续分集直接领取缓存。本轮仍未运行 Android 播放器，不能作为设备首帧、整集解码或所有网络的成功保证。回归覆盖注册失败、并发只注册一次、跨 Repository/重试保持身份、Cookie 白名单、取消注册及 API 23/26 首页与全屏会话共享。原始诊断和探针仅在忽略的 outputs/diagnostics 内，修复尚未正式发布。

### 空响应与客户端签名的关系（2026-11-03）

0.1.16 曾按「设备身份失效、重启即重新注册」的推断加了换身份重试，但它在 0.1.16 发布当天仍被用户复现（「播放又空响应了」）。本轮改用 JVM 探针直接对照请求形态，结论是**换身份不是根因**。

同一进程内，反复读取同一部作品的同一分集，记录 HTTP 状态、Content-Length、响应头与是否为空：

- 现行请求（手工拼 query，只带 UA/Accept，不带 `x-gorgon` 等客户端签名头）：13 次全部 `200 + Content-Length: 0`，响应头 `server: TLB` / `volc-dcdn`。同期搜索、首页、详情接口（走 `Signer.sign` 完整签名）全部正常。
- 只补 `x-ss-req-ticket`（与 query 的 `_rticket` 一致）：3 次仍全空，说明只对齐时间戳头不够。
- 用签名器生成合法 `ts`/`_rticket` 但不发签名头：3 次仍全空。
- 完整签名请求（`x-gorgon`/`x-argus`/`x-ladon`/`x-ss-req-ticket` 全带）：不再空，业务层返回 `110001 未知异常`。
- 完整签名 + 固定 `VendorConstants.device`（不走注册身份）：同样 `110001`，说明 110001 与身份来源无关。

即：本机出口下 CDN 只放行带完整签名的请求，而放行后业务层给 110001。结合 10-27 的对照（用户网络下「添加现有签名器请求头」一组 8 次里 3 次成功拿到模型），110001 更像本机出口被业务层风控，而不是签名请求本身非法；本轮没有干净出口可复核，不能写成已验证。今天是 13/13 全空而 10-27 只有 5/8，也说明空响应率随出口环境变化，单机样本不能外推。

改动：播放接口保留原有匿名请求与换身份作为主路径，连续空响应后在末尾补一次完整签名请求；签名请求若被业务层拒绝（错误码非 0），仍按原「空响应」错误上报，不把 110001 之类内部码透给界面。同时修掉一处自相矛盾：`text()` 过去在重试时只改 query 里的 `_rticket`、不重算签名，现在改为查询参数与 `x-ss-req-ticket` 头一起刷新。

局限：本机无法端到端验证签名兜底是否真能拿到模型，只能确认「空响应 → 兜底 → 不再空」这条链路成立、失败时仍报原错误。是否有效必须用电视端实机验收，且要按首页试播、详情播放、手动切集、自然连播分开计数。

## 2026-11-03 播放接口空响应：抓包定案

用真机（SM-A546E，Android 15）+ MITM 抓 `com.phoenix.read`（红果 7.2.9.32）的播放请求做过比对，
空响应是三个原因叠加，不是单一问题：

1. **域名不对**。网页详情页（`hongguoduanju.com/detail`）给出的分集号属于
   `api5-normal-hl.fqnovel.com` 集群。应用一直打 `api5-normal-sinfonlineb`，
   同一集号在 b/c/a/lf 上都只回 `200 + 0 字节`，只有 hl 返回播放模型。
2. **签名器内部设备信息必须与 query 同源**。`Signer` 默认拿 `VendorConstants.device` 里的
   `device_id` / `version_name` 生成头部，而请求 query 用的是设备注册返回的身份。两者不一致时
   服务端判成伪造，同样只回空；`Signer(device = 实际发出的参数)` 之后立刻拿到 `code = 0`。
3. **新注册的设备身份对播放接口只有一次额度**。同一 `device_id` 的第二次请求（换域名、换集、
   换连接、间隔 10 秒、间隔 90 秒都试过）一律空响应。红果自己不受限，是因为它的设备身份在
   服务端有两个月的历史；把它的 `device_id/iid/cdid` 拿过来、用我们自己的签名连播，也照样给
   完整模型。所以「老身份不限次、新身份一次」是服务端侧的设备历史，客户端伪造不出来，
   只能二选一：每次播放换一台设备，或者直接用一台真实设备的身份（见下一节）。

请求体和参数也要对齐红果当前版本，旧值会被判空：`dr_scene:"player"`、
`disable_video_relate_book`、`use_server_dns:true` + `use_server_dns_scene:"cold_start_stage"`、
`version_code/version_name = 72932 / 7.2.9.32`、`channel=xiaomi_8662_64`、UA 同步成 72932。

改法：`ContentRepository.playbackResponse` 改成「完整签名 + hl 域优先 + 每次播放新设备」，
域名兜底 hl → c → lf；不再对同一域名重复读空响应（播放接口重发没有意义）。

端到端验证：连续三集各拿到 5 档清晰度；默认探针读到 1080P、总长 15,064,141 字节、
首尾字节可读、`ftyp` 头正确。

**抓包环境备忘**（下次直接复用）：Android 14+ 的系统证书在
`/apex/com.android.conscrypt/cacerts`，必须在该目录对 **zygote 的 mount namespace** 做 tmpfs 覆盖
（`nsenter -t <zygote> -m -- mount -t tmpfs ...`），并把目录 chcon 成
`u:object_r:system_security_cacerts_file:s0`，否则 untrusted_app 读不到证书、握手一律失败。
红果有反调试，Java 层 hook 会让进程直接退出，只能靠 MITM。

## 2026-11-03 本地设备身份文件（免注册播放）

### 背景

播放接口的放行额度绑在服务端的设备历史上：新注册的身份基本只放行一次，服务端认过的老身份不限次。
既然伪造不出来，就提供一条「直接用真实设备身份」的路：用户把自己设备上的身份填进配置文件，
程序读它就完全不注册。

### 实测对照

同一份请求形态（完整签名 + hl 域），只换身份：

| 身份 | 第 1 次 | 第 2 次 | 第 3 次 |
| --- | --- | --- | --- |
| 红果真实设备身份 + 完整 Cookie（含 `odin_tt`） | 26032 B | 26026 B | — |
| 红果真实设备身份 + 只有基础 Cookie | 26165 B | — | — |
| 新注册的匿名身份 | 26081 B | 0 | 0 |

`odin_tt` / `passport_csrf_token` 不是必要条件（去掉照样成功），差异纯粹在 `device_id` 这套身份
本身。另测：补全官方请求头与缺的参数、请求体 gzip、把注册版本号对齐到 72932、间隔 90 秒再试，
都不能让新身份播第二次。

### 用法

配置文件是纯文本，一行一个 `键=值`，`#` 后为注释，键名不区分大小写（下面三个值都是占位符，
换成自己设备上的真实值）：

```
device_id=7000000000000000001
install_id=7000000000000000002
cdid=3b1f5c2d-8a4e-4f60-9c71-2d3e4a5b6c7d
```

放置位置：

* **Android TV**：`/sdcard/hgtv/device-identity.txt`
  （`adb push device-identity.txt /sdcard/hgtv/`，也可以直接用文件管理器复制进去）
* **Windows**：程序目录下的 `device-identity.txt`，或 `%USERPROFILE%\.hgtv\device-identity.txt`

TV 端按下面的顺序找文件，第一个能解析成功的生效（见 `MainActivity.onCreate`）：

| 顺序 | 路径 | 是否要额外授权 |
| --- | --- | --- |
| 1 | `/sdcard/hgtv/device-identity.txt` | Android 11+ 要「所有文件访问」，见下 |
| 2 | `/sdcard/Android/media/io.github.wuzuo.hgtv/device-identity.txt` | 不需要，任何版本都能读 |
| 3 | `/sdcard/Android/data/io.github.wuzuo.hgtv/files/device-identity.txt` | 不需要，但文件管理器进不去 |
| 4 | 应用内部 `files/device-identity.txt` | 不需要，`run-as` 才能写入 |

第 1 项是首选，因为位置最好找。但 `targetSdk 36` 的分区存储会把共享目录对应用静默隐藏：
文件明明在，`File.isFile` 也返回 false，既不报错也不抛异常。要在 Android 11+ 上真正读到它，
装完之后执行一次：

```
adb shell appops set io.github.wuzuo.hgtv MANAGE_EXTERNAL_STORAGE allow
```

或者手动：设置 → 应用 → HGTV → 特殊应用权限 → 所有文件访问。Android 10 及以下靠
`requestLegacyExternalStorage` + `READ_EXTERNAL_STORAGE` 直接可读，不需要这条命令。

不想授权就用第 2 项，一样能直接复制（MTP 和文件管理器都看得到 `Android/media`），只是路径长一点。

从官方客户端取值（手机需 root 或可 `adb root`）：

```
/data/data/com.phoenix.read/shared_prefs/applog_stats.xml          → <string name="device_id"> / <string name="install_id">
/data/data/com.phoenix.read/shared_prefs/com.ss.android.deviceregister.utils.Cdid.xml → <string name="cdid">
```

### 实现

* `DeviceIdentityFile`（core）：解析、校验三个值，宽松处理 BOM / CRLF / 引号 / 行尾注释；
  任何一项不合法都返回 `null`，静默走自动注册。`read()` 无参时按「`path` → `fallbacks`」
  依次尝试，第一个解析成功的生效（分区存储挡住共享目录时靠后者兜底）。
* `PlaybackDeviceSession`：
  * 构造参数 `localIdentity` 优先于 `register`，读到配置就不发注册请求；
  * `Profile.trusted` 标记「这是真实身份」；
  * `invalidate()` 命中 trusted 身份时置 `localRejected`，本次进程不再读配置，改走注册兜底。
* `ContentRepository.playbackResponse`：注册身份按「用过就换」处理（用本次调用内的
  `spentInThisCall` 记账，避免刚注册的身份在第一轮就被换掉）；trusted 身份跨播放复用，
  只有它自己拿到空响应才降级。
* 平台入口：`MainActivity.onCreate` 设 Android 的 `path`（`/sdcard/hgtv`）与四个备选，
  `bridge/Main.kt` 设 Windows 的「程序目录 → `~/.hgtv`」。
* `AndroidManifest.xml`：为第 1 个路径加 `MANAGE_EXTERNAL_STORAGE`、
  `READ_EXTERNAL_STORAGE`（`maxSdkVersion=32`）与 `requestLegacyExternalStorage`。
  拿不到权限时上面的回退链照常工作，不弹窗、不报错。

### 验证

* 有配置连播三集：`qualities=[360, 480, 540, 720, 1080] × 3`，`registrations=0`。
  最近一次用真机导出的配置复验：`ep=737112 / 346328 / 689816`，三集都是 5 档。
* 无配置连播三集：同样三集都能播，`registrations=1/2/3`（每集一次）。
* 单测：`DeviceIdentityFileTest` 8 例（含「首选读不到时回退到备选」「首选优先于备选」）、
  `PlaybackDeviceSessionTest` 新增 2 例、`ContentRepositoryTest` 新增 2 例；
  core 全量仅剩已知的 Windows 原子重命名失败。

### 风险说明

* 同一份身份不要和官方客户端**同时**播放：两边共用一个 `device_id`，并发请求会被判设备异常。
* 这**不是**给所有人共用一套身份的方案。多 IP 共用同一个 `device_id` 是典型的共享账号特征，
  被封之后该身份对所有人生效（包括官方 App），所以只适合一人一份、各自填自己的。
* 长期不用的身份会不会被服务端降级，没有数据。真降级了会自动退回注册，功能不受影响。

## 2026-11-03 列表卡片副标题：集数让位给热度

### 现象

找剧、收藏、历史、预约四页的卡片，海报角标已经写了「共 N 集」，下面那行又写一遍。
历史页更明显：副标题是两行（集数 + 进度），卡片因此比别的页高；聚焦时描边还会缺一角。

### 热度从哪来

| 接口 | 字段 | 形如 |
| --- | --- | --- |
| 网页搜索 `api/search/result` | `video_data.hot_score_data.text` | `4597万热度` |
| 找剧筛选 `distribution/category/landpage/v` | `sub_title_list` 里 `data_type == 27` 的那项 | `1335万`（正式客户端给它配火焰图标） |
| 排行榜 `heatText` | 本来就有 | `9667万热度` |

找剧接口没有 `hot_score_data`，只能从 `sub_title_list` 里挑 `data_type == 27`，再补上「热度」，
这样三处的文案才统一。`Series` 因此新增 `heat` 字段，跟着 `toJson` / `fromJson` 进本地缓存；
取不到时为空串，列表回退到原来的 `badge`。

### 改动

* `ContinuousCatalogView` 卡片副标题：榜单 `position.heat` → `item.heat` → `item.badge`。
* `catalogGrid` 副标题统一单行：收藏 / 历史 / 预约写进度，排行榜写热度，找剧与首页写 `item.heat`。
* `EpisodeSummary.label` 增加 `withCount`；为 false 时只返回进度那一行（待上线写成
  「待上线，暂不可观看」），默认行为不变。
* 卡片副标题固定 `maxLines = minLines = 1`、`minHeight = 19dp`，各页卡片高度才对得齐。
* `revealFocusedRow` 与键盘导航的滚动各预留 12dp：卡片聚焦会放大 1.02 并带一圈光晕，
  按未放大的行边界贴边滚动，历史页这种矮视口就会把描边切掉一边。

### 验证

真机（Android 15，debug 包临时加 `.verify` 后缀避免覆盖正式版）逐页截图：
找剧六张卡分别是「4799万热度 / 2010万热度 / 4054万热度 / 2180万热度 / 4435万热度 / 1368万热度」，
海报角标仍是「共 12 集」；历史页「看到第 1 集 · 00:23」单行，放大后四条描边闭合；
排行榜不受影响（「第 1 名」+「共 102 集」+「9667万热度」）。

## 2026-11-04 设备身份配置的读取 / 失效提示

### 背景

配置生效与否原本完全静默：读到了不说，播放中失效也不说，用户只能从「这次要不要多注册一次」的
间接现象去猜。现在 TV 与 Windows 两端都在两个时刻明确提示一次。

### 事件

`DeviceIdentityMonitor`（core）是唯一状态来源：

| 事件 | 触发点 | 默认文案 |
| --- | --- | --- |
| `Loaded` | 启动主动探测，或首次播放时读到合法配置 | 已读取设备身份配置（device_id 尾号 xxxx），播放将跳过注册 |
| `Rejected` | trusted 身份拿到空响应，`PlaybackDeviceSession.invalidate()` 命中 | 设备身份配置已失效，已自动改为注册设备 |

* 同一份配置只提示一次；换文件或换值才会再次 `Loaded`。
* `Rejected` 每个进程只报一次，避免每次播放失败都弹。
* 没有配置不提示——那是正常的自动注册路径，不是错误。

### 平台接入

* **TV**：`MainActivity.onCreate` 先 `addListener`，再 `root.post { DeviceIdentityMonitor.resolve() }`
  主动探一次；事件内用 `Toast.LENGTH_LONG`。`onDestroy` 摘监听，避免 Activity 泄漏。
* **Windows**：bridge 启动时 `DeviceIdentityMonitor.resolve()`，新增 `GET /identity` 返回
  `{configured, deviceId, file, rejected}`；Flutter 启动查一次、之后每 5 秒轮询（失效发生在后台，
  没有回调可挂），底部状态栏常驻显示，状态变化时用 SnackBar 提示一次。

### 验证

* core：`DeviceIdentityMonitorTest` 5 例——读一次只报一次、无配置不报、失效只报一次、
  trusted 身份被 `invalidate` 时上报、注册身份被换掉不误报。
* bridge：`installDist` 后手工起服务，`/identity` 分别返回
  `{"configured":true,...,"deviceId":"111"}` 与 `{"configured":false,...,"deviceId":null}`，均 200。
* `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`、`flutter analyze`、
  `flutter test` 全通过（core 仅既有的 Windows 原子重命名用例失败，与本次无关）。

## 2026-11-04 设备身份配置读不到时要说清原因

### 现象

用户把 `device-identity.txt` 放进 `/sdcard/hgtv/`，App 却表现得像「没有配置」：不提示、照常
自动注册，也没有任何错误。查下来 `appops get <pkg> MANAGE_EXTERNAL_STORAGE` 是 `default`
（且有过一次 `rejectTime`）——**没给存储权限**。

### 为什么完全无声

Android 11+ 的分区存储对没有「所有文件访问」权限的应用，会把 `/sdcard/` 下的共享目录整体隐藏：
`File.isFile()` 直接返回 `false`，不抛异常、不写日志。所以程序没法区分「没放文件」和「放了但被挡」，
只能按「没配置」处理。

更麻烦的是 Android 6–10 这条老路：manifest 里声明了 `READ_EXTERNAL_STORAGE`，但代码从来没有
在运行时申请过，所以那些设备上同样读不到。

### 改法

* `IdentityStatus`（`DeviceIdentityAccess.kt`，app 层）把状态收敛成三种：`Ready` / `NoPermission` /
  `NotConfigured`，判定逻辑抽成纯函数 `resolveIdentityStatus(loaded, permitted)` 以便单测。
  `hasAllFilesAccess(context)` 按版本分三段：≥30 看 `Environment.isExternalStorageManager()`，
  23–29 看 `READ_EXTERNAL_STORAGE` 运行时授权，<23 安装即授予。
* 首次启动若没权限，弹一次说明（只一次，`SharedPreferences` 记 `storage_prompted`）：
  11+ 跳 `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`（失败回退到应用列表页），
  6–10 直接 `requestPermissions`。
* `onResume` 里重新 `DeviceIdentityMonitor.resolve()`：从系统设置开完权限回来，原本被隐藏的文件
  这时才读得到，提示与设置页文案同步更新（Monitor 内部按配置内容去重，不会重复弹提示）。
* 设置页「应用与更新」列新增一行「设备身份配置 · 已读取／需授权／未放置」，点进去：已读到的看
  文件路径与三个字段，缺权限的给开启入口，没配置的讲怎么放。这一行是单行省略的，所以状态词
  控制在四个字以内，具体尾号放在详情里。

### 验证

* `DeviceIdentityStatusTest` 3 例：读到配置即为 Ready（即使没权限，因为备选目录不需要权限）、
  有权限无配置为 NotConfigured、无权限无配置必须是 NoPermission（不能误报成「没配置」）。
* 模拟器（Android TV / SDK 36）实测：撤销 `MANAGE_EXTERNAL_STORAGE` 后启动 → 弹「读取设备身份配置」
  对话框且焦点在「去开启」；确认键跳到 `AllFilesAccessActivity`；授权返回 → 浮层提示
  「已读取设备身份配置（device_id 尾号 7289）」；设置页该行显示「已读取」。
* `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug` 全通过。

## 2026-11-04 Windows 端把配置放在程序目录却读不到

### 现象

用户把 `device-identity.txt` 放到 `D:/Program Files\HGTV\`（`hgtv_windows.exe` 旁边），
Windows 客户端底部状态栏没有任何「已读取」提示，看上去像功能没生效。

### 原因

bridge 是用 `Process.start(..., workingDirectory: bridgeDir)` 起的，工作目录是
`<程序目录>\bridge`；而 bridge 里写的是相对路径 `File("device-identity.txt")`，
于是实际去找 `<程序目录>\bridge\device-identity.txt`。用户放的位置比它高了一级，
读不到就按「没有配置」处理，而「没有配置」按设计是不提示的（自动注册是正常路径），
所以表现成完全静默。文档写的是「程序目录」，实现读的却是 `bridge` 子目录，本身也不一致。

### 改法

* Flutter 启动 bridge 时通过 `environment: {'HGTV_APP_ROOT': root}` 把程序根目录传下去
  （`root` 就是 `hgtv_windows.exe` 所在目录；`flutter run` 时是仓库根）。
* bridge 用 `HGTV_APP_ROOT` 拼首选路径，`fallbacks` 保留本进程工作目录
  （打包布局下即 `<程序目录>\bridge`，兼容已经这么放的用户）与 `%USERPROFILE%\.hgtv\`。
* Windows 设置页新增「设备身份」区块：显示「已读取（尾号 xxxx）／未放置／已失效」、
  实际读取到的文件路径、以及没配置时该放到哪；两个按钮分别是「打开所在文件夹」
  （已读取时）与「如何配置」。状态每 5 秒随轮询刷新，`AppSession` 里补了结构化的
  `identityConfigured/identityFile/identityRejected`，状态栏仍然只显示一句话。

### 验证

* 用安装目录的 `runtime\bin\java.exe` 手工起 bridge，`/identity` 四种摆放依次验证：
  程序目录有文件（`configured:true`，`file` 指向程序目录）、只有 `bridge` 目录有文件
  （`configured:true`，兼容旧放法）、两处都没有（`configured:false`）、
  程序目录空但 `bridge` 有（回退成功）。
* `flutter analyze` 无问题，`flutter test` 19 过 1 跳过（跳过的是需要真 bridge 的那条）。

## 2026-11-05 Rokid Station 1080P 花屏与闪退

### 设备情报

Rokid Station 是 Android TV 盒子（Amlogic S905Y4、2GB RAM、1920x1080@240dpi），
画面经 HDMI/DP 输出到 AR 眼镜。官方适配文档给的是 Android 11，零售机跑 Android TV 12。

### 判断

* 花屏只出现在部分剧的 1080P 上，且会连带进程直接消失 —— 这是解码器/VPU 层的典型表现：
  原生崩溃不经过 Java，界面留不下错误面板，用户看到的就是「闪退」。
* 播放链路（选流、解密、DataBuffer）是全局共用的，如果读错数据，所有清晰度都会花，
  不会只挑 1080P。所以先按「设备端叠加层 + 解码器」处理，不动解密。
* 应用侧能做的是两件事：换一条画面输出路径，以及把崩溃现场留下来。

### 改法

* `PlaybackCompat`：设置页与播放内的「播放设置」都能切「画面输出」。
  默认 `SurfaceView`（硬件叠加层，最省电）；兼容档改用 `TextureView`（GPU 合成）。
  media3 的 `surface_type` 只在 `PlayerView` 构造时读一次、没有 setter，所以兼容档从
  `res/layout/player_texture_view.xml` inflate 出来。
* `PlaybackDiagnostics`：进播放前写 `filesDir/playback-active.txt`，正常退出/离开前台时删掉；
  下次启动如果标记还在，就说明上一次是播放途中没的，补记一条「播放中异常中断」并
  **自动切到兼容档**（Toast 明说可以改回去）。另外注册兜底的未捕获异常处理器，
  把 Java 崩溃栈也写进同一份报告。设置页「播放诊断」可查看/复制，不联网。
* 判定「异常中断」必须只在应用可见时成立，否则后台被系统回收会被误判成闪退，
  所以 `onStop` 也清标记。

### 验证

* `PlaybackDiagnosticsTest` 6 条：异常中断只补记一次、正常退出不留记录、Java 崩溃入档、
  报告带设备与输出方式、开关持久化、兼容布局产出的确实是 `TextureView`。
* 模拟器（hgtv-tv AVD）实跑：设置页多出「画面输出」与「播放诊断」两行，切换后
  `playback-compat-v1.xml` 落盘 `textureView=true`，诊断弹窗显示设备/输出/记录数。
* 真机效果无法在本机验证：花屏是否消失、1080P 是否还会崩，只能在 Rokid Station 上实测。

## 整集只有 bytevc2 档位时补一次兼容模型（2026-11-05）

另一条开发线（`wuzuo/hongguoTV` 的 issue #56）实测：`fallback_api` 返回的 `video_list` 若
全部是 `bytevc2`/`bvc2`，原来会被整档过滤掉，客户端只剩「没有电视可播放的清晰度」，某些剧集
（如《我在古代造酒竟成了国师3D版第十二季》第 71、72 集）完全打不开。官方播放器里有
`force_codec` 与 `codec_type=0` 的兼容分支，对照测试用 `codec_type=0` + `force_codec=h264`
能把供给换成 `hvc1`（1254×720）。

本轮只移植这一处兼容请求，条件收得很窄：仅当 `fallback` 的 `video_list` 非空、每条都有
`main_url` 且编码全是 `bytevc2`/`bvc2` 时，才在原地址上追加这两个参数再读一次模型。
已有可用档位、编码未知、格式异常都不触发；不循环尝试，不改接口、设备身份与解密流程；
换成什么都仍按实际 `codec_type` 过滤、按设备能力选档。参数名不代表返回内容，因此不假设
一定拿到 H.264。

回归：`allByteVc2SourceReadsOneCompatibleModel`（先全 bytevc2、兼容请求回 `bytevc1`，
断言请求序列 `[null, "h264"]` 且最终选到该档）、`playableSourceDoesNotReadTheCompatibleModel`
（有可用档位时只读一次模型）。原 `entirelyIncompatibleSourceFailsBeforeStartingAudio`
仍成立：兼容请求再拿到全 bytevc2 时照旧报错，不会退化成只播音频。

未验证：本机出口不能判断真实播放效果，改动是否覆盖到具体剧集需要在电视端实测。

## 首页推荐与账号的一批线上修复移植（2026-11-05）

来源是另一条开发线（`wuzuo/hongguoTV` main）在 2026-11-03 至 11-04 的八个提交。两个仓库没有
共同祖先（本仓是 `git archive` 重建的历史），只能逐条按功能重写，不能 merge。

### 1. 登录后推荐 110 ILLEGAL_ACCESS
带账号 Cookie 时再附加 `device_id`/`iid`，feed 直接回 `110`；只带 Cookie 返回 `0`。根因是登录
用的固定设备号在会话已代表身份时反而被判非法访问。`RecommendationFeed` 的账号分支不再追加这
两个参数（分类推荐共用同一份参数，一并生效）。

### 2. 推荐失败时换路与丢弃旧会话
每次重试不再固定打 `sinfonlinea`，改为按 `sinfonlinea → hl → lf` 轮换同族域名；本地已有
`session` 而响应非 0 码时，清空 `session`/`offset` 从头拉一次（仍最多三次），避免分页会话
被判无效后只能等下次刷新。注意这与播放接口的「换路」策略同源，但不改设备身份。

### 3. 首页 offset 按条推进
服务端 offset 的单位是「条」而不是「页」：一次返回 6 条却只前进 1，会让每页与上一页重叠 5 条。
改为按本次响应实际下发的条数（`rawVideoCount`，统计 `video_data` 原始行数，不是去重后的条目）
前进，分类推荐继续使用服务端 `next_offset`。回归里 fixture 每页 2 行，断言请求 offset 序列为
`0 → 2 → 4`。

### 4. 首页续刷（冷启动接着上次）
`RecommendationCursor` 增加 `snapshot()` / `restore()` / `hasProgress` / `markSeen()`，并给
`seen` 单独加锁（界面线程展示记录与后台分页共用集合，不能让界面等分页的网络锁）。快照只含
`session`、`offset` 与最近 540 个已看 ID，不含任何凭据，超过 4096 字符的 session 或越界 offset
一律拒绝恢复。

`FeedStartupCache` 增加 `cursor:<source>`（64 KiB 上限，`commit()` 同步落盘——刷几部就强退时
`apply()` 的异步写会丢）。`PublicFeedBackend.page()` 在冷启动且游标无进度时先恢复快照，恢复成功
就跳过首屏缓存直接续拉；每次取页后回写快照。`noteDisplayed()` 由 `HomeFeedScreen.loadCurrent()`
与预加载直切两处调用，把展示过的作品写进 seen，下次冷启动在 parse 阶段就被过滤掉。

账号时序：`AccountController` 记录 `accountRestored` / `accountRestoreFailed`，新增
`recommendationLoading`；身份恢复与云端校验结束前 `recommendationStartupCache()` 返回 null，首页
改为显示「正在恢复账号推荐…」暂不创建，避免临时 guest 后端按单一 owner 隔离把账号游标清掉。
身份字符串带上「是否已确认」，恢复完成的瞬间就会触发首页重建。

### 5. 账号未建档
`biz_user_id` 为空说明该手机号从未在官方 App 侧建立账号资料，与登录链路无关。新增
`AccountNotProvisionedException`，提示改为「请先用官方红果 App 登录一次（或改用扫码）」，不再
让用户反复重试登录。

### Windows 端
`bridge` 直接引用 `core`，上述 1–3 与 5 随 core.jar 自动生效。第 4 项的落盘在 bridge 里补：
启动时从 `feed-cursor.json`（程序目录，缺省 `~/.hgtv/`）恢复游标，每次取页后回写；同时去掉
「page=1 且非 refresh 就把游标重置成新的」那行，否则刚恢复的快照会被立刻丢掉。Windows 界面
的「换一批」仍走 `refresh=true`，语义不变。

### 验证
`RecommendationFeedTest` 新增 `staleSessionErrorDropsCursorAndRotatesHost`（110 → 换域 → offset 0）、
`snapshotKeepsSessionOffsetAndSeenWithoutCredentialFields`、`malformedSnapshotIsRejectedAndLeavesCursorUntouched`；
`FeedStartupCacheTest` 新增游标身份隔离与「冷启动续刷优先于首屏缓存」两条。core 251 项仅剩既有的
Windows 原子重命名失败，app 单测、assembleDebug、lintDebug 与 bridge 测试通过。
未验证：真实账号下的 110 是否彻底消失、续刷在真机冷启动的表现，本机只能验证请求构造与落盘。

## 首页顶部「继续观看」（2026-11-05）

首页（tab 0 的试播流）正文最上方新增一条「继续观看」入口：显示本机最近一条观看记录的剧名、
集数与进度，按确定直接续播那一集。整集已看完时文案改为「接着看下一集」，`openDetail(resume =
true)` 会顺延到下一集；离开前先 `remember()` 并暂停试播，与「观看全集」走同一条路，避免这一屏
的试播进度丢失。

只取一条：`library.history()` 由 `LibraryIndex` 按 `updatedAt` 倒序，第一条就是最近的那集。
`library.isLoaded` 为 false 时返回 null，避免 `checkNotNull` 崩在启动早期。

焦点：在画面上按「右」直接到这一条；它的「上」接当前选中的分类按钮（推荐 / 漫剧 / 真人剧），
「下」「左」回画面。没有记录时整行 `GONE`，同时把画面的「右」指回自己——否则焦点会落到一个
看不见的控件上，按右没反应。

布局：操作栏（暂停试播 / 观看全集 / 账户收藏）展开时右栏只剩约 300dp，这一行临时收起，
`back()` 收起操作栏时再放回来，避免把最后一行按键提示挤出卡片。

回归：`HomeFeedScreenTest` 新增三条（点击后暂停试播并传出、无记录时不占位且不抢焦点、
操作栏展开时让位并可还原）；`HomeResumeTest` 三条（取最新一条、已看完写「接着看下一集」、
空历史返回 null）。app 单测、assembleDebug 与 lintDebug 通过，core 仅剩既有的 Windows
原子重命名失败。

未验证：真机 720P 下右栏高度是否够、遥控器焦点手感。本机只能验证视图状态与文案。

## Windows 首页「继续观看」与本机页顺序（2026-11-05）

TV 端首页顶部那条「继续观看」在 Windows 侧同步了一份。Windows 没有遥控器焦点概念，做法更直接：
首页标题行下方一条卡片，封面 + 剧名 + 「第 N 集 · mm:ss」+ 进度条，右侧一个「继续观看」按钮，
点卡片或按钮都直达播放器。

- 数据来源是 `LocalStore.history`，全部在本地 `library.json`，所以推荐流加载中、加载失败都照样
  能用——这块放在推荐列表的 `Expanded` 之外，不参与 `_loading` 分支。
- 取哪一条由 `latestHistory()`（`models.dart`）决定：按 `updatedAt` 取最大值，而不是直接用
  `history.first`。写入路径确实是插到头部，但旧文件、手工改过的顺序、跨客户端同步来的
  `library.json` 都不保证，按时间取更稳；`updatedAt` 全为 0 的旧数据退化成第一条。
- 文案由 `WatchRecord.resumeWhere` 给出：未看完是「第 N 集 · mm:ss」，整集看完改成
  「接着看下一集」，不写 00:00。集号统一从 1 开始。`clock` / `progress` 一并抽成 getter，
  `library_screen` 的进度条改用 `record.progress`，负位置按 0 处理。
- 点进去之后不再让用户点第二次：`DetailScreen` 新增 `autoplay`，详情一加载完就
  `_openPlayer(_resumeIndex(detail))`。`_resumeIndex` 复用详情页原有逻辑——找到上次那一集，
  已看完顺延下一集，找不到就第 1 集。用 `_autoplayHandled` 只放行一次，从播放器返回后停在详情页；
  加载期间文案是「正在打开上次看到的位置…」，不是空白转圈。
- 本机页（侧边栏「本机」）Tab 顺序按使用频率调换：**观看记录在前、本机收藏在后**，
  两栏的计数也改成「记录 N · 收藏 M」，与 Tab 顺序对应。

回归：新增 `test/resume_test.dart` 6 条（按更新时间取最近、已看完指向下一集、空历史返回 null、
旧数据无时间戳退回第一条、进度夹在 0..1、负位置不出负时间）。`flutter analyze` 无问题，
`flutter test` 25 项通过（`live_bridge_test` 需要环境变量，照常跳过）。

未验证：真机点一下到出画面的实际衔接（详情加载 + 取流两段网络）、卡片在窄窗口下的排布。
本机只能验证数据选取与文案。

## Rokid Station 播放实测与失败处理补齐（2026-11-06）

USB 连上 `1401082421003996`（Rokid_Station / Android 11 / 256MB 堆）实地跑了一轮，release
构建没有 root、`run-as` 不可用，只能靠界面遥控（`uiautomator dump` 读焦点）改设置。

### 实测结论

- 把「画面输出」切回**默认（硬件叠加）**，从首页「继续观看」起播，自动连播 137→148 共 12 集，
  外加 3 轮连续快进/快退（每轮 8 次右键 + 3 次左键）。**进程全程未崩**，
  `dumpsys activity exit-info` 仍是 16 条，今天 0 新增（15 次原生崩溃仍全部落在
  11-04 21:33–22:40）。
- 叠加层下 `screencap` 全黑：画面走硬件 plane、不经 GPU 合成，所以截不到；切到兼容档后
  同一集能截到干净画面。两条渲染路径确实不同，但要判断**花屏**只能靠人眼看眼镜。
- `gralloc4: Buffer descriptor with invalid usage bits 0x2000` 在两种模式下都刷
  （叠加 410 次/9 分钟，兼容 19 次/20 秒），与渲染路径无关，**排除**为花屏诱因。
- 流声明 `hvc1.1.6.L186`（HEVC level 6.2）、`maxSubLayers=2`、1924x1080@30，Media3 报
  `Format exceeds selected codec's capabilities` 仍继续解，而 SurfaceFlinger 显示稳定 30fps
  出帧——声明档次高于设备能力**不是**这次崩溃的充分条件，不必据此降档。
- `esdserver`（眼镜 USB/显示链路 daemon，root）在 08:23–08:30 崩了 3 次，栈一致
  （`libusb_open` 对已销毁互斥量加锁）；08:39–08:50 实测期间未再崩，与播放无重叠。
- 0.1.21 与现在渲染面实现一致（都是 media3 `PlayerView` 默认 SurfaceView），所以
  「当时必崩、现在稳定」无法用代码差异解释，更可能是当时的链路/剧集条件。

### 压力测试中暴露的真实缺陷

连续快进后播放器抛 `ERROR_CODE_UNSPECIFIED`（1000），面板停在「播放器错误…可手动重试」。
自动降档只覆盖 4001–4005，1000 既不学习也不自动重试，用户必须手点一次；实测手点「立即重试」
后能恢复并继续连播。另外 `learnFailure` 只在 Media3 抛解码器错误码时执行，原生崩溃
（进程被直接带走）永远不会被记成「这套解不了」，诊断里的「0 条」因此是必然的。

### 改动

1. `playerFailure`：`ERROR_CODE_UNSPECIFIED` 归入可重试，走既有的 1s/2.5s/5s 三次退避；
   面板照常显示，只是多了一层自动恢复。
2. 每次播放失败（每集只记第一次）写一条「播放器报错」进诊断，带集号/清晰度/编码/尺寸/渲染面，
   下次反馈「卡住/黑屏」时不再完全没有线索。
3. 播放标记额外记录 `avail` / `low` / `heap` / `uptime`：原生崩溃与被系统回收表现一样，
   只有这些字段能把两者分开（处置方向完全不同）。
4. 兼容档已经开着仍然中断时，从标记里解析出 `codec` + `size` 并 `learnFailure`，下次选档避开
   这套组合；解析不出来只提示、不猜。
5. 诊断文案补充「原生崩溃收不到回调、不计入条数，看下方异常中断」，避免「0 条」被误解成没出过事。

回归：`PlaybackDiagnosticsTest` 新增 4 条（标记带内存字段、能从标记取出解码组合、缺字段不猜、
报错进报告且不算异常中断）。app 单测 / assembleDebug / lintDebug 通过，core 251 项仅剩既有的
Windows 原子重命名失败。

未验证：花屏本身（只能人眼看）；15 次崩溃的 tombstone 依旧拿不到（user 构建，`/data/tombstones`
不可读、dropbox 已清），设备上留了 `/sdcard/hgtv-crash.log` 与 `/sdcard/hgtv-main.log` 两个落盘
采集（重启后采集进程会消失，需要重新起）。

## 播放时 GC 过密导致 native 崩溃（2026-11-06）

### 怎么抓到栈的

user 构建读不到 `/data/tombstones`，但 `dumpsys dropbox --print <日期> <时间> SYSTEM_TOMBSTONE`
能打印完整墓碑（含 backtrace、寄存器、fault addr）。dropbox 只留最近几条，所以现场必须当场取：
设备上常驻 `scripts/device-tombstone-crawler.sh`（写进 `/sdcard/hgtv-tombstones.txt`）就不用手守。

另一个坑：`dumpsys activity exit-info <包名>` 会漏条目，必须全量拉取后按 `process=` 过滤。

### 现场

《莞城往事》1080P + 硬件叠加，0.1.23，连续切集一分钟内复现，共三次：

- `ExoPlayer:Loade` → `ThrowClassCastException` → `Class::GetDescriptor` 空指针
- `HeapTaskDaemon` → `ConcurrentCopying::MarkingPhase` → `VisitReferences` 空指针（`fault addr 0x44`）
- 第三次与第二次 PC 完全相同

单集静置播放 60 秒不崩，只有「切集」这个动作能稳定触发。三次 pss 71–88MB，不是 OOM。
后两次落在同一地址，说明是确定性路径；第一次的 ClassCastException 是 GC 搬对象时 JIT 读到旧指针
的连带现象——三种崩点都在 ART 内部，不在解码器、不在渲染面。

### 根因

GC 日志（`logcat | grep "concurrent copying GC"`）显示播放时每秒 1–2 次、单次回收
14–20 万个对象（5–9MB）、暂停 126–380ms。三成以上 CPU 时间在 GC。

垃圾来自解密链路，每次 32KiB 读取约 430KB：

- `MediaCrypto.decrypt` 先 `data.copyOf()`，每个 sample 再 `copyOfRange` 一次
- CTR 非对齐用 `ByteArray(skip) + 明文` 拼接，再整段 `copyInto` 回去
- 每个 sample 一次 `Cipher.getInstance`（这是最大头，`getInstance` 比 `doFinal` 贵得多）、
  一次 `BigInteger` 计数器、两个新数组

### 改动

1. `MediaCrypto.decryptInto(source, sourceOffset, length, start, key, prepared, target, targetOffset)`：
   解密结果直接写进调用方缓冲区，`source` 保持原样（加密数据要原封不动地当 keystream 的输入），
   header 只覆盖重叠的那一段。`decrypt()` 退化为分配一次目标的薄封装。
2. CTR 计数器改成 16 字节大端整型加法，同样按 2^128 取模；Cipher 与 IV/滑槽缓冲按线程复用。
3. 跨块 `skip` 用「先烧掉 keystream 前 skip 字节」代替补零拼接（与 media3 `AesFlushingCipher`
   的做法一致）。Android 的 Conscrypt 对 CTR/NoPadding 的 `update` 是流式的，返回长度等于输入长度。
4. `RemoteVideo.readInto` 直达缓冲区；`read` 变成薄封装；`openRead` 去掉 `scratch.copyOf(n)`。
5. `VideoDataSource.read` 走 `readInto`。

实测（JVM，52 个 sample / 32KiB 读取）：439344 → 5880 字节。

### 验证

`decryptInto` 与旧实现逐字节等价，新增三组用例：所有非对齐窗口（目标缓冲带守卫字节，确认只写
请求范围）、子样本 `encryptedOffset` + 计数器回绕（IV 全 `0xff`）、4096 字节多样本扫描。
`readInto` 与 `read` 等价，覆盖缓存前缀跨界。

core 254 项、app 全量通过；仅剩既有的 Windows 原子重命名失败（本机 `File.renameTo` 不能覆盖）。

未验证：真机播放。本机签不了名，只能走 CI 出 0.1.24 的包再验——重点看 GC 频率是否从
每秒 1–2 次降到偶发，以及连续切集是否还崩。
