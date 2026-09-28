<template>
  <div class="ghost-launch">
    <div class="page-section__title">{{ $t('wu-suo-fa-qi-qian-yi') }}</div>

    <div class="ghost-launch__filters">
      <Select v-model="selectedDsId" filterable clearable :placeholder="$t('wu-suo-xuan-shi-li')" style="width: 240px" @on-change="handleDsChange">
        <Option v-for="inst in instanceList" :key="inst.dsId" :value="inst.dsId" :label="inst.dsName">
          {{ inst.dsName }}
        </Option>
      </Select>
      <Select
        v-model="selectedDbName"
        filterable
        clearable
        :disabled="!selectedDsId"
        :placeholder="$t('wu-suo-xuan-ku')"
        style="width: 200px"
        @on-change="handleDbChange"
      >
        <Option v-for="db in dbList" :key="db.dbName" :value="db.dbName" :label="db.dbName">{{ db.dbName }}</Option>
      </Select>
      <Select
        v-model="selectedTableName"
        filterable
        clearable
        :disabled="!selectedDbName"
        :loading="tableLoading"
        :placeholder="$t('wu-suo-xuan-biao')"
        style="width: 220px"
        @on-change="handleTableChange"
      >
        <Option v-for="tbl in tableList" :key="tbl.tableName" :value="tbl.tableName" :label="tbl.tableName">{{ tbl.tableName }}</Option>
      </Select>
    </div>

    <div v-if="instanceList.length === 0 && !instanceLoading" class="ghost-launch__empty-hint">
      {{ $t('wu-suo-shi-li-kong-ti-shi') }}
    </div>

    <div class="ghost-launch__ddl">
      <div class="panel-subheading">{{ $t('wu-suo-ddl-biao-ti') }}</div>
      <Input v-model="ddlSql" type="textarea" :rows="4" :placeholder="$t('wu-suo-ddl-placeholder')" @on-blur="handleDdlBlur" />
      <div class="ghost-launch__ddl-hint">{{ $t('wu-suo-ddl-ti-shi') }}</div>
    </div>

    <Collapse simple v-model="paramPanels" class="ghost-launch__params">
      <Panel name="params">
        <span class="panel-subheading">{{ $t('wu-suo-can-shu') }}</span>
        <template #content>
          <div class="ghost-launch__params-hint">{{ $t('wu-suo-can-shu-ti-shi') }}</div>
          <div class="ghost-launch__params-grid">
            <div class="ghost-param">
              <span class="ghost-param__label">{{ $t('wu-suo-param-chunk-size') }}</span>
              <InputNumber v-model="options.chunkSize" :min="100" :step="100" style="width: 140px" />
            </div>
            <div class="ghost-param">
              <span class="ghost-param__label">{{ $t('wu-suo-param-dml-batch-size') }}</span>
              <InputNumber v-model="options.dmlBatchSize" :min="1" :step="1" style="width: 140px" />
            </div>
            <div class="ghost-param">
              <span class="ghost-param__label">{{ $t('wu-suo-param-cut-over-type') }}</span>
              <Select v-model="options.cutOverType" style="width: 140px">
                <Option v-for="opt in cutOverTypeOptions" :key="opt" :value="opt">{{ opt }}</Option>
              </Select>
            </div>
            <div class="ghost-param">
              <span class="ghost-param__label">{{ $t('wu-suo-param-cut-over-lock-timeout') }}</span>
              <InputNumber v-model="options.cutOverLockTimeoutSec" :min="1" :step="1" style="width: 140px" />
            </div>
            <div class="ghost-param">
              <span class="ghost-param__label">{{ $t('wu-suo-param-hb-interval') }}</span>
              <InputNumber v-model="options.hbIntervalMs" :min="100" :step="100" style="width: 140px" />
            </div>
            <div class="ghost-param ghost-param--check">
              <Checkbox v-model="options.assumeRbr">{{ $t('wu-suo-param-assume-rbr') }}</Checkbox>
            </div>
            <div class="ghost-param ghost-param--check">
              <Checkbox v-model="options.allowOnMaster">{{ $t('wu-suo-param-allow-on-master') }}</Checkbox>
            </div>
            <div class="ghost-param ghost-param--check">
              <Checkbox v-model="options.assumeDsProxyInFront">{{ $t('wu-suo-param-assume-ds-proxy') }}</Checkbox>
            </div>
            <div class="ghost-param ghost-param--check">
              <Checkbox v-model="options.timestampOldTable">{{ $t('wu-suo-param-timestamp-old-table') }}</Checkbox>
            </div>
            <div class="ghost-param ghost-param--check">
              <Checkbox v-model="options.initiallyDropGhostTable">{{ $t('wu-suo-param-initially-drop-ghost-table') }}</Checkbox>
            </div>
            <div class="ghost-param ghost-param--check">
              <Checkbox v-model="options.exactRowCount">{{ $t('wu-suo-param-exact-row-count') }}</Checkbox>
            </div>
            <div class="ghost-param ghost-param--check">
              <Checkbox v-model="options.concurrentRowCount">{{ $t('wu-suo-param-concurrent-row-count') }}</Checkbox>
            </div>
            <div class="ghost-param ghost-param--check">
              <Checkbox v-model="options.verbose">{{ $t('wu-suo-param-verbose') }}</Checkbox>
            </div>
          </div>
        </template>
      </Panel>
    </Collapse>

    <!-- precheck result zone: backend-translated reasons are rendered verbatim (zero frontend keys for them) -->
    <div v-if="precheckLoading" class="ghost-launch__precheck-hint">
      <Spin size="small" />
      <span>{{ $t('wu-suo-jian-cha-zhong') }}</span>
    </div>
    <template v-if="precheck">
      <Alert v-if="precheck.hardBlocks.length > 0" type="error" class="ghost-launch__alert">
        <div class="ghost-launch__alert-title">{{ $t('wu-suo-ying-jian-zu') }}</div>
        <div v-for="block in precheck.hardBlocks" :key="block.code">{{ block.reason }}</div>
      </Alert>
      <Alert v-if="precheck.suggestions.length > 0" type="warning" class="ghost-launch__alert">
        <div class="ghost-launch__alert-title">{{ $t('wu-suo-jian-yi-biao-ti') }}</div>
        <div v-for="sug in precheck.suggestions" :key="sug.code">
          <div>{{ sug.reason }}</div>
          <div class="ghost-launch__alert-detail">{{ suggestionDetail(sug) }}</div>
        </div>
      </Alert>
      <div v-if="precheck.table" class="ghost-launch__table-stats">
        <div class="ghost-launch__alert-title">{{ $t('wu-suo-biao-xin-xi') }}: {{ precheck.table.tableName }}</div>
        <div class="ghost-launch__stats-row">
          <span class="ghost-launch__stats-item">{{ $t('wu-suo-biao-da-xiao') }}: {{ bytesOrDash(precheck.table.totalBytes) }}</span>
          <span class="ghost-launch__stats-item">{{ $t('wu-suo-shu-ju') }}: {{ bytesOrDash(precheck.table.dataLength) }}</span>
          <span class="ghost-launch__stats-item">{{ $t('wu-suo-suo-yin') }}: {{ bytesOrDash(precheck.table.indexLength) }}</span>
          <span class="ghost-launch__stats-item">{{ $t('wu-suo-hang-shu') }}: {{ numberOrDash(precheck.table.tableRows) }}</span>
        </div>
        <div class="ghost-launch__stats-row">
          <span class="ghost-launch__stats-item">{{ $t('wu-suo-ci-pan-yu-ji', [bytesOrDash(precheck.table.estimatedDiskBytes)]) }}</span>
        </div>
        <div class="ghost-launch__disk-hint">{{ $t('wu-suo-ci-pan-ti-shi') }}</div>
      </div>
    </template>

    <div class="ghost-launch__actions">
      <Button type="primary" :disabled="!canSubmit" :loading="executing" @click="handleExecute">
        {{ $t('wu-suo-fa-qi') }}
      </Button>
    </div>
  </div>
