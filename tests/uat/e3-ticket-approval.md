# E3 工单与审批 · UAT 用例(C9 M2)

> 三分类中本文件承载**②UAT 手工用例**(README §4);C5 轮次绑定的真桥接执行=**③IS-D3-2/IS-D3-13**(载体 dingtalk_bridge.md §5A,本域只登记 UI 观察面与索引,不复制场景步骤——硬判据:凡「留联调」字样一律③);真库主链=`tests/governance/prod-verification.md` Chain One/Two(本域 E3-12/13 收编索引)。
> 证据字段全部留位(红线 3);五元组格式=README §5;凭据纪律=README §8。
> 措辞红线(C10 A6/prd 红线 2):本域用例预期结果引用 i18n **原文**,不得弱化——「未演练」恒常标注、恢复类型七值措辞表(design D6)为权威;分批三点明示/gh-ost 旧表文案核验用例在 e5(载体分工)。

## Purpose

验证工单与审批面在 C 波次后的 UI 可见行为:C3 估算诚实标注(工单侧)、C5 轮次绑定的控制台观察面、C10 恢复类型分级标注全链(Tag 色阶/恒常「未演练」/逐语句折叠区/表单 hint/OA 标注),以及既有真库主链手册的收编索引。

## Scope

- 页面与路由:`views/ticket/ticket.vue`(`/ticket_create`,含 `?govV2=1` 治理 v2)、`ticketDetail.vue`(`/ticket/:id`:C3 Tag、C10 Tag+未演练+折叠区、DingBridgeOutboundPanel 消费点)、`components/GovTicketV2Create.vue`(预检黄旗/分批公告/无键拒绝)、`views/system/approval/`(OA 模板配置,`/integrations/approval`)(survey E3);
- 后端:`GovTicketV2Controller`(`/api/entry/dbChangeV2/{availablePairs,availableServices,check,submit,retryGroup,groupList,returnTicket,resubmit}`)、`ApprovalController`、QueryApprovalHandler(DM_QUERY OA 表单装配:C10 recoveryType 标注+C3 affectCount);
- 权限点:RDP_DB_CHANGE_GOVERN_READ、RDP_DB_CHANGE_PROD_PROMOTE、RDP_DB_CHANGE_PROD_DML_DIRECT、RDP_WORKER_ORDER_REQUEST/READ/EXECUTE;
- 不覆盖:发布链门禁/override(→e4)、无锁面板(→e5)、桥接出站面板三面(→e7/手册 §8)、开放命令面(→e9)。

## 矩阵对应(②类承载对账)

| 矩阵行 | 本域承载 |
|---|---|
| C3 行「②UAT(e3 诚实标注)」+R4/R5(矩阵 §3.3) | E3-01~E3-03 |
| C5 行(①+③;无②——UI 观察面按任务指令 M2.3 登记为索引型用例) | E3-04/E3-05(执行载体=手册 §5A/IS-D3-2) |
| C10 行「②UAT(e3 Tag 色阶/未演练/折叠区/措辞纪律)」+R7/R8(矩阵 §3.9) | E3-06~E3-11 |
| 硬门槛 F-4(回滚能力分类可查询,矩阵 §2.2) | E3-06~E3-09(真演练机制=O-C10-1 独立任务,本域恒「未演练」) |
| 硬门槛 F-2(SQL 优化展示面——既有功能非 C 波次;矩阵 §2.2 载体「e2/e3 冒烟用例」) | E3-14(预检面冒烟:规则解释/建议证据展示)+e2 E2-16/17(执行计划估算证据面);优化建议人工审阅/改写可追踪全貌**不设专用用例**(既有功能面,①RiskAssessorTest 37 承载规则行为)——check 补登 2026-10-15,如实以冒烟登记 |
| 桥接 R6(①+②提交页文案,矩阵 §3.10) | E3-16(载体=手册 §8.1,e7 交叉) |
| 真库主链(P0-1/P0-8) | E3-12/E3-13(收编索引,执行载体=prod-verification) |

