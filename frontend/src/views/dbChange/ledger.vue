<template>
  <div class="page-shell db-change-ledger">
    <div class="page-shell__body">
      <section class="page-section">
        <div class="ledger-toolbar">
          <div class="ledger-toolbar__filters">
            <Select
              v-model="selectedPairId"
              filterable
              clearable
              :placeholder="$t('gov-ledger-select-db')"
              style="width: 320px"
              :loading="dbsLoading"
              @on-change="handleDbChange"
            >
              <Option v-for="db in dbOptions" :key="db.id" :value="db.id" :label="dbLabel(db)">
                {{ dbLabel(db) }}
              </Option>
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
            :data="ledgerTickets"
            @on-selection-change="handleSelectionChange"
            @on-row-click="handleRowClick"
          >
            <template #promoted="{ row }">
              <Tag v-if="row.promoted" color="warning">{{ $t('gov-ledger-promoted') }}</Tag>
              <Tag v-else color="success">{{ $t('gov-ledger-not-promoted') }}</Tag>
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
export default {
  name: 'DbChangeLedger',
  data() {
    return {
      dbOptions: [],
      dbsLoading: false,
      selectedPairId: null,
      selectedPair: null,
      ledgerTickets: [],
      listLoading: false,
      selectedTickets: [],
      createModalVisible: false,
      createTitle: '',
      creating: false
    };
  },
  computed: {
    canCreateRelease() {
      return this.selectedTickets.length > 0 && !this.creating;
    },
    ledgerColumns() {
      return [
        { type: 'selection', width: 55, align: 'center' },
        { title: this.$t('gov-ledger-ticket-id'), key: 'ticketId', width: 90 },
        { title: this.$t('gov-ledger-ticket-title'), key: 'ticketTitle', minWidth: 160, ellipsis: true, tooltip: true },
        { title: this.$t('gov-ledger-service'), key: 'serviceName', width: 120 },
        { title: this.$t('gov-ledger-executor'), key: 'ownerUid', width: 130 },
        { title: this.$t('gov-ledger-exec-time'), key: 'gmtCreate', width: 170, render: (h, params) => this.formatDateTime(params.row.gmtCreate) },
        { title: this.$t('gov-ledger-sql-summary'), key: 'sqlSummary', minWidth: 200, ellipsis: true, tooltip: true },
        { title: this.$t('gov-ledger-promoted-label'), slot: 'promoted', width: 110, align: 'center' },
        { title: this.$t('gov-ledger-release-no'), slot: 'releaseInfo', width: 170 }
      ];
    },
    targetProdDbs() {
      const pair = this.selectedPair;
      if (!pair) {
        return [];
      }
      return [this.formatProdDb(pair)].filter(Boolean);
    }
  },
  mounted() {
    this.loadDbs();
  },
  methods: {
    async loadDbs() {
      this.dbsLoading = true;
      try {
        const res = await this.$services.govLedgerDbs({ data: {} });
        if (res.success) {
          this.dbOptions = res.data || [];
        }
      } finally {
        this.dbsLoading = false;
      }
    },
    async handleDbChange(pairId) {
      this.selectedPair = this.dbOptions.find((db) => db.id === pairId) || null;
      this.selectedTickets = [];
      if (!pairId) {
        this.ledgerTickets = [];
        return;
      }
      await this.loadList();
    },
    async loadList() {
      const pair = this.selectedPair;
      if (!pair) {
        return;
      }
      this.listLoading = true;
      try {
        const res = await this.$services.govLedgerListByDb({
          data: { dsId: pair.preDsId, dbName: pair.preDbName }
        });
        if (res.success) {
          const tickets = res.data || [];
          tickets.forEach((t) => {
            if (t.promoted) {
              t._disabled = true;
            }
          });
          this.ledgerTickets = tickets;
        }
      } finally {
        this.listLoading = false;
      }
    },
    handleSelectionChange(selection) {
      this.selectedTickets = selection || [];
    },
    handleRowClick(row) {
      if (!row || !row.ticketId || !this.selectedPair) {
        return;
      }
      this.$router.push({
        path: '/dbChange/ledger/detail',
        query: {
          ticketId: row.ticketId,
          dsId: this.selectedPair.preDsId,
          dbName: this.selectedPair.preDbName
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
          await this.loadList();
          this.$router.push(`/dbChange/release/${releaseId}`);
        }
      } finally {
        this.creating = false;
      }
    },
    dbLabel(db) {
      const parts = [];
      if (db.preDsName) parts.push(db.preDsName);
      if (db.preDbName) parts.push(db.preDbName);
      if (db.preEnvName) parts.push(`(${db.preEnvName})`);
      return parts.join(' / ');
    },
    formatProdDb(pair) {
      if (!pair) return '';
      const parts = [];
      if (pair.prodDsName) parts.push(pair.prodDsName);
      if (pair.prodDbName) parts.push(pair.prodDbName);
      return parts.join(' / ');
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
