# 联调场景汇总(C9 / 统一联调执行载体)

> 长期有效文档;所有证据字段留位,统一联调执行时回填(红线 3)。三分类中本文件全部属**③联调场景**(硬判据:凡 prd「留统一联调/留项目末尾」字样一律归此,不得以①②冲抵——README §4)。
> 五元组留位统一格式:`日期____|执行人____|结果____|证据指针____|豁免____`(豁免记录引用 dingtalk_bridge.md 附录 A 模板;执行记录表沿用其附录 B 形态)。
> 结构:§1 D3 联调债 14 项全收编 → §2 C7 真库六场景成文(G1 缺口修复)→ §3 真库场景 5/6(原 C9 范围收编,G4)+场景 1~4 载体指引 → §4 九迁移首启+重跑幂等(裁决 5:升级/安装域承载)→ §5 桥接 M5+5A 索引(不复制)。
> 本文件 IS-*/MIG-* 场景编号被 `traceability-matrix.md`(C 级主表「验收方式③」列+§2 硬门槛对照表)反向引用——执行结果同时回填两处(场景块五元组+矩阵行证据列);硬门槛 Go/No-Go 汇总见矩阵 §2。
> 环境红线(全体场景适用,继承 dingtalk_bridge.md ⛔ 红线):真库操作只在腾讯云/阿里云 MySQL 8.0 与 PG 15 测试实例;生产禁破坏性测试;密码/令牌/AK-SK/桥接 token 不写入任何记录(验证 SQL 只写口径不回显值,curl 凭据环境变量注入);钉钉联调只用测试群/测试审批模板。

---

## 1. D3 联调债 14 项全收编

出处总表:survey D3(逐项带原始出处);本节为执行载体——已有手册者索引手册章节(不复制),无载体者指向本文件 §2/§3/§4 成文场景。

### IS-D3-1 桥接 M5 三途径联调(A1~A9)+Step 11 收尾

- 场景:与桥接项目实际联调三途径(途径 1 台账开放读+组合命令/途径 2 发布单 External 出站/途径 3 PROD_DML DIRECT 出站),还 09-16 欠账;联调通过后 09-16-gov-open-command-api 归档+桥接任务归档评估。
- 前置:用户部署重启(cwd=backend/)→开 `dingBridgeOutboundEnable`+配 `dingBridgeBaseUrl`/`dingBridgeAuthToken`(KV 白名单通道,值不入任何记录);测试群/测试审批模板就绪。
- 步骤:按 `tests/governance/dingtalk_bridge.md` §1~§8 逐章执行+§10 验收对照 A1~A9 全表;Step 11 收尾动作见桥接 implement.md L78-81。
- 预期:三途径全链通;A1~A9 逐项可勾;止血开关关闭时纯拉模式向后兼容。
- 证据留位:日期____|执行人____|结果____|证据指针____|豁免____
- 出处:桥接 prd L60/§3 R9-R10;implement.md L78-81;父 prd L92(③④ 压末尾裁决)。

### IS-D3-2 桥接 5A 轮次绑定 10 场景+5A.0 桥接侧适配

- 场景:C5 审批轮次绑定(ADR-012)真桥接验证:5A.0 桥接侧 additive 适配(读 context.round/回传 externalRound/第二轮新 flowId/尽量携带 flowId)+5A.1~5A.10。
- 前置:IS-D3-1 三途径基本通;桥接侧完成 5A.0 四项适配。
- 步骤:按手册 §5A 逐场景执行;**5A.3 旧 flow 拒绝=核心漏洞场景**(resubmit round+1 后旧 flowId 回调→FLOW_ID_MISMATCH);5A.8 execute/return 拒绝审计(O-C2-1 关闭验证);5A.10 迁移 DB 实证(SHOW COLUMNS approval_round+存量=1+重跑幂等,并入 §4 MIG-3)。
- 预期:五守卫链拒绝面全实证;拒绝本身落 EXTERNAL_COMMAND_REJECTED 审计(REQUIRES_NEW 不丢失);宽松档零误拒(D5)。
- 证据留位:日期____|执行人____|结果____|证据指针____|豁免____
- 出处:dingtalk_bridge.md L113-135(§5A);C5 prd R9/A7;4c31d85c。

### IS-D3-3 C4 撞闸样本+真实发布单撞闸/override 实操

- 场景:分类反转后 WARN→BLOCK 存量摩擦面记录(实际拦截样本供 ADR-011 回顾)+UNVERIFIABLE 全链实操:构造→拒绝→grant override→消费放行。
- 前置:测试环境发布单链可用;GOV_SCHEMA_GATE_OVERRIDE 标签账号+无标签对照账号。
- 步骤:1)构造 UNVERIFIABLE 场景(如派发前临时断开目标库/收回元数据读取权限)→派发拒绝(RELEASE_SCHEMA_GATE_UNVERIFIABLE 事件);2)有标签账号在发布单详情 grant override(理由必填+有效期)→HIGH 授权审计;3)重派发→消费放行(RELEASE_SCHEMA_GATE_OVERRIDE 事件)→override 行 consumed(一次性 CAS);4)二次派发需重新 grant;5)记录联调期间全部实际撞闸样本(样本台账:场景/类别/处置,不含数据值);6)O-C4-1 留意 legacy 误判样本(整单零快照判据,SNAPSHOT_MISSING 事件留痕核对)。
- 预期:fail-closed 语义实证「无法证明安全⇒不派发」;override 全链证据完整(权限/理由/时效/审计);break-glass KV 仅降 UNVERIFIABLE 不降确认违规。
- 证据留位:日期____|执行人____|结果____|证据指针____|豁免____
- 出处:C4 prd Notes L46「联调时记录实际拦截样本」;计划文档 L35 行末;父 prd O-C4-1;9b4d70ef。

