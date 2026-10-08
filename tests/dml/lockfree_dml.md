# 无锁数据变更(大表 DML 分批+限流)回归测试手册

> 覆盖:N0 人审链多组执行 / 主流程自动分批(MySQL+PG)/ 断点续跑 / 窗口崩溃人工裁决 / 无键拒绝+告警+审计 / PRE 混合组 / 暂停恢复终止 / 限流节流 / 配置页双区块 / 安全取证 / 存量回归。
> 代码基线:N0~N3 共 7 笔提交(`d6ac8d92`/`0c63d7fd`/`7c1ab86e`/`cfbaa40f`/`336339dc`/`96a162b9`/`816c1580`)+ N4 文档面。
> 自动化基线:后端全量 2053/0(单元+钉测试背书,含 B6 全套治理钉零改动绿),本手册聚焦**人工 UI/真库面**。
> 前端浏览器细项规程:`tests/frontend/ticket/ticket_detail.md` TD-MAIN-08。契约参考:`.trellis/spec/backend/lockfree-dml.md`。

## ⛔ 红线(执行前必读)

1. **仅测试实例**:全部真库操作(造数、分批执行、锁注入、DELETE/UPDATE 对账)只在腾讯云/阿里云 MySQL 8.0 与 PG 15 测试实例执行,严禁生产环境;
2. **钉钉告警用测试群**:先在 `/integrations/im` 配测试群机器人,或演练告警场景前临时关闭 `alertPushEnable`,防打扰真实运维群;
3. **手工改库仅限测试库**:锁注入、计数对账、configData/审计核查等直连操作只允许对**业务测试库与平台元数据库(只读为主)**执行;需改平台元数据的场景改前记录原值;
4. **杀进程场景(第 4 章)影响该 console 全部在途任务**:执行前确认无其他重要工单在跑;kill/重启均由用户执行;
5. 密码/凭据不写入任何文档、截图、工单描述、记录表。

---

## 0. 部署前置(缺一不可,**宿主先行**)

| # | 项目 | 操作 | 通过标准 |
|---|---|---|---|
| 0.1 | 宿主重建 | `gradlew -p /home/myan/open-cdm/backend :boot-alone:installDist`(用户执行重启,**cwd 必须 `backend/`**) | 后端起新版;启动日志无新增 ERROR(既有 RDP 权限组 i18n 缺键噪音可忽略);gh-ost 插件锚 `GHOST tool bound` 仍在(B6 不受影响) |
| 0.2 | 两插件 jar | sql-mysql/sql-postgres `customFatJar` 产出 `ds-mysql-lib.jar`/`ds-postgres-lib.jar`,**手工 cp 到 `~/clouddm/plugins/`**(严禁 `local` 任务部署) | 两 jar 时间戳晚于 N1a 修复重产(含会话变量拒绝自修);`ls -l ~/clouddm/plugins/` 确认 |
| 0.3 | **部署顺序** | 新插件 jar × 旧宿主 = `NoSuchMethodError`——**必须先部署 0.1 宿主新包,再/同时更新 0.2 插件 jar** | 重启后提交任意含 UPDATE 的工单预检,后端日志无 NoSuchMethodError |
| 0.4 | 配置面就位 | 打开 `/system/ghost/settings`;打开通用 KV 配置页 | 设置页出现「无锁数据变更」区块(六项:自动路由开关+五数值);KV 页可检索 `dmlRowThreshold` 等六键(三语描述正常) |
| 0.5 | 权限面就位 | 权限管理查看 `DM_GHOST_EXEC` label | 文案已更新为「无锁变更执行(DDL/DML,包含:空间占用查看权限)」(en: Lock-free Change Execute (DDL/DML, …));权限 key/授权关系零变化 |

## 0A. 前置准备(一次性)

| # | 项目 | 操作 | 通过标准 |
|---|---|---|---|
| 0A.1 | 测试数据源 | MySQL 8.0 直连测试实例 + PG 15 测试实例(通道 C 走平台标准会话,SSH/SSL 亦支持) | 连通性测试通过 |
| 0A.2 | 造数(附录 A) | 建 5 类表:有 PK 大表(MySQL/PG 各一)、**非幂等计数表**、无 PK 大表、字符串 PK 表(可选)、小表若干 | 行数 ≥ 降阈后阈值(推荐降阈路线,免造百万行) |
| 0A.3 | 阈值调低 | 设置页「无锁数据变更」区块:`dmlRowThreshold` 改 **10000**(测完恢复 1000000);`dmlBatchSize` 可留 5000 或调 1000 加快批数观察 | 保存成功;大表超阈、小表不超 |
| 0A.4 | 钉钉测试群 | `/integrations/im` 配置测试群机器人 webhook+secret,enable;`alertPushEnable=true` | 测试推送可达群 |
| 0A.5 | 双账号 | 账号甲=有 `DM_GHOST_EXEC`;账号乙=无 | 权限矩阵用例 |
| 0A.6 | 审批链 | 测试租户 PROD_DML 人审通道可快速通过(或 DingTalk 测试模板);PRE_DDL 自动批 | 不阻塞执行链演练 |
| 0A.7 | 平台元数据库访问 | 可只读查询 `dm_exec_async_task`(configData 取证)、`dm_mon_biz_log`(批取证)、审计表 | 第 3/4/10 章核查用 |

