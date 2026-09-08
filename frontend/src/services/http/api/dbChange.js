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
  dbChangeDirectDmlSubmit: '/api/entry/dbChangeGovern/directDmlSubmit'
};
