# Phase 3 · 逻辑库+环境绑定+治理角色参数

## Goal

逻辑库域：dm_logical_db CRUD+dm_logical_db_env_binding绑定维护(UNIQUE(logical_db_id,env_id)，引用env_id非env_name)；治理角色参数读取(GOV_ROLE=PRE/PROD/空)；绑定解析服务getBinding(服务端解析目标库，前端不可指定/覆盖)；LogicalDbController(list/create/update/delete/detail/bindingSet/bindingList/myLogicalDbs)。验收：getBinding不可被前端覆盖；env_id引用正确。spec §3.3/§8.1-P3。前置：Phase 1。

## Requirements

- TBD

## Acceptance Criteria

- [ ] TBD

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