</template>

<script>
import { formatBytes } from '@/utils/formatBytes';

// Task-level options; defaults mirror the GhostOptionsDTO initializers. The form always sends all
// fields and the backend merge is task-first per field (GhostTools.mergeOptions), so the values
// shown are exactly the effective ones; admin-level GhostPluginConfig only governs callers that
// omit options (e.g. the S7 ticket path).
const DEFAULT_OPTIONS = {
  chunkSize: 3000,
  dmlBatchSize: 10,
  cutOverType: 'DEFAULT',
  cutOverLockTimeoutSec: 10,
  assumeRbr: true,
  allowOnMaster: true,
  assumeDsProxyInFront: true,
  timestampOldTable: true,
  initiallyDropGhostTable: true,
  hbIntervalMs: 2000,
  exactRowCount: false,
  concurrentRowCount: false,
  verbose: false
};

export default {
  name: 'GhostLaunchForm',
  emits: ['started'],
  data() {
    return {
      instanceList: [],
      instanceLoading: false,
      selectedDsId: null,
      dbList: [],
      dbLoading: false,
      selectedDbName: null,
      tableList: [],
      tableLoading: false,
      selectedTableName: null,
      ddlSql: '',
      paramPanels: [],
      // gh-ost --cut-over enum values (technical tokens, rendered verbatim)
      cutOverTypeOptions: ['DEFAULT', 'ATOMIC'],
      options: { ...DEFAULT_OPTIONS },
      precheck: null,
      precheckLoading: false,
      executing: false
    };
  },
  computed: {
    canSubmit() {
      return !!this.selectedDsId && !!this.selectedDbName && !!this.ddlSql.trim() && !this.hasHardBlocks;
    },
    hasHardBlocks() {
      return !!this.precheck && this.precheck.hardBlocks.length > 0;
    }
  },
  mounted() {
    this.loadInstances();
  },
  methods: {
    async loadInstances() {
      this.instanceLoading = true;
      try {
        const res = await this.$services.dmSpaceListInstances();
        // gh-ost is MySQL-only (the backend precheck hard-blocks everything else); the space
        // listInstances whitelist also returns PG and the MySQL-family variants, so narrow it here.
        this.instanceList = (res.data || []).filter((inst) => inst.dsType === 'MySQL');
      } finally {
        this.instanceLoading = false;
      }
    },
    handleDsChange() {
      this.resetSelection();
      this.dbLoading = false;
      this.tableLoading = false;
      this.loadDatabases();
    },
    resetSelection() {
      this.dbList = [];
      this.selectedDbName = null;
      this.tableList = [];
      this.selectedTableName = null;
      this.precheck = null;
      this.precheckLoading = false;
    },
    async loadDatabases() {
      if (!this.selectedDsId) {
        return;
      }
      const dsId = this.selectedDsId;
      this.dbLoading = true;
      try {
        const res = await this.$services.dmSpaceDatabases({ data: { dsId } });
        if (dsId !== this.selectedDsId) {
          return;
        }
        this.dbList = (res.data && res.data.dbs) || [];
      } finally {
        if (dsId === this.selectedDsId) {
          this.dbLoading = false;
        }
      }
    },
    handleDbChange() {
      this.tableList = [];
      this.selectedTableName = null;
      this.precheck = null;
      this.tableLoading = false;
      this.loadTables();
      this.runPrecheck();
    },
    async loadTables() {
      if (!this.selectedDsId || !this.selectedDbName) {
        return;
      }
      const dsId = this.selectedDsId;
      const dbName = this.selectedDbName;
      this.tableLoading = true;
      try {
        const res = await this.$services.dmSpaceTables({ data: { dsId, dbName } });
        if (dsId !== this.selectedDsId || dbName !== this.selectedDbName) {
          return;
        }
        this.tableList = (res.data && res.data.tables) || [];
      } finally {
        if (dsId === this.selectedDsId && dbName === this.selectedDbName) {
          this.tableLoading = false;
        }
      }
    },
    handleTableChange() {
      this.runPrecheck();
    },
    handleDdlBlur() {
      this.runPrecheck();
    },
    // Precheck is re-run on every db/table pick and on SQL blur; a stale in-flight response is
    // dropped by comparing the selection snapshot taken at request time (space-page precedent).
    // The backend rejects a probe with neither table nor statement ("nothing to probe"), so a
    // bare db pick must not call it — it would surface a spurious error modal.
    async runPrecheck() {
      if (!this.selectedDsId || !this.selectedDbName || (!this.selectedTableName && !this.ddlSql.trim())) {
        this.precheck = null;
        return;
      }
      const snapshot = {
        dsId: this.selectedDsId,
        dbName: this.selectedDbName,
        tableName: this.selectedTableName,
        ddlSql: this.ddlSql.trim()
      };
      this.precheckLoading = true;
      try {
        const res = await this.$services.dmGhostPrecheck({ data: snapshot });
        if (
          snapshot.dsId !== this.selectedDsId ||
          snapshot.dbName !== this.selectedDbName ||
          snapshot.tableName !== this.selectedTableName ||
          snapshot.ddlSql !== this.ddlSql.trim()
        ) {
          return;
        }
        if (res.success) {
          const data = res.data || {};
          this.precheck = {
            hardBlocks: data.hardBlocks || [],
            suggestions: data.suggestions || [],
            table: data.table || null
          };
        }
      } finally {
        if (
          snapshot.dsId === this.selectedDsId &&
          snapshot.dbName === this.selectedDbName &&
          snapshot.tableName === this.selectedTableName &&
          snapshot.ddlSql === this.ddlSql.trim()
        ) {
          this.precheckLoading = false;
        }
      }
    },
    async handleExecute() {
      this.executing = true;
      try {
        const res = await this.$services.dmGhostExecute({
          data: {
            dsId: this.selectedDsId,
            dbName: this.selectedDbName,
            ddlSql: this.ddlSql,
            options: { ...this.options }
          }
        });
        if (res.success) {
          this.$Message.success(this.$t('wu-suo-fa-qi-cheng-gong'));
          this.$emit('started', res.data.sessionId);
        }
      } finally {
        this.executing = false;
      }
    },
    suggestionDetail(sug) {
      const parts = [];
      if (sug.sizeBytes !== null && sug.sizeBytes !== undefined) {
        parts.push(`${this.$t('wu-suo-biao-da-xiao')} ${formatBytes(sug.sizeBytes)}`);
      }
      if (sug.tableRows !== null && sug.tableRows !== undefined) {
        parts.push(`${this.$t('wu-suo-hang-shu')} ${sug.tableRows}`);
      }
      const thresholds = [];
      if (sug.sizeThresholdBytes !== null && sug.sizeThresholdBytes !== undefined) {
        thresholds.push(formatBytes(sug.sizeThresholdBytes));
      }
      if (sug.rowThreshold !== null && sug.rowThreshold !== undefined) {
        thresholds.push(`${sug.rowThreshold}`);
      }
      if (thresholds.length > 0) {
        parts.push(`${this.$t('wu-suo-yu-zhi')} ${thresholds.join(' / ')}`);
      }
      return parts.join(' · ');
    },
    bytesOrDash(value) {
      if (value === null || value === undefined) {
        return '-';
      }
      return formatBytes(value) ?? '-';
    },
    numberOrDash(value) {
      if (value === null || value === undefined) {
        return '-';
      }
      return String(value);
    }
  }
};
</script>

