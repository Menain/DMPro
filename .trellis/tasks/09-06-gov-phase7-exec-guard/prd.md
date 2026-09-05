# Phase 7 · 执行链 Guard（门禁二双挂点/逐句hash复验/Preflight/配置合规）

## Goal

门禁二6条双挂点：核心触点#2(prepareExecJobAsync开头门禁调用，无治理引用零成本直通)+核心触点#3(dispatchJob claim后门禁调用，覆盖重试/重调度)；检查项：promotion状态=APPROVED/CONFIRMED(只信DB)、整单+逐句hash复验(任何一句不一致DENY并定位到句)、绑定复验(防审批后换库)、Preflight基础版(走现有schema元数据SPI方言路由：连通+表存在+DDL依赖列/索引状态+DML目标表存在)、幂等双层(execution_key+depend_on_biz_id)、执行配置合规校验(errorStrategy/enableTransactional与成分路由注入值一致，治理PROD禁SKIP)；核心触点#5(skipTask/continueTask对治理PROD工单硬拦截)；GOV_AUTO_CONFIRM=on时推进器自动确认(必经门禁二)；Preflight失败→restoreExecutionConfirmation回WAIT_CONFIRM+记事件。验收：hash/绑定/配置复验；PREFLIGHT_FAIL回确认态；重试路径必经门禁；PROD skip被拒。spec §4.4/§8.1-P7。前置：Phase 0(验证项⑤⑥⑧)、Phase 6。

## Requirements

- TBD

## Acceptance Criteria

- [ ] TBD

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
