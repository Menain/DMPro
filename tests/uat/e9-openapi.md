# E9 开放 API · UAT 用例(C9 M2)

> 三分类中本文件承载**②UAT 手工用例**(README §4);真桥接三途径联调=**③IS-D3-1**(手册 §1~§10)、5A 命令面场景=**③IS-D3-2**(手册 §5A,本域 E9-07 交叉引用不复制)。
> 证据字段全部留位(红线 3);五元组格式=README §5;**凭据纪律(本域重灾区)**:AK/SK、签名串、curl 凭据一律环境变量注入,零入档(手册 ⛔ 红线 3 同款);证据只留请求口径(端点/参数名/响应码),不留请求头与 body 原文。
> **G7 口径注记(矩阵 §1 口径注记)**:端点全集=**15 个 mapping**(DbChangeOpenApi 实测,源码 grep 15 行);`docs/governance/openapi-orchestration.md` §2 标「14 个」因 `tickets/detail` 在 §2 表外以 §5.5 单注——本域用例以 15 端点为全集。`docs/reference/openapi-reference.cn.md` 为用户未跟踪文件(只读引用,不触碰——红线 6)。

## Purpose

验证开放 API 面(桥接/外部编排的服务账号通道)在 C 波次后的三维安全属性:**签名**(五参 HmacSHA1+时间戳新鲜度+body SHA-256,fail-closed)、**租户隔离**(not-found 语义,存在性不泄漏)、**守卫**(严格前置状态+五守卫链+拒绝也留痕 EXTERNAL_COMMAND_REJECTED);以及 additive 面(externalRound/skipOutbound/ledger/list)对旧调用方零破坏。

## Scope

- 入口:`DbChangeOpenApi`(`/api/open/dbchange/*`,15 mapping:orchestration §2.1 五读端点 A~E[tickets/tickets\/statements/releases\/detail/releases\/return/ledger\/list]+§2.2 九命令[approve/reject/cancel/return/execute/submit/resubmit/releases\/create/releases\/resubmit]+§5.5 tickets\/detail);`McpToolsController`(`/api/mcp`,@McpTool 注册面);旁系 `DataSourceApi`/`QueryApi`/BasicApi(不在本域抽查集,登记现状);
- 契约权威:`docs/governance/openapi-orchestration.md`(677 行,wc -l 实测:§3 签名/§4 幂等守卫[4.1 严格前置状态/4.3 租户隔离/4.5 flow-round]/§5 编排时序/§6 External 通道/§10 出站桥接/§11 三途径)+`docs/governance/openapi-dingtalk.md`;
- 鉴权:AK/SK 五参签名+时间戳新鲜度+body SHA-256(C5 核验「强于文档底线」,父 prd L14);服务账号发放面=E8(RDP_PRI_USER_AK_SK_R/W);
- 不覆盖:桥接侧行为(→e7/手册)、命令业务语义细节(→e3/e4)、审计检索面(→e8 E8-05/06/11)。

## 矩阵对应(②类承载对账)

| 矩阵行 | 本域承载 |
|---|---|
| 矩阵 §1 口径注记 G7(15 端点全集) | E9-01 |
| C2 行「拒绝也留痕」(①+③;UI/API 观察面本域登记) | E9-05/E9-06 |
| C5 R5(externalRound additive)/R1(flowId 守卫) | E9-07(执行载体=手册 5A,IS-D3-2) |
| 桥接 R5(ledger/list 租户隔离)/R12(skipOutbound additive) | E9-08/E9-09(手册 §4 交叉) |
| 硬门槛 P0-2(服务端拒绝且库无变更)/P0-5(回调幂等) | E9-02/03/05(开放面臂;钉钉回调面=手册 5A) |
| O-C10-5(submit channel 保真已知限制) | E9-11(观察登记,非缺陷) |
| ①自动化(不重复) | OpenApiSignerTest+GovOpenApiServiceImplTest 30+GovOpenApiLedgerListTest+ExternalCommandTest 27 |

## Preconditions

- 手册 §0.6 前置同款:平台为测试创建专用子账号+AK/SK(租户甲)+对照服务账号(租户乙)——AK/SK 值存于密码管理器/环境变量,零入档;
- 台账/工单样本就位(手册 0A.2 同型:E3/E4 域用例产生的工单与发布单可复用);
- curl/httpie 客户端+签名脚本(orchestration §3.6 伪代码实现,脚本存放路径登记但内容不含凭据——凭据运行时环境变量注入)。

## Test Data