### IS-D3-4 C6 DANGLING 环境 AutoExec 数据任务打包拒绝(边界内行为变更)

- 场景:脱敏策略悬空失效(DANGLING)环境下,AutoExec 数据任务打包从成功变拒绝——prd 边界内行为变更,联调实测确认语义。
- 前置:测试环境构造 DANGLING 策略(策略引用的规则被删/失效)。
- 步骤:数据任务(结果集读取打包路径)在 DANGLING 环境执行→观察拒绝;对照 VALID 环境正常;NOT_BOUND 环境走 IS-D3-5。
- 预期:DANGLING→拒绝(fail-closed,不泄漏原值);拒绝消息经 DbErrorSanitizer 不含数据值段;行为变更影响面如实记录(试用通告素材)。
- 证据留位:日期____|执行人____|结果____|证据指针____|豁免____
- 出处:a9599a46 commit 正文「Behavior change…flagged for the final integration round」;父 prd L48(C6 新增联调关注项)。

### IS-D3-5 C6 NOT_BOUND 观测真实落库

- 场景:无策略环境查询→dm_mon_biz_log MASKING_NOT_BOUND 观测行真实落库(依赖 C8 D-1 缺 AND 修复后首次可落库——修复前该观测因 SQL 语法错误被 never-throw 吞掉,从未落过库)。
- 前置:测试环境某数据源无脱敏策略绑定;C8 代码已部署。
- 步骤:1)执行查询;2)查 dm_mon_biz_log(口径:dependOnBizId+类型过滤,不回显值)确认 MASKING_NOT_BOUND 行存在;3)核对每查询一行、不限频(C6 风暴保护裁决保持)。
- 预期:观测行落库;查询本身正常返回(观测不阻断);行内容无原值。
- 证据留位:日期____|执行人____|结果____|证据指针____|豁免____
- 出处:C8 prd A1「真库落库验证留联调」;a36cea1f 正文 D-1 节;logging-guidelines「Gotcha: the biz_log dedup query…」。

### IS-D3-6 C7 真库六场景(无既有手册载体——本文件 §2 成文=G1 缺口修复)

- 场景/前置/步骤/预期:见 §2 IS-C7-1~IS-C7-6(逐场景成文)。
- 证据留位:各场景块内独立留位。
- 出处:C7 implement.md L17/L63/L68/L92;计划文档 L37 行末「真库发布单段执行/阻断/override 实操留项目末尾统一联调」;380d8d6c/478e4f8e/f6180cb7 正文;C7 prd A8(联调手册补真库场景——本场景集即其交付)。

### IS-D3-7 C8 五项

- 场景:①三迁移首启(V202610130002/3/4,→§4 MIG-6~8)②MASKING_NOT_BOUND 真实落库(=IS-D3-5,同一动作双登记)③GET /api/entry/audit/detail 鉴权+租户隔离实测(→prod-verification 增量 N6)④索引低峰部署实测(op_audit 持续写入下 V202610130002 三索引在线 DDL,记录耗时与锁影响)⑤清理手册执行(用户操作:`tests/governance/audit-legacy-cleanup.md`,前置确认/量级盘点/清空双方案/轮转清单;执行记录留联调档案,O-C1-2 关闭动作闭环)。
- 前置:测试环境审计表有存量数据(⑤仅测试库——裁决 1「测试库直接清空」)。
- 步骤:①②③④按指向载体;⑤用户按清理手册逐节执行并填其执行记录表。
- 预期:①SUCCESS+索引实存;③无标签拒绝/跨租户 not-found/log_info 敏感键 `******`;④低峰在线加索引不阻断写入;⑤存量明文凭据行清零+轮转清单口径执行。
- 证据留位:日期____|执行人____|结果____|证据指针____|豁免____
- 出处:C8 design.md L8/L76;prd A1/A4/R7/A8;implement.md L89「真库联调项四处注记完整」;a36cea1f。

### IS-D3-8 C10 三项

