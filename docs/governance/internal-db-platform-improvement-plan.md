# 内部数据库平台完善计划

> 状态：实施方案（基于当前代码与项目规划的差距闭环版）  
> 核验：2026-10-10/11 已对照代码基线 HEAD=b1915462 逐项核验第 2 章六项 P0 差距，全部属实或部分属实，详见 §2 表后「核验状态注记」  
> 目标读者：平台研发、DBA、安全、业务研发、测试与运维  
> 范围：内部员工使用的 SQL 工作台、数据库变更治理、生产审批执行、脱敏、审计与恢复能力；优先覆盖 MySQL 和 PostgreSQL。

## 1. 目标与决策边界

目标不是另建一套 DMS，而是在 CloudDM 已有数据源、权限、SQL 工单、预检、执行器、审批、审计和脱敏基础上，补齐生产治理的安全闭环，并明确不同数据库的能力等级。

期望主链路：

```text
员工身份/角色/资源授权
  ├─ 查询：SQL检查 → 权限与限额 → 执行 → 结果脱敏 → SQL审计
  └─ 变更：提交 → 预检/风险评估 → PRE自动执行 → 成功后冻结Revision
          → 生产发布单 → 钉钉审批 → 发布前复核 → 生产执行 → 结果审计/处置
```

产品承诺按三层表达：

1. **已存在，重点验证与运营化**：SQL 工作台、工单/审批、PRE 自动执行、Revision/Hash、钉钉桥接、Schema 检查、风险评估、脱敏、MySQL gh-ost、MySQL/PG DML 分批等。详细设计和期次交付材料已存在，不重复建设。
2. **本计划 P0 补齐**：生产 Schema 门禁默认 fail-closed；外部审批回调绑定当前审批轮次；生产发布使用冻结执行计划并支持受控执行器路由；安全、审计、恢复和真实数据库验收闭环。
3. **明确不承诺**：所有数据库通用“无锁 DDL”、任意 DDL 自动回滚、PG 与 MySQL 相同的影响行数估算、自动 SQL 改写后直接上线。遇到不支持形态必须阻断、明确降级或进入人工处置，不能静默按普通 SQL 执行。

## 2. 当前基线与已知差距

项目已有 13 期治理交付记录、双方言治理测试设计及无锁 DML/DDL 专项文档。现有方案覆盖核心流程，但生产准入的安全默认值和执行器一致性仍需以当前源码为准重新验收。

当前源码中应列为 **P0 安全修复/决策项**：

