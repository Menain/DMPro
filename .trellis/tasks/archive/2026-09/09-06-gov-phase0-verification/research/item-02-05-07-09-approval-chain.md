# Research: 验证项 ②⑤⑦⑨ — 审批漏斗身份校验 / autoExecConfig / approBiz 参数化 / ApprovalMO 扩展

- **Query**: CloudDM 二次开发治理平台 Phase 0 验证项 ②⑤⑦⑨（工单创建与审批漏斗）
- **Scope**: internal
- **Date**: 2026-09-06

---

## 结论速览

| 项 | 结论 | 可行性 | 最小改动落点 |
|---|---|---|---|
| ② 审批漏斗身份校验 | `approvalTicket` **Service 层硬校验**审批人列表（dm_approval_person），`confirmTicket` **Service 层硬校验**确认人身份（PRIMARY_ACCOUNT / RDP_WORKER_ORDER_EXECUTE+owner / 资源审批人列表）。SYSTEM 直接走现有漏斗**不可行**。但第三方回调路径 `ApprovalProviderServiceImpl.refreshApprovalStatus` 已有**绕过 approvalTicket 直接调 handler.approvalApproved** 的先例。 | 不可行（直走）→ 可行（定向通道） | 治理推进器仿回调先例：直接调 `handler.approvalApproved()` 跳过 approvalTicket；confirmTicket 需新增 SYSTEM 定向通道或以 PRIMARY_ACCOUNT 身份调 confirmTicket |
| ⑤ autoExecConfig | `DmAutoExecConfigFO`→`AutoExecCreateMO`→`RsExecAutoJobConfigObj`→`dm_exec_auto_job.config`（JacksonTypeHandler）。`confirmTicket` **原样透传**前端 config 到 createJob，**零合规校验**。系统代审可构造 config（confirmUid 是 @JsonIgnore 由 Controller 注入，无"只有确认人才能传"耦合，但 confirmUid 仍受 checkJobOperationEnable 校验）。 | 可行（注入）+ 需新增校验 | 门禁二配置合规校验挂在 `prepareExecJobAsync` 开头（设计 §2.2 触点 #2 已指定） |
| ⑦ approBiz 参数化 | `createSqlTicketInTransaction` 有**两处** DM_QUERY 写死（L809 setApproBiz + L834 createProcess）。下游全部已支持 DM_CHANGE（PreInit.supports / ApprovalStage.checkBiz / handler 路由 / 查询展示）。**但** `ChangeApprovalHandler.convertToChangeForm` 对外部审批**强依赖** `ApprovalMO.changeId/changeOwnerUid`（CI/CD 耦合），治理 PROD 工单走外部审批会抛异常。PRE（Internal）路径正常。 | 零行为变化（默认 DM_QUERY）；DM_CHANGE 走 createSqlTicket 在 PRE/Internal 链路天然支持；PROD/外部审批需处理 ChangeForm 耦合 | 参数化 = 给 `createSqlTicket` 增 `ApprovalBiz` 参数或给 `DmAddTicketFO` 加字段；PROD 外部审批表单需治理层自行构造或扩展 |
| ⑨ ApprovalMO 扩展 | 现有 4 字段（message/autoExec/changeOwnerUid/changeId）。`@JsonIgnoreProperties(ignoreUnknown=true)` + 全局 `FAIL_ON_UNKNOWN_PROPERTIES=false` 双保险。新增 promotionId/revisionId **安全**。**注意**：`updateAutoExecFlag`/`restoreExecutionConfirmation` 对 ticketInfo 做读-改-写，新字段必须**先加到 POJO** 再让治理层写入，否则会被读改写路径静默丢弃。 | 安全 | 直接加字段，无需改 @JsonIgnoreProperties（已有）；确保先改 POJO 再写数据 |

---

## ② 审批漏斗身份校验（影响 Phase 4 推进器 SYSTEM 代审）

### 核心代码证据

#### 2.1 `ApprovalFlowServiceImpl.approvalTicket` — Service 层硬校验审批人

文件：`backend/clouddm-platform/cgdm-console/src/main/java/com/clougence/clouddm/console/web/component/approval/impl/ApprovalFlowServiceImpl.java`

```java
// L127-166
public void approvalTicket(String puid, String uid, RdpApprovalFO fo) {
    DmApprovalDO ticketDO = checkTicket(fo.getTicketId());
    if (ticketDO.getTicketStatus() != ApprovalStatus.WAIT_APPROVAL) {   // L129 状态检查
        throw ...;
    }
    List<DmApprovalPersonDO> persons = this.approvalDal.personMapper().queryByTicketBzId(ticketDO.getBizId()); // L133
    List<String> allowUsers = persons.stream().map(DmApprovalPersonDO::getPersonUid).collect(Collectors.toList()); // L134
    if (!allowUsers.contains(uid)) {   // L136 ← Service 层身份硬校验
        throw new RuntimeException(DmI18nUtils.getMessage(I18nRdpMsgKeys.TICKET_APPROVAL_NO_PERMISSION_ERROR.name()));
    }
    // ... approvalApproved / transition ...
}
```

