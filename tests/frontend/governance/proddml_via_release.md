# PROD_DML 随发布单执行（提交时选路径：DIRECT 直批 / VIA_RELEASE 入台账）

## Purpose

验证 PROD_DML 工单提交时的路径选择（直接审批 DIRECT / 随发布单 VIA_RELEASE）、台账待发布池展示与筛选、混合发布单创建与执行回写、退回/作废回池、returnStmt 修订重提回 WAIT_RELEASE、工单详情页 WAIT_RELEASE 容错。重点防止：VIA_RELEASE 工单存在单独审批入口（双执行窗口）、DIRECT 工单被发布单合并（资格互斥破坏）、台账不显示待发布行、发布单 DONE 后源工单悬挂中间态、退回后源工单不回池、详情页无 process 行时报错。

## Scope

- 页面：工单提交页（`/ticket/create` GovTicketV2Create）、台账页（`/dbChange/ledger`）、发布单详情页（`/dbChange/release/:releaseId`）、工单详情页（`/ticket/:id`）。
- 路由：`/#/ticket/create`、`/#/dbChange/ledger`、`/#/dbChange/release/:releaseId`、`/#/ticket/:id`。
- 接口：`dbChangeV2/submit`（payload 含 `approvalMode`）、`govledger/list`（行含 `ticketType`/`approvalMode`）、`govrelease/create`、`govrelease/detail`（StmtEntry 含 `sourceTicketType`、sourceTickets 含 `ticketType`）、`govrelease/return`、`govrelease/resubmit`、`govrelease/cancel`、`govrelease/returnStmt`、`dbChangeV2/resubmit`。
- 状态：工单 `WAIT_RELEASE`（非终态，非 listUnFinish IN 集）；事件 `TICKET_VIA_RELEASE_DONE`（operator=SYSTEM）。
- 不覆盖：后端审批流互斥内部机制、调度器 sweep 逻辑、外部开放 API（`/api/open/dbchange/*`）端点直测（前端不调用，仅前端可见性口径）、执行面三门（hash→B→A）内部。

## Preconditions

- 本地 `DmAloneLauncher` 已启动，访问地址为 `http://127.0.0.1:8222/`。
- Chrome 已登录主账号（accountType=PRIMARY_ACCOUNT）或维护者（isMaintainer=true）。
- 已配置至少一个逻辑库，PRE 环境与 PROD 环境分别绑定隔离测试数据源（MySQL）。
- 台账中存在至少一张已执行完毕（FINISHED）的 PRE_DDL 工单，用于混合发布单场景。
- 所有写入和执行仅允许使用隔离测试数据库，不得修改生产数据。

## Test Data

| 编号 | 数据说明 | 构造方式 | 唯一标识 | 清理方式 |
|------|---------|---------|---------|---------|
| D01 | VIA_RELEASE DML 工单 | 提交 PROD_DML 工单，approvalMode=VIA_RELEASE，SQL `UPDATE codex_via_<ts> SET v=1 WHERE id=1;`（需先建表），预检通过 | `codex_via_<ts>` | 发布单执行后或回池后 `DROP TABLE codex_via_<ts>` |
| D02 | DIRECT DML 工单（回归 pin） | 提交 PROD_DML 工单，approvalMode=DIRECT，SQL `UPDATE codex_dir_<ts> SET v=1 WHERE id=1;` | `codex_dir_<ts>` | 审批执行后 `DROP TABLE codex_dir_<ts>` |
| D03 | PRE_DDL 已执行工单 | 提交 PRE_DDL `CREATE TABLE codex_pre_<ts> (id INT PRIMARY KEY);` 至 FINISHED | `codex_pre_<ts>` | PROD 执行后 `DROP TABLE codex_pre_<ts>` |
| D04 | 发布单标题 | `测试发布-<ts>` | `<ts>` | 无残留 |
| D05 | 退回原因 | `测试退回-<ts>` | `<ts>` | 无残留 |

