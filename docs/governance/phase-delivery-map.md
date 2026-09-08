# 数据库变更治理 · 期次交付对照表

> 父任务 09-06-db-change-governance [13/13] 收官材料。
> 需求源：spec rev.2.1 §8.2 完成定义（两链路 + 安全底线 8 条）。
> 测试覆盖矩阵：`.trellis/tasks/archive/2026-09/09-06-gov-phase11-testing/research/coverage-matrix.md`（54 项矩阵：43 FULL + 11 EXEMPT，411 `@Test` 方法）。
> 真实环境验证清单：`tests/governance/prod-verification.md`。
> 灰度上线 runbook：`docs/governance/rollout-runbook.md`。

## 13 期交付总览

| Phase | 提交 hash | 核心交付 | 测试与文档落位 |
|---|---|---|---|
| 0 | `517555e6` + `c472da7e` | 治理平台调研与 spec 确认：15 项关键决策定稿、§13 十项验证回填、spec rev.2.1 入库、7 处核心触点总账锁定 | 调研文件 `.trellis/tasks/archive/2026-09/09-06-gov-phase0-verification/research/`；spec `docs/spark/2026-09-06-clouddm-db-change-governance-design.md` |
| 1 | `544ae169` | Flyway 平台元数据迁移 `V202609070001`：治理 10 张新表 DDL + 治理权限标签注册 + 审计类型/资源类型注册 | 迁移文件 `boot-initialization/.../scripts/V202609070001__db_change_governance.java`；表清单见 runbook §0.1 |
| 2 | `6a904689` | 权限组域：4 表 DO/Mapper/Service + 物化展开（`dm_auth_res` 直写 + `PERM_GROUP:` 标记 + 账本表）+ 触点 #4 撤销保护（4 站点） | `PermGroupServiceImplTest` (13)、`DmAuthServiceForManageTouchpointTest` (5)；契约 `governance-contracts.md` "Permission-group materialized expansion" + "Touchpoint #4" |
| 3 | `08393788` | 逻辑库域：2 表 DO/Mapper/Service + `getBinding` 三态语义 + `myLogicalDbs` 可见性 + `bindingSet` 全删全插 + GOV_ROLE 冲突检查 | `LogicalDbServiceImplTest` (33)；契约 "getBinding" + "myLogicalDbs" + "bindingSet" |
| 4 | `330f1ef7` | PRE 治理管道：`preSubmit`（触点 #1）+ 语句拆分 + hash 冻结 + Revision 冻结 + `GovPipelineScheduler`（duty 1-2）+ SYSTEM 代审代确认 + 触点 #2 guard 入口 | `DbChangeGovernServiceImplTest` (10)、`GovStmtSplitServiceImplTest` (10)、`GovSqlHashUtilsTest` (11)、`RevisionFreezeServiceTest` (6)、`GovAutoAdvanceServiceTest` (8)、`GovPipelineSchedulerTest` (4)；契约 "SYSTEM auto-advance channel" + "stmt_index alignment" + "PRE_INIT safety" + "hash single implementation" |
| 5 | `31a0932a` | PRE 修正闭环：触点 #6 `replaceTask` + 语句版本表 + 增量审计 + `retryJob` 断点续跑 + 语句失败通知（duty 3）+ `stmtTimeline` | `GovCorrectionServiceTest` (7)、`ReplaceTaskTest` (6)、`GovFailureNotifyServiceTest` (7)、`StmtTimelineTest` (3)；契约 "Correction loop" + "Audit resId PK" |
| 6 | `a8e3a319` | 生产发布域：Promotion + gate-one 7 项门禁 + 状态机 + execution_key 幂等 + PROD 工单构造 + duty 4 状态同步（13→9 映射） | `GateOneTest` (10)、`AvailableRevisionsTest` (8)、`PromotionStateMachineTest` (14)、`GovPromotionSyncServiceTest` (11)；契约 "Promotion gate-one & state machine" |
| 7 | `c96c1473` | 执行守卫 gate-two：6 项门禁 + 触点 #2/#3 双入口 + Preflight 四项 + 触点 #5 skipTask/continueTask 拦截 + duty 5 `GOV_AUTO_CONFIRM` + PreInit guard handler | `GovExecutionGuardServiceImplTest` (15)、`GovPreInitGuardHandlerTest` (7)、`GovPreflightCheckerTest` (4)、`GovAutoConfirmServiceImplTest` (8)；契约 "Execution guard / gate-two" |
| 8 | `9332a0d5` | 路径 B 直发生产 DML：`directDmlSubmit` + 提交时阈值分级（`GovDmlRowEstimator` + `GovRowLimitConfig`）+ 六对象单事务 + PG DML 降级（恒 0） | `GovDirectDmlServiceTest` (12)、`GovRowLimitConfigTest` (20)、`GovDmlRowEstimatorTest` (4)、`PathBCompatTest` (4)；契约 "Path B direct DML + submit-time threshold tiering" |
| 9 | `8daf6cbe` | 钉钉审批表单：触点 #7 `convertToChangeForm` 治理分支 + 九字段映射 + riskLevel 结构化字段 + 审批同步幂等零改动验证 + 夹带/伪造安全测试 | `ConvertToChangeFormGovernanceTest` (16)、`RiskLevelConsistencyTest` (10)、`ApprovalSyncIdempotencyTest` (5)、`ApprovalForgeryTest` (2)、`GovFoSmugglingTest` (11)；契约 "Governance approval form (touchpoint #7)" |
| 10 | `29ebe745` | 前端页面 + 只读 HTTP 接口：`splitPreview`/`eventTimeline`/`stmtTimeline`/`availableRevisions`/`revisionDetail`/`promotionList`/`promotionDetail` + `govEventConstants.js` + `promotionDetail.vue` + logicalDb 前端 | `SplitPreviewTest` (8)、`EventTimelineTest` (5)、`RevisionDetailTest` (4)、`PromotionListDetailTest` (5)；前端测试 `tests/frontend/governance/governance-flow.md`；契约 "Governance frontend + read-only HTTP surface" |
| 11 | `be552777` | 测试覆盖矩阵闭合：54 项矩阵全覆盖（43 FULL + 11 EXEMPT）、411 `@Test` 方法、安全层测试 + 修正闭环语义 + 双方言参数化 + 四链路编排 | `SecurityAuthTest`、`ApprovalControlSkipContinueTest`、`DispatchJobGuardTest`、`GovernanceDualDialectTest` (5)、`GovernanceChainPreTest`、`GovernanceChainPromoteTest`、`GovernanceChainPathBTest`、`GovernanceChainRetryTest`；覆盖矩阵 `.trellis/tasks/archive/2026-09/09-06-gov-phase11-testing/research/coverage-matrix.md` |
| 12 | 本提交 | 灰度上线 runbook + 期次交付对照表（产码零改动） | `docs/governance/rollout-runbook.md`、`docs/governance/phase-delivery-map.md` |

