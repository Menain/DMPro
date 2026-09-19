<template>
  <div class="page-shell lock-analysis">
    <div class="page-shell__body">
      <section class="page-section">
        <div class="lock-toolbar">
          <div class="lock-toolbar__filters">
            <Select
              v-model="selectedDsId"
              filterable
              clearable
              :placeholder="$t('suo-xuan-shi-shi-li')"
              style="width: 240px"
              @on-change="handleDsChange"
            >
              <Option v-for="inst in instanceList" :key="inst.dsId" :value="inst.dsId" :label="inst.dsName">
                {{ inst.dsName }}
              </Option>
            </Select>
          </div>
          <div class="lock-toolbar__actions">
            <span class="lock-toolbar__auto-refresh">
              <i-switch v-model="autoRefresh" size="small" @on-change="handleAutoRefreshChange" />
              <span class="lock-toolbar__auto-refresh-label">{{ $t('suo-zi-dong-shua-xin') }}</span>
            </span>
            <Button type="primary" ghost :disabled="!selectedDsId" :loading="listLoading" @click="loadOverview()">
              <CustomIcon type="icon-v2-Refresh" />
              {{ $t('shua-xin') }}
            </Button>
          </div>
        </div>

        <div v-if="instanceList.length === 0 && !instanceLoading" class="lock-empty-hint">
          {{ $t('suo-shi-li-kong-ti-shi') }}
        </div>

        <!-- Already-translated backend text (PG account lacking pg_monitor); MySQL branch is always null. -->
        <Alert v-if="privilegeHint" type="warning" class="lock-privilege-hint">{{ privilegeHint }}</Alert>

        <!-- block 1: lock waits -->
        <div class="lock-block">
          <div class="page-section__title">{{ $t('suo-suo-deng-dai') }}</div>
          <Alert v-if="partialErrors.lockWaits" type="warning" class="lock-block-alert">{{ partialErrors.lockWaits }}</Alert>
          <Table
            size="small"
            border
            stripe
            :loading="listLoading"
            :columns="waitColumns"
            :data="pagedLockWaits"
            :no-data-text="selectedDsId ? $t('suo-wu-suo-deng-dai') : $t('suo-xuan-shi-shi-li')"
            @on-sort-change="handleWaitSortChange"
          >
            <template #waitingPid="{ row }">
              <span class="lock-pid-cell">
                <span>{{ cellOrDash(row.waitingPid) }}</span>
                <Button v-if="row.waitingPid" type="text" size="small" class="lock-copy-btn" @click="copyText(row.waitingPid)">
                  <CustomIcon type="icon-v2-CopyOutline" />
                </Button>
              </span>
            </template>
            <template #waitingQuery="{ row }">
              <span v-if="!row.waitingQuery">-</span>
              <span
                v-else-if="isLongText(row.waitingQuery)"
                class="lock-sql-cell"
                @mouseenter="openSqlPop(row.waitingQuery, $event)"
                @mouseleave="closeSqlPopSoon"
              >
                {{ row.waitingQuery }}
              </span>
              <span v-else class="lock-sql-plain" :title="row.waitingQuery">{{ row.waitingQuery }}</span>
            </template>
            <template #blockingPid="{ row }">
              <span class="lock-pid-cell">
                <span>{{ cellOrDash(row.blockingPid) }}</span>
                <Button v-if="row.blockingPid" type="text" size="small" class="lock-copy-btn" @click="copyText(row.blockingPid)">
                  <CustomIcon type="icon-v2-CopyOutline" />
                </Button>
              </span>
            </template>
            <template #blockingQuery="{ row }">
              <!-- null blocking query = idle transaction holding the lock (design F1) -->
              <span v-if="!row.blockingQuery" class="lock-idle-holder">{{ $t('suo-kong-xian-shi-wu-chi-suo') }}</span>
              <span
                v-else-if="isLongText(row.blockingQuery)"
                class="lock-sql-cell"
                @mouseenter="openSqlPop(row.blockingQuery, $event)"
                @mouseleave="closeSqlPopSoon"
              >
                {{ row.blockingQuery }}
              </span>
              <span v-else class="lock-sql-plain" :title="row.blockingQuery">{{ row.blockingQuery }}</span>
            </template>
          </Table>
          <div v-if="sortedLockWaits.length > 0" class="lock-footer">
            <Page
              :total="sortedLockWaits.length"
              :page-size="waitPageSize"
              :model-value="waitPage"
              show-total
              show-sizer
              show-elevator
              :page-size-opts="[20, 50, 100, 500]"
              @on-change="handleWaitPageChange"
              @on-page-size-change="handleWaitPageSizeChange"
            />
          </div>
        </div>

        <!-- block 2: running transactions -->
        <div class="lock-block">
          <div class="page-section__title">{{ $t('suo-yun-xing-zhong-shi-wu') }}</div>
          <Alert v-if="partialErrors.transactions" type="warning" class="lock-block-alert">{{ partialErrors.transactions }}</Alert>
          <Table size="small" border stripe :loading="listLoading" :columns="trxColumns" :data="pagedTransactions" :row-class-name="trxRowClassName">
            <template #threadPid="{ row }">
              <span class="lock-pid-cell">
                <span>{{ cellOrDash(row.threadPid) }}</span>
                <Button v-if="row.threadPid" type="text" size="small" class="lock-copy-btn" @click="copyText(row.threadPid)">
                  <CustomIcon type="icon-v2-CopyOutline" />
                </Button>
              </span>
            </template>
            <template #query="{ row }">
              <span v-if="!row.query">-</span>
              <span v-else-if="isLongText(row.query)" class="lock-sql-cell" @mouseenter="openSqlPop(row.query, $event)" @mouseleave="closeSqlPopSoon">
                {{ row.query }}
              </span>
              <span v-else class="lock-sql-plain" :title="row.query">{{ row.query }}</span>
            </template>
          </Table>
          <div v-if="transactions.length > 0" class="lock-footer">
            <Page
              :total="transactions.length"
              :page-size="trxPageSize"
              :model-value="trxPage"
              show-total
              show-sizer
              show-elevator
              :page-size-opts="[20, 50, 100, 500]"
              @on-change="handleTrxPageChange"
              @on-page-size-change="handleTrxPageSizeChange"
            />
          </div>
        </div>

        <!-- block 3: latest deadlock — rendered only when the backend supports it (MySQL); PG hides it, no fake empty state -->
        <div v-if="deadlockSupported" class="lock-block">
          <Collapse simple v-model="deadlockPanels">
            <Panel name="deadlock">
              <span class="lock-deadlock-title">{{ $t('suo-zui-jin-si-suo') }}</span>
              <template #content>
                <Alert v-if="partialErrors.deadlock" type="warning">{{ partialErrors.deadlock }}</Alert>
                <div v-else-if="deadlock" class="lock-deadlock-body">
                  <pre class="lock-deadlock-pre">{{ deadlock }}</pre>
                  <Button size="small" type="primary" ghost @click="copyText(deadlock)">
                    {{ $t('suo-fu-zhi-quan-wen') }}
                  </Button>
                </div>
                <div v-else-if="deadlockAbsent" class="lock-deadlock-absent">{{ $t('suo-si-suo-wu-ji-lu') }}</div>
              </template>
            </Panel>
          </Collapse>
        </div>
      </section>
    </div>
    <sql-hover-popover ref="sqlPop" />
  </div>
