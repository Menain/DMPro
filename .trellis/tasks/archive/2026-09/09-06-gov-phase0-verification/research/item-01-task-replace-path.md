# Research: 语句替换的引擎路径（验证项①）

- **Query**: 确认 `dm_exec_auto_task` 是否持有语句文本、`dispatchJob → create(jobId)` 打包 zip 的文本读取源；据此判断"replaceTask 新增方法" vs "治理层直写 task 行"两条路径的可行性
- **Scope**: internal (backend Java + MyBatis + Flyway)
- **Date**: 2026-09-06

---

## 结论速览

1. **`dm_exec_auto_task.exec_sql` 列持有拆分后的单句 SQL 文本**（longtext, NOT NULL）。task 行就是语句的权威存储，不是 rawSql 引用。
2. **打包 zip 时文本读取源 = task 行的 `exec_sql` 列**（`AutoExecServiceImpl.create()` 第 374 行 `task.getExecSql()`），**不从 rawSql 重新拆分**。因此"改 task 行 exec_sql"对下一次打包生效——这是两条路径都可行的根本前提。
3. **retryJob → 调度器 scanPendingJob → dispatchJob → create() 重打包**：只重打包 status IN (WAIT_EXEC, FAILED, ROLLBACK) 的 task；FINISH / CANCELED 被排除。断点续跑成立。
4. **exec_order 无连续性要求**：打包按 exec_order 排序分页（`exec_order > afterExecOrder`），只看"待执行"集合内的相对顺序；FINISH/CANCELED 行的 exec_order 被自然跳过。新 task 复用原失败 task 的 exec_order 不会产生 zip 条目冲突（CANCELED 不入包）。
5. **两条路径均可行，推荐路径 (a) `replaceTask`**：失败 task→CANCELED + 新版本 task 入列（复用原 exec_order、新 query_id/biz_id、status=WAIT_EXEC）。理由：task 表无 version 列，(a) 天然保留原失败行作为版本/审计证据，符合设计的"语句版本+1"；(b) 直写 exec_sql 会原地覆盖原文本，版本链只能靠独立审计表补。(a) 落点：`AutoExecService.replaceTask(String jobBizId, long failedTaskId, String newExecSql)`，实现于 `AutoExecServiceImpl`，事务内 updateStatusByTaskId(CANCELED) + batchInsert 单条新行。

---

## 逐项证据

### 1. dm_exec_auto_task 是否持有语句文本？哪一列？

**是。列名 `exec_sql`，类型 longtext NOT NULL。**

- DO：`backend/clouddm-platform/cgdm-dao/src/main/java/com/clougence/clouddm/platform/dal/model/execution/DmExecAutoTaskDO.java:41`
  ```java
  private String execSql;
  ```
  类注解 `@TableName(value = "dm_exec_auto_task")`（第 28 行）。
- Mapper XML 列映射：`DmExecAutoTaskMapper.xml:11` `<result column="exec_sql" property="execSql"/>`；`sqlTask` 片段（第 21-35 行）含 `exec_sql`。
- 建表脚本：`V202605070037__sql_auto_exec.java:63` `exec_sql text not null`；`V202607290003__consolidated_upgrade.java:61-62` `alter table dm_exec_auto_task modify column exec_sql longtext not null`（升级为 longtext）。
- 其他关键列：`exec_order int not null`、`status varchar(32) not null`、`biz_id varchar(128) not null`、`query_id varchar(128) not null`（query_id 由 consolidated upgrade 新增，第 64-65 行 + 第 120-121 行唯一索引 `uk_exec_auto_task_query_id`）。

### 2. console 侧把工单 rawSql 逐句拆分并写入 task 的代码路径

**调用链：`ApprovalControlServiceImpl.prepareExecJobAsync` → `queryAnalysisService.analysisSplitStream` → `autoExecService.createJob(request, scripts)`。task 行存的是拆分后的单句文本。**

- `ApprovalControlServiceImpl.java:1019-1022`（`backend/.../console/web/service/approval/`）：
  ```java
  this.approvalService.consumeSqlFile(dmTicket.getId(), sqlFile -> {
      try (Reader reader = Files.newBufferedReader(sqlFile, StandardCharsets.UTF_8);
              Stream<SplitScript> scripts = this.queryAnalysisService.analysisSplitStream(dsConfig, reader, null, 1, 0)) {
          this.autoExecService.createJob(request, scripts);
  ```
  `analysisSplitStream`（`QueryAnalysisService.java:33`）返回 `Stream<SplitScript>`，每个 `SplitScript.getScript()` 是一条拆分后的语句。
