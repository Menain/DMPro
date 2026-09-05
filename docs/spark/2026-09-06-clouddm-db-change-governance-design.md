# CloudDM 数据库变更治理平台二次开发 · 业务方案设计 Spec

> 日期：2026-09-06（rev.2：语句级版本化与失败修正闭环并入）
> 状态：已与需求方逐节确认定稿（待最终审阅）
> 输入来源：`.claude/READY_PLAN.md`（治理平台愿景与调研任务书）、`.claude/CLOUDDM_SECOND_DEVELOPMENT_CODE_DESIGN.md`（P0 代码级实施设计）、`AGENTS.md`（工程规则）、open-cdm 4.2.0 代码库三轮实勘（现有能力地图 / 工单执行链细节 / AutoExec 多语句执行语义）
> 产出方式：spark 头脑风暴流程，15 项关键决策逐一确认，全部设计断言以仓库真实代码为准

---

## 0. 决策记录（本次头脑风暴确认的全部决策）

| # | 决策 | 结论 |
|---|---|---|
| D1 | 方案范围锚点 | 以 CODE_DESIGN 的 P0 为锚；READY_PLAN 仅作后续阶段路线图，本期只划清边界不展开设计 |
| D2 | 变更类型 | DDL + DML 数据订正（VIEW/PROCEDURE/TRIGGER 等不入 P0） |
| D3 | DML 回滚 | 提交时**强制附回滚 SQL**，与正向 SQL 一起冻结进 Revision（同 hash 契约）；P0 不自动生成、不自动执行回滚；需要回滚时，回滚 SQL 作为一张新变更单走完整链路 |
| D4 | 工单架构 | **复用现有 `dm_approval` 工单系统 + 薄治理层**；不建平行工单体系 |
| D5 | 权限组落地 | **全局生效 + 物化展开**进 `dm_auth_res`（配展开账本表、"总是展开、各删各的"契约、组来源行撤销保护） |
| D6 | 生产查询治理 | P0 **不新开发代码**：靠权限组授权 PROD 资源 + 现有脱敏（`dm_sec_sensitive` + sidecar `doMask`）+ 现有 DM_QUERY 查询工单 + 现有 SQL 审计；方案只写配置与授权策略。强制脱敏/行数硬限制等归 P1 |
| D7 | PRE 审批 | **按环境可配，PRE 默认全自动**（规则审计通过即由系统代审代确认执行）；**PROD 强制审批，不可配置关闭**；PRE 的 DDL 与 DML 对称自动（如需 PRE DML 人审，环境配置即可实现，不改代码） |
| D8 | 部署形态 | **boot-alone 单机一体**；"禁止直连生产"靠补偿控制（见 §2.4）；幂等用 DB 唯一约束，不用分布式锁 |
| D9 | 治理层结构 | **方案 A：新增治理域表 + 与工单弱关联**——治理表持有工单 id（主关联），`dm_approval.ticket_info`（`ApprovalMO` JSON）扩展治理引用字段（反向引用，零表结构改动）；`features` 列是纯枚举（`ApprovalFeature.PRE_INIT`）不可用作扩展槽 |
| D10 | 多方言 | **MySQL 与 PostgreSQL 同时进入 P0**；治理层方言无关，方言差异收敛到 3 个触点（审计规则集按 `ruleDsRange` 路由 / Preflight 走现有 schema 元数据 SPI / DML Explain 的 PG 支持为实施期验证项）；语句与整单 hash 采用**方言中立契约**：`SHA256(原文 + 确定性空白规范化)`，字节级冻结、零改写，弃用 CODE_DESIGN 的 `normalized_sql` 重构方案 |
| D11 | 生产 DML 双入口 | 新增**路径 B：直发生产 DML**（定制化数据 PRE/PROD 不一致的正当场景），约束包全收：仅 DML（parser 行为判定）＋环境开关默认关＋专用权限标签＋回滚 SQL 强制＋EXPLAIN 影响行数阈值分级＋同一 Guard/Preflight/幂等/Timeline |
| D12 | 执行确认 | **按环境可配，默认人工**：审批通过后由持确认权限者在平台点"确认执行"（触发行前 Guard+Preflight），形成"钉钉审批＋平台执行确认"双控；可配"审批通过即自动确认"（自动路径同样必经 Guard+Preflight） |
| D13 | CI/CD 边界 | 治理链路**只走工单入口**；不结合、不修改、不依赖 CI/CD 变更流模块（`dm_change_flow`、`ChangeActionForApproval`、前端 `views/cicd/` 零改动）。CI/CD/GitOps 集成归 P2 |
| D14 | **语句级版本化与失败修正闭环** | Revision 升级为**逐句 manifest**（idx/hash/version/pre_exec）；新增 `dm_db_change_stmt_version` 语句版本历史表；**PRE 修正闭环**：语句执行失败（预检全过后）→ 失败即停、整单 EXEC_FAIL → 推送钉钉通知（复用 `DingTalkMsgSendSpi`/`ImSenderService`，含语句定位+错误+深链）→ 提交人在平台修正该语句 → 版本+1 → 增量审计 → 替换执行任务 → `retryJob` 断点续跑（已成功语句不重放，引擎原生）；**门禁升级逐句比对**（门禁一：manifest 逐条一致+每句 pre_exec=SUCCESS；门禁二：整单+逐句 hash 复验）；**PROD 不允许就地修正**（通知机制复用，入口指向处置页：瞬态→retryJob、改 SQL→回 PRE）；路径 B 失败需改 SQL → 重新提交新的路径 B 工单（新 revision、重新审批）。废弃前一版"血缘新工单+语句跳过账本"设计 |
| D15 | **执行配置策略（成分路由）** | **允许混合工单，不强制分单**；治理层按 parser 逐句判定成分路由执行配置：**纯 DML 单强制 `enableTransactional=true`**（整单一个事务，失败全回滚，PRE/PROD 同语义）；**含 DDL（纯 DDL 或混合）强制逐条 autocommit + `errorStrategy=NONE`**（失败即停），禁用事务模式（守卫引擎缺失的 MySQL DDL 隐式提交击穿事务问题）；治理工单**一律强制 NONE**（某句失败→定位该句→整单停止→退回提交人）；**治理 PROD 工单禁用 SKIP**（`errorStrategy` 不可选 SKIP，且 `skipTask`/`continueTask` 接口对治理 PROD 工单硬拦截——生产执行集必须=审批集）；配置由治理层注入（PRE 系统代审代确认时写入 `autoExecConfig`；PROD 人工确认界面锁定+门禁二校验 job config 合规），确认人不可改 |

---

## 1. 背景、现状与缺口

### 1.1 目标

在 CloudDM（open-cdm 4.2.0）现有 RBAC、资源授权、SQL 审计、工单审批、任务执行体系之上，以**最小侵入**增加企业级数据库变更治理能力：

- 权限组（组→资源授权，全局生效）
- 逻辑库资源 + PRE/PROD 环境绑定（服务端解析目标库，前端不可指定）
- PRE 变更自动验证 → 语句级版本化修正闭环 → 冻结不可变 Revision（整单+逐句 hash manifest）→ 生产推进发布（Promotion）
- 直发生产 DML 受控通道（路径 B）
- 生产强制审批（钉钉）+ 执行确认双控 + 行前 Guard/Preflight + 逐句 manifest 比对
- 全链路可审计 Timeline（工单级 + 语句级）

实施原则（继承两份输入文档并修正）：**最小侵入、最大复用、后端强约束、状态机驱动、生产不可绕过、变更不可篡改。**

### 1.2 现状能力盘点结论（三轮代码实勘，真实名称）

已存在、直接复用、**不允许另起炉灶**的能力：

