# 改进.txt 差距审计（gap 清单）

> 生成方式：对照 `改进.txt` 逐项做仓库审计（grep/读码），记录"文档要求 vs 当前状态"。
> 状态图例：✅ 已达标 / ⚠️ 部分 / ❌ 未做。审计日期：见最近提交记录。

## 1. 依赖方向（改进.txt §一/§六）— 大体达标
- ✅ app 内 `getCSP()` 清零；UI 无 `new OkHttpClient.Builder()`。
- ✅ `:core-model` 无 android import；`:download` 不再依赖 `:spider`（只依赖 `:spider-api`）。
- ✅ Gradle `checkModuleDependencies` 门禁 + CI（`.github/workflows/verify.yml`）。
- ⚠️ 仍存在跨层直读（见 §4 穿透点）。

## 2. 第一阶段验收对照（§七·一）
| 项 | 状态 | 现状/残留 |
|---|---|---|
| SourceViewModel 走 SpiderApi | ✅ type3 typed 优先+回退 | type0/1/4 仍在 VM 内走 `HttpClient`+`xml()/json()/sortJson()` 内联解析；VM 内仍大量 EventBus.post。ApiConfig 直读已清零：源注册表/首页源/vip 旗标改经 `spider-api.SourceConfigApi`（`SourceConfigProviders` 注入，ApiConfig 实现契约） |
| DownloadFragment 走 DownloadFacade | ✅ | MSG_* 已并入 Facade;UI 无 `util.DownloadManager`/内部实现 import(门禁含 kt) |
| DetailActivity 不直调 DownloadManager | ✅ | 另：仍直用 `cache.RoomDataManger.getVodInfo`（DAO 泄漏点，见 §4） |
| 注册并使用 PlayerFactory | ⚠️ | 已注册 IJK(1)/Exo(2) adapter + `PlaybackSessions`/`VideoViewPlayerApi` 会话原型；PlayFragment 仍直持 `MyVideoView`/内核，session 仅日志观察 |

## 3. 模块边界（§二/§八）
| 模块 | 状态 | 残留 |
|---|---|---|
| `:core-model` | ✅ | `ParseBean` 仍在 `:spider`（本轮迁移）+ 含行为(getUrl proxy 替换 / mixUrl Base64) → 迁移时纯化 |
| `:core-network` | ⚠️ | 目录/模块名已对齐;配置(SystemConfig/HawkConfig/KeyValueStore)已迁 :core-storage,event/LogEvent 已清;内部仍是杂项袋:`util/{HttpClient,OkGoHelper,AES,MD5,AdBlocker,AppLog,LOG,urlhttp/*}`(改进.txt §2.8 待拆) |
| `:core-storage` | ✅ | data/cache/Repository + **配置归位**：`SystemConfig/HawkConfig` 迁入 `com.github.tvbox.osc.config`，新增 `KeyValueStore`(Hawk 类型安全封装,App 侧业务 Config 均走它);app 无 DAO 直读、UI 经门面读写配置 |
| `:spider-api` / `:spider` | ✅ 试点 | 字符串通道(SpiderContentApi)仍在(过渡兼容)；`ApiConfig` 仍暴露具体 Spider(内部实现需留) |
| `:download` | ✅ | 内部实现已收 `...download.internal` 包(Manager/Scheduler/Executor/Core/Store/Config/Policy/Archive/Notifier/Log/task 全族),公开包仅 Facade+模型/接口;app 零内部实现引用(门禁 java+kt 全查) |
| `:player-api` / `:player` | ⚠️ | 契约 + 原型已接；app 仍直用 `MyVideoView`/IJK/Exo、`PlayerTrackHelper` 按内核 instanceof 分发 |
| `:ui-common` / ui-kit | ⚠️ | ui-common=纯资源 ✅；app 内已建 ui-kit package（6 个纯净组件），通用 View 归拢中 |
| `:playback` / feature-* | ❌ | 未建（改进.txt 第三/四阶段，需真机回归环境） |

## 4. 穿透点（UI/上层直读下层实现，新代码应避免）
- UI 直读 `Hawk`：**UI 层已清零**。直播偏好→`LiveConfig`(含 EPG 只读、频道播放配置覆写);系统级偏好→
  `SystemConfig`;订阅/搜索域→`util.SubscriptionConfig`;用户页热播缓存→`util.HomeHotCache`。剩余裸读写仅在
  装配/封装边界:App.java 订阅默认注入与 putDefault(启动装配)、RemoteTVBox(类内方法封装)、各配置门面内部。