- `AutoExecServiceImpl.createJob`（第 127-219 行，`@Transactional`）：遍历 `scripts` 迭代器，对每个 `SplitScript`：
  ```java
  execTask.setExecSql(script.getScript());   // 第 183 行 —— 单句文本写入 task 行
  execTask.setExecOrder(order++);            // 第 184 行 —— exec_order 从 1 递增
  execTask.setStatus(AutoExecTaskStatus.WAIT_EXEC);
  execTask.setAutoExecJobId(job.getId());
  execTask.setBizId(DmTeamUtils.nextExecTaskBizId());
  execTask.setQueryId(UUID.randomUUID().toString());
  ```
  随后 `batchInsert`（第 175/192/205 行）。**确认：task 行存的是拆分后的单句文本，不是整单 rawSql。**

### 3. dispatchJob claim 后打包 zip 时，发给 sidecar 的语句文本读取源

**读取源 = task 行的 `exec_sql` 列，不从 rawSql 重新拆分。**

- `AutoExecServiceImpl.dispatchJob`（第 256-305 行）：
  - 第 257 行 `claimJobForPackaging(jobId)`：`dm_exec_auto_job.status` INIT→PACKAGING（`DmExecAutoJobMapper.xml:148-158`，WHERE status='INIT'）。
  - 第 262 行 `taskPackage = this.create(jobId)` —— 进入打包。
  - 第 272-293 行 事务内构建 `AutoExecJobDTO`（`prepareJobData`，第 480-506 行，含 `enableTransactional`、`contextDTO`、`taskPackage`），第 301 行 `execRService.dispatchJob(sendDTO, autoExecJob)` 发给 sidecar。
- `AutoExecServiceImpl.create`（第 308-430 行）—— 打包核心：
  - 第 341 行 `queryNeedExecTaskMaxOrder(jobId)`：取待执行 task 的最大 exec_order。
  - 第 342 行 `queryNeedExecTaskCount(jobId)`：待执行 task 计数。
  - 第 354 行 `queryNeedExecTaskIdsBatch(jobId, afterExecOrder, 100)`：分页取 id，**WHERE status IN ('WAIT_EXEC','FAILED','ROLLBACK') AND exec_order > #{afterExecOrder} ORDER BY exec_order**（`DmExecAutoTaskMapper.xml:139-152`）。
  - 第 359 行 `queryNeedExecTasksByIds(jobId, taskIds)`：取 DO，同样 status 过滤 + `ORDER BY exec_order`。
  - **第 374 行 `task.getExecSql()`** —— 文本读取源，确认是 task 行的 exec_sql。
  - 第 374-401 行：用 `StringReader` 包 `execSql`，经 `analysisService.analysisRequestsStream` 再分析（REWRITE 已 skip，第 325 行），生成 `QueryRequest`（含 `queryId`=task.queryId、`queryBody` 等），写 JSON 入 zip。
  - 第 364 行 zip 条目名 `String.format("%0"+fileNameWidth+"d", tasks.get(0).getExecOrder())` —— 用本批首 task 的 exec_order 命名。
  - 第 410 行 `afterExecOrder = tasks.get(任务.last).getExecOrder()` 推进分页。

**结论：改 task 行 exec_sql 即对下一次打包生效。**

### 4. sidecar 侧执行单元结构、执行顺序、状态回报

**文件：`backend/clouddm-platform/cgdm-sidecar/src/main/java/com/clougence/clouddm/worker/component/autoexec/AutoExecJob.java`（`@Scope("prototype")`，implements Runnable）**

- `run()`（第 89-174 行）：
  1. 第 102-110 行 拉 zip 包：`prepareTaskPackage` → `execJobRService.readPackage(identity(), jobId, attachmentId, offset, length)` 分块下载 + MD5 校验。
  2. 第 128-138 行 创建 session（`sessionManager.createSession`）。
  3. 第 143 行 `jobWrap(taskPackageFile)`。
- `jobWrap`（第 252-298 行）：
  - 事务模式（`job.isEnableTransactional()`）：`sessionAgent.setAutoCommit(false)` → 跑 `jobRun` → 全成功才 `commit` + 发 `TRANSACTION_FINISH`；任一失败 `rollback` + 发 `TRANSACTION_ROLLBACK`。
  - 非事务模式：逐条 autocommit。
