# E5 无锁变更(gh-ost + DML 分批)· UAT 用例(C9 M2)

> 三分类中本文件承载**②UAT 手工用例**(README §4);工单链完整测试契约=既有两手册(**引用不重写**):`tests/dml/lockfree_dml.md`(292 行,B7 无锁 DML)+`tests/ghost/lockfree_ddl.md`(229 行,B6 gh-ost);发布链真库场景=③IS-C7-2(GHOST prod 重放)/IS-C7-3(能力探针)/IS-C7-5(DML_BATCH 阻断→route override),与 e4 交叉;真库场景 3/4(在线 DDL/大型 DML)=IS-D3-11 载体指引。
> 证据字段全部留位(红线 3);五元组格式=README §5;凭据纪律=README §8。
> **措辞红线(本域重点,C10 A6/A7)**:分批三点明示(`fen-pi-que-ren-shi-wu-jin-yong` 键族)与 gh-ost 旧表文案(`wu-suo-jiu-biao` 键族)为既有保护文案——本域 E5-03/E5-06/E5-07 的预期结果=**原文口径,不得弱化**;权威源=`.trellis/spec/backend/lockfree-dml.md` §3.6/「三点明示」注记与 `.trellis/spec/backend/ghost-online-ddl.md` R7 旧表条目。

## Purpose

验证无锁变更面(gh-ost 在线 DDL + DML 分批)在 C 波次后的 UI 可见行为与**措辞纪律零削弱**:C10 恢复类型标注(BATCH_CHECKPOINT/GHOST_OLD_TABLE)叠加后,既有「部分成功非回滚」「删除前可回退」等诚实口径文案逐字保持;C6 分批进度面 cursor sha256 句柄化;C7 发布链交叉面(阻断→override)索引。

## Scope

