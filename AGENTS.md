# MBox(TVBoxOS-Mobile)项目规则(AGENTS)

> 本文件将仓库根目录《改进.txt》的限制与要求固化为**项目级规则**,所有代码改动(人工与 AI)必须遵守;
> 并沉淀安全审计中的**持续约束型**红线(见「七、安全与工程红线」,一次性修复明细不入本文件)。
> 原始文档见仓库根 `改进.txt`(未跟踪,不提交);本文件是其在版本库中的权威落地,随 git 一起跟踪与演进。

---

## 一、目标架构(依赖方向,强制)

最终分四层,依赖只允许自上而下,**禁止反向**:

```
app / feature
    ↓
业务 API:PlayerApi、SpiderService、DownloadFacade
    ↓
业务实现:player、spider、download
    ↓
基础设施:network、storage、state、log
    ↓
纯模型:model
```

- 业务模块只通过公开契约协作(接口/门面),具体实现放 internal 包。
- 禁止基础模块反向依赖业务或 UI。
- 同一领域模型只定义一次;跨模块不共享可变对象。
- 所有后台任务必须由模块级执行器管理,**页面/UI 不得自行创建线程池**。
- UI 组件不得读取全局配置(Hawk/ApiConfig 等)或访问业务单例;状态与事件经属性、接口或 ViewModel 传入。

## 二、模块边界(现状模块与残留治理)

| 模块 | 边界要求 |
|---|---|
| `:core-model` | 纯 Java 模型,不依赖 Android/Hawk/Room/ApiConfig,不执行网络与 DB;尽量不可变;模型无 getSource()/save() 等基础设施行为 |
| `:core-network` | 收敛网络客户端(OkGoHelper/HttpClient/各 OkHttp 创建),业务不得自行 `new OkHttpClient.Builder()` |
| `:core-storage` | Room/缓存/配置收口,对外只暴露 Repository/类型安全 Config,不暴露 DAO;storage 不得依赖 spider/download/player |
| `:spider-api` | 爬虫契约(SourcePage/Category/Detail/Search/Play 请求与结果),QuickJS/JarLoader/ApiConfig/具体 Spider 留在 :spider |
| `:player-api` | 播放契约(PlayerSession/PlayerState/PlayerOptions/PlayerEvent/PlayerFactory),UI 不得直接依赖 MyVideoView/IJK/Exo 具体内核 |
| `:download` | 只公开 DownloadFacade.enqueue/pause/resume/delete/observe;Manager/Scheduler/Executor/Archive 等为模块内部实现 |
| `:ui-common`/ui-kit | ui-common=纯主题资源;通用组件先进 app 内 ui-kit package,成熟后再拆模块 |