| 项 | 当前行为/风险 | 目标行为 |
|---|---|---|
| Schema 预检与漂移 | 预检或 diff 出现 `WARN_SKIP` 时记事件后仍可派发；缺快照、不可解析或基础设施异常可能无法证明生产前置条件（**C4 完成态注记 2026-10-11**：fail-closed 反转已按 ADR-011 由任务 `10-11-schema-gate-fail-closed` 落地——「无法证明安全」9 类默认拒绝派发（语句 FAILED + `RELEASE_SCHEMA_GATE_UNVERIFIABLE` 事件，与确认违规事件区分）；受治理 override 上线（专用权限 `GOV_SCHEMA_GATE_OVERRIDE` + 理由必填 + 有效期 ≤7 天 + 一次性 CAS 消费 + 事件/HIGH 审计双 trail）；D5/D6 正当放行改独立事件语义、S1~S5 静默面收编 PASS+UNCERTAIN 事件、D2 缺快照新单拒绝/存量单一次性放行分叉；两个隐藏全局开关注册进 RootUserConfig 白名单（UI+审计通道），语义收窄为「UNVERIFIABLE 迁移期 break-glass」，已确认违规不再可被开关降级；hash 漂移不可 override。契约详见 governance-contracts.md「Release schema gate fail-closed + governed override」条目；真实发布单撞闸/override 实操留项目末尾统一联调） | 生产默认阻断无法证明安全的情况。只有显式、受权限控制、限时且有理由的 override 才可继续；记录审批人、原因、检查项和证据 |
| 钉钉审批轮次 | 外部命令回调可携带 `externalFlowId`，当前服务需要核验其与本次活动审批实例/轮次严格一致；仅检查工单处于待审批不足以排除旧回调（**C5 完成态注记 2026-10-11**：轮次绑定已按 ADR-012 由任务 `10-11-approval-round-binding` 落地——回调守卫链扩至五道，新增 `FLOW_ID_MISMATCH`（回调携带的 flowId 与库内已记录值不一致即拒绝）与 `ROUND_MISMATCH`（回调携带轮次与 `dm_approval.approval_round` 不一致即拒绝，重提交 round+1 使旧轮次回调失效）；宽松模式起步（标识缺失跳过比对——受开放 API「最简调用只传 ticketId」冻结契约约束），`strict` 档配置位已备默认关；每次拒绝先落 `EXTERNAL_COMMAND_REJECTED` 审计再 throw（含 execute/return 开放命令路径，补齐 C2 观察项）；出站创建审批恒携带 `context.round` 供桥接回传；identity 空窗反写保留但加观测标记。桥接侧 additive 适配（读 round/回传 externalRound）清单见联调手册 5A 章，项目末尾统一联调执行。eventId 幂等经 ADR-012 Decision 6 评估不采纳——不提供旧轮次防护且迁移有存量陷阱） | 强制绑定 release、approval round、active flow id；旧轮次、缺失 ID、重复/乱序事件均幂等拒绝并审计 |
| PROD 执行计划 | PRE 可形成分段执行计划；发布端需核实生产是否复用冻结的路由计划，避免生产侧退回直发单条 SQL，导致大表 DDL 绕过 GHOST/DML_BATCH 路由（**C7 完成态注记 2026-10-13**：冻结计划路由已按 ADR-013 由任务 `10-12-release-chain-frozen-routing` 三里程碑落地——**M1 载体+冻结传播**：新表 `dm_prod_release_stmt_segment` 逐段承载源组 `precheck_result.segments` 的冻结副本（merge 事务内 propagate，生命周期镜像 schema 快照表：resubmit 重填/return/reject/cancel 级联删）+ `dm_prod_release_stmt.plan_hash` 计划完整性 pin（`ReleasePlanHashUtils` 单点口径）；存量不回填，null-plan 保持 legacy 直发逐字节不变（裁决附带）。**M2 段感知派发**：`createReleaseStmtJob` 读 manifest——空→legacy 直发不变、非空→`ReleaseSegmentService` 按段派发且结构上无 DIRECT 回退（不静默降级红线）；AUTOEXEC 段 stmtRange 过滤+全局 exec_order 空洞编号（禁 re-densing）；GHOST 段经 additive 互斥 binding（`releaseStmtId/releaseId`，工单链回写零 diff）在 prod 按 gh-ost 重放，派发前只做能力复核（方言/直连/通道/表+PK-UK 硬前置，不重探测不重路由，裁决 2），失败→`RELEASE_ROUTE_CAPABILITY` 阻断；DML_BATCH 段阻断→`RELEASE_ROUTE_UNSUPPORTED`（裁决 3 分级：release 侧批执行体+段级裁决面留观察项 O-C7-1 后续任务，B8 seam 零触碰）；sweep 段级探活+停链重驱；retry 从 firstNonTerminal 续跑、SUCCESS 段永不重放。**M3 hash 冻结扩展+受治理路由 override**：三派发点门禁序扩为 **hash(文本)→plan(计划)→precheck(B)→diff(A)**，计划漂移（重算 ≠ plan_hash，或 pin 在而 manifest 空）→`RELEASE_PLAN_DRIFT` 拒发且**永不可 override**（完整性类，镜像文本 hash 漂移）；`executionKey` 语义不变（不纳入计划）。新表 `dm_gov_route_override`+专用权限 `GOV_ROUTE_OVERRIDE`+理由必填（≤1024）+有效期 ≤7 天+一次性 CAS 消费+活性唯一（每 (releaseId,stmtId) 至多一条待消费，服务层强制），消费点=DML_BATCH 阻断与 GHOST 能力失败两处，放行语义=该段按单条直发重放（exec_order=stmtIndex）即**显式接受锁/超时风险**（`RELEASE_ROUTE_OVERRIDE` 事件+HIGH 授予人审计双 trail），与 C4 结构门禁 override 完全隔离互不代换（裁决 4）；前端发布单详情新增冻结计划复核区块（段序/类型/表/估算行/分批键/段状态）+DML_BATCH 阻断横幅与出路提示+plan_hash 展示+路由 override 管理入口（权限显隐）+四新事件时间线，存量「重放为直接执行」黄旗收窄至无冻结计划的路由语句。契约详见 governance-contracts.md「Release-chain plan-drift gate + governed route override」条目、ghost-online-ddl.md §5.10、lockfree-dml.md §10.1；真库发布单段执行/阻断/override 实操留项目末尾统一联调） | Revision/manifest/目标库/执行器/参数全部冻结；发布前复核后按计划逐段执行。能力不支持时阻断或显式审批风险，不静默降级 |
| DML 分批语义 | 分批不是无锁，也不是原子事务；停止或失败可能保留已完成批次，PG 影响行数估算目前存在已知降级（**C3 完成态注记 2026-10-11**：「估算未知伪装为 0」已由任务 `10-11-pg-estimation-unknown` 修复——方案 b：估算不可得时 `expected_affected_rows` 落 NULL、工单详情风险区显示「行数不可估算」、分批公告区分表规模口径；风险评级行为不变，「未知→更严格策略」属 Phase 2） | 用户确认文案、恢复入口和风险等级准确；PG 的零估算不得伪装为零影响或低风险 |
| 回滚表述 | 回滚 SQL、事务回滚、影子表保留/备份、自动补偿语义不同 | UI/API/审计按恢复类型标注；不得显示为“可自动回滚”，除非已验证对应恢复物并演练成功 |
| 脱敏与审计 | 有结果集脱敏实现，但要证明所有结果出口、导出、异常日志和异步路径一致（**C8 完成态注记 2026-10-13**：可检索审计链地基已由任务 `10-13-audit-chain-enhancement` 按四裁决落地——`dm_mon_op_audit` 新增四条带索引真列 `sql_fingerprint`/`ticket_id`/`release_id`/`channel`（裁决 2 四列方案，masking 策略快照留 JSON），指纹统一走 `GovSqlHashUtils` 与治理链 `stmt_hash` 同口径天然 join；治理族写点全量接入（GHOST 工单/发布双链、DML_BATCH 派发/裁决/拒绝、override 四类型、SENSITIVE_* 行补脱敏策略快照键）；`dm_exec_sql_audit` 同步加 `sql_fingerprint`；channel 三值 CONSOLE/OPENAPI/SYSTEM_AUTO 由 logOperation 收口点 URI 推断、既有 59 处调用签名零改动（additive 重载）；UI 增通道过滤/工单-发布跳转/指纹列，「查看日志」死端点改造为 `/api/entry/audit/detail`（租户隔离+AuditSanitizeUtils sanitization 兜底，前后值/原因自此 UI 可见，裁决 3）；op_audit 三索引+biz_log 缺 AND 修复（MASKING_NOT_BOUND 观测恢复）+op_audit/biz_log 保留期 KV 与 30 分钟分批清理（L228 保留期签字的机械化，数值试点前定，裁决 4 含 root 全量留痕不推翻）；存量明文行按裁决 1「仅测试库直接清空」——清理手册 `tests/governance/audit-legacy-cleanup.md` 交付用户执行（零凭据口径+轮转清单）；顺带收口 O-C7-5/6（plan gate 残留面 drift 化、override 生成列唯一约束、getDetail 合并读）与 UNRESOLVED raw 错误文本净化扩围。契约详见 logging-guidelines.md / governance-contracts.md / desensitization-rules.md 对应条目；真库检索/导出/保留实测留项目末尾统一联调） | 通过端到端安全测试证明原值不能从任何未授权出口泄漏；SQL、审批、执行、回滚、override 形成可检索审计链 |