**校验内容**：
1. 状态必须 = `WAIT_APPROVAL`（L129）
2. `uid` 必须在 `dm_approval_person` 表中该工单 bizId 对应的审批人列表里（L133-138）

**位置**：Service 层（非仅 Controller 注解）。Controller `ApprovalController.approvalTicket`（L143-151）有 `@RequestAuth(RDP_WORKER_ORDER_APPROVE)`，但 Service 层还有独立的 person-list 校验。

#### 2.2 `ApprovalControlServiceImpl.confirmTicket` — Service 层硬校验确认人

文件：`backend/clouddm-platform/cgdm-console/src/main/java/com/clougence/clouddm/console/web/service/approval/ApprovalControlServiceImpl.java`

```java
// L854-869 confirmTicket
public String confirmTicket(String puid, long ticketId, DmConfirmTicketFO fo) {
    ...
    this.confirmTicketInNewTransaction(ticketId, fo, actionStatus);  // L859/L867
    ...
}

// L905-950 confirmTicketInTransaction
private void confirmTicketInTransaction(long ticketId, DmConfirmTicketFO fo, ApprovalStatus actionStatus) {
    DmApprovalDO rdpTicketDO = this.approvalDal.approvalMapper().selectByIdForUpdate(ticketId);  // L906 行锁
    checkJobOperationEnable(rdpTicketDO, fo.getConfirmUid());  // L910 ← 身份校验
    if (rdpTicketDO.getTicketStatus() != ApprovalStatus.WAIT_CONFIRM) {  // L912 状态检查
        throw ...;
    }
    ...
}

// L871-898 prepareExecJobAsync（异步执行准备，也有身份校验）
private void prepareExecJobAsync(long ticketId, DmConfirmTicketFO fo, String jobBizId, Locale locale) {
    DmApprovalDO rdpTicketDO = this.checkTicket(ticketId);
    checkJobOperationEnable(rdpTicketDO, fo.getConfirmUid());  // L874 ← 第二处身份校验
    if (rdpTicketDO.getTicketStatus() != ApprovalStatus.WAIT_EXEC) {  // L875 状态检查
        throw ...;
    }
    ...
}
```

**`checkJobOperationEnable` / `checkOperationEnableWithResult`**（L1184-1208）：

```java
private boolean checkOperationEnableWithResult(DmApprovalDO ticketDO, String uid) {
    DmAuthUserDO rdpUserDO = authDal.userMapper().queryByUid(uid);
    DmAuthRoleDO rdpRoleDO = authDal.roleMapper().selectById(rdpUserDO.getRoleId());
    if (rdpUserDO.getAccountType() == AccountType.PRIMARY_ACCOUNT) {  // L1193 主账号 → true
        return true;
    }
    if (rdpRoleDO.getRoleAuthLabels().contains(SecRoleAuthLabel.RDP_WORKER_ORDER_EXECUTE) 
        && ticketDO.getOwnerUid().equals(uid)) {  // L1196 有执行标签 + 是工单 owner → true
        return true;
    }
    List<RsAuthPersonObj> ... = this.authDal.userMapper()
        .queryApproPerson(AccountType.SUB_ACCOUNT, rdpUserDO.getParentId(), ticketDO.getBindDsId(), ticketDO.getTargetInfo()); // L1200-1201
    for (...) {
        if (rdpTicketApproPersonDO.getUid().equals(uid)) {  // L1203 在资源审批人列表 → true
            return true;
        }
    }
    return false;  // 否则 → false → 抛异常
}
```

**校验内容**：`fo.getConfirmUid()`（`@JsonIgnore`，由 Controller `fo.setConfirmUid(uid)` 注入，L122 of ApprovalController）必须满足以下之一：
1. PRIMARY_ACCOUNT（主账号）
2. 持有 `RDP_WORKER_ORDER_EXECUTE` 标签 **且** 是工单 `ownerUid`
3. 在该工单 bindDsId + targetInfo 对应的资源审批人列表中

状态必须 = `WAIT_CONFIRM`（confirmTicketInTransaction L912）或 `WAIT_EXEC`（prepareExecJobAsync L875）。

#### 2.3 非入口调用先例（定时任务/内部服务）

