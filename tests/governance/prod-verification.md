# 数据库变更治理 · 真实环境验证清单

> 长期有效文档，不按执行日期生成报告。验证前先通读全文，步骤与当前页面或接口不一致时先核对代码并更新文档，不猜测操作。
> 需求源：spec rev.2.1 §8.2 完成定义 + §7.2 测试策略 + §8.3 灰度路径。
> 自动化测试基线（411 绿）已覆盖 service 级编排逻辑；本文档兜底自动化无法触达的真实环境层。

## Purpose

验证数据库变更治理平台的完整生命周期在真实环境（真实 DB 连接、真实方言 SPI、真实钉钉回调、真实事务提交回滚、真实引擎执行）下端到端跑通，且安全底线 8 条不可绕过。

## Scope

- 覆盖：链路一（路径 A）全生命周期、链路二（路径 B）全生命周期、安全底线 8 条逐条验证、DENY 矩阵 12 case 真实环境抽查、双方言真实验证点。
- 不覆盖：自动化测试已充分钉住的 service 级编排逻辑（DENY 矩阵 12 case 的 service 级断言见 coverage-matrix.md，本文档只做真实环境抽查）。
- 自动化豁免项见末节"自动化豁免清单"。

## Preconditions

### 前置环境要求

1. **平台启动**：`DmAloneLauncher` 单机模式已启动，访问 `http://127.0.0.1:8222/`。
2. **逻辑库与环境绑定**：已配置至少一个逻辑库，其 PRE 环境绑定可用测试数据源，PROD 环境绑定隔离测试数据源（PRE 与 PROD 必须是不同数据库实例或不同 schema）。
3. **治理角色参数**：
    - PRE 环境配置 `GOV_ROLE=PRE`
    - PROD 环境配置 `GOV_ROLE=PROD`
    - 路径 B 测试额外配置 `GOV_DML_DIRECT=on`
    - 行数阈值可选配置 `GOV_DML_ROW_LIMIT=warn:1000,block:100000`
    - 自动确认可选配置 `GOV_AUTO_CONFIRM=on`（仅 PRE 环境有意义）
4. **IM Provider 预检**（spec §13-4）：治理环境必须配置至少一个 IM Provider（钉钉/飞书/企微），否则语句失败通知会抛异常且无降级。预检方法：系统设置 → IM 配置，确认 Provider 已配置且测试消息可发送。
5. **审批模板配置**：PROD 环境必须配置第三方审批模板（非 Internal），否则 gate-one G7 会 DENY。配置位置：环境参数 `CHANGE_TICKET_INFO`，值格式 `EnvTicketMO{approvalType, templateName, templateId}`。PRE 环境默认 Internal 自动模板 `PROC-SELFMAKE-000000001`。
6. **权限标签分配**：验证账号需具备以下标签：
    - `RDP_WORKER_ORDER_REQUEST`：工单请求（preSubmit/correctStatement/splitPreview）
    - `RDP_WORKER_ORDER_READ`：工单读（stmtTimeline/eventTimeline）
    - `RDP_DB_CHANGE_GOVERN_READ`：治理读（availableRevisions/revisionDetail/promotionList/promotionDetail）
    - `RDP_DB_CHANGE_PROD_PROMOTE`：生产发布（promote）
    - `RDP_DB_CHANGE_PROD_DML_DIRECT`：路径 B 直发 DML（directDmlSubmit）
    - `RDP_WORKER_ORDER_EXECUTE`：执行确认（confirm）
    - 分类日志含 `CAT_RDP_DB_CHANGE_GOVERN`
7. **权限组配置**：已创建至少一个权限组，包含成员与 PRE/PROD 资源授权（`res_path` 指向测试逻辑库的 PRE/PROD 绑定路径）。
8. **sidecar worker 在线**：`AutoExecScheduleService` 能扫描到在线 worker，否则 PRE 自动执行步骤会卡在 WAIT_EXEC。
9. **灰度建议**（spec §8.3）：选一个非核心逻辑库标 PRE/PROD 治理角色，只跑路径 A；稳定后开 `GOV_DML_DIRECT` 试跑路径 B；逐步扩大逻辑库范围。未标 `GOV_ROLE` 的环境全程不受治理约束，现有行为零变化。

### 测试数据约定

