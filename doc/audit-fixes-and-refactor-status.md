# 安全审计整改与架构迁移状态（跟踪文档）

> 本文件汇总 TVBoxOS-Mobile 三轮整改（安全/性能审计 + 模块边界路线图 改进.txt）
> 的落地状态、关键改动点与真机回归矩阵。代码级验证：Debug/Release 双变体 BUILD SUCCESSFUL。

## 1. 已落地改动总览

### 1.1 局域网 HTTP 服务（RemoteServer / ControlManager）
- 默认仅绑定 `127.0.0.1`：订阅/本地播放/代理等回环功能不受影响；局域网可达需
  `HawkConfig.LAN_SERVER_ENABLE = true`（设置页新增“局域网服务”开关，重启应用生效）。
- 管理令牌：每次进程启动随机生成（`accessToken`），web 控制台经 `/token.js` 注入
  `window.TVBOX_TOKEN`，所有 AJAX 自动携带 `X-TVBox-Token`；`/token.js` 禁止缓存。
- 鉴权范围：`/upload`、`/newFolder`、`/delFolder`、`/delFile`、`/action`、目录列表须令牌（回环放行）；
  `/proxy`、`/m3u8`、`/dns-query` 仅本机回环。
- 路径安全：`resolveUnderRoot()` 拒绝 `..`/绝对路径/NUL/反斜杠分隔并做 canonical 根目录包含性校验；
  拒绝删除外部存储根；ZIP 解压逐条目 canonical 包含性校验（Zip Slip），`ZipFile`/流全部 try-with-resources。
- 越界修复：`/proxy` 返回数组按 `length>=3` 且 `rs[2] instanceof InputStream` 校验后再读。
- 文件：`app/.../server/RemoteServer.java`、`ControlManager.java`、`InputRequestProcess.java`、
  `res/raw/{index.html,script.js}`。

### 1.2 TLS 与证书策略
- 移除所有“恒真 HostnameVerifier”（OkGoHelper / App / spider OkHttp / SSLCompat.VERIFIER）。
- WebView `onReceivedSslError` 默认 `cancel()`，仅当 `HawkConfig.IGNORE_SSL_ERROR=true`（默认 false）放行。
  覆盖点：PlayFragment、PlayParseHelper、WebSniffResolver。
- 设置页新增“忽略证书错误”开关（默认 OFF；WebView 即时生效，OkHttp 网络请求重启应用后按新值重建客户端生效）。
- 文件：`common/.../util/OkGoHelper.java`、`common/.../net/SSLCompat.java`、
  `spider/.../net/OkHttp.java`、`app/.../base/App.java`、`util/WebSniffResolver.java`、
  `util/player/PlayParseHelper.java`、`ui/fragment/PlayFragment.java`。

### 1.3 明确崩溃点修复（判空/越界）
- ApiConfig `getIJKCodec`：离线/空列表不再 `ijkCodes.get(0)` NPE（改从非空列表取，空列表返回 null 由调用方防御）。
- IjkMediaPlayer.setOptions：codec 判空后再取 option。
- InputRequestProcess：word/url 参数缺失判空。
- CustomWebReceiver：intent/action/extras 判空。
- PlayService：`videoInfo.split("&&")` 越界回退、静态 videoView 判空。
- PlayFragment/PlayParseHelper/DetailActivity：sourceBean/集合/playIndex/seriesMap 判空与越界回退。

### 1.4 Room 与数据层
- `AppDataManager`：移除 `allowMainThreadQueries()`；所有 DAO 访问经 `runOnDb`（单线程专用执行器，串行化），
  主线程不再执行 SQLite 查询。
- Room schema：`AppDataBase version=2`，新增 `MIGRATION_1_2` 为 vodRecord/vodCollect 建
  `(sourceKey,vodId)`、`updateTime` 索引；log 模块 `LogDatabase version=2` + `MIGRATION_1_2` 为
  `log_entry` 建 `(taskKey,timestamp)`、`(category,timestamp)` 索引。schema 导出：1.json（基线）+2.json。
- `RoomDataManger`：Gson/TypeToken 静态单例复用；删除残留的陈旧 3.json。
- `CacheManager`/`RoomDataManger` 全部调用经 `AppDataManager.runOnDb`。

### 1.5 依赖 / 签名 / 构建
- 升级：Room 2.3.0→2.5.2、Gson 2.8.7→2.10.1、XStream 1.4.15→1.4.20（各模块 build.gradle 同步）。
- XStream 白名单：SourceViewModel 两个 fromXML 前 `NoTypePermission.NONE` + 业务 bean/JDK 包放行。
- 移除零引用/重复依赖：ZXing、Conscrypt、lifecycle-extensions（改为显式 viewmodel/livedata/runtime-ktx 2.6.2）、
  app 层重复 retrofuture（spider 层保留）、Glide（调用点统一到 Picasso 后移除，含 4 个文件残留 import 清理）。
- 删除未注册 ExoPlayer/FFmpeg 扩展源码（`player/src/main/java/com/google/android/exoplayer2/ext/ffmpeg`、
  `tv/danmaku/ijk/media/player/ffmpeg`）。
- Manifest：两个广播 Receiver `exported=false`；`allowBackup=false`(+`tools:replace`)；
  清理 READ_PHONE_STATE/GET_TASKS/ACCESS_FINE_LOCATION/REQUEST_INSTALL_PACKAGES 等无用权限；
  READ/WRITE_EXTERNAL_STORAGE 限 maxSdk 32/29。
