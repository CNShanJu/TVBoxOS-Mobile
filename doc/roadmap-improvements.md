# TVBoxOS-Mobile 改进计划清单（源自 doc 改进.txt，结合当前仓库状态）

> 状态图例：🔜待做 / ⚠️部分已做 / ✅已完成。每项含“怎么做 + 验收”。
>
> 进度快照（本清单更新于最近一次批量推进）：
> ✅ 0.1 下载 UI 已全走 DownloadFacade + DownloadRequest 入队入口；✅ 1.1 NetworkProvider(general/noRedirect/playback)+组合根注入；
> ✅ 1.2 HistoryRepository/CollectRepository 已出、UI 改走仓储接口；✅ 1.4 下载 Facade 补全且 UI 零 DownloadManager 直调；
> ✅ :core-model/:core-storage/:core-network/:spider-api 模块齐位；SourceViewModel/嗅探/播放解析均走 spider-api 契约(app getCSP=0)。
> ✅ 1.3 强类型试点:detail/search(quick/聚合)/category/homeContent/homeVideoContent(type3)均已 typed 优先+字符串回退;
> SortParser 迁入 :spider-api 供 VM/契约层共用,排序/筛选解析链路有 JVM 单测。
> ✅ 2.1 原型:PlayerFactory 注册 IJK(1)/Exo(2) adapter + PlaybackSessions 会话层 + VideoViewPlayerApi + PlayFragment 日志观察入口
> (真机回归后再收敛为 session 全驱动)。

## P0 收尾易做项（先清低风险尾巴）
| # | 事项 | 现状 | 做法 | 验收 |
|---|---|---|---|---|
| 0.1 | 下载模块 internal 化 | ✅ Facade 全量收敛 | DownloadConfig/DownloadCore 能力并入 Facade;app 零 `util.Download*` import;装配入口收口 Facade;checkModuleDependencies 源码级门禁防回归 | `download` 内 public 面只剩 Facade/任务 Bean；全量编译 |
| 0.2 | ParseBean 迁 core-model | ❌ 在 spider | 抽出 Base64/DefaultConfig 到调用侧，ParseBean 纯化后迁入 | ParseBean 在 core-model，无 Android 依赖 |
| 0.3 | UI 不直读 ApiConfig/Hawk 的抽查治理 | ❌ | 新代码规范 + 圈出高风险点逐步收口 | 抽样 grep 违规点下降 |
| 0.4 | 事件/线程规范 | ❌ | 禁止新增 EventBus 事件/页面自建线程池的评审清单 + 残余点替换 | 新改动不含新增 EventBus/`newFixedThreadPool` |

## P1 模块边界补齐（按依赖方向自底向上）
| # | 事项 | 现状 | 做法 | 验收 |
|---|---|---|---|---|
| 1.1 | core-network 收敛 + `NetworkProvider` | ⚠️ 更名完成、业务侧不再 new 客户端 | 建 `NetworkProvider {general/spider/download/playback}`；spider `catvod.net.OkHttp`、Exo/Picasso 客户端创建归拢；共享池/DNS/TLS/UA 统一 | 业务模块零裸建客户端；Provider 单一实现注入 |
| 1.2 | core-storage 出 Repository 层 | ⚠️ 数据层已迁 | HistoryRepository/CollectRepository + HistorySnapshot/Summary；DAO 不外泄 | UI 只依赖 Repository/Facade，不再 import DAO |
| 1.3 | spider-api 强类型化 | ⚠️ 试点完成 | detail/search/category/home 已 typed 优先+字符串回退;剩余:SpiderService 完整化/SortPage 容器、删字符串通道 | app 无蜘蛛字符串协议细节;可 Fake 单测 |
| 1.4 | download → DownloadRequest | ⚠️ 门面已用 | enqueue(request) 化（含已解析业务数据） | UI 不传散参、不读 Activity/VM/Hawk |

## P2 播放器与页面（需真机回归，专项立项）
| # | 事项 | 现状 | 做法 | 验收 |
|---|---|---|---|---|
| 2.1 | player-api 接线 + playback 会话层 | ⚠️ 原型已落地 | PlayerFactory 注册 IJK(1)/Exo(2) adapter(AppCompositionRoot);新增 PlaybackSessions 会话注册表 + VideoViewPlayerApi(轮询映射,不抢共享视图监听);PlayFragment 播放入口 bind/释放 仅日志观察 | PlayFragment 收敛到 session.play/pause/observe/release(真机回归后) |
| 2.2 | PlayFragment 拆分 | ❌ | PlayViewModel/Coordinator/SubtitleCoordinator/HistoryRepository | 行数明显下降且行为不变 |
| 2.3 | DetailActivity 补齐拆分 | ⚠️ 已拆两块 | DetailViewModel/Repository/EpisodeSelectionState | 同上 |
| 2.4 | 字幕能力迁入 playback | ❌ | subtitle 包迁 playback 模块 | app 不再直接持字幕实现细节 |

## P3 工程化与现代化（长期）
| # | 事项 | 现状 | 做法 | 验收 |
|---|---|---|---|---|
| 3.1 | ui-common→theme + ui-kit | ⚠️ ui-kit 已建 | app 内建 `ui.kit` package(§2.7 第一阶段):迁入 AppSwitch/AppTitleBar/RatioShadowLayout/ClearEditText/两个 ItemDecoration,带纯净边界约束;弹窗展示统一走 `DialogCoordinator`(ui.dialog);成熟后再拆 Gradle 模块 | UI 组件无 Hawk/ApiConfig/EventBus;ui-common 只留主题资源 |
| 3.2 | 模块依赖检查 + 测试门禁 | ❌ | gradle 依赖方向校验 task + JVM 单测（FakeSpiderService 等） | CI 违反依赖方向即红 |
| 3.3 | EventBus→Flow/监听接口 | ❌ | 分业务逐步替换 | 新事件不再走 EventBus |
| 3.4 | Hawk→DataStore（类型安全 Config） | ❌ | Config/Repository 化 | 业务不再直接 Hawk.put/get |
| 3.5 | Exo→Media3、Java→Kotlin 渐进、Hilt（按需） | ❌ | 待 2.x 稳定后 | 按版本策略迁移 |

## 建议执行顺序
1. P0.1→P0.2（小、独立、可立刻编译验收）；
2. P1.1→P1.4（把基础模块边界补完整，逐个“契约化+编译门禁”）；
3. P2（真机环境建立后一次性专项）；
4. P3 分散在 1/2 阶段内渐进完成。
每项以 `:app:assembleDebug/:app:assembleRelease` 全绿 + 真机回归为完成条件。