**先例 1：第三方回调直接调 handler（绕过 approvalTicket）**

文件：`backend/clouddm-platform/cgdm-console/src/main/java/com/clougence/clouddm/console/web/component/approval/impl/ApprovalProviderServiceImpl.java`

```java
// L337-342 refreshApprovalStatus（第三方审批状态同步，被 ApprovalTaskProcessor 调度调用）
case COMPLETED: {
    this.approvalStateService.updateProcessStatus(ticketId, ApprovalStage.APPROVAL, ApprovalProcessStatus.FINISH, null);
    this.approvalHandler(ticket.getApproBiz()).approvalApproved(ticket.getId(), ticket.getApproBiz(), imSenderService);
    // ↑ 直接调 handler.approvalApproved()，绕过 approvalFlowService.approvalTicket() 的 person 校验
    break;
}
```

这是 `ApprovalTaskProcessor.processWaitApproval`（L195-209）调 `refreshApprovalStatus` → 直接 `handler.approvalApproved()` 的非 HTTP 路径。**它完全绕过了 `approvalTicket` 的 dm_approval_person 校验**。

**先例 2：ApprovalTaskProcessor 直接调 handler.createApproval / executeTicket / runningCheck**

文件：`backend/.../schedule/ApprovalTaskProcessor.java`

```java
// L203 processWaitApproval
approvalHandler(approvalDO.getApproBiz()).createApproval(ticketDO.getId(), imSenderService);

// L290 processWaitExec
approvalHandler(approvalDO.getApproBiz()).executeTicket(approvalDO.getId(), approvalDO.getApproBiz(), imSenderService);

// L326 processRunningCheck
approvalHandler(approvalDO.getApproBiz()).runningCheck(approvalDO.getId(), approvalDO.getApproBiz(), imSenderService);
```

这些都是 1s 守护循环（`ApprovalTaskScheduler`）触发的非 HTTP 调用，直接操作 handler，不经 Controller。

**先例 3：CI/CD `ChangeActionForApproval.createApproval` 编程式创建工单**

文件：`backend/.../cicd/action/ChangeActionForApproval.java` L144-203：编程式 `new DmApprovalDO()` + insert + `approvalFlowService.createProcess()`，不经 Controller。

#### 2.4 puid / uid 参数语义

- `puid` = Primary User UID（主账号），`uid` = 操作者 UID（子账号或主账号）
- Controller 从 `request.getAttribute(RdpUserService.PUID/UID)` 获取
- `approvalTicket(puid, uid, fo)`：puid 未在方法内使用（仅 checkTicket 用 ticketId），uid 用于 person-list 校验
- `confirmTicket(puid, ticketId, fo)`：puid 未在方法内使用，fo.confirmUid 用于身份校验
- SYSTEM 代审需构造合成 puid/uid；uid 会同时被 person-list / checkOperationEnable 校验

### 结论

**SYSTEM 代审直接走现有漏斗不可行**：
- `approvalTicket`：SYSTEM 不在 `dm_approval_person` 表 → L136 抛异常
- `confirmTicket`：SYSTEM 不是 PRIMARY_ACCOUNT、不是 owner+标签、不在资源审批人列表 → L910 抛异常

**最小改动的"定向代审通道"应加在**：
1. **审批环节**：仿 `ApprovalProviderServiceImpl.refreshApprovalStatus` 先例，治理推进器直接调 `approvalHandler(DM_CHANGE).approvalApproved(ticketId, DM_CHANGE, imSenderService)` 完成 `WAIT_APPROVAL → WAIT_CONFIRM`，绕过 `approvalTicket` 的 person 校验（**零改动现有代码**，纯新增推进器逻辑）
2. **确认环节**：`confirmTicket` 的 `checkJobOperationEnable` 无法绕过。选项：(a) 新增治理专用确认方法（绕过 checkJobOperationEnable，直接调 confirmTicketInTransaction + prepareExecJobAsync），(b) 或让 SYSTEM 以 PRIMARY_ACCOUNT 身份调 confirmTicket（需确保 SYSTEM 用户是主账号类型），(c) 或在推进器内复用 confirmTicketInTransaction + prepareExecJobAsync 的内部逻辑（需提取为包级/protected 可见）

---

## ⑤ autoExecConfig 注入与锁定（影响 Phase 4/7）

### 核心代码证据

#### 5.1 `DmAutoExecConfigFO` 结构（前端传入的执行配置）

文件：`backend/.../web/model/fo/ticket/DmAutoExecConfigFO.java`

