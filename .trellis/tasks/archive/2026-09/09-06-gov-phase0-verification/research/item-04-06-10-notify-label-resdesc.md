# Research: 验证项 ④⑥⑩ — 通知复用 / 工单确认权限标签 / res_desc 列

- **Query**: CloudDM 二次开发治理 Phase 0 验证项 ④（通知能力复用）、⑥（工单确认权限标签现状）、⑩（res_desc 列现有使用方式）
- **Scope**: internal（代码实勘）
- **Date**: 2026-09-06

---

## 结论速览

**④ 通知能力复用**：`MsgSendSpi`（SDK SPI，DingTalk/Feishu/Wechat 三实现）仅支持纯文本 Text，无模板表/Markdown/卡片；`ImSenderService`（console 接口）是 cicd 消息入口，强依赖 `DmChangeFlowDO`（流程绑定 webhook）。治理层推荐用 `ImSenderServiceImpl.sendMessage(ownerUid, ImSenderConfig, MsgContent)` 直接入口（绕开 flow 依赖），收件人=工单提交人（`DmApprovalDO.ownerUid`）。无钉钉 Provider 时 `PluginManager.findSpi` 返回 null → 抛异常，无站内信/邮件降级通道。前端工单详情深链格式为 `/ticket/{id}`。`ApprovalHandler` 接口已有"工单状态变化→传 ImSenderService"先例（`ApprovalStateServiceImpl.finalizeApproval` 派发），但 `ChangeApprovalHandler` 的消息发送实际依赖 cicd `changeId/flowId`，非治理工单需自建消息体。

**⑥ 工单确认权限标签**：`confirm` 接口用 `RDP_WORKER_ORDER_EXECUTE`（label 串同名常量，tag=DBA，include READ）。PROD 执行确认复用该标签语义足够（DBA-only，与授权矩阵一致），治理 PROD 禁 SKIP 等额外约束由治理门禁（触点 #5）承接，不依赖标签。建议：复用 `RDP_WORKER_ORDER_EXECUTE`，不新增 `RDP_DB_CHANGE_PROD_CONFIRM`——除非需要审计级区分"确认普通工单"与"确认 PROD 变更"。

**⑩ res_desc 列**：`res_desc` 是 `varchar(512) NOT NULL`，纯展示字段，无任何 WHERE/LIKE/格式解析依赖。写入点全部来自 `DmDsDO.instanceDesc`（或 instanceId 兜底）或 ApplyAuth FO 透传。`mergeGrantedAuth` 同键同 duration 合并时只 merge label、**不碰 resDesc**（保留旧行值）。`PERM_GROUP:<groupId>:<groupResourceId>` 标记方案可行，唯一副作用：`ticketDetail.vue:510` 将 resDesc 显示为"数据源实例"名（DATA_SOURCE_AUTH 工单可见），以及审批表单拼接 `resInstId(resDesc)`——标记串会被用户看到。spec"展开必须绕过 merge 直接 insert"的必要性已验证成立。

---

## ④ 通知能力复用

### 4.1 SPI 接口定义与实现

| 层 | 文件 | 关键签名 |
|---|---|---|
| SDK SPI | `cgdm-plugin-sdk/.../messenger/MsgSendSpi.java` | `interface MsgSendSpi extends Spi { MsgSendResult sendMessage(MsgSendConfig, MsgContent); }` |
| SDK 消息体 | `cgdm-plugin-sdk/.../messenger/MsgContent.java` | `String messageId; List<String> atTarget; MsgSendType type; String body;` |
| SDK 消息类型 | `cgdm-plugin-sdk/.../messenger/MsgSendType.java` | `enum MsgSendType { Text }` — **仅 Text，无 Markdown/Card** |
| SDK Provider 枚举 | `cgdm-plugin-sdk/.../messenger/MsgProviderType.java` | `enum MsgProviderType { DingTalk, Wechat, Feishu }`（Discord/Slack/Email 被注释掉） |
| 钉钉实现 | `plus-provider-dingtalk/.../im/DingTalkMsgSendSpi.java` | `implements MsgSendSpi`；`name()=DingTalk`；`sendMessage` 走 robot webhook（`OapiRobotSendRequest`）；**switch(message.getType()) 仅 Text 分支，default 抛 unsupported**；textMessage 把 `<b>` 替换为空格后写入 `req.setText(text)` |
| 飞书实现 | `plus-provider-feishu/.../im/FeishuMsgSendSpi.java` | 同 SPI 实现 |
| 企微实现 | `plus-provider-wechat/.../im/WechatMsgSendSpi.java` | 同 SPI 实现 |

