# 发布单退回重提与开放全员创建（release return / resubmit / cancel）

## Purpose

验证发布单退回（RETURNED）、重提（resubmit）、作废（cancel）全链路以及转生产全员开放后的前端交互。重点防止：退回按钮越权可见（非管理员/主账号可见退回按钮）、重提时不可用工单被静默剔除而非明确报错、重提后审批流未重建、作废后状态未迁移、创建发布单失败无显式反馈、RETURNED 状态徽章/事件标签缺失。

## Scope

- 页面：台账页（`/dbChange/ledger`）、发布单详情页（`/dbChange/release/:releaseId`）。
- 路由：`/#/dbChange/ledger`、`/#/dbChange/release/:releaseId`。
- 接口：`govrelease/create`、`govrelease/return`、`govrelease/resubmit`、`govrelease/cancel`、`govrelease/detail`。
- 状态：ProdReleaseStatus 含 RETURNED（非终态，warning 色系）。
- 事件：RELEASE_RETURNED、RELEASE_RESUBMITTED。
- 不覆盖：外部开放 API（`/api/open/dbchange/releases/return`）端点测试（外部系统专用，前端不调用）、后端审批流撤销机制内部、stmt 级退回（returnStmt，已独立上线）。

## Preconditions

- 本地 `DmAloneLauncher` 已启动，访问地址为 `http://127.0.0.1:8222/`。
- Chrome 已登录两个账号：
  - 主账号（accountType=PRIMARY_ACCOUNT）或维护者（isMaintainer=true）。
  - 普通子账号（accountType=SUB_ACCOUNT，无 DM_TICKET 数据权限标签），与主账号同租户。
- 已配置至少一个逻辑库，其 PRE 环境绑定可用测试数据源（MySQL），PROD 环境绑定隔离测试数据源。
- 台账中存在至少两张同租户的已执行完毕（FINISHED）预发工单。
- 所有写入和执行仅允许使用隔离测试数据库，不得修改生产数据。

## Test Data

| 编号 | 数据说明 | 构造方式 | 唯一标识 | 清理方式 |
|------|---------|---------|---------|---------|
| D01 | 标准 DDL 工单 | 提交治理工单 `CREATE TABLE codex_ret_<ts> (id INT PRIMARY KEY);`，PRE 执行完毕至 FINISHED | `codex_ret_<ts>` | PRE+PROD 执行后 `DROP TABLE codex_ret_<ts>` |
| D02 | 第二张 DDL 工单 | 提交治理工单 `CREATE TABLE codex_ret2_<ts> (id INT PRIMARY KEY);`，PRE 执行完毕至 FINISHED | `codex_ret2_<ts>` | 同上 |
| D03 | 退回原因 | `测试退回-<ts>` | `<ts>` | 无残留 |

真实边界来自后端 FO 校验（`releaseId @NotNull`、`reason @NotBlank @Size(max=1000)`、`ticketIds @NotEmpty` 最终全量清单，可含原清单外的新增合格工单）。

## Suites

### RET-SMOKE-01 发布单详情页可达与 RETURNED 状态展示

- 风险/目的：P0，确认退回后的发布单详情页正确渲染 RETURNED 状态徽章、退回原因、源工单清单。
- 初始路由与状态：已创建一张 APPROVING 发布单并被管理员退回。
- 测试数据：D01、D03。
- 准备方法：用主账号在台账勾选 D01 → 创建发布单 → 在详情页退回（带 D03 原因）。
- Chrome 操作：
    1. 进入 `/dbChange/release/<releaseId>`。
    2. 查看状态徽章显示"已退回"（warning 色系，与 PARTIAL_FAILED 区分）。
    3. 查看概览区显示"退回原因"字段及内容。
    4. 查看"源工单清单"区块，含 D01 工单行，状态标签为"可重提"或"不可用"。
    5. 查看"门禁快照"区块可见（RETURNED 状态下展示）。
    6. 查看事件流含"发布单退回"事件。
