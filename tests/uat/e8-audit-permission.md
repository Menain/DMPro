# E8 审计与权限管理 · UAT 用例(C9 M2)

> 三分类中本文件承载**②UAT 手工用例**(README §4);三迁移首启=③MIG-6~8、详情端点真库实测=③IS-D3-7③、清理手册执行=③IS-D3-7⑤(用户执行)、retention 数值落定=③IS-D3-14;本域用例引用不复制。
> 证据字段全部留位(红线 3);五元组格式=README §5;**凭据纪律(本域最关键)**:审计验证只写口径(行 id/时间窗/类型/键名),log_info 值经详情端点查看时敏感键必为 `******`;证据文件/截图不含任何明文凭据(存量明文行核对以「是否被掩」为结论,不摘录原文)。
> 契约权威:`.trellis/spec/backend/logging-guidelines.md`(C1 净化/C8 四列+channel 推断+详情端点+retention 三节);矩阵 C8 行「证据双源」不适用(C8 归档 prd 有 check 签署)。

## Purpose

验证 C8「可检索审计链」的 UI/API 面(四列检索/详情端点鉴权+租户隔离+读侧掩码/retention KV/导出 15 列/白名单下拉)、C10 ROLLBACK_MATERIAL 审计行形态、五角色权限矩阵(硬门槛 F-6)与 GOV_*_OVERRIDE 双标签拒绝面。

## Scope

- 页面与路由:`views/system/OperationLog.vue`(操作审计)+`SqlLog.vue`(SQL 审计)+`management/ManagementLogsLayout.vue`(`/manager/logs{,/sql}`;C8 详情弹窗改接新端点、logView.js 死文件已删);权限管理=`views/system/role/`、`permGroup/`、`subaccount/`(含 auth/authDm+BatchAuthorizationPage)、`dbPair/`、`schemaPatrol/`(survey E8);
- 后端:`RdpOpAuditController`(`/api/entry/audit/{queryAll,queryByUser,queryByUserName,queryListCondition,detail(GET,C8 新端点),export}`)、`SqlAuditController`、`RdpRoleController`/`RdpUserController`/`RdpUserManagerController`/`RdpResAuthController`/`PermissionGroupController`/`DmResAuthController`/`RdpUserConfigController`(KV 白名单通道)/`GovSchemaPatrolController`/`DbPairController`;
- 权限点:RDP_OP_AUDIT_READ/EXPORT(EXPORT include READ,tag=DBA)、RDP_ROLE_READ/MANAGE、RDP_USER_READ/MANAGE、RDP_AUTH_READ/MANAGE、RDP_PERM_GROUP_MANAGE、DM_SQL_AUDIT;GOV_SCHEMA_GATE_OVERRIDE(order=6)/GOV_ROUTE_OVERRIDE(order=7)双标签;
- KV:opAuditRetentionDays(默认 365,clamp 30–3650)/bizLogRetentionDays(默认 90,clamp 7–3650);≤0=禁用(永久保留逃生门);
- 不覆盖:各域审计行的业务语境(→e1/e2/e4/e5 产生、本域检索)、清理手册步骤(→IS-D3-7⑤)。

## 矩阵对应(②类承载对账)

| 矩阵行 | 本域承载 |
|---|---|
| C8 行「②UAT(e8 详情弹窗/导出 15 列/过滤下拉)」+R5/R6(矩阵 §3.8) | E8-01~E8-08 |
| C8 R2(retention ①+③) | E8-09(配置面②;数值落定=IS-D3-14③) |
| C8 R7(清理手册③) | E8-10(执行留位索引) |
| C10 R6(ROLLBACK_MATERIAL ①+②) | E8-11 |
| C1 R1/R3(审计净化②) | e1 承载,本域 E8-04 读侧兜底交叉 |
| C4 R4/C7 R8(双标签显隐接线) | E8-13(e4 E4-08/13 双登记) |
| 硬门槛 F-5(审计检索维度)/F-6(五角色)/P0-7(日志审计不含凭据) | E8-16/E8-12/E8-04 |
| O-C4-3 关闭(PURE_URL 下拉) | E8-07 |
| O-C8-3(导出下游兼容) | E8-08(联调确认素材) |

既有前端流程文档(引用不重写):tests/frontend/system/management_logs.md、account_authorization.md。