- 场景:①V202610140001 真库首启(→§4 MIG-9)②OA 模板「恢复类型」控件对齐(钉钉/飞书模板配置同名控件「恢复类型」;企微位置式契约未接,加控件需同步追加位置值——O-C10-4;与桥接联调同轮评估)③PG 模板真库渲染抽查(PG 工单备份模板在 PG 15 实库验证语法合法:双引号标识符/PG 形态注释;MySQL 16 pin 逐字节不变已由自动化守护,此处只验 PG 臂)。
- 前置:②需桥接联调轮(IS-D3-1/2)进行中+OA 模板管理权限;③需 PG 测试实例。
- 步骤:②提交 DM_QUERY 工单→OA 表单出现「恢复类型」标注(钉钉/飞书按名追加;值=类型 label+恒常未演练后缀);③构造 PG 工单(手填回滚 SQL 留空)→提交→查看自动生成的备份模板→在 PG 实库语法校验(EXPLAIN 或事务内试执行后回滚,不提交)。
- 预期:②模板无该控件时零影响(按名匹配失败静默跳过);有控件时标注出现且措辞=「未演练」诚实口径;③模板 PG 语法合法可执行;措辞无「可自动回滚」类承诺。
- 证据留位:日期____|执行人____|结果____|证据指针____|豁免____
- 出处:C10 implement.md L23/L31;计划文档 L39 行末;C10 prd A2;O-C10-4。

### IS-D3-9 手册「M4b 待提交」过时措辞修正

- 场景:dingtalk_bridge.md L4「待提交」措辞与 L5 基线数字(1604/2157)为 2026-10-10 时点数,已过时。
- 处置:**已于 2026-10-14 随 C9 M1 修正**(G5:96f2e70e 已提交事实+基线更新至 console 1876/全后端 2438,注明时点)——本项联调时无需执行动作,仅核对手册头部与当前基线一致。
- 证据留位:日期____|执行人____|结果____|证据指针____|豁免____
- 出处:dingtalk_bridge.md L4-5(修正后);96f2e70e;survey G5。

### IS-D3-10 原 C9 范围收编:真库场景 5/6

- 场景:计划文档 §6 真库端到端场景 5(安全失败链)/6(脱敏出口链)——父 prd 波次表原 C9 行范围,定位变更后由本验收集显式收编(G4,不悬空)。
- 步骤/预期:见 §3 IS-SEC-5/IS-MASK-6(成文)。
- 证据留位:各场景块内独立留位。
- 出处:父 prd L39(C9 行原文,已随 G4 修正为 uat-acceptance-suite+收编注记);计划文档 L199-200。

### IS-D3-11 真库场景 1~4(既有载体收编)

- 场景:计划文档 §6 真库场景 1(MySQL 路径 A 全链)/2(PG 路径 A 全链)/3(MySQL 在线 DDL:合格大表 GHOST 成功+不合格拓扑阻断)/4(MySQL/PG 大型 DML:批次边界/断点续跑/lag 节流/部分成功非回滚)。
- 载体(不新写):场景 1=prod-verification.md Chain One(路径 A 10 步)+Chain Two(路径 B 5 步);场景 2=同手册双方言节(PG 环境验证 4 项+MySQL 对照);场景 3=tests/ghost/lockfree_ddl.md+发布链侧 IS-C7-2/IS-C7-3;场景 4=tests/dml/lockfree_dml.md(批次边界/断点续跑/lag 节流/UNRESOLVED 人工裁决)+IS-SEC-5 步骤 4(响应丢失)。
- 预期:按各手册既有断言;**C 波次新门禁叠加执行**(prod-verification 增量 N1~N6 与 Chain 场景同轮跑)。
- 证据留位:日期____|执行人____|结果____|证据指针____|豁免____
- 出处:计划文档 L195-198;prod-verification.md;survey D3 #11。

### IS-D3-12 列宽任务浏览器验收

- 场景:列宽任务 A1~A6 浏览器验收执行(归档 prd 未勾选=联调债,裁决 6 不回改历史)。
- 前置:前端产物为 b1915462 之后构建(all_build web)。
- 步骤:按 `tests/frontend/ui/table_column_resize.md` 九套件执行(拖拽主链/初始零变化/边界兼容/双击恢复/六类页面长字段列)。
- 预期:九套件通过;执行后回填证据并评估归档 prd 验收状态注记(不回改原文,以记录表补充)。
- 证据留位:日期____|执行人____|结果____|证据指针____|豁免____
- 出处:列宽 prd L37-42;b1915462;survey G6。

### IS-D3-13 strict 档灰度评估

- 场景:govExternalCommandStrictFlow(审批命令 strict 档 KV,默认 false=宽松)——联调实证后评估是否开启。
- 前置:IS-D3-2 5A 场景全通过;桥接侧 flowId 携带率实证(5A.0 适配后「尽量携带」的实际覆盖率)。
- 步骤:按手册 5A.9 双向验证(开 strict→无 flowId 命令拒绝;关→宽松放行);记录联调期无 flowId 命令占比→给出开启建议(生产默认恒 false,开启=运营决策)。
- 预期:双向行为符合 ADR-012 Decision 3;开启建议附实测占比证据。
- 证据留位:日期____|执行人____|结果____|证据指针____|豁免____
- 出处:C5 prd R7;ADR-012 Decision 3;手册 5A.9(可选场景)。

### IS-D3-14 性能指标落定(实测记录+用户确认)

