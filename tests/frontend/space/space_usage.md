# Space Usage (空间占用:实例摘要 + 库/Schema 级 + 表级快照)

## Purpose

验证库表空间占用页(只读快照)的**权限矩阵、双方言(MySQL 8.0 / PostgreSQL 15)摘要条与库级列表主流程、表级钻取、标识符转义边界、空态与排序/过滤/分页纪律**。主要防止的回归:

- 权限闸失效:无 `DM_SPACE_READ` 的账号能看到菜单/直连路由/调通接口;
- 方言分派断裂:MySQL 实例发出 PG SQL(或反之)、PG 库级未列出**实例全部数据库**(把当前连接库的 schema 分解误当库级——初版形态已被冒烟否决)、PG 表级未连到目标库(rdbCatalog 丢失 → 查回默认库)、MySQL 库级未排除 4 个系统 schema;
- 摘要口径错误:摘要条不是对**库级全量**聚合(被表名过滤/分页影响)、PG 摘要 数据/索引/表数 未显示 `-`(库级分解列物理不可得,不得显示 0);
- 数值语义误导:MySQL 估计值未给弱化提示、PG「可回收」列未显示 `-`(PG 无 DATA_FREE 等价物);
- 标识符注入面:含单引号/反斜杠(MySQL 字面量转义)/`\0`/超长的库名直调 tables 接口未转义或未拒绝(值比较位置误用标识符引号会报「column does not exist」——已踩过;PG dbName 仅作连接参数不进 SQL);
- 前端纪律回归:本地排序/过滤/分页(渲染切片)、陈旧响应覆盖新实例数据、切实例未重置、未选库时表级区块出现假空态。

## Scope

- 页面:`/#/space`(`frontend/src/views/space/index.vue`)。
- 入口:侧边栏主菜单「空间占用」(`utils/buildSidebarMenu.js`,条件 `myAuth.includes('DM_SPACE_READ') && !isDesktop`,锁分析条目之后);路由 `meta: { requiredAuth: 'DM_SPACE_READ' }`(`router/index.js` beforeEach 闸)。
- 接口(`SpaceController`,前缀 `/api/entry/space`,均 `@RequestAuth(DM_SPACE_READ)`):
  - `POST /listInstances`:租户内 ∩ 白名单 {MySQL, PostgreSQL} ∩ 未删除的数据源 `[{dsId, dsName, dsType}]`,按名称排序(复用 B2 `SessionInstanceVO`);
  - `POST /databases`:`{dsId}` → `{branch, dbs:[{dbName, tableCount, rowEstimate, dataBytes, indexBytes, freeBytes, totalBytes}]}`;MySQL=单条 M1 聚合(全列);PG=单条 `pg_database` 共享目录查询(**实例全部非模板库**,仅 totalBytes=pg_database_size 有值,分解列 null → 前端 `-`);
  - `POST /tables`:`{dsId, dbName}` → `{dbName, tables:[{schemaName(PG only), tableName, rowEstimate, dataBytes, indexBytes, freeBytes, totalBytes}]}`;dbName 服务端校验后:MySQL 以**字符串字面量**转义拼入 M2;PG 作为 **rdbCatalog 连接参数**(DEFAULT_DATABASE 覆写)连到目标库,P2 跨 schema 全列且**永不拼入 dbName**;
  - 纯只读,**不落操作审计**(平台只读接口先例);每端点一个短诊断会话、单条 SQL、单条 10s 超时;摘要由前端对库级全量聚合,无独立摘要 SQL。
- 错误契约:白名单外类型 → 「该数据源类型暂不支持空间占用查看」;跨租户/不存在 dsId → 「数据源不存在或无权限」(零远程调用);标识符非空/≤64/无 `\0` 校验失败 → 「错误的参数!」(COMM_BAD_ARG_ERROR,拼 SQL 前零远程调用);语句级 ERROR → 「查询空间占用失败:<原始错误>」(SPACE_QUERY_FAILED,单 SQL 端点整体失败,无区块级降级);超时 → 「查询空间占用超时,请稍后重试」(SPACE_QUERY_TIMEOUT);空结果 = 正常空态(库无表/实例无用户库),非错误。
- 不覆盖:历史增长趋势/容量预测/告警(用户决议不做)、bloat/碎片率分析(PG 需扩展;MySQL DATA_FREE 仅原样展示)、分布式数据库(TiDB/PolarDbX/OceanBase 不在白名单)、后端通道 C 内部行为与 dbName 安全面(由 `SpaceServiceImplTest` 20 用例覆盖:M1/M2/P1/P2 映射含列名大小写混杂/null 分解列/reltuples -1 归零 GREATEST/schema_name 列、MySQL 字面量转义、PG catalog 传参断言、超时翻译、语句错误翻译、白名单/租户、listInstances 过滤排序;`OpsDiagnosticExecutorTest` 17 用例含 catalog→rdbCatalog 透传)。