以上第 1—3 项要先用源码、现有测试和真实环境复现/确认，再实施修复；不能因文档已有设计或测试类存在就视为生产验收已完成。

**核验状态注记（2026-10-11，代码基线 HEAD=b1915462，三路只读调研；完整证据底座存 Trellis 任务 `10-11-db-platform-p0-security-hardening` 的 `research/p0-gap-verification.md`，内部材料不入库）**：

| 项 | 核验判定 | 补充（好于/差于本章描述之处） |
|---|---|---|
| Schema 预检与漂移 | **属实** | 「宁漏勿误」是明文设计原则（`PrecheckConclusion` 注释原文）；不可分类 SQL 静默放行且不记事件；WARN 事件只写不读（无告警/门禁消费）；全局配置可将已确认 BLOCK 降级放行且降级无个案审计。**该差距已于 2026-10-11 由 C4 按 ADR-011 反转（任务 `10-11-schema-gate-fail-closed`）**：默认 fail-closed + 受治理 override + 两开关改明（RootUserConfig 注册）；原注释已标 superseded，确认 BLOCK 不再可被开关降级；存量摩擦面（retry 重跑新闸）为 ADR-011 Decision 5 有意设计，联调时记录实际拦截样本 |
| 钉钉审批轮次 | **属实** | 桥接 6 笔提交仅覆盖出站侧；入站 externalFlowId 全链路零比对，旧 flowId 打到新 ticketId 会被接受且反写 appro_identity；被拒命令零审计落库（守卫 throw 先于审计写入）；**签名校验已达标**（五参 HmacSHA1+时间戳新鲜度+body SHA-256，fail-closed，强于本文底线）。**该差距已于 2026-10-11 由 C5 按 ADR-012 修复（任务 `10-11-approval-round-binding`，用户裁决 A+B 一步到位）**：flowId 比对 + approval_round 列一次落地，宽松模式起步、strict 档灰度备位，拒绝路径全量审计（含 execute/return 开放命令）；旧 flow 打新 ticketId 的反写链已被 FLOW_ID_MISMATCH 守卫切断；桥接侧回传适配与 5A.1~5A.10 场景验证留项目末尾统一联调 |
| PROD 执行计划 | **属实** | 代码注释自证过渡态（release-chain ghost/batch routing 属 B6/B7 的 M5 里程碑）；`DmProdReleaseStmtDO` 无 plan/route 字段；hash 门禁本身 fail-closed 但只锁文本，对路由降级无感知；双链不一致：同一生产 DML 走工单链享受 DML_BATCH 分批、走发布链退回单条直发。**该差距已于 2026-10-13 由 C7 按 ADR-013 修复（任务 `10-12-release-chain-frozen-routing`，用户四裁决：独立 manifest 表 / 冻结+能力复核不重路由 / DML_BATCH 分级阻断 / 路由专用 override）**：三里程碑 M1 冻结载体（`dm_prod_release_stmt_segment`+`plan_hash`，merge 传播+级联删+resubmit 重填，存量不回填）→ M2 段感知派发（GHOST 段 gh-ost 重放+能力复核、DML_BATCH 阻断+事件、无 DIRECT 回退=不静默降级、sweep 段级探活、retry 段续跑）→ M3 计划完整性门禁（三派发点 hash→plan→precheck→diff，`RELEASE_PLAN_DRIFT` 永不可 override）+受治理路由 override（一次性 CAS+显式风险接受+双 trail，与 C4 override 隔离）+前端冻结计划复核面。hash 只锁文本的缺口由 plan_hash pin 补齐；双链不一致收敛为「带冻结计划的发布语句按计划逐段执行」，release 侧 DML_BATCH 批执行体+段级裁决面（RERUN/SKIP）留观察项 O-C7-1 后续任务；真库段执行/override 实操留项目末尾统一联调 |
| DML 分批语义 | **部分属实** | 分批非原子已实现且已诚实披露（前端三点明示「平台不提供自动回滚」，有检查点/UNRESOLVED/人工裁决链——好于预期）；但审批 PRE_INIT 路径将 PG 的 null 估算汇总为 0 并据此评 LOW（差于预期，且合同文档 governance-contracts.md:235 钉死该降级）；RiskLevel 枚举无 UNKNOWN。**该差距已于 2026-10-11 由 C3 修复（任务 `10-11-pg-estimation-unknown`，方案 b 用户裁决）**：PRE_INIT 汇总改 null 透传（NULL 入库，不再伪装 0），合同钉死条款同步修订；方案 b 下枚举不加 UNKNOWN、评级行为逐字节不变，未知态由伴生 rowsEstimated 标志与前端「行数不可估算」标注表达 |
| 回滚表述 | **属实** | 四种恢复语义（事务/补偿SQL/备份模板/不可逆）混于单一 rollBackSql 字符串+boolean，全仓无 recoveryType 字段；但 UI/API 未发现虚假「可自动回滚」承诺（措辞为「备份模板」——好于预期） |
| 脱敏与审计 | **部分属实** | 结果构建/分页/导出三大出口已服务端强制+fail-closed（好于预期）；但未知算法→原值放行、无策略→静默放行（fail-open）；错误消息、分批证据日志（游标=真实键值）、结果缓存文件、豁免查看原值四类出口未覆盖；`updateDs` 将含 password 的完整 FO 序列化进审计正文与应用日志（具体泄漏点）。**该差距已于 2026-10-11 由 C1+C6 修复（任务 `10-11-ds-audit-credential-sanitization` + `10-11-masking-fail-closed`，C6 按用户裁决 A2+B2）**：①resultDataRead 截断单元格展开活泄漏已堵（补单值 doMask）；②未知算法命令 FULL_MASK 规范化兜底（不再崩查询、绝不输出原值，null 契约保持）；③策略绑定三态分层——悬空/失效绑定分析期拒绝查询+审计（未知策略≠明示不启用），从未绑定放行+限频观测（opt-in 契约保持，B3 全面拒绝被否决）；④豁免查看原值四处落点分级审计（root→biz_log 高频侧、DM_DAUTH_SENSITIVE/数据编辑器→op_audit HIGH，新 AuditType 四点注册×2）；⑤错误消息与分批 statusMsg/summary 数据值净化（DbErrorSanitizer 保守模式+游标 sha256 化；biz_log/checkpoint 功能性原值保留）；SQL 审计 message 双出口同源净化。`updateDs` 凭据泄漏由 C1 AuditSanitizeUtils 修复（见 logging-guidelines 约定）。端到端脱敏出口链联调验证留项目末尾统一联调。**审计链检索面已于 2026-10-13 由 C8 落地（任务 `10-13-audit-chain-enhancement`，四裁决+两裁量）**：①op_audit 四列 join 键（sql_fingerprint/ticket_id/release_id/channel，带索引）+治理族写点矩阵+sql_audit 指纹列——「可检索审计链」机械化；②「查看日志」死端点改造为审计详情端点（租户隔离+sanitization 兜底），前后值/原因/override 理由 UI 可见（裁决 3）；③NOT_BOUND 观测随 biz_log mapper 缺 AND 修复（D-1，自 initial commit 的语法错误使该观测从未落库）真正恢复，豁免/拒绝审计行增脱敏策略快照键（specId/name/bindingState）；④root 每查询留痕裁决不推翻（红线 7），累积治理走新增 op_audit/biz_log 保留期 KV+30 分钟分批清理（裁决 4）；⑤UNRESOLVED raw 错误文本 4 处扩围 DbErrorSanitizer（biz_log 分批取证原值保留不动）；⑥存量明文行按裁决 1「仅测试库直接清空」——清理手册 tests/governance/audit-legacy-cleanup.md 交付（零凭据，用户执行，记录留联调档案） |

