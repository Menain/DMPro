# 钉钉审批桥接集成联调手册

> 覆盖:出站发起主链(途径 2/3)/ 失败补偿 / 止血开关 / 途径 1 全链(台账开放读+组合命令)/ 途径 3 全链(双选项+退回映射)/ **审批轮次绑定(ADR-012,5A 章,项目末尾统一联调)** / 撤销通知 / 直连通道退役 / 前端三面浏览器验收 / 运维处置。
> 代码基线:4 笔提交(`ba6bd748` 出站基建 / `1ec7ddf7` 台账开放读 / `d5386a86` 直连 deprecated / `11418b0f` 前端三面)+ M4 文档面(`67afc8ee`)+ **M4b skipOutbound 补丁**(2026-10-10,**已提交 `96f2e70e`**:开放 create FO additive 参数 + 透传 + 钩子守卫 + 5 钉)。〔措辞修正 2026-10-14,C9 G5:原「待提交」为过时表述〕
> 自动化基线(2026-10-14 更新,HEAD=`9a0e6352`,C9 G5 修正):`:cgdm-console:test` 124 类 / 1876 / 0 失败;全后端 2438 / 0 失败 / 10 skip(插件模块预存)——桥接七套 106 钉(29/11/24/18/5/13/6)+ GovOpenApiServiceImplTest 2 透传钉背书,本手册聚焦**真实桥接联调 + 浏览器面**。(M4b 提交时点 2026-10-10 旧基线:114 类 / 1604,七套 102 钉;全后端 2157 / 0 / skip 10;桥接面自 `96f2e70e` 后仅 C5 additive 改动——出站 context 携带 round(`4c31d85c`,OutboundService +3 钉/ResubmitOutboundReset +1 钉),手册场景语义不变;其余基线增量为 C1~C10 波次)
> 契约参考:`docs/governance/openapi-orchestration.md` §10(出站桥接)/§11(三途径时序);`.trellis/spec/backend/governance-contracts.md`(出站桥接契约段)。

## ⛔ 红线(执行前必读)

1. **钉钉联调只用测试群/测试审批模板**:桥接项目持有唯一生产钉钉 app 凭据——联调全程不得触碰生产钉钉流程/生产审批模板;
2. **不在生产环境做创建/删除/批量/极限测试**:真库操作只在腾讯云/阿里云 MySQL 8.0 与 PG 15 测试实例;
3. **密码/令牌/AK/SK/桥接 token 不写入任何文档、截图、工单描述、记录表**(curl 验证凭据用环境变量注入);
4. **进程 kill/重启由用户执行**(后端启动 cwd 必须 `backend/`;Claude 只提示不代跑);
5. **手工改平台元数据(第 9 章修复 SQL)仅限测试环境**,改前记录原值,UPDATE 必须带 `gmt_modified = gmt_modified` 中和(该列带 ON UPDATE CURRENT_TIMESTAMP 且兼作调度器 5s 静默窗租约)。

---

## 0. 部署前置(缺一不可)

| # | 项目 | 操作 | 通过标准 |
|---|---|---|---|
| 0.1 | 宿主重建 | `gradlew -p backend :boot-alone:installDist`;重启由用户执行(**cwd 必须 `backend/`**) | 后端起新版;启动日志无新增 ERROR |
| 0.2 | DDL 迁移验证 | 元数据库 `SHOW COLUMNS FROM dm_approval LIKE 'outbound%';` | 四列齐:`outbound_status` varchar(32) / `outbound_error` varchar(512) / `outbound_retry_count` int default 0 / `outbound_next_retry_at` datetime;迁移 `V202610090001` 幂等(重复启动 1060 忽略,无 ERROR) |
| 0.3 | 前端包 | (如前端未随包)`env -C package bash ./all_build.sh web` | BUILD SUCCESSFUL;强刷后系统设置页出现「钉钉审批桥接」区块 |
| 0.4 | KV 四键配置 | `/system/ghost/settings`「钉钉审批桥接」区块:桥接服务地址 / 桥接认证令牌 / 出站超时(默认 10000)/ 出站发起开关(**联调前保持关闭**,按章节需要开启) | 保存成功;令牌不回显(已配置 → 「已配置,留空保持不变」占位);超时越界(如 999/60001)前端拒绝 |
| 0.5 | 桥接侧就位 | 桥接服务运行:`POST {baseUrl}/flows/create`、`/flows/cancel` 可用;`X-Bridge-Auth` 校验约定一致;(bizType,bizId) 去重 + **第二轮重建语义**(orchestration §10.2)已实现;途径 1 的 `releases/create` 调用侧已实现**携带 `skipOutbound=true`**(orchestration §5.8/§11.1,M4b 补丁);测试钉钉 app/测试审批模板配置完成 | 手工 curl 连通(凭据走环境变量,不落文档);桥接侧请求日志可观察(后续断言用) |
| 0.6 | AK/SK 服务账号 | open-cdm 平台为桥接创建专用子账号 + AK/SK(入站命令面 + ledger/list 用) | 五参签名(orchestration §3)调 `/api/open/dbchange/ledger/list` 返回 200 |
| 0.7 | 环境参数 | 测试环境 `SQL_TICKET_INFO` 配 `{"type":"External","openTicket":true}`(`/env` 环境参数页) | 类型下拉可见 External 行可选;DingTalk/Feishu/Wechat 三行置灰(第 7 章细验) |
| 0.8 | 告警通道 | `/integrations/im` 配测试群机器人 webhook+secret,enable;`alertPushEnable=true` | 测试推送可达测试群 |

