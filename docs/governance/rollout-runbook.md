# 数据库变更治理 · 灰度上线 Runbook

> 需求源：spec rev.2.1 §8.3 灰度路径三步走 + §13-4 IM Provider 预检 + §8.1-P12 验收。
> 真实环境验证清单：`tests/governance/prod-verification.md`（本文档退出判据引用，不重复内容）。
> 浏览器操作面：`tests/frontend/governance/governance-flow.md`（本文档操作步骤引用）。
> 产码零改动：本文档不包含任何代码变更；所有操作入口均为现有 API/页面/DB 通道。

## 概述与灰度原则

治理角色标记天然支持逐库灰度（spec §8.3）：通过环境参数 `GOV_ROLE` 标记 PRE/PROD 治理角色，即可逐库启用治理链路；移除标记即回未治理态。

灰度安全底线：**未标记 `GOV_ROLE` 的环境全程行为零变化**——现有普通工单不受治理约束（代码保证：`govRole==null` 时 guard/PreInit 等环节 short-circuit PASS，零治理表查询）。

三步走路线：
1. **阶段 1**：选一个非核心逻辑库标 PRE/PROD 治理角色，只跑路径 A（PRE 提交 → 冻结 → 生产发布）。
2. **阶段 2**：稳定后为该 PROD 环境开 `GOV_DML_DIRECT` 试跑路径 B（直发生产 DML）。
3. **阶段 3**：逐步扩大逻辑库范围与环境标记。

---

## 阶段 0 · 前置

### 0.1 部署核验

治理平台 Flyway 迁移 `V202609070001__db_change_governance` 必须已执行成功。

**追踪表名**：`dm_update_history`（非标准 Flyway `flyway_schema_history`——CloudDM 自定义追踪表名，见 `DmFlywayInit.TABLE` 常量）。

**核验 SQL**：

```sql
-- 检查迁移是否成功执行（success=1 表示成功）
SELECT installed_rank, version, description, type, success, installed_on, installed_by
FROM dm_update_history
WHERE version = '202609070001' AND success = 1;
```

> 注意：`type` 列存储的是迁移类型（Java 迁移为 `JDBC`），不是执行状态。执行成功与否看 `success` 列（`1`=成功）。

**治理 10 张表存在性核验**：

```sql
-- 权限组域（4 表）
SHOW TABLES LIKE 'dm_perm_group';
SHOW TABLES LIKE 'dm_perm_group_member';
SHOW TABLES LIKE 'dm_perm_group_resource';
SHOW TABLES LIKE 'dm_perm_group_grant_record';
-- 环境治理域（2 表）
SHOW TABLES LIKE 'dm_logical_db';
SHOW TABLES LIKE 'dm_logical_db_env_binding';
-- 变更治理域（4 表）
SHOW TABLES LIKE 'dm_db_change_stmt_version';
SHOW TABLES LIKE 'dm_db_change_revision';
SHOW TABLES LIKE 'dm_db_change_promotion';
SHOW TABLES LIKE 'dm_db_change_event';
```

**应用日志核验**：搜索 `InstallUpgradeLogBus` 输出的 `V202609070001__db_change_governance` 成功标记。

### 0.2 权限标签分配

治理功能权限标签共 5 个（定义于 `SecRoleAuthLabel.java`，`CAT_RDP_DB_CHANGE_GOVERN` 分类）：

| 标签名 | order | 默认角色 | 用途 |
|---|---|---|---|
| `RDP_DB_CHANGE_GOVERN_READ` | 0 | DBA_ROLE_NAME, ADMIN_ROLE_NAME | 治理只读（浏览 promotion/revision） |
| `RDP_PERM_GROUP_MANAGE` | 1 | DBA_ROLE_NAME, ADMIN_ROLE_NAME | 权限组管理（CRUD 权限组/成员/资源/授权） |
| `RDP_LOGICAL_DB_MANAGE` | 2 | DBA_ROLE_NAME | 逻辑库管理（CRUD 逻辑库/绑定） |
| `RDP_DB_CHANGE_PROD_PROMOTE` | 3 | DBA_ROLE_NAME | 路径 A 生产发布（promote） |
| `RDP_DB_CHANGE_PROD_DML_DIRECT` | 4 | DBA_ROLE_NAME | 路径 B 直发 DML 提交 |

工单相关标签（非治理专用，但治理工单也依赖）：

| 标签名 | 默认角色 | 用途 |
|---|---|---|
| `RDP_WORKER_ORDER_REQUEST` | DBA_ROLE_NAME, DEV_ROLE_NAME | 工单请求（preSubmit/correctStatement/splitPreview） |
| `RDP_WORKER_ORDER_READ` | DBA_ROLE_NAME, DEV_ROLE_NAME | 工单读（stmtTimeline/eventTimeline） |
| `RDP_WORKER_ORDER_EXECUTE` | DBA_ROLE_NAME | 执行确认（confirm；治理工单的 SYSTEM 自动确认绕过此标签） |
| `RDP_WORKER_ORDER_APPROVE` | DBA_ROLE_NAME | 工单审批 |

