# E2 查询与脱敏 · UAT 用例(C9 M2)

> 三分类中本文件承载**②UAT 手工用例**(README §4);真库脱敏出口链=**③IS-MASK-6**(integration-scenarios §3,本域 E2-13 为其 UI 面展开索引);DANGLING/NOT_BOUND 真库落库=IS-D3-4/IS-D3-5。
> 证据字段全部留位(红线 3);五元组格式=README §5。
> 凭据纪律:测试表敏感标记串**本身非凭据、可 grep**(IS-MASK-6 同款口径);真实敏感数据不入测试表;截图/证据不含原值。
> 契约权威:`.trellis/spec/backend/desensitization-rules.md`(C6/C8 修订后口径)——本域用例预期结果以该 spec §3/§4 为准,不自行措辞。

## Purpose

验证 C6「脱敏 fail-closed」五类出口修复面在真实 UI 的行为(矩阵 C6 行:活泄漏堵住/未知算法兜底/三态绑定/豁免审计/错误净化,每类 ≥2 用例=F1 量级依据),以及 C3「估算未知不伪装 0」的查询侧诚实标注。C6 归档 prd 未回签(裁决 6)——本域用例即其②类验收承载,证据双源口径见矩阵 C6 行。

## Scope

- 页面与路由:`views/sql/index.vue`(SQL 工作台,`/sql`)、`views/security/`(脱敏规则与策略,`/system/dmspec*`、`/data-access/rules*`)、数据编辑器(DataEditorController 消费面)(survey E2);
- 后端:`QueryEditorController`(`/api/entry/query`,C6 M1 resultDataRead 修复面)、`DataEditorController`(`/editor/data`,C6 M3 gate+M5 豁免审计)、`DmSecRulesController`/`DmSecRangeController`;引擎面 `ResultSetRServiceProvider`/`SenAlgorithm`(plus-sec-rules);
- 权限点:DM_QUERY_CONSOLE/DM_QUERY_EXPORT、DM_SECRULES_READ/MANAGE;豁免=DM_SENSITIVE 数据权限标签(+root 全豁免);
- 六类执行出口(spec §1):①结果集构建 ②分页读取 ③④⑤导出三 convert(SQL/Excel/JSON) ⑥截断单元格全文读取(resultDataRead);`downloadResult` 二进制直读=设计上不掩码(低风险已评估,spec §1);
- 不覆盖:真库全出口链(→IS-MASK-6)、AutoExec DANGLING 打包拒绝(→IS-D3-4)、biz_log 落库实证(→IS-D3-5)、工单侧行数标注(→e3)。

## 矩阵对应(②类承载对账)

| 矩阵行 | 本域承载 |
|---|---|
| C6 行「②UAT(e2 五类出口每类 ≥2)」 | M1→E2-01/02;M2→E2-03/04;M3→E2-05/06/07;M5→E2-08/09/10;M6→E2-11;M7→E2-12 |
| C6 R1(①+②)/R4(①+②)/R5(①+②),矩阵 §3.6 | 同上 |
| C6 行「③真库场景 6=IS-MASK-6」 | E2-13/14/15(UI 面展开,执行与 IS-MASK-6 同轮) |
| C3 R4/R5(②前端提示+公告口径,矩阵 §3.3) | E2-16/E2-17(查询侧);工单侧→e3 E3-01~03 |
| 硬门槛 P0-7(未授权身份全出口无原值) | E2-13 全出口矩阵;证据汇入矩阵 P0-7 行 |
| ①自动化(不重复手工) | console 1697 时点+plus-sec-rules 49(SenAlgorithmHalfMaskTest 26/SecRulesServiceImplTest/DbErrorSanitizerTest 17);`a9599a46` |

既有前端流程文档(引用不重写):tests/frontend/sql/ 8 篇(结果网格/导出下载/右键菜单/语句选择等)+tests/frontend/security/query_rule_violation_message.md。

## Preconditions

- 测试环境部署 C6+C8 代码(HEAD≥a36cea1f);MySQL 8.0 测试实例;
- 脱敏配置面:SENSITIVE 规则集(spec rule_kind=SENSITIVE)+内置规则 006(china-phone-half,`HALF_MASK(4,7)`)/004(全掩码)+Column range;环境经 `sensitive_spec_id` 绑定(→e1 E1-07 通路);
- 账号三件套:未授权查询账号(DM_QUERY_CONSOLE,无 DM_SENSITIVE)/豁免账号(+DM_SENSITIVE 标签)/root;
- 测试表含**全库唯一敏感标记串**记录(IS-MASK-6 前置同款;标记串口径如 `codex_mask_<ts>_uniq`,非凭据可 grep)。