## Preconditions

- 后端 8222 运行且包含空间占用端点(`:boot-alone:installDist` 重建后启动,cwd=backend/);前端 8080 dev 或已构建产物(`env -C package ./all_build.sh web`);Chrome 已登录。
- 实例矩阵(尽量齐备,缺项按 Skip Conditions 处理):
  - **M1:MySQL 8.0** 实例,至少含 1 个用户库且有若干 BASE TABLE(任意既有业务/样例库即可,无需专门造数);记录一个库名 `<mydb>` 与其表清单用于对照;
  - **P1:PostgreSQL 15** 实例,至少含 2 个非模板库(其中记 `<pgdb>` 为有业务表的库,内含至少 1 个用户 schema 与若干表;库级列表应能看到**全部**非模板库,含 postgres);
  - X1(可选):白名单外数据源(MariaDB/TiDB/Oracle 等任一,环境中已有即可);
  - E1(可选,仅本地/测试实例):可构造含特殊字符库名的实例,用于 S3 转义边界(见 Test Data)。
- 上述实例绑定的 worker 在线。
- 账号矩阵(可用角色管理页临时授权,测后恢复):
  - A:Manager 或 DBA 角色(默认含 DM_SPACE_READ);
  - B:子账号,角色不授「空间占用查看」(如默认 Developers)。
- **直连对照工具**:mysql CLI / psql(或平台 SQL 工作台)可对 M1、P1 直连执行对照 SQL(见各 Suite)。

### 直连对照 SQL(与后端下发等价,用于核对页面数字)

MySQL(M1,库级/摘要):
```sql
-- 摘要对照(实例总数据/索引/总量;页面摘要条应与此同量级,允许 InnoDB 估计偏差)
SELECT SUM(data_length) AS data_bytes, SUM(index_length) AS index_bytes,
       SUM(data_length + index_length) AS total_bytes
FROM information_schema.tables
WHERE table_type = 'BASE TABLE'
  AND table_schema NOT IN ('information_schema','mysql','sys','performance_schema');
-- 库级对照(逐库,应与页面库级表逐行一致,顺序按 total 降序)
SELECT table_schema AS db_name, COUNT(*) AS table_count, SUM(table_rows) AS row_estimate,
       SUM(data_length) AS data_bytes, SUM(index_length) AS index_bytes, SUM(data_free) AS free_bytes,
       SUM(data_length + index_length) AS total_bytes
FROM information_schema.tables
WHERE table_type = 'BASE TABLE'
  AND table_schema NOT IN ('information_schema','mysql','sys','performance_schema')
GROUP BY table_schema ORDER BY total_bytes DESC;
-- 表级对照(单库 <mydb>)
SELECT table_name, table_rows AS row_estimate, data_length AS data_bytes, index_length AS index_bytes,
       data_free AS free_bytes, data_length + index_length AS total_bytes
FROM information_schema.tables
WHERE table_type = 'BASE TABLE' AND table_schema = '<mydb>' ORDER BY total_bytes DESC;
```

PostgreSQL(P1,库级/摘要/表级 —— 物理精确,应逐值一致):
```sql
-- 库级对照(实例全部非模板库;任一库连接均可执行,页面库级行 total 应与此逐值一致)
SELECT d.datname AS db_name, pg_database_size(d.oid) AS total_bytes,
       pg_size_pretty(pg_database_size(d.oid)) AS pretty
FROM pg_database d WHERE NOT d.datistemplate ORDER BY total_bytes DESC;
-- 摘要对照(页面摘要条 total = 上面各库 pg_database_size 之和,应逐值一致)
SELECT SUM(pg_database_size(d.oid)) AS instance_total_bytes
FROM pg_database d WHERE NOT d.datistemplate;
-- 表级对照(必须先连到目标库 <pgdb> 再执行;跨 schema,应与页面表级逐值一致)
\c <pgdb>
SELECT n.nspname AS schema_name, c.relname AS table_name, GREATEST(c.reltuples,0)::bigint AS row_estimate,
       pg_table_size(c.oid) AS data_bytes, pg_indexes_size(c.oid) AS index_bytes,
       pg_total_relation_size(c.oid) AS total_bytes
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind IN ('r','p') AND n.nspname NOT IN ('pg_catalog','information_schema','pg_toast')
  AND NOT pg_is_other_temp_schema(n.oid)
ORDER BY total_bytes DESC;
```

