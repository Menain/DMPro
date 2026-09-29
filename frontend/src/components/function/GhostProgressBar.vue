<template>
  <div v-if="status" class="ghost-progress">
    <div class="ghost-progress__head">
      <CCStatus :color="statusColor" :content="statusText" />
      <span class="ghost-progress__phase">{{ $t('wu-suo-jie-duan') }}: {{ phaseText }}</span>
      <span v-if="hasBar" class="ghost-progress__count">{{ $t('wu-suo-kao-bei-hang-shu', [status.curValue, status.maxValue]) }}</span>
    </div>
    <Progress v-if="hasBar" :percent="percent" :status="barStatus" />
    <div v-if="status.message" class="ghost-progress__message">{{ status.message }}</div>
    <div v-if="status.failReason" class="ghost-progress__fail">{{ $t('wu-suo-shi-bai-yuan-yin') }}: {{ status.failReason }}</div>
  </div>
</template>

<script>
import CCStatus from '@/components/widgets/CCStatus.vue';
import { GHOST_STATUS_COLOR, GHOST_STATUS_I18N, GHOST_PHASE_I18N } from '@/const/ghost';

/**
 * Pure-display gh-ost progress panel (B6; embedded by the ticket-detail ghost group panel).
 * Props = one GhostStatusDTO-shaped object ({curValue, maxValue, useProgress, message,
 * status, phase, failReason}); the component issues no requests and owns no state.
 */
export default {
  name: 'GhostProgressBar',
  components: { CCStatus },
  props: {
    status: {
      type: Object,
      default: null
    }
  },
  computed: {
    statusColor() {
      return GHOST_STATUS_COLOR[this.status.status] || '#999999';
    },
    statusText() {
      const key = GHOST_STATUS_I18N[this.status.status];
      if (!key) {
        return this.status.status || '-';
      }
      return this.$t(key);
    },
    phaseText() {
      const key = GHOST_PHASE_I18N[this.status.phase];
      if (!key) {
        return this.status.phase || '-';
      }
      return this.$t(key);
    },
    hasBar() {
      return this.status.useProgress && this.status.maxValue > 0;
    },
    percent() {
      const ratio = (this.status.curValue / this.status.maxValue) * 100;
      return Math.max(0, Math.min(100, Math.round(ratio)));
    },
    barStatus() {
      if (this.status.status === 'COMPLETED') {
        return 'success';
      }
      if (this.status.status === 'FAILED') {
        return 'wrong';
      }
      if (this.status.status === 'RUNNING') {
        return 'active';
      }
      return 'normal';
    }
  }
};
</script>

<style lang="less" scoped>
.ghost-progress {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.ghost-progress__head {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}
.ghost-progress__phase {
  font-size: 13px;
  color: var(--text-primary);
}
.ghost-progress__count {
  font-size: 13px;
  color: var(--text-secondary);
}
.ghost-progress__message {
  font-size: 13px;
  color: var(--text-secondary);
  white-space: pre-wrap;
  word-break: break-all;
}
.ghost-progress__fail {
  font-size: 13px;
  color: var(--error-color);
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
