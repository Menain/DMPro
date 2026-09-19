# Session Manage (会话管理:列表 + Kill)

## Purpose

验证实例会话管理页的**权限矩阵、会话列表(过滤/排序/刷新/自动轮询)与 kill 高危流程(二次确认、保护规则、操作审计)**。主要防止的回归:

- 权限闸失效:无 `DM_SESSION_READ` 的账号能看到菜单/直连路由/调通接口,或有 READ 无 `DM_SESSION_KILL` 的账号出现「终止」按钮;
- 保护矩阵前端层失效:受保护行(平台自身连接/系统用户/复制线程)的「终止」按钮变为可点,或 Poptip 保护原因丢失;
- kill 高危链路退化:二次确认弹窗缺会话要素/不可撤销警示、共用账号强提示丢失、成功后列表不刷新、操作审计无记录;
- 列表链路断裂:实例下拉、连接统计条、本地过滤/排序/分页、手动刷新、10s 自动轮询(含离开页面后定时器泄漏)。

## Scope

- 页面:`/#/session`(`frontend/src/views/session/index.vue`)。
- 入口:侧边栏主菜单「会话管理」(`utils/buildSidebarMenu.js`,条件 `myAuth.includes('DM_SESSION_READ') && !isDesktop`);路由 `meta: { requiredAuth: 'DM_SESSION_READ' }`(`router/index.js` beforeEach 闸)。
- 接口(`SessionManageController`,前缀 `/api/entry/session`):
  - `POST /listInstances`(DM_SESSION_READ):当前账号权限 ∩ 方言白名单的数据源 `[{dsId, dsName, dsType}]`;
  - `POST /list`(DM_SESSION_READ):`{dsId}` → `{selfConnectionId, sessions:[{id,user,host,db,command,time,state,info,killable,protectedReason,sharedAccount}], privilegeHint}`,全量返回,前端本地过滤+排序+本地分页渲染(默认 50/页,分页只影响渲染切片,统计条基于全量);`privilegeHint` 仅 PostgreSQL 可能非空(数据源账号未授 pg_monitor 时返回已 i18n 文案,前端表格上方黄色 Alert 直接展示),MySQL 家族恒 null;
  - `POST /kill`(DM_SESSION_KILL + `checkOpPassword=true`):`{dsId, sessionId}`,成功空 data;业务失败为 code=0 信封 + 服务端 i18n msg,经统一错误队列弹窗展示。
- 数据源白名单:MySQL 协议家族 9 类(MySQL、MariaDB、TiDB、PolarDbMySQL、PolarDbX、OceanBase(MySQL 模式)、GoldenDBMySQL、KingbaseESMySQL、AdbForMySQL)+ **PostgreSQL**(design §13 扩展轮)。方言由后端按 dsType 分派:MySQL 家族 `SHOW FULL PROCESSLIST` / `KILL <id>`;PG `pg_stat_activity` 投影(列别名对齐同一 VO,command 列=backend_type,state 列=PG state)/ `SELECT pg_terminate_backend(<id>)`(单行布尔,f=目标已消失)。白名单外类型不出现在下拉;接口层兜底拒绝。
- 不覆盖:后端通道 C(QueryService 短会话)内部行为与保护矩阵服务端判定逻辑(由后端单测 `SessionManageServiceImplTest` 覆盖,含 5 个 PG 方言用例)、Oracle/SQLServer 系等其他方言(不支持)、KILL QUERY 轻量档(未实现)、操作密码全局开关的配置流程(见 system 相关文档)。

## Preconditions

- 后端 8222 运行且包含会话管理端点(`:boot-alone:installDist` 重建后启动);前端 8080 dev 或已构建产物;Chrome 已登录。
- 至少一个**白名单类型**数据源已配置且可连通(本地/测试环境 MySQL 即可),其绑定 worker 在线。
- PG 分支场景另需:一个 **PostgreSQL 15** 数据源(本地或腾讯云/阿里云 RDS PG)可连通;
  - pg_monitor:数据源账号**未授** pg_monitor 时,他人会话 SQL 列显示 PG 原生掩码 `<insufficient privileges>`,且表格上方出现黄色 Alert(文案含 `GRANT pg_monitor TO "<账号>"` 指引);授予后刷新,掩码与 Alert 均消失;
  - kill 权限:PG 终止**其他用户**的会话要求数据源账号为超级用户、目标会话所属角色的成员或 pg_signal_backend 角色成员(云 RDS 使用厂商提供的高权限管理账号授权)。缺失时 kill 返回「终止会话失败:数据源连接账号权限不足…」(SESSION_KILL_NO_PRIVILEGE_PG,附原始错误)。