| 编号 | 数据说明 | 构造方式 | 唯一标识 | 清理方式 |
|---|---|---|---|---|
| E9-D01 | 租户甲服务账号 AK/SK | 平台发放(E8 面) | 账号名 `codex_oapi_a_<ts>` | 禁用/删除账号 |
| E9-D02 | 租户乙对照 AK/SK | 同上 | `codex_oapi_b_<ts>` | 同上 |
| E9-D03 | 样本工单/发布单/台账条目 | 复用 e3/e4 用例产物或手册 0A.2 构造 | ticketId/releaseId 记录 | 走正常终态链 |

## Cases

#### E9-01 端点全集对账(15 mapping,G7 口径)

- 出处:survey G7(orchestration §2「14 个」vs 实测 15);DbChangeOpenApi 源码(15 @RequestMapping 实测);矩阵 §1 口径注记。
- Purpose:UAT 执行前对账端点全集:orchestration §2.1 五+§2.2 九+§5.5 tickets/detail=15;每端点在本域抽查矩阵(E9-02~E9-10)中有归属,无漏网端点。
- Steps:1)源码 grep 口径核对(`grep -c "RequestMapping(value" DbChangeOpenApi.java`=**16**,即 15 个端点 mapping+1 个类级前缀 `/dbchange`);2)orchestration §2/§5.5 与源码逐一对表;3)登记对账表(端点→本域用例号→手册章节);4)docs/reference/openapi-reference.cn.md 如可得,只读对照(用户未跟踪文件不修改)。
- Expected:15/15 对账一致;引用文档口径差(14 vs 15)已按 G7 注记解释,不判 FAIL。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E9-02 签名三维抽查(五参+时间戳新鲜度+body 摘要)

- 出处:orchestration §3(五参 HmacSHA1/§3.2 待签名串/§3.3 BodyDigest=body SHA-256/§3.4 服务端校验流程/§3.5 错误码矩阵);C5 核验「强于文档底线=fail-closed」(父 prd L14);OpenApiSignerTest(①)。
- Purpose:签名失败面三维抽查:①错误签名(篡改签名参数)②过期时间戳(超新鲜度窗口)③body 与摘要不符(签名后改 body 任一字节)→均拒绝且错误码符合 §3.5 矩阵;正确签名→通过。
- Steps:1)以 E9-D01 对 `tickets`(读端点 A)构造四维请求(正确/错签/旧时间戳/body 篡改);2)响应码+错误码逐一对照 §3.5;3)重放同一正确请求(新鲜度窗口内)→按 §4.4 重放安全口径记录实际行为(幂等守卫兜底面);4)凭据全程环境变量(命令行历史清理口径登记)。
- Expected:三失败维拒绝+错误码矩阵一致;fail-closed(校验失败绝不进入业务逻辑);库无变更(P0-2 开放面臂)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E9-03 租户隔离 not-found 语义(读+命令双臂)

- 出处:orchestration §4.3(租户隔离=not-found 语义,存在性不泄漏);手册 §4.1(ledger/list 空集实证)/§4.8(跨租户命令 not-found);GovOpenApiLedgerListTest(①);E8-03(审计面同款口径)。
- Purpose:租户乙 AK/SK 对租户甲对象:读端点→空集/ not-found(list 语义空集非报错),命令端点→not-found(非 403/非「无权限」——两种响应不可区分于「对象不存在」)。
- Steps:1)乙调 `tickets {dbName=甲库}` →空集;2)乙调 `ledger/list {}` →甲条目零出现(手册 4.1 同型);3)乙对甲 ticketId 调 approve/detail→not-found;4)乙对不存在 id 调同端点→响应形态与 3 一致(存在性不泄漏核对);5)甲自调对照→正常。
- Expected:四臂语义正确;响应时间/形态无存在性侧信道(目检口径)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E9-04 读端点五面冒烟(A/B/C/E+tickets/detail)

- 出处:orchestration §2.1+§5.5;手册 §4.3(ledger/list VO 恰 16 字段脱敏:无 ownerUid/无全量 SQL[sqlSummary≤200]/无 precheck/execDetail/无连接串凭据;ghostRouted/dmlBatchRouted 旗标随行)。
- Purpose:五读端点(tickets/tickets\/statements/releases\/detail/ledger\/list/tickets\/detail)正常返回且 VO 脱敏纪律保持(开放面 VO 是独立投影,不携带 console 内部字段)。
- Steps:1)以 E9-D03 样本逐端点调用;2)响应字段清单核对:ledger/list 恰 16 字段(手册 4.3 口径)、tickets/detail 含过程/活动/事件(§5.5 契约)、statements 含预检结果;3)全响应 grep 敏感模式(password/token/连接串特征/ownerUid)零命中;4)分页边界(pageNum<1 钳 1/pageSize<1 钳 20/越界空集,手册 4.2 口径)。
- Expected:五面可用;字段白名单纪律;零敏感泄漏;分页钳制正确。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E9-05 命令守卫矩阵抽查(严格前置状态,§4.1)

