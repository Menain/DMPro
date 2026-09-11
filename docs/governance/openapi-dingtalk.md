# 治理开放接口与钉钉对接指南

> 适用于 P4（治理对外接口与钉钉对接）。本文档涵盖三个开放接口的请求/响应示例、AK/SK 签名调用方法、钉钉侧配置步骤，以及已知安全限制。

## 1. 接口总览

所有接口位于 `POST /api/open/dbchange/*`，认证方式为 AK/SK HmacSHA1 签名（由 `OpenApiSessionManager` 统一校验）。

| 接口 | 路径 | 用途 |
|---|---|---|
| A | `POST /api/open/dbchange/tickets` | 按数据库名查询变更工单列表 |
| B | `POST /api/open/dbchange/tickets/statements` | 按工单 ID 查询语句详情与预检结果 |
| C | `POST /api/open/dbchange/releases/detail` | 按发布单 ID 查询生产发布单详情 |

## 2. AK/SK 签名调用方法

### 2.1 四个公共参数

每次请求必须携带以下四个参数（query string 或 header 均可）：

| 参数 | 说明 |
|---|---|
| `AccessKeyId` | 用户的 Access Key ID（系统内用户的 AK） |
| `Signature` | HMAC-SHA1 签名（Base64 编码） |
| `SignatureMethod` | 固定值 `HmacSHA1` |
| `SignatureNonce` | 随机 UUID，每次请求唯一 |

### 2.2 签名流程

1. 构造待签名字符串：将 `SignatureMethod`、`SignatureNonce`、`AccessKeyId` 三个参数（不含 `Signature` 本身）按 key 字典序排序，对每个 key 和 value 做 percent-encoding（`+`→`%20`、`*`→`%2A`、`%7E`→`~`），用 `&` 连接。
2. 使用用户的 Secret Key（SK）对待签名字符串做 HMAC-SHA1 计算。
3. 将结果 Base64 编码，作为 `Signature` 参数值。

### 2.3 伪代码示例（Java）

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.net.URLEncoder;
import java.util.UUID;

public class OpenApiSigner {

    public static String sign(String accessKeyId, String secretKey) throws Exception {
        String signatureNonce = UUID.randomUUID().toString();
        String signatureMethod = "HmacSHA1";

        // Build sorted percent-encoded param string (exclude Signature itself)
        String params = "AccessKeyId=" + encode(accessKeyId)
            + "&SignatureMethod=" + encode(signatureMethod)
            + "&SignatureNonce=" + encode(signatureNonce);

        // HMAC-SHA1
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secretKey.getBytes("UTF-8"), "HmacSHA1"));
        byte[] rawHmac = mac.doFinal(params.getBytes("UTF-8"));

        // Base64
        String signature = Base64.getEncoder().encodeToString(rawHmac);

        // Build final URL: https://host/api/open/dbchange/tickets?AccessKeyId=...&SignatureMethod=...&SignatureNonce=...&Signature=...
        return "https://your-host/api/open/dbchange/tickets"
            + "?AccessKeyId=" + encode(accessKeyId)
            + "&SignatureMethod=" + encode(signatureMethod)
            + "&SignatureNonce=" + encode(signatureNonce)
            + "&Signature=" + encode(signature);
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~");
    }
}
```

### 2.4 调用示例（cURL）

```bash
curl -X POST \
  'https://your-host/api/open/dbchange/tickets?AccessKeyId=AK_xxx&SignatureMethod=HmacSHA1&SignatureNonce=uuid-xxx&Signature=base64sig' \
  -H 'Content-Type: application/json' \
  -d '{"dbName":"orders_db","page":1,"size":20}'
