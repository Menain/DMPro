<template>
  <div class="page-shell session-manage">
    <div class="page-shell__body">
      <section class="page-section">
        <div class="session-toolbar">
          <div class="session-toolbar__filters">
            <Select
              v-model="selectedDsId"
              filterable
              clearable
              :placeholder="$t('hui-hua-xuan-shi-shi-li')"
              style="width: 240px"
              @on-change="handleDsChange"
            >
              <Option v-for="inst in instanceList" :key="inst.dsId" :value="inst.dsId" :label="inst.dsName">
                {{ inst.dsName }}
              </Option>
            </Select>
            <Select v-model="userFilter" filterable clearable :placeholder="$t('hui-hua-guo-lv-yong-hu')" style="width: 160px">
              <Option v-for="opt in userOptions" :key="opt.value" :value="opt.value" :label="opt.label">{{ opt.label }}</Option>
            </Select>
            <Select v-model="dbFilter" filterable clearable :placeholder="$t('hui-hua-guo-lv-ku-ming')" style="width: 160px">
              <Option v-for="opt in dbOptions" :key="opt.value" :value="opt.value" :label="opt.label">{{ opt.label }}</Option>
            </Select>
            <Select v-model="commandFilter" filterable clearable :placeholder="$t('hui-hua-guo-lv-ming-ling')" style="width: 160px">
              <Option v-for="opt in commandOptions" :key="opt.value" :value="opt.value" :label="opt.label">{{ opt.label }}</Option>
            </Select>
            <InputNumber v-model="minTimeFilter" :min="0" :placeholder="$t('hui-hua-zui-xiao-shi-chang')" style="width: 140px" />
          </div>
          <div class="session-toolbar__actions">
            <span class="session-toolbar__auto-refresh">
              <i-switch v-model="autoRefresh" size="small" @on-change="handleAutoRefreshChange" />
              <span class="session-toolbar__auto-refresh-label">{{ $t('hui-hua-zi-dong-shua-xin') }}</span>
            </span>
            <Button type="primary" ghost :disabled="!selectedDsId" :loading="listLoading" @click="loadSessions()">
              <CustomIcon type="icon-v2-Refresh" />
              {{ $t('shua-xin') }}
            </Button>
          </div>
        </div>

        <div v-if="instanceList.length === 0 && !instanceLoading" class="session-empty-hint">
          {{ $t('hui-hua-shi-li-kong-ti-shi') }}
        </div>

        <div v-if="sessions.length > 0" class="session-stats">
          <div class="session-stats__total">{{ $t('hui-hua-zong-lian-jie-shu', [selectedDsName, sessions.length]) }}</div>
          <div class="session-stats__ip-row">
            <span class="session-stats__ip-label">{{ $t('hui-hua-lai-yuan-ip') }}</span>
            <span class="session-stats__ip-list">
              <span v-for="item in visibleIpStats" :key="item.ip" class="session-stats__ip-item">{{ item.ip }} ({{ item.count }})</span>
            </span>
            <Button v-if="hasMoreIpStats" type="text" size="small" @click="ipStatsExpanded = !ipStatsExpanded">
              {{ ipStatsExpanded ? $t('shou-qi-quan-bu') : $t('zhan-kai-quan-bu') }}
            </Button>
          </div>
        </div>

        <!-- Already-translated backend text (PG account lacking pg_monitor); MySQL family is always null. -->
        <Alert v-if="privilegeHint" type="warning" class="session-privilege-hint">{{ privilegeHint }}</Alert>

        <div class="table-container">
          <Table
            size="small"
            border
            stripe
            :loading="listLoading"
            :columns="tableColumns"
            :data="pagedSessions"
            row-key="id"
            @on-sort-change="handleSortChange"
          >
            <template #info="{ row }">
              <span v-if="!row.info">-</span>
              <!-- Per-row Poptip is expensive on large lists: only long SQL gets the hover-fulltext popup. -->
              <Poptip v-else-if="isLongInfo(row.info)" trigger="hover" placement="bottom" transfer :width="500" :word-wrap="true">
                <span class="session-sql-cell">{{ row.info }}</span>
                <template #content>
                  <pre class="session-popover-pre">{{ row.info }}</pre>
                  <Button size="small" type="primary" style="margin-top: 8px" @click="copyText(row.info)">
                    {{ $t('fu-zhi') }}
                  </Button>
                </template>
              </Poptip>
              <span v-else class="session-sql-plain" :title="row.info">{{ row.info }}</span>
            </template>
            <template #action="{ row }">
              <template v-if="canKill">
                <Button v-if="row.killable" type="error" size="small" ghost @click="handleKill(row)">
                  {{ $t('zhong-zhi') }}
                </Button>
                <Poptip v-else trigger="hover" placement="left" transfer :content="protectedText(row.protectedReason)">
                  <span class="session-kill-protected">
                    <Button type="error" size="small" ghost disabled>{{ $t('zhong-zhi') }}</Button>
                  </span>
                </Poptip>
              </template>
              <span v-else>-</span>
            </template>
          </Table>
        </div>

        <div v-if="filteredSessions.length > 0" class="session-footer">
          <Page
            :total="filteredSessions.length"
            :page-size="pageSize"
            :model-value="currentPage"
            show-total
            show-sizer
            show-elevator
            :page-size-opts="[20, 50, 100, 500]"
            @on-change="handlePageChange"
            @on-page-size-change="handlePageSizeChange"
          />
        </div>
      </section>
    </div>
  </div>