## 0A. 前置准备(一次性)

| # | 项目 | 说明 |
|---|---|---|
| 0A.1 | 测试数据源与库对 | 至少一个 PROD 环境 pair(External 通道环境参数已配);PRE 环境 pair(造台账 PRE_DDL 条目用) |
| 0A.2 | 台账条目 | ①已执行完成的 PRE_DDL 工单 ≥2 条(FINISHED+全语句 SUCCESS);②VIA_RELEASE 进池的 PROD_DML 工单 ≥2 条(WAIT_RELEASE)——途径 1 / ledger/list 数据源 |
| 0A.3 | 双租户账号 | 租户甲:主账号 + 子账号(桥接 AK/SK 挂甲);租户乙:另一主账号 + 服务账号(AK/SK)——跨租户隔离实证用 |
| 0A.4 | 权限账号 | 账号丙:无 `RDP_WORKER_ORDER_REQUEST`/`RDP_WORKER_ORDER_APPROVE`(面板按钮不可见实证) |
| 0A.5 | 元数据库访问 | 可查询 `dm_approval`(出站四列 + appro_identity 取证)、`dm_db_change_event`(事件留痕) |
| 0A.6 | 桥接侧观察面 | 桥接请求日志(create/cancel 到达记录、X-Bridge-Auth 头、幂等去重命中);或桥接 mock 模式(可控返回 2xx/4xx/5xx/超时) |

---

## 1. 出站发起主链(途径 2/3 · 验收 A1)

前置:0.4 开关**开启** + baseUrl/token 已配;环境 = External。

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 1.1 | **途径 3 工单出站(核心)** | `/ticket_create?govV2=1` 提交 PROD_DML 工单,DIRECT「钉钉审批(外部流程)」→ 提交 | ①提交成功、响应耗时无可感知增加(桥接健康时);②工单 WAIT_APPROVAL;③详情页出站区数秒内呈「已发起」+钉钉流程号;④元数据库 `outbound_status='STARTED'`、`appro_identity`=桥接 flowId;⑤桥接侧收到 flows/create:`bizType=TICKET`、`bizId=<ticketId>`、summary≤200 字符、X-Bridge-Auth 头到达 |
| 1.2 | **途径 2 发布单出站(核心)** | `/dbChange/ledger` 选台账建发布单(页面入口) | 发布单 APPROVING(审批工单 WAIT_APPROVAL);审批工单详情面板 STARTED;桥接侧收到 `bizType=RELEASE`、`bizId=<releaseId>`、`bizNo=<releaseNo>` |
| 1.3 | open 面同构 + skipOutbound(M4b) | 桥接以 AK/SK 调 `/api/open/dbchange/releases/create`(0A.2 台账条目):①不带 `skipOutbound`(或 false);②带 `skipOutbound=true` | ①与 1.2 同构出站(service 层单挂点,console/open 双入口同覆盖);②**出站零触发**(桥接侧零 flows/create 请求、审批行 `outbound_status` 停留 NULL)+发布单照常落库——途径 1 专用显式参数(orchestration §5.8/§10.6 末行),取代「桥接侧幂等处置」措辞;未带参数的兜底=桥接按 §10.2 幂等映射回原钉钉流程 |
| 1.4 | flowId 双源先到先得 | (可选,需桥接配合延迟响应)入站 `tickets/approve` 携带 `externalFlowId` 先于出站响应到达 | `appro_identity` = 入站值(仅空时写,先到先得);出站回填跳过但 STARTED 照落;后端日志「already set (inbound first)」 |
| 1.5 | 主链不阻塞实证 | 桥接 mock 延迟 8s 响应 → 提交工单 | 工单照常落库 WAIT_APPROVAL(事务先提交);submit HTTP 响应延迟 ≈ 桥接耗时(afterCommit 同步发送,上限见 9.3);桥接超时 → FAILED 但工单状态不受影响 |
| 1.6 | 幂等防双发 | 对 STARTED 行:①页面「重新发起」按钮;②(如可达)重复触发 | ①按钮置灰(ALREADY_STARTED 镜像);API 强调 reissueFlow → 拒 `DING_BRIDGE_REISSUE_ALREADY_STARTED`;②桥接侧同 (bizType,bizId) 去重返回同 flowId(`duplicated=true`),**钉钉测试群无第二个审批实例**(桥接侧断言) |
| 1.7 | 不适用行零出站 | 提交 Internal 环境工单 / PRE_DDL 工单 / VIA_RELEASE 工单各一 | `outbound_status` 恒 NULL;详情页无出站区(非 External 隐藏);桥接侧零请求 |