- 预期结果：
    1. 状态徽章文字为"已退回"，颜色为 warning（橙色）。
    2. 退回原因字段可见且内容为 D03 文本。
    3. 源工单清单至少一行，工单号和标题正确。
    4. 门禁快照区块可见（RETURNED 在 showGateResult 白名单中）。
    5. 事件流含 RELEASE_RETURNED 事件，操作人 UID 正确。
- 恢复/清理：无。

### RET-MAIN-00 普通子账号创建发布单（全员开放）

- 风险/目的：P0，确认普通子账号（无 DM_TICKET 授权）可为同租户工单创建发布单。
- 初始路由与状态：`/#/dbChange/ledger`。
- 测试数据：D01。
- 准备方法：用普通子账号登录。
- Chrome 操作：
    1. 在台账勾选 D01 工单。
    2. 点击"生成发布单汇总"。
    3. 在弹窗中点击"确认生成"。
    4. 确认跳转到发布单详情页，状态为 APPROVING。
- 预期结果：
    1. "生成发布单汇总"按钮可点击（非 disabled）。
    2. 确认后无 406 错误，创建成功，跳转到详情页。
    3. 详情页显示 APPROVING 状态、审批工单号、语句分组。
- 恢复/清理：退回或作废该发布单释放工单。

### RET-MAIN-01 管理员退回发布单（带原因）

- 风险/目的：P0，确认管理员/主账号可退回 APPROVING 发布单，退回后源工单释放、审批流撤销、事件记录。
- 初始路由与状态：`/#/dbChange/release/<releaseId>`，状态为 APPROVING。
- 测试数据：D01、D03。
- 准备方法：用主账号登录，已完成 RET-MAIN-00 创建发布单。
- Chrome 操作：
    1. 在详情页查看"退回"按钮可见（橙色 warning 按钮）。
    2. 点击"退回"按钮，弹窗显示原因输入框。
    3. 输入 D03 退回原因，点击"确认"。
    4. 等待页面刷新，查看状态变为"已退回"。
    5. 切换到台账页，确认 D01 工单恢复"未转产"可勾选。
- 预期结果：
    1. 退回按钮仅在 APPROVING/APPROVED 状态且主账号/维护者可见。
    2. 退回原因弹窗含必填校验（空值时显示提示，不发送请求）。
    3. 退回成功后详情页状态徽章为"已退回"，退回原因可见。
    4. 台账中 D01 恢复"未转产"状态。
    5. 事件流含 RELEASE_RETURNED 事件。
- 恢复/清理：无（工单已释放）。

### RET-MAIN-02 申请人重提（剔除部分工单）

- 风险/目的：P0，确认 RETURNED 发布单可重提，支持剔除部分工单，重提后重建审批流和 stmt 行。
- 初始路由与状态：`/#/dbChange/release/<releaseId>`，状态为 RETURNED。
- 测试数据：D01、D02（两张工单合并的发布单被退回）。
- 准备方法：用主账号创建含 D01+D02 的发布单并退回；切换为申请人子账号。
- Chrome 操作：
    1. 进入 RETURNED 发布单详情页。
    2. 确认"重新提交"和"作废"按钮可见。
    3. 点击"重新提交"，弹窗显示源工单清单（原合并工单，可用工单默认勾选，不可用禁选+原因）。
    4. 确认弹窗下方显示"可新增工单（未转产）"区域（台账中 promoted=false 且不在原清单的工单，默认不勾选）。
    5. 取消勾选 D02（剔除），点击"确认"。
    6. 等待页面刷新，查看状态变为 APPROVING。
    7. 确认事件流含"发布单重提"事件。
