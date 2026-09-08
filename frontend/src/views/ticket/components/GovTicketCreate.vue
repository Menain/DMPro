<script lang="js">
import TicketEditor from '@/components/editor/TicketEditor';
import SqlFileUploadModal from '@/components/function/SqlFileUploadModal.vue';
import { RULE_WARN_LEVEL } from '@/utils';
import { mapState } from 'vuex';

const MAX_PREVIEW_SQL_LEN = 200;

export default {
  name: 'GovTicketCreate',
  components: {
    TicketEditor,
    SqlFileUploadModal
  },
  data() {
    return {
      loading: false,
      submitting: false,
      logicalDbList: [],
      selectedLogicalDbId: undefined,
      contentType: 'INLINE',
      showSqlUploadModal: false,
      sqlUploading: false,
      sqlAttachment: null,
      splitPreviewData: null,
      showSplitPreviewModal: false,
      showValidationResultModal: false,
      showCheckedOnlyError: false,
      noPassedRuleList: [],
      govTicketData: {
        ticketTitle: '',
        description: ''
      },
      ticketRuleValidate: {
        ticketTitle: [
          {
            required: true,
            message: this.$t('biao-ti-bu-neng-wei-kong'),
            trigger: 'blur'
          }
        ],
        description: [
          {
            required: true,
            message: this.$t('xu-qiu-miao-shu-bu-neng-wei-kong'),
            trigger: 'blur'
          }
        ]
      }
    };
  },
  computed: {
    ...mapState(['dmGlobalSetting']),
    sqlFileMaxMegaByte() {
      return this.dmGlobalSetting?.sqlFileMaxSize || 20;
    },
    selectedDsType() {
      if (!this.selectedLogicalDbId) {
        return 'MySQL';
      }
      const db = this.logicalDbList.find((d) => d.id === this.selectedLogicalDbId);
      return db?.dsType || 'MySQL';
    },
    hasDmlComponent() {
      const ct = this.splitPreviewData?.changeType;
      return ct === 'DML' || ct === 'MIXED';
    },
    canFilterValidationResults() {
      const hasError = this.noPassedRuleList.some((rule) => rule.ruleLevel !== 'SUGGEST');
      return hasError && this.noPassedRuleList.some((rule) => rule.ruleLevel === 'SUGGEST');
    },
    filteredValidationResults() {
      if (this.showCheckedOnlyError) {
        return this.noPassedRuleList.filter((rule) => rule.ruleLevel !== 'SUGGEST');
      }
      return this.noPassedRuleList;
    },
    validationResultColumns() {
      return [
        {
          title: this.$t('deng-ji'),
          slot: 'warnLevel',
          width: 90
        },
        {
          title: this.$t('ming-cheng'),
          key: 'name',
          width: 200
        },
        {
          title: this.$t('wei-gui-ti-shi'),
          key: 'desc'
        }
      ];
    },
    splitStmtColumns() {
      return [
        {
          title: this.$t('gov-split-stmt-index'),
          key: 'stmtIndex',
          width: 70
        },
        {
          title: this.$t('gov-split-stmt-sql'),
          key: 'sqlDisplay',
          ellipsis: true
        },
        {
          title: this.$t('gov-split-stmt-type'),
          slot: 'changeType',
          width: 90
        }
      ];
    },
    splitStmtRows() {
      const stmts = this.splitPreviewData?.stmts || [];
      return stmts.map((s) => ({
        ...s,
        sqlDisplay: s.sql && s.sql.length > MAX_PREVIEW_SQL_LEN ? s.sql.slice(0, MAX_PREVIEW_SQL_LEN) + '...' : s.sql || ''
      }));
    },
    execConfigSummary() {
      const cfg = this.splitPreviewData?.stmts?.[0]?.execConfig;
      if (!cfg) {
        return '-';
      }
      const parts = [];
      parts.push(cfg.enableTransactional ? this.$t('gov-split-transactional-on') : this.$t('gov-split-transactional-off'));
      if (cfg.errorStrategy) {
        parts.push(`${this.$t('gov-split-error-strategy')}: ${cfg.errorStrategy}`);
      }
      return parts.join(' / ');
    }
  },
  mounted() {
    this.loadLogicalDbs();
    this.govTicketData.ticketTitle = `${this.$t('gong-dan')}${new Date().getTime()}`;
    this.$nextTick(() => {
      this.layoutEditors();
    });
    window.addEventListener('resize', this.handleWindowResize);
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.handleWindowResize);
  },
  methods: {
    async loadLogicalDbs() {
      const res = await this.$services.myLogicalDbs({ data: {} });
      if (res.success) {
        this.logicalDbList = res.data || [];
      }
    },
    layoutEditors() {
      this.$nextTick(() => {
        [this.$refs.govSqlEditor, this.$refs.govModalRollbackEditor].forEach((editor) => {
          editor?.monacoEditor?.layout();
        });
      });
    },
    handleWindowResize() {
      this.layoutEditors();
    },
    formatFileSize(size) {
      if (size < 1024) {
        return `${size} B`;
      }
      if (size < 1024 * 1024) {
        return `${(size / 1024).toFixed(1)} KB`;
      }
      return `${(size / 1024 / 1024).toFixed(1)} MB`;
    },
    async uploadSqlFile(files) {
      const file = files?.[0];
      if (!file || this.sqlUploading) {
        return;
      }
      this.sqlUploading = true;
      try {
        const data = new FormData();
        data.append('file', file);
        const res = await this.$services.dmTicketUploadSqlFile({
          data,
          headers: {
            'Content-Type': 'multipart/form-data'
          }
        });
        if (res.success) {
          this.sqlAttachment = res.data;
          this.contentType = 'ATTACHMENT';
          this.showSqlUploadModal = false;
        }
      } finally {
        this.sqlUploading = false;
      }
    },
    switchToInline() {
      this.contentType = 'INLINE';
      this.sqlAttachment = null;
    },
    handleAttachmentModeAction() {
      if (this.contentType === 'ATTACHMENT' && this.sqlAttachment) {
        this.showSqlUploadModal = true;
        return;
      }
      this.contentType = 'ATTACHMENT';
      this.showSqlUploadModal = true;
    },
    getSqlText() {
      if (this.contentType === 'INLINE') {
        return this.$refs.govSqlEditor?.getSql() || '';
      }
      return '';
    },
    getRollbackSqlText() {
      return this.$refs.govModalRollbackEditor?.getSql() || '';
    },
    validateForm() {
      if (!this.selectedLogicalDbId) {
        this.$Message.error(this.$t('gov-logical-db-required'));
        return false;
      }
      const sql = this.getSqlText();
      if (!sql.trim()) {
        this.$Message.error(this.$t('gov-sql-required'));
        return false;
      }
      return true;
    },
    async handleSubmit() {
      if (this.loading) {
        return;
      }
      try {
        await this.$refs.govTicketForm.validate();
      } catch {
        return;
      }
      if (!this.validateForm()) {
        return;
      }
      this.loading = true;
      try {
        const sql = this.getSqlText();
        const res = await this.$services.dbChangeSplitPreview({
          data: {
            logicalDbId: this.selectedLogicalDbId,
            sql
          }
        });
        if (res.success) {
          this.splitPreviewData = res.data;
          this.showSplitPreviewModal = true;
          this.$nextTick(() => {
            this.layoutEditors();
          });
        }
      } finally {
        this.loading = false;
      }
    },
    buildSubmitData() {
      const sql = this.getSqlText();
      const data = {
        logicalDbId: this.selectedLogicalDbId,
        ticketTitle: this.govTicketData.ticketTitle,
        description: this.govTicketData.description,
        sql,
        contentType: this.contentType
      };
      if (this.hasDmlComponent) {
        data.rollbackSql = this.getRollbackSqlText();
      }
      if (this.contentType === 'ATTACHMENT' && this.sqlAttachment) {
        data.attachmentId = this.sqlAttachment.attachmentId;
      }
      return data;
    },
    async handleConfirmSubmit() {
      if (this.submitting) {
        return;
      }
      if (this.hasDmlComponent && !this.getRollbackSqlText().trim()) {
        this.$Message.error(this.$t('gov-rollback-sql-required'));
        return;
      }
      this.submitting = true;
      try {
        const res = await this.$services.dbChangePreSubmit({ data: this.buildSubmitData() });
        if (res.success) {
          if (res.data.failure) {
            this.noPassedRuleList = res.data.checkedVOS || [];
            this.showValidationResultModal = this.noPassedRuleList.length > 0;
          } else {
            this.showSplitPreviewModal = false;
            this.showValidationResultModal = false;
            await this.$router.push({ path: `/ticket/${res.data.ticketId}` });
          }
        }
      } finally {
        this.submitting = false;
      }
    },
    handleCloseValidationResult() {
      this.showValidationResultModal = false;
      this.noPassedRuleList = [];
    },
    handleCloseSplitPreview() {
      this.showSplitPreviewModal = false;
    }
  }
};
</script>

