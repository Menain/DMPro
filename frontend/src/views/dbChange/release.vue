<template>
  <div class="page-shell db-change-release">
    <div class="page-shell__body">
      <section class="page-section">
        <div class="release-toolbar">
          <div class="release-toolbar__filters">
            <Select
              v-model="statusFilter"
              clearable
              :placeholder="$t('gov-release-filter-status')"
              style="width: 200px"
              @on-change="handleStatusChange"
            >
              <Option v-for="status in statusOptions" :key="status" :value="status" :label="releaseStatusText(status)">
                {{ releaseStatusText(status) }}
              </Option>
            </Select>
          </div>
          <div class="release-toolbar__actions">
            <Button type="primary" ghost :loading="listLoading" @click="loadReleases">
              {{ $t('shua-xin') }}
            </Button>
          </div>
        </div>

        <div class="table-container">
          <Table size="small" border stripe :loading="listLoading" :columns="releaseColumns" :data="releaseList">
            <template #status="{ row }">
              <Tag :color="statusColor(row.status)">{{ releaseStatusText(row.status) }}</Tag>
            </template>
            <template #action="{ row }">
              <Button type="text" size="small" @click="handleViewRelease(row)">
                {{ $t('cha-kan') }}
              </Button>
            </template>
          </Table>
        </div>
      </section>
    </div>
  </div>
</template>

<script>
import { RELEASE_STATUS_I18N_KEYS } from './govEventConstants';

export default {
  name: 'DbChangeRelease',
  data() {
    return {
      releaseList: [],
      listLoading: false,
      statusFilter: null
    };
  },
  computed: {
    statusOptions() {
      return Object.keys(RELEASE_STATUS_I18N_KEYS);
    },
    releaseColumns() {
      return [
        { title: this.$t('gov-release-no'), key: 'releaseNo', minWidth: 160 },
        { title: this.$t('gov-release-title'), key: 'title', minWidth: 160, ellipsis: true, tooltip: true },
        { title: this.$t('gov-release-status-label'), slot: 'status', width: 120, align: 'center' },
        { title: this.$t('gov-release-approval-id'), key: 'approvalId', width: 120 },
        { title: this.$t('chuang-jian-shi-jian'), key: 'gmtCreate', width: 170, render: (h, params) => this.formatDateTime(params.row.gmtCreate) },
        { title: this.$t('cao-zuo'), slot: 'action', width: 80, align: 'center' }
      ];
    }
  },
  mounted() {
    this.loadReleases();
  },
  methods: {
    async loadReleases() {
      this.listLoading = true;
      try {
        const res = await this.$services.govReleaseList({
          data: { status: this.statusFilter || undefined, page: 1, size: 100 }
        });
        if (res.success) {
          this.releaseList = res.data || [];
        }
      } finally {
        this.listLoading = false;
      }
    },
    handleStatusChange() {
      this.loadReleases();
    },
    handleViewRelease(row) {
      this.$router.push(`/dbChange/release/${row.id}`);
    },
    releaseStatusText(status) {
      const key = RELEASE_STATUS_I18N_KEYS[status];
      return key ? this.$t(key) : status || '-';
    },
    statusColor(status) {
      if (status === 'DONE') return 'success';
      if (status === 'PARTIAL_FAILED') return 'warning';
      if (status === 'REJECTED' || status === 'CANCELLED') return 'error';
      return 'primary';
    },
    formatDateTime(dt) {
      if (!dt) return '-';
      const ts = typeof dt === 'number' ? dt : Date.parse(dt);
      if (isNaN(ts)) return '-';
      const d = new Date(ts);
      const pad = (n) => String(n).padStart(2, '0');
      return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
    }
  }
};
</script>

<style lang="less">
.db-change-release {
  .page-section {
    margin-bottom: 32px;
  }

  .release-toolbar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 16px;
    gap: 16px;

    &__actions {
      display: flex;
      gap: 8px;
      flex-shrink: 0;
    }
  }

  .table-container {
    overflow-x: auto;
  }
}
</style>