## Preconditions

- 测试环境全链可用(PRE/PROD 环境对、GOV_ROLE 配置、sidecar worker 在线——prod-verification Preconditions 同款);
- 账号:提单账号(RDP_WORKER_ORDER_REQUEST)+审批/确认账号;
- OA 面:钉钉/飞书测试模板配置权限(E3-10 用;企微本期刻意未接=O-C10-4);
- C3 用例需 PG 测试源(估算不可得面)+MySQL 对照源。

## Test Data

| 编号 | 数据说明 | 构造方式 | 唯一标识 | 清理方式 |
|---|---|---|---|---|
| E3-D01 | PG DML 工单(估算不可得) | PRE 工单,UPDATE 带 WHERE | `codex_e3_pg_<ts>` | 工单关闭/表 DROP |
| E3-D02 | MySQL 恒假 WHERE DML(true-0) | PRE 工单 | `codex_e3_zero_<ts>` | 同上 |
| E3-D03 | 大表 DML 工单(超 dmlRowThreshold,触发分批路由+TABLE_SCALE 公告) | 造数至阈值上 | `codex_e3_batch_<ts>` | 同上 |
| E3-D04 | 恢复类型样本工单集:纯 DML(TRANSACTION)/手填回滚 SQL(COMPENSATION_SQL)/DDL 自动生成(BACKUP_TEMPLATE)/含 DROP_TABLE(IRREVERSIBLE)/分批路由(BATCH_CHECKPOINT)/GHOST 路由(GHOST_OLD_TABLE)/无信号 legacy 单(UNSPECIFIED) | 各提交一单(小表安全语句) | `codex_e3_rt_*` | 工单关闭/终态后表清理 |
| E3-D05 | resubmit 轮次样本(EXTERNAL 环境工单,return→resubmit) | e7/手册 5A.2 同型 | `codex_e3_round_<ts>` | 工单关闭 |

## Cases

### 一、C3 估算诚实标注(工单侧)

#### E3-01 ticketDetail「行数不可估算」Tag

- 出处:C3 R4(`53b5e5cf`;ticketDetail Tag,i18n 键 `risk-level-rows-unestimated`);矩阵 §3.3 R4「e3 域」。
- Purpose:PG DML 工单详情风险区显示「行数不可估算」标注,而非 0 行;风险等级不因未知升级(C3 R2 评级行为逐字节不变)。
- Steps:1)提交 E3-D01;2)详情页风险区观察;3)对照 MySQL 工单(E3-D02)数值显示;4)核对两单风险等级口径(未知不升级)。
- Expected:PG→「行数不可估算」Tag;MySQL→真实估算数值(含 true-0 显示 0);无「0 行」假象于 PG 侧。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E3-02 钉钉/OA 审批表单 affectCount null 不 append

- 出处:C3(`53b5e5cf` commit 正文「钉钉 affectCount null 不 append」);矩阵 C3 行。
- Purpose:估算不可得时审批材料**不出现行数行**(而非「影响 0 行」误导审批人);估算可得时行数行照常。
- Steps:1)E3-D01 走到审批表单装配(预览或测试模板实发,凭据纪律=测试模板);2)表单字段核对;3)E3-D02 对照。
- Expected:PG 单表单无影响行数字段;MySQL 单含真实数值;两表单其余字段(单号/风险级/SQL hash 等)齐全。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E3-03 分批公告 TABLE_SCALE 口径

- 出处:C3 R5(分批路由兜底:估算不可得→表行数兜底;公告口径键 TABLE_SCALE);矩阵 §3.3 R5;lockfree-dml spec §2.4(两数字并列展示供审批对比,组织规范 §9.8.3)。
- Purpose:分批路由公告中,估算不可得的语句以**表规模**口径披露(明示是表行数兜底而非语句估算),估算可得时两数字并列。
- Steps:1)提交 E3-D03(触发分批路由);2)GovTicketV2Create 预检公告区观察分批路由披露(估算行数/表行数两口径);3)PG 侧同型(估算不可得→TABLE_SCALE 兜底文案)。
- Expected:公告口径键与数值来源分明;无假估算数字;审批材料可比对(两数字并列)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