- 预期结果：
    1. 重提弹窗标题可编辑（回填当前标题），工单清单含每张工单的可用状态。
    2. 可用工单可勾选/取消勾选，不可用工单 checkbox 禁用。
    3. 可新增候选区标题可见，候选工单默认不勾选。
    4. 取消全部勾选（原清单和候选区）时显示"至少保留一张工单"提示，不发送请求。
    5. 重提成功后状态变为 APPROVING，新审批工单号回填。
    6. 事件流含 RELEASE_RESUBMITTED 事件。
- 恢复/清理：退回或作废该发布单。

### RET-MAIN-02A 申请人重提（新增工单）

- 风险/目的：P0，确认重提弹窗支持从候选区新增台账合格工单到最终清单，重提后 stmt 行包含新增工单。
- 初始路由与状态：`/#/dbChange/release/<releaseId>`，状态为 RETURNED。
- 测试数据：D01（已合并并退回）、D02（台账中未转产，promoted=false）。
- 准备方法：用主账号创建含 D01 的发布单并退回；台账中 D02 为另一张已执行完毕但未转产的工单；切换为申请人子账号。
- Chrome 操作：
    1. 进入 RETURNED 发布单详情页。
    2. 点击"重新提交"，弹窗显示源工单清单（D01）和候选区。
    3. 确认 D01 在原清单区默认勾选。
    4. 在候选区勾选 D02（新增）。
    5. 点击"确认"。
    6. 等待页面刷新，查看状态变为 APPROVING。
- 预期结果：
    1. 候选区包含 D02（promoted=false 且不在原清单）。
    2. 提交成功后状态变为 APPROVING。
    3. 语句分组包含 D01 和 D02 的语句。
    4. 事件流含 RELEASE_RESUBMITTED 事件，eventData 含 addedTicketIds=[D02]。
- 恢复/清理：退回或作废该发布单。

### RET-MAIN-02B 申请人重提（同时增删）

- 风险/目的：P0，确认重提弹窗支持同时剔除原工单和新增候选工单，最终清单由所有勾选项组成。
- 初始路由与状态：`/#/dbChange/release/<releaseId>`，状态为 RETURNED。
- 测试数据：D01、D02（原合并清单含两张工单），D03（台账未转产候选）。
- 准备方法：用主账号创建含 D01+D02 的发布单并退回；台账中 D03 为未转产工单；切换为申请人子账号。
- Chrome 操作：
    1. 进入 RETURNED 发布单详情页。
    2. 点击"重新提交"，弹窗显示原清单（D01、D02）和候选区（含 D03）。
    3. 取消勾选 D02（剔除）。
    4. 在候选区勾选 D03（新增）。
    5. 点击"确认"。
    6. 等待页面刷新，查看状态变为 APPROVING。
- 预期结果：
    1. 提交成功后状态变为 APPROVING。
    2. 语句分组包含 D01 和 D03 的语句（不含 D02）。
    3. 事件流含 RELEASE_RESUBMITTED 事件，eventData 含 addedTicketIds=[D03]、removedTicketIds=[D02]。
- 恢复/清理：退回或作废该发布单。

### RET-FAILURE-02 候选区拉取失败降级

- 风险/目的：P1，确认台账候选拉取失败时不阻断弹窗使用，降级为仅原清单可剔除。
- 初始路由与状态：`/#/dbChange/release/<releaseId>`，状态为 RETURNED。
- 测试数据：D01（已退回的发布单）。
- 准备方法：模拟台账接口 `govLedgerList` 返回失败（如断网或后端不可达）。
- Chrome 操作：
    1. 点击"重新提交"，弹窗打开。
    2. 等待候选区拉取完成。
    3. 确认原清单区正常显示（D01 默认勾选）。
    4. 确认候选区不显示（或为空）。
    5. 取消勾选 D01 并重新勾选，点击"确认"。
- 预期结果：
    1. 弹窗正常打开，无阻断错误提示。
    2. 原清单区工单行正常渲染，可勾选/取消。
    3. 候选区不渲染（v-if="resubmitCandidates.length" 为 false）。
    4. 重提仍可正常提交（仅原清单工单）。
