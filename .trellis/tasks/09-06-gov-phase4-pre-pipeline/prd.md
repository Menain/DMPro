# Phase 4 · PRE 治理链路（preSubmit/成分路由/推进器代审/Revision冻结）

## Goal

路径A PRE段：核心触点#1(createSqlTicket参数化approBiz默认DM_QUERY+ApprovalMO加promotionId/revisionId字段)；preSubmit接口(只收logicalDbId+title+description+sql[+rollbackSql含DML成分必填]，拒收dsId/envId)；逐句拆分写stmt_version(INITIAL)+成分判定(DDL/DML/MIXED)；D15成分路由(纯DML→enableTransactional=true，含DDL→autocommit+NONE，治理单一律NONE)；治理推进器(仿ApprovalStarter守护循环：SYSTEM代审+代确认经现有漏斗注入配置留痕)；Revision冻结(扫FINISHED PRE工单→整单hash+逐句manifest，SHA256(原文+确定性空白规范化))；治理PreInit handler(门禁fast-fail，Spring List注入零接线)。验收：DDL/DML/混合提单→自动→执行成功→冻结；缺回滚SQL被拒；SYSTEM留痕；配置按成分正确注入。spec §4.2/§4.5/§8.1-P4。前置：Phase 0(验证项②⑤⑦⑨)、Phase 1、Phase 3。

## Requirements

- TBD

## Acceptance Criteria

- [ ] TBD

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