## Test Data

| 编号 | 数据说明 | 构造方式 | 唯一标识 | 清理方式 |
|---|---|---|---|---|
| E2-D01 | 掩码测试表(含电话列+唯一标记串) | 测试库建表插入 | `codex_mask_<ts>` | DROP TABLE |
| E2-D02 | typo 算法规则(脚本返回 `algorithm::HALF_MSK(2,5)`) | 脱敏规则页新建自定义脚本规则 | `codex_rule_typo_<ts>` | 页面删除 |
| E2-D03 | 悬空绑定环境(spec 删除后键仍有值) | 绑定→删 spec | 环境 E2-ENV-DANGLE | 恢复绑定 |
| E2-D04 | 无绑定环境(双键均空) | 清空两键(记录原值) | 环境 E2-ENV-NOBOUND | 恢复原值 |
| E2-D05 | 非法 HALF_MASK 参数规则(如 `HALF_MASK(9,2)` 倒置) | 同 E2-D02 | `codex_rule_bad_<ts>` | 页面删除 |

## Cases

### 一、C6 M1 截断展开出口(resultDataRead,≥2)

#### E2-01 掩码列截断单元格展开显示掩码全文

- 出处:C6 R1/M1(`a9599a46`);desensitization-rules §1 出口⑥+§4「truncated cell expanded」行。
- Purpose:结果缓存文件存原值(finishRecord 先于 doMask 落盘),单值读取必须走同一 doMask——修复前此处为活泄漏。
- Steps:1)未授权账号在工作台查询 E2-D01(列值超长触发截断);2)点击截断单元格展开全文;3)分页翻页后重复展开(出口②交叉)。
- Expected:展开内容=**掩码后全文**(非原值截断补全);分页/展开两处一致;唯一标记串原值零出现。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E2-02 豁免查询的截断展开保持原值语义(结构性保证)

- 出处:desensitization-rules §1(豁免查询文件 `usingValueProcess=false` by construction);C6 R1。
- Purpose:豁免账号的展开读取按构造保持原值(不误伤),且该次查看留痕(→E2-08)。
- Steps:1)豁免账号同查询;2)展开截断单元格;3)核对 SENSITIVE_ORIGINAL_VIEW 审计行产生(→e8 E8-11 同族口径)。
- Expected:原值可见(豁免语义);审计留痕;未授权账号对照(E2-01)仍全掩。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

### 二、C6 M2 未知算法 fail-closed(≥2)

#### E2-03 脚本 typo 命令 → FULL_MASK 兜底,查询不失败

- 出处:C6 R2/M2;desensitization-rules §2(chooseAlgorithm 归一化)+§4「script returns unknown command」行+§5 Base case。
- Purpose:规则脚本返回未知命令(如 `HALF_MSK` typo)时整列 `******`+warn 日志——pre-C6 行为是 IllegalStateException 整查询失败,更早版本泄漏原值;修复后过度掩码、绝不泄漏、绝不崩查询。
- Steps:1)绑定 E2-D02 规则到测试环境 spec;2)未授权账号查询命中列;3)查后端日志 warn(口径:grep 算法归一化关键字,不含数据值)。
- Expected:该列全 `******`;查询正常返回其余列;warn 日志存在;无异常弹窗。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E2-04 HALF_MASK 参数非法 → fail-closed 全掩

- 出处:desensitization-rules §3「Fail-closed」(start>end/越界/NumberFormatException→doFullMaskAlgorithm)+§4「args inverted/short value」行。
- Purpose:参数倒置/超出值长/短值等坏配置一律过度掩码。
- Steps:1)绑定 E2-D05(倒置参数);2)查询命中列;3)换短于 end 的值行复测;4)(可选)start=end 与 end=length 边界行核对半掩码 1-based 闭区间语义(`138****5678` 形态,spec §3)。
- Expected:非法参数→`******`;合法边界→按 `[start,end]` 1-based 闭区间半掩;任何分支无原值。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

### 三、C6 M3 三态绑定(≥2)

#### E2-05 NOT_BOUND:双键均空 → 放行+观测(不阻断)

