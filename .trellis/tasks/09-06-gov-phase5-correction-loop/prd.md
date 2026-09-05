# Phase 5 · 修正闭环（失败通知/correctStatement/任务替换/断点续跑）

## Goal

D14核心：语句失败通知(EXEC_FAIL→组装语句定位+错误+深链→复用DingTalkMsgSendSpi/ImSenderService推提交人)；核心触点#6语句替换能力(依Phase0验证项①二选一：AutoExecService新增治理专用replaceTask[失败task→CANCELED+新版本task入列，exec_order语义保持] 或 治理层受控直写task行)；correctStatement接口(鉴权=仅本工单提交人+EXEC_FAIL+PRE治理环境→语句版本+1→增量审计[不过则拒绝版本不前进]→替换该语句执行任务→retryJob断点续跑[FINISH不重放])；stmtTimeline接口(版本史×task状态×事件聚合)。验收：失败→通知→修正→续跑全链；非提交人被拒；已成功语句不重放；版本史完整。spec §4.6/§8.1-P5。前置：Phase 0(验证项①④)、Phase 4。

## Requirements

- TBD

## Acceptance Criteria

- [ ] TBD

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
