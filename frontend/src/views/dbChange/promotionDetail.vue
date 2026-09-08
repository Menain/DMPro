<template>
  <div class="page-shell db-change-promotion-detail">
    <div class="page-shell__body">
      <div v-if="loading" class="gov-detail-loading">
        <Spin size="large" />
      </div>
      <template v-else-if="detail">
        <!-- Promotion overview -->
        <section class="page-section">
          <div class="page-section__title">{{ $t('gov-promotion-overview') }}</div>
          <div class="gov-overview-grid">
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('gov-promotion-code') }}</span>
              <span>{{ detail.promotionCode }}</span>
            </div>
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('gov-promotion-type-label') }}</span>
              <Tag :color="detail.promotionType === 'PRE_PROMOTION' ? 'primary' : 'warning'">
                {{ promotionTypeText(detail.promotionType) }}
              </Tag>
            </div>
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('zhuang-tai') }}</span>
              <span :class="`gov-promotion-status gov-promotion-status--${detail.status}`">
                {{ promotionStatusText(detail.status) }}
              </span>
            </div>
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('gov-execution-key') }}</span>
              <span class="gov-mono">{{ detail.executionKey || '-' }}</span>
            </div>
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('gov-prod-env') }}</span>
              <span>{{ detail.prodEnvId || '-' }}</span>
            </div>
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('gov-prod-ds') }}</span>
              <span>{{ detail.prodDsId || '-' }}</span>
            </div>
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('gov-prod-res-path') }}</span>
              <span class="gov-mono">{{ detail.prodResPath || '-' }}</span>
            </div>
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('gov-prod-ticket') }}</span>
              <span v-if="detail.prodApprovalId">
                <a @click="goToTicket(detail.prodApprovalId)">{{ detail.prodApprovalId }}</a>
              </span>
              <span v-else>-</span>
            </div>
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('chuang-jian-shi-jian') }}</span>
              <span>{{ formatDateTime(detail.gmtCreate) }}</span>
            </div>
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('gov-gmt-modified') }}</span>
              <span>{{ formatDateTime(detail.gmtModified) }}</span>
            </div>
          </div>
        </section>

        <!-- Revision summary -->
        <section v-if="detail.revision" class="page-section">
          <div class="page-section__title">{{ $t('gov-revision-summary') }}</div>
          <div class="gov-overview-grid">
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('gov-revision-code') }}</span>
              <span>{{ detail.revision.revisionCode }}</span>
            </div>
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('gov-change-type-label') }}</span>
              <Tag :color="changeTypeColor(detail.revision.changeType)">
                {{ changeTypeText(detail.revision.changeType) }}
              </Tag>
            </div>
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('gov-stmt-count') }}</span>
              <span>{{ detail.revision.stmtCount }}</span>
            </div>
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('gov-sql-hash') }}</span>
              <span class="gov-mono gov-hash">{{ detail.revision.sqlHash }}</span>
            </div>
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('gov-source-ticket') }}</span>
              <a @click="goToTicket(detail.revision.sourceTicketId)">{{ detail.revision.sourceTicketId }}</a>
            </div>
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('chuang-jian-shi-jian') }}</span>
              <span>{{ formatDateTime(detail.revision.gmtCreate) }}</span>
            </div>
          </div>
        </section>

        <!-- Action area: confirm / retry / failure disposal -->
        <section v-if="showActionArea" class="page-section">
          <div class="page-section__title">{{ $t('gov-promotion-actions') }}</div>
          <!-- WAIT_CONFIRM: confirm execution -->
          <div v-if="detail.status === 'APPROVED'" class="gov-action-row">
            <Button
              v-if="myAuth.includes('RDP_WORKER_ORDER_EXECUTE')"
              type="primary"
              :loading="confirming"
              :disabled="confirming"
              @click="handleConfirm"
            >
              {{ $t('gov-confirm-execution') }}
            </Button>
            <span class="gov-action-hint">{{ $t('gov-confirm-hint') }}</span>
          </div>
          <!-- FAILED: retry or back to PRE -->
          <div v-if="detail.status === 'FAILED'" class="gov-action-row gov-failure-disposal">
            <div class="gov-failure-notice">{{ $t('gov-prod-failure-notice') }}</div>
            <div class="gov-failure-actions">
              <Button v-if="myAuth.includes('RDP_WORKER_ORDER_EXECUTE')" type="primary" :loading="retrying" :disabled="retrying" @click="handleRetry">
                {{ $t('gov-retry-job') }}
              </Button>
              <Button type="default" @click="handleBackToPre">
                {{ $t('gov-back-to-pre') }}
              </Button>
            </div>
            <div class="gov-failure-guidance">{{ $t('gov-back-to-pre-guidance') }}</div>
          </div>
        </section>

        <!-- Gate checklist -->
        <section v-if="detail.gateResult && detail.gateResult.length" class="page-section">
          <div class="page-section__title">{{ $t('gov-gate-checklist') }}</div>
          <div class="table-container">
            <Table size="small" border :columns="gateColumns" :data="detail.gateResult">
              <template #pass="{ row }">
                <Tag :color="row.pass ? 'success' : 'error'">
                  {{ row.pass ? $t('gov-gate-pass') : $t('gov-gate-deny') }}
                </Tag>
              </template>
              <template #item="{ row }">
                <span class="gov-gate-num">{{ row.item }}</span>
              </template>
            </Table>
          </div>
        </section>

        <!-- Preflight results -->
        <section v-if="detail.preflightResult && detail.preflightResult.length" class="page-section">
          <div class="page-section__title">{{ $t('gov-preflight-results') }}</div>
          <div class="table-container">
            <Table size="small" border :columns="preflightColumns" :data="detail.preflightResult">
              <template #pass="{ row }">
                <Tag :color="row.pass ? 'success' : 'error'">
                  {{ row.pass ? $t('gov-gate-pass') : $t('gov-gate-deny') }}
                </Tag>
              </template>
            </Table>
          </div>
        </section>

        <!-- Governance event timeline -->
        <section v-if="detail.events && detail.events.length" class="page-section">
          <div class="page-section__title">{{ $t('gov-event-timeline') }}</div>
          <div class="gov-event-timeline-list">
            <div v-for="evt in detail.events" :key="evt.id" class="gov-event-timeline-row">
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
        {{ $t('gov-promotion-not-found') }}
      </div>
    </div>
  </div>
