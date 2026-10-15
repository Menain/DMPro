# E4 发布链(台账→发布单→门禁→执行)· UAT 用例(C9 M2,最大域)

> 三分类中本文件承载**②UAT 手工用例**(README §4);C4 撞闸/override 全链实操=**③IS-D3-3**、C7 真库六场景=**③IS-C7-1~6**(integration-scenarios §1/§2,本域用例引用不复制);`tests/governance/prod-verification.md` 安全底线 8 条+DENY 12 case+C 波次增量 N1~N6 为既有③载体(E4-14~16 收编索引)。
> 证据字段全部留位(红线 3);五元组格式=README §5;凭据纪律=README §8。
> 篡改类步骤(门禁漂移/双表隔离 API 直调)**仅限隔离测试环境**(prod-verification Skip Conditions 末条同款纪律)。

## Purpose

验证 C4「schema 门禁 fail-closed+governed override」与 C7「发布链冻结计划+段感知派发+drift gate+route override」的 UI 可见面与拒绝面(矩阵 C4/C7 行②类),并收编既有真库门禁载体(安全底线/DENY 矩阵/增量 N1~N6)。核心口径:无法证明安全⇒不派发;override=显式权限+理由+时效+一次性+HIGH 审计;**PLAN_DRIFT 永不可 override(结构保证)**;两 override 表完全隔离互不替代。

## Scope

- 页面与路由:`views/dbChange/ledger.vue`+`ledgerDetail.vue`(`/dbChange/ledger*`)、`release.vue`+`releaseDetail.vue`(`/dbChange/release/:releaseId`:C4 override 区块、C7 冻结计划区块+DML_BATCH 阻断横幅+route override 入口+四事件时间线)、`views/dbChange/govEventConstants.js`(事件映射契约=枚举全覆盖)(survey E4);
- 后端:`GovLedgerController`(`/api/entry/govledger/{list,detail}`)、`GovReleaseController`(`/api/entry/govrelease/{create,list,detail,retryStmt,returnStmt,return,resubmit,cancel,overrideGrant,overrideList,overrideRevoke,routeOverrideGrant,routeOverrideList}`)、ProdReleaseServiceImpl(三派发点门禁序)+ReleaseSegmentService;
- 权限点:**GOV_SCHEMA_GATE_OVERRIDE**(order=6,tag=DBA)、**GOV_ROUTE_OVERRIDE**(order=7,tag=DBA,与前者完全隔离)、RDP_DB_CHANGE_PROD_PROMOTE;租户面=puid==release.primaryUid;
- KV:govReleaseSchemaPrecheckBlock/govReleaseSchemaDiffBlock(默认 true;break-glass 仅降 UNVERIFIABLE);
- 不覆盖:GHOST/DML_BATCH 执行面板(→e5)、审批轮次(→e3/e7)、审计检索面(→e8)、开放 API 命令面(→e9)。

## 矩阵对应(②类承载对账)

| 矩阵行 | 本域承载 |
|---|---|
| C4 行「②UAT(e4 override 显隐/事件时间线)」+R4/R7/R8/R9(矩阵 §3.4) | E4-01~E4-05、E4-10 |
| C7 行「②UAT(e4 冻结计划区块/横幅/override 显隐)」+R5/R8/R10(矩阵 §3.7) | E4-06~E4-13、E4-20 |
| 硬门槛 P0-2/P0-3/P0-4(矩阵 §2.1) | E4-01/04/11/12(UI 面);实操=IS-D3-3/IS-C7-1~6/prod-verification N1~N5 |
| 硬门槛 F-6(双标签服务端拒绝) | E4-13(→e8 E8-13 双登记) |
| 硬门槛 F-1(BLOCK/WARN/UNKNOWN/PASS 分类) | E4-01(UNVERIFIABLE≠违规事件区分)+E4-11 |
| 台账/发布单前端流程 | E4-17~E4-19(tests/frontend/governance/ 3 篇引用) |
| C8 链列(release_id 检索/跳转) | E4-21(→e8 交叉) |