**关键结论**：
- 无模板表/模板格式机制；消息体是纯文本 String（`MsgContent.body`），由调用方用 `DmI18nUtils.getMessage(key, locale, args...)` 拼装。
- 不支持 Markdown/卡片/深链按钮——钉钉 robot 只发 `msgtype=text`。深链只能作为 URL 文本嵌在消息体里（用户需手动复制/点击）。
- `MsgProviderType` 中 Email/Slack/Discord 均被注释——**无邮件 SPI，无站内信 SPI**。

### 4.2 ImSenderService 接口与实现

| 文件 | 说明 |
|---|---|
| `cgdm-console/.../component/cicd/ImSenderService.java` | console 侧接口，5 个方法：`getFlowLanguage`、3 个 `sendMessage(ownerUid, flowId, type, ...)` 流程版、1 个 `sendMessage(ownerUid, ImSenderConfig, MsgContent)` 直配版 |
| `cgdm-console/.../component/cicd/ImSenderServiceImpl.java` | `@Service` 实现 |

**流程版 sendMessage（line 86-114）**：
```java
public void sendMessage(String ownerUid, long flowId, ImMessageType messageType, MsgContent message) {
    DmChangeFlowDO flow = this.changeFlowDal.flowMapper().queryByOwnerAndId(ownerUid, flowId);
    if (flow == null) { throw ...; }
    if (!flow.isEnableMsg() || flow.getRefMsgId() == null) { sendDone(...failed...); return; }
    if (!messageType.testEnable(flow)) { return; }
    DmSysMessengerDO messengerDO = this.systemDal.messengerMapper().queryImById(ownerUid, flow.getRefMsgId());
    if (messengerDO == null) { throw ...; }
    ImSenderConfig imConfig = ImSenderConfig.builder()
        .imType(messengerDO.getImType())
        .webhookUrl(messengerDO.getWebhook())
        .secret(messengerDO.getSecret())
        .build();
    this.sendMessage(ownerUid, imConfig, message);  // 委托直配版
}
```
- 收件人 = flow 的 ownerUid（流程所有者）
- webhook/messenger 配置来自 `DmChangeFlowDO.refMsgId → DmSysMessengerDO`（流程绑定 IM）
- `ImMessageType` 枚举（`ChangeFlowStatus/FlowConfig/ChangeLife/ChangeNotice`）用 `Predicate<DmChangeFlowDO>` 决定是否发送

**直配版 sendMessage（line 117-139，治理层推荐入口）**：
```java
public MsgSendResult sendMessage(String ownerUid, ImSenderConfig imConfig, MsgContent message) {
    MsgSendSpi service = PluginManager.findSpi(MsgSendSpi.class, imConfig.getImType().getProviderType().name());
    if (service == null) {
        throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.DEVOPS_MISSING_PROVIDER.name(), imTypeI18n));
    }
    MsgSendConfig config = new MsgSendConfig();
    config.setWebhookUrl(imConfig.getWebhookUrl());
    config.setSecret(imConfig.getSecret());
    MsgSendResult result = service.sendMessage(config, message);
    this.sendDone(ownerUid, message, result);
    return result;
}
```
- **不依赖 flowId**，直接传 `ImSenderConfig`（imType + webhookUrl + secret）
- **无 Provider 时直接抛异常**（`DEVOPS_MISSING_PROVIDER`），不降级、不静默

### 4.3 console 侧现有调用先例