### 二、C5 轮次绑定(UI 观察面;执行载体=手册 §5A/IS-D3-2)

#### E3-04 resubmit 轮次自增的控制台观察面

- 出处:C5 R2/R3(`4c31d85c`;approval_round 列+clearOutboundForNewRound round+1 语义);手册 5A.2(权威执行步骤);IS-D3-2。
- Purpose:发布单/工单 return→resubmit 后,元数据 `approval_round` 自增、出站四列重置、新 flowId——本用例只登记控制台可见面(详情刷新/出站面板回「未发起→发起中→已发起」),完整断言在 5A.2。
- Steps:引手册 5A.2 执行;本域补充观察:详情页出站面板状态迁移(DingBridgeOutboundPanel,e7 E7-02 交叉)+事件时间线无异常事件。
- Expected:面板新一轮出站正常;`SELECT approval_round FROM dm_approval WHERE id=<新审批行>` 口径=2(手册 5A.2 同款,值非凭据可记录)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E3-05 五守卫拒绝面 UI 呈现(FLOW_ID_MISMATCH/ROUND_MISMATCH)

- 出处:C5 R1/R5(五守卫链:END_STATUS/STATUS_MISMATCH/CHANNEL_MISMATCH/+FLOW_ID_MISMATCH/+ROUND_MISMATCH,ADR-012;tolerant-first 宽松起步+strict 档 `govExternalCommandStrictFlow` 默认 false);C2(拒绝落 EXTERNAL_COMMAND_REJECTED 审计,REQUIRES_NEW);手册 5A.3/5A.4/5A.5/5A.9(权威场景);IS-D3-2/IS-D3-13。
- Purpose:守卫拒绝在控制台侧的可见呈现=工单状态零变化+事件时间线 EXTERNAL_COMMAND_REJECTED 行(eventData 白名单字段:reason/externalFlowId/recordedFlowId 等)+审计页可检索(→e8 E8-06 按 ticketId);宽松档最简调用零误拒(D5)。
- Steps:引手册 5A.3(旧 flow 拒绝=核心漏洞场景)/5A.4(round 不符)/5A.5(宽松跳过 pin)/5A.9(strict 双向,可选)执行;本域核对:每次拒绝后工单详情页时间线出现拒绝事件、中文映射无 raw key、工单状态与拒绝前一致。
- Expected:拒绝面 UI 三要素(时间线事件/状态不变/审计可检索)齐;strict 开关双向行为符合 ADR-012 Decision 3(生产默认恒 false)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

### 三、C10 恢复类型分级标注

#### E3-06 详情页恢复类型 Tag 七值色阶+措辞逐字核对

- 出处:C10 R8/M2(`9a0e6352`;design D6 措辞表;色阶单点权威 `frontend/src/const/recoveryType.js`:IRREVERSIBLE 红/BATCH_CHECKPOINT+GHOST_OLD_TABLE 橙/COMPENSATION_SQL+BACKUP_TEMPLATE 蓝/TRANSACTION 绿/UNSPECIFIED 灰);矩阵 §3.9 R8。
- Purpose:七类型 Tag 的 zh 标注与语义要点**逐字**符合 D6 措辞表(i18n 键 `recovery-type-*` 14 键,zh.json L2237-2250 实存):事务回滚/补偿 SQL(提交人提供)/备份模板(系统生成)/不可逆/分批检查点/gh-ost 旧表/未分级;各 note 原文核对(TRANSACTION=「执行失败自动回滚,库无残留」——事务原子性事实描述,非恢复物承诺;IRREVERSIBLE=「DROP/TRUNCATE 类,平台无恢复物,依赖数据库备份」)。
- Steps:1)逐一提交/打开 E3-D04 七样本单;2)详情页「查看回滚SQL」按钮区 Tag 色阶+悬浮/伴随 note 核对;3)en 语言切换对照(en.json 对称键)。
- Expected:七单 Tag 类型/色阶/措辞与 D6 表一致;推断档位符合严度序(混合取最严:含 DROP_TABLE 单=IRREVERSIBLE 红)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E3-07 恒常「未演练」双处标注(recoveryVerified 恒 false)

