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
| SourceViewModel 走 SpiderApi | ✅ type3 typed 优先+回退 | type0/1/4 仍在 VM 内走 `HttpClient`+`xml()/json()/sortJson()` 内联解析；未到 `SourceViewModel(SpiderService)` 可 Fake 形态；VM 内仍大量 EventBus.post |
| DownloadFragment 走 DownloadFacade | ⚠️ | 控制/查询已走 Facade；仍 import `util.DownloadManager`（读 `MSG_*` 阶段文案）→ 本轮把 MSG 常量并入 `DownloadFacade`，UI 摘除该 import |
| DetailActivity 不直调 DownloadManager | ✅ | 另：仍直用 `cache.RoomDataManger.getVodInfo`（DAO 泄漏点，见 §4） |
| 注册并使用 PlayerFactory | ⚠️ | 已注册 IJK(1)/Exo(2) adapter + `PlaybackSessions`/`VideoViewPlayerApi` 会话原型；PlayFragment 仍直持 `MyVideoView`/内核，session 仅日志观察 |

## 3. 模块边界（§二/§八）
| 模块 | 状态 | 残留 |
|---|---|---|
| `:core-model` | ✅ | `ParseBean` 仍在 `:spider`（本轮迁移）+ 含行为(getUrl proxy 替换 / mixUrl Base64) → 迁移时纯化 |
| `:core-network`(common) | ⚠️ | 更名完成但内部仍是"公共垃圾桶"：`util/{HawkConfig,SystemConfig,HttpClient,OkGoHelper,AES,MD5,AdBlocker,AppLog,LOG,urlhttp/*}`、`event/*`(EventBus 事件)。改进.txt §2.8 期望逐步拆并 |
| `:core-storage` | ⚠️ | data/cache/Repository 已出；DAO 仍外泄(app `RoomDataManger` 3 处)；Hawk 类型安全封装未做 |
| `:spider-api` / `:spider` | ✅ 试点 | 字符串通道(SpiderContentApi)仍在(过渡兼容)；`ApiConfig` 仍暴露具体 Spider(内部实现需留) |
| `:download` | ⚠️ | Facade 已接入；`DownloadManager/Scheduler/Executor/Archive/Core/Store/Config` 仍 public(改进.txt §六要求 internal 化) |
| `:player-api` / `:player` | ⚠️ | 契约 + 原型已接；app 仍直用 `MyVideoView`/IJK/Exo、`PlayerTrackHelper` 按内核 instanceof 分发 |
| `:ui-common` / ui-kit | ⚠️ | ui-common=纯资源 ✅；app 内已建 ui-kit package（6 个纯净组件），通用 View 归拢中 |
| `:playback` / feature-* | ❌ | 未建（改进.txt 第三/四阶段，需真机回归环境） |

## 4. 穿透点（UI/上层直读下层实现，新代码应避免）
- UI 直读 `Hawk`：`LiveActivity`(20+)、`LiveSettingDialog/RightDialog`、`DetailActivity`、`UserFragment`、`PlayFragment`、`GridFragment`、`ApiHistoryDialog`、`LiveApiDialog` 等 ~65 处。
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
| PlayFragment.java | ~1714 | PlayViewModel/Coordinator/PlayerSession/SubtitleCoordinator/HistoryRepo |
| DetailActivity.java | ~1276 | DetailViewModel/Repository/EpisodeSelectionState（已拆出少量 Helper） |
| DownloadFragment.java | ~1208 | 已大量走 Facade，可继续薄化 |
| SourceViewModel.java | ~930 | 依赖 SpiderService、可 Fake 单测 |

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
4. 🔜 `common/util/HawkConfig/SystemConfig/HttpClient` 分模块收口（网络→core-network，配置→core-storage/新 config）。
5. ⚠️ UI 摘 Hawk：直播偏好已收口 `LiveConfig`(键沿用 HawkConfig);其余直读点(DetailActivity showPreview/
   GridFragment fastSearch/PlayFragment debug/UserFragment home_hot 缓存/BackupDialog 等)按需逐步收口。
6. ⏸ playback shell + PlayFragment/DetailActivity 大拆分（需真机回归）。
7. ⏸ feature 模块化、Media3/DataStore/Hilt（长期）。

## 8. 边界规则抽查结果（§六逐条）
- ✅ app 无 getCSP / UI 无裸建 OkHttpClient / core-model 无 android 依赖 / download 无 :spider 依赖。
- ⚠️ 播放器收口第一步:新增 `player/KernelTrackSupport` 能力接口,IJK/Exo 各自实现,
  `PlayerTrackHelper` 不再 instanceof 具体内核(app 内 UI 已无内核强转);轨道切换/内置字幕回调/
  进度恢复语义收敛到接口。
  剩余(需真机回归):PlayFragment 由 mVideoView 直控切到 PlayerApi/PlaybackSessions 全驱动、
  SubtitleCoordinator/PlayViewModel 抽取、app 内 MyVideoView/IKJ/Exo 引用清零、PlayerHelper 工厂
  收口 AppCompositionRoot。
- ⚠️ common/event 收口：已删 HistoryStateEvent/TopStateEvent(零引用孤儿)、DownloadEvent/RefreshEvent/ServerEvent
  各自归位业务/app 模块；common 仅剩 LogEvent(common 内 LOG.java 自用,EventBus 空投遗留——无人订阅,待日志页改造后清理)。
- ⚠️ DownloadFragment 已完成 Facade 订阅去 EventBus;app 其余 EventBus 点(搜索/快速搜索/历史/直播等
  refresh 事件)仍为跨 Fragment 通信,逐步收口属"状态/事件管理"长线项。