- `jobRun`（第 300-321 行）：`ZipInputStream` 遍历 zip 条目（条目顺序 = console 打包的 exec_order 顺序），每个条目内 `MappingIterator<QueryRequest>` 逐句调 `jobRunItem`。任一 `jobRunItem` 返回 false 即 `return false`（失败即停）。
- `jobRunItem`（第 323-392 行）：单句执行 + 回报：
  - 第 333 行 `sendMessage(taskStartMessage(queryId))` → **TASK_START**。
  - 成功：事务模式发 `taskWaitConfirmMessage`（**TASK_WAIT_CONFIRM**，第 366 行）；非事务发 `taskFinishMessage`（**TASK_FINISH**，第 368 行）。
  - 失败：`ErrorStrategy.RETRY` 时发 `taskRetryMessage` + 重试；`SKIP` 时发 `taskSkipMessage` + 返回 true（跳过）；否则发 `taskFailMessage`（**TASK_FAILED**，第 388 行）+ throw。
- 回报路径：`sendMessage`（第 426-441 行）→ `execJobRService.reportMessage(identity(), messageList)`（RSocket 到 console）。
- console 侧接收：`ExecJobRServiceProvider.reportMessage`（`backend/.../console/web/provider/ExecJobRServiceProvider.java:99-164`）按 type 分发：
  - TASK_START → `taskStart`（第 298 行）：`queryByQueryId` + `updateById` 设 EXECUTING。
  - TASK_FINISH → `taskFinish`（第 268 行）：设 FINISH。
  - TASK_WAIT_CONFIRM → `taskWaitConfirm`（第 256 行）：设 WAIT_CONFIRM。
  - TASK_FAILED → `taskFailed`（第 280 行）：设 FAILED + 插 biz_log。
  - TASK_SKIP → `taskSkip`（第 166 行）：`taskSkip(queryId)` SQL 设 CANCELED。
  - TRANSACTION_FINISH → `transactionFinish`（第 200 行）：`transactionCommit(jobId)` 把 WAIT_CONFIRM→FINISH。
  - TRANSACTION_ROLLBACK → `transactionRollback`（第 192 行）：把 EXECUTING/WAIT_CONFIRM→ROLLBACK。
  - JOB_FINISH / JOB_FAILED / JOB_PAUSE / 等见同文件。

### 5. retryJob 重打包逻辑：哪些 task 状态重打包？exec_order 语义？

**`AutoExecServiceImpl.retryJob`（第 636-648 行）：**
```java
public void retryJob(String bizId) {
    DmExecAutoJobDO job = requireJob(bizId);
    if (job.getStatus() != AutoExecJobStatus.FAILED && job.getStatus() != AutoExecJobStatus.PAUSE) { throw ...; }
    job.setStatus(AutoExecJobStatus.INIT);
    int updateCount = execDal.autoJobMapper().retryJob(job.getId());  // dm_exec_auto_job: status='INIT', normal=1
    if (updateCount <= 0) return;
    execDal.autoTaskMapper().retryTask(job.getId());                  // dm_exec_auto_task: status→WAIT_EXEC
}
```
- `retryTask` SQL（`DmExecAutoTaskMapper.xml:108-117`）：`SET status='WAIT_EXEC' WHERE auto_exec_job_id=#{jobId} AND status IN ('FAILED','ROLLBACK','WAIT_CONFIRM','EXECUTING')`。
- **重打包集合**：`create()` 的 `queryNeedExec*` 查询 WHERE status IN ('WAIT_EXEC','FAILED','ROLLBACK')。retryTask 已把 FAILED/ROLLBACK/WAIT_CONFIRM/EXECUTING → WAIT_EXEC，所以它们都入包。**FINISH 与 CANCELED 不入包、不重放**——确认。
- **重试 → 调度 → 打包链**：retryJob 设 job=INIT 后，`AutoExecScheduleService.scanPendingJob`（`backend/.../console/web/component/execute/impl/AutoExecScheduleService.java:88-102`，每 5 秒）取 status=INIT 且过 schedule_time 的 job → `submitTask` → `dispatchJob` → `create()` 重打包。另有 `updateOverOutJob`（每 1 分钟）把超时 PACKAGING/WAIT_EXEC → INIT 兜底（第 70、79-85 行）。