**cicd 模块调用点**：

| 调用方 | 文件:行 | 调用方式 |
|---|---|---|
| `AbstractChangeAction` | `component/cicd/action/AbstractChangeAction.java:43,55,61,67,75,81,91` | `@Resource ImSenderService senderService`；`sender.sendMessage(change.getOwnerUid(), change.getRefFlowId(), ImMessageType.ChangeNotice, errorMsg)` — 失败场景发错误消息 |
| `ChangeScheduleServiceImpl` | `component/cicd/impl/ChangeScheduleServiceImpl.java:65,213,217` | `senderService.getFlowLanguage` + `senderService.sendMessage(ownerUid, flowId, ImMessageType.ChangeNotice, errorMsg)` |
| `DmImServiceImpl` | `service/cicd/DmImServiceImpl.java:59,178` | `testImByConfig` 用直配版 `senderService.sendMessage(ownerUid, imConfig, testMessage)` — **测试 IM 配置** |
| `DmChangeFlowServiceImpl` | `service/cicd/DmChangeFlowServiceImpl.java:84` | 注入 ImSenderService |
| `DmChangeServiceImpl` | `service/cicd/DmChangeServiceImpl.java:90` | 注入 ImSenderService |
| `ChangeCascadeServiceImpl` | `service/cicd/ChangeCascadeServiceImpl.java:58` | 注入 ImSenderService |

**审批模块调用点（"工单状态变化→通知"先例）**：

| 文件 | 关键证据 |
|---|---|
| `component/approval/ApprovalHandler.java` | 接口方法**全部接收 `ImSenderService sender` 参数**：`createApproval(long, ImSenderService)`、`approvalCompleted/approvalApproved/approvalRejected/approvalFailed/approvalCanceled(long, ApprovalBiz, ImSenderService)`、`executeTicket/runningCheck(long, ApprovalBiz, ImSenderService)` |
| `component/approval/impl/ApprovalStateServiceImpl.java:48,72-82` | `@Resource ImSenderService imSenderService`；`finalizeApproval` 按 bizType 找 handler，调 `handler.approvalCompleted/approvalRejected/approvalFailed/approvalCanceled(ticketId, biz, this.imSenderService)` |
| `component/approval/handler/ChangeApprovalHandler.java` | `handleType()=DM_CHANGE`；`approvalApproved` 只更新状态（不发消息）；`approvalCompleted/Rejected/Failed/Canceled` 调 `updateChange` → 内部 `sender.sendMessage(changeDO.getOwnerUid(), changeDO.getRefFlowId(), ...)` — **但 updateChange 要求 `ApprovalMO.changeId != null && changeOwnerUid != null`（cicd 上下文），非 cicd 工单直接 return** |
| `component/approval/schedule/ApprovalTaskProcessor.java:90` | `@Resource ImSenderService imSenderService` |

**先例局限**：`ChangeApprovalHandler.updateChange`（line 223-273）依赖 cicd 的 `DmChangeDO`（changeId/flowId），治理工单（非 cicd 来源）在 `info.getChangeId()==null` 时直接 return，不发消息。治理层需要自建消息体+自选收件人，不能直接套用 `updateChange` 的消息逻辑。

### 4.4 治理层推荐调用入口

**推荐入口**：`ImSenderServiceImpl.sendMessage(String ownerUid, ImSenderConfig imConfig, MsgContent message)`
- 文件：`cgdm-console/.../component/cicd/ImSenderServiceImpl.java:117`
- 参数构造：
  - `ownerUid` = `DmApprovalDO.ownerUid`（工单提交人）
  - `ImSenderConfig` = `ImSenderConfig.builder().imType(...).webhookUrl(...).secret(...).build()` — 治理环境需配置一个 `DmSysMessengerDO`（可复用 cicd 的 IM 管理表 `dm_sys_messenger`，或治理专用配置）
  - `MsgContent` = `new MsgContent()`；`setMessageId(UUID)`；`setType(MsgSendType.Text)`；`setBody(textMsg)` — textMsg 用 `DmI18nUtils.getMessage` 拼装，含工单号/语句定位/错误摘要/深链 URL