</template>

<script>
import { mapState } from 'vuex';
import copyMixin from '@/mixins/copyMixin';

const AUTO_REFRESH_INTERVAL_MS = 10000;
const KILL_CONFIRM_SQL_MAX_LEN = 300;
const KILL_CONFIRM_PRE_STYLE =
  'margin: 8px 0 0; padding: 8px; background: var(--bg-secondary); border-radius: 4px; font-size: 12px; ' +
  'white-space: pre-wrap; word-break: break-all; max-height: 160px; overflow: auto;';
const INFO_POPTIP_MIN_LEN = 80;
const IP_STATS_VISIBLE_LIMIT = 10;
const UNKNOWN_SOURCE_IP = 'unknown';
// processlist user/db/command can be NULL (e.g. unauthenticated or Sleep sessions); group them under one option.
const NULL_FILTER_OPTION = 'NULL';

const PROTECTED_REASON_I18N = {
  PLATFORM_SELF: 'hui-hua-bao-hu-platform-self',
  SYSTEM_USER: 'hui-hua-bao-hu-system-user',
  REPLICATION_THREAD: 'hui-hua-bao-hu-replication-thread'
};

// Kill confirm content is an HTML string rendered by $Modal.confirm — DB-side values must be escaped.
function escapeHtml(value) {
  return String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

// processlist host is "ip:port", "localhost", or bracketed IPv6 "[::1]:port"; bare IPv6 has no port.
function extractSourceIp(host) {
  if (!host) {
    return UNKNOWN_SOURCE_IP;
  }
  const bracketEnd = host.indexOf(']');
  if (host.startsWith('[') && bracketEnd > 0) {
    return host.slice(1, bracketEnd);
  }
  const firstColon = host.indexOf(':');
  if (firstColon > 0 && host.indexOf(':', firstColon + 1) === -1) {
    return host.slice(0, firstColon);
  }
  return host;
}

// Maps a processlist cell to its filter-option value; null/empty falls into the NULL bucket.
function normalizeFilterValue(value) {
  if (value == null || value === '') {
    return NULL_FILTER_OPTION;
  }
  return value;
}

// Distinct values of one processlist column with per-value connection counts, most connected first.
function buildFilterOptions(rows, field) {
  const counts = new Map();
  for (const row of rows) {
    const value = normalizeFilterValue(row[field]);
    counts.set(value, (counts.get(value) || 0) + 1);
  }
  return Array.from(counts, ([value, count]) => ({ value, count, label: `${value} (${count})` })).sort(
    (a, b) => b.count - a.count || a.value.localeCompare(b.value)
  );
}

export default {
  name: 'SessionManage',
  mixins: [copyMixin],
  data() {
    return {
      instanceList: [],
      instanceLoading: false,
      selectedDsId: null,
      sessions: [],
      listLoading: false,
      userFilter: null,
      dbFilter: null,
      commandFilter: null,
      minTimeFilter: null,
      autoRefresh: false,
      autoRefreshTimer: null,
      currentPage: 1,
      pageSize: 50,
      sortKey: '',
      sortOrder: 'normal',
      ipStatsExpanded: false,
      privilegeHint: null
    };
  },
  computed: {
    ...mapState(['myAuth']),
    canKill() {
      return this.myAuth.includes('DM_SESSION_KILL');
    },
    selectedDsName() {
      const inst = this.instanceList.find((i) => i.dsId === this.selectedDsId);
      if (!inst) {
        return '';
      }
      return inst.dsName;
    },
    userOptions() {
      return buildFilterOptions(this.sessions, 'user');
    },
    dbOptions() {
      return buildFilterOptions(this.sessions, 'db');
    },
    commandOptions() {
      return buildFilterOptions(this.sessions, 'command');
    },
    filteredSessions() {
      const userF = this.userFilter;
      const dbF = this.dbFilter;
      const cmdF = this.commandFilter;
      const minTime = this.minTimeFilter;
      return this.sessions.filter((row) => {
        if (userF && normalizeFilterValue(row.user) !== userF) {
          return false;
        }
        if (dbF && normalizeFilterValue(row.db) !== dbF) {
          return false;
        }
        if (cmdF && normalizeFilterValue(row.command) !== cmdF) {
          return false;
        }
        if (minTime != null && (row.time == null || row.time < minTime)) {
          return false;
        }
        return true;
      });
    },
    filterSignature() {
      return [this.userFilter, this.dbFilter, this.commandFilter, this.minTimeFilter].join('|');
    },
    // Stats are instance-level overviews: always computed on the full result set, never filtered/paged.
    ipStats() {
      const counts = new Map();
      for (const row of this.sessions) {
        const ip = extractSourceIp(row.host);
        counts.set(ip, (counts.get(ip) || 0) + 1);
      }
      return Array.from(counts, ([ip, count]) => ({ ip, count })).sort((a, b) => b.count - a.count || a.ip.localeCompare(b.ip));
    },
    visibleIpStats() {
      if (this.ipStatsExpanded) {
        return this.ipStats;
      }
      return this.ipStats.slice(0, IP_STATS_VISIBLE_LIMIT);
    },
    hasMoreIpStats() {
      return this.ipStats.length > IP_STATS_VISIBLE_LIMIT;
    },
    // Sorting happens on the full filtered set; pagination below is a render slice only.
    sortedSessions() {
      if (!this.sortKey || this.sortOrder === 'normal') {
        return this.filteredSessions;
      }
      const factor = this.sortOrder === 'asc' ? 1 : -1;
      return this.filteredSessions.slice().sort((a, b) => factor * ((a[this.sortKey] ?? -1) - (b[this.sortKey] ?? -1)));
    },
    pagedSessions() {
      const start = (this.currentPage - 1) * this.pageSize;
      return this.sortedSessions.slice(start, start + this.pageSize);
    },
    tableColumns() {
      return [
        { title: this.$t('hui-hua-id'), key: 'id', width: 90 },
        { title: this.$t('yong-hu'), key: 'user', width: 120, ellipsis: true, tooltip: true, render: (h, { row }) => this.cellOrDash(row.user) },
        {
          title: this.$t('hui-hua-zhu-ji'),
          key: 'host',
          width: 170,
          ellipsis: true,
          tooltip: true,
          render: (h, { row }) => this.cellOrDash(row.host)
        },
        { title: this.$t('ku-ming'), key: 'db', width: 120, ellipsis: true, tooltip: true, render: (h, { row }) => this.cellOrDash(row.db) },
        {
          title: this.$t('ming-ling'),
          key: 'command',
          width: 110,
          ellipsis: true,
          tooltip: true,
          render: (h, { row }) => this.cellOrDash(row.command)
        },
        {
          title: this.$t('hui-hua-zhi-xing-shi-chang'),
          key: 'time',
          width: 100,
          sortable: 'custom',
          render: (h, { row }) => this.cellOrDash(row.time)
        },
        { title: this.$t('zhuang-tai'), key: 'state', width: 140, ellipsis: true, tooltip: true, render: (h, { row }) => this.cellOrDash(row.state) },
        { title: 'SQL', slot: 'info', minWidth: 240 },
        { title: this.$t('cao-zuo'), slot: 'action', width: 110, align: 'center', fixed: 'right' }
      ];
    }
  },
  watch: {
    filterSignature() {
      this.currentPage = 1;
    },
    filteredSessions(list) {
      // Clamp when the data shrinks (silent auto-refresh / kill reload) without resetting the page.
      const maxPage = Math.max(1, Math.ceil(list.length / this.pageSize));
      if (this.currentPage > maxPage) {
        this.currentPage = maxPage;
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
        const res = await this.$services.dmSessionListInstances();
        this.instanceList = res.data || [];
      } finally {
        this.instanceLoading = false;
      }
    },
    handleDsChange() {
      this.sessions = [];
      this.privilegeHint = null;
      // An in-flight load for the previous datasource is dropped as stale and won't reset
      // listLoading; clear it here so the table/refresh button don't stay in loading state.
      this.listLoading = false;
      this.loadSessions();
    },
    async loadSessions(silent) {
      if (!this.selectedDsId) {
        return;
      }
      const dsId = this.selectedDsId;
      if (!silent) {
        this.listLoading = true;
        this.currentPage = 1;
      }
      try {
        const res = await this.$services.dmSessionList({ data: { dsId } });
        // Drop stale responses when the datasource was switched while in flight.
        if (dsId !== this.selectedDsId) {
          return;
        }
        this.sessions = (res.data && res.data.sessions) || [];
        this.privilegeHint = (res.data && res.data.privilegeHint) || null;
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
        this.loadSessions(true);
      }, AUTO_REFRESH_INTERVAL_MS);
    },
    stopAutoRefresh() {
      if (this.autoRefreshTimer) {
        window.clearInterval(this.autoRefreshTimer);
        this.autoRefreshTimer = null;
      }
    },
    handleSortChange({ key, order }) {
      if (order === 'normal') {
        this.sortKey = '';
        this.sortOrder = 'normal';
      } else {
        this.sortKey = key;
        this.sortOrder = order;
      }
    },
    handlePageChange(page) {
      this.currentPage = page;
    },
    handlePageSizeChange(size) {
      this.pageSize = size;
      this.currentPage = 1;
    },
    isLongInfo(info) {
      return info.length > INFO_POPTIP_MIN_LEN;
    },
    cellOrDash(value) {
      if (value === null || value === undefined || value === '') {
        return '-';
      }
      return String(value);
    },
    protectedText(reason) {
      return this.$t(PROTECTED_REASON_I18N[reason] || 'hui-hua-bao-hu-default');
    },
    handleKill(row) {
      this.$Modal.confirm({
        title: this.$t('hui-hua-zhong-zhi-que-ren'),
        content: this.buildKillConfirmContent(row),
        okText: this.$t('zhong-zhi'),
        loading: true,
        onOk: async () => {
          try {
            const res = await this.$services.dmSessionKill({
              data: { dsId: this.selectedDsId, sessionId: row.id }
            });
            if (res && res.success) {
              this.$Message.success(this.$t('hui-hua-zhong-zhi-cheng-gong'));
              this.loadSessions();
            }
          } finally {
            this.$Modal.remove();
          }
        }
      });
    },
    buildKillConfirmContent(row) {
      const meta = [
        `${this.$t('hui-hua-id')}: ${escapeHtml(row.id)}`,
        `${this.$t('yong-hu')}: ${escapeHtml(row.user || '-')}`,
        `${this.$t('hui-hua-zhu-ji')}: ${escapeHtml(row.host || '-')}`
      ].join(' · ');
      const lines = [`<p>${meta}</p>`];
      if (row.info) {
        let summary = row.info;
        if (summary.length > KILL_CONFIRM_SQL_MAX_LEN) {
          summary = summary.slice(0, KILL_CONFIRM_SQL_MAX_LEN) + '…';
        }
        lines.push(`<pre style="${KILL_CONFIRM_PRE_STYLE}">${escapeHtml(summary)}</pre>`);
      }
      lines.push(`<p style="margin-top: 8px; color: var(--error-color);">${this.$t('hui-hua-zhong-zhi-jing-gao')}</p>`);
      if (row.sharedAccount) {
        lines.push(`<p style="color: var(--warning-color);">${this.$t('hui-hua-gong-yong-zhang-hao-ti-shi')}</p>`);
      }
      return lines.join('');
    }
  }
};
</script>

