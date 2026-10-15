# E1 数据源管理 · UAT 用例(C9 M2)

> 三分类中本文件承载**②UAT 手工用例**(边界见 README §4);①自动化计数引用见 `traceability-matrix.md`(下称「矩阵」);③联调场景见 `integration-scenarios.md`(IS-*)。
> 证据字段全部留位,统一联调执行时回填(红线 3);五元组格式=README §5。
> 凭据纪律(README §8):**本域是凭据敏感面重灾区**——所有步骤/证据只写口径(审计行 id、时间窗、键名),绝不回显密码/token/AK-SK 值;截图必须先核对无凭据可见。

## Purpose

验证 C1「数据源凭据审计净化」在真实 UI/审计出口的一致性(矩阵 C1 行:G6 updateDs 密码入审计),以及数据源管理面在 C 波次后的功能回归(业务链路用原 FO、审计用净化副本,功能零变化=C1 R5)。

## Scope

- 页面与路由:`views/dataSource/DataSource.vue`+`AddDataSource.vue`(`/datasource`)、`views/system/env.vue`(`/env`,环境参数)、`views/sshConfig/`(SSH 隧道)(survey E1);
- 后端:`DmDsController`(`/api/entry/datasource`,**C1 修复点 updateDs**)、`RdpUserManagerController.addSubAccount`(C1 第二泄漏点)、`DmEnvParamController`/`EnvController`/`SshConfigController`;
- 权限点:DM_DS_READ/DM_DS_MANAGE、RDP_DS_READ/RDP_DS_MANAGE、DM_DS_MAINTENANCE、DM_SSH_CHANNEL_READ/WRITE、RDP_ENV_READ/MANAGE;
- 不覆盖:审计详情端点鉴权/租户隔离(→ e8)、审计存量清理(→ IS-D3-7⑤ 用户执行)、数据源连通真库矩阵(→ tests/datasource/)。

## 矩阵对应(②类承载对账)

| 矩阵行 | 本域承载 |
|---|---|
| C1 行「②UAT(e1 审计详情掩码显示)」 | E1-01~E1-03、E1-05 |
| C1 R5(①+②:e1 数据源 CRUD 冒烟,矩阵 §3.1) | E1-06 |
| C1 R1~R4(①自动化,不重复手工验证) | 引用:e5ce2b6e,console 基线内普查 ~35 调用点 |
| 硬门槛 F-7(凭据轮换已演练,矩阵 §2.2) | E1-04 |
| 硬门槛 P0-7(日志与审计不含凭据) | E1-01/E1-02(出口面);全链面→IS-MASK-6 |
| 观察项 O-C1-1(addDs obj="" 可选对齐) | E1-08(现状核对,非缺陷) |

既有前端流程文档(**引用不重写**,tests/frontend/datasource/ 5 篇):cockroachdb_add.md / driver_default_selection.md / goldendb_datasource.md / instance_i18n.md / kingbasees_datasource.md。

## Preconditions

- 测试环境后端+前端已部署当前基线(HEAD≥9a0e6352);元数据库可只读查询 `dm_mon_op_audit`(口径查询不回显 log_info 值);
- 账号:主账号(数据源管理权限)+ 一个可创建子账号的对照账号;
- 一个可安全改密/轮转的**测试数据源**(非生产凭据;轮转值经环境变量/密码管理器传递,不入任何记录);
- 审计页入口可用(`/manager/logs`,RDP_OP_AUDIT_READ)。

## Test Data

| 编号 | 数据说明 | 构造方式 | 唯一标识 | 清理方式 |
|---|---|---|---|---|
| E1-D01 | 测试数据源(MySQL 8.0 测试实例) | 页面新增,凭据只存于密码管理器 | `codex_ds_e1_<ts>` | 页面删除 |
| E1-D02 | 编辑操作(触发 updateDs 审计) | 修改 E1-D01 非凭据字段+凭据字段各一次 | 审计行按时间窗+resource_name 定位 | 无需清理(审计只增) |
| E1-D03 | 测试子账号(触发 addSubAccount 审计) | 页面创建,初始密码经安全通道设置 | `codex_sub_e1_<ts>` | 页面删除/禁用 |