核验确认本章「应列为 P0」的判断成立。其中 Schema 门禁 fail-closed 化与回调轮次绑定均涉及推翻既有架构裁决（「宁漏勿误」设计原则；桥接 PRD 裁决 #5「外部引擎=唯一真相源，console 仅状态守卫」），实施前需 ADR 重裁；轮次绑定改造应以钉钉桥接联调实证为输入。修复批次规划见 Trellis 父任务 `10-11-db-platform-p0-security-hardening/prd.md`（Wave 1 快赢 → Wave 2 核心 P0 → Wave 3 发布链路由 → Wave 4 运营化）。

代码核验入口（实现时应沿现有服务/DAO/执行器边界修复，不另建旁路）：

- 发布前门禁：[ProdReleaseServiceImpl.java](../../backend/clouddm-platform/cgdm-console/src/main/java/com/clougence/clouddm/console/web/service/governance/impl/ProdReleaseServiceImpl.java)、`web/component/governance/impl/ReleaseSchemaPrecheckServiceImpl.java`、`ReleaseSchemaDiffServiceImpl.java`。
- 外部审批回调：[ApprovalProviderServiceImpl.java](../../backend/clouddm-platform/cgdm-console/src/main/java/com/clougence/clouddm/console/web/component/approval/impl/ApprovalProviderServiceImpl.java)、[DbChangeTicketCommandFO.java](../../backend/clouddm-platform/cgdm-console/src/main/java/com/clougence/clouddm/console/web/model/fo/openapi/DbChangeTicketCommandFO.java)。
- 回滚能力分级：[RollbackSqlGenerator.java](../../backend/clouddm-platform/cgdm-console/src/main/java/com/clougence/clouddm/console/web/component/governance/RollbackSqlGenerator.java)；分批执行的部分成功约束见 `.trellis/spec/backend/lockfree-dml.md`（Trellis 内部材料，不随库分发）。

## 3. 数据库类型与版本支持矩阵

“CloudDM 有数据源插件”不等于“治理、预检、无锁变更、恢复均已认证”。产品页面、API 文档和配置均应以本矩阵为准，具体小版本、驱动和云厂商组合由兼容性清单维护。

| 能力 | MySQL | PostgreSQL | 验收与限制 |
|---|---|---|---|
| SQL 工作台、连接、元数据浏览 | P0 主支持 | P0 主支持 | 按平台已认证的服务端版本/驱动组合测试；角色、权限、分页、取消、超时、导出分别验证 |
| SQL 解析、基础规则审计、工单、PRE→PROD、Hash/Revision、审批、Timeline | P0 | P0 | 同一套治理不变量分别在 MySQL、PG 真库跑全链；方言规则集、标识符大小写、schema/catalog、类型和多语句解析分别校验 |
| Explain 与 DML 影响行数 | MySQL 原生估算路径 | PG 现状 DML 估算可能不支持/返回未知 | 未知值必须显示“不可估算”，不得当作 0 行；高风险 PG DML 采用规则门禁、人工确认、显式行数限制/受控执行策略 |
| 大型 DML 分批 | MySQL 8.0+ 具备路由能力 | PostgreSQL 15+ 具备路由能力 | 必须通过形态白名单、主键/唯一键检查、检查点、重复/不确定提交恢复和 lag 节流验证；不等同事务回滚 |
| 大表在线 DDL | gh-ost 路径；目标拓扑/云厂商需认证 | gh-ost 不支持 | MySQL 仅在工具前置条件满足时走 GHOST；不满足则阻断或经审批明确接受锁风险。PG 先支持安全分类和专用策略，不声称通用无锁 |
| PostgreSQL 在线结构变更 | 不适用 | 分能力逐项认证 | P1 评估 `CREATE INDEX CONCURRENTLY`、短锁 DDL 策略；P2 再评估 `pg_repack`。每种操作单独定义事务限制、失败清理、锁窗口、空间和恢复 |
| 自动回滚 | 非通用能力 | 非通用能力 | 两者均按事务性 DML、补偿 SQL、备份恢复、不可逆 DDL 分级，不承诺通用自动逆向 |
| 其他数据库（Oracle、SQL Server、各云厂商兼容库等） | — | — | 保留现有连接/查询能力不代表进入治理发布认证范围；默认不启用自动审批发布、分批或无锁执行，待独立适配和验收后加入矩阵 |

