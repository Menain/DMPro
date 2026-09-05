# Phase 0 验证总结与裁决（2026-09-06）

## 结论总表（10 项）

| # | 验证项 | 结论 | 对 spec 的影响 | 证据文件 |
|---|---|---|---|---|
| ① | 语句替换引擎路径 | ✅ 可行，**锁定方案 (a)**：`AutoExecService.replaceTask(jobBizId, failedTaskId, newExecSql)`——失败 task→CANCELED + 新行 WAIT_EXEC（复用 exec_order、新 query_id/biz_id）。`exec_sql` 列为单句权威文本；dispatchJob 打包读 task 行；retryJob 只重放 WAIT_EXEC/FAILED/ROLLBACK | 触点 #6 落定 | item-01 |
| ② | 审批漏斗身份校验 | ⚠️ SYSTEM 直走漏斗不可行（approvalTicket 校验 dm_approval_person；confirmTicket 经 checkJobOperationEnable 硬校验）→ **定向代审通道**：审批仿 `refreshApprovalStatus` 先例直调 `handler.approvalApproved()`；确认新增 SYSTEM 定向入口 | §4.5 改写，Phase 4 范围+1 | item-02-05-07-09 |
| ③ | DmlExplain PG 支持 | ✅ 部分支持，**降级零代码**：PgExplainPlanSpi 排除 DML 行估算→expectedAffectedRows=0→UNSUPPORTED 不阻断；行阈值对 PG 自然失效（已知约束） | §2.3 回填 | item-03-08 |
| ④ | 通知能力复用 | ✅ 可行有约束：MsgSendSpi **纯文本 only**；入口=直配版 `ImSenderServiceImpl.sendMessage(ownerUid, ImSenderConfig, MsgContent)`；深链 `/ticket/{id}`；无 Provider 抛异常、**无降级通道**→治理环境必须配 IM Provider | §4.5-4 补约束；Phase 12 预检+1 | item-04-06-10 |
| ⑤ | autoExecConfig 注入与锁定 | ✅ 可行：confirmTicket 现状透传零校验；系统代审服务端构造无耦合（confirmUid 由 Controller 注入）；合规校验挂点=`prepareExecJobAsync` 开头（触点 #2 原位） | §4.5 回填 | item-02-05-07-09 |
| ⑥ | 确认权限标签 | ✅ **裁决：复用 `RDP_WORKER_ORDER_EXECUTE`**（confirm 现用标签，DBA-only），不新增 RDP_DB_CHANGE_PROD_CONFIRM | §5.2 落定（D16） | item-04-06-10 |
| ⑦ | approBiz 参数化 | ✅ 可行（写死仅 2 处、下游全支持 DM_CHANGE、默认零变化）+ **新坑**：`convertToChangeForm` 强依赖 changeId/changeOwnerUid，治理 PROD 工单走第三方审批抛异常 → **裁决：扩展该方法加治理分支 = 新增触点 #7** | 触点 6→7（D16）；§5.5/Phase 9 范围修正 | item-02-05-07-09 |
| ⑧ | schema 元数据 SPI | ✅ 四项 Preflight 检查全现成接口零新增：`DsSchemaService#realTimeFetchVersion`（连通）/`#realTimeFetchSelectObject`（表存在，返 RdbTable/null）/`RdbTable#getColumns()`/`getIndices()`；双方言覆盖 | §4.4 门禁二回填 | item-03-08 |
| ⑨ | ApprovalMO 扩展 | ✅ 安全（类级 @JsonIgnoreProperties + 全局 FAIL_ON_UNKNOWN=false 双保险）；**实施顺序**：先加 POJO 字段再让治理层写（ticketInfo 存在读-改-写路径，否则静默丢字段） | 触点 #1 补顺序注意 | item-02-05-07-09 |
| ⑩ | res_desc 用法 | ✅ 标记可行：纯展示字段无解析依赖；mergeGrantedAuth 合并会丢标记+混 label → "绕过 merge 直接 insert" 必要性被证实；副作用（审批表单暴露标记串）不在治理路径 | §3.2 证实，无改动 | item-04-06-10 |

## 用户裁决（2026-09-06，AskUserQuestion）

1. **验证项⑦（审批表单）**：选"扩展 `convertToChangeForm` 加治理分支"——ticketInfo 含治理引用（promotionId/revisionId）时组装治理字段表单；无治理引用保持 CI/CD 原逻辑。改动集中一处，符合最小侵入。
2. **验证项⑥（确认标签）**：选"复用 `RDP_WORKER_ORDER_EXECUTE`"——与授权矩阵"生产确认=DBA"一致；治理 PROD 额外约束（禁 SKIP 等）由触点 #5 门禁承接。

## 波及的 spec 修订（rev.2 → rev.2.1）

- §0 新增 D16（Phase 0 裁决记录）
- §2.2 触点总账 6→7：#1 补 POJO 顺序注意与"写死仅 2 处"结论；#6 锁定 replaceTask；**新增 #7 convertToChangeForm 治理分支**
- §2.3 触点 2/3 回填 SPI 名称与 PG 降级结论
- §4.4 门禁二 Preflight 回填具体接口
- §4.5 推进器：代审通道由"验证点"改为"定向通道方案"；通知补纯文本/无降级约束
- §5.2 确认标签落定复用
- §5.5 补触点 #7 说明
- §8.1 Phase 4（定向代审通道）/Phase 9（触点 #7 + CI/CD 表单零变化验收）修正
- §10 ADR-002 触点数 6→7
- §11 风险 #1/#2/#12 标记已验证并落定处置；风险 #8 触点数 6→7
- §13 十项全部回填结论

## 后续 Phase 的直接输入

- **Phase 4**：触点 #1（approBiz 参数化 2 处 + ApprovalMO 字段先行）、SYSTEM 定向代审通道（审批+确认两环节）、autoExecConfig 服务端构造
- **Phase 5**：触点 #6 = `replaceTask` 方案 (a)；通知 = `ImSenderServiceImpl.sendMessage` 直配版纯文本 + `/ticket/{id}` 深链
- **Phase 7**：Preflight 调 `DsSchemaService` 现成四接口；配置合规校验挂 `prepareExecJobAsync` 开头
- **Phase 8**：PG 行阈值自然失效为已知约束（降级零代码）
- **Phase 9**：触点 #7 = `convertToChangeForm` 治理分支（验收加"CI/CD 工单表单行为零变化"）
- **Phase 12**：灰度预检加"治理环境已配置 IM Provider"