- 出处:C10 红线 4(**无 recovery_verified 列**——VO 读时常量 false,直至真演练机制落地=O-C10-1);M2.1 注记(双处=按钮区+modal 标题,镜像既有「系统生成/申请人填写」Tag 双处先例);i18n 键 `recovery-unverified`(zh.json L2235)。
- Purpose:任何类型(含 TRANSACTION)的恢复材料旁恒伴「未演练」——诚实标注,无「已验证/已演练」假信号;七单全部核对。
- Steps:1)E3-D04 各单详情按钮区+回滚 SQL modal 标题两处观察;2)核对任何面无「已演练/verified」字样。
- Expected:双处恒常显示「未演练」;无任何条件使其消失(常量语义);modal 内材料可读。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E3-08 逐语句推断折叠区(读时重算,永不落库)

- 出处:C10 M2(governance-contracts「Per-statement companion list」条目:仅 groupList 读装配、同一 Inferrer 重算、零额外 SELECT、永不持久化;信号不足→空清单→整块不渲染=诚实降级);i18n 键 `recovery-stmt-list-title`=「逐语句恢复类型推断」。
- Purpose:折叠区逐语句显示推断类型(与整单 Tag 同引擎);legacy 工单(无组行)/无信号单整块不渲染(无假空网格)。
- Steps:1)E3-D04 混合单(DDL+DML 多语句)展开折叠区→逐语句类型与整单档位一致(最严档=整单 Tag);2)存量 legacy 单(C10 前提交,列 NULL)打开→读时派生显示 Tag 而 `recovery_type` 列仍 NULL(口径查询核对,MIG-9 交叉);3)无信号单→折叠区整块不渲染。
- Expected:三面(逐语句/legacy 派生/诚实降级)符合契约;展开/收起交互复用既有折叠形态(wu-suo-seg-zhan-kai/shou-qi 键)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E3-09 表单 hint 与手填=补偿 SQL 语义(裁决 1)

- 出处:C10 D6(表单 hint 维持不承诺);i18n 键 `hui-gun-sql-hint`(zh.json L2231:「选填,未填写时系统将自动生成备份模板;手动填写将视为补偿 SQL。恢复类型由系统自动标注(未演练)」);governance-contracts 推断臂 4(手填=补偿意图声明,内容永不检验=O-C10-1)。
- Purpose:提交页回滚 SQL 区 hint 原文核对;手填材料单→COMPENSATION_SQL 蓝 Tag(内容不做真假判别);留空→自动生成 BACKUP_TEMPLATE。
- Steps:1)提交页 hint 逐字核对(zh/en);2)E3-D04 手填单与留空单各提交→详情 Tag 对照;3)resubmit 场景:D7 三分支中「材料变化→再推断」「留空∧原手填→保持不动」两分支的 Tag/审计行为(审计行→e8 E8-11;分支 3 双不写)。
- Expected:hint 无承诺措辞;手填=补偿 SQL(蓝);留空=备份模板(蓝);resubmit 分支语义符合 D7。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E3-10 OA 表单「恢复类型」标注(钉钉/飞书按名追加;企微刻意未接)