**版本策略**：一期将 MySQL 8.0、PostgreSQL 15 作为当前治理执行器的最低能力线（与现有 DML_BATCH 设计一致）；若企业线上仍有更低版本，先列出实际清单，再决定支持、只读治理或迁移豁免。不得仅按主版本号推断某云发行版行为一致。锁定产品支持的 patch/minor、JDBC 驱动、字符集/排序规则、代理/主从拓扑并做升级回归。

## 4. 目标功能与业务规则

### 4.1 访问与查询

- 使用现有用户/角色功能权限与实例资源授权；角色权限与实例资源授权分开核验。生产网络只允许 CloudDM 执行节点访问数据库，应用账号和人员账号不得绕过平台直连。
- 权限范围至少覆盖环境、实例、数据库/schema；敏感业务按需配置表/列授权。后端执行入口必须再次鉴权，不能只依赖前端菜单或按钮隐藏。
- 查询按环境配置超时、最大返回行数、并发/资源额度和导出额度；超限时有确定的取消和资源释放语义。
- 脱敏规则以执行身份/环境/字段敏感级别匹配；默认拒绝未知算法/未知敏感策略的原值输出。原值直出需独立授权并形成审计。
- 审计至少记录：主体与来源、环境/实例/库、SQL 指纹及受控原文策略、对象/动作、耗时/影响行数、成功/失败、脱敏策略、审批/工单/发布关联、执行器与重试/取消信息。凭据、连接串、访问令牌不得进入审计正文。

### 4.2 SQL 预检与优化

- 预检分别输出语法/方言解析、规则命中、对象解析、权限、影响范围估算、执行计划、DDL 锁风险、可恢复性和建议路由；每项包含 PASS/WARN/BLOCK/UNKNOWN、依据、版本/时间戳。
- 对无法解析、多语句边界不清、方言能力未知的高风险 SQL，按策略阻断，不能把“未发现风险”当成“安全”。
- SQL 优化 P1 定义为“计划解读与建议”：扫描/连接/索引/统计信息提示，显示估算来源和置信边界；优化建议默认只读。自动改写必须展示原 SQL、改写 SQL、语义差异/参数、再次 Explain 与人工确认，不自动执行。
- MySQL 与 PG 分开维护规则集及版本行为测试；例如索引并发创建、DDL 事务、默认值加列、锁行为、RETURNING、反引号/双引号、search_path 均不能用一套通用判断替代。

### 4.3 变更发布状态机

```text
SUBMITTED → PRE_CHECKED → PRE_RUNNING → PRE_SUCCEEDED
          → FROZEN_REVISION → PROD_APPROVAL_PENDING → APPROVED
          → PROD_GUARD_PASSED → RUNNING → SUCCEEDED
                                      └→ FAILED/PARTIAL_FAILED → REMEDIATION
```

- PRE 自动执行成功后生成不可变 revision：逐句 SQL/hash/version、回滚/补偿材料 hash、源/目标逻辑库映射、PRE 结果、Schema 基线、执行器及参数、规则集版本均冻结。
- 发起生产审批后不允许编辑审批载荷；任何内容、目标库、执行器、关键参数变化均生成新 revision 并重走 PRE/审批。
- 钉钉通过不是立即执行授权的全部条件。执行前再核验活动审批实例、发布对象 hash、目标资源权限、Schema 前置条件、数据库连通性和执行计划；所有校验成功才派发。
- 钉钉回调校验签名/身份、flow id、审批轮次、事件唯一 ID、当前状态；重复事件幂等，迟到/乱序/旧轮次写安全审计但不得推进状态。
- 一条生产发布的执行集必须与审批 manifest 完全一致；禁止跳过失败语句、改 SQL、换库、任意重试已成功语句。失败后按语句状态和恢复类型转处置单。

### 4.4 回滚/恢复能力分级

| 类别 | 机制 | 产品表述与门槛 |
|---|---|---|
| 事务型 DML | 单事务提交前 rollback | 仅限数据库确实支持且执行链保证全程同一事务；验证断线/超时/进程退出后最终提交状态 |
| 分批 DML | 检查点 + 重入/补偿 | 标注“可能部分完成”；继续/重跑前核实批次边界和提交结果。停止不代表撤销已提交批次 |
| 提供回滚 SQL 的 DML | 人工审阅后以新工单执行补偿 | 回滚语句与正向 SQL 一起冻结、预检和审批；执行前检查幂等性及当前数据状态 |
| MySQL GHOST DDL | 旧表保留/切回能力依工具阶段而定 | 完成切换后保留窗口、表空间、写入一致性与旧表只读/清理规则必须验证；不可称任意时刻可回滚 |
| 普通 DDL / PG DDL | 反向 DDL或备份恢复 | 明确不可逆操作、锁窗口、备份点、恢复责任人与 RTO/RPO；不满足恢复前置条件时高风险审批或阻断 |

