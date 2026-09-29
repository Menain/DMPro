<template>
  <div class="ghost-panel">
    <div class="ghost-panel__head">
      <span class="ghost-panel__title">{{ $t('wu-suo-jie-gou-bian-geng') }}</span>
      <span class="ghost-panel__session">{{ $t('wu-suo-hui-hua-bian-hao') }}: {{ group.ghostSessionId }}</span>
    </div>

    <!-- live surface (DM_GHOST_EXEC holders): progress + controls + log tail -->
    <template v-if="canExec">
      <GhostProgressBar v-if="statusDTO" :status="statusDTO" />
      <div v-else class="ghost-panel__static">
        <CCStatus :color="staticColor" :content="staticText" />
      </div>

      <div v-if="oldTableName" class="ghost-panel__old-table">
        <span class="ghost-panel__old-table-name">{{ $t('wu-suo-jiu-biao') }}: {{ oldTableName }}</span>
        <Tag v-if="dropped">{{ $t('wu-suo-yi-shan-chu') }}</Tag>
        <Button v-else-if="isTerminal" type="error" size="small" ghost :loading="actionLoading === 'drop'" @click="handleDropOldTable">
          {{ $t('wu-suo-shan-chu-jiu-biao') }}
        </Button>
      </div>

      <div class="ghost-panel__actions">
        <Button v-if="canPause" size="small" :loading="actionLoading === 'pause'" @click="handlePause">{{ $t('zan-ting') }}</Button>
        <Button v-if="canResume" size="small" :loading="actionLoading === 'resume'" @click="handleResume">{{ $t('hui-fu') }}</Button>
        <Button v-if="canStop" size="small" type="error" ghost :loading="actionLoading === 'stop'" @click="handleStop">
          {{ $t('zhong-zhi') }}
        </Button>
        <Button size="small" type="primary" ghost @click="refreshAll">
          <CustomIcon type="icon-v2-Refresh" />
          {{ $t('shua-xin') }}
        </Button>
      </div>

      <div class="ghost-panel__log">
        <div class="ghost-panel__log-head">
          <span class="ghost-panel__log-title">{{ $t('ri-zhi') }}</span>
          <div class="ghost-panel__log-controls">
            <Button size="small" :disabled="logStartLine === 0" :loading="logLoading" @click="handleLogPage(-25)">
              {{ $t('shang-yi-ye') }}
            </Button>
            <Button size="small" :disabled="!logHasMore" :loading="logLoading" @click="handleLogPage(25)">{{ $t('xia-yi-ye') }}</Button>
            <Button size="small" :disabled="logStartLine === 0" :loading="logLoading" @click="handleLogPage(0)">
              {{ $t('wu-suo-hui-dao-zui-xin') }}
            </Button>
          </div>
        </div>
        <pre v-if="logArr.length > 0" ref="logPre" class="ghost-panel__log-pre">{{ logArr.join('\n') }}</pre>
        <div v-else class="ghost-panel__log-empty">{{ $t('wu-suo-ri-zhi-kong') }}</div>
      </div>
    </template>

    <!-- read-only surface (no DM_GHOST_EXEC): every ghost endpoint is permission-gated, so this
         view renders ONLY the groupList VO projection — no requests are issued at all -->
    <template v-else>
      <div class="ghost-panel__static">
        <CCStatus :color="staticColor" :content="staticText" />
      </div>
      <div v-if="oldTableName" class="ghost-panel__old-table">
        <span class="ghost-panel__old-table-name">{{ $t('wu-suo-jiu-biao') }}: {{ oldTableName }}</span>
        <Tag v-if="dropped">{{ $t('wu-suo-yi-shan-chu') }}</Tag>
      </div>
    </template>
  </div>
</template>

<script>
import GhostProgressBar from '@/components/function/GhostProgressBar.vue';
import CCStatus from '@/components/widgets/CCStatus.vue';
import { GHOST_STATUS_COLOR, GHOST_STATUS_I18N, GHOST_TERMINAL_TOOL_STATUS } from '@/const/ghost';

// Polling follows the ticketDetail 5s auto-refresh precedent (pollInFlight re-entry guard +
// beforeUnmount cleanup); the tailLog page size is fixed at 25 lines per page by the tool-side
// contract (startLine=0 ⇒ latest page).
const POLL_INTERVAL_MS = 5000;
const LOG_PAGE_SIZE = 25;