- 出处:orchestration §4.1(严格前置状态表:approve/reject/cancel=WAIT_APPROVAL、execute=WAIT_CONFIRM、return=EXEC_FAIL/WAIT_EXEC+FAILED 组、resubmit=REVISION 等)+§4.2 通道守卫+§4.4 重放安全;手册 §5.6(乱序拒绝抽验);ExternalCommandTest 27(①主战场,本域只做真环境抽查)。
- Purpose:乱序/终态/重复命令→拒绝且**零状态副作用**;拒绝错误码语义正确(GOV_OPEN_COMMAND_INVALID_STATUS 族);通道守卫(非 External 通道工单收命令→CHANNEL_MISMATCH,手册 2.7 同族)。
- Steps:1)对 WAIT_APPROVAL 工单调 execute(乱序)→拒绝;2)对 FINISHED 工单调 approve(终态)→拒绝(END_STATUS 守卫);3)重复 approve(第二次)→状态守卫拒(幂等面:第一次生效第二次拒绝,零双重副作用);4)对 Internal 环境工单调命令→CHANNEL_MISMATCH;5)每步后工单状态口径查询核对零变化。
- Expected:五臂拒绝+零副作用;错误码与 §4.1/§4.2 一致;与 e3 E3-05(五守卫 UI 观察面)互证。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E9-06 EXTERNAL_COMMAND_REJECTED 审计(拒绝也留痕)

- 出处:C2(`be0f2f8c`;GovEventType.EXTERNAL_COMMAND_REJECTED+REQUIRES_NEW 审计不随拒绝回滚丢失+eventData 白名单);C5 R8(execute/return 拒绝路径同落,O-C2-1 关闭);手册 5A.8(权威场景);logging-guidelines(DML_BATCH_REJECT 行 channel=到达通道真实:开放调用→OPENAPI)。
- Purpose:E9-05 每次拒绝在事件表/审计面留 EXTERNAL_COMMAND_REJECTED 行(eventData 白名单字段:command/reason/externalFlowId 等,toStatus=null 不变式);REQUIRES_NEW 语义=拒绝事务回滚不吞审计;跨租户拒绝(E9-03)**不落审计**(存在性不泄漏优先,手册 5A.8 口径)。
- Steps:1)E9-05 各拒绝后按 ticketId 检索审计/事件面(→e8 E8-06 过滤);2)eventData 字段白名单核对(无越权字段/无凭据/无 SQL 全文);3)跨租户臂核对零审计行;4)DML_BATCH_REJECT 对照(e5 E5-08 样本):经开放 submit 触发时 channel=OPENAPI、无 ticket_id(设计口径)。
- Expected:拒绝留痕三面齐;白名单纪律;跨租户零行。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E9-07 externalRound/externalFlowId additive 兼容(旧调用方零破坏)

- 出处:C5 R5(入站 externalRound additive 可选)+R1(flowId 一致性守卫宽松起步:缺失=跳过校验);手册 5A.5(最简调用冻结 pin=`tickets/approve {ticketId}` 无 flowId/round 照常成功)/5A.4(round 不符拒绝)/5A.9(strict 档 KV `govExternalCommandStrictFlow` 默认 false);IS-D3-2(③真桥接载体)。
- Purpose:开放命令 FO 的 additive 字段对未升级调用方零破坏(不带 externalRound/externalFlowId→宽松放行);带上则受守卫保护(不符→拒绝)。生产默认宽松档(strict=false),开启=运营决策(IS-D3-13 灰度评估)。
- Steps:1)最简调用 approve(仅 ticketId)→成功(5A.5 pin 真环境复证);2)带错误 externalRound→ROUND_MISMATCH 拒绝+留痕(E9-06);3)带正确 externalRound→成功;4)strict=true(测试环境临时,root KV,记录原值)→最简调用拒绝 FLOW_MISSING;恢复 false→放行(5A.9 双向);5)真桥接全链→引手册 5A(IS-D3-2),本用例只覆盖 AK/SK 直调臂。
- Expected:additive 三态(缺失/错误/正确)行为符合 ADR-012;strict 双向可用且生产默认恒 false。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E9-08 releases/create skipOutbound additive(途径 1 防重复建实例)

