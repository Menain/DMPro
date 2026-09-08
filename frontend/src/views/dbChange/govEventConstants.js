/**
 * Shared governance event-type constants.
 *
 * Used by both the ticket-detail page (ticket-level event timeline) and the
 * promotion-detail page (promotion/revision-level event timeline) so the two
 * views stay in sync when GovEventType evolves.
 *
 * The keys MUST cover every value of the backend enum
 * `com.clougence.clouddm.platform.dal.model.dbchange.GovEventType`:
 *   SUBMIT, SYSTEM_APPROVE, SYSTEM_CONFIRM, REVISION_FROZEN, FREEZE_ANOMALY,
 *   CORRECTION, FAIL_NOTIFIED, PROMOTION_CREATED, GATE_DENY, STATUS_SYNC,
 *   GUARD_PASS, GUARD_DENY, AUTO_CONFIRM, DIRECT_DML_SUBMIT, DIRECT_DML_DENY
 */
export const GOV_EVENT_TYPE_I18N_KEYS = {
  SUBMIT: 'gov-event-type-SUBMIT',
  SYSTEM_APPROVE: 'gov-event-type-SYSTEM_APPROVE',
  SYSTEM_CONFIRM: 'gov-event-type-SYSTEM_CONFIRM',
  REVISION_FROZEN: 'gov-event-type-REVISION_FROZEN',
  FREEZE_ANOMALY: 'gov-event-type-FREEZE_ANOMALY',
  CORRECTION: 'gov-event-type-CORRECTION',
  FAIL_NOTIFIED: 'gov-event-type-FAIL_NOTIFIED',
  PROMOTION_CREATED: 'gov-event-type-PROMOTION_CREATED',
  GATE_DENY: 'gov-event-type-GATE_DENY',
  STATUS_SYNC: 'gov-event-type-STATUS_SYNC',
  GUARD_PASS: 'gov-event-type-GUARD_PASS',
  GUARD_DENY: 'gov-event-type-GUARD_DENY',
  AUTO_CONFIRM: 'gov-event-type-AUTO_CONFIRM',
  DIRECT_DML_SUBMIT: 'gov-event-type-DIRECT_DML_SUBMIT',
  DIRECT_DML_DENY: 'gov-event-type-DIRECT_DML_DENY'
};

export const GOV_EVENT_ICONS = {
  SUBMIT: 'ios-paper-plane-outline',
  SYSTEM_APPROVE: 'ios-checkmark-circle-outline',
  SYSTEM_CONFIRM: 'ios-checkmark-circle-outline',
  REVISION_FROZEN: 'ios-lock-outline',
  FREEZE_ANOMALY: 'ios-alert-outline',
  CORRECTION: 'ios-create-outline',
  FAIL_NOTIFIED: 'ios-notifications-outline',
  PROMOTION_CREATED: 'ios-rocket-outline',
  GATE_DENY: 'ios-close-circle-outline',
  STATUS_SYNC: 'ios-swap-outline',
  GUARD_PASS: 'ios-checkmark-outline',
  GUARD_DENY: 'ios-close-outline',
  AUTO_CONFIRM: 'ios-checkmark-circle-outline',
  DIRECT_DML_SUBMIT: 'ios-paper-plane-outline',
  DIRECT_DML_DENY: 'ios-close-circle-outline'
};

/**
 * Promotion status i18n keys — covers all 9 values of PromotionStatus enum:
 * CREATED, APPROVING, APPROVED, CONFIRMED, EXECUTING, SUCCEEDED, REJECTED, CANCELLED, FAILED.
 */
export const PROMOTION_STATUS_I18N_KEYS = {
  CREATED: 'gov-promotion-status-CREATED',
  APPROVING: 'gov-promotion-status-APPROVING',
  APPROVED: 'gov-promotion-status-APPROVED',
  CONFIRMED: 'gov-promotion-status-CONFIRMED',
  EXECUTING: 'gov-promotion-status-EXECUTING',
  SUCCEEDED: 'gov-promotion-status-SUCCEEDED',
  REJECTED: 'gov-promotion-status-REJECTED',
  CANCELLED: 'gov-promotion-status-CANCELLED',
  FAILED: 'gov-promotion-status-FAILED'
};

/**
 * Promotion type i18n keys — PRE_PROMOTION / DIRECT_PROD_DML.
 */
export const PROMOTION_TYPE_I18N_KEYS = {
  PRE_PROMOTION: 'gov-promotion-type-PRE_PROMOTION',
  DIRECT_PROD_DML: 'gov-promotion-type-DIRECT_PROD_DML'
};

/**
 * Change type i18n keys — DDL / DML / MIXED.
 */
export const CHANGE_TYPE_I18N_KEYS = {
  DDL: 'gov-change-type-DDL',
  DML: 'gov-change-type-DML',
  MIXED: 'gov-change-type-MIXED'
};
