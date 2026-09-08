<template>
  <div class="page-shell db-change-promotion">
    <div class="page-shell__body">
      <!-- Path A: available revisions + promote -->
      <section class="page-section">
        <div class="page-section__title">{{ $t('gov-promotion-path-a') }}</div>
        <div class="gov-promotion-revisions">
          <div class="option">
            <Button type="primary" ghost :loading="revisionsLoading" @click="loadAvailableRevisions">
              {{ $t('gov-refresh-revisions') }}
            </Button>
          </div>
          <div class="table-container">
            <Table
              size="small"
              border
              stripe
              :loading="revisionsLoading"
              :columns="revisionColumns"
              :data="availableRevisions"
              :row-class-name="revisionRowClass"
              @on-row-click="handleSelectRevision"
            >
              <template #changeType="{ row }">
                <Tag :color="changeTypeColor(row.changeType)">{{ changeTypeText(row.changeType) }}</Tag>
              </template>
              <template #action="{ row }">
                <Button type="text" size="small" @click.stop="handleSelectRevision(row)">
                  {{ $t('xuan-ze') }}
                </Button>
              </template>
            </Table>
          </div>
        </div>
      </section>

      <section v-if="selectedRevision" class="page-section">
        <div class="page-section__title">{{ $t('gov-selected-revision') }}</div>
        <div class="gov-revision-summary">
          <div class="gov-revision-summary-row">
            <span class="gov-revision-label">{{ $t('gov-revision-code') }}</span>
            <span>{{ selectedRevision.revisionCode }}</span>
          </div>
          <div class="gov-revision-summary-row">
            <span class="gov-revision-label">{{ $t('gov-logical-db') }}</span>
            <span>{{ selectedRevision.logicalDbResourceName }}</span>
          </div>
          <div class="gov-revision-summary-row">
            <span class="gov-revision-label">{{ $t('gov-change-type-label') }}</span>
            <Tag :color="changeTypeColor(selectedRevision.changeType)">{{ changeTypeText(selectedRevision.changeType) }}</Tag>
          </div>
          <div class="gov-revision-summary-row">
            <span class="gov-revision-label">{{ $t('gov-stmt-count') }}</span>
            <span>{{ selectedRevision.stmtCount }}</span>
          </div>
          <div class="gov-revision-summary-row">
            <span class="gov-revision-label">{{ $t('gov-source-ticket') }}</span>
            <span>{{ selectedRevision.sourceTicketId }}</span>
          </div>
          <div class="gov-revision-summary-row">
            <span class="gov-revision-label">{{ $t('chuang-jian-shi-jian') }}</span>
            <span>{{ formatDateTime(selectedRevision.gmtCreate) }}</span>
          </div>
        </div>

        <div v-if="revisionDetailLoading" class="gov-revision-detail-loading">
          <Spin />
        </div>
        <template v-else-if="revisionDetail">
          <div class="gov-revision-detail-block">
            <div class="panel-subheading">{{ $t('gov-sql-text') }}</div>
            <ReadOnlyEditor :text="revisionDetail.sqlText || ''" :max-height="300" />
          </div>
          <div v-if="revisionDetail.rollbackSqlText" class="gov-revision-detail-block">
            <div class="panel-subheading">{{ $t('gov-rollback-sql') }}</div>
            <ReadOnlyEditor :text="revisionDetail.rollbackSqlText" :max-height="200" />
          </div>
          <div v-if="revisionDetail.stmtManifest && revisionDetail.stmtManifest.length" class="gov-revision-detail-block">
            <div class="panel-subheading">{{ $t('gov-stmt-manifest') }}</div>
            <div class="table-container">
              <Table size="small" border :columns="manifestColumns" :data="revisionDetail.stmtManifest">
                <template #preExec="{ row }">
                  <Tag :color="row.preExec === 'SUCCESS' ? 'success' : 'error'">{{ row.preExec }}</Tag>
                </template>
              </Table>
            </div>
          </div>
          <div v-if="revisionDetail.auditSnapshot && revisionDetail.auditSnapshot.length" class="gov-revision-detail-block">
            <div class="panel-subheading">{{ $t('gov-audit-snapshot') }}</div>
            <div class="table-container">
              <Table size="small" border :columns="auditColumns" :data="revisionDetail.auditSnapshot">
                <template #context="{ row }">
                  <pre class="gov-audit-context">{{ formatJson(row.context) }}</pre>
                </template>
              </Table>
            </div>
          </div>
        </template>

        <div class="gov-promote-form">
          <a-form label-position="top" :labelCol="{ span: 24 }" :label-wrap="true">
            <a-form-item :label="$t('miao-shu')">
              <Input v-model="promoteDescription" type="textarea" :rows="2" :placeholder="$t('gov-promote-description-placeholder')" />
            </a-form-item>
          </a-form>
          <div class="gov-promote-actions">
            <Button
              v-if="myAuth.includes('RDP_DB_CHANGE_PROD_PROMOTE')"
              type="primary"
              :loading="promoting"
              :disabled="promoting"
              @click="handlePromote"
            >
              {{ $t('gov-promote-submit') }}
            </Button>
          </div>
        </div>
      </section>

      <!-- Path B: direct production DML -->
      <section v-if="pathBVisible" class="page-section">
        <div class="page-section__title">{{ $t('gov-promotion-path-b') }}</div>
        <div class="gov-pathb-notice">{{ $t('gov-pathb-notice') }}</div>
        <div class="gov-pathb-form">
          <a-form label-position="top" :labelCol="{ span: 24 }" :label-wrap="true" ref="pathBForm" :model="pathBData">
            <a-form-item :label="$t('gov-logical-db')" name="logicalDbId">
              <Select v-model="pathBData.logicalDbId" :placeholder="$t('gov-select-logical-db')" filterable @on-change="handlePathBLogicalDbChange">
                <Option v-for="db in logicalDbList" :key="db.id" :value="db.id" :label="db.resourceName">
                  {{ db.resourceName }} ({{ db.resourceCode }})
                </Option>
              </Select>
            </a-form-item>
            <div class="gov-pathb-editors">
              <div class="gov-pathb-editor-col">
                <div class="gov-pathb-editor-label">
                  {{ $t('gov-dml-sql') }}
                  <span class="gov-pathb-required">*</span>
                </div>
                <div class="gov-pathb-editor-wrapper">
                  <ticket-editor ref="pathBSqlEditor" data-source-type="MySQL" />
                </div>
              </div>
              <div class="gov-pathb-editor-col">
                <div class="gov-pathb-editor-label">
                  {{ $t('gov-rollback-sql') }}
                  <span class="gov-pathb-required">*</span>
                </div>
                <div class="gov-pathb-editor-wrapper">
                  <ticket-editor ref="pathBRollbackEditor" data-source-type="MySQL" />
                </div>
              </div>
            </div>
            <a-form-item :label="$t('miao-shu')">
              <Input v-model="pathBData.description" type="textarea" :rows="2" />
            </a-form-item>
          </a-form>
          <div class="gov-pathb-actions">
            <Button
              v-if="myAuth.includes('RDP_DB_CHANGE_PROD_DML_DIRECT')"
              type="primary"
              :loading="pathBSubmitting"
              :disabled="pathBSubmitting"
              @click="handleDirectDmlSubmit"
            >
              {{ $t('gov-pathb-submit') }}
            </Button>
          </div>
        </div>
      </section>

      <!-- Promotion list -->
      <section class="page-section">
        <div class="page-section__title">{{ $t('gov-promotion-list') }}</div>
        <div class="option">
          <Button type="primary" ghost :loading="promotionListLoading" @click="loadPromotionList">
            {{ $t('shua-xin') }}
          </Button>
        </div>
        <div class="table-container">
          <Table size="small" border stripe :loading="promotionListLoading" :columns="promotionColumns" :data="promotionList">
            <template #promotionType="{ row }">
              <Tag :color="row.promotionType === 'PRE_PROMOTION' ? 'primary' : 'warning'">
                {{ promotionTypeText(row.promotionType) }}
              </Tag>
            </template>
            <template #status="{ row }">
              <span :class="`gov-promotion-status gov-promotion-status--${row.status}`">{{ promotionStatusText(row.status) }}</span>
            </template>
            <template #action="{ row }">
              <Button type="text" size="small" @click="handleViewPromotion(row)">
                {{ $t('cha-kan') }}
              </Button>
            </template>
          </Table>
        </div>
        <div class="footer">
          <Page
            :total="promotionTotal"
            show-total
            show-elevator
            show-sizer
            :page-size="promotionPage.pageSize"
            :model-value="promotionPage.pageNum"
            @on-change="handlePromotionPageChange"
            @on-page-size-change="handlePromotionPageSizeChange"
          />
        </div>
      </section>
    </div>
  </div>