<style lang="less" scoped>
.session-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  gap: 8px;
  flex-wrap: wrap;
}
.session-toolbar__filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.session-toolbar__actions {
  display: flex;
  align-items: center;
  gap: 16px;
}
.session-toolbar__auto-refresh {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.session-toolbar__auto-refresh-label {
  font-size: 13px;
  color: var(--text-secondary);
  white-space: nowrap;
}
.session-empty-hint {
  padding: 32px;
  text-align: center;
  color: var(--text-secondary);
  font-size: 14px;
}
.session-privilege-hint {
  margin-bottom: 12px;
}
// Flat light-surface strip (no border card, per ui-layout-consistency skill).
.session-stats {
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
.session-stats__total {
  color: var(--text-primary);
}
.session-stats__ip-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.session-stats__ip-label {
  white-space: nowrap;
}
.session-stats__ip-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
}
.session-stats__ip-item {
  white-space: nowrap;
}
.session-sql-cell {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
  color: var(--link-color);
}
.session-sql-plain {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-popover-pre {
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  max-height: 300px;
  overflow: auto;
  margin: 0;
  background: var(--bg-secondary);
  padding: 8px;
  border-radius: 4px;
}
.session-footer {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
// Disabled buttons swallow hover events — let the Poptip wrapper receive them instead.
.session-kill-protected {
  display: inline-block;
  button {
    pointer-events: none;
  }
}
</style>