- 出处:C6 R3/M3+M4;desensitization-rules §3 tri-state+§4 首行;矩阵 C6 R3「NOT_BOUND 放行+biz_log 观测」;真库落库实证=IS-D3-5(③,本用例只做 UI 面)。
- Purpose:从未绑定=显式 opt-out——查询正常返回未掩码结果,但不再全静默:每 dsId 每天一行 MASKING_NOT_BOUND 观测(风暴保护限频口径;不限频的每查询一行留痕面是 root 豁免 biz_log,见 E2-09;NOT_BOUND 落库依赖 C8 D-1 修复后首次可验证,IS-D3-5)。
- Steps:1)E2-D04 环境查询 E2-D01;2)查询成功、结果未掩码(未授权账号亦如此——opt-in 激活契约,B3 全量拒绝被裁决否决);3)观测行落库核对→引 IS-D3-5 步骤(口径查询不回显值)。
- Expected:查询放行;观测行存在(执行时点=C8 修复后首次可落库);排障口径=先查绑定再查规则(spec §7 Correct ops)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E2-06 DANGLING:悬空绑定 → 控制台分析时拒绝

- 出处:C6 R3/M3(B2 裁决「未知策略≠显式退出」);desensitization-rules §4 第二行+§7「Correct (ops, since C6)」;IS-D3-4(AutoExec 面行为变更,③)。
- Purpose:键有值但 spec 被删/禁用/非数字且无 legacy 回救 → 查询在 console 分析链被拒(i18n `MASKING_BINDING_DANGLING_ERROR`),绝不静默供给明文;每次尝试留 SENSITIVE_MASKING_REJECTED 审计行。
- Steps:1)构造 E2-D03;2)未授权账号查询→观察拒绝提示文案;3)数据编辑器路径同查(共享 ensureMaskingBindingUsable gate)→同拒;4)审计行核对(→e8);5)环境管理页 UI 警示 {openSec, invalid} 回显;6)按 spec §7 正确运维路径修复绑定→查询恢复掩码。
- Expected:两路径均拒绝+文案一致;审计行每次尝试一条;root 不被 DANGLING 拒绝(既有全豁免保持);修复后 VALID 正常。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E2-07 VALID 边界:有效 spec 零启用 referer 不回退(显式意图)

- 出处:desensitization-rules §3(收窄回退:仅 unbound/missing/disabled 触发 legacy 回退;valid-spec-zero-referers 恒 VALID=pinned 裁决)+§4 第三行。
- Purpose:「新键绑定有效但零 referer」=用户显式意图,不掩码且不回退 legacy 混合 spec、不判 DANGLING。
- Steps:1)绑定一个启用但无启用 referer 的 SENSITIVE spec;2)查询→未掩码放行;3)确认 legacy check_spec_id 的 SENSITIVE referers 未被回退使用(对照:解绑新键→legacy 回退生效掩码)。
- Expected:两态行为分明(VALID 零 referer=不掩不回退;仅键空/spec 失效才回退);无拒绝、无观测行(非 NOT_BOUND 非 DANGLING)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

### 四、C6 M5 豁免审计分级(≥2)

#### E2-08 DM_DAUTH_SENSITIVE 豁免查看 → SENSITIVE_ORIGINAL_VIEW(HIGH)

- 出处:C6 R4/M5;desensitization-rules §3「Exemption audit tiers」+C8 增量(obj 加脱敏策略快照三键+channel 分落点);矩阵 C6 R4。
- Purpose:每次原值查看留痕可答「谁看过明文」;obj 含策略快照(maskingSpecId/Name/BindingState=查询时点解析值)回答「当时套用了哪个策略」;obj 绝无数据值/SQL/凭据。
- Steps:1)豁免账号经工作台查询命中列(analysis 链);2)经数据编辑器查看表级豁免面(editor 落点);3)审计页定位两类 SENSITIVE_ORIGINAL_VIEW 行(→e8 E8-05/06 检索面);4)详情读 obj:exemption marker/dsId/uid/queryId/resPaths≤20+count+策略快照三键;channel 落点核对(editor=CONSOLE、analysis 链=SYSTEM_AUTO)。
- Expected:两落点均 HIGH 留痕;obj 字段口径与 spec 一致;queryId 可一跳 `dm_exec_sql_audit`(该表含 sql_fingerprint,C8);审计失败不失败查询(never-throw)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E2-09 root 全豁免 → biz_log 观测(非 op_audit,风暴保护)

