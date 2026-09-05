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
| `:ui-common` / ui-kit | ⚠️ | ui-common=纯资源 ✅；app 内无 ui-kit package，通用 View 未归拢 |
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
- ⚠️ UI 直读 Room/Hawk、`PlayerTrackHelper` instanceof 内核、EventBus 新增仍存在 → 作为持续治理项。