| 编号 | 数据说明 | 构造方式 | 清理方式 |
|------|---------|---------|---------|
| D01 | DDL 工单 SQL | `CREATE TABLE codex_gov_<ts> (id INT PRIMARY KEY, name VARCHAR(50));` | `DROP TABLE codex_gov_<ts>` |
| D02 | DML 工单 SQL | `INSERT INTO codex_gov_<ts> (id, name) VALUES (1, 'test');` | `DELETE FROM codex_gov_<ts> WHERE id=1` |
| D03 | DML + 回滚 SQL | 正向 = D02；回滚 = `DELETE FROM codex_gov_<ts> WHERE id=1;` | 同 D02 |
| D04 | 故意失败语句 | `INSERT INTO codex_gov_notexist (id) VALUES (1);` | 无残留 |
| D05 | 路径 B 直发 DML | `UPDATE codex_gov_<ts> SET name='updated' WHERE id=1;` 回滚 = `UPDATE codex_gov_<ts> SET name='test' WHERE id=1;` | 恢复 name 原值 |
| D06 | 混合工单（DDL+DML） | D01 + D02 拼接（两条语句，提交时含回滚 SQL） | 先 DELETE 再 DROP TABLE |

---

## Chain One · 路径 A（PRE 提交 → 冻结 → 发布 → 执行 → Timeline）

### Step 1 · 用户加入权限组获得 PRE 资源

- 入口：系统设置 → 权限组管理页面（`/#/system/permissionGroup`）。
- 操作：
    1. 确认权限组已包含当前用户为成员，且组资源已授权目标逻辑库的 PRE 环境路径。
    2. 确认 `dm_auth_res` 中有 `res_desc` 含 `PERM_GROUP:<groupId>:<groupResourceId>` 的展开行，`owner_uid` 为当前用户。
- 预期状态：用户在控制台查询逻辑库时能看到目标逻辑库（`myLogicalDbs` 返回含该库）。
- 预期事件：无治理事件（权限组展开不产生 `dm_db_change_event`）。
- 失败排查：逻辑库不可见时检查组资源 `start_time/end_time` 是否在有效期内、`res_path` 是否匹配逻辑库路径前缀、权限组 `status` 是否为启用。

### Step 2 · 提交治理工单（DDL / DML / 混合）

- 入口：工单创建页 `/#/ticket_create`，切换到"治理工单"模式。
- API：`POST /dbChangeGovern/preSubmit`，请求体 `GovPreSubmitFO`（`logicalDbId / ticketTitle / sql / contentType / rollBackSql`）。
- 操作：
    1. 在治理模式下选择逻辑库下拉项（`DsSelect` 隐藏，由服务端解析 PRE 绑定数据源）。
    2. 在 SQL 编辑器输入 D01（DDL）、D03（DML 含回滚）或 D06（混合含回滚）。
    3. 点击"校验并提交"，查看拆分歧览弹窗（成分标记 DDL/DML/MIXED + 执行配置摘要：DML→transactional=true，DDL/MIXED→transactional=false + errorStrategy=NONE）。
    4. 点击"确认提交"。
- 预期状态：工单创建为 `ApprovalBiz=DM_CHANGE`，`envName` 为 PRE 环境名，`ticketInfo` 含 `ApprovalMO{logicalDbId, govRole=PRE}`；工单进入 `PRE_INIT_WAIT` 状态。
- 预期事件：`dm_db_change_event` 写入 `GovEventType.SUBMIT`（`event_data` 含 ticketId、logicalDbId、changeType）。
- 失败排查：
    - 提交被拒"无 PRE 资源权限"：检查权限组展开行是否存在、`checkResAuth` 路径是否匹配。
    - 拆分失败：检查 `QueryAnalysisService` 是否正确解析方言、`BehaviorAction` 是否正确判定成分。
    - 含 DML 缺回滚 SQL：后端 `@NotBlank rollBackSql` 校验拒绝（D3 契约），补回滚 SQL。
    - IM Provider 未配置：不影响提交，但后续失败通知会抛异常（Step 6）。

### Step 3 · 规则审计 + PRE 自动执行

- 入口：工单详情页 `/#/ticket/:id`。
- API（内部触发）：`PreInitHandler` 链执行（BehaviorPreInitHandler → RuleCheckPreInitHandler → DmlExplainPreInitHandler）→ 推进器 `GovAutoAdvanceService` 系统代审 → `AutoExecService.createJob/startJob`。
- 操作：等待工单状态从 `PRE_INIT_WAIT → PRE_INIT_RUN → WAIT_APPROVAL`（PRE 默认全自动，规则审计通过即系统代审代确认执行）→ `WAIT_EXEC → RUNNING`。
- 预期状态：工单进入 RUNNING 后 sidecar 逐条执行语句（`dm_exec_auto_task` 按 `exec_order` 1..n 顺序执行）。
- 预期事件：
    - `GovEventType.SYSTEM_APPROVE`（系统代审记录）
    - `GovEventType.SYSTEM_CONFIRM`（系统代确认记录，若 `GOV_AUTO_CONFIRM=on`）
