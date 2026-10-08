<template>
  <div class="page-shell db-change-release-detail">
    <div class="page-shell__body">
      <div class="release-detail-back">
        <Button type="text" @click="handleBack">
          <Icon type="ios-arrow-back" />
          {{ $t('fan-hui') }}
        </Button>
      </div>
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
            <div v-if="detail.returnReason" class="gov-overview-item gov-overview-item--full">
              <span class="gov-overview-label">{{ $t('gov-release-return-reason-label') }}</span>
              <span class="gov-release-return-reason-text">{{ detail.returnReason }}</span>
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

        <Alert v-if="ghostReplayHint" type="warning" show-icon class="gov-ghost-replay-hint">
          {{ $t('gov-release-ghost-replay-hint') }}
        </Alert>
        <!-- B7 N3 发布链披露：分批源的生产重放为直接执行（长锁/超时风险黄旗） -->
        <Alert v-if="dmlBatchReplayHint" type="warning" show-icon class="gov-ghost-replay-hint">
          {{ $t('gov-release-dml-batch-replay-hint') }}
        </Alert>

        <section v-if="showReleaseActions" class="page-section">
          <div class="gov-release-actions">
            <Button v-if="canReturnRelease" type="warning" :loading="returning" @click="openReturnModal">
              {{ $t('gov-release-return-button') }}
            </Button>
            <template v-if="detail.status === 'RETURNED'">
              <Button type="primary" :loading="resubmitting" @click="openResubmitModal">
                {{ $t('gov-release-resubmit-button') }}
              </Button>
              <Button type="error" :loading="cancelling" @click="handleCancel">
                {{ $t('gov-release-cancel-button') }}
              </Button>
            </template>
          </div>
        </section>

        <section v-if="showGateResult" class="page-section">
          <div class="page-section__title">{{ $t('gov-release-gate-result') }}</div>
          <pre class="gov-gate-result-json">{{ formatJson(detail.gateResult) }}</pre>
        </section>

        <section v-if="detail.status === 'RETURNED' && detail.sourceTickets && detail.sourceTickets.length" class="page-section">
          <div class="page-section__title">{{ $t('gov-release-source-tickets') }}</div>
          <div class="gov-source-tickets">
            <div v-for="t in detail.sourceTickets" :key="t.ticketId" class="gov-source-ticket-row">
              <div class="gov-source-ticket-row__info">
                <span class="gov-source-ticket-row__id">{{ t.ticketId }}</span>
                <span class="gov-source-ticket-row__title">{{ t.ticketTitle }}</span>
                <Tag v-if="t.ticketType === 'PROD_DML'" size="small" color="warning">{{ $t('gov-v2-type-prod-dml') }}</Tag>
                <Tag v-else-if="t.ticketType === 'PRE_DDL'" size="small" color="primary">{{ $t('gov-v2-type-pre-ddl') }}</Tag>
              </div>
              <div class="gov-source-ticket-row__status">
                <Tag v-if="t.available" color="success">{{ $t('gov-release-source-ticket-available') }}</Tag>
                <Tag v-else color="error">{{ $t('gov-release-source-ticket-unavailable') }}</Tag>
                <span v-if="!t.available && t.unavailableReason" class="gov-source-ticket-row__reason">{{ t.unavailableReason }}</span>
              </div>
            </div>
          </div>
        </section>

        <section v-if="detail.stmtGroups && detail.stmtGroups.length" class="page-section">
          <div class="page-section__title">{{ $t('gov-release-stmt-groups') }}</div>
          <div class="gov-stmt-groups">
            <div v-for="group in detail.stmtGroups" :key="`${group.prodDsId}-${group.prodDbName}`" class="gov-stmt-group">
              <div class="gov-stmt-group__header" @click="toggleGroup(group)">
                <div class="gov-stmt-group__db">
                  <Icon type="ios-server-outline" />
                  <span>{{ group.prodDbName }}</span>
                </div>
                <div class="gov-stmt-group__meta">
                  <span class="gov-stmt-group__ds-id">{{ $t('gov-release-ds-label') }}{{ group.prodDsId }}</span>
                  <span class="gov-stmt-group__count">{{ group.stmts.length }}</span>
                  <Icon class="gov-stmt-group__arrow" :type="isGroupCollapsed(group) ? 'ios-arrow-down' : 'ios-arrow-up'" />
                </div>
              </div>
              <div v-show="!isGroupCollapsed(group)" class="gov-stmt-group__body">
                <div v-for="stmt in group.stmts" :key="stmt.id" class="gov-stmt-card">
                  <div class="gov-stmt-card__header">
                    <span class="gov-stmt-card__seq">{{ $t('gov-release-seq-label') }}{{ stmt.seq }}</span>
                    <div class="gov-stmt-card__type-status">
                      <Tag v-if="stmt.sourceTicketType === 'PROD_DML'" size="small" color="warning">{{ $t('gov-v2-type-prod-dml') }}</Tag>
                      <Tag v-else-if="stmt.sourceTicketType === 'PRE_DDL'" size="small" color="primary">{{ $t('gov-v2-type-pre-ddl') }}</Tag>
                      <span :class="['gov-stmt-card__status', `is-${stmt.execStatus}`]">{{ stmt.execStatus }}</span>
                    </div>
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
                    <Button v-if="stmt.execStatus === 'FAILED' && canRetry" size="small" :loading="returning" @click="openReturnStmtModal(stmt.id)">
                      {{ $t('gov-release-return-stmt') }}
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
    <CCModal
      v-model="showReturnStmtModal"
      :title="$t('gov-release-return-stmt')"
      @on-ok="handleReturnStmtSubmit"
      @on-cancel="
        () => {
          showReturnStmtModal = false;
          returnStmtReason = '';
          returnStmtId = null;
        }
      "
    >
      <Input v-model="returnStmtReason" type="textarea" :rows="3" :placeholder="$t('gov-v2-return-reason-placeholder')" />
    </CCModal>

    <CCModal v-model="showReturnModal" :title="$t('gov-release-return-button')" :footer-hide="true">
      <div class="gov-release-return-form">
        <div class="gov-release-return-form__label">{{ $t('gov-release-return-reason-label') }}</div>
        <Input
          v-model="returnReason"
          type="textarea"
          :rows="4"
          :maxlength="1000"
          show-word-limit
          :placeholder="$t('gov-release-return-reason-placeholder')"
        />
      </div>
      <template #footer>
        <Button @click="showReturnModal = false">{{ $t('qu-xiao') }}</Button>
        <Button type="primary" :loading="returning" @click="handleReturnSubmit">{{ $t('que-ren') }}</Button>
      </template>
    </CCModal>

    <CCModal v-model="showResubmitModal" :title="$t('gov-release-resubmit-button')" :footer-hide="true" width="680">
      <div class="gov-resubmit-form">
        <div class="gov-resubmit-form__title-row">
          <span class="gov-resubmit-form__label">{{ $t('gov-release-title') }}</span>
          <Input v-model="resubmitTitle" :placeholder="$t('gov-ledger-title-placeholder')" maxlength="255" />
        </div>
        <div class="gov-resubmit-form__label">{{ $t('gov-release-resubmit-exclude-hint') }}</div>
        <div class="gov-resubmit-tickets">
          <div v-for="t in resubmitSourceTickets" :key="t.ticketId" class="gov-resubmit-ticket-row" :class="{ 'is-disabled': !t.available }">
            <Checkbox v-model="t.checked" :disabled="!t.available">
              <span class="gov-resubmit-ticket-row__id">{{ t.ticketId }}</span>
              <span class="gov-resubmit-ticket-row__title">{{ t.ticketTitle }}</span>
              <Tag v-if="t.ticketType === 'PROD_DML'" size="small" color="warning">{{ $t('gov-v2-type-prod-dml') }}</Tag>
              <Tag v-else-if="t.ticketType === 'PRE_DDL'" size="small" color="primary">{{ $t('gov-v2-type-pre-ddl') }}</Tag>
            </Checkbox>
            <div class="gov-resubmit-ticket-row__status">
              <Tag v-if="t.available" color="success" size="small">{{ $t('gov-release-source-ticket-available') }}</Tag>
              <template v-else>
                <Tag color="error" size="small">{{ $t('gov-release-source-ticket-unavailable') }}</Tag>
                <span v-if="t.unavailableReason" class="gov-resubmit-ticket-row__reason">{{ t.unavailableReason }}</span>
              </template>
            </div>
          </div>
        </div>
        <template v-if="resubmitCandidates.length">
          <div class="gov-resubmit-form__label">{{ $t('gov-release-resubmit-candidates-title') }}</div>
          <div class="gov-resubmit-tickets">
            <div v-for="t in resubmitCandidates" :key="t.ticketId" class="gov-resubmit-ticket-row">
              <Checkbox v-model="t.checked">
                <span class="gov-resubmit-ticket-row__id">{{ t.ticketId }}</span>
                <span class="gov-resubmit-ticket-row__title">{{ t.ticketTitle }}</span>
                <span class="gov-resubmit-ticket-row__service">{{ t.serviceName }}</span>
                <Tag v-if="t.ticketType === 'PROD_DML'" size="small" color="warning">{{ $t('gov-v2-type-prod-dml') }}</Tag>
                <Tag v-else-if="t.ticketType === 'PRE_DDL'" size="small" color="primary">{{ $t('gov-v2-type-pre-ddl') }}</Tag>
              </Checkbox>
            </div>
          </div>
        </template>
      </div>
      <template #footer>
        <Button @click="showResubmitModal = false">{{ $t('qu-xiao') }}</Button>
        <Button type="primary" :loading="resubmitting" @click="handleResubmitSubmit">{{ $t('que-ren') }}</Button>
      </template>
    </CCModal>
  </div>
