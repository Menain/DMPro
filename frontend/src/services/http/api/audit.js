export const auditApi = {
  // rdp
  rdpAuditExport: '/api/entry/audit/export',
  rdpAuditQueryAll: '/api/entry/audit/queryAll',
  rdpAuditQueryListCondition: '/api/entry/audit/queryListCondition',
  // C8 M3: the audit-detail endpoint (replaces the never-implemented /logview/grep/operationlog)
  rdpAuditDetail: '/api/entry/audit/detail',

  // dm
  dmAuditSqlAuditQueryAll: '/api/entry/audit/sqlAudit/queryAll',
  dmAuditSqlAuditListDs: '/api/entry/audit/sqlAudit/listDs',
  dmAuditSqlAuditOperateUser: '/api/entry/audit/sqlAudit/operateUser'
};