## Preconditions

- prod-verification Preconditions 全套(PRE/PROD 环境对+权限组+审批模板+sidecar);MIG-2/MIG-4/MIG-5/MIG-8 迁移已首启(→integration-scenarios §4);
- 账号三件套:发布确认人(RDP_DB_CHANGE_PROD_PROMOTE)、DBA 甲(仅 GOV_SCHEMA_GATE_OVERRIDE)、DBA 乙(仅 GOV_ROUTE_OVERRIDE)、无标签对照账号丙;
- 台账条目就位(dingtalk_bridge.md 0A.2 同型:已 FINISHED 的 PRE_DDL ≥2+WAIT_RELEASE 的 PROD_DML ≥2)。

## Test Data

| 编号 | 数据说明 | 构造方式 | 唯一标识 | 清理方式 |
|---|---|---|---|---|
| E4-D01 | UNVERIFIABLE 样本发布单(派发前目标库元数据不可读) | 临时断连/收回元数据权限(IS-D3-3 步骤 1 同型) | `codex_e4_unv_<ts>` | 恢复连接;发布单取消 |
| E4-D02 | 含 DML_BATCH 段的发布单(大表 DML 走工单链预检路由后合并) | e5 造数+台账合并 | `codex_e4_batch_<ts>` | 同上 |
| E4-D03 | 含 GHOST 段的发布单(合格大表 DDL) | 同上 | `codex_e4_ghost_<ts>` | 同上 |
| E4-D04 | plan 漂移样本(隔离测试库篡改 segment plan 列) | 仅隔离环境 | `codex_e4_drift_<ts>` | 恢复原值(改前记录) |
| E4-D05 | 台账/发布单生命周期样本(create→approve→execute→finished) | 正常链 | `codex_e4_life_<ts>` | 终态自然留存 |

## Cases

### 一、C4 schema 门禁 fail-closed + override(②UI 面;实操=IS-D3-3)

#### E4-01 UNVERIFIABLE 拒绝面与事件区分(第四态)

- 出处:C4 R1/R2(`9b4d70ef`;PrecheckConclusion UNVERIFIABLE 第四态+「不可知≠违规」事件区分);prod-verification 增量 N1;ADR-011;矩阵 §3.4。
- Purpose:派发前 schema 不可读→不派发,stmt FAILED+事件 `RELEASE_SCHEMA_GATE_UNVERIFIABLE`(基础设施不可知),与确认违规 `RELEASE_SCHEMA_PRECHECK_FAILED` 区分;默认生产模式无「WARN_SKIP 后自动继续」(P0-3)。
- Steps:1)构造 E4-D01→派发;2)详情页 stmt 状态+事件时间线核对(两事件中文映射不同文案,govEventConstants);3)目标库核对无对应变更(口径查询);4)恢复连接重派发→正常(或走 E4-02 override 路径)。
- Expected:拒绝+事件区分+库无变更;9 类原 WARN_SKIP 场景同拒(17 类分类反转,①自动化已 pin——ReleaseSchemaPrecheckServiceImplTest,不重复手工)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E4-02 gate override 入口显隐(标签门控 myAuth)

- 出处:C4 R9(前端 override 管理:stmt FAILED 行入口+myAuth 门控);R4(GOV_SCHEMA_GATE_OVERRIDE order=6 tag=DBA+i18n 三文件);矩阵 §3.4 R4「e4/e8 显隐」。
- Purpose:override 入口仅对持标签账号可见(前端显隐),且入口只出现在 FAILED 语句行(非任意行)。
- Steps:1)DBA 甲打开 E4-D01 详情→FAILED stmt 行「门禁 Override」按钮可见(i18n 键 `gov-release-override-button`);2)账号丙(无标签)打开同页→按钮不可见;3)非 FAILED 行无入口;4)**显隐不代替拒绝**:丙 API 直调 overrideGrant→服务端拒绝(→E4-13 同族)。
- Expected:三面显隐正确;服务端拒绝兜底(前端隐藏≠安全边界)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E4-03 grant 弹窗字段与校验(理由必填/≤7 天/作用范围)