<template>
  <div class="gov-ticket-create">
    <div class="create-content-container">
      <div class="create-ticket-editor">
        <div class="create-ticket-editor-toolbar">
          <div class="create-ticket-editor-operator">
            <span class="gov-logical-db-label">{{ $t('gov-logical-db') }}</span>
            <Select v-model="selectedLogicalDbId" filterable clearable :placeholder="$t('gov-select-logical-db')" style="width: 260px">
              <Option v-for="db in logicalDbList" :key="db.id" :value="db.id" :label="db.resourceName">
                <span>{{ db.resourceName }}</span>
                <span style="color: #999; margin-left: 8px; font-size: 12px">{{ db.resourceCode }}</span>
              </Option>
            </Select>
          </div>
          <div class="sql-mode-actions">
            <Button size="small" :type="contentType === 'INLINE' ? 'primary' : 'default'" @click="switchToInline">
              {{ $t('ticket-sql-online-edit') }}
            </Button>
            <Button size="small" :type="contentType === 'ATTACHMENT' ? 'primary' : 'default'" @click="handleAttachmentModeAction">
              {{ $t(contentType === 'ATTACHMENT' && sqlAttachment ? 'ticket-sql-reupload' : 'ticket-sql-file-upload') }}
            </Button>
          </div>
        </div>
        <div class="editor">
          <div class="collapse raw">
            <div v-if="contentType === 'ATTACHMENT' && sqlAttachment" class="sql-file-meta">
              <span class="sql-file-name">{{ sqlAttachment.fileName }}</span>
              <span>{{ formatFileSize(sqlAttachment.fileSize) }}</span>
              <span>{{ $t('ticket-sql-readonly') }}</span>
            </div>
            <div class="content sql-editor-content">
              <ticket-editor
                ref="govSqlEditor"
                :data-source-type="selectedDsType"
                :read-only="contentType === 'ATTACHMENT'"
                :virtual-scroll-mode="contentType === 'ATTACHMENT'"
              />
            </div>
          </div>
        </div>
      </div>
      <div class="create-ticket-content">
        <a-form
          ref="govTicketForm"
          label-position="top"
          :labelCol="{ span: 24 }"
          :label-wrap="true"
          :model="govTicketData"
          :rules="ticketRuleValidate"
        >
          <a-form-item :label="$t('biao-ti')" name="ticketTitle">
            <Input v-model="govTicketData.ticketTitle" />
          </a-form-item>
          <a-form-item :label="$t('xu-qiu-miao-shu')" name="description">
            <Input type="textarea" v-model="govTicketData.description" :rows="4" />
          </a-form-item>
        </a-form>
        <div class="create-ticket-form-btn">
          <Button type="primary" :loading="loading" :disabled="loading" @click="handleSubmit">
            {{ $t('jiao-yan-0') }}
          </Button>
        </div>
      </div>
    </div>

    <CCModal
      v-model="showSplitPreviewModal"
      :width="900"
      :title="$t('gov-split-preview')"
      :mask-closable="false"
      @on-cancel="handleCloseSplitPreview"
    >
      <div v-if="splitPreviewData" class="gov-split-preview-content">
        <div class="gov-split-summary">
          <div class="gov-split-summary-item">
            <span class="gov-split-summary-label">{{ $t('gov-split-overall-type') }}</span>
            <Tag :color="hasDmlComponent ? 'warning' : 'primary'">{{ splitPreviewData.changeType }}</Tag>
          </div>
          <div class="gov-split-summary-item">
            <span class="gov-split-summary-label">{{ $t('gov-split-exec-config') }}</span>
            <span>{{ execConfigSummary }}</span>
          </div>
        </div>
        <Table :columns="splitStmtColumns" :data="splitStmtRows" border size="small" max-height="240">
          <template #changeType="{ row }">
            <Tag :color="row.changeType === 'DML' ? 'warning' : 'primary'">{{ row.changeType }}</Tag>
          </template>
        </Table>
        <div v-if="hasDmlComponent" class="gov-split-rollback">
          <div class="gov-split-rollback-label">
            {{ $t('gov-rollback-sql') }}
            <span class="gov-split-rollback-required">*</span>
            <span class="gov-split-rollback-hint">{{ $t('gov-rollback-sql-hint') }}</span>
          </div>
          <div class="gov-split-rollback-editor-wrapper">
            <ticket-editor ref="govModalRollbackEditor" :data-source-type="selectedDsType" />
          </div>
        </div>
      </div>
      <template #footer>
        <Button type="primary" :loading="submitting" :disabled="submitting" @click="handleConfirmSubmit">
          {{ $t('gov-split-confirm') }}
        </Button>
        <Button @click="handleCloseSplitPreview">{{ $t('qu-xiao') }}</Button>
      </template>
    </CCModal>

    <CCModal
      v-model="showValidationResultModal"
      :width="800"
      :title="$t('gui-ze-xiao-yan-shi-bai')"
      wrap-class-name="ticket-rule-validation-modal"
      @on-cancel="handleCloseValidationResult"
    >
      <div v-if="canFilterValidationResults" class="validation-result-toolbar">
        <Checkbox v-model="showCheckedOnlyError">{{ $t('jin-xian-shi-yan-zhong') }}</Checkbox>
      </div>
      <Table :columns="validationResultColumns" :data="filteredValidationResults" border stripe max-height="360">
        <template #warnLevel="{ row }">
          <Tag :color="row.ruleLevel === 'SUGGEST' ? 'warning' : 'error'">
            {{ RULE_WARN_LEVEL[row.ruleLevel] }}
          </Tag>
        </template>
      </Table>
      <template #footer>
        <Button @click="handleCloseValidationResult">{{ $t('guan-bi') }}</Button>
      </template>
    </CCModal>

    <SqlFileUploadModal v-model="showSqlUploadModal" :loading="sqlUploading" :max-mega-byte="sqlFileMaxMegaByte" @confirm="uploadSqlFile" />
  </div>
