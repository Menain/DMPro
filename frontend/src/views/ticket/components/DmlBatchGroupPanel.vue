<template>
  <div class="dml-batch-panel">
    <div class="dml-batch-panel__head">
      <span class="dml-batch-panel__title">{{ $t('fen-pi-kuai-biao-ti') }}</span>
      <CCStatus :color="statusColor" :content="statusText" />
      <Tag v-if="throttled" size="small" color="warning">{{ $t('fen-pi-zhuang-tai-throttled') }}</Tag>
      <Tag v-if="lagInvisible" size="small">{{ $t('fen-pi-yan-chi-bu-ke-jian') }}</Tag>
    </div>

    <div v-if="hasProgress" class="dml-batch-panel__progress">
      <Progress v-if="percent != null" :percent="percent" :status="barStatus" />
      <span class="dml-batch-panel__count">{{ progressText }}</span>
    </div>
    <div v-else-if="summary" class="dml-batch-panel__progress">
      <span class="dml-batch-panel__count">{{ summaryText }}</span>
    </div>

    <div v-if="retryPendingLine" class="dml-batch-panel__retrying">{{ retryPendingLine }}</div>

    <div v-if="unresolved" class="dml-batch-panel__unresolved">
      <span>{{ $t('fen-pi-cai-jue-dai-chu-li') }}</span>
      <Button v-if="canExec" size="small" type="error" ghost @click="$emit('resolve')">
        {{ $t('fen-pi-ren-gong-cai-jue') }}
      </Button>
    </div>

    <div v-if="showControls" class="dml-batch-panel__actions">
      <Button v-if="canPause" size="small" :loading="actionLoading === 'pause'" @click="runControl('pause')">
        {{ $t('zan-ting') }}
      </Button>
      <Button v-if="canResume" size="small" :loading="actionLoading === 'resume'" @click="runControl('resume')">
        {{ $t('hui-fu') }}
      </Button>
      <Button v-if="canStop" size="small" type="error" ghost :loading="actionLoading === 'stop'" @click="confirmStop">
        {{ $t('zhong-zhi') }}
      </Button>
    </div>
  </div>
</template>

<script>
import CCStatus from '@/components/widgets/CCStatus.vue';
import {
  DML_BATCH_STATUS_COLOR,
  DML_BATCH_STATUS_I18N,
  DML_BATCH_TERMINAL_TASK_STATUS,
  canPauseBatchTask,
  canResumeBatchTask,
  canStopBatchTask
} from '@/const/dmlBatch';

/**
 * B7 N3 ticket-detail DML_BATCH segment panel (design §10): batch progress, lag-throttle state and
 * the ticket-chain controls embedded inline in the segment row — the lightweight twin of
 * GhostGroupPanel with a DIFFERENT data shape (no tool session, no log tail):
 *
 * - ZERO pollers of its own (the ghost panel polls /ghost/tailStatus because its live data lives in
 *   the tool session; the batch progress lives ENTIRELY in the groupList VO projection
 *   `dmlBatchProgress`, which arrives with the page-level 5s ticket auto-refresh — implement.md N3
 *   「行内进度(投影数据,零新轮询器)」). The component is props-driven and issues requests only for
 *   the three control actions.
 * - Controls are the /dmlBatch/* endpoints, visible ONLY for DM_GHOST_EXEC holders (裁决 #15 —
 *   canExec prop, the B6 ghost double-condition discipline); without it the panel is read-only and
 *   NO /dmlBatch/* request is ever issued (权限矩阵).
 * - The UNRESOLVED window-crash verdict entry emits `resolve` — the adjudication modal is
 *   group-scoped in ticketDetail (it submits retryGroup with the resolution), so the panel only
 *   surfaces the trigger.
 * - Pause is batch-granular (the current batch finishes first); stop keeps the partial success and
 *   has NO automatic rollback — the confirm modal states both (三点明示, 裁决 #11).
 */
