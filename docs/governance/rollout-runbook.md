# 数据库变更治理 · 灰度上线 Runbook

> 适用版本：P1–P5 治理 v2（库级映射模型）。本文档反映 P5 旧链路下线后的最终形态。
> 旧 GOV_ROLE / promotion / revision 链路已物理下线；本文档不再描述其操作面。
> 关联文档：[`openapi-dingtalk.md`](./openapi-dingtalk.md)（钉钉 / 外部审批对接细节）。

## 概述与灰度原则

治理 v2 以 **库级映射表（`dm_db_pair`）** 为灰度开关：一对 `(预发数据源 + 预发库, 生产数据源 + 生产库)` 绑定到一个服务后，该库即纳入治理。**清空 `dm_db_pair` 中对应行即等价于行为回退**——该库回到普通工单流程，无任何治理约束。

灰度安全底线：**未在 `dm_db_pair` 中登记的库全程行为零变化**——普通工单不受治理约束（代码保证：v2 提单按 `ticketType` 区分，未登记库不产生 PRE_DDL/PROD_DML 工单，guard / dispatch 对非治理工单 short-circuit PASS，零治理表查询）。

灰度三步走：
1. **纳管**：为一个非核心库在「库映射」页登记一对预发/生产库与归属服务。
2. **试跑 PRE_DDL**：对登记库发起 PRE_DDL 工单，走自动审批 → 预检 → 自动执行 → 台账 → 转产发布单。
3. **扩大**：稳定后逐步为更多库登记映射。

---

## 阶段 0 · 前置

### 0.1 部署核验

治理 v2 迁移必须已执行成功（追踪表 `dm_update_history`，非标准 Flyway `flyway_schema_history`）：

```sql
SELECT installed_rank, version, description, type, success, installed_on, installed_by
FROM dm_update_history
WHERE version IN ('202609070001','202609110001','202609110002','202609110003') AND success = 1
ORDER BY installed_rank;
```

- `V202609070001`：历史迁移，创建治理域 10 张表（权限组 4、逻辑库 2、变更治理 4）。
- `V202609110001`（P1）：`dm_db_pair` / `dm_db_pair_service` / `dm_db_service`（库级映射模型）。
- `V202609110002`（P2）：`dm_ticket_db_stmt` + `dm_exec_auto_job` 加列（v2 工单与组任务）。
- `V202609110003`（P3）：`dm_prod_release` / `dm_prod_release_stmt` + `dm_exec_auto_job` 加列（生产发布单）。

> `type` 列存迁移类型（Java 迁移为 `JDBC`），执行成败看 `success`（`1`=成功）。

应用日志核验：搜索 `InstallUpgradeLogBus` 输出的上述迁移成功标记。

### 0.2 权限标签分配

治理 v2 复用既有权限标签，无需新增治理专用标签：

- 工单域：`RDP_WORKER_ORDER_READ` / `RDP_WORKER_ORDER_REQUEST` / `RDP_WORKER_ORDER_APPROVE` / `RDP_WORKER_ORDER_EXECUTE`。
- 库映射与台账：`RDP_LOGICAL_DB_MANAGE` / `RDP_DB_PAIR_MANAGE` / `RDP_WORKER_ORDER_READ`。
- 发布单：`RDP_DB_CHANGE_PROD_PROMOTE`（创建/操作发布单）。
- OpenAPI：主账号 AK/SK（见 §openapi）。

> 旧标签 `RDP_DB_CHANGE_GOVERN_READ`（promotion 页）与 `RDP_DB_CHANGE_PROD_DML_DIRECT`（路径 B 直发）随旧链路下线，前端菜单与路由已移除；标签枚举值保留以兼容历史授权数据，运维可按需清理。

### 0.3 库映射与服务维护

在「系统管理 · 库映射」页（`/manager/dbPair`）维护：

1. 新建服务（`dm_db_service`：服务编码 + 名称）。
2. 新建库映射（`dm_db_pair`）：选预发数据源 + 预发库名、生产数据源 + 生产库名，关联服务（`dm_db_pair_service`，可多对多）。
3. 逻辑库与绑定（`/manager/logicalDb`）：逻辑库本体与绑定维护保留，仅去除了治理角色语义——绑定下拉与 CRUD 不变。

---

## 阶段 1 · v2 工单（两类型 + 预检闸门）

治理 v2 工单按 `ticketType` 分两类，均在工单创建页以治理模式提交：

- **PRE_DDL**：预发库 DDL 变更。提交后走 SYSTEM 自动审批 → 自动确认 → 按组（`dm_ticket_db_stmt`）创建执行任务 → 预检 → 执行。
- **PROD_DML**：生产库 DML 订正。需人工审批通过后执行。

**预检闸门**：v2 提单时按 `analysisRulesStream` 直接调用预检（控制台拦截复用 `GovStmtSplitServiceImpl.isDdlType/isDmlType` 做成分纯度校验）。PRE_DDL 必须为纯 DDL，PROD_DML 必须为纯 DML；不满足在提交时即拒。

