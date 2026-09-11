<template>
  <div class="page-shell db-change-release-detail">
    <div class="page-shell__body">
      <div v-if="loading" class="gov-detail-loading">
        <Spin size="large" />
      </div>
      <template v-else-if="detail">
        <section class="page-section">
          <div class="page-section__title">{{ $t('gov-release-overview') }}</div>
          <div class="gov-overview-grid">
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('gov-release-no') }}</span>
              <span>{{ detail.releaseNo }}</span>
            </div>
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('gov-release-title') }}</span>
              <span>{{ detail.title }}</span>
            </div>
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('gov-release-status-label') }}</span>
              <Tag :color="statusColor(detail.status)">{{ releaseStatusText(detail.status) }}</Tag>
            </div>
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('gov-release-creator') }}</span>
              <span>{{ detail.creatorUid || '-' }}</span>
            </div>
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('gov-release-approval-ticket') }}</span>
              <span v-if="detail.approvalId">
                <a @click="goToTicket(detail.approvalId)">{{ detail.approvalId }}</a>
              </span>
              <span v-else>-</span>
            </div>
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('chuang-jian-shi-jian') }}</span>
              <span>{{ formatDateTime(detail.gmtCreate) }}</span>
            </div>
          </div>
        </section>

        <section v-if="showGateResult" class="page-section">
          <div class="page-section__title">{{ $t('gov-release-gate-result') }}</div>
          <pre class="gov-gate-result-json">{{ formatJson(detail.gateResult) }}</pre>
        </section>

        <section v-if="detail.stmtGroups && detail.stmtGroups.length" class="page-section">
          <div class="page-section__title">{{ $t('gov-release-stmt-groups') }}</div>
          <div class="gov-stmt-groups">
            <div v-for="group in detail.stmtGroups" :key="`${group.prodDsId}-${group.prodDbName}`" class="gov-stmt-group">
              <div class="gov-stmt-group__header">
                <div class="gov-stmt-group__db">
                  <Icon type="ios-server-outline" />
                  <span>{{ group.prodDbName }}</span>
                </div>
                <span class="gov-stmt-group__ds-id">{{ $t('gov-release-ds-label') }}{{ group.prodDsId }}</span>
              </div>
              <div class="gov-stmt-group__body">
                <div v-for="stmt in group.stmts" :key="stmt.id" class="gov-stmt-card">
                  <div class="gov-stmt-card__header">
                    <span class="gov-stmt-card__seq">{{ $t('gov-release-seq-label') }}{{ stmt.seq }}</span>
                    <span :class="['gov-stmt-card__status', `is-${stmt.execStatus}`]">{{ stmt.execStatus }}</span>
                  </div>
                  <div class="gov-stmt-card__sql">
                    <read-only-editor :text="stmt.sqlContent" :max-height="200" />
                  </div>
                  <div class="gov-stmt-card__meta">
                    <span class="gov-mono">{{ $t('gov-release-hash') }}: {{ shortHash(stmt.hash) }}</span>
                    <span v-if="stmt.execDetail" class="gov-stmt-card__detail">{{ stmt.execDetail }}</span>
                  </div>
                  <div class="gov-stmt-card__actions">
                    <Button
                      v-if="stmt.execStatus === 'FAILED' && canRetry"
                      type="primary"
                      size="small"
                      :loading="retrying"
                      @click="handleRetryStmt(stmt.id)"
                    >
                      {{ $t('gov-v2-retry') }}
                    </Button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </section>

        <section v-if="detail.events && detail.events.length" class="page-section">
          <div class="page-section__title">{{ $t('gov-event-timeline') }}</div>
          <div class="gov-event-timeline-list">
            <div v-for="(evt, idx) in detail.events" :key="idx" class="gov-event-timeline-row">
              <div class="gov-event-timeline-row__icon">
                <Icon :type="govEventIcon(evt.eventType)" />
              </div>
              <div class="gov-event-timeline-row__body">
                <div class="gov-event-timeline-row__header">
                  <strong>{{ govEventTypeText(evt.eventType) }}</strong>
                  <span v-if="evt.fromStatus || evt.toStatus" class="gov-event-timeline-row__transition">
                    {{ evt.fromStatus || '-' }}{{ $t('gov-arrow') }}{{ evt.toStatus || '-' }}
                  </span>
                  <span class="gov-event-timeline-row__time">{{ formatDateTime(evt.gmtCreate) }}</span>
                </div>
                <div v-if="evt.operatorUid" class="gov-event-timeline-row__operator">{{ $t('gov-event-operator') }}: {{ evt.operatorUid }}</div>
              </div>
            </div>
          </div>
        </section>
      </template>
      <div v-else class="gov-detail-empty">
        {{ $t('gov-release-not-found') }}
      </div>
    </div>
  </div>
