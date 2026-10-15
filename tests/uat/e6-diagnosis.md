# E6 诊断与会话运维 · UAT 用例(C9 M2)

> 三分类中本文件承载**②UAT 手工用例**(README §4)。本域**无 C 波次直接改动**(survey E6:B2/B3/B4 底座,09-17 交付)——用例以冒烟级回归为主,量级 4~6(F1);完整套件=既有前端流程文档(引用不重写)。
> 证据字段全部留位(红线 3);五元组格式=README §5;凭据纪律=README §8(kill/诊断操作只在测试实例)。

## Purpose

验证诊断四页(会话/锁分析/空间/慢 SQL)在 C 波次全局改动(审计链列、列宽 DmTable 适配、i18n 追加)后零回归;通道 C(OpsDiagnosticExecutor)被 E5 无锁任务复用后诊断面不受影响。

## Scope

- 页面与路由:`views/session/index.vue`(`/session`)、`views/lockAnalysis/index.vue`(`/lockAnalysis`)、`views/space/index.vue`(`/space`)、`views/slowSql/index.vue`(`/slowSql`)(survey E6);
- 后端:`SessionManageController`/`LockAnalysisController`/`SpaceController`/`SlowSqlController`;OpsDiagnosticExecutor(通道 C,无独立 UI,被 E5 复用=设计使然);
- 权限点:DM_SESSION_READ/DM_SESSION_KILL、DM_LOCK_READ、DM_SPACE_READ;慢 SQL=login-only(DM_SLOW_SQL_READ gate 已移除,cloud-integration spec);
- 契约权威:`.trellis/spec/backend/ops-diagnostics.md`(kill 保护矩阵/方言白名单/PG 严格白名单)、`cloud-integration.md`(慢 SQL 代理);
- 不覆盖:无锁任务内的探针行为(→e5)、审计行(→e8 KILL_DB_SESSION 注册面仅 E6-05 抽查)。

## 矩阵对应(②类承载对账)

本域无对应 C 级行(无直接改动);承载口径=README §7 映射表「前端流程」域+硬门槛 F-7(值班告警/运维面素材)。列宽任务六类页面含**会话/空间/慢SQL**(矩阵列宽行 R2)→E6-06 交叉 IS-D3-12。

## Preconditions

- MySQL 8.0+PG 15 测试实例各一(可制造会话/锁样本);慢 SQL 面:腾讯云 DBbrain 代理凭据已配(系统凭据键 isSecret,值不入记录;不可得→E6-04 SKIP);
- 账号:DM_SESSION_READ/KILL+DM_LOCK_READ+DM_SPACE_READ 持有者;无标签对照账号(拒绝面)。

## Test Data

| 编号 | 数据说明 | 构造方式 | 唯一标识 | 清理方式 |
|---|---|---|---|---|
| E6-D01 | 长事务+锁等待样本(两会话互锁) | 测试库两连接构造 | `codex_e6_lock_<ts>` 表 | ROLLBACK+DROP |
| E6-D02 | 可 kill 的测试会话 | 独立连接 sleep | 连接 id 记录 | kill 即清理 |
| E6-D03 | 空间样本库表 | 复用 E5/E2 造数表 | — | 随各域清理 |

## Cases

#### E6-01 会话管理冒烟(列表+kill 保护矩阵)

- 出处:tests/frontend/session/session_manage.md(既有流程,套件权威);ops-diagnostics spec(kill 保护矩阵=服务端权威,前端隐藏不代替);survey E6。
- Purpose:会话列表(双方言)、筛选、kill 主链回归;kill 保护面(受保护会话服务端拒绝)抽查。
- Steps:1)按 session_manage.md 选套执行(MySQL+PG 两源);2)E6-D02 kill→成功+KILL_DB_SESSION 审计行(→e8 检索面);3)受保护会话 kill 尝试→服务端拒绝文案(矩阵口径);4)无 DM_SESSION_KILL 账号→按钮不可见+API 直调拒绝。
- Expected:按既有文档预期;kill 审计注册面正常(3-place registration,spec)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E6-02 锁分析冒烟(锁等待链/运行中事务/最近死锁)

