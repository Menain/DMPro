// Ghost (lock-free DDL / gh-ost) display maps — consumed by GhostProgressBar and the ticket
// detail group panel. Colors follow the TICKET_STATUS_COLOR convention: in-flight/paused orange,
// success green, failure red, neutral/aborted grey.

export const GHOST_STATUS_COLOR = {
  INIT: '#999999',
  RUNNING: '#FFA30E',
  PAUSED: '#FFA30E',
  COMPLETED: '#52C41A',
  FAILED: '#FF1815',
  ABORTED: '#999999'
};

export const GHOST_STATUS_I18N = {
  INIT: 'wu-suo-status-init',
  RUNNING: 'wu-suo-status-running',
  PAUSED: 'wu-suo-status-paused',
  COMPLETED: 'wu-suo-status-completed',
  FAILED: 'wu-suo-status-failed',
  ABORTED: 'wu-suo-status-aborted'
};

export const GHOST_PHASE_I18N = {
  READY: 'wu-suo-phase-ready',
  GHOST_TABLE_CREATED: 'wu-suo-phase-ghost-table',
  COPYING: 'wu-suo-phase-copying',
  ROW_COPY_COMPLETE: 'wu-suo-phase-copy-complete',
  CUT_OVER: 'wu-suo-phase-cut-over',
  DONE: 'wu-suo-phase-done'
};

export const GHOST_TERMINAL_TOOL_STATUS = ['COMPLETED', 'FAILED', 'ABORTED'];