- 出处:C4 R5/R8(grant/list/revoke 端点+理由 NOT NULL+expireAt);前端 i18n 键组 `gov-release-override-*`(zh.json L4677-4689 实存:门禁类型 PRECHECK/DIFF、作用范围「仅本语句/整单」、理由必填 ≤1024、有效期 hint「最长 7 天;一次性消费,放行一次后失效」)。
- Purpose:弹窗字段完备性与前端校验:理由空→拒绝提交(`gov-release-override-reason-required`);有效期超 7 天→拒绝;门禁类型/作用范围选项正确。
- Steps:1)DBA 甲开弹窗核对字段与 hint 原文;2)理由留空提交→前端校验文案;3)有效期选 8 天后→拒绝;4)合法值提交→成功+HIGH 授权审计行(→e8);5)overrideList 区块状态 PENDING 显示。
- Expected:校验三面+审计留痕;记录列表含理由/有效期/状态(「待消费」)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E4-04 一次性消费与状态流转(PENDING→CONSUMED→重新 grant)

- 出处:C4 R6(gate 消费:未过期未消费→降级放行+RELEASE_SCHEMA_GATE_OVERRIDE 事件+一次性 CAS);prod-verification 增量 N2;IS-D3-3 步骤 3/4(实操权威)。
- Purpose:override 消费后不可复用;第二次派发需重新 grant;revoke 可用(PENDING 行)。
- Steps:引 IS-D3-3 步骤 2~4 执行;本域 UI 核对:1)消费后 overrideList 行状态「已消费」;2)事件时间线 `RELEASE_SCHEMA_GATE_OVERRIDE` 中文映射;3)再次派发同闸→仍拒(需新 grant);4)另一 PENDING 行 revoke→列表移除+REVOKE 审计。
- Expected:一次性语义 UI 三面齐;CAS 并发面(双请求同消费一次)归①自动化(SchemaGateOverrideServiceTest 20)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E4-05 break-glass KV 仅降 UNVERIFIABLE(两开关)

- 出处:C4 R7(govReleaseSchemaPrecheckBlock/govReleaseSchemaDiffBlock 注册 RootUserConfig 默认 true;break-glass 仅降 UNVERIFIABLE 不降确认违规);矩阵 §3.4 R7;prod-verification 增量 N2 末句。
- Purpose:KV 关闭时 UNVERIFIABLE 降级放行(应急逃生门),**确认违规(PRECHECK_FAILED/DIFF 实锤漂移)仍拒**——开关不是全局旁路。
- Steps:1)root 改 govReleaseSchemaPrecheckBlock=false(记录原值);2)E4-D01 同型重派发→UNVERIFIABLE 放行(事件留痕口径核对);3)构造确认违规样本(预检实锤 BLOCK 类)→仍拒;4)恢复 KV=true→UNVERIFIABLE 重新拒绝。
- Expected:双向行为符合 ADR-011 break-glass 语义;KV 改动本身留审计(root 配置面)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

### 二、C7 冻结计划+段感知派发+route override(②UI 面;真库=IS-C7-1~6)

#### E4-06 冻结计划区块与 manifest 一致(逐语句段序列)

- 出处:C7 R10(前端复核面:冻结计划区块=段序列/类型/表/参数);`f6180cb7`;IS-C7-6 步骤 1(浏览器验收权威);i18n 键 `gov-release-frozen-plan-title`=「冻结段计划」、`gov-release-plan-hash`。
- Purpose:发布单详情冻结计划区块与 `dm_prod_release_stmt_segment` manifest 一致;plan_hash 显示可与库中值口径核对(pin 值非凭据可记录)。
- Steps:1)E4-D02/D03 详情页展开冻结计划区块;2)逐段核对(段类型 AUTOEXEC/GHOST/DML_BATCH、表名、参数、stmtRange);3)`SELECT plan_hash FROM dm_prod_release_stmt WHERE id=…` 口径核对 UI 显示;4)legacy 无段计划发布单(存量)→区块不渲染(诚实降级,null-plan 走 legacy DIRECT)。
- Expected:区块=manifest 投影;hash pin 一致;存量单零变化。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E4-07 DML_BATCH 阻断横幅措辞核对

