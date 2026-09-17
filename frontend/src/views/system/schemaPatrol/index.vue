<template>
  <div class="schema-patrol-page">
    <div class="table-list-layout">
      <div class="table-list">
        <div class="content">
          <div class="option border-radius-card">
            <div class="left">
              <Button :loading="runLoading" type="primary" @click="handleRunNow">
                {{ $t('xun-jian-qi-dong') }}
              </Button>
            </div>
            <div class="right">
              <Button :loading="loading" @click="loadRuns(true)">
                {{ $t('cha-xun') }}
              </Button>
            </div>
          </div>

          <div class="table-container">
            <Table :columns="runColumns" :data="runs" size="small" :loading="loading" border stripe>
              <template #triggerType="{ row }">
                <Tag :color="row.triggerType === 'AUTO' ? 'blue' : 'green'" size="small">
                  {{ row.triggerType === 'AUTO' ? $t('xun-jian-chu-fa-auto') : $t('xun-jian-chu-fa-manual') }}
                </Tag>
              </template>
              <template #status="{ row }">
                <Tag :color="statusColor(row.status)" size="small">
                  {{ statusLabel(row.status) }}
                </Tag>
              </template>
              <template #pairs="{ row }">{{ row.pairsDone }} / {{ row.pairsTotal }}</template>
              <template #action="{ row }">
                <Button type="primary" size="small" ghost @click="handleViewDetail(row)">
                  {{ $t('xun-jian-xiang-qing') }}
                </Button>
                <Button
                  v-if="row.status === 'RUNNING'"
                  type="error"
                  size="small"
                  ghost
                  :loading="stopLoadingId === row.id"
                  class="stop-btn"
                  @click="handleStop(row)"
                >
                  {{ $t('xun-jian-ting-zhi') }}
                </Button>
              </template>
            </Table>
            <div v-if="!runs.length && !loading" class="empty-tip">
              {{ $t('xun-jian-kong-lie-biao') }}
            </div>
            <div v-if="total > pageSize" class="page-bar">
              <Page :total="total" :page-size="pageSize" :current="currentPage" @on-change="handlePageChange" size="small" />
            </div>
          </div>
        </div>
      </div>
    </div>

    <Modal v-model="detailVisible" :title="$t('xun-jian-xiang-qing')" :width="1100" footer-hide>
      <div v-if="detailRun" class="detail-header">
        <span>{{ $t('xun-jian-shi-jian') }}: {{ formatTime(detailRun.gmtCreate) }}</span>
        <span>{{ $t('xun-jian-chu-fa') }}: {{ detailRun.triggerType === 'AUTO' ? $t('xun-jian-chu-fa-auto') : $t('xun-jian-chu-fa-manual') }}</span>
        <span>{{ $t('xun-jian-zhuang-tai') }}: {{ statusLabel(detailRun.status) }}</span>
        <span>{{ $t('xun-jian-wei-jie-shi') }}: {{ detailRun.unexplainedCount }}</span>
        <Button size="small" class="export-btn" :disabled="!detailItems.length" @click="handleExportDetail">
          {{ $t('xun-jian-dao-chu') }}
        </Button>
      </div>
      <Table v-if="detailItems.length" :columns="itemColumns" :data="detailItems" size="small" border stripe :max-height="480">
        <template #diffClass="{ row }">
          <Tooltip :content="diffTip(row.diffClass)" max-width="320" transfer>
            <Tag :color="diffColor(row.diffClass)" size="small">
              {{ diffLabel(row.diffClass) }}
            </Tag>
          </Tooltip>
        </template>
        <template #explainClass="{ row }">
          <Tooltip :content="explainTip(row.explainClass)" max-width="320" transfer>
            <Tag :color="explainColor(row.explainClass)" size="small">
              {{ explainLabel(row.explainClass) }}
            </Tag>
          </Tooltip>
        </template>
        <template #detailJson="{ row }">
          <pre v-if="row.detailJson" class="detail-json">{{ formatJson(row.detailJson) }}</pre>
        </template>
      </Table>
      <div v-else class="empty-tip">
        {{ $t('xun-jian-kong-lie-biao') }}
      </div>
    </Modal>
  </div>
</template>