```java
// L26-35
public class DmAutoExecConfigFO {
    private AutoExecType autoExecType;       // 执行类型（IMMEDIATE/MANUAL_EXEC/...）
    private boolean        enableTransactional;  // 事务模式
    private ErrorStrategy  errorStrategy;         // NONE/RETRY/SKIP
    private Long           retryWaitTime;
    private Long           retryCount;
    private Long           execTime;             // 定时执行时间
    private boolean        snapshot;
}
```

#### 5.2 `DmConfirmTicketFO` — autoExecConfig 字段

文件：`backend/.../web/model/fo/ticket/DmConfirmTicketFO.java`

```java
// L32-46
public class DmConfirmTicketFO {
    private long                ticketId;
    private DmConfirmActionType confirmActionType;  // CONFIRM/REFUSE
    @JsonIgnore
    private String              confirmUid;          // L41 ← Controller 注入，非前端 JSON
    private String              comment;
    private DmAutoExecConfigFO  autoExecConfig;      // L45 ← 执行配置
}
```

`confirmUid` 是 `@JsonIgnore`（L41），由 `ApprovalController.confirmTicket`（L122 `fo.setConfirmUid(uid)`）注入。**无"只有确认人才能传 autoExecConfig"的耦合**——autoExecConfig 是普通 FO 字段，任何能调到 confirmTicket 的调用方都能传。

#### 5.3 消费链路：confirmTicket → prepareExecJobAsync → createExecJob → createJob

文件：`backend/.../service/approval/ApprovalControlServiceImpl.java`

```
confirmTicket(puid, ticketId, fo)                     // L854
  ├─ confirmTicketInNewTransaction → confirmTicketInTransaction  // L859/L867, 状态迁移 WAIT_CONFIRM→WAIT_EXEC
  └─ approvalTaskScheduler.submitControlTask → 
       prepareExecJobAsync(ticketId, fo, jobBizId, locale)       // L860, 异步
         ├─ checkJobOperationEnable(rdpTicketDO, fo.getConfirmUid())  // L874
         ├─ createExecJob(fo, rdpTicketDO, dmTicketDO, jobBizId, locale)  // L883
         │   └─ DmAutoExecConfigFO config = fo.getAutoExecConfig();      // L1006
         │   └─ AutoExecCreateMO.builder()
         │        .execType(config.getAutoExecType())
         │        .transactional(config.isEnableTransactional())  // L1012
         │        .errorStrategy(config.getErrorStrategy())       // L1013
         │        .retryWaitTime(config.getRetryWaitTime())        // L1014
         │        .retryCount(config.getRetryCount())              // L1015
         │        .execTime(config.getExecTime())
         │        .languageTag(locale.toLanguageTag())
         │        .build();                                       // L1007-1018
         │   └─ autoExecService.createJob(request, scripts)      // L1022
         └─ autoExecService.startJob(jobBizId, fo.getConfirmUid())  // L885
```

#### 5.4 `AutoExecServiceImpl.createJob` — 写入 dm_exec_auto_job.config

文件：`backend/.../component/execute/impl/AutoExecServiceImpl.java`

```java
// L129-219 createJob
public void createJob(AutoExecCreateMO request, Stream<SplitScript> scripts) {
    ...
    DmExecAutoJobDO job = new DmExecAutoJobDO();
    ...
    RsExecAutoJobConfigObj jobConfig = new RsExecAutoJobConfigObj();  // L151
    jobConfig.setEnableTransactional(request.isTransactional());      // L152
    jobConfig.setRetryWaitTime(request.getRetryWaitTime());           // L153
    jobConfig.setErrorStrategy(request.getErrorStrategy());           // L154
    jobConfig.setRetryCount(request.getRetryCount());                 // L155
    jobConfig.setLanguageTag(request.getLanguageTag());               // L156
    job.setConfig(jobConfig);                                         // L157
    ...
    this.execDal.autoJobMapper().insert(job);  // L164 ← 落库
}
```

`DmExecAutoJobDO.config` 字段（文件 `backend/.../dal/model/execution/DmExecAutoJobDO.java` L52-53）：

```java
@TableField(typeHandler = JacksonTypeHandler.class)
private RsExecAutoJobConfigObj config;
```

MyBatis `JacksonTypeHandler` 将 `RsExecAutoJobConfigObj` 序列化为 JSON 存入 `dm_exec_auto_job.config` 列，反序列化时用 `JsonUtils.defaultObjectMapper()`（全局 `FAIL_ON_UNKNOWN_PROPERTIES=false`）。

#### 5.5 `RsExecAutoJobConfigObj` 结构（持久化形态）

文件：`backend/.../dal/model/execution/RsExecAutoJobConfigObj.java`