现状残留(持续治理,新代码勿新增同类):
- app 仍直用 `MyVideoView`/IJK/Exo、`PlayerTrackHelper` 按内核分发(播放器收口长线)。
- `:core-network` 内部仍是杂项袋(util/{HttpClient,OkGoHelper,AES,MD5,AdBlocker,AppLog,LOG,urlhttp/*}),待拆。
- `:spider-api` 字符串通道(SpiderContentApi)为过渡兼容层,新功能不得新增字符串协议依赖。
- 未建 `:playback` / feature-* 模块(第三/四阶段,需真机回归环境再动)。

## 三、大页面拆分目标(§三)

- **PlayFragment**:应拆为 PlayFragment(只绑 View/生命周期)+ PlayViewModel(页面状态)+ PlayCoordinator(换源/解析/重试)+ PlayerSession(播放控制)+ SubtitleCoordinator + PlayHistoryRepository。
  - Fragment 不应直接持有 Spider、ExoPlayer、IJK 或 DAO;解析/嗅探引擎已抽 `util/player/PlayParseCoordinator`。
  - 已抽:`PlayRequest`/`PlaySessionKeys`/`PlayedVodKey`/`SubtitleCoordinator`/`PlayHistoryRepository`/`PlayParseCoordinator`;剩余大块(startPlayUrl 等)仍与控制器耦合,拆分需真机回归。
- **DetailActivity**:应拆 DetailViewModel/DetailRepository/EpisodeSelectionState/DetailDownloadCoordinator/QuickSearchCoordinator;Activity 只处理导航、弹窗、视图事件。
- **SourceViewModel**:只依赖 SpiderService 等契约(可 FakeSpiderService 做 JVM 单测),不得直调 `ApiConfig.get().getCSP()` 之类具体爬虫。

## 四、依赖注入(§四)

- 不强制全面引入 Hilt;先建 app 层组合根 `AppCompositionRoot`(network/storage/spider/download/players 等)。
- 静态 get() 保留为兼容入口;新代码一律构造注入接口;ViewModel 经 Factory 取依赖;旧调用逐个迁移后静态入口清零。
- 依赖注入的目的是可替换实现与便于测试,不是为框架而框架。

## 五、状态与事件(§五)

- 页面状态:ViewModel + LiveData(逐步 Kotlin 化后 StateFlow);一次性页面事件用明确 Event 类型。
- 跨页状态(下载/播放):由 Facade 提供订阅接口(如 `DownloadFacade.DownloadStatusListener/TaskProgressListener`、`PlaybackSessions`)。
- 系统状态(电量/网络):统一 SystemStateMonitor。
- **EventBus 仅作迁移兼容层:新代码禁止新增 EventBus 事件**;refresh 类跨页事件逐类收口为直调/接口。

## 六、验收红线(新代码不得触碰)

1. app 内基本搜索不到 `getCSP()`、`DownloadManager.get()` 与具体播放器内核直调(见 `checkModuleDependencies` 门禁)。
2. UI/页面层禁止裸读 Hawk 或具体配置单例(一律经业务 Config 门面读写,如 SystemConfig/LiveConfig/PlayConfig/SubscriptionConfig;底层为 core-storage `PrefsDataStore`/DataStore)。
3. UI 禁止自建线程池;禁止 `(Activity) context` 强转具体 Activity 依赖弹窗/组件(依赖经构造注入窄宿主接口)。
4. UI 组件禁止发 EventBus 业务事件;新代码禁止新增 EventBus 事件。
5. 模块依赖只增公开契约接口;Gradle 依赖默认 `implementation`,谨慎 `api`。
6. 业务/后台任务必须用模块级共享执行器(如 `HeavyTaskUtil`),且带取消/过期自检语义(epoch),不得每轮 new 线程池后 shutdown 了事。
7. 所有可滚动组件(RecyclerView/ScrollView/GridView/横向列表等)必须保留**内边距**并配合
   `clipToPadding=false` 让首/末内容不贴到屏幕或容器边缘滚动;禁止内容贴边滚动影响视觉效果。

## 七、安全与工程红线(持续约束)

**网络与 TLS**
- 禁止全局关闭 HTTPS/TLS 校验(如 WebView `onReceivedSslError` 一律 proceed、HttpClient 全信任);确需忽略证书只允许"按域 + 用户显式开关"且默认拒绝(cancel)。
- 局域网/回环服务(RemoteServer 等)按需启动、用毕即停;对外暴露最小化,敏感操作必须带鉴权/令牌,禁止无鉴权读写。

**输入与文件安全**
- 本地路径一律防目录穿越:拼接前校验并规范化,拒绝 `..`/绝对路径逃逸出目标目录。
- 解压(压缩包/归档)防 Zip Slip:每个条目的最终落盘路径必须位于目标目录内,解压前校验。
- HLS/代理分片等高频落盘必须节流/限频,禁止无界写盘。

**数据、并发与性能**
- Room/DB 查询禁止主线程执行;需即异步(协程/执行器)或缓存预热。
- Gson 等解析器复用实例并建索引/缓存,禁止热路径重复构建;UA 等常量数组化,避免每请求新建数组。
- 启动阶段不做阻塞式删缓存/重 IO;大资源懒加载,页面销毁即释放。
- 列表更新用 DiffUtil 时保证差量正确(LocalVideoAdapter/下载列表等禁止 O(n²)/整表 notify);页面/Adapter 不得把视图/Activity 持有到生命周期外(视图泄漏)。

**Android 暴露面**
- Manifest 权限最小化:只声明实际使用权限;组件(Activity/Service/Receiver/Provider)按需 `exported`,不对外暴露者一律 `android:exported="false"`,敏感暴露组件配权限保护。

**依赖、构建与资源**
- 依赖升级与版本对齐走显式批次(Room/exo 等跨模块依赖版本一致),禁止引入冗余/重复依赖;同步清理死源码与无效 import。
- Gradle 开启/维护缓存与并行构建,避免本地与 CI 行为漂移。
- 图片加载统一单一图片库,不复用多套;网络状态/电量等系统状态统一经 SystemStateMonitor 单点订阅。
- **全局复用同一个图片占位符**:全 App 图片(海报/封面/缩略图等)占位与加载失败统一用
  `placeholder_poster`(灰底+居中图标,主题感知)。**占位图标固定只能用 `ic_image_placeholder.xml`**
  (源文件 `D:\Code\Video\icon\图片加载失败.svg` 转的矢量);禁止各页面/适配器自行引入第二套占位图
  (如 iv_load_fail / img_loading_placeholder / ic_img_placeholder / ic_poster_placeholder 等),发现即收敛到统一占位。
- 多语言资源按需裁剪(`resConfigs`),禁止无界塞入语言包。

## 八、推荐推进顺序(供排期参考)

1. **第一阶段(补边界)**:SourceViewModel→SpiderApi;DownloadFragment→DownloadFacade;DetailActivity 不直调 DownloadManager;注册并使用 PlayerFactory;禁止新增 Hawk/EventBus/具体 Manager 直调。
2. **第二阶段(抽基础)**:core-model → spider-api → core-network → core-storage。
3. **第三阶段(拆播放器与大页)**:playback shell;拆 PlayFragment/DetailActivity;字幕迁 playback;ViewModel 只调接口。
4. **第四阶段**:feature-* 按需模块化(不要在依赖未稳时先搬目录)。
5. **第五阶段(现代化)**:Exo→Media3、EventBus→Flow/接口、Hawk→DataStore、Java→Kotlin、Hilt(按需)、依赖检查与测试门禁。

## 九、开发与提交纪律

- 全量门禁:改动后必须跑 `:app:assembleDebug :app:assembleRelease :app:testDebugUnitTest checkModuleDependencies` 全绿再提交。
- 提交信息遵循 `doc/Git提交规范.md`(type[scope]: 描述 ≤50 字;一次提交一件事;提交前 git status 确认无多余文件)。
- 文件级提交:按范围 `git add`,不混入无关改动;本规则文件(AGENTS.md)与各 doc/ 状态文档随对应批次同步更新并提交。
- 改进.txt 本身与 release-notes-v3.0.1.md 不入库;其要求以上方章节为准。

### 构建内存纪律(强制)

1. **Gradle 堆固定,不因单次构建临时改动**:`gradle.properties` 的 `org.gradle.jvmargs` 固定为
   `-Xmx2g -XX:MaxMetaspaceSize=512m -Djdk.tls.client.protocols=TLSv1.2`。禁止为了单次打包临时调大/调小 `-Xmx`
   构建后还原——不同 `-Xmx` 会让 Gradle 各起一个常驻 Daemon(4g/2g/1g daemon 并存)互抢内存,
   这正是"每次打包都内存不足/`mmap failed`/`hs_err_pid*.log`"的根因。
2. **打包内存不足时的标准处理**:先 `gradlew --stop` 停 Daemon,仍不足再结束残留 jdk17 java 进程
   (`Get-Process java` 排查含 `GradleDaemon`/`KotlinCompileDaemon` 的进程),然后以固定配置重新构建。
   不要靠改堆绕行。
3. 若确需为 CI/极端机器调堆,走显式批次讨论,完成后回写固定值,不得留下多个历史堆配置常驻进程。

### 发布与版本纪律(强制)

1. **Release 文案一律归纳,禁止照搬 commit**(强制):发布 Release/发版说明(以及任何给用户看的版本更新说明)时,
   **禁止直接把 git commit 列表原文照搬贴出**,必须**基于 commit 记录自行总结**为**用户可读的更新说明**。做法:
   - 先取本次发版的 commit 区间(上一发布 tag.. 当前 tag,如 `git log <prev>..<curr> --no-merges`),
     逐个读懂每条 commit 的**意图与影响**,不能只扫标题;
   - 按**用户视角**归并分类(通常「新增功能 / 修复 / 改进 / 其他」),每类下用口语化、面向用户的要点概括
     「改了什么 + 对用户有何影响」,而非罗列 commit 标题;
   - 剔除对用户无意义的内部细节(依赖重构、代码结构调整、占位符实现、安全审计等),避免照抄;
   - 按「大.中.小」版本号写出与版本定位相符的总结,**重要变更置顶**,开头标注版本号;措辞克制准确,不夸大新增/修复;
   - 同一条 commit 跨多类时归入最相关的一类,避免重复。
2. **debug 包严禁进 git**:永远不要提交/推送 debug 构建产物(如 *_debug.apk、build 产物);
   除非用户**明确要求**,且即便如此也须先与用户**二次确认**后方可提交。
3. **push 前自动升版本(用户未另行说明时)**:
   - 每次 push 若用户没有指定版本,先按 **大版本号·中版本号·小版本号** 提升**小版本号**一次
     (改 `app/app_config.properties` 的 `versionName` 末段 +1,并同步 `versionCode` 单调递增),再提交代码。
   - 用户让**打 tag** 时:一律基于**最新的 versionName** 打(如 `v3.4.5`)。
   - 用户让**发布 app(出正式包)**时:一律基于**最新 tag 对应的版本**构建。
   - 用户有主动说明(指定版本号/tag/发布方式)时,以用户说明为准。

## 十、常用基础设施速查

- 配置:core-storage `config.PrefsDataStore`(DataStore,运行权威;历史 Hawk 一次性迁移通道 `KeyValueStore` 已随 hawk 退役下线);各业务 Config 门面见 `com.github.tvbox.osc.config`(SystemConfig)与各模块 config 包。
- 契约 Providers(app 侧桥接 :spider 实现):`spider-api.SourceConfigProviders/ParseConfigProviders/LiveChannelConfigApi/SourceLoaderApi/IjkCodecConfigProviders` 等,业务/UI 一律经它们取源元信息,禁止直触 ApiConfig。
- 弹窗:统一 `ui/dialog/DialogCoordinator`(center/right/bottom/loading/confirm);同构内容层合并用共享 Panel(如 LiveSettingPanel/DownloadSeriesPanel/PlayingControlPanel),Bottom/Right 收敛为薄壳。
- 共享执行器:`util/HeavyTaskUtil`(getBigTaskExecutorService 并行 / getSerialExecutorService 串行);配合 epoch/过期自检做取消语义。
- 播放上下文:`util/player/{PlayRequest,PlaySessionKeys,PlayedVodKey,PlayHistoryRepository,SubtitleCoordinator,PlayParseCoordinator,PlaybackSessions}`。