> 口径差异(必读):MySQL 的 `data_bytes/index_bytes/total_bytes/row_estimate` 全部为 InnoDB **估计值**,且来自实例统计缓存(`information_schema_stats_expiry`,默认 ≤24h 滞后),与直连对照**允许偏差**(尤其 row_estimate ±40-50%);断言只核对「量级一致、非负、总量≈数据+索引、库级排除系统 schema」。PG 的 size 为**实时物理精确值**(lstat),与 `pg_*_size` 对照应**逐值一致**;仅 `row_estimate`(reltuples)为上次 ANALYZE 估计。

## Test Data

B4 为**只读快照**,正常主流程(S1/S2/S4/S5)在实例**既有**库表上验证,不构造、不写入任何数据。仅 S3 转义边界需要特殊字符库名时,才在**本地/测试**实例上临时构造并清理:

| 编号  | 数据说明                          | 构造方式(含清理)                                                                                                                                            | 唯一标识                        | 清理方式                        |
|-----|-------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------|-----------------------------|
| D01 | MySQL 既有用户库 `<mydb>`(含若干表)     | 复用实例已有库表(无需构造)                                                                                                                                        | 库名 `<mydb>`                  | 无(不写)                       |
| D02 | PG 既有 schema `<schema>`(含若干表)   | 复用实例已有 schema/表(无需构造)                                                                                                                                  | schema 名 `<schema>`          | 无(不写)                       |
| D03 | MySQL 含单引号库名(转义边界,仅本地/测试实例)   | ``CREATE DATABASE `or'ders_demo`; CREATE TABLE `or'ders_demo`.t(id INT);`` 直调 tables 接口 dbName=`or'ders_demo`                                          | 库名含单引号                      | ``DROP DATABASE `or'ders_demo`;`` |
| D04 | PG 含单引号库名(连接参数边界,仅本地/测试实例) | `CREATE DATABASE "pu'blic_demo";` 连入后 `CREATE TABLE t(id INT);` 直调 tables 接口 dbName=`pu'blic_demo`                                                | 库名含单引号(验证 rdbCatalog 传参)                | `DROP DATABASE "pu'blic_demo";` |
| D05 | 非法库名(拒绝边界,无需真实存在)             | 直调 tables 接口,dbName 分别取:含 `\0` 的串、长度 65 的串、空白串                                                                                                          | 后端返回「错误的参数!」且零远程 SQL        | 无(不写)                       |

> D03/D04 仅在**本地 docker 或专属测试实例**执行(云共享实例不宜建特殊字符库);无法构造时 S3 转义/连接边界 SKIP,由 `SpaceServiceImplTest`(MySQL 字面量转义:单引号+反斜杠 doubling、下发 SQL 断言完整比较片段;PG:SQL_PG_TABLES 原样下发 + ctx.rdbCatalog=dbName 捕获断言;`\0`/超长/空白拒绝零远程)覆盖。

## Suites

### S1 Permission · 权限矩阵(P0)

- 风险/目的:`DM_SPACE_READ` 菜单/路由/接口三层闸;纯只读无写端点。P0。
- 初始状态:分别以 B、A 账号登录任意主页面。
- 步骤与预期:
  1. 账号 B:侧边栏**无**「空间占用」入口(「会话管理」「锁分析」等其他条目不受影响);地址栏直连 `/#/space` → 被重定向到默认页,不渲染空间占用页;
  2. 账号 B:开发者工具直接 `POST /api/entry/space/databases`(携带登录 Cookie,body `{"dsId":<M1 id>}`)→ 406,前端统一「权限异常」弹窗文案含缺失权限点;`POST /listInstances`、`POST /tables` 同;
  3. 账号 A:侧边栏出现「空间占用」(锁分析条目之后,数据库图标),进入页面可选实例;
  4. 角色管理页确认「运维诊断」类目下存在「空间占用查看」权限点(en 环境显示 Space Usage View),DBA/Manager 角色默认勾选。
  5. 壳层(账号 A 进入 `/#/space` 后):侧边栏「空间占用」条目呈选中态(有着重底色 `is-active`),页头标题为「空间占用」(en: Space Usage),**不是**「配置」。