- 失败排查：
    - 工单卡在 `WAIT_APPROVAL`：检查 PRE 环境 `GOV_ROLE=PRE` 是否配置、推进器 `GovPipelineScheduler` 是否运行（1s 守护循环）、审批模板是否为 Internal 自动模板。
    - 工单卡在 `WAIT_EXEC`：检查 sidecar worker 是否在线、`AutoExecScheduleService` 是否运行（5s 扫描循环）。
    - 规则审计失败：工单回 `REJECTED`，检查 `dm_sec_rules` 规则集与 `check_spec_id` 环境绑定。
    - DML Explain 失败（PG 环境）：PG SPI 不支持 INSERT/UPDATE/DELETE 的行估算，`expectedAffectedRows` 恒为 0，不阻断提交（已知约束非缺陷）。

### Step 4 · 全部成功 → 冻结 Revision

- 入口：无前端入口（后台推进器 `RevisionFreezeService` 扫描式触发）。
- 操作：工单 RUNNING → 所有 task FINISH → 工单 FINISHED → 推进器扫描到 FINISHED 的治理工单 → 冻结 Revision。
- 预期状态：`dm_db_change_revision` 写入一条记录，含 `revision_code`、`source_ticket_id`、`change_type`、`sql_text`、`sql_hash`、`rollback_sql_text`、`rollback_sql_hash`、`stmt_manifest`（JSON 数组 `[{idx, stmt_hash, version, pre_exec, corrected_from}]`）、`audit_snapshot`。
- 预期事件：`GovEventType.REVISION_FROZEN`（`event_data` 含 revisionId、revisionCode、stmtCount）。
- 失败排查：
    - 工单 FINISHED 但未冻结：检查推进器 `GovPipelineScheduler` 是否运行、`RevisionFreezeService.freezeFinishedRevisions` 是否扫描到该工单。
    - 冻结异常：`GovEventType.FREEZE_ANOMALY` 事件写入（如 source_ticket 已有 revision、manifest 不全 SUCCESS），检查工单状态机是否正常。
    - 幂等：重复扫描不会重复插入（`UNIQUE(source_ticket_id)` 约束兜底，`DuplicateKeyException` 静默跳过）。

### Step 5 · 创建 Promotion（逐句核验门禁一）

- 入口：生产发布页 `/#/dbChange/promotion`。
- API：`POST /dbChangeGovern/promote`，请求体 `GovPromoteFO`（`revisionId / description`）。
- 操作：
    1. 在路径 A 区域点击"刷新"加载可用 Revision 列表（`availableRevisions` 返回的 Revision 已通过 6 项过滤排除规则）。
    2. 选中刚冻结的 Revision，填写发布描述，点击"提交生产发布"。
- 预期状态：`dm_db_change_promotion` 写入一条记录，`status=CREATED`，`gate_result` JSON 含 7 项门禁结果全 PASS。
- 预期事件：`GovEventType.PROMOTION_CREATED`（`event_data` 含 promotionId、revisionId、promotionCode）。
- 门禁一 7 项逐条核验（全部必须 PASS）：
    1. Revision hash integrity：`sql_hash` 一致
    2. Source ticket FINISHED + manifest pre_exec SUCCESS：来源工单终态 + 每句 `pre_exec=SUCCESS`
    3. PROD resource auth：`checkResAuth` 对 PROD 资源权限通过
    4. PROD binding resolvable：`getBinding` 能解析 PROD 环境数据源
    5. Revision not already promoted：`UNIQUE(source_ticket_id)` 保证不重复发布
    6. Rollback SQL required for DML/MIXED：含 DML 成分时 `rollback_sql_text` 非空
    7. PROD approval template configured：PROD 环境审批模板为第三方（非 Internal）
- 失败排查：
    - GATE_DENY：检查 `dm_db_change_event` 中 `GovEventType.GATE_DENY` 事件的 `event_data`（含 `gateResult` JSON），定位是哪条门禁失败。
    - G3 auth denied：检查权限组是否已授权 PROD 资源路径、展开行是否存在。
    - G7 internal template：PROD 环境审批模板配置为 Internal，改为第三方。

### Step 6 · 钉钉审批

- 入口：钉钉审批应用（外部系统）。
- 操作：钉钉收到审批通知 → 审批人在钉钉中审批通过（或拒绝）。
- 预期状态：审批通过 → promotion `status=APPROVING → APPROVED`（`GovPromotionSyncService` 同步钉钉回调结果）。
- 预期事件：`GovEventType.STATUS_SYNC`（`event_data` 含 fromStatus、toStatus、providerType）。
- 失败排查：
    - 审批回调未同步：检查 `ApprovalRefreshService` / `DingApprovalStreamHandler` 是否接收事件、`GovPromotionSyncService.syncPromotionStatus` 是否扫描到该 promotion。
    - 审批被拒：promotion `status=REJECTED`，工单可回退重提交（不继续后续步骤）。