- 页面与路由:`views/system/ghost/settings.vue`(「无锁变更」配置页 `/system/ghost/settings`:gh-ost 区块+无锁数据变更区块+钉钉桥接区块三块并列独立保存)、`views/ticket/components/GhostGroupPanel.vue`+`DmlBatchGroupPanel.vue`(段视图/批进度/控制按钮/人工裁决 modal,C10 措辞纪律零触碰面)、`components/function/GhostProgressBar.vue`(survey E5);
- 后端:`GhostController`(`/api/entry/ghost`)、`DmlBatchController`(`/dmlBatch`)、`DockTaskController`(dock guard);GhostService/DmlBatchService/两 AsyncTask+通道 C(OpsDiagnosticExecutor,无独立 UI=设计使然);插件 plus-online-ddl;
- 权限点:DM_GHOST_EXEC(B7 复用裁决 #15,含 DM_SPACE_READ);
- KV:ghostSizeThresholdMb/ghostRowThreshold/ghostDefaultOptions/ghostAutoRoute+dmlRowThreshold/dmlBatchSize/dmlBatchIntervalMs/dmlLagThresholdSec/dmlBatchTimeoutSec/dmlBatchAutoRoute(六 KV 失效安全解析);
- 不覆盖:发布链段派发/override(→e4 E4-06~13+IS-C7-*)、审计行(→e8)、错误净化(→e2 E2-12a)、工单链完整契约(两手册)。

## 矩阵对应(②类承载对账)

| 矩阵行 | 本域承载 |
|---|---|
| 硬门槛 F-3(在线变更显示执行器/前置/锁风险/空间预算/进度/暂停终止/失败恢复;**PG 不显示 gh-ost 承诺**;矩阵 §2.2)+「措辞纪律核验(C10 M2.5 grep 口径)」 | E5-02/03/05/10/13 |
| C10 行「分批三点明示与 gh-ost 文案零 diff」(矩阵 §3.9 R8;红线 2) | E5-03/E5-06/E5-07(**预期=原文,弱化即 FAIL**) |
| C7 R5(②横幅,e4 承载)/R4(GHOST 段) | E5-11/E5-12(交叉索引,载体=e4+IS-C7-2/5) |
| C6 R5(②cursor 句柄) | E5-05(e2 E2-11 双登记,分批面板语境) |
| C3(TABLE_SCALE 公告) | e3 E3-03 承载,本域 E5-09 灰提示语境引用 |
| ①自动化(不重复) | ghost/dmlbatch 族 ~22 类+plus-online-ddl 65;RollbackSqlGeneratorTest/RecoveryTypeInferTest(C10) |

## Preconditions

- 两手册前置各自适用(lockfree_dml.md/lockfree_ddl.md Preconditions);MySQL 8.0 测试实例(gh-ost 工具可用+合格大表:有 PK/UK、体量达 ghostRowThreshold);PG 15 实例(分批+「PG 不显示 gh-ost 承诺」核对);
- 账号:DM_GHOST_EXEC 持有者+无该标签对照账号;
- KV 六键可改(root,记录原值)。

## Test Data

| 编号 | 数据说明 | 构造方式 | 唯一标识 | 清理方式 |
|---|---|---|---|---|
| E5-D01 | 合格大表 DDL(ADD COLUMN,gh-ost 路由) | 造数达阈值 | `codex_e5_ghost_<ts>` | 旧表按面板流程删+DROP |
| E5-D02 | 不合格拓扑 DDL(无 PK/UK) | 建无键表 | `codex_e5_nogh_<ts>` | DROP |
| E5-D03 | 大表 DML(超 dmlRowThreshold,有批次键) | 造数 | `codex_e5_dml_<ts>` | DROP |
| E5-D04 | 无键大表 DML(NO_KEY 拒绝面) | 建无键表造数 | `codex_e5_nokey_<ts>` | DROP |
| E5-D05 | PG 大表 DML(分批路由) | PG 造数 | `codex_e5_pg_<ts>` | DROP |

## Cases

### 一、配置与面板面

#### E5-01 无锁变更设置页:三区块独立保存+六 KV

- 出处:survey E5(settings.vue 三区块并列独立保存);lockfree-dml spec §8.1(六 KV 平面,无 JSON options 层)+§9.5(设置页双区块物理隔离);dingtalk_bridge.md §8.4(桥接区块三块互不干扰权威步骤)。
- Purpose:gh-ost 区块/无锁数据变更区块/钉钉桥接区块草稿互不干扰、各自保存;dml 六 KV(batchSize/intervalMs/lagThresholdSec/timeoutSec/autoRoute/dmlRowThreshold)与 ghost 四 KV 修改生效并恢复。
- Steps:1)引手册 §8.4 执行三区块交叉保存;2)改 dmlBatchSize 等六 KV→保存→提交 E5-D03 观察 effectiveParams 快照(面板/审计口径);3)恢复原值;4)越界值前端校验(如 batchSize≤0)。
- Expected:区块独立;KV 生效于新任务快照(在飞任务用启动冻结值=spec §3.1 launch freeze);恢复后新任务回默认。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E5-02 gh-ost 段面板全要素(F-3 显示面)

- 出处:硬门槛 F-3(计划 L220:执行器/前置条件/锁风险/空间预算/进度/暂停终止/失败恢复路径);tests/ghost/lockfree_ddl.md(工单链套件权威);GhostGroupPanel.vue。
- Purpose:GHOST 段面板显示面完备:执行器=gh-ost、前置条件、进度条(GhostProgressBar)、暂停/终止按钮、cut-over 阶段、失败恢复路径(retryGroup 从失败段续跑)。
- Steps:1)E5-D01 提交→路由 GHOST 段;2)面板各要素逐项核对;3)执行中暂停/恢复/终止各一次(按 lockfree_ddl.md 套件);4)完成后旧表区块出现(E5-03)。
- Expected:按手册套件预期;F-3 七要素齐;PG 工单面**无任何 gh-ost/通用无锁承诺**(E5-13 步骤 3 交叉)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E5-03 gh-ost 旧表文案零削弱核验(措辞红线)