**分配操作**：

- 前端页面：角色管理页（路由 `/manager/role`）→ 角色列表页 `frontend/src/views/system/role/index.vue` → 点击「创建角色」→ 角色编辑器 `frontend/src/views/system/role/RoleEditorPage.vue`（路由 `/manager/role/create`）→ 在权限标签树中勾选治理标签 → 保存。
- API 正门：`POST /api/entry/role/listRoleAuthLabelTree` 获取标签树 → `POST /api/entry/role/createRole` 或 `POST /api/entry/role/updateRole` 创建/更新角色（含标签分配）。鉴权：`RDP_ROLE_MANAGE`。
- 内置角色（`innerTag=true`）的标签由系统迁移注册，不可前端编辑；自定义角色由 DBA 管理员创建后勾选治理标签并绑定用户。
- `RDP_WORKER_ORDER_EXECUTE` 默认 tag 到 DBA 角色——治理工单的人工确认仍需此标签；SYSTEM 自动确认（`confirmTicketBySystem`）绕过此标签检查。

### 0.3 环境参数四键配置

治理环境参数共 4 个键（定义于 `EnvParamKeys.java`），**无前端 UI 设置入口**——`frontend/src/views/system/env.vue` 只处理 `dm_allow_all_statements`/`check_spec_id`/`ticket_info` 三类既有键，不含 `GOV_*` 键。逻辑库绑定列表页（`/manager/logicalDb`）只读展示治理角色与开关状态，无编辑入口。

这是 spec 的有意设计（ADR-005：治理角色走 env param 免改 `dm_sys_env` 表），低频管理员操作走 API 正门。

每键提供**三件套**：API 正门 + DB 应急旁路 + 核验查询。

#### GOV_ROLE

- 取值：`PRE` / `PROD` / null（null = 未治理，灰度安全底线）
- 读取方：`LogicalDbServiceImpl.bindingSet()` 冲突检查；`getBinding()` 角色匹配；`myLogicalDbs()` 可见性过滤。
- `GovRole` 枚举只有 `PRE` 和 `PROD` 两个值（`GovRole.java`）；`myLogicalDbs()` 只展示 `GOV_ROLE ∈ {PRE, PROD}` 的绑定（null/empty = 不可见）。

**API 正门**：
```
POST /api/entry/envparam/bindEnvParam
Content-Type: application/json
{ "envId": <环境ID>, "paramKey": "GOV_ROLE", "paramValue": "PRE" }
```
鉴权：`DM_DS_MANAGE`。FO 字段（`DmBindEnvParamFO`）：`envId`(long) / `paramKey`(String, @NotBlank) / `paramValue`(String, @NotBlank)。

**DB 应急旁路**（仅在 API 不可用时使用，**绕过鉴权与审计，生产库直改风险**）：
```sql
-- 写入
INSERT INTO dm_sys_env_param (gmt_create, gmt_modified, env_id, config_key, config_value, primary_uid)
VALUES (NOW(), NOW(), <环境ID>, 'GOV_ROLE', 'PRE', '<主账号UID>');
-- 更新（已存在时）
UPDATE dm_sys_env_param SET config_value = 'PROD', gmt_modified = NOW()
WHERE env_id = <环境ID> AND config_key = 'GOV_ROLE';
```

表：`dm_sys_env_param`（`DmSysEnvParamDO`，`@TableName("dm_sys_env_param")`）；列名：`id`, `gmt_create`, `gmt_modified`, `env_id`, `config_key`, `config_value`, `primary_uid`。

**核验查询**：
```sql
SELECT env_id, config_key, config_value
FROM dm_sys_env_param
WHERE config_key = 'GOV_ROLE' AND env_id = <环境ID>;
```

**冲突检查**：`LogicalDbServiceImpl.bindingSet()` 在设置绑定时预检 GOV_ROLE 冲突（同一 non-empty role 不能分配给多个环境），保证 `getBinding()` 的确定性（0 match → throw；>1 match → throw conflict）。

**移除（回滚操作）**：
- API：`POST /api/entry/envparam/unbindEnvParam` `{ "envId": <环境ID>, "paramKey": "GOV_ROLE" }`（鉴权 `DM_DS_MANAGE`；FO `DmUnbindEnvParamFO`：`envId`/`paramKey`）。
- DB 旁路：`DELETE FROM dm_sys_env_param WHERE env_id = <环境ID> AND config_key = 'GOV_ROLE'`。

#### GOV_DML_DIRECT

- 取值：`on` / `off` / null（null/off = 路径 B 关闭）
- 读取方：`GovDirectDmlServiceImpl.directDmlSubmit()`：`!"on".equals(directSwitch)` → reject。

