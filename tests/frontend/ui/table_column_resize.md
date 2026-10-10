# 表格列宽拖拽调节(全局 DmTable)

## Purpose

验证全局表格列宽拖拽能力:所有经 `DmTable`(全局 `<Table>` 覆盖 → ant-design-vue `a-table`)渲染的列表,有数值宽度的列可拖拽调宽、双击列边界手柄恢复默认;初始渲染与改造前逐像素一致(零视觉变化)。

主要防止的回归:
- 受控宽度 `widthOverrides` 写回页面 columns/computed 源(污染页面状态、computed 重建丢失);
- `convertTableColumns.js` L8 `width || minWidth` 折叠语义被改动(全量 61 处初始宽度翻转);
- 写死 `scroll.x` 页面(工单列表)拖宽后 fixed right 列与横向滚动范围错位;
- 无 width 自动列被硬补默认宽(布局模型改变);
- antd DragHandle mouseup 尾发 rAF 旧宽写回导致双击恢复失效。

## Scope

- 页面与路由:全局 41 文件 61 处 `<Table>`;重点六类——工单列表 `/ticket`、工单详情、工单创建校验弹窗、变更台账 `/dbChange/ledger`、生产发布单 `/dbChange/release`、慢SQL `/slowSql`、会话管理 `/session`、空间占用 `/space`(路由以 `router/index.js` 实际注册为准)。
- 入口:各列表页表格表头列右缘 16px 拖拽热区(透明 handle,hover 显参考线)。
- 关联接口与状态:无后端接口(纯前端内存态,无持久化,刷新回默认)。
- 关联源码:`frontend/src/components/ui/DmTable.vue`(widthOverrides/@resize-column/mergedScroll/双击恢复)、`frontend/src/utils/convertTableColumns.js`(resizable/minWidth/maxWidth 透传+默认启用)。
- 不覆盖:`sql/components/Result.vue` 等 7 处直连 `<a-table>`(不经 DmTable,Result.vue 有独立自研拖拽);列顺序/列显隐;localStorage 持久化(已裁决不做)。

## Preconditions

- 服务地址与依赖服务:本地 `http://localhost:8222`(DmAloneLauncher);前端产物为 `cd package && ./all_build.sh web` 构建后**重启后端**加载的新产物(或 `npm run serve:dm` dev server)。
- 所需账号角色与权限:能查看对应列表页的登录账号(工单/台账/发布单需治理查看权限;会话管理需相应实例权限);复用 Chrome 已登录状态,不读取任何认证数据。
- 稳定初始状态:各页面列表有至少 1 行数据;未拖动过任何列宽(刷新后即为默认)。
- 允许的数据写入范围:无(拖拽纯内存态;回归场景「行选建发布单」如需实建,仅在本地测试环境用 `codex_` 前缀数据)。

## Test Data

| 编号 | 数据说明 | 构造方式 | 唯一标识 | 清理方式 |
|-----|------|------|---------|------|
| D01 | 台账/发布单/工单既有列表行 | 复用环境存量数据,不新建 | — | 无需清理 |
| D02 | 长字段行(sqlSummary/SQL 语句/sqlTemplate 超 200 字符) | 复用存量;无存量时经既有工单流程造 `codex_resize_<时间戳>` | `codex_resize_` | 按既有工单清理流程 |
| D03 | (仅回归项)可合并台账条目→发布单 | 本地环境按 governance-flow.md D 系列构造 | `codex_` | 撤销/取消该发布单 |

真实边界:最小列宽 50px(antd DragHandle 默认 minWidth);handle 热区 16px(右伸 8px 可能被 ellipsis/容器 overflow 裁剪,残留内侧 8px);均为代码事实(`node_modules/ant-design-vue/es/vc-table/Header/DragHandle.js`、`table/style/resize.js`)。

## Suites

### TCR-SMOKE-01 handle 出现与基础拖拽

- 风险/目的:P0,能力点亮验证(默认启用策略 D2)
- 初始路由与状态:变更台账列表,刷新后默认列宽
- 测试数据:D01
- 准备方法:直接进入页面
- Chrome 操作:
    1. hover 任一有宽度列(如「工单标题」)表头右缘;
    2. 按住向右拖 ~100px 后松开。
- 预期结果:
    1. hover 时鼠标变 `col-resize`,出现竖直参考线;
    2. 拖动过程列宽实时跟随,松开后保持;内容区不整体错位。
- 恢复/清理:刷新页面(列宽回默认)。

### TCR-MAIN-01 六类页面长字段拖宽可见

- 风险/目的:P0,用户核心痛点(字段过长看不全)
- 初始路由与状态:逐页——台账(sqlSummary 列)、会话管理(SQL 列)、慢SQL(sqlTemplate 列)、空间占用(库名/表名列)、工单列表(标题列)、发布单列表(标题列)
- 测试数据:D01/D02
- Chrome 操作:
    1. 每页将长字段列拖宽至内容完整显示;
    2. 对照既有 ellipsis/tooltip 行为。
- 预期结果:
    1. 拖宽后被截断内容完整可见;
    2. 原有 hover tooltip 照常;无 width 的自动列(如弹窗 desc/content)无 handle、不可拖、布局不变。
- 恢复/清理:逐页刷新。

### TCR-MAIN-02 双击恢复默认

- 风险/目的:P0,D3 决议 + pendingWidthReset 竞态防护
- 初始路由与状态:任一列表,先将某列拖宽
- Chrome 操作:
    1. 双击该列右缘 handle;
    2. 双击表头其他区域(标题文字上);
    3. 拖宽后快速双击 handle(竞态窗口)。