```

## 3. 接口请求/响应示例

### 3.1 接口 A：按数据库名查询工单

**请求：**
```json
POST /api/open/dbchange/tickets
{
  "dbName": "orders_db",
  "ticketType": "PRE_DDL",
  "executedOnly": false,
  "page": 1,
  "size": 20
}
```

**响应：**
```json
{
  "code": 0,
  "msg": "success",
  "data": [
    {
      "ticketId": 100,
      "ticketType": "PRE_DDL",
      "title": "orders 表新增列",
      "serviceName": "order-service",
      "dbNames": ["orders_db"],
      "sqlSummary": "ALTER TABLE orders ADD COLUMN note TEXT;",
      "execStatus": "EXECUTED",
      "executedAt": "2026-09-11 14:00:00",
      "promoted": true,
      "releaseId": 500,
      "releaseNo": "REL-20260911-0001",
      "releaseStatus": "DONE"
    }
  ]
}
```

**字段说明：**

| 字段 | 类型 | 说明 |
|---|---|---|
| `dbName` | String | **必填**。库名同时匹配预发侧和生产侧（dm_db_pair），两侧皆可查 |
| `ticketType` | String | 可选。`PRE_DDL` / `PROD_DML`，不传则返回全部 |
| `executedOnly` | Boolean | 默认 `false`。为 `true` 时仅返回 FINISHED 且全部成功的工单 |
| `execStatus` | String | 由工单状态+组状态推导：`WAIT_APPROVAL` / `EXECUTING` / `EXECUTED` / `FAILED` |
| `promoted` | Boolean | 是否已转入发布单 |

### 3.2 接口 B：按工单查语句详情

**请求：**
```json
POST /api/open/dbchange/tickets/statements
{
  "ticketId": 100
}
```

**响应：**
```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "ticketId": 100,
    "ticketType": "PRE_DDL",
    "title": "orders 表新增列",
    "serviceName": "order-service",
    "ticketStatus": "FINISHED",
    "groups": [
      {
        "groupId": 201,
        "pairId": 1,
        "dsId": 10,
        "dbName": "orders_db",
        "sqlContent": "ALTER TABLE orders ADD COLUMN note TEXT;",
        "precheckResult": "{\"checkStatus\":\"PASS\",\"changeType\":\"DDL\",\"statements\":[{\"index\":0,\"type\":\"ALTER_TABLE\",\"sql\":\"ALTER TABLE orders ADD COLUMN note TEXT;\"}],\"rulesCheck\":{\"checked\":true,\"messages\":[]},\"checkedAt\":\"2026-09-11T14:00:00+08:00\",\"engine\":\"v2\"}",
        "execStatus": "SUCCESS",
        "execDetail": null
      }
    ]
  }
}
```

> `precheckResult` 为 P2 §4 定义的 JSON 契约原样透出，钉钉侧可解析展示预检结果。

### 3.3 接口 C：按发布单查详情

**请求：**
```json
POST /api/open/dbchange/releases/detail
{
  "releaseId": 500
}
```

**响应：**
```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "id": 500,
    "releaseNo": "REL-20260911-0001",
    "title": "生产发布单",
    "status": "DONE",
    "approvalId": 600,
    "creatorUid": "uid-001",
    "gmtCreate": "2026-09-11 15:00:00",
    "gateResult": null,
    "stmtGroups": [
      {
        "prodDsId": 20,
        "prodDbName": "orders_db",
        "stmts": [
          {
            "id": 301,
            "seq": 1,
            "sqlContent": "ALTER TABLE orders ADD COLUMN note TEXT;",
            "hash": "a1b2c3d4...",
            "sourceTicketId": 100,
            "sourceStmtId": 201,
            "execStatus": "SUCCESS",
            "execDetail": null,
            "gmtCreate": "2026-09-11 15:00:00"
          }
        ]
      }
    ],
    "events": [
      {
        "eventType": "RELEASE_CREATED",
        "fromStatus": null,
        "toStatus": "APPROVING",
        "operatorUid": "uid-001",
        "gmtCreate": "2026-09-11 15:00:00",
        "eventData": null
      }
    ]
  }
}
```

## 4. 钉钉侧配置步骤

### 4.1 创建专用子账号

1. 在 CloudDM 系统中创建一个子账号（如 `dingtalk-bot`）。
2. 确保子账号的 AK/SK 已生成（`dm_auth_user` 表的 `access_key` / `secret_key`）。
3. 为子账号配置以下权限：
   - **DataSource 数据权限**：授予需要查询的生产数据源的数据权限（`DM_DAUTH_TICKET` 或更高）。
   - **角色权限**：授予 `RDP_WORKER_ORDER_READ` 角色（可查看工单）。
4. 将 AK/SK 配置在钉钉侧的调用代码中。

### 4.2 配置 SQL_TICKET_INFO 环境参数

1. 进入 CloudDM 控制台 → 环境管理 → 选择生产环境。
2. 在环境参数中配置 `SQL_TICKET_INFO`，JSON 格式如下：
   ```json
   {
     "type": "DingTalk",
     "templateId": "PROC-xxxxxxxx",
     "templateName": "数据库变更审批"
   }
   ```
3. `type` = `DingTalk` 表示使用钉钉审批通道；`Internal` 表示内置审批。
4. `templateId` 为钉钉审批模板的 processCode。

### 4.3 刷新审批模板

1. 在 CloudDM 控制台 → 环境管理 → 审批配置页面，点击「刷新模板」按钮。
2. 系统会通过 DingTalk SPI 拉取可用审批模板列表并缓存到 `dm_approval_template` 表。
3. 确保 `templateId` 对应的模板已出现在列表中。

### 4.4 钉钉审批模板字段映射

发布单和 v2 工单审批表单使用 `ChangeForm`，钉钉表单字段映射如下：

| ChangeForm 字段 | 钉钉表单字段 | 最大长度 | 来源 |
|---|---|---|---|
| `ticketTitle` | 标题 | 400 | 发布单号+标题 / 工单标题 |
| `ticketDesc` | 需求描述 | 4000 | 申请人/类型/服务/库数/链接 |
| `targetDs` | 目标数据源 | 400 | 生产库名列表 |
| `executeSql` | 执行 SQL | 4000 | 每库 SQL 摘要（截断+深链） |
| `flowName` | 发布流 | 400 | null（治理无 CI/CD 流） |
| `changeName` | 变更 | 400 | null |
| `branch` | 分支 | 400 | null |
| `ticketUserPhone` | （发起人解析） | - | 工单/发布单创建者手机号 |

> 钉钉表单需在钉钉管理后台配置对应字段（标题、需求描述、目标数据源、执行 SQL、发布流、变更、分支），并确保字段名称与上表一致。

## 5. 安全限制说明

| 限制 | 说明 | 建议 |
|---|---|---|
| **无限流** | 平台无内置限流机制，`/api/open/*` 路径无请求频率限制 | 建议在网关/SLB 层对 `/api/open/dbchange/*` 配置 IP+AK 维度的限流（如 100 req/min），防止钉钉重试风暴或恶意调用 |
| **Nonce 不去重** | `SignatureNonce` 参数虽要求唯一，但服务端无去重缓存，重放请求会再次执行 | 只读接口重放风险低；如需强防护，建议引入 Redis nonce 去重 |
| **签名不含 body** | 签名仅覆盖 4 个公共参数（AccessKeyId、SignatureMethod、SignatureNonce），不含请求体 | 与既有 openapi 行为一致；GET 请求无 body 影响，POST 接口的 body 未被签名保护 |
| **无操作审计** | openapi 读接口不写操作审计日志（与既有 openapi 惯例一致） | 每个接口入口有结构化 `log.info` 记录调用者、端点、参数、结果计数；如需正式审计留痕，需额外实现 |

## 6. 运维建议

- 钉钉专用子账号应授予最小权限：目标 DS 的 `DataSource` 数据权限 + `RDP_WORKER_ORDER_READ` 角色。
- 定期轮换 AK/SK。
- 生产环境的 `SQL_TICKET_INFO` 配置变更后需验证审批通道连通性。
- 监控 `/api/open/dbchange/*` 的访问日志，关注异常高频调用。