- 账号矩阵(可用角色管理页临时授权,测后恢复):
  - A:Manager 或 DBA 角色(默认含 DM_SESSION_READ + DM_SESSION_KILL);
  - B:子账号,角色仅授「会话管理查看」(DM_SESSION_READ);
  - C:子账号,角色不授任何会话权限(如默认 Developers)。
- 若平台全局开启操作密码(`config.isOppassword()`),账号 A 需已设置操作密码,kill 时按平台既有弹窗流程输入。
- 数据源连接账号权限:kill **其他用户**的会话要求 DS 配置账号具备 MySQL SUPER 或 CONNECTION_ADMIN 权限(平台角色 Manager/DBA 与此无关)。缺失时 kill 返回「数据源连接账号权限不足…」业务提示(SESSION_KILL_NO_PRIVILEGE,附原始 DB 错误)。S3 成功路径需测试实例的 DS 账号已授权,或目标会话即以 DS 账号自身发起(同账号线程可杀)。
- kill 为**真实高危写操作**:仅允许在本地/测试环境实例执行,严禁对生产实例 kill。

## Test Data

| 编号  | 数据说明                        | 构造方式                                                                 | 唯一标识                          | 清理方式                     |
|-----|-----------------------------|----------------------------------------------------------------------|-------------------------------|--------------------------|
| D01 | 可杀测试会话(长查询)                | MySQL 系:用 mysql CLI 或平台 SQL 工作台执行 `SELECT SLEEP(300)`;PG:用 psql 执行 `SELECT pg_sleep(300)`(连接账号任意,非 DS 配置账号更佳) | 会话行 info 含 `SLEEP(300)` / `pg_sleep(300)` | kill 场景本身即清理;或客户端 Ctrl+C |
| D02 | 共用账号会话(sharedAccount=true)  | 用数据源配置页显示的 DS 账号另开一条连接执行 `SELECT SLEEP(300)`(PG 为 `SELECT pg_sleep(300)`)      | 会话行 user = DS 配置账号            | 同上                       |
| D03 | 系统/复制会话(event_scheduler 等) | 依赖目标库自身开启(单机本地 MySQL 通常无);PG 天然存在后台进程(checkpointer/autovacuum worker 等),本地即可观察 | MySQL:user ∈ 系统用户 / command=Binlog Dump;PG:command(backend_type)≠ client backend | 无需清理(不可杀)               |
| D04 | 白名单外数据源                     | 环境中已有 Oracle/SQLServer 等数据源(或临时配置一个;**注意 PostgreSQL 已在白名单内**,不能再作白名单外样例)     | dsType 非白名单                    | 无需清理(仅验证不出现在下拉)         |
| D05 | PG 未授 pg_monitor 的账号状态        | 数据源账号不授 pg_monitor(云 RDS 默认多未授);对照场景临时 `GRANT pg_monitor TO "<账号>"` 后回收       | 他人会话 SQL 列 = `<insufficient privileges>` | 测后 REVOKE 恢复原状           |

## Suites

### S1 Permission · 权限矩阵(P0)

- 风险/目的:读/杀权限分离与菜单/路由/接口三层闸。P0。
- 初始状态:分别以 C、B、A 账号登录任意主页面。
- 步骤与预期:
  1. 账号 C:侧边栏**无**「会话管理」入口;地址栏直连 `/#/session` → 被重定向到默认页(如 `/#/sql`),不渲染会话页;
  2. 账号 C:开发者工具直接 `POST /api/entry/session/list`(携带登录 Cookie)→ 406,前端统一「权限异常」弹窗文案含缺失权限点;
  3. 账号 B:侧边栏出现「会话管理」,进入页面可选实例、列表正常;所有行操作列显示 `-`,**无**「终止」按钮;
  4. 账号 B:直调 `POST /api/entry/session/kill` → 406 权限异常;
  5. 账号 A:列表可见且 killable 行出现红色 ghost「终止」按钮。