- 预期结果:
    1. 列宽回默认;
    2. 不触发恢复(排序列则正常触发排序);
    3. 恢复后不被旧宽写回(列宽保持默认)。
- 恢复/清理:刷新。

### TCR-MAIN-03 fixed right + scroll.x 联动(工单列表)

- 风险/目的:P0,唯一写死 scroll.x=1682 页面,mergedScroll 数学
- 初始路由与状态:`/ticket` 工单列表
- Chrome 操作:
    1. 将任一数据列拖宽 ~200px;
    2. 横向滚动条拉到底;
    3. 再将两列分别拖宽后重复。
- 预期结果:
    1. 横向滚动范围随拖宽同步增大;
    2. fixed right 操作列无留白/错位/遮挡,始终贴右;
    3. 多列 delta 累计正确。
- 恢复/清理:刷新。

### TCR-BOUNDARY-01 最小宽夹取与热区可用性

- 风险/目的:P1,边界矩阵 #3 + check 风险提示 1/2(ellipsis 列、末列热区减半)
- Chrome 操作:
    1. 将某列向左拖至极小;
    2. 在 ellipsis 列(台账 sqlSummary)与表格末列右缘尝试拖拽。
- 预期结果:
    1. 列宽不小于 50px;
    2. 残留热区(≥8px)仍可拖,无功能丧失。
- 恢复/清理:刷新。

### TCR-BOUNDARY-02 控制台零告警

- 风险/目的:P1,DragHandle width required Number(无 width 列不得渲染 handle)
- Chrome 操作:打开 DevTools Console,遍历六类页面(含工单详情 5 处表格、两个日志弹窗、创建校验弹窗)。
- 预期结果:无 antd devWarning("width must be a number")、无 Vue prop warning、无新增报错。
- 恢复/清理:无。

### TCR-REPEAT-01 连续拖动与多表独立

- 风险/目的:P1,快速连续操作;工单详情两个日志表共享同一 columns 数组但实例独立
- Chrome 操作:
    1. 同一列快速连续拖拽多次;
    2. 工单详情:打开调度日志弹窗拖宽 time 列,再打开任务日志弹窗看同名列。
- 预期结果:
    1. 无卡顿、无宽度跳变回旧值;
    2. 两弹窗列宽互不影响(widthOverrides 按实例隔离)。
- 恢复/清理:刷新。

### TCR-LIFECYCLE-01 刷新回默认 + i18n 切换 override 存活

- 风险/目的:P0,D5 无持久化决议 + 边界矩阵 #2(computed columns 重建)
- Chrome 操作:
    1. 拖宽某列后刷新页面;
    2. 拖宽工单详情 DML Explain 表某列后切换语言(zh↔en);
    3. 台账页拖宽后切换筛选条件触发 computed 重建。
- 预期结果:
    1. 刷新后回默认宽;
    2. 语言切换后拖后宽度保持(override 按 key 存活于列重建);
    3. 筛选变化后宽度保持。
- 恢复/清理:刷新、语言切回。

### TCR-STATE-01 既有功能回归

- 风险/目的:P0,A6 无回归(勾选/排序/行选建发布单/kill/分页筛选)
- Chrome 操作:
    1. 台账:勾选行(handle 不得出现在勾选列)→「生成发布单」流程入口正常(如需实建用 D03,本地环境);
    2. 慢SQL:点击排序列标题排序正常(handle 右伸 8px 不拦截排序点击);
    3. 会话:kill 按钮列可点;
    4. 各页分页/筛选正常;
    5. 工单详情/发布单详情:钉钉桥接出站面板显示不受影响。
- 预期结果:全部与改造前一致;勾选列/展开列无拖拽 handle。
- 恢复/清理:D03 发布单撤销;其余无需清理。

### TCR-EXTREME-01 暗色主题与窄视口

- 风险/目的:P2,边界矩阵 #9(handle 色值走既有 token)+ 响应式
- Chrome 操作:切换暗色主题后 hover/拖拽一次;窗口缩至 ~1024px 宽重复 SMOKE。
- 预期结果:handle 参考线在暗色下可见不刺眼;窄视口拖拽正常、无横向布局破坏。
- 恢复/清理:主题切回、窗口还原。

### 不适用套件

- FAILURE:纯前端内存态能力,无接口调用/网络失败面(拖拽不发请求)——不适用。
- PERMISSION:能力对所有能看见表格的用户一致,无按钮级权限面(既有页面权限不受改造影响,由 TCR-STATE-01 覆盖入口可达性)——不适用。

## Cleanup

1. 刷新所有测试页面(列宽为内存态,刷新即回默认,无数据残留);
2. 恢复主题、语言、窗口尺寸;删除 D03 实建的测试发布单(如有);
3. 关闭测试标签页,确认无遗留轮询。

## Skip Conditions

- 慢SQL/会话管理无存量数据且本地无对应实例可造数时,该两页 MAIN-01 可 SKIP(替代验证:台账+工单+空间占用三页拖拽通过即证 DmTable 单点能力生效,因 61 处同链路);覆盖缺口=该两页特有列形态(minWidth 300/240 折叠列)未实测,静态已由 check 核验;
- 生产环境禁止执行 STATE-01 的 D03 实建发布单(红线),仅本地环境执行;
- 工单创建校验弹窗(ticket.vue)需触发规则校验失败才出现,无法稳定构造时 SKIP 该弹窗项(替代:ticketDetail 日志弹窗覆盖「Modal 内表格」同场景);
- 未执行、失败或环境阻塞的场景不得标记为通过。