---

## §8.2 验收映射

spec §8.2 完成定义：两条链路全生命周期跑通 + 安全底线 8 条不可绕过。

### 链路一（路径 A）：PRE 提交 → 冻结 → 发布 → 执行 → Timeline

| 环节 | spec §8.2 步骤 | 对应 Phase | 测试类 / 方法 | 真实验证 |
|---|---|---|---|---|
| 提交治理工单 | Step 2 | 4 | `DbChangeGovernServiceImplTest` (preSubmit ×3 + rejections) | prod-verification.md §Chain One Step 2 |
| 规则审计 + PRE 自动执行 | Step 3 | 4+7 | `GovAutoAdvanceServiceTest` (hit matrix) + `GovPreInitGuardHandlerTest` | prod-verification.md §Chain One Step 3 |
| 全部成功 → 冻结 Revision | Step 4 | 4 | `RevisionFreezeServiceTest` (manifest + idempotency) | prod-verification.md §Chain One Step 4 |
| 创建 Promotion（门禁一） | Step 5 | 6 | `GateOneTest` (7-gate DENY + all-PASS) + `AvailableRevisionsTest` (6-filter) | prod-verification.md §Chain One Step 5 |
| 钉钉审批 | Step 6 | 9+6 | `GovPromotionSyncServiceTest` (duty 4 sync) + `ConvertToChangeFormGovernanceTest` (表单) | prod-verification.md §Chain One Step 6 |
| 平台确认执行 | Step 7 | 7 | `GovAutoConfirmServiceImplTest` (duty 5) + `GovExecutionGuardServiceImplTest` (checkByTicket) | prod-verification.md §Chain One Step 7 |
| Guard 门禁二 + Preflight | Step 8 | 7 | `GovExecutionGuardServiceImplTest` (6-gate) + `GovPreflightCheckerTest` | prod-verification.md §Chain One Step 8 |
| 生产执行 → SUCCEEDED | Step 9 | 7+6 | `GovExecutionGuardServiceImplTest` (dispatch) + `GovPromotionSyncServiceTest` (SUCCEEDED) | prod-verification.md §Chain One Step 9 |
| 完整 Timeline | Step 10 | 10 | `EventTimelineTest` (event mapping) | prod-verification.md §Chain One Step 10 |
| 语句失败修正闭环 | Step 3 失败 | 5 | `GovCorrectionServiceTest` + `ReplaceTaskTest` + `GovFailureNotifyServiceTest` | prod-verification.md §Chain One Step 3 失败排查 |
| 链路编排 | 全链 | 11 | `GovernanceChainPreTest` (PRE 全链 4 事件) + `GovernanceChainPromoteTest` (promote 全链 4+ 事件) + `GovernanceChainRetryTest` (EXEC_FAIL 重试链 4 事件) | prod-verification.md §Chain One 全链 |