---

## 1. N0 地基:多组 PROD_DML 全组执行(缺陷修复实证 · 验收 A1)

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 1.1 | **多组全执行(核心)** | 提交 PROD_DML 工单含 **2 个组**(不同库对各一组,每组 1~2 条小 UPDATE)→ 审批 → 人工确认「立即执行」 | **两组都执行**(历史缺陷=只执行首组);工单详情组区两组状态各自推进到 SUCCESS;工单终态 FINISHED;两库数据都实际变更(逐组 SQL 对账) |
| 1.2 | 组行推进 | 1.1 完成后查平台元数据库 `dm_ticket_db_stmt` | 两组 execStatus 均 SUCCESS(历史缺陷=组行永远 PENDING) |
| 1.3 | 执行期状态语义 | 1.1 执行期间观察工单状态 | 工单停留 **WAIT_EXEC(待执行)** 至终态聚合,不转「执行中/RUNNING」——**这是声明的行为变更**(v2 组路径 activities-only 标记,与 PRE_DDL 现状一致),非缺陷;步骤时间线「已派发」正常打点 |
| 1.4 | legacy 明细区空置 | 打开 1.1 工单详情执行步骤 | legacy task 明细卡**空置/不渲染**(v2 组无 dependOnBizId job)——诚实呈现(裁决 #20),组区/段区是执行呈现面;不弹错误 |
| 1.5 | MANUAL_EXEC 保留 | 另提一单,确认时选「已手动完成」 | 工单直接 FINISHED,不建任何执行体(job/任务均无) |
| 1.6 | SPECIFY_TIME 保留(纯普通组) | 另提一单纯普通小 DML,确认时选「定时执行」+未来时间 | job 落库 INIT 等定时;到点自动执行;语义与切换前一致 |
| 1.7 | 失败恢复 | (可选,构造成本高:确认瞬间令组 2 数据源不可达)确认多组工单 | 组 1 已建 job 被清理(仅未被调度器认领时)、组态复位、确认状态完整恢复(可重新确认);无半派发残留 |
| 1.8 | open command 对等 | (如有 External 通道凭据)open API execute 命令确认一单多组 PROD_DML | 与人工确认同构:全组派发(SYSTEM 副本同款 v2 分支) |

## 2. 主流程:单条大 DML 自动分批(验收 A2,MySQL+PG 双方言)

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 2.1 | **UPDATE 主流程(MySQL,最重要)** | 提交 PROD_DML 单组单条:`UPDATE dml_test_big SET status = 1 WHERE status = 0 AND amount < 100;`(超阈)→ /check 预览 → 提交 → 审批 → 确认(立即执行)→ 观察至终态 | ① 预检/提交页**黄旗**「语句 1:表 dml_test_big 预估影响 ≈N 行 ≥ 阈值,将以分批限流方式执行」(键列/批大小/预计批数)+**执行段**块出现 DML_BATCH 段;② 确认弹窗:**事务开关禁用**+明示「批间独立提交,整单事务不生效;部分成功语义;无自动回滚」;③ 执行期:详情页段视图 DML_BATCH 徽标+**行内进度**(已执行批/行数/百分比≈)随 5s 组刷新推进,**Network 面板无独立轮询请求**(仅既有 groupList/detail 5s 链);④ 工单 FINISHED;⑤ **SQL 对账**:`SELECT COUNT(*) FROM dml_test_big WHERE status=1 AND amount<100` = 预期行数;边界行不重不漏(总数对账+抽样边界 id) |
| 2.2 | DELETE 一轮(MySQL) | 同 2.1,语句 `DELETE FROM dml_test_del WHERE amount < 100;`(造数副本表) | 分批删除到 FINISHED;`COUNT(*)` 对账=删除预期;DELETE 幂等黄旗**不出现**非幂等警示(绿字语义) |
| 2.3 | **PG 双方言** | PG 15 测试实例重复 2.1(表名/列小写;键列双引号纪律由平台改写承载) | 与 MySQL 同款全链通过;首批改写 SQL(第 10 章取证)中键列为 `"k"` 双引号形态、边界子查询 `SELECT MAX(...) FROM (... ORDER BY ... LIMIT ...) bnd` 形态正确 |
| 2.4 | 未超阈值不触发 | 对小表提交同类 UPDATE | 无黄旗无分段,直接执行(现状行为逐位一致) |
| 2.5 | 非幂等黄旗 | 提交 `UPDATE dml_test_counter SET cnt = cnt + 1 WHERE amount < 100;` | 预检黄旗**追加非幂等警示**(自引用 SET);照常可分批(标记不拦截);审批材料可见 |
| 2.6 | 形状门灰提示 | 分别提交超阈但:多表 UPDATE(`UPDATE a, b SET …`)、自带 LIMIT、含注释(`-- x` 前缀)、CTE 形态 | 均**不分批不拒绝**,直接执行+灰字「形态不支持分批,将直接执行(大表存在长锁/超时风险)」类提示 |
| 2.7 | 字符串键(可选) | 字符串 PK 表(VARCHAR)超阈 UPDATE | 游标式分批通过;对账不重不漏(collation 安全论证的实测面) |
| 2.8 | 无权限只读 | 账号乙查看 2.1 执行中工单 | 进度/段状态可见;暂停/恢复/终止按钮与裁决 Modal **不可见**;Network 无 `/dmlBatch/*` 请求 |
| 2.9 | 止血开关 | `dmlBatchAutoRoute=false` 保存 → 提交超阈工单 | **不评估/不标记/不拒绝/不告警**,直接执行(现状);在途批任务(如有)跑完不受影响;测完恢复开关 |

## 3. 断点续跑:批失败→自动重试→retryGroup(验收 A3)

前置:非幂等计数表 `dml_test_counter`(每行 cnt 初值已知,附录 A);锁注入用第二会话(测试库直连)。

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 3.1 | **批失败自动重试** | 提交 `UPDATE dml_test_counter SET cnt = cnt + 1 WHERE amount < 100;` → 执行中用第二会话 `BEGIN; SELECT * FROM dml_test_counter WHERE id BETWEEN <当前批区间> FOR UPDATE;`(不提交,盯 configData cursor 定位当前批) | 批 DML 锁等待超时(DB 错误 1205)→ 任务进入重试(statusMsg/详情可见,间隔加倍)→ 释放锁(ROLLBACK)后**同批重试成功**(语句级原子=零生效后重跑,非幂等亦安全);继续到 FINISHED |
| 3.2 | 重试耗尽→段 FAILED | 3.1 中持续持锁不放(≥3 次重试,间隔 intervalMs×2) | 重试耗尽 → 段 FAILED(错误=原始 DB 错误)→ 组 FAILED → 工单 **EXEC_FAIL**;checkpoint 留痕(configData:cursor/intent.retryPending/retryCount/lastBatchError) |
| 3.3 | **retryGroup 断点续跑+不重放** | 释放锁 → 工单详情点「重试」 | 新批任务从**冻结 cursor 续跑**(configData 新任务 checkpoint.cursor=旧任务 done 游标;旧任务不复用);已成功批**不重放**——对账:全表 `SELECT COUNT(*) FROM dml_test_counter WHERE cnt <> <初值+1>` = **0(每行恰好 +1)**;这是「已成功批永不重放」硬契约的数据实证 |
| 3.4 | 活跃拒绝 | 批任务在途时对工单点「重试」 | 被拒(GOV_TICKET_V2_DML_BATCH_TASK_ACTIVE 文案),不发生双任务 |
| 3.5 | 多语句组失败续跑 | (可选)组 [小 UPDATE, 大 UPDATE],令大 UPDATE 段失败 → 重试 | 仅失败段重放;前段 AUTOEXEC 已成功语句不重跑(段编排语义) |
| 3.6 | 无需 resolution 的普通失败 | 3.2/3.3 全程 | 普通批失败(DB 错误已收=零生效可证)重试**不弹裁决 Modal**、retryGroup 不要求 resolution 参数(retryPending 态不升级人工——裁决 #21 收窄) |

## 4. 窗口崩溃人工裁决(验收 A4,选项 C 硬契约)

⚠ 本章需杀 console 进程(红线 4:确认无其他重要在途任务;kill/重启由用户执行,cwd=backend/)。

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 4.1 | **intent 无 done → UNRESOLVED** | 大 UPDATE(建议非幂等计数表)分批执行中,盯 `dm_exec_async_task.config_data` 出现 `intent`(seq/lo/startedAt,无 retryPending)的瞬间 **kill 派发 console** → 用户重启后端 | 重启后任务主机复位恢复 → tick 入口检出 intent∧¬retryPending → **不自动续跑** → 段 FAILED + summary 冻结 `unresolvedIntent` + **钉钉 DML_BATCH_UNRESOLVED 告警**(工单深链+段/批定位+「需人工核对后选择重跑/跳过」);详情段行红字「批 N 结果未知」类文案。(窗口窄可多试;或改用 4.1b) |
| 4.1b | 失联死判路径(备选) | kill 后**不重启**该 console(或重启前等待),让 WAIT_EXEC sweep 心跳陈旧死判(阈值=timeoutSec+max(intervalMs,10s)×2+60s,默认 ≈380s) | sweep 死判段 FAILED(DML_BATCH_TASK_LOST 文案)+ intent 未决 → 同 4.1 的 UNRESOLVED 标记+告警;checkpoint 完好 |
| 4.2 | **裁决 Modal(红绿字)** | 4.1 后账号甲打开工单详情 → 失败段行「人工裁决」入口 | Modal:批定位(intentSeq)+lastBatchError 辅助+**非幂等红字**「重跑该批可能双倍生效,请先核对数据」(幂等语句=绿字「重跑安全」)+RERUN/SKIP 单选+风险说明+二次确认;账号乙不可见该入口 |
| 4.3 | RERUN 续跑 | 先直连测试库**人工核对该批数据**(cursor 区间行是否已 +1),据实选择:未生效 → 选「重跑该批」 | retryGroup 携带 resolution=RERUN_BATCH → 新任务从 cursor 正常续跑 → FINISHED;对账每行恰好 +1;事件留痕 `DML_BATCH_RESOLUTION`(operator=操作人)+审计 DML_BATCH_EXECUTE(obj.resolution/intentSeq/via=TICKET_RETRY) |
| 4.4 | SKIP 续跑 | 另造一次 4.1,人工核对该批**已生效** → 选「跳过该批」 | 首 tick 仅重推导边界推进 cursor,**零写效应**(该批不再执行);summary 出现 `skippedBatches` 证据(seq/lo/hi);续跑到 FINISHED;对账:被跳批行 = +1(已生效那次),其余行 +1,无双倍 |
| 4.5 | resolution 缺失拒绝 | (API 级,可选)对 UNRESOLVED 段不带 dmlBatchResolution 直接调 retryGroup | 拒绝 `GOV_TICKET_V2_DML_BATCH_RESOLUTION_REQUIRED`;UI 上 Modal 是唯一入口(缺失时无从绕过) |
| 4.6 | **未消费 SKIP 重冻结(N2 修复实证,尽力而为)** | 选 SKIP 提交 retry 后,**赶在新任务首 tick skip-done 落盘前**再次 kill console(窗口=秒级,多试;或先暂停任务再杀)→ 重启 → 等 sweep 死判 | 段再次 FAILED 且 summary **仍带 unresolvedIntent**(SKIP 裁决未被消费 ⇒ marker 重冻结)→ 再点「重试」**仍要求人工裁决**(可再确认 SKIP 或改 RERUN),**绝不静默按 RERUN 重跑结果未知批** |
| 4.7 | 终止后裁决链 | (可选)UNRESOLVED 工单直接走第 7 章终止 | 部分成功保留+EXEC_FAIL;段仍 UNRESOLVED,重试仍要求裁决 |

## 5. 无键拒绝 + 告警 + 审计留痕(验收 A5)

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 5.1 | **/check 红错 + submit 拒绝(核心)** | 对 `dml_test_nopk`(无 PK/UK,超阈)提交大 UPDATE → 先 /check 预览 → 再 submit | /check:组级**红错**「表无主键/唯一键,无法无锁执行;请加键后重试或缩小影响范围」(表+行数);**预览不发消息不写审计**;submit:**整单拒绝**(工单不落库,列表查无此单)+同款拒绝文案 |
| 5.2 | 钉钉告警送达 | 5.1 submit 拒绝时观察测试群 | 告警含:申请人+`db.table`+行数/阈值+组织规范 §9.8.3 违规说明+SQL 摘要(≤500 截断)+**无工单深链**(工单未落库,文案注明) |
| 5.3 | **审计留痕可查(裁决 #16)** | 审计日志页检索 `DML_BATCH_REJECT`(或按操作人/时间过滤) | 行存在且在 submit 事务回滚后**存活**(事务外写入实证):主体=提交人,资源=数据源,obj 含 table/rows/reason=NO_KEY/sqlSummary/keyDetail/stmtIndex/**alertPushed=true** |
| 5.4 | 告警关闭审计照写 | `alertPushEnable=false` → 重跑 5.1 | 钉钉**不发**;拒绝语义不变;**审计照写**(alertPushed=false);恢复开关 |
| 5.5 | 未配 messenger | (临时 disable 测试群机器人)重跑 5.1 | 静默降级,拒绝不变,审计照写,无报错弹窗 |
| 5.6 | open 通道对等 | (如有凭据)open API `/tickets/submit` 提交同款无键大 DML | 同款拒绝+告警+审计(URI marker=/open/dbChange/tickets/submit)——开放通道不是留痕绕行后门 |
| 5.7 | 未超阈无键放行 | 对小无键表提交 UPDATE | **现状放行**(不误伤;拒绝仅限超阈+确认无键) |
| 5.8 | 可空 UK 视同无键 | (可选)建表仅有可空 UK 列(多 NULL 行),超阈大 UPDATE | 走拒绝路径(nullable UK=NULL 键行会静默漏行,视同无键) |
| 5.9 | PG 同评估 | PG 无键大表重复 5.1 | 同款拒绝+告警+审计 |
| 5.10 | 会话状态门(不拒绝) | 组内 [大 UPDATE(无键表), `SET @x=1;`] 提交 | **NO_KEY 拒绝不受组内 SET 语句影响**(拒绝先于路由);对照:组内 [大 UPDATE(有键表), `SET @x=1;`] → 大 UPDATE **不路由分批**(灰提示「组内含会话状态语句」),直接执行不拒绝 |

## 6. PRE 混合组三类段串行(验收 A6)

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 6.1 | **混合组全链(核心)** | 提交 PRE_DDL 工单同组 3 条:①小 UPDATE(普通)②超阈大 UPDATE(→DML_BATCH)③超阈大 ALTER(→GHOST,`ghostRowThreshold` 已按 B6 手册调低)→ 审批(SYSTEM 自动批)→ 执行 | 段计划=[AUTOEXEC(①), DML_BATCH(②), GHOST(③)] 三段**严格串行**:①直接 job → ②批任务(进度推进)→ ③gh-ost(进度面板);工单 FINISHED;三条语句全部生效(SQL 对账+SHOW CREATE TABLE);段间不并发 |
| 6.2 | 纯普通组回归 | 全小语句 PRE 组完整走一遍 | 行为与 B7 之前**逐位一致**:无段视图、直接 job 链、事务开关按 changeType 现状(DML 组事务开启可用,不被强制关) |
| 6.3 | 多组聚合 | PROD_DML 两组:组 A=超阈大 DML(分批),组 B=普通小 DML(直接) | 两组独立派发各自推进;全组终态聚合正确(都成功→FINISHED;任一失败→EXEC_FAIL) |
| 6.4 | 语句编号契约 | 6.1 完成后核对段视图语句行 | 1 基编号与提交清单一致;DML_BATCH 语句占号不建 task 行(空洞编号但清单不缺号不乱序) |
| 6.5 | 分批组事务强制 | PROD_DML 组含大 DML(分段)+小 DML,确认弹窗观察 | 事务开关**禁用**(前端);(API 级可选:带 enableTransactional=true 强行确认 → 服务端复验强制 false,AUTOEXEC 段仍非事务执行,log 有 DML_BATCH_TRANSACTIONAL_FORCED 痕迹) |
| 6.6 | SPECIFY_TIME+分段组拒绝 | 对 6.5 的工单在确认弹窗选「定时执行」 | 前端选项**禁用+提示**;若 API 强带 SPECIFY_TIME → 拒绝 `GOV_TICKET_V2_DML_BATCH_SCHEDULE_UNSUPPORTED`(「分批组暂不支持定时执行」);纯普通组定时不受影响(1.6) |

## 7. 暂停 / 恢复 / 终止(验收 A7)

在 2.1/6.1 执行期间(批数多窗口长;可调小 dmlBatchSize 拉长):

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 7.1 | 暂停=批粒度 | 点「暂停」 | **当前批跑完才停**(在途批 done 照写,configData 时间戳实证);进度停增;状态「已暂停」;工单/组/段状态**不变**(暂停≠失败) |
| 7.2 | 长暂停不被死判 | 暂停放置 **>10 分钟**(远超心跳陈旧阈 ≈380s) | sweep **不误判**(PAUSE=受控健康,不查心跳);恢复后续跑正常 |
| 7.3 | 恢复 | 点「恢复」 | 从暂停点续批;进度继续推进到 FINISHED |
| 7.4 | 节流黄标 | (如延迟探测可见且实例有副本)lag 超阈期间观察 | 状态出现「主从延迟节流中」黄标+lag 值;节流期间不写批;滞回:低于阈值一半才恢复批执行 |
| 7.5 | **终止=部分成功保留** | 另起一单,分批中点「终止」(红色,二次确认) | 在途批跑完后停;段 FAILED(部分成功文案)+组 FAILED+工单 **EXEC_FAIL**;**已生效批不回滚**(数据对账:cursor 前已改,后未改);事件留痕 `DML_BATCH_ABORT`;详情明示无自动回滚(三点明示) |
| 7.6 | **dock 四拒** | 任务面板(dock)对 ticket-bound DML_BATCH 任务点 暂停/取消/重试/**恢复** | **四个操作全部被拒**(DML_BATCH_TASK_DOCK_OP_FORBIDDEN 文案;比 B6 ghost 严:resume 亦拒——控制单入口=工单链端点);其他 bizType 任务 dock 操作照常;B6 ghost 任务的 resume 放行不受影响(抽验) |
| 7.7 | 状态门诚实拒 | (可选,API 级)对 WAIT_START(排队中)任务调 pause | 拒绝 `DML_BATCH_TASK_STATE_INVALID`(「稍后重试」)——不假成功 |
| 7.8 | 控制按钮权限 | 账号乙查看执行中工单 | 暂停/恢复/终止按钮不可见(甲可见);进度只读 |

## 8. 限流与节流参数实证(验收 A8)

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 8.1 | 批间隔生效 | `dmlBatchIntervalMs=5000` 保存 → 新提交分批工单 → 查 configData 各批 done 时间戳(或 lastTickAt 序列) | 相邻批间隔 ≥5s(含 delayTask 调度抖动);**新派发任务采用新值**(effectiveParams 快照=configData 内 frozen 值,与派发时刻 KV 一致) |
| 8.2 | 延迟探测可见性 | 有副本的实例跑一批;查 statusMsg/lagState | `lagState.visible=true`+lastLagSec 数值;无副本实例 → lag=0 照常执行(无节流) |
| 8.3 | 探测权限缺失降级 | (可选)用无 REPLICATION CLIENT(MySQL)/无 pg_monitor(PG)的账号数据源 | 探测失败 → **never-throw 降级**:固定间隔继续+「延迟不可见」黄标(DML_BATCH_LAG_UNKNOWN 文案);执行不受影响 |
| 8.4 | 真实延迟节流(可选,需副本环境) | 主库持续写入压力制造 Seconds_Behind_Source > `dmlLagThresholdSec`(可调至 1 便于触发) | 批循环让位(节流 tick 零批执行+心跳照写+delayTask≥5s);延迟回落到阈值一半以下 → 自动恢复;记录滞回实证 |
| 8.5 | 单批超时 | (可选)`dmlBatchTimeoutSec=10` + 大批/锁注入 | 批超时归 DB 错误/UNKNOWN 分类处理(见 3.1/4.1 语义);测完恢复 300 |

## 9. 配置页双区块(验收 A9,裁决 #14)

入口:`/system/ghost/settings`(管理员)。

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 9.1 | 区块展示 | 打开页面 | gh-ost 区块(自动路由/告警/13 参数,B6 原样)与「无锁数据变更」区块(开关+五数值:行数阈值/单批行数/批间隔/主从延迟阈值/单批超时)**并列**;各区块独立保存按钮 |
| 9.2 | 范围校验 | DML 区块输入越界值(阈值 0、批大小 999/50001、间隔 -1、延迟 0、超时 9) | 前端拒绝+范围提示,与后端 WhiteList 一致(1~1e9 / 1000~50000 / 0~60000 / 1~3600 / 10~3600) |
| 9.3 | **交叉保存互不干扰(核心)** | ① DML 区块改 `dmlBatchSize=2000`,gh-ost 区块改 `chunkSize=2000` → **只保存 DML 区块** → 刷新;② 反向只保存 gh-ost 区块 → 刷新 | ① DML 值持久化,gh-ost 草稿保留未丢且 `ghostDefaultOptions` JSON **未被写入 2000 以外的 DML 值**;② 反向同理;两区块脏标记独立(改一块只亮该块的保存钮) |
| 9.4 | **同名键显示正确(N2 修复实证)** | 查看 gh-ost 区块「DML 批量大小」字段(=gh-ost `--dml-batch-size`,JSON 内默认 10) | 显示 **JSON 值(10 或管理员配置值)**,**绝不显示 B7 KV 的 5000/2000**;改 B7 `dmlBatchSize` 保存后刷新,gh-ost 该字段仍显示 JSON 值(两载体物理隔离) |
| 9.5 | 恢复默认区块作用域 | DML 区块点「恢复默认」 | 确认弹窗草稿丢弃警告**仅涉本区块**;确认后仅六 DML KV 清空回 fail-safe 默认(5000/1000/10/300/1e6/开);gh-ost 区块(含 ghostDefaultOptions)分毫不动;反向同理 |
| 9.6 | 六 KV 即时生效 | 改 `dmlBatchSize=1000`+`dmlBatchIntervalMs=3000` 保存 → 新提交分批工单 | 新任务 effectiveParams 快照=新值(configData 取证);批数变多、间隔变长(进度/时间戳实证);**在途旧任务用旧快照跑完**(参数随任务冻结) |
| 9.7 | 止血开关 | 9 章内完成 2.9 复核(开关行为) | 同 2.9 |
| 9.8 | 权限 | 非管理员访问 | 无入口/被拒(B6 同款门控) |

## 10. 安全取证(验收 A10)

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 10.1 | **首批改写 SQL 取证** | 2.1/2.3 执行后查平台元数据库 `dm_mon_biz_log`(depend 类型=DML_BATCH_TASK,dependOnBizId=批任务 bizId) | **批 1 有改写后全文**:原语句 + `((原WHERE) AND k > … AND k <= …)` 注入形态;标识符引号正确(MySQL 反引号/PG 双引号);字面量转义正确(字符串键含 `'`/`\` 时按方言 doubling);原文其余部分(SET/别名/格式)零改动;后续每批 (lo,hi,affectRows) 逐批留痕 |
| 10.2 | 边界连续性 | 核对逐批日志的 lo/hi 序列 | 前批 hi=后批 lo(不重不漏);首批无下界;终止批之后无多余记录 |
| 10.3 | 无凭据泄漏 | 检查批任务相关日志/biz_log/configData | 无密码/凭据字样(通道 C 标准会话,凭据不经批面) |
| 10.4 | 注入面抽验 | (可选)造字符串键值含 `'`、`\`、`"` 的行,让游标经过该行 | 改写 SQL 转义正确、批执行无语法错误、数据对账正确 |

## 11. 存量回归(非影响面确认)

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| 11.1 | **B6 ghost 全链抽验** | 按 `tests/ghost/lockfree_ddl.md` 2.1 跑一单超阈 ALTER(或复用既有场景) | gh-ost 自动路由/进度面板/终止/重试全链不受 B7 影响 |
| 11.2 | 发布链披露黄旗 | 有 FINISHED 分批源 PRE 工单 → 台账合并确认/发布详情 | 黄旗「PRE 侧曾分批执行;PROD 重放为直接执行,大表存在长锁/超时风险」;**仅分批源**显示 dmlBatch 披露;纯 ghost 源只显示 ghostRouted(不混);**纯普通源零黄旗**(gate_result 字节形状不变) |
| 11.3 | 普通小 DML 工单 | 提交执行未超阈 PROD_DML | 与历史行为一致(直接 job 链;1.1~1.6 的 N0 语义面除外——那是声明的行为变更) |
| 11.4 | legacy 旧工单 | 找 B7 上线前提交的旧工单查看/重试/退回修订 | 均正常(决策序 legacy 分支;无 ticketType 判别走原路径) |
| 11.5 | 审批/人审链 | 走一个需人工审批的普通工单 | 审批链行为不变 |
| 11.6 | dock 其他任务 | AUTOEXEC/其他 bizType 任务 dock 操作 | 照常(仅 ticket-bound DML_BATCH 四拒、ticket-bound GHOST 三拒) |
| 11.7 | /check 预览幂等 | 同一大 DML 反复 /check | 每次评估一致、不发告警、不写审计;估算/探测结果幂等 |

## 12. 验收对照表(→ prd Acceptance Criteria)

| 验收项 | 覆盖章节 | 结果 | 日期 |
|---|---|---|---|
| A1 N0 地基(多组全执行/三入口对等) | 1 | ☐ | |
| A2 主流程(黄旗→确认→分批→FINISHED→对账,双方言) | 2(+TD-MAIN-08) | ☐ | |
| A3 断点续跑(已成功批不重放,计数器对账) | 3 | ☐ | |
| A4 窗口崩溃人工裁决(UNRESOLVED→RERUN/SKIP) | 4 | ☐ | |
| A5 无键拒绝+告警+审计留痕 | 5 | ☐ | |
| A6 PRE 混合组三段串行+回归 | 6 | ☐ | |
| A7 暂停/终止(批粒度/部分成功/权限/dock 四拒/事务强制/定时拒绝) | 7 + 6.5/6.6 | ☐ | |
| A8 限流与节流 | 8 | ☐ | |
| A9 配置生效(六 KV/止血/双区块隔离) | 9 + 2.9 | ☐ | |
| A10 安全(改写取证/转义/无泄漏) | 10(单测面已绿) | ☐ | |
| A11 测试基线 | 自动化 2053/0 已绿(门禁记录) | ☐ | |
| A12 冒烟签署 | 全册(含阿里云侧补验或豁免) | ☐ | |

---

## 附录 A · 造数 SQL(仅测试实例)

**MySQL 8.0:**

```sql
-- 有 PK 大表(主流程 UPDATE / DELETE 用;倍增插入 14 次 ≈ 16384 行 > 降阈后 10000)
CREATE TABLE dml_test_big (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(64) NOT NULL,
  amount DECIMAL(12,2),
  status TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
INSERT INTO dml_test_big(name, amount) VALUES ('seed', 1.0);
INSERT INTO dml_test_big(name, amount) SELECT CONCAT(name,'-x'), amount FROM dml_test_big;
-- ↑ 重复执行 14 次;SELECT COUNT(*) 验证 ≥ 10000

-- DELETE 副本表(每轮 DELETE 前重建)
CREATE TABLE dml_test_del LIKE dml_test_big;
INSERT INTO dml_test_del SELECT * FROM dml_test_big;

-- 非幂等计数表(断点续跑/窗口崩溃对账用;cnt 初值 0,对账=每行恰好 +1)
CREATE TABLE dml_test_counter (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  cnt INT NOT NULL DEFAULT 0,
  amount DECIMAL(12,2)
) ENGINE=InnoDB;
INSERT INTO dml_test_counter(amount) SELECT amount FROM dml_test_big;
-- 对账基线重置(每轮演练前):UPDATE dml_test_counter SET cnt = 0;

-- 无 PK/UK 大表(拒绝场景)
CREATE TABLE dml_test_nopk (name VARCHAR(64), amount DECIMAL(12,2)) ENGINE=InnoDB;
INSERT INTO dml_test_nopk SELECT name, amount FROM dml_test_big;

-- 可空 UK 表(5.8 可选)
CREATE TABLE dml_test_nulluk (id BIGINT, name VARCHAR(64), UNIQUE KEY uk_name (name)) ENGINE=InnoDB;
INSERT INTO dml_test_nulluk(id, name) SELECT id, NULL FROM dml_test_big;  -- 大量 NULL 键行

-- 字符串 PK 表(2.7 可选)
CREATE TABLE dml_test_strpk (code VARCHAR(64) NOT NULL PRIMARY KEY, v INT) ENGINE=InnoDB;
INSERT INTO dml_test_strpk SELECT CONCAT('C-', LPAD(id,8,'0'), IF(MOD(id,97)=0, "'", '')), id FROM dml_test_big;
-- ↑ 每 97 行埋一个含单引号的键值(10.4 转义抽验)

-- 小表(混合组普通段)
CREATE TABLE dml_test_small1 (id INT PRIMARY KEY, v VARCHAR(32)) ENGINE=InnoDB;
```

**PG 15**(小写标识符;倍增同款):

```sql
CREATE TABLE dml_test_big (id BIGSERIAL PRIMARY KEY, name VARCHAR(64) NOT NULL,
  amount NUMERIC(12,2), status SMALLINT NOT NULL DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
INSERT INTO dml_test_big(name, amount) VALUES ('seed', 1.0);
INSERT INTO dml_test_big(name, amount) SELECT name || '-x', amount FROM dml_test_big;  -- ×14
CREATE TABLE dml_test_counter (id BIGSERIAL PRIMARY KEY, cnt INT NOT NULL DEFAULT 0, amount NUMERIC(12,2));
INSERT INTO dml_test_counter(amount) SELECT amount FROM dml_test_big;
CREATE TABLE dml_test_nopk (name VARCHAR(64), amount NUMERIC(12,2));
INSERT INTO dml_test_nopk SELECT name, amount FROM dml_test_big;
```

**清理**(全部测完后):DROP 全部 `dml_test_*` 表(双方言)+ B6 混合组用 ghost 测试表按 B6 手册清理 + **恢复六 KV 默认**(阈值 1000000、批 5000、间隔 1000、延迟 10、超时 300、autoRoute 开)+ 恢复 `ghostRowThreshold`/`alertPushEnable`(如动过)。

## 附录 B · 已知边界与豁免记录

| 项 | 说明 | 处置 |
|---|---|---|
| 阿里云侧 | 主演练环境腾讯云;阿里云 RDS 拓扑(代理)对通道 C 无特殊要求(区别于 gh-ost 的 `--aliyun-rds`) | 补验或记录豁免理由:______ |
| 真实延迟节流(8.4) | 需带副本且可制造延迟的环境;单测已钉 engage/滞回/释放全矩阵 | 无环境则记录豁免,以 8.2/8.3 可见性+降级面替代:______ |
| PG GUC 残余 | PG 语句内 `current_setting('k', true)` 在批会话返回 NULL(静默语义漂移),SPI 不拒(过罕见);组级会话状态门只覆盖 SET 语句形态 | 已知口径(spec lockfree-dml.md §13.8),非缺陷;演练不使用该形态 |
| 窗口崩溃时机(4.1/4.6) | intent 窗口=毫秒~秒级,kill 时机需多试;4.1b 失联死判(≈380s)是稳定替代路径;4.6 为尽力而为项 | 记录实际采用的构造方式:______ |
| 首批取证范围 | 改写全文仅批 1 落 biz_log(续跑新任务不再落全文;逐批 lo/hi/affectRows 仍在) | 已知口径(spec §13.7) |
| 快照/漂移语义 | 游标越过后新插入的匹配行不被本单覆盖;并发写入落 (lo,hi] 的行被本批覆盖(谓词作用域语义) | 预期行为,预检黄旗+详情文案已明示;对账脚本避免并发写入期执行 |
| 终止后现场 | 终止=部分成功保留,无自动回滚;恢复靠 §9.8.1 备份纪律 | 演练前对 counter/big 表记录基线(COUNT/SUM 快照) |
| resubmit 边界拒绝留痕 | 修订重提(resubmit)路径的无键拒绝**无审计留痕**(submit/open-submit 已挂钩;工单已存在走事件链) | 已知债务(spec §13.1) |

## 附录 C · 测试记录表模板

| 章节# | 场景 | 预期 | 实际 | 通过 | 执行人 | 日期 | 备注 |
|---|---|---|---|---|---|---|---|
| 1.1 | 多组全执行 | | | ☐ | | | |
| 2.1 | MySQL UPDATE 主流程 | | | ☐ | | | |
| 2.3 | PG 主流程 | | | ☐ | | | |
| 3.3 | 断点续跑对账 | | | ☐ | | | |
| 4.1 | UNRESOLVED 检出 | | | ☐ | | | |
| 4.3/4.4 | RERUN/SKIP 裁决 | | | ☐ | | | |
| 5.1~5.3 | 拒绝+告警+审计 | | | ☐ | | | |
| 6.1 | PRE 混合三段 | | | ☐ | | | |
| 7.5 | 终止部分成功 | | | ☐ | | | |
| 9.3/9.4 | 双区块隔离 | | | ☐ | | | |
| ... | | | | | | | |
