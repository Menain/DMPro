<template>
  <div class="ghost-tasks">
    <div class="ghost-tasks__toolbar">
      <div class="page-section__title">{{ $t('wu-suo-ren-wu-lie-biao') }}</div>
      <Button type="primary" ghost :loading="taskLoading" @click="loadTasks">
        <CustomIcon type="icon-v2-Refresh" />
        {{ $t('shua-xin') }}
      </Button>
    </div>

    <Table size="small" border stripe :loading="taskLoading" :columns="taskColumns" :data="pagedTasks" :no-data-text="$t('wu-suo-ren-wu-kong')">
      <template #action="{ row }">
        <div class="ghost-tasks__row-actions">
          <Button size="small" type="primary" ghost @click="$emit('view-session', row)">{{ $t('cha-kan') }}</Button>
          <Button v-if="canDropOldTable(row)" size="small" type="error" ghost @click="handleDropOldTable(row)">
            {{ $t('wu-suo-shan-chu-jiu-biao') }}
          </Button>
        </div>
      </template>
    </Table>

    <div v-if="tasks.length > 0" class="ghost-tasks__footer">
      <Page
        :total="tasks.length"
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
  </div>
</template>

<script>
import CCStatus from '@/components/widgets/CCStatus.vue';
import { formatTime } from '@/utils';
import { GHOST_STATUS_COLOR, GHOST_STATUS_I18N, GHOST_TASK_STATUS_TO_TOOL, GHOST_TERMINAL_TASK_STATUS } from '@/const/ghost';

// Confirm content is an HTML string rendered by $Modal.confirm — DB-side values must be escaped.
function escapeHtml(value) {
  return String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

export default {
  name: 'GhostTaskList',
  emits: ['view-session'],
  data() {
    return {
      tasks: [],
      taskLoading: false,
      currentPage: 1,
      pageSize: 50
    };
  },
  computed: {
    pagedTasks() {
      const start = (this.currentPage - 1) * this.pageSize;
      return this.tasks.slice(start, start + this.pageSize);
    },
    taskColumns() {
      return [
        {
          title: this.$t('wu-suo-ren-wu-fa-qi-ren'),
          key: 'initiatorName',
          width: 120,
          ellipsis: true,
          tooltip: true,
          render: (h, { row }) => row.initiatorName || row.initiatorUid || '-'
        },
        {
          title: this.$t('wu-suo-ren-wu-mu-biao'),
          key: 'target',
          minWidth: 200,
          ellipsis: true,
          tooltip: true,
          render: (h, { row }) => this.targetText(row)
        },
        {
          title: this.$t('wu-suo-ren-wu-ddl'),
          key: 'ddlSql',
          minWidth: 220,
          ellipsis: true,
          tooltip: true,
          render: (h, { row }) => row.ddlSql || '-'
        },
        {
          title: this.$t('zhuang-tai'),
          key: 'status',
          width: 120,
          render: (h, { row }) =>
            h('span', { title: row.statusMsg || '' }, [h(CCStatus, { color: this.statusColor(row), content: this.statusText(row) })])
        },
        {
          title: this.$t('jin-du'),
          key: 'progress',
          width: 90,
          render: (h, { row }) => this.progressText(row.progress)
        },
        {
          title: this.$t('wu-suo-jiu-biao'),
          key: 'oldTableName',
          width: 220,
          ellipsis: true,
          tooltip: true,
          render: (h, { row }) => this.oldTableText(row)
        },
        {
          title: this.$t('chuang-jian-shi-jian'),
          key: 'gmtCreate',
          width: 160,
          render: (h, { row }) => this.dateText(row.gmtCreate)
        },
        {
          title: this.$t('jie-shu-shi-jian'),
          key: 'timeOfFinish',
          width: 160,
          render: (h, { row }) => this.dateText(row.timeOfFinish)
        },
        { title: this.$t('cao-zuo'), slot: 'action', width: 200, align: 'center', fixed: 'right' }
      ];
    }
  },
  watch: {
    tasks(list) {
      // Clamp when the data shrinks on refresh without resetting the page.
      const maxPage = Math.max(1, Math.ceil(list.length / this.pageSize));
      if (this.currentPage > maxPage) {
        this.currentPage = maxPage;
      }
    }
  },
  mounted() {
    this.loadTasks();
  },
  methods: {
    async loadTasks() {
      this.taskLoading = true;
      try {
        const res = await this.$services.dmGhostTasks();
        if (res.success) {
          this.tasks = res.data || [];
        }
      } finally {
        this.taskLoading = false;
      }
    },
    handlePageChange(page) {
      this.currentPage = page;
    },
    handlePageSizeChange(size) {
      this.pageSize = size;
      this.currentPage = 1;
    },
    // The tool-domain status wins when captured; young tasks fall back to the AsyncTask status
    // mapped into the same domain (mirrors the backend's mapTaskStatusToTool).
    displayStatus(row) {
      if (row.toolStatus) {
        return row.toolStatus;
      }
      return GHOST_TASK_STATUS_TO_TOOL[row.status] || row.status || '';
    },
    statusColor(row) {
      return GHOST_STATUS_COLOR[this.displayStatus(row)] || '#999999';
    },
    statusText(row) {
      const status = this.displayStatus(row);
      const key = GHOST_STATUS_I18N[status];
      if (!key) {
        return status || '-';
      }
      return this.$t(key);
    },
    targetText(row) {
      const parts = [row.dsName, row.dbName, row.tableName].filter((part) => !!part);
      if (parts.length === 0) {
        return '-';
      }
      return parts.join(' · ');
    },
    progressText(progress) {
      if (progress === null || progress === undefined) {
        return '-';
      }
      return `${progress}%`;
    },
    oldTableText(row) {
      if (!row.oldTableName) {
        return '-';
      }
      if (row.oldTableDropped) {
        return `${row.oldTableName} (${this.$t('wu-suo-yi-shan-chu')})`;
      }
      return row.oldTableName;
    },
    dateText(value) {
      if (!value) {
        return '-';
      }
      return formatTime(value, 'YYYY-MM-DD HH:mm:ss');
    },
    canDropOldTable(row) {
      return !!row.oldTableName && !row.oldTableDropped && GHOST_TERMINAL_TASK_STATUS.includes(row.status);
    },
    handleDropOldTable(row) {
      this.$Modal.confirm({
        title: this.$t('wu-suo-shan-chu-jiu-biao-que-ren'),
        content: this.$t('wu-suo-shan-chu-jiu-biao-jing-gao', [escapeHtml(row.oldTableName)]),
        className: 'dm-modal-destructive',
        okText: this.$t('wu-suo-shan-chu-jiu-biao'),
        loading: true,
        onOk: async () => {
          try {
            const res = await this.$services.dmGhostDropOldTable({ data: { sessionId: row.sessionId } });
            if (res && res.success) {
              this.$Message.success(this.$t('wu-suo-jiu-biao-yi-shan-chu'));
              this.loadTasks();
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
.ghost-tasks__toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  gap: 8px;
  flex-wrap: wrap;
}
// Standard block title (space/lockAnalysis local pattern); the toolbar owns the bottom spacing.
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
.ghost-tasks__row-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}
.ghost-tasks__footer {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
