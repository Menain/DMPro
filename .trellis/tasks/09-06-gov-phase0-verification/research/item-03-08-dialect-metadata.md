# Research: 验证项 ③ DmlExplainPreInitHandler 的 PG 支持 / ⑧ schema 元数据 SPI 确切名称

- **Query**: Phase 0 验证清单 ③⑧ —— DML EXPLAIN 对 PostgreSQL 的支持程度（影响 Phase 8 路径 B）；Preflight 表/列/索引元数据获取接口的确切名称与调用链（影响 Phase 7）
- **Scope**: internal（纯代码实勘，无外部检索）
- **Date**: 2026-09-06

---

## 结论速览

**③ DmlExplainPreInitHandler 的 PG 支持**：handler 自身无方言分支、无 `supports()` 方言门槛（只按 approBiz=DM_QUERY/DM_CHANGE 放行）。EXPLAIN 解析/改写完全委托给两个 SPI：`ExplainPlanSpi`（解析执行计划为统一 `ExplainPlan` 模型）、`RewriteSpi.rewriteToExplain`（生成 EXPLAIN 前缀语句）。PG 侧 `PgExplainPlanSpi` **已注册**，但其 `supportByQueryType` **显式排除 INSERT/UPDATE/DELETE/MERGE**（仅放行 SELECT）——因此 PG 的 UPDATE/DELETE/MERGE 走 `executeOne4Unsupported`，结果标记 UNSUPPORTED、对 `expectedAffectedRows` 贡献 0；PG INSERT（带 insertRows 语句级估算）仍走 `executeOne4Insert`（不实际执行 EXPLAIN，从解析的关系取行数，方言无关）。**降级无需改代码**：handler 对 UNSUPPORTED 不抛错、不阻断工单，仅写状态；对无 SPI 的数据源则 `findExplainSpi==null` 早退（静默 no-op）。`expectedAffectedRows` 写入 `DmApprovalDO.expectedAffectedRows`（持久 Long 字段，经 `DmApprovalMapper.updateExpectedAffectedRows`），路径 B 阈值分级读该字段可行——但 PG DML 该值为 0/null，行数门禁对 PG DML 实际失效，等价降级为"规则审计+人工确认"。若要让 PG DML 也有行估算属新增能力（需放开 PG `supportByQueryType` 的 DML 限制并验证 `EXPLAIN UPDATE/DELETE` 输出解析）。

**⑧ schema 元数据 SPI 确切名称**：三层链路——console 门面 `DsSchemaService`（impl `RemoteDsSchemaService`，@Service 可直接注入）→ RSocket 契约 `MetaRService`（@RSocketApiClass，console 端 `@Resource` 即 RSocket 代理）→ sidecar `MetaRServiceProvider` 开 SessionAgent 委托每会话 `DsMetaService`（SDK 接口，基类 `DefaultRdbMetaService`，MySQL=`MyMetaService`、PG=`PgMetaService`，二者均经 `*Hooks` 注册）。**Preflight 四项全覆盖、MySQL/PG 双方言均覆盖、无需新增 SPI**：连通性=`DsSchemaService#realTimeFetchVersion`（现有"测试连接"即走此路，取版本=连通）或直接 RSocket `MetaRService#testConnect`（返回 `TestConnectResultDO(boolean,msg)`，但当前**无 console 端调用者**，Preflight 将是首个调用方）；表存在性=`DsSchemaService#realTimeFetchSelectObject(dsDO, levelsParam, tableName)` 返回 `RdbTable` 或 null；列存在性=`RdbTable#getColumns()`；索引存在性=`RdbTable#getIndices()/getPrimaryKey()/getUniqueKeys()`——后三项均从 `realTimeFetchSelectObject` 一次调用拿到。

---

## ③ DmlExplainPreInitHandler 的 PG 支持 —— 逐项证据

### ③-1 handler 主体与 SPI 委托架构