- 出处:C7 R5(阻断披露段号/表/估算行/源工单)+R10(横幅);i18n 键 `gov-release-dml-batch-block-banner`(zh.json L4717 实存,含「发布链暂未接入分批执行体,派发时将阻断(**绝不静默直发**)。出路:授予路由 Override…或将语句退回源工单处理」);ADR-013 裁决 3;IS-C7-5 步骤 1。
- Purpose:含 DML_BATCH 段的语句在详情显示阻断横幅,措辞**逐字**符合交付文案(两条出路明示;O-C7-1 开放=release 侧无批执行体的如实披露)。
- Steps:1)E4-D02 详情核对横幅原文(zh/en);2)派发后 stmt FAILED+事件 `RELEASE_ROUTE_UNSUPPORTED`(披露 eventData:段号/表/估算行/源工单)时间线核对;3)横幅两出路入口可达(route override→E4-09;returnStmt→E4-19)。
- Expected:横幅/事件/出路三面齐;绝无静默直发或自动降级。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E4-08 route override 入口显隐+双标签前端隔离

- 出处:C7 R8(GOV_ROUTE_OVERRIDE order=7 tag=DBA,与 C4 标签完全隔离);R10(override 入口权限显隐);IS-C7-6 步骤 3(两入口互不串位)。
- Purpose:「路由 Override」按钮仅 GOV_ROUTE_OVERRIDE 持有者可见;**仅持 GOV_SCHEMA_GATE_OVERRIDE 的 DBA 甲看不到路由入口**(反之乙看不到门禁入口)——前端面双表隔离。
- Steps:1)DBA 乙打开 E4-D02→「路由 Override」可见(`gov-release-route-override-button`)、「门禁 Override」不可见;2)DBA 甲→相反;3)丙→两者均不可见;4)两按钮位置/文案不串位。
- Expected:四账号显隐矩阵正确;服务端拒绝兜底→E4-13。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E4-09 route override 弹窗:风险提示+理由+限时

- 出处:C7 R8(理由必填 ≤1024+有效期 ≤7 天+一次性 CAS+HIGH 审计+covered_seg_idxs);i18n 键组 `gov-release-route-override-*`(zh.json L4718-4734 实存:risk-hint「放行该语句的一个被阻断路段(DML_BATCH 不支持 或 GHOST 能力复核失败),按单条直发重放——显式接受锁/超时风险;一次性消费,理由必填」、expire-invalid「有效期须在未来 7 天内」、状态 PENDING/CONSUMED/EXPIRED「待消费/已消费/已过期」);IS-C7-5 步骤 2~7(实操权威)。
- Purpose:弹窗字段/校验/记录列表(含状态三值与「语句」作用域列)符合交付;grant 成功提示与审计留痕。
- Steps:1)DBA 乙开弹窗核对 risk-hint 原文;2)理由空/超期校验;3)合法提交→成功提示+HIGH 审计(→e8)+列表 PENDING 行;4)重派发消费→CONSUMED+事件 `RELEASE_ROUTE_OVERRIDE`(eventData 含覆盖 segIdx 清单)+该段按 AUTOEXEC 显式直发;5)已消费后再派发→需重新 grant;6)活性唯一:同 (releaseId,stmtId) 已有 PENDING 再 grant→服务层友好拒绝(V202610130004 生成列唯一兜底,MIG-8 交叉)。
- Expected:IS-C7-5 预期同款;EXPIRED 行读时派生不消费。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E4-10 六新事件时间线中文映射(枚举全覆盖契约)