## 2. 失败补偿(验收 A2)

前置:桥接 mock 可控故障(或临时改 baseUrl 为不可达端口)。

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 2.1 | **不可达 → FAILED+告警(核心)** | baseUrl 改为不可达地址(记录原值)→ 提交 External DIRECT 工单 | 工单照常落库 WAIT_APPROVAL(主链零影响);`outbound_status='FAILED'`+`outbound_error` 含根因;面板「发起失败」+原因;测试群告警一次(DING_BRIDGE_OUTBOUND_FAILED,retry 文案) |
| 2.2 | **sweep 退避实证** | 恢复 baseUrl → 观察该行自动重试(不手动干预) | `outbound_retry_count` 1→2→3 递增,`outbound_next_retry_at` 间隔 ≈1m/5m/15m(元数据库时间戳取证 + 后端 `[DingBridge]` 日志);恢复后首次重试成功 → STARTED+flowId 回填 |
| 2.3 | 上限停扫+终态告警 | 保持桥接不可达直至 3 次耗尽 | `retry_count=3`、`next_retry_at=NULL`(脱扫);终态告警一次(final 文案);此后 sweep 静默(日志不再出现该行);中途重试**不重复告警**(防轰炸:首转 FAILED 一次+终态一次) |
| 2.4 | **手动重发成功链** | 2.3 后恢复桥接 → 详情页「重新发起」 | 同步返回成功;`retry_count` 重置 0 → STARTED+flowId;面板回读「已发起」(既有刷新链,零手工刷新可接受 5s 内) |
| 2.5 | 手动重发仍失败 | 桥接仍不可达时点「重新发起」 | 错误弹窗「重新发起失败: <落库原因>」(REISSUE_FAILED 携带落库原因);面板呈 FAILED+新原因;不重复告警(同步报错即反馈) |
| 2.6 | 4xx 契约错不重试 | 桥接 mock 返回 400 → 触发发起 | FAILED+终态告警一次;`next_retry_at=NULL` **不进 sweep**(无自动重试);手动重发可续(修复契约后) |
| 2.7 | 守卫矩阵补验 | 对 Internal 行/跨租户行/不存在行 API 强调 reissueFlow | 分别拒 CHANNEL_MISMATCH / not-found / not-found(i18n 文案,存在性不泄露) |
| 2.8 | 终态 quiesce | FAILED 在飞时桥接下发 `tickets/reject`(→REJECTED 终态)→ 等下一轮 sweep | 该行 `next_retry_at` 置 NULL 永久脱扫(error=「ticket reached terminal state …quiesced」);不再告警、不再重试 |

## 3. 止血开关(验收 A3)

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 3.1 | **off = 纯拉模式(核心)** | 设置页关闭 `dingBridgeOutboundEnable` 保存 → 提交 External DIRECT 工单 + 建发布单 | 全部出站静默跳过:`outbound_status` 停留 NULL(面板「未发起」);桥接侧零请求;工单/发布单照常 WAIT_APPROVAL;后端日志「kill switch off」 |
| 3.2 | 入站命令照常 | 3.1 的工单由桥接下发 approve → execute | 状态迁移照常(命令面与开关无关)——09-16 纯拉架构完整回归 |
| 3.3 | 开关热生效 | 打开开关保存(不重启)→ 再提交一单 | 新提交立即出站(STARTED);无需重启实证 |
| 3.4 | baseUrl 空等价跳过 | 开关开、baseUrl 清空 → 提交 | 同 3.1(日志「baseUrl blank」);测完恢复 |
| 3.5 | 撤销/重试同受开关管 | 开关 off 时:人工 cancel 一个有 flowId 的存量 STARTED 工单;观察 sweep | cancel 出站跳过(零 flows/cancel);FAILED 存量行 sweep 不扫(整轮 no-op) |
| 3.6 | 在途不受影响 | 开关 off 后观察已 STARTED 工单被桥接 approve | 照常受理(开关只管出站,不管入站/在途审批流转) |

## 4. 途径 1 全链(验收 A4)