// Confirm contents are HTML strings rendered by $Modal.confirm — DB-side values must be escaped.
function escapeHtml(value) {
  return String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

/**
 * Ticket-detail ghost group panel (B6 Step 7d / design §6.8 M1): live gh-ost progress, phase and
 * controls embedded in the v2 group row. Data sources: the groupList VO ghost projection (D0
 * additive fields — status/old-table fallbacks) plus the existing /ghost/tailStatus|tailLog
 * endpoints by ghostSessionId (zero new ghost endpoints). Polling runs ONLY for permission
 * holders while the task is non-terminal; the page-level ticket auto-refresh stays untouched.
 */
export default {
  name: 'GhostGroupPanel',
  components: { GhostProgressBar, CCStatus },
  props: {
    group: {
      type: Object,
      required: true
    },
    canExec: {
      type: Boolean,
      default: false
    }
  },
  emits: ['dropped'],
  data() {
    return {
      statusDTO: null,
      dropped: this.group.ghostOldTableDropped === true,
      actionLoading: '',
      logArr: [],
      logStartLine: 0,
      logHasMore: false,
      logLoading: false,
      pollTimer: null,
      pollInFlight: false
    };
  },
  computed: {
    toolStatus() {
      if (this.statusDTO) {
        return this.statusDTO.status || '';
      }
      return this.group.ghostTaskStatus || 'INIT';
    },
    isTerminal() {
      return GHOST_TERMINAL_TOOL_STATUS.includes(this.toolStatus);
    },
    canPause() {
      return this.toolStatus === 'RUNNING';
    },
    canResume() {
      return this.toolStatus === 'PAUSED';
    },
    canStop() {
      return ['INIT', 'RUNNING', 'PAUSED'].includes(this.toolStatus);
    },
    oldTableName() {
      if (this.statusDTO && this.statusDTO.oldTableName) {
        return this.statusDTO.oldTableName;
      }
      return this.group.ghostOldTableName || '';
    },
    staticColor() {
      return GHOST_STATUS_COLOR[this.toolStatus] || '#999999';
    },
    staticText() {
      const key = GHOST_STATUS_I18N[this.toolStatus];
      if (!key) {
        return this.toolStatus || '-';
      }
      return this.$t(key);
    }
  },
  watch: {
    'group.ghostOldTableDropped'(value) {
      this.dropped = value === true;
    },
    // Deviation-1 closure (mirror of the backend terminal-wins rule): the VO terminal status is
    // authoritative — when it arrives while a frozen NON-terminal live snapshot still pins the
    // progress bar (e.g. the session was lost mid-poll and tailStatus now fails silently), drop
    // the stale snapshot so the static terminal display takes over, and make sure the loop is off.
    'group.ghostTaskStatus'(value) {
      if (!GHOST_TERMINAL_TOOL_STATUS.includes(value)) {
        return;
      }
      this.stopPolling();
      if (this.statusDTO && !GHOST_TERMINAL_TOOL_STATUS.includes(this.statusDTO.status || '')) {
        this.statusDTO = null;
      }
    },
    // retryGroup rebuild closes the old session and dispatches a fresh one — reset and re-attach
    'group.ghostSessionId'() {
      this.stopPolling();
      this.statusDTO = null;
      this.logArr = [];
      this.logStartLine = 0;
      this.logHasMore = false;
      if (this.canExec) {
        this.refreshAll();
      }
    }
  },
  mounted() {
    if (this.canExec) {
      this.refreshAll();
    }
  },
  beforeUnmount() {
    this.stopPolling();
  },
  methods: {
    async refreshAll() {
      await this.fetchStatus();
      await this.fetchLog();
      // statusDTO guard: a failed fetch (global error modal owns it) must NOT restart the loop —
      // a manual refresh restarts polling when a fresh status arrives and is still non-terminal
      if (this.statusDTO && !this.isTerminal && !this.pollTimer) {
        this.startPolling();
      }
    },
    async fetchStatus() {
      // modal:false — background probe, not a user action: the ticket form releases the session
      // at every terminal (the sidecar slot contract), so a closed session is the NORMAL state
      // of a finished migration. Failures degrade silently to the groupList VO projection
      // (static terminal status); a modal on every completed-ticket view would be pure noise.
      const res = await this.$services.dmGhostTailStatus({ data: { sessionId: this.group.ghostSessionId }, modal: false });
      if (!res.success) {
        // Stop the loop so a dead session is not re-probed every tick; the VO projection owns
        // the terminal display. A manual refresh retries when the session may be alive again.
        this.stopPolling();
        return;
      }
      this.statusDTO = res.data;
      if (this.isTerminal) {
        this.stopPolling();
      }
    },
    async fetchLog(startLine = this.logStartLine) {
      this.logLoading = true;
      try {
        const res = await this.$services.dmGhostTailLog({ data: { sessionId: this.group.ghostSessionId, startLine }, modal: false });
        if (res.success) {
          const data = res.data || {};
          this.logArr = data.logArr || [];
          this.logHasMore = this.logArr.length >= LOG_PAGE_SIZE;
          this.logStartLine = startLine;
          if (startLine === 0) {
            this.scrollLogToBottom();
          }
        }
      } finally {
        this.logLoading = false;
      }
    },
    scrollLogToBottom() {
      this.$nextTick(() => {
        if (this.$refs.logPre) {
          this.$refs.logPre.scrollTop = this.$refs.logPre.scrollHeight;
        }
      });
    },
    // Paging mirrors the standalone view: startLine=0 is the special "latest tail" page,
    // positive startLines page forward through the file 25 lines at a time.
    handleLogPage(step) {
      let startLine;
      if (step === 0 || this.logStartLine + step < 0) {
        startLine = 0;
      } else {
        startLine = this.logStartLine + step;
      }
      this.fetchLog(startLine);
    },
    startPolling() {
      this.stopPolling();
      this.pollTimer = window.setInterval(() => {
        this.pollTick();
      }, POLL_INTERVAL_MS);
    },
    stopPolling() {
      if (this.pollTimer) {
        window.clearInterval(this.pollTimer);
        this.pollTimer = null;
      }
    },
    async pollTick() {
      if (this.pollInFlight) {
        return;
      }
      this.pollInFlight = true;
      try {
        await this.fetchStatus();
        // Follow mode only: never overwrite a history page the user is reading; after a terminal
        // status one final tail keeps the completed log on screen.
        if (this.logStartLine === 0 || this.isTerminal) {
          await this.fetchLog();
        }
      } finally {
        this.pollInFlight = false;
      }
    },
    async runSessionAction(kind, serviceFn) {
      this.actionLoading = kind;
      try {
        const res = await serviceFn({ data: { sessionId: this.group.ghostSessionId } });
        return !!(res && res.success);
      } finally {
        this.actionLoading = '';
      }
    },
    async handlePause() {
      if (await this.runSessionAction('pause', this.$services.dmGhostPause)) {
        await this.refreshAll();
      }
    },
    async handleResume() {
      if (await this.runSessionAction('resume', this.$services.dmGhostResume)) {
        await this.refreshAll();
      }
    },
    handleStop() {
      this.$Modal.confirm({
        title: this.$t('wu-suo-zhong-zhi-que-ren'),
        content: this.$t('wu-suo-zhong-zhi-jing-gao'),
        className: 'dm-modal-destructive',
        okText: this.$t('zhong-zhi'),
        loading: true,
        onOk: async () => {
          try {
            if (await this.runSessionAction('stop', this.$services.dmGhostStop)) {
              await this.refreshAll();
            }
          } finally {
            this.$Modal.remove();
          }
        }
      });
    },
    handleDropOldTable() {
      this.$Modal.confirm({
        title: this.$t('wu-suo-shan-chu-jiu-biao-que-ren'),
        content: this.$t('wu-suo-shan-chu-jiu-biao-jing-gao', [escapeHtml(this.oldTableName)]),
        className: 'dm-modal-destructive',
        okText: this.$t('wu-suo-shan-chu-jiu-biao'),
        loading: true,
        onOk: async () => {
          try {
            if (await this.runSessionAction('drop', this.$services.dmGhostDropOldTable)) {
              this.dropped = true;
              this.$Message.success(this.$t('wu-suo-jiu-biao-yi-shan-chu'));
              this.$emit('dropped');
            }
          } finally {
            this.$Modal.remove();
          }
        }
      });
    }
  }
};
</script>

<style lang="less" scoped>
.ghost-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  background: var(--bg-secondary, #f8fafc);
  border-radius: 6px;
}
.ghost-panel__head {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}
.ghost-panel__title {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary, #181d26);
}
.ghost-panel__session {
  font-size: 12px;
  color: var(--text-secondary, #707070);
  word-break: break-all;
}
.ghost-panel__static {
  display: flex;
  align-items: center;
}
.ghost-panel__old-table {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
}
.ghost-panel__old-table-name {
  word-break: break-all;
}
.ghost-panel__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.ghost-panel__log-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.ghost-panel__log-title {
  color: var(--text-primary, #181d26);
  font-size: 13px;
  font-weight: 500;
}
.ghost-panel__log-controls {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ghost-panel__log-pre {
  margin: 0;
  padding: 12px;
  background: var(--bg-primary, #ffffff);
  border: 1px solid #eaeaea;
  border-radius: 6px;
  font-family: Menlo, Monaco, 'Courier New', monospace;
  font-size: 12px;
  line-height: 18px;
  max-height: 240px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
.ghost-panel__log-empty {
  padding: 16px;
  text-align: center;
  color: var(--text-secondary, #707070);
  font-size: 13px;
}
</style>