**API 正门**：
```
POST /api/entry/envparam/bindEnvParam
{ "envId": <环境ID>, "paramKey": "GOV_DML_DIRECT", "paramValue": "on" }
```

**DB 应急旁路**：
```sql
INSERT INTO dm_sys_env_param (gmt_create, gmt_modified, env_id, config_key, config_value, primary_uid)
VALUES (NOW(), NOW(), <环境ID>, 'GOV_DML_DIRECT', 'on', '<主账号UID>');
```

**核验查询**：
```sql
SELECT env_id, config_key, config_value
FROM dm_sys_env_param
WHERE config_key = 'GOV_DML_DIRECT' AND env_id = <环境ID>;
```

**移除**：API `unbindEnvParam` 或设 `paramValue: "off"`（效果等同 null/off）。

#### GOV_DML_ROW_LIMIT

- 取值格式：`warn:N,block:M`（逗号分隔，冒号分隔键值，大小写不敏感）
- 容错（`GovRowLimitConfig.parse()`）：missing/invalid segment → 该级别视为未配置(null)；空串 → 整体未配置。
- 语义：
  - `shouldBlock(rows)`: `rows > block`
  - `shouldWarn(rows)`: `warn < rows <= block`（需 warn 和 block 同时配置）
  - 边界：`rows == block` → warn（不 block）；`rows == warn` → normal

**API 正门**：
```
POST /api/entry/envparam/bindEnvParam
{ "envId": <环境ID>, "paramKey": "GOV_DML_ROW_LIMIT", "paramValue": "warn:1000,block:100000" }
```

**DB 应急旁路**：
```sql
INSERT INTO dm_sys_env_param (gmt_create, gmt_modified, env_id, config_key, config_value, primary_uid)
VALUES (NOW(), NOW(), <环境ID>, 'GOV_DML_ROW_LIMIT', 'warn:1000,block:100000', '<主账号UID>');
```

**核验查询**：
```sql
SELECT env_id, config_key, config_value
FROM dm_sys_env_param
WHERE config_key = 'GOV_DML_ROW_LIMIT' AND env_id = <环境ID>;
```

#### GOV_AUTO_CONFIRM

- 取值：`on` / `off` / null（null/off = 不自动确认）
- 读取方：`GovAutoConfirmServiceImpl.autoConfirmProdTickets()`：filter 4 判定（PROD governance ticket ∧ promotion exists ∧ not SUCCEEDED/REJECTED/CANCELLED ∧ `GOV_AUTO_CONFIRM == "on"`）→ `confirmTicketBySystem(ticketId, config)`。
- 语义：审批通过后自动确认执行（跳过人工确认环节）；自动路径同样必经 Guard+Preflight（gate-two），不绕过任何门禁。

**API 正门**：
```
POST /api/entry/envparam/bindEnvParam
{ "envId": <环境ID>, "paramKey": "GOV_AUTO_CONFIRM", "paramValue": "on" }
```

**DB 应急旁路**：
```sql
INSERT INTO dm_sys_env_param (gmt_create, gmt_modified, env_id, config_key, config_value, primary_uid)
VALUES (NOW(), NOW(), <环境ID>, 'GOV_AUTO_CONFIRM', 'on', '<主账号UID>');
```

**核验查询**：
```sql
SELECT env_id, config_key, config_value
FROM dm_sys_env_param
WHERE config_key = 'GOV_AUTO_CONFIRM' AND env_id = <环境ID>;
```

### 0.4 IM Provider 预检（硬前置）

**未配置 IM Provider 时禁止进入阶段 1**——这是灰度安全硬前置。

**预检理由**（按实现行为修正，D-P12-3）：

设计文档 §13-4 原述"未配置 IM Provider 抛异常且无降级"，但 Phase 5 实现（`GovFailureNotifyServiceImpl`）做了容错：
- 无 messenger 时 `log.warn("[GovFailureNotify] no IM messenger configured...")` + `return false`（不写 `FAIL_NOTIFIED` 事件，下次扫描重试），**不抛异常、不阻断主链路**。
- IM Provider 不可用时（网络异常/插件未安装），抛异常但被 catch 吞掉，通知不送达但不阻断治理主链路。
- 通知是 `GovPipelineScheduler` duty 3 的独立扫描线程，与主链路异常隔离。

**但修正闭环是通知驱动**——无 IM 则语句失败时提交人无人知晓（工单静默卡在 `EXEC_FAIL` 状态等重试），闭环不可用。因此预检不是防止链路崩溃，而是确保修正闭环可用。

**预检操作**：

