# 治理变更全链路（提交 PRE → 修正闭环 → 发布 → 确认 → Timeline）

## Purpose

验证数据库变更治理平台的完整生命周期：治理模式工单提交、逐句拆分歧览、PRE 自动执行、失败修正闭环、版本冻结、生产发布推进、钉钉审批、确认执行、行前 Guard/Preflight 门禁、PROD 执行结果以及 Timeline 查看与路径 B 直发 DML 流程。重点防止契约绕过（PROD 无 SQL 编辑器、路径 B 仅 DML）、修正入口越权（非提交人可见、PROD 出现修正表单）、门禁结论与 Preflight 证据渲染错位、状态机映射遗漏以及事件 Timeline 事件类型缺失。

## Scope

- 页面：工单创建（治理模式 `GovTicketCreate`）、工单详情（语句级视图 + 修正入口 + 治理事件区）、生产发布页（路径 A + 路径 B）、发布详情/Timeline、PROD 失败处置。
- 路由：`/#/ticket_create`、`/#/ticket/:id`、`/#/dbChange/promotion`、`/#/dbChange/promotion/:promotionId`。
- 接口：`dbChangeGovern/preSubmit`、`dbChangeGovern/splitPreview`、`dbChangeGovern/stmtTimeline`、`dbChangeGovern/correctStatement`、`dbChangeGovern/eventTimeline`、`dbChangeGovern/availableRevisions`、`dbChangeGovern/promote`、`dbChangeGovern/promotionList`、`dbChangeGovern/promotionDetail`、`dbChangeGovern/directDmlSubmit`、`approval/confirm`、`approval/retryAutoExecJob`、`logicalDb/myLogicalDbs`、`logicalDb/bindingList`。
- 状态：PromotionStatus 9 态（CREATED → APPROVING → APPROVED → CONFIRMED → EXECUTING → SUCCEEDED / REJECTED / CANCELLED / FAILED）；GovEventType 15 种事件。
- 不覆盖：钉钉审批系统内部页面与回调、规则引擎与 parser 正确性、AutoExec sidecar 执行引擎内部、CI/CD 变更流模块。

## Preconditions

- 本地 `DmAloneLauncher` 已启动，访问地址为 `http://127.0.0.1:8222/`。
- Chrome 已登录具备治理权限的账号（权限标签含 `RDP_WORKER_ORDER_REQUEST`、`RDP_DB_CHANGE_PROD_PROMOTE`、`RDP_WORKER_ORDER_EXECUTE`；分类日志含 `CAT_RDP_DB_CHANGE_GOVERN`）。
- 已配置至少一个逻辑库，其 PRE 环境绑定可用测试数据源（MySQL），PROD 环境绑定隔离测试数据源（MySQL）。
- PRE 环境已配置 `GOV_ROLE=PRE`；PROD 环境已配置 `GOV_ROLE=PROD` 且审批模板为第三方（非 Internal）。
- 路径 B 测试需 PROD 环境额外配置 `GOV_DML_DIRECT=on`。
- 所有写入和执行仅允许使用隔离测试数据库，不得修改生产数据。

## Test Data

| 编号 | 数据说明 | 构造方式 | 唯一标识 | 清理方式 |
|------|---------|---------|---------|---------|
| D01 | 标准 DDL 工单 SQL | `CREATE TABLE codex_gov_<ts> (id INT PRIMARY KEY, name VARCHAR(50));` | `codex_gov_<ts>` | PRE+PROD 执行后 `DROP TABLE codex_gov_<ts>` |
| D02 | 标准 DML 工单 SQL | `INSERT INTO codex_gov_<ts> (id, name) VALUES (1, 'test');` | 同上表 | `DELETE FROM codex_gov_<ts> WHERE id=1` |
| D03 | 含 DML + 回滚 SQL | 正向 = D02；回滚 = `DELETE FROM codex_gov_<ts> WHERE id=1;` | 同上表 | 同 D02 |
| D04 | 故意失败语句 | `INSERT INTO codex_gov_notexist (id) VALUES (1);`（表不存在） | `codex_gov_notexist` | 无残留 |
| D05 | 路径 B 直发 DML | `UPDATE codex_gov_<ts> SET name='updated' WHERE id=1;` 回滚 = `UPDATE codex_gov_<ts> SET name='test' WHERE id=1;` | 同上表 | 恢复 name 原值 |