- 出处:desensitization-rules §3(root→MASKING_ROOT_EXEMPT biz_log,高频允许,never op_audit)+§4 末行;C8 红线 7(留痕裁决不动,增长答案=bizLogRetentionDays,→e8 E8-09)。
- Purpose:root 查询掩码环境数据源不掩码(既有裁决),留痕走 biz_log 每查询一行。
- Steps:1)root 对已绑定环境查询;2)结果未掩码;3)biz_log 口径查询 MASKING_ROOT_EXEMPT 行(不回显值);4)op_audit 确认**无**对应行(分层正确)。
- Expected:biz_log 留痕、op_audit 零行;高频查询不被限频(裁决保持)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E2-10 未授权尝试豁免通道 → SENSITIVE_MASKING_REJECTED 留痕

- 出处:C6 R4(MASKING_REJECTED 分级);IS-MASK-6 步骤 5(受控例外双侧留痕);矩阵 C6 行。
- Purpose:未授权身份走豁免/编辑器原值通道被拒且留痕(拒绝面也可审计)。
- Steps:1)未授权账号对命中列尝试数据编辑器原值读取路径;2)拒绝提示核对;3)SENSITIVE_MASKING_REJECTED 审计行定位(与 E2-06 DANGLING 拒绝同类型不同 reason 语境,核对 obj 语境字段)。
- Expected:拒绝+留痕;无原值出现在响应/日志/审计任何面。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

### 五、C6 M6 cursor 哈希化(≥1,与 e5 交叉)

#### E2-11 分批 statusMsg 只见 sha256 显示句柄

- 出处:C6 R5/M6(`DmlBatchLiterals.hashedCursorForDisplay`);lockfree-dml spec §3.2 步骤 5(C6/M6 注记)+§4.4(双侧 builder);矩阵 C6 R5。
- Purpose:用户可见面(前端 statusMsg/段 summary)游标=**`sha256:<8hex>…(len=N)`** 句柄;功能面(checkpoint 续跑/biz_log 取证/skips 证据)保原值——敏感键值不经分批进度泄漏。
- Steps:1)对含敏感列游标的表启动分批任务(构造与执行细节→e5 E5-05/lockfree_dml.md);2)前端观察 statusMsg 与段详情 summary;3)biz_log 取证行口径核对(确认原值仅存在于取证侧,不回显)。
- Expected:两个用户可见 builder 侧均句柄化;续跑/裁决功能不受影响(原值在功能载体);len=N 与游标长度一致。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

### 六、C6 M7 错误消息净化(≥2)

#### E2-12 DB 错误消息值段净化(DbErrorSanitizer 六缝)

- 出处:C6 R6/M7(DbErrorSanitizerTest 16→C8 追加 1=17);lockfree-dml spec §11.2 钉死条款同步修订;矩阵 C6 R6。
- Purpose:含敏感列值的 DB 错误(duplicate-key 冲突值、约束违规回显等)经六缝净化后才到用户/日志/审计——错误面不成为第七出口。
- Steps:1)对 E2-D01 唯一约束列构造 duplicate-key 写入(MySQL 1062/PG 23505 各一,如双环境可得);2)工作台/工单/分批三个用户面观察错误提示;3)后端日志与 biz_log 检索唯一标记串(环境变量比对)。
- Expected:用户面错误提示不含原值段(净化后形态,如 PG duplicate-key 保值删列名口径按 spec 钉死条款);日志/审计 grep 零命中;错误语义仍可排障(表名/约束名保留)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E2-12a 分批失败错误链净化(EXEC_FAILED/lastBatchError)

- 出处:lockfree-dml spec index 注记「C6 data-value disclosure hardening:EXEC_FAILED/lastBatchError/SQL-audit message ride DbErrorSanitizer(raw-error pin REVISED)」。
- Purpose:分批批失败的错误文案(前端段详情 lastBatchError/裁决面板「上次批错误」辅助行)同样净化。
- Steps:1)构造批执行 DB 错误(引用 e5 场景);2)裁决面板与段 summary 观察错误文案;3)dm_exec_sql_audit 对应行 message 口径核对。
- Expected:三处均净化形态;裁决所需事实(批 seq/lo-hi 句柄)保留。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

### 七、真库场景 6 UI 面展开(执行与 IS-MASK-6 同轮,③载体不复制)

#### E2-13 唯一敏感标记全出口矩阵(未授权侧)

- 出处:IS-MASK-6 步骤 1(计划文档 L200 原文六出口);矩阵 C6 行③。
- Purpose:以 E2-D01 唯一标记串为探针,未授权账号在①结果网格(含 resultDataRead)②分页③导出(SQL/Excel/JSON 三 convert)④异步结果⑤数据编辑器逐出口 grep 零命中;downloadResult 二进制直读=设计豁免面(spec §1,登记现状)。
- Steps:按 IS-MASK-6 步骤 1~3 执行,本用例承载其浏览器操作面与出口清单核对;导出文件在受控目录 grep 后删除。
- Expected:六出口+分批面(E2-11)未授权侧零原值;豁免侧可见且双侧留痕(IS-MASK-6 步骤 5);证据指针=IS-MASK-6 场景块+本用例双回填。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E2-14 导出三 convert 一致性