- PROD 失败加推 DBA：再查 `dm_auth_user` 角色=DBA 的用户列表，逐个发送（或用 group webhook）
- 深链格式：`/ticket/{ticketId}`（前端路由 `router/index.js:101`：`path: '/ticket/:id'`），完整 URL 需拼平台 base URL

**非钉钉环境降级行为**：`PluginManager.findSpi(MsgSendSpi.class, providerType)` 返回 null → `throw ErrorMessageException(DEVOPS_MISSING_PROVIDER)`。**无站内信 SPI、无邮件 SPI**（`MsgProviderType` 中 Email 被注释）。未配 dingtalk provider 时通知直接失败抛异常，治理层应 try-catch 记录到 `dm_db_change_event`（事件类型 `STMT_FAIL_NOTIFIED` 带 result=failed）。

### 4.5 触发点建议

治理推进器扫描 EXEC_FAIL 工单触发通知（扫描式）——现有代码里有以下可参照的扫描式先例：
- `ApprovalTaskScheduler`（1s 守护循环，扫描审批状态）— 设计文档 §1.2 提及
- `DmAuthServiceForManageImpl.init()`（`ScheduledExecutorService`，120min 周期清理过期授权）— line 78-91
- `AutoExecScheduleService.scanPendingJob`（5s 扫描待派发 job）— 设计文档 §1.2 提及

推进器仿这些模式：独立守护线程、扫描 `dm_approval.status=EXEC_FAIL AND approBiz=DM_CHANGE AND ticketInfo含治理引用` 的工单、检查 `dm_db_change_event` 是否已有 `STMT_FAIL_NOTIFIED` 事件（幂等）、无则组装消息+发送+写事件。

---

## ⑥ 工单确认权限标签现状

### 6.1 ApprovalController 全部 @RequestAuth 标签

文件：`cgdm-console/.../controller/approval/ApprovalController.java`

| 接口 | 路径 | @RequestAuth 标签 | 常量名 | label 字符串 | tag（默认角色） | include |
|---|---|---|---|---|---|---|
| createTicket | `/create` | `level=HIGH, value=RDP_WORKER_ORDER_REQUEST` | `RDP_WORKER_ORDER_REQUEST` | `"RDP_WORKER_ORDER_REQUEST"` | DBA, DEV | RDP_WORKER_ORDER_READ |
| **confirmTicket** | `/confirm` | `level=HIGH, value=RDP_WORKER_ORDER_EXECUTE` | **`RDP_WORKER_ORDER_EXECUTE`** | `"RDP_WORKER_ORDER_EXECUTE"` | **DBA** | RDP_WORKER_ORDER_READ |
| approvalTicket | `/approval` | `level=HIGH, value=RDP_WORKER_ORDER_APPROVE` | `RDP_WORKER_ORDER_APPROVE` | `"RDP_WORKER_ORDER_APPROVE"` | DBA | RDP_WORKER_ORDER_READ |
| cancelTicket | `/cancel` | `level=HIGH, value={RDP_WORKER_ORDER_REQUEST, RDP_WORKER_ORDER_APPROVE}` | 两者任一 | 同上 | — | — |
| closeTicket | `/close` | `level=HIGH, value={RDP_WORKER_ORDER_REQUEST, RDP_WORKER_ORDER_APPROVE}` | 两者任一 | 同上 | — | — |
| **retryAutoExecJob** | `/retryAutoExecJob` | `level=HIGH, value=RDP_WORKER_ORDER_EXECUTE` | `RDP_WORKER_ORDER_EXECUTE` | `"RDP_WORKER_ORDER_EXECUTE"` | DBA | — |
| skipAutoExecTask | `/skipAutoExecTask` | `level=HIGH, value=RDP_WORKER_ORDER_READ` | `RDP_WORKER_ORDER_READ` | `"RDP_WORKER_ORDER_READ"` | DBA, DEV | — |
| continueAutoExecTask | `/continueAutoExecTask` | `level=HIGH, value=RDP_WORKER_ORDER_READ` | `RDP_WORKER_ORDER_READ` | `"RDP_WORKER_ORDER_READ"` | DBA, DEV | — |
| stopAutoExecJob | `/stopAutoExecJob` | `level=HIGH, value=RDP_WORKER_ORDER_EXECUTE` | `RDP_WORKER_ORDER_EXECUTE` | `"RDP_WORKER_ORDER_EXECUTE"` | DBA | — |
| endAutoExecJob | `/endAutoExecJob` | `level=HIGH, value=RDP_WORKER_ORDER_READ` | `RDP_WORKER_ORDER_READ` | `"RDP_WORKER_ORDER_READ"` | DBA, DEV | — |
| createDataSourceAuthTicket | `/createDataSourceAuthApproval` | `strategy=Ignore`（无需鉴权） | — | — | — | — |