真实边界来自后端 FO 校验：`approvalMode` 可选值 DIRECT/VIA_RELEASE；PRE_DDL + VIA_RELEASE → 400；空/非法值 → DIRECT 缺省。

## Suites

### PVR-SMOKE-01 提交页路径选择控件可见性与切换

- 风险/目的：P0，确认 PROD_DML 时审批路径 RadioGroup 可见、PRE_DDL 时不可见，切换重置为 DIRECT。
- 初始路由与状态：`/#/ticket/create`。
- 测试数据：无。
- 准备方法：用主账号登录。
- Chrome 操作：
    1. 进入提交页，默认 ticketType=PRE_DDL，确认无「审批路径」控件。
    2. 切换到 PROD_DML，确认「审批路径」RadioGroup 出现，默认选中「直接审批」，下方显示直接审批说明文案。
    3. 选择「随发布单」，确认说明文案切换为随发布单说明，并出现混合顺序提示。
    4. 切换回 PRE_DDL，确认审批路径控件消失。
    5. 再切换到 PROD_DML，确认 approvalMode 重置为「直接审批」。
- 预期结果：
    1. PRE_DDL 无审批路径控件。
    2. PROD_DML 显示 RadioGroup，默认 DIRECT，文案为「提交后直接进入钉钉审批流程…」。
    3. 选 VIA_RELEASE 后文案切换为「提交后进入变更台账待发布池…」，混合顺序提示可见。
    4. 切回 PRE_DDL 控件消失。
    5. 再次切 PROD_DML 时 approvalMode 回到 DIRECT。
- 恢复/清理：无。

### PVR-MAIN-01 VIA_RELEASE 提交进入待发布池

- 风险/目的：P0，确认 VIA_RELEASE 提交后工单状态为 WAIT_RELEASE、无审批流、出现在台账待发布行。
- 初始路由与状态：`/#/ticket/create`。
- 测试数据：D01。
- 准备方法：预先在 PROD 库建表 `codex_via_<ts> (id INT PRIMARY KEY, v INT)` 并插入 id=1。
- Chrome 操作：
    1. 选 PROD_DML + 随发布单，选映射库，填入 D01 SQL，点预检。
    2. 确认预检通过。
    3. 点提交，确认跳转到工单详情页 `/ticket/<id>`。
    4. 查看工单状态徽章区显示「待发布」提示（WAIT_RELEASE notice）。
    5. 查看进度区：仅显示「创建」步骤，无审批/确认/执行步骤（无 process 行容错）。
    6. 确认无「审批」「执行」按钮（canApproval/canExecute 均为 false）。
    7. 切到台账页，确认 D01 行出现，类型列显示「生产库 DML」Tag，状态为未转产，预发库列显示「—」，生产库列显示目标库。
- 预期结果：
    1. 预检通过。
    2. 提交成功，跳转详情页。
    3. 详情页显示 WAIT_RELEASE 提示文案「工单处于待发布态…」。
    4. 进度区仅有「创建」步骤，无报错无空白异常。
    5. 无审批/执行按钮。
    6. 台账显示 D01 待发布行，类型 Tag 为 warning 色「生产库 DML」，预发库列「—」，生产库列有值。
- 恢复/清理：无（工单待发布，后续场景消费或退回）。

### PVR-MAIN-02 DIRECT 提交回归 pin

- 风险/目的：P0，确认 DIRECT 路径与现状逐位一致：建审批流、状态 WAIT_APPROVAL、可被审批。
- 初始路由与状态：`/#/ticket/create`。
- 测试数据：D02。
- 准备方法：预先建表 `codex_dir_<ts>`。
- Chrome 操作：
    1. 选 PROD_DML + 直接审批，填入 D02 SQL，预检通过后提交。
    2. 确认跳转详情页，状态为 WAIT_APPROVAL（非 WAIT_RELEASE）。
    3. 确认进度区有审批/确认/执行步骤（process 行存在）。
    4. 确认「审批」按钮可见（canApproval=true）。
    5. 切到台账页，确认 D02 不出现在台账（DIRECT 不入池）。
