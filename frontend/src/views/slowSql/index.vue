<template>
  <div class="page-shell slow-sql">
    <div class="page-shell__body">
      <section class="page-section">
        <div class="slow-sql-toolbar">
          <div class="slow-sql-toolbar__filters">
            <Select
              v-model="selectedDsId"
              filterable
              clearable
              :placeholder="$t('man-sql-xuan-shi-shi-li')"
              style="width: 240px"
              @on-change="handleDsChange"
            >
              <Option v-for="inst in instanceList" :key="inst.dsId" :value="inst.dsId" :label="inst.dsName">
                {{ inst.dsName }}
              </Option>
            </Select>
            <DatePicker
              v-model="dateRange"
              type="datetimerange"
              format="yyyy-MM-dd HH:mm:ss"
              :placeholder="$t('qing-xuan-ze-shi-jian')"
              style="width: 340px"
              transfer
              @on-change="handleDateRangeChange"
            />
            <Select v-model="quickTime" :placeholder="$t('man-sql-kuai-jie-shi-jian')" style="width: 120px" @on-change="handleQuickTime">
              <Option :value="1">{{ $t('yi-tian') }}</Option>
              <Option :value="3">{{ $t('man-sql-san-tian') }}</Option>
              <Option :value="7">{{ $t('yi-zhou') }}</Option>
            </Select>
            <Input v-model="schemaFilter" :placeholder="$t('man-sql-ku-ming-guo-lv')" style="width: 160px" clearable @on-enter="handleQuery" />
            <Button type="primary" :loading="listLoading" :disabled="!canQuery" @click="handleQuery">
              {{ $t('cha-xun') }}
            </Button>
          </div>
          <div class="slow-sql-toolbar__actions">
            <Button type="primary" ghost :loading="instanceLoading" @click="loadInstances">
              <CustomIcon type="icon-v2-Refresh" />
            </Button>
          </div>
        </div>

        <div v-if="instanceList.length === 0 && !instanceLoading" class="slow-sql-empty-hint">
          {{ $t('man-sql-shi-li-kong-ti-shi') }}
        </div>

        <div class="table-container">
          <Table
            size="small"
            border
            stripe
            :loading="listLoading"
            :columns="tableColumns"
            :data="topSqlRows"
            row-key="md5"
            @change="handleTableChange"
          >
            <template #sqlTemplate="{ row }">
              <Poptip trigger="hover" placement="bottom" transfer :width="500" :word-wrap="true">
                <span class="slow-sql-sql-cell">{{ row.sqlTemplate }}</span>
                <template #content>
                  <pre class="slow-sql-popover-pre">{{ row.sqlText || row.sqlTemplate }}</pre>
                  <Button size="small" type="primary" style="margin-top: 8px" @click="copyText(row.sqlText || row.sqlTemplate)">
                    {{ $t('fu-zhi') }}
                  </Button>
                </template>
              </Poptip>
            </template>
            <template #action="{ row }">
              <Button type="text" size="small" :loading="adviceLoading" @click="handleAdvice(row)">
                {{ $t('man-sql-you-hua-jian-yi') }}
              </Button>
            </template>
          </Table>
        </div>

        <div class="slow-sql-footer">
          <Page
            :total="totalCount"
            :page-size="pageSize"
            :model-value="currentPage"
            show-total
            show-sizer
            show-elevator
            :page-size-opts="[20, 50, 100]"
            @on-change="handlePageChange"
            @on-page-size-change="handlePageSizeChange"
          />
        </div>
      </section>
    </div>

    <CCModal v-model="adviceModalVisible" :title="$t('man-sql-you-hua-jian-yi')" :width="720" footer-hide>
      <Spin v-if="adviceLoading" fix />
      <div v-if="adviceData">
        <div v-if="adviceData.advices.length === 0" class="slow-sql-advice-empty">
          {{ $t('man-sql-wu-xu-you-hua') }}
        </div>
        <template v-else>
          <div v-for="(advice, index) in adviceData.advices" :key="index" class="slow-sql-advice-item">
            <div class="panel-subheading">{{ advice.tableSchema }}.{{ advice.tableName }}</div>
            <div v-for="(sql, sIndex) in advice.indexSqls" :key="sIndex" class="slow-sql-advice-ddl">
              <pre class="slow-sql-code-block">{{ sql }}</pre>
              <Button size="small" type="text" @click="copyText(sql)">{{ $t('fu-zhi') }}</Button>
            </div>
          </div>
        </template>

        <div v-if="adviceData.cost" class="slow-sql-cost-section">
          <div class="panel-subheading">{{ $t('man-sql-cheng-ben-dui-bi') }}</div>
          <div class="slow-sql-cost-grid">
            <div class="slow-sql-cost-cell">
              <span class="slow-sql-cost-label">{{ $t('man-sql-you-hua-qian') }}</span>
              <span class="slow-sql-cost-value">{{ adviceData.cost.before }}</span>
            </div>
            <div class="slow-sql-cost-cell">
              <span class="slow-sql-cost-label">{{ $t('man-sql-you-hua-hou') }}</span>
              <span class="slow-sql-cost-value">{{ adviceData.cost.after }}</span>
            </div>
            <div class="slow-sql-cost-cell">
              <span class="slow-sql-cost-label">{{ $t('man-sql-jie-yue-bi-li') }}</span>
              <span class="slow-sql-cost-value">{{ adviceData.cost.ratio }}%</span>
            </div>
          </div>
        </div>

        <div v-if="adviceData.comments && adviceData.comments.length > 0" class="slow-sql-comments-section">
          <div class="panel-subheading">{{ $t('man-sql-bei-zhu') }}</div>
          <ul class="slow-sql-comments-list">
            <li v-for="(comment, index) in adviceData.comments" :key="index">{{ comment }}</li>
          </ul>
        </div>
      </div>
    </CCModal>
  </div>