前置:开关状态不限(4.x 主验入站读+命令组合);0A.2 台账条目就位;桥接 AK/SK(租户甲)。

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 4.1 | **租户隔离空集实证(核心)** | 租户甲 AK/SK 调 `ledger/list {}` → 租户乙 AK/SK 同样调用 | 甲:仅见甲租户台账行;乙:甲的条目**零出现**(乙自身台账或空集)——空集非报错(list 语义,存在性不泄露) |
| 4.2 | 过滤与分页 | `ticketType=PRE_DDL` / `PROD_DML` / 非法值(如小写 `pre_ddl`);`promoted=true/false/不传`;`pageNum/pageSize` 边界(0、越界页) | 类型精确匹配(非法值 → 空集,不报错);promoted 三态正确;pageNum<1 钳 1、pageSize<1 钳 20、越界页空集;响应无 total 字段(空页=翻页尽) |
| 4.3 | 脱敏核验 | 检查 4.1 响应 JSON 全字段 | 恰 16 字段;**无 ownerUid、无全量 SQL(sqlSummary≤200)、无 precheck/execDetail、无连接串/凭据**;ghostRouted/dmlBatchRouted 旗标随行 |
| 4.4 | **组合链 create→approve(核心,skipOutbound=true 实证)** | ledger/list 选 2 条(promoted=false)→ `releases/create {ticketIds, creatorAccount, title, skipOutbound=true}`(途径 1 必须携带,M4b)→ `releases/detail {releaseId}` 取 approvalId → `tickets/approve {ticketId=approvalId}` | create 返回 {releaseId, releaseNo, ticketId};**出站零触发实证**:桥接侧零 flows/create 请求、审批行 `outbound_status` 停留 NULL、钉钉测试群零新实例(原钉钉流转即审批,orchestration §11.1);**发布单照常**:detail 的 approvalId == create 返回 ticketId、审批工单 WAIT_APPROVAL 可受命令;**approve 组合收敛**:approve 后发布单 APPROVED、审批工单 WAIT_CONFIRM;源台账行 promoted=true+releaseNo 回填 |
| 4.5 | **中断收敛(桥接重试责任)** | mock:approve 首次调用失败(桥接侧模拟网络错)→ 桥接重试 approve | 第二次成功(或若已生效则被状态守卫拒绝=零副作用)——组合可重试收敛;最终一致由桥接用 detail 自查 |
| 4.6 | 已被合并报错传导 | 对 4.4 已合并的 ticketIds 再次 `releases/create` | 撞 `uk_source_stmt` 清晰报错(ResWebData code<0+i18n msg);桥接可解析并传导回钉钉侧提示重选 |
| 4.7 | 执行触发 | 4.4 后 `tickets/execute {ticketId=approvalId}` | WAIT_EXEC → startExecution → 发布单 EXECUTING → 逐组 job → DONE(§11.4 约定链) |
| 4.8 | 跨租户命令 | 租户乙 AK/SK 对甲的 ticketId 调 approve | not-found(存在性不泄露,既有冻结行为抽验) |
| 4.9 | creatorAccount 校验 | create 携带租户乙账号名(甲的 AK) | 拒绝(解析用户 puid ≠ 调用方 puid) |

## 5. 途径 3 全链(验收 A5)

前置:开关开启;External 环境;桥接可下发命令。

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 5.1 | **双选项分叉(核心)** | 同一环境提交两单:①DIRECT「钉钉审批(外部流程)」;②VIA_RELEASE「随发布单」 | ①WAIT_APPROVAL+出站 STARTED(第 1 章);②WAIT_RELEASE 入台账池——**零出站、无审批实例**,ledger/list 可见(promoted=false, approvalMode=VIA_RELEASE);②行为与开关开启前逐位一致(pin) |
| 5.2 | 审批→执行全链 | ①的工单:桥接收钉钉通过事件 → `tickets/approve {approver, comment, externalFlowId}` → 自动 `tickets/execute` | approve → WAIT_CONFIRM(进度区合成 activity 显示 approver/comment);execute → WAIT_EXEC → 执行链 → FINISHED;事件表 EXTERNAL_APPROVED+SYSTEM_CONFIRM 留痕(via=EXTERNAL_COMMAND, operator=服务账号 uid) |
| 5.3 | **退回映射 reject+重新提单(核心)** | 桥接收钉钉「退回发起人修改」事件 → `tickets/reject {reason}` → 发起人修改后重新提交新单(页面或桥接 `tickets/submit`) | reject → REJECTED 终态(事件留痕);新单 = 新 ticketId/新审批行 → WAIT_APPROVAL → 出站 flows/create(**新 bizId = 全新钉钉实例**);旧单 REJECTED 留存可审计;⚠ 对 REJECTED 单调 `/tickets/resubmit` → 状态守卫拒绝(前置 REVISION,orchestration §11.5) |
| 5.4 | 执行失败退回环 | 构造执行失败(测试库锁/语法)→ EXEC_FAIL → 桥接 `tickets/return {reason}` → `tickets/resubmit {groups}` | return → REVISION+IM 通知申请人;resubmit 预检通过**直落 WAIT_EXEC**(零新审批记录、零出站——原审批仍有效,appro_identity 存留);重跑到终态 |
| 5.5 | **发布单第二轮(核心)** | 发布单(1.2 产物)桥接 `releases/return`(或页面退回)→ `releases/resubmit {releaseId, ticketIds}` | return → RETURNED+出站 flows/cancel(RELEASE_RETURNED);resubmit → 新审批行出站四列清空+**重新出站** → 桥接返回**新 flowId**(≠第一轮,第二轮语义实证 orchestration §10.2);钉钉测试群出现新审批实例、旧实例已关闭 |
| 5.6 | 守卫抽验 | WAIT_APPROVAL 时桥接下发 execute(乱序) | 拒绝 GOV_OPEN_COMMAND_INVALID_STATUS,零副作用(重放/乱序无害抽验) |