### 链路二（路径 B）：直发生产 DML → 冻结 → 审批 → 执行 → Timeline

| 环节 | spec §8.2 步骤 | 对应 Phase | 测试类 / 方法 | 真实验证 |
|---|---|---|---|---|
| 直发 DML + 回滚 SQL | Step B2 | 8 | `GovDirectDmlServiceTest` (4 validations + threshold + success) | prod-verification.md §Chain Two Step B2 |
| 冻结 Revision | Step B3 | 8 | `GovDirectDmlServiceTest` (六对象单事务) + `PathBCompatTest` (G2 忽略 pre_exec) | prod-verification.md §Chain Two Step B3 |
| 钉钉审批（表单含影响行数） | Step B4 | 9 | `ConvertToChangeFormGovernanceTest` (九字段) + `RiskLevelConsistencyTest` | prod-verification.md §Chain Two Step B4 |
| 确认 → Guard → 执行 → Timeline | Step B5 | 7+10 | `GovExecutionGuardServiceImplTest` (checkByJob) + `EventTimelineTest` | prod-verification.md §Chain Two Step B5 |
| 链路编排 | 全链 | 11 | `GovernanceChainPathBTest` (路径 B 全链 2+ 事件) | prod-verification.md §Chain Two 全链 |
| PG 环境降级 | §13-3 | 8+11 | `GovDmlRowEstimatorTest` (SPI-unsupported → 0) + `GovernanceDualDialectTest` (双方言一致性) | prod-verification.md §双方言真实验证点 |

### 安全底线 8 条 → DENY 矩阵 case / 测试类映射

