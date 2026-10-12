# 治理编排开放接口对接指南

> 适用于外部编排架构(桥接项目持有钉钉唯一 Stream 连接与 app 凭据,open-cdm 提供全生命周期命令式开放 API + 经桥接的出站发起/撤销)。
> 本文档面向桥接项目与审批引擎的对接开发,涵盖端点全集、签名规则、幂等与守卫语义、编排时序、错误矩阵、出站桥接契约(§10)与三途径时序(§11)。
> 2026-10-10 修订(task 10-09-dingtalk-approval-bridge):§1 立场由「纯拉」修订为「推拉结合」;端点全集 13→14(+`ledger/list`,§5.10);新增 §7.3 直连通道退役、§10 出站桥接、§11 三途径编排时序。入站命令面(§2~§9)行为冻结不变。
> 2026-10-10 M4b 补丁(用户裁决):`/releases/create` 新增 additive `skipOutbound` 参数(§5.8)——途径 1(钉钉内审批已完成后由桥接转建发布单)的出站回声由「桥接侧幂等处置」改为**显式参数契约**(§10.6 末行/§11.1);console 页面建单不暴露该参数、行为逐位不变。
> 2026-10-11 修订(ADR-012,task 10-11-approval-round-binding):**入站冻结声明修订为「frozen except the additive FLOW_ID_MISMATCH/ROUND_MISMATCH rejections」**——三态命令(approve/reject/cancel)新增 flow 一致性守卫与审批轮次守卫(§4.5),既有合法调用(最简 ticketId 调用、携带一致 flowId/round 的调用)行为逐位不变,仅「携带的 externalFlowId/externalRound 与本地记录**不一致**」的命令由静默推进改为拒绝+审计;§5.4 payload 新增 additive 可选字段 `externalRound`(旧桥接不发送不受影响);§10.2 出站 context 新增 `round` 键(§10.2.1 轮次语义);executes/return 守卫拒绝补审计落库(O-C2-1)。

## 1. 架构定位

open-cdm 在外部编排架构中定位收窄为:

- **工单全生命周期 + SQL 预检/执行引擎 + 完整开放 API 面**;
- open-cdm **不直连钉钉**:不持有 Stream、不轮询、不做审批对账;审批实例的创建/撤销**经桥接服务出站发起**(`flows/create` / `flows/cancel`,见 §10)——2026-10-10 立场修订,此前「不建外部审批实例」的纯拉立场升级为**推拉结合**;
- **推拉结合**:出站发起为主(提交/建单事务提交后 afterCommit 经桥接实时发起,不阻塞主链);止血开关 `dingBridgeOutboundEnable` 关闭时退化为**纯拉模式**(零出站,桥接用读端点自行核对并以命令驱动——即原架构全部行为,向后兼容);
- 提交创建内部工单 + 过程记录 → 落 `WAIT_APPROVAL` **等待外部命令**(开关开启时,出站发起已同步委托桥接创建钉钉实例);
- **外部引擎 = 审批流转唯一真相源,命令即事实**;open-cdm 靠状态前置守卫 + 幂等保证重放/乱序无害;
- 唯一特殊流转:SQL 执行失败 → 退回申请人 → `REVISION` → 修改重提 → 预检通过 → **跳过审批创建,直接落 `WAIT_EXEC`**。

External(外部编排)审批通道类型:提交时不**直连**建外部实例(出站开关开启时经桥接发起,§10;关闭时零出站)、无审批人列表、`WAIT_APPROVAL` 等待命令;调度器对该类型 no-op(不轮询、不调 getLastInfo)。

## 2. 端点全集(14 个)

所有端点位于 `POST /api/open/dbchange/*`,认证方式为 AK/SK 签名(见 §3),`@RequestBody` JSON。

### 2.1 读端点(5 个)

| 接口 | 路径 | 用途 |
|---|---|---|
| A | `POST /api/open/dbchange/tickets` | 按数据库名查询变更工单列表 |
| B | `POST /api/open/dbchange/tickets/statements` | 按工单 ID 查询语句详情与预检结果 |
| C | `POST /api/open/dbchange/releases/detail` | 按发布单 ID 查询生产发布单详情 |
| D | `POST /api/open/dbchange/releases/return` | 退回发布单(APPROVING/APPROVED → RETURNED) |
| E | `POST /api/open/dbchange/ledger/list` | 变更台账列表查询(租户隔离、list 级脱敏;2026-10-10 新增,途径 1 数据源) |

> A/B/C 的请求/响应示例见 [`openapi-dingtalk.md`](./openapi-dingtalk.md) §3。D 见本文档 §5.3,E 见 §5.10。

### 2.2 命令端点(9 个)

| 接口 | 路径 | 用途 | 前置状态 |
|---|---|---|---|
| 1 | `POST /api/open/dbchange/tickets/approve` | 审批通过(工单/发布单同套) | `WAIT_APPROVAL` |
| 2 | `POST /api/open/dbchange/tickets/reject` | 审批拒绝(终态 REJECTED) | `WAIT_APPROVAL` |
| 3 | `POST /api/open/dbchange/tickets/cancel` | 审批撤销(终态 CANCELED) | `WAIT_APPROVAL` |
| 4 | `POST /api/open/dbchange/tickets/return` | 执行失败退回申请人(REVISION) | `EXEC_FAIL` / `WAIT_EXEC`(+FAILED 组) |
| 5 | `POST /api/open/dbchange/tickets/execute` | 触发执行(系统变体) | `WAIT_CONFIRM` |
| 6 | `POST /api/open/dbchange/tickets/submit` | 代提交工单 | — |
| 7 | `POST /api/open/dbchange/tickets/resubmit` | 代重提(REVISION → External 直落 WAIT_EXEC) | `REVISION` |
| 8 | `POST /api/open/dbchange/releases/create` | 合并建发布单 | — |
| 9 | `POST /api/open/dbchange/releases/resubmit` | 退回发布单重提 | `RETURNED` |

读端点 `/tickets/detail`(工单详情含过程/活动/事件)见 §5.5。

## 3. 签名规则(5 参数·硬切)

> **硬切声明**:本签名规则为破坏性变更,不兼容旧 3 参数签名。因无存量调用方,无迁移分支——旧签名一律拒绝。

### 3.1 六个公共参数

每次请求必须携带(均支持 query string 或 header):

| 参数 | 说明 |
|---|---|
| `AccessKeyId` | 用户 Access Key ID |
| `Signature` | HMAC-SHA1 签名(Base64) |
| `SignatureMethod` | 固定 `HmacSHA1` |
| `SignatureNonce` | 随机 UUID,每次唯一 |
| `Timestamp` | 请求时刻,epoch 毫秒(UTC) |
| `BodyDigest` | 请求体 SHA-256 hex(小写);空 body 用空串的 SHA-256 |