- 场景:计划 §7 性能与可靠性指标(预检 P95/查询超时/最大结果集/并发上限/回调恢复时限/任务检测恢复时限/Timeline 时延/审计保留期数值/RPO-RTO/GHOST 磁盘峰值/cut-over 锁窗口)——本项目降维为「实测记录+用户确认」(父 prd 裁决),不预填拍脑袋数字。
- 前置:联调各场景执行完毕(指标从执行记录中采集)。
- 步骤:1)从联调执行记录采集各指标实测值(时间戳差/日志耗时/sweep 周期观察);2)retention KV 数值(opAuditRetentionDays/bizLogRetentionDays)由用户试点前定(≤0=禁用的逃生门口径说明);3)汇总表交用户确认签字(试点前)。
- 预期:每指标有实测出处或显式「试点期采集」标注;无凭空数字。
- 证据留位:日期____|执行人____|结果____|证据指针____|豁免____
- 出处:计划文档 L228;父 prd L101(降维裁决);C8 裁决 4「数值试点前定」。

---

## 2. C7 真库六场景成文(G1 缺口修复:C7 prd A8「联调手册补真库场景」的执行载体)

> 依据:ADR-013(docs/adr/,四裁决:manifest 载体/冻结+能力复核/DML_BATCH 分级阻断/路由专用 override)+380d8d6c/478e4f8e/f6180cb7 三 commit 正文+C7 implement.md 各里程碑注记。契约细节以 `.trellis/spec/backend/governance-contracts.md`「Release-chain segment-aware dispatch」条目与 `ghost-online-ddl.md` §5.10 为权威;本节只写执行步骤与预期。

### IS-C7-1 两迁移首启(V202610120001 + V202610130001)

- 场景:C7 两个迁移在真实 MySQL 8.0 元数据库首启验证(M1/M3 交付时均注明「真库首启验证留联调」)。
- 前置:测试环境元数据库备份;新代码未启动过(dm_update_history 无该两版本行)。
- 步骤:
  1. `SELECT version, success FROM dm_update_history WHERE version IN ('202610120001','202610130001');`(口径查询,预期空);
  2. 启动后端(cwd=backend/);
  3. 复查 dm_update_history 两行 SUCCESS;
  4. `SHOW CREATE TABLE dm_prod_release_stmt_segment;`——核对:plan 列组(seg_idx/segment 计划字段)+runtime 列组+`uk_rstmt_seg` 唯一键+`idx_release` 索引;
  5. `SHOW COLUMNS FROM dm_prod_release_stmt LIKE 'plan_hash';`——varchar(64) NULL;存量行全 NULL(M1 只写不读阶段+M2 后新单才写);
  6. `SHOW CREATE TABLE dm_gov_route_override;`——核对:reason varchar(1024)/status DEFAULT 'PENDING'/expire_at/consumed_at/covered_seg_idxs varchar(256)+`idx_route_override_release(release_id,status,expire_at)`;
  7. 二次重启→两版本不重跑(dm_update_history 无重复行、无报错)。
- 预期:两迁移 SUCCESS;表/列/索引实存;存量发布单行为零变化(null-plan→legacy DIRECT 逐字节)。
- 证据留位:日期____|执行人____|结果____|证据指针____|豁免____
- 出处:380d8d6c/f6180cb7;C7 implement.md M1.1 注记「真实 MySQL 首启验证留联调(用户自行重启)」+M3.1 注记;survey B5。

### IS-C7-2 gh-ost prod 重放(GHOST 段发布链执行)

- 场景:冻结计划含 GHOST 段的发布单在真库执行——release binding 互斥、段推进链式、SUCCESS 段永不重放。
- 前置:MySQL 测试 DS+gh-ost 工具可用;合格大表 DDL(有 PK/UK、体量达 ghostRowThreshold);工单链预检产生 GHOST 段并冻结进发布链。
- 步骤:
  1. 创建发布单(merge propagate)→详情核对 manifest 段序列(含 GHOST 段类型/表/参数)+plan_hash 已写;
  2. 审批通过→派发:GHOST 段以 release binding 启动(releaseStmtId/releaseId 与工单链 ticketId/groupId **互斥**,工单链零污染);
  3. gh-ost 执行至 cut-over 完成→终态 hook→段推进 nextPendingStmt 链式派发后续 AUTOEXEC 段→发布单 SUCCESS;
  4. 审计核对:GHOST_EXECUTE HIGH 行(release 链侧:releaseId+fingerprint=hash(seg.sqlText)+channel=SYSTEM_AUTO;C8 链列可检索);
  5. 重放防护:对已完成发布单触发 retry/sweep→SUCCESS 段永不重放(无第二次 gh-ost 启动、无重复任务行);
  6. 会话经济:执行完毕后目标库无残留 gh-ost 会话/无 orphan 会话(SHOW PROCESSLIST 口径核对)。
- 预期:发布链 GHOST 段全链通;工单链既有行为零 diff(对照 lockfree_ddl.md 工单侧套件不受影响);事件时间线含段级事件。
- 证据留位:日期____|执行人____|结果____|证据指针____|豁免____
- 出处:478e4f8e(R4);C7 implement.md M2 各注记;ghost-online-ddl §5.10;计划文档 L37。

