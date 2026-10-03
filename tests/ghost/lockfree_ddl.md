# 无锁结构变更(gh-ost)回归测试手册

> 覆盖:专用配置页(7e)/ 单条 ALTER 自动路由(M1)/ 告警放行(M1)/ 混合分段串行编排(M2)/ 三链补全 retry·resubmit·发布链明示(M3)/ 段级视图(M4,§6.6)/ 安全面 / 存量回归。
> 代码基线:`54aac74e`(M3 已提交)+ M4(前端语句级段视图,工作区交付);段级视图小节见 6.6。
> 自动化基线:后端全量 1580/0(单元+钉测试背书),本手册聚焦**人工 UI/真库面**。

## ⛔ 红线(执行前必读)

1. **仅测试实例**:全部真库操作只在腾讯云/阿里云 MySQL 8.0 测试实例执行,严禁生产环境(创建/删除/批量/并发/极限操作一律禁止);
2. **钉钉告警用测试群**:先在 `/integrations/im` 配测试群机器人,或演练告警场景前临时关闭 `alertPushEnable`,防打扰真实运维群;
3. **手工改库仅限测试库**:F1 场景需 UPDATE 工单运行态,只允许对测试库执行,改前记录原值;
4. 密码/凭据不写入任何文档、截图、工单描述。

---

## 0. 前置准备(一次性)

| # | 项目 | 操作 | 通过标准 |
|---|---|---|---|
| 0.1 | 后端版本 | 重启后端(cwd 必须 `backend/`) | 启动日志含 `GHOST tool bound (gh-ost lock-free DDL)`;无新增 ERROR(既有 42 条 RDP 权限组 i18n 缺键噪音可忽略) |
| 0.2 | gh-ost 二进制 | `ls -l ~/clouddm/tools/gh-ost/gh-ost` | 存在且可执行(16,950,087 B = 1.1.11 linux) |
| 0.3 | 测试数据源 | 平台已注册腾讯云 MySQL 8.0 测试实例(直连,非 SSH/SSL) | 数据源连通性测试通过 |
| 0.4 | 造数(见附录 A) | 建 `ghost_test_big`(有 PK,≥10 万行)+ `ghost_test_nopk`(无 PK,≥10 万行) | `SELECT COUNT(*)` ≥ 100000 |
| 0.5 | 阈值调低 | 管理后台 → 无锁变更设置页:`ghostRowThreshold` 改 **50000**(测完恢复 10000000) | 保存成功,`ghost_test_big` 超阈值、小表不超 |
| 0.6 | 钉钉测试群 | `/integrations/im` 配置测试群机器人 webhook+secret,enable | 测试推送可达群 |
| 0.7 | 双账号 | 账号甲=有 `DM_GHOST_EXEC` 权限;账号乙=无 | 权限矩阵用例需要 |
| 0.8 | 审批链 | 确认测试租户 PRE_DDL 工单审批可自动/快速通过 | 不阻塞执行链演练 |

---

## 1. 专用配置页(7e · R14 · 验收 A5)

入口:侧栏「管理」组 → 无锁变更(`/system/ghost/settings`),需管理员权限。

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 1.1 | 三区块展示 | 打开页面 | 「自动路由」(ghostAutoRoute 开关+两阈值)、「告警」(alertPushEnable 开关+/integrations/im 引导链接)、「gh-ost 默认参数」(13 参数表单:数值/布尔/cutOverType 下拉 DEFAULT/ATOMIC)齐全;当前生效值=管理 JSON 叠加插件默认 |
| 1.2 | 保存生效 | 改 `chunkSize=2000`、`verbose=true` → 保存 | 保存成功提示;刷新页面回显一致;审计日志有 upsert 留痕 |
| 1.3 | 数值范围校验 | 阈值输入 0 / 超上限(>1048576 MB 或 >100000000000 行) | 前端拒绝+范围提示,与后端 WhiteList 一致 |
| 1.4 | 坏 JSON 容错 | (如走通用 KV 页手工写坏 ghostDefaultOptions)回配置页 | 红色 Alert 提示+回退插件默认展示;在配置页重新保存后自愈 |
| 1.5 | 恢复插件默认 | 点「恢复插件默认」 | 确认弹窗**含草稿丢弃警告**;确认后 KV 清空、表单回插件默认;立即持久化 |
| 1.6 | 参数真实生效 | 完成 1.2 后跑一次第 2 章流程 | gh-ost 日志可见 `chunk-size=2000` 等参数生效(或 effectiveOptions 快照留痕);**测完把参数改回默认** |
| 1.7 | 开关即时生效 | `ghostAutoRoute` 关闭→保存→提交超阈值工单 | 不评估/不标记/不告警,直接 ALTER(=2.3);重开恢复 |
| 1.8 | 权限 | 非管理员账号访问 | 无入口/被拒 |

