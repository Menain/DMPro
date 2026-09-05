# CloudDM 数据库变更治理平台二次开发（P0）· 父任务

## Goal

在 CloudDM（open-cdm 4.2.0）现有 RBAC、资源授权、SQL 审计、工单审批、任务执行体系之上，以**最小侵入**增加企业级数据库变更治理能力：权限组、逻辑库+PRE/PROD 环境绑定、PRE 自动验证→语句级版本化修正闭环→冻结不可变 Revision（整单+逐句 hash manifest）→生产推进发布（Promotion）、直发生产 DML 受控通道（路径 B）、生产强制审批+执行确认双控+行前 Guard/Preflight、全链路可审计 Timeline（工单级+语句级）。MySQL 与 PostgreSQL 双方言同构，CI/CD 零耦合。

## 需求源（唯一事实来源）

- **Spec**：`docs/spark/2026-09-06-clouddm-db-change-governance-design.md`（rev.2，已定稿入库 main，commit `03cc00ad`）
  - §0 决策记录 D1–D15（全部关键决策，实施不得偏离）
  - §2.2 核心触点总账（6 处小改动，其余全部纯新增）
  - §3 领域模型（10 张新表）
  - §4 状态机与核心流程（双入口/双 Guard/推进器/修正闭环）
  - §13 实施期验证清单（10 项 Open Items，Phase 0 回填）
- 辅助输入（已被 spec 修正，冲突时以 spec 为准）：`.claude/CLOUDDM_SECOND_DEVELOPMENT_CODE_DESIGN.md`、`.claude/READY_PLAN.md`
- 工程规则：`.claude/AGENTS.md`（Mapper XML 排版、前端构建纪律、Conventional Commits 等）

## 任务地图（13 个子任务，按 Phase 顺序推进）

| 顺序 | 子任务 | 交付物 | 前置 |
|---|---|---|---|
| 0 | `gov-phase0-verification` | §13 十项验证结论回填（首位：语句替换引擎路径） | 无 |
| 1 | `gov-phase1-flyway-schema` | 1 个 Flyway Java 脚本：10 表+5 权限标签+4 env param key | 无（可与 0 并行） |
| 2 | `gov-phase2-perm-group` | 权限组 CRUD/成员/资源/物化展开/账本/撤销保护（触点 #4） | P1 |
| 3 | `gov-phase3-logical-db` | 逻辑库+环境绑定+治理角色参数+绑定解析服务 | P1 |
| 4 | `gov-phase4-pre-pipeline` | preSubmit/逐句拆分/成分路由/推进器系统代审/Revision 冻结（触点 #1） | P0②⑤⑦⑨、P1、P3 |
| 5 | `gov-phase5-correction-loop` | 失败通知/correctStatement/任务替换/断点续跑/stmtTimeline（触点 #6） | P0①④、P4 |
| 6 | `gov-phase6-promotion-gate` | availableRevisions/promote/门禁一（逐句 manifest 核验）/Promotion 状态机 | P4 |
| 7 | `gov-phase7-exec-guard` | 门禁二双挂点/逐句 hash 复验/Preflight/配置合规/skip 防护（触点 #2#3#5） | P0⑤⑥⑧、P6 |
| 8 | `gov-phase8-direct-dml` | 路径 B directDmlSubmit/仅 DML 判定/阈值分级 | P0③、P6、P7 |
| 9 | `gov-phase9-dingtalk` | 治理字段表单/强制第三方 Provider/回调幂等兜底验证 | P6 |
| 10 | `gov-phase10-frontend` | 权限组页/逻辑库页/工单治理模式+语句级视图/发布页/Timeline/处置页 | P2–P9 接口就绪 |
| 11 | `gov-phase11-testing` | DENY 矩阵 12 case/修正闭环/幂等并发/安全/权限组一致性/双方言回归 | P1–P10 |
| 12 | `gov-phase12-rollout` | 逐库灰度三步走 | P11 |

排序约束说明：Phase 顺序即推荐实施顺序；有依赖的子任务须等前置完成（如 P5 依赖 P0 验证项①的结论选择语句替换实现路径）。每个子任务 `task.py start` 前需将本 spec 对应章节写入其 `prd.md`，复杂子任务（P4/P5/P7/P10/P11）另需 `design.md`+`implement.md`。