| 能力 | 现有载体 |
|---|---|
| 工单系统 | `dm_approval`（`DmApprovalDO`：`rawSql`、`rollBackSql`、`envName`、`bindDsId`、`expectedAffectedRows`、`ticketInfo`、`features` 等），状态机 `ApprovalStatus`：`PRE_INIT_WAIT → PRE_INIT_RUN → WAIT_APPROVAL → WAIT_CONFIRM → WAIT_EXEC → RUNNING → FINISHED / EXEC_FAIL / EXEC_PAUSE / REJECTED / CLOSED / CANCELED / FAILED`；业务类型 `ApprovalBiz`：`DM_QUERY / DM_CHANGE / DATA_SOURCE_AUTH / CC_DATA_JOB_AUTH` |
| 工单创建 | `ApprovalControlServiceImpl.createSqlTicket(puid, uid, DmAddTicketFO)`（普通 Spring Service，可编程调用；权限注解只在 Controller 层）；`DmAddTicketFO` 已含 `dbLevels / rawSql / contentType / attachmentId / rollBackSql / ticketTitle / description / force`；服务端编程式创建先例：`ChangeActionForApproval.createApproval`（CI/CD 用，写 `ticketInfo=ApprovalMO{changeId, changeOwnerUid}`） |
| 工单前置分析 | `PreInitHandler` 接口 + `AbstractPreInitHandler`（`supports()` 覆盖 DM_QUERY/DM_CHANGE），纯 Spring `List<PreInitHandler>` 注入、按 `displayOrder()` 排序：`BehaviorPreInitHandler`(1) → `RuleCheckPreInitHandler`(2) → `DmlExplainPreInitHandler`(3)。**新增 handler = 新增一个 `@Service`，零接线改动** |
| 审批引擎 | `ApprovalFlowService(Impl)` / `ApprovalStateService` / `ApprovalTaskScheduler`（1s 守护循环）/ `ApprovalTaskProcessor`；`ChangeApprovalHandler`（handleType=DM_CHANGE，审批通过→WAIT_CONFIRM）；确认执行 `ApprovalControlServiceImpl.confirmTicket`（`DmConfirmTicketFO.autoExecConfig` 在此选定）→ `prepareExecJobAsync` → `createExecJob` → `AutoExecService.createJob/startJob`（失败回退路径 `restoreExecutionConfirmation` 现成） |
| **执行语义（AutoExec）** | console 侧按方言 parser 将工单 SQL **逐条拆分**：一条语句 = 一个 `dm_exec_auto_task`（`exec_order` 1..n，`query_id` UUID），sidecar（`AutoExecJob.jobRun`）**严格顺序逐条执行**；job 级配置存 `dm_exec_auto_job.config` JSON（`RsExecAutoJobConfigObj`）：`enableTransactional`（true=整单一个事务：commit 前 task 报 WAIT_CONFIRM、全部成功 commit 后转 FINISH、任何失败/暂停整体 rollback 且 task→ROLLBACK；false（默认）=逐条 autocommit 即 FINISH）、`errorStrategy`（`NONE` 默认失败即停整单 FAILED / `RETRY` 原地重试 / `SKIP` 跳过继续）、`retryCount/retryWaitTime`；`retryJob` **断点续跑**：只重打包 `WAIT_EXEC/FAILED/ROLLBACK` task，**FINISH/CANCELED 不重放**；`skipTask`/`continueTask`（job PAUSE/FAILED 态）；每语句状态/影响行数/执行次数在 `ticketDetail.vue` EXECUTION 步骤表可见；**无方言守卫：MySQL DDL 隐式提交会静默击穿事务模式**（代码无防护） |
| 审批 Provider | `ApprovalProviderSpi`（Internal/DingTalk/Wechat/Feishu/Custom）、`ApprovalCallbackSpi`、`ChangeForm`/`QueryForm`/`AuthForm`、模板 `dm_approval_template`、审批人 `dm_approval_person`；**按环境绑定审批模板**：env param `SQL_TICKET_INFO`/`CHANGE_TICKET_INFO`（`EnvTicketMO{approvalType, templateName, templateId}`），未配置时默认 Internal 自动模板 `PROC-SELFMAKE-000000001` |
| 钉钉集成 | `plus-provider-dingtalk`：`DingApprovalProviderSpi`、`DingApprovalStreamHandler`（Stream SDK 事件→`ApprovalRefreshService`）、HTTP 回调 `CallbackController`、`DingClient/DingApi`；**消息通知**：`DingTalkMsgSendSpi`（IM 工作通知，`ImSenderService` 已在 cicd 使用）——语句失败通知复用此能力 |
| SQL 解析/审计 | `cg-dslparser`（ANTLR）+ 方言插件 `sql-mysql`/`sql-postgres` 等（行为分析 `BehaviorAction` CREATE/ALTER/DROP…，逐句判定 DDL/DML 的依据）；规则引擎 `cg-detectrule`（`DetectRuleEngine`）+ `plus-sec-rules`；规则表 `dm_sec_rules`（含 `ruleDsRange` 数据源圈定）、规则集 `dm_sec_spec` 按环境绑定（env param `check_spec_id`） |
| 执行派发 | `AutoExecService` → `dm_exec_auto_job`/`dm_exec_auto_task`（`depend_on_biz_id` 唯一 = **一工单一 job**）→ `AutoExecScheduleService.scanPendingJob`（5s）→ `dispatchJob`（claim 后按 task 状态打包 zip）→ RSocket 派发 sidecar worker（`bindClusterId` 选在线 worker）→ 执行回报 `ExecJobRServiceProvider.reportMessage`（TASK_START/FINISH/FAILED/SKIP/WAIT_CONFIRM、JOB_FINISH/FAILED/PAUSE）；重试 `retryAutoExecJob`（EXEC_FAIL→WAIT_EXEC 重派发） |
| 功能权限 | `SecRoleAuthLabel`（`@AuthLabel` 标签）+ `@RequestAuth` 注解 + `RequestAuthServiceImpl` 启动扫描 URL→标签映射 + `DmAuthServiceForBiz.checkRoleAuth`；内置角色 `SecSysRole`：Manager/Developers/DBA/PM |
| 资源授权 | `dm_auth_res`（`DmAuthResDO`：`ownerUid/resId/resPath/levelOne..Four/kindType(AuthKind)/res_auth_label(JSON)/startTime/endTime/res_desc`），运行时 `resPath` LIKE 前缀查询 + 有效期判断，无专门权限缓存；写入路径 `DmAuthServiceForManageImpl.modifyUserAuth → mergeGrantedAuth → authDal.resMapper().insert/updateById`（同键行会合并 label）；**无任何来源/备注扩展列** |
| 环境模型 | `dm_sys_env`（仅 `envName/description`，**自由文本标签，无 DB 唯一约束**，无固定 PRE/PROD 枚举）；env param KV 机制（`dm_sys_env_param`，`EnvParamKeys`）；数据源 `dm_ds.dsEnvId`；工单只存 `env_name` 反规范化字符串 |
| 脱敏 | `plus-sec-rules` 敏感体系：`dm_sec_sensitive` + sidecar 结果集构建时 `ImplResultSetRowsBuild.doMask()` |
| 审计 | SQL 执行审计 `dm_exec_sql_audit`（sidecar 写）；操作审计 `dm_mon_op_audit`（`RdpOpAuditService`，含登录审计）；工单过程 `dm_approval_process`/`dm_approval_process_activity` |
| 平台建表机制 | `boot-initialization` Flyway Java 脚本（`V<yyyyMMddNNNN>__<desc> extends AbstractUpgradeJavaMigration`，classpath 自动注册；最新 `V202608060002`），平台自身元数据库的全部 `dm_*` 表皆出于此 |
| 前端 | 工单 `views/ticket/`（`ticket.vue` 创建：`DsSelect` 选数据源+catalog/schema，envId 由数据源推导；Monaco `TicketEditor`/`ReadOnlyEditor`；`ticketDetail.vue` 步骤条 CREATE/EXPLAIN/APPROVAL/CONFIRM/EXECUTION + 按语句的执行状态表）；授权 `views/system/subaccount/auth/authDm.vue`、`views/system/Permission.vue`；API 层 `services/http/api/*.js` + `request.js` |

### 1.3 真实缺口（本期新建的全部内容）

1. **权限组**：不存在任何组实体，授权严格按用户
2. **逻辑库资源 + 环境绑定**：环境是自由标签，无跨数据源的逻辑库对象，无 PRE/PROD 治理语义
3. **不可变 Revision + hash 冻结（整单+逐句 manifest）**：工单 `rawSql` 可变，无内容 hash 不可变性，无语句级版本概念
4. **语句失败修正闭环**：现有引擎只能原样 `retryJob` 或人工 skip，无"修正某条语句→版本升级→替换任务→断点续跑"能力，也无失败通知推送
5. **环境门禁式 Promotion**：CI/CD flow 级联（`dm_change_transfer`）偏 GitOps 路线且刚合入，无环境门禁语义；本期不依赖它（D13）
6. **生产 Guard/Preflight**：无任何以"生产"为键的执行前门禁
7. **工单流无自动执行路径**：即使 Internal 模板也必经 `WAIT_APPROVAL →（审批）→ WAIT_CONFIRM →（人工确认）→ 执行`——"PRE 默认全自动"（D7）必须新增系统代审机制（§4.5）
8. **执行配置的治理守卫**：`enableTransactional`/`errorStrategy` 由确认环节自由选择，且引擎对 MySQL DDL 击穿事务模式无防护（D15 成分路由补守卫）

### 1.4 对 CODE_DESIGN 原案的关键修正（代码实勘驱动）

| 原案 | 修正 | 依据 |
|---|---|---|
| 新建 `db_change_ticket` 表 | ❌ 删除，由 `dm_approval` 承担 | 现有工单已含 rawSql/rollBackSql/expectedAffectedRows/envName/bindDsId 与完整状态机 |
| 新建 `db_change_execution` 表 + execution_key | 并入 `dm_db_change_promotion`（幂等键上移） | 执行记录由 `dm_exec_auto_job/task` 承担，`depend_on_biz_id` 唯一已保证一工单一 job |
| 新建 `db_change_approval` 表 | ❌ 删除 | `ApprovalProviderSpi` + 模板/审批人表 + 钉钉 Stream/回调同步全部现成 |
| `console_job`/`console_task` handler | ❌ 概念不存在 | 真实机制是 `dm_exec_async_task` 与 `AutoExecService` 链路 |
| 用 `features` 字段携带治理引用 | 改用 `ticket_info`（`ApprovalMO` POJO 扩展字段） | `ApprovalFeature` 是纯枚举，塞自定义值破坏 Jackson 反序列化；`ticketInfo` 是现成自由 JSON 槽且有 CI/CD 先例 |
| `sql_hash = SHA256(normalized_sql)`（解析重构规范化） | 改为 `SHA256(原文 + 确定性空白规范化)` | 多方言下解析重构依赖各方言 grammar 完备性，风险高收益低（D10） |
| 工单→Revision 两级模型 | 升级为**工单→语句版本→Revision（逐句 manifest）**三级 | D14：语句级版本化使失败修正、断点续跑、生产逐句校验都有精确载体 |
| 权限组展开可加 `grant_source` 列 | 不加列：独立账本表 + `res_desc` 标记 | `dm_auth_res` 无扩展列且为核心表；`res_desc` 现成可用 |
| `resource_environment_binding.environment` 枚举列 | 演化为 `env_id` 引用 + env param `GOV_ROLE` 治理角色 | 兼容现有自由环境模型、免改 `dm_sys_env`、附带逐环境灰度能力 |
| 钉钉审批硬编码 | Provider 不硬编码：PROD 治理环境**必须配置审批模板**且**强制第三方 Provider**（禁 Internal 自审） | 复用按环境模板绑定机制，平台不与钉钉耦合（§5.5） |
| READY_PLAN 问题 1"角色授权后无实例权限是缺陷" | **判定为设计使然，非 bug** | Role（`dm_auth_role`）只承载功能标签；实例访问是独立的按用户资源授权（`dm_auth_res`），两者互不打通——恰好验证"Role=功能、Group=资源"分离模型 |

---

## 2. 总体架构

### 2.1 架构图与模块落位

```text
                     CloudDM（boot-alone 单 JVM）
┌────────────────────────────────────────────────────────────┐
│ cgdm-console                                               │
│  ┌─────────────── 治理层（本期新增）───────────────┐        │
│  │ ① 权限组   group / member / resource / 展开账本 │        │
│  │ ② 逻辑库   logical_db + PRE/PROD 绑定 + GOV_ROLE│        │
│  │ ③ 语句版本化 + Revision 冻结（manifest/hash）   │        │
│  │ ④ Promotion 门禁 + Guard + Preflight(逐句)      │        │
│  │ ⑤ 治理推进器（代审/冻结/终态同步/通知）         │        │
│  └──────┬────────────────────────────┬────────────┘        │
│    创建/关联 DM_CHANGE 工单       复用现有能力               │
│  ┌──────▼───────────┐   ┌───────────▼──────────────┐       │
│  │ 现有工单引擎      │   │ SecRules 规则审计          │       │
│  │ dm_approval      │   │ DML Explain 影响行数       │       │
│  │ 状态机+审批流     │   │ 钉钉 Approval/MsgSend SPI  │       │
│  └──────┬───────────┘   │ AutoExec → dm_exec_auto_job│       │
│         └───────────────┴───────────┬──────────────┘       │
│        内嵌 sidecar worker（RSocket）│ SQL执行/脱敏/SQL审计   │
└──────────────────────────────────────┼─────────────────────┘
                          ┌────────────┴───────────┐
                       PRE MySQL/PG           PROD MySQL/PG
                                     （账号最小权限 + 防火墙白名单）
```

代码归属（与现有模块边界一致）：治理层业务逻辑 → `cgdm-console`；DO/Mapper → `cgdm-dao`（MyBatis，XML 排版遵循 `DmApprovalProcessMapper.xml` 参考与 AGENTS.md Mapper 规则）；平台元数据库脚本 → `boot-initialization` Flyway Java；前端 → 现有 `views/` + `services/http/api/` 模式。**CI/CD 模块零改动（D13）。**