### 3.2 待签名字符串构造

将以下 **5 个参数**(不含 `Signature` 本身)按 key 字典序排序,对每个 key 和 value 做 percent-encoding(`+`→`%20`、`*`→`%2A`、`%7E`→`~`),用 `&` 连接:

```
AccessKeyId=<AK>
BodyDigest=<SHA-256-hex>
SignatureMethod=HmacSHA1
SignatureNonce=<uuid>
Timestamp=<epoch-millis>
```

使用用户 SK(Secret Key)对待签名字符串做 HMAC-SHA1 → Base64 编码 → 作为 `Signature`。

### 3.3 BodyDigest 计算

```
BodyDigest = SHA-256(原始请求体字节).hexLowerCase()
```

- 空 body(GET 请求或空 POST body):`BodyDigest = SHA-256("")` = `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- 服务端用 `CachedBodyHttpServletRequest` 缓存 body 以便签名校验与 `@RequestBody` 绑定各读一次。

### 3.4 校验流程(服务端)

1. 提取 6 参数(query 优先,header 兜底)。**缺任一 → HTTP 499**(COMMON_PARAMS_HAS_EMTPY)。
2. AK 查用户。**不存在 → HTTP 498**(USER_NOT_EXIST)。
3. 解密 SK,用 5 参数重组签名。**签名不符 → HTTP 497**(SIGNATURE_ERROR)。
4. 时间戳校验:`|now − Timestamp| > 5min` → **HTTP 497**。
5. Body 摘要校验:`SHA-256(实际 body) != BodyDigest` → **HTTP 497**。

### 3.5 错误码矩阵

| HTTP Status | 含义 | 触发条件 |
|---|---|---|
| 499 | 公共参数缺失 | 6 参数中任一为空(含旧 3 参数签名缺 Timestamp/BodyDigest) |
| 498 | 用户不存在 | AK 未解析到用户 |
| 497 | 签名错误 | 签名不符 / 时间戳偏移超 5 分钟 / body 摘要不符 / body 未缓存 |

> 旧 3 参数签名因缺 `Timestamp`/`BodyDigest` → 命中 499(参数缺失)路径 → 拒绝。这是硬切的自然实现,无兼容分支。

### 3.6 签名伪代码(Java)

```java
String ak = "AK_TEST_001";
String sk = "<decrypted-secret-key>";
String nonce = UUID.randomUUID().toString();
String method = "HmacSHA1";
long ts = System.currentTimeMillis();
String body = "{\"ticketId\":100}";
String bodyDigest = sha256Hex(body); // SHA-256 → lowercase hex

Map<String,String> params = new TreeMap<>();
params.put("AccessKeyId", ak);
params.put("SignatureMethod", method);
params.put("SignatureNonce", nonce);
params.put("Timestamp", String.valueOf(ts));
params.put("BodyDigest", bodyDigest);

String toSign = percentEncodeSorted(params);  // AccessKeyId=..&BodyDigest=..&SignatureMethod=..&SignatureNonce=..&Timestamp=..
String signature = hmacSha1Base64(toSign, sk);

// URL: https://host/api/open/dbchange/tickets/approve
//      ?AccessKeyId=..&SignatureMethod=..&SignatureNonce=..&Timestamp=..&BodyDigest=..&Signature=..
// Header: Content-Type: application/json
// Body:  {"ticketId":100}
```

## 4. 幂等与守卫语义

### 4.1 严格前置状态

命令端点要求工单处于精确前置状态;不满足 → 清晰 i18n 错误,零副作用(照 `/releases/return` 幂等先例):

| 命令 | 前置状态 | 不满足行为 |
|---|---|---|
| approve / reject / cancel | `WAIT_APPROVAL` | 拒绝(GOV_OPEN_COMMAND_INVALID_STATUS) |
| execute | `WAIT_CONFIRM` | 拒绝 |
| return | `EXEC_FAIL` ∨ `WAIT_EXEC` + ≥1 FAILED 组 + 未合并 | 拒绝 |
| resubmit | `REVISION` | 拒绝 |
| releases/resubmit | `RETURNED` | 拒绝 |
| releases/return | `APPROVING` ∨ `APPROVED` | 拒绝 |

终态工单(REJECTED/CANCELED/FINISHED)收到任何命令 → 拒绝,零副作用。

### 4.2 通道守卫

命令面仅服务 External 通道工单。非 External(approType)工单收到命令 → 拒绝(`GOV_OPEN_COMMAND_CHANNEL_MISMATCH`),防止外部引擎误驱动 Internal/钉钉工单。

### 4.3 租户隔离(not-found 语义)

所有命令以 AK/SK 解析 puid 做租户校验。跨租户命令 → 统一 not-found(存在性不泄露,照 `/releases/return` 先例)。

### 4.4 重放安全

- 签名窗口 ≤ 5 分钟(Timestamp 校验)→ 重放窗口短;
- 状态前置守卫 → 重放/乱序无害:同一条 approve 命令重放 → 第二次因状态已非 `WAIT_APPROVAL` 被拒;
- 无 nonce 去重基建(网关限流职责);外部引擎用读接口自行核对状态。

### 4.5 flow 一致性与轮次守卫(ADR-012,2026-10-11 新增)

三态命令(approve/reject/cancel,`meta != null ∧ approType=External` 作用域)在三个状态守卫(END_STATUS / STATUS_MISMATCH / CHANNEL_MISMATCH)之后新增两个一致性守卫;**每个拒绝都先落 `EXTERNAL_COMMAND_REJECTED` 审计事件再抛错,零状态副作用**:

| 守卫 | 条件 | 拒绝 reason | 宽松语义(红线) |
|---|---|---|---|
| ④ flow 一致性 | 命令 `externalFlowId` 非空 ∧ 已记录 `appro_identity` 非空 ∧ **不相等** | `FLOW_ID_MISMATCH`(eventData 携带两值:externalFlowId + recordedFlowId) | 任一为空 → 跳过校验(空 identity = §10.6 合法空窗,见观测条款;缺失 flowId = §5.4 冻结的「最简调用」) |
| ⑤ 轮次绑定 | 命令 `externalRound` 携带 ∧ 与 `dm_approval.approval_round` **不相等** | `ROUND_MISMATCH` | 字段缺失 → 跳过(旧桥接不发送该字段,行为不变);本地轮次列为 NULL(异常防御)同样跳过 |

- **架构立场**(桥接 PRD 裁决 #5 修订):「外部引擎 = 审批流转唯一真相源」**保留**;console 由「仅状态守卫」升级为「状态守卫 + flow 一致性守卫」——不对账、不轮询,只拒绝**可证明**属于其他 flow/轮次的命令;
- **空窗观测**:入站命令 flowId 写入空 `appro_identity` 时(既有「仅空时写入」双源契约不变)记 warn 日志 + 成功事件 `eventData.identityBackfill=true` 标记——为严格档灰度积累真实窗口频率数据;
- **严格档(预留,默认关闭)**:root KV `govExternalCommandStrictFlow=true` 时,三态命令缺失 externalFlowId / externalRound 即拒(`FLOW_ID_MISSING` / `ROUND_MISSING`)。默认 false=宽松档;读取失败同样按宽松处理。开启前必须完成桥接升级联调实证(§10.2.1);
- **execute/return 拒绝审计(O-C2-1 同批关闭)**:`tickets/execute` 守卫拒绝(reason=`EXECUTE_STATUS_MISMATCH` / `CHANNEL_MISMATCH`,command=`execute`)与 `tickets/return` 委托链守卫拒绝(umbrella reason=`RETURN_GUARD`,command=`return`)同样落 `EXTERNAL_COMMAND_REJECTED`;not-found/跨租户拒绝**不落审计**(存在性不泄露);
- **D5 零误拒**:工单级 External resubmit(§10.5)后工单在 WAIT_EXEC 链,三态命令被状态守卫②先行拒绝,④⑤执行不到;execute/return 不消费 flowId/round——原审批(含旧 flowId)照常有效。

## 5. 编排时序

### 5.1 工单环(PROD_DML DIRECT)

```
submit → WAIT_APPROVAL
  ↓ approve 命令        → WAIT_CONFIRM
  ↓ execute 命令        → WAIT_EXEC → 执行链启动 → FINISHED
  ↓ [执行失败]          → EXEC_FAIL
       ↓ return 命令    → REVISION
            ↓ resubmit 命令(External:预检通过直落) → WAIT_EXEC → 执行链重启
  ↓ reject 命令         → REJECTED(终态)
  ↓ cancel 命令         → CANCELED(终态)
