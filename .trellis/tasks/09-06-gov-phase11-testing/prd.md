# Phase 11 · 集成+安全测试+双方言回归（DENY 矩阵 12 case）

## Goal

本项目豁免AGENTS.md'不主动新增测试类'规则(spec §7.2)：Guard DENY矩阵12 case全覆盖(①PRE未成功②无审批③审批被拒④整单hash篡改⑤逐句manifest不一致[hash/version/pre_exec任一]⑥PROD绑定缺失⑦绑定被换⑧无PROD资源权限⑨Preflight失败⑩DDL走直发入口⑪开关关闭/行数超block阈值⑫执行配置被篡改→全部DENY且留痕)；修正闭环测试(通知发出/非提交人被拒/增量审计拒绝非法修正/版本+1/任务替换/断点续跑不重放/manifest含修正史/事务模式全回滚重跑/混合工单DDL前序已应用)；幂等与并发(双钉钉回调/重试重入必经dispatchJob门禁/EXECUTING中重启三态判定/同revision并发promote唯一约束/双worker只执行一次)；安全测试(无标签403/夹带sql dsId approvalStatus拒绝或忽略/伪造APPROVED无效/治理PROD skip continue被拒/correctStatement对PROD被拒/通知深链无凭证)；权限组一致性(入组即得离组即失双路径验证/各删各的/保护拦截/有效期同步失效)；双方言回归(全部治理链路用例MySQL+PG各跑一遍：规则集路由/Preflight元数据/DML Explain/成分路由事务语义)；单元测试最小覆盖+集成链路(PRE全链/promote全链/路径B全链/EXEC_FAIL重试链)；前端lint/check-i18n/all_build.sh web+核心流程浏览器级检查(tests/frontend/维护可复用流程文档)。验收：spec §8.2完成定义两条链路真实环境跑通+安全底线8条全拦截。spec §7.2/§8.2/§8.1-P11。前置：Phase 1-10全部完成。

## Requirements

- TBD

## Acceptance Criteria

- [ ] TBD

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