- 出处:C4 R8+C7 R10(govEventConstants 枚举全覆盖,无 raw key);`govEventConstants.js` L46-52 实存(RELEASE_SCHEMA_GATE_UNVERIFIABLE/RELEASE_SCHEMA_GATE_OVERRIDE/RELEASE_ROUTE_UNSUPPORTED/RELEASE_ROUTE_CAPABILITY/RELEASE_PLAN_DRIFT/RELEASE_ROUTE_OVERRIDE);zh.json `gov-event-type-RELEASE_PLAN_DRIFT`=「冻结计划漂移(已拒发)」。
- Purpose:两波次六个新事件在工单/发布单时间线均有中文映射+图标,无 raw key 裸露(i18n 契约)。
- Steps:1)汇集 E4-01/04/07/09/12 与 IS-C7-3 产生的事件样本;2)时间线逐一目检 zh/en;3)对照 govEventConstants 全枚举 grep 前端无遗漏映射(口径:枚举键数=映射表键数)。
- Expected:六事件双语映射齐;图标语义正确(拒绝类=close/warning 系)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E4-11 门禁序四闸的拒绝事件对应(hash→plan→precheck→diff)

- 出处:C7 R7(三派发点门禁序;首拒即停,昂贵检查不在便宜闸拒绝后执行);ADR-013 M3;prod-verification 增量 N4;矩阵 §3.7 R7。
- Purpose:UI 观察面=每闸拒绝有专属事件/文案,可按事件类型反推拒绝闸位(hash 闸=既有 executionKey 语义、plan 闸=RELEASE_PLAN_DRIFT、precheck 闸=SCHEMA_GATE 族、diff 闸=漂移比对事件)——执行顺序本身归①自动化(1763 时点 pin)+③IS-C7 实操。
- Steps:1)以 E4-D01(UNVERIFIABLE)/E4-D04(drift)样本触发不同闸位;2)时间线事件类型与闸位对应表核对;3)drift 拒绝后确认 precheck/diff 未执行(N4「plan 拒绝后不再执行」口径:无后续闸事件)。
- Expected:闸位↔事件一一对应;首拒即停可观察。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E4-12 PLAN_DRIFT 永不可 override(结构保证 UI 验证)

- 出处:C7 R7(RELEASE_PLAN_DRIFT 永不可 override=完整性类;门禁结构不引用 override 服务);prod-verification 增量 N4(「grant route override 后重派发仍拒」);矩阵 P0-4 行。
- Purpose:漂移拒绝面无任何 override 出路——UI 无入口、API 直调 route/gate override 后重派发仍拒(结构保证,非配置项)。
- Steps:1)隔离测试库构造 E4-D04(篡改 segment plan 列,改前记录原值)→派发/retry→stmt FAILED「Plan drift」+事件;2)详情页核对**无** override 入口出现在 drift 拒绝行;3)DBA 乙对同 (releaseId,stmtId) grant route override(API 或可用入口)→重派发→**仍拒**(override 不被 drift 闸消费);4)恢复原值→正常。
- Expected:drift=终局拒绝(唯一出路=回工单链重新冻结);override 行未被消费或消费后仍拒(以事件为准记录实际形态)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E4-13 override 双表隔离服务端拒绝(API 直调)

- 出处:C4 R4+C7 R8+C8 M5.3(两表/两标签/两事件族完全隔离,互不替代);prod-verification 增量 N3;IS-C7-5 步骤 6;矩阵 F-6 行;e8 E8-13 双登记(权限面归属 e8,发布链语境在本域)。
- Purpose:前端隐藏不代替后端拒绝——仅持一标签的账号直调另一族端点→服务端拒绝;两事件族不串写。
- Steps:1)DBA 甲(仅 gate 标签)curl/直调 `routeOverrideGrant`→拒绝(权限错误码,非 not-found);2)DBA 乙(仅 route 标签)直调 `overrideGrant`→拒绝;3)两方向审计核对:拒绝不落授权审计(无 grant 成功行);4)跨租户:另一租户主账号直调→租户校验拒绝(puid==release.primaryUid,C4 R5/C7 M3.3)。
- Expected:四方向全拒;错误语义不泄漏存在性;两 override 表无串位行(口径查询)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