</template>

<script>
import copyMixin from '@/mixins/copyMixin';
import SqlHoverPopover from '@/components/widgets/SqlHoverPopover.vue';

const AUTO_REFRESH_INTERVAL_MS = 10000;
/** Transactions running at least this long are highlighted (constant threshold, phase 1 is not configurable). */
const LONG_TRX_SECONDS = 60;
const TEXT_POPTIP_MIN_LEN = 80;

export default {
  name: 'LockAnalysis',
  components: { SqlHoverPopover },
  mixins: [copyMixin],
  data() {
    return {
      instanceList: [],
      instanceLoading: false,
      selectedDsId: null,
      listLoading: false,
      autoRefresh: false,
      autoRefreshTimer: null,
      // overview payload (LockOverviewVO)
      lockWaits: [],
      transactions: [],
      deadlockSupported: false,
      deadlock: null,
      deadlockAbsent: false,
      privilegeHint: null,
      partialErrors: {},
      // lock-wait local paging + full-set sorting (wait seconds default descending)
      waitPage: 1,
      waitPageSize: 50,
      waitSortKey: 'waitSeconds',
      waitSortOrder: 'desc',
      // transaction local paging
      trxPage: 1,
      trxPageSize: 50,
      // deadlock collapse panel, collapsed by default
      deadlockPanels: []
    };
  },
  computed: {
    // Sorting happens on the full set; pagination below is a render slice only (B2 pattern).
    sortedLockWaits() {
      if (!this.waitSortKey || this.waitSortOrder === 'normal') {
        return this.lockWaits;
      }
      const factor = this.waitSortOrder === 'asc' ? 1 : -1;
      return this.lockWaits.slice().sort((a, b) => factor * ((a[this.waitSortKey] ?? -1) - (b[this.waitSortKey] ?? -1)));
    },
    pagedLockWaits() {
      const start = (this.waitPage - 1) * this.waitPageSize;
      return this.sortedLockWaits.slice(start, start + this.waitPageSize);
    },
    pagedTransactions() {
      const start = (this.trxPage - 1) * this.trxPageSize;
      return this.transactions.slice(start, start + this.trxPageSize);
    },
    waitColumns() {
      return [
        { title: this.$t('suo-deng-dai-hui-hua'), slot: 'waitingPid', width: 130 },
        {
          title: this.$t('suo-deng-dai-shi-chang'),
          key: 'waitSeconds',
          width: 110,
          sortable: 'custom',
          sortType: 'desc',
          render: (h, { row }) => this.cellOrDash(row.waitSeconds)
        },
        { title: this.$t('suo-deng-dai-sql'), slot: 'waitingQuery', minWidth: 200 },
        {
          title: this.$t('suo-suo-dui-xiang'),
          key: 'lockTable',
          width: 220,
          ellipsis: true,
          tooltip: true,
          render: (h, { row }) => this.lockObjectText(row)
        },
        {
          title: this.$t('suo-suo-lei-xing-mo-shi'),
          key: 'lockType',
          width: 130,
          ellipsis: true,
          tooltip: true,
          render: (h, { row }) => this.lockTypeModeText(row)
        },
        { title: this.$t('suo-zu-se-hui-hua'), slot: 'blockingPid', width: 130 },
        { title: this.$t('suo-zu-se-sql'), slot: 'blockingQuery', minWidth: 200 },
        {
          title: this.$t('suo-zu-se-shi-wu-qi-dong'),
          key: 'blockingTrxStarted',
          width: 180,
          ellipsis: true,
          tooltip: true,
          render: (h, { row }) => this.cellOrDash(row.blockingTrxStarted)
        }
      ];
    },
    trxColumns() {
      return [
        {
          title: this.$t('suo-shi-wu-id'),
          key: 'trxId',
          width: 140,
          ellipsis: true,
          tooltip: true,
          render: (h, { row }) => this.cellOrDash(row.trxId)
        },
        { title: this.$t('zhuang-tai'), key: 'state', width: 140, ellipsis: true, tooltip: true, render: (h, { row }) => this.cellOrDash(row.state) },
        { title: this.$t('suo-hui-hua-id'), slot: 'threadPid', width: 120 },
        {
          title: this.$t('suo-qi-dong-shi-jian'),
          key: 'started',
          width: 180,
          ellipsis: true,
          tooltip: true,
          render: (h, { row }) => this.cellOrDash(row.started)
        },
        {
          title: this.$t('suo-chi-xu-shi-chang'),
          key: 'durationSeconds',
          width: 110,
          render: (h, { row }) => this.cellOrDash(row.durationSeconds)
        },
        {
          title: this.$t('suo-suo-ding-hang-shu'),
          key: 'rowsLocked',
          width: 100,
          render: (h, { row }) => this.cellOrDash(row.rowsLocked)
        },
        {
          title: this.$t('suo-xiu-gai-hang-shu'),
          key: 'rowsModified',
          width: 100,
          render: (h, { row }) => this.cellOrDash(row.rowsModified)
        },
        { title: 'SQL', slot: 'query', minWidth: 240 }
      ];
    }
  },
  watch: {
    sortedLockWaits(list) {
      // Clamp when the data shrinks (silent auto-refresh) without resetting the page.
      const maxPage = Math.max(1, Math.ceil(list.length / this.waitPageSize));
      if (this.waitPage > maxPage) {
        this.waitPage = maxPage;
      }
    },
    transactions(list) {
      const maxPage = Math.max(1, Math.ceil(list.length / this.trxPageSize));
      if (this.trxPage > maxPage) {
        this.trxPage = maxPage;
      }
    }
  },
  mounted() {
    this.loadInstances();
  },
  beforeUnmount() {
    this.stopAutoRefresh();
  },
  methods: {
    async loadInstances() {
      this.instanceLoading = true;
      try {
        const res = await this.$services.dmLockAnalysisListInstances();
        this.instanceList = res.data || [];
      } finally {
        this.instanceLoading = false;
      }
    },
    handleDsChange() {
      this.resetOverview();
      // An in-flight load for the previous datasource is dropped as stale and won't reset
      // listLoading; clear it here so the tables/refresh button don't stay in loading state.
      this.listLoading = false;
      this.loadOverview();
    },
    resetOverview() {
      this.lockWaits = [];
      this.transactions = [];
      this.deadlockSupported = false;
      this.deadlock = null;
      this.deadlockAbsent = false;
      this.privilegeHint = null;
      this.partialErrors = {};
      this.waitPage = 1;
      this.trxPage = 1;
    },
    async loadOverview(silent) {
      if (!this.selectedDsId) {
        return;
      }
      const dsId = this.selectedDsId;
      if (!silent) {
        this.listLoading = true;
        this.waitPage = 1;
        this.trxPage = 1;
      }
      try {
        const res = await this.$services.dmLockAnalysisOverview({ data: { dsId } });
        // Drop stale responses when the datasource was switched while in flight.
        if (dsId !== this.selectedDsId) {
          return;
        }
        const data = res.data || {};
        this.lockWaits = data.lockWaits || [];
        this.transactions = data.transactions || [];
        this.deadlockSupported = !!data.deadlockSupported;
        this.deadlock = data.deadlock || null;
        this.deadlockAbsent = !!data.deadlockAbsent;
        this.privilegeHint = data.privilegeHint || null;
        this.partialErrors = data.partialErrors || {};
      } finally {
        if (dsId === this.selectedDsId && !silent) {
          this.listLoading = false;
        }
      }
    },
    handleAutoRefreshChange(on) {
      if (on) {
        this.startAutoRefresh();
      } else {
        this.stopAutoRefresh();
      }
    },
    startAutoRefresh() {
      this.stopAutoRefresh();
      this.autoRefreshTimer = window.setInterval(() => {
        this.loadOverview(true);
      }, AUTO_REFRESH_INTERVAL_MS);
    },
    stopAutoRefresh() {
      if (this.autoRefreshTimer) {
        window.clearInterval(this.autoRefreshTimer);
        this.autoRefreshTimer = null;
      }
    },
    handleWaitSortChange({ key, order }) {
      if (order === 'normal') {
        this.waitSortKey = '';
        this.waitSortOrder = 'normal';
      } else {
        this.waitSortKey = key;
        this.waitSortOrder = order;
      }
    },
    handleWaitPageChange(page) {
      this.waitPage = page;
    },
    handleWaitPageSizeChange(size) {
      this.waitPageSize = size;
      this.waitPage = 1;
    },
    handleTrxPageChange(page) {
      this.trxPage = page;
    },
    handleTrxPageSizeChange(size) {
      this.trxPageSize = size;
      this.trxPage = 1;
    },
    /** Long transactions (>= 60s) get a warning-background row highlight (F3).
     * DmTable wraps ant-design-vue: rowClassName is invoked with ({ record, index }), not view-ui's
     * ({ row, rowIndex }) — accepting both keeps the callback total (an undefined row threw inside
     * BodyRow render and aborted the whole page update, stranding a half-dead subtree). */
    trxRowClassName({ record, row }) {
      const target = record || row;
      if (target && target.durationSeconds != null && target.durationSeconds >= LONG_TRX_SECONDS) {
        return 'lock-trx-long';
      }
      return '';
    },
    isLongText(text) {
      return text.length > TEXT_POPTIP_MIN_LEN;
    },
    openSqlPop(text, event) {
      this.$refs.sqlPop.open(text, event.currentTarget.getBoundingClientRect());
    },
    closeSqlPopSoon() {
      this.$refs.sqlPop.closeSoon();
    },
    cellOrDash(value) {
      if (value === null || value === undefined || value === '') {
        return '-';
      }
      return String(value);
    },
    lockObjectText(row) {
      if (!row.lockTable) {
        return '-';
      }
      if (row.lockIndex) {
        return `${row.lockTable}.${row.lockIndex}`;
      }
      return row.lockTable;
    },
    lockTypeModeText(row) {
      const parts = [row.lockType, row.lockMode].filter(Boolean);
      if (parts.length === 0) {
        return '-';
      }
      return parts.join(' / ');
    }
  }
};
</script>