### Step 7 · 平台确认执行（配置锁定）

- 入口：发布详情页 `/#/dbChange/promotion/:promotionId`。
- API：`POST /approval/confirm`，请求体含 `ticketId / confirmActionType=CONFIRM / autoExecConfig`。
- 操作：promotion `status=APPROVED` 时操作区显示"确认执行"按钮，点击确认。
- 预期状态：确认后 `autoExecConfig` 由治理层注入（D15 成分路由：纯 DML→transactional=true，DDL/MIXED→transactional=false + errorStrategy=NONE），确认人不可改。promotion `status=CONFIRMED`。
- 预期事件：若 `GOV_AUTO_CONFIRM=on` 则 `GovEventType.AUTO_CONFIRM`；否则无治理事件（走现有 `confirmTicket` 链路）。
- 失败排查：
    - 确认按钮不可见：检查账号是否有 `RDP_WORKER_ORDER_EXECUTE` 标签。
    - 配置被篡改：若确认人传入非成分路由注入值，gate-two G6 会 DENY（见安全底线第 7 条）。

### Step 8 · Guard 门禁二 + Preflight

- 入口：无前端入口（`prepareExecJobAsync` 和 `dispatchJob` 内部调用 `GovExecutionGuardService`）。
- 操作：确认执行后 → `prepareExecJobAsync` 调用 Guard → 通过后创建执行 job → `dispatchJob` 再次调用 Guard → 通过后派发 sidecar。
- 预期状态：Guard 结论 `GuardConclusion.pass=true`，6 项门禁全 PASS。
- 门禁二 6 项逐条核验：
    1. `G1_promotion_status`：promotion status=APPROVED 或 CONFIRMED
    2. `G2_hash`：整单 sql_hash + 逐句 stmt_hash 复验一致
    3. `G3_binding`：PROD dsId 与快照一致（绑定未被换）
    4. `G4_preflight`：四项 Preflight 检查通过（connectivity / table_exists / ddl_dep / dml_target）
    5. `G5_idempotency`：execution_key 一致 + `depend_on_biz_id` UNIQUE 约束
    6. `G6_config`：job config 符合成分路由（SKIP 拒绝、transactional 匹配）
- 预期事件：`GovEventType.GUARD_PASS`（`event_data` 含 ticketId、jobId、stage=CONFIRM/DISPATCH）。
- 失败排查：
    - Guard DENY：promotion `status` 回退到 WAIT_CONFIRM，`GovEventType.GUARD_DENY` 事件写入（`event_data` 含 denyItem、evidence）。在发布详情页查看门禁清单表格定位 DENY 项。
    - Preflight 连接失败：检查 PROD 数据源网络连通性、防火墙白名单。
    - 表不存在：DML 引用的表在 PROD 未创建，检查 Preflight `table_exists` 项。
    - hash 不一致：语句文本被篡改或 task exec_sql 被改，检查 `dm_exec_auto_task.exec_sql` 与 revision `stmt_manifest` 逐句比对。

### Step 9 · 生产执行 → PROD_SUCCESS

- 入口：发布详情页 EXECUTION 区域。
- 操作：Guard 通过后 → sidecar 逐条执行 SQL → 全部成功 → 工单 FINISHED → promotion `status=EXECUTING → SUCCEEDED`。
- 预期状态：promotion 终态 `SUCCEEDED`，`dm_exec_auto_task` 全部 FINISH。
- 预期事件：`GovEventType.STATUS_SYNC`（EXECUTING→SUCCEEDED）。
- 失败排查：
    - 执行失败：工单 EXEC_FAIL，promotion `status=FAILED`。`GovEventType.GUARD_DENY` 或引擎回调 `TASK_FAILED`。
    - PROD 失败处置页（GOV-MAIN-03）：只显示"重试执行"（retryJob）+ "回到 PRE 工单"指引，无修正表单（D14 契约）。

### Step 10 · 完整 Timeline

- 入口：发布详情页 `/#/dbChange/promotion/:promotionId` 治理事件 Timeline 区域；工单详情页 `/#/ticket/:id` 治理事件区。
- API：`POST /dbChangeGovern/eventTimeline`。
- 预期状态：Timeline 显示完整事件序列（15 种 `GovEventType` 中本链路涉及的子集），按时间排序。每个事件显示：事件类型（i18n 映射中文文案）、操作人（SYSTEM 标记系统代审）、时间戳。
- 预期事件序列（链路一完整成功路径）：
    1. `SUBMIT`（Step 2）
    2. `SYSTEM_APPROVE`（Step 3 系统代审）
    3. `SYSTEM_CONFIRM`（Step 3 系统代确认，若 AUTO_CONFIRM=on）
    4. `REVISION_FROZEN`（Step 4）
    5. `PROMOTION_CREATED`（Step 5）
    6. `STATUS_SYNC`（Step 6 审批通过 APPROVING→APPROVED）
    7. `GUARD_PASS`（Step 8 门禁二通过）
    8. `STATUS_SYNC`（Step 9 EXECUTING→SUCCEEDED）
