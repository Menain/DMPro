<template>
  <div class="page-shell db-change-release-detail">
    <div class="page-shell__body">
      <div class="release-detail-back">
        <Button type="text" @click="handleBack">
          <Icon type="ios-arrow-back" />
          {{ $t('fan-hui') }}
        </Button>
      </div>
      <div v-if="loading" class="gov-detail-loading">
        <Spin size="large" />
      </div>
      <template v-else-if="detail">
        <section class="page-section">
          <div class="page-section__title">{{ $t('gov-release-overview') }}</div>
          <div class="gov-overview-grid">
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('gov-release-no') }}</span>
              <span>{{ detail.releaseNo }}</span>
            </div>
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('gov-release-title') }}</span>
              <span>{{ detail.title }}</span>
            </div>
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('gov-release-status-label') }}</span>
              <Tag :color="statusColor(detail.status)">{{ releaseStatusText(detail.status) }}</Tag>
            </div>
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('gov-release-creator') }}</span>
              <span>{{ detail.creatorUid || '-' }}</span>
            </div>
            <div v-if="detail.returnReason" class="gov-overview-item gov-overview-item--full">
              <span class="gov-overview-label">{{ $t('gov-release-return-reason-label') }}</span>
              <span class="gov-release-return-reason-text">{{ detail.returnReason }}</span>
            </div>
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('gov-release-approval-ticket') }}</span>
              <span v-if="detail.approvalId">
                <a @click="goToTicket(detail.approvalId)">{{ detail.approvalId }}</a>
              </span>
              <span v-else>-</span>
            </div>
            <div class="gov-overview-item">
              <span class="gov-overview-label">{{ $t('chuang-jian-shi-jian') }}</span>
              <span>{{ formatDateTime(detail.gmtCreate) }}</span>
            </div>
          </div>
        </section>

        <!-- 钉钉审批桥接出站面板(task 10-09,design §3.1):发布单审批工单为 External 通道才呈现;数据经 queryApprovalBaseInfo(approvalId) 随 loadDetail 带出,零新轮询器 -->
        <section v-if="showBridgeOutbound" class="page-section bridge-outbound-section">
          <div class="page-section__title">{{ $t('ding-qiao-biao-ti') }}</div>
          <DingBridgeOutboundPanel
            :outbound-status="approvalBaseInfo.outboundStatus"
            :outbound-error="approvalBaseInfo.outboundError"
            :flow-id="approvalBaseInfo.approIdentity"
            :reissuing="bridgeReissuing"
            :can-reissue="canReissueFlow"
            @reissue="handleReissueFlow"
          />
        </section>

        <Alert v-if="ghostReplayHint" type="warning" show-icon class="gov-ghost-replay-hint">
          {{ $t('gov-release-ghost-replay-hint') }}
        </Alert>
        <!-- B7 N3 发布链披露：分批源的生产重放为直接执行（长锁/超时风险黄旗） -->
        <Alert v-if="dmlBatchReplayHint" type="warning" show-icon class="gov-ghost-replay-hint">
          {{ $t('gov-release-dml-batch-replay-hint') }}
        </Alert>

        <section v-if="showReleaseActions" class="page-section">
          <div class="gov-release-actions">
            <Button v-if="canReturnRelease" type="warning" :loading="returning" @click="openReturnModal">
              {{ $t('gov-release-return-button') }}
            </Button>
            <template v-if="detail.status === 'RETURNED'">
              <Button type="primary" :loading="resubmitting" @click="openResubmitModal">
                {{ $t('gov-release-resubmit-button') }}
              </Button>
              <Button type="error" :loading="cancelling" @click="handleCancel">
                {{ $t('gov-release-cancel-button') }}
              </Button>
            </template>
          </div>
        </section>

        <section v-if="showGateResult" class="page-section">
          <div class="page-section__title">{{ $t('gov-release-gate-result') }}</div>
          <pre class="gov-gate-result-json">{{ formatJson(detail.gateResult) }}</pre>
        </section>

        <section v-if="detail.status === 'RETURNED' && detail.sourceTickets && detail.sourceTickets.length" class="page-section">
          <div class="page-section__title">{{ $t('gov-release-source-tickets') }}</div>
          <div class="gov-source-tickets">
            <div v-for="t in detail.sourceTickets" :key="t.ticketId" class="gov-source-ticket-row">
              <div class="gov-source-ticket-row__info">
                <span class="gov-source-ticket-row__id">{{ t.ticketId }}</span>
                <span class="gov-source-ticket-row__title">{{ t.ticketTitle }}</span>
                <Tag v-if="t.ticketType === 'PROD_DML'" size="small" color="warning">{{ $t('gov-v2-type-prod-dml') }}</Tag>
                <Tag v-else-if="t.ticketType === 'PRE_DDL'" size="small" color="primary">{{ $t('gov-v2-type-pre-ddl') }}</Tag>
              </div>
              <div class="gov-source-ticket-row__status">
                <Tag v-if="t.available" color="success">{{ $t('gov-release-source-ticket-available') }}</Tag>
                <Tag v-else color="error">{{ $t('gov-release-source-ticket-unavailable') }}</Tag>
                <span v-if="!t.available && t.unavailableReason" class="gov-source-ticket-row__reason">{{ t.unavailableReason }}</span>
              </div>
            </div>
          </div>
        </section>

        <section v-if="detail.stmtGroups && detail.stmtGroups.length" class="page-section">
          <div class="page-section__title">{{ $t('gov-release-stmt-groups') }}</div>
          <div class="gov-stmt-groups">
            <div v-for="group in detail.stmtGroups" :key="`${group.prodDsId}-${group.prodDbName}`" class="gov-stmt-group">
              <div class="gov-stmt-group__header" @click="toggleGroup(group)">
                <div class="gov-stmt-group__db">
                  <Icon type="ios-server-outline" />
                  <span>{{ group.prodDbName }}</span>
                </div>
                <div class="gov-stmt-group__meta">
                  <span class="gov-stmt-group__ds-id">{{ $t('gov-release-ds-label') }}{{ group.prodDsId }}</span>
                  <span class="gov-stmt-group__count">{{ group.stmts.length }}</span>
                  <Icon class="gov-stmt-group__arrow" :type="isGroupCollapsed(group) ? 'ios-arrow-down' : 'ios-arrow-up'" />
                </div>
              </div>
              <div v-show="!isGroupCollapsed(group)" class="gov-stmt-group__body">
                <div v-for="stmt in group.stmts" :key="stmt.id" class="gov-stmt-card">
                  <div class="gov-stmt-card__header">
                    <span class="gov-stmt-card__seq">{{ $t('gov-release-seq-label') }}{{ stmt.seq }}</span>
                    <div class="gov-stmt-card__type-status">
                      <Tag v-if="stmt.sourceTicketType === 'PROD_DML'" size="small" color="warning">{{ $t('gov-v2-type-prod-dml') }}</Tag>
                      <Tag v-else-if="stmt.sourceTicketType === 'PRE_DDL'" size="small" color="primary">{{ $t('gov-v2-type-pre-ddl') }}</Tag>
                      <span :class="['gov-stmt-card__status', `is-${stmt.execStatus}`]">{{ stmt.execStatus }}</span>
                    </div>
                  </div>
                  <div class="gov-stmt-card__sql">
                    <read-only-editor :text="stmt.sqlContent" :max-height="200" />
                  </div>
                  <!-- ADR-013 M3 冻结计划复核面（design D10）：有 manifest 才渲染；null-plan 存量语句保持原形态 -->
                  <div v-if="stmt.segments && stmt.segments.length" class="gov-stmt-card__plan">
                    <div class="gov-plan-title">{{ $t('gov-release-frozen-plan-title') }}（{{ stmt.segments.length }}）</div>
                    <Alert v-if="hasDmlBatchSegment(stmt)" type="warning" show-icon class="gov-plan-banner">
                      {{ $t('gov-release-dml-batch-block-banner') }}
                    </Alert>
                    <div class="gov-plan-row gov-plan-row--head">
                      <span class="gov-plan-col gov-plan-col--seg">{{ $t('gov-release-plan-col-seg') }}</span>
                      <span class="gov-plan-col gov-plan-col--type">{{ $t('gov-release-plan-col-type') }}</span>
                      <span class="gov-plan-col gov-plan-col--stmts">{{ $t('gov-release-plan-col-stmts') }}</span>
                      <span class="gov-plan-col gov-plan-col--table">{{ $t('gov-release-plan-col-table') }}</span>
                      <span class="gov-plan-col gov-plan-col--rows">{{ $t('gov-release-plan-col-rows') }}</span>
                      <span class="gov-plan-col gov-plan-col--key">{{ $t('gov-release-plan-col-batch-key') }}</span>
                      <span class="gov-plan-col gov-plan-col--status">{{ $t('gov-release-plan-col-status') }}</span>
                    </div>
                    <div v-for="s in stmt.segments" :key="s.segIdx" class="gov-plan-row">
                      <span class="gov-plan-col gov-plan-col--seg gov-mono">{{ s.segIdx }}</span>
                      <span class="gov-plan-col gov-plan-col--type">
                        <Tag :color="segmentTypeColor(s.type)" size="small">{{ s.type }}</Tag>
                      </span>
                      <span class="gov-plan-col gov-plan-col--stmts gov-mono">{{ segmentStmtsText(s) }}</span>
                      <span class="gov-plan-col gov-plan-col--table" :title="s.tableName">{{ s.tableName || '-' }}</span>
                      <span class="gov-plan-col gov-plan-col--rows gov-mono">{{ s.rowsEstimated != null ? s.rowsEstimated : '-' }}</span>
                      <span class="gov-plan-col gov-plan-col--key gov-mono">{{ batchKeyText(s) }}</span>
                      <span class="gov-plan-col gov-plan-col--status">
                        <Tag :color="segmentStatusColor(s.status)" size="small" :title="s.error || ''">{{ s.status }}</Tag>
                      </span>
                    </div>
                  </div>
                  <div class="gov-stmt-card__meta">
                    <span class="gov-mono">{{ $t('gov-release-hash') }}: {{ shortHash(stmt.hash) }}</span>
                    <span v-if="stmt.planHash" class="gov-mono">{{ $t('gov-release-plan-hash') }}: {{ shortHash(stmt.planHash) }}</span>
                    <span v-if="stmt.execDetail" class="gov-stmt-card__detail">{{ stmt.execDetail }}</span>
                  </div>
                  <div class="gov-stmt-card__actions">
                    <Button
                      v-if="stmt.execStatus === 'FAILED' && canRetry"
                      type="primary"
                      size="small"
                      :loading="retrying"
                      @click="handleRetryStmt(stmt.id)"
                    >
                      {{ $t('gov-v2-retry') }}
                    </Button>
                    <Button v-if="stmt.execStatus === 'FAILED' && canRetry" size="small" :loading="returning" @click="openReturnStmtModal(stmt.id)">
                      {{ $t('gov-release-return-stmt') }}
                    </Button>
                    <!-- ADR-011 门禁 Override：仅 FAILED 语句 + GOV_SCHEMA_GATE_OVERRIDE 标签持有者可见（端点同标签强制） -->
                    <Button v-if="stmt.execStatus === 'FAILED' && canOverride" size="small" @click="openOverrideModal(stmt)">
                      {{ $t('gov-release-override-button') }}
                    </Button>
                    <!-- ADR-013 M3 路由 Override：仅 FAILED 且带冻结计划的语句 + GOV_ROUTE_OVERRIDE 标签持有者可见（端点同标签强制；与门禁 Override 完全隔离） -->
                    <Button
                      v-if="stmt.execStatus === 'FAILED' && canRouteOverride && stmt.segments && stmt.segments.length"
                      size="small"
                      @click="openRouteOverrideModal(stmt)"
                    >
                      {{ $t('gov-release-route-override-button') }}
                    </Button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </section>

        <!-- ADR-011 门禁 Override 记录：标签持有者可见（列表端点同标签强制），状态 未消费/已消费/已过期/已撤回 -->
        <section v-if="canOverride" class="page-section">
          <div class="page-section__title">{{ $t('gov-release-override-list-title') }}</div>
          <div v-if="!overrides.length" class="gov-override-empty">{{ $t('gov-release-override-empty') }}</div>
          <div v-else class="gov-override-list">
            <div class="gov-override-row gov-override-row--head">
              <span class="gov-override-col gov-override-col--gate">{{ $t('gov-release-override-col-gate') }}</span>
              <span class="gov-override-col gov-override-col--scope">{{ $t('gov-release-override-col-scope') }}</span>
              <span class="gov-override-col gov-override-col--reason">{{ $t('gov-release-override-col-reason') }}</span>
              <span class="gov-override-col gov-override-col--operator">{{ $t('gov-release-override-col-operator') }}</span>
              <span class="gov-override-col gov-override-col--expire">{{ $t('gov-release-override-col-expire') }}</span>
              <span class="gov-override-col gov-override-col--status">{{ $t('gov-release-override-col-status') }}</span>
              <span class="gov-override-col gov-override-col--action">{{ $t('gov-release-override-col-action') }}</span>
            </div>
            <div v-for="row in overrides" :key="row.id" class="gov-override-row">
              <span class="gov-override-col gov-override-col--gate gov-mono">{{ row.gateType }}</span>
              <span class="gov-override-col gov-override-col--scope">{{ overrideScopeText(row) }}</span>
              <span class="gov-override-col gov-override-col--reason" :title="row.reason">{{ row.reason }}</span>
              <span class="gov-override-col gov-override-col--operator">{{ row.operatorUid }}</span>
              <span class="gov-override-col gov-override-col--expire">{{ formatDateTime(row.expireAt) }}</span>
              <span class="gov-override-col gov-override-col--status">
                <Tag :color="overrideStatusColor(row.status)" size="small">{{ $t(`gov-release-override-status-${row.status}`) }}</Tag>
              </span>
              <span class="gov-override-col gov-override-col--action">
                <Button v-if="row.status === 'ACTIVE'" type="text" size="small" @click="handleRevokeOverride(row)">
                  {{ $t('gov-release-override-revoke-button') }}
                </Button>
              </span>
            </div>
          </div>
        </section>

        <!-- ADR-013 M3 路由 Override 记录：GOV_ROUTE_OVERRIDE 标签持有者可见（列表端点同标签强制），状态 待消费/已消费/已过期；与门禁 Override 记录完全隔离 -->
        <section v-if="canRouteOverride" class="page-section">
          <div class="page-section__title">{{ $t('gov-release-route-override-list-title') }}</div>
          <div v-if="!routeOverrides.length" class="gov-override-empty">{{ $t('gov-release-route-override-empty') }}</div>
          <div v-else class="gov-override-list">
            <div class="gov-override-row gov-override-row--head">
              <span class="gov-override-col gov-override-col--scope">{{ $t('gov-release-route-override-col-scope') }}</span>
              <span class="gov-override-col gov-override-col--reason">{{ $t('gov-release-route-override-col-reason') }}</span>
              <span class="gov-override-col gov-override-col--operator">{{ $t('gov-release-route-override-col-creator') }}</span>
              <span class="gov-override-col gov-override-col--expire">{{ $t('gov-release-route-override-col-expire') }}</span>
              <span class="gov-override-col gov-override-col--status">{{ $t('gov-release-route-override-col-status') }}</span>
              <span class="gov-override-col gov-override-col--consumed">{{ $t('gov-release-route-override-col-consumed') }}</span>
            </div>
            <div v-for="row in routeOverrides" :key="row.id" class="gov-override-row">
              <span class="gov-override-col gov-override-col--scope">{{ overrideScopeText(row) }}</span>
              <span class="gov-override-col gov-override-col--reason" :title="row.reason">{{ row.reason }}</span>
              <span class="gov-override-col gov-override-col--operator">{{ row.createdBy }}</span>
              <span class="gov-override-col gov-override-col--expire">{{ formatDateTime(row.expireAt) }}</span>
              <span class="gov-override-col gov-override-col--status">
                <Tag :color="routeOverrideStatusColor(row.status)" size="small">{{ $t(`gov-release-route-override-status-${row.status}`) }}</Tag>
              </span>
              <span class="gov-override-col gov-override-col--consumed gov-mono">{{ routeOverrideConsumedText(row) }}</span>
            </div>
          </div>
        </section>

        <section v-if="detail.events && detail.events.length" class="page-section">
          <div class="page-section__title">{{ $t('gov-event-timeline') }}</div>
          <div class="gov-event-timeline-list">
            <div v-for="(evt, idx) in detail.events" :key="idx" class="gov-event-timeline-row">
              <div class="gov-event-timeline-row__icon">
                <Icon :type="govEventIcon(evt.eventType)" />
              </div>
              <div class="gov-event-timeline-row__body">
                <div class="gov-event-timeline-row__header">
                  <strong>{{ govEventTypeText(evt.eventType) }}</strong>
                  <span v-if="evt.fromStatus || evt.toStatus" class="gov-event-timeline-row__transition">
                    {{ evt.fromStatus || '-' }}{{ $t('gov-arrow') }}{{ evt.toStatus || '-' }}
                  </span>
                  <span class="gov-event-timeline-row__time">{{ formatDateTime(evt.gmtCreate) }}</span>
                </div>
                <div v-if="evt.operatorUid" class="gov-event-timeline-row__operator">{{ $t('gov-event-operator') }}: {{ evt.operatorUid }}</div>
              </div>
            </div>
          </div>
        </section>
      </template>
      <div v-else class="gov-detail-empty">
        {{ $t('gov-release-not-found') }}
      </div>
    </div>
    <CCModal
      v-model="showReturnStmtModal"
      :title="$t('gov-release-return-stmt')"
      @on-ok="handleReturnStmtSubmit"
      @on-cancel="
        () => {
          showReturnStmtModal = false;
          returnStmtReason = '';
          returnStmtId = null;
        }
      "
    >
      <Input v-model="returnStmtReason" type="textarea" :rows="3" :placeholder="$t('gov-v2-return-reason-placeholder')" />
    </CCModal>

    <CCModal v-model="showReturnModal" :title="$t('gov-release-return-button')" :footer-hide="true">
      <div class="gov-release-return-form">
        <div class="gov-release-return-form__label">{{ $t('gov-release-return-reason-label') }}</div>
        <Input
          v-model="returnReason"
          type="textarea"
          :rows="4"
          :maxlength="1000"
          show-word-limit
          :placeholder="$t('gov-release-return-reason-placeholder')"
        />
      </div>
      <template #footer>
        <Button @click="showReturnModal = false">{{ $t('qu-xiao') }}</Button>
        <Button type="primary" :loading="returning" @click="handleReturnSubmit">{{ $t('que-ren') }}</Button>
      </template>
    </CCModal>

    <CCModal v-model="showResubmitModal" :title="$t('gov-release-resubmit-button')" :footer-hide="true" width="680">
      <div class="gov-resubmit-form">
        <div class="gov-resubmit-form__title-row">
          <span class="gov-resubmit-form__label">{{ $t('gov-release-title') }}</span>
          <Input v-model="resubmitTitle" :placeholder="$t('gov-ledger-title-placeholder')" maxlength="255" />
        </div>
        <div class="gov-resubmit-form__label">{{ $t('gov-release-resubmit-exclude-hint') }}</div>
        <div class="gov-resubmit-tickets">
          <div v-for="t in resubmitSourceTickets" :key="t.ticketId" class="gov-resubmit-ticket-row" :class="{ 'is-disabled': !t.available }">
            <Checkbox v-model="t.checked" :disabled="!t.available">
              <span class="gov-resubmit-ticket-row__id">{{ t.ticketId }}</span>
              <span class="gov-resubmit-ticket-row__title">{{ t.ticketTitle }}</span>
              <Tag v-if="t.ticketType === 'PROD_DML'" size="small" color="warning">{{ $t('gov-v2-type-prod-dml') }}</Tag>
              <Tag v-else-if="t.ticketType === 'PRE_DDL'" size="small" color="primary">{{ $t('gov-v2-type-pre-ddl') }}</Tag>
            </Checkbox>
            <div class="gov-resubmit-ticket-row__status">
              <Tag v-if="t.available" color="success" size="small">{{ $t('gov-release-source-ticket-available') }}</Tag>
              <template v-else>
                <Tag color="error" size="small">{{ $t('gov-release-source-ticket-unavailable') }}</Tag>
                <span v-if="t.unavailableReason" class="gov-resubmit-ticket-row__reason">{{ t.unavailableReason }}</span>
              </template>
            </div>
          </div>
        </div>
        <template v-if="resubmitCandidates.length">
          <div class="gov-resubmit-form__label">{{ $t('gov-release-resubmit-candidates-title') }}</div>
          <div class="gov-resubmit-tickets">
            <div v-for="t in resubmitCandidates" :key="t.ticketId" class="gov-resubmit-ticket-row">
              <Checkbox v-model="t.checked">
                <span class="gov-resubmit-ticket-row__id">{{ t.ticketId }}</span>
                <span class="gov-resubmit-ticket-row__title">{{ t.ticketTitle }}</span>
                <span class="gov-resubmit-ticket-row__service">{{ t.serviceName }}</span>
                <Tag v-if="t.ticketType === 'PROD_DML'" size="small" color="warning">{{ $t('gov-v2-type-prod-dml') }}</Tag>
                <Tag v-else-if="t.ticketType === 'PRE_DDL'" size="small" color="primary">{{ $t('gov-v2-type-pre-ddl') }}</Tag>
              </Checkbox>
            </div>
          </div>
        </template>
      </div>
      <template #footer>
        <Button @click="showResubmitModal = false">{{ $t('qu-xiao') }}</Button>
        <Button type="primary" :loading="resubmitting" @click="handleResubmitSubmit">{{ $t('que-ren') }}</Button>
      </template>
    </CCModal>

    <!-- ADR-011 门禁 Override 授予弹窗：理由必填 + 有效期 ≤7 天 + 门禁类型/范围（交互抄 returnStmtReason 先例） -->
    <CCModal v-model="showOverrideModal" :title="$t('gov-release-override-title')" :footer-hide="true" width="560">
      <div class="gov-override-form">
        <div class="gov-override-form__label">{{ $t('gov-release-override-gate-type') }}</div>
        <RadioGroup v-model="overrideForm.gateType">
          <Radio v-for="opt in gateTypeOptions" :key="opt.value" :label="opt.value">{{ opt.text }}</Radio>
        </RadioGroup>
        <div class="gov-override-form__label">{{ $t('gov-release-override-scope') }}</div>
        <RadioGroup v-model="overrideForm.scope">
          <Radio v-for="opt in scopeOptions" :key="opt.value" :label="opt.value" :disabled="opt.disabled">{{ opt.text }}</Radio>
        </RadioGroup>
        <div class="gov-override-form__label">{{ $t('gov-release-override-reason-label') }}</div>
        <Input
          v-model="overrideForm.reason"
          type="textarea"
          :rows="3"
          :maxlength="1024"
          show-word-limit
          :placeholder="$t('gov-release-override-reason-placeholder')"
        />
        <div class="gov-override-form__label">{{ $t('gov-release-override-expire-label') }}</div>
        <DatePicker v-model="overrideForm.expireAt" type="datetime" :options="overrideExpireOptions" />
        <div class="gov-override-form__hint">{{ $t('gov-release-override-expire-hint') }}</div>
      </div>
      <template #footer>
        <Button @click="showOverrideModal = false">{{ $t('qu-xiao') }}</Button>
        <Button type="primary" :loading="grantingOverride" @click="handleOverrideGrantSubmit">{{ $t('que-ren') }}</Button>
      </template>
    </CCModal>

    <!-- ADR-013 M3 路由 Override 授予弹窗：理由必填 + 有效期 ≤7 天（粒度固定 (releaseId, stmtId)，无门禁类型/范围选择；风险提示前置） -->
    <CCModal v-model="showRouteOverrideModal" :title="$t('gov-release-route-override-title')" :footer-hide="true" width="560">
      <div class="gov-override-form">
        <Alert type="warning" show-icon class="gov-route-override-risk">{{ $t('gov-release-route-override-risk-hint') }}</Alert>
        <div class="gov-override-form__label">{{ $t('gov-release-route-override-reason-label') }}</div>
        <Input
          v-model="routeOverrideForm.reason"
          type="textarea"
          :rows="3"
          :maxlength="1024"
          show-word-limit
          :placeholder="$t('gov-release-route-override-reason-placeholder')"
        />
        <div class="gov-override-form__label">{{ $t('gov-release-route-override-expire-label') }}</div>
        <DatePicker v-model="routeOverrideForm.expireAt" type="datetime" :options="overrideExpireOptions" />
        <div class="gov-override-form__hint">{{ $t('gov-release-route-override-expire-hint') }}</div>
      </div>
      <template #footer>
        <Button @click="showRouteOverrideModal = false">{{ $t('qu-xiao') }}</Button>
        <Button type="primary" :loading="grantingRouteOverride" @click="handleRouteOverrideGrantSubmit">{{ $t('que-ren') }}</Button>
      </template>
    </CCModal>
  </div>