真实边界来自后端 FO 校验（`@NotBlank sql/rollbackSql`、`@JsonAnySetter` 拒绝未知字段）与 `GovDirectDmlSubmitFO` 契约。

## Suites

### GOV-SMOKE-00 生产发布页可达

- 风险/目的：P0，确认治理入口可见、页面路由正确、核心数据加载。
- 初始路由与状态：登录后进入主页。
- 测试数据：无。
- 准备方法：确认账号具备 `CAT_RDP_DB_CHANGE_GOVERN` 分类权限。
- Chrome 操作：
    1. 查看侧边栏是否有"生产发布"入口。
    2. 点击进入 `/#/dbChange/promotion`。
    3. 查看页面上半部（路径 A）区域与下半部（路径 B，若环境开关开启则可见）区域。
    4. 查看底部"发布列表"表格是否加载。
- 预期结果：
    1. 侧边栏"生产发布"入口可见（`CAT_RDP_DB_CHANGE_GOVERN` 控制）。
    2. URL 为 `/#/dbChange/promotion`。
    3. 上半部"路径 A · 生产发布"标题可见，含可用 Revision 列表表格与"刷新"按钮。
    4. 路径 B 区域仅在选中逻辑库的 PROD 绑定 `govDmlDirect=on` 时渲染；未选逻辑库或绑定不满足条件时不显示。
    5. 底部"发布列表"表格加载成功，列含发布编号、发布类型、状态、Revision ID、创建时间、操作。
- 恢复/清理：无。

### GOV-MAIN-00 路径 A 完整流程（PRE 提交 → 冻结 → 发布）

- 风险/目的：P0，确认治理模式工单从提交到生产发布的标准链路。
- 初始路由与状态：`/#/ticket_create`。
- 测试数据：D01（DDL 工单 SQL）。
- 准备方法：确认逻辑库 PRE 绑定可用、PRE 环境 `GOV_ROLE=PRE` 已配置。
- Chrome 操作：
    1. 在工单创建页切换到"治理工单"模式。
    2. 选择逻辑库下拉项。
    3. 在 SQL 编辑器输入 D01 SQL。
    4. 点击"校验并提交"，查看拆分歧览弹窗（成分标记 + 执行配置摘要）。
    5. 在弹窗中点击"确认提交"。
    6. 等待 PRE 自动执行完成，进入工单详情页 `/#/ticket/:id` 查看 EXECUTION 步骤。
    7. 确认工单 FINISHED 后进入 `/#/dbChange/promotion`。
    8. 在可用 Revision 列表点击"选择"选中刚冻结的 Revision。
    9. 填写发布描述，点击"提交生产发布"。
    10. 确认跳转到发布详情页 `/#/dbChange/promotion/:promotionId`。
- 预期结果：
    1. 治理模式下 `DsSelect` 隐藏，替换为逻辑库下拉（`myLogicalDbs`）。
    2. 拆分歧览弹窗显示服务端拆分结果（仅渲染，前端不自行拆分/判成分/算 hash）。
    3. 提交请求只含 `logicalDbId/ticketTitle/sql/contentType`（DDL 无回滚 SQL），不含 `dsId/envId/approvalStatus` 等裁决字段。
    4. 工单详情 EXECUTION 步骤显示逐句任务表，DDL 自动执行成功后工单转为 FINISHED。
    5. 可用 Revision 列表中出现该冻结 Revision，显示 revisionCode、逻辑库名、changeType=DDL、stmtCount。
    6. 选中 Revision 后显示摘要信息（revisionCode、逻辑库、成分、语句数、来源工单、创建时间）。
    7. promote 请求只含 `revisionId + description`，不含 `sql/dsId` 等字段。
    8. 发布详情页显示 promotionCode、promotionType=PRE_PROMOTION、status=APPROVING 或 APPROVED。
- 恢复/清理：在测试库执行 `DROP TABLE codex_gov_<ts>` 清理 DDL 产物。

### GOV-MAIN-01 修正闭环（失败 → 修正 → 版本升级 → 续跑）