- 失败排查：事件缺失时检查 `dm_db_change_event` 表是否写入、`EventTimelineService` 映射是否正确、15 种 `GovEventType` 是否全部有 i18n 映射（`govEventConstants.js`）。

---

## Chain Two · 路径 B（直发生产 DML → 冻结 → 审批 → 执行 → Timeline）

### Step B1 · 确认环境开关与权限

- 入口：系统设置 → 环境参数。
- 操作：确认 PROD 环境已配置 `GOV_DML_DIRECT=on`、`GOV_ROLE=PROD`、账号具备 `RDP_DB_CHANGE_PROD_DML_DIRECT` 标签。
- 预期状态：环境参数 KV 已写入 `dm_sys_env_param`。

### Step B2 · 直发 DML + 回滚 SQL

- 入口：生产发布页 `/#/dbChange/promotion` 路径 B 区域。
- API：`POST /dbChangeGovern/directDmlSubmit`，请求体 `GovDirectDmlSubmitFO`（`logicalDbId / sql / rollbackSql / description`）。
- 操作：
    1. 选择逻辑库，路径 B 表单渲染（接口返回 `govDmlDirect=on` 且 `govRole=PROD` 时可见）。
    2. 在 DML 编辑器输入 D05 SQL，在回滚编辑器输入回滚 SQL。
    3. 填写描述，点击"提交直发生产 DML"。
- 预期状态：后端判定仅 DML（parser `BehaviorAction` 逐句判定，含 DDL/MIXED 则拒绝）、EXPLAIN 阈值分级（MySQL: `expectedAffectedRows` 非零评估；PG: SPI 不支持→0→永不拒绝）、同事务创建 6 对象（revision + promotion + PROD 工单 + task + event ×2）。
- 预期事件：`GovEventType.DIRECT_DML_SUBMIT`（`event_data` 含 revisionId、promotionId、estimatedRows、riskLevel）。
- 失败排查：
    - 表单不可见：检查 PROD 环境 `GOV_DML_DIRECT=on` 是否配置、逻辑库 PROD 绑定是否存在。
    - DDL 被拒：路径 B 仅接受纯 DML，parser 判定含 DDL 则 `GovEventType.DIRECT_DML_DENY` + 零对象创建。
    - 行数超 block：`GovEventType.DIRECT_DML_DENY` 事件写入 + 零对象创建。检查 `GOV_DML_ROW_LIMIT` 阈值配置。
    - 回滚 SQL 为空：`@NotBlank rollbackSql` 校验拒绝（D3 契约：含 DML 成分时回滚 SQL 必填）。

### Step B3 · 冻结 Revision（路径 B 1:1 指向 PROD 工单本身）

- 预期状态：`dm_db_change_revision` 写入，`source_type=DIRECT_PROD_DML`，`source_ticket_id` 指向 PROD 工单本身（路径 B 1:1）。
- 预期事件：`GovEventType.REVISION_FROZEN`。

### Step B4 · 钉钉审批（表单含影响行数）

- 入口：钉钉审批应用。
- 预期状态：审批表单（触点 #7 `convertToChangeForm` 治理分支）含字段：单号、风险级（riskLevel）、影响行数（estimatedRows）、SQL hash、PRE 结果。审批通过 → promotion `status=APPROVING → APPROVED`。
- 预期事件：`GovEventType.STATUS_SYNC`。
- 失败排查：表单字段缺失时检查 `ConvertToChangeFormGovernanceTest` 覆盖的 9 字段组装逻辑、`GovChangeFormAssembler` 是否正确读取 `gate_result` 中的 riskLevel/estimatedRows。

### Step B5 · 确认 → Guard + Preflight → 生产执行 → Timeline

- 同链路一 Step 7-10（确认执行 → Guard 门禁二 → Preflight → 生产执行 → promotion SUCCEEDED → 完整 Timeline）。
- 预期事件序列（链路二完整成功路径）：
    1. `DIRECT_DML_SUBMIT`（Step B2）
    2. `REVISION_FROZEN`（Step B3）
    3. `STATUS_SYNC`（Step B4 审批通过）
    4. `GUARD_PASS`（确认后门禁二通过）
    5. `STATUS_SYNC`（执行成功 EXECUTING→SUCCEEDED）

---