## Preconditions

- MIG-6/7/8 迁移已首启(三索引/四列/生成列唯一,→integration-scenarios §4);测试库审计表有存量数据(含 C8 前行=四列 NULL、含 C1 前存量明文行如可得);
- 账号:安全审计员(RDP_OP_AUDIT_READ/EXPORT)、平台管理员(root 或全标签)、DBA 甲/乙(单 override 标签,e4 同款)、开发(最小标签)、发布确认人(RDP_WORKER_ORDER_EXECUTE 系)——五角色矩阵(E8-12)与拒绝面用;
- 各域审计行样本已由 e1/e2/e4/e5 用例产生(执行顺序建议:本域排各域之后)。

## Test Data

| 编号 | 数据说明 | 构造方式 | 唯一标识 | 清理方式 |
|---|---|---|---|---|
| E8-D01 | 治理族审计行样本(GHOST_EXECUTE/DML_BATCH_EXECUTE/override GRANT/REVOKE/CONSUME/SENSITIVE_*/ROLLBACK_MATERIAL/EXTERNAL_COMMAND_REJECTED/DML_BATCH_REJECT) | 由 e1~e5/e9 用例产生 | 行 id 记录 | 审计只增不删(清理走手册) |
| E8-D02 | 跨租户对照行(租户乙产生) | 双租户账号(手册 0A.3 同型) | 行 id | 同上 |
| E8-D03 | retention 试验窗口(短保留值观察清理腿) | KV 临时改小值 | — | 恢复默认 |

## Cases

### 一、C8 检索与详情面

#### E8-01 审计详情弹窗改接新端点(「查看」面复活)

- 出处:C8 R6(`a36cea1f`;GET /api/entry/audit/detail 替代从未实现的 `/api/entry/logview/grep/operationlog` 死 URL——开源基线以来「查看」弹窗首次可用);logging-guidelines「Audit detail endpoint」节;矩阵 §3.8 R6;management_logs.md(既有流程交叉)。
- Purpose:审计页「查看」按钮打开详情弹窗,内容=log_info 经 sanitization+字典翻译(`audit_detail_label_<key>`,缺键显示 raw key);isExistsLog 扩宽=任何非空 log_info 行可查(治理行的 reason/前后值 UI 可见=裁决 3 目的)。
- Steps:1)打开 `/manager/logs`,对治理族行(E8-D01)点「查看」;2)弹窗内容核对:字典翻译键显示中文标签、无 raw JSON 键名裸露(缺字典键→raw key 如实);3)对 pre-C8 无 log_info 行→「查看」不可用(空 body 语义);4)Network 面板核对请求=`/api/entry/audit/detail?id=`(死 URL 零请求)。
- Expected:弹窗可用+翻译+空行不可查;按 management_logs.md 既有纪律补文档更新(浏览器验证后更新流程文档,手册 §8 同款纪律)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E8-02 详情端点鉴权拒绝面

- 出处:C8 R6(@RequestAuth(RDP_OP_AUDIT_READ));prod-verification 增量 N6;IS-D3-7③(③真库实测);矩阵 P0-7/F-5。
- Purpose:无 RDP_OP_AUDIT_READ 标签账号调详情端点→服务端拒绝(前端「查看」入口对其不可见≠安全边界)。
- Steps:1)开发角色账号(无审计标签)API 直调 detail(带自身会话)→拒绝;2)列表页对该账号整体不可见(路由/菜单层)核对;3)持 READ 无 EXPORT 账号→detail 可用+导出拒绝(标签分级:EXPORT include READ 的反向不成立)。
- Expected:三层(菜单/列表/端点)拒绝语义一致;错误不泄漏行存在性。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E8-03 租户隔离 not-found 语义

- 出处:C8 R6(queryAuditDetail 租户隔离 by owner_uid:跨租户/缺失读作 not-found,never 403-leaks existence);logging-guidelines 详情端点节;IS-D3-7③。
- Purpose:持标签账号读跨租户行(E8-D02)或不存在 id→not-found 语义,不以 403 泄漏存在性。
- Steps:1)租户甲审计员读乙的行 id→not-found;2)读不存在 id→同 not-found(两种响应不可区分);3)读本租户行→正常。
- Expected:1/2 响应形态一致;3 正常;与 e9 开放面 not-found 口径一致(orchestration §4.3)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E8-04 读侧 sanitization 兜底(存量明文行第二保险)