- 预期结果：
    1. DIRECT 提交后状态 WAIT_APPROVAL。
    2. 进度区含审批/确认/执行步骤。
    3. 审批按钮可见。
    4. 台账无 D02 行。
- 恢复/清理：审批拒绝或关闭 D02 工单释放。

### PVR-MAIN-03 PRE_DDL + VIA_RELEASE 被拒（入口校验）

- 风险/目的：P0，确认 PRE_DDL 选 VIA_RELEASE 提交时后端 400 拒绝。
- 初始路由与状态：`/#/ticket/create`。
- 测试数据：无。
- Chrome 操作：
    1. 选 PRE_DDL（无审批路径控件，approvalMode 不传或传 DIRECT）。
    2. 提交。
    3. 确认提交成功（PRE_DDL 不受 approvalMode 影响）。
- 预期结果：
    1. PRE_DDL 提交页无审批路径控件（前端不暴露 VIA_RELEASE 给 PRE_DDL）。
    2. PRE_DDL 提交行为不变。
- 恢复/清理：关闭该 PRE_DDL 工单。

### PVR-MAIN-04 台账类型筛选

- 风险/目的：P0，确认台账类型筛选下拉可按 PRE_DDL / PROD_DML 过滤，默认全部。
- 初始路由与状态：`/#/dbChange/ledger`，已有 D01（PROD_DML 待发布）与 D03（PRE_DDL 已执行）。
- 测试数据：D01、D03。
- Chrome 操作：
    1. 默认筛选「全部」，确认两类行都可见。
    2. 选「生产库 DML」，确认仅显示 D01 行。
    3. 选「预发变更」，确认仅显示 D03 行。
    4. 清空筛选回到全部。
- 预期结果：
    1. 默认全部，两类行可见，类型 Tag 颜色区分（PRE_DDL=primary，PROD_DML=warning）。
    2. 选生产库 DML 仅 D01。
    3. 选预发变更仅 D03。
    4. 清空回到全部。
- 恢复/清理：无。

### PVR-MAIN-05 混合发布单创建与执行回写

- 风险/目的：P0，确认可混合勾选 PRE_DDL + PROD_DML 创建发布单，审批通过后 PROD_DML 源工单回写 FINISHED，发布单 DONE 时无悬挂。
- 初始路由与状态：`/#/dbChange/ledger`，D01 待发布、D03 已执行。
- 测试数据：D01、D03、D04。
- Chrome 操作：
    1. 在台账勾选 D01 与 D03，点「生成发布单汇总」。
    2. 弹窗确认目标生产库，填 D04 标题，确认生成。
    3. 跳转发布单详情页，确认状态 APPROVING，语句分组含两类 stmt。
    4. 确认 stmt 卡片显示源工单类型 Tag（PROD_DML/PRE_DDL）。
    5. 通过钉钉/审批流让发布单审批通过并执行（或模拟审批通过）。
    6. 刷新详情页，确认发布单状态 DONE。
    7. 查看事件流含 RELEASE_DONE。
    8. 切到 D01 工单详情页，确认状态变为 FINISHED，进度区有 process 行（执行完成）。
    9. 切到台账，确认 D01 行不再为未转产（已 DONE 回写）。
- 预期结果：
    1. 混合发布单创建成功。
    2. stmt 卡片类型 Tag 正确。
    3. 发布单 DONE 后 D01 源工单 FINISHED（无悬挂中间态）。
    4. D03（PRE_DDL）源工单状态不受影响（仍 FINISHED）。
    5. 事件流可见 RELEASE_DONE。
- 恢复/清理：在测试库执行 `DROP TABLE codex_via_<ts>; DROP TABLE codex_pre_<ts>`。

### PVR-MAIN-06 退回发布单后 PROD_DML 源工单回池