- UI 直触 DAO/存储实现：已清零(app `RoomDataManger` 直读已收口到 HistoryRepository)。
- UI/业务自建线程池：`PlayFragment`(PLAYED_RECORD_EXECUTOR/parseThreadPool)、`Thunder`、subtitle `DefaultTaskExecutor`、`LocalVideoFrameLoader`/`LocalVideoAdapter` 等 `new*ThreadPool`；未全部收口到模块级执行器（各点均有串行/取消语义约束，随大页面拆分一并治理）。
- EventBus 仍广泛(register/post ~37 处)；新事件仍有出现，未真正退为"仅兼容层"。
- ui-kit:app 内已建 `com.github.tvbox.osc.ui.kit`(§2.7 第一阶段),迁入 6 个纯净组件;
  播放器/业务耦合视图(Player*View/FrostedGlassUtil)仍留 widget 包。
- 直播偏好已收口:`util.LiveConfig` 门面(connectTimeout/showTime/showNetSpeed/channelReverse/crossGroup/
  lastChannel/liveHistory),LiveActivity/三个设置弹窗/历史源弹窗不再裸读 Hawk;EPG_URL 仍跨模块(spider 写)。
- 直播设置弹窗重复已合并:`ui/dialog/LiveSettingPanel` 共享协调器,LiveSettingDialog(底部)/
  LiveSettingRightDialog(抽屉)收敛为薄壳(-372 行);LiveActivity 内嵌面板(showSettingGroup 家族)
  已删除(死代码,无调用入口,含 activity_live.xml 布局块)。
- 弹窗展示协调器:`ui/dialog/DialogCoordinator` 统一 XPopup 组装(center/right/bottom/loading/confirm),
  页面不再散拼 `new XPopup.Builder(...)`;DetailActivity/LiveActivity/LocalPlayActivity/PlayFragment/
  DownloadFragment/MyFragment/BaseActivity/LiveApiDialog 已迁移;XPopup 内置形态(asCenterList/
  asInputConfirm/asImageViewer)与 ConfirmDialog 工厂保留直调。
- 同构弹窗内容层合并(改进.txt §三 消除重复):
  `ui/dialog/{LiveSetting,DownloadSeries,PlayingControl}Panel` 共享内容协调器,
  各自 Bottom/Right 双壳收敛为薄壳(仅保留 壳/间距/字号 差异);
  已合并:LiveSettingDialog↔Right / DownloadSeriesDialog↔Right / PlayingControlDialog↔Right。
  说明:AllVodSeriesBottom↔Right 是**刻意差异**(Bottom 本地 RoundChip 网格单选,Right 复用
  DetailActivity 的 SeriesAdapter+flags 且 onDismiss 复位 grid)——不强行合并,保持两套适配器契约。
- DownloadEvent 自 common/event 迁入 download 模块(事件归业务模块,§2.8);common 仅剩
  RefreshEvent/ServerEvent(app 用)、LogEvent(common 内用)、HistoryStateEvent/TopStateEvent(无引用残留)。
- DownloadFragment 不再直连 EventBus:下载结构变更走 DownloadFacade.DownloadStatusListener、
  任务级进度走新增 DownloadFacade.TaskProgressListener(onTaskProgress(taskId))——改进.txt §五
  "跨页状态由 Facade 提供订阅"落地;UI 零 org.greenrobot.eventbus import。

## 5. 大文件拆分（§三）— 未完成
| 文件 | 行数 | 期望 |
|---|---|---|
| PlayFragment.java | ~1610 | PlayViewModel/Coordinator/PlayerSession(SubtitleCoordinator/PlayHistoryRepository 已抽,见 §8) |
| DetailActivity.java | ~1276 | DetailViewModel/Repository/EpisodeSelectionState（已拆出少量 Helper） |
| DownloadFragment.java | ~1208 | 已大量走 Facade，可继续薄化 |
| SourceViewModel.java | ~970 | 源元信息已走 `SourceConfigApi` 契约；type0/1 内联解析/EventBus 仍留(进一步依赖注入化) |

## 6. 现代化（§七·五）— 全部未启动（符合"最后做"）
Exo→Media3、EventBus→Flow/接口、Hawk→DataStore、Java→Kotlin 渐进、Hilt（按需）、ui-common→ui-kit 拆分。

## 7. 近期可安全推进清单（按收益）
1. ✅ `ParseBean` 已迁 `:core-model` 并纯化：移除 Base64(mixUrl)/proxy 替换依赖；行为收敛到
   `:spider` 的 `ParseBeanUrls.url()/mixUrl()`（调用点 PlayUrlResolver/PlayFragment 已切换）。