**治理层"失败 task→CANCELED + 新增 WAIT_EXEC task"兼容性：**
- CANCELED 行：retryTask 不动（retryTask 的 IN 列表不含 CANCELED）；`queryNeedExec*` 不含 CANCELED → **不重打包、不重放**。正确。
- 新 WAIT_EXEC 行：retryTask 不动（本就 WAIT_EXEC 或新插入即 WAIT_EXEC）；`queryNeedExec*` 含 WAIT_EXEC → **重打包**。正确。

**exec_order 语义：**
- 打包排序与分页依据 = `exec_order`（`ORDER BY exec_order`，分页 `exec_order > afterExecOrder`）。**仅"待执行"集合内的相对顺序有意义，跨全表无连续性要求**：FINISH/CANCELED 行的 exec_order 被自然跳过。
- zip 条目名 = 本批首 task 的 exec_order（第 364 行）。若 CANCELED 行与 WAIT_EXEC 行 exec_order 相同（复用原位置），CANCELED 不入包 → 无 zip 条目名冲突。
- **若治理层新增 task 复用原失败 task 的 exec_order**：该新 task 在原位置重放，后续 WAIT_EXEC task 顺序不变 → 断点续跑顺序正确。
- **若新增 task 用 max(exec_order)+1**：会排到末尾，改变执行顺序（对 DDL 链有语义风险，不推荐）。
- DB 层无 (auto_exec_job_id, exec_order) 唯一约束（`V202607290003:58-59` 是普通 index `idx_auto_exec_task_job_order`），复用 exec_order 不违反约束。

### 6. task 行现成的更新路径（update 方法/Mapper）

**Mapper XML 显式 update（`DmExecAutoTaskMapper.xml`）：** 均为状态/影响行更新，无 exec_sql 更新：
| 方法 | 行 | 改动列 |
|---|---|---|
| `updateStatusByTaskId` | 67-74 | status |
| `updateStatusAndAffectLineByTaskId` | 98-106 | status, affect_row |
| `transactionCommit` | 76-85 | status→FINISH (WHERE WAIT_CONFIRM) |
| `transactionRollback` | 87-96 | status→ROLLBACK (WHERE WAIT_CONFIRM/EXECUTING) |
| `retryTask` | 108-117 | status→WAIT_EXEC |
| `taskSkip` | 119-126 | status→CANCELED (WHERE query_id) |
| `cancelAllWaitTask` | 128-137 | status→CANCELED (WHERE WAIT_EXEC/EXECUTING) |

**MyBatis-Plus `BaseMapper<DmExecAutoTaskDO>` 继承的 `updateById`**（`DmExecAutoTaskMapper.java:28` extends BaseMapper）：全列更新（按 id）。`ExecJobRServiceProvider` 在 `taskStart/taskFinish/taskWaitConfirm/taskFailed` 中 load DO → 改 status/affect_row/exec_count/gmt → `updateById`（第 265、277、289、306 行）。**理论上可改 exec_sql，但现有代码从不改。**

**除状态回报外的更新场景**：仅 `continueTask`（第 509-524 行，CANCELED→WAIT_EXEC）、`skipTask`（第 527-550 行，→CANCELED）、`endJob`→`cancelAllWaitTask`（第 630 行）。无任何地方更新 exec_sql。

**结论：无现成的 exec_sql 专用 update 方法；若走直写路径需新增 mapper 方法或用 `updateById`。**

### 7. 事务模式（enableTransactional=true）下 FAILED/ROLLBACK task 的重放语义

- sidecar `jobWrap`（`AutoExecJob.java:252-298`）：事务模式任一句失败 → `sessionAgent.rollback()` + 发 `TRANSACTION_ROLLBACK`。库状态零变化（整单回滚）。
- console `transactionRollback`（`ExecJobRServiceProvider.java:192-198`）：`dm_exec_auto_task SET status='ROLLBACK' WHERE status IN ('WAIT_CONFIRM','EXECUTING')`——所有未提交的 task → ROLLBACK。已 FINISH 的不受影响（但事务模式下 FINISH 只在 commit 后由 `transactionCommit` 批量设置，失败时不会有 FINISH 行）。
- retryJob → retryTask：ROLLBACK → WAIT_EXEC。**全部 task 重放**（含替换文本后的 task）。
- **语义正确性**：事务模式失败 = 整单回滚 = 库零变化 → 全部重跑等价于首次执行；替换文本后重放语义正确（设计 §4.6 "纯 DML 工单：全部回滚 → 修正后重提 = 全部重跑，语义正确"）。
- 非事务模式（DDL/混合）：失败即停，失败点前 FINISH（已应用，DDL 物理不可回滚），失败点 FAILED，后续 WAIT_EXEC。retryJob 只重打包 FAILED + WAIT_EXEC（FINISH 不重放）→ 断点续跑。替换失败 task 文本后，该 task 在原位置重放新文本，后续继续。语义正确。