- 出处:tests/frontend/lock/lock_analysis.md(套件权威);ops-diagnostics spec(MySQL 8.0-only+PG 严格白名单+garbage-version 拒绝/P_S=OFF 降级/partialErrors 升级规则/死锁三态真值表)。
- Purpose:E6-D01 样本下三区块(锁等待链/运行中事务/最近死锁)显示正确;PG 面按严格白名单;降级面(P_S=OFF)文案如实。
- Steps:按 lock_analysis.md 选套执行;附加:构造互锁样本核对等待链方向与 kill 建议入口。
- Expected:按既有文档预期;死锁三态语义正确。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E6-03 空间占用冒烟(实例摘要/库-schema/表级下钻)

- 出处:tests/frontend/space/space_usage.md(套件权威);ops-diagnostics spec(空间 SQL 契约:PG temp-schema 排除/dbName 安全口径/表级下钻 DiagOpts.catalog 连接)。
- Purpose:三级空间面回归;表级下钻连接正确(catalog override);PG database 级=实例全量口径。
- Steps:按 space_usage.md 选套执行(MySQL+PG);附加:E6-D03 样本表在表级快照可见、估算语义(MySQL estimate)如实标注。
- Expected:按既有文档预期。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E6-04 慢 SQL 冒烟(登录即可读+7 天窗口)

- 出处:tests/frontend/sql/slow_sql_access.md(套件权威);cloud-integration spec(/api/entry/slowSql:login-only、7 天窗口校验、SDK 错误矩阵、系统凭据 isSecret)。
- Purpose:慢 SQL 页登录账号可访问(gate 已移除的 2026-09-17 口径);时间窗口校验(>7 天拒绝);DBbrain 代理错误文案(凭据未配/服务不可达)不泄漏系统凭据。
- Steps:按 slow_sql_access.md 选套执行;附加:构造 8 天窗口请求→拒绝文案;错误面核对无凭据回显(C1 同族纪律)。
- Expected:按既有文档预期;错误矩阵文案与 spec 一致。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E6-05 通道 C 复用面回归(E5 任务在飞时诊断正常)

- 出处:survey E6(OpsDiagnosticExecutor 被 E5 复用,无独立 UI);ops-diagnostics spec(通道 C runner 契约:DiagOpts 粒度/中性 DiagTimeoutException/per-service i18n);lockfree-dml spec §2.1/§3.5(探针经通道 C)。
- Purpose:分批/gh-ost 任务在飞(通道 C 被占用探活/lag 探针/边界 SELECT)时,四诊断页并发使用无相互干扰;诊断超时中性异常不串面。
- Steps:1)启动 E5-D03 分批任务(e5 交叉);2)任务在飞期间依次操作四页(会话列表/锁分析/空间/慢 SQL);3)kill 一个测试会话(E6-D02)确认与分批会话互不误伤(分批会话不在可 kill 建议面误导,如出现核对保护口径)。
- Expected:诊断面全部正常;无串面错误;分批任务心跳/进度不受影响(e5 面板对照)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E6-06 列宽六类页面交叉(会话/空间/慢SQL 三页)

- 出处:矩阵列宽行 R2(六类页面落地含慢SQL/会话/空间);tests/frontend/ui/table_column_resize.md(九套件权威载体);IS-D3-12(浏览器验收联调债)。
- Purpose:本域三页的列宽拖拽套件由 table_column_resize.md 承载——登记执行路由(不重写);长字段列(如锁等待 SQL 文本/空间表名)拖宽后内容可见。
- Steps:执行=table_column_resize.md 对应套件(IS-D3-12 同轮);本域核对:双击恢复默认+无持久化(裁决口径)在三页一致。
- Expected:套件通过;证据双回填(IS-D3-12 场景块+本用例)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

## Cleanup

1. E6-D01 样本事务 ROLLBACK、E6-D02 会话确认已 kill、E6-D03 随各域清理;
2. 无 KV/配置改动(如有临时 DBbrain 凭据配置,恢复原状,值不入记录)。

## Skip Conditions

- DBbrain 凭据不可得→E6-04 SKIP(记录替代=错误文案面以未配凭据状态覆盖);
- PG 实例不可得→E6-01/02/03 PG 臂 SKIP(MySQL 臂必执行);
- E5 任务不可构造→E6-05 SKIP(登记覆盖缺口:通道 C 并发面);
- 未执行、失败或环境阻塞的场景不得标记为通过(TEMPLATE.md 纪律)。