<style lang="less" scoped>
.ghost-launch {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
// Standard block title (space/lockAnalysis local pattern); spacing comes from the flex gap.
.page-section__title {
  font-size: 16px;
  font-weight: 500;
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
.ghost-launch__filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.ghost-launch__empty-hint {
  padding: 16px;
  text-align: center;
  color: var(--text-secondary);
  font-size: 14px;
}
.ghost-launch__ddl-hint {
  margin-top: 6px;
  font-size: 13px;
  color: var(--text-secondary);
}
.panel-subheading {
  margin-bottom: 8px;
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 500;
}
.ghost-launch__params {
  max-width: 860px;
}
.ghost-launch__params-hint {
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--text-secondary);
}
.ghost-launch__params-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px 24px;
}
.ghost-param {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.ghost-param__label {
  font-size: 13px;
  color: var(--text-primary);
}
.ghost-param--check {
  justify-content: flex-start;
}
.ghost-launch__precheck-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-secondary);
}
.ghost-launch__alert {
  max-width: 860px;
}
.ghost-launch__alert-title {
  font-weight: 500;
  margin-bottom: 4px;
}
.ghost-launch__alert-detail {
  font-size: 13px;
  opacity: 0.85;
}
.ghost-launch__table-stats {
  padding: 8px 12px;
  background: var(--bg-secondary);
  border-radius: 6px;
  font-size: 13px;
  color: var(--text-secondary);
  max-width: 860px;
}
.ghost-launch__stats-row {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 16px;
  margin-top: 4px;
}
.ghost-launch__stats-item {
  white-space: nowrap;
}
.ghost-launch__disk-hint {
  margin-top: 6px;
  font-size: 13px;
  color: var(--text-secondary);
}
.ghost-launch__actions {
  display: flex;
  justify-content: flex-start;
}
</style>