- 风险/目的：P0，确认退回（returnRelease）后 VIA_RELEASE 源工单回池可重新勾选，promoted 自动翻 false。
- 初始路由与状态：已创建含 D01 的 APPROVING 发布单。
- 测试数据：D01、D05。
- Chrome 操作：
    1. 在发布单详情页点「退回」，填 D05 原因，确认。
    2. 确认发布单状态变为 RETURNED。
    3. 切到台账，确认 D01 行恢复「未转产」可勾选。
    4. 切到 D01 工单详情页，确认状态仍为 WAIT_RELEASE（未被误改）。
- 预期结果：
    1. 发布单 RETURNED。
    2. D01 在台账恢复未转产、可勾选。
    3. D01 工单状态仍 WAIT_RELEASE（退回不碰源工单状态）。
- 恢复/清理：作废该 RETURNED 发布单释放工单。

### PVR-MAIN-07 作废/拒绝后回池

- 风险/目的：P1，确认 handleRejected/handleCancelled/cancelRelease 后 VIA_RELEASE 源工单回池。
- 初始路由与状态：含 D01 的发布单。
- 测试数据：D01。
- Chrome 操作：
    1. 构造发布单被审批拒绝（REJECTED）或作废（CANCELLED）。
    2. 切到台账确认 D01 恢复未转产可勾选。
    3. 确认 D01 工单状态仍 WAIT_RELEASE。
- 预期结果：
    1. 发布单 REJECTED/CANCELLED 后 stmt 行删除，promoted=false。
    2. D01 回池可重新合并。
    3. D01 工单状态不变（WAIT_RELEASE）。
- 恢复/清理：无。

### PVR-MAIN-08 returnStmt 修订环回 WAIT_RELEASE

- 风险/目的：P0，确认单语句执行失败后 returnStmt 使源工单 REVISION，修订重提后落回 WAIT_RELEASE。
- 初始路由与状态：含 D01 的发布单执行部分失败（PARTIAL_FAILED）。
- 测试数据：D01。
- Chrome 操作：
    1. 在发布单详情页对 FAILED 的 D01 stmt 点「退回工单」（returnStmt）。
    2. 确认发布单 stmt 行删除。
    3. 切到 D01 工单详情页，确认状态变为 REVISION，失败组可编辑。
    4. 修改 SQL 后点「重新提交」。
    5. 确认工单状态回到 WAIT_RELEASE（非 WAIT_APPROVAL）。
    6. 切到台账确认 D01 重新出现在待发布池。
- 预期结果：
    1. returnStmt 后 D01 工单 REVISION，失败组 group→FAILED。
    2. 修订重提后落回 WAIT_RELEASE（跳过审批人重建）。
    3. D01 重新入台账待发布池。
- 恢复/清理：退回或作废相关发布单；清理测试表。

### PVR-STATE-01 WAIT_RELEASE 状态徽章与事件标签

- 风险/目的：P0，确认 WAIT_RELEASE 有提示文案，TICKET_VIA_RELEASE_DONE 事件有中英文标签。
- 初始路由与状态：VIA_RELEASE 工单详情页（WAIT_RELEASE）；执行完成的发布单事件流。
- 测试数据：D01。
- Chrome 操作：
    1. 在 WAIT_RELEASE 工单详情页查看提示文案。
    2. 发布单执行 DONE 后，在发布单详情页事件流查看 TICKET_VIA_RELEASE_DONE 事件标签（若事件流渲染该类型）。
    3. 切换语言到 English，确认英文文案。
- 预期结果：
    1. WAIT_RELEASE 工单详情页显示「工单处于待发布态…」（中文）/「Ticket is pending release…」（英文）。
    2. TICKET_VIA_RELEASE_DONE 事件标签为「随发布单执行完成」/「Released via Release」。
    3. 中英文文案一致切换。
- 恢复/清理：恢复语言设置。

### PVR-CONSIST-01 DIRECT 工单不可被发布单合并