| 底线 # | 安全底线 | DENY 矩阵 case | 门禁 | 测试类 / 方法 | 真实验证 |
|---|---|---|---|---|---|
| 1 | 无冻结 Revision → 不能生产 | — | gate-one G1 | `GateOneTest#gate1_hashMismatch_deny` (hash 不匹配 = revision 不一致) | prod-verification.md §安全底线 1 |
| 2 | 整单或任一语句 hash/version 不一致 → 不能生产 | ④ ⑤ | gate-one G1 + gate-two G2 | `GateOneTest#gate1_hashMismatch_deny` + `GovExecutionGuardServiceImplTest#checkByTicket_wholeTicketHashMismatch_denyG2` + `checkByJob_perStmtHashMismatch_denyG2` + `GateOneTest#gate2_manifestPreExecNotSuccess_deny` + `gate2_manifestVersionHashStale_deny` | prod-verification.md §安全底线 2 |
| 3 | 任一语句 PRE 未成功 → 不能生产 | ① | gate-one G2 | `GateOneTest#gate2_sourceTicketNotFinished_deny` + `RevisionFreezeServiceTest#freeze_anomalyTaskNotFinished_freezeAnomalyEventNoRevision` + `AvailableRevisionsTest#availableRevisions_manifestNotAllSuccess_excluded` | prod-verification.md §安全底线 3 |
| 4 | 无 PROD 资源权限 → 不能生产 | ⑧ | gate-one G3 | `GateOneTest#gate3_authDenied_deny` | prod-verification.md §安全底线 4 |
| 5 | 无审批通过 → 不能生产 | ② ③ | gate-one G7 + gate-two G1 | `GateOneTest#gate7_internalApproval_deny` + `gate7_noConfig_deny` + `GovExecutionGuardServiceImplTest#checkByTicket_promotionStatusCreated_denyG1` + `checkByTicket_promotionStatusRejected_denyG1` + `ApprovalForgeryTest` (伪造 APPROVED 无效) | prod-verification.md §安全底线 5 |
| 6 | Preflight 不通过 → 不能生产 | ⑨ | gate-two G4 | `GovExecutionGuardServiceImplTest#checkByTicket_preflightFail_denyG4` + `GovPreflightCheckerTest#check_connectivityFail_deny` + `check_tableNotFound_deny` | prod-verification.md §安全底线 6 |
| 7 | 执行配置不合成分路由 → 不能生产 | ⑫ | gate-two G6 | `GovExecutionGuardServiceImplTest#checkByTicket_configSkip_denyG6` + `checkByTicket_configTransactionalMismatch_denyG6` | prod-verification.md §安全底线 7 |
| 8 | 路径 B 非纯 DML 或开关关闭 → 不能生产 | ⑩ ⑪ | path B validation | `GovDirectDmlServiceTest#directDmlSubmit_nonDml_rejected` + `directDmlSubmit_mixed_rejected` + `directDmlSubmit_switchOff_rejected` + `directDmlSubmit_blockExceeded_rejected_zeroObjects` | prod-verification.md §安全底线 8 |

### 安全补充测试（非底线 8 条，但 spec §7.2 要求）

| 安全项 | DENY 矩阵 case | 测试类 / 方法 | 真实验证 |
|---|---|---|---|
| 无标签直调 → 403 | — | `SecurityAuthTest` (JwtManager.testAuth direct + controller @RequestAuth reflection) | prod-verification.md §Preconditions |
| 请求体夹带字段 | — | `GovFoSmugglingTest` (11 tests: promote + preSubmit + directDml + correctStatement) | prod-verification.md §安全底线 5 |
| 伪造 APPROVED 不能改 DB | — | `ApprovalForgeryTest` (callback always WAIT_CONFIRM + ApprovalMO ignores unknown fields) | prod-verification.md §安全底线 5 |
| skipTask/continueTask 对 PROD 被拒 | — | `ApprovalControlSkipContinueTest` (4 tests: skip + continue × PROD/non-gov) | prod-verification.md §安全底线 7 |
| correctStatement 对 PROD 拒绝 | — | `GovCorrectionServiceTest#correct_prodTicket_rejected` | prod-verification.md §安全底线 3 |
| 通知深链无凭证 | — | `GovFailureNotifyServiceTest#notify_deepLinkContainsNoCredentials` | prod-verification.md §Preconditions |

### 双方言回归

