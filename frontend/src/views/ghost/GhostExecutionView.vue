<template>
  <div class="ghost-exec">
    <div class="page-section__title">{{ $t('wu-suo-zhi-xing-shi-tu') }}</div>

    <div class="ghost-exec__meta">
      <span class="ghost-exec__meta-item">{{ $t('wu-suo-hui-hua-bian-hao') }}: {{ sessionId }}</span>
      <span v-if="logFile" class="ghost-exec__meta-item">{{ $t('wu-suo-ri-zhi-wen-jian') }}: {{ logLocationText }}</span>
    </div>

    <GhostProgressBar :status="statusDTO" />

    <div v-if="oldTableName" class="ghost-exec__old-table">
      <span class="ghost-exec__old-table-name">{{ $t('wu-suo-jiu-biao') }}: {{ oldTableName }}</span>
      <Tag v-if="dropped">{{ $t('wu-suo-yi-shan-chu') }}</Tag>
      <Button v-else-if="isTerminal" type="error" size="small" ghost :loading="actionLoading === 'drop'" @click="handleDropOldTable">
        {{ $t('wu-suo-shan-chu-jiu-biao') }}
      </Button>
    </div>

    <div class="ghost-exec__actions">
      <Button v-if="canPause" :loading="actionLoading === 'pause'" @click="handlePause">{{ $t('zan-ting') }}</Button>
      <Button v-if="canResume" :loading="actionLoading === 'resume'" @click="handleResume">{{ $t('hui-fu') }}</Button>
      <Button v-if="canStop" type="error" ghost :loading="actionLoading === 'stop'" @click="handleStop">{{ $t('zhong-zhi') }}</Button>
      <Button v-if="isTerminal" :loading="actionLoading === 'close'" @click="handleClose">{{ $t('wu-suo-guan-bi-hui-hua') }}</Button>
      <Button type="primary" ghost @click="refreshAll">
        <CustomIcon type="icon-v2-Refresh" />
        {{ $t('shua-xin') }}
      </Button>
    </div>

    <div class="ghost-exec__log">
      <div class="ghost-exec__log-head">
        <span class="panel-subheading">{{ $t('ri-zhi') }}</span>
        <div class="ghost-exec__log-controls">
          <Button size="small" :disabled="logStartLine === 0" :loading="logLoading" @click="handleLogPage(-25)">{{ $t('shang-yi-ye') }}</Button>
          <Button size="small" :disabled="!logHasMore" :loading="logLoading" @click="handleLogPage(25)">{{ $t('xia-yi-ye') }}</Button>
          <Button size="small" :disabled="logStartLine === 0" :loading="logLoading" @click="handleLogPage(0)">
            {{ $t('wu-suo-hui-dao-zui-xin') }}
          </Button>
        </div>
      </div>
      <pre v-if="logArr.length > 0" ref="logPre" class="ghost-exec__log-pre">{{ logArr.join('\n') }}</pre>
      <div v-else class="ghost-exec__log-empty">{{ $t('wu-suo-ri-zhi-kong') }}</div>
    </div>
  </div>
</template>

<script>
import GhostProgressBar from '@/components/function/GhostProgressBar.vue';
import { GHOST_TERMINAL_TOOL_STATUS } from '@/const/ghost';

// Active-migration polling follows the ticketDetail auto-refresh precedent (5s); the tailLog
// page size is fixed at 25 lines per page by the tool-side contract (startLine=0 ⇒ latest page).
const POLL_INTERVAL_MS = 5000;
const LOG_PAGE_SIZE = 25;

// Confirm contents are HTML strings rendered by $Modal.confirm — DB-side values must be escaped.
function escapeHtml(value) {
  return String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

export default {
  name: 'GhostExecutionView',
  components: { GhostProgressBar },
  props: {
    sessionId: {
      type: String,
      required: true
    },
    oldTableDropped: {
      type: Boolean,
      default: false
    }
  },
  emits: ['closed', 'dropped'],
  data() {
    return {
      statusDTO: null,
      dropped: this.oldTableDropped,
      actionLoading: '',
      logArr: [],
      logStartLine: 0,
      logHasMore: false,
      logLoading: false,
      logFile: '',
      logHost: '',
      pollTimer: null,
      pollInFlight: false
    };
  },
  computed: {
    toolStatus() {
      if (!this.statusDTO) {
        return '';
      }
      return this.statusDTO.status || '';
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
      if (!this.statusDTO) {
        return '';
      }
      return this.statusDTO.oldTableName || '';
    },
    logLocationText() {
      if (!this.logHost) {
        return this.logFile;
      }
      return `${this.logFile} · ${this.logHost}`;
    }
  },
  watch: {
    oldTableDropped(value) {
      this.dropped = value;
    }
  },
  mounted() {
    this.refreshAll();
  },
  beforeUnmount() {
    this.stopPolling();
  },
  methods: {
    async refreshAll() {
      await this.fetchStatus();
      await this.fetchLog();
      if (this.statusDTO && !this.isTerminal && !this.pollTimer) {
        this.startPolling();
      }
    },
    async fetchStatus() {
      const res = await this.$services.dmGhostTailStatus({ data: { sessionId: this.sessionId } });
      if (!res.success) {
        // The global error modal owns the failure; stop the loop so it is not re-triggered
        // every tick. A manual refresh restarts polling when the status is still non-terminal.
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
        const res = await this.$services.dmGhostTailLog({ data: { sessionId: this.sessionId, startLine } });
        if (res.success) {
          const data = res.data || {};
          this.logArr = data.logArr || [];
          this.logHasMore = this.logArr.length >= LOG_PAGE_SIZE;
          this.logFile = data.logFile || '';
          this.logHost = data.logHost || '';
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
    // Paging mirrors the faker log-modal formula: startLine=0 is the special "latest tail" page,
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
        const res = await serviceFn({ data: { sessionId: this.sessionId } });
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
    handleClose() {
      this.$Modal.confirm({
        title: this.$t('wu-suo-guan-bi-que-ren'),
        content: this.$t('wu-suo-guan-bi-ti-shi'),
        loading: true,
        onOk: async () => {
          try {
            if (await this.runSessionAction('close', this.$services.dmGhostClose)) {
              this.$emit('closed');
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
.ghost-exec {
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
.ghost-exec__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 24px;
  font-size: 13px;
  color: var(--text-secondary);
}
.ghost-exec__meta-item {
  word-break: break-all;
}
.ghost-exec__old-table {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  background: var(--bg-secondary);
  border-radius: 6px;
  font-size: 13px;
}
.ghost-exec__old-table-name {
  word-break: break-all;
}
.ghost-exec__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.ghost-exec__log-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.ghost-exec__log-head .panel-subheading {
  margin-bottom: 0;
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 500;
}
.ghost-exec__log-controls {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ghost-exec__log-pre {
  margin: 0;
  padding: 12px;
  background: var(--bg-secondary);
  border-radius: 6px;
  font-family: Menlo, Monaco, 'Courier New', monospace;
  font-size: 12px;
  line-height: 18px;
  max-height: 420px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
.ghost-exec__log-empty {
  padding: 24px;
  text-align: center;
  color: var(--text-secondary);
  font-size: 13px;
}
</style>
