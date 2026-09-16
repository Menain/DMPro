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
 * Plus the v2/release-era additions (RELEASE_*, TICKET_RETURNED, TICKET_VIA_RELEASE_DONE, ...).
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
  DIRECT_DML_DENY: 'gov-event-type-DIRECT_DML_DENY',
  RELEASE_CREATED: 'gov-event-type-RELEASE_CREATED',
  RELEASE_APPROVED: 'gov-event-type-RELEASE_APPROVED',
  RELEASE_REJECTED: 'gov-event-type-RELEASE_REJECTED',
  RELEASE_CANCELLED: 'gov-event-type-RELEASE_CANCELLED',
  RELEASE_EXEC_STARTED: 'gov-event-type-RELEASE_EXEC_STARTED',
  RELEASE_STMT_SUCCESS: 'gov-event-type-RELEASE_STMT_SUCCESS',
  RELEASE_STMT_FAILED: 'gov-event-type-RELEASE_STMT_FAILED',
  RELEASE_HASH_DRIFT: 'gov-event-type-RELEASE_HASH_DRIFT',
  RELEASE_SCHEMA_PRECHECK_FAILED: 'gov-event-type-RELEASE_SCHEMA_PRECHECK_FAILED',
  RELEASE_SCHEMA_PRECHECK_SKIPPED: 'gov-event-type-RELEASE_SCHEMA_PRECHECK_SKIPPED',
  RELEASE_SCHEMA_DRIFT: 'gov-event-type-RELEASE_SCHEMA_DRIFT',
  RELEASE_SCHEMA_DRIFT_WARN: 'gov-event-type-RELEASE_SCHEMA_DRIFT_WARN',
  RELEASE_SCHEMA_SNAPSHOT_MISSING: 'gov-event-type-RELEASE_SCHEMA_SNAPSHOT_MISSING',
  RELEASE_DONE: 'gov-event-type-RELEASE_DONE',
  TICKET_RETURNED: 'gov-event-type-TICKET_RETURNED',
  RELEASE_STMT_RETURNED: 'gov-event-type-RELEASE_STMT_RETURNED',
  RELEASE_RETURNED: 'gov-event-type-RELEASE_RETURNED',
  RELEASE_RESUBMITTED: 'gov-event-type-RELEASE_RESUBMITTED',
  TICKET_VIA_RELEASE_DONE: 'gov-event-type-TICKET_VIA_RELEASE_DONE'
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
  DIRECT_DML_DENY: 'ios-close-circle-outline',
  RELEASE_CREATED: 'ios-rocket-outline',
  RELEASE_APPROVED: 'ios-checkmark-circle-outline',
  RELEASE_REJECTED: 'ios-close-circle-outline',
  RELEASE_CANCELLED: 'ios-remove-circle-outline',
  RELEASE_EXEC_STARTED: 'ios-play-circle-outline',
  RELEASE_STMT_SUCCESS: 'ios-checkmark-outline',
  RELEASE_STMT_FAILED: 'ios-close-outline',
  RELEASE_HASH_DRIFT: 'ios-alert-outline',
  RELEASE_SCHEMA_PRECHECK_FAILED: 'ios-close-circle-outline',
  RELEASE_SCHEMA_PRECHECK_SKIPPED: 'ios-warning-outline',
  RELEASE_SCHEMA_DRIFT: 'ios-alert-outline',
  RELEASE_SCHEMA_DRIFT_WARN: 'ios-warning-outline',
  RELEASE_SCHEMA_SNAPSHOT_MISSING: 'ios-eye-off-outline',
  RELEASE_DONE: 'ios-checkbox-outline',
  TICKET_RETURNED: 'ios-undo-outline',
  RELEASE_STMT_RETURNED: 'ios-arrow-back-outline',
  RELEASE_RETURNED: 'ios-undo-outline',
  RELEASE_RESUBMITTED: 'ios-refresh-outline',
  TICKET_VIA_RELEASE_DONE: 'ios-checkbox-outline'
};

/**
 * Promotion status / type / change-type i18n maps were removed in P5 together with the legacy
 * promotion chain (PromotionStatus / PromotionType enums deleted; ChangeType label was only
 * rendered by the retired promotion page). The v2 / release pipelines keep their own maps below.
 */

/**
 * Production release status i18n keys — covers all values of ProdReleaseStatus enum:
 * APPROVING, APPROVED, EXECUTING, DONE, PARTIAL_FAILED, REJECTED, CANCELLED, RETURNED.
 */
export const RELEASE_STATUS_I18N_KEYS = {
  APPROVING: 'gov-release-status-APPROVING',
  APPROVED: 'gov-release-status-APPROVED',
  EXECUTING: 'gov-release-status-EXECUTING',
  DONE: 'gov-release-status-DONE',
  PARTIAL_FAILED: 'gov-release-status-PARTIAL_FAILED',
  REJECTED: 'gov-release-status-REJECTED',
  CANCELLED: 'gov-release-status-CANCELLED',
  RETURNED: 'gov-release-status-RETURNED'
};