| 方言项 | 测试类 / 方法 | 真实验证 |
|---|---|---|
| 配置路由双方言 | `GovernanceDualDialectTest#preSubmit_mysql_configTransparentlyPassedToSplit` + `preSubmit_postgresql_configTransparentlyPassedToSplit` | prod-verification.md §双方言真实验证点 |
| hash 方言中立 | `GovernanceDualDialectTest#sqlHash_sameText_bothDialects_producesSameHash` | prod-verification.md §双方言真实验证点 |
| 路径 B 阈值双方言 | `GovernanceDualDialectTest#pathBThreshold_bothDialects_estimationPathConsistent` + `pathBThreshold_bothDialects_warnLevelConsistent` | prod-verification.md §双方言真实验证点 |
| PG DML Explain 降级 | `GovDmlRowEstimatorTest` + `GovDirectDmlServiceTest#directDmlSubmit_pgDegradation_rowZero_notRejected` + `GovRowLimitConfigTest#pgDegradation_rowZero_notBlocked` | prod-verification.md §双方言真实验证点 |
| 规则集路由 | EXEMPT（`PluginManager` static SPI 不可 mock） | prod-verification.md §双方言真实验证点 |
| Preflight 元数据方言 | EXEMPT（`realTimeFetchVersion`/`realTimeFetchSelectObject` 需真实 DB） | prod-verification.md §双方言真实验证点 |

---

## 真实环境验证索引

自动化测试（411 `@Test`，43 FULL + 11 EXEMPT）覆盖 service 级编排逻辑；真实环境层（真实 DB 连接、真实方言 SPI、真实钉钉回调、真实事务提交回滚、真实引擎执行、真实 IM 发送、Controller HTTP 鉴权）由以下文档人工兜底：

| 文档 | 路径 | 覆盖内容 |
|---|---|---|
| 真实环境验证清单 | `tests/governance/prod-verification.md` | 两条链路全生命周期 + 安全底线 8 条逐条验证 + DENY 矩阵真实环境抽查 + 双方言真实验证点 + 自动化豁免清单 |
| 浏览器操作流程 | `tests/frontend/governance/governance-flow.md` | 治理前端浏览器操作面（创建/发布/详情/修正/Timeline） |
| 灰度上线 runbook | `docs/governance/rollout-runbook.md` | 逐库灰度三步走 + 阶段 0 前置 + 回滚预案 + 监控巡检 |
| 覆盖矩阵 | `.trellis/tasks/archive/2026-09/09-06-gov-phase11-testing/research/coverage-matrix.md` | 54 项矩阵全覆盖明细（43 FULL + 11 EXEMPT → prod-verification.md） |
| 领域契约 | `.trellis/spec/backend/governance-contracts.md` | 7 处触点 + 权限组展开 + binding 解析 + hash 契约 + 修正闭环 + gate-one/gate-two + 路径 B + 审批表单 + 前端接口 |

### 11 项 EXEMPT → prod-verification.md 映射

| # | 矩阵项 | 豁免原因 | 人工验证章节 |
|---|---|---|---|
| 1 | 规则集路由 | `PluginManager` static SPI 不可 mock | §双方言真实验证点 |
| 2 | Preflight 元数据 | `realTimeFetchVersion`/`realTimeFetchSelectObject` 需真实 DB | §双方言真实验证点 + §安全底线 6 |
| 3 | DML Explain 原生路径 | NATIVE_EXPLAIN 需真实 EXPLAIN 执行 | §双方言真实验证点 |
| 4 | 配置路由 PG 变体 | 真实 PG 方言行为 | §双方言真实验证点 |
| 5 | PRE 全链 real-DB | 真实自动执行 + task 状态迁移 + freeze | §Chain One Steps 2-4 |
| 6 | promote 全链 real-DingTalk | 真实钉钉 Stream/callback | §Chain One Steps 5-6 |
| 7 | 路径 B 全链 real-EXPLAIN | 真实 EXPLAIN 执行 + 阈值评估 | §Chain Two Steps B2-B5 |
| 8 | EXEC_FAIL 重试 real-engine | 真实 retryJob task 选择 + 断点续跑 | §Chain One Step 9 |
| 9 | EXECUTING 重启三态 | 真实系统重启 | §Chain One Steps 3-4 |
| 10 | 双 worker 单执行 | 真实并发 claimJobForPackaging | §Chain One Step 3 |
| 11 | 权限组有效期 real-SQL | 真实 deleteByEndTimeExceed DELETE | §Chain One Step 1 |