- 出处:C10 R7(governance-contracts「OA form annotation」条目:DingTalk 两映射点 null-guarded 按名追加+Feishu widget 分支;**WeChat 位置式契约刻意未接=O-C10-4**);IS-D3-8②(③真模板对齐,桥接联调轮);矩阵 §3.9 R7。
- Purpose:DM_QUERY 工单的 OA 审批表单在模板含同名控件「恢复类型」时渲染标注(值=类型 label+恒常未演练后缀);模板无该控件→静默跳过零影响(存量模板安全)。
- Steps:1)测试模板配置「恢复类型」控件(钉钉/飞书各一);2)提交 DM_QUERY 工单→OA 表单出现标注,措辞=D6 表+「未演练」;3)移除控件→重提一单→表单正常无报错(按名匹配失败静默);4)企微通道确认**无**该标注(刻意未接,登记 O-C10-4 评估素材);5)ChangeForm(变更工单表单)零触碰核对(红线 6:无恢复类型字段)。
- Expected:钉钉/飞书按名追加生效;无控件零影响;企微不加(位置式契约,加控件需同步追加位置值);ChangeForm 无变化。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E3-11 措辞纪律:禁语零命中(全前端)

- 出处:C10 A6/prd 红线 2、3(禁语「可自动回滚」/「auto rollback」grep 零命中);C10 implement.md M2.5 注记(grep 口径;其「自动回滚全前端仅两处合法」系注释+note 两类口径——**当前全量基线 6 处**,见 Steps 3 清单,全部否定式/D6 原文/保护键;归档注记按裁决 6 不回改,本用例以 C9 check 2026-10-15 复核清单为准)。
- Purpose:全前端不引入「可自动回滚」类虚假承诺——UI 面 grep 纪律复核(执行时点=统一联调,前端产物为当前构建)。
- Steps:1)`grep -rn 可自动回滚 frontend/src/` →零命中;2)`grep -rni "auto rollback" frontend/src/` →零命中;3)`grep -rn 自动回滚 frontend/src/` →命中全部落于合法清单(2026-10-15 check 复核基线 **6 处**:B7/N2 否定式注释 ×2〔dmlBatch.js「部分成功保留 + 无自动回滚」/ticketDetail.vue 同款〕+`recovery-type-transaction-note`「执行失败自动回滚,库无残留」D6 原文+保护键 ×3〔`fen-pi-que-ren-shi-wu-jin-yong`/`fen-pi-zhong-zhi-jing-gao`/`fen-pi-zhong-zhi-bu-fen-cheng-gong`,均「平台不提供自动回滚」否定式〕);4)BATCH_CHECKPOINT 文案无「回滚」承诺字样(仅「部分成功非回滚」澄清口径,design D6 红线 2 明文指定)。
- Expected:1/2 零命中;3 命中全在合法清单内且均为否定式/事实描述形态;4 符合。清单外新增命中或任何承诺式(「可自动回滚」类)字样出现=FAIL(措辞红线)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

### 四、真库主链收编索引(执行载体=既有手册,不复制)

#### E3-12 prod-verification Chain One(路径 A 10 步)索引

- 出处:prod-verification.md Chain One(Step 1~10:权限组→提交→规则审计+PRE 自动执行→冻结→Promotion 门禁一→钉钉审批→确认→Guard 门禁二→生产执行→Timeline);矩阵 P0-1/P0-8 行;IS-D3-11(真库场景 1/2 载体指引)。
- Purpose:登记工单侧真库主链的执行载体路由——UAT 执行时按手册逐步走,证据回填手册行+矩阵 P0-1 行(双回填口径=integration-scenarios 头部约定)。
- Steps:执行=手册 Chain One 全 10 步(MySQL)双方言节(PG);本用例核对点=手册 Step 3 失败排查节已含 C3 修订(「expectedAffectedRows 落 NULL…不再伪装为 0」原文在)与 Step 10 Timeline 事件集含 C4~C7 新事件(govEventConstants 全覆盖→e4 E4-10)。
- Expected:手册步骤与当前页面/接口一致(不一致先更新手册再执行——手册头部纪律);C 波次增量门禁 N1~N6 与 Chain 同轮跑(prod-verification 增量节首声明)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E3-13 prod-verification Chain Two(路径 B 5 步)索引