2. ✅ download public 面收敛：`DownloadConfig/DownloadCore` 能力并入 `DownloadFacade`（config + episodeId/states），
   app UI/工具全改走 Facade；装配入口 `init/setUrlResolverApi/setUrlSniffer` 收口到 Facade 静态方法；
   app 对 `util.Download*` import 清零，`checkModuleDependencies` 新增源码级门禁防回归。
3. ✅ app 内 `RoomDataManger` 直读已清零：UI 改走 `HistoryRepositories.history().get(...)`
   （接口新增 get(sourceKey,vodId)，Fake/单测同步）。
4. 🔜 `:core-network`(原 common)`util/{HawkConfig,SystemConfig,HttpClient}` 分模块收口（网络留下，配置→core-storage/新 config）。
5. ✅ UI 摘 Hawk(UI 层清零):直播偏好 `LiveConfig`(connectTimeout/showTime/netSpeed/channelReverse/
   crossGroup/lastChannel/liveHistory + EPG 只读 + 频道播放配置覆写);系统级 `SystemConfig`;订阅/搜索域
   `util.SubscriptionConfig`;用户页热播缓存 `util.HomeHotCache`。UI/页面/Helper 对 Hawk 与 HawkConfig 键的
   裸读写全部改走门面;残留仅在装配(App 订阅注入/putDefault)与类内封装(RemoteTVBox)与门面自身。
6. ⏸ playback shell + PlayFragment/DetailActivity 大拆分（需真机回归）。
7. ⏸ feature 模块化、Media3/DataStore/Hilt（长期）。

## 8. 边界规则抽查结果（§六逐条）
- ✅ app 无 getCSP / UI 无裸建 OkHttpClient / core-model 无 android 依赖 / download 无 :spider 依赖。
- ⚠️ 播放器收口第一步:新增 `player/KernelTrackSupport` 能力接口,IJK/Exo 各自实现,
  `PlayerTrackHelper` 不再 instanceof 具体内核(app 内 UI 已无内核强转);轨道切换/内置字幕回调/
  进度恢复语义收敛到接口。
  剩余(需真机回归):PlayFragment 由 mVideoView 直控切到 PlayerApi/PlaybackSessions 全驱动、
  PlayViewModel 抽取、app 内 MyVideoView/IKJ/Exo 引用清零、PlayerHelper 工厂
  收口 AppCompositionRoot。
- ✅ SubtitleCoordinator 抽离(等价搬移,宿主薄委托):`util/player/SubtitleCoordinator.java` 注入
  (Activity,VodController,MyVideoView),承载字幕装载(缓存/外挂/内置自动选中文)/字幕设置弹窗
  (在线搜索/本地选择/字号延迟样式/开关)/音轨与内置字幕切换(SelectDialog+轨道切换+进度恢复);
  PlayFragment 相应方法变薄委托,refresh 字幕字号事件转调 applySubtitleSize。
- ✅ PlayHistoryRepository 抽离(等价搬移,宿主薄委托):`util/player/PlayHistoryRepository.java`
  收口 "key→MD5→CacheRepository" 读写与"跳过片头(st)叠加"读取语义(类型兼容分支/打印保留);
  PlayFragment 的 getSavedProgress/saveProgress/切集与重置 delete 六处触点全委托,HistoryRepositories/MD5
  直读清零;JVM 单测 7 例(Fake CacheRepository:roundTrip/skip 取大/String 兼容/删除)。
  配套:common `MD5.string2MD5/encrypt` 的空值判断去 Android TextUtils 依赖(行为等价,纯算法类可 JVM 测)。
- ✅ SourceViewModel 去 ApiConfig 直读:新增 `spider-api.SourceConfigApi`(getSource/getHomeSourceBean/
  getSourceBeanList/getVipParseFlags,只用 core-model 类型)+ `SourceConfigProviders` 持有者(:spider 的
  ApiConfig 实现契约,AppCompositionRoot.init 注入);SourceViewModel 改持 `SourceConfigApi` 字段,8 处
  `ApiConfig.get().*` 清零,不再 import :spider 的 ApiConfig 类(VM 侧源元信息可经接口注入 Fake;type0/1
  内联解析仍留)。
- ✅ UI/展示层源元信息读取收口(同一契约):DetailActivity/GridFragment/UserFragment/HomeFragment/
  CollectActivity/HistoryActivity/FastSearchActivity、Search/History/Collect/FastSearch/QuickSearch Adapter、
  PlayFragment(getSource/getVipParseFlags)、SearchHelper/DetailQuickSearchHelper/WebSniffResolver 等
  18 处文件改走 `SourceConfigProviders`;app 内"源元信息(源注册表/首页源/源列表/vip 旗标)"经 ApiConfig
  直读清零(HomeFragment 等保留 ApiConfig 仅做 loadConfig/loadJar/setSourceBean 等源管理调用)。