- 出处:桥接 R12/M4b(`96f2e70e`;开放 create FO additive 参数+透传+钩子守卫+5 钉;用户裁决 2026-10-10);orchestration §5.8/§10.6 末行/§11.1;手册 §1.3/§4.4(权威场景);ResubmitOutboundResetTest(①)。
- Purpose:`releases/create` 带 `skipOutbound=true`→出站零触发(审批行 outbound_status 停留 NULL、桥接侧零 flows/create)+发布单照常落库;不带/false→与 console 创建同构出站(service 层单挂点双入口同覆盖);未带参数旧调用方兜底=桥接 (bizType,bizId) 幂等映射回原流程(零破坏)。
- Steps:引手册 §1.3+§4.4 执行(桥接联调轮,③);本域登记 AK/SK 直调臂:1)带 true 创建→发布单成+outbound NULL(口径查询);2)不带创建→outbound 状态按开关语境(开=STARTED/关=NULL,与 e7 止血开关面一致);3)旧 FO(无该字段)反序列化正常(additive 零破坏)。
- Expected:三臂符合 M4b 语义;执行证据与手册 §4.4 双回填。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E9-09 ledger/list 租户隔离+分页+脱敏(读端点 E)

- 出处:桥接 R5(`1ec7ddf7`;AK/SK puid 强制租户隔离,绝不继承 console 无过滤+分页过滤对齐+VO 脱敏+@McpTool);手册 §4.1~4.3(权威场景);GovOpenApiLedgerListTest(①);orchestration §5.10。
- Purpose:途径 1 数据源端点三维(隔离/分页/脱敏)真环境抽查——本用例与手册 §4.1~4.3 同源,执行以手册为准,此处登记 UAT 归属与证据双回填口径。
- Steps:执行=手册 §4.1(双租户空集)/§4.2(过滤与分页边界:ticketType 精确匹配非法值空集、promoted 三态、pageNum/pageSize 钳制、无 total 字段=空页翻页尽)/§4.3(16 字段脱敏);本域补充:与 e4 E4-17(console 台账面)数据一致性对照(同一条目两面字段语义一致)。
- Expected:手册三维预期原文;console/开放两面一致性成立。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E9-10 MCP 注册面抽查(@McpTool 全集)

- 出处:survey E9(McpToolsController `/api/mcp`,@McpTool 注册面);DbChangeOpenApi 源码(15 端点均带 @McpTool 注解,i18n key M_DBCHANGE_* 实存)。
- Purpose:15 端点的 MCP 工具注册完整(i18n key 无缺失=启动无 log.error 噪声,database-guidelines i18n Gotcha 同族);MCP 面调用与 HTTP 面同守卫(签名/租户/前置状态不因入口不同而放松)。
- Steps:1)MCP 工具清单口径核对(注册数=15,i18n 键双语存在);2)经 MCP 面(如环境可达)调一读端点→与 HTTP 面同响应;3)MCP 面不可达→SKIP 登记(替代验证=@McpTool 注解与 HTTP 端点一一对账,源码口径)。
- Expected:注册全集齐;守卫同构或 SKIP 有替代。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E9-11 O-C10-5 submit channel 保真观察(已知口径限制登记)

- 出处:O-C10-5(矩阵/survey D1 开放:openapi submit 与 console 共享单一 public submit 入口→ROLLBACK_MATERIAL 审计行 channel=CONSOLE 而非 OPENAPI;additive 接口拆分留观察);governance-contracts C10 审计条目「Known caliber limitation (registered)」。
- Purpose:联调期观察登记该已知限制的实际影响面(开放 submit 产生的材料变更行 channel 语境),为后续是否拆分接口提供使用数据——**非缺陷,不判 FAIL**;现状如实记录。
- Steps:1)经开放 `tickets/submit` 代提交一单(带手填回滚 SQL);2)定位 ROLLBACK_MATERIAL 行→channel=CONSOLE(共享入口 URI 推断结果);3)对照开放 `tickets/resubmit` 臂→channel=OPENAPI(faithful,既有 openCommand 判别);4)登记「开放 submit 使用频率」观察数据(试用通告/后续任务优先级素材)。
- Expected:两臂 channel 与登记口径一致;观察数据回流 O-C10-5 处置评估。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

## Cleanup

1. E9-D01/D02 服务账号禁用/AK-SK 吊销(平台发放面回收);
2. strict KV(E9-07 步骤 4)恢复 false;
3. shell 历史中含凭据环境变量的会话清理口径登记(凭据零入档收尾);
4. 样本工单/发布单走正常终态链(复用 e3/e4 Cleanup)。

## Skip Conditions

- AK/SK 发放面不可用→本域全部 SKIP(前置=手册 §0.6,桥接联调轮具备);
- 桥接服务不可得→E9-08 出站观察臂降级为 outbound_status 口径核对(桥接侧请求日志断言留 IS-D3-1);
- MCP 面不可达→E9-10 按步骤 3 替代验证;
- 未执行、失败或环境阻塞的场景不得标记为通过(TEMPLATE.md 纪律);**开放面命令用例不得以 ExternalCommandTest 计数(①)冲抵真环境抽查**(裁决 2 边界:本域为②真环境 API 面,③真桥接面在手册)。