## 安全底线 8 条逐条验证方法

> spec §8.2：安全底线最终形态——"不允许通过 UI、Controller、Task、脚本任何方式绕过"。
> 每条含**构造篡改的具体操作步骤**与**核查点**。

### 底线 1 · 无冻结 Revision → 不能生产

- 构造篡改：通过 API 直接调用 `POST /dbChangeGovern/promote`，传入一个不存在的 `revisionId`（如 `999999`）。
- 预期：gate-one G1 DENY（"Revision hash integrity" → revision not found）。promotion 不创建，`GovEventType.GATE_DENY` 事件写入。
- 核查点：确认无任何方式可以在没有冻结 Revision 的情况下创建 promotion——promote 接口入参只有 `revisionId + description`，不含 `sql/dsId`（`@JsonAnySetter` 拒绝未知字段）。

### 底线 2 · 整单或任一语句 hash/version 不一致 → 不能生产

- 构造篡改（整单 hash）：
    1. 在 `dm_db_change_revision` 表中找到已冻结的 revision，将 `sql_hash` 改一位（如末尾 `a` 改 `b`）。
    2. 通过 UI 调用 promote → gate-one G1 DENY（hash 不匹配）。
    3. 即使绕过 gate-one（不可能），gate-two G2 会在确认执行时再次复验整单 hash → DENY。
- 构造篡改（逐句 hash）：
    1. 在 `dm_exec_auto_task` 表中找到该 job 的 task 行，将 `exec_sql` 改一个字符。
    2. 确认执行 → gate-two G2 逐句比对 `exec_sql` hash vs manifest `stmt_hash` → DENY（`event_data` 含 denyItem=`G2_hash`、evidence 含 idx 定位）。
- 构造篡改（version）：
    1. 在 `dm_db_change_stmt_version` 表中将某语句的 `stmt_version` 改大（但 manifest 中的 version 仍是旧值）。
    2. promote → gate-one G2 DENY（manifest version 与最新版本不一致）。
- 核查点：确认 hash 在冻结后不可变（应用层无 update 路径），逐句 hash 在 dispatchJob 时再次复验（retryJob 重入必经门禁二）。

### 底线 3 · 任一语句 PRE 未成功 → 不能生产

- 构造篡改：
    1. 提交一个含 D04（故意失败语句）的 PRE 工单 → EXEC_FAIL。
    2. 不修正直接尝试 promote → revision 未冻结（`RevisionFreezeService` 只冻结 FINISHED 工单）。
    3. 即使手动在 `dm_db_change_revision` 插入一条记录（绕过冻结逻辑），`stmt_manifest` 中该句 `pre_exec` 不是 SUCCESS → gate-one G2 DENY。
- 核查点：确认 `availableRevisions` 过滤排除 manifest 非全 SUCCESS 的 revision、`RevisionFreezeService` 不冻结非 FINISHED 工单。

### 底线 4 · 无 PROD 资源权限 → 不能生产

- 构造篡改：
    1. 用一个没有 PROD 资源权限的账号（不在权限组或组未授权 PROD 路径）调用 promote。
    2. gate-one G3 DENY（`checkResAuth` 抛异常 → "PROD resource auth" 失败）。
- 核查点：确认 `checkResAuth` 在 service 层执行（不在 Controller 层），即使有 `RDP_DB_CHANGE_PROD_PROMOTE` 功能标签也必须有 `dm_auth_res` 行级资源授权。权限组展开行 `PERM_GROUP:` 标记的行与直接授权行等价生效。

### 底线 5 · 无审批通过 → 不能生产

- 构造篡改：
    1. 创建 promotion 后（status=CREATED），不审批直接尝试确认执行。
    2. gate-two G1 DENY（promotion status=CREATED，期望 APPROVED 或 CONFIRMED）。
- 构造篡改（伪造审批）：
    1. 通过 API 发送伪造的审批回调（请求体含 `{"approvalStatus":"APPROVED"}`）。
    2. 预期：`ApprovalCallbackSpi` 不接受 `approvalStatus` 入参（审批结论只读 DB，回调只触发状态同步，不同步入参状态）。`ApprovalMO` 忽略未知字段。DB 审批状态不变。
- 核查点：审批结论由 `ApprovalStateService.updateApprovalStatus` 服务端写入，不接受外部传入的状态值。伪造 `approvalStatus=APPROVED` 在请求体中被 `@JsonAnySetter` 拒绝或被 Jackson 忽略。

### 底线 6 · Preflight 不通过 → 不能生产

- 构造篡改（连接失败）：
    1. 在 PROD 数据源防火墙中临时阻断 CloudDM 服务器 IP。
    2. 确认执行 → gate-two G4 Preflight `connectivity` DENY（`realTimeFetchVersion` 抛异常，永不降级）。