1. 前端配置：IM 配置页（路由 `/integrations/im`）→ `frontend/src/views/im/index.vue` → 点击「新增」→ 表单 `frontend/src/views/im/form.vue`（路由 `/integrations/im/create`）→ 选择 IM 类型（钉钉/飞书/企微）→ 填写 webhook + secret → 保存。
2. API 配置：`POST /api/entry/devops/im/add`（鉴权 `DM_IM_MANAGE`）。
3. **测试发送**（核心预检手段）：前端 IM 列表页点击「测试」按钮 → 调用 `POST /api/entry/devops/im/test`（鉴权 `DM_IM_MANAGE`）→ `DmImServiceImpl.testImByConfig()` 构建 `ImSenderConfig` + 测试消息 → `ImSenderServiceImpl.sendMessage()` → 发送到 webhook → 返回 `MsgSendResult`，失败抛 `ErrorMessageException`。
4. 确认测试消息在 IM 群/个人收到。

**存储表**：`dm_sys_messenger`（`DmSysMessengerDO`：`owner_uid`/`im_type`/`im_display`/`webhook`/`secret`/`enable`）。
**Provider 类型**（`ImType.java`）：`DingTalk` / `Wechat` / `Feishu`。

### 0.5 逻辑库与绑定创建

1. **创建逻辑库**：
   - 前端：逻辑库管理页（路由 `/manager/logicalDb`）→ `frontend/src/views/system/logicalDb/index.vue` → 点击「创建逻辑库」。
   - API：`POST /api/entry/logicalDb/create`（`{ resourceCode, resourceName, description }`）。鉴权：`RDP_LOGICAL_DB_MANAGE`。
2. **设置环境绑定**（envId + dsId + resPath）：
   - 前端：逻辑库列表 → 点击「资源绑定」→ 弹出绑定编辑 modal → 添加绑定行（选择环境/数据源/资源路径）→ 保存。
   - API：`POST /api/entry/logicalDb/bindingSet`（`{ logicalDbId, bindings: [{ envId, dsId, resPath }] }`）。鉴权：`RDP_LOGICAL_DB_MANAGE`。
   - `BindingSetFO` **不含 GOV_ROLE 字段**——绑定操作只创建 env-ds-resPath 映射，不设置治理角色。GOV_ROLE 在 0.3 单独配置。
   - `resPath` 约束：经 `DmDsUtils.normalizeResourcePath` 规范化，段数 ∈ [1,2]（`/db/` 或 `/db/schema/`；root `/` 和 table-level ≥3 被拒——治理粒度是 catalog/schema）。
3. **核验绑定**：
   - API：`POST /api/entry/logicalDb/bindingList` `{ id: <逻辑库ID> }` → 返回的 binding VO 中 `govRole`/`govDmlDirect`/`govDmlRowLimit`/`govAutoConfirm` 字段反映当前 env param 配置状态。
   - 前端：逻辑库绑定列表页的「治理角色」列显示对应 Tag。

### 阶段 0 进入判据

- [ ] `dm_update_history` 查到 `V202609070001` 且 `success = 1`。
- [ ] 治理 10 张表全部存在。
- [ ] 5 个治理标签已分配到 DBA 角色（或自定义角色并绑定用户）。
- [ ] PRE 环境配置 `GOV_ROLE=PRE`，PROD 环境配置 `GOV_ROLE=PROD`（通过 API 或 DB 写入并核验）。
- [ ] IM Provider 已配置且测试消息可送达（`/api/entry/devops/im/test` 返回成功）。
- [ ] 逻辑库已创建，PRE/PROD 环境绑定已设置（`bindingList` 返回正确的 dsId/resPath）。
- [ ] PROD 环境审批模板已配置为第三方（非 Internal），见 `tests/governance/prod-verification.md` Preconditions 第 5 条。

---

## 阶段 1 · 路径 A 试点

### 前置条件

阶段 0 全部进入判据达成。

### 非核心库选择判据

- 业务量低（试点期间不影响核心业务）。
- 有 PRE + PROD 双环境且绑定可解析（不同数据库实例或不同 schema）。
- 权限组已创建并包含成员与 PRE/PROD 资源授权（`res_path` 指向试点逻辑库的 PRE/PROD 绑定路径）。
- sidecar worker 在线（`AutoExecScheduleService` 能扫描到在线 worker）。

### 操作步骤

1. 确认 PRE 环境已配置 `GOV_ROLE=PRE`（阶段 0 已完成）。
2. 确认 PROD 环境已配置 `GOV_ROLE=PROD`（阶段 0 已完成）。
3. 提交治理工单：工单创建页（路由 `/ticket_create`）切换到治理工单模式 → 选择逻辑库 → 输入 SQL → 校验并提交。
   - API：`POST /api/entry/dbChangeGovern/preSubmit`（`GovPreSubmitFO`：`logicalDbId` / `ticketTitle` / `sql` / `contentType` / `rollBackSql`）。鉴权：`RDP_WORKER_ORDER_REQUEST`。