## 2. 单条 ALTER 自动路由(M1 核心 · R2/R4 · 验收 A2/A5)

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 2.1 | **主流程(最重要)** | 提交 PRE_DDL 工单,单条:`ALTER TABLE ghost_test_big ADD COLUMN remark VARCHAR(128) NULL;` → 预检 → 审批通过 → 自动执行 | ① 提交页预检结果区**黄旗明示**「表 ≈N 行 ≥ 阈值,将以无锁方式执行」(无勾选、无确认,自动路由);② 审批过后自动走 gh-ost(无 job 直接链);③ 工单详情组区出现 ghost 进度面板:阶段语义(inspect→建影子表→拷贝→cut-over→完成)+ Copy N/M 百分比 + Lag + ETA 实时推进(tailStatus 5s 轮询);④ 完成后工单 **FINISHED**;⑤ 库侧验证:`SHOW CREATE TABLE ghost_test_big` 含 remark 列 |
| 2.2 | 未超阈值不触发 | 对 <5 万行小表提交同类 ALTER | 预检无黄旗,走现状直接 ALTER,行为与历史一致 |
| 2.3 | 止血开关 | `ghostAutoRoute=false` 后对 ghost_test_big 提交 ALTER | 不评估不分段不告警,直接 ALTER(现状行为);恢复开关 |
| 2.4 | 阈值修改即时生效 | `ghostRowThreshold` 调到 200000 → 对 10 万行表提交 | 不再触发无锁(直接执行);调回 50000 恢复触发 |
| 2.5 | 无权限只读 | 账号乙查看 2.1 执行中的工单详情 | 进度可见,暂停/恢复/终止/删旧表按钮**不可见** |
| 2.6 | PG 不评估 | (如有 PG 15 数据源)提交 PG DDL 工单 | 预检不做 ghost 评估,现状行为 |

## 3. 进度呈现与执行控制(R5/R6 · 验收 A2)

在 2.1 执行期间(拷贝阶段窗口最长,适合演练;可加大表行数延长窗口):

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 3.1 | 暂停/恢复 | 拷贝中点「暂停」→ 观察 → 点「恢复」 | gh-ost 日志出现 throttle 标记,进度停增;恢复后继续推进;工单状态不因此失败 |
| 3.2 | 日志 tail | 打开日志面板翻页 | tailLog 分页正常(0=最新页,>0 自文件头正向翻页);teardown 噪音行**不判失败** |
| 3.3 | 终止 | 另起一单,拷贝中点「终止」 | gh-ost panic 中止;工单进 **EXEC_FAIL**(执行失败);组状态 FAILED;可走第 7 章 retry |
| 3.4 | 完成态浏览 | 打开已 FINISHED 的 ghost 工单详情 | **不弹全局错误 modal**(会话已释放,背景探测静默);终态信息/旧表名仍可见 |

## 4. 旧表管理(R7 · 验收 A3)

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 4.1 | 旧表保留 | 2.1 完成后查库 | `SHOW TABLES LIKE 'ghost_test_big%'` 存在 `_ghost_test_big_YYYYMMDDHHMMSS_del` 时间戳旧表(timestampOldTable=true) |
| 4.2 | 删除入口+二次确认 | 工单详情点「删除旧表」 | 提示「删除前可回退,删除后不可回退」+二次确认;确认后表不存在 |
| 4.3 | 审计 | 查审计日志 | 删除操作有审计留痕(操作人/时间/表名) |
| 4.4 | 无权限 | 账号乙 | 删除按钮不可见 |