### 三、既有真库门禁载体收编索引(执行载体=手册,不复制)

#### E4-14 安全底线 8 条收编索引

- 出处:prod-verification.md「安全底线 8 条逐条验证方法」(每条含构造篡改步骤+核查点);矩阵 P0-2/P0-4 行;G9 增量节首声明「上方安全底线 8 条…零削弱、全部继续有效」。
- Purpose:登记执行载体路由:8 条(无冻结不能生产/hash 不一致/PRE 未成功/无 PROD 权限/无审批/Preflight 不过/配置不合成分路由/路径 B 违规)在统一联调按手册逐条执行,证据回填手册+矩阵 P0-2 行。
- Steps:执行=手册该章原文;本用例核对点=C 波次后 8 条语境未被新增门禁削弱(N1~N6 为**叠加**,非替代)。
- Expected:8 条全执行(或按手册 Skip Conditions 豁免记录);任何一条不过=父任务不收官(P0-9/父 prd A1)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E4-15 DENY 矩阵 12 case 抽查索引(④⑦⑫)

- 出处:prod-verification.md「DENY 矩阵 12 case 真实环境抽查点」(真实环境抽查=④整单 hash 篡改/⑦绑定被换/⑫执行配置篡改;其余 9 case ①自动化充分覆盖)。
- Purpose:登记三抽查 case 执行路由;service 级 12 case 断言归①(不重复)。
- Steps:执行=手册表内「真实环境操作步骤」列(隔离测试库);证据回填手册行+矩阵 P0-2 行。
- Expected:三 case DENY 位置与事件与手册一致(G2_hash/G3_binding/G6_config)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E4-16 C 波次增量门禁 N1~N6 抽查索引(G9 增补节)

- 出处:prod-verification.md「C4~C10 波次增量门禁抽查点」(N1 UNVERIFIABLE/N2 override 一次性/N3 双表隔离/N4 plan drift/N5 段阻断→直发/N6 审计详情端点;2026-10-14 C9 M1 增补,只增不删);完整执行载体=integration-scenarios(IS-C7-1~6、IS-D3-3/4/5/7、IS-SEC-5、IS-MASK-6)。
- Purpose:登记 N1~N6 与 IS-* 载体的路由关系(两处描述不一致时以 integration-scenarios 执行块为准——该节首声明口径)。
- Steps:执行=按 N 行「出处」列跳转对应 IS 场景;N6 归 e8(E8-02~04)执行。
- Expected:N1~N6 全有执行记录(本域 N1~N5;N6→e8);矩阵 P0-2/P0-3/P0-4/P0-7/F-6 行证据汇聚。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

### 四、发布链既有功能面回归

#### E4-17 台账列表/详情冒烟

- 出处:survey E4(GovLedgerController/ledger.vue);tests/frontend/governance/governance-flow.md(既有流程引用)。
- Purpose:台账两分支(PRE_DDL 已执行/PROD_DML WAIT_RELEASE 池)列表过滤、详情、C7 后新增段计划披露列(如 dmlBatchRouted/ghostRouted 旗标)正常;C7 段化不改台账语义(R9 gate 粒度保持)。
- Steps:1)台账列表按类型/状态过滤;2)详情打开(含旗标显示);3)开放读对照(ledger/list 同数据面→e9 E9-09);4)列宽拖拽长字段列(→IS-D3-12 载体 table_column_resize.md 发布单/台账套件交叉)。
- Expected:列表/详情/旗标三面正常;租户隔离(仅本租户行)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E4-18 治理前端流程文档复测索引(3 篇)

- 出处:tests/frontend/governance/governance-flow.md(全链路)、proddml_via_release.md(VIA_RELEASE 双分支)、release_return_resubmit.md(退回重提+开放全员创建)。
- Purpose:C4/C7 在 releaseDetail.vue 叠加新区块后,三篇既有流程零回归(引用执行,不重写)。
- Steps:按三篇文档套件执行;附加核对:新区块(冻结计划/横幅/override 管理)不破坏既有布局与操作链。
- Expected:三篇通过;新旧面共存。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E4-19 发布单生命周期链(create→approve→execute→终态+return/resubmit/cancel)