4. 观察工单全生命周期：工单详情页（路由 `/ticket/:id`）→ 查看 CREATE/EXPLAIN/APPROVAL/CONFIRM/EXECUTION 步骤条 + 治理事件区。
5. 生产发布：发布页（路由 `/dbChange/promotion`）→ 刷新可用 Revision → 选中冻结的 Revision → 填写描述 → 提交生产发布。
   - API：`POST /api/entry/dbChangeGovern/promote`（`GovPromoteFO`：`revisionId` / `description`）。鉴权：`RDP_DB_CHANGE_PROD_PROMOTE`。
6. 发布详情页（路由 `/dbChange/promotion/:promotionId`）查看门禁清单、审批状态、执行状态、治理事件 Timeline。

### 进入/退出判据

**进入判据**：阶段 0 全部达成。

**退出判据**（引用 `tests/governance/prod-verification.md`，不重复内容）：

- **链路一（路径 A）全生命周期跑通**：对照 `tests/governance/prod-verification.md` §Chain One Step 1-10，逐步骤验证状态与事件。重点：
  - Step 4：`dm_db_change_revision` 写入 `REVISION_FROZEN` 事件。
  - Step 5：门禁一 7 项全 PASS，`PROMOTION_CREATED` 事件写入。
  - Step 8：Guard 门禁二 6 项全 PASS，`GUARD_PASS` 事件写入。
  - Step 9：生产执行成功，promotion 终态 `SUCCEEDED`。
  - Step 10：Timeline 显示完整事件序列。
- **安全底线相关条目**：对照 `tests/governance/prod-verification.md` §安全底线 底线 1-7（路径 A 涉及），逐条验证不可绕过。
- **灰度量级判据**：
  - 试点工单量：建议至少完成 3 个治理工单（DDL / DML / 混合各一），覆盖三种成分路由。
  - DENY 率巡检：`dm_db_change_event` 无非预期 `GATE_DENY`/`GUARD_DENY` 事件（如有，排查原因并记录）。
  - 修正闭环可用性：至少演练一次语句失败 → 通知 → 修正 → 版本升级 → 断点续跑全链路（对照 `tests/governance/prod-verification.md` §Chain One Step 3 失败排查 + Step 4 冻结修正史）。
  - Timeline 完整性抽查：每个试点工单的治理事件 Timeline 显示完整事件序列，无缺失。

### 观察期检查点

- `GovPipelineScheduler` 守护线程运行正常（日志可见 `GovPipelineScheduler started`）。
- `ApprovalTaskScheduler`（1s 守护循环）正常推进工单状态。
- `AutoExecScheduleService`（5s 扫描循环）正常派发执行任务。
- PRE 环境工单能自动推进（`SYSTEM_APPROVE` + `SYSTEM_CONFIRM` 事件写入）。
- 修正闭环通知能送达（IM 收到失败通知消息）。

### 回滚预案

**移除 `GOV_ROLE` 即回未治理态**——代码保证已由 Phase 2-11 测试钉住（`govRole==null` 时 guard/PreInit 等环节 short-circuit PASS，零治理表查询）。

1. 解绑 PRE 环境的 `GOV_ROLE`：
   - API：`POST /api/entry/envparam/unbindEnvParam` `{ "envId": <PRE环境ID>, "paramKey": "GOV_ROLE" }`。
   - DB 旁路：`DELETE FROM dm_sys_env_param WHERE env_id = <PRE环境ID> AND config_key = 'GOV_ROLE'`。
2. 解绑 PROD 环境的 `GOV_ROLE`（同上，替换 envId）。
3. 核验普通工单回归：提交一个普通工单（不选治理模式），确认工单按现有流程推进（PRE_INIT → WAIT_APPROVAL → WAIT_CONFIRM → WAIT_EXEC → RUNNING → FINISHED），不受治理约束。
4. 核验 `bindingList` 返回的 VO 中 `govRole` 字段为 null。
5. 进行中的试点工单处置：已冻结的 Revision 不会被删除（不可变），但新工单不再受治理约束。已创建的 promotion 保留在表中（终态不可变），可手动关闭未完成的 promotion。

---

## 阶段 2 · 路径 B 试跑

### 前置条件

阶段 1 退出判据全部达成（路径 A 稳定运行，修正闭环可用，Timeline 完整）。

### 操作步骤

1. 为试点 PROD 环境开启 `GOV_DML_DIRECT`：
   - API：`POST /api/entry/envparam/bindEnvParam` `{ "envId": <PROD环境ID>, "paramKey": "GOV_DML_DIRECT", "paramValue": "on" }`。
2. 配置 `GOV_DML_ROW_LIMIT` 初始保守值（**按业务校准**，以下为量级示例）：
   - API：`POST /api/entry/envparam/bindEnvParam` `{ "envId": <PROD环境ID>, "paramKey": "GOV_DML_ROW_LIMIT", "paramValue": "warn:1000,block:10000" }`。
   - 初始建议保守：`warn:1000,block:10000`——超 1000 行标注高风险，超 10000 行拒绝提交。根据实际业务数据量调整。