- ✅ IJK 解码配置收口到播放契约:新增 `player-api.IjkCodecConfigApi`(getIjkCodes/getCurrentIJKCode/
  getIJKCodec,返回值 core-model IJKCode)+ `IjkCodecConfigProviders` 持有者;AppCompositionRoot 以适配器
  桥接 :spider ApiConfig 现有实现(避免 spider 反向依赖播放契约)。app 内 5 个解码配置读取点
  (IjkMediaPlayer/VodController/LocalVideoController/PlayerHelper/SettingActivity)改走契约,
  :spider ApiConfig 的 import 从播放侧清零。
- ✅ 解析配置/解析执行收口:新增 `spider-api.ParseConfigApi`(getDefaultParse/setDefaultParse/
  getParseBeanList/jsonExt/jsonExtMix)+ `ParseConfigProviders` 持有者;AppCompositionRoot 桥接
  ApiConfig(jar loader)。app 内解析读取点 VodController(默认解析弹窗/列表)与 PlayFragment
  (解析流程 defaultParse/parseBeanList/jsonExt/jsonExtMix)全改走契约,两文件对 ApiConfig import 清零。
- ✅ 直播/源加载收口 + ApiConfig 全 app 直读清零:新增 `spider-api.LiveChannelConfigApi`
  (getChannelGroupList/loadLives)、`SourceLoaderApi`(loadConfig/loadJar/getSpider + Callback,
  回调语义与原 LoadConfigCallback 对齐)、`SourceConfigApi.setSourceBean`;LiveActivity/HomeFragment
  改走契约,清理 LivePlayerManager/FolderAdapter/DoubanSuggestAdapter/RemoteServer 四处死 import。
  至此 app 代码对 `:spider` ApiConfig 的引用仅剩 AppCompositionRoot 桥接点(组合根,合法),业务/UI 全经
  spider-api/player-api 契约。
- ✅ download internal 化(§六"实现放 internal 包"):19 个实现/辅助类移入 `com.github.tvbox.osc.download.internal`
  (util/{Manager,Scheduler,Executor,Core,Store,Config,Policy,FileCleaner}、task/*、根包 Archive/Log/Notifier/
  ForegroundService/Event/ProgressEvent);DownloadFacade 增补委托(queryArchiveByVod/getAllArchive/
  findArchiveByPath/removeArchiveOrphansByVod,init 内含 Notifier 初始化);app 泄漏点全改走 Facade
  (App/DetailActivity/DownloadFragment 归档调用、SettingActivity 下载设置原直读 util.DownloadConfig 改 Facade);
  checkModuleDependencies 源码门禁扩展覆盖 .kt 与 download.internal 包,防回归。
- ✅ app 内 3 处 `RoomDataManger` 死 import 已删(CollectActivity/HomeFragment/HistoryActivity,无实际调用)。
- ✅ 配置归位 core-storage(改进.txt §2.3):`SystemConfig/HawkConfig` 由 :core-network(原 common)迁入
  core-storage `com.github.tvbox.osc.config` 包;新增 `KeyValueStore`(Hawk 类型安全封装:getString/getBoolean/
  getInt/泛型 get/put/delete/contains),SystemConfig 内部改走 KeyValueStore;App 侧 LiveConfig/SubscriptionConfig/
  HomeHotCache 亦改走 KeyValueStore(仅剩 App 启动装配直触 Hawk)。依赖补齐:core-storage→:log/:core-model/hawk,
  core-network→core-storage,spider→core-storage(无环、通过 checkModuleDependencies)。
- ✅ 订阅页本地导入改系统 SAF(替代 hedzr 反射 StorageVolume 兼容性问题):`SubscriptionActivity.pickFile`
  改用 `ActivityResultContracts.OpenDocument`(*/* + 扩展名校验),仅接受 ExternalStorageProvider 主卷并转真实
  路径后仍以 clan:// 订阅源加入(保留记忆导入目录/去重/权限门禁);hedzr 选择器在字幕本地字幕导入
  (SubtitleCoordinator)仍使用,真机回归见 9/5 订阅导入日志噪音是否消失。
- ✅ :download 持久化收口:`internal/{DownloadManager,DownloadStore,DownloadArchive,DownloadPolicy}` 的 Hawk 直存
  (任务列表/档案/并发/WiFi 策略)改走 `config.KeyValueStore`,:download 新增依赖 :core-storage、移除 hawk 库依赖。