### 2.2 核心触点总账（6 处小改动，其余全部纯新增）

| # | 核心改动 | 幅度 |
|---|---|---|
| 1 | 工单创建服务参数化 `approBiz`（默认值保持现行为 `DM_QUERY`）+ `ApprovalMO` 增加治理引用字段（`promotionId`/`revisionId`） | 一个方法签名 + 一个 POJO 字段 |
| 2 | `ApprovalControlServiceImpl.prepareExecJobAsync` 开头调用治理门禁（工单无治理引用时零成本直通） | 一处调用点 |
| 3 | `AutoExecServiceImpl.dispatchJob` 在 `claimJobForPackaging` 后同样门禁调用（覆盖重试/重新调度路径） | 一处调用点 |
| 4 | 资源授权撤销路径保护带 `PERM_GROUP:` 标记的行（提示"该权限来自权限组，请在组内操作"） | 一处条件分支 |
| 5 | `AutoExecServiceImpl.skipTask/continueTask` 入口治理防护：治理 PROD 工单拒绝操作（D15；无治理引用零成本直通） | 两处条件分支 |
| 6 | 语句替换能力：治理专用的失败语句任务替换（`replaceTask` 式小扩展或直写 task 行，二选一取决于 §13-1 验证；新增方法不改现有行为） | 一个新方法或一条受控写路径 |

纯新增：治理表 ×10、治理服务/推进器/门禁、PreInit 治理 handler（Spring List 注入零接线）、Flyway 脚本 ×1、前端页面与 API 模块、语句失败通知（复用 MsgSend SPI）。**不改**：状态机、审批引擎、钉钉 Provider、执行器核心、parser、脱敏、CI/CD。

### 2.3 多方言原则（MySQL + PostgreSQL，D10）

治理层方言无关；方言差异只允许出现在 3 个触点：

1. **SQL 审计规则集**：规则引擎按 `dm_sec_rules.ruleDsRange` 数据源圈定路由；为 PG 配一套与 MySQL 对等的最低规则集（含 PG 特有项：`CREATE INDEX` 非 `CONCURRENTLY` 锁写、`ADD COLUMN NOT NULL DEFAULT` 版本行为差异、`TRUNCATE`/`DROP` 分级）；规则集按环境绑定（现有 `check_spec_id` 机制）。主要是配置+规则脚本工作
2. **Preflight 元数据检查**：不手写方言 SQL，走 CloudDM 现有 schema 元数据抽象（`ds-postgres`/`sql-postgres` 插件已实现元数据获取；确切 SPI 名见 §13 验证清单）
3. **DML Explain**：`DmlExplainPreInitHandler` 对 PG 的支持程度为实施期验证项；不支持则 PG 路径 B 降级为"规则审计+人工确认"，不阻塞主链

执行语义差异（PG DDL 事务性 vs MySQL 隐式提交）由 D15 成分路由统一守卫：**事务模式只对纯 DML 工单开放**（两方言物理性一致），含 DDL 一律逐条 autocommit——治理层借此补上引擎缺失的方言防护。

### 2.4 单机部署（D8）下的"禁止直连生产"补偿控制

boot-alone 无法靠网络拓扑隔离，改为三道防线：

1. PROD 数据库账号**最小权限**（仅业务所需 DML/DDL 权限，无 `DROP DATABASE`/`GRANT`/`CREATE USER`；SQL 规则与 DB 账号双重防护——即使审计规则有 bug，账号也无高危权限）
2. 防火墙白名单：PROD MySQL/PG 只允许 CloudDM 服务器 IP 访问
3. 平台内所有 PROD SQL 必经 Guard 链路；数据源密码仅存于数据源管理（管理员可见），普通用户全程接触不到生产 dsId（提交页只选逻辑库，见 §6.3）

幂等设计不依赖分布式锁：`execution_key` 唯一约束 + `dm_exec_auto_job.depend_on_biz_id` 唯一约束 + 状态判断，双层硬幂等。

---

## 3. 领域模型与数据表

### 3.1 领域全景与关联原则

```text
权限组域                    环境治理域                  变更治理域
─────────                  ─────────                  ─────────
dm_perm_group              dm_logical_db              dm_db_change_stmt_version
dm_perm_group_member       dm_logical_db_env_binding   dm_db_change_revision
dm_perm_group_resource                                dm_db_change_promotion
dm_perm_group_grant_record                            dm_db_change_event
      │展开(同事务)               │绑定解析                  │冻结/门禁/逐句比对
      ▼                          ▼                          ▼
dm_auth_res(现有,不加列)     dm_sys_env+dm_ds(现有,不改)   dm_approval+dm_exec_auto_task(现有,不改结构)
```

关联原则：治理表持有现有对象 id 做**单向关联**（如 `source_ticket_id → dm_approval.id`）；反向引用写入 `dm_approval.ticket_info`（`ApprovalMO` 扩展字段），现有表零结构改动。命名对齐仓库现行 DDL 风格（`dm_` 前缀、时间戳列名以现有 init SQL 约定为准）。

### 3.2 权限组域（4 张新表）

| 表 | 关键字段 | 约束/语义 |
|---|---|---|
| `dm_perm_group` | group_code, group_name, description, status, creator_uid | `UNIQUE(group_code)` |
| `dm_perm_group_member` | group_id, uid | `UNIQUE(group_id, uid)`；成员不带有效期（有效期在组资源行上，与现有授权习惯一致） |
| `dm_perm_group_resource` | group_id, auth_kind（沿用 `AuthKind`）, res_id（→dm_ds）, res_path（沿用 `DsResPath` 格式）, res_auth_label（沿用现有标签 JSON）, start_time, end_time | `UNIQUE(group_id, auth_kind, res_id, res_path)`；语义与 `dm_auth_res` 行同构，主体从用户换成组 |
| `dm_perm_group_grant_record` | group_id, group_resource_id, member_uid, auth_res_id（→dm_auth_res.id） | `UNIQUE(group_resource_id, member_uid)`；展开/回收的账本 |

**展开契约（承载安全语义）**：

- **"总是展开、各删各的"**：组资源 × 成员的每个组合直接 `resMapper().insert()` 写入一条 `dm_auth_res` 行（`ownerUid=成员`，有效期继承组资源行），同时在账本记 `auth_res_id`。**绕过 `mergeGrantedAuth` 的 label 合并逻辑**（防止回收时误删直接授权的 label）。用户直接授权行与展开行共存不合并——重复行对现有 LIKE 存在性鉴权无害；回收只删自己账本里的行，永不误删直接授权
- **来源标记**：展开行 `res_desc` 写 `PERM_GROUP:<groupId>:<groupResourceId>`；现有个人授权撤销路径对该标记行拒绝单独回收（核心触点 #4）
- **同事务重算**：加/移成员、授/撤组资源、组停用/删除、有效期调整 → 同事务增删对应展开行与账本行。离组即回收（物化机制达成 CODE_DESIGN 55 节的时效语义）
- 现有全部鉴权路径（控制台查询、实例浏览、工单）**零修改、全局生效**
- P0 不做组继承（User→Group、Group→Resource 两层封顶）、不做 ABAC

### 3.3 环境治理域（2 张新表 + 治理角色参数）

| 表 | 关键字段 | 约束/语义 |
|---|---|---|
| `dm_logical_db` | resource_code, resource_name, description, status, creator_uid | `UNIQUE(resource_code)`；如 `ORDER_DB=订单库` |
| `dm_logical_db_env_binding` | logical_db_id, env_id（→dm_sys_env.id）, ds_id（→dm_ds.id）, res_path（治理粒度定位到库/schema，沿用 `DsResPath`） | `UNIQUE(logical_db_id, env_id)`：**一个逻辑库每环境只绑一个目标**；分库分表/多实例批量明确排除在 P0 外。**引用 env_id 而非 env_name**（现状 env_name 无 DB 唯一约束，仅应用层保证） |

**环境治理角色**（不改 `dm_sys_env` 表，用现有 env param KV 机制新增 key）：

| env param key | 取值 | 语义 |
|---|---|---|
| `GOV_ROLE` | `PRE` / `PROD` / 空 | PRE：变更工单执行成功→触发 Revision 冻结；失败→修正闭环（§4.6）。PROD：强制审批（不可关）+ 只接受治理链路创建的工单 + Guard/Preflight 必经 + 禁 SKIP；空：完全保持现有普通工单行为，不受治理约束 |
| `GOV_DML_DIRECT` | on/off（默认 off） | 路径 B 直发生产 DML 入口开关（仅对 PROD 角色环境有意义） |
| `GOV_DML_ROW_LIMIT` | 阈值策略（如 `warn:1000,block:100000`） | 路径 B EXPLAIN 影响行数分级：≤warn 正常；warn~block 审批表单标注高风险；>block 拒绝 |
| `GOV_AUTO_CONFIRM` | on/off（默认 off） | 审批通过后自动确认执行（D12；自动路径同样必经 Guard+Preflight） |

治理角色标记同时提供**逐环境灰度能力**：治理只对标了角色的环境生效，可以一个库一个库接入，无需全量切换。

### 3.4 变更治理域（4 张新表）

**`dm_db_change_stmt_version`（语句版本历史——语句级链路载体，D14）**

| 字段 | 语义 |
|---|---|
| ticket_id, stmt_index, stmt_version | 工单 × 语句序号 × 版本号（从 1 起，修正一次 +1），`UNIQUE(ticket_id, stmt_index, stmt_version)` |
| stmt_text, stmt_hash | 该版本语句文本与方言中立 hash（`SHA256(原文+确定性空白规范化)`，与整单 sql_hash 同契约按句计算） |
| source | `INITIAL`（首次提交拆分）/ `CORRECTION`（失败修正） |
| fail_reason | 上一版本的执行失败错误摘要（CORRECTION 行携带，来自 task 失败回调） |
| operator_uid, gmt_create | 修正人与时间 |

当前语句清单 = 每个 `stmt_index` 取最大 `stmt_version` 行；工单首次提交（或修正后重拆）时由治理层写入/追加。**这张表就是"工单中每条 SQL 自提交后的执行状态与修正历史"的单一事实来源**，前端语句级 Timeline 直接渲染它 + task 状态。

**`dm_db_change_revision`（冻结版本——生产 SQL 的唯一合法载体）**