- 风险/目的：P0，确认语句失败修正闭环：失败即停 → 钉钉通知深链 → 修正入口 → 版本+1 → 增量审计 → 替换 task → retryJob 断点续跑。
- 初始路由与状态：已完成 GOV-MAIN-00 的 PRE 提交步骤，SQL 改为 D04（故意失败语句）。
- 测试数据：D04 + 一条有效修正 SQL（如 `CREATE TABLE codex_gov_<ts> (id INT);`）。
- 准备方法：在治理工单中提交一条会在 PRE 执行时失败的 SQL。
- Chrome 操作：
    1. 提交含 D04 的治理工单，等待自动执行。
    2. 工单 EXEC_FAIL 后进入工单详情 `/#/ticket/:id`。
    3. 查看 EXECUTION 步骤语句表，确认失败语句高亮。
    4. 确认仅提交人可见"修正"按钮（换用非提交人账号验证不可见）。
    5. 点击"修正"，在弹出的修正表单中输入新 SQL + 修正原因。
    6. 点击"确认修正"。
    7. 查看语句版本区域（stmtTimeline），确认版本号+1。
    8. 等待 retryJob 断点续跑，已成功语句不重放。
- 预期结果：
    1. 失败语句在任务表中高亮（EXEC_FAIL 状态）。
    2. 修正按钮仅对提交人可见（`stmtTimeline.currentStatus` + `queryApprovalBaseInfo` 提交人比对）。
    3. 修正表单含 Monaco 编辑器 + 修正原因必填（前端校验非空，后端 `@NotBlank reason` 兜底）。
    4. 修正后 stmtTimeline 显示新版本行（source=CORRECTION，version+1）。
    5. retryJob 后已成功语句不重放，修正后的语句重新执行。
    6. 工单最终 FINISHED。
- 恢复/清理：清理创建的测试表。

### GOV-MAIN-02 发布确认执行 → Guard/Preflight → PROD 成功

- 风险/目的：P0，确认发布确认触发 Guard + Preflight 门禁，通过后执行成功。
- 初始路由与状态：已完成 GOV-MAIN-00，promotion 已创建。
- 测试数据：延续 D01。
- 准备方法：确认 PROD 环境审批模板已配置为第三方（非 Internal）。
- Chrome 操作：
    1. 进入发布详情页 `/#/dbChange/promotion/:promotionId`。
    2. 查看状态为 APPROVED（审批通过待确认）时，操作区显示"确认执行"按钮。
    3. 点击"确认执行"（调用 `approval/confirm`）。
    4. 查看门禁清单、Preflight 结果、治理事件 Timeline 区域。
    5. 等待执行完成，状态转为 SUCCEEDED。
- 预期结果：
    1. WAIT_CONFIRM（status=APPROVED）时"确认执行"按钮可见（`RDP_WORKER_ORDER_EXECUTE` 权限）。
    2. confirm 请求含 `ticketId/confirmActionType=CONFIRM/autoExecConfig`，走现有 approval 端点。
    3. 门禁清单表格逐条显示 7 项门禁 PASS/DENY + 原因 + 时间戳。
    4. Preflight 结果表格逐项显示检查项名称（connectivity/table_exists:xxx/ddl_dep:xxx）、PASS/DENY、证据、时间戳。
    5. 治理事件 Timeline 显示事件列表（事件类型 i18n 映射、状态迁移、操作人、时间）。
    6. 最终 status=SUCCEEDED，文字颜色为绿色。
- 恢复/清理：在 PROD 测试库执行 `DROP TABLE codex_gov_<ts>`。

### GOV-MAIN-03 PROD 失败处置（retryJob + 回 PRE 指引，无修正表单）

- 风险/目的：P0，确认 PROD 失败处置页只展示 retryJob + 回 PRE 指引，严禁修正表单（D14/AC6）。
- 初始路由与状态：构造一个 PROD 执行失败的 promotion。
- 测试数据：D05 回滚 SQL 故意写错（使 PROD 执行失败）。
- 准备方法：提交一条会在 PROD 执行失败的变更，等待 promotion 到 FAILED 状态。
- Chrome 操作：
    1. 进入发布详情页。
    2. 查看操作区：FAILED 状态时显示"重试执行"按钮 + "回到 PRE 工单"按钮 + 失败指引文案。
    3. 确认页面无修正表单、无 Monaco 编辑器、无"修正"按钮。
    4. 点击"回到 PRE 工单"，确认跳转到来源 PRE 工单详情。
    5. （可选）点击"重试执行"，确认调用 `approval/retryAutoExecJob`。
