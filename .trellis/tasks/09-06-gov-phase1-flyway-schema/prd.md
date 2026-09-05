# Phase 1 · 平台元数据库脚本（10 表+5 标签+4 env key）

## Goal

新增 1 个 Flyway Java 脚本(V<实施日>NNNN__db_change_governance, AbstractUpgradeJavaMigration)：创建治理层 10 张新表(权限组域4+环境治理域2+变更治理域4)、注册 5 个新权限标签(RDP_PERM_GROUP_MANAGE/RDP_LOGICAL_DB_MANAGE/RDP_DB_CHANGE_PROD_PROMOTE/RDP_DB_CHANGE_PROD_DML_DIRECT/RDP_DB_CHANGE_GOVERN_READ)、新增 4 个 env param key(GOV_ROLE/GOV_DML_DIRECT/GOV_DML_ROW_LIMIT/GOV_AUTO_CONFIRM)。验收：脚本可执行、唯一约束生效、不动历史脚本、不改现有表。spec §3/§8.1-P1。

## Requirements

- TBD

## Acceptance Criteria

- [ ] TBD

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
