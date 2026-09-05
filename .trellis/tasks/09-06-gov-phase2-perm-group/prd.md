# Phase 2 · 权限组（CRUD/成员/资源/物化展开/撤销保护）

## Goal

权限组域全量：组CRUD+成员管理+组资源授权(DO/Mapper/Service/PermissionGroupController)；物化展开契约'总是展开、各删各的'(绕过mergeGrantedAuth直接resMapper().insert，账本dm_perm_group_grant_record记auth_res_id)；展开行res_desc写PERM_GROUP:<groupId>:<groupResourceId>标记；核心触点#4个人授权撤销路径对标记行拒绝单独回收；同事务重算(加移成员/授撤资源/停用/有效期)。验收：入组即得、离组即失、各删各的、保护拦截生效。spec §3.2/§8.1-P2。前置：Phase 1。

## Requirements

- TBD

## Acceptance Criteria

- [ ] TBD

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