### 6.2 SecRoleAuthLabel 定义

文件：`cgdm-plugin-sdk/.../security/auth/def/SecRoleAuthLabel.java`

```java
@AuthLabel(order = 0, category = CAT_RDP_WORKER_ORDER, i18nKey = AUTH_KEY_RDP_WORKER_ORDER_READ, tag = { SecSysRole.DBA_ROLE_NAME, SecSysRole.DEV_ROLE_NAME })
String RDP_WORKER_ORDER_READ    = "RDP_WORKER_ORDER_READ";       // order=0

@AuthLabel(order = 1, ..., tag = { SecSysRole.DBA_ROLE_NAME, SecSysRole.DEV_ROLE_NAME })
String RDP_WORKER_ORDER_REQUEST = "RDP_WORKER_ORDER_REQUEST";   // order=1, include READ

@AuthLabel(order = 2, ..., tag = { SecSysRole.DBA_ROLE_NAME })
String RDP_WORKER_ORDER_APPROVE = "RDP_WORKER_ORDER_APPROVE";   // order=2, include READ

@AuthLabel(order = 3, ..., tag = { SecSysRole.DBA_ROLE_NAME })
String RDP_WORKER_ORDER_EXECUTE = "RDP_WORKER_ORDER_EXECUTE";   // order=3, include READ
```

**label 字符串 = 常量名**（Java interface 常量，值与名相同）。`tag` 是默认建议授予角色；`include` 是级联包含的下级标签。鉴权逻辑在 `RequestAuthServiceImpl`（启动扫描 URL→标签映射）+ `DmAuthServiceForBiz.checkRoleAuth`。

### 6.3 PROD 执行确认：复用 vs 新增

**现有 confirm 标签**：`RDP_WORKER_ORDER_EXECUTE`（tag=DBA only，include READ）

**设计 spec §5.2 原文**：
> 生产执行确认 → 优先复用现有工单确认权限（实施期确认 `ApprovalController.confirm` 现有标签，无则补 `RDP_DB_CHANGE_PROD_CONFIRM`，见 §13）

**结论与建议**：
- `RDP_WORKER_ORDER_EXECUTE` 已存在且 tag=DBA only，与授权矩阵"生产执行确认=DBA"完全一致。
- **复用语义足够**：DBA 持有该标签即可确认执行；治理层三层 AND 的第三层（治理门禁）已在 `prepareExecJobAsync` Guard 处提供 PROD 专属校验（hash 复验/Preflight/配置合规），不依赖标签层区分。
- **额外约束不靠标签**：治理 PROD 禁 SKIP（`skipTask`/`continueTask` 拒绝）由触点 #5 承接；`stopAutoExecJob`/`endAutoExecJob` 同理。这些操作虽有 `RDP_WORKER_ORDER_EXECUTE`/`READ` 标签，但治理门禁会拦截。
- **建议复用 `RDP_WORKER_ORDER_EXECUTE`**，不新增 `RDP_DB_CHANGE_PROD_CONFIRM`。除非有以下需求之一：①审计需要区分"确认普通工单"与"确认 PROD 变更"两个动作；②需要将"PROD 确认权"单独授予非 DBA 角色（如发布负责人）。当前授权矩阵确认人=DBA，无需新增。