</template>

<script>
import { mapState } from 'vuex';
import ReadOnlyEditor from '@/components/editor/ReadOnlyEditor';
import DingBridgeOutboundPanel from '@/components/function/DingBridgeOutboundPanel.vue';
import formatError from '@/services/formatError';
import { GOV_EVENT_TYPE_I18N_KEYS, GOV_EVENT_ICONS, RELEASE_STATUS_I18N_KEYS } from './govEventConstants';

export default {
  name: 'DbChangeReleaseDetail',
  components: { ReadOnlyEditor, DingBridgeOutboundPanel },
  data() {
    return {
      releaseId: null,
      detail: null,
      loading: false,
      retrying: false,
      returning: false,
      collapsedGroups: {},
      showReturnStmtModal: false,
      returnStmtReason: '',
      returnStmtId: null,
      showReturnModal: false,
      returnReason: '',
      showResubmitModal: false,
      resubmitTitle: '',
      resubmitSourceTickets: [],
      resubmitCandidates: [],
      resubmitCandidatesLoading: false,
      resubmitting: false,
      cancelling: false,
      // 钉钉审批桥接(task 10-09):发布单审批工单的 base info(含 outbound 字段)+ 重发 in-flight
      approvalBaseInfo: null,
      bridgeReissuing: false,
      // ADR-011 门禁 Override：记录列表 + 授予弹窗状态（一次性消费、理由必填、有效期 ≤7 天）
      overrides: [],
      showOverrideModal: false,
      grantingOverride: false,
      overrideForm: {
        gateType: 'PRECHECK',
        scope: 'STMT',
        stmtId: null,
        reason: '',
        expireAt: null
      },
      // ADR-013 M3 路由 Override：与门禁 Override 完全隔离（两标签两端点两列表），粒度固定单语句
      routeOverrides: [],
      showRouteOverrideModal: false,
      grantingRouteOverride: false,
      routeOverrideForm: {
        stmtId: null,
        reason: '',
        expireAt: null
      },
      overrideExpireOptions: {
        disabledDate(date) {
          if (!date) return false;
          const now = Date.now();
          return date.getTime() < now - 86400000 || date.getTime() > now + 7 * 86400000;
        }
      }
    };
  },
  computed: {
    ...mapState(['userInfo']),
    myAuth() {
      return this.$store.state.myAuth || [];
    },
    canRetry() {
      return this.myAuth.includes('RDP_WORKER_ORDER_EXECUTE');
    },
    // ADR-011 门禁 Override 权限门控（镜像后端 @RequestAuth(GOV_SCHEMA_GATE_OVERRIDE) 三端点）
    canOverride() {
      return this.myAuth.includes('GOV_SCHEMA_GATE_OVERRIDE');
    },
    // ADR-013 M3 路由 Override 权限门控（镜像后端 @RequestAuth(GOV_ROUTE_OVERRIDE) 两端点；与门禁 Override 标签互不代换）
    canRouteOverride() {
      return this.myAuth.includes('GOV_ROUTE_OVERRIDE');
    },
    gateTypeOptions() {
      return [
        { value: 'PRECHECK', text: this.$t('gov-release-override-gate-PRECHECK') },
        { value: 'DIFF', text: this.$t('gov-release-override-gate-DIFF') }
      ];
    },
    scopeOptions() {
      return [
        { value: 'STMT', text: this.$t('gov-release-override-scope-stmt'), disabled: !this.overrideForm.stmtId },
        { value: 'RELEASE', text: this.$t('gov-release-override-scope-release'), disabled: false }
      ];
    },
    // ---- 钉钉审批桥接出站面板(task 10-09,design §3.1/§4) ----
    // 适用性判定:审批工单为 External 通道才呈现(其余通道 outbound 列恒 NULL)
    showBridgeOutbound() {
      return this.approvalBaseInfo?.approType === 'External';
    },
    // 权限镜像 reissueFlow 端点注解(HIGH + REQUEST/APPROVE 任一);状态守卫由面板内部置灰呈现
    canReissueFlow() {
      return this.myAuth.includes('RDP_WORKER_ORDER_REQUEST') || this.myAuth.includes('RDP_WORKER_ORDER_APPROVE');
    },
    isPrimaryOrMaintainer() {
      const info = this.userInfo || {};
      return info.accountType === 'PRIMARY_ACCOUNT' || info.isMaintainer === true;
    },
    canReturnRelease() {
      if (!this.detail) return false;
      return ['APPROVING', 'APPROVED'].includes(this.detail.status) && this.isPrimaryOrMaintainer;
    },
    canManageReturnedRelease() {
      if (!this.detail || this.detail.status !== 'RETURNED') return false;
      if (this.isPrimaryOrMaintainer) return true;
      return this.detail.creatorUid && this.userInfo?.uid === this.detail.creatorUid;
    },
    showReleaseActions() {
      return this.canReturnRelease || this.canManageReturnedRelease;
    },
    showGateResult() {
      if (!this.detail || !this.detail.gateResult) {
        return false;
      }
      return ['REJECTED', 'CANCELLED', 'RETURNED'].includes(this.detail.status);
    },
    // B6 M3/B7 N3 存量披露 + ADR-013 M3 收窄：黄旗只覆盖「曾路由但无冻结计划」的语句（存量
    // release 仍整段直发重放）；带 manifest 的语句由逐语句冻结计划区块按真实行为披露
    // （GHOST 段 gh-ost 重放 / DML_BATCH 段阻断），不再显示「重放为直接执行」的旧文案。
    // 判定：gate_result.stmts 快照（sourceStmtId + ghostRouted/dmlBatchRouted 标记）× 详情
    // 语句的 segments 有无（sourceStmtId 对齐）；旧发布单无提示键 → 不显示（容错解析）
    legacyRoutedHints() {
      const hints = { ghost: false, dmlBatch: false };
      if (!this.detail || !this.detail.gateResult) {
        return hints;
      }
      let gr;
      try {
        gr = JSON.parse(this.detail.gateResult);
      } catch (e) {
        return hints;
      }
      const snapshots = (gr && gr.stmts) || [];
      const hasPlan = {};
      for (const group of this.detail.stmtGroups || []) {
        for (const stmt of group.stmts || []) {
          hasPlan[stmt.sourceStmtId] = !!(stmt.segments && stmt.segments.length);
        }
      }
      for (const snap of snapshots) {
        if (!snap || hasPlan[snap.sourceStmtId]) {
          continue; // 带冻结计划的语句按计划区块披露，不适用存量黄旗
        }
        if (snap.ghostRouted) hints.ghost = true;
        if (snap.dmlBatchRouted) hints.dmlBatch = true;
      }
      return hints;
    },
    ghostReplayHint() {
      return this.legacyRoutedHints.ghost;
    },
    dmlBatchReplayHint() {
      return this.legacyRoutedHints.dmlBatch;
    }
  },
  mounted() {
    this.releaseId = this.$route.params.releaseId;
    if (this.releaseId) {
      this.loadDetail();
    }
  },
  methods: {
    handleBack() {
      this.$router.push('/dbChange/release');
    },
    async loadDetail() {
      this.loading = true;
      try {
        const res = await this.$services.govReleaseDetail({
          data: { releaseId: Number(this.releaseId) }
        });
        if (res.success) {
          this.detail = res.data;
          await this.loadApprovalOutbound();
          this.loadOverrides();
          this.loadRouteOverrides();
        }
      } finally {
        this.loading = false;
      }
    },
    // ADR-011 门禁 Override 记录——静默加载:无标签/请求失败均置空列表,不影响详情主体呈现
    async loadOverrides() {
      if (!this.canOverride) {
        this.overrides = [];
        return;
      }
      try {
        const res = await this.$services.govReleaseOverrideList({
          data: { releaseId: Number(this.releaseId) },
          modal: false
        });
        this.overrides = res.success && res.data ? res.data : [];
      } catch {
        this.overrides = [];
      }
    },
    openOverrideModal(stmt) {
      this.overrideForm = {
        gateType: 'PRECHECK',
        scope: 'STMT',
        stmtId: stmt ? stmt.id : null,
        reason: '',
        expireAt: new Date(Date.now() + 24 * 3600 * 1000)
      };
      this.showOverrideModal = true;
    },
    async handleOverrideGrantSubmit() {
      if (this.grantingOverride) {
        return;
      }
      const reason = (this.overrideForm.reason || '').trim();
      if (!reason) {
        this.$Message.warning(this.$t('gov-release-override-reason-required'));
        return;
      }
      const expireMs = this.overrideForm.expireAt ? new Date(this.overrideForm.expireAt).getTime() : NaN;
      const now = Date.now();
      if (isNaN(expireMs) || expireMs <= now || expireMs - now > 7 * 86400000) {
        this.$Message.warning(this.$t('gov-release-override-expire-invalid'));
        return;
      }
      this.grantingOverride = true;
      try {
        const data = {
          releaseId: Number(this.releaseId),
          gateType: this.overrideForm.gateType,
          reason,
          expireAt: expireMs
        };
        if (this.overrideForm.scope === 'STMT' && this.overrideForm.stmtId) {
          data.stmtId = this.overrideForm.stmtId;
        }
        const res = await this.$services.govReleaseOverrideGrant({ data });
        if (res.success) {
          this.$Message.success(this.$t('gov-release-override-grant-success'));
          this.showOverrideModal = false;
          await this.loadOverrides();
        } else {
          this.$Message.error(formatError(res.msg));
        }
      } finally {
        this.grantingOverride = false;
      }
    },
    handleRevokeOverride(row) {
      this.$Modal.confirm({
        title: this.$t('gov-release-override-revoke-button'),
        content: this.$t('gov-release-override-revoke-confirm'),
        onOk: async () => {
          const res = await this.$services.govReleaseOverrideRevoke({ data: { id: row.id } });
          if (res.success) {
            this.$Message.success(this.$t('gov-release-override-revoke-success'));
            await this.loadOverrides();
          } else {
            this.$Message.error(formatError(res.msg));
          }
        }
      });
    },
    overrideScopeText(row) {
      if (!row.stmtId) {
        return this.$t('gov-release-override-scope-release');
      }
      // row.stmtId 是数据库主键,语句卡片区展示的是 seq——映射为同口径;
      // 查不到(如语句已被 returnStmt 删除)回退显示主键
      for (const group of this.detail.stmtGroups) {
        for (const stmt of group.stmts) {
          if (stmt.id === row.stmtId) {
            return `${this.$t('gov-release-seq-label')}${stmt.seq}`;
          }
        }
      }
      return `${this.$t('gov-release-seq-label')}${row.stmtId}`;
    },
    overrideStatusColor(status) {
      if (status === 'ACTIVE') return 'primary';
      if (status === 'EXPIRED') return 'warning';
      return 'default';
    },
    // ---- ADR-013 M3 路由 Override（与门禁 Override 完全隔离：独立端点/列表/弹窗状态） ----
    // 静默加载:无标签/请求失败均置空列表,不影响详情主体呈现（loadOverrides 同款）
    async loadRouteOverrides() {
      if (!this.canRouteOverride) {
        this.routeOverrides = [];
        return;
      }
      try {
        const res = await this.$services.govReleaseRouteOverrideList({
          data: { releaseId: Number(this.releaseId) },
          modal: false
        });
        this.routeOverrides = res.success && res.data ? res.data : [];
      } catch {
        this.routeOverrides = [];
      }
    },
    openRouteOverrideModal(stmt) {
      this.routeOverrideForm = {
        stmtId: stmt ? stmt.id : null,
        reason: '',
        expireAt: new Date(Date.now() + 24 * 3600 * 1000)
      };
      this.showRouteOverrideModal = true;
    },
    async handleRouteOverrideGrantSubmit() {
      if (this.grantingRouteOverride) {
        return;
      }
      const reason = (this.routeOverrideForm.reason || '').trim();
      if (!reason) {
        this.$Message.warning(this.$t('gov-release-route-override-reason-required'));
        return;
      }
      const expireMs = this.routeOverrideForm.expireAt ? new Date(this.routeOverrideForm.expireAt).getTime() : NaN;
      const now = Date.now();
      if (isNaN(expireMs) || expireMs <= now || expireMs - now > 7 * 86400000) {
        this.$Message.warning(this.$t('gov-release-route-override-expire-invalid'));
        return;
      }
      this.grantingRouteOverride = true;
      try {
        const res = await this.$services.govReleaseRouteOverrideGrant({
          data: {
            releaseId: Number(this.releaseId),
            stmtId: this.routeOverrideForm.stmtId,
            reason,
            expireAt: expireMs
          }
        });
        if (res.success) {
          this.$Message.success(this.$t('gov-release-route-override-grant-success'));
          this.showRouteOverrideModal = false;
          await this.loadRouteOverrides();
        } else {
          this.$Message.error(formatError(res.msg));
        }
      } finally {
        this.grantingRouteOverride = false;
      }
    },
    routeOverrideStatusColor(status) {
      if (status === 'PENDING') return 'primary';
      if (status === 'EXPIRED') return 'warning';
      return 'default';
    },
    routeOverrideConsumedText(row) {
      if (row.status !== 'CONSUMED') {
        return '-';
      }
      return `#${row.coveredSegIdxs || '-'} · ${this.formatDateTime(row.consumedAt)}`;
    },
    // ---- ADR-013 M3 冻结计划区块渲染（读 manifest,plan+runtime 双面） ----
    hasDmlBatchSegment(stmt) {
      return (stmt.segments || []).some((s) => s.type === 'DML_BATCH');
    },
    segmentTypeColor(type) {
      if (type === 'GHOST') return 'primary';
      if (type === 'DML_BATCH') return 'warning';
      return 'default';
    },
    segmentStatusColor(status) {
      if (status === 'SUCCESS') return 'success';
      if (status === 'FAILED') return 'error';
      if (status === 'EXECUTING') return 'primary';
      return 'default';
    },
    segmentStmtsText(s) {
      if (s.stmtRangeFrom != null && s.stmtRangeTo != null) {
        return `${s.stmtRangeFrom}-${s.stmtRangeTo}`;
      }
      if (s.stmtIndex != null) {
        return `#${s.stmtIndex}`;
      }
      return '-';
    },
    batchKeyText(s) {
      if (!s.batchKeyColumn) {
        return '-';
      }
      return `${s.batchKeyColumn}(${s.batchKeyType || '?'})`;
    },
    // 审批工单 base info(含 outbound 状态)——静默加载:无 approvalId / 无权限 / 请求失败
    // 均置 null,面板隐藏,不影响发布单详情主体呈现
    async loadApprovalOutbound() {
      const approvalId = this.detail?.approvalId;
      if (!approvalId) {
        this.approvalBaseInfo = null;
        return;
      }
      try {
        const res = await this.$services.rdpTicketQueryTicketBaseInfo({
          data: { ticketId: approvalId, refreshCache: false },
          modal: false
        });
        this.approvalBaseInfo = res.success && res.data ? res.data : null;
      } catch {
        this.approvalBaseInfo = null;
      }
    },
    // 手动重发钉钉桥接出站发起(task 10-09,design §4):后端同步语义——成功即 STARTED;
    // 五类拒绝由后端 i18n 错误经标准错误弹窗直达操作者,前端不重复映射文案
    async handleReissueFlow() {
      const approvalId = this.detail?.approvalId;
      if (this.bridgeReissuing || !approvalId) {
        return;
      }
      this.bridgeReissuing = true;
      try {
        const res = await this.$services.dmTicketReissueFlow({ data: { ticketId: approvalId } });
        if (res.success) {
          this.$Message.success(this.$t('ding-qiao-chong-xin-fa-qi-cheng-gong'));
          await this.loadApprovalOutbound();
        }
      } finally {
        this.bridgeReissuing = false;
      }
    },
    goToTicket(ticketId) {
      if (ticketId) {
        this.$router.push(`/ticket/${ticketId}`);
      }
    },
    isGroupCollapsed(group) {
      return !!this.collapsedGroups[`${group.prodDsId}|${group.prodDbName}`];
    },
    toggleGroup(group) {
      const key = `${group.prodDsId}|${group.prodDbName}`;
      this.collapsedGroups = { ...this.collapsedGroups, [key]: !this.collapsedGroups[key] };
    },
    async handleRetryStmt(stmtId) {
      if (this.retrying) {
        return;
      }
      this.retrying = true;
      try {
        const res = await this.$services.govReleaseRetryStmt({
          data: { stmtId }
        });
        if (res.success) {
          this.$Message.success(this.$t('gov-v2-retry-success'));
          await this.loadDetail();
        }
      } finally {
        this.retrying = false;
      }
    },
    openReturnStmtModal(stmtId) {
      this.returnStmtId = stmtId;
      this.returnStmtReason = '';
      this.showReturnStmtModal = true;
    },
    async handleReturnStmtSubmit() {
      const stmtId = this.returnStmtId;
      if (this.returning || !stmtId) {
        return;
      }
      this.returning = true;
      try {
        const res = await this.$services.govReleaseReturnStmt({
          data: { stmtId, reason: this.returnStmtReason || undefined }
        });
        if (res.success) {
          this.$Message.success(this.$t('gov-release-return-success'));
          this.showReturnStmtModal = false;
          this.returnStmtReason = '';
          this.returnStmtId = null;
          await this.loadDetail();
        } else {
          this.$Message.error(formatError(res.msg));
        }
      } finally {
        this.returning = false;
      }
    },
    openReturnModal() {
      this.returnReason = '';
      this.showReturnModal = true;
    },
    async handleReturnSubmit() {
      if (this.returning) {
        return;
      }
      const reason = (this.returnReason || '').trim();
      if (!reason) {
        this.$Message.warning(this.$t('gov-release-return-reason-required'));
        return;
      }
      this.returning = true;
      try {
        const res = await this.$services.govReleaseReturn({
          data: { releaseId: Number(this.releaseId), reason }
        });
        if (res.success) {
          this.$Message.success(this.$t('gov-release-return-release-success'));
          this.showReturnModal = false;
          this.returnReason = '';
          await this.loadDetail();
        } else {
          this.$Message.error(formatError(res.msg));
        }
      } finally {
        this.returning = false;
      }
    },
    async openResubmitModal() {
      this.resubmitTitle = this.detail.title || '';
      this.resubmitSourceTickets = (this.detail.sourceTickets || []).map((t) => ({
        ticketId: t.ticketId,
        ticketTitle: t.ticketTitle,
        ticketStatus: t.ticketStatus,
        ticketType: t.ticketType,
        available: t.available,
        unavailableReason: t.unavailableReason,
        checked: true
      }));
      this.resubmitCandidates = [];
      this.resubmitCandidatesLoading = true;
      this.showResubmitModal = true;
      // Fetch addable tickets from the ledger (NOT_PROMOTED, not already in original list).
      // Failure degrades silently — modal still usable for original-list removal only.
      const originalIds = new Set(this.resubmitSourceTickets.map((t) => t.ticketId));
      try {
        const res = await this.$services.govLedgerList({ data: {} });
        if (res.success && res.data) {
          this.resubmitCandidates = res.data
            .filter((t) => !t.promoted && !originalIds.has(t.ticketId))
            .map((t) => ({
              ticketId: t.ticketId,
              ticketTitle: t.ticketTitle,
              serviceName: t.serviceName,
              ticketType: t.ticketType,
              checked: false
            }));
        }
      } catch {
        // Degrade: no addable candidates, modal remains usable for original-list removal
      } finally {
        this.resubmitCandidatesLoading = false;
      }
    },
    async handleResubmitSubmit() {
      if (this.resubmitting) {
        return;
      }
      const originalChecked = this.resubmitSourceTickets.filter((t) => t.available && t.checked).map((t) => t.ticketId);
      const candidateChecked = this.resubmitCandidates.filter((t) => t.checked).map((t) => t.ticketId);
      const ticketIds = [...new Set([...originalChecked, ...candidateChecked])];
      if (ticketIds.length === 0) {
        this.$Message.warning(this.$t('gov-release-resubmit-keep-at-least-one'));
        return;
      }
      this.resubmitting = true;
      try {
        const res = await this.$services.govReleaseResubmit({
          data: {
            releaseId: Number(this.releaseId),
            ticketIds,
            title: this.resubmitTitle || undefined
          }
        });
        if (res.success) {
          this.$Message.success(this.$t('gov-release-resubmit-success'));
          this.showResubmitModal = false;
          await this.loadDetail();
        } else {
          this.$Message.error(formatError(res.msg));
        }
      } finally {
        this.resubmitting = false;
      }
    },
    handleCancel() {
      if (this.cancelling) {
        return;
      }
      this.$Modal.confirm({
        title: this.$t('gov-release-cancel-button'),
        content: this.$t('gov-release-cancel-confirm'),
        onOk: async () => {
          this.cancelling = true;
          try {
            const res = await this.$services.govReleaseCancel({
              data: { releaseId: Number(this.releaseId) }
            });
            if (res.success) {
              this.$Message.success(this.$t('gov-release-cancel-success'));
              await this.loadDetail();
            } else {
              this.$Message.error(formatError(res.msg));
            }
          } finally {
            this.cancelling = false;
          }
        }
      });
    },
    govEventTypeText(eventType) {
      const key = GOV_EVENT_TYPE_I18N_KEYS[eventType];
      return key ? this.$t(key) : eventType;
    },
    govEventIcon(eventType) {
      return GOV_EVENT_ICONS[eventType] || 'ios-information-circle-outline';
    },
    releaseStatusText(status) {
      const key = RELEASE_STATUS_I18N_KEYS[status];
      return key ? this.$t(key) : status || '-';
    },
    statusColor(status) {
      if (status === 'DONE') return 'success';
      if (status === 'PARTIAL_FAILED') return 'warning';
      if (status === 'REJECTED' || status === 'CANCELLED') return 'error';
      if (status === 'RETURNED') return 'warning';
      return 'primary';
    },
    shortHash(hash) {
      if (!hash) return '-';
      return hash.substring(0, 8);
    },
    formatDateTime(dt) {
      if (!dt) return '-';
      const ts = typeof dt === 'number' ? dt : Date.parse(dt);
      if (isNaN(ts)) return '-';
      const d = new Date(ts);
      const pad = (n) => String(n).padStart(2, '0');
      return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
    },
    formatJson(str) {
      if (!str) return '-';
      if (typeof str !== 'string') return JSON.stringify(str, null, 2);
      try {
        return JSON.stringify(JSON.parse(str), null, 2);
      } catch {
        return str;
      }
    }
  }
};
</script>