- ✅ js 运行时缓存文件化:spider `util/js/local`(JS localStorage 桥)改存 `filesDir/js_runtime/*.txt`(ApiConfig
  setAppContext 注入 context),旧 Hawk 键按访问惰性迁移删除。
  **hawk 移除决策**:core-storage 的 hawk 保留为"一次性 legacy 迁移通道"(下载任务/档案回退、旧键导入——
  下载任务恢复数据安全优先),KeyValueStore 收敛为迁移辅助、DataStore 为运行权威;各模块(除 core-storage)已零 hawk 依赖。
- ✅ Hawk→DataStore 分叉修复与收边:`:spider ApiConfig` 共享键(EPG_URL/LIVE_HISTORY/api_url/ijk_codec/自用
  HOME_API/DEFAULT_PARSE)改走 PrefsDataStore(与订阅/直播/播放域同底层);App.putDefault/DEBUG_OPEN 不再回写
  Hawk;RemoteTVBox 遥控记忆、HomeHotCache 热播缓存亦切 DataStore(含存量迁移)。js 运行时缓存(local.java)
  仍留 Hawk,待文件化后评估移除 core-storage 的 hawk 依赖。
- ✅ DataStore typed 对象支持(PrefsDataStore `putJson/getJson`,gson+TypeToken):`SubscriptionConfig`(订阅列表/
  默认订阅/搜索历史/源勾选 HashMap)与 `LiveConfig`(偏好标量+历史源列表 json+频道播放配置改存 JSON 文本,
  频道旧键按访问惰性迁移)均切 DataStore,旧 Hawk 存量导入删键。
- ✅ DataStore 扩域(SystemConfig):13 键全部迁移(主题/DNS/首页/历史数/直播源/无痕/预览/快搜/调试/SSL/局域网/
  loading_anim),加载动画旧"数字型"历史值跳过避免类型冲突;旧 Hawk 存量类加载时一次性导入并删旧键。
  已覆盖域:下载策略、播放设置、日志、系统级偏好。
- ✅ DataStore 扩域(现代化):`PlayConfig`(播放 12 键,含 float)+ `PlayerFactory`(play_type 同键)+ `LogConfig`
  (经注入实现 `DefaultLogConfigStore` 切 PrefsDataStore,app_log 全链路 AppLog/设置页同底层)均完成迁移,
  旧 Hawk 存量各自一次性导入并删旧键。DataStore 化已覆盖:下载并发/WiFi、播放设置、日志开关三域。
- ✅ DataStore 化试点(改进.txt §7.5):core-storage 引入 `datastore-preferences-rxjava3`,新增 `config.PrefsDataStore`
  (Preferences DataStore 同步门面:启动读盘入内存,get 内存直读,put 串行同步落盘;标量 int/boolean/string/float/long);
  `DownloadPolicy`(下载并发/仅WiFi 两纯标量键)完成迁移 + 旧 Hawk 存量一次性搬入并删旧键(App.initParams 先初始化
  PrefsDataStore)。后续纯标量域(SystemConfig 等)可同模式扩展;对象键域需先定 typed 序列化/文件化方案。
- ✅ :download 大值键脱离配置存储:任务列表(download_tasks_v1)/档案(download_archive_v1)改存应用私有文件
  `filesDir/*.json`(gson+原子 tmp rename),启动读文件、旧 Hawk 存量一次性迁移并删旧键;load 移到
  `DownloadManager.init(context)` 后 boot()(解决"构造早于 appContext"时序);并发/WiFi 等小偏好仍在
  KeyValueStore。配置存储(DataStore 化候选)不再承载大对象。
- ✅ EventBus 收敛试点:电量改为 SystemStateMonitor 事件(`TYPE_BATTERY_LEVEL` 百分比 + SystemState.batteryPercent,
  主线程回调),PlayFragment/LocalPlayActivity 电池图标改订阅 monitor(注册即取当前值);DetailActivity/LocalPlay
  的 BatteryReceiver→EventBus 转发删除,BatteryReceiver.kt 孤儿文件移除。EventBus 仅剩跨页 refresh 事件(长线)。
- ✅ :log 日志配置依赖倒置(DataStore 化前置):新增 `log.LogConfigStore` 端口,`LogConfig` 不再直 Hawk,由
  宿主注入 core-storage `DefaultLogConfigStore`(基于 KeyValueStore)或未来 DataStore 实现;:log 移除 hawk 依赖。
  → 第三方 hawk 现仅 core-storage `KeyValueStore` 单一持有,DataStore 化(第五阶段)前置已清。
