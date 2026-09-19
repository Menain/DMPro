# Lock Analysis (锁分析:锁等待链 + 运行中事务 + 最近死锁)

## Purpose

验证锁分析页(只读)的**权限矩阵、双方言(MySQL 8.0 / PostgreSQL 15)锁等待链主流程、死锁区块(仅 MySQL)、长事务高亮与降级/边界路径**。主要防止的回归:

- 权限闸失效:无 `DM_LOCK_READ` 的账号能看到菜单/直连路由/调通接口;
- 方言分派断裂:MySQL 实例发出 PG SQL(或反之)、PG 实例渲染死锁区块(假空态)、MySQL 5.7/MariaDB 实例未被明确拒绝;
- 锁等待链数据错误:等待/阻塞双方 pid、锁对象、时长映射错位,阻塞 SQL 为空时缺「空闲事务持锁」弱化提示;
- 降级语义丢失:performance_schema=OFF 时直接查 data_locks 把"看不到"误报成"无锁等待"(应显示区块 Alert)、无 PROCESS 账号未翻译成授权指引、单区块失败拖垮整页(应区块级降级);
- 死锁原文截断:LATEST DETECTED DEADLOCK 段不完整(displayChars 透传回归)或复制全文失效;
- 前端纪律回归:本地分页/排序、自动轮询定时器泄漏、陈旧响应覆盖新实例数据。

## Scope

- 页面:`/#/lockAnalysis`(`frontend/src/views/lockAnalysis/index.vue`)。
- 入口:侧边栏主菜单「锁分析」(`utils/buildSidebarMenu.js`,条件 `myAuth.includes('DM_LOCK_READ') && !isDesktop`,session 条目之后);路由 `meta: { requiredAuth: 'DM_LOCK_READ' }`(`router/index.js` beforeEach 闸)。
- 接口(`LockAnalysisController`,前缀 `/api/entry/lockAnalysis`,均 `@RequestAuth(DM_LOCK_READ)`):
  - `POST /listInstances`:租户内 ∩ 白名单 {MySQL, PostgreSQL} ∩ 未删除的数据源 `[{dsId, dsName, dsType}]`,按名称排序;
  - `POST /overview`:`{dsId}` → `{branch, version, deadlockSupported, lockWaits[], transactions[], deadlock, deadlockAbsent, privilegeHint, partialErrors{}}`;纯只读,**不落操作审计**(平台只读接口先例);一次刷新=一个短诊断会话内串行 SQL(MySQL 5 条:VERSION → @@performance_schema → 锁等待 → innodb_trx → SHOW ENGINE INNODB STATUS;PG 2 条:pg_blocking_pids 锁等待 → pg_stat_activity 活跃事务),单条 10s 超时。
- 错误契约:白名单外类型 → 「该数据源类型暂不支持锁分析」;跨租户/不存在 dsId → 「数据源不存在或无权限」(零远程调用);MySQL 版本 <8.0/含 MariaDB/解析失败 → 「锁分析仅支持 MySQL 8.0 及以上版本(当前实例版本:…)」整体拒绝;MySQL 锁视图 access/command denied → 「查询锁信息失败:…PROCESS 权限…原始错误:…」整体失败;P_S=OFF → `partialErrors.lockWaits` 区块 Alert(事务/死锁正常);单区块非权限失败/超时 → 该区块 Alert 降级,其余正常;PG 无 pg_monitor → 页顶黄色 `privilegeHint` Alert(query 列显示 `<insufficient privileges>` 掩码,列表不中断)。
- 不覆盖:页内 kill(一期不做,处置走会话管理页 `tests/frontend/session/session_manage.md`)、PG 死锁历史(服务器日志侧,平台不采集)、锁等待历史趋势(实时快照)、MDL 专项、分布式数据库(TiDB/PolarDbX/OceanBase 不在白名单)、后端通道 C 内部行为(由 `LockAnalysisServiceImplTest` 27 个用例覆盖:方言分派/版本防御 4 形态/双方言映射/死锁段提取 4 形态/P_S 降级/权限翻译/超时/displayChars=131072)。