### 4.5 无锁/低锁变更

- “无锁”改为“在线/低锁变更”产品术语，逐语句显示锁风险和预期执行器。
- MySQL 超阈值 ALTER 只有在主键/唯一键、拓扑、权限、binlog、网络、磁盘空间、工具版本等硬条件全部满足时走 gh-ost。大表但前置条件不满足要阻断或走显式风险例外，禁止静默退化为普通 ALTER。
- PostgreSQL 不复用 gh-ost。P0 支持预检识别及风险分类；P1 才逐项开放 `CREATE INDEX CONCURRENTLY` 等原生低锁语句；PG 全表结构改造或 `pg_repack` 需单独 PoC、审批、监控和恢复演练。
- 执行过程中支持可观察进度、暂停/恢复/终止、告警、任务丢失检测、重启恢复和人工接管；中止后的数据库部分状态必须可判定。

## 5. 分阶段实施步骤

### Phase 0：基线核验与范围冻结（1 个迭代）

1. 对照当前代码核验现有 13 期交付、前端入口、权限标签、数据库 migration、真实测试与实际部署包；明确哪些实现已进入当前构建产物。
2. 导出 MySQL/PG 生产版本、云厂商、拓扑、数据量级、字符集、代理、驱动、权限账号能力清单。
3. 建立能力矩阵：每个“数据库类型×版本×功能”标记 GA/受限/实验/不支持；形成签字版支持矩阵和豁免清单。
4. 建测试环境：MySQL 与 PG 各自独立实例，支持造数、故障注入、钉钉审批测试租户、专用只读/执行账号。
5. 每项差距登记 Owner、风险等级、受影响模块、回滚方案、验收证据；未确认的业务语义先形成 ADR，不直接编码猜测。

**退出条件**：真实需求与数据库版本清单确认；生产审批/回调、Schema WARN、发布路由三个高风险点均有可复现步骤和明确决策；测试环境可重复重建。

**进度注记（2026-10-11）**：第 1 条的代码核验部分已完成——三路只读调研确证六项 P0 差距（见 §2 核验状态注记），交付物盘点与实际部署包核对未做；第 2—5 条未启动，其中「签字版矩阵」「多角色共同给出指标」在本项目（单人+AI 协作形态）降维为「实测记录+用户确认」执行。

### Phase 1：生产安全闭环 P0（2–4 个迭代，按团队容量细化）

1. **Schema gate fail-closed**：区分真实 drift、无快照、元数据不可读、解析不支持、超时、权限不足；生产默认非 PASS 即 BLOCK。若需 override，要求专用权限、二次确认/审批、原因、过期时间、检查证据及完整审计；只允许对经风险评审的特定检查项放行。
2. **审批回调轮次绑定**：引入/复用 approval round 与 active external flow identity；回调强制 flow/event 标识、签名核验、状态 CAS、幂等唯一约束。测试旧实例回调、重放、乱序、拒绝后重提、重新发起审批。
3. **发布工件不可变**：明确 revision manifest 与 PROD 逐句执行计划字段；发布时对整单、逐句、回滚材料、目标映射、执行器及其参数重算 hash。不能重算/不匹配时绝不派发。
4. **生产执行器路由统一**：将 GHOST、DML_BATCH、普通执行纳入同一生产发布分段编排；组/段严格串行，失败即停，segment/task 状态可恢复；用同一不可变计划派发。当前没有认证实现的方言路径拒绝自动路由。
5. **权限/网络/密钥边界**：验证最小权限 DB 账号、PROD 网络隔离、凭据加密/轮换、日志脱敏；确保用户不能通过 API、重放请求、前端字段夹带、旧工单接口绕过治理门禁。
6. **审计与对账**：将审批事件、PRE、冻结、发布前检查、执行器任务、每条 SQL 结果、失败/重试/恢复、脱敏访问关联成可检索时间线；后台任务定期检查“发布状态与执行任务状态”不一致并告警。

**退出条件**：所有安全拒绝用例通过；任一门禁异常不能进入执行；审批载荷与执行集 100% 一致；测试环境完整跑通 MySQL、PG 正向和失败处置链路。

### Phase 2：双数据库体验与运维化 P1（2–3 个迭代）

1. SQL 预检分层结果、解释和可追踪性统一；MySQL/PG 规则包分别版本化，规则变更有回归样本。
2. 补齐 PG DML Explain/行数估算能力评估。若稳定性不足，产品展示 UNKNOWN 并采用更严格风险策略，不用 0 代替。
3. 增加优化建议：Explain 计划可读化、索引/统计信息建议、前后计划对比；只生成建议，不自动落生产。
4. 做脱敏出口清点与加固：SQL 网格、分页、导出、异步结果、WebSocket、复制/下载、错误消息、审计日志、缓存/临时文件逐条检查。
5. 完成执行可观测性：SQL/工单/发布/数据库任务关联 ID、执行时长、影响行、锁等待/复制延迟、重试、断连、任务心跳和告警看板。
6. 做值班手册：钉钉回调故障、任务卡住、部分成功、GHOST cut-over 异常、DML checkpoint 不确定、Schema drift、凭据失效的处置及升级步骤。

### Phase 3：在线结构变更能力认证 P1/P2

1. **MySQL**：按实际主流云厂商各跑 GHOST dry-run 与可丢弃测试库真实迁移；验证直连/代理、ROW binlog、复制延迟、权限、磁盘峰值、限流、cut-over 时间、暂停/终止和旧表一致性。
2. **PostgreSQL**：先评估并认证索引 `CONCURRENTLY` 的事务限制、失败残留索引清理和锁表现；再选择是否引入 `pg_repack`。所有其他 DDL 保持显式风险分类和审批，不统称无锁。
3. 给每个在线执行策略维护支持的 SQL AST 形态白名单、版本/拓扑条件、退出码映射、失败恢复和资源预算；升级工具或数据库主版本必须重跑兼容测试。