## 5A. 审批轮次绑定(ADR-012 C5,2026-10-11 新增 — 项目末尾统一联调)

前置:开关开启;External 环境;**桥接侧已完成 5A.0 适配清单**(未适配时 5A.3/5A.4/5A.5 的入站回传场景不可测,仅可测出站携带与宽松跳过)。契约参考:orchestration §4.5/§5.4/§10.2.1。

**桥接侧 additive 适配清单(5A.0,外部项目对接依据)**:

1. **读取 `flows/create.context.round`**(新键,additive):与 `(bizType,bizId)↔flowId` 映射一并存储;未升级桥接按 §10.2 未知键容忍忽略,零破坏;
2. **入站三态命令回传 `externalRound`**(§5.4 additive 可选字段)= 该命令所属 flow 创建时收到的 `context.round`;不发送则 console 宽松跳过(行为与升级前一致);
3. **重申第二轮硬要求**(§10.2):旧实例撤销后同键再发起**必须**返回新 flowId,绝不复用已撤销实例 id——round 绑定使违反该要求的事件在 console 侧可拒绝、可审计;
4. 尽量携带 `externalFlowId`(既有可选字段;携带即受 FLOW_ID_MISMATCH 一致性保护,空窗反写场景 console 侧有观测标记)。

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 5A.1 | **出站 context 携带 round(核心)** | 提交 External DIRECT 工单;桥接侧记录 flows/create 请求体 | `context.round=1`(首轮);发布单建单同验 `bizType=RELEASE` 行 `round=1` |
| 5A.2 | **resubmit 轮次自增(核心)** | 发布单 return → `releases/resubmit`(5.5 场景)→ 观察第二轮 flows/create 与元数据库 | 新审批行 `approval_round=2`(元数据库取证:`SELECT approval_round FROM dm_approval WHERE id=<新审批行>`);第二轮 context.round=2;桥接返回**新 flowId** |
| 5A.3 | **旧 flow 残留回调被拒(核心漏洞场景)** | 5A.2 后 identity 已回填 F2 时,以 AK/SK 模拟旧桥接事件:`tickets/approve {ticketId=新审批行, externalFlowId=<F1 旧值>}` | 拒绝 `GOV_OPEN_COMMAND_FLOW_MISMATCH`;事件表 `EXTERNAL_COMMAND_REJECTED`(reason=FLOW_ID_MISMATCH,eventData 含 externalFlowId=F1 + recordedFlowId=F2);工单状态零变化 |
| 5A.4 | **round 不符被拒** | 对 5A.2 的新审批行(WAIT_APPROVAL,round=2)下发 `tickets/approve {externalRound=1}`(可同带 F2 flowId) | 拒绝 `GOV_OPEN_COMMAND_ROUND_MISMATCH`;审计 reason=ROUND_MISMATCH;零状态副作用;`externalRound=2` 同命令 → 照常成功 |
| 5A.5 | **宽松跳过(冻结契约 pin)** | 对 WAIT_APPROVAL 工单下发**最简调用** `tickets/approve {ticketId}`(无 flowId/round);再下发只带 externalFlowId 不带 round | 两命令均照常成功(缺失=跳过校验,§5.4「最简调用」冻结承诺不破);旧桥接零回归 |
| 5A.6 | **空窗观测样本** | (需桥接配合延迟出站响应,或 1.4 场景复用)入站命令携 flowId 先于出站回填到达 | 命令照常受理(宽松);后端 warn 日志「back-filled an EMPTY appro_identity」;成功事件 `eventData.identityBackfill=true`——样本计数入严格档灰度评估 |
| 5A.7 | D5 零误拒 | 5.4 场景(工单 return→resubmit 直落 WAIT_EXEC,原 flowId 存留)后:①下发 `tickets/execute {ticketId}`;②模拟乱序三态命令 `tickets/approve {externalFlowId=<原 flowId>}` | ①execute 照常(不消费 flowId/round);②被**状态守卫**拒(STATUS_MISMATCH,非 FLOW_ID_MISMATCH——守卫顺序 pin),审计 reason=STATUS_MISMATCH |
| 5A.8 | execute/return 拒绝审计(O-C2-1) | 对 WAIT_APPROVAL 工单下发 `tickets/execute`;对不可退回工单下发 `tickets/return` | 各自拒绝且事件表落 `EXTERNAL_COMMAND_REJECTED`(command=execute reason=EXECUTE_STATUS_MISMATCH;command=return reason=RETURN_GUARD);跨租户调用**不落审计**(存在性不泄露) |
| 5A.9 | 严格档预留验证(可选,测试环境) | root KV `govExternalCommandStrictFlow=true`(测完恢复 false)→ 最简调用 approve | 拒绝 `GOV_OPEN_COMMAND_FLOW_MISSING`;恢复 false 后同调用照常成功——灰度开关双向可用;**生产默认恒 false** |
| 5A.10 | 迁移验证 | 部署后元数据库 `SHOW COLUMNS FROM dm_approval LIKE 'approval_round';` + 存量行抽查 | `int NOT NULL DEFAULT 1`;存量行全为 1;重启重跑迁移无 ERROR(1060 忽略幂等,V202610110002) |