- 构造篡改（表不存在）：
    1. 在 revision 中引用一个 PROD 库不存在的表名。
    2. 确认执行 → gate-two G4 Preflight `table_exists:xxx` DENY。
- 核查点：确认 Preflight 永不降级（connectivity 异常=DENY 而非 PASS），四项检查（connectivity / table_exists / ddl_dep / dml_target）全部通过才放行。

### 底线 7 · 执行配置不合成分路由 → 不能生产

- 构造篡改（SKIP 策略）：
    1. 在确认执行时传入 `autoExecConfig` 含 `errorStrategy=SKIP`。
    2. gate-two G6 DENY（治理 PROD 工单禁用 SKIP）。
- 构造篡改（事务模式不匹配）：
    1. 工单含 DDL 成分，确认时传入 `enableTransactional=true`。
    2. gate-two G6 DENY（DDL 工单必须逐条 autocommit，禁用事务模式——防止 MySQL DDL 隐式提交击穿事务）。
- 核查点：确认 `autoExecConfig` 由治理层注入（PRE 系统代审代确认时写入、PROD 人工确认时锁定），确认人不可改。成分路由规则：纯 DML→transactional=true + errorStrategy=NONE；含 DDL→transactional=false + errorStrategy=NONE。

### 底线 8 · 路径 B 非纯 DML 或开关关闭 → 不能生产

- 构造篡改（DDL 走直发入口）：
    1. 在路径 B 表单输入 DDL 语句（如 `CREATE TABLE ...`）。
    2. `directDmlSubmit` 拒绝（parser 判定含 DDL），`GovEventType.DIRECT_DML_DENY` + 零对象创建。
- 构造篡改（开关关闭）：
    1. 将 PROD 环境 `GOV_DML_DIRECT` 改为 `off`（或删除）。
    2. 路径 B 表单不可见，`directDmlSubmit` 接口拒绝。
- 构造篡改（行数超 block）：
    1. 配置 `GOV_DML_ROW_LIMIT=block:10`，提交影响 100 行的 DML。
    2. `GovEventType.DIRECT_DML_DENY` + 零对象创建。
- 核查点：路径 B 的 4 项验证（仅 DML / 开关 / 阈值 / 专用标签）全部在 service 层强制，前端只做显隐控制不自行判断。

---

## DENY 矩阵 12 case 真实环境抽查点

> 自动化测试（Wave A）已逐 case 钉住 service 级 DENY + 留痕断言。以下为真实环境值得额外抽查的安全关键 case。

| # | DENY Case | 抽查价值 | 真实环境操作步骤 | 预期 DENY 位置 |
|---|-----------|---------|----------------|---------------|
| ④ | 整单 hash 被篡改 | 篡改 Revision 表后确认是否被拦截 | 改 `dm_db_change_revision.sql_hash` 一位 → 确认执行 | gate-two G2（`G2_hash` DENY + `GUARD_DENY` 事件） |
| ⑦ | 绑定被换 | 运行期换 PROD 数据源后是否被拦截 | 改 `dm_logical_db_env_binding.ds_id` → 确认执行 | gate-two G3（`G3_binding` dsId != 快照 → `GUARD_DENY`） |
| ⑫ | 执行配置被篡改 | 确认人传入违规 config 是否被拦截 | 确认时传 `errorStrategy=SKIP` 或 DDL 工单传 `enableTransactional=true` | gate-two G6（`G6_config` → `GUARD_DENY`） |

其余 9 case（①②③⑤⑥⑧⑨⑩⑪）自动化测试已充分覆盖 service 级逻辑，真实环境抽查价值低（路径相同），可选择性验证。

---

## 双方言真实验证点

> spec §7.2：同一治理链路用例在 MySQL 与 PG 各跑一遍。
> 自动化测试（Wave C）已覆盖 service 级编排一致性（`GovernanceDualDialectTest` 5 方法）。以下为真实方言行为差异点。

### PG 环境验证