## Preconditions

- 后端 8222 运行且包含锁分析端点(`:boot-alone:installDist` 重建后启动,cwd=backend/);前端 8080 dev 或已构建产物;Chrome 已登录。
- 实例矩阵(尽量齐备,缺项按 Skip Conditions 处理):
  - M1:**MySQL 8.0** 实例,DS 账号具备 PROCESS 权限(云 RDS 高权限账号即可),performance_schema=ON(默认);
  - P1:**PostgreSQL 15** 实例,DS 账号已授 pg_monitor(对照组);
  - P2(可选):PG 实例的 DS 账号**未授** pg_monitor(掩码 hint 场景;云 RDS 默认多未授,可临时 REVOKE/GRANT 切换);
  - N1(可选):MySQL 实例 DS 账号**无 PROCESS**(仅 SELECT/UPDATE 业务库权限)→ 权限翻译场景;
  - X1(可选):白名单外数据源(MariaDB/TiDB/Oracle 等任一,环境中已有即可);
  - L1(可选):MySQL 5.7 或 MariaDB 实例(版本防御实测;不在下拉中,需直调接口验证)。
- 上述实例绑定的 worker 在线。
- 账号矩阵(可用角色管理页临时授权,测后恢复):
  - A:Manager 或 DBA 角色(默认含 DM_LOCK_READ);
  - B:子账号,角色不授「锁等待与死锁分析查看」(如默认 Developers)。
- 死锁/锁等待构造需**两个以上独立数据库客户端会话**(mysql CLI / psql 或平台 SQL 工作台多标签);仅允许在本地/测试实例执行。

## Test Data

| 编号  | 数据说明              | 构造方式(含清理)                                                                                                                                                                                                                             | 唯一标识                                    | 清理方式                     |
|-----|-------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------|--------------------------|
| D01 | MySQL 锁等待对(A 持锁/B 等待) | mysql CLI 两个会话。准备:`CREATE DATABASE IF NOT EXISTS lock_demo; CREATE TABLE lock_demo.t_lock (id INT PRIMARY KEY, c INT) ENGINE=InnoDB; INSERT INTO lock_demo.t_lock VALUES (1,0),(2,0);` 会话A:`BEGIN; UPDATE lock_demo.t_lock SET c=c+1 WHERE id=1;`(不提交) 会话B:`BEGIN; UPDATE lock_demo.t_lock SET c=c+1 WHERE id=1;`(挂起等待) | 锁等待行 lock_table=`lock_demo.t_lock`        | 会话A/B `ROLLBACK`;`DROP DATABASE lock_demo;` |
| D02 | PG 锁等待对           | psql 两个会话。准备:`CREATE TABLE t_lock_demo (id INT PRIMARY KEY, c INT); INSERT INTO t_lock_demo VALUES (1,0),(2,0);` 会话A:`BEGIN; UPDATE t_lock_demo SET c=c+1 WHERE id=1;` 会话B:`BEGIN; UPDATE t_lock_demo SET c=c+1 WHERE id=1;`(挂起)              | 锁等待行 lock_table=`t_lock_demo`(或 `public.t_lock_demo`) | 会话A/B `ROLLBACK`;`DROP TABLE t_lock_demo;` |
| D03 | MySQL 死锁(仅 M1)    | 在 D01 表上另开会话C/D。C:`BEGIN; UPDATE lock_demo.t_lock SET c=c+1 WHERE id=1;` D:`BEGIN; UPDATE lock_demo.t_lock SET c=c+1 WHERE id=2;` C:`UPDATE lock_demo.t_lock SET c=c+1 WHERE id=2;`(等 D) D:`UPDATE lock_demo.t_lock SET c=c+1 WHERE id=1;` → 立即报 Deadlock found,一方被回滚 | 死锁原文含两事务 SQL 与 `WE ROLL BACK TRANSACTION` | C/D `ROLLBACK`(死锁记录留存于 INNODB STATUS 直到实例重启,无需清理) |
| D04 | 长事务(≥60s)         | 复用 D01/D02 的会话A:持锁不提交挂 70s 以上(或事务内执行 `SELECT SLEEP(70)` / `SELECT pg_sleep(70)`)                                                                                                                                                             | 事务行 duration ≥ 60                         | 会话A `ROLLBACK`             |
| D05 | 无 PROCESS 的 MySQL DS 账号(N1) | `CREATE USER 'lock_nopriv'@'%' IDENTIFIED BY '<随机强口令>'; GRANT SELECT, UPDATE ON lock_demo.* TO 'lock_nopriv'@'%';` 以该账号注册/改配一个 MySQL 数据源                                                                                                              | overview 整体报 PROCESS 授权指引                 | `DROP USER 'lock_nopriv'@'%';` 数据源改回原账号 |
| D06 | 无 pg_monitor 的 PG DS 账号(P2) | 数据源账号不授 pg_monitor;对照场景临时 `GRANT pg_monitor TO "<账号>"`,测后 `REVOKE pg_monitor FROM "<账号>"`                                                                                                                                                  | 他人会话 SQL 列 = `<insufficient privileges>` + 页顶黄色 Alert | REVOKE 恢复原状              |