- 恢复/清理：关闭弹窗。

### RET-MAIN-03 作废 RETURNED 发布单

- 风险/目的：P0，确认 RETURNED 发布单可作废，状态迁移到 CANCELLED。
- 初始路由与状态：`/#/dbChange/release/<releaseId>`，状态为 RETURNED。
- 测试数据：D01（已退回的发布单）。
- 准备方法：创建并退回一张发布单。
- Chrome 操作：
    1. 在 RETURNED 详情页点击"作废"按钮。
    2. 确认弹出确认对话框，含"确定要作废此发布单吗？"文案。
    3. 点击确认对话框的"确认"按钮。
    4. 等待页面刷新，查看状态变为"已取消"。
- 预期结果：
    1. 作废按钮在 RETURNED 状态下对 creator/主账号/维护者可见。
    2. 确认对话框文案正确，确认后状态迁移到 CANCELLED。
    3. CANCELLED 为终态，无"重新提交"或"作废"按钮。
- 恢复/清理：无（CANCELLED 为终态）。

### RET-PERMISSION-00 子账号不可见退回按钮

- 风险/目的：P0，确认普通子账号在 APPROVING 发布单详情页看不到"退回"按钮。
- 初始路由与状态：`/#/dbChange/release/<releaseId>`，状态为 APPROVING。
- 测试数据：D01。
- 准备方法：用普通子账号创建发布单后，仍在该子账号登录态。
- Chrome 操作：
    1. 进入 APPROVING 发布单详情页。
    2. 查看发布单级操作区。
- 预期结果：
    1. 无"退回"按钮可见（退回按钮仅对主账号/维护者可见）。
    2. 页面其他区域（概览、语句分组、事件流）正常渲染。
- 恢复/清理：用主账号退回该发布单。

### RET-PERMISSION-01 申请人可见重提/作废按钮

- 风险/目的：P0，确认被退回发布单的申请人（非主账号）可看到"重新提交"和"作废"按钮。
- 初始路由与状态：`/#/dbChange/release/<releaseId>`，状态为 RETURNED。
- 测试数据：D01。
- 准备方法：用普通子账号创建发布单，主账号退回后，切换回该子账号。
- Chrome 操作：
    1. 进入 RETURNED 发布单详情页。
    2. 查看发布单级操作区。
- 预期结果：
    1. "重新提交"和"作废"按钮可见（creator 本人可见）。
    2. "退回"按钮不可见（非 APPROVING/APPROVED 状态）。
- 恢复/清理：无。

### RET-BOUNDARY-00 退回原因为空校验

- 风险/目的：P1，确认退回原因必填校验（前端 + 后端 `@NotBlank`）。
- 初始路由与状态：APPROVING 发布单详情页，主账号登录。
- 测试数据：无。
- Chrome 操作：
    1. 点击"退回"按钮，弹窗显示。
    2. 不输入原因，点击"确认"。
- 预期结果：
    1. 显示"退回原因不能为空"提示。
    2. 请求不发送，弹窗保持打开。
- 恢复/清理：关闭弹窗。

### RET-BOUNDARY-01 重提全部取消勾选

- 风险/目的：P1，确认重提弹窗中至少保留一张工单的前端校验。
- 初始路由与状态：RETURNED 发布单详情页。
- 测试数据：D01、D02。
- 准备方法：创建含两张工单的发布单并退回。
- Chrome 操作：
    1. 点击"重新提交"，弹窗显示。
    2. 取消所有可用工单的勾选。
    3. 点击"确认"。
- 预期结果：
    1. 显示"至少保留一张工单"提示。
    2. 请求不发送，弹窗保持打开。
- 恢复/清理：关闭弹窗。

### RET-FAILURE-00 退回失败显式反馈