- ✅ 剩余 hawk 直用收口:core-network `AppLog`(运行日志开关)、player-api `PlayConfig/PlayerFactory`(播放偏好
  /默认播放器)、spider `ApiConfig` 与 `util/js/local`(订阅配置缓存/js 运行时缓存)全部改走 `KeyValueStore`;
  相应移除 core-network/player-api/spider/app 的 hawk 库依赖。第三方 hawk 仅剩持有者:core-storage(KeyValueStore
  封装)与 :log(LogConfig 开关/级别——依赖方向 log 不可反向依赖 core-storage,由 :log 自持,属底层边界)。
- ✅ app 对第三方 Hawk 依赖清零:App 启动装配(Hawk.init→`KeyValueStore.init`、默认值 putDefault/订阅文件同步→`SubscriptionConfig`+
  KeyValueStore)与 RemoteTVBox(遥控主机记忆)亦改走 core-storage config 封装;app 源码零
  `com.orhanobut.hawk` import/调用(仅 core-storage `KeyValueStore` 持有 Hawk)。
- ✅ LogEvent 空投清理:LOG 曾向 EventBus 空投 LogEvent(全仓零订阅者),现已移除投递与 `event/LogEvent`,
  LOG 保持纯 Logcat 输出;core-network 移除 eventbus 依赖与空 event 目录(运行时日志文件由 :log 模块承担)。
- ✅ common(现 :core-network)event 收口完成:已删 HistoryStateEvent/TopStateEvent(零引用孤儿)、
  DownloadEvent/RefreshEvent/ServerEvent 各自归位业务/app 模块,LogEvent 空投随 LOG 改造移除——core-network
  已无 EventBus 事件/依赖,event 目录删除。
- ⚠️ DownloadFragment 已完成 Facade 订阅去 EventBus;app 其余 EventBus 点(搜索/快速搜索/历史/直播等
  refresh 事件)仍为跨 Fragment 通信,逐步收口属"状态/事件管理"长线项。
  **当前面貌(截至 2026 快搜/字幕/死事件批次后)**:EventBus 订阅方仅剩
  BaseActivity(空壳载体)/DetailActivity(`TYPE_REFRESH`、`TYPE_QUICK_SEARCH_RESULT`)/FastSearchActivity
  (`TYPE_SEARCH_RESULT`、`ServerEvent`)/PlayService(`TYPE_REFRESH_NOTIFY`)/DownloadFacade(模块内桥);
  PlayFragment/QuickSearchDialog/LocalPlayActivity/UserFragment/GridFragment 均已零订阅。
  剩余有意保留:①播放器主线 `TYPE_REFRESH`/`TYPE_REFRESH_NOTIFY`(后台播放宿主销毁时 EventBus
  静默丢弃是保护语义,直调化并入 PlayFragment 全驱动改造);②VM 多源结果流 `TYPE_SEARCH_RESULT`/
  `TYPE_QUICK_SEARCH_RESULT`(每源一批、宿主累加,LiveData 单值会丢中间批次,归 SourceViewModel
  注入化);③`ServerEvent` 遥控域(SearchReceiver 空壳/16-17 常量由并行侧接线);④download 模块内
  Manager→Facade 桥(模块内实现,跨线程切主线程职责,非跨模块)。

## 10. 处理记录(按轮追加)
- ✅ SourceViewModel type0/1 解析纯函数化:`spider-api/AbsXmlParser.parseXml/parseJson/normalize`(XStream 白名单加固、
  xml 清洗、线路串→beanList、sourceKey 回填),VM `xml()/json()` 改为解析+`publishDetailPayload` 副作用分离;
  建议真机回归 type0/1 列表/详情/搜索与 type3 typed 路径。并行侧同文件改动(absXml 简化)已合流,评审一次。
- ✅ 真机 NPE 修复(typed 通道):absXml(typed) 改走公开的 `AbsXmlParser.normalize`,统一回填 sourceKey 并
  拆分线路串→beanList(此前并行简化把 typed 端拆分丢掉,checkThunder 遍历 null beanList 崩溃);另加
  beanList 空防御。gate 绿。真机回归点:type3 typed 详情/播放/雷资源判定。(commit 348fca79)
- ✅ AbsXmlParser XML 样例 + 防御回归:parseXml type0 多线路 dd/空 year/state 样例;normalize typed 路径
  (仅 urls 文本补 beanList,对应真机 NPE);normalize 自身加固 null data/空白 urls。(6c07f31f)
- ✅ RefreshEvent 零订阅死类型/死投递清理:全仓 @Subscribe 审计后删 TYPE_HISTORY_REFRESH、
  TYPE_API_URL_CHANGE/TYPE_PUSH_URL(仅 ControlManager 投,零订阅;push 本就"暂未实现")、
  LocalPlayActivity finish() 投 TYPE_REFRESH ""(处理器仅认 Integer/JSONObject)。遥控推送常量 16/17
  由并行侧按新编号保留声明,未动。(86ea62be)