```java
// L27-35
@JsonIgnoreProperties(ignoreUnknown = true)
public class RsExecAutoJobConfigObj {
    private boolean       enableTransactional;
    private ErrorStrategy errorStrategy;
    private Long          retryWaitTime;
    private Long          retryCount;
    private String        languageTag;
}
```

**注意**：`DmAutoExecConfigFO` 有 `snapshot` 字段但 `RsExecAutoJobConfigObj` 没有 → snapshot 不落库到 job config。

### 系统代审注入可行性

- **无"确认人才能传"耦合**：`autoExecConfig` 是普通 FO 字段，confirmUid 仅用于身份校验，二者解耦
- **系统代审可构造 autoExecConfig 传入 confirmTicket**：需同时解决 confirmUid 身份校验（见 ② 结论）
- **成分路由注入点**：治理推进器在调 confirmTicket 前按 D15 构造 `DmAutoExecConfigFO`（纯 DML→enableTransactional=true；含 DDL→enableTransactional=false+errorStrategy=NONE）

### PROD 人工确认场景：合规校验挂点

**现状**：`confirmTicket` → `prepareExecJobAsync` → `createExecJob` → `createJob` **原样透传**前端 config，**零合规校验**。前端传来的 `enableTransactional`/`errorStrategy` 直接落库到 `dm_exec_auto_job.config`。

**最佳挂点**：`prepareExecJobAsync` 方法开头（L872 `try {` 之后、`createExecJob` 调用之前）。这正好对应设计 §2.2 触点 #2（"`ApprovalControlServiceImpl.prepareExecJobAsync` 开头调用治理门禁"）。此处可：
1. 读取工单治理引用（ticketInfo 中的 promotionId/revisionId）
2. 读取治理层按 change_type 成分路由注入的预期 config 值
3. 比对 `fo.getAutoExecConfig()` 是否一致 → 不一致 DENY（回退 `restoreExecutionConfirmation`）

**confirmTicket 不会原样落库前端配置吗？** 会。当前代码 `createExecJob`（L1006）直接 `fo.getAutoExecConfig()` 取值建 job，无任何中间校验/覆盖。门禁二配置合规校验必须新增。

---

## ⑦ approBiz 参数化影响面（影响 Phase 4 触点 #1）

### 核心代码证据

#### 7.1 `createSqlTicketInTransaction` 中 DM_QUERY 写死位置

文件：`backend/.../service/approval/ApprovalControlServiceImpl.java`

```java
// L729-838 createSqlTicketInTransaction
private DmTicketResultVO createSqlTicketInTransaction(String puid, String uid, DmAddTicketFO fo) {
    ...
    ticket.setApproBiz(ApprovalBiz.DM_QUERY);  // L809 ← 写死位置 1
    ...
    this.approvalFlowService.createProcess(ticket.getId(), ApprovalBiz.DM_QUERY, mo.getMessage() == null);  // L834 ← 写死位置 2
    ...
}
```

`DmAddTicketFO`（文件 `backend/.../web/model/fo/ticket/DmAddTicketFO.java` L33-46）**不含 approBiz 字段**。参数化 = 给 FO 加字段 **或** 给 `createSqlTicket`/`createSqlTicketInTransaction` 增 `ApprovalBiz` 参数（方法签名变更）。

#### 7.2 下游对 bizType 的全部分支依赖

| # | 文件:行 | 代码 | DM_CHANGE 是否已支持 |
|---|---|---|---|
| 1 | `AbstractPreInitHandler.java:27-30` | `supports()`: `approBiz == DM_QUERY \|\| approBiz == DM_CHANGE` | ✅ 已支持 |
| 2 | `ApprovalStage.java:25-34` | `checkBiz()`: EXPLAIN/APPROVAL/CONFIRM/EXECUTION 均含 DM_CHANGE | ✅ 已支持 |
| 3 | `ApprovalFlowServiceImpl.java:70-80` | `Map<ApprovalBiz, ApprovalHandler>` 按 `handleType()` 路由 | ✅ ChangeApprovalHandler.handleType()=DM_CHANGE |
| 4 | `ApprovalTaskProcessor.java:101-111` | 同上，独立 `Map<ApprovalBiz, ApprovalHandler>` | ✅ 同 |
| 5 | `ApprovalStateServiceImpl.java:73` | `.filter(candidate -> candidate.handleType() == approval.getApproBiz())` | ✅ 路由到 ChangeApprovalHandler |
| 6 | `ApprovalControlServiceImpl.java:269` | `convertAndFillExtraInfo`: `if (approBiz == DM_QUERY \|\| approBiz == DM_CHANGE)` 同分支展示 | ✅ |
| 7 | `ApprovalControlServiceImpl.java:509-518` | `queryTicketDetail`: `switch(approBiz) { case DM_QUERY: case DM_CHANGE: break; default: return null; }` | ✅ |
| 8 | `ApprovalControlServiceImpl.java:349` | `vo.setApproBiz(approvalDO.getApproBiz())` 透传前端 | ✅ 无分支 |
| 9 | `ApprovalTaskScheduler.java:281` | `approBiz != DATA_SOURCE_AUTH` → 仅排除授权工单 | ✅ DM_CHANGE 通过 |
| 10 | 前端（`.vue`/`.js`/`.ts`） | grep `approBiz\|bizType\|DM_QUERY\|DM_CHANGE` | ✅ **零前端分支依赖** |
| 11 | `ChangeForm.java` / `QueryForm.java` | 两个 Form 类，无 bizType 路由逻辑，纯数据载体 | ✅ 无影响 |