- 出处:C8 裁决 3 双保险(清理手册用户执行可能滞后——端点读侧兜底);C1 双层敏感键判定(6 精确键+键名模式兜底);logging-guidelines 详情端点节+「Known leftovers」;e1 E1-03 双登记(数据源语境)。
- Purpose:pre-C1 存量行如仍含明文凭据,详情读出时敏感键值已 `******`;不可解析/截断 log_info 原样返回不抛错(sanitizeLogInfoJson never-throws)。
- Steps:1)定位存量 UPDATE_DATA_SOURCE_CONFIG/用户管理类旧行(口径:时间<C1 部署时点);2)详情读取→敏感键掩码核对(结论记录「已掩/无敏感键」,不摘录值);3)构造截断行样本(65535 截断不可解析)→原样返回;4)IS-D3-7⑤ 清理执行后复查:全表口径 grep 明文模式零命中(清理手册零凭据口径)。
- Expected:兜底掩码生效;截断面不抛错;清理后零明文(⑤证据互引)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E8-05 链四列 UI 面(channel/ticket/release/fingerprint)

- 出处:C8 R3/R4(四列+写点矩阵);logging-guidelines「Audit-chain columns」节(channel 推断三臂:/open/ 或 /api/open→OPENAPI、/system/→SYSTEM_AUTO、其余非空→CONSOLE、空→null 永不假默认);OperationLog.vue(channel 列 i18n/ticketId/releaseId 链接/fingerprint 前 12 位+复制,实测 L298-369)。
- Purpose:列表四新列显示语义:channel 三值中文映射+推断正确(人工 grant=CONSOLE/消费派发=SYSTEM_AUTO/开放命令=OPENAPI);「工单 <id>」「发布 <id>」链接跳转;fingerprint 截断显示+点击复制全值;pre-C8 存量行四列 NULL→显示面如实空(不显示假值)。
- Steps:1)E8-D01 各族行逐列核对(预期 channel 按写点矩阵:GHOST_EXECUTE=SYSTEM_AUTO、override GRANT=CONSOLE、SENSITIVE_ORIGINAL_VIEW 编辑器落点=CONSOLE/分析链=SYSTEM_AUTO、DML_BATCH_REJECT=到达通道真实[console 提交→CONSOLE、开放调用→OPENAPI]);2)链接双向跳转(→e3/e4 详情);3)fingerprint 复制值与 `dm_exec_sql_audit.sql_fingerprint` 等值 join 口径核对(GovSqlHashUtils 同契约,值不回显仅等值比较);4)存量 NULL 行显示。
- Expected:写点矩阵逐行对上;链接可达;join 等值;NULL 如实。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E8-06 过滤面四新谓词组合

- 出处:C8 R4(operationAuditCondition 四可选谓词;全空=渲染 SQL 与 pre-C8 逐字节同形,BoundSql-pinned=①不重复);QueryOpAuditFO(sqlFingerprint 字段实存);矩阵 F-5。
- Purpose:审计页过滤区新增 channel/ticketId/releaseId/sqlFingerprint 四过滤(与既有用户/类型/资源/时间组合);过滤结果与列值一致;全空过滤=旧行为(列表形态零变化)。
- Steps:1)按 channel 三值各过滤一次;2)按 ticketId/releaseId 过滤(E8-D01 样本)→命中集与链接跳转互证;3)粘贴 fingerprint 过滤→精确命中;4)全清空→列表与 pre-C8 列形态一致(既有 10 列顺序不变);5)组合过滤(channel+时间窗)。
- Expected:四谓词可用可组合;空=旧形态;无过滤态泄漏(跨租户行永不出现在任何过滤结果)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E8-07 资源类型下拉白名单(PURE_URL+UPDATE_ACCOUNT_USER_NAME,O-C4-3 关闭)

