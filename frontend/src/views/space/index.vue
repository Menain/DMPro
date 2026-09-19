<template>
  <div class="page-shell space-usage">
    <div class="page-shell__body">
      <section class="page-section">
        <div class="space-toolbar">
          <div class="space-toolbar__filters">
            <Select
              v-model="selectedDsId"
              filterable
              clearable
              :placeholder="$t('kong-jian-xuan-shi-li')"
              style="width: 240px"
              @on-change="handleDsChange"
            >
              <Option v-for="inst in instanceList" :key="inst.dsId" :value="inst.dsId" :label="inst.dsName">
                {{ inst.dsName }}
              </Option>
            </Select>
            <Select
              v-model="selectedDbName"
              filterable
              clearable
              :disabled="!selectedDsId"
              :placeholder="$t('kong-jian-xuan-ku')"
              style="width: 200px"
              @on-change="handleDbChange"
            >
              <Option v-for="opt in dbOptions" :key="opt.value" :value="opt.value" :label="opt.label">{{ opt.label }}</Option>
            </Select>
            <Input
              v-model="tableNameFilter"
              clearable
              :disabled="!selectedDbName"
              :placeholder="$t('kong-jian-guo-lv-biao-ming')"
              style="width: 200px"
            />
          </div>
          <div class="space-toolbar__actions">
            <Button type="primary" ghost :disabled="!selectedDsId" :loading="dbLoading || tableLoading" @click="handleRefresh">
              <CustomIcon type="icon-v2-Refresh" />
              {{ $t('shua-xin') }}
            </Button>
          </div>
        </div>

        <div v-if="instanceList.length === 0 && !instanceLoading" class="space-empty-hint">
          {{ $t('kong-jian-shi-li-kong-ti-shi') }}
        </div>

        <!-- Instance summary: aggregated on the FULL database-level set, never filtered/paged (session-page discipline). -->
        <div v-if="dbs.length > 0" class="space-stats">
          <div class="space-stats__total">{{ $t('kong-jian-shi-li-zong-zhan-yong', [selectedDsName, formatBytes(summaryTotal)]) }}</div>
          <div class="space-stats__row">
            <span class="space-stats__item">{{ $t('kong-jian-shu-ju') }}: {{ summaryDataText }}</span>
            <span class="space-stats__item">{{ $t('kong-jian-suo-yin') }}: {{ summaryIndexText }}</span>
            <span class="space-stats__item">{{ $t('kong-jian-ku-shu') }}: {{ dbs.length }}</span>
            <span class="space-stats__item">{{ $t('kong-jian-biao-shu') }}: {{ summaryTableCountText }}</span>
          </div>
        </div>

        <!-- block 1: database level snapshot — MySQL user databases / PG every instance database -->
        <div class="space-block">
          <div class="page-section__title">{{ dbBlockTitle }}</div>
          <div v-if="dbScopeHint" class="space-scope-hint">{{ dbScopeHint }}</div>
          <Table
            size="small"
            border
            stripe
            :loading="dbLoading"
            :columns="dbColumns"
            :data="pagedDbs"
            :no-data-text="selectedDsId ? $t('kong-jian-ku-wu-shu-ju') : $t('kong-jian-xuan-shi-li')"
            @on-sort-change="handleDbSortChange"
          />
          <div v-if="sortedDbs.length > 0" class="space-footer">
            <Page
              :total="sortedDbs.length"
              :page-size="dbPageSize"
              :model-value="dbPage"
              show-total
              show-sizer
              show-elevator
              :page-size-opts="[20, 50, 100, 500]"
              @on-change="handleDbPageChange"
              @on-page-size-change="handleDbPageSizeChange"
            />
          </div>
        </div>

        <!-- block 2: table level snapshot — rendered only after a database is picked (no fake empty state) -->
        <div v-if="selectedDbName" class="space-block">
          <div class="page-section__title">{{ $t('kong-jian-biao-ji-zhan-yong') }}</div>
          <Table
            size="small"
            border
            stripe
            :loading="tableLoading"
            :columns="tableColumns"
            :data="pagedTables"
            :no-data-text="$t('kong-jian-biao-kong-ti-shi')"
            @on-sort-change="handleTblSortChange"
          />
          <div v-if="sortedTables.length > 0" class="space-footer">
            <Page
              :total="sortedTables.length"
              :page-size="tblPageSize"
              :model-value="tblPage"
              show-total
              show-sizer
              show-elevator
              :page-size-opts="[20, 50, 100, 500]"
              @on-change="handleTblPageChange"
              @on-page-size-change="handleTblPageSizeChange"
            />
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script>
import { formatBytes } from '@/utils/formatBytes';

