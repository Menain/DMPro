# Phase 0 · 实施期验证清单（10 项 Open Items）

## Goal

动代码前，以**只读代码调研**逐项确认 spec §13 的 10 个验证项，每项产出：结论 + 代码证据（文件/类/方法/行号）+ 对后续 Phase 的影响与实现路径选择。结论持久化到本任务 `research/` 目录，最终回填 spec §13。

## 需求源

- `docs/spark/2026-09-06-clouddm-db-change-governance-design.md` §13（验证清单原文）、§2.2（核心触点）、§4.5/§4.6（推进器与修正闭环）
- 代码库：open-cdm 4.2.0（backend/ 全部模块 + frontend/）

## Requirements（10 项验证，含影响的 Phase）

| # | 验证项 | 需回答的问题 | 影响 |
|---|---|---|---|
| ① | **语句替换的引擎路径**（最高优先） | `dm_exec_auto_task` 是否持有语句文本（哪列）；console 侧拆句写入点；`dispatchJob` 打包 zip 的文本读取源（task 行 or 重拆 rawSql）；`retryJob` 重打包对新 task（CANCELED 旧 + 新增行）的兼容性、`exec_order` 语义；结论=`AutoExecService` 新增治理专用 `replaceTask` vs 治理层受控直写 task 行，二选一并给出具体落点 | P5/P7 |
| ② | 审批漏斗身份校验 | `approvalFlowService.approvalTicket`/`confirmTicket` 是否校验操作者必须为被指派审批人/确认人；SYSTEM 代审走现有漏斗是否可行；若被拒，定向代审通道的最小改动点 | P4 |
| ③ | `DmlExplainPreInitHandler` 的 PG 支持 | EXPLAIN 执行方式、有无方言分支、PG 数据源是否可用；不可用则确认路径 B PG 降级策略生效条件 | P8 |
| ④ | 通知能力复用 | `ImSenderService`/`DingTalkMsgSendSpi` 在 console 侧的通用调用入口（cicd 现有用法）、消息模板机制、深链格式 | P5 |
| ⑤ | `autoExecConfig` 注入与锁定 | `DmConfirmTicketFO.autoExecConfig` 结构与 `confirmTicket` 消费方式；SYSTEM 代审传参注入点；PROD 人工确认时的锁定方式（前端预置+门禁二配置合规校验兜底）可行性 | P4/P7 |
| ⑥ | 工单确认权限标签现状 | `ApprovalController.confirm`（或对应接口）现有 `@RequestAuth` 标签；决定复用还是新增 `RDP_DB_CHANGE_PROD_CONFIRM` | P7/P10 |
| ⑦ | `approBiz` 参数化影响面 | `createSqlTicket`/`createSqlTicketInTransaction` 中 `DM_QUERY` 写死位置；下游对 bizType 的全部分支依赖（handler 路由/前端展示/查询逻辑）；参数化后默认值保持 `DM_QUERY` 是否零行为变化 | P4（触点 #1） |
| ⑧ | schema 元数据 SPI 确切名称 | Preflight 存在性检查（表/列/索引）所用的元数据获取接口：console 侧经 RSocket 到 sidecar 的调用路径与接口名 | P7 |
| ⑨ | `ApprovalMO` 扩展兼容性 | 现有字段（`changeId`/`changeOwnerUid`/`autoExec` 等）与 CI/CD 用法；新增 `promotionId`/`revisionId` 字段的 Jackson 序列化/反序列化兼容性（未知字段策略、旧数据反序列化） | P4（触点 #1） |
| ⑩ | `res_desc` 列现有使用方式 | 授权查询/展示/写入逻辑是否依赖 `res_desc` 语义；写入 `PERM_GROUP:<groupId>:<groupResourceId>` 标记有无副作用 | P2 |

## 约束

- **只读调研**：不修改任何业务代码；产出只写本任务 `research/` 目录 + 最终回填 spec §13（docs 文件）
- 每项结论必须附代码证据（路径+类/方法+关键行内容），不允许凭 spec 记忆推断
- 与 spec 假设冲突时：以代码为准，记录冲突点与对 spec 的修正建议

## Acceptance Criteria

- [ ] 10 项全部有明确结论（可行/不可行/需小改动+改动点），持久化在 `research/`
- [ ] 验证项① 给出语句替换二选一的最终推荐及理由（影响 Phase 5 设计）
- [ ] 验证项②⑤⑦⑨ 的结论明确 Phase 4 触点 #1 与推进器的实现细节
- [ ] spec §13 每项回填结论（或注明"见 research/xxx.md"）
- [ ] 发现的 spec 冲突/修正建议单独列出，交用户裁决