- 签名：`TVBoxOSC.jks` 移出版本控制（`git rm --cached`），`.gitignore` 收编 `*.jks/keystore.properties`；
  `app/build.gradle` 从环境变量(`KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD`)或
  `app/keystore.properties` 注入，缺失自动回退 debug 签名。
  ⚠️ 若仓库公开：旧 keystore+口令曾入库，需更换密钥并清理 git 历史；CI 请改用 secrets。
- gradle.properties：`org.gradle.parallel=true`、`org.gradle.caching=true`；app 默认 `resConfigs 'zh-rCN','zh'`。

### 1.6 启动与运行性能
- 播放器缓存清理：`App.onCreate` 移除主线程递归删除 → `schedulePlayerCacheCleanup()`（延迟 5s、后台低优先级线程、
  `FileUtils.cleanPlayerCacheIfOverflow(100MB)` 阈值）。
- EPG JSON：启动不再解析，`EpgUtil.getEpgInfo` 首次使用懒加载（`init()` synchronized）。
- OkHttp 根统一：`OkGoHelper.newBaseBuilder()` 作为默认/免重定向/Exo 客户端公共根。
- UA.java：约 5400 条 → 47 条去重真实 UA（文件 ~733KB→8KB）。
- 下载进度：`DownloadManager.flushProgress()` 600ms 窗口合并落盘+广播（直链/HLS 分片/合并进度），终态强制落盘。
- 下载页刷新：新增 `DownloadProgressEvent(taskId)` 高频增量；`DownloadEvent` 仅结构性全量。
- 网络事件统一：删除 DownloadScheduler 自注册 ConnectivityManager 回调，改订阅
  SystemStateMonitor TYPE_NETWORK（WIFI→续传；CELLULAR 且非仅WiFi→续传；NONE→不处理）。
- LocalVideoAdapter：convert 内 O(n²) 全量统计移除 → 增量计数（syncSelection/setItemChecked/selectAll/
  cancelAllSelection；BRVAH notifyDataSetChanged 为 final，调用点已接线）。
- GridFragment：单套 RecyclerView+Adapter + 轻量快照栈（数据引用/page/滚动/loadMoreEnd），逐层新建视图移除。

### 1.7 改进.txt（模块边界）第一阶段
- DownloadFacade 补全（enqueue/pause/resume/remove/removeArchiveByPath/removeTasksByPath/getPosterFile/
  ensurePosterAsync/pauseAll/startAll/getTasks…）。
- UI 只走门面：DownloadFragment、DetailActivity、VideoListActivity.kt 的 `DownloadManager.get()` 清零
  （DownloadFragment 保留 MSG_* 常量引用）。

### 1.8 超大类物理拆分（已做部分）
- 新增 `app/.../util/EpisodeDownloadBatch.java`：DetailActivity “整剧批量下载”逻辑整体搬出
  （解析/拼名/统一剧集标识/门面入队/计数），并修复“均已下载/已在任务中”提示分支不可达问题。
- 新增 `app/.../util/DetailQuickSearchHelper.java` 并已接线：DetailActivity 快速搜索编排整体迁移
  （共享线程池 HeavyTaskUtil + epoch 去重/切换词取消、弹窗打开续跑/关闭暂停语义），
  Activity 内不再持有 searchExecutorService/pauseRunnable/quickSearchData 等搜索状态。
- 详情/播放页另有先期拆分的 `util/player/PlayParseHelper.java`（暂未被引用，见“待后续”）。

## 2. 待后续（需真机回归或架构决策）

| 项 | 说明 |
|---|---|
| PlayFragment(~1.8k) 进一步拆分 | 字幕/播放器控制器与宿主深度耦合，无回归环境不强行搬移 |
| `util/player/PlayParseHelper.java`（未引用） | 疑似拆分遗留件，未接入任何调用方；可选择接线或删除 |
| 改进.txt 第二阶段：`:spider-api`/`:core-model`/`:core-network`/`:core-storage` | 需架构拍板；完成后 SourceViewModel 等 `ApiConfig.getCSP` 改走 SpiderService 门面 |
| player-api PlayerFactory 适配器注册 | 已有 PlayerFactory/PlayerApi/PlayOptions 契约，尚未注册 EXO/IJK adapter |
| 局域网服务热切换 | 有意不做：重启应用生效即可（热重启会打断回环播放代理流） |
| web 控制台静态资源(~260KB)精简 | 视觉设计类工作，另行处理 |

## 3. 真机回归矩阵（建议）

1. 详情页→下载整剧（含“全部已下载/已在任务中/解析失败”提示、多选、失败重下、暂停/恢复/删除）。
2. 下载页：进度平滑度（多任务+多分片）、单行刷新不打断长按多选、底部内存栏、完成列表计数。
3. 本地视频列表与下载完成文件列表：进多选/长按/全选/取消全选/删除后计数与删除按钮态。
4. 分类/文件夹 3+ 级下钻返回：滚动位置、加载更多续页、下拉刷新、旋转后列数。
5. 断网→恢复/飞行模式/仅WiFi 开关：下载暂停与自动续传行为。
6. 局域网：默认仅本机；设置开启后重启 → 局域网浏览器可开控制台、目录/上传/删除需令牌；
   未开启时局域网不可达 9978。
7. 自签名/证书错误站点：默认无法加载/播放，开启“忽略证书错误”后可访问。
8. 后台播放 + 通知栏控制；历史/收藏列表新增与删除后的刷新。
9. 冷启动速度（缓存清理不再阻塞主线程）与升级后历史/收藏数据保留（Room 迁移）。