```

> External 通道 resubmit **直落 WAIT_EXEC**(单事务:APPROVAL→FINISH + CONFIRM→FINISH + EXECUTION→INIT + 逐组 createGroupJob/startJob),不重建审批流、零新审批记录。Internal 通道 resubmit 重提重审(行为不变,回归 pin)。

### 5.2 发布单环(DM_PROD_RELEASE)

```
releases/create → 发布单 WAIT_APPROVAL(审批工单 WAIT_APPROVAL)
  ↓ approve 命令        → handleApproved → release APPROVED; ticket WAIT_CONFIRM
  ↓ execute 命令       → WAIT_EXEC → startExecution → release EXECUTING → 逐组 job → DONE
  ↓ reject 命令        → release REJECTED + 删 stmt 行(终态)
  ↓ cancel 命令        → release CANCELED + 删 stmt 行(终态)
  ↓ releases/return    → release RETURNED → releases/resubmit 命令 → 重新 WAIT_APPROVAL
```

### 5.3 releases/return(D 端点)

**请求:**
```json
POST /api/open/dbchange/releases/return
{
  "releaseNo": "REL-20260911-0001",
  "reason": "申请人撤回"
}
```
(`releaseId` 与 `releaseNo` 二选一;`reason` 必填)

**响应:** `ResWebData`(空 data)

**幂等:** `RETURNED`/终态发布单 → `GOV_RELEASE_RETURN_INVALID_STATUS` 错误,零副作用。

### 5.4 工单命令 FO

approve/reject/cancel/return/execute 共用 `DbChangeTicketCommandFO`:

```json
{
  "ticketId": 100,
  "approver": "张三",
  "comment": "同意",
  "externalFlowId": "EXT-FLOW-001",
  "externalRound": 1,
  "reason": "执行失败退回修改"
}
```

| 字段 | 必选 | 适用端点 | 说明 |
|---|---|---|---|
| `ticketId` | 是 | 全部 | dm_approval.id,按 approBiz 路由(工单/发布单) |
| `approver` | 否 | approve/reject/cancel | 审批人名,合成 activity 进度区展示 |
| `comment` | 否 | approve/reject/cancel | 审批意见 |
| `externalFlowId` | 否 | approve/reject/cancel | 外部流程 ID,落 approIdentity(仅当前为空时);**与已记录 approIdentity 均非空且不一致 → 拒绝 `FLOW_ID_MISMATCH`**(§4.5,ADR-012) |
| `externalRound` | 否 | approve/reject/cancel | **additive 2026-10-11(ADR-012)**:命令所属审批轮次(整数,来自出站 `flows/create.context.round`,§10.2.1);携带且与本地 `approval_round` 不一致 → 拒绝 `ROUND_MISMATCH`;缺失 → 跳过校验(旧桥接不发送不受影响) |
| `reason` | 否 | return | 退回原因,入 eventData + IM 通知申请人 |

> 最简调用:只传 `ticketId` 即可驱动状态迁移;有 approver/comment 时增强进度区展示。**宽松红线(ADR-012)**:缺失 externalFlowId/externalRound 永不拒绝(严格档为配置预留、默认关闭,§4.5)——本冻结承诺不因轮次绑定而破坏。

### 5.5 /tickets/detail(读端点)

**请求:**
```json
POST /api/open/dbchange/tickets/detail
{
  "ticketId": 100
}
```

**响应:** `OpenDbChangeTicketDetailVO` — 含 base info + processList(每阶段含 activityList)+ events trail。脱敏:不含 AK/SK、内部异常栈、console 权限位。跨租户 → null(not-found)。

### 5.6 /tickets/submit

**请求:**
```json
POST /api/open/dbchange/tickets/submit
{
  "ownerAccount": "applicant-user",
  "ticketType": "PROD_DML",
  "approvalMode": "DIRECT",
  "ticketTitle": "orders 表新增列",
  "description": "业务需求",
  "serviceId": 5,
  "groups": [
    {"pairId": 1, "sqlContent": "ALTER TABLE orders ADD COLUMN note TEXT;"}
  ]
}
```

**响应:** `OpenDbChangeTicketSubmitVO` — `{ticketId, bizId, ticketStatus, ticketType}`

> `ownerAccount` 必填:平台账号 → 解析 uid + 租户内校验(解析用户 puid 必须 == 调用方 puid)→ ticket.ownerUid = 该用户(退回/IM 通知收件人)。

### 5.7 /tickets/resubmit

**请求:**
```json
POST /api/open/dbchange/tickets/resubmit
{
  "ticketId": 100,
  "groups": [
    {"groupId": 201, "sqlContent": "ALTER TABLE orders ADD COLUMN note VARCHAR(255);"}
  ],
  "reason": "修正列类型",
  "rollBackSql": "ALTER TABLE orders DROP COLUMN note;"
}
```

**响应:** `OpenDbChangeTicketSubmitVO`

> `groups` 仅含修改后 SQL(仅 FAILED 组可重提)。External 通道 → 直落 `WAIT_EXEC`;Internal → 重提重审(回归 pin)。

### 5.8 /releases/create

**请求:**
```json
POST /api/open/dbchange/releases/create
{
  "ticketIds": [100, 101],
  "creatorAccount": "release-creator",
  "title": "2026-09-17 生产发布",
  "skipOutbound": true
}
```

| 字段 | 必选 | 说明 |
|---|---|---|
| `ticketIds` | 是 | 待合并源工单 ID 列表(资格四条件 / 三门 / 租户锁 / uk_source_stmt 全复用) |
| `creatorAccount` | 否 | 缺省 = 服务账号自身;提供时解析用户的 puid 必须 == 调用方 puid |
| `title` | 否 | 发布单标题,缺省自动生成 |
| `skipOutbound` | 否 | 缺省 false(null 视同 false)。**途径 1 专用**:桥接在钉钉审批流程已完成后把台账条目转建发布单时**必须携带 true**——建单成功后不调桥接 `flows/create`(该钉钉流转本身即审批,防止重复创建实例;§10.6 末行/§11.1)。需要发起钉钉审批的代建场景不传(出站照常发起)。**仅影响出站钩子**:发布单落库/审批面/入站命令面与缺省行为一致;console 页面建单(/govrelease/create)不暴露该参数 |

**响应:** `OpenDbChangeReleaseCreateVO` — `{releaseId, releaseNo, ticketId}`(ticketId = 发布单审批工单 id,命令路由键)

### 5.9 /releases/resubmit

**请求:**
```json
POST /api/open/dbchange/releases/resubmit
{
  "releaseId": 500,
  "ticketIds": [100, 102],
  "title": "2026-09-17 生产发布(重提)"
}
```

**响应:** `ResWebData`(空 data)

> External 通道发布单 resubmit = **新审批轮次**:本项目清空新审批行的 `appro_identity` + 出站四列并重新出站发起,桥接侧必须返回**新 flowId**(§10.2 第二轮语义 / §10.5)。

### 5.10 /ledger/list(读端点 E,2026-10-10 新增)

变更台账 = 可合并进生产发布单的工单投影:**已执行完成的 PRE_DDL 工单**(FINISHED 且全部语句 SUCCESS)+ **VIA_RELEASE 进池的 PROD_DML 工单**(WAIT_RELEASE/FINISHED 且全部语句 PENDING)。供桥接在钉钉审批流程内渲染选择列表(途径 1,§11.1)。

**请求**(`DbChangeLedgerListFO`,字段全部可选,空 FO = 本租户全量台账第一页):

```json
POST /api/open/dbchange/ledger/list
{
  "ticketType": "PRE_DDL",
  "promoted": false,
  "pageNum": 1,
  "pageSize": 20
}
```

| 字段 | 必选 | 说明 |
|---|---|---|
| `ticketType` | 否 | 精确匹配过滤,合法枚举 **`PRE_DDL` / `PROD_DML`**;非法值(大小写/空格差异等)→ **空集**(列表过滤语义,不报错) |
| `promoted` | 否 | `true`=仅已合并进发布单;`false`=仅未合并(可选择合并);不传=全部 |
| `pageNum` | 否 | 缺省 1;`<1` 钳制为 1 |
| `pageSize` | 否 | 缺省 20;`<1` 钳制为 20 |

**响应:** `ResWebData<List<OpenDbChangeLedgerTicketVO>>` — data = **当前页行列表,无 total 字段**(空页 = 翻页尽)。过滤先于分页(租户/类型/转产过滤作用于分页前,页节奏不泄露存在性)。

VO 16 字段(list 级口径):

| 字段 | 说明 |
|---|---|
| `ticketId` | dm_approval.id |
| `ticketTitle` / `serviceName` / `serviceId` | 标题与业务归属 |
| `gmtCreate` | `yyyyMMdd HHmmss`(北京时间) |
| `execStatus` | `SUCCESS`(已执行 PRE_DDL)/ `PENDING`(进池 PROD_DML) |
| `sqlSummary` | 首组 SQL 前 **200 字符截断**——绝无全文 |
| `ticketType` | `PRE_DDL` / `PROD_DML` |
| `approvalMode` | 进池行 = `VIA_RELEASE`;历史 PRE_DDL 行 = null |
| `preDbs` | 预发库列表 `[{dsId, dbName, dsName}]` |
| `prodDbs` | 生产库展示名列表 `["dsName/dbName"]` |
| `promoted` / `releaseNo` / `releaseStatus` | 是否已转产及归属发布单 |
| `ghostRouted` / `dmlBatchRouted` | 无锁路由披露旗标(桥接建单前可见锁风险提示) |

**脱敏口径:** 无 `ownerUid`、无全量 SQL、无预检/执行明细、无连接串/凭据;**台账 detail 端点有意不开放**(全量 SQL 暴露面留在 console 权限体系)——钉钉侧展示 200 字符摘要 + 单号 + 类型 + 状态已足够选择。

**租户隔离(硬约束):** 仅返回 `primaryUid == 调用方 puid` 的行;跨租户行静默丢弃 → **空集(列表语义,非 not-found,存在性不泄露)**。console 台账页无租户过滤是既有 D6 决策,开放面**不继承**——两者为不同方法,console 实现零改动。

**实时性口径(投影):** 台账是投影,桥接/钉钉侧选择的瞬间条目可能已被他人合并 → `releases/create` 撞 `uk_source_stmt` 报错(既有语义,§5.8),**由桥接负责传导回钉钉侧提示重选**(§11.1)。

## 6. External 通道语义

- **命令即事实**:外部引擎下发命令后,工单状态立即反映命令结果,不经过中间状态;
- **open-cdm 不对账**:不调 getLastInfo、不轮询;审批实例的创建/撤销经桥接出站发起(§10,开关开启时),**审批状态的一致性对账责任仍在外部引擎/桥接**;入站命令不依赖出站状态(守卫只看工单状态——事件先于出站响应到达也照常受理,§10.6);
- **审计口径**:命令 operator = AK/SK 解析的服务账号 uid(非 SYSTEM);事件 eventData 携带 `via=EXTERNAL_COMMAND`(resubmit 为 `via=EXTERNAL_RESUBMIT`)+ approver/comment/externalFlowId;
- **事件类型**:审批三态用新增 `EXTERNAL_APPROVED`/`EXTERNAL_REJECTED`/`EXTERNAL_CANCELED`;execute 复用 `SYSTEM_CONFIRM`;return 复用 `TICKET_RETURNED`;resubmit 复用 `SUBMIT` + `SYSTEM_CONFIRM`;
- **进度区**:命令携带 approver/comment 时合成 activity,走既有 `convertToTicketActivityVO` 渲染,前端零改动。

## 7. 环境配置

### 7.1 SQL_TICKET_INFO 配置 External 通道

环境参数 `SQL_TICKET_INFO`:
```json
{
  "type": "External",
  "openTicket": true
}
```

`type = External` → 外部编排通道(恒 enable,无需模板、无需 config 开关)。环境参数页类型下拉数据源 `getTicketTypes` 已含 External 行,前端零改动。

### 7.2 适用业务面

| 业务面 | External 通道行为 |
|---|---|
| PROD_DML DIRECT | 提交 → WAIT_APPROVAL 等命令(开关开启时同步出站发起,§10/§11.3);resubmit 直落 WAIT_EXEC |
| DM_PROD_RELEASE | 建单 → WAIT_APPROVAL 等命令(开关开启时同步出站发起,§10/§11.2);approve → handleApproved → APPROVED |
| PRE_DDL | 自动推进链(External 不改变其行为,仍全自动到 WAIT_EXEC) |
| DM_QUERY / DATA_SOURCE_AUTH 等老业务面 | 不在范围(配置了也仅 no-op 等待,文档注明不支持) |

### 7.3 直连通道退役(deprecated,2026-10-10)

由钉钉插件直连建实例 + Stream 回调 + 周期对账的直连审批通道(**DingTalk / Feishu / Wechat**)已标记废弃(钉钉 app 唯一且已由桥接项目接入,本项目不再直连):

- **新配置选不到**:环境参数 `SQL_TICKET_INFO` 类型下拉(`getTicketTypes`)中三行 `enable=false`、desc=「已废弃,请改用外部编排(External)通道。」(i18n 键 `TICKET_APPROVAL_TYPE_DEPRECATED`);**行仍保留返回**——前端按 `enable` 渲染禁用卡(无点击入口,物理选不到),存量配置回显正常;
- **存量已配置环境行为逐位不变**:提交链读的是环境参数**存量值**(与下拉可选性无关),`ApprovalType.getByName` 继续解析存量字符串,建实例/对账轮询/Stream 回调照旧工作;插件代码零删除;
- **迁移指引**:①部署桥接服务并配置 §10.1 四 KV(baseUrl/token/timeout/开关);②环境参数 `SQL_TICKET_INFO` 的 `type` 改为 `External`;③新工单即走桥接链路(出站发起 + 入站命令);存量在途工单沿原直连链路自然走完,无需迁移。

## 8. 安全说明

| 项 | 说明 |
|---|---|
| 签名覆盖范围 | 5 参数(AK + Method + Nonce + Timestamp + BodyDigest);body 经 SHA-256 摘要纳入签名 |
| 时间戳防重放 | ±5 分钟窗口;超窗口拒绝 |
| 无 nonce 去重 | 服务端无 nonce 缓存(网关职责);重放由状态前置守卫兜底 |
| 租户隔离 | AK/SK → puid,跨租户 not-found |
| 限流 | 平台无内置限流;建议网关/SLB 对 `/api/open/*` 配置 IP+AK 维度限流 |
| 操作审计 | 命令端点入口有结构化 `log.info`;治理事件表记录 operator uid + via + 元数据 |
| 出站桥接鉴权 | `flows/create` / `flows/cancel` 携带 `X-Bridge-Auth` 请求头;token KV 加密存储、不回显(isSecret);为空则头省略(是否拒绝由桥接决定);出站目标仅限配置的 baseUrl |
| 出站止血开关 | `dingBridgeOutboundEnable` 默认 false(上线零行为变化);读取 fail-safe(读失败=关闭);关闭时发起/撤销/补偿重试全部静默跳过,入站命令面不受影响(§10.1) |

## 9. 错误响应

签名校验失败返回 HTTP 497/498/499 + 空 body `{}`;业务校验失败返回 `ResWebData{code:<0>, msg:<i18n 错误>}`。

| i18n 键 | 场景 |
|---|---|
| `GOV_OPEN_COMMAND_INVALID_STATUS` | 命令前置状态不满足(携带 ticketId + 当前状态 + 命令) |
| `GOV_OPEN_COMMAND_CHANNEL_MISMATCH` | 非 External 通道工单收到命令 |
| `GOV_OPEN_COMMAND_FLOW_MISMATCH` | 命令 externalFlowId 与已记录 approIdentity 不一致(§4.5 守卫④,ADR-012;携带两值) |
| `GOV_OPEN_COMMAND_ROUND_MISMATCH` | 命令 externalRound 与本地 approval_round 不一致(§4.5 守卫⑤,ADR-012;携带两值) |
| `GOV_OPEN_COMMAND_FLOW_MISSING` | 严格档(config 预留,默认关闭)下三态命令缺失 externalFlowId |
| `GOV_OPEN_COMMAND_ROUND_MISSING` | 严格档(config 预留,默认关闭)下三态命令缺失 externalRound |
| `GOV_RELEASE_RETURN_INVALID_STATUS` | 发布单退回状态不满足 |
| `TICKET_OPERATOR_TYPE_NOT_MATCH_STATUS` | 工单状态与操作不匹配(既有键) |

## 10. 出站桥接(本项目 → 桥接服务,2026-10-10 新增)

> 方向定义:§2~§9 为**入站**(桥接 → open-cdm,AK/SK 签名开放面,行为冻结);本节为**出站**(open-cdm → 桥接,提交/建单/人工终止成功后的主动调用)。
> 本节为本项目**单边契约提案**(联调定稿,checklist 见 `tests/governance/dingtalk_bridge.md`);代码权威 = console `web/component/bridge/` 包(`DingBridgeClient` / `DingBridgeOutboundService` / `BridgeCreateRequest` / `BridgeCancelRequest`)。

### 10.1 配置与适用性

四个 root KV(系统设置页 `/system/ghost/settings`「钉钉审批桥接」区块;子账号不可读 root KV):

| KV | 默认 | 说明 |
|---|---|---|
| `dingBridgeBaseUrl` | 空 | 桥接服务地址 `http(s)://host[:port][/prefix]`;**留空 = 全部出站跳过** |
| `dingBridgeAuthToken` | 空 | `X-Bridge-Auth` 请求头令牌;加密存储、不回显(isSecret),留空保持不变 |
| `dingBridgeTimeoutMs` | 10000 | 读/写超时(毫秒),白名单 1000~60000;**连接超时固定 3 秒** |
| `dingBridgeOutboundEnable` | false | **止血总开关**;读取 fail-safe(读失败 = 关闭);热生效,无需重启 |

- **适用性判定**:仅 `appro_type=External` ∧ 开关开 ∧ baseUrl 非空的审批行进出站;其余行 `outbound_status` 恒 NULL(详情页不渲染出站区);
- **触发时机**:业务事务(提交/建单/人工终止)**提交后**的 afterCommit 回调——出站永不阻塞、永不回滚主链;事务回滚 = 零出站调用;
- **never-throw**:网络异常/超时/非 2xx 全部归一为出站状态落库,任何失败不向业务操作发起人传播(唯一同步例外 = 手动「重新发起」,§10.4)。

### 10.2 发起审批 `POST {dingBridgeBaseUrl}/flows/create`

**请求**(`BridgeCreateRequest`):

```json
{
  "bizType": "TICKET",
  "bizId": "100",
  "bizNo": "T202610090001",
  "title": "orders 表新增列",
  "applicant": {"account": "<申请人 uid>", "name": "<显示名>"},
  "summary": "ALTER TABLE orders ADD COLUMN note TEXT;...(≤200 字符截断)",
  "context": {"puid": "<租户 puid>", "approBiz": "PROD_DML", "round": 1}
}
```

| 字段 | 说明 |
|---|---|
| `bizType` | `TICKET`(PROD_DML DIRECT 工单审批行)/ `RELEASE`(发布单审批行) |
| `bizId` | TICKET = dm_approval.id 字符串;RELEASE = releaseId 字符串——**与 bizType 构成幂等键** |
| `bizNo` | 展示用单号(工单 bizId / releaseNo) |
| `title` | 工单/发布单标题 |
| `applicant` | `account`=申请人 uid(工单 ownerUid/发布单创建人);`name`=显示名(查询失败退化为裸 uid)——**钉钉用户映射为桥接侧责任** |
| `summary` | rawSql 前 200 字符截断(与台账 list 级同口径:无全量 SQL、无连接串) |
| `context` | `puid`=租户;`approBiz`=`PROD_DML` / `DM_PROD_RELEASE` 等;`round`=本次发起的审批轮次(**additive 2026-10-11,ADR-012**,恒携带,首轮=1,§10.2.1)——未知键容忍,未升级桥接忽略即可 |

**鉴权**:请求头 `X-Bridge-Auth: <dingBridgeAuthToken>`(token 空则省略该头)。

**响应**(2xx;未知键容忍 = 桥接侧 additive 演进):

```json
{"flowId": "<钉钉审批实例 id>", "duplicated": false}
```

**幂等键 `(bizType, bizId)` — 第二轮语义(硬要求)**:

1. 旧实例**存活期间**的重复请求 → 桥接侧去重返回**同一 flowId**(`duplicated=true`),绝不创建第二个钉钉实例(本地行级 CAS 之后的第二道防双发网,兜住跨进程重发/重复重发);
2. **旧实例已被撤销后**(收到出站 `flows/cancel` 通知或桥接侧自行关闭)同键再次发起 → **必须创建新实例返回新 flowId**,绝不能返回已撤销实例的旧 id。典型场景:发布单 `RETURNED` → `releases/resubmit` 重建审批面(本项目清空 `appro_identity`+出站四列后重新出站,`bizId`=releaseId 跨轮次不变)= **第二轮审批**(§10.5)。

**flowId 回填(双源先到先得)**:出站响应的 `flowId` 回填 `dm_approval.appro_identity`,SQL 级原子 CAS——**仅当前为空时写入**;与入站命令携带的 `externalFlowId`(§5.4)落同一字段、同一守卫。双源本质是同一实例 id,先到先写,后到跳过回填(STARTED 照落,§10.6)。**ADR-012 一致性补充**:「双源同值」由假设升级为**校验**——入站三态命令携带的 flowId 与已记录值不一致即拒(§4.5 守卫④);写入规则本身不变(比对是新增守卫,不是覆盖语义);入站写入空 identity 时记观测标记(`identityBackfill`,§4.5)。

#### 10.2.1 审批轮次语义(ADR-012,2026-10-11 新增)

- **本地轮次列**:`dm_approval.approval_round`(int NOT NULL DEFAULT 1,迁移 V202610110002)。首轮=1;**发布单 resubmit(§10.5)在清空 `appro_identity`+出站四列的同时 round+1**(轮次自增与轮次清空同一 SQL,原子);
- **出站携带**:`flows/create.context.round` = 发起时刻的行轮次(additive,恒携带)——桥接侧应将 round 与 `(bizType,bizId)↔flowId` 映射一并存储;
- **入站回传(桥接侧 additive 适配)**:三态命令携带 `externalRound`(§5.4)= 该命令所属 flow 创建时收到的 `context.round`;console 比对本地 `approval_round`,不等 → `ROUND_MISMATCH` 拒绝+审计(§4.5 守卫⑤);缺失 → 跳过(宽松档,旧桥接零影响);
- **第二轮硬要求衔接**:§10.2 幂等键第二轮语义(旧实例撤销后同键再发起**必须**返回新 flowId)不变;round 是其**可校验化**——即使桥接误将旧实例事件按稳定键解析到新轮次审批行,旧 flow 的 F1 回调也会被守卫④(identity 已回填时)或守卫⑤(externalRound 不符时)拒绝,而不再静默反写推进;
- **严格档灰度(预留)**:`govExternalCommandStrictFlow=true` 后「缺失 externalFlowId/externalRound 即拒」——须待桥接升级(读取 context.round + 回传 externalRound)经统一联调实证后方可开启(ADR-012 Decision 3)。

**错误分类(决定补偿策略)**:

| 桥接响应 | 分类 | 本地行为 |
|---|---|---|
| 2xx + 可解析 body | 成功 | `outbound_status=STARTED` + flowId 回填 |
| 4xx / 2xx 但 body 不可解析 | **契约错,不可重试** | `FAILED` + 终态告警一次,**不自动重试**(人工修复后手动重发) |
| 5xx / IO / 超时 | **可重试** | `FAILED` + 退避排程,进补偿 sweep(§10.4) |

### 10.3 撤销实例 `POST {dingBridgeBaseUrl}/flows/cancel`

**请求**(`BridgeCancelRequest`):

```json
{"bizType": "TICKET", "bizId": "100", "flowId": "<可空>", "reason": "LOCAL_CANCELED"}
```

| `reason` | 触发挂点(全部为**人工 console 路径**,afterCommit) |
|---|---|
| `LOCAL_CANCELED` | 工单人工撤销(cancelTicket);发布单人工撤销(cancelRelease,二次机会——return 时通知可能已失败,桥接按幂等处理) |
| `LOCAL_CLOSED` | 工单人工关闭(closeTicket) |
| `RELEASE_RETURNED` | 发布单整单退回(RETURNED,本地已取消审批流) |

- `flowId` = `appro_identity`(可空——桥接也可按 `(bizType,bizId)` 自行解析);**本项目侧无 flowId(从未发起成功)时零调用跳过**,请求不发出;
- **尽力而为语义**:404/已撤销 → 视为幂等成功;5xx/超时 → **仅告警 + 日志,不重试**——本地终止流转已完成且不回滚,僵尸钉钉实例由入站命令状态守卫无害化(终态工单拒绝任何命令);
- **回声隔离(结构保证)**:出站撤销**仅**挂人工汇聚点;入站 `/tickets/cancel` 开放命令走独立路径(`applyExternalApprovalResult`),物理不经过挂点——桥接下发 cancel 命令后**绝不会**再触发一次出站撤销回环。

### 10.4 出站状态与失败补偿

`dm_approval` 出站四列(2026-10-10 纯增列迁移 `V202610090001`,旧版本二进制透明):

| 列 | 语义 |
|---|---|
| `outbound_status` | `NULL/NOT_STARTED → STARTING → STARTED \| FAILED`(STARTING = 行级 CAS 已认领、在飞) |
| `outbound_error` | 最近一次失败原因(500 字符截断;列宽 512) |
| `outbound_retry_count` | 已自动重试次数 |
| `outbound_next_retry_at` | 下次可重试时间(`NULL` = 脱离扫描集:不可重试/达上限/quiesce) |

- **自动补偿 sweep**:挂既有工单调度器 tick、30 秒节流(轮次本体跑在 worker 池,绝不占用调度线程);扫描 `FAILED ∧ retry_count<3 ∧ next_retry_at<=now` → 重出站;**退避 1m/5m/15m,上限 3 次**;行级 CAS 认领(多 console 并发安全,败者跳过);达上限停扫 + 终态告警一次(此后静默);工单已终态的 FAILED 行 quiesce(`next_retry_at` 置 NULL 永久脱扫,不告警);
- **手动重发(console)**:`POST /api/entry/approval/reissueFlow`,body `{"ticketId": <approvalId>}`;权限 = HIGH + `RDP_WORKER_ORDER_REQUEST`/`RDP_WORKER_ORDER_APPROVE` 任一(照 cancel/close 先例);**同步语义**——桥接结果直达操作者(成功 = 空 data;失败 = i18n 错误携带落库原因)。守卫五拒:

| 拒绝键 | 条件 |
|---|---|
| `DING_BRIDGE_REISSUE_DISABLED` | 开关关闭或 baseUrl 未配置 |
| not-found(`GOV_TICKET_V2_TICKET_NOT_FOUND`) | 工单不存在 / 跨租户(存在性不泄露) |
| `DING_BRIDGE_REISSUE_CHANNEL_MISMATCH` | 非 External 通道 |
| `DING_BRIDGE_REISSUE_ALREADY_STARTED` | `STARTED`(防双发;第二道网 = 桥接幂等键) |
| `DING_BRIDGE_REISSUE_IN_FLIGHT` | `STARTING`(在飞,稍后刷新) |

  放行后重置 `retry_count=0` 立即出站;仍失败 → `DING_BRIDGE_REISSUE_FAILED: <落库原因>`;
- **告警节奏(防轰炸)**:AlertScene `DING_BRIDGE_OUTBOUND_FAILED`——首转 FAILED 告警一次(排程了重试为 retry 文案,不可重试为 final 文案),sweep 中途静默,达上限/不可重试终态补一次;撤销失败单次告警。送达依赖 `alertPushEnable` + 已配置 messenger(治理告警同通道);
- **详情页面板**:工单/发布单详情出站区呈现四态(未发起/发起中/已发起+钉钉流程号/失败+原因)+「重新发起」按钮(可见性 = REQUEST/APPROVE 任一权限位;置灰镜像 ALREADY_STARTED/IN_FLIGHT;DISABLED 不可静态镜像——root KV 子账号不可读,按钮可点、后端 i18n 拒绝文案经错误弹窗直达 = 主动提示)。

### 10.5 发布单重提 = 新审批轮次

`releases/resubmit`(RETURNED → 重提)重建审批面:本项目在 resubmit 事务内对新审批行显式清空 `appro_identity` + 出站四列并将 `approval_round` **+1**(轮次清空契约显性化;ADR-012 起为**轮次自增语义**,§10.2.1),事务提交后**重新出站发起**(context 携带新 round)。旧实例已由 return 时的 `RELEASE_RETURNED` 撤销通知关闭;桥接侧对第二轮 `flows/create` **必须返回新 flowId**(§10.2 第二轮语义)。

注意区分:**工单 External resubmit**(`/tickets/resubmit`,REVISION → 直落 WAIT_EXEC)是执行失败后的重提,**原审批仍然有效——不清空、不重新出站**(D5 语义,旧 flowId 存留有效)。

### 10.6 边界与并发(关键行摘录)

| 场景 | 行为 |
|---|---|
| 开关 off / baseUrl 空 | 全部出站静默跳过(NOT_STARTED 停留);入站命令面照常 = 纯拉模式 |
| 事件先于出站响应到达(极端并发) | 入站命令不依赖 `outbound_status`(守卫只看工单状态)→ 照常受理;出站响应后到 STARTED 照落。**ADR-012 观测条款**:此时 identity 为空,入站 flowId 反写照常(宽松红线)但记 warn 日志+成功事件 `identityBackfill=true` 标记(§4.5)——空窗频率可测,严格档灰度依据 |
| 出站回填 vs 入站 externalFlowId 竞争 | `appro_identity` 仅空时写,先到先得(双源同实例,值一致——ADR-012 起「同值」由假设升级为**校验**:入站三态命令携带值与已记录值不一致即拒 `FLOW_ID_MISMATCH`,§4.5);被抢占时回填跳过、STARTED 照落 |
| 人工 cancel 后桥接 approve 事件到达 | 状态守卫拒绝(CANCELED 终态)——入站面已交付行为 |
| 人工 cancel 但出站撤销失败 | 本地流转不回滚,仅告警;僵尸实例由守卫无害化 |
| 手动重发与 sweep 并发 | 行级 CAS 双向:reissue 拒 STARTING;sweep 认领要求 FAILED + retry_count 匹配 |
| 途径 1 的发布单(open 面 create) | service 层单挂点默认**同样触发出站 flows/create**(不区分 console/open 入口)。途径 1(钉钉内审批已完成后转单)以 **`skipOutbound=true` 显式化解**(§5.8,M4b 补丁 2026-10-10):出站钩子跳过、桥接零调用、不重复新建实例,发布单落库/审批面/入站命令面不受影响;未携带该参数的 create 出站照常——桥接侧仍须按 §10.2 幂等映射回原钉钉流程、不得重复新建实例(兜底语义,第二道网) |
| 出站发起后桥接极端慢 | afterCommit 同步发送:提交/建单 HTTP 响应最长延迟 ≈ 3s connect + KV read 超时(默认 10s)≈ 13s;**事务已提交、单据已落库**,仅响应变慢(联调手册 §9.3) |

## 11. 三途径编排时序(2026-10-10 新增)

> 责任划分:钉钉侧交互(H5/表单选台账、事件监听、钉钉用户→本项目账号映射表、`creatorAccount` 租户内校验材料)与**组合中断重试/最终一致自查** = 桥接侧责任;本项目保证各端点幂等 + 状态守卫。联调 checklist:`tests/governance/dingtalk_bridge.md`。

### 11.1 途径 1:钉钉流程内选台账 → 自动转生产发布单

```
桥接(钉钉审批流程内)                                open-cdm
─────────────────────────────                       ─────────────────────────────
ledger/list(AK/SK,租户隔离)      ──────────────▶   仅返回本租户台账(空集=无可选项,§5.10)
用户在钉钉内选条目 → 审批通过事件
releases/create {ticketIds, creatorAccount, title, skipOutbound=true}
                                     ──────────────▶   建发布单(审批工单 WAIT_APPROVAL)
                                                       └─ skipOutbound=true → 出站零触发
                                                          (§5.8/§10.6 末行,M4b:钉钉审批
                                                           已在桥接流程内完成,不重复新建
                                                           实例——该钉钉流转本身即审批)
releases/detail {releaseId}        ──────────────▶   取 approvalId(= 命令路由键 ticketId)
tickets/approve {ticketId=approvalId, approver, comment, externalFlowId}
                                     ──────────────▶   发布单 APPROVED;审批工单 WAIT_CONFIRM
tickets/execute(§11.4 约定)        ──────────────▶   WAIT_EXEC → startExecution → EXECUTING → DONE
```

- **出站回声化解(M4b,用户裁决 2026-10-10)**:途径 1 的 create **必须携带 `skipOutbound=true`**(§5.8)——钉钉审批已在桥接自身流程内完成,本项目不再出站发起;发布单落库/审批面/入站命令面与缺省行为逐位一致。未携带该参数的 open create(如桥接代建一个**需要**钉钉审批的发布单)出站照常发起;
- **组合责任**:`create → detail → approve (→ execute)` 是桥接侧的编排事务——create 成功后任何一步失败由**桥接负责重试收敛**(本项目各段分别幂等:create 重放撞 `uk_source_stmt`/租户锁报清晰错误;approve/execute 重放被状态前置守卫拒绝,零副作用);**最终一致自查**由桥接用读端点(C/A)完成;
- **台账已被合并**(并发竞争/选择过期):`releases/create` 撞 `uk_source_stmt` 报错(§5.8 既有语义),桥接传导回钉钉侧提示重选(§5.10 实时性口径);
- **身份映射**:`creatorAccount` 必须是调用方租户内的平台账号(解析用户 puid 一致性校验,跨租户 = not-found);钉钉用户 → 账号映射表由桥接侧维护。

### 11.2 途径 2:本项目页面建发布单 → 出站发起钉钉审批

```
页面选台账建发布单(External 环境)→ 事务提交
  ↓ afterCommit 出站 flows/create {bizType:RELEASE, bizId:releaseId}  → 桥接建钉钉实例
  ↓ flowId 回填 appro_identity;outbound_status=STARTED(详情页面板「已发起」+流程号)
钉钉审批通过事件 → 桥接 tickets/approve {ticketId=approvalId}   → 发布单 APPROVED
  ↓ tickets/execute(§11.4)                                     → 执行链 → DONE
钉钉审批拒绝事件 → 桥接 tickets/reject                          → 发布单 REJECTED + 删 stmt 行(终态)
钉钉侧撤销       → 桥接 tickets/cancel                          → 发布单 CANCELED + 删 stmt 行(终态)
本地人工退回     → releases/return(或页面操作)                 → 出站 flows/cancel(RELEASE_RETURNED)
  ↓ releases/resubmit → 新审批轮次(§10.5:清四列重新出站,桥接返回新 flowId)
```

### 11.3 途径 3:PROD_DML 提交选项(单级审批显性化)

```
提交页(PROD_DML)approvalMode 二选一:
  ① DIRECT「钉钉审批(外部流程)」(环境通道 = External 时的显性化文案)
       → 提交 → WAIT_APPROVAL → afterCommit 出站 flows/create {bizType:TICKET, bizId:ticketId}
       → 钉钉事件 → tickets/approve → tickets/execute → FINISHED
  ② VIA_RELEASE「随发布单」
       → 提交 → WAIT_RELEASE 进变更台账池(行为逐位不变;无审批实例、零出站)
       → 后续经途径 1/2 合并进发布单(ledger/list 可见:promoted=false)
```

- 文案联动:环境 `SQL_TICKET_INFO type=External` 时 DIRECT 选项显示「钉钉审批(外部流程)」及桥接语义描述;Internal/未配置环境保持原文案(回归 pin);VIA_RELEASE 文案不变;
- 执行失败退回环(既有语义不变):`EXEC_FAIL` → 桥接 `tickets/return` → `REVISION` → `tickets/resubmit` 直落 `WAIT_EXEC`(原审批仍有效,零新审批记录、零出站,D5)。

### 11.4 执行触发单一化约定

- **约定:桥接在 `tickets/approve` 成功后自动调 `tickets/execute`**(外部编排的全自动语义);
- 页面人工确认仍可用(console 确认链不变)——**状态守卫保证互斥无害**:`execute` 前置 `WAIT_CONFIRM`,先到先得;后到者因状态已 `WAIT_EXEC`+ 被拒(零副作用),不存在双执行;
- 发布单同构(approve → handleApproved → 审批工单 `WAIT_CONFIRM` → execute → startExecution)。

### 11.5 钉钉「退回发起人修改」映射(审批阶段)

审批阶段命令只有**三态**(通过/拒绝/撤销,09-16 D4 冻结),无「审批阶段退回」命令。钉钉「退回发起人修改」事件映射为 **reject + 重新提单组合**(零接口改动):

```
钉钉退回发起人修改事件
  → 桥接 tickets/reject {ticketId, reason=<退回原因>}   → 工单 REJECTED(终态,事件留痕)
  → 发起人修改 SQL → 重新提单:
      a) console 页面重新提交(新单);或
      b) 桥接编排 tickets/submit 代提交(新单,ownerAccount=原申请人)
  → 新 ticketId / 新审批行 → WAIT_APPROVAL → 出站 flows/create(新 bizId = 全新钉钉实例)
```

- **单号变化语义**:REJECTED 是终态,修改重提 = **新单**(新 ticketId),旧单留痕可审计;桥接侧负责钉钉侧新旧实例/单号的关联展示;
- **注意区分**:`/tickets/resubmit` 端点的前置状态是 `REVISION`(仅适用执行失败退回,§5.7),**不适用于 REJECTED 工单**(状态守卫拒绝)——「reject+resubmit 组合」中的 resubmit 指**重新提交新单的动作**(页面或 `/tickets/submit`),不是 resubmit 端点。