## 6. 撤销通知(验收 A6)

前置:开关开启;各有 flowId 的 STARTED 存量行(工单/发布单各一)。

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 6.1 | **工单人工撤销(核心)** | 详情页撤销 WAIT_APPROVAL 的 External 工单 | 本地 CANCELED 即时完成;afterCommit 出站 flows/cancel(reason=`LOCAL_CANCELED`,携带 flowId);桥接侧关闭钉钉实例(测试群可见撤销) |
| 6.2 | 工单人工关闭 | 同 6.1 用关闭操作 | reason=`LOCAL_CLOSED`,余同 |
| 6.3 | 发布单退回/撤销 | 发布单页面退回(return);另造一单走 cancelRelease | return → `RELEASE_RETURNED`;cancel → `LOCAL_CANCELED`(二次机会语义:若 return 时已通知成功,桥接按幂等处理不报错) |
| 6.4 | **失败不阻塞** | 桥接不可达时人工 cancel | 本地 CANCELED 照常完成(不回滚不报错);告警一次(cancel 失败单次);**不重试**(尽力而为);后端日志「local flow unaffected」 |
| 6.5 | 无 flowId 零调用 | 对 NOT_STARTED/FAILED(从未发起成功)工单人工 cancel | 桥接侧**零** flows/cancel 请求(日志断言);本地照常 CANCELED |
| 6.6 | **回声隔离(核心)** | 桥接下发入站 `tickets/cancel` 命令 | 本地 CANCELED;**桥接侧零新增 flows/cancel**(入站命令路径不经出站挂点——无撤销回环) |
| 6.7 | 撤销幂等 | 桥接 mock 对 flows/cancel 返回 404 | 本项目视为成功(无告警);本地流转不受影响 |
| 6.8 | 撤销后僵尸命令 | 6.1 的 CANCELED 工单,桥接再下发 approve | 状态守卫拒绝(终态)——僵尸实例无害化兜底实证 |

## 7. 直连通道退役(验收 A7)

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 7.1 | **下拉置灰(核心)** | `/env` 环境参数 → 工单变更配置弹窗 → 类型下拉 | DingTalk/Feishu/Wechat 三行**保留呈现但置灰**(disabled 卡,无点击入口),Tooltip desc=「已废弃,请改用外部编排(External)通道。」;新配置物理选不到 |
| 7.2 | External/Internal 不动 | 同弹窗 | External 行恒可选;Internal 行恒可选;Disable 卡照常 |
| 7.3 | 存量回显 | 打开一个**存量 type=DingTalk** 环境的配置弹窗 | 回显正常(DingTalk 置灰卡命中显示原值,不落到 Disable 卡);模板列表加载照常;取消关闭不改动存量值 |
| 7.4 | 存量行为不变 | 存量 DingTalk 环境提交一单(如测试模板可用;否则引用自动化回归) | 建实例/对账轮询/审批链**逐位不变**(提交链读环境参数存量值,与下拉无关);自动化面背书:ExternalApprovalTypeTest 8 / GovTicketV2SubmitModeTest 17 / ExternalCommandTest 13 等零改动绿 |
| 7.5 | 迁移演练 | 将 7.3 环境 type 改为 External 保存 → 提交新单 | 新单走桥接链路(出站发起+等命令);在途旧 DingTalk 单不受影响自然走完 |

## 8. 前端三面浏览器验收

