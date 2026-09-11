export const dbChangeApi = {
  dbChangePreSubmit: '/api/entry/dbChangeGovern/preSubmit',
  dbChangeCorrectStatement: '/api/entry/dbChangeGovern/correctStatement',
  dbChangeStmtTimeline: '/api/entry/dbChangeGovern/stmtTimeline',
  dbChangeSplitPreview: '/api/entry/dbChangeGovern/splitPreview',
  dbChangeEventTimeline: '/api/entry/dbChangeGovern/eventTimeline',
  dbChangeAvailableRevisions: '/api/entry/dbChangeGovern/availableRevisions',
  dbChangeRevisionDetail: '/api/entry/dbChangeGovern/revisionDetail',
  dbChangePromote: '/api/entry/dbChangeGovern/promote',
  dbChangePromotionList: '/api/entry/dbChangeGovern/promotionList',
  // 注意：入参 FO 字段 id 语义为 promotionId（后端复用 LogicalDbIdFO）
  dbChangePromotionDetail: '/api/entry/dbChangeGovern/promotionDetail',
  dbChangeDirectDmlSubmit: '/api/entry/dbChangeGovern/directDmlSubmit',
  govLedgerDbs: '/api/entry/govledger/dbs',
  govLedgerListByDb: '/api/entry/govledger/listByDb',
  govLedgerDetail: '/api/entry/govledger/detail',
  govReleaseCreate: '/api/entry/govrelease/create',
  govReleaseList: '/api/entry/govrelease/list',
  govReleaseDetail: '/api/entry/govrelease/detail',
  govReleaseRetryStmt: '/api/entry/govrelease/retryStmt'
};