- 恢复:不修改角色配置(如为测试临时授权,测后在角色管理页还原)。

### S2 Main Flow · 会话列表(P0)

- 风险/目的:列表主链路(下拉→加载→统计条→过滤/排序/分页/刷新/自动轮询)。P0。
- 初始状态:账号 A 进入 `/#/session`,D01 已在跑。
- 步骤与预期:
  1. 实例下拉仅出现白名单 ∩ 权限内数据源(D04 类型不在其中);下拉可搜索(filterable);
  2. 选择实例后表格自动加载:列 = 会话ID/用户/主机/库名/命令/时长(秒)/状态/SQL/操作,D01 行可见且 info 含 `SLEEP(300)`;
  3. 统计条(表格上方浅底条):「{实例名} 总连接数: N」中 N = 接口返回全量条数,设置任何过滤条件后 N **不变**;「按来源 IP」将 host 剥掉端口分组计数(如 `10.1.1.9:5555` → `10.1.1.9`;`localhost`/IPv6 原样;host 为空归入 `unknown`),按连接数降序展示 `ip (数量)`,最多 10 个,超过时出现「展开全部/收起全部」文字按钮(展开为横向换行排列,页面无卡顿);kill 成功列表重拉后统计条同步更新;
  4. SQL 列:超过 80 字符的 info 显示为链接色截断文本,悬浮出现 Poptip 全文(可滚动),「复制」按钮生效(成功 toast);≤80 字符的短 SQL 为纯文本渲染(列宽不足时省略号截断,原生 title 悬浮可见全文),**不挂 Poptip**;
  5. 过滤:用户/库名/命令为**下拉选择**(可搜索、可清空),选项由当前实例全量会话去重生成,每项带连接数 `值 (数量)` 按数量降序,对应列为 NULL 的会话归入 `NULL` 选项;选中后表格**精确匹配**收敛且**回到第 1 页**;「最小时长」输入 60 → 仅剩 time ≥ 60 的行;点 × 清空过滤恢复全量;列表刷新/kill 重拉后下拉选项同步重算,若已选值在新数据中消失则过滤结果为空表(不报错,清空即恢复);
  6. 点击「时长(秒)」列头 → 本地数值升/降序切换,排序作用于**全量过滤后数据**(跨页全局有序:升序时第 1 页首行为全量最小值,翻到末页为最大值),null 时长行升序时排在最前;
  7. 分页:默认 50/页,表格仅渲染当前页切片(可用开发者工具确认 DOM 行数 ≤ 页大小);页码切换即时生效;每页条数可选 20/50/100/500,切换后回到第 1 页;分页器总条数 = 过滤后条数;
  8. 手动「刷新」→ 表格 loading 后数据更新且回到第 1 页(刷新期间新起的 D01 类会话出现、已结束的消失);未选实例时刷新按钮禁用;
  9. 打开「自动刷新」开关 → Network 面板每 ~10s 出现一次 `/api/entry/session/list`(静默,无全屏 loading 闪烁,且**不打断当前浏览页码**;若数据收缩导致当前页越界,自动收敛到有效末页);关闭开关 → 周期请求停止;
  10. 切换到其他路由(如 `/#/sql`)→ Network 面板不再出现 session/list 周期请求(定时器已清理);
  11. **PG 分支**:下拉选中 PostgreSQL 实例 → 表格列结构与 MySQL 完全一致;command 列显示 backend_type(普通连接为 `client backend`),state 列显示 PG 状态(active/idle/idle in transaction…);D01(PG)行 info 含 `pg_sleep(300)`;unix socket 连接(本机 psql 不带 -h)host 列显示 `local`;后台进程行(checkpointer 等)时长列可为空显示 `-`;
  12. **PG 分支(privilegeHint)**:数据源账号未授 pg_monitor(D05)时,他人会话 SQL 列显示 `<insufficient privileges>`,且表格上方出现**黄色 Alert**,文案为后端已翻译提示(含 `GRANT pg_monitor TO` 指引);切换到 MySQL 实例 → Alert 消失;对 PG 账号临时授予 pg_monitor 后刷新 → 掩码与 Alert 均消失(测后 REVOKE)。
- 恢复:关闭自动刷新开关,清空过滤条件;回收临时授予的 pg_monitor。

