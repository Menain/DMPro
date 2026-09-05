# Phase 12 · 灰度上线（逐库灰度三步走）

## Goal

治理角色标记天然支持逐库灰度(spec §8.3)：①选一个非核心逻辑库标PRE/PROD治理角色只跑路径A；②稳定后为该PROD环境开GOV_DML_DIRECT试跑路径B；③逐步扩大逻辑库范围与环境标记。验收：未标记环境全程行为零变化(现有普通工单不受治理约束)；灰度期间DENY事件与Timeline完整可审计。前置：Phase 11完成定义达成。

## Requirements

- TBD

## Acceptance Criteria

- [ ] TBD

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