- 预期结果：
    1. FAILED 状态时操作区显示失败通知文案 + retryJob 入口 + 回 PRE 指引。
    2. 处置页无任何 SQL 编辑入口（无 TicketEditor、无 Monaco、无修正原因输入）。
    3. "回到 PRE 工单"按钮跳转到 `/#/ticket/:sourceTicketId`。
    4. "重试执行"调用 `dmTicketRetryAutoExecJob`（入参仅 `ticketId`）。
    5. 回 PRE 指引文案说明：修改 SQL 需回 PRE 修正闭环；路径 B 失败需改 SQL 时重新提交新工单。
- 恢复/清理：关闭/取消失败的 promotion，清理测试数据。

### GOV-MAIN-04 路径 B 直发生产 DML

- 风险/目的：P0，确认路径 B 直发 DML 表单仅 DML、回滚 SQL 必填、显隐按接口返回。
- 初始路由与状态：`/#/dbChange/promotion`。
- 测试数据：D05（DML + 回滚 SQL）。
- 准备方法：确认 PROD 环境配置 `GOV_DML_DIRECT=on`，账号具备 `RDP_DB_CHANGE_PROD_DML_DIRECT` 权限标签。
- Chrome 操作：
    1. 在生产发布页路径 B 区域选择逻辑库。
    2. 确认选择逻辑库后路径 B 表单可见（接口返回 `govDmlDirect=on` 且 `govRole=PROD`）。
    3. 在 DML 编辑器输入 D05 SQL，在回滚编辑器输入回滚 SQL。
    4. 填写描述，点击"提交直发生产 DML"。
    5. 确认跳转到发布详情页。
- 预期结果：
    1. 路径 B 区域仅在选中逻辑库的 PROD 绑定 `govDmlDirect=on` 时渲染（前端不自行判断阈值/开关）。
    2. DML + 回滚双编辑器并排，窄屏（<1024px）改堆叠。
    3. directDmlSubmit 请求只含 `logicalDbId/sql/rollbackSql/description`（`@JsonAnySetter` 拒绝未知字段）。
    4. 提交成功后跳转到发布详情页，promotionType=DIRECT_PROD_DML。
    5. 后端阈值分级拒绝时透传错误文案（前端不自行判断行数）。
- 恢复/清理：执行回滚 SQL 恢复数据。

### GOV-BOUNDARY-00 路径 B 回滚 SQL 为空

- 风险/目的：P1，确认回滚 SQL 必填校验（前端 + 后端 `@NotBlank`）。
- 初始路由与状态：`/#/dbChange/promotion`。
- 测试数据：D05 正向 SQL，回滚 SQL 留空。
- Chrome 操作：
    1. 选择逻辑库，路径 B 表单可见。
    2. 输入 DML SQL，回滚 SQL 编辑器留空。
    3. 点击"提交直发生产 DML"。
- 预期结果：
    1. 前端校验拦截，显示"包含 DML 成分时回滚 SQL 不能为空"提示。
    2. 请求不发送。
- 恢复/清理：无。

### GOV-STATE-00 状态机 9 态映射齐全

- 风险/目的：P0，确认 PromotionStatus 全部 9 个枚举值有 i18n 映射。
- 初始路由与状态：发布列表/详情页。
- 测试数据：无。
- 准备方法：对照后端 `PromotionStatus` 枚举（CREATED/APPROVING/APPROVED/CONFIRMED/EXECUTING/SUCCEEDED/REJECTED/CANCELLED/FAILED）。
- Chrome 操作：
    1. 在发布列表查看不同状态的记录。
    2. 在发布详情页查看状态显示。
- 预期结果：
    1. 全部 9 个状态值有中文文案（非英文枚举名）。
    2. SUCCEEDED 绿色、FAILED 红色、REJECTED/CANCELLED 橙色。
- 恢复/清理：无。

### GOV-STATE-01 事件 Timeline 15 种事件类型映射齐全