<script>
export default {
  name: 'Management_SchemaPatrol',
  data() {
    return {
      runs: [],
      loading: false,
      runLoading: false,
      stopLoadingId: null,
      currentPage: 1,
      pageSize: 20,
      total: 0,
      detailVisible: false,
      detailRun: null,
      detailItems: []
    };
  },
  computed: {
    runColumns() {
      return [
        {
          title: this.$t('xun-jian-shi-jian'),
          key: 'gmtCreate',
          minWidth: 160,
          render: (h, params) => h('span', this.formatTime(params.row.gmtCreate))
        },
        { title: this.$t('xun-jian-chu-fa'), slot: 'triggerType', width: 100 },
        { title: this.$t('xun-jian-zhuang-tai'), slot: 'status', width: 100 },
        { title: this.$t('xun-jian-dui-shu'), slot: 'pairs', width: 100 },
        { title: this.$t('xun-jian-wei-jie-shi'), key: 'unexplainedCount', width: 100 },
        { title: this.$t('xun-jian-xiang-qing'), slot: 'action', width: 170 }
      ];
    },
    itemColumns() {
      return [
        { title: this.$t('xun-jian-biao-ming'), key: 'tableName', minWidth: 160 },
        { title: this.$t('xun-jian-diff-lei-xing'), slot: 'diffClass', width: 150 },
        { title: this.$t('xun-jian-jie-shi-lei-xing'), slot: 'explainClass', width: 150 },
        { title: this.$t('xun-jian-jie-shi-yin-yong'), key: 'explainRef', width: 140 },
        { title: this.$t('xun-jian-ming-xi'), slot: 'detailJson', minWidth: 320 }
      ];
    }
  },
  mounted() {
    this.loadRuns();
  },
  methods: {
    async loadRuns(notify) {
      this.loading = true;
      try {
        const res = await this.$services.schemaPatrolRuns({ data: { page: this.currentPage, size: this.pageSize } });
        this.runs = res.data || [];
        this.total = res.total || this.runs.length;
        if (notify === true) {
          this.$Message.success(this.$t('xun-jian-shua-xin-cheng-gong'));
        }
      } catch (e) {
        this.$Message.error(e.message || 'Failed to load runs');
      } finally {
        this.loading = false;
      }
    },
    async handleStop(row) {
      this.stopLoadingId = row.id;
      try {
        const res = await this.$services.schemaPatrolStop({ data: { runId: row.id } });
        const result = res.data && res.data.result;
        if (result === 'STOPPING') {
          this.$Message.info(this.$t('xun-jian-ting-zhi-zhi-ling'));
        } else if (result === 'STOPPED') {
          this.$Message.success(this.$t('xun-jian-ting-zhi-cheng-gong'));
        } else {
          this.$Message.warning(this.$t('xun-jian-ren-wu-wei-yun-xing'));
        }
        this.loadRuns();
      } catch (e) {
        this.$Message.error(e.message || 'Failed to stop patrol');
      } finally {
        this.stopLoadingId = null;
      }
    },
    handlePageChange(page) {
      this.currentPage = page;
      this.loadRuns();
    },
    async handleRunNow() {
      this.runLoading = true;
      try {
        const res = await this.$services.schemaPatrolRunNow({ data: {} });
        if (res.data && res.data.runId < 0) {
          this.$Message.warning(this.$t('xun-jian-zheng-zai-yun-xing'));
        } else if (res.data && res.data.runId > 0) {
          this.$Message.success(this.$t('xun-jian-qing-qiu-cheng-gong', [res.data.runId]));
          setTimeout(() => this.loadRuns(), 2000);
        }
      } catch (e) {
        this.$Message.error(e.message || 'Failed to start patrol');
      } finally {
        this.runLoading = false;
      }
    },
    async handleViewDetail(row) {
      this.detailVisible = true;
      this.detailRun = row;
      this.detailItems = [];
      try {
        const res = await this.$services.schemaPatrolRunDetail({ data: { runId: row.id } });
        if (res.data) {
          this.detailRun = res.data.run;
          this.detailItems = res.data.items || [];
        }
      } catch (e) {
        this.$Message.error(e.message || 'Failed to load detail');
      }
    },
    formatTime(t) {
      if (!t) return '';
      return new Date(t).toLocaleString();
    },
    formatJson(json) {
      try {
        return JSON.stringify(JSON.parse(json), null, 2);
      } catch (e) {
        return json;
      }
    },
    statusColor(status) {
      const map = { RUNNING: 'processing', DONE: 'success', PARTIAL: 'warning', FAILED: 'error', CANCELLED: 'default' };
      return map[status] || 'default';
    },
    statusLabel(status) {
      const map = {
        RUNNING: 'xun-jian-zhuang-tai-running',
        DONE: 'xun-jian-zhuang-tai-done',
        PARTIAL: 'xun-jian-zhuang-tai-partial',
        FAILED: 'xun-jian-zhuang-tai-failed',
        CANCELLED: 'xun-jian-zhuang-tai-cancelled'
      };
      return this.$t(map[status] || 'xun-jian-zhuang-tai-running');
    },
    diffColor(diffClass) {
      const map = { ONLY_PRE: 'blue', ONLY_PROD: 'red', MISMATCH: 'orange' };
      return map[diffClass] || 'default';
    },
    diffTip(diffClass) {
      const map = {
        ONLY_PRE: 'xun-jian-diff-only-pre-tip',
        ONLY_PROD: 'xun-jian-diff-only-prod-tip',
        MISMATCH: 'xun-jian-diff-mismatch-tip'
      };
      return map[diffClass] ? this.$t(map[diffClass]) : '';
    },
    explainTip(explainClass) {
      const map = {
        EXEMPT_ACTIVE: 'xun-jian-jie-shi-exempt-tip',
        PENDING_RELEASE: 'xun-jian-jie-shi-pending-tip',
        UNEXPLAINED: 'xun-jian-jie-shi-unexplained-tip'
      };
      return map[explainClass] ? this.$t(map[explainClass]) : '';
    },
    handleExportDetail() {
      const header = [
        this.$t('xun-jian-biao-ming'),
        this.$t('xun-jian-diff-lei-xing'),
        this.$t('xun-jian-jie-shi-lei-xing'),
        this.$t('xun-jian-jie-shi-yin-yong'),
        this.$t('xun-jian-ming-xi')
      ];
      const rows = this.detailItems.map((it) => [
        it.tableName || '',
        this.diffLabel(it.diffClass),
        this.explainLabel(it.explainClass),
        it.explainRef || '',
        it.detailJson || ''
      ]);
      const escapeCell = (v) => '"' + String(v).replace(/"/g, '""') + '"';
      const csv = [header, ...rows].map((r) => r.map(escapeCell).join(',')).join('\r\n');
      // BOM so Excel detects UTF-8 (Chinese labels)
      const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8' });
      const runId = this.detailRun ? this.detailRun.id : 'unknown';
      const ts = new Date().toISOString().slice(0, 19).replace(/[-:T]/g, '');
      const link = document.createElement('a');
      link.href = window.URL.createObjectURL(blob);
      link.download = `schema-patrol-run-${runId}-${ts}.csv`;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      window.URL.revokeObjectURL(link.href);
    },
    diffLabel(diffClass) {
      const map = { ONLY_PRE: 'xun-jian-diff-only-pre', ONLY_PROD: 'xun-jian-diff-only-prod', MISMATCH: 'xun-jian-diff-mismatch' };
      return this.$t(map[diffClass] || diffClass);
    },
    explainColor(explainClass) {
      const map = { EXEMPT_ACTIVE: 'success', PENDING_RELEASE: 'blue', UNEXPLAINED: 'error' };
      return map[explainClass] || 'default';
    },
    explainLabel(explainClass) {
      const map = {
        EXEMPT_ACTIVE: 'xun-jian-jie-shi-exempt',
        PENDING_RELEASE: 'xun-jian-jie-shi-pending',
        UNEXPLAINED: 'xun-jian-jie-shi-unexplained'
      };
      return this.$t(map[explainClass] || explainClass);
    }
  }
};
</script>

<style lang="less" scoped>
.schema-patrol-page {
  .table-list-layout {
    .table-list {
      .content {
        .option {
          display: flex;
          justify-content: space-between;
          align-items: center;
          padding: 12px 16px;
          margin-bottom: 8px;
          background: #fff;
          border: 1px solid #eee;
          border-radius: 8px;
        }
        .table-container {
          background: #fff;
          border: 1px solid #eee;
          border-radius: 8px;
          padding: 8px;
        }
      }
    }
  }
  .page-bar {
    text-align: right;
    padding: 8px 0;
  }
  .stop-btn {
    margin-left: 8px;
  }
}

// Modal content is teleported to <body> — these rules must stay TOP-LEVEL
// (not nested under .schema-patrol-page) so the scoped attribute still matches.
.empty-tip {
  text-align: center;
  padding: 24px;
  color: #999;
}
.detail-header {
  display: flex;
  align-items: center;
  gap: 24px;
  margin-bottom: 12px;
  padding: 8px 0;
  border-bottom: 1px solid #eee;
  span {
    font-size: 13px;
    color: #555;
  }
  .export-btn {
    margin-left: auto;
  }
}
.detail-json {
  background: #f5f5f5;
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 12px;
  max-height: 200px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}
// Classic always-visible scrollbars in the detail table's scroll region —
// overlay scrollbars auto-hide and are hard to grab with the mouse.
:deep(.ivu-table-overflowY) {
  overflow-y: scroll !important;
  &::-webkit-scrollbar {
    width: 12px;
  }
  &::-webkit-scrollbar-track {
    background: #f3f5f7;
    border-radius: 6px;
  }
  &::-webkit-scrollbar-thumb {
    background: #c2c7d0;
    border-radius: 6px;
    border: 2px solid #f3f5f7;
    &:hover {
      background: #a8aeb8;
    }
  }
}
</style>