- 恢复:如为测试临时授权,在角色管理页还原。

### S2 Main Flow · 摘要条 + 库级列表(P0,MySQL 与 PG 各一遍)

- 风险/目的:双方言库级主链路(下拉→加载→摘要条全量聚合→库级表字段→直连对照→统计语义文案)。P0。
- 初始状态:账号 A 进入 `/#/space`;M1 有 `<mydb>`(D01)、P1 连接库 `<pgdb>` 有 `<schema>`(D02)。
- 步骤与预期:
  1. 实例下拉仅出现 {MySQL, PostgreSQL} ∩ 租户内数据源(X1 白名单外类型不在其中);下拉可搜索;
  2. **MySQL 轮**(选 M1):摘要条出现——第一行「<M1 名> 总占用: X」(X 为人类可读,1024 基至 TB,1 位小数);数据/索引/库数/表数四项;**无**「当前数据库」行(MySQL 无单库 scope);库级表标题=「库级空间占用」,首列列头=「库名」;标题下弱化提示=「MySQL 数值为 InnoDB 估算值…(最长滞后 24 小时)」;
  3. MySQL 库级表逐行核对直连「库级对照 SQL」:库名集合一致(**不含** information_schema/mysql/sys/performance_schema 四系统库)、每库 表数/数据/索引/可回收/总量与直连**同量级**(允许估计偏差)、默认按总量降序;摘要条 数据/索引/总量 = 库级全量各行之和(用直连「摘要对照 SQL」核对量级);摘要「表数」= 各库 table_count 之和;
  4. **PG 轮**(选 P1):摘要条出现——第一行「<P1 名> 总占用: X」;**无**「当前数据库」行(库级已覆盖实例全部库);摘要 数据/索引/表数 三项显示 **`-`**(PG 库级分解列物理不可得,不得显示 0);库数=非模板库个数;库级表标题=「库级空间占用」,首列列头=「库名」;标题下弱化提示=「PostgreSQL 库级为实例上各数据库的总空间占用(物理精确);表数、行数、数据、索引等分解列需选择具体数据库钻取后查看」;
  5. PG 库级表逐行核对直连「库级对照 SQL」:库名集合一致(**全部非模板库**,含 postgres,不含 template0/template1)、total_bytes 与 `pg_database_size` **逐值一致**(物理精确)、表数/行估算/数据/索引/可回收列**全部显示 `-`**;摘要条 total = 直连「摘要对照 SQL」的 Σ pg_database_size(**逐值一致**——旧版「schema 聚合与 pg_database_size 的口径差值说明」已随裁决废弃);
  6. 数字格式化核对:任一 >1GB 的库显示如「1.5 GB」,>1MB 显示「x.x MB」,<1024B 显示「N B」,均为 1024 进制、1 位小数;
  7. 手动「刷新」→ 库级表 loading 后回到第 1 页,数据仍在;未选实例时刷新按钮禁用;库/schema Select 在未选实例时禁用;
  8. 陈旧响应丢弃:选择 M1 后立刻(请求未返回时)切到 P1 → 页面最终展示 P1 数据,M1 的迟到响应不覆盖;切换/清空实例时库级表与刷新按钮不残留 loading,且库/schema Select、表级区块、表名过滤一并重置;
  9. 页面无 privilegeHint Alert(B4 size 函数无掩码语义,双方言均不应出现权限提示条)。
- 恢复:无(只读)。

### S3 Main Flow · 表级钻取 + 标识符转义边界(P0)