| 字段 | 语义 |
|---|---|
| revision_code | 全局唯一可读编号（如 `REV-20260906-0001`），`UNIQUE` |
| logical_db_id, env_id | 来源逻辑库 + 来源环境 |
| source_type | `PRE_TICKET`（路径 A）/ `DIRECT_PROD_DML`（路径 B） |
| source_ticket_id | 来源工单（→dm_approval.id），`UNIQUE`——**一张来源工单最多冻结一个 revision**：路径 A = 成功的 PRE 工单（1:1）；路径 B 指向其 PROD 工单本身（同样 1:1） |
| change_type | `DDL` / `DML` / `MIXED`（parser 行为分析逐句判定汇总，非前端声明；驱动 D15 成分路由） |
| sql_text, rollback_sql_text | 整单冻结副本（ATTACHMENT 工单在冻结时由服务端读附件文本物化，保证载体自包含）；**含 DML 成分（DML 或 MIXED）时 rollback_sql_text 必填**（D3），纯 DDL 可空 |
| sql_hash, rollback_sql_hash | 整单 `SHA256(原文+确定性空白规范化)`（总体防篡改双保险） |
| **stmt_manifest** | **逐句清单 JSON（D14 核心）**：`[{idx, stmt_hash, version, pre_exec, corrected_from:[{version,hash,fail_reason}…]}]`——冻结时刻每句的 hash、版本号与执行结果快照 |
| audit_snapshot | 规则审计 + DML Explain（影响行数）结果快照 JSON |

**不可变契约**：应用层只暴露 insert 与只读查询，无任何 update 路径；修订历史 = 语句版本表（工单内）+ 多 revision 行（跨工单），天然留痕。

**`dm_db_change_promotion`（生产发布对象 = Release Artifact + 门禁快照唯一载体）**

| 字段 | 语义 |
|---|---|
| promotion_code | `UNIQUE` 可读编号 |
| promotion_type | `PRE_PROMOTION` / `DIRECT_DML`（与 revision.source_type 对应，冗余便于查询统计与门禁路由） |
| revision_id | `UNIQUE(revision_id)`：**一个 revision 只允许一次有效 promotion**；PROD 失败→重走 PRE 产生新 revision，不允许原 revision 二次发布 |
| logical_db_id, prod_env_id, prod_ds_id, prod_res_path | **创建时刻的绑定快照**（服务端解析，前端不可传） |
| prod_approval_id | 由此 promotion 创建的 PROD 工单（→dm_approval.id） |
| execution_key | `SHA256(revision_id + prod_ds_id + logical_db_id)`，`UNIQUE` → 幂等硬约束（单机部署下替代分布式锁） |
| gate_result, preflight_result | 门禁/Preflight 逐项（含逐句比对结果）评估快照 JSON（含时间戳与证据，DENY 也记录） |
| status | promotion 自有薄状态机（§4.3） |

**双重校验契约**：执行时刻 Guard 重新解析当前绑定并与快照比对——promotion 创建后若有人改了 PROD 绑定（换库），比对不一致 → DENY。快照 + 复验两者都要。

**`dm_db_change_event`（治理时间线）**：`promotion_id / revision_id / ticket_id, event_type, from_status, to_status, operator_uid, event_data(JSON), gmt_create`。append-only（应用层只 insert）。与 `dm_mon_op_audit`（系统操作审计）、`dm_approval_process_activity`（工单过程）、`dm_db_change_stmt_version`（语句级）分工：本表记**治理层状态迁移与门禁结论**，四者共同拼出完整 Timeline（前端聚合展示，语句级视图见 §6.3）。

### 3.5 平台元数据库脚本

全部新表（10 张）+ 权限标签数据 + env param key 定义，通过**一个新的 Flyway Java 脚本**落地（`V<实施日>NNNN__db_change_governance.java`，`AbstractUpgradeJavaMigration` 模式，classpath 自动注册，版本号实施时按当日日期取号）。纯新增：不动任何历史脚本、不改任何现有表结构。此机制是 CloudDM 平台自身元数据库的建表/升级通道（仓库所有 `dm_*` 表的来源），**与 CI/CD、与业务库变更无关**（对应 AGENTS.md"数据库变更"节约定）。

---

## 4. 状态机与核心流程

### 4.1 双入口模型与安全底线改写（D11/D14 核心契约）

CODE_DESIGN 原绝对禁令"PROD 不允许直接重新输入 SQL / 无 PRE_SUCCESS 不能生产"改写为：

> **任何 PROD 执行的 SQL 必须：来自冻结 Revision（执行时刻整单+逐句 hash 复验）＋ 挂在 Promotion 上（门禁快照唯一载体）＋ 逐句 manifest 与 PRE 验证结果一致（每句 hash/version 匹配且 pre_exec=SUCCESS）＋ 审批通过 ＋ Guard/Preflight 通过 ＋ 幂等执行。路径差异只在"来源门禁集"：路径 A 的门槛是 PRE 逐句验证完成；路径 B 的门槛是【仅 DML ＋ 环境开关开启 ＋ 专用权限标签 ＋ EXPLAIN 阈值分级 ＋ 回滚 SQL 强制】。**

其余底线（不可篡改、必审批、不可绕过、可审计）两条路径完全一致。路径 B 不是绕过治理的口子，而是换了一套来源门禁的治理路径；**DDL 永远没有直发路径**；**PROD 永远没有就地修正路径（D14）**。

### 4.2 三条链路

```text
路径 A · PRE 段（含修正闭环, D14）
  提交(选逻辑库→服务端解析PRE绑定; 含DML成分必填回滚SQL)
    → 治理层逐句拆分: 写 stmt_version(INITIAL) + 成分判定(D15 路由执行配置)
    → 创建 DM_CHANGE 工单(approBiz参数化, ticketInfo带治理引用)
    → PRE_INIT[行为分析 → 规则审计(PRE规则集) → DML Explain]   ←全部现有 handler
    → WAIT_APPROVAL ──治理推进器──→ 系统代审+代确认(注入成分路由配置, 留痕 SYSTEM)
    → WAIT_EXEC → AutoExec job → sidecar 逐条顺序执行
        ├─ 全部成功 → FINISHED → 推进器冻结 Revision(整单hash + 逐句manifest)
        └─ 第 n 条失败 → 失败即停(NONE) → EXEC_FAIL
             → 钉钉通知提交人(语句定位+错误+深链修正入口)
             → 提交人修正该语句 → stmt_version +1(CORRECTION) → 增量审计(拒绝则不落版本)
             → 替换该语句执行任务(触点#6) → retryJob 断点续跑(FINISH 不重放)
             → …循环直至全部成功 → 冻结(manifest 含每句最终版本+修正史)

路径 A · PROD 段(Promotion)
  选可发布 Revision → promote() 门禁一(含逐句manifest核验)
    → INSERT promotion(绑定快照+execution_key) → 用冻结 SQL 全集创建 PROD DM_CHANGE 工单
    → PRE_INIT[治理门禁 handler(新,首位顺序,fast-fail) → 规则审计(PROD规则集) → Explain]
    → WAIT_APPROVAL(钉钉实例, 表单含: 单号/逻辑库/环境/风险级/影响行数/sql_hash/PRE结果/SQL摘要)
    → 审批通过 → WAIT_CONFIRM → 【执行确认: 默认人工, 可配自动(D12); 配置被治理锁定(D15)】
    → prepareExecJobAsync 处 Guard 硬门禁(逐句hash复验+绑定复验+Preflight+配置合规)
    → 执行 → FINISHED → promotion SUCCEEDED
    → 语句失败: 失败即停 → EXEC_FAIL → 通知(提交人+DBA, 入口=处置页, 无就地修正)
         ├─ 瞬态故障 → retryJob 断点续跑(必经 dispatchJob 门禁逐句复验)
         └─ 需改 SQL → 回 PRE 修正闭环 → 新 Revision → 重新审批推进(原 promotion 终态 FAILED)

路径 B · 直发生产 DML
  提交(选逻辑库→服务端解析PROD绑定; DML+回滚SQL必填)
    → 开关(GOV_DML_DIRECT)/专用标签/PROD资源权限校验
    → parser 行为判定"仅DML"(DDL/DCL拒绝) → 逐句拆分写 stmt_version(INITIAL)
    → 同事务: 创建 PROD 工单 + 冻结 Revision(DIRECT_PROD_DML) + promotion
    → PRE_INIT[治理门禁 handler → 规则审计(PROD规则集) → Explain对PROD库(只读,安全)
                → 治理 handler 读 expectedAffectedRows 做阈值分级(超 block 阈值→FAILED)]
    → 之后与路径 A PROD 段完全同链(审批→确认→Guard→Preflight→执行)
    → 语句失败: 通知复用; 瞬态→retryJob; 需改SQL→重新提交新的路径B工单
      (新 revision、新 promotion、重新审批——路径 B 无 PRE 可回)
```

治理模式工单统一 `approBiz=DM_CHANGE`、`contentType` 沿用现有 INLINE/ATTACHMENT 契约（冻结时物化为文本，见 §3.4）。

### 4.3 Promotion 状态机（治理层唯一自有状态机，保持薄）

```text
CREATED → APPROVING → APPROVED → CONFIRMED → EXECUTING → SUCCEEDED   (主干)
              │            │                      │
              ▼            ▼                      ▼
          REJECTED     (工单撤回=CANCELLED)      FAILED
```

- 工单状态（`ApprovalStatus`）由现有状态机**独占管理**，治理层不镜像、不改写
- promotion 状态由治理推进器扫描工单状态**单向同步**（FINISHED→SUCCEEDED、EXEC_FAIL→FAILED、REJECTED/CLOSED/CANCELED→对应终态）；门禁/Preflight 结论在 hook 点就地写入 `gate_result`/`preflight_result`
- `PREFLIGHT_FAIL` 不是独立状态：Guard/Preflight 失败 → 工单经现有 `restoreExecutionConfirmation` 回 `WAIT_CONFIRM` + promotion 记 preflight_result + 事件；重新确认即重跑 Preflight——审批仍有效（逐句 hash 未变则无需重审）
- 非法状态迁移一律拒绝（状态迁移集中在治理服务单点实现，业务代码不直写 status）

### 4.4 两道 Guard 检查清单（D14 升级为逐句粒度）

**门禁一：promote()/directDmlSubmit() 创建时**

1. Revision 已冻结且整单 hash + 逐句 manifest 完整（路径 B：提交内容即时冻结）
2. 路径 A：来源 PRE 工单状态 = FINISHED，**且逐句 manifest 核验通过**——待创建的 PROD 执行集每条语句的 `(idx, stmt_hash, version)` 与冻结 manifest 逐条一致、每句 `pre_exec = SUCCESS`；路径 B：行为判定 = 纯 DML ＋ `GOV_DML_DIRECT` 开启
3. 发起人持对应功能标签（`RDP_DB_CHANGE_PROD_PROMOTE` / `RDP_DB_CHANGE_PROD_DML_DIRECT`）**且**持 PROD 资源权限（权限组展开行或直授）
4. 逻辑库存在 PROD 绑定 → 解析为快照（**前端传入的任何 dsId/envId/sql 被忽略或直接抛参数异常**）
5. Revision 未被消费（`UNIQUE(revision_id)` 硬约束兜底）
6. 含 DML 成分的变更回滚 SQL 非空（冻结时已强制，此处复验）
7. PROD 环境已配置审批模板且为第三方 Provider（§5.5）

**门禁二：执行时刻（`prepareExecJobAsync` + `dispatchJob` 双挂点；工单无治理引用零成本直通）**