- 出处:C10 A6/prd 红线 2(gh-ost「删除前可回退」既有文案零削弱);C10 implement.md M2.5 注记(保护键 `wu-suo-jiu-biao` 零 diff 证明);ghost-online-ddl spec R7(旧表默认保留 `--timestamp-old-table`,名 `_tbl_<yyyyMMddHHmmss>_del`;drop=通道 C DROP TABLE+标识符校验+二次确认+审计;absent→GHOST_OLD_TABLE_ABSENT 零副作用);zh.json L4857 `wu-suo-jiu-biao`=「旧表(cut-over 后保留,删除前可回退)」+L2249 C10 GHOST_OLD_TABLE note=「cut-over 后旧表保留、删除前可人工回退;非任意时刻可回退」。
- Purpose:两处旧表文案**逐字**保持——既有面板文案(wu-suo-jiu-biao 键族)与 C10 新 Tag note 并存且口径一致(诚实:删除前可回退≠任意时刻可回退);任何弱化(如删去「非任意时刻可回退」限定、改为「可回滚」)=FAIL。
- Steps:1)E5-D01 完成后 GhostGroupPanel 旧表区块文案逐字核对(zh.json L4857 原文);2)工单详情 C10 Tag=GHOST_OLD_TABLE 橙+note 逐字核对(L2249 原文);3)旧表删除流程:二次确认弹窗+删除后「旧表已删除」(`wu-suo-jiu-biao-yi-shan-chu` L4864)+删除审计行(→e8);4)对已删旧表再点删除→GHOST_OLD_TABLE_ABSENT 拒绝零副作用;5)`git diff` 口径复核保护键(locales 两键族零 diff,C10 M2.5 同款命令)。
- Expected:文案=原文(两键族+note);删除链三要素(二次确认/审计/ABSENT 拒绝)齐;多段组刷新后按钮复现的已知 UX 债(ghost-online-ddl spec 债 6)如复现→登记不判 FAIL(后端拒绝兜底)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E5-04 不合格拓扑阻断(E5-D02,不转普通 ALTER)

- 出处:真库场景 3(计划 L197:不合格拓扑必须阻断/显式风险处理,不能转普通 ALTER);lockfree_ddl.md 套件;ghost-online-ddl spec 边界矩阵。
- Purpose:无 PK/UK 表的大 DDL 不走 GHOST(能力前置不满足),按边界矩阵处置(直接链+披露或阻断,依既有契约),**绝不静默转普通长锁 ALTER 冒充无锁**。
- Steps:1)E5-D02 提交;2)预检/路由披露面核对(灰提示或阻断文案);3)执行面观察(直接链语义=工单既有行为)。
- Expected:按 lockfree_ddl.md 既有断言;披露文案与路由结果一致。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

### 二、DML 分批面

#### E5-05 分批面板:进度/游标句柄/控制按钮

- 出处:lockfree-dml spec §3.2(tick 语义+statusMsg:`sha256:<8hex>…(len=N)` 句柄,C6/M6)+§3.1(checkpoint 载体);DmlBatchGroupPanel.vue;tests/dml/lockfree_dml.md(套件权威);矩阵 C6 R5。
- Purpose:分批执行面板显示面:批进度(rowsDone/estimatedRows「约」口径)、statusMsg(批 N/游标句柄/节流原因)、暂停/恢复/停止按钮(批粒度暂停=在飞批不打断);游标**只见 sha256 句柄无原值**(e2 E2-11 双登记,本域为面板语境)。
- Steps:1)E5-D03 提交→路由分批;2)执行中观察面板三要素;3)暂停→「批边界暂停」文案(spec §3.2 步骤 1:暂停中心跳照写)→恢复;4)停止→部分成功终态(E5-10 交叉);5)statusMsg 与段 summary 游标均句柄化(两 builder 侧)。
- Expected:按 lockfree_dml.md 套件;句柄形态 `sha256:<8hex>…(len=N)`;lag 节流时 statusMsg=「主从延迟节流中」语境(spec §3.5,如可构造)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E5-06 分批三点明示零削弱核验(措辞红线)