## 5. 告警放行(M1 · R15 · 验收 A4)

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 5.1 | **无 PK 告警放行(核心)** | 提交单条 `ALTER TABLE ghost_test_nopk ADD COLUMN remark VARCHAR(128) NULL;`(超阈值+无 PK)→ 预检 → 审批 → 执行 | ① 工单**不拦截**,提交页有「超阈值但无主键,将直接执行并已告警」提示;② **钉钉测试群收到告警**:文本含申请人+表名+行数/大小+风险说明+工单深链;③ 工单事件链有 `GHOST_ALERT_TRIGGERED` 留痕;④ 工单照常直接 ALTER 执行成功 |
| 5.2 | 告警开关 | `alertPushEnable=false` → 重跑 5.1 新单 | 钉钉**不发**;提交/执行完全不受影响;事件链无告警留痕(或静默记录,以实际为准记录);恢复开关 |
| 5.3 | 未配 messenger | (可临时 disable 测试群机器人)重跑 5.1 | 静默降级,提交不受影响,无报错弹窗 |
| 5.4 | /check 预览不轰炸 | 提交页反复点「预检」多次 | 钉钉**只在 submit 权威预检发一次**,预览不发(防重复轰炸) |
| 5.5 | SSH/SSL 数据源 | (如有此类数据源)超阈值 ALTER 提交 | 同族处置:放行直接执行+告警;无此环境则**记录豁免** |

## 6. 混合分段串行编排(M2 · R9 · 验收 A6)

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 6.1 | **混合组全链(核心)** | 提交一个 PRE_DDL 工单,同组 4 条语句:①`ALTER TABLE ghost_test_small1 ADD COLUMN c1 INT;`(小表)②`ALTER TABLE ghost_test_small2 ADD COLUMN c2 INT;`(小表)③`ALTER TABLE ghost_test_big ADD COLUMN seg3 VARCHAR(64);`(超阈值)④`ALTER TABLE ghost_test_small3 ADD COLUMN c4 INT;`(小表)→ 审批 → 执行 | ① 提交页明示段计划(不再拦截要求拆分);② 执行按段串行:普通段[①②]直接 job → ghost 段[③] gh-ost(进度面板)→ 普通段[④] job;③ 工单终态 **FINISHED**,4 条语句全部生效(逐条 SHOW CREATE TABLE 验证);④ 段间严格串行(③完成前④不启动) |
| 6.2 | **纯普通组回归(关键)** | 全小表多语句组工单,完整走一遍 | 行为与 M2 之前**逐位一致**:无段视图差异、直接 job 链、终态一致(这是存量回归面,任何差异都是缺陷) |
| 6.3 | 多组工单聚合 | 一个工单含两组:组 A=超阈值 ghost 组,组 B=普通组 | 两组独立推进,工单终态=全组聚合正确(A、B 都成功→FINISHED;任一失败→EXEC_FAIL) |
| 6.4 | 语句编号契约 | 6.1 完成后查执行详情/语句清单 | stmt_index 与提交顺序一致(ghost 语句占号不建 task 行=空洞编号,但清单展示不乱序不丢号) |
| 6.5 | 存量工单 | 找 M2 上线**前**提交的旧工单(无 segments) | 查看/重试/退回修订均正常(决策序 legacy 分支) |
| 6.6 | 段级视图(M4) | 见下方 **6.6 小节**(6.6a~6.6h) | 按 6.6 小节逐项验证 |

### 6.6 段级视图(M4 · 前端语句级段清单 · 验收 A6 前端面)