---

## ⑩ res_desc 列现有使用方式

### 10.1 表定义与 DO 字段

| 项 | 值 |
|---|---|
| 表 | `dm_auth_res` |
| 列定义 | `res_desc varchar(512) COLLATE utf8mb4_general_ci NOT NULL`（`V202605070030__init_sql.java:520`，`V202605070001__init_sql.java:338` 同） |
| DO 字段 | `DmAuthResDO.resDesc`（`private String resDesc;`，无特殊注解，`DmAuthResDO.java:52`） |
| Mapper resultMap | `<result column="res_desc" property="resDesc"/>`（`DmAuthResMapper.xml:11`） |
| SQL 片段 | 在 `rdpResAuth` 和 `rdpResAuthWithoutAuthLabel` 两个 `<sql>` 片段中均包含 `res_desc`（line 29, 50） |

### 10.2 所有写入点（setResDesc）

| # | 文件:行 | 场景 | 写入内容 |
|---|---|---|---|
| 1 | `DmAuthServiceForManageImpl.java:596` | `normalizeGlobalAuth`（全局授权规范化） | `"ALL"`（固定值） |
| 2 | `DmAuthServiceForBizImpl.java:356` | 业务鉴权时构造 DO | `dsDO.getInstanceDesc()`（数据源实例描述） |
| 3 | `RdpConvertUtils.java:423` | `convertToAuthDOFromInsert`（管理授权追加） | `instDesc` 参数（来自 `fillExtraInfo`：`DmDsDO.getInstanceDesc()`，空则 `getInstanceId()`） |
| 4 | `RdpConvertUtils.java:437` | `convertToAuthDOFromApply`（审批工单授权申请） | `info.getResDesc()`（ApplyAuth FO 透传） |
| 5 | `RdpConvertUtils.java:453` | `convertToAuthDOFromUpdate`（管理授权更新） | `instDesc` 参数（同 #3） |
| 6 | `RdpConvertUtils.java:516` | `convertToAuthDOByDataSource`（按数据源构造） | `rdpDatasourceDo.getInstanceDesc()` |
| 7 | `AuthApprovalHandler.java:219` | 授权审批 handler 处理 | `applyAuth.getResDesc()`（ApplyAuth 透传） |
| 8 | `ApprovalControlServiceImpl.java:703,708` | 创建授权工单时填充 ApplyAuth | `resDescMap.get(resId)` = `DmDsDO.getInstanceDesc()`（空则 `getInstanceId()`，再空则 `resourceId`） |

**写入规律**：resDesc 始终来自数据源实例描述（`DmDsDO.instanceDesc`）或申请表单透传，无任何业务逻辑解析其内容。

### 10.3 所有读取点（getResDesc）

| # | 文件:行 | 场景 | 用途 |
|---|---|---|---|
| 1 | `RdpConvertUtils.java:236` | `convertToResAuthVO` → `vo.setResDesc(dsAuthDO.getResDesc())` | 前端展示：`ResAuthVO.resDesc` 字段 |
| 2 | `RdpConvertUtils.java:437` | `convertToAuthDOFromApply` → `info.getResDesc()` | FO 字段透传（非语义读取） |
| 3 | `DingApiUtils.java:138` | 钉钉审批表单 | `safeLength(applyAuth.getResInstId() + "(" + applyAuth.getResDesc() + ")", 400, true)` — 拼入审批表单文本 |
| 4 | `FeishuApiUtils.java:114` | 飞书审批表单 | 同上格式 `resInstId(resDesc)` |
| 5 | `WechatApprovalProviderSpi.java:173` | 企微审批表单 | 同上格式 `resInstId(resDesc)` |