- 出处:C8 R5(O-C4-3 关闭:PURE_URL 入审计 UI resourceTypes 下拉白名单);矩阵 §3.8 R5「下拉抽查」;O-C4-3(survey D1 已闭 2026-10-14)。
- Purpose:治理族行的资源类型(PURE_URL——override/ROLLBACK_MATERIAL 等 PURE_URL 式行)在过滤下拉可选可见,不再「有行无法过滤」。
- Steps:1)过滤下拉展开→PURE_URL、UPDATE_ACCOUNT_USER_NAME 在列;2)选 PURE_URL→命中 override/ROLLBACK_MATERIAL 族行(resourceName=`ticket:<id>` bypass 形态如实显示);3)下拉全集与 init() 白名单对账(口径:下拉项数=白名单注册数)。
- Expected:白名单对账一致;PURE_URL 行可过滤可显示。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E8-08 导出 15 列(既有 10 列顺序不变+5 列追加;log_info 不导出)

- 出处:C8 R4/logging-guidelines「export appends the four columns + operation_uri AFTER the existing ten(order untouched——unknown downstream scripts keep their positions,O-C8-3);log_info deliberately stays OUT」;exportColumns() 实测(10+5=15 列,LinkedHashMap 有序);矩阵 §3.8/O-C8-3「联调确认」。
- Purpose:导出文件列序=既有 10 列(用户名/uid/时间/资源类型/审计类型/资源值/来源IP/日志路径workerIP/安全级别/uuid)原序+追加 channel/ticket_id/release_id/sql_fingerprint/operation_uri 五列;log_info 绝不出现在导出(导出文件不得成为敏感数据面);EXPORT 标签门控。
- Steps:1)审计员导出(小时间窗);2)表头列序核对(15 列,前 10 列与旧导出模板对位);3)文件 grep log_info 特征键零命中;4)无 EXPORT 标签账号导出→拒绝(E8-02 步骤 3 交叉);5)O-C8-3 登记:向下游脚本持有方确认新列追加兼容(联调确认素材,结论回流观察项)。
- Expected:15 列序正确;log_info 零出现;拒绝面正确;O-C8-3 确认动作有记录。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

### 二、retention 与清理

#### E8-09 retention KV 配置面与清理腿观察

- 出处:C8 R2(opAuditRetentionDays 默认 365 clamp 30–3650/bizLogRetentionDays 默认 90 clamp 7–3650;≤0 禁用=试点前逃生门;30min 三独立腿 LIMIT-500 分批;一表失败不影响另一表不杀周期任务;D-3 缺 user_conf 行 warn+skip);logging-guidelines retention 节;IS-D3-14(数值试点前定,③)。
- Purpose:KV 配置面(root userConfig 白名单通道)可读写+clamp 校验;清理腿行为观察(短窗口试验:改小值→30min 周期后过期行删除、删除计数 log.info、未过期行保留;≤0→腿禁用零删除)。
- Steps:1)root 配置页改 opAuditRetentionDays=30(记录原值)→保存→重启后生效面(或热生效口径按 KV 通道实况记录);2)构造过期样本行(测试库口径:operate_date 早于窗口)→等待清理周期→行删除+应用日志计数行;3)bizLogRetentionDays 同型(平台全局腿+多租户最保守解析口径:任一禁用票→禁用);4)改 0→禁用验证(零删除);5)恢复默认;6)数值落定动作→引 IS-D3-14(试点前用户签字,不预填)。
- Expected:clamp 边界(29/3651 拒绝或钳制按实现口径记录);三腿独立;禁用生效;D-3 守卫(删 user_conf 行样本→warn+skip 不瘫调度,如可安全构造)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E8-10 清理手册执行留位(IS-D3-7⑤ 索引,用户执行)

- 出处:C8 R7(③;`tests/governance/audit-legacy-cleanup.md`:前置确认/量级盘点/清空双方案/应用日志侧/轮转清单口径/执行记录表/override 唯一约束前置检查 §7);O-C1-2(已闭,关闭动作=本项执行);IS-D3-7⑤;矩阵 C1 行「历史存量清理」。
- Purpose:登记执行路由——存量明文凭据清理**仅测试库**(裁决 1「测试库直接清空」)由用户按手册执行,执行记录表留联调档案;轮转清单(曾入审计的凭据即使清理也按已泄漏处置轮转)口径向用户明示。
- Steps:执行=手册全节(用户);本域核对:1)执行后 E8-04 步骤 4 复查零明文;2)执行记录表归档路径登记;3)MIG-8 前置检查(§7 口径:两 override 表生成列唯一约束在位)完成;4)轮转清单处置结论(哪些凭据已轮转)登记——**清单本身不含凭据值**。
- Expected:清理闭环(O-C1-2 关闭动作完成);记录留档;复查零明文。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