</template>

<script>
import { mapState } from 'vuex';
import ReadOnlyEditor from '@/components/editor/ReadOnlyEditor';
import formatError from '@/services/formatError';
import { GOV_EVENT_TYPE_I18N_KEYS, GOV_EVENT_ICONS, RELEASE_STATUS_I18N_KEYS } from './govEventConstants';

export default {
  name: 'DbChangeReleaseDetail',
  components: { ReadOnlyEditor },
  data() {
    return {
      releaseId: null,
      detail: null,
      loading: false,
      retrying: false,
      returning: false,
      collapsedGroups: {},
      showReturnStmtModal: false,
      returnStmtReason: '',
      returnStmtId: null,
      showReturnModal: false,
      returnReason: '',
      showResubmitModal: false,
      resubmitTitle: '',
      resubmitSourceTickets: [],
      resubmitCandidates: [],
      resubmitCandidatesLoading: false,
      resubmitting: false,
      cancelling: false
    };
  },
  computed: {
    ...mapState(['userInfo']),
    myAuth() {
      return this.$store.state.myAuth || [];
    },
    canRetry() {
      return this.myAuth.includes('RDP_WORKER_ORDER_EXECUTE');
    },
    isPrimaryOrMaintainer() {
      const info = this.userInfo || {};
      return info.accountType === 'PRIMARY_ACCOUNT' || info.isMaintainer === true;
    },
    canReturnRelease() {
      if (!this.detail) return false;
      return ['APPROVING', 'APPROVED'].includes(this.detail.status) && this.isPrimaryOrMaintainer;
    },
    canManageReturnedRelease() {
      if (!this.detail || this.detail.status !== 'RETURNED') return false;
      if (this.isPrimaryOrMaintainer) return true;
      return this.detail.creatorUid && this.userInfo?.uid === this.detail.creatorUid;
    },
    showReleaseActions() {
      return this.canReturnRelease || this.canManageReturnedRelease;
    },
    showGateResult() {
      if (!this.detail || !this.detail.gateResult) {
        return false;
      }
      return ['REJECTED', 'CANCELLED', 'RETURNED'].includes(this.detail.status);
    },
    // B6 M3 发布链过渡明示：gate_result 的 additive 提示键（ghostRoutedStmtCount>0）→ 黄旗
    // 「PROD 重放=直接执行，大表存在锁风险」；旧发布单无该键 → 不显示（容错解析）
    ghostReplayHint() {
      if (!this.detail || !this.detail.gateResult) {
        return false;
      }
      try {
        const gr = JSON.parse(this.detail.gateResult);
        return Number(gr && gr.ghostRoutedStmtCount) > 0;
      } catch (e) {
        return false;
      }
    },
    // B7 N3 发布链披露：dmlBatchRoutedStmtCount>0 → 「PRE 侧曾分批执行；PROD 重放为直接执行，
    // 大表存在长锁/超时风险」黄旗；旧发布单无该键 → 不显示（同款容错解析）
    dmlBatchReplayHint() {
      if (!this.detail || !this.detail.gateResult) {
        return false;
      }
      try {
        const gr = JSON.parse(this.detail.gateResult);
        return Number(gr && gr.dmlBatchRoutedStmtCount) > 0;
      } catch (e) {
        return false;
      }
    }
  },
  mounted() {
    this.releaseId = this.$route.params.releaseId;
    if (this.releaseId) {
      this.loadDetail();
    }
  },
  methods: {
    handleBack() {
      this.$router.push('/dbChange/release');
    },
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
    isGroupCollapsed(group) {
      return !!this.collapsedGroups[`${group.prodDsId}|${group.prodDbName}`];
    },
    toggleGroup(group) {
      const key = `${group.prodDsId}|${group.prodDbName}`;
      this.collapsedGroups = { ...this.collapsedGroups, [key]: !this.collapsedGroups[key] };
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
    openReturnStmtModal(stmtId) {
      this.returnStmtId = stmtId;
      this.returnStmtReason = '';
      this.showReturnStmtModal = true;
    },
    async handleReturnStmtSubmit() {
      const stmtId = this.returnStmtId;
      if (this.returning || !stmtId) {
        return;
      }
      this.returning = true;
      try {
        const res = await this.$services.govReleaseReturnStmt({
          data: { stmtId, reason: this.returnStmtReason || undefined }
        });
        if (res.success) {
          this.$Message.success(this.$t('gov-release-return-success'));
          this.showReturnStmtModal = false;
          this.returnStmtReason = '';
          this.returnStmtId = null;
          await this.loadDetail();
        } else {
          this.$Message.error(formatError(res.msg));
        }
      } finally {
        this.returning = false;
      }
    },
    openReturnModal() {
      this.returnReason = '';
      this.showReturnModal = true;
    },
    async handleReturnSubmit() {
      if (this.returning) {
        return;
      }
      const reason = (this.returnReason || '').trim();
      if (!reason) {
        this.$Message.warning(this.$t('gov-release-return-reason-required'));
        return;
      }
      this.returning = true;
      try {
        const res = await this.$services.govReleaseReturn({
          data: { releaseId: Number(this.releaseId), reason }
        });
        if (res.success) {
          this.$Message.success(this.$t('gov-release-return-release-success'));
          this.showReturnModal = false;
          this.returnReason = '';
          await this.loadDetail();
        } else {
          this.$Message.error(formatError(res.msg));
        }
      } finally {
        this.returning = false;
      }
    },
    async openResubmitModal() {
      this.resubmitTitle = this.detail.title || '';
      this.resubmitSourceTickets = (this.detail.sourceTickets || []).map((t) => ({
        ticketId: t.ticketId,
        ticketTitle: t.ticketTitle,
        ticketStatus: t.ticketStatus,
        ticketType: t.ticketType,
        available: t.available,
        unavailableReason: t.unavailableReason,
        checked: true
      }));
      this.resubmitCandidates = [];
      this.resubmitCandidatesLoading = true;
      this.showResubmitModal = true;
      // Fetch addable tickets from the ledger (NOT_PROMOTED, not already in original list).
      // Failure degrades silently — modal still usable for original-list removal only.
      const originalIds = new Set(this.resubmitSourceTickets.map((t) => t.ticketId));
      try {
        const res = await this.$services.govLedgerList({ data: {} });
        if (res.success && res.data) {
          this.resubmitCandidates = res.data
            .filter((t) => !t.promoted && !originalIds.has(t.ticketId))
            .map((t) => ({
              ticketId: t.ticketId,
              ticketTitle: t.ticketTitle,
              serviceName: t.serviceName,
              ticketType: t.ticketType,
              checked: false
            }));
        }
      } catch {
        // Degrade: no addable candidates, modal remains usable for original-list removal
      } finally {
        this.resubmitCandidatesLoading = false;
      }
    },
    async handleResubmitSubmit() {
      if (this.resubmitting) {
        return;
      }
      const originalChecked = this.resubmitSourceTickets.filter((t) => t.available && t.checked).map((t) => t.ticketId);
      const candidateChecked = this.resubmitCandidates.filter((t) => t.checked).map((t) => t.ticketId);
      const ticketIds = [...new Set([...originalChecked, ...candidateChecked])];
      if (ticketIds.length === 0) {
        this.$Message.warning(this.$t('gov-release-resubmit-keep-at-least-one'));
        return;
      }
      this.resubmitting = true;
      try {
        const res = await this.$services.govReleaseResubmit({
          data: {
            releaseId: Number(this.releaseId),
            ticketIds,
            title: this.resubmitTitle || undefined
          }
        });
        if (res.success) {
          this.$Message.success(this.$t('gov-release-resubmit-success'));
          this.showResubmitModal = false;
          await this.loadDetail();
        } else {
          this.$Message.error(formatError(res.msg));
        }
      } finally {
        this.resubmitting = false;
      }
    },
    handleCancel() {
      if (this.cancelling) {
        return;
      }
      this.$Modal.confirm({
        title: this.$t('gov-release-cancel-button'),
        content: this.$t('gov-release-cancel-confirm'),
        onOk: async () => {
          this.cancelling = true;
          try {
            const res = await this.$services.govReleaseCancel({
              data: { releaseId: Number(this.releaseId) }
            });
            if (res.success) {
              this.$Message.success(this.$t('gov-release-cancel-success'));
              await this.loadDetail();
            } else {
              this.$Message.error(formatError(res.msg));
            }
          } finally {
            this.cancelling = false;
          }
        }
      });
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
      if (status === 'RETURNED') return 'warning';
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
  .release-detail-back {
    margin-bottom: 8px;
  }

  .gov-ghost-replay-hint {
    margin-bottom: 24px;
  }

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

  .gov-overview-item--full {
    grid-column: 1 / -1;
    flex-direction: column;
    align-items: flex-start;
    gap: 4px;
  }

  .gov-release-return-reason-text {
    font-size: 14px;
    line-height: 1.5;
    word-break: break-all;
  }

  .gov-release-actions {
    display: flex;
    gap: 12px;
    flex-wrap: wrap;
  }

  .gov-source-tickets,
  .gov-resubmit-tickets {
    display: flex;
    flex-direction: column;
    gap: 0;
  }

  .gov-source-ticket-row,
  .gov-resubmit-ticket-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 8px 0;
    border-bottom: 1px solid #f0f0f0;
    gap: 12px;

    &:last-child {
      border-bottom: none;
    }

    &__info {
      display: flex;
      align-items: center;
      gap: 8px;
      min-width: 0;
      flex: 1;
    }

    &__id {
      font-size: 13px;
      font-family: Menlo, Monaco, 'Courier New', monospace;
      color: var(--muted, #41454d);
      flex-shrink: 0;
    }

    &__title {
      font-size: 14px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    &__service {
      font-size: 13px;
      color: var(--muted, #41454d);
      margin-left: 4px;
    }

    &__status {
      display: flex;
      align-items: center;
      gap: 8px;
      flex-shrink: 0;
    }

    &__reason {
      font-size: 13px;
      color: #ed4014;
    }

    &.is-disabled {
      opacity: 0.6;
    }
  }

  .gov-release-return-form {
    display: flex;
    flex-direction: column;
    gap: 8px;

    &__label {
      font-size: 14px;
      color: var(--muted, #41454d);
    }
  }

  .gov-resubmit-form {
    display: flex;
    flex-direction: column;
    gap: 16px;

    &__title-row {
      display: flex;
      align-items: center;
      gap: 12px;
    }

    &__label {
      font-size: 14px;
      color: var(--muted, #41454d);
      flex-shrink: 0;
    }
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
      cursor: pointer;
    }

    &__db {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 14px;
      font-weight: 500;
      color: #181d26;
    }

    &__meta {
      display: flex;
      align-items: center;
      gap: 12px;
    }

    &__ds-id {
      font-size: 13px;
      color: var(--muted, #41454d);
      font-family: Menlo, Monaco, 'Courier New', monospace;
    }

    &__count {
      font-size: 13px;
      color: var(--muted, #41454d);
    }

    &__arrow {
      font-size: 14px;
      color: var(--muted, #41454d);
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

    &__type-status {
      display: flex;
      align-items: center;
      gap: 8px;
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