3. 确认 `RDP_DB_CHANGE_PROD_DML_DIRECT` 标签已分配给直发 DML 负责人。
4. 提交直发 DML：发布页（路由 `/dbChange/promotion`）路径 B 区域 → 选择逻辑库 → 输入 DML + 回滚 SQL → 填写描述 → 提交直发生产 DML。
   - API：`POST /api/entry/dbChangeGovern/directDmlSubmit`（`GovDirectDmlSubmitFO`：`logicalDbId` / `sql` / `rollbackSql` / `description`）。鉴权：`RDP_DB_CHANGE_PROD_DML_DIRECT`。
5. 观察路径 B 全生命周期：发布详情页查看审批表单（含影响行数、风险等级）、Guard 门禁、执行状态、Timeline。

### PG 环境约束

如果试点逻辑库的 PROD 环境是 PostgreSQL，需注意以下已知约束（非缺陷，spec §13-3）：

1. **DML Explain 降级**：PG 的 `PgExplainPlanSpi` 显式排除 INSERT/UPDATE/DELETE/MERGE 的行估算 → `GovDmlRowEstimator` 走 `UNSUPPORTED` 分支 → `estimatedRows` 恒为 0。
2. **阈值自然失效**：`GovRowLimitConfig.shouldBlock(0)` = `0 > block` = false（永远不 block）；`shouldWarn(0)` = `0 > warn` = false（永远不 warn，因为 `0 <= warn`）。
3. **降级行为**：PG 环境下 `GOV_DML_ROW_LIMIT` 配置的阈值**自然失效**——路径 B 的风险控制依赖**规则审计 + 人工确认**（PreInit 规则审计 + gate-two Guard + 人工审批），而非行数阈值。
4. **代码证据**：`GovDmlRowEstimator.java` L131-133：`!explainSpi.supportByQueryType(analyzed.getQueryTypes())` → `branch = "UNSUPPORTED"`, `rows = 0`。

### 进入/退出判据

**进入判据**：阶段 1 退出达成。

**退出判据**（引用 `tests/governance/prod-verification.md`，不重复内容）：

- **链路二（路径 B）全生命周期跑通**：对照 `tests/governance/prod-verification.md` §Chain Two Step B1-B5，逐步骤验证状态与事件。重点：
  - Step B2：`DIRECT_DML_SUBMIT` 事件写入，审批表单含影响行数与风险等级。
  - Step B5：Guard 门禁二通过，生产执行成功，promotion 终态 `SUCCEEDED`。
- **安全底线第 8 条**：对照 `tests/governance/prod-verification.md` §安全底线 底线 8，验证路径 B 非纯 DML 被拒、开关关闭被拒、行数超 block 被拒。
- **灰度量级判据**：至少完成 2 个路径 B 工单（正常 DML + 超阈值拒绝各一），验证阈值分级生效（PG 环境除外，阈值自然失效）。

### 回滚预案

**关闭 `GOV_DML_DIRECT` 即回未治理态**（PROD 环境仍保留 `GOV_ROLE=PROD`，路径 A 仍可用）。

1. 关闭开关：
   - API：`POST /api/entry/envparam/unbindEnvParam` `{ "envId": <PROD环境ID>, "paramKey": "GOV_DML_DIRECT" }`。
   - 或设 `paramValue: "off"`（效果等同 null/off）。
   - DB 旁路：`DELETE FROM dm_sys_env_param WHERE env_id = <PROD环境ID> AND config_key = 'GOV_DML_DIRECT'`。
2. 核验：`bindingList` 返回 VO 的 `govDmlDirect` 字段为 null/off；`directDmlSubmit` 接口拒绝（返回 "Path B direct DML is not enabled for this environment"）。

**进行中工单处置**（关闭开关后，已提交但未完成执行的路径 B 工单）：

路径 B 工单是标准 `DM_CHANGE` 工单（`dm_approval` 表），状态机为 `ApprovalStatus`。按工单当前状态处置：

| 工单状态 | 处置方式 |
|---|---|
| `PRE_INIT_WAIT` / `PRE_INIT_RUN` | 可正常走完 PRE_INIT → 审批 → 确认 → 执行（开关关闭不影响已提交工单的推进，因为工单的 `ticketInfo` 已写入 `govRole=PROD`，治理链路已激活） |
| `WAIT_APPROVAL` | 审批人可正常审批通过或拒绝；通过后进入 `WAIT_CONFIRM` |
| `WAIT_CONFIRM` | 确认人可执行确认（Guard 门禁二仍生效）或关闭工单（`CLOSED` 终态） |
| `WAIT_EXEC` / `RUNNING` | 等待执行完成；可正常完成或执行失败（`EXEC_FAIL`）后走修正闭环 |
| `EXEC_FAIL` | 可修正语句后 `retryJob` 断点续跑；PROD 不允许就地修正（返回 PRE 工单重提交） |
| 终态（`FINISHED` / `REJECTED` / `CLOSED` / `FAILED` / `CANCELED`） | 无需处置，终态不可变 |