### 8. 最终结论：两条路径的可行性与推荐

#### 路径 (a)：AutoExecService 新增治理专用 `replaceTask`（失败 task→CANCELED + 新版本 task 入列）

**可行。** 落点与签名建议：
```java
// AutoExecService.java（接口新增）
void replaceTask(String jobBizId, long failedTaskId, String newExecSql);

// AutoExecServiceImpl.java（实现，@Transactional）
// 1. requireJob(bizId) 校验 job 存在且 status=FAILED 或 PAUSE
// 2. execDal.autoTaskMapper().selectById(failedTaskId) 取原失败 task，校验 autoExec_job_id 匹配 + status=FAILED
// 3. updateStatusByTaskId(failedTaskId, CANCELED)   // 原行 CANCELED，保留旧文本作为版本证据
// 4. 构造新 DmExecAutoTaskDO:
//    execSql = newExecSql
//    execOrder = failedTask.execOrder   // 复用原位置，保证断点续跑顺序
//    status = WAIT_EXEC
//    autoExecJobId = job.id
//    bizId = DmTeamUtils.nextExecTaskBizId()   // 新 biz_id（uk_biz 唯一）
//    queryId = UUID.randomUUID().toString()     // 新 query_id（uk_exec_auto_task_query_id 唯一）
//    batchInsert(List.of(newTask))
// 治理层随后调 retryJob(jobBizId) → 调度器 scanPendingJob → dispatchJob → create() 重打包
```
- **优点**：原失败行保留（旧 exec_sql + CANCELED）= 天然版本/审计证据，符合"语句版本+1"；新 query_id 避免任何滞留 sidecar 回报串扰；不改任何现有方法行为（纯新增）；exec_order 复用保证顺序。
- **风险/注意**：
  - 必须保证新 query_id / biz_id 全局唯一（UUID + `nextExecTaskBizId`，现有 createJob 同款生成器，满足）。
  - `create()` 打包用 `task.getGmtCreate()` 作为 `request.setRequestTime`（第 397 行）——新 task 的 gmt_create = 替换时刻，审计时间戳为替换时间（语义正确）。
  - 若同一 exec_order 位置多次修正，会累积多条 CANCELED + 一条 WAIT_EXEC 同 exec_order——打包只取 WAIT_EXEC，无冲突；但 `querySummaryListByJobId` 展示会列出所有行（含 CANCELED），前端需能区分版本。
  - 治理层须在 job=FAILED/PAUSE 时调用（与 retryJob 前置条件一致），执行中不可替换。

#### 路径 (b)：治理层受控直写 task 行 exec_sql

**技术上可行，但不推荐。** 实现方式：新增 `DmExecAutoTaskMapper.updateExecSql(taskId, execSql)` 或用 `updateById`（load DO → setExecSql → save），再把 status 留 FAILED 或重置 WAIT_EXEC，随后 retryJob。
- **优点**：实现量最小；不新增行；query_id/biz_id/exec_order 不变。
- **缺点/风险**：
  - **原地覆盖原失败 SQL 文本** → task 行丢失版本史；"语句版本+1"只能靠治理层独立审计表补记（task 表无 version 列）。
  - query_id 不变 → 若 sidecar 有滞留/延迟的 TASK_* 回报（理论上 job 已 FAILED 停止，但极端竞态下），可能与新文本混淆。
  - 需手动重置 exec_count / affect_row / gmt_last_start / gmt_last_end（否则 retry 后这些计数累加，展示混乱）；路径 (a) 新行天然清零。
  - 治理层直接操作执行层 DO/mapper，边界更模糊（设计 §2.2 触点 #6 倾向"新增方法不改现有行为"）。

#### 推荐：路径 (a)