### S3 Main Flow · kill 会话(P0,仅测试环境)

- 风险/目的:高危写操作全链路(二次确认→真实终止→反馈→审计)。P0。
- 初始状态:S2 页面,D01 在跑,账号 A(如全局开启操作密码,已设置)。
- 步骤与预期:
  1. 点击 D01 行「终止」→ 弹出确认弹窗:标题「终止会话」,内容含 会话ID/用户/主机 元信息行、SQL 摘要(pre 块,超 300 字符截断加省略号)、红色「不可撤销」警示;确认按钮文案为「终止」;
  2. 点「取消」→ 弹窗关闭,D01 仍存活(kill 客户端 `SHOW PROCESSLIST` 复核),无请求发出;
  3. 再次「终止」并确认 → 确认按钮进入 loading;成功后弹窗关闭,绿色成功提示「会话已终止」,列表自动刷新且 D01 行消失;
  4. 在发起 `SELECT SLEEP(300)` 的客户端观察 → 连接被断开(Lost connection);
  5. 进入 管理 → 日志(操作审计页):出现「终止数据库会话」(KILL_DB_SESSION)记录,安全级别 HIGH,资源 = 目标数据源实例,详情(logInfo)含 sessionId/目标用户/主机/SQL 摘要/结果;
  6. 对已消失的会话 id 再直调 kill 接口(或列表未刷新时快速二次 kill)→ 业务提示「会话已不存在或已结束」(统一错误队列),不白屏、无异常栈,且**不产生**审计记录;
  7. (DS 账号未授 SUPER/CONNECTION_ADMIN 且目标会话属于**其他**数据库用户时)kill → 统一错误队列提示「终止会话失败:数据源连接账号权限不足,终止其他用户的会话需要数据库 SUPER 或 CONNECTION_ADMIN 权限,请为该账号授权后重试。原始错误:You are not owner of thread …」;目标会话存活(mysql CLI `SHOW PROCESSLIST` 复核),且**不产生**审计记录;
  8. **PG 分支**:对 PG 实例的 D01(pg_sleep)行执行「终止」→ 二次确认弹窗要素与 MySQL 一致 → 成功后绿色提示、列表刷新且该行消失;发起 pg_sleep 的 psql 客户端观察到连接被切断(`terminating connection due to administrator command`);操作审计出现同款 KILL_DB_SESSION HIGH 记录(资源=该 PG 数据源);
  9. **PG 分支(权限不足)**:DS 账号非超级用户/非目标角色成员/非 pg_signal_backend 成员时 kill **其他用户**会话 → 统一错误队列提示「终止会话失败:数据源连接账号权限不足。PostgreSQL 终止其他用户的会话需要超级用户、目标会话所属角色的成员或 pg_signal_backend 角色成员…原始错误:…」(SESSION_KILL_NO_PRIVILEGE_PG);目标会话存活(psql `SELECT * FROM pg_stat_activity` 复核),**不产生**审计记录;
  10. **PG 分支(目标已消失)**:kill 与目标自然结束竞态(pg_terminate_backend 返回 f)→ 业务提示「终止会话失败: 会话已不存在或已结束」,不白屏、无异常栈,**不产生**审计记录。
- 恢复:无(D01 已被杀);审计记录按平台策略保留,不删除。

### S4 Boundaries And Failure · 保护与边界(P0)

- 风险/目的:保护矩阵前端表现 + 服务端兜底错误路径。P0。
- 初始状态:S2 页面,账号 A。
- 步骤与预期:
  1. 列表刷新后立即可见「平台当前连接」行(id = selfConnectionId;MySQL 通常 command=Query/Sleep、info 为 `SHOW FULL PROCESSLIST` 或空;PG 为 command=client backend、info 为 `SELECT pg_backend_pid()`):其「终止」按钮**置灰不可点**,悬浮出现 Poptip「平台当前连接,禁止终止」;
  2. (环境存在 D03 时)系统用户/复制线程行同样置灰,Poptip 分别为「系统用户会话,禁止终止」/「复制线程会话,禁止终止」;**PG 分支**:后台进程行(command 列为 checkpointer/autovacuum worker/walwriter 等 ≠ client backend)置灰「系统用户会话」;walsender 行(如配置了流复制)置灰「复制线程会话」(优先于泛系统进程规则);
  3. 绕过前端直调 kill 接口构造请求:`sessionId: "1 OR 1"` → 参数/业务错误拒绝;`sessionId` 为受保护行 id → 「该会话受保护,禁止终止」;`dsId` 为白名单外数据源(D04)→ 「该数据源类型暂不支持会话管理」;`dsId` 为他人租户/不存在 → 「数据源不存在或无权限」;以上均为统一错误队列弹窗,无异常栈;
  4. 目标实例宕机/不可达时选择该实例 → 连接失败业务错误提示,页面不白屏(数据源状态由平台既有机制维护);
  5. 空态:使用无任何白名单实例的账号进入页面 → 实例下拉为空,页面显示居中灰字空态提示(含支持范围说明),不报错;
  6. (可构造时)目标库挂起使查询超 10s → 「查询实例会话超时,请稍后重试」业务提示,可重试。