</template>

<script>
import ReadOnlyEditor from '@/components/editor/ReadOnlyEditor';
import { GOV_EVENT_TYPE_I18N_KEYS, GOV_EVENT_ICONS, RELEASE_STATUS_I18N_KEYS } from './govEventConstants';

export default {
  name: 'DbChangeReleaseDetail',
  components: { ReadOnlyEditor },
  data() {
    return {
      releaseId: null,
      detail: null,
      loading: false,
      retrying: false
    };
  },
  computed: {
    myAuth() {
      return this.$store.state.myAuth || [];
    },
    canRetry() {
      return this.myAuth.includes('RDP_WORKER_ORDER_EXECUTE');
    },
    showGateResult() {
      if (!this.detail || !this.detail.gateResult) {
        return false;
      }
      return ['REJECTED', 'CANCELLED'].includes(this.detail.status);
    }
  },
  mounted() {
    this.releaseId = this.$route.params.releaseId;
    if (this.releaseId) {
      this.loadDetail();
    }
  },
  methods: {
    async loadDetail() {
      this.loading = true;
      try {
        const res = await this.$services.govReleaseDetail({
          data: { releaseId: Number(this.releaseId) }
        });
        if (res.success) {
          this.detail = res.data;
        }
      } finally {
        this.loading = false;
      }
    },
    goToTicket(ticketId) {
      if (ticketId) {
        this.$router.push(`/ticket/${ticketId}`);
      }
    },
    async handleRetryStmt(stmtId) {
      if (this.retrying) {
        return;
      }
      this.retrying = true;
      try {
        const res = await this.$services.govReleaseRetryStmt({
          data: { stmtId }
        });
        if (res.success) {
          this.$Message.success(this.$t('gov-v2-retry-success'));
          await this.loadDetail();
        }
      } finally {
        this.retrying = false;
      }
    },
    govEventTypeText(eventType) {
      const key = GOV_EVENT_TYPE_I18N_KEYS[eventType];
      return key ? this.$t(key) : eventType;
    },
    govEventIcon(eventType) {
      return GOV_EVENT_ICONS[eventType] || 'ios-information-circle-outline';
    },
    releaseStatusText(status) {
      const key = RELEASE_STATUS_I18N_KEYS[status];
      return key ? this.$t(key) : status || '-';
    },
    statusColor(status) {
      if (status === 'DONE') return 'success';
      if (status === 'PARTIAL_FAILED') return 'warning';
      if (status === 'REJECTED' || status === 'CANCELLED') return 'error';
      return 'primary';
    },
    shortHash(hash) {
      if (!hash) return '-';
      return hash.substring(0, 8);
    },
    formatDateTime(dt) {
      if (!dt) return '-';
      const ts = typeof dt === 'number' ? dt : Date.parse(dt);
      if (isNaN(ts)) return '-';
      const d = new Date(ts);
      const pad = (n) => String(n).padStart(2, '0');
      return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
    },
    formatJson(str) {
      if (!str) return '-';
      if (typeof str !== 'string') return JSON.stringify(str, null, 2);
      try {
        return JSON.stringify(JSON.parse(str), null, 2);
      } catch {
        return str;
      }
    }
  }
};
</script>

