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
| `:core-network` | ⚠️ | 目录/模块名已对齐(:core-network,原 common);内部仍是"公共垃圾桶"：`util/{HawkConfig,SystemConfig,HttpClient,OkGoHelper,AES,MD5,AdBlocker,AppLog,LOG,urlhttp/*}`、`event/*`(EventBus 事件)。改进.txt §2.8 期望逐步拆并 |
| `:core-storage` | ⚠️ | data/cache/Repository 已出；app 无 DAO 直读(RoomDataManger 死 import 已清);Hawk 类型安全封装未做 |
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
- ⚠️ common/event 收口:已删 HistoryStateEvent/TopStateEvent(零引用孤儿)、DownloadEvent/RefreshEvent/ServerEvent
  各自归位业务/app 模块；common 仅剩 LogEvent(common 内 LOG.java 自用,EventBus 空投遗留——无人订阅,待日志页改造后清理)。
- ⚠️ DownloadFragment 已完成 Facade 订阅去 EventBus;app 其余 EventBus 点(搜索/快速搜索/历史/直播等
  refresh 事件)仍为跨 Fragment 通信,逐步收口属"状态/事件管理"长线项。