- 出处:C10 A6/红线 2(分批三点明示「平台不提供自动回滚」零削弱);C10 implement.md M2.5 注记(保护键 `fen-pi-que-ren-shi-wu-jin-yong` 零 diff);lockfree-dml spec §3.6(Completed batches are KEPT; NO automatic rollback——三点明示之一)+§9.4(ticketDetail 确认弹窗:分段组事务开关 DISABLED+内联三点明示文案+SPECIFY_TIME 禁用,服务端强制 §6.3 兜底);zh.json L4938=「本单含分批执行语句:批间独立提交,整单事务不生效;接受部分成功语义,平台不提供自动回滚」。
- Purpose:PROD_DML 确认弹窗三点明示文案**逐字**保持(①批间独立提交,整单事务不生效②接受部分成功语义③平台不提供自动回滚);事务开关对分段组 DISABLED(服务端强制 §6.3 兜底);任何弱化(如删去③、软化②)=FAIL。
- Steps:1)E5-D03 走 PROD_DML 确认链→弹窗文案逐字核对(zh.json L4938 原文);2)事务开关 DISABLED+SPECIFY_TIME 单选禁用(引导文案在);3)确认执行后批间独立提交事实核对(目标库多事务痕迹口径,lockfree_dml.md 套件);4)`git diff` 口径复核保护键零 diff(M2.5 同款命令);5)停止后已完成批保留、无自动回滚(spec §3.6:回滚依据仅 rollBackSql 备份模板纪律=三点明示的恢复面口径)。
- Expected:文案=原文;开关禁用;部分成功=保留语义(「部分成功不是回滚」,计划 L198 真库场景 4 口径)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E5-07 BATCH_CHECKPOINT Tag 文案无「回滚」承诺

- 出处:C10 D6 红线 2 核查点(BATCH_CHECKPOINT 标注不得含「回滚」字样,用「部分成功非回滚」口径=design D6 明文指定澄清);zh.json L2241 `recovery-type-batch-checkpoint`=「分批检查点」+L2248 note=「部分成功非回滚;已提交批次保留,续跑/裁决见分批面板」;governance-contracts C10 条目(BATCH_CHECKPOINT/GHOST_OLD_TABLE 为 NON-ROLLBACK 恢复语义,labels must never say「回滚」)。
- Purpose:含分批段的工单 C10 Tag=「分批检查点」橙,note 逐字核对——「非回滚」为澄清口径而非承诺;与 E5-06 三点明示口径一致互不矛盾。
- Steps:1)E5-D03 工单详情 Tag+note 逐字核对;2)整单档位核对(混合单含 DROP→IRREVERSIBLE 压过 BATCH_CHECKPOINT,严度序);3)与 e3 E3-06 样本交叉引用(避免重复造单)。
- Expected:label/note=原文;无「可回滚」类字样;档位=严度序最严。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E5-08 无键拒绝面(DML_NO_KEY,唯一拒绝类)

- 出处:lockfree-dml spec §7(NO_KEY 是唯一拒绝:阈值超∧shape 过∧探针确认无键→组 FAIL DML_NO_KEY+出事务告警+审计;阈值下无键 DML 放行不误伤)+§2.1(可空单列 UK=NO KEY 口径);矩阵 e3 E3-14 交叉;e9 E9-06(DML_BATCH_REJECT 审计行)。
- Purpose:E5-D04 提交→组失败提示 DML_NO_KEY(文案含出路指引),告警+审计双留痕;对照:阈值下无键小 DML 正常放行。
- Steps:1)E5-D04 提交→拒绝面文案核对;2)告警通道(测试群)收到 DML_NO_KEY 类告警;3)审计行 DML_BATCH_REJECT 定位(→e8/e9:fingerprint 携带、无 ticket_id=设计口径、channel=到达通道真实);4)小表无键 DML 对照放行。
- Expected:拒绝+双留痕+不误伤;可空 UK 表按 NO KEY 处置(spec §2.1 HIGH handover 口径)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E5-09 路由灰提示三键(探测降级/键类型不支持/lag 不可见)