<style lang="less">
.db-change-release-detail {
  .page-section {
    margin-bottom: 32px;
  }

  .page-section__title {
    font-size: 16px;
    font-weight: 500;
    margin-bottom: 16px;
    padding-left: 10px;
    position: relative;

    &::before {
      content: '';
      position: absolute;
      left: 0;
      top: 50%;
      transform: translateY(-50%);
      width: 3px;
      height: 16px;
      background: var(--primary, #181d26);
      border-radius: 2px;
    }
  }

  .gov-detail-loading {
    display: flex;
    justify-content: center;
    padding: 80px 0;
  }

  .gov-detail-empty {
    text-align: center;
    padding: 80px 0;
    color: var(--muted, #41454d);
  }

  .gov-overview-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
    gap: 12px 24px;
    background: var(--surface-soft, #f8fafc);
    padding: 16px 24px;
    border-radius: 10px;
  }

  .gov-overview-item {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 14px;
    min-height: 28px;
  }

  .gov-overview-label {
    color: var(--muted, #41454d);
    flex-shrink: 0;
    min-width: 80px;
  }

  .gov-mono {
    font-family: Menlo, Monaco, 'Courier New', monospace;
    font-size: 13px;
    word-break: break-all;
  }

  .gov-gate-result-json {
    font-family: Menlo, Monaco, 'Courier New', monospace;
    font-size: 12px;
    white-space: pre-wrap;
    word-break: break-all;
    max-height: 300px;
    overflow-y: auto;
    margin: 0;
    background: var(--surface-soft, #f8fafc);
    padding: 12px 16px;
    border-radius: 10px;
  }

  .gov-stmt-groups {
    display: flex;
    flex-direction: column;
    gap: 24px;
  }

  .gov-stmt-group {
    &__header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 8px 16px;
      background: var(--surface-soft, #f8fafc);
      border-radius: 6px;
      margin-bottom: 12px;
    }

    &__db {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 14px;
      font-weight: 500;
      color: #181d26;
    }

    &__ds-id {
      font-size: 13px;
      color: var(--muted, #41454d);
      font-family: Menlo, Monaco, 'Courier New', monospace;
    }

    &__body {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
  }

  .gov-stmt-card {
    border: 1px solid #eaeaea;
    border-radius: 6px;
    overflow: hidden;

    &__header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 6px 16px;
      background: #fafafa;
    }

    &__seq {
      font-size: 14px;
      font-weight: 600;
      color: #181d26;
    }

    &__status {
      font-size: 13px;
      font-weight: 500;

      &.is-SUCCESS {
        color: #18b566;
      }
      &.is-FAILED {
        color: #ed4014;
      }
      &.is-PENDING {
        color: #707070;
      }
      &.is-EXECUTING {
        color: #2d8cf0;
      }
    }

    &__sql {
      padding: 12px 16px;
    }

    &__meta {
      display: flex;
      gap: 16px;
      font-size: 13px;
      padding: 0 16px 8px;
      flex-wrap: wrap;
    }

    &__detail {
      color: #ed4014;
    }

    &__actions {
      display: flex;
      gap: 8px;
      padding: 0 16px 12px;
    }
  }

  .gov-event-timeline-list {
    display: flex;
    flex-direction: column;
    gap: 0;
  }

  .gov-event-timeline-row {
    display: flex;
    gap: 12px;
    padding: 8px 0;
    border-bottom: 1px solid #f0f0f0;

    &:last-child {
      border-bottom: none;
    }

    &__icon {
      font-size: 18px;
      color: var(--primary, #181d26);
      flex-shrink: 0;
      padding-top: 2px;
    }

    &__body {
      flex: 1;
      min-width: 0;
    }

    &__header {
      display: flex;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
      font-size: 14px;
    }

    &__transition {
      color: var(--muted, #41454d);
      font-size: 13px;
      background: var(--surface-soft, #f8fafc);
      padding: 2px 8px;
      border-radius: 4px;
    }

    &__time {
      margin-left: auto;
      color: var(--muted, #41454d);
      font-size: 12px;
    }

    &__operator {
      color: var(--muted, #41454d);
      font-size: 13px;
      margin-top: 4px;
    }
  }
}
</style>