| File | Class/Method | 关键行摘录 |
|---|---|---|
| `backend/clouddm-platform/cgdm-console/src/main/java/com/clougence/clouddm/console/web/component/approval/handler/DmlExplainPreInitHandler.java` | `DmlExplainPreInitHandler extends AbstractPreInitHandler` | L63-123 `doHandle`：构建请求缓存→执行→`expectedAffectedRows = results.stream().map(DmlExplainResultMO::getEstimatedAffectedRows).filter(Objects::nonNull).mapToLong(...).sum()` → L122 `context.getApprovalDal().approvalMapper().updateExpectedAffectedRows(approvalId, expectedAffectedRows)` |
| 同上 | `findExplainSpi(DataSourceType)` | L125-129 `DsPluginInfo dsPlugin = PluginManager.findDsPlugin(dsType); List<ExplainPlanSpi> explains = dsPlugin==null?emptyList():dsPlugin.findSpi(ExplainPlanSpi.class); return explains.isEmpty()?null:explains.get(0);` —— **SPI 解析按数据源类型查插件**，handler 无方言 if/else |
| 同上 | `buildRequests4File` | L160-163 `ExplainPlanSpi explainSpi = findExplainSpi(context.getDsConfig().getDataSourceType()); if (explainSpi == null) { return; }` —— **无 SPI 则静默早退** |
| 同上 | `executeRequests` | L314-317 `ExplainPlanSpi explainSpi = findExplainSpi(...); if (explainSpi == null) { return; }` —— 执行阶段同样早退 |
| `.../handler/AbstractPreInitHandler.java` | `AbstractPreInitHandler#supports(DmApprovalDO)` | L27-30 `return approBiz == DM_QUERY || approBiz == DM_CHANGE;` —— **只按业务类型放行，不按数据源类型**，因此 PG 工单也进入此 handler |

### ③-2 EXPLAIN 怎么执行 / 行数怎么提取（通用，非 MySQL 专用）

| 分支 | 方法（行） | 行为 |
|---|---|---|
| INSERT 带语句级估算 | `executeOne4Insert` L386-394 | `explainSpi.analyze(Collections.emptyList(), request.getRelations())` —— **不实际执行 EXPLAIN**，行数来自解析器算出的 `BehaviorRelation.getInsertRows()`（`INSERT INTO ... VALUES` 字面行数），方言无关 |
| 不支持 | `executeOne4Unsupported` L396-401 | 全部 result 置 `DmlExplainStatus.UNSUPPORTED`，无行数估算 |
| 原生 EXPLAIN | `executeOne4NativeExplain` L403-431 | `request.setUseExplain(true); ResultList resultList = this.queryService.syncExecuteQuery(uid, sessionId, request);` 走 **sidecar 查询通道**（隔离会话，`rdbAutoCommit=false`，结束时 rollback+close，见 `createExplainSession` L338-345 / `closeExplainSession` L347-361）；然后 `ExplainPlan plan = explainSpi.analyze(rawResults, request.getRelations())` |
| 行数提取 | `insertAffectedRows(subjects, plan)` L433-447 | `plan.getNodes().stream().filter(node -> subjects.contains(node.getObjectPath())).map(ExplainPlanNode::getEstimatedRows).filter(Objects::nonNull).mapToDouble(...).sum()` —— **操作统一 `ExplainPlan` 模型，不直接读 MySQL/PG 原始列**，方言差异由 SPI 吸收 |

EXPLAIN 前缀生成在 `prepareExplainRequest` L270-284：`supportsNativeExplain = explainSpi.supportByQueryType(request.getQueryTypes())`；若 true 且非 INSERT 估算则 `rewriteSpi.rewriteToExplain(queryId, queryBody, ctx)` 改写为 EXPLAIN 语句（`request.setQueryBody(explainQuery); request.setUseExplain(true)`）。

### ③-3 PG 支持程度（核心判断）