前置:6.1 的混合组工单可复用于 6.6b~6.6d;权限矩阵用 0.7 双账号;轮询/请求面断言用 DevTools Network 面板过滤 `tailStatus|tailLog|groupList`。

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 6.6a | 创建页段计划明示 | 6.1 工单在提交页点「预检」 | 预检结果区出现「执行段」块:每段一行 = 徽标(直接执行/无锁执行)+ 语句号范围(如「语句 1 - 2」「语句 3」)+ GHOST 段表名·约行数;与 M1 路由黄旗行**共存不冲突**;纯普通组预检**无**「执行段」块 |
| 6.6b | 计划态段视图(未执行) | 提交后、审批/执行前打开工单详情 | 组区出现可折叠「执行段」块(段数徽标),非 SUCCESS 组默认展开;各段状态「待开始」;段内语句行 = 类型 Tag + SQL 预览,按段范围过滤,**1 基编号与提交清单一致**(ghost 语句占号,不缺号不乱序);GHOST 段**无面板**(未派发无会话);点段头折叠/展开,选择跨 5s 自动刷新保持 |
| 6.6c | 执行态:唯一面板+轮询语义 | 6.1 执行期间观察(gh-ost 拷贝窗口) | EXECUTING 的 ghost 段行内嵌无锁面板(进度/阶段/Lag/ETA/日志/控制按钮),且为该组**唯一**面板(组级不再另渲染 = 互斥,无双进度条);AUTOEXEC 段仅段级状态,**无假语句级进度**;Network:tailStatus/tailLog ~5s 轮询**只对活会话**;已终态段的面板挂载时仅一次静默探测(不弹全局错误 modal、不起 interval) |
| 6.6d | 完成态 | 6.1 FINISHED 后打开详情 | 全段「完成」;ghost 段行内旧表名+删除入口(= 4.2 的入口移到段行内,二次确认文案一致);路由黄旗行仍显示(阈值文案);SUCCESS 组段视图默认收起,一键展开 |
| 6.6e | 权限矩阵 | 账号乙(无 DM_GHOST_EXEC)查看 6.6c 执行中工单 | 段视图/段状态/语句清单正常可见(只读进度);ghost 段面板只读:无暂停/恢复/终止/删旧表按钮;Network 中账号乙**不发** /ghost/* 请求(仅 groupList 投影) |
| 6.6f | 失败与重试联动 | 3.3 终止(或 7.1 失败)后观察,再点「重试」 | 失败段行红字「失败原因」+ 段状态「失败」,组 EXEC_FAIL;重试后:失败段「待开始」→「执行中」(ghost 段=新会话新面板),已成功段保持「完成」**不重放**;视图跟随运行态,无键错乱/闪变 |
| 6.6g | 存量形态回归 | ① M2 上线前提交的 M1 存量 flags-only ghost 工单(预检有 ghostRoutes 无 segments);② 纯普通/legacy 工单 | ① 详情页仍走 **M1 组级面板**形态,无「执行段」块;② 无任何段视图,组区渲染与 M4 之前逐位一致(6.2 的前端面) |
| 6.6h | 多 ghost 段已知瑕疵 | (可选,造数成本高)一组含 ≥2 条超阈值 ALTER → 全部完成后删除**更早段**旧表 → 刷新页面 | 更早段删除按钮**复显**(组投影只携带最新任务事实,已知无害瑕疵,附录 B 记录);点击 → 后端拒绝(GHOST_OLD_TABLE_ABSENT 文案),**零副作用**;最新段按钮态正常 |

## 7. 失败重试三链(M3 · 验收 A6)

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 7.1 | 普通段失败→retry 续跑 | 混合组中让某普通段失败(如语句引用不存在的列)→ 工单 EXEC_FAIL → 点「重试」 | 从**失败段**续跑:已成功段**不重放**(验证其表变更时间戳/内容无二次动作),失败段修复后(退回修订改 SQL 或环境恢复)继续到 FINISHED |
| 7.2 | ghost 段失败→重试重建 | 3.3 终止造成的 ghost 段 FAILED → 点「重试」 | 旧会话被释放(closeQuietly),**重建新 gh-ost 会话**从头迁移(initiallyDropGhostTable 清残留影子表);二次迁移成功到 FINISHED;**连续第三次**再迁移仍可成功(并发会话槽位释放验证) |
| 7.3 | **F1 场景:探测直达终态** | ① 让 ghost 段迁移**真实成功**(新列已落+旧表已生成)但工单回写丢失——两种做法任选:(a) 拷贝完成 cut-over 后、终态回写前立刻重启后端(窗口窄,多试几次);(b) 手工改测试库:该组置 FAILED、exec_segments 对应段状态改 FAILED(**改前记录原值**)→ ② 点「重试」 | retryGroup 探测 `information_schema.columns` 发现 ADD COLUMN 已落 → 段直接置 SUCCESS(summary 留痕 `resumeProbe:TARGET_COLUMN_EXISTS`)→ **工单直达 FINISHED**(不得卡 EXEC_FAIL;若卡住=F1 回归,报缺陷);**不重放迁移**(表上无二次 gh-ost 动作) |
| 7.4 | **F2 场景:dock 拒绝旧任务重试** | 制造段失败 → 点「重试」重建在途 → 立刻到任务面板(dock)对**旧终态 GHOST 任务**点「重试」 | 被拒,提示 GHOST_TASK_DOCK_OP_FORBIDDEN 对应文案;**在途新迁移不受影响**,继续到终态 |
| 7.5 | dock 守卫全组 | 对 ticket-bound GHOST 任务在 dock 点 暂停/取消/重试 | 三个操作均被拒(工单绑定任务生命周期属工单链,恢复入口=工单重试);**其他 bizType 任务 dock 操作照常**(抽验 AUTOEXEC/其他) |
| 7.6 | 活跃拒绝 | ghost 段执行中(在途)对工单点「重试」 | 被拒(活跃检查:in-flight 一律拒),不发生双 gh-ost |
| 7.7 | resubmit 重算 | 分段组工单失败 → 「退回修订」→ 修改 SQL(增/删语句或改表)→ 重新提交 | 预检重跑、**段计划重算**(exec_segments 重置)、从首段重新执行;SUCCESS 组不可编辑(被拒) |

## 8. 发布链过渡明示(M3)

前置:测试租户发布链(台账/合并确认)可用;有 FINISHED 的 ghost 源 PRE_DDL 工单。

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 8.1 | 合并确认黄旗 | 台账勾选含 ghost 源工单 → 合并确认 | Modal 出现**黄旗 Alert**:PROD 重放=直接执行、大表存在锁风险(本期发布链不 ghost 化) |
| 8.2 | 发布详情黄旗 | 打开含 ghost 源的发布单详情 | 黄旗 Alert 展示(gateResult 解析容错,坏数据不白屏) |
| 8.3 | 无 ghost 源不变 | 纯普通工单走发布链 | **无任何黄旗**(gate_result 字节形状与 M3 前一致),合并/重放行为不变 |
| 8.4 | 台账标记 | 台账列表 | ghostRouted 工单有 additive 标记展示 |
| 8.5 | 豁免 | 环境无发布链形态 | 记录豁免+原因 |

## 9. 安全面(R12 · 验收 A7)

在任一 ghost 迁移执行中(拷贝阶段):

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 9.1 | argv 无密码 | 部署机 `ps aux \| grep gh-ost`(用户自行执行) | 命令行含 `--ask-pass`,**不含** `--password=`、不含任何明文凭据 |
| 9.2 | 日志无密码 | 检查后端日志/gh-ost 日志中打印的命令 | 只出现真实命令(无脱敏占位差异=argv 本就无密码),无密码字样 |

## 10. 存量回归(非影响面确认)

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 10.1 | 普通 DML 工单 | 提交执行一个 DML 工单 | 与历史行为一致(与 ghost 无涉) |
| 10.2 | 普通 DDL 小表 | 6.2 已覆盖 | — |
| 10.3 | 审批/人审链 | 走一个需人工审批的工单 | 审批链行为不变 |
| 10.4 | 任务面板 | dock 其他 bizType 任务操作(暂停/取消/重试) | 照常(仅 ticket-bound GHOST 被守卫) |
| 10.5 | 启动日志 | 重启后核对 | 无新增 ERROR(既有 RDP i18n 噪音除外) |
| 10.6 | 独立页已撤 | 访问 `/ghost` 旧路由 | 404/重定向,侧栏无独立菜单(素材页已撤) |

## 11. 引擎侧 curl 冒烟(可选 · Step 6 遗留)

需登录态凭据(用户自行携带,勿外泄/勿入文档):

- `POST /api/entry/ghost/precheck`:有 PK 表 / 无 PK 表 / PG / SSH 数据源 → 校验矩阵;tableName+ddlSql 双空 → COMM_BAD_ARG_ERROR;
- `POST /api/entry/ghost/execute`(测试实例真库)→ `tailStatus`(Copy N/M/阶段/Lag/ETA)→ `tailLog`(0=最新页,>0 正向翻页)→ `pause`/`resume`/`stop` → `dropOldTable`(二次确认+审计);
- 无凭据访问 → 401(认证门);不存在路径 → 静态兜底 200(已验证)。

## 12. 验收对照表

| 验收项 | 覆盖章节 | 结果 | 日期 |
|---|---|---|---|
| A1 编译与部署 | 0.1/0.2(已于 2026-10-01 实证 ✅) | ✅ | 2026-10-01 |
| A2 工单全流程 | 2.1 + 3 + 4.1 | ☐ | |
| A3 旧表删除 | 4 | ☐ | |
| A4 告警放行 | 5 | ☐ | |
| A5 自动路由与配置 | 1 + 2 | ☐ | |
| A6 混合编排与重试 | 6 + 7 | ☐ | |
| A7 安全 | 9 | ☐ | |
| A8 测试 | 自动化已绿(1578/0),人工核对门禁记录 | ☐ | |
| A9 冒烟 | 全册(含阿里云侧补验或豁免记录) | ☐ | |

---

## 附录 A · 造数 SQL(仅测试实例)

```sql
-- 大表(有 PK,≥10 万行)
CREATE TABLE ghost_test_big (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(64) NOT NULL,
  amount DECIMAL(12,2),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- 倍增插入:执行 17 次 ≈ 131,072 行(每次行数翻倍)
INSERT INTO ghost_test_big(name, amount) VALUES ('seed', 1.0);
INSERT INTO ghost_test_big(name, amount) SELECT CONCAT(name, '-x'), amount FROM ghost_test_big;
-- ↑ 重复执行 17 次;SELECT COUNT(*) 验证 ≥ 100000

-- 无 PK 大表(告警场景)
CREATE TABLE ghost_test_nopk (
  name VARCHAR(64),
  amount DECIMAL(12,2)
) ENGINE=InnoDB;
INSERT INTO ghost_test_nopk(name, amount) SELECT name, amount FROM ghost_test_big;

-- 小表(混合组普通段用,行数 < 阈值)
CREATE TABLE ghost_test_small1 (id INT PRIMARY KEY, v VARCHAR(32)) ENGINE=InnoDB;
CREATE TABLE ghost_test_small2 (id INT PRIMARY KEY, v VARCHAR(32)) ENGINE=InnoDB;
CREATE TABLE ghost_test_small3 (id INT PRIMARY KEY, v VARCHAR(32)) ENGINE=InnoDB;
```

清理(全部测完后):`DROP TABLE ghost_test_big, ghost_test_nopk, ghost_test_small1, ghost_test_small2, ghost_test_small3;` + 清理全部 `_..._del` 时间戳旧表 + 恢复阈值(1000 万行/1GB)与 ghostDefaultOptions(恢复插件默认)。

## 附录 B · 已知边界与豁免记录

| 项 | 说明 | 处置 |
|---|---|---|
| 阿里云侧 | spike 仅腾讯云实证;阿里云 RDS 需 `--aliyun-rds` 拓扑开关(实现已带) | 补验或在此记录豁免理由:______ |
| 1.1.11 dry-run | spike 于 1.1.4 实测,1.1.11 契约 flag 面核对一致 | DB 侧 dry-run 复验结果追记 spike-result §6:______ |
| 探测预先存在列 | 7.3 探测对「用户提交对已有列的 ADD」判实成功(终态等价,summary 留痕) | 已知口径,非缺陷 |
| 告警真 @ 到人 | 本期群推+文案注明申请人 | 二期 |
| 旧表定时清理 | 本期保留+手动删 | 后续定时任务 |
| M4 段级视图 | 6.6 小节已补全(6.6a~6.6h) | 浏览器复测随 6.6 表执行(需后端重启到含 M4 的版本) |
| 更早 ghost 段删除按钮复显 | 多 ghost 段组:组投影只携带最新任务事实,更早段旧表已删后刷新页面按钮复显 | 已知无害瑕疵(点击被 GHOST_OLD_TABLE_ABSENT 拒绝,零副作用);修复需段级 dropped 事实进投影/合成 DTO=后端行为面,超 M4 边界,移交后续(6.6h 验证) |

## 附录 C · 测试记录表模板

| 章节# | 场景 | 预期 | 实际 | 通过 | 执行人 | 日期 | 备注 |
|---|---|---|---|---|---|---|---|
| 2.1 | 主流程 | | | ☐ | | | |
| ... | | | | | | | |