- 风险/目的：P0，确认 DIRECT 路径 PROD_DML 工单不出现在台账候选池，无法被发布单合并（资格互斥）。
- 初始路由与状态：D02（DIRECT，WAIT_APPROVAL）。
- 测试数据：D02。
- Chrome 操作：
    1. 切到台账，确认 D02 不出现（DIRECT 不入池）。
    2. 尝试在发布单重提弹窗候选区查找 D02，确认不存在。
- 预期结果：
    1. 台账无 D02 行。
    2. 重提候选区无 D02。
- 恢复/清理：拒绝/关闭 D02 工单。

### PVR-CONSIST-02 无 process 行时进度区容错

- 风险/目的：P1，确认 WAIT_RELEASE 工单（无 APPROVAL/CONFIRM/EXECUTION process 行）详情页进度区不报错、不空白异常。
- 初始路由与状态：D01 工单详情页（WAIT_RELEASE）。
- 测试数据：D01。
- Chrome 操作：
    1. 进入 D01 工单详情页。
    2. 查看进度区渲染。
    3. 点「创建」步骤，查看步骤详情面板。
- 预期结果：
    1. 进度区仅显示「创建」步骤（finished 态），无报错、无空白异常。
    2. 步骤详情面板正常显示创建信息（处理人、时间）。
    3. 无审批/确认/执行步骤（无 process 行，天然容错）。
- 恢复/清理：无。

### PVR-CONSIST-03 重提弹窗源工单类型 Tag

- 风险/目的：P1，确认 RETURNED 发布单重提弹窗的源工单清单与候选区行显示类型 Tag。
- 初始路由与状态：含 D01 + D03 的 RETURNED 发布单详情页。
- 测试数据：D01、D03。
- Chrome 操作：
    1. 在 RETURNED 发布单点「重新提交」打开弹窗。
    2. 查看原清单行：D01 行显示 PROD_DML Tag，D03 行显示 PRE_DDL Tag。
    3. 查看候选区行（如有），确认类型 Tag 正确。
- 预期结果：
    1. 原清单行类型 Tag 正确（warning/primary）。
    2. 候选区行类型 Tag 正确。
- 恢复/清理：关闭弹窗或作废发布单。

### PVR-CONSIST-04 开放口径（钉钉筛池）可见性

- 风险/目的：P2，确认通过工单列表口径可查询待发布 PROD_DML 工单（前端不直接调用开放 API，仅验证台账与详情页对外可见的状态口径一致）。
- 初始路由与状态：台账页。
- 测试数据：D01。
- Chrome 操作：
    1. 在台账确认 D01 行状态字段（execStatus=PENDING、ticketType=PROD_DML、approvalMode=VIA_RELEASE）。
    2. 确认工单详情页状态展示与台账一致。
- 预期结果：
    1. 台账 D01 行 execStatus=PENDING、类型 PROD_DML。
    2. 详情页 WAIT_RELEASE 提示与台账状态一致。
- 恢复/清理：无。

## Cleanup

1. 退回或作废所有测试创建的发布单，释放工单。
2. 在测试数据库执行 `DROP TABLE IF EXISTS codex_via_<ts>`、`DROP TABLE IF EXISTS codex_dir_<ts>`、`DROP TABLE IF EXISTS codex_pre_<ts>` 删除测试表。
3. 关闭/拒绝所有残留工单。
4. 恢复测试账号权限和网络条件。

## Skip Conditions

- 钉钉审批流未配置或无法模拟审批通过时，SKIP PVR-MAIN-05/PVR-MAIN-07 的审批通过步骤，仅验证创建与退回路径；执行回写可通过后端单测覆盖证据替代。
- returnStmt 修订环需构造执行失败的 stmt；无法稳定构造部分失败时 SKIP PVR-MAIN-08，仅验证 returnStmt 入口可见性与退回按钮存在。
- 开放 API 直测需配置 AK/SK；本地无法安全模拟时 SKIP PVR-CONSIST-04 的外部调用，仅验证前端台账/详情页状态口径。