- 出处:survey E4(GovReleaseController 全端点);release_return_resubmit.md;IS-C7-2(执行链真库载体)。
- Purpose:E4-D05 正常链全生命周期 UI 走查:创建(merge propagate 段计划冻结)、审批、确认执行、段推进至 SUCCESS、时间线完整;退回/重提/取消三人工路径可用(returnStmt 语句级退回=出路之二,E4-07 交叉)。
- Steps:1)create→详情四区块(语句/段计划/事件/override 记录)核对;2)审批→执行→SUCCESS(SUCCESS 段永不重放,IS-C7-2 步骤 5 交叉);3)returnStmt 单语句退回源工单链;4)return 整单退回→resubmit 重提(段计划重填,C7 R2);5)cancel 终态。
- Expected:状态机与库事实可对账(P0-8);四路径级联删/重填语义正确(resubmit 后新段计划与源工单一致)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E4-20 黄旗收窄核对(阻断态不再黄旗化)

- 出处:C7 M3(`f6180cb7` 交付说明「黄旗收窄」);IS-C7-6 步骤 5;lockfree-dml spec §10.1 注记(release-chain DML_BATCH 段 BLOCKED 取代「直发+黄旗警告」形态,披露键保留)。
- Purpose:黄旗仅存**披露**语义(路由提示),阻断态由横幅+FAILED 表达——不再以黄旗暗示「可继续」。
- Steps:1)E4-D02 预检/详情黄旗面核对(工单链侧黄旗=分批路由披露,e5 交叉);2)发布链侧阻断=横幅+FAILED(无黄旗化降级表达);3)legacy 无段计划单的既有黄旗提示不变(replay hints 限 plan-less routed stmts)。
- Expected:黄旗/阻断两表达分明;披露键(dmlBatchRouted 等)保留。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E4-21 发布链审计可检索面(release_id 链列)

- 出处:C8(四列+链检索;logging-guidelines 写点矩阵:GHOST_EXECUTE release 链/DML_BATCH/override GRANT/REVOKE/ROUTE_OVERRIDE_CONSUME 均带 releaseId);矩阵 §3.8 R4;e8 E8-05/06 交叉。
- Purpose:本域产生的全部审计行(override grant/revoke/consume、GHOST_EXECUTE release 侧、段派发)在审计页可按 releaseId 过滤命中,行内「发布 <id>」链接跳回详情页(双向可达)。
- Steps:1)E4-04/09/12 执行后,审计页按 releaseId 过滤;2)channel 列核对(CONSOLE=人工 grant/SYSTEM_AUTO=消费与派发);3)链接跳转回发布单详情;4)fingerprint 列在 GHOST_EXECUTE/DML_BATCH 行存在且可与段 sqlText hash 口径核对(值不回显,等值比较)。
- Expected:按 releaseId 检索全命中;channel/fingerprint/链接三面正确(详情=e8 承载)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

## Cleanup

1. E4-D01~D05 发布单走 cancel/终态正常链;恢复 E4-D01 目标库连接/权限、E4-D04 被篡改 plan 列原值(改前记录);
2. override 测试行:CONSUMED/EXPIRED 自然留存(只增),PENDING 行 revoke 清理;
3. break-glass KV 恢复默认 true(E4-05);
4. 测试表 DROP;账号标签临时授予回收。

## Skip Conditions

- 大表造数不具备→E4-D02/D03 相关用例(E4-06~09/12/20)SKIP,替代覆盖=IS-C7-5/6 执行轮补(记录替代口径);
- 隔离测试库不可得→E4-12 篡改臂 SKIP(N4 由 prod-verification 抽查轮覆盖);
- 双 DBA 标签账号不可得→E4-08/13 显隐矩阵降级为单标签+无标签两账号执行并记录缺口;
- 未执行、失败或环境阻塞的场景不得标记为通过(TEMPLATE.md 纪律)。