> 关键：关闭 `GOV_DML_DIRECT` 只阻止**新提交**的路径 B 工单，不影响**已提交**工单的推进——工单的治理状态由 `ticketInfo.govRole` 决定，不依赖运行时读取 `GOV_DML_DIRECT`。如需彻底停止已提交工单的治理推进，需移除该环境的 `GOV_ROLE`（但会影响路径 A 工单，不推荐）。

---

## 阶段 3 · 扩大

### 批量标记顺序

1. **先 PRE 后 PROD**：先为新逻辑库的 PRE 环境标 `GOV_ROLE=PRE`，观察路径 A 链路稳定后，再标 PROD 环境的 `GOV_ROLE=PROD`。
2. **逐库观察**：每新增一个逻辑库的治理标记后，至少观察一个完整工单生命周期再继续扩大。
3. **每批核验点**：
   - 新库的 `bindingList` 返回正确的 `govRole` / `govDmlDirect` 等字段。
   - 新库的治理工单能正常推进（preSubmit → 审计 → 执行 → 冻结）。
   - DENY 事件巡检无非预期激增。
   - Timeline 完整性抽查。

### GOV_AUTO_CONFIRM 启用建议

**何时可开**：
- 阶段 1/2 稳定运行至少一个完整观察周期。
- 所有门禁（gate-one 7 项 + gate-two 6 项）记录全绿，无异常 DENY。
- 修正闭环至少演练一次并可用。
- 审批流程稳定（钉钉回调正常同步）。

**风险说明**：
- 启用 `GOV_AUTO_CONFIRM=on` 后，PROD 治理工单审批通过即自动确认执行，跳过人工确认环节。
- **但门禁二全保留**：自动确认走 `confirmTicketBySystem` → 同样调用 `prepareExecJobAsSystemAsync` → 同样插入 gate-two Guard 调用 → `GovExecutionGuardServiceImpl.checkByTicket` 全 6 项门禁执行。自动路径无绕过（代码保证：SYSTEM-copy insertion 与人工 confirm 字节级一致）。
- `GovAutoConfirmServiceImpl` 是 `GovPipelineScheduler` duty 5（1s 守护循环），独立 try-catch，异常不影响其他 duty。

**建议**：先在 PRE 环境启用（PRE 工单的自动确认由 duty 1 `GovAutoAdvanceService` 处理，不依赖 `GOV_AUTO_CONFIRM`）；PROD 环境在阶段 3 稳定后谨慎启用。

---

## 监控巡检

### DENY 三事件巡检

DENY 相关事件类型 3 种（`GovEventType` 枚举，共 15 种）：

| 事件类型 | 语义 | eventData 字段结构 |
|---|---|---|
| `GATE_DENY` | 门禁一（7-gate）评估失败 | `{items:[{item,pass,reason,timestamp}], revisionId, operator}` |
| `GUARD_DENY` | 门禁二（6-gate execution guard）失败 | 6-gate 评估结果 JSON，含 denyItem + evidence |
| `DIRECT_DML_DENY` | 路径 B 阈值拒绝 | `{logicalDbId, estimatedRows, warn, block, evidence}` |

**巡检 SQL**（表 `dm_db_change_event`，列：`id`, `gmt_create`, `gmt_modified`, `promotion_id`, `revision_id`, `ticket_id`, `event_type`, `from_status`, `to_status`, `operator_uid`, `event_data`）：

```sql
-- DENY 事件查询（近 24 小时）
SELECT id, gmt_create, promotion_id, revision_id, ticket_id,
       event_type, from_status, to_status, operator_uid, event_data
FROM dm_db_change_event
WHERE event_type IN ('GATE_DENY', 'GUARD_DENY', 'DIRECT_DML_DENY')
  AND gmt_create >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
ORDER BY gmt_create DESC;
```

`event_data` 是 `longtext` JSON，字段结构因事件类型而异（见上表）。可用 `JSON_EXTRACT` 提取关键字段：

```sql
-- 提取 GATE_DENY 的失败项
SELECT gmt_create, ticket_id,
       JSON_EXTRACT(event_data, '$.items') AS gate_items,
       JSON_EXTRACT(event_data, '$.revisionId') AS revision_id
FROM dm_db_change_event
WHERE event_type = 'GATE_DENY'
ORDER BY gmt_create DESC;
```

### Timeline 页面入口