1. promotion 状态 = APPROVED/CONFIRMED（审批结论只信 DB，不信任何请求参数）
2. **hash 复验（整单+逐句）**：对工单当前 rawSql 重算整单 SHA256 与 `revision.sql_hash` 比对；对待执行 task 集逐句重算 hash 与 manifest 比对——任何一句不一致 → DENY 并定位到句（工单层被任何方式篡改、或语句替换集漂移，都能拦住）
3. 绑定复验：当前逻辑库 PROD 绑定与 promotion 快照一致（防审批后换库）
4. Preflight（走现有 schema 元数据 SPI，方言路由）：数据源连通 + 表存在；DDL：依赖列/索引状态符合语句预期（ADD COLUMN→列不存在、CREATE INDEX→索引不存在）；DML：目标表存在。**P0 为基础版**；长事务/元数据锁/复制延迟归 P1
5. 幂等：`execution_key` UNIQUE + `depend_on_biz_id` UNIQUE 双层
6. **执行配置合规（D15）**：job 的 `errorStrategy`/`enableTransactional` 与治理按 `change_type` 成分路由注入的值一致（防确认环节篡改配置）；治理 PROD 工单 `errorStrategy=SKIP` 一律拒绝

Preflight 失败策略（READY_PLAN 问题 4 的定论）：**直接 DENY，回 WAIT_CONFIRM，人工决策重做 PRE 或修绑定；P0 不提供 override**；DBA 紧急 override 连同紧急变更通道归 P1。

### 4.5 系统代审机制（治理推进器，PRE 全自动的实现）

治理推进器 = 治理层**自有**的轻量调度循环（仿现有 `ApprovalStarter` 模式：独立守护线程、扫描式、无状态、重启自愈），职责四件：

1. **自动推进**：PRE 治理环境 + 未配人工审批模板的工单，PRE_INIT 全部通过后，经**现有 service 漏斗**（`approvalFlowService.approvalTicket` → `confirmTicket`）以 SYSTEM 身份完成审批+确认，**确认时按 D15 成分路由注入 `autoExecConfig`**（纯 DML→事务模式；含 DDL→autocommit+NONE），事件表与工单过程表留痕（operator=SYSTEM）。**不绕过状态机私改状态**。⚠️ 实施期验证点：若漏斗内有"审批人必须是被指派人"的身份校验，为 SYSTEM 增加定向代审通道（小改动，见 §13）
2. **冻结 Revision**：扫描 FINISHED 的 PRE 治理工单，无对应 revision 则冻结（整单 hash + 逐句 manifest 从 stmt_version 当前版 + task 终态汇总；`UNIQUE(source_ticket_id)` 幂等；不侵入现有 `completeExecution` 漏斗）
3. **终态同步**：promotion 状态跟随工单终态；审批状态兜底同步复用现有钉钉 Stream/回调 + `ApprovalProviderSpi.getLastInfo` 机制，治理层不自建
4. **失败通知**：治理工单语句失败（EXEC_FAIL）→ 组装语句定位+错误信息+深链，经现有 `DingTalkMsgSendSpi`/`ImSenderService` 推送提交人（PROD 失败加推 DBA/确认人）；通知只是入口，修正/处置动作在平台内强鉴权完成

PROD 环境的 `GOV_AUTO_CONFIRM=on` 时，推进器同样承担"审批通过→自动确认"（必经门禁二，配置注入同 D15）。

### 4.6 失败 / 修正 / 重试 / 重启路径（D14/D15 定稿）

| 场景 | 行为 |
|---|---|
| **执行配置语义（成分路由）** | 纯 DML 工单：整单一个事务——任何语句失败 → **全部回滚**（task→ROLLBACK，库状态零变化）→ 修正后重提 = 全部重跑（ROLLBACK task 重放，语义正确）；含 DDL 工单（纯 DDL 或混合）：逐条 autocommit + 失败即停——失败点之前的语句**已应用**（MySQL DDL 物理不可回滚），按语句粒度可见 |
| **PRE 语句失败（修正闭环）** | 失败即停、整单 EXEC_FAIL → 钉钉通知提交人（语句定位+错误+深链）→ 提交人在平台修正该语句（`correctStatement`：仅本工单提交人 + EXEC_FAIL + PRE 治理环境）→ 语句版本+1、增量审计（审计不过则拒绝修正，版本不前进）→ 替换该语句执行任务 → `retryJob` 断点续跑（已成功语句不重放）→ 循环至全部成功 → 冻结（manifest 记录每句最终版本+修正史）。**同一工单内完成，不新建工单** |
| PRE 瞬态失败（锁等待/连接断） | 不修正、直接 `retryJob` 断点续跑（引擎原生：FINISH task 不重放） |
| **PROD 语句失败（无就地修正，D14）** | 失败即停 → 通知提交人+DBA（入口=处置页，非修正表单）：瞬态 → `retryJob`（必经 `dispatchJob` 门禁逐句复验）；需改 SQL → **回 PRE 修正闭环** → 新 Revision → 重新审批推进（原 promotion 记 FAILED 终态，完整事件留痕）；路径 B 失败需改 SQL → 重新提交新的路径 B 工单（新 revision/promotion/审批）。**绝不自动修改 SQL、绝不自动回滚** |
| 治理 PROD 的 skip/continue | `skipTask`/`continueTask` 对治理 PROD 工单**拒绝**（触点 #5；生产执行集必须=审批集）；PRE 工单保留 skip 灵活性（订正实验环境无妨，skip 结果计入 manifest 的 pre_exec 语义由修正闭环替代——PRE 冻结要求每句 SUCCESS） |
| 钉钉回调重复 | 现有幂等 + promotion 状态判断（APPROVED 后收到的重复回调直接忽略，不重复创建执行） |
| 钉钉回调丢失 | 现有 Stream 事件 + `getLastInfo` 定期同步兜底（复用，不自建） |
| 服务重启于 EXECUTING 中 | 推进器扫描式自愈；执行结果靠 sidecar 回报 + 现有轮询兜底（`ChangeApprovalHandler.updateExecutionStatus`）；job 状态三态可判（成功/执行中/失败），**未知状态绝不盲目重发** |
| 并发双提交同一 revision | `UNIQUE(revision_id)` + `execution_key` 数据库层硬拦截，后到者失败 |
| 两个 worker/两次派发 | `depend_on_biz_id` 唯一 + `dispatchJob` claim 机制（现有）+ 门禁二逐句复验，只执行一次 |

---

## 5. 权限模型与授权矩阵

### 5.1 三层 AND 结构（治理链路判定顺序）

```text
① 功能权限（Role 标签层）   —— 谁能做这类操作    现有: SecRoleAuthLabel + @RequestAuth
② 资源权限（资源授权层）     —— 谁能碰这个库      现有: dm_auth_res + 本期权限组展开(全局生效)
③ 治理门禁（变更治理层）     —— 这一次执行是否合法  新增: promote 门禁 + 执行时刻 Guard(逐句)
```

三层 AND，逐层收窄，任何一层失败即拒绝。①② 现有机制零改动（②的组扩展靠物化展开达成，鉴权路径不变）。

### 5.2 新增功能权限标签（进 `SecRoleAuthLabel`，禁止硬编码 Role ID）

| 标签 | 语义 | 默认建议授予 |
|---|---|---|
| `RDP_PERM_GROUP_MANAGE` | 权限组 CRUD/成员/资源管理 | DBA、Manager |
| `RDP_LOGICAL_DB_MANAGE` | 逻辑库 + 环境绑定 + 治理参数管理 | DBA |
| `RDP_DB_CHANGE_PROD_PROMOTE` | 发起生产推进发布（路径 A） | DBA、发布负责人 |
| `RDP_DB_CHANGE_PROD_DML_DIRECT` | 发起直发生产 DML（路径 B） | 定向授予（数据订正负责人） |
| `RDP_DB_CHANGE_GOVERN_READ` | 治理视图：发布列表/Timeline/门禁与 Preflight 证据 | DBA、Manager、审计员 |

**复用现有、不新增**：PRE 变更提交与修正（`correctStatement` 鉴权 = 工单提交人身份 + 工单状态，不另设标签）→ 现有工单提交标签（`RDP_WORKER_ORDER_REQUEST`）；生产执行确认 → 优先复用现有工单确认权限（实施期确认 `ApprovalController.confirm` 现有标签，无则补 `RDP_DB_CHANGE_PROD_CONFIRM`，见 §13）；资源授权管理 → 现有 `RDP_AUTH_MANAGE`。

### 5.3 授权矩阵（典型角色 × 能力）

| 能力 | 开发 | 高级开发/发布负责人 | 数据订正负责人 | DBA |
|---|---|---|---|---|
| 提交 PRE 变更（DDL/DML/混合） | ✅（需 PRE 组资源） | ✅ | ✅ | ✅ |
| 修正 PRE 失败语句（本工单提交人） | ✅ | ✅ | ✅ | ✅ |
| 发起 PROD 推进（路径 A） | ❌ | ✅（需 PROD 组资源） | ❌ | ✅ |
| 直发 PROD DML（路径 B） | ❌ | ❌ | ✅（需 PROD 组资源+开关开） | ✅ |
| 生产执行确认（双控第二步） | ❌ | ❌ | ❌ | ✅ |
| 审批 | —（钉钉模板侧决定，平台不圈人） | 同左 | 同左 | 同左 |
| 权限组/逻辑库管理 | ❌ | ❌ | ❌ | ✅ |

三权分离：**发起人 ≠ 确认人 ≠ 审批人**。审批人由钉钉审批模板决定；确认执行在平台 DBA 侧；发起拿平台标签 + 资源权限。管理员/super admin 也不能绕过 Guard（生产执行必经门禁二）；紧急发布通道（`EMERGENCY_PROD_RELEASE`）单独设计归 P1，不在普通执行接口留后门。

### 5.4 资源权限要点

- **PRE 与 PROD 是两个独立安全域**：权限组按"逻辑库+环境"建（如 `ORDER_DB_PRE`、`ORDER_DB_PROD`）；有 PRE 资源不隐含任何 PROD 资源（不同环境是不同 datasource/resPath，天然隔离）
- **逻辑库可见性跟随资源权限**：提交页逻辑库下拉（`myLogicalDbs`）按用户 PRE/PROD 资源权限过滤，无需单独查看标签
- **权限组管理是管理员语义**：持 `RDP_PERM_GROUP_MANAGE` 者可管理所有组（与现有资源授权管理的管理员语义一致，不做"只能授出自己已有权限"约束，避免过度设计）
- 生产执行主体：执行走 sidecar 数据源注册账号（= 服务账号语义），最小权限约束见 §2.4；不使用审批人或申请人的数据库账号

### 5.5 生产审批 Provider 策略

