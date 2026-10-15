# UAT 验收集 · 总纲(README)

> 长期有效文档,不按执行日期生成报告(纪律沿用 tests/frontend/README.md L3-4)。
> 定位:C9「全功能 UAT 验收集」统一载体,回答两个问题——**结果和需求是否一致?(→ traceability-matrix.md)测试过程能否验收各项功能?(→ 九域用例 + integration-scenarios.md)**。
> 需求源:父任务 `10-11-db-platform-p0-security-hardening` prd §3 Wave 4 收口行;计划文档 `docs/governance/internal-db-platform-improvement-plan.md` §6 回归测试体系 + §7 全量验收标准。
> 证据底座:`.trellis/tasks/10-14-uat-acceptance-suite/research/c9-uat-survey.md`(只读盘点,行号基线 HEAD=9a0e6352)。
> **执行时点 = 项目末尾统一联调**(用户 2026-10-11 裁决,父 prd §5):本目录所有证据字段当前全部留位,任何 PASS/FAIL/日期在统一联调执行时才回填。

---

## 1. 目录与九域索引

| 文件 | 内容 | 状态 |
|---|---|---|
| `README.md` | 本总纲:索引+口径+Runbook+映射表 | M1 交付 |
| `traceability-matrix.md` | 需求追踪矩阵三件套:C 级 12 行主表 + 计划 §7 硬门槛 17 条对照表 + R 级 91 行附录 | M1 交付 |
| `integration-scenarios.md` | 联调场景汇总:D3 联调债 14 项 + C7 真库六场景成文 + 真库场景 5/6 + 九迁移首启幂等 + 桥接 M5/5A 索引 | M1 交付 |
| `e1-datasource.md` | E1 数据源管理(C1 审计净化验收/凭据轮转后回归/CRUD 冒烟) | M2 交付(8 用例,F1 区间 6~10) |
| `e2-query-masking.md` | E2 查询与脱敏(C6 五类出口每类 ≥2 + 真库场景 6 UI 面展开 + C3 诚实标注) | M2 交付(18 用例,F1 区间 15~20) |
| `e3-ticket-approval.md` | E3 工单与审批(C3/C5 round 观察面/C10 恢复类型标注;prod-verification Chain One/Two 收编索引) | M2 交付(16 用例,F1 区间 15~20) |
| `e4-release-chain.md` | E4 发布链(最大域:C4 撞闸+override/C7 门禁序+冻结计划/override 双表隔离;安全底线+DENY+N1~N6 收编索引) | M2 交付(21 用例,F1 区间 20~25) |
| `e5-lockfree.md` | E5 无锁变更(两手册引用 + 发布链 GHOST 重放/DML_BATCH 阻断交叉 e4 + 三点明示/gh-ost 旧表文案零削弱核验) | M2 交付(13 用例,F1 区间 12~18) |
| `e6-diagnosis.md` | E6 诊断与会话运维(无 C 波次改动,冒烟级回归) | M2 交付(6 用例,F1 区间 4~6) |
| `e7-dingtalk-bridge.md` | E7 钉钉桥接(dingtalk_bridge.md 章节路由索引+6 项增量核对,不复制) | M2 交付(索引+6 增量核对,F1 口径=收编手册不重写) |
| `e8-audit-permission.md` | E8 审计与权限(C8 四列/详情端点/retention/导出 15 列 + ROLLBACK_MATERIAL 行 + 五角色矩阵 + GOV_*_OVERRIDE 双标签拒绝面) | M2 交付(16 用例,F1 区间 15~20) |
| `e9-openapi.md` | E9 开放 API(15 端点全集×签名/租户隔离/守卫三维抽查;additive 面 externalRound/skipOutbound/ledger-list;5A 交叉 e7) | M2 交付(11 用例,F1 区间 10~15) |

九域划分出处:survey E 节(E1~E9 入口清单,9/9 域均具备「前端页面+后端 API+权限点」三要素)。

**用例总数对账(M2.10)**:8+18+16+21+13+6+6+16+11 = **115 条**(含 e2 E2-12a),落于 F1 总量级 ~110-135 区间;每域均达 F1 区间下限(机械计数见各文件用例编号)。既有手册承载的场景(手册 §1~§10 约 60 场景+prod-verification Chain/底线/DENY+两无锁契约手册套件)不计入本数(引用不复制,裁决 4)。交叉引用双向可达口径(A8):每域文件头部「矩阵对应」节→矩阵 C 级/R 级行,矩阵「验收方式②」列→e 域编号;IS-*/MIG-* 引用↔integration-scenarios 定义;手册章节引用↔dingtalk_bridge/prod-verification 实存章节。