<style lang="less">
.db-change-release-detail {
  .release-detail-back {
    margin-bottom: 8px;
  }

  .gov-ghost-replay-hint {
    margin-bottom: 24px;
  }

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

  .gov-overview-item--full {
    grid-column: 1 / -1;
    flex-direction: column;
    align-items: flex-start;
    gap: 4px;
  }

  .gov-release-return-reason-text {
    font-size: 14px;
    line-height: 1.5;
    word-break: break-all;
  }

  .gov-release-actions {
    display: flex;
    gap: 12px;
    flex-wrap: wrap;
  }

  .gov-source-tickets,
  .gov-resubmit-tickets {
    display: flex;
    flex-direction: column;
    gap: 0;
  }

  .gov-source-ticket-row,
  .gov-resubmit-ticket-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 8px 0;
    border-bottom: 1px solid #f0f0f0;
    gap: 12px;

    &:last-child {
      border-bottom: none;
    }

    &__info {
      display: flex;
      align-items: center;
      gap: 8px;
      min-width: 0;
      flex: 1;
    }

    &__id {
      font-size: 13px;
      font-family: Menlo, Monaco, 'Courier New', monospace;
      color: var(--muted, #41454d);
      flex-shrink: 0;
    }

    &__title {
      font-size: 14px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    &__service {
      font-size: 13px;
      color: var(--muted, #41454d);
      margin-left: 4px;
    }

    &__status {
      display: flex;
      align-items: center;
      gap: 8px;
      flex-shrink: 0;
    }

    &__reason {
      font-size: 13px;
      color: #ed4014;
    }

    &.is-disabled {
      opacity: 0.6;
    }
  }

  .gov-release-return-form {
    display: flex;
    flex-direction: column;
    gap: 8px;

    &__label {
      font-size: 14px;
      color: var(--muted, #41454d);
    }
  }

  // ADR-011 门禁 Override:列表行沿用少线框扁平行样式(同 gov-source-ticket-row),零新色值
  .gov-override-empty {
    font-size: 13px;
    color: var(--muted, #41454d);
    background: var(--surface-soft, #f8fafc);
    padding: 12px 16px;
    border-radius: 6px;
  }

  .gov-override-list {
    display: flex;
    flex-direction: column;
  }

  .gov-override-row {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 8px 0;
    border-bottom: 1px solid #f0f0f0;
    font-size: 13px;

    &:last-child {
      border-bottom: none;
    }

    &--head {
      color: var(--muted, #41454d);
    }
  }

  .gov-override-col {
    min-width: 0;

    &--gate {
      flex: 0 0 100px;
    }
    &--scope {
      flex: 0 0 90px;
    }
    &--reason {
      flex: 1;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    &--operator {
      flex: 0 0 120px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    &--expire {
      flex: 0 0 150px;
    }
    &--status {
      flex: 0 0 80px;
    }
    &--action {
      flex: 0 0 60px;
      text-align: right;
    }
    // ADR-013 M3 路由 Override 记录的「消费（段号）」列（比操作列宽,容纳 段号+时间）
    &--consumed {
      flex: 0 0 180px;
      text-align: right;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .gov-override-form {
    display: flex;
    flex-direction: column;
    gap: 8px;

    &__label {
      font-size: 14px;
      color: var(--muted, #41454d);
    }

    &__hint {
      font-size: 12px;
      color: var(--muted, #41454d);
    }
  }

  .gov-resubmit-form {
    display: flex;
    flex-direction: column;
    gap: 16px;

    &__title-row {
      display: flex;
      align-items: center;
      gap: 12px;
    }

    &__label {
      font-size: 14px;
      color: var(--muted, #41454d);
      flex-shrink: 0;
    }
  }

  .gov-mono {
    font-family: Menlo, Monaco, 'Courier New', monospace;
    font-size: 13px;
    word-break: break-all;
  }

  .gov-gate-result-json {
    font-family: Menlo, Monaco, 'Courier New', monospace;
    font-size: 12px;
    white-space: pre-wrap;
    word-break: break-all;
    max-height: 300px;
    overflow-y: auto;
    margin: 0;
    background: var(--surface-soft, #f8fafc);
    padding: 12px 16px;
    border-radius: 10px;
  }

  .gov-stmt-groups {
    display: flex;
    flex-direction: column;
    gap: 24px;
  }

  .gov-stmt-group {
    &__header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 8px 16px;
      background: var(--surface-soft, #f8fafc);
      border-radius: 6px;
      margin-bottom: 12px;
      cursor: pointer;
    }

    &__db {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 14px;
      font-weight: 500;
      color: #181d26;
    }

    &__meta {
      display: flex;
      align-items: center;
      gap: 12px;
    }

    &__ds-id {
      font-size: 13px;
      color: var(--muted, #41454d);
      font-family: Menlo, Monaco, 'Courier New', monospace;
    }

    &__count {
      font-size: 13px;
      color: var(--muted, #41454d);
    }

    &__arrow {
      font-size: 14px;
      color: var(--muted, #41454d);
    }

    &__body {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
  }

  .gov-stmt-card {
    border: 1px solid #eaeaea;
    border-radius: 6px;
    overflow: hidden;

    &__header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 6px 16px;
      background: #fafafa;
    }

    &__seq {
      font-size: 14px;
      font-weight: 600;
      color: #181d26;
    }

    &__type-status {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    &__status {
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

    &__sql {
      padding: 12px 16px;
    }

    &__meta {
      display: flex;
      gap: 16px;
      font-size: 13px;
      padding: 0 16px 8px;
      flex-wrap: wrap;
    }

    &__detail {
      color: #ed4014;
    }

    &__actions {
      display: flex;
      gap: 8px;
      padding: 0 16px 12px;
    }

    // ADR-013 M3 冻结计划区块:浅底分区(少线框,与 gov-overview-grid 同款),零新色值
    &__plan {
      margin: 0 16px 12px;
      background: var(--surface-soft, #f8fafc);
      border-radius: 6px;
      padding: 12px 16px;
    }
  }

  .gov-plan-title {
    font-size: 13px;
    font-weight: 500;
    color: #181d26;
    margin-bottom: 8px;
  }

  .gov-plan-banner {
    margin-bottom: 8px;
  }

  .gov-plan-row {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 6px 0;
    border-bottom: 1px solid #f0f0f0;
    font-size: 13px;

    &:last-child {
      border-bottom: none;
    }

    &--head {
      color: var(--muted, #41454d);
    }
  }

  .gov-plan-col {
    min-width: 0;

    &--seg {
      flex: 0 0 40px;
    }
    &--type {
      flex: 0 0 110px;
    }
    &--stmts {
      flex: 0 0 80px;
    }
    &--table {
      flex: 1;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    &--rows {
      flex: 0 0 100px;
      text-align: right;
    }
    &--key {
      flex: 0 0 120px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    &--status {
      flex: 0 0 100px;
      text-align: right;
    }
  }

  .gov-route-override-risk {
    margin-bottom: 8px;
  }

  .gov-event-timeline-list {
    display: flex;
    flex-direction: column;
    gap: 0;
  }

  .gov-event-timeline-row {
    display: flex;
    gap: 12px;
    padding: 8px 0;
    border-bottom: 1px solid #f0f0f0;

    &:last-child {
      border-bottom: none;
    }

    &__icon {
      font-size: 18px;
      color: var(--primary, #181d26);
      flex-shrink: 0;
      padding-top: 2px;
    }

    &__body {
      flex: 1;
      min-width: 0;
    }

    &__header {
      display: flex;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
      font-size: 14px;
    }

    &__transition {
      color: var(--muted, #41454d);
      font-size: 13px;
      background: var(--surface-soft, #f8fafc);
      padding: 2px 8px;
      border-radius: 4px;
    }

    &__time {
      margin-left: auto;
      color: var(--muted, #41454d);
      font-size: 12px;
    }

    &__operator {
      color: var(--muted, #41454d);
      font-size: 13px;
      margin-top: 4px;
    }
  }
}

// 钉钉审批桥接出站面板(task 10-09):浅底分区(少线框,与工单详情面板同款)
.bridge-outbound-section {
  .ding-bridge-outbound {
    background: var(--surface-soft, #f8fafc);
    padding: 16px 24px;
    border-radius: 10px;
  }
}
</style>
