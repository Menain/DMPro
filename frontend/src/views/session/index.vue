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
            <Input v-model="userFilter" :placeholder="$t('hui-hua-guo-lv-yong-hu')" style="width: 140px" clearable />
            <Input v-model="dbFilter" :placeholder="$t('hui-hua-guo-lv-ku-ming')" style="width: 140px" clearable />
            <Input v-model="commandFilter" :placeholder="$t('hui-hua-guo-lv-ming-ling')" style="width: 140px" clearable />
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

        <div class="table-container">
          <Table size="small" border stripe :loading="listLoading" :columns="tableColumns" :data="filteredSessions" row-key="id">
            <template #info="{ row }">
              <Poptip v-if="row.info" trigger="hover" placement="bottom" transfer :width="500" :word-wrap="true">
                <span class="session-sql-cell">{{ row.info }}</span>
                <template #content>
                  <pre class="session-popover-pre">{{ row.info }}</pre>
                  <Button size="small" type="primary" style="margin-top: 8px" @click="copyText(row.info)">
                    {{ $t('fu-zhi') }}
                  </Button>
                </template>
              </Poptip>
              <span v-else>-</span>
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

const PROTECTED_REASON_I18N = {
  PLATFORM_SELF: 'hui-hua-bao-hu-platform-self',
  SYSTEM_USER: 'hui-hua-bao-hu-system-user',
  REPLICATION_THREAD: 'hui-hua-bao-hu-replication-thread'
};

// Kill confirm content is an HTML string rendered by $Modal.confirm — DB-side values must be escaped.
function escapeHtml(value) {
  return String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
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
      userFilter: '',
      dbFilter: '',
      commandFilter: '',
      minTimeFilter: null,
      autoRefresh: false,
      autoRefreshTimer: null
    };
  },
  computed: {
    ...mapState(['myAuth']),
    canKill() {
      return this.myAuth.includes('DM_SESSION_KILL');
    },
    filteredSessions() {
      const userF = this.userFilter.trim().toLowerCase();
      const dbF = this.dbFilter.trim().toLowerCase();
      const cmdF = this.commandFilter.trim().toLowerCase();
      const minTime = this.minTimeFilter;
      return this.sessions.filter((row) => {
        if (userF && !(row.user || '').toLowerCase().includes(userF)) {
          return false;
        }
        if (dbF && !(row.db || '').toLowerCase().includes(dbF)) {
          return false;
        }
        if (cmdF && !(row.command || '').toLowerCase().includes(cmdF)) {
          return false;
        }
        if (minTime != null && (row.time == null || row.time < minTime)) {
          return false;
        }
        return true;
      });
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
          sorter: (a, b) => (a.time ?? -1) - (b.time ?? -1),
          render: (h, { row }) => this.cellOrDash(row.time)
        },
        { title: this.$t('zhuang-tai'), key: 'state', width: 140, ellipsis: true, tooltip: true, render: (h, { row }) => this.cellOrDash(row.state) },
        { title: 'SQL', slot: 'info', minWidth: 240 },
        { title: this.$t('cao-zuo'), slot: 'action', width: 110, align: 'center', fixed: 'right' }
      ];
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
      }
      try {
        const res = await this.$services.dmSessionList({ data: { dsId } });
        // Drop stale responses when the datasource was switched while in flight.
        if (dsId !== this.selectedDsId) {
          return;
        }
        this.sessions = (res.data && res.data.sessions) || [];
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
.session-sql-cell {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
  color: var(--link-color);
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
// Disabled buttons swallow hover events — let the Poptip wrapper receive them instead.
.session-kill-protected {
  display: inline-block;
  button {
    pointer-events: none;
  }
}
</style>
