// B7 lock-free DML (无锁数据变更) display maps — consumed by the ticket-detail DML batch panel and
// the segment badges. Task statuses are the dm_exec_async_task AsyncTaskStatus wire values of the
// DML_BATCH bizType; colors follow the GHOST_STATUS_COLOR convention (in-flight/paused orange,
// success green, failure red, neutral grey). i18n keys reuse the wu-suo-status-* family wherever
// the semantics match (复用纪律), fen-pi-* only where they do not.

export const DML_BATCH_STATUS_COLOR = {
  INIT: '#999999',
  WAIT_START: '#999999',
  BLOCK: '#FFA30E',
  RUNNING: '#FFA30E',
  PAUSE: '#FFA30E',
  PAUSING: '#FFA30E',
  CANCELING: '#999999',
  COMPLETE: '#52C41A',
  FAILURE: '#FF1815',
  CANCEL: '#999999'
};

export const DML_BATCH_STATUS_I18N = {
  INIT: 'wu-suo-status-init',
  WAIT_START: 'wu-suo-status-init',
  // BLOCK = framework slot wait (NOT the lag throttle — throttling shows via the lagState tag
  // while the task status stays RUNNING between delayTask ticks)
  BLOCK: 'ticket-execution-waiting',
  RUNNING: 'wu-suo-status-running',
  PAUSE: 'wu-suo-status-paused',
  PAUSING: 'wu-suo-status-paused',
  CANCELING: 'wu-suo-status-aborted',
  COMPLETE: 'wu-suo-status-completed',
  FAILURE: 'wu-suo-status-failed',
  CANCEL: 'wu-suo-status-aborted'
};

export const DML_BATCH_TERMINAL_TASK_STATUS = ['COMPLETE', 'FAILURE', 'CANCEL'];

// Control availability mirrors the backend state gates (DmlBatchControlService):
// pause ← RUNNING/BLOCK; resume ← PAUSE; stop ← RUNNING/BLOCK/PAUSE/INIT.
export function canPauseBatchTask(taskStatus) {
  return taskStatus === 'RUNNING' || taskStatus === 'BLOCK';
}

export function canResumeBatchTask(taskStatus) {
  return taskStatus === 'PAUSE';
}

export function canStopBatchTask(taskStatus) {
  return ['RUNNING', 'BLOCK', 'PAUSE', 'INIT'].includes(taskStatus);
}

// Segment-type badge → i18n key (three-way: GHOST yellow / DML_BATCH yellow / AUTOEXEC default).
export function segmentTypeBadgeI18n(segType) {
  if (segType === 'GHOST') {
    return 'wu-suo-lu-you-ghost';
  }
  if (segType === 'DML_BATCH') {
    return 'fen-pi-lu-you-biao-qian';
  }
  return 'wu-suo-seg-zhi-jie';
}

// The sweep's terminal-replay copy for a stopped task is mechanical English
// ("dml batch task CANCEL (completion writeback lost)") — map it to the ABORTED semantics copy
// (N2 check LOW #5: 部分成功保留 + 无自动回滚). Returns null when no mapping applies.
export function mapDmlBatchSegmentError(error) {
  if (!error) {
    return null;
  }
  if (error.includes('dml batch task CANCEL')) {
    return 'fen-pi-zhong-zhi-bu-fen-cheng-gong';
  }
  return null;
}