</template>

<script>
import TicketEditor from '@/components/editor/TicketEditor';
import ReadOnlyEditor from '@/components/editor/ReadOnlyEditor';
import { CHANGE_TYPE_I18N_KEYS, PROMOTION_STATUS_I18N_KEYS, PROMOTION_TYPE_I18N_KEYS } from './govEventConstants';

export default {
  name: 'DbChangePromotion',
  components: { TicketEditor, ReadOnlyEditor },
  data() {
    return {
      availableRevisions: [],
      revisionsLoading: false,
      selectedRevision: null,
      revisionDetail: null,
      revisionDetailLoading: false,
      promoteDescription: '',
      promoting: false,
      logicalDbList: [],
      pathBBindings: [],
      pathBVisible: false,
      pathBSubmitting: false,
      pathBData: {
        logicalDbId: null,
        description: ''
      },
      promotionList: [],
      promotionTotal: 0,
      promotionListLoading: false,
      promotionPage: {
        pageNum: 1,
        pageSize: 10
      }
    };
  },
  computed: {
    myAuth() {
      return this.$store.state.myAuth || [];
    },
    revisionColumns() {
      return [
        { title: this.$t('gov-revision-code'), key: 'revisionCode', minWidth: 160 },
        { title: this.$t('gov-logical-db'), key: 'logicalDbResourceName', minWidth: 140 },
        { title: this.$t('gov-change-type-label'), slot: 'changeType', width: 100 },
        { title: this.$t('gov-stmt-count'), key: 'stmtCount', width: 90 },
        { title: this.$t('gov-source-ticket'), key: 'sourceTicketId', width: 110 },
        { title: this.$t('chuang-jian-shi-jian'), key: 'gmtCreate', width: 170, render: (h, params) => this.formatDateTime(params.row.gmtCreate) },
        { title: this.$t('cao-zuo'), slot: 'action', width: 80, align: 'center' }
      ];
    },
    promotionColumns() {
      return [
        { title: this.$t('gov-promotion-code'), key: 'promotionCode', minWidth: 160 },
        { title: this.$t('gov-promotion-type-label'), slot: 'promotionType', width: 130 },
        { title: this.$t('zhuang-tai'), slot: 'status', width: 110 },
        { title: this.$t('gov-revision-id'), key: 'revisionId', width: 100 },
        { title: this.$t('gov-logical-db-id'), key: 'logicalDbId', width: 100 },
        { title: this.$t('chuang-jian-shi-jian'), key: 'gmtCreate', width: 170, render: (h, params) => this.formatDateTime(params.row.gmtCreate) },
        { title: this.$t('cao-zuo'), slot: 'action', width: 80, align: 'center' }
      ];
    },
    manifestColumns() {
      return [
        { title: this.$t('gov-manifest-stmt-index'), key: 'stmtIndex', width: 80, align: 'center' },
        {
          title: this.$t('gov-manifest-stmt-hash'),
          key: 'stmtHash',
          minWidth: 200,
          render: (h, params) => h('span', { class: 'gov-mono' }, params.row.stmtHash)
        },
        { title: this.$t('gov-manifest-version'), key: 'version', width: 80, align: 'center' },
        { title: this.$t('gov-manifest-pre-exec'), slot: 'preExec', width: 110, align: 'center' }
      ];
    },
    auditColumns() {
      return [
        { title: this.$t('gov-audit-activity'), key: 'activityId', width: 180 },
        { title: this.$t('gov-audit-status'), key: 'status', width: 100 },
        { title: this.$t('gov-audit-context'), slot: 'context', minWidth: 280 }
      ];
    }
  },
  mounted() {
    this.loadAvailableRevisions();
    this.loadLogicalDbs();
    this.loadPromotionList();
  },
  methods: {
    async loadAvailableRevisions() {
      this.revisionsLoading = true;
      try {
        const res = await this.$services.dbChangeAvailableRevisions({ data: {} });
        if (res.success) {
          this.availableRevisions = res.data || [];
        }
      } finally {
        this.revisionsLoading = false;
      }
    },
    async loadLogicalDbs() {
      const res = await this.$services.myLogicalDbs({ data: {} });
      if (res.success) {
        this.logicalDbList = res.data || [];
      }
    },
    async loadPromotionList() {
      this.promotionListLoading = true;
      try {
        const res = await this.$services.dbChangePromotionList({
          data: {
            page: { pageNum: this.promotionPage.pageNum, pageSize: this.promotionPage.pageSize }
          }
        });
        if (res.success) {
          this.promotionList = res.data?.records || [];
          this.promotionTotal = res.data?.total || 0;
        }
      } finally {
        this.promotionListLoading = false;
      }
    },
    handleSelectRevision(row) {
      this.selectedRevision = row;
      this.promoteDescription = '';
      this.revisionDetail = null;
      if (row) {
        this.loadRevisionDetail(row.revisionId);
      }
    },
    async loadRevisionDetail(revisionId) {
      this.revisionDetailLoading = true;
      try {
        const res = await this.$services.dbChangeRevisionDetail({ data: { revisionId } });
        if (res.success) {
          this.revisionDetail = res.data;
        }
      } finally {
        this.revisionDetailLoading = false;
      }
    },
    revisionRowClass(row) {
      return row && this.selectedRevision && row.revisionId === this.selectedRevision.revisionId ? 'gov-revision-row-selected' : '';
    },
    async handlePromote() {
      if (this.promoting || !this.selectedRevision) {
        return;
      }
      this.promoting = true;
      try {
        const res = await this.$services.dbChangePromote({
          data: {
            revisionId: this.selectedRevision.revisionId,
            description: this.promoteDescription
          }
        });
        if (res.success) {
          const promotionId = res.data;
          this.$Message.success(this.$t('gov-promote-success'));
          this.selectedRevision = null;
          this.promoteDescription = '';
          await this.loadAvailableRevisions();
          await this.loadPromotionList();
          this.$router.push(`/dbChange/promotion/${promotionId}`);
        }
      } finally {
        this.promoting = false;
      }
    },
    async handlePathBLogicalDbChange(logicalDbId) {
      if (!logicalDbId) {
        this.pathBVisible = false;
        return;
      }
      const res = await this.$services.logicalDbBindingList({ data: { id: logicalDbId } });
      if (res.success) {
        this.pathBBindings = res.data || [];
        const prodBinding = this.pathBBindings.find((b) => b.govRole === 'PROD' && b.govDmlDirect === 'on');
        this.pathBVisible = !!prodBinding;
      } else {
        this.pathBVisible = false;
      }
    },
    async handleDirectDmlSubmit() {
      if (this.pathBSubmitting) {
        return;
      }
      const sql = this.$refs.pathBSqlEditor?.getSql() || '';
      const rollbackSql = this.$refs.pathBRollbackEditor?.getSql() || '';
      if (!sql.trim()) {
        this.$Message.error(this.$t('gov-sql-required'));
        return;
      }
      if (!rollbackSql.trim()) {
        this.$Message.error(this.$t('gov-rollback-sql-required'));
        return;
      }
      this.pathBSubmitting = true;
      try {
        const res = await this.$services.dbChangeDirectDmlSubmit({
          data: {
            logicalDbId: this.pathBData.logicalDbId,
            sql,
            rollbackSql,
            description: this.pathBData.description
          }
        });
        if (res.success) {
          this.$Message.success(this.$t('gov-pathb-submit-success'));
          if (res.data?.promotionId) {
            this.$router.push(`/dbChange/promotion/${res.data.promotionId}`);
          }
        }
      } finally {
        this.pathBSubmitting = false;
      }
    },
    handleViewPromotion(row) {
      this.$router.push(`/dbChange/promotion/${row.id}`);
    },
    handlePromotionPageChange(page) {
      this.promotionPage.pageNum = page;
      this.loadPromotionList();
    },
    handlePromotionPageSizeChange(size) {
      this.promotionPage.pageSize = size;
      this.promotionPage.pageNum = 1;
      this.loadPromotionList();
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
      const d = typeof dt === 'number' ? new Date(dt) : new Date(dt);
      if (isNaN(d.getTime())) return '-';
      const pad = (n) => String(n).padStart(2, '0');
      return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
    },
    formatJson(obj) {
      if (obj == null) return '-';
      if (typeof obj === 'string') return obj;
      try {
        return JSON.stringify(obj, null, 2);
      } catch {
        return String(obj);
      }
    }
  }
};
</script>