**进度**：Phase 0 已完成（2026-09-06）——十项验证结论+两项用户裁决（D16）回填 spec rev.2.1；证据在 `.trellis/tasks/09-06-gov-phase0-verification/research/00-summary-decisions.md`。关键落定：触点 #6=`replaceTask` 方案(a)；触点 #7 新增（convertToChangeForm 治理分支）；SYSTEM 定向代审通道并入 Phase 4；确认标签复用 `RDP_WORKER_ORDER_EXECUTE`；Preflight 四接口零新增；PG 行估算降级零代码。

## 全局约束（所有子任务必须遵守）

1. **核心触点仅 7 处**（spec §2.2，rev.2.1）：#1 createSqlTicket 参数化 approBiz+ApprovalMO 治理字段（POJO 字段先行）、#2 prepareExecJobAsync 门禁、#3 dispatchJob 门禁、#4 撤销保护、#5 skipTask/continueTask 拦截、#6 语句替换（已锁定 `AutoExecService.replaceTask` 方案）、#7 convertToChangeForm 治理分支（Phase 0 验证后新增，D16）。其余全部纯新增。
2. **不改**：状态机、审批引擎、钉钉 Provider、执行器核心、parser、脱敏。
3. **CI/CD 模块零改动**（D13）：`dm_change_flow`、`ChangeActionForApproval`、前端 `views/cicd/` 不碰。
4. 代码落位：治理业务→`cgdm-console`；DO/Mapper→`cgdm-dao`（XML 排版对齐 `DmApprovalProcessMapper.xml`）；元数据库脚本→`boot-initialization` Flyway Java；前端→现有 `views/`+`services/http/api/` 模式。
5. 契约红线（spec §6.4）：前端传的 approvalStatus/preResult/sqlHash/dsId/autoExecConfig 一律不信任，全部后端裁决。
6. hash 契约（D10）：`SHA256(原文+确定性空白规范化)`，整单+逐句双层，字节级冻结零改写。
7. 每阶段编译+相关模块测试通过才进下一阶段；前端源码修改后 `cd package && ./all_build.sh web` 必须退出码 0。
8. Conventional Commits 分提交，直接提交 `main`（用户已确认此工作方式）。
9. ADR-001~010 实施期落 `docs/adr/`（spec §10）。
10. 测试豁免：本项目豁免 AGENTS.md"不主动新增测试类"默认规则（spec §7.2）。

## Acceptance Criteria（跨子任务验收 = spec §8.2 完成定义）

以下两条链路在真实环境全部跑通，且 DENY 矩阵任一条件不满足时确认被拦截：

- [ ] **链路一（路径 A）**：用户→加入权限组→获得 PRE 资源→提交 DDL/DML/混合（含 DML 附回滚 SQL）→规则审计→PRE 自动执行→（语句失败→通知→修正→断点续跑）→全部成功→冻结 Revision（整单 hash+逐句 manifest）→创建 Promotion（逐句核验）→钉钉审批通过→平台确认执行（配置锁定）→Guard（逐句 hash 复验）+Preflight→生产执行→PROD_SUCCESS→完整 Timeline（工单级+语句级）
- [ ] **链路二（路径 B）**：数据订正负责人→开关开启环境→直发 DML+回滚 SQL→仅 DML 判定→EXPLAIN 阈值分级→冻结→钉钉审批（表单含影响行数）→确认→Guard+Preflight→生产执行→完整 Timeline
- [ ] **安全底线 8 条全拦截**：无冻结 Revision／整单或任一语句 hash/version 不一致／任一语句 PRE 未成功／无 PROD 资源权限／无审批通过／Preflight 不通过／执行配置不合成分路由／路径 B 非纯 DML 或开关关闭——任一不满足即不能生产，不允许通过 UI、Controller、Task、脚本任何方式绕过
- [ ] **双方言**：上述链路 MySQL 与 PG 各验证一遍
- [ ] **未标记环境零影响**：无 GOV_ROLE 标记的环境全程保持现有普通工单行为
- [ ] §13 十项验证结论全部回填、ADR-001~010 落档

## Notes

- 父任务不直接承载实现；实现全部在子任务内进行，父任务负责需求集维护、顺序裁决与最终集成评审。
- 灰度（Phase 12）为上线动作，非代码交付；未标记环境零变化是硬性回归标准。