### IS-C7-3 能力探针(冻结计划复用不重探测;失败→RELEASE_ROUTE_CAPABILITY)

- 场景:GHOST 段 prod 重放前能力复核——**只复核能力(方言=MySQL 族/gh-ost 工具可用/channel 会话可用/目标表存在且有 PK/UK),不重跑路由决策**(阈值/行数/路由矩阵不再评估,冻结计划复用不重探测);失败走 override 通道。
- 前置:含 GHOST 段的发布单已冻结;派发前使能力前置失效(测试库上临时移除目标表 PK/UK,或使 gh-ost 不可用)。
- 步骤:
  1. 派发→能力复核失败→stmt FAILED+RELEASE_ROUTE_CAPABILITY 事件(eventData 含失败项);
  2. 核对**未**重新评估路由(无新 precheck 段计划产生、阈值判定未重跑——冻结计划原样);
  3. 失败分支自清理:无半启动 gh-ost 会话残留;
  4. 出路验证:grant route override→重派发按 AUTOEXEC 显式直发(风险披露入理由+审计);或 returnStmt 回工单链;
  5. 恢复能力前置(还原 PK/UK)→重新走正常链(对照)。
- 预期:能力复核失败=拒发不降级;override/returnStmt 两出路可用;无残留会话。
- 证据留位:日期____|执行人____|结果____|证据指针____|豁免____
- 出处:478e4f8e(R6);C7 implement.md M2.12 check 注记②「launch 自证=接受(失败分支全自清理无半启动会话)」。

### IS-C7-4 sweep 段级探活+停链重驱

- 场景:发布链执行中断(进程崩溃/派发点失败)后,sweep 段级探活与 redriveStalledReleaseChains 重驱停链;并发无双派发窗口。
- 前置:含多段的发布单在执行中(GHOST 或 AUTOEXEC 段)。
- 步骤:
  1. 段执行中 kill 后端进程→重启;
  2. 观察 sweep:段级探活(按段类型:GHOST 会话探活/AUTOEXEC job 探活)→停链判定→redriveStalledReleaseChains 重驱;
  3. 核对重驱从正确段继续:SUCCESS 段不重放、执行中段按探活结果处置(会话在→等待;会话亡→失败处置);
  4. 并发窗口:重驱与用户 retry 同时发生→无双派发(行锁+CAS 语义,观察无重复 job/无重复 gh-ost 启动);
  5. 顺带观察 O-C7-4(陈旧 RR 快照双 retry 竞态,survey D1「建议联调并发场景顺带观察」)——如有异常样本记录回流。
- 预期:停链自愈;无双执行;FAILED 段处置=gate-denial 镜像(PARTIAL_FAILED 聚合,出路闭合)。
- 证据留位:日期____|执行人____|结果____|证据指针____|豁免____
- 出处:478e4f8e(sweep 段级探活+redriveStalledReleaseChains);C7 implement.md M2.12 check 注记③「并发逐条验证无双派发窗口」;O-C7-4。

### IS-C7-5 DML_BATCH 阻断→route override 实操(CAS 一次性+限时 ≤7 天+HIGH 审计)

- 场景:含 DML_BATCH 段的冻结计划进发布链→阻断(裁决 3:分级阻断,release 侧无批执行体=O-C7-1 开放)→route override 显式直发出路全链实操。
- 前置:大表 DML(超 dmlRowThreshold、有批次键)走工单链预检→DML_BATCH 路由→冻结进发布链;GOV_ROUTE_OVERRIDE 标签账号+仅 GOV_SCHEMA_GATE_OVERRIDE 标签对照账号。
- 步骤:
  1. 派发→阻断:stmt FAILED+RELEASE_ROUTE_UNSUPPORTED 事件(披露段号/表/估算行/源工单)+前端阻断横幅;
  2. 有标签账号 grant route override:理由必填(≤1024)+有效期(≤7 天,超期拒绝)→HIGH 授权审计(CONSOLE channel+releaseId);
  3. 重派发→CAS 一次性消费→该段按 AUTOEXEC 显式直发+RELEASE_ROUTE_OVERRIDE 事件(eventData 含覆盖的 segIdx 清单);
  4. override 行状态 PENDING→CONSUMED;再次派发同段(如 retry)→需重新 grant(已消费不可复用);
  5. 过期语义:grant 后不改 expire_at 等待过期(或直接构造已过期行)→派发不消费 EXPIRED 行(读时派生);
  6. 双表隔离:仅 GOV_SCHEMA_GATE_OVERRIDE 标签账号调 route override 端点→**服务端拒绝**(前端隐藏不代替后端拒绝);反向亦然;
  7. 活性唯一:同 (releaseId,stmtId) 已有 PENDING 行再 grant→服务层拒绝(V202610130004 生成列唯一约束兜底,MIG-8)。