> 按 frontend AGENTS 复测流程规则执行(Chrome `codex` 标签组;预期=可观察事实)。**浏览器验证完成后须按规则更新 `tests/frontend/` 对应流程文档**(M3 移交债务,归本步收尾)。

| # | 面 | 步骤 | 预期 |
|---|---|---|---|
| 8.1 | **提交页文案联动** | External 环境:`/ticket_create?govV2=1` 选 PROD_DML+对应 pair → 观察 approvalMode 区块;再切 Internal 环境 pair | External:DIRECT 选项=「钉钉审批(外部流程)」+desc「提交后经钉钉审批桥接发起外部审批流程…」;Internal/未配置:原文案「直接审批」逐位不变;VIA_RELEASE 文案两种环境均不变;切换 pair 后文案随环境通道刷新(陈旧响应不闪回) |
| 8.2 | **详情页面板四态+重发全链** | 依次构造 NULL/NOT_STARTED→STARTING(桥接 mock 延迟)→STARTED→FAILED 四态工单,打开 `/ticket/:id`;发布单同验 `/dbChange/release/:releaseId` | 未发起(按钮可点)/发起中(置灰)/已发起(+钉钉流程号,置灰)/失败(+原因,可点);重发成功 → 面板随既有刷新链回读「已发起」;工单页随 5s 自动刷新带出;发布单页随 loadDetail 带出;非 External 工单**无出站区块**;Network 无新增轮询请求 |
| 8.3 | 发布单面板降级 | (可选,构造 queryApprovalBaseInfo 500)打开发布单详情 | 面板隐藏、主体正常(静默降级);**后端日志可查真因**(9.2) |
| 8.4 | **settings 区块三** | `/system/ghost/settings`「钉钉审批桥接」区块:改四键 → 只保存本区块;改 ghost/dml 区块值 → 保存各自区块;恢复默认;离开确认 | 三区块草稿互不干扰(交叉保存后刷新:已保存区块持久化、未保存区块草稿保留);恢复默认仅清空桥接四键(确认文案明示令牌一并清空),ghost/dml 分毫不动;令牌留空保存=保持不变(不产生写);超时 999/60001 拒绝+范围提示(1000~60000);脏状态离开弹确认+beforeunload |
| 8.5 | 权限面 | 账号丙(无 REQUEST/APPROVE)打开 STARTED/FAILED 工单详情 | 「重新发起」按钮不可见;面板状态只读可见 |
| 8.6 | DISABLED 主动提示 | 开关 off 时点 FAILED/未发起行的「重新发起」 | 后端 i18n 拒绝文案经错误弹窗直达:「钉钉桥接出站未启用或桥接地址未配置,请先在系统设置中开启并配置」(root KV 子账号不可读 → 主动提示式,非静态置灰) |

## 9. 运维处置

### 9.1 STARTING 悬挂(已知窗口,M1 LOW#1)

**成因**:claim CAS 提交后、桥接响应落库前进程崩溃 → 行卡 `STARTING`(sweep 只扫 FAILED;reissue 拒 IN_FLIGHT;面板「发起中」置灰)——无自动回收,需手工修复。

**只读排查**(任意环境):

```sql
SELECT id, ticket_status, appro_type, appro_identity,
       outbound_status, outbound_retry_count, outbound_next_retry_at, outbound_error
FROM dm_approval
WHERE outbound_status = 'STARTING';
```

**修复(⚠ 仅限测试环境;先确认后端已重启、无在飞出站;改前记录原值)**:

```sql
UPDATE dm_approval
SET outbound_status        = 'FAILED',
    outbound_retry_count   = 0,
    outbound_error         = 'manual repair: stale STARTING after console crash',
    outbound_next_retry_at = NOW(),          -- 交 sweep 自动接管;置 NULL 则走页面手动重发
    gmt_modified           = gmt_modified     -- 必须中和:该列带 ON UPDATE CURRENT_TIMESTAMP 且兼作调度器租约
WHERE id = <approvalId> AND outbound_status = 'STARTING';
```

修复后:详情页刷新 → 面板「发起失败」→「重新发起」(或等 sweep 按 next_retry_at 接管)。**无双实例风险**:崩溃前 HTTP 若已达桥接,重发被 (bizType,bizId) 幂等去重返回同 flowId(`duplicated=true`)。

### 9.2 发布单详情面板异常隐藏

面板经 `approvalId` 静默拉 `queryApprovalBaseInfo`(modal:false+catch→null→隐藏,设计取舍=诊断面板不阻塞主体)。异常隐藏(External 发布单却无面板)→ **查后端日志**该请求是否 500;权限面已排除(路由与端点同 `RDP_WORKER_ORDER_READ`)。

### 9.3 提交响应最长 ~13s 说明