<style lang="less">
.db-change-promotion {
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

  .option {
    display: flex;
    justify-content: flex-end;
    margin-bottom: 12px;
  }

  .table-container {
    overflow-x: auto;
  }

  .gov-revision-row-selected td {
    background-color: var(--surface-soft, #f8fafc) !important;
  }

  .gov-revision-summary {
    background: var(--surface-soft, #f8fafc);
    padding: 16px 24px;
    border-radius: 10px;
    margin-bottom: 16px;
  }

  .gov-revision-summary-row {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 4px 0;
    font-size: 14px;
  }

  .gov-revision-label {
    min-width: 100px;
    color: var(--muted, #41454d);
    flex-shrink: 0;
  }

  .gov-promote-form {
    margin-top: 16px;
  }

  .gov-revision-detail-block {
    margin-top: 16px;
  }

  .gov-revision-detail-loading {
    display: flex;
    justify-content: center;
    padding: 24px 0;
  }

  .panel-subheading {
    font-size: 14px;
    font-weight: 500;
    margin-bottom: 8px;
  }

  .gov-audit-context {
    font-family: Menlo, Monaco, 'Courier New', monospace;
    font-size: 12px;
    white-space: pre-wrap;
    word-break: break-all;
    max-height: 200px;
    overflow-y: auto;
    margin: 0;
  }

  .gov-promote-actions {
    display: flex;
    justify-content: flex-end;
  }

  .gov-pathb-notice {
    color: var(--muted, #41454d);
    font-size: 13px;
    margin-bottom: 16px;
    padding: 8px 12px;
    background: var(--surface-soft, #f8fafc);
    border-radius: 6px;
  }

  .gov-pathb-editors {
    display: flex;
    gap: 16px;
    margin-bottom: 16px;

    @media (max-width: 1023px) {
      flex-direction: column;
    }
  }

  .gov-pathb-editor-col {
    flex: 1;
    min-width: 0;
  }

  .gov-pathb-editor-label {
    font-size: 14px;
    font-weight: 500;
    margin-bottom: 8px;
  }

  .gov-pathb-required {
    color: #ed4014;
    margin-left: 2px;
  }

  .gov-pathb-editor-wrapper {
    height: 240px;
    border: 1px solid #dddddd;
    border-radius: 6px;
    overflow: hidden;
  }

  .gov-pathb-actions {
    display: flex;
    justify-content: flex-end;
  }

  .gov-promotion-status {
    font-size: 13px;

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

  .footer {
    margin-top: 12px;
    display: flex;
    justify-content: flex-end;
  }
}
</style>