- 风险/目的：P1，确认退回操作失败时显示明确错误消息，弹窗保持打开。
- 初始路由与状态：APPROVING 发布单详情页。
- 测试数据：模拟后端返回错误（如状态已被并发退回）。
- Chrome 操作：
    1. 点击"退回"按钮，输入原因。
    2. 点击"确认"。
    3. 后端返回 success=false。
- 预期结果：
    1. 显示 `$Message.error` 错误消息（formatError 格式化）。
    2. 弹窗保持打开，状态保留。
    3. loading 状态恢复。
- 恢复/清理：关闭弹窗。

### RET-FAILURE-01 创建发布单失败显式反馈

- 风险/目的：P1，确认台账页创建发布单失败时显示明确错误消息，弹窗保持打开、选中保留。
- 初始路由与状态：`/#/dbChange/ledger`。
- 测试数据：D01。
- 准备方法：模拟后端返回错误（如工单已被抢占）。
- Chrome 操作：
    1. 勾选 D01，点击"生成发布单汇总"。
    2. 点击"确认生成"。
    3. 后端返回 success=false。
- 预期结果：
    1. 显示 `$Message.error` 错误消息（formatError 格式化）。
    2. 创建弹窗保持打开，选中工单保留。
    3. creating 状态恢复。
- 恢复/清理：关闭弹窗。

### RET-STATE-00 RETURNED 状态徽章与事件标签

- 风险/目的：P0，确认 RETURNED 状态有 i18n 映射，RELEASE_RETURNED/RELEASE_RESUBMITTED 事件有中英文标签。
- 初始路由与状态：发布单详情页。
- 测试数据：无。
- Chrome 操作：
    1. 在 RETURNED 发布单详情页查看状态徽章。
    2. 在事件流查看 RELEASE_RETURNED 事件标签。
    3. 切换语言到 English，确认英文文案。
- 预期结果：
    1. RETURNED 状态徽章显示"已退回"（中文）/"Returned"（英文）。
    2. RELEASE_RETURNED 事件显示"发布单退回"（中文）/"Release Returned"（英文）。
    3. RELEASE_RESUBMITTED 事件显示"发布单重提"（中文）/"Release Resubmitted"（英文）。
- 恢复/清理：恢复语言设置。

### RET-LIFECYCLE-00 外部撤回后页面状态同步

- 风险/目的：P1，确认外部 API 撤回后，前端详情页刷新能正确显示 RETURNED 状态。
- 初始路由与状态：APPROVING 发布单详情页。
- 测试数据：D01。
- 准备方法：用主账号创建发布单后，通过外部 API（AK/SK）调用 `/api/open/dbchange/releases/return` 退回。
- Chrome 操作：
    1. 在 APPROVING 发布单详情页手动刷新。
    2. 查看状态徽章。
    3. 查看退回原因字段。
    4. 查看事件流。
- 预期结果：
    1. 刷新后状态为"已退回"（RETURNED）。
    2. 退回原因字段可见（含外部撤回原因）。
    3. 事件流含 RELEASE_RETURNED 事件。
    4. 源工单清单区块可见。
- 恢复/清理：无。

## Cleanup

1. 退回或作废所有测试创建的发布单，释放工单。
2. 在测试数据库执行 `DROP TABLE IF EXISTS codex_ret_<ts>` 删除测试表。
3. 恢复测试账号权限和网络条件。

## Skip Conditions

- 外部 API 撤回测试需配置有效的 AK/SK 凭据；本地无法安全模拟时 SKIP 外部撤回步骤，仅验证前端刷新后状态显示。
- 重提时"不可用工单"场景需构造已被其他发布单抢占或状态变更的工单；无法稳定构造时 SKIP 该分支，仅验证可用工单的重提路径。
- 后端退回/重提/作废端点尚未实现时，所有涉及后端写入的场景 SKIP，仅验证前端 UI 渲染和校验逻辑。