## Cases

### E1-01 updateDs 审计行无明文凭据(DB 出口)

- 出处:C1 R1/R3(矩阵 §3.1);commit `e5ce2b6e`;spec `.trellis/spec/backend/logging-guidelines.md`「Operation audit objects must be credential-sanitized」节。
- Purpose:数据源编辑提交后,`dm_mon_op_audit` 新行 obj 中 6 个 isSecret 键(password/sslCaData/sslCaPassword/sslClientCertData/sslClientKeyData/sslClientKeyPassword)值为 `******`,非敏感字段(host/userName/端口等)原样保留(排障价值不牺牲)。
- Steps:1)编辑 E1-D01 修改凭据字段+非凭据字段各一,保存;2)按时间窗+资源名定位新审计行(口径:`SELECT id, audit_type, resource_name FROM dm_mon_op_audit WHERE audit_type='UPDATE_DATA_SOURCE_CONFIG' ORDER BY id DESC LIMIT 5;` 不回显 log_info);3)经审计详情弹窗查看 log_info(→ e8 E8-01 端点);4)对 log_info 全文 grep 本次设置的凭据值(在受控 shell 中以环境变量比对,值不落盘不入记录)。
- Expected:详情中敏感键=`******`;非敏感字段完整;grep 零命中;空凭据值不被假掩码(如实「未提供」)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

### E1-02 双出口同防(应用日志出口)

- 出处:C1 R3(两出口同源同串,check 结论 3);`e5ce2b6e`。
- Purpose:logOperation 序列化的第二出口——应用日志 `log.info` 明文行——同样只含净化后串。
- Steps:1)重复 E1-01 步骤 1;2)在后端应用日志中按时间窗检索该审计写点日志行(口径:grep 审计类型键,不 grep 凭据值本身);3)对命中行以环境变量比对凭据值。
- Expected:应用日志行与 DB log_info 同串(净化后);凭据值零命中。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

### E1-03 审计详情读侧兜底覆盖 pre-C1 存量行

- 出处:C8 裁决 3 双保险(`a36cea1f`);logging-guidelines「Audit detail endpoint」节;矩阵 C8 R6。
- Purpose:即使存量清理(IS-D3-7⑤)未执行/滞后,详情端点对 pre-C1 明文行的敏感键仍读侧掩码。
- Steps:1)定位一条 C1 之前的历史 UPDATE_DATA_SOURCE_CONFIG 审计行(如测试库存量存在;口径查询不回显);2)经 `GET /api/entry/audit/detail` / 审计页「查看」弹窗读取;3)敏感键值核对。
- Expected:存量明文字段被 `******`(读侧第二保险生效);不可解析/截断 log_info 原样返回不抛错。存量行不存在时本用例 SKIP(记录原因,替代验证=IS-D3-7⑤ 清理后复查零明文行)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

### E1-04 凭据轮转后全链回归(F-7 演练素材)

- 出处:硬门槛 F-7「数据源凭据轮换…已演练」(计划文档 L224;矩阵 §2.2);C1 R5(功能不变)。
- Purpose:轮转测试数据源密码后,业务链路(连接测试/工作台查询/工单预检)全部正常——审计净化不干扰真实凭据传递(业务调用仍用原 FO)。
- Steps:1)在目标库侧轮转 E1-D01 账号密码(值经安全通道);2)页面更新数据源密码;3)连接测试;4)SQL 工作台对该源执行只读查询;5)提交一个引用该源的 PRE 工单走到预检完成;6)复查步骤 2 产生的审计行(同 E1-01 口径)。
- Expected:2~5 全部成功(功能零变化);6 审计行仅含 `******` 与新凭据零泄漏;旧凭据在任何出口不再出现。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