- 出处:lockfree-dml spec §2.1(DML_BATCH_PROBE_DEGRADED「探测失败,无法评估无锁执行」=不路由不拒绝)+§2.2(DML_BATCH_KEY_UNSUPPORTED 直接执行保留长锁风险披露)+§3.5(DML_BATCH_LAG_UNKNOWN「延迟不可见,限流退化为固定间隔」);C3(灰提示降级仅在估算确认超阈值后出现=阈值下静默)。
- Purpose:三类灰提示语境正确(均为披露非拒绝):探针降级(构造:临时收回 information_schema 读权限或超时)、键类型不支持(FLOAT/复合键/JSON 表)、lag 不可见(MySQL <8.0.22 或无 REPLICATION CLIENT 权限)。
- Steps:1)分别构造三场景提交超阈值 DML;2)灰提示文案与路由结果核对(DIRECT_* 路由值在 precheck_result.dmlBatchRoutes 口径查询);3)阈值下同场景**无**灰提示(静默=status quo);4)同表累积提示(SAME_TABLE_ACCUMULATE,组级黄提示,R13 显示级)。
- Expected:三键文案=spec 原文口径;不路由不拒绝;阈值下静默。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E5-10 UNRESOLVED 人工裁决面板(红色口径+幂等分级)

- 出处:lockfree-dml spec §5(UNKNOWN→UNRESOLVED 人工核对态,intent 保留载体,永不自动重试)+裁决面板红文案(idempotent=false:「该批结果未知且语句非幂等(自引用 SET/非确定函数),重跑可能双倍生效,请先核对数据」zh.json L4949 实存;幂等侧 L4948)+RERUN/SKIP 出路(L4951-4954)+无权限文案(L4955);IS-SEC-5 步骤 4(响应丢失→UNKNOWN 真库载体);硬门槛 P0-6。
- Purpose:模拟批执行 UNKNOWN(通信链路中断)后:段 FAILED+UNRESOLVED 标记→裁决面板出现,红文案分级(幂等/非幂等)、RERUN(断点游标重推边界)/SKIP(重推批宽仅推进游标+证据留痕)两出路、无 DM_GHOST_EXEC 权限账号见「需人工裁决…请联系管理员」只读文案。
- Steps:1)按 IS-SEC-5 步骤 4 构造(或 lockfree_dml.md UNKNOWN 套件);2)裁决面板四要素核对(标题「批执行结果未知 — 人工裁决」L4945/说明 L4946/幂等分级/两出路);3)RERUN 或 SKIP 选择后组重试携裁决结果;4)SKIP 证据(skips 记录)与 biz_log 取证原值侧核对(不回显);5)无权限账号对照。
- Expected:不自动重试(P0-6「无法自动判定进入人工核对态」);面板措辞=原文;裁决后收敛到终态;「上次跳过未成功」再冻结语义(L4947)如触发按 spec §5.3。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

### 三、发布链交叉索引(载体=e4/IS-C7,不复制)

#### E5-11 发布链 GHOST 重放交叉索引