## Suites

### S1 Permission · 权限矩阵(P0)

- 风险/目的:`DM_LOCK_READ` 菜单/路由/接口三层闸;纯只读无写端点。P0。
- 初始状态:分别以 B、A 账号登录任意主页面。
- 步骤与预期:
  1. 账号 B:侧边栏**无**「锁分析」入口(「会话管理」等其他条目不受影响);地址栏直连 `/#/lockAnalysis` → 被重定向到默认页,不渲染锁分析页;
  2. 账号 B:开发者工具直接 `POST /api/entry/lockAnalysis/overview`(携带登录 Cookie,body `{"dsId":<M1 id>}`)→ 406,前端统一「权限异常」弹窗文案含缺失权限点;`POST /listInstances` 同;
  3. 账号 A:侧边栏出现「锁分析」(session 条目之后,锁形图标),进入页面可选实例;
  4. 角色管理页确认「运维诊断」类目下存在「锁等待与死锁分析查看」权限点(en 环境显示 Lock & Deadlock Analysis View),DBA/Manager 角色默认勾选。
- 恢复:如为测试临时授权,在角色管理页还原。

### S2 Main Flow · 锁等待链(P0,MySQL 与 PG 各一遍)

- 风险/目的:双方言锁等待主链路(下拉→加载→等待链字段→复制→排序→分页→刷新/轮询→回空态)。P0。
- 初始状态:账号 A 进入 `/#/lockAnalysis`;D01(MySQL 轮)/D02(PG 轮)已构造且 B 处于等待。
- 步骤与预期:
  1. 实例下拉仅出现 {MySQL, PostgreSQL} ∩ 租户内数据源(X1 白名单外类型不在其中;MariaDB/TiDB 不出现);下拉可搜索;
  2. 选择 M1(或 P1)后三个区块加载:「锁等待」「运行中事务」始终渲染;「最近死锁」折叠面板**仅 MySQL 实例出现**,PG 实例整块不渲染(非空态、非报错);
  3. 锁等待表恰有一行(D01/D02):等待会话ID = 会话B 连接 id(与 `SHOW PROCESSLIST`/`pg_stat_activity` 中执行 UPDATE 的会话一致)、等待时长(秒) ≥ 0 且随刷新递增、等待SQL 含 `UPDATE`、锁对象 = `lock_demo.t_lock.PRIMARY`(MySQL,含索引)/ `t_lock_demo` 或 `public.t_lock_demo`(PG relation)、类型/模式 = `RECORD / X`(MySQL)或 `tuple / ExclusiveLock`(PG)、阻塞会话ID = 会话A 连接 id、**阻塞SQL 列显示弱化灰字「空闲事务持锁」**(会话A 事务内空闲,query 为 NULL)、阻塞事务启动时间为会话A BEGIN 时刻;
  4. 等待时长列头默认降序箭头;点击列头 → 升/降序切换,排序作用于全量数据(跨页全局有序);
  5. 复制:点击「阻塞会话ID」旁复制图标 → 成功 toast,粘贴值 = 会话A pid(可携带到会话管理页 kill,本页面**无** kill 按钮);等待会话ID、事务区会话ID 复制同样生效;
  6. SQL 列 >80 字符时显示链接色截断文本,悬浮 Poptip 全文可滚动、可复制;≤80 字符纯文本渲染(省略号+原生 title);
  7. 长 SQL 场景可选:会话B 改执行带长注释的 UPDATE(>80 字符)后复核第 6 步;
  8. 手动「刷新」→ 表格 loading 后回到第 1 页,数据仍在(B 仍等待);未选实例时刷新按钮禁用;
  9. 打开「自动刷新」→ Network 每 ~10s 一次 `/api/entry/lockAnalysis/overview`(静默无 loading 闪烁、不打断当前分页页码);切换到其他路由(如 `/#/sql`)→ 周期请求停止(定时器已清理);
  10. 会话A 执行 `COMMIT` → 手动刷新:锁等待表回到空态「当前无锁等待」(常态非错误,无 Alert);B 会话的 UPDATE 完成;
  11. 陈旧响应丢弃:选择 M1 后立刻(请求未返回时)切到 P1 → 页面最终展示 P1 数据,M1 的迟到响应不覆盖;切换/清空实例时表格与刷新按钮不残留 loading;
  12. **PG 轮补充**(P2 或临时 REVOKE pg_monitor):页顶出现黄色 Alert(后端已翻译文案,含 `GRANT pg_monitor TO` 指引),锁等待/事务列表仍可用,他人会话 SQL 列显示 `<insufficient privileges>`;GRANT 后刷新 → Alert 与掩码消失(测后 REVOKE 还原);MySQL 实例任何情况下无此 Alert。