### 三、C10 审计行与角色矩阵

#### E8-11 ROLLBACK_MATERIAL 审计行形态(obj 恰 4 字段/channel 推断/可 join)

- 出处:C10 R6(governance-contracts「Audit ROLLBACK_MATERIAL」条目:NORMAL 级四点注册+PURE_URL bypass resourceName=`ticket:<id>`(刻意无 getResourceName case,C4/C7/C8 先例)+label 三语「回滚材料变更/Rollback Material Change」;obj={source,recoveryType,fingerprint,summary≤500} **恰四字段**(reflection-pinned,绝无材料全文);三材料变更写点落行、材料未变不落(分支 3 双不写);channel=写点 URI 推断(console submit/resubmit+legacy create→CONSOLE;open resubmit→OPENAPI);已知口径限制 O-C10-5:openapi submit 共享入口→行 channel=CONSOLE);e3 E3-09 交叉。
- Purpose:提交/重提工单产生的 ROLLBACK_MATERIAL 行五要素核对:类型标签中文「回滚材料变更」、resourceName=`ticket:<id>`、obj 四字段(summary≤500 截断)、fingerprint 与治理链同口径(可与 stmt hash join)、channel 按写点。
- Steps:1)e3 E3-09 样本单提交后定位审计行;2)详情读 obj 四字段(source=GENERATED/USER/REGENERATED 语境正确);3)fingerprint 等值 join 口径核对;4)resubmit「留空∧原手填」分支→**无新行**(无变更无留痕);5)开放面 resubmit(e9 交叉)→channel=OPENAPI;开放 submit→CONSOLE(O-C10-5 已知限制,登记非缺陷);6)下拉过滤「回滚材料变更」类型可检索。
- Expected:六面符合契约;obj 绝无全文(C8 obj 纪律);O-C10-5 现状如实。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E8-12 五角色矩阵(计划 L223,硬门槛 F-6)

- 出处:计划文档 §7 L223(角色验收覆盖开发/DBA 审核人/发布确认人/安全审计员/平台管理员;无权限入口均由服务端拒绝);矩阵 F-6 行;prod-verification Preconditions 6(权限标签分配)。
- Purpose:五角色对九域关键入口的「可见性×服务端拒绝」矩阵抽查(前端隐藏不代替后端拒绝=计划 §6 访问权限域纪律):开发=提单/查询(无审计/override/发布确认)、DBA 审核人=审计读+override(按标签细分甲/乙)、发布确认人=确认执行(无提单面则如实体)、安全审计员=审计读/导出(无业务操作)、平台管理员=root KV/用户/角色管理。
- Steps:1)按角色逐一登录,记录菜单/入口可见性矩阵(九域各一入口抽查);2)每角色对**不可见入口**API 直调一次→服务端拒绝(403/权限错误,非 not-found 者核对是否泄漏存在性);3)越权组合抽查:开发直调 overrideGrant/audit detail/export、审计员直调 submit、确认人直调清理 KV;4)权限缓存刷新面(改标签后生效时延记录,计划 §6「权限缓存刷新」场景)。
- Expected:矩阵每格=「不可见+直调拒绝」双成立;拒绝语义不泄漏;结果汇总表回填矩阵 F-6 行证据。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E8-13 GOV_*_OVERRIDE 双标签显隐拒绝面(权限面归属)

- 出处:C4 R4(GOV_SCHEMA_GATE_OVERRIDE order=6 tag=DBA+i18n 三文件+实际接线)/C7 R8(GOV_ROUTE_OVERRIDE order=7 tag=DBA 完全隔离)/C8 M5.3(活性唯一 DB 兜底);prod-verification 增量 N3;e4 E4-08/E4-13 双登记(发布链语境);矩阵 F-6。
- Purpose:权限管理面(role 标签配置)视角:两标签在 DBA 角色标签清单可见可配(order 6/7 相邻、i18n 三语)、单独授予/回收即时生效于 e4 入口显隐;服务端拒绝面执行=e4 E4-13(本域登记权限配置通路)。
- Steps:1)角色管理页 DBA 角色标签清单核对两标签(名称/描述 zh/en);2)给甲授 gate 标签、乙授 route 标签(单独);3)e4 E4-02/08 显隐矩阵执行(互引证据);4)回收标签→入口即时消失+API 直调仍拒;5)i18n 三文件对账(缺 en 条目=启动 log.error 噪声,database-guidelines Gotcha)。
- Expected:配置通路+即时生效+回收面正确;两标签互不隐含(授一不得一)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