- 出处:desensitization-rules §1 出口③④⑤(SQL/Excel/JSON);tests/frontend/sql/query_result_export_download.md(文件名/下载链既有流程引用)。
- Purpose:同一掩码查询的三种导出格式掩码一致(机制=同一 doMask,验证出口无遗漏);导出文件名纪律按既有流程文档。
- Steps:1)未授权账号执行掩码查询;2)分别导出 SQL/Excel/JSON;3)三文件受控 grep 唯一标记串;4)豁免账号对照导出(原值+留痕)。
- Expected:未授权三文件零命中且掩码形态一致;豁免侧原值+SENSITIVE_ORIGINAL_VIEW 行;下载文件名符合既有文档预期。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E2-15 数据编辑器面(第六消费点)

- 出处:survey E2(DataEditorController=C6 M3 gate+M5 豁免审计消费点);desensitization-rules §3(editor 路径不走 configMasking,共享 ensureMaskingBindingUsable)。
- Purpose:表数据编辑器(非 SQL 工作台路径)的掩码/三态/豁免行为与工作台一致;豁免审计 channel=CONSOLE 落点(editor=真实 UI 请求)。
- Steps:1)未授权账号经编辑器打开 E2-D01 表;2)掩码列核对;3)DANGLING 环境编辑器打开→拒绝(E2-06 步骤 3 已含,此处补 VALID 对照);4)豁免账号编辑器原值查看→审计行 channel 核对。
- Expected:编辑器与工作台掩码一致;三态语义一致;审计落点 channel 正确。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

### 八、C3 估算诚实标注(查询侧,≥2)

#### E2-16 rowsEstimated 不可得 → 「行数不可估算」,永不假 0

- 出处:C3 R4/R5(`53b5e5cf`);矩阵 C3 行②;i18n 键 `risk-level-rows-unestimated`(zh.json 实存);prod-verification 双方言节 PG DML Explain 降级修订处。
- Purpose:PG DML(SPI 显式排除 INSERT/UPDATE/DELETE/MERGE)估算不可得时,前端显示「行数不可估算」标注——绝不显示 0(null 永不伪装);风险等级不因未知升级(评级行为逐字节不变,C3 R2 方案 b)。
- Steps:1)PG 测试源工作台/预检面执行 DML 相关查询或工单预检;2)观察行数展示位;3)对照 MySQL 源同语句(真实估算数值)。
- Expected:PG→「行数不可估算」;MySQL→数值;两处均无「0 行」误导;风险等级两侧口径一致(未知不升级)。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

#### E2-17 真实估算 0 与未知的区分(true-0 honest)

- 出处:C3 R1(区分「真实估算 0」与「未知」;governance-contracts「rowsEstimated = has-estimate (true-0 honest)」修订);矩阵 §3.3。
- Purpose:EXPLAIN 真实估算 0 行(如 WHERE 恒假)显示 0(诚实),与 E2-16 的「不可估算」两种展示不混淆。
- Steps:1)MySQL 源构造 WHERE 恒假的 DML 预检/估算面;2)观察显示 0(而非「不可估算」);3)分批路由兜底口径核对:估算不可得→表行数兜底(TABLE_SCALE 公告口径键,C3 R5;UI 面→e3 E3-03)。
- Expected:0=真实估算、标注=未知,两态分明;兜底公告显示表规模口径而非假行数。
- 证据:日期____|执行人____|结果____|证据指针____|豁免____

## Cleanup

1. DROP E2-D01;删除 E2-D02/E2-D05 规则;E2-D03/E2-D04 环境恢复原绑定(原值记录不入档);
2. 删除受控目录中的导出验证文件(grep 完成后即删);
3. 清理测试账号的 DM_SENSITIVE 临时授权(如有)。

## Skip Conditions

- PG 测试实例不可得→E2-16 SKIP(MySQL 侧对照仍执行);
- 数据编辑器面在部署形态中未启用→E2-15 SKIP(记录替代=IS-MASK-6 出口⑤);
- 双环境不可得→E2-12 仅执行单方言臂并记录;
- 未执行、失败或环境阻塞的场景不得标记为通过(TEMPLATE.md 纪律)。