- 出处:prod-verification.md Chain Two(Step B1~B5:开关权限→直发 DML+回滚→冻结→钉钉审批含影响行数→确认执行 Timeline);矩阵 P0-1 行。
- Purpose:PROD_DML 直发路径真库主链载体路由;B4 表单字段核对点含 C3 修订(影响行数=null 不 append,E3-02 同口径)。
- Steps:执行=手册 Chain Two;前置=GOV_DML_DIRECT=on;本用例核对点=B2 预期状态行已含 task 10-11 语义(「PG: SPI 不支持→估算不可得 NULL→永不拒绝」原文在)。
- Expected:按手册预期;PG 路径 B 行阈值自然失效为已知约束(Skip Conditions 同款,非缺陷)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E3-14 工单 v2 提交面冒烟(预检黄旗/分批公告/无键拒绝)

- 出处:survey E3(GovTicketV2Create 面);lockfree-dml spec §4.2(预检标记缝:黄旗/公告/DML_NO_KEY 组失败);C3(TABLE_SCALE 公告交叉 E3-03)。
- Purpose:`/ticket_create?govV2=1` 提交面三可见行为冒烟:预检黄旗(路由披露)、分批公告(阈值+键+估算)、无键拒绝(唯一拒绝类:DML_NO_KEY 组 FAIL 提示)。
- Steps:1)E3-D03 提交→预检黄旗+分批公告出现;2)构造无键大表 DML→提交拒绝提示(DML_NO_KEY,含指纹口径→e9 E9-06 审计交叉);3)阈值下小 DML→无黄旗无公告(不误伤);4)tests/frontend/ticket/ticket_detail.md 既有流程选套复测。
- Expected:三面行为与 spec §4.2/§7 一致;拒绝仅 NO_KEY 一类(其余=直接链+灰提示,永不 FAIL)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E3-15 工单详情既有流程复测(ticket_detail.md)

- 出处:tests/frontend/ticket/ticket_detail.md(既有流程文档);矩阵「前端流程」域映射(README §7)。
- Purpose:C 波次在 ticketDetail.vue 叠加了 C3 Tag/C10 区块/出站面板三个新面——既有详情流程(状态区/执行进度/Timeline/失败定位)零回归。
- Steps:按 ticket_detail.md 套件执行;附加核对:新面(C10 按钮区 Tag/折叠区、C3 风险区标注)不破坏既有布局(窄屏/刷新纪律)。
- Expected:既有套件通过;新面共存无布局破坏;事件时间线含新事件中文映射(→e4 E4-10)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E3-16 提交页审批模式文案联动(桥接 R6)

- 出处:桥接 R6(矩阵 §3.10:①+②提交页文案+③手册 §5/§8.1);`11418b0f`;手册 §8.1(权威浏览器步骤);e7 E7-02 交叉。
- Purpose:PROD_DML 提交页 approvalMode 语义联动:External 环境 DIRECT 选项=「钉钉审批(外部流程)」+desc;Internal/未配置=原文案「直接审批」逐位不变;VIA_RELEASE 文案两环境均不变(回归 pin)。
- Steps:引手册 §8.1 执行(本域登记工单面归属);核对切换 pair 后文案随环境通道刷新(陈旧响应不闪回)。
- Expected:手册 §8.1 预期原文;执行证据双回填(手册 §10 A5 行+本用例)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

## Cleanup

1. E3-D01~D05 工单走关闭/取消正常链(顺带验证 e7 撤销面者注明);测试表 DROP;
2. OA 测试模板移除「恢复类型」控件恢复原状(E3-10);
3. 恢复 resubmit 样本环境参数(如动过)。

## Skip Conditions

- 桥接/OA 未配置→E3-04/05/10/16 SKIP(执行载体压 IS-D3-1/2 桥接联调轮,本域只留观察面);
- PG 源不可得→E3-01/02 PG 臂 SKIP(MySQL 对照仍执行);
- E3-D04 七样本中 GHOST/DML_BATCH 路由样本依赖大表造数,不具备→该两单 SKIP 并以 e5 面板用例(E5-03/07)替代覆盖 Tag 面(记录替代口径);
- 未执行、失败或环境阻塞的场景不得标记为通过(TEMPLATE.md 纪律)。
