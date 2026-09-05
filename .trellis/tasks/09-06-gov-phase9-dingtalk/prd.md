# Phase 9 · 钉钉审批集成（治理字段表单/强制第三方Provider/回调幂等）

## Goal

PROD治理环境强制第三方Provider校验(禁Internal自审，门禁一第7条：无审批模板→拒绝创建promotion)；ChangeForm填充治理字段(变更单号/逻辑库/环境/申请人/风险等级/影响行数/sql_hash/PRE执行结果/SQL摘要前N字符，完整SQL留平台)；复用现有DingApprovalProviderSpi创建实例+Stream/回调更新审批状态；回调只更新状态绝不直接执行SQL(执行统一走工单确认→Guard→AutoExec)；回调重复幂等(APPROVED后重复回调忽略)+丢失兜底(Stream事件+getLastInfo定期同步，复用不自建)。验收：表单含单号/风险级/影响行数/hash/PRE结果；回调丢失可同步；重复回调幂等。spec §5.5/§8.1-P9。前置：Phase 6。

## Requirements

- TBD

## Acceptance Criteria

- [ ] TBD

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