- 出处:IS-C7-2(gh-ost prod 重放:release binding 互斥/链式推进/SUCCESS 段永不重放/会话经济)+IS-C7-3(能力探针:只复核能力不重跑路由);C7 R4/R6;ghost-online-ddl spec §5.10(release-chain wiring)。
- Purpose:登记执行路由——发布链侧 GHOST 行为在 IS-C7-2/3 执行;本域核对点=工单链侧套件(lockfree_ddl.md)在发布链重放期间**零 diff**(工单路径 zero-diff 红线 2)。
- Steps:执行=IS-C7-2/3;本域补充:同工单链样本对照(同表同 DDL 走工单链)行为一致性目检;release 侧会话经济三点(cap-2/closeQuietly/终态释放)按 spec §5.10 口径观察无残留会话。
- Expected:IS-C7-2 预期+工单链零回归。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E5-12 发布链 DML_BATCH 阻断→route override 交叉索引

- 出处:IS-C7-5(阻断→override→显式直发全链实操);e4 E4-07/E4-09(UI 面承载);ADR-013 裁决 3(分级阻断)+裁决 4(路由专用 override);O-C7-1(release 侧批执行体=开放后续任务,联调仅验证阻断+override 出路)。
- Purpose:登记执行路由——分批语句进发布链的行为(阻断,绝不静默直发)与出路(override 单条直发=显式接受锁/超时风险;或 returnStmt 回工单链走完整分批能力)在 IS-C7-5/e4 执行;本域核对点=横幅文案与工单链分批披露口径一致(同一事实两处表达不矛盾)。
- Steps:执行=IS-C7-5+e4 E4-07/09;本域补充:override 直发后该段执行=普通单条 DML(长锁风险已显式接受)——目标库锁等待观察记录(试用通告素材,O-C7-1 优先级评估输入:推广前按大表 DML 走发布链频率评估)。
- Expected:阻断→override→直发链通;风险披露贯穿(横幅/弹窗/审计三处)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E5-13 两手册收编索引+PG 无承诺面

- 出处:tests/dml/lockfree_dml.md+tests/ghost/lockfree_ddl.md(工单链执行载体,套件全表);硬门槛 F-3「PG 不显示 gh-ost/通用无锁承诺」;lockfree-dml spec §2.3(PG 分批支持=major≥15)+ghost 路由 MySQL 族限定。
- Purpose:登记两手册为工单链无锁面权威执行载体(UAT 不重写);PG 面诚实性:PG 工单/设置页无 gh-ost 承诺(路由面 MySQL only),PG 分批可用(≥15)且文案无 MySQL 专属语义泄漏。
- Steps:1)按两手册套件执行(选套记录);2)PG 源提交 DDL→无 GHOST 路由/无 gh-ost 文案;3)PG 源 E5-D05 大 DML→分批路由正常(PG 臂:quote-doubling 重写/`pg_stat_replication` lag 探针,spec §1.6/§3.5);4)设置页 ghost 区块对 PG 环境无误导承诺。
- Expected:手册套件通过;PG 三面(无 gh-ost/分批可用/无承诺文案)诚实。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

## Cleanup

1. E5-D01~D05 测试表 DROP;gh-ost 旧表按面板流程删除(顺带验证 E5-03 删除链)或随表 DROP;
2. KV 六键恢复原值(E5-01);收回的探针权限恢复;
3. 分批/gh-ost 任务全部终态后确认无残留会话(SHOW PROCESSLIST 口径)与无在飞 AsyncTask;
4. 测试群告警样本记录后无需清理(只读)。

## Skip Conditions

- gh-ost 工具/合格大表不具备→E5-02/03/04/11 SKIP(替代=IS-C7-2 执行轮);
- 无主从拓扑→lag 节流/lag 不可见面(E5-05 步骤 5 部分/E5-09 第三场景)SKIP,记录「lagState.visible=false 固定间隔」降级面已覆盖;
- PG 实例不可得→E5-13 PG 臂 SKIP;
- UNKNOWN 构造不可行(无法安全断链)→E5-10 SKIP(替代=IS-SEC-5 步骤 4);
- 未执行、失败或环境阻塞的场景不得标记为通过(TEMPLATE.md 纪律)。
