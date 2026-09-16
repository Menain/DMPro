<template>
  <div class="page-shell db-change-ledger">
    <div class="page-shell__body">
      <section class="page-section">
        <div class="ledger-toolbar">
          <div class="ledger-toolbar__filters">
            <Select v-model="serviceFilter" clearable :placeholder="$t('gov-ledger-filter-service')" style="width: 200px">
              <Option value="" :label="$t('gov-ledger-filter-all')">{{ $t('gov-ledger-filter-all') }}</Option>
              <Option v-for="svc in serviceOptions" :key="svc.serviceId" :value="svc.serviceId" :label="svc.serviceName">
                {{ svc.serviceName }}
              </Option>
            </Select>
            <Select v-model="dbFilter" clearable :placeholder="$t('gov-ledger-filter-db')" style="width: 240px">
              <Option value="" :label="$t('gov-ledger-filter-all')">{{ $t('gov-ledger-filter-all') }}</Option>
              <Option v-for="db in dbOptions" :key="db.value" :value="db.value" :label="db.label">
                {{ db.label }}
              </Option>
            </Select>
            <Select v-model="promotedFilter" clearable :placeholder="$t('gov-ledger-filter-promoted')" style="width: 160px">
              <Option value="" :label="$t('gov-ledger-filter-all')">{{ $t('gov-ledger-filter-all') }}</Option>
              <Option value="NOT_PROMOTED" :label="$t('gov-ledger-not-promoted')">{{ $t('gov-ledger-not-promoted') }}</Option>
              <Option value="PROMOTED" :label="$t('gov-ledger-promoted')">{{ $t('gov-ledger-promoted') }}</Option>
            </Select>
            <Select v-model="ticketTypeFilter" clearable :placeholder="$t('gov-ledger-filter-type')" style="width: 160px">
              <Option value="" :label="$t('gov-ledger-filter-all')">{{ $t('gov-ledger-filter-all') }}</Option>
              <Option value="PRE_DDL" :label="$t('gov-v2-type-pre-ddl')">{{ $t('gov-v2-type-pre-ddl') }}</Option>
              <Option value="PROD_DML" :label="$t('gov-v2-type-prod-dml')">{{ $t('gov-v2-type-prod-dml') }}</Option>
            </Select>
          </div>
          <div class="ledger-toolbar__actions">
            <Button type="primary" :disabled="!canCreateRelease" :loading="creating" @click="handleShowCreateModal">
              {{ $t('gov-ledger-create-release') }}
            </Button>
          </div>
        </div>

        <div class="table-container">
          <Table
            size="small"
            border
            stripe
            :loading="listLoading"
            :columns="ledgerColumns"
            :data="filteredTickets"
            :row-selection="rowSelection"
            row-key="ticketId"
          >
            <template #promoted="{ row }">
              <Tag v-if="row.promoted" color="warning">{{ $t('gov-ledger-promoted') }}</Tag>
              <Tag v-else color="success">{{ $t('gov-ledger-not-promoted') }}</Tag>
            </template>
            <template #ticketType="{ row }">
              <Tag v-if="row.ticketType === 'PROD_DML'" color="warning">{{ $t('gov-v2-type-prod-dml') }}</Tag>
              <Tag v-else-if="row.ticketType === 'PRE_DDL'" color="primary">{{ $t('gov-v2-type-pre-ddl') }}</Tag>
              <span v-else>-</span>
            </template>
            <template #releaseInfo="{ row }">
              <span v-if="row.promoted && row.releaseNo" class="gov-mono">{{ row.releaseNo }}</span>
              <span v-else>-</span>
            </template>
          </Table>
        </div>
      </section>
    </div>

    <Modal
      v-model="createModalVisible"
      :title="$t('gov-ledger-create-release-title')"
      :ok-text="$t('gov-ledger-confirm-create')"
      :cancel-text="$t('gov-ledger-cancel')"
      :loading="creating"
      @on-ok="handleCreateRelease"
    >
      <div class="ledger-create-form">
        <div class="ledger-create-summary">
          <div class="ledger-create-summary-row">
            <span class="ledger-create-label">{{ $t('gov-ledger-selected-count') }}</span>
            <span>{{ selectedTickets.length }}</span>
          </div>
          <div class="ledger-create-summary-row">
            <span class="ledger-create-label">{{ $t('gov-ledger-target-dbs') }}</span>
            <div class="ledger-create-target-dbs">
              <Tag v-for="db in targetProdDbs" :key="db" size="small">{{ db }}</Tag>
            </div>
          </div>
        </div>
        <div class="ledger-create-title-input">
          <Input v-model="createTitle" :placeholder="$t('gov-ledger-title-placeholder')" maxlength="255" />
        </div>
      </div>
    </Modal>
  </div>