- 预期:阻断→override→直发全链符合 ADR-013 裁决 3/4;一次性+限时+HIGH 审计三要素实证;两 override 表权限完全隔离。
- 证据留位:日期____|执行人____|结果____|证据指针____|豁免____
- 出处:478e4f8e(R5)+f6180cb7(R8);C7 implement.md M3.3 清单;计划文档 L37;O-C7-1(联调仅验证阻断+override 出路,不验批执行体)。

### IS-C7-6 冻结计划 UI 核验(浏览器)

- 场景:发布单详情冻结计划复核面浏览器验收(逐语句段区块+plan_hash pin+两新时间线事件族)。
- 前置:IS-C7-2/IS-C7-5 产生的发布单(含段计划、含阻断态样本);浏览器复用已登录状态(tests/frontend/ 纪律)。
- 步骤:
  1. 发布单详情→冻结计划区块:逐语句段序列(段类型/表/参数/DML_BATCH 阻断披露)与 manifest 一致;plan_hash 显示(pin 值可与 `SELECT plan_hash FROM dm_prod_release_stmt WHERE id=…` 口径核对);
  2. DML_BATCH 阻断横幅出现(IS-C7-5 步骤 1 样本);
  3. override 入口显隐:GOV_ROUTE_OVERRIDE 标签账号可见/无标签账号隐藏;GOV_SCHEMA_GATE_OVERRIDE 与 GOV_ROUTE_OVERRIDE 两入口互不串位(双表隔离的前端面);
  4. 事件时间线:RELEASE_ROUTE_UNSUPPORTED/RELEASE_ROUTE_CAPABILITY/RELEASE_ROUTE_OVERRIDE/RELEASE_PLAN_DRIFT 四类新事件中文映射出现(govEventConstants 枚举全覆盖契约,无 raw key);
  5. 黄旗收窄核对(C7 M3 交付:黄旗仅存披露语义,阻断态不再黄旗化)。
- 预期:UI 五面与 f6180cb7 交付说明一致;i18n 无 raw key;窄屏/刷新不破坏区块(前端流程文档通用纪律)。
- 证据留位:日期____|执行人____|结果____|证据指针____|豁免____
- 出处:f6180cb7(前端冻结计划区块+阻断横幅+override 管理+四事件时间线+黄旗收窄);survey D3 #6⑥。

---

## 3. 真库场景 5/6 成文(原 C9 范围收编,G4)+场景 1~4 载体指引

> 出处:计划文档 §6「必需的真库端到端场景」L193-200。场景 1~4 的既有载体见 IS-D3-11(不重写);场景 5/6 为本节成文(父 prd 波次表原 C9 行范围的显式收编)。

### IS-SEC-5 安全失败链(真库场景 5)

- 场景:模拟四类安全失败,确认无重复派发/越权执行并可人工查明(计划 L199 原文);C 波次后四类失败均有专属拒绝语义与审计链。
- 前置:测试环境全链可用(工单链+发布链+桥接可选);C4/C5/C2/C8 代码已部署;审计四列可检索(MIG-7 完成)。
- 步骤:
  1. **Schema 读取超时**:发布单派发前使目标库元数据读取超时/断连→派发;
  2. **审批旧回调**:工单 resubmit(round+1、新 flowId)后,重放旧 flowId 的 approve 回调(手册 5A.3 同型,无桥接时以开放命令端点直接重放,curl 凭据环境变量注入);
  3. **审批状态与任务状态不一致**:工单已终态(FINISHED/CLOSED)后重放审批命令→状态守卫拒绝;
  4. **数据库执行已成功但响应丢失**:DML 分批执行中模拟通信链路中断(如批执行期间断目标库网络)→观察 UNKNOWN 分类处置;发布链侧同型=job 回调丢失后 sweep 探活(IS-C7-4 交叉)。
- 预期:
  - 步骤 1:RELEASE_SCHEMA_GATE_UNVERIFIABLE 拒绝(C4 fail-closed),库无变更;
  - 步骤 2:FLOW_ID_MISMATCH 拒绝+EXTERNAL_COMMAND_REJECTED 审计落库(C5+C2),状态不推进;
  - 步骤 3:END_STATUS/STATUS_MISMATCH 守卫拒绝+拒绝审计,终态不被改变;
  - 步骤 4:**不自动重试**——分批侧 UNRESOLVED 人工核对态(intent 保留在载体,裁决面板可用);发布链侧探活后按事实处置;全程无双派发;
  - 人工查明:每类失败可经审计链四列检索定位(ticket_id/release_id/sql_fingerprint/channel)+事件时间线还原全过程(C8 交付面)。
- 证据留位:日期____|执行人____|结果____|证据指针____|豁免____
- 出处:计划文档 L199;父 prd L39(原 C9 行);9b4d70ef/4c31d85c/be0f2f8c/a36cea1f;lockfree-dml.md §3.4(UNKNOWN 三分类)。

### IS-MASK-6 脱敏出口链(真库场景 6)