- ✅ 字幕字号变更同屏直调化:DetailActivity→PlayFragment 的 TYPE_SUBTITLE_SIZE_CHANGE 广播改为
  playFragment.applySubtitleTextSize 直调(预览播放器与详情页同屏、两端唯一);PlayFragment 移除
  唯一 @Subscribe 及 register/unregister;常量 12 删除。(d9f754eb)
- ✅ 快速搜索弹窗簇收口 EventBus(DetailActivity 屏内闭环直调):QuickSearchDialog 删注册/@Subscribe/投递,
  改 Host(onVideoSelected/onWordChange)+appendResults/updateWords 宿主直喂;DetailQuickSearchHelper 注入
  QuickSearchOutput 输出回调(仅弹窗展示期有效,等价原订阅窗口);DetailActivity tvSite 打开时 setHost+初始
  直喂累计结果/词表(修复原 show 前广播在弹窗注册前丢失的时序);RefreshEvent 删零引用常量
  QUICK_SEARCH/SELECT/WORD/WORD_CHANGE(2-5)。保留 TYPE_QUICK_SEARCH_RESULT:SourceViewModel→DetailActivity
  的多源异步结果流(每源一批、宿主累加),LiveData 单值会丢中间批次,归"SourceViewModel 双轨/注入化"长线。
  (98a253a3)真机回归点:详情页"来源"快搜弹窗数据流/点词切换/点结果跳详情。
- ✅ type4 快搜 onError 路由修复:SourceViewModel.getQuickSearch type4 分支 HTTP 失败误投 TYPE_SEARCH_RESULT
  (FastSearch 主搜索通道),改投 TYPE_QUICK_SEARCH_RESULT null(与 type0/1 快搜 onError 对齐),避免错误清空
  无关搜索页结果。(db13be31)
- ✅ UserFragment/GridFragment 死 EventBus import 清理(无 @Subscribe/register/post;GridFragment 的
  mGridView.post 为 View.post 非 EventBus)。(e2bfc2b7/90299dd4/ca63859e)
- ✅ DetailActivity onDestroy 断开快搜输出桥(setQuickSearchOutput null)并清弹窗引用,防匿名回调
  悬空引用已销毁弹窗/Activity。(d2580119)
- ✅ SortParser type0 XML 分类样例单测(parseSortXml 已测:rss/class/ty 解析 + filters 空补 +
  畸形输入返回 null;JSON 分支原有覆盖)。双通道均 JVM 可测。(9077b3fd)
- ✅ DownloadFragment 纯展示逻辑抽离为 `util.DownloadDisplay`(纯静态,无 Android 依赖):
  剧集名/清晰度/索引段解析、尺寸/速度格式化、列表指纹、递归删除;DownloadFragment 净 -78 行、
  TextUtils import 清除;新增 JVM 单测 7 例(格式化边界/命名解析/指纹/递归删)。
  配套:`checkModuleDependencies` 门禁收窄为仅拦截 download.internal(app util 的 Download* 旧实现
  已迁入 internal 并删除,宽拦 util.Download* 已无对象)。(8de5b5ee)
- ✅ buildPercentText(任务行"大小·进度"文本)并入 DownloadDisplay,补 HLS 混排/纯字节/失败附因用例。
- ✅ LiveActivity 移除死代码:EPG `getTime(String,String)`/`durationToString(int)` 全仓零调用,删除并清 import。(57203751)
- ✅ DownloadFragment 聚合分组抽离 `util/DownloadGrouping`(纯数据):DownloadGroup 模型 + 分组/归属/组序/
  存在性判定迁出,宿主仅留 Facade 取数与委托;JVM 单测 6 例。DownloadFragment 1306→1079 行。(30a792be)
- ✅ 任务行状态展示下沉 DownloadDisplay:statusTextOf/statusToneOf(ERROR/MUTED/ACTIVE,颜色表留 UI)、
  stageMessageOwnsProgress(合并/补片自带进度)、shouldShowSpeed(逐字等价原 startsWith 判定);单测 3 组。
  DownloadDisplayTest 累计 12 例。(aef13cfd)
- ✅ 聚合卡副标题 aggregateNote(任务数+已完成且文件存在集数)下沉 DownloadGrouping;单测 7 例。
  DownloadFragment 1306→1038 行。(1f876086)
- ✅ 任务行文本拼装下沉 DownloadDisplay:titleText(剧名·集名判重)/statusLine(状态+百分比+网速,
  收尾省略整体百分比)/sourceText(未知来源)/swipeActionText(继续/重试/暂停);convert 仅剩 setText
  与色调取色。DownloadDisplayTest 累计 15 例。DownloadFragment →1016 行。(0d929aeb)