**SYSTEM 自动推进**：`GovPipelineScheduler` 单 duty（`advancePreTickets`）按 `ticketType==PRE_DDL ∧ status==WAIT_APPROVAL` 过滤，执行 SYSTEM 自动审批 + `confirmTicketBySystemForV2` + 按组 `createGroupJob`。

---

## 阶段 2 · 台账（Ledger）

台账页（`/dbChange/ledger`）按逻辑库聚合展示已完成的 PRE_DDL 工单及其语句组状态，并支持「创建生产发布单」入口（`govLedger*` 端点）。台账详情（`/dbChange/ledger/detail`）按组展示语句执行结果与预检状态。

---

## 阶段 3 · 生产发布单（三重保险）

发布单（`/dbChange/release`，`govRelease*` 端点）将已完成的 PRE_DDL 冻结快照推进到生产：

1. **快照一致性**：`dm_prod_release_stmt` 存 stmt hash（`GovSqlHashUtils`），执行前重算校验，防篡改。
2. **dispatch 闸门**：`AutoExecServiceImpl.dispatchJob` 三分支——release 分支校验 release 状态 EXECUTING 且 stmt 可重试；group 分支走 v2 guard；legacy 分支走 guard。任一 DENY 删任务并恢复。
3. **状态机**：`ProdReleaseStateMachine` 独立状态机（APPROVING/APPROVED/EXECUTING/DONE/PARTIAL_FAILED/REJECTED/CANCELLED），单点 `transit` 条件更新。

发布单详情（`/dbChange/release/detail`）展示语句组执行结果与事件时间线（复用 `dm_db_change_event` 表，`release_id` 列标识）。

---

## 阶段 4 · OpenAPI（三接口 + AK/SK）

治理 OpenAPI 供外部系统（如钉钉审批、自动化平台）调用，鉴权用主账号 AK/SK：

- 库映射查询、台账查询、发布单操作三类接口（详见 [`openapi-dingtalk.md`](./openapi-dingtalk.md)）。
- 钉钉 / 外部审批引擎对接：环境参数 `SQL_TICKET_INFO` 配置审批类型（Internal/DingTalk/Feishu/Wechat/Custom）；非 Internal 走第三方审批流。配置指引见 [`openapi-dingtalk.md`](./openapi-dingtalk.md)。

---

## 死表与残留配置说明（P5 下线产物）

### 留表停写的三张旧表

旧治理链路（promotion/revision/stmt_version）代码已物理删除，但其三张表**留表停写**（用户决策 D1：保留对照能力、零迁移风险）：

| 表 | 处置 | 说明 |
|---|---|---|
| `dm_db_change_stmt_version` | 留表停写 | 代码（DO/Mapper/Service）已删，表结构与历史数据保留。仅可直连 DB 查阅。 |
| `dm_db_change_revision` | 留表停写 | 同上。P3 发布单改用 `dm_prod_release_stmt`（含 hash 列）。 |
| `dm_db_change_promotion` | 留表停写 | 同上。P3 发布单改用 `dm_prod_release`。 |
| `dm_db_change_event` | **保留复用** | P3 发布单事件留痕在用（`release_id` 列）。不可删。 |

三张死表无代码引用，不影响运行。**未来可单独出迁移脚本清理**（`DROP TABLE IF EXISTS ...`），非本次范围。

### `dm_sys_env_param` 中 GOV_ROLE 残留行

旧治理角色环境参数（`GOV_ROLE` / `GOV_DML_DIRECT` / `GOV_DML_ROW_LIMIT` / `GOV_AUTO_CONFIRM`）的代码侧常量与读写逻辑已删除，`dm_sys_env_param` 表中存量行**留行停写**（不再写入/读取，残留行无害）。如需清理：

```sql
DELETE FROM dm_sys_env_param WHERE config_key IN ('GOV_ROLE','GOV_DML_DIRECT','GOV_DML_ROW_LIMIT','GOV_AUTO_CONFIRM');
```

> 不建议在迁移脚本中执行此删除——`dm_sys_env_param` 是通用环境参数表，按 config_key 删行会侵入其他功能域。留行停写=行为零变化。

---

## 回滚预案

治理 v2 无数据库破坏性变更（留表停写、零 DDL），回滚纯代码层：

1. **单库回退**：在「库映射」页删除对应 `dm_db_pair` 行——该库立即回到普通工单流程，无治理约束。在途的 v2 工单继续按现有状态机走完（不会中途丢失）。
2. **全量回退**：回滚到 git tag `pre-p5-cleanup`（P5 清理前）或更早的 v2 上线 tag——代码与表结构均恢复，数据库零变更、零风险。

灰度过程中如需紧急停推进：`GovPipelineScheduler` 是单 duty 守护线程（`advancePreTickets`），停止后端即停止 SYSTEM 自动审批/执行；在途 PRE_DDL 工单停在 WAIT_APPROVAL，人工处置。