### 四、SQL 审计与既有流程

#### E8-14 SQL 审计页冒烟(指纹列写入面)

- 出处:C8 R3(dm_exec_sql_audit 指纹列;logging-guidelines:sql_audit fingerprint 在 prepareAudit 全文盖印,pre-65535-truncation);SqlLog.vue(`/manager/logs/sql`);DM_SQL_AUDIT 标签。
- Purpose:SQL 审计页正常检索;新行 sql_fingerprint 非空(全文口径)、与 op_audit 同 SQL 的行等值 join 可行(E8-05 步骤 3 互证);存量行 NULL 如实。
- Steps:1)执行一条工作台查询→SQL 审计新行;2)指纹列显示/过滤(如有 UI 面)或口径查询核对非空;3)与 E2 掩码查询样本交叉:审计行不含原值(截断/掩码语境按既有契约,值不回显);4)无 DM_SQL_AUDIT 账号→页不可见+直调拒绝。
- Expected:指纹写入+join 等值+权限面正确。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E8-15 管理日志/账号授权既有流程复测

- 出处:tests/frontend/system/management_logs.md+account_authorization.md(既有流程文档);README §7「前端流程」域映射。
- Purpose:C8 改动(详情弹窗/新列/新过滤/导出)叠加后,两篇既有流程零回归;列宽套件(system 页)交叉 IS-D3-12。
- Steps:按两篇文档套件执行;附加:新列不破坏既有表格布局(列宽拖拽可用→table_column_resize.md 交叉)。
- Expected:两篇通过;新旧面共存。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E8-16 F-5 检索维度对账(硬门槛素材)

- 出处:硬门槛 F-5(计划 L222:审计可按用户/环境/实例/工单/revision/release/审批 flow/SQL 指纹检索;可导出;保留策略合规);矩阵 F-5 行(载体=e8 用例+IS-D3-7)。
- Purpose:对 F-5 列举的 **8 个检索维度**(用户/环境/实例/工单/revision/release/审批 flow/SQL 指纹,计划 L222 原文枚举;check 修正 2026-10-15:原写 9)逐一给出「可达方式」对账:直接过滤(用户=既有;工单/release/指纹=C8 新列)或一跳可达(revision/审批 flow:经 ticket_id 跳工单详情→revision/事件时间线;环境/实例:经资源值或工单详情一跳)——如实记录「直接/一跳」口径,不虚报全部直接可过滤。
- Steps:1)八维度逐一操作并记录可达路径;2)一跳路径的操作性验证(从审计行到工单/发布详情≤2 次点击);3)导出文件对八维度的离线检索可用性(15 列含 4 链列);4)保留策略合规结论(数值=IS-D3-14 签字后回填)。
- Expected:八维度全可达(直接/一跳分布按实测口径记录);可导出+保留策略两项另行核对(F-5 完整句);对账表回填矩阵 F-5 行;缺口(如有)回流观察项。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

## Cleanup

1. E8-D03 retention KV 恢复默认(365/90);
2. 五角色测试账号临时标签回收(角色配置恢复);
3. 导出验证文件删除(受控目录,grep 后即删);
4. 审计行只增不删——存量清理仅走 IS-D3-7⑤(手册,用户执行)。

## Skip Conditions

- 存量明文行不可得(测试库已清理)→E8-04 步骤 1/2 SKIP(兜底面以构造样本替代:手工插入含敏感键名的测试行——**值用假值**,仅验证掩码机制);
- 清理周期(30min)等待不可行→E8-09 步骤 2 观察窗缩短为「触发口径查询+日志计数行」并记录;
- 五角色账号不足→E8-12 降级为可得角色子集执行并记录缺口(F-6 证据如实标注覆盖范围);
- 未执行、失败或环境阻塞的场景不得标记为通过(TEMPLATE.md 纪律)。