- 恢复:D01/D02 会话 `ROLLBACK`;关闭自动刷新;回收临时 pg_monitor 授权。

### S3 Main Flow · 死锁区块(P0,仅 MySQL;PG 断言区块不渲染)

- 风险/目的:LATEST DETECTED DEADLOCK 原文提取完整性(displayChars 透传)、折叠面板交互、复制全文、无死锁空态。P0。
- 初始状态:账号 A,选中 M1;D03 已构造(一方被回滚);**注意死锁记录留存到实例重启**,新实例/重启后实例才有「未记录死锁」空态。
- 步骤与预期:
  1. M1 页面出现「最近死锁」折叠面板,**默认收起**;点击展开;
  2. 面板内 pre 等宽原文含:`*** (1) TRANSACTION:`、C 与 D 的两条 `UPDATE lock_demo.t_lock ...` 语句、`*** (2) HOLDS THE LOCK(S):`(或 WAITING FOR)、`*** WE ROLL BACK TRANSACTION (1|2)`;文本完整无中段截断(若结尾带省略标记说明被列字节上限截断,记录实际长度);
  3. 「复制全文」按钮 → 成功 toast,粘贴内容与 pre 原文一致(含换行);
  4. 无死锁记录的空态:使用**重启后未发生过死锁**的 MySQL 实例(或本地 docker 重启 mysql 容器)→ 展开面板显示灰字「自实例启动以来未记录死锁」(非报错、非假原文);
  5. **PG 断言**:选中 P1 → 页面 DOM 中不存在「最近死锁」区块(`deadlockSupported=false`,无假空态);
  6. 死锁区块与 partialErrors:构造死锁查询失败(如 N1 无 PROCESS 账号数据源,见 S5-3)→ 该实例 overview 整体报权限错误;若仅死锁区块单独失败(不易构造,可由后端单测覆盖)→ 面板内 Alert、其余区块正常。