出站发送在 afterCommit **同步**执行(事务已提交、单据已落库):桥接极端慢/不可达时,提交/建单 HTTP 响应最长延迟 ≈ 3s(connect 固定)+ `dingBridgeTimeoutMs`(默认 10s)≈ **13s**(超时 KV 上限 60s 时最长 ≈63s)。仅响应变慢,无数据不一致;介意可调低超时 KV。

### 9.4 崩溃路径告警缺位(已知)

出站尝试外层崩溃(DB 宕)时 best-effort FAILED 写库也可能失败 → 该场景**无告警**(DB 已宕,告警链自身难保;代码内已注明)。排查以后端日志 `[DingBridge]` 为准。

### 9.5 常用排查 SQL(只读)

```sql
-- 某审批行出站全景
SELECT id, ticket_status, appro_type, appro_identity,
       outbound_status, outbound_error, outbound_retry_count, outbound_next_retry_at
FROM dm_approval WHERE id = <approvalId>;

-- 当前全部失败/在飞行
SELECT id, ticket_status, outbound_status, outbound_retry_count, outbound_next_retry_at
FROM dm_approval WHERE outbound_status IN ('FAILED','STARTING') AND deleted = false;
```

---

## 10. 验收对照表(→ prd Acceptance Criteria)

| 验收项 | 覆盖章节 | 结果 | 日期 |
|---|---|---|---|
| A1 出站发起主链(途径 2/3,STARTED+flowId+不阻塞) | 1 | ☐ | |
| A2 失败补偿(FAILED+告警+重发+sweep 退避+幂等防双发) | 2 | ☐ | |
| A3 止血开关(off=纯拉,热生效) | 3 | ☐ | |
| A4 途径 1 全链(租户隔离空集+组合链+中断收敛+uk 报错传导) | 4 | ☐ | |
| A5 途径 3 全链(双选项+approve/execute+退回映射+第二轮) | 5(+8.1) | ☐ | |
| A6 撤销通知(四挂点+失败不阻塞+零调用跳过+回声隔离) | 6 | ☐ | |
| A7 直连退役(下拉置灰+存量不变+迁移) | 7 | ☐ | |
| A8 文档+i18n+测试(自动化基线已绿:console 1604/全后端 2157,含 M4b +5 钉;i18n 三件套计数齐) | 门禁记录 | ☐ | |
| A9 联调+收尾(本册全过 → 09-16-gov-open-command-api 归档;本任务归档评估) | 全册 | ☐ | |

## 附录 A · 豁免记录模板

| 项 | 客观原因 | 替代验证 | 记录人/日期 |
|---|---|---|---|
| (示例)桥接 mock 不可用,4xx 分类未演练 | 桥接侧未提供 mock 面 | 单测分类矩阵钉(DingBridgeOutboundServiceTest)+联调后补 | ______ |
| | | | |

## 附录 B · 测试记录表模板

| 章节# | 场景 | 预期 | 实际 | 通过 | 执行人 | 日期 | 备注 |
|---|---|---|---|---|---|---|---|
| 1.1 | 途径 3 工单出站 | | | ☐ | | | |
| 1.2 | 途径 2 发布单出站 | | | ☐ | | | |
| 2.2 | sweep 退避 1m/5m/15m | | | ☐ | | | |
| 2.4 | 手动重发成功链 | | | ☐ | | | |
| 3.1 | off=纯拉模式 | | | ☐ | | | |
| 4.1 | 跨租户空集实证 | | | ☐ | | | |
| 4.4 | create→approve 组合 | | | ☐ | | | |
| 5.3 | reject+重新提单 | | | ☐ | | | |
| 5.5 | 发布单第二轮新 flowId | | | ☐ | | | |
| 5A.2 | resubmit 轮次自增(round=2) | | | ☐ | | | |
| 5A.3/5A.4 | 旧 flow / round 不符被拒+审计 | | | ☐ | | | |
| 5A.5 | 宽松跳过(最简调用冻结 pin) | | | ☐ | | | |
| 6.1/6.6 | 撤销+回声隔离 | | | ☐ | | | |
| 7.1/7.4 | 下拉置灰+存量不变 | | | ☐ | | | |
| 8.1~8.4 | 前端三面 | | | ☐ | | | |
| ... | | | | | | | |

## 附录 C · 清理(全部测完后)

1. 设置页桥接区块「恢复默认」(四键清空:地址空/令牌清/超时默认/开关关)或按运维要求保留联调配置;
2. 环境参数 `SQL_TICKET_INFO` 恢复演练前值(如 7.5 改动过存量环境);
3. 恢复 `alertPushEnable`(如动过);测试群机器人可保留;
4. 清理测试工单/发布单(撤销/关闭走正常链,顺带再验撤销通知);测试表数据按各库手册清理;
5. 元数据库手工修复行(9.1)如有遗留 STARTING/FAILED 测试行,记录处置结果。