| File | Class/Method | 关键行摘录 | 结论 |
|---|---|---|---|
| `backend/clouddm-plugins/clouddm-ds/ds-postgres/src/main/java/com/clougence/clouddm/ds/postgres/PgDsPlugin.java` | `configExecute` L83-91 | L90 `dsPlugin.addPluginSpi(new PgExplainPlanSpi());` | **PgExplainPlanSpi 已注册** |
| `.../ds/postgres/execute/explain/PgExplainPlanSpi.java` | `UNSUPPORTED_DML` L23-27 | `EnumSet.of(INSERT, UPDATE, DELETE, MERGE)` | PG SPI 显式列出排除的 DML |
| 同上 | `supportByQueryType` L94-99 | `return queryTypes!=null && queryTypes.contains(SELECT) && Collections.disjoint(queryTypes, UNSUPPORTED_DML);` —— **覆盖接口默认实现**（默认 `anyMatch(isAllowPlan)`，L34-36） | **PG DML(UPDATE/DELETE/MERGE) 返回 false → supportsNativeExplain=false → executeOne4Unsupported → UNSUPPORTED，0 行** |
| 同上 | `analyze` L102-146 / `planLine` L29-63 | 解析 PG 文本 QUERY PLAN：`normalized.indexOf("(cost=")`、`normalized.indexOf("rows=", costStart)`，从 `rows=123` 取数字；只读 `ResultSet` 第 0 列文本行 | **PG 文本计划解析能力存在且对 SELECT 生效**（PG `EXPLAIN SELECT` 输出每行一个文本计划行，含 `rows=`） |
| `backend/clouddm-plugins/clouddm-sql/sql-postgres/src/main/java/com/clougence/sql/postgres/editor/rewrite/PgRewriteSpi.java` | `rewriteToExplain` L97-114 | L108-111 `SplitQueryType type = new PgSplitVisitor(...).visit(astTree); if (type==null || !type.isAllowPlan()) return null;` L113 `return "EXPLAIN " + queryStr;` | 改写器能拼 `EXPLAIN ` 前缀，但受 `supportByQueryType` 上游拦截，DML 根本到不了这里 |

**对照 MySQL**：`backend/clouddm-plugins/clouddm-ds/ds-mysql/src/main/java/com/clougence/clouddm/ds/mysql/execute/explain/MyExplainPlanSpi.java` 未覆盖 `supportByQueryType`（用接口默认 `isAllowPlan`），DML 放行；`analyze` L47-76 读 MySQL 表格 EXPLAIN 的 `rows` 列（`columnIndexes.get("rows")`）。故 MySQL DML 走原生 EXPLAIN 有行估算，PG DML 走 UNSUPPORTED 无行估算——**方言差异完全由 SPI 层吸收，handler 层无分支**。

### ③-4 降级策略是否需要代码改动

- `AbstractPreInitHandler#supports` 不按数据源类型 gate（③-1），handler 对 PG 工单照常进入。
- handler 对 UNSUPPORTED 不抛错、不中断：`executeOne` L375-383 仅在**异常**时把 result 置 FAILED 并 `incrementFailedCount`；UNSUPPORTED 是正常状态分支，`expectedAffectedRows` 求和时 `filter(Objects::nonNull)` 自动跳过 null 行数。
- 对完全无 SPI 的数据源，build/execute 两阶段均 `if (explainSpi == null) return;` 静默 no-op。
- **结论**：spec §2.3-3 "PG 路径 B 降级为规则审计+人工确认" **无需改 handler 代码**——现状即已优雅降级（PG DML 结果 UNSUPPORTED、行估算为 0/null）。行数阈值分级（`GOV_DML_ROW_LIMIT` warn/block）读 `expectedAffectedRows` 时，PG DML 该值为 0，门禁自然失效，等价于降级到纯规则审计。

### ③-5 expectedAffectedRows 落点与路径 B 阈值读字段可行性

| File | 定位 | 摘录 |
|---|---|---|
| `backend/clouddm-platform/cgdm-dao/src/main/java/com/clougence/clouddm/platform/dal/model/approval/DmApprovalDO.java` | L74 `private Long expectedAffectedRows;` | 持久化字段（Lombok @Getter/@Setter） |
| `backend/clouddm-platform/cgdm-dao/src/main/java/com/clougence/clouddm/platform/dal/mapper/approval/DmApprovalMapper.java` | L63 `void updateExpectedAffectedRows(@Param("ticketId") Long, @Param("expectedAffectedRows") Long);` | 写入入口 |
| `DmlExplainPreInitHandler.java` L117-122 | 求和后调用上述 mapper | 路径 B 阈值分级读 `DmApprovalDO.expectedAffectedRows` **可行**（持久 Long 列，审批行内） |
| `.../console/web/model/vo/ticket/DmQueryTicketVO.java` L44 | `private Long expectedAffectedRows;` | VO 已透出该字段 |

---

## ⑧ schema 元数据 SPI 确切名称 —— 逐项证据

### ⑧-1 三层元数据链路总览