#### 7.3 ChangeApprovalHandler 行为分析（DM_CHANGE handler）

文件：`backend/.../component/approval/handler/ChangeApprovalHandler.java`

| 方法 | 行 | 行为 | 治理 DM_CHANGE 工单可用性 |
|---|---|---|---|
| `handleType()` | L80-82 | return `DM_CHANGE` | — |
| `approvalApproved()` | L198-200 | `updateApprovalStatus(WAIT_CONFIRM)` | ✅ 与 QueryApprovalHandler 完全一致 |
| `executeTicket()` | L86-94 | 查 job → `updateExecutionStatus` | ✅ 通用 |
| `runningCheck()` | L98-102 | 同上 | ✅ 通用 |
| `queryPerson()` | L126-156 | 主账号 + 有审批标签+资源权限的子账号 | ✅ 通用 |
| `createApproval()` | L160-188 | 外部审批时调 `convertToChangeForm` | ⚠️ **见下** |
| `approvalCompleted()` | L191-195 | `updateChange` → 读 changeId | ✅ no-op（changeId=null 时 L226 return） |
| `approvalRejected()` | L203-207 | `updateChange` | ✅ no-op |
| `approvalFailed()` | L210-214 | `updateChange` | ✅ no-op |
| `approvalCanceled()` | L217-221 | `updateChange` | ✅ no-op |

**⚠️ `createApproval()` 的 CI/CD 耦合（L160-188, L275-297）**：

```java
// L160-164
public void createApproval(long approvalId, ImSenderService sender) {
    DmApprovalDO ticketDO = approvalDal.approvalMapper().selectByIdForUpdate(approvalId);
    if (ticketDO.getApproType() == ApprovalType.Internal) {
        return; // ← Internal 类型直接返回，不创建外部审批实例
    }
    ChangeForm form = convertToChangeForm(ticketDO, ticketDO.getApproTemplateIdentity());  // L166
    ...
}

// L275-297 convertToChangeForm
private ChangeForm convertToChangeForm(DmApprovalDO ticketDO, String templateId) {
    ApprovalMO info = JsonUtils.toObj(ticketDO.getTicketInfo(), ApprovalMO.class);
    if (info == null || info.getChangeOwnerUid() == null || info.getChangeId() == null) {  // L277-279
        throw new IllegalArgumentException("ticket info is null");  // ← 治理工单无 changeId → 抛异常
    }
    DmChangeDO changeDO = this.changeFlowDal.changeMapper().queryChangeById(info.getChangeId());  // L281 ← 查 CI/CD change 表
    DmChangeFlowDO flowDO = ...;  // L282 ← 查 CI/CD flow 表
    ChangeForm form = new ChangeForm();
    form.setFlowName(flowDO.getFlowName());      // L293 ← CI/CD 专属字段
    form.setChangeName(changeDO.getChangeName()); // L294 ← CI/CD 专属字段
    form.setBranch(changeDO.getChangeBranch());   // L295 ← CI/CD 专属字段
    ...
}
```

- **PRE 路径（Internal）**：`createApproval` L162-163 直接 return → **无问题**
- **PROD 路径（第三方/钉钉）**：`convertToChangeForm` 要求 `ApprovalMO.changeId` 和 `changeOwnerUid` 非空（L277-279），治理工单的 ticketInfo 不含这两个字段 → **抛 IllegalArgumentException**
- `ChangeForm` 本身是 CI/CD 专属（flowName/changeName/branch），不含治理需要的字段（风险等级/影响行数/sql_hash 等）

### 结论

**参数化 + 默认值 DM_QUERY = 零行为变化**：现有 DM_QUERY 工单不受影响。

