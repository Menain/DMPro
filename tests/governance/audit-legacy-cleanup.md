# 审计存量数据一次性清理手册（C8 / M4）

> 任务源：`10-13-audit-chain-enhancement` 裁决 1（O-C1-2 存量审计行含明文凭据——**仅测试库，直接清空**）。
> 依据：平台未在生产部署；存量行只存在于开发/测试库。凭据轮转口径 = 曾入库的测试凭据**全部作废重发**。
> 应用日志侧：logback gz 归档 maxHistory=60 天自然过期（可选手工清理，见 §4）。
> **本手册零凭据**：只含口径 SQL 与占位符（`<...>`），任何真实密码/AK/token 不得写入本文件或执行记录。
> 执行主体：**用户**（数据主权 + 真库操作仅测试环境纪律）；执行记录留联调档案（§6）。

---

## 1. 前置确认（执行前逐项核对）

| # | 确认项 | 通过标准 |
|---|---|---|
| 1 | 目标环境 = 测试库 | 核对连接串主机/端口/库名与测试环境登记一致；**平台未生产部署**声明成立（裁决 1 依据） |
| 2 | 后端进程状态 | 清理期间建议停后端（TRUNCATE 与在线写入并发虽安全，但 COUNT 对账需要静默窗口）；重启由用户自行执行 |
| 3 | 备份 | 测试库快照或 mysqldump（可选，测试数据可弃则跳过；跳过决定记入 §6） |
| 4 | 迁移版本 | `dm_update_history` 中 C8 迁移（V202610130002/3/4）已 SUCCESS（清理与新 schema 无依赖，仅顺带核对） |

## 2. 量级盘点（只计数与键名判定，不回显任何值）

```sql
-- ① 泄漏面五类型总行数与时间窗（含 legacy 两类型兜底——本代码库历史零写入点，理论上为 0）
SELECT audit_type, COUNT(*), MIN(operate_date), MAX(operate_date)
FROM dm_mon_op_audit
WHERE audit_type IN ('UPDATE_DATA_SOURCE_CONFIG', 'ADD_SUB_ACCOUNT',
                     'QUERY_DATA_SOURCE_CONFIG', 'UPDATE_DS_ACCOUNT_PASSWD', 'DELETE_DS_ACCOUNT_PASSWD')
GROUP BY audit_type;

-- ② 精确收窄（键名判定，SELECT 列表无 log_info，不回显值）：含 password 键且未被掩码的行
SELECT id, audit_type, operate_date
FROM dm_mon_op_audit
WHERE audit_type IN ('UPDATE_DATA_SOURCE_CONFIG', 'ADD_SUB_ACCOUNT')
  AND log_info LIKE '%"password"%'
  AND log_info NOT LIKE '%"password":"******"%';

-- ③ 受影响资产集合（§5 轮转清单的唯一输入；resource_value 即 dsId / 子账号 uid）
SELECT DISTINCT audit_type, resource_value
FROM dm_mon_op_audit
WHERE audit_type IN ('UPDATE_DATA_SOURCE_CONFIG', 'ADD_SUB_ACCOUNT',
                     'QUERY_DATA_SOURCE_CONFIG', 'UPDATE_DS_ACCOUNT_PASSWD', 'DELETE_DS_ACCOUNT_PASSWD');

-- ④ 三表总量（执行前后 COUNT 对账用）
SELECT 'dm_mon_op_audit' t, COUNT(*) FROM dm_mon_op_audit
UNION ALL SELECT 'dm_mon_biz_log', COUNT(*) FROM dm_mon_biz_log
UNION ALL SELECT 'dm_exec_sql_audit', COUNT(*) FROM dm_exec_sql_audit;
```

注意：口径②的 LIKE 组合对 `ssl*` 键有假阴性（键名不含 password）。方案 A（三表全清）下无需精确清单；若选方案 B 且需要逐行判定，导出 id 清单后用 `AuditSanitizeUtils.isSensitiveKey`（工具已存在）离线判定，**判定过程不回显值**。

## 3. 清空操作（两方案并列，用户择一）

### 方案 A：三表全清（测试库最简，推荐）

审计三表整体清空——测试库不需要保留任何历史审计链；C8 之后新写入的行即为干净基线：