治理层**不硬编码钉钉**：PROD 治理角色环境**必须配置审批模板**（门禁一第 7 条：无模板 → 拒绝创建 promotion），且**强制第三方 Provider（禁 Internal）**——防止小团队用 Internal 模板自己审自己、弱化双控。具体 Provider（DingTalk/Feishu/Wechat…）由模板配置决定；本期落地配置为钉钉，链路即 CODE_DESIGN 的钉钉方案：现有 `DingApprovalProviderSpi` 创建实例 → `ChangeForm` 填充治理字段（变更单号/逻辑库/环境/申请人/风险等级/影响行数/sql_hash/PRE 执行结果/SQL 摘要前 N 字符；完整 SQL 留在平台）→ Stream/回调更新审批状态 → **回调只更新状态、绝不直接执行 SQL**（执行统一走工单确认 → Guard → AutoExec 链路）。

---

## 6. API 与前端

### 6.1 后端 API（全部新增 Controller，落位 `cgdm-console`，接口名驼峰，`@RequestAuth` + 新标签鉴权；路径前缀实施时对齐现有 Controller 约定）

**① PermissionGroupController（权限组）**

| 接口 | 说明 |
|---|---|
| `list` / `create` / `update` / `delete` / `detail` | 组 CRUD（`RDP_PERM_GROUP_MANAGE`） |
| `memberAdd` / `memberRemove` / `memberList` | 成员管理（批量；同事务触发展开/回收） |
| `resourceGrant` / `resourceRevoke` / `resourceList` | 组资源授权（资源树复用现有 `fetchAuthTreeDef`，不新做树接口） |

**② LogicalDbController（逻辑库与环境绑定）**

| 接口 | 说明 |
|---|---|
| `list` / `create` / `update` / `delete` / `detail` | 逻辑库 CRUD（`RDP_LOGICAL_DB_MANAGE`） |
| `bindingSet` / `bindingList` | 环境绑定维护（每环境唯一；含治理角色参数联动校验） |
| `myLogicalDbs` | 按当前用户资源权限过滤的逻辑库列表（提交页下拉专用，无需管理标签） |

**③ DbChangeGovernController（变更治理）**

| 接口 | 说明 |
|---|---|
| `preSubmit` | 路径 A PRE 段：入参仅 `logicalDbId + title + description + sql [+ rollbackSql(含 DML 成分时必填)] [+ contentType/attachmentId]`——**不接受 dsId/envId**；服务端解析 PRE 绑定 → 逐句拆分写 stmt_version → 成分判定（D15 路由）→ 调现有创建服务（`approBiz=DM_CHANGE`） |
| `correctStatement` | **PRE 修正闭环（D14）**：入参 `ticketId + stmtIndex + newSql + reason`；鉴权=仅本工单提交人 + 工单 EXEC_FAIL + PRE 治理环境；流程=语句版本+1 → 增量审计（规则+行为分析，不过则拒绝、版本不前进）→ 替换该语句执行任务（触点 #6）→ 触发 `retryJob` 断点续跑 |
| `stmtTimeline` | 语句级视图：每条语句的版本历史 + 执行状态 + 失败原因 + 修正记录（`stmt_version` × task 状态 × 事件聚合） |
| `availableRevisions` | 可发布 Revision 列表：冻结 + 来源工单 FINISHED + manifest 每句 pre_exec=SUCCESS + 未被 promotion 消费 + 当前用户有 PROD 资源权限 + 逻辑库有 PROD 绑定 |
| `promote` | 路径 A PROD 段：入参仅 `revisionId + description`；**请求体出现 `sql`/`dsId` 字段直接抛参数异常**；门禁一（含逐句 manifest 核验）→ 建 promotion → 建 PROD 工单（执行集=manifest 全集） |
| `directDmlSubmit` | 路径 B：入参 `logicalDbId + sql + rollbackSql(必填) + description`；开关/标签/资源权限/仅 DML 判定 → 同事务建工单+冻结 Revision+promotion |
| `promotionList` / `promotionDetail` | 发布对象列表/详情（含 gate_result、preflight_result 逐句证据） |
| `timeline` | 聚合 Timeline：`dm_db_change_event` + 工单 `dm_approval_process_activity` + revision 冻结记录 + 语句版本史入口，按时间归并 |

### 6.2 明确复用、不新增的接口

执行确认 → 现有 `confirmTicket`（Guard 在其执行路径内挂钩，接口面零变化；治理工单的 `autoExecConfig` 由治理注入/锁定，见 §4.5/D15）；工单取消/关闭/重试 → 现有 `cancel`/`close`/`retryAutoExecJob`（重试自动经过 `dispatchJob` 门禁逐句复验）；审批操作 → 现有钉钉回调/Stream + `approvalTicket` 链路；失败通知 → 现有 `DingTalkMsgSendSpi`/`ImSenderService`；环境治理参数（`GOV_ROLE`/`GOV_DML_DIRECT`/`GOV_DML_ROW_LIMIT`/`GOV_AUTO_CONFIRM`）→ 现有 envParam CRUD 加新 key；SQL 审计查询 → 现有 `sqlAudit/queryAll`。

### 6.3 前端（Vue 3 + 现有组件体系；用户可见文案全部进 `frontend/src/locales/`）

| 页面/组件 | 新增/改造 | 要点 |
|---|---|---|
| `views/system/permGroup/`（列表 + 详情 Tab：基本信息/成员/资源权限） | 新增 | 资源授权树复用 `authDm.vue` 树交互模式；有效期字段与现有授权一致 |
| `views/system/logicalDb/`（列表 + 绑定编辑） | 新增 | 绑定编辑 = 环境 × 数据源 × resPath；显示各环境治理角色与直发开关状态 |
| `views/ticket/ticket.vue` 创建表单 | 改造 | 增加**治理模式**：选 `myLogicalDbs` 逻辑库（替代 `DsSelect` 自由选实例——用户全程接触不到生产 dsId）；SQL 经行为判定含 DML 成分时动态展示回滚 SQL 编辑器（复用 `TicketEditor`）；提交前展示逐句拆分歧览（成分标记 DDL/DML + 将采用的执行配置）。非治理环境保持现状提交路径不变 |
| `ticketDetail.vue` 语句级视图 | 改造 | 现有 EXECUTION 按语句状态表叠加：每句的版本号/修正历史（`stmtTimeline`）、EXEC_FAIL 时失败语句高亮 + 「修正」入口（仅提交人可见，深链自钉钉通知落地于此，Monaco 编辑器 + 修正原因必填） |
| 生产发布页 `views/dbChange/promotion.vue` | 新增 | 上半部：`availableRevisions` 选择 + **只读** SQL/逐句 manifest/审计快照/PRE 执行结果（复用 `ReadOnlyEditor.vue`）+「提交生产发布」；下半部：路径 B「生产数据订正」表单（仅环境开关开启且持标签时显示），DML + 回滚 SQL 双编辑器 |
| 发布详情 / Timeline | 新增+改造 | promotion 详情：门禁清单、**逐句 manifest 比对结果**、Preflight 结果逐项渲染（JSON 证据 → 检查项列表）；工单详情步骤条扩展聚合治理事件区；PROD 失败处置页（retryJob 入口 + 回 PRE 指引，**无修正表单**） |
| `services/http/api/`：`permGroup.js`、`logicalDb.js`、`dbChange.js` | 新增 | 沿用现有 `request.js` 封装模式 |

**PROD 无 SQL 编辑器的契约保证**：不只是页面不放编辑器——路径 A 的 `promote` 后端拒收 SQL 字段；路径 B 的 SQL 只在提交时刻存在，冻结后所有展示接口返回 revision 只读副本；`correctStatement` 后端硬校验 PRE 治理环境（PROD 工单调用直接拒绝）。

**布局纪律**：新页面检查移动端与常见桌面宽度（表格列溢出、双编辑器并排窄屏改堆叠）。前端构建纪律：任何前端源码修改后 `cd package && ./all_build.sh web` 必须成功（退出码 0）。

### 6.4 前后端契约红线（全部后端裁决）

- 前端传的 `approvalStatus` / `preResult` / `sqlHash` / `dsId` / `autoExecConfig` 一律不信任：审批结论只读 DB、PRE 结论只看工单终态+manifest、hash 执行时刻整单+逐句重算、数据源只从绑定解析、执行配置只认治理注入值
- 影响行数（`expectedAffectedRows`）由 `DmlExplainPreInitHandler` 服务端计算，前端只展示（标注"预估"）
- 路径 B 开关与阈值从环境参数服务端读取，前端仅按接口返回渲染入口可见性
- 语句拆分/成分判定/hash 计算全部服务端完成，前端拆分歧览仅渲染服务端结果
- 后端已删除/不存在的字段，前端不留 fallback

---

## 7. 审计与测试

### 7.1 五层审计分工（三层现有 + 两层新增，语句级历史独立成表）

| 层 | 载体 | 记什么 |
|---|---|---|
| 治理时间线 | `dm_db_change_event`（新增，append-only） | 治理状态迁移 + 每次门禁/Preflight 逐项结论（**DENY 也记录含原因**，含逐句比对失败定位）+ REVISION_FREEZE + CONFIRM_EXEC（谁确认）+ RETRY + 系统代审事件 + 通知推送 |
| 语句级链路 | `dm_db_change_stmt_version`（新增） | 每条语句每个版本的文本/hash/来源/失败原因/修正人/时间——"每条 SQL 自提交后的执行状态"的单一事实来源 |
| 系统操作审计 | `dm_mon_op_audit`（现有 `RdpOpAuditService`） | 权限组管理、逻辑库/绑定管理、promote/directDml 发起等管理动作 |
| 工单过程 | `dm_approval_process_activity`（现有） | 工单阶段流转、审批动作、SYSTEM 代审留痕 |
| SQL 执行审计 | `dm_exec_sql_audit`（现有，sidecar 自动写） | 实际执行 SQL、影响行数、状态 |

治理事件类型清单：`CREATE_TICKET(GOVERN) / AUDIT_PASS / AUDIT_FAIL / PRE_EXECUTION_START / STMT_EXECUTION_FAIL / STMT_FAIL_NOTIFIED / STMT_CORRECTED / STMT_TASK_REPLACED / PRE_EXECUTION_SUCCESS / REVISION_FREEZE / PROMOTION_CREATE / GATE_PASS / GATE_DENY / MANIFEST_MISMATCH_DENY / APPROVAL_CREATE / APPROVAL_APPROVED / APPROVAL_REJECTED / CONFIRM_EXEC / PREFLIGHT_PASS / PREFLIGHT_FAIL / PROD_EXECUTION_START / PROD_EXECUTION_SUCCESS / PROD_EXECUTION_FAIL / RETRY`。

安全日志关键字段（进 `event_data` JSON）：ticketNo / revisionId / stmtIndex+stmtHash（语句级事件）/ sqlHash / operatorUid / 审批实例 id / dsId / executionId / clientIp / 起止时间。SQL 含敏感值（密码/Token/密钥）时展示层走现有脱敏机制、执行层用原文；数据库访问日志严格保护。

### 7.2 测试策略