**DM_CHANGE 工单走 createSqlTicket 创建后的链路支持情况**：
- PreInit（行为分析→规则审计→DML Explain）：✅ 天然支持（`AbstractPreInitHandler.supports` 已含 DM_CHANGE）
- 审批→确认→执行状态机：✅ 天然支持（`ApprovalStage.checkBiz` 全阶段含 DM_CHANGE）
- PRE/Internal 审批：✅ `createApproval` 对 Internal 直接 return
- PROD/第三方审批：⚠️ `ChangeApprovalHandler.convertToChangeForm` 强依赖 CI/CD 的 `changeId`/`changeOwnerUid`，治理 PROD DM_CHANGE 工单会抛异常。**缺失 handler**：不是 handler 缺失，而是 handler 的外部审批表单构造方法有 CI/CD 耦合。治理层需自行处理表单（或扩展 ChangeForm/convertToChangeForm，或治理工单用不同 form 路径）

---

## ⑨ ApprovalMO 扩展兼容性（影响 Phase 4 触点 #1）

### 核心代码证据

#### 9.1 ApprovalMO 现有字段全清单

文件：`backend/.../component/approval/model/ApprovalMO.java`

```java
// L26-32
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)   // L25 ← 类级注解，忽略未知属性
public class ApprovalMO {
    private String  message;         // 工单消息（创建时设置，queryTicketDetail 读取）
    private boolean autoExec;        // 是否自动执行（updateAutoExecFlag/restoreExecutionConfirmation 读写）
    private String  changeOwnerUid;  // CI/CD 变更归属主账号（ChangeActionForApproval 写入）
    private Long    changeId;        // CI/CD 变更 ID（ChangeActionForApproval 写入）
}
```

#### 9.2 ticket_info 列存储机制

`DmApprovalDO.ticketInfo`（文件 `backend/.../dal/model/approval/DmApprovalDO.java` L76）：

```java
private String ticketInfo;   // ← 纯 String，无 TypeHandler，原始 JSON 文本存入 dm_approval.ticket_info 列
```

无 MyBatis TypeHandler → 存储为原始字符串，序列化/反序列化全在应用层用 `JsonUtils` 完成。

#### 9.3 序列化点（writeValueAsString / toJson）

| # | 文件:行 | 代码 | 写入哪些字段 |
|---|---|---|---|
| W1 | `ApprovalControlServiceImpl.java:822` | `ticket.setTicketInfo(JsonUtils.toJson(mo))` | `new ApprovalMO()` → 仅 `message`（创建时） |
| W2 | `ApprovalControlServiceImpl.java:965` | `updateTicketInfo(dmTicketDO.getId(), JsonUtils.toJson(info))` | 读改写：读现有 → 改 `autoExec` → 写回 |
| W3 | `ApprovalControlServiceImpl.java:980` | `updateTicketInfo(dmTicketDO.getId(), JsonUtils.toJson(info))` | 读改写：读现有 → 改 `autoExec=false` → 写回 |
| W4 | `ChangeActionForApproval.java:196` | `ticket.setTicketInfo(JsonUtils.toJson(ticketInfo))` | CI/CD：`changeOwnerUid` + `changeId` |

#### 9.4 反序列化点（readValue / toObj）

| # | 文件:行 | 代码 | 读取哪些字段 |
|---|---|---|---|
| R1 | `ApprovalControlServiceImpl.java:542` | `JsonUtils.toObj(approvalDO.getTicketInfo(), ApprovalMO.class)` | `message`, `autoExec` |
| R2 | `ApprovalControlServiceImpl.java:963` | `StringUtils.isEmpty(...) ? new ApprovalMO() : JsonUtils.toObj(...)` | `autoExec`（读改写） |
| R3 | `ApprovalControlServiceImpl.java:978` | 同上 | `autoExec`（读改写） |
| R4 | `ChangeApprovalHandler.java:225` | `JsonUtils.toObj(ticketDO.getTicketInfo(), ApprovalMO.class)` | `changeOwnerUid`, `changeId` |
| R5 | `ChangeApprovalHandler.java:276` | `JsonUtils.toObj(ticketDO.getTicketInfo(), ApprovalMO.class)` | `changeOwnerUid`, `changeId` |

#### 9.5 Jackson 配置

文件：`backend/clouddm-utils/cg-utils/src/main/java/com/clougence/utils/JsonUtils.java`

```java
// L41-47
static {
    objectMapper = new ObjectMapper();
    objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);  // L44 ← 全局关闭
    objectMapper.registerModule(new JavaTimeModule());
}

// L59-64 toObj 空字符串处理
public static <T> T toObj(String jsonStr, Class<T> clz) {
    if (StringUtils.isBlank(jsonStr)) {
        return null;   // ← 空/blank → 返回 null（不抛异常）
    }
    return objectMapper.readValue(jsonStr, clz);
}
```