```sql
TRUNCATE TABLE dm_mon_op_audit;
TRUNCATE TABLE dm_mon_biz_log;
TRUNCATE TABLE dm_exec_sql_audit;
```

- TRUNCATE 重置自增 id（审计行无外部按 id 引用，安全）；
- 三表无外键约束（平台元数据库全库无 FK），无执行顺序要求；
- 执行后重跑 §2 口径④，三表 COUNT 均应为 0。

### 方案 B：精确 DELETE（仅删受影响行，保留其余审计链）

```sql
-- B1：泄漏面五类型全删（凭据暴露行按已暴露处理，不做 UPDATE 脱敏——修改历史审计行本身是治理动作，
--     测试库场景下 DELETE 更简单且不损伤任何真实合规面）
DELETE FROM dm_mon_op_audit
WHERE audit_type IN ('UPDATE_DATA_SOURCE_CONFIG', 'ADD_SUB_ACCOUNT',
                     'QUERY_DATA_SOURCE_CONFIG', 'UPDATE_DS_ACCOUNT_PASSWD', 'DELETE_DS_ACCOUNT_PASSWD');

-- B2（可选加严）：仅删口径②命中行（保留已掩码/无 password 键的同类型行）
-- DELETE FROM dm_mon_op_audit WHERE id IN (<口径②导出的 id 清单>);
```

- 执行前后各跑一次 §2 口径①/④，差值记入 §6；
- 大表分批：测试库量级下无需分批；如行数 > 10 万，按 `AND id BETWEEN <lo> AND <hi>` 分段。

## 4. 应用日志侧（60 天自然过期）

- 泄漏双出口之二：`logOperation` 的 `log.info` 明文行进入应用日志；logback 日滚 + gz 归档，`maxHistory=60` 天——C1 修复（e5ce2b6e，2026-10-11）前的最后泄漏行**至迟约 2026-12-10 自然滚没**，无需强制手工清理；
- 可选手工清理：直接删除归档目录中早于修复部署日的 gz 文件（路径 = logback 配置的 `<logPath>/<yyyy-MM-dd>/`）；
- 验证口径（**仅计数，不回显内容**）：

```bash
# 每个归档日目录计数含 "password" 键的行数（清理前应为非 0，清理/滚没后应为 0）
grep -c '"password"' <logPath>/<yyyy-MM-dd>/alone-*.log.gz
```

## 5. 凭据轮转清单口径（轮转动作 = 测试凭据作废重发）

以 §2 口径③的资产集合为输入，**清单只含 dsId/账号标识，不含任何值**：

| # | 暴露类型 | 轮转范围 | 动作 |
|---|---|---|---|
| 1 | DB 连接密码 | 口径③中 `UPDATE_DATA_SOURCE_CONFIG`/`QUERY_DATA_SOURCE_CONFIG`/`UPDATE_DS_ACCOUNT_PASSWD` 命中的全部 dsId | 对应数据源的 DB 账号密码**作废重发**（测试库口径下全量轮转，不做"是否非空"精查） |
| 2 | SSL 材料 | 同上 dsId 集合中配置过 sslCaData/sslCaPassword/sslClientCertData/sslClientKeyData/sslClientKeyPassword 者 | 换发测试证书/密钥 |
| 3 | 平台子账号初始密码 | 口径③中 `ADD_SUB_ACCOUNT` 命中的 resource_value（子账号 uid）集合 | 强制重置（若审计显示其后已有 UPDATE_SUB_ACCOUNT_PWD/UPDATE_ACCOUNT_PWD 行，可视为已轮转，记入 §6） |
| 4 | 排查面纪律 | 本手册执行过程 | 口径②/③导出文件与判定过程**不得二次泄漏**（凭据不入任何文档/聊天记录；导出文件用后即删） |

## 6. 执行记录表（留联调档案）

| 项 | 内容 |
|---|---|
| 执行人 | <填写> |
| 执行时间 | <填写> |
| 目标环境（连接串主机/库名） | <填写，不含密码> |
| 选用方案 | A（三表全清） / B（精确 DELETE，含 B1/B2 口径） |
| 执行前 COUNT（op_audit / biz_log / sql_audit） | <填写 §2 口径④ 结果> |
| 执行后 COUNT | <填写> |
| 口径②命中行数 | <填写> |
| 轮转完成情况（§5 四类逐项） | <填写：已完成/豁免及原因> |
| 应用日志侧处置 | 自然过期 / 手工清理（目录与日期范围） |
| 备注 | <填写> |