- 风险/目的:库 Select 驱动表级明细;表名过滤/排序/分页;dbName 安全面(MySQL 单引号/反斜杠字面量转义、PG 仅作连接参数永不进 SQL、`\0`/超长/空白拒绝)。P0。
- 初始状态:账号 A,已选 M1(S2 之后);`<mydb>` 有多张表。
- 步骤与预期:
  1. 未选库时:**表级区块完全不渲染**(非假空态、非报错);库/schema Select 的选项来自 databases 结果的库名集合;
  2. 选择 `<mydb>` → 表级区块出现,标题「表级空间占用」,列=表名/行数估计/数据/索引/可回收/总量(**PG 轮前置 Schema 列**,列出该库全部 schema 的表),默认总量降序;逐行核对直连「表级对照 SQL」(MySQL 同量级/PG 逐值一致;PG 可回收列 `-`);
  3. 表名过滤:在工具栏「按表名过滤」输入既有表名片段 → 表级表**本地**实时过滤(不发新请求,Network 无新增 `/tables`);清空过滤 → 恢复全量;过滤只作用于表级表,**不影响摘要条与库级表**(全量聚合纪律);
  4. 切换库/schema Select 到另一个库 → 表级数据刷新、表名过滤清空、表级分页回到第 1 页;
  5. 清空库 Select(×)→ 表级区块隐藏,表名过滤输入禁用;
  6. **转义边界(环境可构造 D03/D04 时执行,否则 SKIP 注明单测覆盖)**:
     - MySQL 含单引号库名(D03):直调 `POST /api/entry/space/tables` body `{"dsId":<E1 id>,"dbName":"or'ders_demo"}` → 返回该库表数据或正常空态(**不报 SQL 语法错、不注入**);后端下发 SQL 中库名为单引号字符串字面量且内部单引号 doubling(`table_schema = 'or''ders_demo'`)——由单测断言,浏览器侧只验证「不报错、正常返回」;
     - PG 含单引号库名(D04):直调 dbName=`pu'blic_demo` → 会话正常连到该库并返回其表列表(dbName 仅作 rdbCatalog 连接参数,**永不进 SQL**——下发语句固定为 SQL_PG_TABLES,由单测断言;浏览器侧只验证「不报错、正常返回」);
     - 非法库名(D05):直调 dbName 含 `\0` / 长度 65 / 空白 → 统一错误队列「错误的参数!」,且 Network 仅一次 `/tables` 请求、后端**零远程 SQL**(createSession 未发生);
  7. 前端 dbName 仅来自 databases 下拉(不手填);即便伪造直调,后端校验+转义兜底(第 6 步)。
- 恢复:D03/D04 若构造则 `DROP`;只读流程无需恢复。

### S4 Boundaries · 空态与边界(P1)

- 风险/目的:空库/空实例/PG free 列/不可达实例的空态与复位。P1。
- 初始状态:账号 A,`/#/space`。
- 步骤与预期:
  1. **实例无用户库**:选中一个只有系统库的 MySQL 实例(或空 PG 库)→ 库级表显示空态「暂无库空间数据」(非报错),摘要条不出现(dbs 为空);
  2. **库无表**:选择一个无 BASE TABLE 的库(MySQL)/无表的 schema(PG)→ 表级区块显示空态「该库暂无表」;
  3. **PG 可回收列**:P1 库级与表级「可回收」列全部 `-`(free_bytes 恒 null);对照 MySQL 该列有值(DATA_FREE);
  4. **空实例态**:使用无 {MySQL, PostgreSQL} 数据源的账号进入页面 → 下拉为空,居中灰字提示「暂无可用实例(空间占用目前支持 MySQL 8.0 与 PostgreSQL 数据源)」,不报错;
  5. **实例不可达**:停止目标实例后选中 → 连接失败业务错误提示(统一错误队列,createSession 失败路径已含 handleException),页面不白屏、loading 正常复位;
  6. **白名单外兜底**(X1 存在时):X1 不出现在下拉;直调 databases `{dsId:<X1 id>}` → 「该数据源类型暂不支持空间占用查看」;
  7. **跨租户/不存在 dsId**:直调 databases `{dsId: 99999999}` → 「数据源不存在或无权限」,且后端无任何远程 SQL 发出(Network 仅一次请求);
  8. 清空已选实例(下拉 ×)→ 摘要条/库级表/表级区块全部清空、库 Select 与表名过滤禁用、无残留 loading、无 JS 报错。
- 恢复:恢复被停实例。

### S5 Repeat And Consistency · 排序/过滤/分页纪律(P1)

