# Phase 8 · 路径 B 直发生产 DML（开关/仅DML判定/阈值分级）

## Goal

directDmlSubmit接口(入参logicalDbId+sql+rollbackSql必填+description)：GOV_DML_DIRECT开关校验+专用标签RDP_DB_CHANGE_PROD_DML_DIRECT+PROD资源权限→parser行为判定'仅DML'(DDL/DCL拒绝)→逐句拆分写stmt_version(INITIAL)→同事务创建PROD工单+冻结Revision(DIRECT_PROD_DML)+promotion；EXPLAIN对PROD库(只读安全)+GOV_DML_ROW_LIMIT阈值分级(≤warn正常/warn~block审批表单标注高风险/>block拒绝FAILED)；之后与路径A PROD段完全同链；路径B失败需改SQL→重新提交新工单(新revision/promotion/审批)。验收：仅DML、默认关、超阈值按策略处置、同事务三对象一致。spec §4.2路径B/§8.1-P8。前置：Phase 0(验证项③)、Phase 6、Phase 7。

## Requirements

- TBD

## Acceptance Criteria

- [ ] TBD

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