## 7. 附注：C8 M5 override 表唯一约束的前置检查口径

C8 迁移 `V202610130004` 为两张 override 表建生成列唯一约束（`uk_route_override_active` / `uk_gate_override_active`）。**建约束前存量重复占用 active_key 的行会让 UNIQUE 创建失败**（错误码 1062 被迁移框架吞掉 → 约束静默缺失）。注意：生成列判活**不看 expire_at**（MySQL 生成列禁用 NOW() 等非确定函数）——凡 `status='PENDING'`（路由表）/ `consumed=0 AND revoked=0 AND expired=0`（gate 表）的行都持有 active_key，**含已过时钟有效期但从未被收敛的存量行**。因此普查口径必须去掉 expire_at 过滤，覆盖全部占位行：

```sql
-- 路由 override：同一 (release_id, stmt_id) 的 PENDING 占位行多于一条 → 需先收敛（不看 expire_at）
SELECT release_id, stmt_id, COUNT(*) c
FROM dm_gov_route_override
WHERE status = 'PENDING'
GROUP BY release_id, stmt_id HAVING c > 1;

-- schema gate override：同粒度（release 级按 gate_type，stmt 级按 gate_type+stmt_id）
SELECT release_id, IFNULL(stmt_id, 0) stmt_key, gate_type, COUNT(*) c
FROM dm_gov_schema_gate_override
WHERE consumed = 0 AND revoked = 0 AND expired = 0
GROUP BY release_id, IFNULL(stmt_id, 0), gate_type HAVING c > 1;
```

有命中行的处置（过期语义收敛）：
- **已过时钟有效期的行（expire_at ≤ NOW()）全部收敛**——它们本就是派生 EXPIRED，收敛只读物化：路由表 `UPDATE ... SET status='EXPIRED' WHERE id=<行id>`；gate 表 `UPDATE ... SET expired=1 WHERE id=<行id>`；
- **未过期的重复行**（历史竞态产物，罕见）：保留最新一条（id 最大），其余按已过期处理（同上 UPDATE；路由表对这类 expire_at 仍在未来的行建议**连带 `SET expire_at=NOW()`**——读侧 deriveStatus 按时钟派生显示状态，只改 status 会让 UI 继续显示 PENDING，虽然消费/查找均已不可达）。

测试库场景下若 §3 选了方案 A 且 override 表也可弃，可直接 `TRUNCATE dm_gov_route_override; TRUNCATE dm_gov_schema_gate_override;`（两表只承载短时效豁免授权，清空无历史证据损失——消费痕迹在 dm_db_change_event 与 op_audit）。

**重建约束的注意点**：迁移框架对 1062 是"跳过并继续"（`AbstractUpgradeJavaMigration.safeExecute` IGNORABLE_ERROR_CODES），迁移整体仍会在 `dm_update_history` 记 SUCCESS——**若迁移已经带着重复行跑过一次，事后仅重启后端不会重跑该版本、约束不会自动补建**。两种补救（测试库口径）任选其一：
1. 收敛重复行后，删除 `dm_update_history` 中 `V202610130004` 的记录行，重启后端让迁移整体重跑（各语句幂等：已存在的列/索引报 1060/1061 被跳过，仅补建缺失的 UNIQUE）；
2. 收敛重复行后，手工执行两条 `ALTER TABLE ... ADD UNIQUE KEY ...`（语句原文见迁移脚本，执行时报 1061 即说明该约束本已存在）。

完成后核对 `SHOW INDEX FROM dm_gov_route_override` 含 `uk_route_override_active`、`SHOW INDEX FROM dm_gov_schema_gate_override` 含 `uk_gate_override_active`（**两表都要核**——约束可能只缺其中一张）。

> 注：`dm_gov_schema_gate_override.expired` 列为 V202610130004 新增（生成列判活三元之一），首启迁移后才有；迁移前自查该表可去掉 `AND expired = 0` 条件（等价：存量行 expired 默认 0）。