```
console 门面                      RSocket 契约                 sidecar provider              per-session SPI
DsSchemaService    ──@Resource──>  MetaRService   ──RSocket──>  MetaRServiceProvider  ──>  Session.getMetaService(): DsMetaService
( RemoteDsSchemaService )          ( @RSocketApiClass )         ( 开 SessionAgent )          ( DefaultRdbMetaService 子类 )
                                                                                              ├─ MyMetaService  (MySQL)
                                                                                              └─ PgMetaService  (PostgreSQL)
```

### ⑧-2 Layer A：console 侧可编程门面（Preflight 直接注入点）

| File | Class | 角色 |
|---|---|---|
| `backend/clouddm-platform/cgdm-console/src/main/java/com/clougence/clouddm/console/web/component/schema/DsSchemaService.java` | `interface DsSchemaService` | 接口定义 |
| `.../component/schema/RemoteDsSchemaService.java` | `@Service class RemoteDsSchemaService implements DsSchemaService` | Spring bean，Preflight `@Resource` 即得 |

Preflight 四项可用的方法签名（接口 L39-71）：

| 用途 | 方法签名 | impl 行号（向 RSocket 代理转发） |
|---|---|---|
| 连通性（取版本=连通） | `String realTimeFetchVersion(DmDsDO dsDO, Map<UmiTypes,Object> levelsParam)` | L105-109 → `metaRService.getVersion(...)` |
| 表存在性 + 列 + 索引（一次调用） | `Value realTimeFetchSelectObject(DmDsDO dsDO, Map<UmiTypes,Object> levelsParam, String leafName)` | L112-122 → `metaRService.fetchSelectObject(...)`，返回 `RdbTable` 或 null |
| 列批量 | `Map<String,List<RdbColumn>> loadColumns(DmDsDO dsDO, Map<UmiTypes,Object> levelsParam, UmiTypes leafType, List<String> names)` | L445-449 → `metaRService.loadColumns(...)` |
| 表清单 | `List<DsElement> listLeaf(DmDsDO dsDO, Map<UmiTypes,Object> levelsParam, UmiTypes leafType, String pattern, boolean refreshCache)` | L212-228 |
| 单对象详情 | `Value detailLeaf(DmDsDO dsDO, Map<UmiTypes,Object> levelsParam, UmiTypes leafType, String leafName, boolean refreshCache)` | L231-247 |

另有现成更高阶门面（现有"查表结构/数据字典"页面路径）：

| File | Class#method | 摘录 |
|---|---|---|
| `.../console/web/service/sdk/ConsoleMetaServiceImpl.java` | `@Service class ConsoleMetaServiceImpl implements MetaService` | L53 |
| 同上 | `List<MetaCol> fetchTableColumns(String uid, long dsId, Map<UmiTypes,Object> levelsParam, String tableName)` L65-84 | `Value value = dsSchemaService.realTimeFetchSelectObject(dsDO, levelsParam, tableName); if (value==null) throw DS_TABLE_NOT_EXIST_ERROR;` 然后从 `RdbTable` 取 `getColumns()/getPrimaryKey()/getUniqueKeys()/getIndices()/getForeignKeys()` 转 `MetaCol` —— **即现有表结构查看功能的编程入口** |

### ⑧-3 Layer B：sidecar RSocket 契约

| File | Class | 摘录 |
|---|---|---|
| `backend/clouddm-platform/cgdm-api/src/main/java/com/clougence/clouddm/api/sidecar/session/execute/MetaRService.java` | `@RSocketApiClass interface MetaRService` | L32-60 |
| 方法清单 | | `testConnect`(返回 `TestConnectResultDO`)、`getVersion`、`getSqlParserParameters`、`listLevels`、`detailLevel`、`listLeaf`、`detailLeaf`、`fetchSelectObject`、`loadColumns`、`loadTableEditor`、`requestObjectScript` |
| console 端注入 | `RemoteDsSchemaService` L65 `@Resource private MetaRService metaRService;` | `@Resource` 即 RSocket 远程代理（框架自动生成） |

### ⑧-4 Layer C：sidecar provider + 每会话 SPI + 双方言实现

