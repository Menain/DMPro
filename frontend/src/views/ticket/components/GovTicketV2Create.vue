<script lang="js">
import TicketEditor from '@/components/editor/TicketEditor';
import { mapState } from 'vuex';

const PREFILL_KEY = 'cgdm.govTicketPrefill.v2';
const PREFILL_TTL_MS = 10 * 60 * 1000;

export default {
  name: 'GovTicketV2Create',
  components: {
    TicketEditor
  },
  data() {
    return {
      TYPE_PRE_DDL: 'PRE_DDL',
      TYPE_PROD_DML: 'PROD_DML',
      ticketType: 'PRE_DDL',
      pairList: [],
      serviceList: [],
      selectedPairIds: [],
      selectedServiceId: undefined,
      groupSqlMap: {},
      checkResult: null,
      submitLoading: false,
      checkLoading: false,
      loadingPairs: false,
      ticketForm: {
        ticketTitle: '',
        description: ''
      },
      ticketRuleValidate: {
        ticketTitle: [{ required: true, message: this.$t('biao-ti-bu-neng-wei-kong'), trigger: 'blur' }]
      }
    };
  },
  computed: {
    ...mapState(['dmGlobalSetting']),
    side() {
      return this.ticketType === 'PRE_DDL' ? 'PRE' : 'PROD';
    },
    selectedPairs() {
      return this.selectedPairIds.map((id) => this.pairList.find((p) => p.id === id)).filter(Boolean);
    },
    pairSelectOptions() {
      const field = this.ticketType === 'PRE_DDL' ? 'preDbName' : 'prodDbName';
      const dsField = this.ticketType === 'PRE_DDL' ? 'preDsName' : 'prodDsName';
      const envField = this.ticketType === 'PRE_DDL' ? 'preEnvName' : 'prodEnvName';
      return this.pairList.map((p) => ({
        value: p.id,
        label: `${p[dsField]}/${p[field]}（${p[envField] || '-'}）`
      }));
    }
  },
  watch: {
    selectedPairIds(ids) {
      const map = {};
      ids.forEach((id) => {
        map[id] = this.groupSqlMap[id] || '';
      });
      this.groupSqlMap = map;
    }
  },
  mounted() {
    this.loadServices();
    this.loadPairs();
    this.ticketForm.ticketTitle = `${this.$t('gong-dan')}${new Date().getTime()}`;
    this.$nextTick(() => this.layoutEditors());
    window.addEventListener('resize', this.handleWindowResize);
    this.applyPrefill();
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.handleWindowResize);
  },
  methods: {
    async loadPairs() {
      this.loadingPairs = true;
      try {
        const res = await this.$services.dbChangeV2AvailablePairs({ data: { side: this.side } });
        if (res.success) {
          this.pairList = res.data || [];
        }
      } finally {
        this.loadingPairs = false;
      }
    },
    async loadServices() {
      const res = await this.$services.dbChangeV2AvailableServices({ data: {} });
      if (res.success) {
        this.serviceList = res.data || [];
      }
    },
    handleTypeChange() {
      this.selectedPairIds = [];
      this.groupSqlMap = {};
      this.checkResult = null;
      this.loadPairs();
    },
    handleRemovePair(pairId) {
      this.selectedPairIds = this.selectedPairIds.filter((id) => id !== pairId);
      const map = { ...this.groupSqlMap };
      delete map[pairId];
      this.groupSqlMap = map;
      this.checkResult = null;
    },
    getEditorRef(pairId) {
      return this.$refs[`editor-${pairId}`];
    },
    getSqlForPair(pairId) {
      const editor = this.getEditorRef(pairId);
      if (Array.isArray(editor)) {
        return editor[0]?.getSql() || '';
      }
      return editor?.getSql() || '';
    },
    setSqlForPair(pairId, sql) {
      this.$nextTick(() => {
        const editor = this.getEditorRef(pairId);
        if (Array.isArray(editor)) {
          editor[0]?.setSql(sql || '');
        } else {
          editor?.setSql(sql || '');
        }
      });
    },
    layoutEditors() {
      this.$nextTick(() => {
        this.selectedPairIds.forEach((id) => {
          const editor = this.getEditorRef(id);
          if (Array.isArray(editor)) {
            editor[0]?.monacoEditor?.layout();
          } else {
            editor?.monacoEditor?.layout();
          }
        });
      });
    },
    handleWindowResize() {
      this.layoutEditors();
    },
    pairDisplay(pair) {
      const field = this.ticketType === 'PRE_DDL' ? 'preDbName' : 'prodDbName';
      const dsField = this.ticketType === 'PRE_DDL' ? 'preDsName' : 'prodDsName';
      const envField = this.ticketType === 'PRE_DDL' ? 'preEnvName' : 'prodEnvName';
      return `${pair[dsField]}/${pair[field]}（${pair[envField] || '-'}）`;
    },
    validateForm() {
      if (this.selectedPairIds.length === 0) {
        this.$Message.error(this.$t('gov-v2-pair-required'));
        return false;
      }
      for (const id of this.selectedPairIds) {
        const sql = this.getSqlForPair(id);
        if (!sql.trim()) {
          this.$Message.error(this.$t('gov-v2-sql-required'));
          return false;
        }
      }
      return true;
    },
    buildCheckData() {
      return {
        ticketType: this.ticketType,
        groups: this.selectedPairIds.map((pairId) => ({
          pairId,
          sqlContent: this.getSqlForPair(pairId)
        }))
      };
    },
    async handleCheck() {
      if (this.checkLoading) {
        return;
      }
      try {
        await this.$refs.v2TicketForm.validate();
      } catch {
        return;
      }
      if (!this.validateForm()) {
        return;
      }
      this.checkLoading = true;
      try {
        const res = await this.$services.dbChangeV2Check({ data: this.buildCheckData() });
        if (res.success) {
          this.checkResult = res.data;
        }
      } finally {
        this.checkLoading = false;
      }
    },
    buildSubmitData() {
      return {
        ticketType: this.ticketType,
        serviceId: this.selectedServiceId,
        ticketTitle: this.ticketForm.ticketTitle,
        description: this.ticketForm.description,
        groups: this.selectedPairIds.map((pairId) => ({
          pairId,
          sqlContent: this.getSqlForPair(pairId)
        }))
      };
    },
    async handleSubmit() {
      if (this.submitLoading) {
        return;
      }
      try {
        await this.$refs.v2TicketForm.validate();
      } catch {
        return;
      }
      if (!this.validateForm()) {
        return;
      }
      this.submitLoading = true;
      try {
        const res = await this.$services.dbChangeV2Submit({ data: this.buildSubmitData() });
        if (res.success && res.data) {
          await this.$router.push({ path: `/ticket/${res.data.ticketId}` });
        }
      } finally {
        this.submitLoading = false;
      }
    },
    checkGroupForPair(pairId) {
      if (!this.checkResult?.groups) {
        return null;
      }
      return this.checkResult.groups.find((g) => g.pairId === pairId) || null;
    },
    applyPrefill() {
      const query = this.$route.query;
      if (query.govV2 !== '1') {
        return;
      }
      const prefillRaw = localStorage.getItem(PREFILL_KEY);
      if (prefillRaw) {
        try {
          const prefill = JSON.parse(prefillRaw);
          if (prefill.ts && Date.now() - prefill.ts < PREFILL_TTL_MS) {
            if (query.ticketType === 'PROD_DML') {
              this.ticketType = 'PROD_DML';
            }
            this.loadPairs().then(() => {
              this.applyPrefillSelection(query, prefill);
            });
          }
        } catch (e) {
          // invalid prefill data
        }
        localStorage.removeItem(PREFILL_KEY);
      } else {
        this.applyPrefillSelection(query, null);
      }
    },
    applyPrefillSelection(query, prefill) {
      if (query.ticketType === 'PROD_DML') {
        this.ticketType = 'PROD_DML';
      } else {
        this.ticketType = 'PRE_DDL';
      }
      if (query.pairId) {
        const pairId = Number(query.pairId);
        this.selectedPairIds = [pairId];
        if (prefill?.sql) {
          this.$nextTick(() => {
            this.setSqlForPair(pairId, prefill.sql);
          });
        }
      }
    }
  }
};
</script>