> 两份输入文档明确要求测试覆盖（CODE_DESIGN §129/130/131/144-148），本项目**豁免** AGENTS.md"不主动新增测试类"默认规则。

- **Guard DENY 矩阵**（12 case 全覆盖）：① PRE 未成功 ② 无审批 ③ 审批被拒 ④ 整单 hash 被篡改 ⑤ **逐句 manifest 不一致（hash/version/pre_exec 任一）** ⑥ PROD 绑定缺失 ⑦ 绑定被换（快照不一致） ⑧ 无 PROD 资源权限 ⑨ Preflight 失败 ⑩ DDL 走直发入口 ⑪ 环境开关关闭/行数超 block 阈值 ⑫ **执行配置被篡改（不符合成分路由注入值）** → 全部 DENY 且留痕
- **修正闭环**：语句失败→通知发出→非提交人调用 `correctStatement` 被拒→提交人修正→增量审计拒绝非法修正→合法修正版本+1→任务替换→断点续跑（已成功语句不重放）→冻结 manifest 含修正史；事务模式（纯 DML）失败→全回滚→修正后全部重跑；混合工单含 DDL 失败→前序已应用→修正续跑→manifest 正确
- **幂等与并发**：双钉钉回调、任务重试重入（必经 `dispatchJob` 门禁）、EXECUTING 中重启三态判定、同 revision 并发 promote（唯一约束兜底）、双 worker 派发只执行一次
- **安全测试**：无标签直调 PROD 接口 → 403；请求体夹带 `sql`/`dsId`/`approvalStatus` → 拒绝或忽略且不影响裁决；伪造 `{"approvalStatus":"APPROVED"}` 不能改动 DB 审批状态；治理 PROD 工单 `skipTask`/`continueTask` 被拒；`correctStatement` 对 PROD 工单被拒；通知深链无凭证（仅入口，鉴权在平台）
- **权限组一致性**：入组即得/离组即失（控制台查询 + 工单双路径验证全局生效）、直接授权与展开行共存时"各删各的"、个人授权 UI 回收组来源行被保护拦截、组资源有效期到期展开行同步失效
- **双方言回归**：同一治理链路用例在 MySQL 与 PG 各跑一遍（规则集路由、Preflight 元数据、DML Explain、成分路由的事务语义）
- **单元测试最小覆盖**：权限组服务与展开/回收、治理推进器、门禁一/门禁二（含逐句比对）、语句拆分与 hash、修正闭环状态流转、成分路由配置注入、阈值分级
- **集成测试链路**：PRE 提交→审计→自动执行→（失败→修正→续跑）→冻结；promote→钉钉→确认→Guard→执行；路径 B 全链；EXEC_FAIL 重试链
- **前端**：`npm run lint`、`npm run check-i18n`、`./all_build.sh web` 全过；核心用户流程（提交 PRE→修正闭环→发布→确认→Timeline）浏览器级检查，并按 AGENTS.md 在 `tests/frontend/` 维护可复用流程文档（一个流程一份长期文档，不按日期生成报告）

---

## 8. 实施分期与灰度

### 8.1 Phase 表（每阶段编译 + 相关模块测试通过才进下一阶段；Conventional Commits 分提交）

| Phase | 内容 | 关键验收 |
|---|---|---|
| 1 | **平台元数据库脚本**：按 `boot-initialization` 现行 Flyway Java 模式新增一个升级脚本，创建治理层 10 张新表 + 注册新权限标签 + 新增 env param key 定义（纯平台基建，不碰业务库、不碰 CI/CD） | 脚本可执行、唯一约束生效、不动历史脚本、不改现有表 |
| 2 | 权限组（CRUD/成员/资源/展开账本/`res_desc` 标记/撤销保护） | 入组即得、离组即失、各删各的、保护拦截生效 |
| 3 | 逻辑库 + 环境绑定 + 治理角色参数 | `getBinding` 服务端解析不可被前端覆盖；env_id 引用正确 |
| 4 | PRE 治理链路（`preSubmit` + 逐句拆分/成分路由 + `approBiz` 参数化 + 推进器系统代审 + Revision 冻结含 manifest） | DDL/DML/混合提单→自动→执行成功→冻结；含 DML 缺回滚 SQL 被拒；SYSTEM 留痕；事务/autocommit 配置按成分正确注入 |
| 5 | **修正闭环**（失败通知 + `correctStatement` + 增量审计 + 任务替换 + 断点续跑 + `stmtTimeline`） | 失败→通知→修正→续跑全链；非提交人被拒；已成功语句不重放；版本史完整 |
| 6 | Promotion + 门禁一（`availableRevisions` / `promote`，含逐句 manifest 核验） | 只能选到合格 revision；门禁逐条 DENY 可验证；夹带参数被拒 |
| 7 | 执行链 Guard（门禁二双挂点 + 逐句 hash 复验 + Preflight + 配置合规校验 + skip/continue 防护 + `GOV_AUTO_CONFIRM`） | hash/绑定/配置复验；PREFLIGHT_FAIL 回 WAIT_CONFIRM；重试路径必经门禁；PROD skip 被拒 |
| 8 | 路径 B 直发 DML（开关/行为判定/阈值分级/专用标签） | 仅 DML、默认关、超阈值按策略处置、同事务三对象一致 |
| 9 | 钉钉表单治理字段 + 审批同步兜底验证 | 表单含单号/风险级/影响行数/hash/PRE 结果；回调丢失可同步；重复回调幂等 |
| 10 | 前端（权限组页→逻辑库页→工单治理模式+语句级视图→发布页/Timeline/处置页） | lint / check-i18n / `all_build.sh web` 全过 + 浏览器流程文档 |
| 11 | 集成 + 安全测试 + 双方言回归 | DENY 矩阵 12 case 全覆盖；完成定义（§8.2）达成 |
| 12 | 灰度上线 | 见 §8.3 |

### 8.2 完成定义（P0 Done 的硬标准）

以下两条链路在真实环境全部跑通，且 DENY 矩阵任一条件不满足时确认被拦截：

```text
链路一(路径 A)：用户 → 加入权限组 → 获得 PRE 资源 → 提交 DDL/DML/混合(含DML附回滚SQL)
  → 规则审计 → PRE 自动执行 → (语句失败→通知→修正→断点续跑) → 全部成功
  → 冻结 Revision(整单hash+逐句manifest) → 创建 Promotion(逐句核验)
  → 钉钉审批通过 → 平台确认执行(配置锁定) → Guard(逐句hash复验)+Preflight
  → 生产执行 → PROD_SUCCESS → 完整 Timeline(工单级+语句级)

链路二(路径 B)：数据订正负责人 → 开关开启环境 → 直发 DML+回滚SQL → 仅DML判定
  → EXPLAIN 阈值分级 → 冻结 → 钉钉审批(表单含影响行数) → 确认 → Guard+Preflight
  → 生产执行 → 完整 Timeline
```

安全底线最终形态：无冻结 Revision → 不能生产；整单或**任一语句** hash/version 不一致 → 不能生产；任一语句 PRE 未成功 → 不能生产；无 PROD 资源权限 → 不能生产；无审批通过 → 不能生产；Preflight 不通过 → 不能生产；执行配置不合成分路由 → 不能生产；路径 B 非纯 DML 或开关关闭 → 不能生产。**不允许通过 UI、Controller、Task、脚本任何方式绕过。**

### 8.3 灰度路径

治理角色标记天然支持逐库灰度：① 选一个非核心逻辑库，标 PRE/PROD 治理角色，只跑路径 A；② 稳定后为该 PROD 环境开 `GOV_DML_DIRECT`，试跑路径 B；③ 逐步扩大逻辑库范围与环境标记；未标记环境全程不受影响（现有行为零变化）。

---

## 9. P0 / P1 / P2 边界定稿

**P0（本 spec 全部范围）**：权限组、逻辑库+环境绑定+治理角色、PRE 自动验证链、**语句级版本化与修正闭环（通知驱动）**、Revision 冻结（整单+逐句 manifest）、Promotion 门禁（逐句比对）、路径 B 直发 DML、钉钉审批集成、执行确认双控、成分路由执行配置、Guard/Preflight 基础版、幂等、Timeline（工单级+语句级）、五层审计、双方言（MySQL+PG）。

**P0 明确不做**：自动回滚（DDL/DML）、回滚 SQL 自动生成、多库/分库分表批量发布、组继承、ABAC（部门/岗位/IP/设备策略）、策略 DSL、灰度发布、跨 Region、**与 CI/CD 变更流的任何集成（D13）**、紧急变更通道、gh-ost/Liquibase、**PROD 就地语句修正（D14，通知复用但入口指向处置页）**、**工单自动拆分双单编排**（治理层可识别成分并路由配置，但不拆成两张工单；如确需为 P1 可选）、**工单内分段事务**（DDL 段 autocommit + DML 段事务的引擎级段事务控制，改动大收益低）。

**P1**：DBA 紧急 override + 紧急变更通道（`EMERGENCY_PROD_RELEASE`，全审计不留后门；含 PROD 紧急修正场景的专项审批方案）、高级 Preflight（长事务/元数据锁/复制延迟）、风险评分与审批模板按风险路由、定时执行/变更窗口、回滚 SQL 自动生成（备份快照/binlog）、PROD 查询硬策略（强制脱敏/行数上限/临时授权增强）、PRE DML 人审模板细化、工单自动拆分双单编排（可选）、全局（非血缘）重复语句提示。

**P2**：gh-ost（MySQL 大表在线 DDL）与 PG 对应物（`pg_repack`/`CREATE INDEX CONCURRENTLY` 策略）、Liquibase/GitOps migration 仓库（统一多语言 Java/Python/NodeJS/Rust 的 Database Migration Repository）、分库分表批量发布、灰度/跨 Region 发布、Expand→Deploy→Contract 应用发布协调、Schema Drift 平台化治理、CI/CD 变更流集成评估。

---

## 10. ADR 清单（重大架构决策记录，实施期落 `docs/adr/`）