- 恢复:恢复被停掉的目标实例(如有)。

### S5 State Consistency · 共用账号强提示(P1)

- 风险/目的:sharedAccount 会话的警示可见性(防误杀平台/共用连接)。P1。
- 初始状态:S2 页面,D02 已构造(用 DS 配置账号另开连接执行 SLEEP)。
- 步骤与预期:
  1. 列表中找到 user = DS 配置账号的 D02 行:「终止」按钮**可用**(killable=true,与普通行一致);
  2. 点击「终止」→ 确认弹窗在不可撤销警示外**追加**橙色强提示「该会话使用数据源配置账号,可能是平台自身或共用账号的连接,请谨慎确认后再终止。」;
  3. 取消弹窗 → D02 存活。
- 恢复:清理 D02(客户端断开即可)。

## Cleanup

1. 确认所有测试构造的 `SLEEP` 会话已结束或已被 kill;删除临时脚本/下载文件。
2. 若为 S1 临时调整过角色权限,在角色管理页恢复原授权。
3. 关闭页面上的自动刷新开关,关闭多余测试标签页,确认无遗留周期请求。
4. 若 S4 停过目标实例或改过数据源配置,恢复原状。

## Skip Conditions

- **环境无白名单类型数据源**:S2/S3/S5 跳过,仅执行 S1(菜单/路由闸)与 S4-5 空态;原因=无可用实例,替代验证=后端单测已覆盖映射与矩阵;覆盖缺口=真实 JDBC 链路未验证。
- **环境无 PostgreSQL 实例**:S2-11/12、S3-8/9/10 及 S4 的 PG 分支跳过;原因=无可用 PG 15 实例,替代验证=后端 `SessionManageServiceImplTest` 5 个 PG 用例(pg_stat_activity 别名映射/保护矩阵 walsender 优先/pg_terminate_backend 返回 t 成功路径与返回 f 失败路径/privilegeHint 置位)已覆盖方言分派;覆盖缺口=真实 PG JDBC 链路、pg_monitor 掩码与云 RDS 权限行为未在浏览器验证。
- **单机本地库无系统/复制会话(D03 不存在)**:S4-2 标记 SKIP;原因=event_scheduler/复制线程依赖库配置;替代验证=PLATFORM_SELF 行置灰(S4-1)+ 后端 `SessionManageServiceImplTest` 保护矩阵逐条断言。
- **平台未全局开启操作密码**:S3 中操作密码弹窗环节不适用;原因=JwtManager 开关未开;替代验证=开启开关的环境重跑 S3,或直接验证 498/499 走平台既有弹窗(不拦截、不白屏)。
- **DS 账号已具备 SUPER/CONNECTION_ADMIN(或测试环境不宜回收该权限)**:S3-7 权限不足分支 SKIP;原因=需临时回收数据源账号权限,对共享测试实例影响面大;替代验证=后端单测 `SessionManageServiceImplTest.kill_notOwnerOfThread_surfacesNoPrivilegeMessage_sessionClosed` 已断言「You are not owner of thread」→ SESSION_KILL_NO_PRIVILEGE 映射。
- **仅生产实例可用(无测试实例)**:S3 及一切真实 kill 场景必须 SKIP,不得降级执行;原因=kill 为不可撤销高危写操作;替代验证=S1/S2/S4 只读与拒绝路径(零副作用),覆盖缺口=真实终止与审计落库需测试环境补验。