- ✅ FormatSRT 解析样例单测(字幕装载纯解析链路首测):双字幕顺序+毫秒精确、多行 <br /> 连接、
  空输入零字幕。(ffbd4988)
- ✅ LiveActivity 死代码收尾:showBottomEpg 无用空 Handler、一批零使用字段(playUrl/timeFormat/
  countDownTimer3/videoWidth/videoHeight/show)+ 从未赋值的 countDownTimer(恒空 cancel 块)删除,
  清 CountDownTimer/SimpleDateFormat import。LiveActivity 815→731 行。(679881bb/b8e0b7ce)
- ✅ SourceViewModel 死代码/冗余清理:删除零调用 getSortFilter(及 gson JsonArray/JsonElement/
  JsonObject/LinkedHashMap import);sortJson/sortXml 未用的 MutableLiveData result 参数去除
  (解析后由调用方发布),4 处调用点同步。(1ddead9b)
- ✅ DetailActivity 下载弹窗数据模型抽离 `util/DownloadSeriesModel`(纯):勾选集名收集、
  按全集正表重建选集副本并写 episodeId、批量状态查询数组拆分;宿主仅做弹窗类型分派与
  Facade episodeId 工厂注入。单测 4 例。(308572ba)
- ✅ 批量下载结果文案下沉 EpisodeDownloadBatch.toastMessage(Outcome)(六路文案纯映射),
  宿主只弹 toast + null 兜底;单测覆盖各分支。DetailActivity 下载弹窗状态层数据/文案面
  已纯化;剩余为 UI 弹窗实例/生命周期编排(全屏退出、抽屉/底部弹窗分派、Facade 订阅),
  与宿主强耦合,深拆需真机背书。(2f7149d1)
- ✅ DetailActivity 下载弹窗协调器等值搬移 `ui/dialog/DownloadDialogCoordinator`:
  弹窗实例/防重入、DownloadFacade 状态订阅注销、数据后台准备、Wi-Fi 确认+批量入队+文案
  全部下沉;DetailActivity 只实现 Host(数据/全屏退出时序/postDelayed/跳转/toast/runOnUi),
  public 入口转发协调器;同步清理 30+ 失效 import。DetailActivity 1244→993 行。
  (013f9c15)真机回归点:详情"下载"底部弹窗、全屏控制栏右侧抽屉、选集勾选/排序保留、
  批量下载文案、下载状态实时刷新。
- ✅ DetailActivity 再清理零调用死方法 getHtml;同时并行侧提交搜索卡片/全屏控制样式批
  (9bb20a51),整仓门禁复绿。DetailActivity →987 行。(254bb13d)
- ✅ 直播分组密码门禁抽离 `util/LiveChannelAuth`:isPasswordConfirmed/needInputPassword/
  visibleChannels 纯逻辑(组数据+确认集合传参),LiveActivity 三方法改委托;单测 4 例。
  (9bdcc169)
- ✅ 频道组/频道导航纯化 `util/LiveChannelNav`:getNextChannel 跨组/加密跳过/回卷决策抽出,
  行为等价 + 全锁定/单组防死循环兜底;firstOpenGroup 并入 LiveChannelAuth。
  LiveActivity 815→705 行;导航/门禁单测累计 9 例。(afccb330)
- ✅ LiveActivity 死字段清理:hsEpg(Hashtable)/imgLiveIcon 零使用、isSHIYI 恒假开关
  (if 恒不触发/赋值恒 false)连用法删除,清 Hashtable import。LiveActivity 815→746 行。(ede84aea)

## 9. 待真机回归后继续(播放器主线尾段,当前挂起)
> 集中回归清单见 `doc/device-regression-checklist.md`(按功能域分组,门禁绿后逐项过)。

- PlayerApi 会话内核**实验选项**:设置页加"播放器内核:PlayerApi 会话(实验)"开关(默认关);
  开启时点播走 PlayerFactory 创建的内核并驱动基础播放 + PlaybackSessions 会话观察,与 doikki 路径并行对比。
  需控制器/字幕/进度接线与真机调,决定先不做,待播放器相关批次(会话/内核统一/字幕装载/电量/网速/DataStore/
  SAF)真机回归通过后,再按完整方案实现。
- 回归通过后可继续:SourceViewModel `xml()/json()` 纯函数提取(type0/1 下沉前置,等值可单测);
  EventBus 跨页 refresh 逐类收口;core-storage hawk 依赖下线(一次性迁移通道退役)。