</template>

<script>
import formatError from '@/services/formatError';

export default {
  name: 'DbChangeLedger',
  data() {
    return {
      ledgerTickets: [],
      listLoading: false,
      selectedTickets: [],
      selectedRowKeys: [],
      serviceFilter: null,
      dbFilter: null,
      promotedFilter: 'NOT_PROMOTED',
      ticketTypeFilter: null,
      createModalVisible: false,
      createTitle: '',
      creating: false
    };
  },
  computed: {
    canCreateRelease() {
      return this.selectedTickets.length > 0 && !this.creating;
    },
    rowSelection() {
      return {
        // 筛选切换后已选行不在当前数据源,antd 默认会丢弃其 key;保留以支持跨筛选累积勾选
        preserveSelectedRowKeys: true,
        selectedRowKeys: this.selectedRowKeys,
        onChange: (selectedRowKeys, selectedRows) => {
          this.selectedRowKeys = selectedRowKeys;
          this.selectedTickets = selectedRows;
        },
        getCheckboxProps: (record) => ({ disabled: record.promoted })
      };
    },
    serviceOptions() {
      const seen = new Map();
      for (const t of this.ledgerTickets) {
        if (t.serviceId != null && !seen.has(t.serviceId)) {
          seen.set(t.serviceId, t.serviceName);
        }
      }
      const result = [];
      for (const [serviceId, serviceName] of seen) {
        result.push({ serviceId, serviceName });
      }
      return result;
    },
    dbOptions() {
      const seen = new Map();
      for (const t of this.ledgerTickets) {
        for (const db of t.preDbs || []) {
          const value = `${db.dsId}|${db.dbName}`;
          if (!seen.has(value)) {
            seen.set(value, `${db.dsName}/${db.dbName}`);
          }
        }
      }
      const result = [];
      for (const [value, label] of seen) {
        result.push({ value, label });
      }
      return result;
    },
    filteredTickets() {
      return this.ledgerTickets.filter((t) => {
        if (this.serviceFilter && t.serviceId !== this.serviceFilter) return false;
        if (this.dbFilter) {
          const matched = (t.preDbs || []).some((db) => `${db.dsId}|${db.dbName}` === this.dbFilter);
          if (!matched) return false;
        }
        if (this.promotedFilter === 'NOT_PROMOTED' && t.promoted) return false;
        if (this.promotedFilter === 'PROMOTED' && !t.promoted) return false;
        if (this.ticketTypeFilter && t.ticketType !== this.ticketTypeFilter) return false;
        return true;
      });
    },
    ledgerColumns() {
      return [
        {
          title: this.$t('gov-ledger-ticket-id'),
          key: 'ticketId',
          width: 90,
          render: (h, params) =>
            h(
              'a',
              {
                onClick: () => this.goLedgerDetail(params.row),
                style: { cursor: 'pointer' }
              },
              params.row.ticketId
            )
        },
        { title: this.$t('gov-ledger-ticket-title'), key: 'ticketTitle', minWidth: 160, ellipsis: true, tooltip: true },
        { title: this.$t('gov-ledger-service'), key: 'serviceName', width: 120 },
        { title: this.$t('gov-ledger-ticket-type'), slot: 'ticketType', width: 110, align: 'center' },
        {
          title: this.$t('gov-ledger-pre-db'),
          key: 'preDbs',
          minWidth: 160,
          ellipsis: true,
          render: (h, params) => {
            if (params.row.ticketType === 'PROD_DML') {
              return h('span', this.$t('zhan-wei-fu'));
            }
            const dbs = params.row.preDbs || [];
            const text = dbs.map((db) => `${db.dsName}/${db.dbName}`).join(', ');
            return h('span', text || '-');
          }
        },
        {
          title: this.$t('gov-ledger-prod-db'),
          key: 'prodDbs',
          minWidth: 160,
          ellipsis: true,
          render: (h, params) => {
            const dbs = params.row.prodDbs || [];
            const text = Array.isArray(dbs) ? dbs.join(', ') : String(dbs || '');
            return h('span', text || '-');
          }
        },
        { title: this.$t('gov-ledger-executor'), key: 'ownerUid', width: 130 },
        { title: this.$t('gov-ledger-exec-time'), key: 'gmtCreate', width: 170, render: (h, params) => this.formatDateTime(params.row.gmtCreate) },
        { title: this.$t('gov-ledger-sql-summary'), key: 'sqlSummary', minWidth: 200, ellipsis: true, tooltip: true },
        { title: this.$t('gov-ledger-promoted-label'), slot: 'promoted', width: 110, align: 'center' },
        { title: this.$t('gov-ledger-release-no'), slot: 'releaseInfo', width: 170 }
      ];
    },
    targetProdDbs() {
      const seen = new Set();
      const result = [];
      for (const t of this.selectedTickets) {
        for (const db of t.prodDbs || []) {
          if (!seen.has(db)) {
            seen.add(db);
            result.push(db);
          }
        }
      }
      return result;
    }
  },
  mounted() {
    this.loadList();
  },
  methods: {
    async loadList() {
      this.listLoading = true;
      try {
        const res = await this.$services.govLedgerList({ data: {} });
        if (res.success) {
          this.ledgerTickets = res.data || [];
          this.selectedRowKeys = [];
          this.selectedTickets = [];
        }
      } finally {
        this.listLoading = false;
      }
    },
    goLedgerDetail(row) {
      if (!row || !row.ticketId) {
        return;
      }
      this.$router.push({
        path: '/dbChange/ledger/detail',
        query: {
          ticketId: row.ticketId
        }
      });
    },
    handleShowCreateModal() {
      if (this.selectedTickets.length === 0) {
        return;
      }
      this.createTitle = '';
      this.createModalVisible = true;
    },
    async handleCreateRelease() {
      if (this.creating || this.selectedTickets.length === 0) {
        return;
      }
      this.creating = true;
      try {
        const ticketIds = this.selectedTickets.map((t) => t.ticketId);
        const res = await this.$services.govReleaseCreate({
          data: { ticketIds, title: this.createTitle || undefined }
        });
        if (res.success) {
          const releaseId = res.data;
          this.$Message.success(this.$t('gov-ledger-create-success'));
          this.createModalVisible = false;
          this.selectedTickets = [];
          this.selectedRowKeys = [];
          await this.loadList();
          this.$router.push(`/dbChange/release/${releaseId}`);
        } else {
          this.$Message.error(formatError(res.msg));
        }
      } finally {
        this.creating = false;
      }
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
.db-change-ledger {
  .page-section {
    margin-bottom: 32px;
  }

  .ledger-toolbar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 16px;
    gap: 16px;

    &__filters {
      display: flex;
      gap: 12px;
      flex-wrap: wrap;
    }

    &__actions {
      display: flex;
      gap: 8px;
      flex-shrink: 0;
    }
  }

  .table-container {
    overflow-x: auto;
  }

  .gov-mono {
    font-family: Menlo, Monaco, 'Courier New', monospace;
    font-size: 13px;
    word-break: break-all;
  }

  .ledger-create-form {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  .ledger-create-summary {
    background: var(--surface-soft, #f8fafc);
    padding: 12px 16px;
    border-radius: 10px;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .ledger-create-summary-row {
    display: flex;
    align-items: flex-start;
    gap: 12px;
    font-size: 14px;
  }

  .ledger-create-label {
    min-width: 100px;
    color: var(--muted, #41454d);
    flex-shrink: 0;
  }

  .ledger-create-target-dbs {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
  }
}
</style>