### Phase 4：企业扩展 P2（按实际需求）

评估 Liquibase/GitOps migration 仓库、应用发布 Expand→Deploy→Contract 联动、分库分表/灰度发布、跨 Region、其他数据库治理插件。每项必须有业务 Owner、预算、SLO 和独立 ADR；不影响 MySQL/PG P0 安全底线。

## 6. 回归测试体系

测试金字塔：SQL/状态机/策略单测 → DAO/API/审批契约测试 → MySQL/PG 真库集成测试 → 浏览器端到端 → 故障注入与灰度演练。现有测试类要映射到本矩阵；缺口优先补实际风险路径。按仓库规则，新增测试类需在任务中明确授权。

| 测试域 | 必测场景（MySQL 与 PG 各自执行，注明不适用项） |
|---|---|
| SQL/方言 | 注释/字符串中的分号、引号/大小写、schema 与 search_path、CTE/多语句、DDL/DML 识别、未知语法、超长 SQL、Unicode、参数化语句；解析失败对高风险动作的策略 |
| 预检/优化 | 无权限对象、缺表/缺列、索引缺失、全表 DML、Explain 超时/权限不足、估算 UNKNOWN、统计信息陈旧、规则版本变化、同 SQL 在不同方言的风险差异 |
| 访问权限 | 无实例权限、只读角色写入、越权表/列、环境错配、权限过期/撤销、API 直调、权限缓存刷新；前端隐藏不得代替后端拒绝 |
| PRE→冻结 | PRE 成功后编辑任意字符/目标库/rollback SQL/执行参数；逐句版本变化；预检通过但部分 SQL 失败；重试不重放已成功语句；冻结后任何 hash mismatch 均拒绝发布 |
| 审批 | 钉钉通过/拒绝/撤回、重复事件、旧 flow 回调、旧轮次重放、乱序回调、审批撤销与平台状态竞争、钉钉超时、审批服务不可用、重新提交审批 |
| 生产门禁 | Schema PASS/BLOCK/WARN/UNKNOWN、无快照、权限不足、数据库断连、超时、表对象无法解析、diff 变化；默认未验证不执行；合法 override 有完整证据且不能扩大放行范围 |
| 幂等/并发 | 双击确认、并发两个发布、重复 job 回调、服务重启恢复、worker 重连、DB 已提交但任务响应丢失、审批与执行状态并发变化；同一 release 不得重复执行 |
| DML 分批 | MySQL UPDATE 匹配但值未变、NULL 键、唯一键/复合键/无键、边界分页、重复批次、超时未知提交、deadlock/lock timeout、暂停/恢复/停止、复制延迟、权限探针失败；PG 大事务/锁等待/lag 不可见 |
| DDL/在线变更 | MySQL GHOST 不满足主键/权限/binlog/拓扑/空间/网络条件；大表运行中节流、暂停、终止、cut-over、旧表保留与清理；PG `CONCURRENTLY` 事务限制、失败残留和锁超时；普通 DDL 误路由必须阻断 |
| 脱敏 | 姓名/电话/证件/自定义规则、NULL/空/短值/Unicode；表格、导出、复制、异步结果、WebSocket、错误/日志；无策略/未知算法/策略异常时禁止泄漏原值；授权查看原值全量审计 |
| 回滚/恢复 | 事务提交前失败、提交后断线；补偿 SQL 过期/重复执行/影响行数不符；备份不可读/空间不足；DDL 不可逆；部分执行后选择继续/补偿/人工接管，记录最终真实数据库状态 |
| 前端流程 | 创建、校验、预发执行、revision 详情、审批、发布确认、执行进度、失败定位、Timeline、权限差异；刷新/多标签/后退、重复点击、窄屏、超长 SQL、API 401/403/500、断网恢复 |
| 升级/安装 | 空库安装、旧版升级、Flyway 重启与重复启动、配置默认值、插件未安装/加载失败；验证历史 migration 不变，只追加新 migration |

### 必需的真库端到端场景

1. **MySQL 路径 A**：提交 DDL + 纯 DML 至 PRE → 自动检查执行 → PRE 成功冻结 → 钉钉审批 → 生产门禁 → 按计划执行 → Timeline/审计可对账。
2. **PG 路径 A**：同等全链；覆盖 PG schema/search_path 与事务/DDL 语义，确认无 MySQL 专属语句泄漏。
3. **MySQL 在线 DDL**：合格大表走 GHOST 成功；另测不合格拓扑必须阻断/显式风险处理，不能转普通 ALTER。
4. **MySQL/PG 大型 DML**：分别验证批次边界、断点续跑、lag 节流和“部分成功不是回滚”的真实状态。
5. **安全失败链**：模拟 Schema 读取超时、审批旧回调、审批状态与任务状态不一致、数据库执行已成功但响应丢失，确认无重复派发/越权执行并可人工查明。
6. **脱敏出口链**：用带唯一敏感标记的测试记录，从查询、分页、导出、异步结果、异常日志和审计路径验证授权角色看不到原值；受控例外角色可见且留痕。

## 7. 全量验收标准（Go/No-Go）

### P0 上线硬门槛