**前端展示点**：
- `frontend/src/views/ticket/ticketDetail.vue:510`：`{{ authItem.resDesc }}` — 显示为"数据源实例"名（DATA_SOURCE_AUTH 工单内容区）
- `frontend/src/views/ticket/ticketDetail.vue:1067`：`resDesc: authItem.resDesc || authItem.resInstId || String(authItem.resId)` — fallback 逻辑
- `frontend/src/views/system/subaccount/auth/authDm.vue`：**不直接引用 resDesc**（grep 无命中），授权管理页用 `resInstId` 做实例匹配与展示

**API 返回路径**：`RdpResAuthController`（4 个接口：`listUserAuthOfRes`/`listUserAuthRes`/`listMyAuthOfRes`/`listMyAuthRes`）均调 `convertToResAuthVO` → 返回 `ResAuthVO`（含 resDesc）给前端。

### 10.4 Mapper 查询中的 res_desc

`DmAuthResMapper.xml` 全部查询：
- `queryAuthCountByUser`：`COUNT(*)`，不查 res_desc
- `queryByPathLike`/`queryByLikePath`/`queryByPath`/`listByKind`/`listEffectiveGlobalByUser`/`listWithoutLabels`/`queryByUniqueKey`：SELECT 含 res_desc，**WHERE 子句从不使用 res_desc**
- `updateAuthById`：UPDATE 只改 `res_auth_label`/`start_time`/`end_time`，**不动 res_desc**
- 所有 DELETE 语句：按 `owner_uid`/`res_id`/`kind_type`/`res_path` 删，**不按 res_desc 删**

**关键结论**：res_desc 在 SQL 层无 LIKE 查询、无 WHERE 条件、无格式假设——纯展示字段。

### 10.5 mergeGrantedAuth 行为分析

文件：`DmAuthServiceForManageImpl.java:417-441`

```java
private void mergeGrantedAuth(DmAuthResDO grantAuth) {
    normalizeGlobalAuth(grantAuth);
    if (CollectionUtils.isEmpty(grantAuth.getAuthLabels())) { return; }

    List<DmAuthResDO> existingAuths = this.authDal.resMapper()
        .queryByPath(grantAuth.getResId(), grantAuth.getOwnerUid(), grantAuth.getKindType(), grantAuth.getResPath());
    DmAuthResDO sameDurationAuth = existingAuths.stream()
        .filter(existing -> Objects.equals(existing.getStartTime(), grantAuth.getStartTime())
                         && Objects.equals(existing.getEndTime(), grantAuth.getEndTime()))
        .findFirst().orElse(null);

    if (sameDurationAuth == null) {
        grantAuth.setAuthLabels(this.getCascadeAuthByLabel(grantAuth.getAuthLabels()));
        this.authDal.resMapper().insert(grantAuth);   // ← 新行，resDesc 来自调用方
        return;
    }

    // 同键同 duration：只 merge label，不碰 resDesc
    Set<String> mergedLabels = new HashSet<>(sameDurationAuth.getAuthLabels());
    mergedLabels.addAll(grantAuth.getAuthLabels());
    sameDurationAuth.setAuthLabels(new ArrayList<>(this.evalLabels(sameDurationAuth.getAuthLabels(), new ArrayList<>(mergedLabels))));
    sameDurationAuth.setGmtModified(new Date());
    this.authDal.resMapper().updateById(sameDurationAuth);   // ← 旧行 resDesc 保留
}
```

**行为总结**：
- **同键（resId+ownerUid+kindType+resPath）同 duration（startTime+endTime 完全相等）**：`updateById` 合并 label，**resDesc 不被修改**（保留旧行值）
- **同键不同 duration**：`insert` 新行（新 resDesc 来自调用方）
- **无同键行**：`insert` 新行

**对 PERM_GROUP 标记方案的影响**：
1. 若权限组展开行与用户直接授权行**同键同 duration**，`mergeGrantedAuth` 会 merge label 并保留旧行 resDesc——**PERM_GROUP 标记不会被写入**（被旧 resDesc 覆盖），且权限组 label 会被 merge 进直接授权行，回收时 `revokeGrantedAuth` 会从合并行删 label，**可能误删直接授权的 label**。
2. 即使标记成功写入，`revokeGrantedAuth`（line 443-470）按 path 查行后删 label，**不区分行来源**——会同时影响同键的所有行。