export default {
  name: 'DmlBatchGroupPanel',
  components: { CCStatus },
  props: {
    ticketId: {
      type: [Number, String],
      required: true
    },
    group: {
      type: Object,
      required: true
    },
    seg: {
      type: Object,
      required: true
    },
    canExec: {
      type: Boolean,
      default: false
    }
  },
  emits: ['refresh', 'resolve'],
  data() {
    return {
      actionLoading: ''
    };
  },
  computed: {
    // the group-level projection belongs to THIS segment only when the task's binding matches
    // (the segmentPanelGroup taskId-matching precedent; earlier batch segments of a multi-batch
    // group render from their frozen summary instead)
    progress() {
      const p = this.group.dmlBatchProgress;
      if (p && p.segmentIndex === this.seg.segIdx) {
        return p;
      }
      return null;
    },
    hasProgress() {
      return this.progress != null && this.progress.batchesDone != null;
    },
    summary() {
      return this.seg.summary || null;
    },
    segStatus() {
      return this.seg.status || 'PENDING';
    },
    taskStatus() {
      return this.progress ? this.progress.taskStatus || '' : '';
    },
    statusColor() {
      if (this.taskStatus) {
        return DML_BATCH_STATUS_COLOR[this.taskStatus] || '#999999';
      }
      // no live task attached: derive from the segment status
      const bySeg = { PENDING: '#999999', EXECUTING: '#FFA30E', SUCCESS: '#52C41A', FAILED: '#FF1815' };
      return bySeg[this.segStatus] || '#999999';
    },
    statusText() {
      if (this.taskStatus) {
        const key = DML_BATCH_STATUS_I18N[this.taskStatus];
        return key ? this.$t(key) : this.taskStatus;
      }
      const bySeg = {
        PENDING: 'ticket-execution-waiting',
        EXECUTING: 'ticket-execution-running',
        SUCCESS: 'ticket-execution-complete',
        FAILED: 'ticket-execution-failed'
      };
      const key = bySeg[this.segStatus];
      return key ? this.$t(key) : this.segStatus;
    },
    throttled() {
      return !!(this.progress && this.progress.lagState && this.progress.lagState.throttled);
    },
    lagInvisible() {
      const lag = this.progress && this.progress.lagState;
      return !!lag && lag.visible === false;
    },
    percent() {
      if (this.progress && this.progress.percentApprox != null) {
        return this.progress.percentApprox;
      }
      return null;
    },
    barStatus() {
      if (this.taskStatus === 'COMPLETE' || this.segStatus === 'SUCCESS') {
        return 'success';
      }
      if (this.taskStatus === 'FAILURE' || this.taskStatus === 'CANCEL' || this.segStatus === 'FAILED') {
        return 'wrong';
      }
      if (this.taskStatus === 'RUNNING') {
        return 'active';
      }
      return 'normal';
    },
    progressText() {
      const p = this.progress;
      if (p && p.percentApprox != null) {
        return this.$t('fen-pi-jin-du', [p.batchesDone, p.rowsDone, p.percentApprox]);
      }
      if (p) {
        return this.$t('fen-pi-jin-du-wu-fen-mu', [p.batchesDone, p.rowsDone]);
      }
      return '';
    },
    summaryText() {
      const s = this.summary;
      return this.$t('fen-pi-jin-du-wu-fen-mu', [s.batchesDone ?? 0, s.rowsDone ?? 0]);
    },
    retryPendingLine() {
      const p = this.progress;
      if (p && p.retryPending && p.lastBatchError) {
        return this.$t('fen-pi-zhong-shi-zhong', [p.lastBatchError]);
      }
      return '';
    },
    // the resolution trigger keys on the SEGMENT SUMMARY (the backend guard's sole authority —
    // N2 补充裁决 (b)); the projection's unresolvedIntent is display material only
    unresolved() {
      return this.segStatus === 'FAILED' && !!(this.summary && this.summary.unresolvedIntent === true);
    },
    showControls() {
      return (
        this.canExec &&
        this.progress != null &&
        this.segStatus === 'EXECUTING' &&
        this.group.execStatus === 'EXECUTING' &&
        !DML_BATCH_TERMINAL_TASK_STATUS.includes(this.taskStatus)
      );
    },
    canPause() {
      return canPauseBatchTask(this.taskStatus);
    },
    canResume() {
      return canResumeBatchTask(this.taskStatus);
    },
    canStop() {
      return canStopBatchTask(this.taskStatus);
    }
  },
  methods: {
    async runControl(kind) {
      if (this.actionLoading) {
        return;
      }
      const serviceFn = { pause: this.$services.dmlBatchPause, resume: this.$services.dmlBatchResume }[kind];
      this.actionLoading = kind;
      try {
        const res = await serviceFn({ data: this.controlPayload() });
        if (res.success) {
          this.$Message.success(this.$t('cao-zuo-cheng-gong'));
          this.$emit('refresh');
        }
      } finally {
        this.actionLoading = '';
      }
    },
    confirmStop() {
      this.$Modal.confirm({
        title: this.$t('fen-pi-zhong-zhi-que-ren'),
        content: this.$t('fen-pi-zhong-zhi-jing-gao'),
        className: 'dm-modal-destructive',
        okText: this.$t('zhong-zhi'),
        loading: true,
        onOk: async () => {
          try {
            this.actionLoading = 'stop';
            const res = await this.$services.dmlBatchStop({ data: this.controlPayload() });
            if (res.success) {
              this.$Message.success(this.$t('cao-zuo-cheng-gong'));
              this.$emit('refresh');
            }
          } finally {
            this.actionLoading = '';
            this.$Modal.remove();
          }
        }
      });
    },
    controlPayload() {
      return {
        ticketId: Number(this.ticketId),
        groupId: this.group.groupId,
        segmentIndex: this.seg.segIdx
      };
    }
  }
};
</script>

<style lang="less" scoped>
.dml-batch-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  background: var(--bg-secondary, #f8fafc);
  border-radius: 6px;
}
.dml-batch-panel__head {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}
.dml-batch-panel__title {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary, #181d26);
}
.dml-batch-panel__progress {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.dml-batch-panel__count {
  font-size: 13px;
  color: var(--text-secondary, #707070);
}
.dml-batch-panel__retrying {
  font-size: 13px;
  color: #ffa30e;
  white-space: pre-wrap;
  word-break: break-all;
}
.dml-batch-panel__unresolved {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
  color: var(--error-color, #ff1815);
}
.dml-batch-panel__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
</style>
