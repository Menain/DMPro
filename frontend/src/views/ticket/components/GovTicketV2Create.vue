<script lang="js">
import TicketEditor from '@/components/editor/TicketEditor';
import dayjs from 'dayjs';
import { mapState } from 'vuex';
import { segmentRangeI18n } from '@/const/ghost';
import { segmentTypeBadgeI18n } from '@/const/dmlBatch';

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
      MODE_DIRECT: 'DIRECT',
      MODE_VIA_RELEASE: 'VIA_RELEASE',
      ticketType: 'PRE_DDL',
      approvalMode: 'DIRECT',
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
      lastAutoTitle: '',
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
    },
    selectedServiceId() {
      if (this.ticketForm.ticketTitle === this.lastAutoTitle) {
        this.applyAutoTitle();
      }
    }
  },
  mounted() {
    this.loadServices();
    this.loadPairs();
    this.applyAutoTitle();
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
    applyAutoTitle() {
      const prefix = this.$t('gov-v2-title-prefix');
      const ts = dayjs().format('YYYYMMDDHHmmss');
      const svc = this.serviceList.find((s) => s.id === this.selectedServiceId);
      let title = `${prefix}-${ts}`;
      if (svc) {
        title = `${prefix}-${svc.serviceName}-${ts}`;
      }
      this.lastAutoTitle = title;
      this.ticketForm.ticketTitle = title;
    },
    handleTypeChange() {
      this.selectedPairIds = [];
      this.groupSqlMap = {};
      this.checkResult = null;
      this.approvalMode = this.MODE_DIRECT;
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
        const rbEditor = this.$refs.rollBackSqlEditor;
        if (Array.isArray(rbEditor)) {
          rbEditor[0]?.monacoEditor?.layout();
        } else {
          rbEditor?.monacoEditor?.layout();
        }
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
      const data = {
        ticketType: this.ticketType,
        serviceId: this.selectedServiceId,
        ticketTitle: this.ticketForm.ticketTitle,
        description: this.ticketForm.description,
        rollBackSql: this.$refs.rollBackSqlEditor?.getSql()?.trim() || '',
        groups: this.selectedPairIds.map((pairId) => ({
          pairId,
          sqlContent: this.getSqlForPair(pairId)
        }))
      };
      if (this.ticketType === this.TYPE_PROD_DML) {
        data.approvalMode = this.approvalMode;
      }
      return data;
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
    // B6 M4: the mixed-group segment PLAN (check VO GroupResult.segments, 1-based exec_order
    // bounds) — shows the submitter how the group will be split and serially executed (the M1
    // multi-statement intercept is gone since M2; the plan display is its successor)
    segmentRangeText(seg) {
      const range = segmentRangeI18n(seg);
      if (!range) {
        return '';
      }
      return this.$t(range.key, range.args);
    },
    segmentTypeBadgeI18n(segType) {
      return segmentTypeBadgeI18n(segType);
    },
    // GHOST plan segments freeze `rows` (B6), DML_BATCH plan segments freeze `estimatedRows` (B7)
    segmentRowsOf(seg) {
      if (seg.rows != null) {
        return seg.rows;
      }
      return seg.estimatedRows != null ? seg.estimatedRows : null;
    },
    // B7 N3: dmlBatchRoutes display marks — the message copy is backend pre-translated (the
    // GHOST_ROUTE_ANNOUNCE precedent, rendered verbatim incl. the multi-line non-idempotent risk)
    dmlRouteTagI18n(route) {
      if (route === 'DML_BATCH') {
        return 'fen-pi-lu-you-biao-qian';
      }
      if (route === 'SAME_TABLE_ACCUMULATE') {
        return 'fen-pi-lei-ji-ti-shi';
      }
      return 'wu-suo-seg-zhi-jie';
    },
    dmlRouteTagColor(route) {
      if (route === 'DML_BATCH' || route === 'DIRECT_KEY_UNSUPPORTED' || route === 'SAME_TABLE_ACCUMULATE') {
        return 'warning';
      }
      return 'default';
    },
    dmlRouteRowClass(route) {
      // yellow-flag rows: 将分批 / 键型不支持(长锁风险) / 同表累计提示; grey rows: 形状不支持 /
      // 探测失败 / 组内会话状态语句(能力限制非违规)
      if (route === 'DML_BATCH' || route === 'DIRECT_KEY_UNSUPPORTED' || route === 'SAME_TABLE_ACCUMULATE') {
        return 'is-batch';
      }
      return 'is-direct';
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
          <div v-if="ticketType === TYPE_PROD_DML" class="v2-approval-mode-section">
            <span class="v2-label">{{ $t('gov-v2-approval-mode') }}</span>
            <RadioGroup v-model="approvalMode" size="small">
              <Radio :label="MODE_DIRECT">{{ $t('gov-v2-approval-mode-direct') }}</Radio>
              <Radio :label="MODE_VIA_RELEASE">{{ $t('gov-v2-approval-mode-via-release') }}</Radio>
            </RadioGroup>
            <div class="v2-approval-mode-desc">
              <span v-if="approvalMode === MODE_DIRECT">{{ $t('gov-v2-approval-mode-direct-desc') }}</span>
              <span v-else>{{ $t('gov-v2-approval-mode-via-release-desc') }}</span>
            </div>
            <div v-if="approvalMode === MODE_VIA_RELEASE" class="v2-mixed-order-hint">
              {{ $t('gov-v2-mixed-release-order-hint') }}
            </div>
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
              <!-- B7 N3 (裁决 #5/#16, design §8.1): the no-key large-DML rejection renders the
                   structured payload (failCode=DML_NO_KEY + dmlNoKeyReject) — 表/行数/阈值/键详情/
                   guidance; submit refuses the whole ticket server-side (this is the /check 呈现) -->
              <div v-if="checkGroupForPair(pair.id).failCode === 'DML_NO_KEY' && checkGroupForPair(pair.id).dmlNoKeyReject" class="check-dml-reject">
                <div class="check-dml-reject__title">{{ $t('fen-pi-ju-jue-biao-ti') }}</div>
                <div class="check-dml-reject__row">
                  <span>{{ $t('fen-pi-ju-jue-biao') }}</span>
                  <strong>{{ checkGroupForPair(pair.id).dmlNoKeyReject.dbName }}.{{ checkGroupForPair(pair.id).dmlNoKeyReject.tableName }}</strong>
                </div>
                <div class="check-dml-reject__row">
                  <span>{{ $t('fen-pi-ju-jue-hang-shu') }}</span>
                  <strong>
                    {{
                      $t('fen-pi-ju-jue-hang-shu-zhi', [
                        checkGroupForPair(pair.id).dmlNoKeyReject.estimatedRows,
                        checkGroupForPair(pair.id).dmlNoKeyReject.rowThreshold
                      ])
                    }}
                  </strong>
                </div>
                <div v-if="checkGroupForPair(pair.id).dmlNoKeyReject.keyDetail" class="check-dml-reject__row">
                  <span>{{ $t('fen-pi-ju-jue-jian') }}</span>
                  <strong>{{ checkGroupForPair(pair.id).dmlNoKeyReject.keyDetail }}</strong>
                </div>
                <div v-if="checkGroupForPair(pair.id).dmlNoKeyReject.sqlSummary" class="check-dml-reject__sql">
                  {{ checkGroupForPair(pair.id).dmlNoKeyReject.sqlSummary }}
                </div>
                <div class="check-dml-reject__guidance">{{ $t('fen-pi-ju-jue-zhi-dao') }}</div>
              </div>
              <div v-if="checkGroupForPair(pair.id).statements?.length" class="check-statements">
                <div v-for="stmt in checkGroupForPair(pair.id).statements" :key="stmt.index" class="check-stmt-row">
                  <Tag size="small">{{ stmt.type }}</Tag>
                  <span class="check-stmt-sql">{{ stmt.sql }}</span>
                </div>
              </div>
              <div v-if="checkGroupForPair(pair.id).ghostRoutes?.length" class="check-ghost-routes">
                <div
                  v-for="route in checkGroupForPair(pair.id).ghostRoutes"
                  :key="route.stmtIndex"
                  :class="['check-ghost-route-row', route.route === 'GHOST' ? 'is-ghost' : 'is-alert-pass']"
                >
                  <Tag size="small" :color="route.route === 'GHOST' ? 'warning' : 'default'">
                    {{ route.route === 'GHOST' ? $t('wu-suo-lu-you-ghost') : $t('wu-suo-lu-you-alert-pass') }}
                  </Tag>
                  <span class="check-ghost-route-msg">{{ route.message }}</span>
                </div>
              </div>
              <!-- B7 N3 (design §10): dmlBatchRoutes display marks — 路由公告黄旗(将分批+非幂等风险
                   行) / 键型不支持黄旗 / 形状不支持·探测失败·组内会话状态灰提示 / 同表累计提示(R13);
                   the message copy is backend pre-translated (rendered verbatim, multi-line) -->
              <div v-if="checkGroupForPair(pair.id).dmlBatchRoutes?.length" class="check-dml-routes">
                <div
                  v-for="(route, routeIdx) in checkGroupForPair(pair.id).dmlBatchRoutes"
                  :key="`${route.stmtIndex}-${routeIdx}`"
                  :class="['check-dml-route-row', dmlRouteRowClass(route.route)]"
                >
                  <Tag size="small" :color="dmlRouteTagColor(route.route)">
                    {{ $t(dmlRouteTagI18n(route.route)) }}
                  </Tag>
                  <span class="check-dml-route-msg">{{ route.message }}</span>
                </div>
              </div>
              <!-- B6 M4: mixed-group segment plan — per-segment badges make the serial split
                   explicit at submit time (GHOST segment = yellow flag; replaces the M1
                   multi-statement intercept lifted in M2). B7 N3: the badge is three-way
                   (GHOST / DML_BATCH / AUTOEXEC) — additive, the ghost rows render unchanged -->
              <div v-if="checkGroupForPair(pair.id).segments?.length" class="check-segments">
                <div class="check-segments__title">{{ $t('wu-suo-zhi-xing-duan') }}</div>
                <div v-for="seg in checkGroupForPair(pair.id).segments" :key="seg.segIdx" class="check-segment-row">
                  <Tag size="small" :color="seg.type === 'AUTOEXEC' ? 'default' : 'warning'">
                    {{ $t(segmentTypeBadgeI18n(seg.type)) }}
                  </Tag>
                  <span class="check-segment-range">{{ segmentRangeText(seg) }}</span>
                  <span v-if="seg.type !== 'AUTOEXEC' && seg.table" class="check-segment-table">
                    {{ segmentRowsOf(seg) == null ? seg.table : $t('wu-suo-seg-biao-hang-shu', [seg.table, segmentRowsOf(seg)]) }}
                  </span>
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
          <a-form-item :label="$t('hui-gun-sql')" class="rollback-sql-form-item">
            <div class="rollback-sql-editor-wrapper">
              <ticket-editor ref="rollBackSqlEditor" data-source-type="MySQL" />
            </div>
            <div class="rollback-sql-hint">{{ $t('hui-gun-sql-hint') }}</div>
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

        .v2-approval-mode-section {
          min-width: 0;
          display: flex;
          flex-direction: column;
          gap: 4px;

          .v2-approval-mode-desc {
            font-size: 12px;
            color: #707070;
          }

          .v2-mixed-order-hint {
            font-size: 12px;
            color: #707070;
            padding: 4px 8px;
            background: var(--surface-soft, #f8fafc);
            border-radius: 4px;
          }
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

            .check-ghost-routes {
              display: flex;
              flex-direction: column;
              gap: 4px;

              .check-ghost-route-row {
                display: flex;
                align-items: center;
                gap: 8px;
                font-size: 12px;
                padding: 4px 8px;
                border-radius: 4px;

                // yellow flag: threshold-exceeding ALTER auto-routed to gh-ost (design §6.8)
                &.is-ghost {
                  background: rgba(255, 153, 0, 0.08);
                }

                // alert-pass: threshold exceeded but not lock-free capable — direct execution,
                // ops already alerted (design §6.10 boundary matrix)
                &.is-alert-pass {
                  background: var(--surface-soft, #f8fafc);
                }

                .check-ghost-route-msg {
                  color: #41454d;
                }
              }
            }

            // B7 N3: DML batch route marks (the check-ghost-routes twin)
            .check-dml-routes {
              display: flex;
              flex-direction: column;
              gap: 4px;

              .check-dml-route-row {
                display: flex;
                align-items: flex-start;
                gap: 8px;
                font-size: 12px;
                padding: 4px 8px;
                border-radius: 4px;

                // yellow flag: 将分批 / 键型不支持(长锁风险) / 同表累计提示
                &.is-batch {
                  background: rgba(255, 153, 0, 0.08);
                }

                // grey hint: 形状不支持 / 探测失败 / 组内会话状态语句(能力限制非违规)
                &.is-direct {
                  background: var(--surface-soft, #f8fafc);
                }

                .check-dml-route-msg {
                  color: #41454d;
                  white-space: pre-wrap;
                  word-break: break-all;
                }
              }
            }

            // B7 N3 (裁决 #5): the no-key large-DML rejection block (failCode=DML_NO_KEY payload)
            .check-dml-reject {
              display: flex;
              flex-direction: column;
              gap: 4px;
              padding: 8px 12px;
              border-radius: 4px;
              background: rgba(255, 24, 21, 0.06);
              font-size: 12px;

              .check-dml-reject__title {
                font-size: 13px;
                font-weight: 500;
                color: #ed4014;
              }

              .check-dml-reject__row {
                display: flex;
                gap: 8px;
                color: #41454d;

                span {
                  flex: none;
                  color: #707070;
                }
              }

              .check-dml-reject__sql {
                color: #41454d;
                font-family: Menlo, Monaco, 'Courier New', monospace;
                white-space: pre-wrap;
                word-break: break-all;
              }

              .check-dml-reject__guidance {
                color: #ed4014;
              }
            }

            // B6 M4: mixed-group segment plan rows (badge + range + ghost target facts)
            .check-segments {
              display: flex;
              flex-direction: column;
              gap: 4px;

              .check-segments__title {
                font-size: 12px;
                font-weight: 500;
                color: #181d26;
              }

              .check-segment-row {
                display: flex;
                align-items: center;
                gap: 8px;
                font-size: 12px;

                .check-segment-range {
                  color: #41454d;
                }

                .check-segment-table {
                  color: #707070;
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

      .rollback-sql-form-item {
        .rollback-sql-editor-wrapper {
          height: 120px;
          border: 1px solid #eaeaea;
          border-radius: 6px;
          overflow: hidden;
        }

        .rollback-sql-hint {
          margin-top: 4px;
          font-size: 12px;
          color: #707070;
          line-height: 1.5;
        }
      }

      .create-ticket-form-btn {
        margin-top: 24px;
        display: flex;
        gap: 8px;
      }
    }
  }
}
</style>