<template>
  <div class="gov-v2-create">
    <div class="create-content-container">
      <div class="create-ticket-editor">
        <div class="create-ticket-editor-toolbar">
          <div class="v2-type-section">
            <RadioGroup v-model="ticketType" size="small" @on-change="handleTypeChange">
              <Radio :label="TYPE_PRE_DDL">{{ $t('gov-v2-type-pre-ddl') }}</Radio>
              <Radio :label="TYPE_PROD_DML">{{ $t('gov-v2-type-prod-dml') }}</Radio>
            </RadioGroup>
          </div>
          <div class="v2-pair-select-section">
            <span class="v2-label">{{ $t('gov-v2-mapped-db') }}</span>
            <Select
              v-model="selectedPairIds"
              multiple
              filterable
              clearable
              :placeholder="$t('gov-v2-select-mapped-db')"
              style="width: 360px"
              :loading="loadingPairs"
            >
              <Option v-for="opt in pairSelectOptions" :key="opt.value" :value="opt.value" :label="opt.label">
                {{ opt.label }}
              </Option>
            </Select>
          </div>
        </div>
        <div class="editor-groups">
          <div v-if="selectedPairs.length === 0" class="editor-empty">
            <span>{{ $t('gov-v2-select-db-to-start') }}</span>
          </div>
          <div v-for="pair in selectedPairs" :key="pair.id" class="editor-group">
            <div class="editor-group__header">
              <span class="editor-group__label">{{ pairDisplay(pair) }}</span>
              <Button type="text" size="small" @click="handleRemovePair(pair.id)">
                <Icon type="md-close" />
              </Button>
            </div>
            <div class="editor-group__body">
              <ticket-editor :ref="`editor-${pair.id}`" data-source-type="MySQL" />
            </div>
            <div v-if="checkGroupForPair(pair.id)" class="editor-group__check">
              <template v-if="checkGroupForPair(pair.id).checkStatus === 'PASS'">
                <Tag color="success">{{ $t('gov-v2-check-pass') }}</Tag>
                <span class="check-change-type">{{ checkGroupForPair(pair.id).changeType }}</span>
              </template>
              <template v-else>
                <Tag color="error">{{ $t('gov-v2-check-fail') }}</Tag>
                <span v-if="checkGroupForPair(pair.id).errorMessage" class="check-error-msg">
                  {{ checkGroupForPair(pair.id).errorMessage }}
                </span>
              </template>
              <div v-if="checkGroupForPair(pair.id).statements?.length" class="check-statements">
                <div v-for="stmt in checkGroupForPair(pair.id).statements" :key="stmt.index" class="check-stmt-row">
                  <Tag size="small">{{ stmt.type }}</Tag>
                  <span class="check-stmt-sql">{{ stmt.sql }}</span>
                </div>
              </div>
              <div v-if="checkGroupForPair(pair.id).rulesCheck?.messages?.length" class="check-rules">
                <div v-for="rule in checkGroupForPair(pair.id).rulesCheck.messages" :key="rule.rule" class="check-rule-row">
                  <Tag size="small" :color="rule.level === 'BLOCK' ? 'error' : 'warning'">{{ rule.level }}</Tag>
                  <span class="check-rule-name">{{ rule.rule }}</span>
                  <span class="check-rule-msg">{{ rule.message }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
      <div class="create-ticket-content">
        <a-form ref="v2TicketForm" label-position="top" :labelCol="{ span: 24 }" :label-wrap="true" :model="ticketForm" :rules="ticketRuleValidate">
          <a-form-item :label="$t('gov-v2-service')" name="service">
            <Select v-model="selectedServiceId" clearable filterable :placeholder="$t('gov-v2-select-service')" style="width: 100%">
              <Option v-for="svc in serviceList" :key="svc.id" :value="svc.id" :label="svc.serviceName">
                {{ svc.serviceName }}
              </Option>
            </Select>
          </a-form-item>
          <a-form-item :label="$t('biao-ti')" name="ticketTitle">
            <Input v-model="ticketForm.ticketTitle" />
          </a-form-item>
          <a-form-item :label="$t('xu-qiu-miao-shu')" name="description">
            <Input type="textarea" v-model="ticketForm.description" :rows="4" />
          </a-form-item>
        </a-form>
        <div class="create-ticket-form-btn">
          <Button :loading="checkLoading" :disabled="checkLoading || submitLoading" @click="handleCheck">
            {{ $t('gov-v2-check') }}
          </Button>
          <Button type="primary" :loading="submitLoading" :disabled="submitLoading" @click="handleSubmit">
            {{ $t('gov-v2-submit') }}
          </Button>
        </div>
      </div>
    </div>
  </div>
</template>

<style lang="less">
.gov-v2-create {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  overflow: hidden;

  .create-content-container {
    flex: 1;
    display: flex;
    min-height: 0;
    overflow: hidden;
    padding: 16px 24px;
    gap: 24px;

    .create-ticket-editor {
      flex: 1;
      min-width: 0;
      min-height: 0;
      overflow: hidden;
      display: flex;
      flex-direction: column;

      .create-ticket-editor-toolbar {
        flex-shrink: 0;
        padding-bottom: 16px;
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 16px;
        flex-wrap: wrap;

        .v2-type-section {
          min-width: 0;
        }

        .v2-pair-select-section {
          display: flex;
          align-items: center;
          gap: 12px;

          .v2-label {
            font-size: 14px;
            font-weight: 500;
            color: #181d26;
            white-space: nowrap;
          }
        }
      }

      .editor-groups {
        flex: 1;
        min-height: 0;
        overflow-y: auto;
        display: flex;
        flex-direction: column;
        gap: 16px;

        .editor-empty {
          display: flex;
          align-items: center;
          justify-content: center;
          height: 100%;
          color: #707070;
          font-size: 14px;
        }

        .editor-group {
          flex-shrink: 0;
          display: flex;
          flex-direction: column;

          &__header {
            flex-shrink: 0;
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 8px 12px;
            background: var(--bg-secondary, #f8fafc);
            border-radius: 6px 6px 0 0;

            .editor-group__label {
              font-size: 14px;
              font-weight: 500;
              color: #181d26;
            }
          }

          &__body {
            height: 200px;
            border: 1px solid #eaeaea;
            border-top: none;
            overflow: hidden;
          }

          &__check {
            padding: 8px 12px;
            border: 1px solid #eaeaea;
            border-top: none;
            border-radius: 0 0 6px 6px;
            display: flex;
            flex-direction: column;
            gap: 8px;

            .check-change-type {
              font-size: 13px;
              color: #41454d;
              margin-left: 8px;
            }

            .check-error-msg {
              font-size: 13px;
              color: #ed4014;
              margin-left: 8px;
            }

            .check-statements {
              display: flex;
              flex-direction: column;
              gap: 4px;

              .check-stmt-row {
                display: flex;
                align-items: center;
                gap: 8px;
                font-size: 12px;

                .check-stmt-sql {
                  color: #41454d;
                  overflow: hidden;
                  text-overflow: ellipsis;
                  white-space: nowrap;
                  max-width: 600px;
                }
              }
            }

            .check-rules {
              display: flex;
              flex-direction: column;
              gap: 4px;

              .check-rule-row {
                display: flex;
                align-items: center;
                gap: 8px;
                font-size: 12px;

                .check-rule-name {
                  color: #181d26;
                  font-weight: 500;
                }

                .check-rule-msg {
                  color: #41454d;
                }
              }
            }
          }
        }
      }
    }

    .create-ticket-content {
      width: 320px;
      flex-shrink: 0;
      background: #f8fafc;
      padding: 24px;
      border-radius: 10px;
      overflow-y: auto;

      .create-ticket-form-btn {
        margin-top: 24px;
        display: flex;
        gap: 8px;
      }
    }
  }
}
</style>