</template>

<style lang="less">
.gov-ticket-create {
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

        .create-ticket-editor-operator {
          min-width: 0;
          display: flex;
          align-items: center;
          gap: 12px;

          .gov-logical-db-label {
            font-size: 14px;
            font-weight: 500;
            color: #181d26;
            white-space: nowrap;
          }
        }

        .sql-mode-actions {
          flex: none;
          display: flex;
          align-items: center;
          gap: 8px;
        }
      }

      .editor {
        flex: 1;
        min-height: 200px;
        display: flex;
        flex-direction: column;

        .collapse {
          display: flex;
          flex-direction: column;
          min-height: 0;
          flex: 1;

          &.raw {
            overflow: hidden;
          }

          .sql-file-meta {
            flex: none;
            display: flex;
            min-width: 0;
            min-height: 36px;
            align-items: center;
            gap: 12px;
            padding: 0 12px;
            margin-bottom: 8px;
            background: var(--bg-secondary, #f8fafc);
            color: var(--text-secondary, #707070);
            font-size: 13px;

            .sql-file-name {
              max-width: 320px;
              overflow: hidden;
              text-overflow: ellipsis;
              white-space: nowrap;
            }
          }

          .content {
            flex: 1;
            min-height: 0;
            border: 1px solid #eaeaea;
            border-radius: 6px;
            overflow: hidden;
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

        button {
          margin-right: 12px;
        }
      }
    }
  }
}

.gov-split-preview-content {
  .gov-split-summary {
    display: flex;
    gap: 32px;
    margin-bottom: 16px;

    .gov-split-summary-item {
      display: flex;
      align-items: center;
      gap: 8px;

      .gov-split-summary-label {
        font-size: 14px;
        font-weight: 500;
        color: #181d26;
      }
    }
  }

  .gov-split-rollback {
    margin-top: 16px;

    .gov-split-rollback-label {
      font-size: 14px;
      font-weight: 500;
      color: #181d26;
      margin-bottom: 8px;

      .gov-split-rollback-required {
        color: #ed4014;
        margin-left: 4px;
      }

      .gov-split-rollback-hint {
        font-size: 13px;
        font-weight: 400;
        color: #41454d;
        margin-left: 12px;
      }
    }

    .gov-split-rollback-editor-wrapper {
      height: 200px;
      border: 1px solid #eaeaea;
      border-radius: 6px;
      overflow: hidden;
    }
  }
}
</style>