### E1-05 addSubAccount 审计净化(第二泄漏点)

- 出处:C1 R4(~35 调用点普查中修复的第二处:`RdpUserManagerController.addSubAccount` 解密后明文入审计);`e5ce2b6e`。
- Purpose:创建子账号的审计行不含初始密码明文(修复前该 FO 的 password 已在审计调用前被就地解密)。
- Steps:1)创建 E1-D03 子账号;2)定位新增用户类审计行(口径查询);3)详情读取+环境变量比对。
- Expected:密码字段 `******` 或不出现;用户名等非敏感标识保留;grep 零命中。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

### E1-06 数据源 CRUD 冒烟(既有流程文档引用)

- 出处:C1 R5(①+②,矩阵 §3.1「e1 数据源 CRUD 冒烟」);tests/frontend/datasource/ 5 篇。
- Purpose:新增/编辑/删除/连接测试主链在 C 波次后零回归;不重写既有浏览器流程。
- Steps:1)按 `tests/frontend/datasource/driver_default_selection.md` 与 `instance_i18n.md` 执行核心套件(新增→默认驱动→i18n 展示);2)GoldenDB/KingbaseES/CockroachDB 三篇按环境具备条件选套(不具备则 SKIP 记录);3)删除 E1-D01 收尾。
- Expected:所选套件按其文档预期通过;删除后列表不再出现;无残留连接池报错。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

### E1-07 环境参数管理面冒烟(E2/E3/E7 前置依赖)

- 出处:survey E1(env.vue 入口);desensitization-rules.md §3(双键激活:sensitive_spec_id/check_spec_id);dingtalk_bridge.md §0.7(SQL_TICKET_INFO)。
- Purpose:`/env` 环境参数页可配置本验收集后续域依赖的键:SQL_TICKET_INFO(审批通道)、check_spec_id/sensitive_spec_id(脱敏双链)、GOV_ROLE(治理角色)——配置面本身可用且回显正确。
- Steps:1)打开环境参数页,核对上述键的编辑入口与当前值回显(值可截屏——均非凭据);2)修改 sensitive_spec_id 为一个合法 spec 再改回(为 e2 三态用例做通路验证);3)固定槽位 VO 之外新键不可见的已知限制核对(database-guidelines「Gotcha」:新键需扩 VO 才可见——现状登记,非缺陷)。
- Expected:键值读写正常;脱敏槽位显示 {openSec, invalid} 状态语义(悬空绑定 UI 警示,desensitization-rules §3)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

### E1-08 addDs 审计 obj="" 现状核对(O-C1-1 观察项)

- 出处:O-C1-1(矩阵口径注记/survey D1,开放:可选对齐 sanitizedDsConfigSubmit);logging-guidelines「Known leftovers」。
- Purpose:确认新增数据源(addDs)审计行 obj 为空串——安全(零凭据)但零排障信息;联调期登记现状,供后续决定是否对齐 updateDs 的净化副本形态。
- Steps:1)新增 E1-D01;2)定位 CREATE 类数据源审计行;3)详情读取。
- Expected:obj 空(现状=安全);无凭据泄漏;登记「是否需要对齐」评估结论到观察项处置记录(不改代码)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

## Cleanup

1. 删除 E1-D01/E1-D03 及本域创建的临时对象;恢复 E1-04 轮转的密码为测试基线值(值不入记录);
2. 恢复 E1-07 改动的环境参数原值;
3. 审计行只增不删——存量清理走 `tests/governance/audit-legacy-cleanup.md`(IS-D3-7⑤,用户执行)。

## Skip Conditions

- E1-03 依赖测试库存在 pre-C1 存量明文行;不存在→SKIP(替代=IS-D3-7⑤);
- E1-06 三方数据源篇(GoldenDB/KingbaseES/CockroachDB)缺驱动/缺实例→按各文档 Skip Conditions 处理;
- 未执行、失败或环境阻塞的场景不得标记为通过(TEMPLATE.md 纪律)。
