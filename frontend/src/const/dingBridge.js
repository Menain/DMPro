// DingTalk approval bridge outbound (task 10-09) display maps — consumed by the ticket-detail and
// release-detail outbound panels. Status vocabulary = the dm_approval.outbound_status wire values
// (design §3.1; a NULL column reads as NOT_STARTED). Tag colors follow the semantic convention of
// the ticket-detail Tags: in-flight orange (warning), success green, failure red, neutral default.

export const BRIDGE_OUTBOUND_STATUS_TAG = {
  NOT_STARTED: 'default',
  STARTING: 'warning',
  STARTED: 'success',
  FAILED: 'error'
};

export const BRIDGE_OUTBOUND_STATUS_I18N = {
  NOT_STARTED: 'ding-qiao-chu-zhan-wei-fa-qi',
  STARTING: 'ding-qiao-chu-zhan-fa-qi-zhong',
  STARTED: 'ding-qiao-chu-zhan-yi-fa-qi',
  FAILED: 'ding-qiao-chu-zhan-shi-bai'
};

// NULL column = NOT_STARTED (design §3.1 vocabulary); an unknown wire value falls back to the raw
// token rendered verbatim (the maps above miss → the panel shows the literal value)
export function bridgeOutboundStatusOf(raw) {
  return raw || 'NOT_STARTED';
}

// Reissue-button availability mirrors the backend guard matrix (DingBridgeOutboundService.
// reissueFlow): STARTED → ALREADY_STARTED reject, STARTING → IN_FLIGHT reject, FAILED ∨
// NOT_STARTED/NULL → allowed. The remaining backend rejects are NOT client-mirrorable by design:
// DISABLED (kill switch / baseUrl KVs are root-account-only, invisible to sub-accounts) and
// not-found/CHANNEL_MISMATCH (implied by the panel's own visibility) — they surface as the
// backend's localized i18n error through the standard error modal.
export function canReissueBridgeFlow(outboundStatus) {
  const status = bridgeOutboundStatusOf(outboundStatus);
  return status === 'FAILED' || status === 'NOT_STARTED';
}