</template>

<script>
import {
  GOV_EVENT_TYPE_I18N_KEYS,
  GOV_EVENT_ICONS,
  CHANGE_TYPE_I18N_KEYS,
  PROMOTION_STATUS_I18N_KEYS,
  PROMOTION_TYPE_I18N_KEYS
} from './govEventConstants';

export default {
  name: 'DbChangePromotionDetail',
  data() {
    return {
      promotionId: null,
      detail: null,
      loading: false,
      confirming: false,
      retrying: false
    };
  },
  computed: {
    myAuth() {
      return this.$store.state.myAuth || [];
    },
    showActionArea() {
      return this.detail && (this.detail.status === 'APPROVED' || this.detail.status === 'FAILED');
    },
    gateColumns() {
      return [
        { title: this.$t('gov-gate-item'), slot: 'item', width: 70, align: 'center' },
        { title: this.$t('gov-gate-label'), key: 'label', minWidth: 200 },
        {
          title: this.$t('gov-gate-result'),
          slot: 'pass',
          width: 100,
          align: 'center'
        },
        { title: this.$t('gov-gate-reason'), key: 'reason', minWidth: 260 },
        { title: this.$t('gov-gate-timestamp'), key: 'timestamp', width: 170 }
      ];
    },
    preflightColumns() {
      return [
        { title: this.$t('gov-preflight-item'), key: 'label', minWidth: 220 },
        {
          title: this.$t('gov-gate-result'),
          slot: 'pass',
          width: 100,
          align: 'center'
        },
        { title: this.$t('gov-preflight-evidence'), key: 'reason', minWidth: 280 },
        { title: this.$t('gov-gate-timestamp'), key: 'timestamp', width: 170 }
      ];
    }
  },
  mounted() {
    this.promotionId = this.$route.params.promotionId;
    if (this.promotionId) {
      this.loadDetail();
    }
  },
  methods: {
    async loadDetail() {
      this.loading = true;
      try {
        // D-P10-2: promotionDetail FO field `id` carries promotionId (backend reuses LogicalDbIdFO)
        const res = await this.$services.dbChangePromotionDetail({
          data: { id: Number(this.promotionId) }
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
    async handleConfirm() {
      if (this.confirming) {
        return;
      }
      this.confirming = true;
      try {
        // D15 component routing: DML→enableTransactional=true; DDL/MIXED→false; errorStrategy=NONE always.
        // The backend Guard G6 validates config compliance; frontend sends the locked expected value.
        const changeType = this.detail.revision?.changeType;
        const enableTransactional = changeType === 'DML';
        const res = await this.$services.dmTicketConfirm({
          data: {
            ticketId: this.detail.prodApprovalId,
            confirmActionType: 'CONFIRM',
            autoExecConfig: {
              autoExecType: 'IMMEDIATE',
              enableTransactional,
              errorStrategy: 'NONE'
            }
          }
        });
        if (res.success) {
          this.$Message.success(this.$t('cao-zuo-cheng-gong'));
          await this.loadDetail();
        }
      } finally {
        this.confirming = false;
      }
    },
    async handleRetry() {
      if (this.retrying) {
        return;
      }
      this.retrying = true;
      try {
        const res = await this.$services.dmTicketRetryAutoExecJob({
          data: { ticketId: this.detail.prodApprovalId }
        });
        if (res.success) {
          this.$Message.success(this.$t('zhong-shi-cheng-gong'));
          await this.loadDetail();
        }
      } finally {
        this.retrying = false;
      }
    },
    handleBackToPre() {
      if (this.detail?.revision?.sourceTicketId) {
        this.$router.push(`/ticket/${this.detail.revision.sourceTicketId}`);
      }
    },
    govEventTypeText(eventType) {
      const key = GOV_EVENT_TYPE_I18N_KEYS[eventType];
      return key ? this.$t(key) : eventType;
    },
    govEventIcon(eventType) {
      return GOV_EVENT_ICONS[eventType] || 'ios-information-circle-outline';
    },
    changeTypeText(type) {
      const key = CHANGE_TYPE_I18N_KEYS[type];
      return key ? this.$t(key) : type || '-';
    },
    changeTypeColor(type) {
      if (type === 'DDL') return 'primary';
      if (type === 'DML') return 'warning';
      if (type === 'MIXED') return 'error';
      return 'default';
    },
    promotionStatusText(status) {
      const key = PROMOTION_STATUS_I18N_KEYS[status];
      return key ? this.$t(key) : status || '-';
    },
    promotionTypeText(type) {
      const key = PROMOTION_TYPE_I18N_KEYS[type];
      return key ? this.$t(key) : type || '-';
    },
    formatDateTime(dt) {
      if (!dt) return '-';
      const ts = typeof dt === 'number' ? dt : Date.parse(dt);
      if (isNaN(ts)) return '-';
      const d = new Date(ts);
      const pad = (n) => String(n).padStart(2, '0');
      return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
    }
  }
};
</script>

<style lang="less">
.db-change-promotion-detail {
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

  .gov-hash {
    max-width: 240px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    display: inline-block;
    vertical-align: middle;
  }

  .gov-action-row {
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
    padding: 12px 16px;
    background: var(--surface-soft, #f8fafc);
    border-radius: 10px;
  }

  .gov-action-hint {
    color: var(--muted, #41454d);
    font-size: 13px;
  }

  .gov-failure-disposal {
    flex-direction: column;
    align-items: stretch;
    gap: 12px;
  }

  .gov-failure-notice {
    color: #ed4014;
    font-size: 14px;
    font-weight: 500;
  }

  .gov-failure-actions {
    display: flex;
    gap: 8px;
  }

  .gov-failure-guidance {
    color: var(--muted, #41454d);
    font-size: 13px;
    line-height: 1.6;
  }

  .table-container {
    overflow-x: auto;
  }

  .gov-gate-num {
    font-weight: 600;
  }

  .gov-promotion-status {
    font-size: 14px;

    &--SUCCEEDED {
      color: #19be6b;
    }
    &--FAILED {
      color: #ed4014;
    }
    &--REJECTED,
    &--CANCELLED {
      color: #ff9900;
    }
  }

  .gov-event-timeline-list {
    display: flex;
    flex-direction: column;
    gap: 12px;
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