- 风险/目的：P0，确认 GovEventType 全部 15 个枚举值有 i18n 映射。
- 初始路由与状态：发布详情页 Timeline 区域、工单详情页治理事件区。
- 测试数据：无。
- 准备方法：对照后端 `GovEventType` 枚举（SUBMIT/SYSTEM_APPROVE/SYSTEM_CONFIRM/REVISION_FROZEN/FREEZE_ANOMALY/CORRECTION/FAIL_NOTIFIED/PROMOTION_CREATED/GATE_DENY/STATUS_SYNC/GUARD_PASS/GUARD_DENY/AUTO_CONFIRM/DIRECT_DML_SUBMIT/DIRECT_DML_DENY）。
- Chrome 操作：
    1. 在发布详情页查看治理事件 Timeline。
    2. 在工单详情页查看治理事件区。
- 预期结果：
    1. 全部 15 个事件类型有中文文案（非英文枚举名）。
    2. 两处使用同一共享常量映射（`govEventConstants.js`），无遗漏。
- 恢复/清理：无。

### GOV-PERMISSION-00 无 promote 权限按钮不可见

- 风险/目的：P0，确认权限控制。
- 初始路由与状态：`/#/dbChange/promotion`。
- 测试数据：无。
- 准备方法：使用不具备 `RDP_DB_CHANGE_PROD_PROMOTE` 权限的账号。
- Chrome 操作：
    1. 进入生产发布页。
    2. 查看路径 A"提交生产发布"按钮。
    3. 查看路径 B"提交直发生产 DML"按钮。
- 预期结果：
    1. 无 `RDP_DB_CHANGE_PROD_PROMOTE` 权限时路径 A promote 按钮不可见。
    2. 无 `RDP_DB_CHANGE_PROD_DML_DIRECT` 权限时路径 B submit 按钮不可见。
- 恢复/清理：无。

### GOV-LIFECYCLE-00 发布详情刷新与状态同步

- 风险/目的：P1，确认刷新后状态与后端最终事实一致。
- 初始路由与状态：发布详情页。
- 测试数据：一个进行中的 promotion。
- Chrome 操作：
    1. 在发布详情页查看当前状态。
    2. 手动刷新页面。
    3. 确认状态与刷新前一致或更新。
- 预期结果：
    1. 刷新后 promotionDetail 重新加载，各区域数据与服务端一致。
    2. 不会回退到旧状态。
- 恢复/清理：无。

### GOV-EXTREME-00 窄屏布局（双编辑器堆叠）

- 风险/目的：P1，确认路径 B 双编辑器在窄屏改堆叠。
- 初始路由与状态：`/#/dbChange/promotion`，路径 B 表单可见。
- 测试数据：无。
- Chrome 操作：
    1. 将浏览器窗口宽度调整到 1024px 以下。
    2. 查看路径 B 双编辑器布局。
    3. 查看发布列表表格。
- 预期结果：
    1. 双编辑器从并排改为纵向堆叠（flex-direction: column）。
    2. 表格在宽度不足时允许横向滚动。
    3. 无控件遮挡或溢出。
- 恢复/清理：恢复窗口宽度。

## Cleanup

1. 在测试数据库执行 `DROP TABLE IF EXISTS codex_gov_<ts>` 删除治理流程创建的测试表。
2. 恢复路径 B DML 更新的数据行。
3. 关闭/取消未完成的测试 promotion 和工单。
4. 恢复测试账号权限和网络条件。

## Skip Conditions

- 钉钉审批流推送和回调需 PROD 环境配置真实钉钉应用（AppKey/AppSecret），本地无法安全模拟时标记 SKIP，仅验证到 promotion 创建与确认入口可见。
- PRE 自动执行需 sidecar worker 在线（`AutoExecScheduleService`），worker 不可用时 SKIP 自动执行步骤，但前置提交与页面渲染验证仍可执行。
- 路径 B 测试需 PROD 环境 `GOV_DML_DIRECT=on`，未配置时 SKIP 路径 B 套件。
- PG 数据源路径 B 的 `expectedAffectedRows` 恒为 0（已知约束，非缺陷），PG 环境下行阈值分级自然失效，SKIP 行数阈值验证。
