# 强类型 SpiderService 设计（改进.txt §1/§4.3 落地方案）

> 目标：把 app 侧“字符串 JSON/XML + 各自解析”下沉为 :spider 内实现返回的**类型化领域对象**，
> app/UI 只依赖 :spider-api 契约；并用 FakeSpiderService 支持 JVM 单测。
> 现状锚点：`SourceViewModel` 依 type=0/1/3/4 分别走 XML/JSON/JS+Jar 并各自 parse 成
> `AbsSortXml/AbsXml`（core-model）；`:spider-api` 已有字符串级 `SpiderContentApi`。
> 本设计分“对象模型 → 契约 → 实现下沉 → 迁移 → 假件/门禁”五步，全部落地后才能删字符串通道。

## 1. 对象模型（全部放 core-model，纯 Java，Serializable）
- 复用已有：`AbsSortXml`(分类/首页 tab)、`AbsXml`(详情/搜索/推荐列表)、`Movie/MovieSort`。
- 新增强类型容器（可选，先以 Abs* 为最终返回，避免二次建模）：
  - `SortPage { SortData 分类元数据; List<Movie.Video> videoList; 分页状态 }`
  - `DetailResult { AbsXml absXml; String sourceKey; String vodId; }`
  - `PlayResult { JSONObject 兼容字段 key/url/flag/parse/header/headerObj… }`
- 规则：对象只放数据，无 ApiConfig/Hawk/网络行为；与 Room Entity 不共用。

## 2. 契约（:spider-api）
```java
public interface SpiderService {
    SortPage home(String sourceKey, boolean filter);          // 首页分类+视频
    SortPage category(String sourceKey, String tid, int page, Map<String,String> extend);
    DetailResult detail(String sourceKey, String vodId);
    SearchPage search(String sourceKey, String word, boolean quick);
    PlayResult  resolvePlay(String sourceKey, String flag, String id, List<String> vipFlags);
}
```
- `:spider` 内实现 `ApiSpiderService`：内部封装“取源 → JS/Jar/HTTP 拉取 → 解析成 Abs*”，
  复用现有 SpiderContentApi 的桥接与现有解析器（把 app 的 xml/json 解析按格式搬运到实现侧）。

## 3. 解析下沉（关键风险点）
- 现有 `SourceViewModel.json()/xml()/sortJson()/sortXml()` 与 `SourceViewModel` 里的分支(0/1/3/4)
  是 app 内与源格式耦合最重的地方；逐方法迁移：
  1) detail 先迁（唯一 JSON/XML 混合、风险最低、历史/详情主链路）；
  2) search/quickSearch 次之；3) category/home/推荐最后（多 tab 联动最多）。
- 每迁一个方法：:spider 内实现解析+返回类型；SourceViewModel 相应分支改为调用返回对象直接 post；
  删除对应字符串解析分支；`assembleDebug/Release` 门禁 + 真机回归该类源。

## 4. 迁移后形态
- `SourceViewModel(SpiderService spiderService)`：类型安全、可用 FakeSpiderService；
- 字符串协议(JSON 拼接/正则嗅探等)只存在于 :spider 实现内部；
- `SpiderContentApi`(字符串版) 作过渡兼容层，迁移完成后删除。

## 5. 假件与门禁
- 测试模块 `:app:test`（或后续 core 模块 test）：`FakeSpiderService` 返回固定 Abs*/DetailResult，
  覆盖 SourceViewModel 各 tab 事件后置、空结果、异常回退。
- 新增 Gradle 依赖方向校验 task（违规红门禁），CI 可插。

## 6. 实施顺序（每个子项 = 一个可编译提交）
1. core-model 补类型容器(如需要)  → 2. :spider-api 加 SpiderService → 3. :spider 实现 detail→search→category/home
→ 4. SourceViewModel 切换 + 删旧解析分支 → 5. FakeSpiderService + 单测 → 6. 删 SpiderContentApi。
> 前置：真机/模拟器环境就绪（XML/JSON/JS/Jar 四类源逐一回归）。