1. **规则集路由**：为 PG 环境配置一套与 MySQL 对等的规则集（含 PG 特有项：`CREATE INDEX CONCURRENTLY` / `ADD COLUMN NOT NULL DEFAULT` / `TRUNCATE`/`DROP` 分级），通过 `check_spec_id` 环境绑定。提交 PG 方言 SQL → 规则审计按 PG 规则集路由。
2. **Preflight 元数据**：`realTimeFetchVersion` 返回 PostgreSQL 版本字符串（如 `PostgreSQL 15.4`）；`realTimeFetchSelectObject` 查询 PG 系统目录表。四项检查行为与 MySQL 一致（connectivity / table_exists / ddl_dep / dml_target）。
3. **DML Explain 降级**：PG `PgExplainPlanSpi` 显式排除 INSERT/UPDATE/DELETE/MERGE → `supportByQueryType` 返回 false → `expectedAffectedRows` 恒为 0。路径 B 提交 PG DML → 阈值评估走 0 行分支 → 永不拒绝提交。`GOV_DML_ROW_LIMIT` 行阈值对 PG 自然失效（已知约束非缺陷）。
4. **成分路由事务语义**：PG DDL 支持事务性（与 MySQL DDL 隐式提交不同），但 D15 成分路由统一守卫：事务模式只对纯 DML 工单开放，含 DDL 一律逐条 autocommit。PG 环境 DDL 工单 → `transactional=false` + `errorStrategy=NONE`。

### MySQL 环境对照

1. **DML Explain 原生路径**：MySQL `MyExplainPlanSpi` 无 DML 排除 → EXPLAIN 执行 → `expectedAffectedRows` 非零评估。路径 B MySQL DML → 阈值评估走真实行数分支 → 超 block 拒绝。
2. **DDL 隐式提交守卫**：MySQL DDL 隐式提交会击穿事务模式，D15 成分路由补守卫：含 DDL → `transactional=false`。验证 MySQL DDL 工单执行后 task 逐条 autocommit。

---

## 自动化豁免清单

> 以下项因基建限制（D-P11-1：零新基建，不引入 Spring 上下文/H2/testcontainers/mockito-inline）无法在自动化测试中覆盖，由本清单对应章节人工验证兜底。

| 豁免项 | 原因 | 人工兜底章节 |
|--------|------|-------------|
| 真实方言行为差异（SQL 解析、EXPLAIN 执行、schema 元数据查询） | `PluginManager.findDsPlugin()` 为 static 方法，不可 Mockito mock；SPI 实现通过 static `dsMetaMap` 加载 | §双方言真实验证点 |
| `deleteByEndTimeExceed` mapper SQL 行为 | 真实 DELETE SQL 执行需真实 DB | §Chain One Step 1（权限组有效期展开行可见性）+ 权限组管理页验证到期行删除 |
| 引擎内部任务状态迁移（retryJob 真实行为） | AutoExec sidecar 引擎内部状态迁移逻辑，service 级测试 mock 引擎边界 | §Chain One Step 3-4 + Step 9（执行成功/失败终态） |
| 真实钉钉审批回调 | 钉钉 Stream SDK 事件 / HTTP 回调需真实钉钉应用配置 | §Chain One Step 6 + §Chain Two Step B4 |
| 真实事务提交回滚 | `enableTransactional=true` 时整单一个事务的 commit/rollback 行为需真实 DB | §Chain One Step 9（DML 工单事务执行） |
| 真实 DB 执行层（NATIVE_EXPLAIN / Preflight realTimeFetch*） | `realTimeFetchVersion` / `realTimeFetchSelectObject` 需真实 DB 连接 | §安全底线 6（Preflight 不通过）+ §双方言真实验证点 |
| IM Provider 发送行为 | `ImSenderService.sendMessage` 需真实 IM Provider 配置 | §Chain One Step 3 失败排查（IM Provider 预检）+ Step 6 |
| Controller 层 HTTP 鉴权拦截（无标签→403） | 无 `@WebMvcTest` / MockMvc 基建；service 级 `JwtManager.testAuth()` 直测已覆盖逻辑 | §Preconditions（权限标签分配）+ §安全底线 4-5（无权限/伪造审批被拒） |

---

## Cleanup

1. 在测试数据库执行 `DROP TABLE IF EXISTS codex_gov_<ts>` 删除治理流程创建的测试表。
2. 恢复路径 B DML 更新的数据行。
3. 关闭/取消未完成的测试 promotion 和工单。
4. 恢复测试账号权限、环境参数和网络条件。
5. 恢复 `dm_db_change_revision.sql_hash` 等被篡改的测试数据（安全底线验证后）。

## Skip Conditions

- 钉钉审批流推送和回调需 PROD 环境配置真实钉钉应用（AppKey/AppSecret），本地无法安全模拟时 SKIP，仅验证到 promotion 创建与确认入口可见。
- PRE 自动执行需 sidecar worker 在线，worker 不可用时 SKIP 自动执行步骤。
- 路径 B 测试需 PROD 环境 `GOV_DML_DIRECT=on`，未配置时 SKIP 路径 B 套件。
- PG 数据源路径 B 的 `expectedAffectedRows` 恒为 0（已知约束），PG 环境下行阈值分级自然失效，SKIP 行数阈值验证。
- 真实环境篡改 `dm_db_change_revision` / `dm_exec_auto_task` 表数据需在隔离测试环境进行，不得在生产环境操作。