</template>

<script>
import copyMixin from '@/mixins/copyMixin';

const MAX_SPAN_DAYS = 7;

function formatTime(date) {
  const pad = (n) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

export default {
  name: 'SlowSql',
  mixins: [copyMixin],
  data() {
    return {
      instanceList: [],
      instanceLoading: false,
      selectedDsId: null,
      dateRange: [],
      quickTime: null,
      schemaFilter: '',
      topSqlRows: [],
      totalCount: 0,
      currentPage: 1,
      pageSize: 20,
      listLoading: false,
      sortBy: 'QueryTime',
      orderBy: 'DESC',
      adviceModalVisible: false,
      adviceLoading: false,
      adviceData: null
    };
  },
  computed: {
    canQuery() {
      return this.selectedDsId != null && this.dateRange && this.dateRange.length === 2;
    },
    tableColumns() {
      return [
        { title: this.$t('man-sql-mo-ban'), slot: 'sqlTemplate', minWidth: 300, ellipsis: true },
        { title: this.$t('ku-ming'), key: 'schema', width: 120, ellipsis: true, tooltip: true },
        { title: this.$t('man-sql-zhi-xing-ci-shu'), key: 'execTimes', width: 100, sorter: true },
        {
          title: this.$t('man-sql-zong-hao-shi'),
          key: 'queryTime',
          width: 100,
          sorter: true,
          render: (h, { row }) => this.formatSeconds(row.queryTime)
        },
        { title: this.$t('man-sql-ping-jun-hao-shi'), key: 'queryTimeAvg', width: 100, render: (h, { row }) => this.formatSeconds(row.queryTimeAvg) },
        { title: this.$t('man-sql-zui-da-hao-shi'), key: 'queryTimeMax', width: 100, render: (h, { row }) => this.formatSeconds(row.queryTimeMax) },
        {
          title: this.$t('man-sql-suo-deng-dai'),
          key: 'lockTime',
          width: 90,
          sorter: true,
          render: (h, { row }) => this.formatSeconds(row.lockTime)
        },
        { title: this.$t('man-sql-sao-miao-hang-shu'), key: 'rowsExamined', width: 100, sorter: true },
        { title: this.$t('man-sql-fan-hui-hang-shu'), key: 'rowsSent', width: 100, sorter: true },
        { title: this.$t('cao-zuo'), slot: 'action', width: 100, align: 'center', fixed: 'right' }
      ];
    }
  },
  mounted() {
    this.setDefaultTimeRange();
    this.loadInstances();
  },
  methods: {
    setDefaultTimeRange() {
      const end = new Date();
      const start = new Date(end.getTime() - 24 * 60 * 60 * 1000);
      this.dateRange = [start, end];
    },
    async loadInstances() {
      this.instanceLoading = true;
      try {
        const res = await this.$services.dmSlowSqlListInstances();
        this.instanceList = res.data || [];
      } finally {
        this.instanceLoading = false;
      }
    },
    handleDsChange() {
      this.quickTime = null;
    },
    handleDateRangeChange(range) {
      if (!range || range.length !== 2) {
        return;
      }
      this.quickTime = null;
      const start = new Date(range[0]);
      const end = new Date(range[1]);
      this.dateRange = this.clampDateRange(start, end);
    },
    handleQuickTime(days) {
      if (!days) {
        return;
      }
      const end = new Date();
      const start = new Date(end.getTime() - days * 24 * 60 * 60 * 1000);
      this.dateRange = this.clampDateRange(start, end);
    },
    clampDateRange(start, end) {
      const diffMs = end.getTime() - start.getTime();
      if (diffMs >= MAX_SPAN_DAYS * 24 * 60 * 60 * 1000) {
        this.$Message.warning(this.$t('man-sql-shi-jian-kuang-du-chao-xian'));
        return [start, new Date(start.getTime() + MAX_SPAN_DAYS * 24 * 60 * 60 * 1000 - 60000)];
      }
      return [start, end];
    },
    handleQuery() {
      this.currentPage = 1;
      this.loadTopSqls();
    },
    async loadTopSqls() {
      if (!this.canQuery) {
        return;
      }
      this.listLoading = true;
      try {
        const res = await this.$services.dmSlowSqlListTopSqls({
          data: {
            dsId: this.selectedDsId,
            startTime: formatTime(this.dateRange[0]),
            endTime: formatTime(this.dateRange[1]),
            sortBy: this.sortBy,
            orderBy: this.orderBy,
            schema: this.schemaFilter || undefined,
            limit: this.pageSize,
            offset: (this.currentPage - 1) * this.pageSize
          }
        });
        this.topSqlRows = (res.data && res.data.rows) || [];
        this.totalCount = (res.data && res.data.totalCount) || 0;
      } finally {
        this.listLoading = false;
      }
    },
    handleTableChange(pagination, filters, sorter) {
      if (sorter && sorter.field) {
        const fieldMap = {
          execTimes: 'ExecTimes',
          queryTime: 'QueryTime',
          lockTime: 'LockTime',
          rowsExamined: 'RowsExamined',
          rowsSent: 'RowsSent'
        };
        const newSortBy = fieldMap[sorter.field];
        if (newSortBy) {
          this.sortBy = newSortBy;
          this.orderBy = sorter.order === 'ascend' ? 'ASC' : 'DESC';
        }
      } else {
        this.sortBy = 'QueryTime';
        this.orderBy = 'DESC';
      }
      this.currentPage = 1;
      this.loadTopSqls();
    },
    handlePageChange(page) {
      this.currentPage = page;
      this.loadTopSqls();
    },
    handlePageSizeChange(size) {
      this.pageSize = size;
      this.currentPage = 1;
      this.loadTopSqls();
    },
    async handleAdvice(row) {
      this.adviceModalVisible = true;
      this.adviceLoading = true;
      this.adviceData = null;
      try {
        const res = await this.$services.dmSlowSqlSqlAdvice({
          data: {
            dsId: this.selectedDsId,
            sqlText: row.sqlTemplate,
            schema: row.schema
          }
        });
        this.adviceData = res.data;
      } finally {
        this.adviceLoading = false;
      }
    },
    formatSeconds(val) {
      if (val == null) {
        return '-';
      }
      return `${Number(val).toFixed(2)}s`;
    }
  }
};
</script>

<style lang="less" scoped>
.slow-sql-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  gap: 8px;
  flex-wrap: wrap;
}
.slow-sql-toolbar__filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.slow-sql-empty-hint {
  padding: 32px;
  text-align: center;
  color: var(--text-secondary);
  font-size: 14px;
}
.slow-sql-sql-cell {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
  color: var(--link-color);
}
.slow-sql-popover-pre {
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
.slow-sql-footer {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
.slow-sql-advice-item {
  margin-bottom: 16px;
}
.slow-sql-advice-ddl {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 8px;
}
.slow-sql-code-block {
  background: var(--bg-secondary);
  padding: 8px 12px;
  border-radius: 4px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
  flex: 1;
  max-height: 200px;
  overflow: auto;
}
.slow-sql-advice-empty {
  padding: 24px;
  text-align: center;
  color: var(--text-secondary);
}
.slow-sql-cost-section {
  margin-top: 24px;
}
.slow-sql-cost-grid {
  display: flex;
  gap: 24px;
  margin-top: 12px;
}
.slow-sql-cost-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.slow-sql-cost-label {
  font-size: 13px;
  color: var(--text-secondary);
}
.slow-sql-cost-value {
  font-size: 18px;
  font-weight: 500;
}
.slow-sql-comments-section {
  margin-top: 24px;
}
.slow-sql-comments-list {
  margin: 8px 0 0;
  padding-left: 20px;
  font-size: 13px;
  color: var(--text-secondary);
}
</style>