理由汇总：
1. task 表无 version 列 → (a) 用"旧行 CANCELED + 新行 WAIT_EXEC"天然表达版本链，(b) 需外挂审计表。
2. (a) 是纯新增方法，零改动现有执行/打包/回报代码路径（设计 §2.2 "新增方法不改现有行为"的红线满足）。
3. (a) 新 query_id 隔离 sidecar 回报，更安全。
4. exec_order 复用 + 现有打包/调度链已验证兼容，无需改 create()/retryJob/scanPendingJob。

---

## 关键文件索引

| File | 作用 |
|---|---|
| `backend/clouddm-platform/cgdm-dao/src/main/java/.../model/execution/DmExecAutoTaskDO.java` | task DO，execSql 字段（第 41 行） |
| `backend/clouddm-platform/cgdm-dao/src/main/resources/mybatis/mapper/DmExecAutoTaskMapper.xml` | 全部 task SQL（batchInsert/retryTask/queryNeedExec*/transactionCommit/Rollback 等） |
| `backend/clouddm-platform/cgdm-dao/src/main/java/.../mapper/execution/DmExecAutoTaskMapper.java` | Mapper 接口（extends BaseMapper，含 updateById） |
| `backend/clouddm-platform/cgdm-dao/src/main/java/.../model/execution/AutoExecTaskStatus.java` | 7 态枚举：WAIT_EXEC/EXECUTING/WAIT_CONFIRM/FAILED/FINISH/ROLLBACK/CANCELED |
| `backend/clouddm-platform/cgdm-console/src/main/java/.../component/execute/AutoExecService.java` | 接口（createJob/dispatchJob/retryJob/skipTask/continueTask/create） |
| `backend/clouddm-platform/cgdm-console/src/main/java/.../component/execute/impl/AutoExecServiceImpl.java` | 核心实现：createJob(127)、dispatchJob(256)、create打包(308)、retryJob(636)、skipTask(527)、continueTask(509) |
| `backend/clouddm-platform/cgdm-console/src/main/java/.../component/execute/impl/AutoExecScheduleService.java` | 调度器：scanPendingJob(88) 每5s→dispatchJob；updateOverOutJob(79) 每1min 兜底 |
| `backend/clouddm-platform/cgdm-console/src/main/java/.../service/approval/ApprovalControlServiceImpl.java` | prepareExecJobAsync(871)：analysisSplitStream(1021)→createJob(1022) |
| `backend/clouddm-platform/cgdm-console/src/main/java/.../provider/ExecJobRServiceProvider.java` | sidecar 回报处理：reportMessage(99)，taskStart/Finish/Failed/WaitConfirm/Skip + transactionCommit/Rollback |
| `backend/clouddm-platform/cgdm-sidecar/src/main/java/.../worker/component/autoexec/AutoExecJob.java` | sidecar 执行：run(89)/jobWrap(252)/jobRun(300)/jobRunItem(323)，sendMessage(426) |
| `backend/clouddm-boot/boot-initialization/src/main/java/.../scripts/V202605070037__sql_auto_exec.java` | 原始建表 dm_auto_exec_task（第 55-72 行，exec_sql text not null） |
| `backend/clouddm-boot/boot-initialization/src/main/java/.../scripts/V202607290003__consolidated_upgrade.java` | 升级：exec_sql→longtext(61)、+query_id(64)、唯一索引 uk_exec_auto_task_query_id(120) |
| `backend/clouddm-platform/cgdm-dao/src/main/resources/mybatis/mapper/DmExecAutoJobMapper.xml` | job 表 SQL：claimJobForPackaging(148)/retryJob(100)/listUnFinishJobIdList(244) |

## Caveats / Not Found

- `replaceTask` 方法在现有代码中**不存在**（grep 无命中），属本期纯新增。
- 未发现任何对 `exec_sql` 列的 update 操作（仅 createJob 插入 + queryAutoExecTaskSql 读取展示）。
- task 表**无 version 列**——"语句版本+1"须由治理层独立表（设计 §3.4 新增表）承载，task 行的版本体现仅靠 CANCELED 旧行 + WAIT_EXEC 新行的物理并存。
- `AutoExecCreateMO` / `DmConfirmTicketFO.autoExecConfig` 的治理注入点属验证项 #5，本文未展开。
- 未覆盖 PG 方言下 `analysisRequestsStream` 对修正后语句的再分析差异（验证项 #3 范畴）。