| File | Class | 摘录 |
|---|---|---|
| `.../cgdm-sidecar/src/main/java/com/clougence/clouddm/worker/provider/MetaRServiceProvider.java` | `@Service @RSocketApiClass class MetaRServiceProvider implements MetaRService` | L50；每方法 `try(SessionAgent s = metaSession(dbConfig, levelsParam)){ s.getMetaService().xxx(); }`（L83 等）；`testConnect` L81-93 `rdbSession.getMetaService().testConnect(); return new TestConnectResultDO(true,"OK")` 失败返 `(false, msg)` |
| `.../cgdm-plugin-sdk/src/main/java/com/clougence/clouddm/sdk/execute/meta/DsMetaService.java` | `interface DsMetaService` | SDK 每会话 SPI：`testConnect()`、`getVersion()`、`getCurrentCatalog/Schema()`、`listLevels/detailLevel/listLeaf/detailLeaf/fetchSelectObject/batchColumns/loadTableEditor/requestObjectScript` |
| `.../cgdm-plugin-sdk/src/main/java/com/clougence/clouddm/sdk/execute/session/rdb/DefaultRdbMetaService.java` | `abstract class DefaultRdbMetaService implements DsMetaService` | 基类，所有方法经 `rdbUmiService(con).xxx()`；`fetchSelectObject` L146-154、`batchColumns` L211-219、`listLeaf` L178-197 |
| `.../dsc-common-mysql/src/main/java/com/clougence/clouddm/dsfamily/mysql/execute/MyMetaService.java` | `class MyMetaService extends DefaultRdbMetaService` | L42；由 `MyHooks` L53 `return new MyMetaService(session)` 注册（会话钩子） |
| `.../dsc-common-postgres/src/main/java/com/clougence/clouddm/dsfamily/postgres/execute/PgMetaService.java` | `class PgMetaService extends DefaultRdbMetaService` | L42；override `getSqlParserParameters`(L49-52 取 `current_setting('server_version')`)、`getCurrentCatalog`(L63-75 `select current_database()`)、`getCurrentSchema`(L78-91 `select current_schema()`)、`testConnect`(L94-109 `select 1`)、`requestObjectScript`；`rdbUmiService` L55-57 返回 `PgUmiServiceDm` |

### ⑧-5 现有"测试数据源连接"代码路径

| File | 定位 | 摘录 |
|---|---|---|
| `.../controller/datasource/DmDsController.java` | L312-313 `@RequestMapping("/testConnect")` POST；L199 `@RequestMapping("/connectDs")` POST | → `DmDsService.testConnect(...)` |
| `.../component/dsconfig/DmDsService.java` | L36 `String testConnect(long dsId);` L38 `String testConnect(ConnectDsFO fo);` | 接口 |
| `.../component/dsconfig/impl/DmDsServiceImpl.java` | `testConnect(long dsId)` L132-144 / `testConnect(ConnectDsFO)` L187-232 / `testConnect(clusterId,driver,dsConfig)` L234-247 | 三入口最终都调 `getVersion` (L146-184) → **L170 `return this.schemaService.realTimeFetchVersion(clusterId, dsConfig, levelsParam);`** —— 即"测试连接=取版本成功=连通" |
| `MetaRService.testConnect` / `DsMetaService.testConnect` | sidecar 侧 `select 1` 返 `TestConnectResultDO(boolean,msg)` | **当前无任何 console 端调用者**（grep `metaRService.testConnect`/`MetaRService.*testConnect` 在 cgdm-console 源码 0 命中）——这是 sidecar 内部能力，Preflight 若用将成为首个 console 调用方 |

### ⑧-6 RdbTable 模型：一次调用拿全表/列/索引

`backend/clouddm-utils/cg-schema/src/main/java/com/clougence/schema/umi/special/rdb/RdbTable.java`（L38-173，Lombok @Getter/@Setter）字段：

- `Map<String,RdbColumn> columns`（L52）→ `getColumns()` 列存在性
- `RdbPrimaryKey primaryKey`（L54）→ `getPrimaryKey()` 主键
- `List<RdbUniqueKey> uniqueKeys`（L56）→ `getUniqueKeys()` 唯一键
- `List<RdbIndex> indices`（L57）→ `getIndices()` 索引
- `List<RdbForeignKey> foreignKeys`（L59）→ `getForeignKeys()` 外键