### 10.6 冲突分析与结论

**PERM_GROUP:<groupId>:<groupResourceId> 标记方案可行性**：

| 检查项 | 结论 | 风险 |
|---|---|---|
| SQL 查询依赖 | 无 WHERE/LIKE 依赖 | 无冲突 |
| NOT NULL 约束 | 标记串非空，满足 | 无冲突 |
| varchar(512) 长度 | `PERM_GROUP:<longId>:<longId>` 远小于 512 | 无冲突 |
| 前端 authDm.vue 展示 | 不引用 resDesc | 无冲突 |
| 前端 ticketDetail.vue 展示 | `:510` 显示为"数据源实例"名 | **DATA_SOURCE_AUTH 工单内容区会显示标记串**（但治理工单是 DM_CHANGE 非 DATA_SOURCE_AUTH，影响极小） |
| 审批表单拼接 | DingTalk/Feishu/Wechat 拼成 `resInstId(resDesc)` | **权限组展开行若出现在审批表单（DATA_SOURCE_AUTH 工单），标记串会暴露给审批人** |
| mergeGrantedAuth | 同键同 duration 合并时 resDesc 不被写入 | **必须绕过 merge** |

**结论**：res_desc 标记方案**可行**。唯一显示副作用是审批表单/工单内容区的 `resInstId(resDesc)` 拼接会暴露标记串，但权限组展开行不会出现在 DM_CHANGE 工单内容区（治理工单不申请 DATA_SOURCE_AUTH），且 DATA_SOURCE_AUTH 审批工单属于授权申请场景与治理 DB 变更无交集。

**spec"展开必须绕过 merge 直接 insert"的必要性已验证**：
- `mergeGrantedAuth` 同键同 duration 时 `updateById` 不改 resDesc → 标记无法写入
- merge 会把组 label 混入直接授权行 → 回收时 `revokeGrantedAuth` 误删直接授权 label
- 治理层展开必须直接调 `authDal.resMapper().insert()` 写独立行（带 PERM_GROUP 标记 + 组 label），与直接授权行物理隔离，回收时按账本 `auth_res_id` 精确删行

**更稳妥的替代（若需避免标记暴露）**：可考虑用 `res_inst_id` 列（`varchar(512) NULL`）写标记（当前展开行 resInstId = 数据源 instanceId，可追加后缀 `_PG:<groupId>`），但 spec 已选 res_desc 且无查询依赖，维持原方案即可。

---

## Caveats / Not Found

- **MsgSendSpi 不支持 Markdown/卡片**：钉钉 robot 只发 `msgtype=text`，深链只能作为纯文本 URL 嵌在消息体。若需可点击深链按钮，需走钉钉审批实例（`DingApprovalProviderSpi`，走 ActionCard）而非 MsgSendSpi，但那创建的是审批实例不是通知。
- **无邮件/站内信 SPI**：`MsgProviderType` 中 Email 被注释，无任何邮件/站内信发送接口。治理层若需非钉钉降级，只能写 `dm_db_change_event` 记录通知失败 + 平台内查看。
- **ImSenderService 流程版强依赖 cicd flow**：治理工单无 `DmChangeFlowDO`，不能直接用 `sendMessage(ownerUid, flowId, ...)` 流程版，必须用直配版 `sendMessage(ownerUid, ImSenderConfig, MsgContent)` 且需自行获取 messenger 配置。
- **ChangeApprovalHandler 的通知先例不通用**：`updateChange` 要求 cicd `changeId/flowId`，非 cicd 工单不发消息。治理层需自建消息体。
- **RDP_WORKER_ORDER_EXECUTE 同时授予 skip/stop/retry 权限**：治理 PROD 禁 SKIP 靠治理门禁（触点 #5）拦截，不靠标签。确认无误。