- 场景:带唯一敏感标记的测试记录,从查询、分页、导出、异步结果、异常日志和审计路径验证授权角色看不到原值;受控例外角色可见且留痕(计划 L200 原文);C6 五类出口修复面全展开。
- 前置:测试表插入含**全库唯一敏感标记串**的记录(标记串本身非凭据、可 grep;策略绑定该列);未授权角色+豁免角色两账号;C6/C8 代码已部署。
- 步骤:
  1. 未授权角色逐出口验证唯一标记不出现原值:①结果网格(含单值读取 resultDataRead——C6 M1 活泄漏修复面)②分页翻页③导出④异步结果⑤数据编辑器;
  2. **异常日志出口**:构造含该列值的 DB 错误(如 duplicate-key 冲突)→应用日志 grep 唯一标记→零命中(DbErrorSanitizer 值段净化,C6 M7);
  3. **审计路径**:dm_mon_op_audit/dm_mon_biz_log grep 唯一标记→零命中;NOT_BOUND 对照环境查询→MASKING_NOT_BOUND 观测行落库(IS-D3-5 交叉,C8 D-1 修复验证);
  4. **分批 statusMsg**:含该列游标的分批任务→前端 statusMsg 只见 `sha256:<8hex>…(len=N)` 显示句柄,无原值(C6 M6);biz_log 取证侧保原值(口径核对不回显);
  5. **受控例外**:豁免角色查看原值→成功+SENSITIVE_ORIGINAL_VIEW 审计留痕(obj 含脱敏策略快照键);未授权角色尝试豁免通道→SENSITIVE_MASKING_REJECTED 留痕;
  6. **未知算法兜底**:构造策略指向未知/失效算法→FULL_MASK 全掩码+warn 日志(C6 M2),不泄漏原值;
  7. DANGLING 环境交叉:IS-D3-4(AutoExec 打包拒绝)。
- 预期:六类出口+分批面未授权侧零原值;例外两侧均留痕;唯一标记在日志/审计/前端全部显示面零命中;掩码行为分层(NOT_BOUND 观测放行/DANGLING 拒绝/VALID 掩码)符合三态契约。
- 证据留位:日期____|执行人____|结果____|证据指针____|豁免____
- 出处:计划文档 L200;a9599a46(M1/M2/M5/M6/M7);a36cea1f(D-1);desensitization-rules.md(C6 修订后口径)。

---

## 4. 九迁移首启+重跑幂等(升级/安装域承载,裁决 5;B5 迁移清单为载体)

> 规范口径(database-guidelines):Flyway **Java** 迁移;幂等=dm_update_history 版本表(SUCCESS 不重跑)+`CREATE TABLE IF NOT EXISTS`+父类吞 IGNORABLE_ERROR_CODES {1050,1054,1060,1061,1062,1072,1091,1146};**严禁修改历史迁移**——验证只读,不改任何 V 文件。索引类迁移(V202610130002)撞 1061 亦在 IGNORABLE 集。
> 通用步骤(一次执行覆盖九项,每步记录):
> 1. **备份**测试环境元数据库(mysqldump 口径,备份文件不含凭据明文入档);
> 2. 首启前:`SELECT version, success FROM dm_update_history WHERE version LIKE '202610%';`(口径查询,记录现有行);
> 3. 部署新代码首启(cwd=backend/,用户自行重启——MEMORY 纪律:AI 不 kill/拉起进程);
> 4. 首启后:复查 dm_update_history 九版本全 SUCCESS;逐项执行下表「验证点」;
> 5. **二次重启**:观察启动日志无迁移报错、dm_update_history 无重复行/无版本变更(重跑幂等);
> 6. 升级路径对照(如条件允许):从旧版本库(含 2026-09 版本行)升级首启→九迁移顺序执行成功,历史 migration 行零变化(计划 §6「验证历史 migration 不变,只追加」)。