- [ ] MySQL 和 PostgreSQL 分别完成受支持版本的真库主链路；报告记录确切服务端版本、驱动、部署形态、用例、日志/审计证据。
- [ ] 未审批、审批实例不匹配、旧轮次回调、Hash 不匹配、目标库不匹配、权限不足、Schema BLOCK/UNKNOWN、执行计划不支持，均为服务端拒绝，且数据库没有对应变更。
- [ ] 默认生产模式不存在“Schema 检查 WARN_SKIP 后自动继续”；任何 override 都有显式权限、审批/理由、检查证据、操作者、时间和完整审计，并可配置禁用。
- [ ] 一个审批 manifest 对应唯一不可变执行集：revision hash、逐句 hash、目标资源、执行器及参数在生产执行前后完全一致；已成功语句不被重放。
- [ ] 钉钉回调签名/flow/round/event 幂等校验通过；迟到、重复、乱序回调不能改变终态或触发执行。
- [ ] 并发确认、重复请求、平台/worker 重启、执行结果不确定等场景不会产生双重生产执行；无法自动判定时进入人工核对态，不盲目重试。
- [ ] 脱敏测试中未授权测试身份在所有已纳入范围的出口均无原值泄漏；日志与审计不包含凭据、Token。
- [ ] MySQL/PG 的执行、部分失败、取消、重试、补偿和最终库状态一致，状态机与数据库事实可对账。
- [ ] 所有 P0 阻断缺陷关闭；安全/权限/数据泄漏类无已知未接受风险；数据库兼容矩阵与禁用功能清单已发布。

### 功能与运营验收

- [ ] SQL 预检至少将问题分类为 BLOCK/WARN/UNKNOWN/PASS，给出数据库方言、规则版本和解释；UNKNOWN 不冒充安全 PASS。
- [ ] SQL 优化展示执行计划证据和人工可审阅建议；改写默认不执行，前后 SQL 与计划可追踪。
- [ ] 在线变更清楚显示执行器、前置条件、锁风险、空间预算、进度、暂停/终止状态和失败恢复路径；PG 不显示 gh-ost/通用无锁承诺。
- [ ] 回滚能力按事务、补偿、备份恢复、不可逆分类；生产变更前的恢复策略、责任人和演练记录可查询。
- [ ] 审计可按用户、环境、实例、工单、revision、release、审批 flow、SQL 指纹检索；可导出并保留策略符合公司合规要求。
- [ ] 角色验收覆盖开发、DBA/审核人、发布确认人、安全审计员、平台管理员；无权限入口均由服务端拒绝。
- [ ] 灰度 runbook、值班告警、备份恢复、回滚/止血开关、数据源凭据轮换及升级流程已演练。

### 性能与可靠性指标的落定

在试点前由平台、DBA 和业务共同给出并签字：预检 P95、查询超时与最大结果集、并发执行上限、审批回调恢复时限、任务检测/恢复时限、Timeline 查询时延、审计保留期、RPO/RTO、GHOST 磁盘峰值和 cut-over 锁窗口。以实际数据量与生产拓扑压测，不在没有基线时预填拍脑袋数字。

## 8. 灰度、回滚与上线策略

1. 先在隔离测试库验证，再选一个非关键 MySQL 与一个非关键 PG 业务库试点；只开放只读和低风险变更，随后扩大用户/库范围。
2. 在线变更按数据库厂商与拓扑逐类开启，不做全局一键宣称支持。每批执行后核验锁等待、延迟、磁盘、错误率、脱敏、审计和执行状态。
3. 生产安全门禁从 fail-closed 上线；兼容例外采用明确的受限 override，不通过关闭全部门禁解决历史数据缺失。
4. 止血开关应停止新建相关执行，不应丢失已运行任务状态；禁止通过回退代码造成工单、发布单和数据库事实不一致。
5. 每个灰度批次满足预设观测窗口与业务指标后方可扩容；触发条件、回退负责人和人工接管步骤必须在执行前确认。

## 9. 交付清单与责任分工

| 角色 | 必须交付 |
|---|---|
| 平台后端 | fail-closed 门禁、审批轮次幂等、冻结执行计划/分段派发、权限与状态一致性、结构化审计 |
| 前端 | 风险与 UNKNOWN 可解释展示、审批/发布对象只读冻结、进度/失败处置、脱敏与恢复能力边界清晰 |
| DBA | MySQL/PG 版本与拓扑矩阵、规则阈值、账号最小权限、在线变更策略、备份与恢复演练 |
| 安全/合规 | 数据分类与脱敏策略、生产网络边界、原值查看例外、审计保留和审计访问权限 |
| 测试 | 双数据库测试环境、回归矩阵、真库全链/故障注入、证据包与覆盖缺口签署 |
| 运维 | 监控/告警、凭据轮换、部署升级、止血、任务恢复、值班和事故演练 |
| 产品/业务 Owner | SQL 优化边界、风险接受规则、PRE 验证定义、审批责任链、SLO 与试点范围 |

最终发布包至少包含：版本化支持矩阵、需求/ADR 决议、迁移与升级验证、自动化测试结果、MySQL/PG 真库证据、前端复测流程、权限/脱敏检查、回滚演练记录、灰度 runbook、未关闭风险与书面豁免。

## 10. 关联项目材料

- 治理平台愿景与需求分析：`.claude/READY_PLAN.md`（本地内部材料，不随库分发）
- [数据库变更治理设计 Spec](../spark/2026-09-06-clouddm-db-change-governance-design.md)
- [治理期次交付对照表](phase-delivery-map.md)
- [灰度上线 Runbook](rollout-runbook.md)
- 无锁 DML 设计与测试契约：`.trellis/spec/backend/lockfree-dml.md`（Trellis 内部材料，不随库分发）
- MySQL gh-ost 在线 DDL 需求与验收：`.trellis/tasks/archive/2026-10/09-17-online-ddl-activation/prd.md`（Trellis 归档材料，不随库分发）
- P0 差距代码核验报告（2026-10-11）：`.trellis/tasks/10-11-db-platform-p0-security-hardening/research/p0-gap-verification.md`（Trellis 内部材料，不随库分发）