- 风险/目的:本地排序作用于全量、分页为渲染切片、过滤不影响摘要、数据收缩页码收敛。P1。
- 初始状态:账号 A,选中 M1,库级表有 >1 页数据(或调小每页条数制造多页);已选 `<mydb>` 且表级多行。
- 步骤与预期:
  1. 库级表:总量列头默认降序箭头;点击各数值列头(表数/行数估计/数据/索引/可回收/总量)→ 升/降序切换,排序作用于**全量**(跨页全局有序);点击库名/Schema 列头 → 按名称字典序排序;
  2. 库级分页:默认 50/页,页码/每页条数(20/50/100/500)切换生效,总条数 = 接口全量库数;翻页不重新请求(Network 无新增 `/databases`);
  3. 表级表:同款排序/分页;表名过滤 + 排序 + 分页三者叠加正确(过滤后集合上排序,再分页切片);
  4. **过滤不影响摘要**:输入表名过滤使表级表只剩少数行 → 摘要条 数据/索引/总量/库数/表数**不变**(仍为库级全量聚合);清空过滤摘要仍不变;
  5. **数据收缩页码收敛**:停在库级表第 2 页,切换到库数更少的实例(或刷新后库数减少)→ 页码自动收敛到有效末页,不停留在空白页;
  6. 快速连续操作:连续切换实例/库、双击刷新 → 无旧响应覆盖新状态、无重复 loading 卡死、无 JS 报错(陈旧响应丢弃生效)。
- 恢复:无(只读)。

## Cleanup

- B4 为**只读快照**功能,正常流程(S1/S2/S4/S5 及 S3 步骤 1-5、7)**不产生任何写操作**,Cleanup = 无。
- 仅当 S3 步骤 6 在本地/测试实例构造了 D03/D04 特殊字符库时:`DROP DATABASE`/`DROP SCHEMA ... CASCADE` 清理;D05 非法库名直调不产生数据,无需清理。
- 若 S4-5 停过实例、S1 临时调整过角色权限:恢复实例、在角色管理页还原授权。
- 关闭多余测试标签页。

## Skip Conditions

- **环境无 MySQL 8.0 实例**:S2(MySQL 轮)/S3(MySQL 钻取)/S4-1~3(MySQL 部分)/S5(MySQL)跳过;原因=无可用实例;替代验证=`SpaceServiceImplTest` 已覆盖 M1/M2 映射(列名大小写混杂/null free_bytes)、系统 schema 排除、BASE TABLE 过滤、转义、超时/语句错误翻译;覆盖缺口=真实 JDBC 链路与 InnoDB 估计值量级未在浏览器核对。
- **环境无 PG 15 实例**:S2(PG 轮)/S3(PG 钻取)/S4-3(PG free 列 `-`)/S2-4~5(schema 措辞 + current_database 标注)跳过;原因=无可用实例;替代验证=单测 P1/P2 映射、`current_database()` 探测先发、`GREATEST(c.reltuples,0)` 归零断言、free_bytes 恒 null;覆盖缺口=真实 PG `pg_*_size` 逐值一致性与单库 scope 文案。
- **无法构造特殊字符库名(云共享实例不宜建库)**:S3 步骤 6(D03/D04/D05 转义边界)SKIP;原因=需 CREATE DATABASE/SCHEMA 写权限且共享实例不宜改;替代验证=`SpaceServiceImplTest` 转义四形态——反引号 doubling(`` `or``ders` ``)/双引号 doubling(`"pu""blic"`)/`\0` 拒绝且 `createSession` never + `executedSqls` 为空/超长(65)拒绝且零远程;覆盖缺口=真实数据库对特殊字符标识符的接受度未在浏览器验证。
- **仅生产实例可用**:S3 步骤 6 的造库场景必须 SKIP(不得在生产建特殊字符库);主流程 S1/S2/S4/S5 为只读,可在生产实例安全执行(仅浏览空间,不写)。
- **无白名单外实例(X1)**:S4-6 SKIP;替代验证=单测 MariaDB → SPACE_DS_TYPE_UNSUPPORTED + `verify never createSession`。
- **无法构造实例不可达/超时**:S4-5 及超时路径 SKIP;替代验证=单测 createSession 挂起 / 语句挂起 → SPACE_QUERY_TIMEOUT,语句 ERROR → SPACE_QUERY_FAILED(携带原始错误)。