- 恢复:C/D 会话 `ROLLBACK`;死锁记录无需清理(随实例重启消失)。

### S4 Transactions · 运行中事务与长事务高亮(P1)

- 风险/目的:双方言事务列表映射 + ≥60s 行高亮常量。P1。
- 初始状态:账号 A;D04(会话A 持锁事务挂 ≥70s)在跑;可另开一个短事务(会话E `BEGIN; UPDATE ... id=2;` 立即保持)作对照。
- 步骤与预期:
  1. M1「运行中事务」区块:会话A 行可见——事务ID 非空、状态 `RUNNING`、会话ID = A pid(可复制)、启动时间 = BEGIN 时刻、持续时长(秒) ≥ 60 且**整行浅黄警示底色高亮**(暗色主题下同样可辨);锁定行数/修改行数 ≥ 1;SQL 列为空显示 `-`(事务内空闲);
  2. 对照行(会话E,持续 <60s)**无高亮**;
  3. 平台自身诊断连接说明:MySQL 事务快照可能短暂包含诊断会话自身(query 为诊断 SQL 文本,持续时长 0),属正常现象,不作为断言失败;
  4. P1「运行中事务」:会话A 行——状态 `idle in transaction`(或 active)、**事务ID/锁定行数/修改行数三列显示 `-`**(PG 无等价统计)、会话ID/启动时间/持续时长/SQL 正常;≥60s 同样高亮;
  5. PG 后台进程(checkpointer 等)**不出现**在事务列表(仅 client backend 且 xact_start 非空);
  6. 事务表本地分页:默认 50/页,页码/每页条数切换生效,总条数 = 接口全量;自动轮询时页码保持、数据收缩时自动收敛到有效末页;
  7. 会话A `COMMIT` 后刷新 → 该行消失,高亮行不复存在。
- 恢复:D04/会话E `ROLLBACK`。

### S5 Degradation And Boundaries · 降级与边界(P0)

- 风险/目的:版本防御、P_S 降级、权限翻译、白名单/租户兜底、超时。P0。
- 初始状态:账号 A,`/#/lockAnalysis`;按场景准备 N1/X1/L1/P_S-OFF 实例。
- 步骤与预期:
  1. **P_S 关闭**(本地 docker:`mysqld --performance-schema=OFF` 重启,或 my.cnf 设置):选中该实例 → 「锁等待」区块内出现黄色 Alert(文案含 `performance_schema` 未开启与开启方法),锁等待表无数据但**不误报「当前无锁等待」为正常空态**(Alert 优先可见);「运行中事务」「最近死锁」区块正常返回数据;
  2. **5.7/MariaDB 版本防御**(L1 存在时):L1 不出现在下拉;开发者工具直调 `POST /overview` `{dsId:<L1 id>}` → 统一错误队列提示「锁分析仅支持 MySQL 8.0 及以上版本(当前实例版本:5.7.xx / xx-MariaDB)」;页面不白屏;
  3. **无 PROCESS 翻译**(N1):选中 N1 → 统一错误队列提示「查询锁信息失败:数据源连接账号权限不足,…PROCESS 权限(GRANT PROCESS ON *.* TO '<账号>'@'%')。原始错误:Access denied…」;三个区块均无数据(整体失败,非区块降级);为 N1 账号 `GRANT PROCESS ON *.* TO ...` 后刷新 → 正常展示(测后回收);
  4. **白名单外兜底**(X1):X1 不出现在下拉;直调 overview `{dsId:<X1 id>}` → 「该数据源类型暂不支持锁分析」;
  5. **跨租户/不存在 dsId**:直调 overview `{dsId: 99999999}` → 「数据源不存在或无权限」,且后端无任何远程 SQL 发出(Network 仅一次 overview 请求);
  6. **超时降级**(可构造时):目标库挂起使单条锁查询 >10s → 对应区块 Alert「查询锁信息超时,请稍后重试」,其余区块正常;若版本探测挂起 → 整体超时错误「查询锁信息超时,请稍后重试」;
  7. **空实例态**:使用无 {MySQL, PostgreSQL} 数据源的账号进入页面 → 下拉为空,居中灰字提示「暂无可用实例(锁分析目前支持 MySQL 8.0 及以上与 PostgreSQL 数据源)」,不报错;
  8. **实例不可达**:停止目标实例后选中 → 连接失败业务错误提示(统一错误队列),页面不白屏、loading 正常复位;
  9. 清空已选实例(下拉 ×)→ 三分区数据清空、无残留 loading、无 JS 报错。