## 2. 既有手册收编索引(只索引不复制,单一权威源)

| 手册 | 位置 | 与 UAT 关系 |
|---|---|---|
| 钉钉桥接联调手册 | `tests/governance/dingtalk_bridge.md`(§1~§9 + §5A + §10 验收对照 + 附录记录表) | E7 域执行载体;桥接 M5 三途径联调 + C5 轮次绑定 5A 场景的权威源 |
| 真库验证清单 | `tests/governance/prod-verification.md`(Chain One/Two + 安全底线 8 条 + DENY 矩阵 12 case + 双方言 + 豁免清单 + C 波次增量门禁抽查) | E3/E4 域真库载体;真库场景 1~4 的既有承载 |
| 审计存量清理手册 | `tests/governance/audit-legacy-cleanup.md` | 用户执行,记录留联调档案(O-C1-2 关闭动作,D3 #7⑤) |
| 无锁 DML 测试契约 | `tests/dml/lockfree_dml.md` | E5 域工单链载体(引用不重写) |
| gh-ost 测试契约 | `tests/ghost/lockfree_ddl.md` | E5 域工单链载体(引用不重写) |
| 前端流程文档 30 篇 | `tests/frontend/`(12 业务目录;TEMPLATE.md 字段纪律) | 各 E 域浏览器面交叉引用;列宽验收载体 = `tests/frontend/ui/table_column_resize.md` |

## 3. 矩阵口径说明(裁决 1)

`traceability-matrix.md` 三件套:

1. **C 级 12 行主表**(主口径):C1~C10 + 桥接 + 列宽;六列 = 需求项 | 需求源(G 映射)| 实现状态 | 实现位置(commit+关键文件)| 验收方式(三分类)| 验收证据(五元组留位)。与父 prd 波次表/21 commit/归档目录一一对应(survey B1/B2)。
2. **计划 §7 硬门槛 17 条对照表**:P0 上线硬门槛 9 条 + 功能与运营验收 7 条 + 性能指标落定 1 条,逐条映射到 C 级行与验收载体(父任务验收标准 A1 的机械化)。
3. **R 级 91 行附录**(子口径下钻):按任务分节,与各任务 prd §Requirements 逐行对账(C1 5 + C2 5 + C3 6 + C4 11 + C5 11 + C6 8 + C7 10 + C8 9 + C10 9 + 桥接 12 + 列宽 5 = 91);每行 = R 编号 | 摘要 | 验收方式 | 证据指针。

G 级(6 差距)不单设表——由 C 级「需求源(G 映射)」列表达(G4/G6 等「部分属实」差距横跨多任务,单行无法表达实现位置,survey F2)。

## 4. 验收方式三分类边界(裁决 2;survey F3 原文口径)

| 类别 | 边界判据 | 引用形态 |
|---|---|---|
| ① 自动化测试计数引用 | 已有 JUnit pin 覆盖且断言强度经 check 核验的行为——UAT 不重复手工验证,只引用计数与类名 | 「console 1876 中的 SchemaGateOverrideServiceTest 20 用例;commit 9b4d70ef」(计数=当前基线 XML 实测;C4 交付时点为 19,C8 追加 1) |
| ② UAT 手工用例 | UI 可见行为/措辞纪律/多角色权限显隐/浏览器面——自动化不可达或成本过高 | e1~e9 各域用例编号;tests/frontend/ 九套件矩阵选套 |
| ③ 联调场景 | 需真库/真桥接/真钉钉/迁移首启/用户执行的操作 | integration-scenarios.md 场景编号(IS-*/MIG-*)+对应手册章节 |

**硬判据:凡 prd 中「留统一联调/留项目末尾」字样一律归③,不得以①②冲抵。** 同一需求项可多类并存(如 C5 = 自动化 pin + 5A 联调)。

自动化基线(2026-10-14,HEAD=9a0e6352,survey C1 XML 独立复算):console **1876 tests / 0 fail / 0 skip**(124 类);全后端 **2438 / 10 skip(插件模块预存)/ 0 fail**(22 模块 183 suites)。

## 5. 证据五元组留位(裁决 3;survey F4)

每用例/每矩阵行/每联调场景带统一留位,执行时才回填:

```
执行日期:____ | 执行人(用户/AI):____ | 结果(PASS/FAIL/SKIP+原因):____ | 证据指针(截图路径/SQL 输出文件/JUnit XML 计数/commit):____ | 豁免记录(引用手册附录 A 模板):____
```

表格内压缩形态:`____|____|____|____|____`(顺序同上)。

## 6. 执行 Runbook(统一联调时的使用顺序)

1. **迁移首启**(integration-scenarios.md §4,MIG-1~9):备份元数据库 → 首启验证九迁移 SUCCESS + 表/列/索引实存 → 二次重启验证幂等;
2. **联调场景**(integration-scenarios.md §1~§3、§5):D3 联调债 14 项 → C7 真库六场景(IS-C7-1~6)→ 真库场景 5/6(安全失败链+脱敏出口链)→ 桥接 M5 三途径 + 5A(dingtalk_bridge.md 为执行载体);真库场景 1~4 走 prod-verification.md 既有 Chain One/Two;
3. **UAT 手工用例**(e1~e9):按域执行,浏览器面复用 tests/frontend/ 流程文档;
4. **证据回填**:每执行一项回填五元组;豁免走手册附录 A 模板;执行记录表沿用 dingtalk_bridge.md 附录 B 形态;
5. **硬门槛对账**(traceability-matrix.md §2):17 条逐条 Go/No-Go,任一安全拒绝用例不通过则父任务不收官(父 prd A1);
6. **收尾动作**:桥接 A1~A9 勾选 + 09-16 归档评估(D3 #1)、审计存量清理留档(D3 #7⑤)、性能指标「实测记录+用户确认」(D3 #14)、开放观察项 18 条逐项处置评估(survey D1)。

## 7. 计划 §6 测试域 ↔ 九域 ↔ 承载映射(裁决 5)

计划文档 §6 矩阵名义「12 测试域」,表实际 13 行(L177-191);映射如下。**升级/安装域不设第十域文件——「九迁移首启+重跑幂等」手工用例并入 integration-scenarios.md §4**(B5 迁移清单为载体)。

| 计划 §6 测试域 | 自动化承载(survey C5) | UAT 九域/联调承载 |
|---|---|---|
| SQL/方言 | sql-mysql 63 + sql-postgres 50 + cg-detectrule 42 | e2(工作台展示面) |
| 预检/优化 | RiskAssessorTest 37 + DmlBatchRoutePrecheckTest 33 + FullWidthSqlLintTest | e3(预检黄旗/分批公告/无键拒绝) |
| 访问权限 | SecurityAuthTest + 各 *AuthTest | e8(五角色矩阵+拒绝面)、e9(签名/租户隔离) |
| PRE→冻结 | ProdRelease* 6 类 + ProdReleaseStateMachineTest | e3/e4 + prod-verification Chain One |
| 审批 | ExternalCommandTest 27 + ApprovalFlow* 族 | e3 + e7(桥接/5A) |
| 生产门禁 | SchemaGate 族(含 SchemaGateOverrideServiceTest 20)+ GovRouteOverrideServiceTest 21 | e4 + integration-scenarios(IS-C7-1~6)+ prod-verification(安全底线 8 条 + DENY 12 case + C 波次增量) |
| 幂等/并发 | ApprovalSyncIdempotencyTest + DispatchJobGuardTest + sweep 族 | integration-scenarios(IS-C7-4 sweep 重驱;真库场景 5) |
| DML 分批 | DmlBatch* 12 类 | e5 + tests/dml/lockfree_dml.md + 真库场景 4 |
| DDL/在线变更 | Ghost* 10 类 + plus-online-ddl 65 | e5 + tests/ghost/lockfree_ddl.md + 真库场景 3 |
| 脱敏 | plus-sec-rules 49 + DbErrorSanitizerTest 17 | e2 + 真库场景 6(integration-scenarios §3) |
| 回滚/恢复 | RecoveryTypeInferTest 42 + RollbackSqlGeneratorTest 27(含 16 逐字节 pin,当前 XML 实测) | e3(C10 Tag/未演练/折叠区) |
| 前端流程 | tests/frontend/ 30 文档(人工) | e1~e9 浏览器面交叉引用 |
| 升级/安装 | **无自动化承载**(survey C5/G10) | integration-scenarios.md §4(MIG-1~9 首启+重跑幂等) |

## 8. 凭据纪律声明(A4,红线)

- 密码/token/AK-SK/私钥/桥接凭据**不写入本目录任何文档、截图、记录表、证据文件**;
- 验证 SQL 只写口径不回显值(先例:audit-legacy-cleanup.md、dingtalk_bridge.md 红线 3);
- curl/接口验证凭据一律环境变量注入(dingtalk_bridge.md 红线先例);
- 生产环境禁破坏性测试;真库验证仅测试环境(腾讯云/阿里云 MySQL 8.0 + PG 15.0);
- 历史归档任务 prd 不回改(裁决 6):C6/列宽归档 prd 未回签为历史记录,矩阵以「证据双源」注记如实表达。