| ADR | 主题 | 对应章节 |
|---|---|---|
| ADR-001 | Role=功能权限 / Group=资源权限分离；权限组物化展开 +"总是展开、各删各的"+ 账本表 + `res_desc` 标记 + 撤销保护 | §3.2 / §5 |
| ADR-002 | 复用 `dm_approval` + 薄治理层弱关联（治理表持工单 id；`ticket_info`/`ApprovalMO` 承载反向引用；核心触点总账 6 处） | §2.2 / §3.1 |
| ADR-003 | 双来源门禁模型：安全底线改写为"任何 PROD SQL 必经冻结 Revision + 逐句 manifest 一致 + 路径全套门禁"；路径 B 仅 DML 约束包 | §4.1 / §4.4 |
| ADR-004 | hash 方言中立契约：SHA256(原文+确定性空白规范化)，整单+逐句双层，字节级冻结零改写，弃用解析重构规范化 | D10 / §3.4 |
| ADR-005 | 环境治理角色走 env param（`GOV_ROLE`），免改 `dm_sys_env`，附带逐环境灰度能力 | §3.3 / §8.3 |
| ADR-006 | 生产执行确认双控：默认人工确认，`GOV_AUTO_CONFIRM` 可配自动，两者均必经 Guard+Preflight | §4.2 / D12 |
| ADR-007 | PROD 审批 Provider 策略：不硬编码钉钉；PROD 必须配置审批模板且强制第三方 Provider（禁 Internal 自审） | §5.5 |
| ADR-008 | Guard 双挂点：`prepareExecJobAsync`（首次执行）+ `dispatchJob`（重试/重调度），治理推进器扫描式自愈，不侵入核心状态漏斗 | §4.4 / §4.5 |
| ADR-009 | **语句级版本化与修正闭环**：工单→语句版本→Revision(manifest) 三级模型；PRE 通知驱动就地修正（版本+1+增量审计+任务替换+断点续跑）；PROD 不允许就地修正（通知复用、处置页引导 retryJob 或回 PRE）；废弃血缘新工单/跳过账本设计 | §3.4 / §4.2 / §4.6 |
| ADR-010 | **执行配置成分路由**：允许混合工单不强制分单；纯 DML 强制整单事务、含 DDL 强制 autocommit+失败即停（守卫 MySQL DDL 隐式提交击穿事务）；治理工单强制 NONE；治理 PROD 禁 SKIP（执行集=审批集）；配置治理注入、确认环节锁定 | D15 / §4.6 |

---

## 11. 风险清单（含缓解）

| # | 风险 | 缓解/处置 |
|---|---|---|
| 1 | 系统代审漏斗内可能存在审批人身份校验，SYSTEM 代审被拒 | 实施期验证点（§13-2）；需要时加 SYSTEM 定向代审通道（小改动） |
| 2 | `DmlExplainPreInitHandler` 对 PG 的支持程度未证实 | 实施期验证项（§13-3）；不支持则 PG 路径 B 降级"规则审计+人工确认"，不阻塞主链 |
| 3 | EXPLAIN 行数是估算值 | 阈值分级只作风险信号，不作正确性依据；审批表单标注"预估" |
| 4 | `env_name` 无 DB 唯一约束（现状缺陷，应用层仅按 owner 查重） | 治理绑定一律用 `env_id`，免疫此缺陷 |
| 5 | 审批后-执行前窗口内绑定/表结构被改 | Guard 绑定复验 + 整单/逐句 hash 复验 + Preflight 三重收窄窗口 |
| 6 | 展开行与人工授权行同键并存 | 对 LIKE 存在性鉴权无害（已核实机制）；撤销保护防账本打穿；展开绕过 merge 防 label 污染 |
| 7 | boot-alone 单点故障 | P0 可接受（内部平台）；治理层全在 console，天然兼容后续拆分 console+sidecar 部署演进 |
| 8 | 上游 CloudDM 官方演进冲突（4.2.0 刚改过工单表） | 核心改动收敛为 6 处小 hook + 治理表全独立 + 弱关联不依赖新列，升级合并成本受控 |
| 9 | 权限组展开数据量 = 成员数 × 资源数 | 企业内部规模可接受；量级担忧时再评估运行时 union 方案（P2+，ADR-001 记录取舍） |
| 10 | 治理推进器与现有 `ApprovalTaskScheduler` 双循环竞争同一工单 | 推进器只经现有 service 漏斗操作（不直写状态），漏斗内部状态检查天然串行化；实施期以并发用例验证 |
| 11 | **MySQL DDL 隐式提交静默击穿 `enableTransactional`（实勘确认引擎无守卫）** | D15 成分路由守卫：事务模式只对纯 DML 工单开放，含 DDL 强制 autocommit；门禁二校验配置合规防绕过 |
| 12 | **语句替换的引擎能力不确定**（task 文本存储位置 / `dispatchJob` 打包读取源 / 一工单一 job 约束下无法重建 job） | §13-1 首位验证；两条实现路径备选（`replaceTask` 治理专用扩展 / 受控直写 task 行）；门禁二逐句复验兜底防替换集漂移 |
| 13 | 通知深链的权限边界 | 通知只是入口不携带任何凭证；`correctStatement` 平台内强鉴权（仅本工单提交人 + EXEC_FAIL + PRE 治理环境），PROD 工单调用直接拒绝 |
| 14 | 修正闭环中"增量审计通过但语义已偏离原审批意图"（PRE） | PRE 属低风险提示环境、修正仅提交人可为且全程版本留痕；PROD 侧由逐句 manifest 比对硬拦截任何未 PRE 验证的语句版本 |

---

## 12. READY_PLAN 十问 · 最终回答归档

| # | 问题 | 定论 | 章节 |
|---|---|---|---|
| 1 | CloudDM 当前权限模型？角色授权后为何无实例权限？ | Role=功能标签、资源授权=按用户 `dm_auth_res`（LIKE+有效期），互不打通——**设计使然非 bug**；本期补权限组物化展开（全局生效） | §1.4 / §3.2 / §5 |
| 2 | PRE 工单与 PROD 发布对象的关系？ | PRE 工单 1:1 Revision（冻结，含逐句 manifest）1:1 Promotion（发布对象）1:1 PROD 工单；语句级版本史在 `stmt_version` 表 | §3.4 |
| 3 | 什么条件下 PRE 可以进 PROD？ | Promotion Gate 门禁一（7 条，含逐句 manifest 核验）+ 执行时刻门禁二（6 条，含整单+逐句 hash 复验与配置合规），任一不满足即 DENY 且留痕定位到句 | §4.4 |
| 4 | PRE 与 PROD Schema 不一致怎么办？ | Preflight 基础版（存在性+一致性）失败即 DENY 回确认态，人工决策重做 PRE 或修绑定；P0 无 override，DBA 紧急 override 归 P1 | §4.4 |
| 5 | SQL 被修改了怎么办？ | PRE 阶段：语句级版本化修正闭环（版本+1、增量审计、全留痕）——修改是受控能力；冻结后：不可变 + 门禁整单/逐句 hash 复验——修改是硬拦截；PROD 阶段：不允许就地修正，回 PRE | §3.4 / §4.4 / §4.6 |
| 6 | 谁有权限做什么？ | 三层 AND（功能标签/资源权限/治理门禁）+ 授权矩阵 + 发起/审批/确认三权分离；管理员也不绕过 Guard | §5 |
| 7 | 普通 DDL 与大表 DDL 如何区分？ | P0 不做执行引擎分流（统一现有执行器）；表数据量/锁风险作为审计规则集的高风险提示；gh-ost 与 PG 对应物归 P2 | §2.3 / §9 |
| 8 | 生产失败怎么办？ | 通知复用（语句定位+错误详情）；瞬态故障 retryJob 断点续跑（必经门禁逐句复验）；需改 SQL → 回 PRE 修正闭环 → 新 Revision → 重新审批推进；路径 B → 重新提交新工单；绝不自动改 SQL、不自动回滚、不允许 PROD 就地修正 | §4.6 |
| 9 | 紧急生产变更怎么办？ | P0 明确不做（防止掏空治理体系）；P1 设计 `EMERGENCY_PROD_RELEASE` 独立通道（全审计、专项审批、不留普通接口后门；含 PROD 紧急修正场景） | §5.3 / §9 |
| 10 | 数据库变更和应用发布谁先谁后？ | P0 不承载 Expand→Deploy→Contract；以工单描述/发布说明人工协调；P2 与应用发布体系协调 | §9 |

---

## 13. 实施期验证清单（Open Items，实施第一步逐项确认后回填结论）

1. **语句替换的引擎路径**（最高优先，影响 Phase 5/7）：`dm_exec_auto_task` 是否持有语句文本、`dispatchJob → create(jobId)` 打包 zip 的文本读取源；据此二选一：`AutoExecService` 新增治理专用 `replaceTask`（失败 task→CANCELED + 新版本 task 入列，`exec_order` 语义保持）或治理层受控直写 task 行；同时确认 `retryJob` 重打包对新 task 的兼容性
2. **审批漏斗身份校验**：`approvalFlowService.approvalTicket` / `confirmTicket` 是否校验操作者必须为被指派审批人/确认人；SYSTEM 代审是否需要定向通道（影响 Phase 4）
3. **`DmlExplainPreInitHandler` 的 PG 支持**：对 PG 数据源是否可用；不可用则路径 B PG 降级策略生效（影响 Phase 8）
4. **通知能力复用**：`ImSenderService`/`DingTalkMsgSendSpi` 在 console 侧的通用调用入口、消息模板与深链格式（影响 Phase 5）
5. **`autoExecConfig` 注入与锁定**：`DmConfirmTicketFO.autoExecConfig` 的治理注入点（系统代审传参）与 PROD 人工确认时的锁定方式（前端预置+门禁二配置合规校验兜底）（影响 Phase 4/7）
6. **工单确认权限标签现状**：`ApprovalController.confirm` 现有 `@RequestAuth` 标签；决定复用还是新增 `RDP_DB_CHANGE_PROD_CONFIRM`（影响 Phase 7/10）
7. **`approBiz` 参数化影响面**：`createSqlTicketInTransaction` 中 `DM_QUERY` 写死位置及下游对 bizType 的分支依赖（影响 Phase 4，核心触点 #1）
8. **schema 元数据 SPI 确切名称**：Preflight 存在性检查所用的表/列/索引元数据获取接口（console 侧经 RSocket 到 sidecar 的调用路径）（影响 Phase 7）
9. **`ApprovalMO` 扩展兼容性**：新增治理引用字段对 CI/CD 现有用法（`changeId/changeOwnerUid/autoExec`）的 Jackson 兼容性确认（影响 Phase 4，核心触点 #1）
10. **`res_desc` 列的现有使用方式**：确认授权查询/展示逻辑不依赖 `res_desc` 语义，标记写入无副作用（影响 Phase 2）

---

## 附：与输入文档的对应关系

- 本 spec 的 P0 范围 = CODE_DESIGN §156 P0 清单，替换其数据模型/执行/审批部分为"复用现有系统"实现（§1.4 修正表），新增其未覆盖的：路径 B 直发 DML（D11）、语句级版本化与修正闭环（D14）、执行配置成分路由（D15）
- CODE_DESIGN 的"工单→Revision"两级模型升级为"工单→语句版本→Revision(逐句 manifest)"三级；其"PRE 验证 A / PROD 执行 A"原则由整单 hash 强化为整单+逐句双层 hash 比对
- READY_PLAN 的调研义务（Bytebase/Liquibase/gh-ost/GitLab 参考、十问）以 §12 归档回答 + §9 分期边界方式收口；其"第一阶段不改代码、先分析后设计"的纪律由本 spec（纯设计文档）+ §13 验证清单履行
- 后续实施建议按 Trellis 流程建任务树：本 spec 作为父任务需求源，Phase 1-12 拆子任务独立验收