- 恢复:恢复被停实例;回收 N1 的临时 GRANT;P_S 实例恢复原配置。

## Cleanup

1. 所有构造会话(A/B/C/D/E)执行 `ROLLBACK`(或 `COMMIT`);删除 `lock_demo` 库 / `t_lock_demo` 表;
2. 删除临时账号(D05 `DROP USER`;D06 `REVOKE pg_monitor`),数据源配置改回原账号;
3. 若为 S1 临时调整过角色权限,在角色管理页恢复原授权;
4. 关闭页面自动刷新开关,关闭多余测试标签页,确认无遗留周期请求;
5. 若 S5 停过实例或改过 P_S 配置,恢复原状。

## Skip Conditions

- **环境无 MySQL 8.0 实例**:S2(MySQL 轮)/S3/S4-1~3/S5-1~3 跳过;原因=无可用实例;替代验证=`LockAnalysisServiceImplTest` 已覆盖 MySQL 分派 SQL 序列、8.0 映射、死锁段提取 4 形态、P_S 降级、权限翻译;覆盖缺口=真实 JDBC 链路与 INNODB STATUS 大文本透传未在浏览器验证。
- **环境无 PG 15 实例**:S2(PG 轮)/S3-5/S4-4~5/S2-12 跳过;原因=无可用实例;替代验证=单测 PG 映射、pg_blocking_pids 分派、掩码 hint、区块降级不升级;覆盖缺口=真实 PG 掩码与 LATERAL 查询行为。
- **无 5.7/MariaDB 实例(L1)**:S5-2 SKIP;原因=项目规划不接低于 8.0 的 MySQL,测试环境常无;替代验证=单测版本防御 4 形态(8.0.32 通过/5.7.44/10.6.12-MariaDB/garbage 均拒绝且零后续 SQL)。
- **performance_schema 不可关闭(云 RDS 不允许改参)**:S5-1 SKIP;原因=需重启参数,云实例不可控;替代验证=单测 ps_enabled=0 → 不下发锁等待 SQL + partialErrors.lockWaits;覆盖缺口=真实 OFF 实例的 Alert 展示。
- **无法构造无 PROCESS 的 DS 账号(N1,共享实例不宜改账号)**:S5-3 SKIP;替代验证=单测 command denied/access denied → 整体 LOCK_QUERY_NO_PRIVILEGE 升级(含原始错误携带断言)。
- **无法构造目标库挂起**:S5-6 SKIP;替代验证=单测超时三分支(createSession 挂起/探测挂起→整体失败,区块挂起→降级文案)。
- **死锁空态需重启实例**:S3-4 在共享测试实例上 SKIP(重启影响面大);原因=死锁记录留存至重启;替代验证=本地 docker mysql 重启后复测,或单测无标记文本 → deadlockAbsent=true。
- **仅生产实例可用**:一切锁等待/死锁/长事务构造场景必须 SKIP,不得在生产库执行 BEGIN 不提交类构造;替代验证=S1 权限闸与 S5-4/5/7 只读拒绝路径(零副作用)。
