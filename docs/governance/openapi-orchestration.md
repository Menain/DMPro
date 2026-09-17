# 治理编排开放接口对接指南

> 适用于外部编排架构(用户自建审批引擎 + 自研项目持有钉钉 Stream,open-cdm 提供全生命周期命令式开放 API)。
> 本文档面向自研项目与审批引擎的对接开发,涵盖端点全集、签名规则、幂等与守卫语义、编排时序、错误矩阵。

## 1. 架构定位

open-cdm 在外部编排架构中定位收窄为:

- **工单全生命周期 + SQL 预检/执行引擎 + 完整开放 API 面**;
- open-cdm **不碰钉钉**:不建外部审批实例、不持有 Stream、不轮询、不做审批对账;
- 提交创建内部工单 + 过程记录 → 落 `WAIT_APPROVAL` **等待外部命令**;
- **外部引擎 = 审批流转唯一真相源,命令即事实**;open-cdm 靠状态前置守卫 + 幂等保证重放/乱序无害;
- 唯一特殊流转:SQL 执行失败 → 退回申请人 → `REVISION` → 修改重提 → 预检通过 → **跳过审批创建,直接落 `WAIT_EXEC`**。

External(外部编排)审批通道类型:提交时不建外部实例、无审批人列表、`WAIT_APPROVAL` 等待命令;调度器对该类型 no-op(不轮询、不调 getLastInfo)。

## 2. 端点全集(13 个)

所有端点位于 `POST /api/open/dbchange/*`,认证方式为 AK/SK 签名(见 §3),`@RequestBody` JSON。

### 2.1 既有读端点(4 个)

| 接口 | 路径 | 用途 |
|---|---|---|
| A | `POST /api/open/dbchange/tickets` | 按数据库名查询变更工单列表 |
| B | `POST /api/open/dbchange/tickets/statements` | 按工单 ID 查询语句详情与预检结果 |
| C | `POST /api/open/dbchange/releases/detail` | 按发布单 ID 查询生产发布单详情 |
| D | `POST /api/open/dbchange/releases/return` | 退回发布单(APPROVING/APPROVED → RETURNED) |

> A/B/C 的请求/响应示例见 [`openapi-dingtalk.md`](./openapi-dingtalk.md) §3。D 见本文档 §5.3。

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
  "reason": "执行失败退回修改"
}
```

| 字段 | 必选 | 适用端点 | 说明 |
|---|---|---|---|
| `ticketId` | 是 | 全部 | dm_approval.id,按 approBiz 路由(工单/发布单) |
| `approver` | 否 | approve/reject/cancel | 审批人名,合成 activity 进度区展示 |
| `comment` | 否 | approve/reject/cancel | 审批意见 |
| `externalFlowId` | 否 | approve/reject/cancel | 外部流程 ID,落 approIdentity(仅当前为空时) |
| `reason` | 否 | return | 退回原因,入 eventData + IM 通知申请人 |

> 最简调用:只传 `ticketId` 即可驱动状态迁移;有 approver/comment 时增强进度区展示。

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
  "title": "2026-09-17 生产发布"
}
```

**响应:** `OpenDbChangeReleaseCreateVO` — `{releaseId, releaseNo, ticketId}`(ticketId = 发布单审批工单 id,命令路由键)

> 资格四条件 / 三门 / 租户锁 / uk_source_stmt 全复用。`creatorAccount` 缺省 = 服务账号自身。

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

## 6. External 通道语义

- **命令即事实**:外部引擎下发命令后,工单状态立即反映命令结果,不经过中间状态;
- **open-cdm 不对账**:不调 getLastInfo、不轮询、不建外部实例;外部引擎负责与钉钉审批状态的一致性;
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
| PROD_DML DIRECT | 提交 → WAIT_APPROVAL 等命令;resubmit 直落 WAIT_EXEC |
| DM_PROD_RELEASE | 建单 → WAIT_APPROVAL 等命令;approve → handleApproved → APPROVED |
| PRE_DDL | 自动推进链(External 不改变其行为,仍全自动到 WAIT_EXEC) |
| DM_QUERY / DATA_SOURCE_AUTH 等老业务面 | 不在范围(配置了也仅 no-op 等待,文档注明不支持) |

## 8. 安全说明

| 项 | 说明 |
|---|---|
| 签名覆盖范围 | 5 参数(AK + Method + Nonce + Timestamp + BodyDigest);body 经 SHA-256 摘要纳入签名 |
| 时间戳防重放 | ±5 分钟窗口;超窗口拒绝 |
| 无 nonce 去重 | 服务端无 nonce 缓存(网关职责);重放由状态前置守卫兜底 |
| 租户隔离 | AK/SK → puid,跨租户 not-found |
| 限流 | 平台无内置限流;建议网关/SLB 对 `/api/open/*` 配置 IP+AK 维度限流 |
| 操作审计 | 命令端点入口有结构化 `log.info`;治理事件表记录 operator uid + via + 元数据 |

## 9. 错误响应

签名校验失败返回 HTTP 497/498/499 + 空 body `{}`;业务校验失败返回 `ResWebData{code:<0>, msg:<i18n 错误>}`。

| i18n 键 | 场景 |
|---|---|
| `GOV_OPEN_COMMAND_INVALID_STATUS` | 命令前置状态不满足(携带 ticketId + 当前状态 + 命令) |
| `GOV_OPEN_COMMAND_CHANNEL_MISMATCH` | 非 External 通道工单收到命令 |
| `GOV_RELEASE_RETURN_INVALID_STATUS` | 发布单退回状态不满足 |
| `TICKET_OPERATOR_TYPE_NOT_MATCH_STATUS` | 工单状态与操作不匹配(既有键) |