即 `realTimeFetchSelectObject` → cast `RdbTable` → 一次拿到列+PK+UK+索引+FK。

### ⑧-7 Preflight 四项检查接口落位（结论表）

| 检查项 | 调用接口（类#方法） | MySQL | PostgreSQL | 缺口 |
|---|---|---|---|---|
| 数据源连通性 | `DsSchemaService#realTimeFetchVersion(DmDsDO, Map)`（现有 test-connect 走此）或直接 `MetaRService#testConnect`（RSocket，返 TestConnectResultDO） | ✅ MyMetaService | ✅ PgMetaService.testConnect override(L94-109 `select 1`) | `MetaRService#testConnect` 当前无 console 调用方；Preflight 可复用 `realTimeFetchVersion` 或成首个 testConnect 调用方，二选一均无需改 sidecar |
| 表存在性 | `DsSchemaService#realTimeFetchSelectObject(DmDsDO, Map, String tableName)`（null=不存在，RdbTable=存在） | ✅ | ✅ | 无 |
| 列存在性 | `RdbTable#getColumns()`（来自上一条）或 `DsSchemaService#loadColumns(DmDsDO, Map, UmiTypes, List<String>)` 批量 | ✅ | ✅ | 无 |
| 索引存在性 | `RdbTable#getIndices()`/`getPrimaryKey()`/`getUniqueKeys()`/`getForeignKeys()`（来自 realTimeFetchSelectObject） | ✅ | ✅ | 无 |

**结论**：四项 Preflight 检查全部可由现有 `DsSchemaService` 门面覆盖，MySQL/PG 双方言均有对应 `*MetaService` 实现（均经 `DefaultRdbMetaService` 基类 + `*UmiService`），**无需新增 SPI 或新写方言 SQL**（与 spec §2.3-2 "不手写方言 SQL，走现有元数据抽象"一致）。

---

## Caveats / 未尽事项

- **③ PG DML 行估算缺失是设计而非 bug**：`PgExplainPlanSpi.supportByQueryType` 显式排除 DML 是插件作者主动选择（PG 语法上 `EXPLAIN UPDATE/DELETE` 可用，但 SPI 未实现其输出解析）。若 spec 后续要求 PG DML 也走原生 EXPLAIN 行估算，需：(1) 放开 `UNSUPPORTED_DML` 限制；(2) 在 `analyze`/`planLine` 验证 PG DML EXPLAIN 输出（PG 对 DML 的 EXPLAIN 仍输出含 `rows=` 的文本计划，解析逻辑可能可复用，但需实测）。本期 P0 按"降级为规则审计+人工确认"处理无需改动。
- **③ `GOV_DML_ROW_LIMIT` 尚未在代码中存在**：grep `GOV_DML_ROW_LIMIT`/`expected_affected_rows` 仅命中 `DmApprovalDO.expectedAffectedRows` 字段与 mapper，未发现现成 warn/block 阈值配置——该阈值分级是治理平台新增逻辑，读 `DmApprovalDO.expectedAffectedRows` 字段技术可行，但分级配置本身需新建。
- **⑧ `MetaRService.testConnect` 的 console 首调用方**：若 Preflight 直接用 `DsSchemaService#realTimeFetchVersion`（=现有 test-connect 路径）则零新代码；若想用带布尔结果的 `MetaRService#testConnect`，需在 Preflight 侧直接 `@Resource MetaRService`（RSocket 代理可直接注入，RemoteDsSchemaService 已是此模式），仍无需改 sidecar。两者皆可，前者更省事。
- **⑧ levelsParam 构造**：`realTimeFetchSelectObject` 等需 `Map<UmiTypes,Object> levelsParam`（含 Catalog/Schema）。现有 test-connect 路径在 `DmDsServiceImpl.getVersion` L147-155 用 `SessionSpi.createSessionContext(dsConfig, emptyMap)` 取 `rdbCatalog/rdbSchema` 填充——Preflight 可复用同法（或参考 `DmlExplainPreInitHandler` 的 `DmDsUtils.createSessionCtx` / `context.getDsLevels()`）。
- 未读 `PgUmiServiceDm` / `MyUmiServiceDm` 内部（`fetchSelectObject` 的具体信息_schema SQL），因属 sidecar 插件内部实现，Preflight 调用链止于 `DsMetaService` 接口层即足够。