| # | 迁移版本 | 任务 | 验证点(只读口径,不回显值) | 出处(首启留置注记) |
|---|---|---|---|---|
| MIG-1 | V202610090001__dm_approval_outbound_bridge | 桥接 M1 | dm_approval 出站四列实存(outbound_status/outbound_error/outbound_retry_count/outbound_next_retry_at,SHOW COLUMNS);outbound_status 为 NULL——DB 无 NOT_STARTED 默认值,应用层将 NULL 读作 NOT_STARTED(DingBridgeOutboundService 状态机词表) | 手册 §0.2(DDL 迁移验证含四列;survey B5 误记 §0.3,以手册为准) |
| MIG-2 | V202610110001__gov_schema_gate_override | C4 | 表实存+reason NOT NULL+consumed/expire 列(SHOW CREATE TABLE) | C4 prd 红线;IS-D3-3 交叉 |
| MIG-3 | V202610110002__dm_approval_round | C5 | approval_round 列 NOT NULL DEFAULT 1;存量行值全=1;重跑幂等(=手册 5A.10 同项,双登记) | 手册 5A.10(SHOW COLUMNS+存量=1+重跑幂等) |
| MIG-4 | V202610120001__prod_release_stmt_segment | C7 M1 | segment 表(uk_rstmt_seg+idx_release)+dm_prod_release_stmt.plan_hash varchar(64) NULL(=IS-C7-1 步骤 4/5,双登记) | C7 implement.md L17 |
| MIG-5 | V202610130001__gov_route_override | C7 M3 | 表实存+`idx_route_override_release(release_id,status,expire_at)` 索引(SHOW CREATE 核对)+status DEFAULT 'PENDING'(=IS-C7-1 步骤 6,双登记) | C7 implement.md L68 |
| MIG-6 | V202610130002__op_audit_indexes | C8 M1 | dm_mon_op_audit 三索引实存(SHOW INDEX:owner_uid+operate_date/audit_type/resource_type);**低峰部署**(审计表持续写入,在线 DDL 耗时与锁影响记录→IS-D3-7④) | C8 design.md L8 |
| MIG-7 | V202610130003__audit_chain_columns | C8 M2 | op_audit 四列(sql_fingerprint varchar(64)/ticket_id bigint/release_id bigint/channel varchar(16))全可空+存量行 NULL;dm_exec_sql_audit 指纹列 | C8 design.md L76 |
| MIG-8 | V202610130004__override_active_unique | C8 M5 | 两 override 表(schema_gate/route)生成列+唯一约束实存(SHOW CREATE TABLE);活性唯一语义抽查(=IS-C7-5 步骤 7 交叉);清理手册 §7 前置检查口径 | C8 design.md D7;audit-legacy-cleanup.md §7 |
| MIG-9 | V202610140001__dm_approval_recovery_type | C10 M1 | dm_approval.recovery_type 列可空;存量行全 NULL(零回填口径);读时派生不落库(详情页存量单显示派生值而列仍 NULL) | C10 implement.md L23 |

- 证据留位(每 MIG 独立回填):MIG-1 日期____|执行人____|结果____|证据指针____|豁免____(MIG-2~9 同式,执行记录表可沿用 dingtalk_bridge.md 附录 B 形态复制九行)
- 预期汇总:九版本 SUCCESS;二次重启零重跑零报错;历史 migration(2026-09 及以前)行零变化;所有 additive 列存量 NULL/默认值零回填。

---

## 5. 桥接 M5+5A 场景索引(手册为权威载体,不复制)

> 权威源:`tests/governance/dingtalk_bridge.md`(279 行;头部基线已随 G5 修正至 2026-10-14 口径)。本节仅给「UAT 执行时点的手册章节路由」与前置核对清单。

| 手册章节 | 内容 | UAT 关联 |
|---|---|---|
| ⛔ 红线(5 条) | 测试群/测试模板、生产禁破坏、凭据零入档等 | 全体 IS-* 场景继承(本文件头部已引) |
| §0 部署前置(0.1~0.8) | KV 配置/出站四列验证(MIG-1)/测试群机器人 | IS-D3-1 前置 |
| §0A 前置准备 | 数据/账号/环境准备 | IS-D3-1 前置 |
| §1~§4 出站主链/补偿/止血/撤销 | 途径 2/3 出站+失败补偿+止血开关+撤销通知(桥接 R1~R4) | IS-D3-1 主体 |
| §5 途径 1 全链 | 台账开放读+组合命令(create/approve/…;skipOutbound 防重复建实例) | IS-D3-1 主体;e9 域交叉 |
| §5A 轮次绑定 10 场景(5A.0~5A.10) | C5 桥接侧适配(5A.0 四项)+十场景(5A.3 旧 flow=核心漏洞场景/5A.8 execute-return 拒绝审计/5A.9 strict 双向/5A.10 迁移实证=MIG-3) | IS-D3-2 主体;IS-D3-13(5A.9 灰度评估) |
| §6~§7 途径 3 全链/直连退役 | 双选项+退回映射;deprecated 下拉过滤 | IS-D3-1 主体 |
| §8 前端三面 | 审批模式文案/DingBridgeOutboundPanel/桥接设置区块(浏览器验收) | IS-D3-1 主体;e7 域交叉 |
| §9 运维处置(9.1~9.5) | STARTING 悬挂手工修复模板/详情面板异常隐藏/~13s 响应说明/告警缺位/排查 SQL(只读) | 联调期间异常处置参照;F-7 硬门槛素材 |
| §10 验收对照 A1~A9 | 桥接任务验收标准对照表 | IS-D3-1 收官核对;通过后桥接 prd A1~A9 勾选(主会话动作) |
| 附录 A 豁免模板/B 记录表/C 清理 | 证据纪律载体 | 全体 IS-* 场景的豁免/记录/清理口径 |

**5A.0 前置适配核对清单**(桥接侧执行,联调第一轮前确认——IS-D3-2 前置):
1. 桥接读取 `flows/create.context.round`(additive,未知键容忍已确证);
2. 命令回传 `externalRound`(additive FO 字段);
3. 第二轮使用新 flowId(resubmit 后旧 flow 必拒=5A.3);
4. 尽量携带 flowId(strict 档灰度前提,IS-D3-13 采集携带率)。

**OA 模板「恢复类型」控件对齐**(与桥接联调同轮,O-C10-4):钉钉/飞书模板配置同名控件「恢复类型」→IS-D3-8② 验证;企微位置式契约本期未接,加控件需同步追加位置值(评估记录回流 O-C10-4)。