<style lang="less" scoped>
.lock-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  gap: 8px;
  flex-wrap: wrap;
}
.lock-toolbar__filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.lock-toolbar__actions {
  display: flex;
  align-items: center;
  gap: 16px;
}
.lock-toolbar__auto-refresh {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.lock-toolbar__auto-refresh-label {
  font-size: 13px;
  color: var(--text-secondary);
  white-space: nowrap;
}
.lock-empty-hint {
  padding: 32px;
  text-align: center;
  color: var(--text-secondary);
  font-size: 14px;
}
.lock-privilege-hint {
  margin-bottom: 12px;
}
// Flat layout: blocks are separated by title + gap, no wireframe cards (ui-layout-consistency skill).
.lock-block {
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
.lock-block-alert {
  margin-bottom: 12px;
}
.lock-pid-cell {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}
.lock-copy-btn {
  padding: 0 2px;
}
.lock-idle-holder {
  color: var(--text-secondary);
  font-size: 13px;
}
.lock-sql-cell {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
  color: var(--link-color);
}
.lock-sql-plain {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.lock-footer {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
.lock-deadlock-title {
  font-size: 16px;
  font-weight: 500;
}
.lock-deadlock-body {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}
.lock-deadlock-pre {
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  max-height: 480px;
  overflow: auto;
  margin: 0;
  width: 100%;
  background: var(--bg-secondary);
  padding: 8px;
  border-radius: 4px;
}
.lock-deadlock-absent {
  color: var(--text-secondary);
  font-size: 13px;
}
// Long-transaction row highlight (>= 60s); DmTable renders ant-design-vue rows (tr.<class>), not ivu-table.
:deep(tr.lock-trx-long td) {
  background-color: var(--warning-bg);
}
</style>