**双重保险**：
1. 全局 `FAIL_ON_UNKNOWN_PROPERTIES = false`（L44）
2. `ApprovalMO` 类级 `@JsonIgnoreProperties(ignoreUnknown = true)`（L25）

#### 9.6 CI/CD 模块对 ApprovalMO 的现有用法

文件：`backend/.../cicd/action/ChangeActionForApproval.java`

```java
// L193-196 createApproval
ApprovalMO ticketInfo = new ApprovalMO();
ticketInfo.setChangeOwnerUid(change.getOwnerUid());
ticketInfo.setChangeId(change.getId());
ticket.setTicketInfo(JsonUtils.toJson(ticketInfo));
```

文件：`backend/.../handler/ChangeApprovalHandler.java`

```java
// L225-228 updateChange（审批完成/拒绝/失败/取消回调）
ApprovalMO info = JsonUtils.toObj(ticketDO.getTicketInfo(), ApprovalMO.class);
if (info == null || info.getChangeOwnerUid() == null || info.getChangeId() == null) {
    return;   // ← 治理工单（无 changeId）此处直接 return，无异常
}

// L276-279 convertToChangeForm（外部审批创建）
ApprovalMO info = JsonUtils.toObj(ticketDO.getTicketInfo(), ApprovalMO.class);
if (info == null || info.getChangeOwnerUid() == null || info.getChangeId() == null) {
    throw new IllegalArgumentException("ticket info is null");  // ← 此处会抛异常（见 ⑦ 分析）
}
```

### 结论

**直接加字段安全**：
- 新增 `promotionId`/`revisionId` 到 `ApprovalMO` 不会破坏任何现有读写路径
- 双重 `ignoreUnknown` 保险：旧数据（无新字段）反序列化正常；新数据（有新字段）被旧代码读取时忽略新字段
- 现有读路径只取各自关心的字段（R1 取 message/autoExec，R4/R5 取 changeOwnerUid/changeId），新增字段不影响

**注意事项**：
1. **读-改-写路径（W2/W3 = updateAutoExecFlag/restoreExecutionConfirmation）**：这两个方法读现有 ticketInfo → 修改 autoExec → 写回。如果 `promotionId`/`revisionId` **还没加到 ApprovalMO POJO** 时治理层就写入了它们，读改写会用**不含新字段的 POJO**反序列化（新字段被 ignore）→ 再序列化时**新字段被静默丢弃**。**必须先改 POJO 加字段，再让治理层写入**。
2. **空/非法 ticketInfo**：`JsonUtils.toObj` 对 blank 返回 null（L60-62）。现有代码已处理 null（R2/R3 用三元 `new ApprovalMO()`，R4 检查 `info == null`）。无问题。
3. **@JsonIgnoreProperties 已存在**（L25），无需新增。全局配置也已关闭 FAIL_ON_UNKNOWN_PROPERTIES。
4. CI/CD 路径（ChangeActionForApproval 写 changeOwnerUid/changeId）与治理路径（写 promotionId/revisionId）互不干扰——CI/CD 工单不含治理字段，治理工单不含 CI/CD 字段，各自读取各自关心的字段。

---

## Caveats / Not Found

- **`ApprovalPersonService.replacePersons`**：未深入阅读该方法（`ApprovalTaskProcessor.updatePerson` L338 调用），它负责在 `processWaitConfirm` 时刷新 `dm_approval_person` 表。治理推进器若直接调 handler.approvalApproved 绕过 approvalTicket，需确认 `dm_approval_person` 是否已被 ApprovalTaskProcessor 正确填充（对 Internal 类型，`createProcess` L190-193 会调 `replacePersons` 填充审批人；但对 SYSTEM 代审路径，审批人列表可能不含 SYSTEM——但这不影响，因为绕过 approvalTicket 后不再查 person 表）。
- **`DmlExplainPreInitHandler` 对 PostgreSQL 的支持**（验证项 ③，不在本次范围）：已确认该 handler 通过 `findExplainSpi(DataSourceType)` 动态查找方言插件 SPI（L125-129），PG 支持取决于 `ds-postgres` 插件是否实现了 `ExplainPlanSpi`，本次未深入插件层。
- **`ErrorStrategy` 枚举值**：未读取该枚举完整定义，但从 `AutoExecServiceImpl.createJob` L133-140 可推断含 RETRY；从设计文档 D15 可知含 NONE/SKIP。
- **前端 `ticketDetail.vue` 对 autoExecConfig 的展示逻辑**：未深入前端代码，但后端 `queryAutoExecJob`（AutoExecServiceImpl L553-608）返回 `enableTransactional` 供前端展示。
