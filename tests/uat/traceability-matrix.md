# 需求追踪矩阵(C9 / 三件套)

> 长期有效文档;证据字段全部留位,统一联调执行时回填(红线 3)。口径说明与三分类边界见 `README.md` §3/§4;联调场景编号(IS-*/MIG-*)见 `integration-scenarios.md`。
> 三件套:§1 C 级 12 行主表(主口径)→ §2 计划 §7 硬门槛 17 条对照表(父任务 A1 机械化)→ §3 R 级 91 行附录(子口径下钻)。
> 数字基线(2026-10-14,HEAD=9a0e6352):console 1876/0 fail/0 skip(124 类);全后端 2438/10 skip/0 fail;commit 对账区间 b1915462..9a0e6352(15 笔)+桥接 6 笔=21 笔(survey B1)。

---

## 1. C 级 12 行主表

五元组留位格式(每格)= `日期____|执行人____|结果____|证据指针____|豁免____`。

| 需求项 | 需求源(G 映射) | 实现状态 | 实现位置(commit+关键文件) | 验收方式(三分类) | 验收证据(五元组留位) |
|---|---|---|---|---|---|
| C1 `ds-audit-credential-sanitization` | G6(updateDs 密码入审计) | 已落库归档(2026-10-11);归档 prd A1~A5 全勾+check 签署 | `e5ce2b6e`;web/util/AuditSanitizeUtils.java(双层敏感键判定)+DmDsController.updateDs/RdpUserManagerController.addSubAccount 两泄漏点 | ①自动化(console 1604 全绿;普查 ~35 调用点)+②UAT(e1 审计详情掩码显示) | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |
| C2 `rejected-command-audit` | G2(被拒命令零审计) | 已落库归档;prd A1~A5 全勾+check 签署(含 1 处自修:cancel 日志补齐) | `be0f2f8c`;GovEventType.EXTERNAL_COMMAND_REJECTED+REQUIRES_NEW 审计+DbChangeOpenApi 三端点入口日志 | ①自动化(ExternalCommandTest;1607)+③联调(手册 5A.8 验证,IS-D3-2) | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |
| C3 `pg-estimation-unknown` | G4(PG 估算降级伪装 0) | 已落库归档;prd A1~A6 全勾+逐项注记 | `53b5e5cf`;DmlExplainPreInitHandler(PRE_INIT 聚合 nullable)+RiskAssessor(评级行为逐字节不变)+前端「行数不可估算」Tag+TABLE_SCALE 公告口径 | ①自动化(1614)+②UAT(e3 诚实标注) | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |
| C4 `schema-gate-fail-closed` | G1(WARN_SKIP 仍可派发) | 已落库归档(ADR-011 implemented);prd A1~A9 全勾+check PASS | `9b4d70ef`+`61993ca6`(ADR);PrecheckConclusion UNVERIFIABLE 第四态+17 类分类反转+dm_gov_schema_gate_override(V202610110001)+GOV_SCHEMA_GATE_OVERRIDE+两开关+三 gate 点孪生 | ①自动化(1654;SchemaGateOverrideServiceTest 19)+②UAT(e4 override 显隐/事件时间线)+③联调(撞闸样本+override 实操 IS-D3-3;O-C4-1 legacy 样本观察) | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |
| C5 `approval-round-binding` | G2(回调缺轮次绑定)+O-C2-1 | 已落库归档(ADR-012 implemented);prd A1~A9 全勾+check PASS 零自修 | `4c31d85c`;五守卫链(+FLOW_ID_MISMATCH/+ROUND_MISMATCH)+dm_approval.approval_round(V202610110002)+resubmit round+1+出站 context.round+strict 档 KV(govExternalCommandStrictFlow 默认 false) | ①自动化(1672;ExternalCommandTest 27)+③联调(手册 5A.0~5A.10,IS-D3-2;strict 灰度 IS-D3-13) | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |
| C6 `masking-fail-closed` | G6(脱敏出口一致性) | 已落库归档;**归档 prd A1~A8 未回签(裁决 6:历史记录不回改)——证据双源=commit a9599a46 正文(M1~M8 全交付)+父 prd W2 注记(「A2+B2 implemented,M3 DANGLING 收窄为 spec 级经 check 裁决成立」)+计划文档 L53 注记** | `a9599a46`;ResultSetRServiceProvider.resultDataRead 堵漏+未知算法 FULL_MASK+绑定三态(NOT_BOUND/DANGLING/VALID)+豁免审计(ORIGINAL_VIEW/MASKING_REJECTED)+cursor sha256+DbErrorSanitizer 六缝 | ①自动化(console 1697+plus-sec-rules 49)+②UAT(e2 五类出口每类 ≥2)+③联调(真库场景 6=IS-MASK-6;DANGLING AutoExec 行为变更 IS-D3-4;NOT_BOUND 落库 IS-D3-5) | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |
| C7 `release-chain-frozen-routing` | G3(PROD 绕过冻结计划直发) | 已落库归档(三里程碑;ADR-013 全交付);implement.md M1~M3 全 [x]+三次 check PASS | `380d8d6c`(M1 manifest)+`478e4f8e`(M2 段感知派发)+`f6180cb7`(M3 drift gate+route override);dm_prod_release_stmt_segment(V202610120001)+ReleasePlanHashUtils+dm_gov_route_override(V202610130001)+GOV_ROUTE_OVERRIDE+三派发点门禁序 hash→plan→precheck→diff+前端冻结计划区块 | ①自动化(1711/1732/1763;ReleaseSegmentDispatch/Propagate+GovRouteOverrideServiceTest 20)+②UAT(e4 冻结计划区块/横幅/override 显隐)+③联调(**真库六场景 IS-C7-1~6 已由 C9 成文=G1 缺口修复**;IS-D3-6) | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |
| C8 `audit-chain-enhancement` | G1/G2/G6 审计收口+O-C1-2 | 已落库归档;implement.md M1~M5 全 [x]+check PASS(7 偏差全闭环) | `a36cea1f`;V202610130002(三索引)+D-1 缺 AND 修复+两表 retention KV+V202610130003(四列+sql_audit 指纹列)+logOperation 11 参 additive 重载(59 处零改动)+AuditChannel URI 推断+GET /api/entry/audit/detail+V202610130004(生成列唯一)+清理手册 | ①自动化(1805;AuditChainColumnTest 13+AuditDetailQueryTest 8+AuditRetentionTest 9+DbErrorSanitizerTest 17〔C6 建 16,C8 追加 1〕)+②UAT(e8 详情弹窗/导出 15 列/过滤下拉)+③联调(三迁移首启 MIG-6~8+详情端点实测+清理手册用户执行,IS-D3-7) | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |
| C9 `uat-acceptance-suite` | 全功能 UAT 收口(父 prd §3 W4;用户 2026-10-12 定位裁决;**显式收编原 `e2e-security-tests` 范围=真库场景 5/6**) | 进行中(M1 矩阵+联调场景+缺口修复 → M2 九域用例) | 本目录 `tests/uat/`(README+traceability-matrix+integration-scenarios+e1~e9) | ②本验收集自身即载体;执行=统一联调 | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |
| C10 `recovery-type-grading` | G5(回滚缺分级标注) | 已落库归档(M1+M2);implement.md 全 [x]+check PASS | `3ace7316`(M1)+`9a0e6352`(M2);RecoveryType 7 值枚举+V202610140001(零回填)+RecoveryTypeInferrer 纯函数(IRREVERSIBLE 扩数据毁灭类 8 值)+RollbackSqlGenerator 双方言 per-group(MySQL 16 pin 逐字节)+五写点(含 D7 三分支)+读时派生+ROLLBACK_MATERIAL 审计+详情 Tag/恒常未演练/逐语句折叠区+QueryForm OA 标注 | ①自动化(1872/1876;RecoveryTypeInferTest 42+RollbackSqlGeneratorTest 27〔含 16 前-C10 逐字节 pin〕)+②UAT(e3 Tag 色阶/未演练/折叠区/措辞纪律)+③联调(V202610140001 首启 MIG-9+OA 模板「恢复类型」控件对齐+PG 模板真库渲染抽查,IS-D3-8) | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |
| 桥接 `dingtalk-approval-bridge`(10-09) | 父 prd §3(09-16 欠账;裁决 #1~#3) | **代码落库、验收待联调**(task.json in_progress;A1~A9 未勾=预期状态压末尾联调,非缺陷——survey G8;R9 联调/R10 09-16 归档未执行) | 6 笔:`ba6bd748`(M1 出站基建)+`1ec7ddf7`(M2 台账开放读)+`d5386a86`(M2 直连 deprecated)+`11418b0f`(M3 前端三面)+`96f2e70e`(M4b skipOutbound)+`67afc8ee`(M4 文档);DingBridgeOutboundService+DbChangeOpenApi ledger/list+tests/governance/dingtalk_bridge.md | ①自动化(桥接七套 106 钉,当前基线——M4b 时点 102,C5 additive +4;DingBridgeOutboundServiceTest 29+GovOpenApiServiceImplTest 30+ResubmitOutboundResetTest 5)+②UAT(e7 索引+前端三面)+③联调(手册 §1~§9+§10 A1~A9+5A 全表,IS-D3-1/2) | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |
| 列宽 `table-column-resize`(10-10) | 用户需求 2026-10-10(无 G 映射) | 已落库归档;**归档 prd A1~A6 未勾选(裁决 6:不回改)——浏览器验收保留为联调债 IS-D3-12,执行载体 tests/frontend/ui/table_column_resize.md(9 suites)已交付** | `b1915462`;convertTableColumns resizable 透传+DmTable 受控 widthOverrides+双击恢复+无持久化(裁决);61 处全局 Table 单点覆盖 | ②UAT(table_column_resize.md 九套件浏览器执行)+③联调(IS-D3-12 执行后回填证据) | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |

**口径注记(裁决 1/6;survey G 节)**:
- **C6 行(G2)**:归档 prd 未回签为历史记录;实现状态与验收证据以 commit `a9599a46` 正文+父 prd W2 注记+计划文档 L53 注记三处为准(证据双源口径)。
- **列宽行(G6)**:父 prd 统一联调裁决节记「① 列宽任务归档 ✅ 已完成」指归档动作;浏览器验收(A5)执行状态无回签,保留为联调债 IS-D3-12。
- **桥接行(G8)**:实现状态措辞固定为「代码落库、验收待联调」(红线 7),不虚报「已验收」。
- **E9 引用处(G7)**:开放 API 端点全集=**15 个 mapping**(DbChangeOpenApi 实测;docs/governance/openapi-orchestration.md §2 标「14 个」因 tickets/detail 在表外 §5.5 单注——e9 域用例以 15 端点为全集并注明口径)。
- **观察项(G3)**:O-C7-3(dm_mon_biz_log 类观测,联调后评估)已于 2026-10-14 补登父 prd 观察池;池登记 24 条=开放 **18**/已闭 6(survey D1)。

## 2. 计划 §7 全量验收标准 17 条对照表(父任务 A1 机械化)

出处:`docs/governance/internal-db-platform-improvement-plan.md` §7(L202-228);复选框全空=待统一联调执行,本表不改变其状态,只给出「逐条→C 级行→验收载体」映射。

### 2.1 P0 上线硬门槛(9 条)

| # | 条目(摘要) | 对应 C 级行 | 验收载体 | 证据(五元组留位) |
|---|---|---|---|---|
| P0-1 | MySQL 与 PG 分别完成受支持版本真库主链路(版本/驱动/部署形态/用例/日志审计证据记录) | 全部(链路底座) | prod-verification.md Chain One/Two+双方言节;IS-D3-11(真库场景 1/2 索引至既有手册) | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |
| P0-2 | 未审批/审批实例不匹配/旧轮次回调/Hash 不匹配/目标库不匹配/权限不足/Schema BLOCK-UNKNOWN/执行计划不支持=服务端拒绝且库无变更 | C4+C5+C7(+C2 拒绝留痕) | prod-verification 安全底线 8 条+DENY 12 case+**C 波次增量门禁抽查 N1~N6(G9)**;IS-C7-5;手册 5A.3/5A.5 | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |
| P0-3 | 默认生产模式无「WARN_SKIP 后自动继续」;override 有显式权限/理由/证据/操作者/时间/完整审计且可配置禁用 | C4 | prod-verification 增量 N1/N2;IS-D3-3(撞闸→grant→消费全链实操);两开关 govReleaseSchemaPrecheckBlock/DiffBlock | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |
| P0-4 | 审批 manifest 对应唯一不可变执行集:整单/逐句 hash、目标资源、执行器参数前后一致;已成功语句不重放 | C7 | IS-C7-1/IS-C7-2(SUCCESS 段永不重放);prod-verification 增量 N4(plan drift)+底线 2/底线 7 | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |
| P0-5 | 钉钉回调签名/flow/round/event 幂等校验;迟到/重复/乱序回调不改终态不触发执行 | C5+C2+桥接 | dingtalk_bridge.md §5A(5A.3 旧 flow=核心漏洞场景/5A.5 旧轮次/5A.6 幂等);IS-D3-2 | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |
| P0-6 | 并发确认/重复请求/重启/结果不确定不产生双重生产执行;无法自动判定进入人工核对态 | C6(UNKNOWN→UNRESOLVED)+C7(sweep)+B7 底座 | IS-SEC-5(安全失败链:执行成功但响应丢失);IS-C7-4(sweep 重驱);tests/dml/lockfree_dml.md(UNRESOLVED 人工裁决) | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |
| P0-7 | 脱敏:未授权身份在所有纳入范围出口无原值泄漏;日志与审计不含凭据/Token | C6+C1+C8 | IS-MASK-6(脱敏出口链全出口);e1(审计净化);prod-verification 增量 N6(详情端点 sanitization) | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |
| P0-8 | MySQL/PG 执行/部分失败/取消/重试/补偿与最终库状态一致;状态机与数据库事实可对账 | C7+C10+B6/B7 底座 | 真库场景 3/4(IS-D3-11 索引至 lockfree_ddl/lockfree_dml 手册);IS-C7-2;IS-SEC-5;e5;prod-verification Step 9/Timeline | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |
| P0-9 | 所有 P0 阻断缺陷关闭;安全/权限/泄漏类无已知未接受风险;兼容矩阵与禁用功能清单发布 | 父任务收官项 | 本对照表汇总 Go/No-Go+开放观察项 18 条逐项处置评估(survey D1)+README Runbook 第 6 步 | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |

### 2.2 功能与运营验收(7 条)

| # | 条目(摘要) | 对应 C 级行 | 验收载体 | 证据(五元组留位) |
|---|---|---|---|---|
| F-1 | 预检分类 BLOCK/WARN/UNKNOWN/PASS+方言/规则版本/解释;UNKNOWN 不冒充安全 PASS | C4(UNVERIFIABLE 第四态)+C3(估算 UNKNOWN) | e3/e4 用例;IS-C7-1 前置 | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |
| F-2 | SQL 优化展示执行计划证据与人工可审阅建议;改写默认不执行、前后 SQL 与计划可追踪 | 既有功能面(非 C 波次) | e2/e3 冒烟用例(M2) | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |
| F-3 | 在线变更显示执行器/前置条件/锁风险/空间预算/进度/暂停终止/失败恢复路径;PG 不显示 gh-ost 承诺 | B6 底座+C10(GHOST_OLD_TABLE 标注) | e5 用例+tests/ghost/lockfree_ddl.md;措辞纪律核验(C10 M2.5 grep 口径) | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |
| F-4 | 回滚能力按事务/补偿/备份恢复/不可逆分类;恢复策略/责任人/演练记录可查询 | C10(7 值分级+恒常「未演练」诚实标注;真演练机制=O-C10-1 独立任务) | e3 用例(Tag 色阶/折叠区/未演练双处);O-C10-2 试点期数据收集 | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |
| F-5 | 审计按用户/环境/实例/工单/revision/release/审批 flow/SQL 指纹检索;可导出;保留策略合规 | C8(四列+指纹 join 键+retention KV) | e8 用例;IS-D3-7(详情端点/导出 15 列/O-C8-3 下游兼容联调确认) | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |
| F-6 | 角色验收覆盖开发/DBA 审核人/发布确认人/安全审计员/平台管理员;无权限入口服务端拒绝 | 全部权限面(C4/C7 GOV_*_OVERRIDE 双标签隔离为重点) | e8 五角色矩阵(计划 L223)+prod-verification 增量 N3(双表隔离服务端拒绝) | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |
| F-7 | 灰度 runbook/值班告警/备份恢复/回滚止血开关/凭据轮换/升级流程已演练 | 运营面(桥接止血开关+审计清理+迁移) | dingtalk_bridge.md §9(运维处置)+audit-legacy-cleanup.md+MIG-1~9+IS-D3-14 | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |

### 2.3 性能与可靠性指标落定(1 条)

| # | 条目(摘要) | 对应 C 级行 | 验收载体 | 证据(五元组留位) |
|---|---|---|---|---|
| PF-1 | 试点前共同给出并签字:预检 P95/查询超时/最大结果集/并发上限/回调恢复时限/任务检测恢复时限/Timeline 时延/审计保留期/RPO-RTO/GHOST 磁盘峰值/cut-over 锁窗口(本项目降维=「实测记录+用户确认」,父 prd 裁决) | 跨任务(retention 数值=C8 裁决 4;桥接 sweep=手册 §9) | IS-D3-14(性能指标落定场景);不预填拍脑袋数字 | 日期____\|执行人____\|结果____\|证据指针____\|豁免____ |

## 3. R 级 91 行附录(与各任务 prd §Requirements 逐行对账)

> 验收方式列:①=自动化计数引用/②=UAT 手工/③=联调(硬判据:prd「留统一联调/留项目末尾」字样一律③)。证据指针=commit+测试类/文档。摘要为编写时压缩,权威原文=各任务 prd §Requirements(归档路径 `.trellis/tasks/archive/2026-10/<task>/prd.md`;桥接=活跃区 `10-09-dingtalk-approval-bridge/prd.md`)。

### 3.1 C1 ds-audit-credential-sanitization(5 行;prd R1~R5;commit e5ce2b6e)

| R | 摘要 | 验收方式 | 证据指针 |
|---|---|---|---|
| R1 | updateDs 审计 obj 不含凭据明文;保留非敏感字段排障价值(键值级 `"******"` 掩码,非 obj="" 一刀切) | ① | e5ce2b6e;console 1604;C1 prd A2 前后对照 |
| R2 | 敏感键清单完整:@ConfigDef(isSecret) 6 键精确清单+键名模式兜底(代码证据,不凭猜测) | ① | AuditSanitizeUtils;C1 prd A3 清单 |
| R3 | 双出口同防:logInfo(DB 65535 截断)+log.info(应用日志)序列化前净化生效 | ① | C1 check 结论 3(两出口同源同串) |
| R4 | 同类入口普查:~35 logOperation 调用点核查;同形态泄漏(addSubAccount 解密后明文)一并修复 | ① | C1 prd A4 普查清单 |
| R5 | 功能不变:业务链路用原 FO;logOperation 签名/语义零改动(调用方选择性接入) | ①+②(e1 数据源 CRUD 冒烟) | e5ce2b6e;e1 域(M2) |

### 3.2 C2 rejected-command-audit(5 行;prd R1~R5;commit be0f2f8c)

| R | 摘要 | 验收方式 | 证据指针 |
|---|---|---|---|
| R1 | 守卫拒绝路径落库 EXTERNAL_COMMAND_REJECTED 事件(REQUIRES_NEW 不随拒绝回滚丢失;eventData 白名单) | ① | be0f2f8c;ExternalCommandTest;1607 |
| R2 | 拒绝语义零改变:仍 throw/零状态副作用;审计失败仅 log 不吞拒绝 | ① | ExternalCommandTest 16/16 |
| R3 | reject/cancel 入口日志补 externalFlowId 与 approve 对齐(check 自修:cancel 遗漏补齐,三端点逐字同构) | ① | C2 check 结论(R3/A4 半交付已补齐) |
| R4 | 重复命令(第二次被状态守卫拒)同样落拒绝审计 | ① | ExternalCommandTest |
| R5 | 边界:不引入 flowId 比对/round-eventId 幂等(归 C5);不改签名校验 | ① | C5 落地对账(4c31d85c) |

### 3.3 C3 pg-estimation-unknown(6 行;prd R1~R6;commit 53b5e5cf)

| R | 摘要 | 验收方式 | 证据指针 |
|---|---|---|---|
| R1 | 审批路径去伪:expectedAffectedRows 区分「真实估算 0」与「未知」(NULL 入库不汇总为 0) | ① | 53b5e5cf;1614;DmlExplainPreInitHandlerTest |
| R2 | 风险评级修正:null 不落 LOW(选方案 b:评级行为逐字节不变) | ① | RiskAssessorTest 37 |
| R3 | governance-contracts:235「SPI-unsupported→0」钉死条款按新语义修订 | ①(spec) | 53b5e5cf 内 spec 修订 |
| R4 | 前端「行数不可估算」显式提示(rowsEstimated 透传) | ② | e3 域(M2);ticketDetail Tag L112 |
| R5 | 分批路由兜底口径:估算不可得→表行数兜底;分批公告 TABLE_SCALE 口径键 | ①+② | 53b5e5cf;DmlBatchRoutePrecheckTest |
| R6 | 不扩 scope:PG DML EXPLAIN 能力补齐归后续(刻意保守降级保持) | ①(负向 pin) | prod-verification 双方言节(PG DML Explain 降级修订处) |

### 3.4 C4 schema-gate-fail-closed(11 行;prd R1~R11;commit 9b4d70ef+61993ca6)

| R | 摘要 | 验收方式 | 证据指针 |
|---|---|---|---|
| R1 | 分类反转:9 类 WARN_SKIP 转拒绝;D5/D6 放行独立语义;D2 新老分叉;S1~S5 收编(共 17 类) | ① | 9b4d70ef;1654;ReleaseSchemaPrecheckServiceImplTest |
| R2 | 「不可知≠违规」:UNVERIFIABLE 第四态+RELEASE_SCHEMA_GATE_UNVERIFIABLE 事件区分 | ① | ProdReleaseServiceImplTest pin 翻转 |
| R3 | dm_gov_schema_gate_override 表(releaseId/stmtId/gateType/operator/reason NOT NULL/expireAt/consumedFlag)+V202610110001 | ①+③(MIG-2 首启) | 9b4d70ef;MIG-2 |
| R4 | GOV_SCHEMA_GATE_OVERRIDE 权限点(order=6,tag=DBA)+i18n 三文件+实际接线 | ①+②(e4/e8 显隐) | SecRoleAuthLabel;e8 域(M2) |
| R5 | grant/list/revoke 端点:新标签鉴权+租户校验(puid==release.primaryUid)+HIGH 操作审计 | ① | SchemaGateOverrideServiceTest 19 |
| R6 | gate 消费:未过期未消费 override→降级放行+RELEASE_SCHEMA_GATE_OVERRIDE 事件;一次性 CAS | ①+③(IS-D3-3 实操) | 9b4d70ef;IS-D3-3 |
| R7 | 两开关注册 RootUserConfig(govReleaseSchemaPrecheckBlock/DiffBlock 默认 true;break-glass 仅降 UNVERIFIABLE) | ①+② | RootUserConfig L438/L440 |
| R8 | 事件枚举加法+前端 govEventConstants 两映射表+zh/en(枚举全覆盖契约) | ①+② | govEventConstants.js;e4 时间线用例(M2) |
| R9 | 前端 override 管理:stmt FAILED 行入口(myAuth 门控)+理由/有效期弹窗 | ② | e4 域(M2);releaseDetail.vue |
| R10 | 测试 pin 翻转(ProdReleaseServiceImplTest/AutoExecReleaseJobCompletionTest/diff/precheck 各处) | ① | 9b4d70ef(1654 全绿) |
| R11 | spec/文档:governance-contracts「宁漏勿误」pin 修订+计划 §2 注记+ADR-011 implemented | ①(文档) | 61993ca6;docs/adr/ADR-011 |

### 3.5 C5 approval-round-binding(11 行;prd R1~R11;commit 4c31d85c)

| R | 摘要 | 验收方式 | 证据指针 |
|---|---|---|---|
| R1 | flowId 一致性守卫:三态命令 externalFlowId 非空∧appro_identity 非空∧不等→FLOW_ID_MISMATCH 拒绝(宽松起步) | ①+③(5A.3) | ExternalCommandTest pin 翻转;1672 |
| R2 | dm_approval.approval_round int NOT NULL DEFAULT 1(V202610110002)+DO/XML additive | ①+③(MIG-3/5A.10) | 4c31d85c;手册 5A.10 |
| R3 | clearOutboundForNewRound 改 round+1 语义(appro_identity 置 NULL+出站四列重置+自增) | ① | ResubmitOutboundResetTest |
| R4 | 出站 flows/create.context additive 携带 round(桥接不升级不破) | ①+③(5A.0 桥接侧适配) | BridgeCreateRequest.Context;手册 5A.0 |
| R5 | 入站 externalRound additive+比对不等→ROUND_MISMATCH 拒绝 | ①+③(5A.4/5A.5)〔check 修正:原仅 5A.5,round 不符拒绝载体=5A.4,宽松跳过=5A.5〕 | ExternalCommandTest |
| R6 | identity 空窗观测:log.warn+成功事件 identityBackfill 标记(不拒绝) | ① | 4c31d85c |
| R7 | strict 档预留:govExternalCommandStrictFlow 默认 false(宽松);语义文档化 | ①+③(5A.9/IS-D3-13 灰度评估) | RootUserConfig;手册 5A.9 |
| R8 | executeTicket/returnTicket 拒绝路径落 EXTERNAL_COMMAND_REJECTED 审计(O-C2-1 关闭) | ①+③(5A.8) | GovOpenApiServiceImpl;4c31d85c M8 |
| R9 | 契约文档:orchestration §5.4/§10.2/L5 冻结声明修订 | ①(文档) | 4c31d85c 内文档面 |
| R10 | ExternalCommandTest L217-228 pin 翻转(「不同 flowId 照常成功」→拒绝断言)+新用例 | ① | 4c31d85c(1672 全绿) |
| R11 | spec:守卫 3→5(END_STATUS/STATUS_MISMATCH/CHANNEL_MISMATCH/+FLOW_ID_MISMATCH/+ROUND_MISMATCH) | ①(文档) | governance-contracts external-orchestration 条目 |

### 3.6 C6 masking-fail-closed(8 行;prd R1~R8;commit a9599a46;**归档 prd 未回签——证据以 commit 正文为准,裁决 6/G2 口径**;〔check 重对齐 2026-10-14:原稿按交付里程碑 M1~M8 编号致 R4~R7 与 prd 错位且漏 prd R7 spec 修订行,现已与 prd R1~R8 一比一——NOT_BOUND 观测并入 R3,M 号保留在证据指针列〕)

| R | 摘要 | 验收方式 | 证据指针 |
|---|---|---|---|
| R1 | 活泄漏修复(最高优先):resultDataRead 补 doMask(单值,分页同款机制);downloadResult 二进制直读不动 | ①+②(e2) | a9599a46 M1;console 1697 |
| R2 | 未知算法 FULL_MASK 兜底(doAlgorithm else 死代码→结构性封死+warn;null 输入保持 throw 既有 pin) | ① | plus-sec-rules 49(SenAlgorithmHalfMaskTest 26 等) |
| R3 | 无策略分层三态:NOT_BOUND/DANGLING/VALID——DANGLING 拒绝(**行为变更:AutoExec 数据任务打包在 DANGLING 环境从成功变拒绝——留联调**);NOT_BOUND 放行+biz_log 观测(每查询一行、不限频=风暴防护裁决;依赖 C8 D-1 修复后首次可落库) | ①+③(IS-D3-4/IS-D3-5) | a9599a46 M3/M4;commit 正文 Behavior change 节;a36cea1f D-1 节 |
| R4 | 豁免查看审计:SENSITIVE_ORIGINAL_VIEW/SENSITIVE_MASKING_REJECTED 分级+四点注册 | ①+②(e2 豁免面) | a9599a46 M5 |
| R5 | 分批 statusMsg cursor 哈希化(sha256 显示句柄;biz_log 取证与 checkpoint 保原值) | ①+② | a9599a46 M6;DmlBatchLiterals.hashedCursorForDisplay |
| R6 | 错误消息值段净化:DbErrorSanitizer 六缝(PG duplicate-key 保值删列名等;lockfree-dml §11.2 钉死条款同步修订) | ① | DbErrorSanitizerTest 16(C6 交付时点;C8 +1→现 17) |
| R7 | spec 修订:desensitization-rules L36(silent→logged/拒绝分层)+L42(typos leak→FULL_MASK 兜底)钉死条款改写;已裁决矩阵不动 | ①(文档) | a9599a46;desensitization-rules.md |
| R8 | 引擎级测试补全(processRow/doMask 原无单测:未知命令→FULL_MASK/resultDataRead/三态/豁免) | ① | a9599a46(1697+49) |

### 3.7 C7 release-chain-frozen-routing(10 行;prd R1~R10;commit 380d8d6c/478e4f8e/f6180cb7)

| R | 摘要 | 验收方式 | 证据指针 |
|---|---|---|---|
| R1 | manifest 载体:dm_prod_release_stmt_segment 表(plan+runtime 同表)+V202610120001;只写不读阶段派发零 diff | ①+③(MIG-4) | 380d8d6c;1711 |
| R2 | 冻结传播:createRelease/resubmit merge 事务内拷冻结副本;returnStmt/returnRelease/handleRejected/handleCancelled 四路径级联删;resubmit 重填 | ① | ReleaseSegmentPropagateTest |
| R3 | 段感知派发:createReleaseStmtJob 读 manifest 按段派发;null-plan→legacy DIRECT 逐字节不变 | ① | 478e4f8e;1732;ReleaseSegmentDispatchTest |
| R4 | GHOST 接入:binding 泛化(releaseStmtId/releaseId 与 ticketId/groupId 互斥)+会话释放+终态 hook 链式推进+段级探活;SUCCESS 段永不重放 | ①+③(IS-C7-2) | 478e4f8e;GhostGroupCompletionServiceTest |
| R5 | DML_BATCH 阻断:拒派发+stmt FAILED+RELEASE_ROUTE_UNSUPPORTED 事件(披露段号/表/估算行/源工单);出路三条(override/returnStmt/等执行体) | ①+②(横幅)+③(IS-C7-5) | 478e4f8e;f6180cb7 |
| R6 | 能力复核:GHOST 段 prod 重放前只复核能力(方言/gh-ost 可用/会话/表 PK-UK)不重跑路由决策;失败→RELEASE_ROUTE_CAPABILITY→override 通道 | ①+③(IS-C7-3) | 478e4f8e;f6180cb7 |
| R7 | plan_hash 冻结扩展:dm_prod_release_stmt additive 列;三派发点门禁序 hash→plan→precheck→diff;漂移→RELEASE_PLAN_DRIFT 拒发**永不可 override**(结构保证);executionKey 语义不变 | ①+③(prod-verification 增量 N4) | f6180cb7;1763 |
| R8 | 路由 override:dm_gov_route_override(V202610130001)+GOV_ROUTE_OVERRIDE(order=7,tag=DBA,与 C4 标签完全隔离)+理由必填 ≤1024+有效期 ≤7 天+一次性 CAS+HIGH 审计+RELEASE_ROUTE_OVERRIDE 事件;粒度 (releaseId,stmtId) | ①+②+③(MIG-5/IS-C7-5) | GovRouteOverrideServiceTest 20;f6180cb7 |
| R9 | C4 gate 时序保持:段化不改 gate 粒度;快照沿用 merge propagate;任一段 FAILED→stmt FAILED | ① | f6180cb7 |
| R10 | 前端复核面:冻结计划区块(段序列/类型/表/参数)+阻断横幅+override 入口(权限)+四事件时间线;all_build web 门禁 | ②+③(IS-C7-6 浏览器验收) | f6180cb7;e4 域(M2) |

### 3.8 C8 audit-chain-enhancement(9 行;prd R1~R9;commit a36cea1f)

| R | 摘要 | 验收方式 | 证据指针 |
|---|---|---|---|
| R1 | 检索地基:op_audit 三索引(V202610130002)+D-1 缺 AND 修复(救活 C6 NOT_BOUND 观测;先失败复现再修) | ①+③(MIG-6 低峰部署实测) | a36cea1f;1805;AuditRetentionTest BoundSql pin |
| R2 | 保留策略:opAuditRetentionDays(默认 365)/bizLogRetentionDays(默认 90)KV+30min 分批清理三独立腿+D-3 判空守卫;≤0 禁用 | ①+③(数值试点前定,IS-D3-14) | AuditRetentionTest 9 |
| R3 | 四列补齐:sql_fingerprint/ticket_id/release_id/channel(V202610130003)+sql_audit 指纹列;全可空存量 NULL | ①+③(MIG-7) | AuditChainColumnTest 13 |
| R4 | 写点接入矩阵:治理族(GHOST_EXECUTE×3/DML_BATCH×2/override×3/ROUTE_OVERRIDE_CONSUME/SENSITIVE×2);11 参 additive 重载 59 处零改动;热路径零额外 SELECT | ① | a36cea1f;logging-guidelines 写点矩阵节 |
| R5 | UI/对账:PURE_URL+UPDATE_ACCOUNT_USER_NAME 入白名单(O-C4-3 关闭);V202610130004 两 override 表生成列唯一约束(O-C7-6 关闭) | ①+②(下拉抽查)+③(MIG-8) | AuditDetailQueryTest 8 |
| R6 | 详情端点:GET /api/entry/audit/detail(RDP_OP_AUDIT_READ)+租户隔离 not-found 语义+log_info sanitization 兜底(读侧双保险)+字典翻译 | ①+②(e8 弹窗)+③(IS-D3-7③ 实测;prod-verification 增量 N6) | AuditDetailQueryTest 8 |
| R7 | 存量清理手册:tests/governance/audit-legacy-cleanup.md(测试库清空口径+轮转清单;用户执行,记录留联调档案) | ③(IS-D3-7⑤) | a36cea1f;清理手册 |
| R8 | 小加固收口:UNRESOLVED raw rootMessage 4 处 DbErrorSanitizer+O-C7-5 plan gate drift 化+O-C7-6 getDetail N+1 合并读 | ① | a36cea1f M5 |
| R9 | spec/文档:logging-guidelines 四列契约+channel 推断+详情端点 sanitization;governance-contracts join 键;计划 §2 G6 注记 | ①(文档) | a36cea1f |

### 3.9 C10 recovery-type-grading(9 行;prd R1~R9;commit 3ace7316+9a0e6352)

| R | 摘要 | 验收方式 | 证据指针 |
|---|---|---|---|
| R1 | 建模:RecoveryType 7 值枚举(严度序=声明序)+dm_approval.recovery_type(V202610140001,additive nullable 零回填) | ①+③(MIG-9) | 3ace7316;1872 |
| R2 | 推断引擎:RecoveryTypeInferrer 纯函数(提交时点信号;IRREVERSIBLE 扩数据毁灭类 8 值=check 期主会话裁决)+逐语句伴生清单不落库 | ① | RecoveryTypeInferTest 42 |
| R3 | 生成器双方言+型化:PG 双引号/合法形态;per-group;**既有 MySQL 16 pin 逐字节不变(红线)** | ①+③(PG 真库渲染抽查,IS-D3-8③) | RollbackSqlGeneratorTest |
| R4 | 写路径:五写点(submit/resubmit D7 三分支/legacy/CICD/openapi 复用);resubmit 再推断规则 D4;分支 3 双不写 | ① | GovTicketV2SubmitModeTest+ResubmitExternal 等 additive |
| R5 | 存量读时派生:queryTicketDetail null→派生不回填;不可得→UNSPECIFIED | ① | SkipContinue 详情装配 4 测试 |
| R6 | 审计:ROLLBACK_MATERIAL 四点注册;obj 恰 4 字段白名单+fingerprint 与治理链同口径可 join+C8 链列接入 | ①+② | 3ace7316;注册对账扩 3 |
| R7 | VO/表单透出:DmQueryTicketVO+recoveryType/recoveryVerified(恒 false);QueryForm OA 标注(钉钉/飞书按名追加;**企微刻意不接**=O-C10-4);ChangeForm 零触碰 | ①+②+③(OA 模板控件对齐,IS-D3-8②) | 9a0e6352;O-C10-4 |
| R8 | UI 贯穿(M2):详情 Tag 7 类型色阶+恒常「未演练」双处+逐语句折叠区(groupList 读时重算永不落库)+措辞纪律(禁语 grep 零命中;分批三点明示与 gh-ost 文案零 diff)+i18n 前端 16 键×2/后端 8 键 | ② | 9a0e6352;1876;e3 域(M2) |
| R9 | spec/文档:governance-contracts recoveryType 契约节+计划 §2 G5 注记+O-C10-2 规范冲突登记 | ①(文档) | 3ace7316/9a0e6352 |

### 3.10 桥接 dingtalk-approval-bridge(12 行;prd §3 R1~R12;6 commit;**实现状态=代码落库、验收待联调**)

| R | 摘要 | 验收方式 | 证据指针 |
|---|---|---|---|
| R1 | 出站发起基建:桥接 KV 四键(dingBridgeBaseUrl/AuthToken(isSecret)/TimeoutMs/OutboundEnable 默认 false)+HTTP 客户端 never-throw+afterCommit 触发+outbound_status 状态机+externalFlowId 双源回填+业务单号幂等键 | ①+③(手册 §0/§1) | ba6bd748;DingBridgeOutboundServiceTest 29 |
| R2 | 出站发起场景:途径 2(发布单 External 创建成功)+途径 3(PROD_DML DIRECT 落 WAIT_APPROVAL)+止血开关关闭全不出站(纯拉模式向后兼容) | ①+③(手册 §1/§3)〔check 修正 2026-10-15:原 §1~§2,止血臂载体=§3〕 | ba6bd748;DingBridgeFlowTriggerTest |
| R3 | 发起失败补偿:FAILED+原因落库+AlertPush 告警+详情页手动重发端点+后台 sweep 重试(上限+退避)+重试幂等 | ①+③(手册 §2;§9.1 STARTING 悬挂窗口)〔check 修正:原 §3,失败补偿载体=§2〕 | ba6bd748;DingBridgeSweepRetryTest |
| R4 | 出站撤销通知:工单 cancel/close、发布单 cancel/return 人工终止点 afterCommit 调桥接撤销;失败不阻塞;无 externalFlowId 跳过 | ①+③(手册 §6)〔check 修正:原 §4,撤销通知载体=§6〕 | ba6bd748;DingBridgeCancelNotifyTest |
| R5 | 台账开放读:/api/open/dbchange/ledger/list——AK/SK puid 强制租户隔离(绝不继承 console 无过滤)+分页过滤对齐+VO 脱敏+@McpTool | ①+③(手册 §4)〔check 修正:原 §5,ledger/list 载体=§4.1~4.3〕 | 1ec7ddf7;GovOpenApiLedgerListTest |
| R6 | PROD_DML 单级审批选项显性化:提交页 approvalMode 语义联动(External→「钉钉审批(外部流程)」文案);VIA_RELEASE 逐位不变(回归 pin) | ①+②(提交页文案)+③(手册 §5/§8.1)〔check 修正:原 §6,途径 3 载体=§5+浏览器面 §8.1〕 | 11418b0f |
| R7 | 直连通道 deprecated:SQL_TICKET_INFO 类型下拉不再提供 DingTalk/Feishu/Wechat(前端过滤+后端同步);**存量已配置环境行为逐位不变**;插件零删除 | ①+②(下拉)+③(手册 §7) | d5386a86;ApprovalTypeDeprecationTest |
| R8 | 契约文档:orchestration §10(出站桥接)/§11(三途径时序)+桥接侧新增接口契约+联调手册新建 | ①(文档) | 67afc8ee;tests/governance/dingtalk_bridge.md |
| R9 | 联调验收:与桥接项目实际联调三途径(还 09-16 欠账);checklist 入 tests/ 手册 | **③(IS-D3-1;手册 §1~§10 全表执行——本行即「留联调」硬判据典型)** | dingtalk_bridge.md §10 A1~A9 对照 |
| R10 | 旧任务收尾:联调通过后 09-16-gov-open-command-api 归档;proddml-ding-template 即刻归档(已完成,archive/2026-10/) | ③(联调通过后) | 桥接 implement.md Step 11 |
| R11 | i18n:新错误键/标签键/设置页键三件套(zh/en/properties) | ① | 11418b0f |
| R12 | 测试纪律:JUnit4+手写 mock;全量基线不回退;09-16 已交付面回归 pin 零改动;skipOutbound additive(途径 1 防重复建钉钉实例,用户裁决 2026-10-10) | ① | 96f2e70e(+5 钉);ResubmitOutboundResetTest 5 |

### 3.11 列宽 table-column-resize(5 行;prd R1~R5;commit b1915462;**归档 prd A1~A6 未勾=浏览器验收联调债 IS-D3-12**)

| R | 摘要 | 验收方式 | 证据指针 |
|---|---|---|---|
| R1 | 通用拖拽能力:convertTableColumns resizable 透传+DmTable 受控 widthOverrides(自定义载体,design 定稿) | ①(lint+build)+② | b1915462;tests/frontend/ui/table_column_resize.md |
| R2 | 六类页面落地:工单/台账/发布单(列表+详情)+慢SQL+会话+空间;长字段列拖宽后内容可见(61 处全局 Table 单点覆盖) | ②(浏览器,九套件) | table_column_resize.md suites |
| R3 | 交互细节(终审定稿):双击列边界恢复默认=支持;持久化=不做;有数值宽度列默认全开+显式 resizable:false 可关 | ② | b1915462 正文 |
| R4 | 渐进推广面:未落地页面零成本套用(一行指令/一处替换);不强推全部 41 文件;清单入 design | ①(文档) | b1915462;design 清单 |
| R5 | 质量门禁:npm lint 0 error+check-i18n+all_build web SUCCESSFUL+浏览器级六类页面验收+既有流程复测补文档 | ②+③(IS-D3-12 浏览器验收执行后回填) | b1915462;table_column_resize.md(9 suites) |

### 3.12 行数对账

C1 5 + C2 5 + C3 6 + C4 11 + C5 11 + C6 8 + C7 10 + C8 9 + C10 9 + 桥接 12 + 列宽 5 = **91 行**(survey A3 口径一致;C9 自身 R1~R6 不计入——C9 为本验收集,其验收=本目录文档完备性 A1~A8,由 check 核验)。
