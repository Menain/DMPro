<template>
  <div class="page-shell db-change-ledger-detail">
    <div class="page-shell__body">
      <div v-if="loading" class="gov-detail-loading">
        <Spin size="large" />
      </div>
      <template v-else>
        <section class="page-section">
          <div class="page-section__title">{{ $t('gov-ledger-detail-title') }}</div>
          <div class="gov-overview-grid">
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('gov-ledger-ticket-id') }}</span>
              <span>{{ ticketId }}</span>
            </div>
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('gov-ledger-db') }}</span>
              <span>{{ dbName }}</span>
            </div>
          </div>
        </section>

        <section v-if="groupList.length" class="page-section gov-v2-group-section">
          <div class="page-section__title">{{ $t('gov-v2-group-list') }}</div>
          <div class="gov-v2-group-list">
            <div v-for="group in groupList" :key="group.groupId" class="gov-v2-group-row">
              <div class="gov-v2-group-row__header">
                <div class="gov-v2-group-row__db">
                  <Icon type="ios-server-outline" />
                  <span>{{ group.dbName }}</span>
                </div>
                <span :class="['gov-v2-group-row__status', `is-${group.execStatus}`]">
                  {{ group.execStatus }}
                </span>
              </div>
              <div class="gov-v2-group-row__body">
                <div class="gov-v2-group-row__sql">
                  <read-only-editor :text="group.sqlContent" :max-height="200" />
                </div>
                <div v-if="group.execDetail" class="gov-v2-group-row__detail">
                  <span>{{ $t('gov-v2-exec-detail') }}:</span>
                  <span>{{ group.execDetail }}</span>
                </div>
                <div v-if="parsePrecheckResult(group.precheckResult)" class="gov-v2-group-row__precheck">
                  <span class="gov-v2-group-row__precheck-status">
                    {{ $t('gov-v2-precheck-status') }}: {{ parsePrecheckResult(group.precheckResult).checkStatus }}
                  </span>
                  <span class="gov-v2-group-row__precheck-type">
                    {{ parsePrecheckResult(group.precheckResult).changeType }}
                  </span>
                </div>
              </div>
            </div>
          </div>
        </section>

        <div v-else class="gov-detail-empty">
          {{ $t('gov-ledger-no-groups') }}
        </div>
      </template>
    </div>
  </div>
</template>

<script>
import ReadOnlyEditor from '@/components/editor/ReadOnlyEditor';

export default {
  name: 'DbChangeLedgerDetail',
  components: { ReadOnlyEditor },
  data() {
    return {
      ticketId: null,
      dsId: null,
      dbName: '',
      groupList: [],
      loading: false
    };
  },
  mounted() {
    this.ticketId = Number(this.$route.query.ticketId);
    this.dsId = Number(this.$route.query.dsId);
    this.dbName = this.$route.query.dbName || '';
    if (this.ticketId && this.dsId && this.dbName) {
      this.loadDetail();
    }
  },
  methods: {
    async loadDetail() {
      this.loading = true;
      try {
        const res = await this.$services.govLedgerDetail({
          data: { ticketId: this.ticketId, dsId: this.dsId, dbName: this.dbName }
        });
        if (res.success) {
          this.groupList = res.data || [];
        }
      } finally {
        this.loading = false;
      }
    },
    parsePrecheckResult(json) {
      if (!json) {
        return null;
      }
      try {
        return JSON.parse(json);
      } catch (e) {
        return null;
      }
    }
  }
};
</script>

<style lang="less">
.db-change-ledger-detail {
  .page-section {
    margin-bottom: 32px;
  }

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

  .gov-detail-loading {
    display: flex;
    justify-content: center;
    padding: 80px 0;
  }

  .gov-detail-empty {
    text-align: center;
    padding: 80px 0;
    color: var(--muted, #41454d);
  }

  .gov-overview-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
    gap: 12px 24px;
    background: var(--surface-soft, #f8fafc);
    padding: 16px 24px;
    border-radius: 10px;
  }

  .gov-overview-item {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 14px;
    min-height: 28px;
  }

  .gov-overview-label {
    color: var(--muted, #41454d);
    flex-shrink: 0;
    min-width: 80px;
  }

  .gov-v2-group-section {
    .gov-v2-group-list {
      display: flex;
      flex-direction: column;
      gap: 16px;
    }

    .gov-v2-group-row {
      border: 1px solid #eaeaea;
      border-radius: 6px;
      overflow: hidden;

      &__header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 8px 16px;
        background: var(--bg-secondary, #f8fafc);

        .gov-v2-group-row__db {
          display: flex;
          align-items: center;
          gap: 8px;
          font-size: 14px;
          font-weight: 500;
          color: #181d26;
        }

        .gov-v2-group-row__status {
          font-size: 13px;
          font-weight: 500;

          &.is-SUCCESS {
            color: #18b566;
          }
          &.is-FAILED {
            color: #ed4014;
          }
          &.is-PENDING {
            color: #707070;
          }
          &.is-EXECUTING {
            color: #2d8cf0;
          }
        }
      }

      &__body {
        padding: 16px;
        display: flex;
        flex-direction: column;
        gap: 12px;
      }

      &__detail {
        font-size: 13px;
        color: #41454d;

        span:first-child {
          font-weight: 500;
          color: #181d26;
          margin-right: 8px;
        }
      }

      &__precheck {
        display: flex;
        gap: 16px;
        font-size: 13px;

        .gov-v2-group-row__precheck-status {
          font-weight: 500;
          color: #181d26;
        }

        .gov-v2-group-row__precheck-type {
          color: #41454d;
        }
      }
    }
  }
}
</style>