- **发布详情页**：路由 `/dbChange/promotion/:promotionId`（`frontend/src/views/dbChange/promotionDetail.vue`）→ 治理事件 Timeline 区域。
  - API：`POST /api/entry/dbChangeGovern/eventTimeline` `{ ticketId }`。鉴权：`RDP_WORKER_ORDER_READ`。
- **工单详情页**：路由 `/ticket/:id`（`frontend/src/views/ticket/ticketDetail.vue`）→ 治理事件区 + 语句级 Timeline。
  - API：`POST /api/entry/dbChangeGovern/stmtTimeline` `{ ticketId }`。鉴权：`RDP_WORKER_ORDER_READ`。
- 前端事件常量映射：`frontend/src/views/dbChange/govEventConstants.js`（15 种 `GovEventType` + 9 `PromotionStatus` + 2 `PromotionType` + 3 `ChangeType`）。

### 审计表巡检

- **`dm_exec_sql_audit`**：SQL 执行审计（sidecar 写）。治理工单执行的 SQL 在此可查。
- **`dm_mon_op_audit`**：操作审计（`RdpOpAuditService`）。治理相关操作审计类型（`AuditType` 枚举）：
  - 权限组：`CREATE_PERM_GROUP` / `UPDATE_PERM_GROUP` / `DELETE_PERM_GROUP` / `ENABLE_PERM_GROUP` / `DISABLE_PERM_GROUP` / `ADD_PERM_GROUP_MEMBER` / `REMOVE_PERM_GROUP_MEMBER` / `GRANT_PERM_GROUP_RESOURCE` / `REVOKE_PERM_GROUP_RESOURCE`
  - 逻辑库：`CREATE_LOGICAL_DB` / `UPDATE_LOGICAL_DB` / `DELETE_LOGICAL_DB` / `SET_LOGICAL_DB_BINDING`
  - 路径 B：`SUBMIT_DB_CHANGE_DIRECT_DML`
  - 资源类型（`ResourceType` 枚举）：`PERM_GROUP` / `LOGICAL_DB`
- **`dm_mon_biz_log`**：执行失败错误日志（`GovFailureNotifyServiceImpl.fetchErrorSummary()` 查询 `LogDependBizType.AUTO_EXEC_TASK` + `Loglevel.ERROR`）。

### 异常升级路径

1. **DENY 事件异常增多**：检查 `event_data` JSON 定位是哪条门禁失败 → 对照 `governance-contracts.md` 契约文档排查根因。
2. **工单卡在 `WAIT_APPROVAL`**：检查 PRE 环境 `GOV_ROLE=PRE` 是否配置 → `GovPipelineScheduler` 是否运行 → 审批模板是否为 Internal 自动模板。
3. **工单卡在 `WAIT_EXEC`**：检查 sidecar worker 是否在线 → `AutoExecScheduleService` 是否运行。
4. **修正闭环通知不送达**：检查 IM Provider 是否正常 → `GovFailureNotifyServiceImpl` 日志是否有 `no IM messenger configured` 警告。
5. **生产执行失败**：PROD 不允许就地修正——返回 PRE 工单修正重提交（spec D14）；路径 B 失败需改 SQL → 重新提交新的路径 B 工单。

---

## P1 改进候选

以下为调研和写作过程中发现的摩擦点，记入改进候选清单，不在本期实现。

### 1. GOV_* env param UI 编辑器

**问题**：`GOV_ROLE` / `GOV_DML_DIRECT` / `GOV_DML_ROW_LIMIT` / `GOV_AUTO_CONFIRM` 四键无前端 UI 设置入口，只能通过 API 直调或 DB 直写。逻辑库绑定列表页只读展示这些值，无法编辑。

**影响**：灰度标记是低频管理员操作，但缺乏 UI 使得操作门槛高（需用 curl/Postman 调 API 或直改 DB）。

**改进建议**：在逻辑库绑定列表页或环境参数管理页增加 `GOV_*` 键的编辑入口，复用现有 `bindEnvParam` / `unbindEnvParam` API。`BindingSetFO` 可扩展 `govRole` / `govDmlDirect` / `govDmlRowLimit` / `govAutoConfirm` 字段，在 `bindingSet` 时一并写入 env param。

**优先级**：P1（不影响功能正确性，仅影响操作体验）。

### 2. 阶段 0 部署核验自动化

**问题**：阶段 0 的部署核验（`dm_update_history` 查询 + 10 表存在性检查 + env param 配置核验）目前是手工 SQL 操作。

**改进建议**：可在平台系统管理页增加治理健康度检查面板，一键展示迁移状态、表存在性、env param 配置状态。

**优先级**：P2。

### 3. DENY 事件巡检面板化

**问题**：DENY 事件巡检目前需手工 SQL 查询 `dm_db_change_event` 表。

**改进建议**：在发布详情页或治理管理页增加 DENY 事件巡检面板，展示近期 DENY 事件及其 `event_data` 解析。

**优先级**：P2。