export default {
  name: 'SpaceUsage',
  data() {
    return {
      instanceList: [],
      instanceLoading: false,
      selectedDsId: null,
      // databases payload (SpaceDatabasesVO)
      branch: '',
      dbs: [],
      dbLoading: false,
      // drill-down selection + tables payload (SpaceTablesVO)
      selectedDbName: null,
      tables: [],
      tableLoading: false,
      tableNameFilter: '',
      // database-level local paging + full-set sorting (total bytes default descending)
      dbPage: 1,
      dbPageSize: 50,
      dbSortKey: 'totalBytes',
      dbSortOrder: 'desc',
      // table-level local paging + sorting over the filtered set
      tblPage: 1,
      tblPageSize: 50,
      tblSortKey: 'totalBytes',
      tblSortOrder: 'desc'
    };
  },
  computed: {
    selectedDsName() {
      const inst = this.instanceList.find((i) => i.dsId === this.selectedDsId);
      if (!inst) {
        return '';
      }
      return inst.dsName;
    },
    isPostgres() {
      return this.branch === 'POSTGRES';
    },
    dbOptions() {
      return this.dbs.map((d) => ({ value: d.dbName, label: d.dbName }));
    },
    // Database level is per-database for BOTH dialects now (PG lists every instance database via
    // the shared catalog — 2026-09-19 ruling), so no schema wording branch remains here.
    dbBlockTitle() {
      if (!this.branch) {
        return '';
      }
      return this.$t('kong-jian-ku-ji-zhan-yong');
    },
    // Statistics-semantics copy (F3): MySQL estimate/cache caveat; PG breakdown-unreachable caveat.
    dbScopeHint() {
      if (!this.branch) {
        return '';
      }
      if (this.isPostgres) {
        return this.$t('kong-jian-pg-ku-ji-ti-shi');
      }
      return this.$t('kong-jian-mysql-gu-ji-ti-shi');
    },
    // Summary is instance-level: always the full dbs set, independent of any table filter/paging.
    // PG database rows carry totalBytes only — a null sum renders '-' instead of a misleading 0.
    summaryDataText() {
      return this.summaryText(this.sumField(this.dbs, 'dataBytes'), true);
    },
    summaryIndexText() {
      return this.summaryText(this.sumField(this.dbs, 'indexBytes'), true);
    },
    summaryTotal() {
      return this.sumField(this.dbs, 'totalBytes');
    },
    summaryTableCountText() {
      return this.summaryText(this.sumField(this.dbs, 'tableCount'), false);
    },
    sortedDbs() {
      return this.sortRows(this.dbs, this.dbSortKey, this.dbSortOrder);
    },
    pagedDbs() {
      const start = (this.dbPage - 1) * this.dbPageSize;
      return this.sortedDbs.slice(start, start + this.dbPageSize);
    },
    filteredTables() {
      const keyword = this.tableNameFilter.trim().toLowerCase();
      if (!keyword) {
        return this.tables;
      }
      return this.tables.filter((t) => (t.tableName || '').toLowerCase().includes(keyword));
    },
    sortedTables() {
      return this.sortRows(this.filteredTables, this.tblSortKey, this.tblSortOrder);
    },
    pagedTables() {
      const start = (this.tblPage - 1) * this.tblPageSize;
      return this.sortedTables.slice(start, start + this.tblPageSize);
    },
    dbColumns() {
      return [
        { title: this.$t('kong-jian-ku-ming'), key: 'dbName', minWidth: 160, ellipsis: true, tooltip: true, sortable: 'custom' },
        {
          title: this.$t('kong-jian-biao-shu'),
          key: 'tableCount',
          width: 100,
          sortable: 'custom',
          render: (h, { row }) => this.cellOrDash(row.tableCount)
        },
        {
          title: this.$t('kong-jian-hang-shu-gu-ji'),
          key: 'rowEstimate',
          width: 130,
          sortable: 'custom',
          render: (h, { row }) => this.cellOrDash(row.rowEstimate)
        },
        {
          title: this.$t('kong-jian-shu-ju'),
          key: 'dataBytes',
          width: 110,
          sortable: 'custom',
          render: (h, { row }) => this.bytesOrDash(row.dataBytes)
        },
        {
          title: this.$t('kong-jian-suo-yin'),
          key: 'indexBytes',
          width: 110,
          sortable: 'custom',
          render: (h, { row }) => this.bytesOrDash(row.indexBytes)
        },
        {
          title: this.$t('kong-jian-ke-hui-shou'),
          key: 'freeBytes',
          width: 110,
          sortable: 'custom',
          render: (h, { row }) => this.bytesOrDash(row.freeBytes)
        },
        {
          title: this.$t('kong-jian-zong-liang'),
          key: 'totalBytes',
          width: 120,
          sortable: 'custom',
          sortType: 'desc',
          render: (h, { row }) => this.bytesOrDash(row.totalBytes)
        }
      ];
    },
    tableColumns() {
      const columns = [];
      // PG drill-down lists every schema of the target database — the schema column disambiguates
      // same-named tables; MySQL table level is already scoped to the selected database.
      if (this.isPostgres) {
        columns.push({ title: this.$t('kong-jian-schema-ming'), key: 'schemaName', width: 140, ellipsis: true, tooltip: true, sortable: 'custom' });
      }
      columns.push(
        { title: this.$t('kong-jian-biao-ming'), key: 'tableName', minWidth: 200, ellipsis: true, tooltip: true, sortable: 'custom' },
        {
          title: this.$t('kong-jian-hang-shu-gu-ji'),
          key: 'rowEstimate',
          width: 130,
          sortable: 'custom',
          render: (h, { row }) => this.cellOrDash(row.rowEstimate)
        },
        {
          title: this.$t('kong-jian-shu-ju'),
          key: 'dataBytes',
          width: 110,
          sortable: 'custom',
          render: (h, { row }) => this.bytesOrDash(row.dataBytes)
        },
        {
          title: this.$t('kong-jian-suo-yin'),
          key: 'indexBytes',
          width: 110,
          sortable: 'custom',
          render: (h, { row }) => this.bytesOrDash(row.indexBytes)
        },
        {
          title: this.$t('kong-jian-ke-hui-shou'),
          key: 'freeBytes',
          width: 110,
          sortable: 'custom',
          render: (h, { row }) => this.bytesOrDash(row.freeBytes)
        },
        {
          title: this.$t('kong-jian-zong-liang'),
          key: 'totalBytes',
          width: 120,
          sortable: 'custom',
          sortType: 'desc',
          render: (h, { row }) => this.bytesOrDash(row.totalBytes)
        }
      );
      return columns;
    }
  },
  watch: {
    sortedDbs(list) {
      // Clamp when the data shrinks (silent refresh) without resetting the page.
      const maxPage = Math.max(1, Math.ceil(list.length / this.dbPageSize));
      if (this.dbPage > maxPage) {
        this.dbPage = maxPage;
      }
    },
    sortedTables(list) {
      const maxPage = Math.max(1, Math.ceil(list.length / this.tblPageSize));
      if (this.tblPage > maxPage) {
        this.tblPage = maxPage;
      }
    }
  },
  mounted() {
    this.loadInstances();
  },
  methods: {
    formatBytes,
    async loadInstances() {
      this.instanceLoading = true;
      try {
        const res = await this.$services.dmSpaceListInstances();
        this.instanceList = res.data || [];
      } finally {
        this.instanceLoading = false;
      }
    },
    handleDsChange() {
      this.resetAll();
      // An in-flight load for the previous datasource is dropped as stale and won't reset the
      // loading flags; clear them here so the tables/refresh button don't stay in loading state.
      this.dbLoading = false;
      this.tableLoading = false;
      this.loadDatabases();
    },
    resetAll() {
      this.branch = '';
      this.dbs = [];
      this.selectedDbName = null;
      this.tables = [];
      this.tableNameFilter = '';
      this.dbPage = 1;
      this.tblPage = 1;
    },
    async loadDatabases() {
      if (!this.selectedDsId) {
        return;
      }
      const dsId = this.selectedDsId;
      this.dbLoading = true;
      this.dbPage = 1;
      try {
        const res = await this.$services.dmSpaceDatabases({ data: { dsId } });
        // Drop stale responses when the datasource was switched while in flight.
        if (dsId !== this.selectedDsId) {
          return;
        }
        const data = res.data || {};
        this.branch = data.branch || '';
        this.dbs = data.dbs || [];
        // Keep the drill-down consistent: drop a selection that vanished from the fresh snapshot.
        if (this.selectedDbName && !this.dbs.some((d) => d.dbName === this.selectedDbName)) {
          this.selectedDbName = null;
          this.tables = [];
        }
      } finally {
        if (dsId === this.selectedDsId) {
          this.dbLoading = false;
        }
      }
    },
    handleDbChange() {
      this.tables = [];
      this.tableNameFilter = '';
      this.tblPage = 1;
      this.tableLoading = false;
      if (this.selectedDbName) {
        this.loadTables();
      }
    },
    async loadTables() {
      if (!this.selectedDsId || !this.selectedDbName) {
        return;
      }
      const dsId = this.selectedDsId;
      const dbName = this.selectedDbName;
      this.tableLoading = true;
      this.tblPage = 1;
      try {
        const res = await this.$services.dmSpaceTables({ data: { dsId, dbName } });
        // Drop stale responses when the datasource or database was switched while in flight.
        if (dsId !== this.selectedDsId || dbName !== this.selectedDbName) {
          return;
        }
        const data = res.data || {};
        this.tables = data.tables || [];
      } finally {
        if (dsId === this.selectedDsId && dbName === this.selectedDbName) {
          this.tableLoading = false;
        }
      }
    },
    async handleRefresh() {
      if (!this.selectedDsId) {
        return;
      }
      await this.loadDatabases();
      if (this.selectedDbName) {
        await this.loadTables();
      }
    },
    handleDbSortChange({ key, order }) {
      if (order === 'normal') {
        this.dbSortKey = '';
        this.dbSortOrder = 'normal';
      } else {
        this.dbSortKey = key;
        this.dbSortOrder = order;
      }
    },
    handleDbPageChange(page) {
      this.dbPage = page;
    },
    handleDbPageSizeChange(size) {
      this.dbPageSize = size;
      this.dbPage = 1;
    },
    handleTblSortChange({ key, order }) {
      if (order === 'normal') {
        this.tblSortKey = '';
        this.tblSortOrder = 'normal';
      } else {
        this.tblSortKey = key;
        this.tblSortOrder = order;
      }
    },
    handleTblPageChange(page) {
      this.tblPage = page;
    },
    handleTblPageSizeChange(size) {
      this.tblPageSize = size;
      this.tblPage = 1;
    },
    // Null-aware sum: null when EVERY row lacks the field (PG database rows carry totalBytes only)
    // — a plain 0 would render as "0 B" and mislead; partial nulls still sum over the rest.
    sumField(rows, key) {
      let sum = 0;
      let seen = false;
      for (const row of rows) {
        const value = row[key];
        if (value !== null && value !== undefined) {
          sum += value;
          seen = true;
        }
      }
      return seen ? sum : null;
    },
    summaryText(value, isBytes) {
      if (value === null || value === undefined) {
        return '-';
      }
      if (isBytes) {
        return formatBytes(value) ?? '-';
      }
      return String(value);
    },
    // Full-set sort shared by both tables; string columns sort lexically, numeric columns by value.
    sortRows(rows, key, order) {
      if (!key || order === 'normal') {
        return rows;
      }
      const factor = order === 'asc' ? 1 : -1;
      return rows.slice().sort((a, b) => {
        const left = a[key];
        const right = b[key];
        if (typeof left === 'string' || typeof right === 'string') {
          return factor * String(left ?? '').localeCompare(String(right ?? ''));
        }
        return factor * ((left ?? -1) - (right ?? -1));
      });
    },
    bytesOrDash(value) {
      if (value === null || value === undefined) {
        return '-';
      }
      return formatBytes(value) ?? '-';
    },
    cellOrDash(value) {
      if (value === null || value === undefined || value === '') {
        return '-';
      }
      return String(value);
    }
  }
};
</script>

<style lang="less" scoped>
.space-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  gap: 8px;
  flex-wrap: wrap;
}
.space-toolbar__filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.space-toolbar__actions {
  display: flex;
  align-items: center;
  gap: 16px;
}
.space-empty-hint {
  padding: 32px;
  text-align: center;
  color: var(--text-secondary);
  font-size: 14px;
}
// Flat light-surface strip (no border card, per ui-layout-consistency skill).
.space-stats {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 12px;
  padding: 8px 12px;
  background: var(--bg-secondary);
  border-radius: 6px;
  font-size: 13px;
  color: var(--text-secondary);
}
.space-stats__total {
  color: var(--text-primary);
}
.space-stats__row {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 16px;
}
.space-stats__item {
  white-space: nowrap;
}
// Flat layout: blocks are separated by title + gap, no wireframe cards (ui-layout-consistency skill).
.space-block {
  margin-top: 24px;

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
}
.space-scope-hint {
  margin: -8px 0 12px;
  font-size: 13px;
  color: var(--text-secondary);
}
.space-footer {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
