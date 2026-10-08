<template>
  <div class="page-shell ticket-detail-page">
    <div class="page-shell__body ticket-detail-container">
      <div class="ticket-detail-back">
        <Button type="text" @click="handleBack">
          <Icon type="ios-arrow-back" />
          {{ $t('fan-hui') }}
        </Button>
      </div>
      <section class="page-section ticket-info-section">
        <div class="ticket-info-section__header">
          <div class="ticket-info-section__heading">
            <div class="ticket-info-section__label">{{ $t('ticket-information') }}</div>
            <div class="ticket-info-section__title" :title="ticketDetail.ticketTitle || '-'">
              {{ ticketDetail.ticketTitle || '-' }}
            </div>
          </div>
          <div class="ticket-overview__actions">
            <Button class="warning-btn" v-if="ticketDetail.canApproval" type="primary" @click="handleShowApprovalModal">
              {{ $t('shen-pi') }}
            </Button>
            <Button type="primary" v-if="ticketDetail.canExecute" @click="handleShowAutoExecuteModal('CONFIRM')">
              {{ $t('zhi-xing') }}
            </Button>
            <Button type="primary" v-if="ticketDetail.pcUrl" @click="handleGoToTheApproval">
              {{ thirdPartyName[ticketDetail.approType] }}
            </Button>
            <Button v-if="ticketDetail.canClose" @click="handleShowCloseTicketModal">
              {{ $t('guan-bi') }}
            </Button>
            <Button class="refresh-btn" :loading="loading" @click="getTicketDetail('refresh')">
              <CustomIcon type="icon-v2-Refresh" v-if="!loading" />
            </Button>
          </div>
        </div>
        <div class="ticket-overview">
          <div
            :class="[
              'ticket-overview__primary-grid',
              { 'ticket-overview__primary-grid--with-target-database': ['DM_QUERY', 'DM_CHANGE'].includes(ticketDetail.approBiz) }
            ]"
          >
            <div class="ticket-meta-item">
              <span class="ticket-meta-item__label">{{ $t('ticket-number') }}</span>
              <span class="ticket-meta-item__value ticket-meta-item__ticket-id">
                <strong>{{ ticketDetail.bizId || `#${ticketDetail.id || ticketId}` }}</strong>
                <button
                  type="button"
                  class="ticket-meta-item__copy"
                  :aria-label="$t('fu-zhi')"
                  :title="$t('fu-zhi')"
                  @click="copyText(ticketDetail.bizId || `#${ticketDetail.id || ticketId}`)"
                >
                  <Icon type="ios-copy-outline" />
                </button>
              </span>
            </div>
            <div class="ticket-meta-item">
              <span class="ticket-meta-item__label ticket-meta-item__label--with-icon">
                <Icon type="ios-person-outline" />
                {{ $t('shen-qing-ren') }}
              </span>
              <span class="ticket-meta-item__value">
                {{ ticketDetail.userName || '-' }}
              </span>
            </div>
            <div v-if="['DM_QUERY', 'DM_CHANGE'].includes(ticketDetail.approBiz)" class="ticket-meta-item ticket-meta-item--target-database">
              <span class="ticket-meta-item__label ticket-meta-item__label--with-icon">
                <Icon type="ios-server-outline" />
                {{ $t('db-shi-li-ku-ming') }}
              </span>
              <Tooltip transfer :content="ticketDetail.targetInfo">
                <span class="ticket-meta-item__value ticket-meta-item__database">
                  <DataSourceIcon
                    class="ticket-meta-item__database-icon"
                    :type="ticketDetail.dataSourceType || 'DataBase'"
                    :instanceType="ticketDetail.dsDeployType"
                    size="24px"
                    leftMargin="0"
                  />
                  <span class="ticket-meta-item__database-info">
                    <span class="ticket-meta-item__database-name">
                      {{ ticketDetail.dataSourceDesc || ticketDetail.dataSourceInstName || '-' }}
                    </span>
                    <span class="ticket-meta-item__database-path">
                      {{ formatResourcePath(ticketDetail.targetInfo) }}
                    </span>
                  </span>
                </span>
              </Tooltip>
            </div>
            <div v-if="ticketDetail.ticketType" class="ticket-meta-item">
              <span class="ticket-meta-item__label ticket-meta-item__label--with-icon">
                <Icon type="ios-pricetag-outline" />
                {{ $t('lei-xing') }}
              </span>
              <span class="ticket-meta-item__value">
                <Tag v-if="ticketDetail.ticketType === 'PRE_DDL'" color="primary">{{ $t('gov-v2-type-pre-ddl') }}</Tag>
                <Tag v-else-if="ticketDetail.ticketType === 'PROD_DML'" color="warning">{{ $t('gov-v2-type-prod-dml') }}</Tag>
              </span>
            </div>
            <div v-if="ticketDetail.riskLevel" class="ticket-meta-item">
              <span class="ticket-meta-item__label ticket-meta-item__label--with-icon">
                <Icon type="ios-warning-outline" />
                {{ $t('risk-level-label') }}
              </span>
              <span class="ticket-meta-item__value">
                <Tag v-if="ticketDetail.riskLevel === 'CRITICAL'" color="error">{{ $t('risk-level-critical') }}</Tag>
                <Tag v-else-if="ticketDetail.riskLevel === 'HIGH'" color="warning">{{ $t('risk-level-high') }}</Tag>
                <Tag v-else-if="ticketDetail.riskLevel === 'MEDIUM'" color="info">{{ $t('risk-level-medium') }}</Tag>
                <Tag v-else color="default">{{ $t('risk-level-low') }}</Tag>
              </span>
            </div>
            <div v-if="ticketDetail.serviceName" class="ticket-meta-item">
              <span class="ticket-meta-item__label ticket-meta-item__label--with-icon">
                <Icon type="ios-people-outline" />
                {{ $t('gov-v2-service') }}
              </span>
              <span class="ticket-meta-item__value">{{ ticketDetail.serviceName }}</span>
            </div>
            <div class="ticket-meta-item">
              <span class="ticket-meta-item__label ticket-meta-item__label--with-icon">
                <Icon type="ios-layers-outline" />
                {{ $t('ticket-application-template') }}
              </span>
              <span class="ticket-meta-item__value">
                <img
                  v-if="['Internal', 'Inner'].includes(ticketDetail.approType)"
                  class="ticket-meta-item__product-icon"
                  src="/dm.ico"
                  alt="CloudDM"
                />
                <CustomIcon
                  v-else-if="ticketDetail.approType"
                  :type="`icon-v2-${ticketDetail.approType}`"
                  :instanceType="ticketDetail.dsDeployType"
                />
                {{ ticketDetail.approTemplateName || ticketDetail.approTypeName || '-' }}
              </span>
            </div>
          </div>

          <div
            :class="[
              'ticket-overview__secondary-grid',
              { 'ticket-overview__secondary-grid--with-target-database': ['DM_QUERY', 'DM_CHANGE'].includes(ticketDetail.approBiz) }
            ]"
          >
            <div class="ticket-meta-item">
              <span class="ticket-meta-item__label ticket-meta-item__label--with-icon">
                <Icon type="ios-time-outline" />
                {{ $t('chuang-jian-shi-jian') }}
              </span>
              <span>{{ ticketDetail.gmtCreate || '-' }}</span>
            </div>
            <div class="ticket-meta-item">
              <span class="ticket-meta-item__label ticket-meta-item__label--with-icon">
                <Icon type="ios-time-outline" />
                {{ $t('wan-cheng-shi-jian') }}
              </span>
              <span>{{ ticketDetail.finishTime || '-' }}</span>
            </div>
            <div class="ticket-meta-item ticket-meta-item--description">
              <span class="ticket-meta-item__label ticket-meta-item__label--with-icon">
                <Icon type="ios-document-outline" />
                {{ $t('miao-shu') }}
              </span>
              <Tooltip transfer :content="ticketDetail.description">
                <span class="ticket-meta-item__description">{{ ticketDetail.description || '-' }}</span>
              </Tooltip>
            </div>
          </div>
          <div v-if="ticketDetail.ticketStatus === 'FAILED' && ticketDetail.statusMessage" class="ticket-overview__error">
            <Icon type="ios-alert-outline" />
            <span>{{ ticketDetail.statusMessage }}</span>
          </div>
          <div v-if="ticketDetail.ticketStatus === 'REVISION'" class="ticket-overview__revision">
            <Icon type="ios-create-outline" />
            <span>{{ $t('gov-v2-revision-notice') }}</span>
          </div>
          <div v-if="ticketDetail.ticketStatus === 'WAIT_RELEASE'" class="ticket-overview__wait-release">
            <Icon type="ios-archive-outline" />
            <span>{{ $t('gov-v2-wait-release-notice') }}</span>
          </div>
        </div>
      </section>

      <section v-if="ticketType === 'DM_PROD_RELEASE' && releaseSummary" class="page-section gov-release-summary-section">
        <div class="page-section__title">{{ $t('gov-release-summary-title') }}</div>
        <div class="gov-release-summary-card">
          <div class="gov-release-summary-row">
            <span class="gov-release-summary-label">{{ $t('gov-release-no') }}</span>
            <span class="gov-mono">{{ releaseSummary.releaseNo }}</span>
          </div>
          <div class="gov-release-summary-row">
            <span class="gov-release-summary-label">{{ $t('gov-release-status-label') }}</span>
            <Tag :color="releaseStatusColor(releaseSummary.status)">{{ releaseStatusText(releaseSummary.status) }}</Tag>
          </div>
          <div class="gov-release-summary-row">
            <span class="gov-release-summary-label">{{ $t('gov-release-title') }}</span>
            <span>{{ releaseSummary.title || '-' }}</span>
          </div>
          <div class="gov-release-summary-actions">
            <Button type="primary" @click="goToRelease(releaseSummary.id)">
              {{ $t('gov-release-view-detail') }}
            </Button>
          </div>
        </div>
      </section>

      <section v-if="ticketProgressSteps.length" class="page-section ticket-progress-card">
        <div class="page-section__title">{{ $t('jin-du') }}</div>
        <div class="ticket-progress-section">
          <div class="ticket-progress-scroll">
            <div class="ticket-progress">
              <template v-for="(step, index) in ticketProgressSteps" :key="step.key">
                <button
                  type="button"
                  :class="['ticket-progress-step', `ticket-progress-step--${step.state}`, { 'is-selected': selectedStepKey === step.key }]"
                  @click="selectTicketStep(step)"
                >
                  <span class="ticket-progress-step__icon"><Icon :type="step.icon" /></span>
                  <span class="ticket-progress-step__content">
                    <strong>{{ step.title }}</strong>
                    <span>{{ step.time || '-' }}</span>
                    <span class="ticket-progress-step__handler">{{ $t('chu-li-ren') }}：{{ step.handler || '-' }}</span>
                  </span>
                </button>
                <span
                  v-if="index < ticketProgressSteps.length - 1"
                  :class="['ticket-progress-connector', { 'is-reached': ticketProgressSteps[index + 1].state !== 'pending' }]"
                ></span>
              </template>
            </div>
          </div>
        </div>

        <div v-if="selectedTicketStep" class="ticket-step-detail">
          <div class="page-section__title">{{ $t('ticket-step-details') }}</div>
          <div class="ticket-step-summary">
            <div class="ticket-step-summary__item">
              <span>{{ $t('ticket-current-step') }}</span>
              <strong class="ticket-step-name">{{ selectedTicketStep.title }}</strong>
            </div>
            <div class="ticket-step-summary__item">
              <span>{{ $t('chu-li-ren') }}</span>
              <strong>{{ selectedTicketStep.handler || '-' }}</strong>
            </div>
            <div class="ticket-step-summary__item">
              <span>{{ $t('kai-shi-shi-jian') }}</span>
              <strong>{{ selectedTicketStep.startTime || '-' }}</strong>
            </div>
            <div class="ticket-step-summary__item">
              <span>{{ $t('zhuang-tai') }}</span>
              <strong :class="['analysis-item-status', `status-${selectedTicketStep.statusClass}`]">{{ selectedTicketStep.statusText }}</strong>
            </div>
          </div>

          <div v-if="selectedTicketRejectionReason" class="ticket-step-rejection-reason">
            <span class="ticket-step-rejection-reason__icon">
              <Icon type="ios-alert-outline" />
            </span>
            <strong>{{ $t('ticket-rejection-reason') }}</strong>
            <p>{{ selectedTicketRejectionReason }}</p>
          </div>

          <template v-if="selectedTicketStep.stage === 'EXPLAIN' && analysisItems.length">
            <div class="analysis-summary-list">
              <div
                v-for="item in analysisItems"
                :key="item.activityTitle"
                :class="[
                  'analysis-summary-item',
                  {
                    'analysis-summary-item--table': ['BEHAVIOR_ANALYSIS', 'DML_EXPLAIN'].includes(item.activityTitle),
                    'is-expanded': ['BEHAVIOR_ANALYSIS', 'DML_EXPLAIN'].includes(item.activityTitle) && isAnalysisResultExpanded(item.activityTitle)
                  }
                ]"
              >
                <div
                  class="analysis-summary-row"
                  role="button"
                  tabindex="0"
                  :aria-label="isAnalysisResultExpanded(item.activityTitle) ? $t('ticket-collapse-details') : $t('ticket-expand-details')"
                  :aria-expanded="isAnalysisResultExpanded(item.activityTitle)"
                  :aria-controls="`analysis-result-${item.activityTitle}`"
                  @click="toggleAnalysisResult(item.activityTitle)"
                  @keydown.enter.prevent="toggleAnalysisResult(item.activityTitle)"
                  @keydown.space.prevent="toggleAnalysisResult(item.activityTitle)"
                >
                  <span :class="['analysis-summary-row__expand', { 'is-expanded': isAnalysisResultExpanded(item.activityTitle) }]" aria-hidden="true">
                    <Icon type="ios-arrow-forward" />
                  </span>
                  <span class="analysis-summary-row__icon">
                    <Icon :type="item.activityTitle === 'SECURITY_RULE' ? 'ios-lock-outline' : 'ios-search'" />
                  </span>
                  <strong>{{ analysisTypeText(item.activityTitle) }}</strong>
                  <span :class="['analysis-item-status', analysisStatusClass(item.activityStatus)]">
                    {{ analysisStatusText(item.activityStatus) }}
                  </span>
                  <span class="analysis-summary-row__result">
                    {{ analysisSummaryText(item) }}
                  </span>
                  <span class="analysis-summary-row__elapsed">
                    <span>{{ $t('hao-shi') }}</span>
                    {{ analysisElapsed(item) }}
                  </span>
                </div>

                <div
                  :id="`analysis-result-${item.activityTitle}`"
                  :class="['analysis-result-collapse', { 'is-expanded': isAnalysisResultExpanded(item.activityTitle) }]"
                  :aria-hidden="!isAnalysisResultExpanded(item.activityTitle)"
                  :inert="!isAnalysisResultExpanded(item.activityTitle)"
                >
                  <div class="analysis-result-collapse__content">
                    <div
                      :class="[
                        'analysis-result-details',
                        { 'analysis-result-details--table': ['BEHAVIOR_ANALYSIS', 'DML_EXPLAIN'].includes(item.activityTitle) }
                      ]"
                    >
                      <div class="page-panel-body">
                        <template v-if="item.activityTitle === 'BEHAVIOR_ANALYSIS'">
                          <Table
                            v-if="recognizedBehaviorRows.length"
                            :columns="recognizedContentColumns"
                            :data="recognizedBehaviorRows"
                            border
                            size="small"
                          >
                            <template #resourceType="{ row }">
                              <Tag>{{ row.resourceType || '--' }}</Tag>
                            </template>
                            <template #actions="{ row }">
                              <Tag v-for="action in row.actionItems" :key="action.action" color="primary">
                                {{ behaviorActionText(action) }}
                              </Tag>
                            </template>
                          </Table>
                          <div v-else class="analysis-result-empty">{{ analysisEmptyText(item) }}</div>
                        </template>

                        <template v-else-if="item.activityTitle === 'SECURITY_RULE'">
                          <div v-if="analysisRuleResults.length" class="analysis-rule-toolbar">
                            <Checkbox v-model="showCheckedOnlyError">{{ $t('jin-xian-shi-yan-zhong') }}</Checkbox>
                          </div>
                          <div v-if="checkRoleResultList().length" class="validation-content">
                            <div v-for="(rule, index) in checkRoleResultList()" :key="index" class="rule-item">
                              <div class="rule-header">
                                <Tag :color="rule.ruleLevel === 'SUGGEST' ? 'warning' : 'error'" class="rule-level">
                                  {{ RULE_WARN_LEVEL[rule.ruleLevel] }}
                                </Tag>
                                <span class="rule-name">{{ rule.name }}</span>
                                <div v-if="rule.lines && rule.lines.length" class="rule-lines">
                                  <span class="lines-label">{{ $t('wei-zhi-0') }}:</span>
                                  <span v-for="line in rule.lines" :key="line" class="lines-content">{{ line }}</span>
                                  <span v-if="rule.hitCount > rule.lines.length" class="lines-content">
                                    {{ $t('ticket-rule-location-total', { count: rule.hitCount }) }}
                                  </span>
                                </div>
                              </div>
                              <div class="rule-desc">{{ rule.desc }}</div>
                            </div>
                          </div>
                          <div v-else class="analysis-result-empty">
                            {{ analysisRuleResults.length && showCheckedOnlyError ? $t('ticket-analysis-security-passed') : analysisEmptyText(item) }}
                          </div>
                        </template>

                        <template v-else-if="item.activityTitle === 'DML_EXPLAIN'">
                          <Table
                            v-if="item.explainResults && item.explainResults.length"
                            :columns="dmlExplainTableColumns()"
                            :data="dmlExplainRows(item)"
                            border
                            size="small"
                          >
                            <template #estimatedAffectedRows="{ row }">
                              <span>{{ row.estimatedAffectedRows ?? '--' }}</span>
                            </template>
                            <template #actions="{ row }">
                              <Tag v-for="action in row.actions" :key="action" color="primary">{{ action }}</Tag>
                            </template>
                            <template #subjects="{ row }">
                              <span>{{ row.subjects.join($t('ticket-analysis-dml-explain-index-separator')) }}</span>
                            </template>
                            <template #statementCount="{ row }">
                              <span>{{ dmlExplainStatementText(row) }}</span>
                            </template>
                            <template #description="{ row }">
                              <span>{{ dmlExplainDescription(row) }}</span>
                            </template>
                          </Table>
                          <div v-else class="analysis-result-empty">{{ analysisEmptyText(item) }}</div>
                        </template>

                        <div v-else class="analysis-result-empty">{{ analysisEmptyText(item) }}</div>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </template>

          <template v-else-if="selectedTicketStep.stage === 'EXECUTION' && autoExec && ['DM_QUERY', 'DM_CHANGE'].includes(ticketType)">
            <div class="ticket-execution-detail">
              <div :class="['analysis-summary-item', 'ticket-execution-card', { 'is-expanded': taskExecutionExpanded }]">
                <div class="analysis-summary-row ticket-execution-summary-row">
                  <button
                    type="button"
                    :class="['analysis-summary-row__expand', { 'is-expanded': taskExecutionExpanded }]"
                    :aria-label="taskExecutionExpanded ? $t('ticket-collapse-details') : $t('ticket-expand-details')"
                    :aria-expanded="taskExecutionExpanded"
                    aria-controls="ticket-execution-result"
                    @click="taskExecutionExpanded = !taskExecutionExpanded"
                  >
                    <Icon type="ios-arrow-forward" />
                  </button>
                  <span class="analysis-summary-row__icon ticket-execution-summary-row__icon">
                    <Icon type="ios-play-outline" />
                  </span>
                  <strong>{{ $t('ren-wu-zhi-hang') }}</strong>
                  <span class="ticket-execution-status-wrap">
                    <Poptip v-if="!autoExecJobInfo.normal" :content="autoExecJobInfo.message" trigger="hover">
                      <Icon type="ios-alert-outline" />
                    </Poptip>
                    <span :class="['ticket-execution-status', `is-${autoExecJobInfo.status || 'INIT'}`]">
                      {{ autoExecJobStatusText(autoExecJobInfo.status) }}
                    </span>
                  </span>
                  <div class="ticket-execution-context">
                    <span v-if="autoExecJobInfo.execTime">{{ $t('ji-hua-zhi-hang-shi-jian') }} {{ autoExecJobInfo.execTime }}</span>
                    <span v-if="autoExecJobInfo.workerIp">{{ $t('ji-qi-ip-0') }} {{ autoExecJobInfo.workerIp }}</span>
                    <span v-if="autoExecJobInfo.workerStatus">{{ $t('ji-qi-zhuang-tai-0') }} {{ autoExecJobInfo.workerStatus }}</span>
                  </div>
                  <div class="ticket-execution-actions">
                    <Button v-if="autoExecJobInfo.canEnd" type="text" size="small" @click="handleShowEndAutoExecJobModal">
                      {{ $t('ticket-execution-action-terminate') }}
                    </Button>
                    <Button v-if="autoExecJobInfo.canPause" type="text" size="small" @click="handleShowStopAutoExecJobModal">
                      {{ $t('zan-ting') }}
                    </Button>
                    <Button v-if="autoExecJobInfo.canRestart" type="text" size="small" @click="handleShowRetryAutoExecJobModal">
                      {{ $t('hui-fu') }}
                    </Button>
                    <Button v-if="autoExecJobInfo.canRetry" type="text" size="small" @click="handleShowRetryAutoExecJobModal">
                      {{ $t('zhong-shi') }}
                    </Button>
                    <Button type="text" size="small" @click="handleAutoExecLog(null)">
                      <Icon type="ios-list-box-outline" />
                      {{ $t('ticket-execution-scheduling-log') }}
                    </Button>
                    <Button type="text" size="small" @click="handleRefreshTaskList">
                      <Icon type="md-refresh" />
                      {{ $t('shua-xin') }}
                    </Button>
                  </div>
                </div>

                <div
                  id="ticket-execution-result"
                  :class="['analysis-result-collapse', { 'is-expanded': taskExecutionExpanded }]"
                  :aria-hidden="!taskExecutionExpanded"
                  :inert="!taskExecutionExpanded"
                >
                  <div class="analysis-result-collapse__content">
                    <div class="analysis-result-details ticket-execution-body">
                      <div class="ticket-execution-table">
                        <Table :columns="autoExecTaskColumns" :data="autoExecTaskList" border size="small">
                          <template #status="{ row }">
                            <span :class="['ticket-task-status', `is-${row.status}`]">
                              {{ autoExecTaskStatusText(row.status) }}
                            </span>
                          </template>
                          <template #action="{ row }">
                            <Button type="text" size="small" @click="handleAutoExecSQL(row)">
                              {{ $t('cha-kan') }}
                            </Button>
                            <Button type="text" size="small" @click="handleAutoExecLog(row)">
                              {{ $t('ticket-execution-action-log') }}
                            </Button>
                            <Button v-if="row.canSkip" type="text" size="small" @click="handleShowSkipAutoExecTaskModal(row)">
                              {{ $t('ticket-execution-action-skip') }}
                            </Button>
                            <Button v-if="row.canCancelSkip" type="text" size="small" @click="handleShowContinueAutoExecTaskModal(row)">
                              {{ $t('qu-xiao-tiao-guo') }}
                            </Button>
                          </template>
                        </Table>
                      </div>
                      <div class="ticket-execution-pagination">
                        <Page v-model="page" :page-size="pageSize" :total="total" @on-change="handleTaskPageChange" size="small" />
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </template>

          <div v-else-if="selectedTicketStep.activities.length" class="ticket-activity-list">
            <div
              class="ticket-activity-row"
              v-for="activity in selectedTicketStep.activities"
              :key="activity.processActivityId || activity.activityTitle"
            >
              <div>
                <span>{{ stepNodeLabel(activity) }}</span>
                <strong>{{ activity.activityTitle }}</strong>
              </div>
              <div>
                <span>{{ $t('chu-li-ren') }}</span>
                <strong>{{ activity.approvalUserList && activity.approvalUserList.length ? activity.approvalUserList.join(', ') : '-' }}</strong>
              </div>
              <div>
                <span>{{ $t('wan-cheng-shi-jian') }}</span>
                <strong>{{ activity.finishTime || '-' }}</strong>
              </div>
              <span :class="['analysis-item-status', analysisStatusClass(activity.activityStatus)]">
                {{ stepStatusText(activity) }}
              </span>
            </div>
          </div>
        </div>
      </section>

      <section v-if="ticketType === 'DM_QUERY' || ticketType === 'DM_CHANGE'" class="page-section ticket-sql-section">
        <div class="ticket-sql-toolbar">
          <div class="ticket-sql-toolbar__meta">
            <div class="page-section__title">{{ $t('sql-nei-rong') }}</div>
            <span v-if="ticketDetail.contentType === 'ATTACHMENT'" class="ticket-sql-file">
              <Icon type="ios-document-outline" />
              <span>{{ $t('ticket-sql-attachment') }}</span>
              <span>{{ ticketDetail.attachmentFileName }}</span>
              <span>{{ formatFileSize(ticketDetail.attachmentFileSize || 0) }}</span>
            </span>
            <span v-if="ticketDetail.ticketMessage" class="parse-error-msgContent">{{ ticketDetail.ticketMessage }}</span>
          </div>
          <div class="ticket-sql-toolbar__actions">
            <Button type="text" @click="handleShowTicketContentModal">
              <Icon type="ios-eye-outline" />
              {{ $t('ticket-view-content') }}
            </Button>
            <Button type="text" :loading="sqlContentAction === 'copy'" @click="handleCopyTicketSql">
              <Icon type="ios-copy-outline" />
              {{ $t('fu-zhi') }}
            </Button>
            <Button type="text" :loading="sqlContentAction === 'download'" @click="handleDownloadTicketSql">
              <Icon type="ios-download-outline" />
              {{ $t('xia-zai') }}
            </Button>
            <Button v-if="ticketDetail.rollBackSql" type="text" @click="handleShowRollbackSqlModal">
              {{ $t('cha-kan-hui-gun-sql') }}
              <Tag v-if="ticketDetail.rollBackSqlGenerated" size="small" color="warning">{{ $t('xi-tong-sheng-cheng') }}</Tag>
              <Tag v-else size="small" color="success">{{ $t('shen-qing-ren-tian-xie') }}</Tag>
            </Button>
            <Button v-if="ticketDetail.impactScopeText" type="text" @click="showImpactScopeModal = true">
              <Icon type="ios-information-circle-outline" />
              {{ $t('impact-scope') }}
            </Button>
          </div>
        </div>
        <read-only-editor
          :text="ticketSqlContent"
          key="ticket-sql-content"
          :ds-type="ticketDetail.dataSourceType"
          @reach-bottom="loadNextTicketSqlContent"
        />
      </section>
      <section v-if="ticketDetail.ticketType && v2GroupList.length" class="page-section gov-v2-group-section">
        <div class="page-section__title">{{ $t('gov-v2-group-list') }}</div>
        <div class="gov-v2-group-list">
          <div v-for="group in v2GroupList" :key="group.groupId" class="gov-v2-group-row">
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
                <read-only-editor :text="group.sqlContent" :ds-type="ticketDetail.dataSourceType" :max-height="200" />
              </div>
              <!-- B6 M4 (design §6.8): collapsible segment/statement list — rendered ONLY for
                   segmented groups (exec_segments runtime ∨ precheck segments plan); pure-normal,
                   legacy and M1 flags-only groups keep the exact M1 chain below. The inline panel
                   of a GHOST segment is that group's SINGLE ghost surface — the M1 group-level
                   panel is excluded for segmented groups, so progress/controls never render twice
                   and exactly one tailStatus poller runs per group (continuous polling starts only
                   for a live session; a released one degrades to one silent synthesized probe).
                   B7 N3: a DML_BATCH segment mounts the lightweight DmlBatchGroupPanel instead —
                   ZERO new pollers (its live data is the groupList VO `dmlBatchProgress`
                   projection, arriving with this page's own 5s auto-refresh; controls are the
                   /dmlBatch/* endpoints, DM_GHOST_EXEC-gated inside the panel).
                   ⚠ execSegments/plan rendering here AND the dmlBatchProgress projection both ride
                   the ghostRouteFlags dual-write coupling — B7 semantic widening: the flags mean
                   "has non-direct-execution segments" (GHOST ∨ DML_BATCH), wire shape unchanged
                   (backend pins: GovTicketV2ServiceImpl.groupList / TicketSegmentService.persistRuntime). -->
              <div v-if="isSegmentedGroup(group)" class="gov-v2-segments">
                <div class="gov-v2-segments__head" @click="toggleSegments(group)">
                  <Icon :type="segmentsOpen(group) ? 'ios-arrow-down' : 'ios-arrow-forward'" />
                  <span class="gov-v2-segments__title">{{ $t('wu-suo-zhi-xing-duan') }}</span>
                  <span class="gov-v2-segments__count">{{ groupSegments(group).length }}</span>
                  <span class="gov-v2-segments__action">
                    {{ segmentsOpen(group) ? $t('wu-suo-seg-shou-qi') : $t('wu-suo-seg-zhan-kai') }}
                  </span>
                </div>
                <div v-show="segmentsOpen(group)" class="gov-v2-segments__body">
                  <div v-for="seg in groupSegments(group)" :key="seg.segIdx" class="gov-v2-segment">
                    <div class="gov-v2-segment__head">
                      <Tag size="small" :color="seg.type === 'AUTOEXEC' ? 'default' : 'warning'">
                        {{ $t(segmentTypeBadgeI18n(seg.type)) }}
                      </Tag>
                      <span class="gov-v2-segment__range">{{ segmentRangeText(seg) }}</span>
                      <span v-if="seg.type !== 'AUTOEXEC' && seg.table" class="gov-v2-segment__table">
                        {{ segmentRowsOf(seg) == null ? seg.table : $t('wu-suo-seg-biao-hang-shu', [seg.table, segmentRowsOf(seg)]) }}
                      </span>
                      <span :class="['gov-v2-segment__status', `is-${segmentStatusOf(seg)}`]">
                        {{ segmentStatusText(seg) }}
                      </span>
                    </div>
                    <div v-if="segmentStatements(group, seg).length" class="gov-v2-segment__stmts">
                      <div v-for="stmt in segmentStatements(group, seg)" :key="stmt.index" class="gov-v2-segment__stmt-row">
                        <Tag size="small">{{ stmt.type }}</Tag>
                        <span class="gov-v2-segment__stmt-sql">{{ stmt.sql }}</span>
                      </div>
                    </div>
                    <div v-if="seg.error" class="gov-v2-segment__error">
                      <span>{{ $t('wu-suo-shi-bai-yuan-yin') }}:</span>
                      <span>{{ segmentErrorText(seg) }}</span>
                    </div>
                    <ghost-group-panel
                      v-if="seg.type === 'GHOST' && seg.sessionId"
                      :group="segmentPanelGroup(group, seg)"
                      :can-exec="canGhostExec"
                      @dropped="loadV2GroupList"
                    />
                    <dml-batch-group-panel
                      v-if="seg.type === 'DML_BATCH' && showDmlBatchPanel(group, seg)"
                      :ticket-id="ticketId"
                      :group="group"
                      :seg="seg"
                      :can-exec="canGhostExec"
                      @refresh="loadV2GroupList"
                      @resolve="openDmlResolutionModal(group, seg)"
                    />
                  </div>
                </div>
              </div>
              <!-- M1 group-level panel: unsegmented flags-only groups ONLY (coexistence ruling —
                   a segmented group's ghost surface lives inline in its GHOST segment rows) -->
              <ghost-group-panel
                v-if="!isSegmentedGroup(group) && group.ghostSessionId"
                :group="group"
                :can-exec="canGhostExec"
                @dropped="loadV2GroupList"
              />
              <div v-if="showGhostRouteFlags(group)" class="gov-v2-group-row__ghost-routes">
                <div
                  v-for="route in ghostRoutesOf(group)"
                  :key="route.stmtIndex"
                  :class="['ghost-route-flag', route.route === 'GHOST' ? 'is-ghost' : 'is-alert-pass']"
                >
                  <Tag size="small" :color="route.route === 'GHOST' ? 'warning' : 'default'">
                    {{ route.route === 'GHOST' ? $t('wu-suo-lu-you-ghost') : $t('wu-suo-lu-you-alert-pass') }}
                  </Tag>
                  <span class="ghost-route-msg">{{ route.message }}</span>
                </div>
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
              <div v-if="ticketDetail.ticketStatus === 'REVISION' && group.execStatus === 'FAILED'" class="gov-v2-group-row__edit">
                <Input v-model="v2ResubmitSqlMap[group.groupId]" type="textarea" :rows="6" :placeholder="$t('gov-v2-resubmit-placeholder')" />
              </div>
              <div class="gov-v2-group-row__actions">
                <Button
                  v-if="group.execStatus === 'FAILED' && ticketDetail.canExecute"
                  type="primary"
                  size="small"
                  :loading="v2RetryLoading"
                  @click="handleRetryGroup(group.groupId)"
                >
                  {{ $t('gov-v2-retry') }}
                </Button>
                <Button v-if="canReturnTicket" size="small" :loading="v2ReturnLoading" @click="showReturnTicketModal = true">
                  {{ $t('gov-v2-return-for-revision') }}
                </Button>
                <Button
                  v-if="ticketDetail.ticketStatus === 'REVISION' && group.execStatus === 'FAILED'"
                  type="primary"
                  size="small"
                  :loading="v2ResubmitLoading"
                  @click="openResubmitModal(group.groupId)"
                >
                  {{ $t('gov-v2-resubmit') }}
                </Button>
              </div>
            </div>
          </div>
        </div>
      </section>
      <section v-if="ticketType === 'DATA_SOURCE_AUTH'" class="page-section ticket-auth-section">
        <div class="page-section__title">{{ $t('gong-dan-nei-rong') }}</div>
        <div v-if="formattedAuths.length" class="ticket-auth-list">
          <article v-for="authItem in formattedAuths" :key="authItem.resId" class="ticket-auth-record">
            <div class="ticket-auth-record__meta">
              <div class="ticket-auth-record__field">
                <span>{{ $t('shu-ju-yuan-shi-li') }}</span>
                <strong class="ticket-auth-record__datasource">
                  <DataSourceIcon :type="authItem.dataSourceType || 'DataBase'" size="22px" leftMargin="0" />
                  <span class="ticket-auth-record__datasource-info">
                    <span class="ticket-auth-record__datasource-name">{{ authItem.resDesc }}</span>
                    <span class="ticket-auth-record__datasource-id">{{ authItem.resInstId }}</span>
                  </span>
                </strong>
              </div>
              <div class="ticket-auth-record__field">
                <span>{{ $t('zi-yuan-lu-jing') }}</span>
                <strong>{{ authItem.resPaths }}</strong>
              </div>
              <div class="ticket-auth-record__field">
                <span>{{ $t('sheng-xiao-shi-jian') }}</span>
                <strong>{{ authItem.effectiveTime }}</strong>
              </div>
            </div>
            <div class="ticket-auth-record__permissions">
              <span class="ticket-auth-record__permissions-title">{{ $t('quan-xian-lie-biao') }}</span>
              <div class="ticket-auth-labels">
                <span v-for="(label, index) in authItem.authLabels" :key="index" class="ticket-auth-label">{{ label }}</span>
                <span v-if="!authItem.authLabels.length">-</span>
              </div>
            </div>
          </article>
        </div>
        <div v-else class="ticket-auth-empty">
          <span class="ticket-auth-empty__icon"><Icon type="ios-key-outline" /></span>
          <span>{{ $t('ticket-auth-empty') }}</span>
        </div>
      </section>
    </div>
    <CCModal v-model="showApprovalModal" :title="$t('shen-pi')" :closable="false">
      <Form>
        <FormItem :label="$t('yi-jian')">
          <RadioGroup v-model="approvalData.action">
            <Radio :label="'approve'">{{ $t('tong-yi') }}</Radio>
            <Radio :label="'reject'">{{ $t('ju-jue') }}</Radio>
            <Radio :label="'return'">{{ $t('gov-v2-approver-return') }}</Radio>
          </RadioGroup>
        </FormItem>
        <FormItem :label="$t('li-you')">
          <Input type="textarea" v-model="approvalData.comment"></Input>
        </FormItem>
      </Form>
      <template #footer>
        <Button @click="handleApproval" type="primary">{{ $t('ti-jiao') }}</Button>
        <Button @click="handleCloseModal">{{ $t('guan-bi') }}</Button>
      </template>
    </CCModal>
    <CCModal v-model="showCancelTicketModal" :title="$t('che-xiao-gong-dan-que-ren')">
      <p>{{ $t('gong-dan-che-xiao-hou-bu-ke-hui-fu-que-ren-yao-che-xiao-gai-gong-dan-ma') }}</p>
      <template #footer>
        <Button type="primary" @click="cancelTicket">{{ $t('que-ding') }}</Button>
        <Button @click="handleCloseModal">{{ $t('qu-xiao') }}</Button>
      </template>
    </CCModal>
    <CCModal v-model="showRollbackSqlModal" :width="1000">
      <template #title>
        {{ $t('cha-kan-hui-gun-sql') }}
        <Tag v-if="ticketDetail.rollBackSqlGenerated" size="small" color="warning">{{ $t('xi-tong-sheng-cheng') }}</Tag>
        <Tag v-else size="small" color="success">{{ $t('shen-qing-ren-tian-xie') }}</Tag>
      </template>
      <read-only-editor :text="ticketDetail.rollBackSql" key="rollback" :max-height="500" :ds-type="ticketDetail.dataSourceType" />
      <template #footer>
        <Button type="primary" @click="copyText(ticketDetail.rollBackSql)">
          {{ $t('fu-zhi-sql') }}
        </Button>
        <Button @click="handleCloseModal">{{ $t('guan-bi') }}</Button>
      </template>
    </CCModal>
    <CCModal v-model="showImpactScopeModal" :width="700">
      <template #title>
        {{ $t('impact-scope') }}
      </template>
      <div class="impact-scope-content">
        <pre>{{ ticketDetail.impactScopeText }}</pre>
      </div>
      <template #footer>
        <Button @click="showImpactScopeModal = false">{{ $t('guan-bi') }}</Button>
      </template>
    </CCModal>
    <CCModal v-model="showTicketContentModal" :title="$t('gong-dan-nei-rong')" width="80vw" centered :draggable="false" class="responsive-sql-modal">
      <read-only-editor
        :text="ticketSqlContent"
        key="ticket-sql-content-modal"
        :max-height="500"
        :ds-type="ticketDetail.dataSourceType"
        @reach-bottom="loadNextTicketSqlContent"
      />
      <template #footer>
        <Button type="primary" :loading="sqlContentAction === 'copy'" @click="handleCopyTicketSql">
          <Icon type="ios-copy-outline" />
          {{ $t('fu-zhi') }}
        </Button>
        <Button :loading="sqlContentAction === 'download'" @click="handleDownloadTicketSql">
          <Icon type="ios-download-outline" />
          {{ $t('xia-zai') }}
        </Button>
        <Button @click="handleCloseModal">{{ $t('guan-bi') }}</Button>
      </template>
    </CCModal>
    <CCModal :title="$t('ti-shi')" v-model="showCloseTicketModal" @on-ok="closeTicket" @on-cancel="handleCloseModal">
      {{ $t('que-ding-yao-guan-bi-gong-dan-ma') }}
      <template #footer>
        <Button type="primary" @click="closeTicket">{{ $t('que-ding') }}</Button>
        <Button @click="handleCloseModal">{{ $t('qu-xiao') }}</Button>
      </template>
    </CCModal>
    <CCModal :title="$t('gong-dan-zhi-xing')" v-model="showAutoExecuteModal" width="800px">
      <Form class="ticket-execute-form" :model="confirmInfo.autoExecConfig" label-position="top">
        <FormItem :label="$t('zhi-hang-ce-lve')" prop="autoExecType">
          <RadioGroup class="ticket-execute-mode-options" v-model="confirmInfo.autoExecConfig.autoExecType">
            <Radio label="MANUAL_EXEC">{{ $t('yi-shou-dong-wan-cheng') }}</Radio>
            <Radio label="IMMEDIATE">{{ $t('li-ji-zhi-xing') }}</Radio>
            <!-- B7 N3 (裁决 #17): 分批/分段组无定时承载 — SPECIFY_TIME 禁用+提示,服务端确认复验兜底 -->
            <Radio label="SPECIFY_TIME" :disabled="ticketHasDmlBatchSegment">{{ $t('ding-shi-zhi-xing') }}</Radio>
          </RadioGroup>
          <div v-if="ticketHasDmlBatchSegment" class="ticket-execute-batch-hint">
            {{ $t('fen-pi-ding-shi-bu-zhi-chi') }}
          </div>
          <div v-if="confirmInfo.autoExecConfig.autoExecType === 'SPECIFY_TIME'" class="ticket-execute-schedule-row">
            <span class="ticket-execute-schedule-label">{{ $t('zhi-hang-shi-jian') }}</span>
            <DatePicker
              class="ticket-execute-schedule"
              v-model="confirmInfo.autoExecConfig.execTime"
              type="datetime"
              :placeholder="$t('qing-xuan-ze-zhi-hang-shi-jian')"
            />
          </div>
        </FormItem>
        <FormItem
          :label="$t('shi-wu')"
          prop="enableTransactional"
          v-if="!isCk(ticketDetail.dataSourceType) && !isMongoDB(ticketDetail.dataSourceType)"
        >
          <div class="ticket-execute-transaction">
            <i-switch
              v-model="confirmInfo.autoExecConfig.enableTransactional"
              size="large"
              :disabled="confirmInfo.autoExecConfig.autoExecType === 'MANUAL_EXEC' || ticketHasDmlBatchSegment"
            >
              <template #open>{{ $t('kai-qi-0') }}</template>
              <template #close>{{ $t('guan-bi') }}</template>
            </i-switch>
            <!-- B7 N3 (裁决 #11 三点明示之二): 分批组事务开关禁用+行内明示,服务端强制 false 复验兜底 -->
            <span v-if="ticketHasDmlBatchSegment" class="ticket-execute-batch-transaction-hint">
              {{ $t('fen-pi-que-ren-shi-wu-jin-yong') }}
            </span>
            <span v-else>{{ $t('ru-guo-sql-yu-ju-zhong-cun-zai-fei-dml-yu-ju-ke-neng-hui-bei-fen-wei-duo-ge-shi-wu-zhi-hang') }}</span>
          </div>
        </FormItem>
        <FormItem :label="$t('bei-zhu')">
          <a-textarea class="ticket-execute-comment" v-model:value="confirmInfo.comment" :rows="3" />
        </FormItem>
      </Form>
      <template #footer>
        <Button
          type="primary"
          :loading="confirmSubmitting"
          :disabled="confirmSubmitting"
          @click="handleFinishTicket"
          v-if="confirmInfo.autoExecConfig.autoExecType === 'MANUAL_EXEC'"
        >
          {{ $t('jie-shu-gong-dan') }}
        </Button>
        <Button type="primary" :loading="confirmSubmitting" :disabled="confirmSubmitting" @click="handleConfirmTicket" v-else>
          {{ confirmInfo.autoExecConfig.autoExecType == 'IMMEDIATE' ? $t('li-ji-zhi-hang') : $t('ding-shi-zhi-hang') }}
        </Button>
        <Button @click="handleCloseModal">{{ $t('qu-xiao') }}</Button>
      </template>
    </CCModal>
    <CCModal v-model="showAutoExecJobLogModal" :title="$t('ticket-execution-scheduling-log')" @ok="handleCloseModal" :width="800">
      <Table :columns="autoExecJobLogColumns" :data="autoExecJobLogList" border size="small" />
    </CCModal>
    <CCModal v-model="showAutoExecTaskLogModal" :title="$t('ticket-execution-action-log')" @ok="handleCloseModal" :width="800">
      <Table :columns="autoExecJobLogColumns" :data="autoExecTaskLogList" border size="small" />
    </CCModal>
    <CCModal v-model="showAutoExecTaskSQLModal" :title="$t('sql-yu-ju')" width="80vw" centered :draggable="false" class="responsive-sql-modal">
      <div class="responsive-sql-modal-editor">
        <read-only-editor :text="selectedAutoExecTaskSql" key="auto-exec-task-sql" :ds-type="ticketDetail.dataSourceType" />
      </div>
      <template #footer>
        <Button :disabled="!canViewPreviousAutoExecTask || autoExecTaskSqlLoading" @click="handleSwitchAutoExecSQL(-1)">
          <Icon type="ios-arrow-back" />
          {{ $t('ticket-previous-item') }}
        </Button>
        <Button :disabled="!canViewNextAutoExecTask || autoExecTaskSqlLoading" @click="handleSwitchAutoExecSQL(1)">
          {{ $t('ticket-next-item') }}
          <Icon type="ios-arrow-forward" />
        </Button>
        <Button type="primary" @click="copyText(selectedAutoExecTaskSql)">{{ $t('fu-zhi-sql') }}</Button>
        <Button @click="handleCloseModal">{{ $t('guan-bi') }}</Button>
      </template>
    </CCModal>
    <CCModal v-model="showStopAutoExecJobModal" :title="$t('zan-ting')" @ok="handleStopAutoExecJob">
      {{
        $t(
          'zan-ting-jiang-zhong-duan-dang-qian-zheng-zai-zhi-hang-de-sql-bing-ting-zhi-tiao-du-hou-xu-de-sql-yi-cheng-gong-de-sql-bu-shou-yin-xiang-ru-guo-dang-qian-zheng-zai-de-sql-chu-yu-shi-wu-zhi-zhong-zheng-ge-shi-wu-jiang-hui-bei-hui-gun'
        )
      }}
    </CCModal>
    <CCModal v-model="showRetryAutoExecJobModal" :title="$t('zhong-shi')" @ok="handleRetryAutoExecJob">
      {{ $t('jiang-zhong-xin-zhi-hang-yi-shi-bai-dai-zhi-hang-hui-gun-he-dai-que-ren-de-ren-wu') }}
    </CCModal>
    <CCModal v-model="showEndAutoExecJobModal" :title="$t('ticket-execution-action-terminate')" @ok="handleEndAutoExecJob">
      {{ $t('zhong-zhi-hou-jiang-wu-fa-zhi-hang-ren-wu-qie-hui-guan-bi-gong-dan') }}
    </CCModal>
    <CCModal v-model="showSkipAutoExecTaskModal" :title="$t('ticket-execution-action-skip')" @ok="handleSkipAutoExecTask">
      {{ $t('tiao-guo-hou-zhong-shi-ren-wu-shi-jiang-hui-tiao-guo-gai-sql-zhi-hang') }}
    </CCModal>
    <CCModal v-model="showContinueSkipAutoExecTaskModal" :title="$t('qu-xiao-tiao-guo')" @ok="handleContinueAutoExecTask">
      {{ $t('qu-xiao-tiao-guo-hou-xia-ci-zhong-shi-ren-wu-shi-jiang-zhi-hang-gai-sql') }}
    </CCModal>
    <CCModal
      v-model="showReturnTicketModal"
      :title="$t('gov-v2-return-for-revision')"
      @on-ok="handleReturnTicket"
      @on-cancel="
        () => {
          showReturnTicketModal = false;
          v2ReturnReason = '';
        }
      "
    >
      <div style="margin-bottom: 12px">{{ $t('gov-v2-return-notice') }}</div>
      <Input v-model="v2ReturnReason" type="textarea" :rows="3" :placeholder="$t('gov-v2-return-reason-placeholder')" />
    </CCModal>
    <CCModal
      v-model="showResubmitModal"
      :title="$t('gov-v2-resubmit')"
      @on-ok="handleResubmitSubmit"
      @on-cancel="
        () => {
          showResubmitModal = false;
          v2ResubmitReason = '';
          v2ResubmitRollBackSql = '';
        }
      "
    >
      <Input v-model="v2ResubmitReason" type="textarea" :rows="3" :placeholder="$t('gov-v2-resubmit-reason-placeholder')" />
      <div class="resubmit-rollback-section">
        <label class="resubmit-rollback-label">{{ $t('hui-gun-sql') }}</label>
        <Input v-model="v2ResubmitRollBackSql" type="textarea" :rows="4" :placeholder="$t('hui-gun-sql-resubmit-placeholder')" />
      </div>
    </CCModal>
    <!-- B7 N3 (design §7.4, 裁决 #9 选项 C): the window-crash manual-verdict modal — a batch whose
         outcome is UNKNOWN (intent ∧ ¬retryPending frozen into the segment summary, or the N2
         re-frozen unconsumed-SKIP shape) must be adjudicated RERUN/SKIP before retryGroup resumes;
         the modal IS the second confirmation (二次确认) over the retry entry. Visibility follows
         DM_GHOST_EXEC (the retry intercept warns read-only users instead). -->
    <CCModal v-model="showDmlResolutionModal" :title="$t('fen-pi-cai-jue-biao-ti')" width="640px" @on-cancel="closeDmlResolutionModal">
      <div v-if="dmlResolutionSeg" class="dml-resolution">
        <div class="dml-resolution__locate">
          {{ $t('fen-pi-cai-jue-shuo-ming', [dmlResolutionIntentSeq, dmlResolutionSeg.table || '-']) }}
        </div>
        <div v-if="dmlResolutionSkipVerdictPending" class="dml-resolution__skip-again">
          {{ $t('fen-pi-cai-jue-skip-wei-cheng-gong') }}
        </div>
        <div :class="['dml-resolution__idempotent', dmlResolutionIdempotent ? 'is-idempotent' : 'is-non-idempotent']">
          {{ dmlResolutionIdempotent ? $t('fen-pi-cai-jue-mi-deng') : $t('fen-pi-cai-jue-fei-mi-deng') }}
        </div>
        <div v-if="dmlResolutionLastError" class="dml-resolution__last-error">
          {{ $t('fen-pi-cai-jue-shang-ci-cuo-wu', [dmlResolutionLastError]) }}
        </div>
        <RadioGroup v-model="dmlResolutionForm.resolution" class="dml-resolution__options">
          <Radio label="RERUN_BATCH">
            {{ $t('fen-pi-cai-jue-rerun') }}
            <span class="dml-resolution__option-desc">{{ $t('fen-pi-cai-jue-rerun-shuo-ming') }}</span>
          </Radio>
          <Radio label="SKIP_BATCH">
            {{ $t('fen-pi-cai-jue-skip') }}
            <span class="dml-resolution__option-desc">{{ $t('fen-pi-cai-jue-skip-shuo-ming') }}</span>
          </Radio>
        </RadioGroup>
      </div>
      <template #footer>
        <Button type="primary" :loading="dmlResolutionSubmitting" :disabled="dmlResolutionSubmitting" @click="submitDmlResolution">
          {{ $t('que-ding') }}
        </Button>
        <Button @click="closeDmlResolutionModal">{{ $t('qu-xiao') }}</Button>
      </template>
    </CCModal>
  </div>
</template>

<script>
import appLogger from '@/utils/logger';
import { mapState } from 'vuex';
import { TICKET_PROCESS_STATUS } from '@/const';
import ReadOnlyEditor from '@/components/editor/ReadOnlyEditor';
import copyMixin from '@/mixins/copyMixin';
import { isCk, isMongoDB, RULE_WARN_LEVEL } from '@/utils';
import GhostGroupPanel from './components/GhostGroupPanel.vue';
import DmlBatchGroupPanel from './components/DmlBatchGroupPanel.vue';
import { SEGMENT_STATUS_I18N, segmentRangeI18n } from '@/const/ghost';
import { segmentTypeBadgeI18n, mapDmlBatchSegmentError } from '@/const/dmlBatch';

const TICKET_AUTO_REFRESH_INTERVAL_MS = 5000;
const TICKET_TERMINAL_STATUSES = new Set(['REJECTED', 'FINISHED', 'CLOSED', 'CANCELED', 'FAILED']);

const dmlExplainChange = (row) => ({
  actions: [...(row.actions || [])].sort(),
  subjects: [...(row.subjects || [])].sort()
});

const dmlExplainChangeKey = (row) => {
  const change = dmlExplainChange(row);
  return JSON.stringify([change.actions, change.subjects]);
};

const aggregateDmlExplainDetails = (details) => {
  const groups = new Map();
  details.forEach((row) => {
    const key = dmlExplainChangeKey(row);
    if (!groups.has(key)) {
      groups.set(key, {
        ...dmlExplainChange(row),
        details: []
      });
    }
    groups.get(key).details.push(row);
  });
  return [...groups.values()].map((group) => {
    const indices = [...new Set(group.details.map((row) => row.index))].sort((left, right) => left - right);
    const statementStartLines = [...new Set(group.details.map((row) => row.statementStartLine).filter((line) => line > 0))].sort(
      (left, right) => left - right
    );
    const statuses = [...new Set(group.details.map((row) => row.status).filter(Boolean))];
    const messages = [...new Set(group.details.map((row) => row.message).filter(Boolean))];
    const estimates = group.details.map((row) => row.estimatedAffectedRows);
    const allEstimated = estimates.every((value) => value != null);
    return {
      ...group,
      indices,
      statementStartLines,
      statementCount: indices.length,
      status: statuses.join(' / '),
      message: messages.join(' / '),
      estimatedAffectedRows: allEstimated ? estimates.reduce((total, value) => total + value, 0) : null
    };
  });
};

const AUTO_EXEC_JOB_STATUS_I18N_KEYS = {
  INIT: 'ticket-execution-waiting',
  WAIT_EXEC: 'ticket-execution-waiting',
  EXECUTING: 'ticket-execution-running',
  FAILED: 'ticket-execution-failed',
  PAUSE: 'ticket-execution-paused',
  PAUSING: 'ticket-execution-pausing',
  FINISH: 'ticket-execution-complete',
  TERMINATION: 'ticket-execution-terminated'
};

const AUTO_EXEC_TASK_STATUS_I18N_KEYS = {
  WAIT_EXEC: 'ticket-execution-waiting',
  EXECUTING: 'ticket-execution-running',
  WAIT_CONFIRM: 'ticket-execution-awaiting-confirmation',
  FAILED: 'ticket-execution-failed',
  FINISH: 'ticket-execution-complete',
  ROLLBACK: 'ticket-execution-rollback',
  CANCELED: 'ticket-execution-canceled'
};

export default {
  name: 'TicketDetail',
  components: {
    ReadOnlyEditor,
    GhostGroupPanel,
    DmlBatchGroupPanel
  },
  mixins: [copyMixin],
  data() {
    return {
      autoExec: false,
      RULE_WARN_LEVEL,
      noPassedRuleList: [],
      analysisBehaviors: [],
      analysisSqlCount: null,
      analysisResultTab: 'BEHAVIOR_ANALYSIS',
      recognizedContentColumns: [
        {
          title: this.$t('zi-yuan-lei-xing'),
          slot: 'resourceType',
          width: 180
        },
        {
          title: this.$t('zi-yuan-lu-jing'),
          key: 'resourcePath'
        },
        {
          title: this.$t('cao-zuo'),
          slot: 'actions',
          width: 320
        }
      ],
      showCheckedOnlyError: false,
      showContinueSkipAutoExecTaskModal: false,
      showSkipAutoExecTaskModal: false,
      showStopAutoExecJobModal: false,
      showEndAutoExecJobModal: false,
      showRetryAutoExecJobModal: false,
      showAutoExecTaskSQLModal: false,
      showAutoExecJobLogModal: false,
      showAutoExecTaskLogModal: false,
      autoExecJobLogColumns: [
        {
          title: this.$t('deng-ji'),
          key: 'logLevel',
          width: 100
        },
        {
          title: this.$t('shi-jian'),
          key: 'time',
          width: 200
        },
        {
          title: this.$t('nei-rong'),
          key: 'content'
        }
      ],
      autoExecJobLogList: [],
      autoExecTaskLogList: [],
      selectedAutoExecTask: {},
      selectedAutoExecTaskSql: '',
      autoExecTaskSqlLoading: false,
      autoExecTaskColumns: [],
      autoExecTaskColumnsWithTrans: [
        {
          title: this.$t('xu-hao'),
          key: 'executeOrder',
          width: 80
        },
        {
          title: this.$t('zhi-xing-ci-shu'),
          key: 'execCount',
          width: 100
        },
        // {
        //   title: '影响行数',
        //   key: 'affectLine',
        //   width: 100
        // },
        // {
        //   title: '事务编号',
        //   key: 'transactionGroup',
        //   width: 100
        // },
        {
          title: this.$t('zhuang-tai'),
          slot: 'status',
          width: 100
        },
        {
          title: this.$t('sql-yu-ju'),
          key: 'execSql',
          ellipsis: true
        },
        {
          title: this.$t('cao-zuo'),
          width: 200,
          fixed: 'right',
          slot: 'action'
        }
      ],
      autoExecTaskColumnsWithoutTrans: [
        {
          title: this.$t('xu-hao'),
          key: 'executeOrder',
          width: 80
        },
        {
          title: this.$t('zhi-xing-ci-shu'),
          key: 'execCount',
          width: 100
        },
        // {
        //   title: '影响行数',
        //   key: 'affectLine',
        //   width: 100
        // },
        {
          title: this.$t('zhuang-tai'),
          slot: 'status',
          width: 100
        },
        {
          title: this.$t('sql-yu-ju'),
          key: 'execSql',
          ellipsis: true
        },
        {
          title: this.$t('cao-zuo'),
          width: 200,
          fixed: 'right',
          slot: 'action'
        }
      ],
      autoExecJobInfo: {},
      autoExecTaskList: [],
      page: 1,
      pageSize: 10,
      total: 0,
      showCloseTicketModal: false,
      activeSqlTab: 'raw',
      showAutoExecuteModal: false,
      showManualExecuteModal: false,
      showRollbackSqlModal: false,
      showImpactScopeModal: false,
      showTicketContentModal: false,
      sqlContentAction: '',
      showApprovalModal: false,
      approvalData: {
        action: 'approve',
        comment: ''
      },
      taskList: [],
      startId: 0,
      exportJobList: [],
      preStartIds: [],
      ticketId: 0,
      ticketDetail: {},
      ticketSqlContent: '',
      ticketSqlTotalLines: 1,
      ticketSqlNextStartLine: 1,
      ticketSqlLoadingMore: false,
      ticketSqlContentInitialized: false,
      ticketAutoRefreshActive: false,
      ticketAutoRefreshTimer: null,
      analysisResultsExpanded: false,
      taskExecutionExpanded: true,
      durationNow: Date.now(),
      durationTimer: null,
      TICKET_PROCESS_STATUS,
      loading: false,
      confirmSubmitting: false,
      confirmInfo: {
        autoExecConfig: {}
      },
      autoExecuteRule: {},
      showCancelTicketModal: false,
      selectedStepKey: '',
      ticketStepManuallySelected: false,
      activityStatus: {
        NEW: this.$t('chu-shi-hua'),
        RUNNING: this.$t('deng-dai-shen-pi'),
        CANCELED: this.$t('yi-qu-xiao'),
        COMPLETED: this.$t('yi-tong-guo'),
        REFUSE: this.$t('yi-ju-jue')
      },
      executionActivityStatus: {
        NEW: this.$t('chu-shi-hua'),
        RUNNING: this.$t('ticket-exec-step-running'),
        CANCELED: this.$t('yi-qu-xiao'),
        COMPLETED: this.$t('ticket-exec-step-completed'),
        REFUSE: this.$t('ticket-exec-step-failed')
      },
      thirdPartyName: {
        DingTalk: this.$t('ding-ding-shen-pi'),
        Feishu: this.$t('fei-shu-shen-pi'),
        Wechat: this.$t('wei-xin-shen-pi')
      },
      ticketType: '',
      authList: [],
      v2GroupList: [],
      v2GroupLoading: false,
      v2RetryLoading: false,
      v2ReturnLoading: false,
      v2ResubmitLoading: false,
      v2ReturnReason: '',
      showReturnTicketModal: false,
      v2ResubmitReason: '',
      v2ResubmitRollBackSql: '',
      showResubmitModal: false,
      v2ResubmitGroupId: null,
      v2ResubmitSqlMap: {},
      // B6 M4: per-group user overrides of the segment list collapse state (groupId → boolean);
      // an absent entry falls back to the status-derived default in segmentsOpen()
      segmentsExpanded: {},
      releaseSummary: null,
      // B7 N3: the window-crash manual-verdict modal (design §7.4 — RERUN_BATCH/SKIP_BATCH over an
      // UNRESOLVED DML_BATCH segment; submit = retryGroup with the additive dmlBatchResolution)
      showDmlResolutionModal: false,
      dmlResolutionSubmitting: false,
      dmlResolutionForm: {
        resolution: 'RERUN_BATCH'
      },
      dmlResolutionGroup: null,
      dmlResolutionSeg: null
    };
  },
  async mounted() {
    this.ticketId = this.$route.params.id;
    this.ticketAutoRefreshActive = true;
    await this.getTicketDetail('init');
    this.scheduleTicketAutoRefresh();
    this.durationTimer = window.setInterval(() => {
      this.durationNow = Date.now();
    }, 1000);
  },
  beforeUnmount() {
    this.stopTicketAutoRefresh();
    if (this.durationTimer) {
      window.clearInterval(this.durationTimer);
    }
  },
  computed: {
    ...mapState(['userInfo', 'myAuth', 'dmGlobalSetting']),
    // B6 M1: every /ghost/* endpoint is DM_GHOST_EXEC-gated — holders get the live panel
    // (progress/controls/log), everyone else the read-only groupList projection (design §6.9).
    // B7 N3 (裁决 #15): the /dmlBatch/* controls + the window-crash verdict entry reuse the SAME
    // permission bit (canGhostExec is deliberately the single gate — zero new frontend auth keys).
    canGhostExec() {
      return this.myAuth.includes('DM_GHOST_EXEC');
    },
    // B7 N3 confirm-dialog gate: any group of this ticket carries a DML_BATCH segment (plan ∨
    // runtime) → the transaction switch is disabled (批间独立提交, the server force is the
    // backstop — 裁决 #11) and SPECIFY_TIME is rejected (AsyncTask 无定时承载 — 裁决 #17).
    ticketHasDmlBatchSegment() {
      return this.v2GroupList.some((group) => this.groupSegments(group).some((seg) => seg && seg.type === 'DML_BATCH'));
    },
    // ---- the adjudication modal's material (design §7.4: 批定位+幂等红绿字+lastBatchError) ----
    dmlResolutionProgress() {
      const group = this.dmlResolutionGroup;
      const seg = this.dmlResolutionSeg;
      if (!group || !seg) {
        return null;
      }
      const p = group.dmlBatchProgress;
      if (p && p.segmentIndex === seg.segIdx) {
        return p;
      }
      return null;
    },
    dmlResolutionIntentSeq() {
      const summary = this.dmlResolutionSeg ? this.dmlResolutionSeg.summary : null;
      if (summary && summary.intentSeq != null) {
        return summary.intentSeq;
      }
      if (this.dmlResolutionProgress && this.dmlResolutionProgress.intentSeq != null) {
        return this.dmlResolutionProgress.intentSeq;
      }
      return '-';
    },
    // conservative direction: green ONLY on an explicit true (a missing mark shows the red copy)
    dmlResolutionIdempotent() {
      return !!(this.dmlResolutionSeg && this.dmlResolutionSeg.idempotent === true);
    },
    dmlResolutionLastError() {
      if (this.dmlResolutionProgress && this.dmlResolutionProgress.lastBatchError) {
        return this.dmlResolutionProgress.lastBatchError;
      }
      const summary = this.dmlResolutionSeg ? this.dmlResolutionSeg.summary : null;
      return summary && summary.error ? summary.error : '';
    },
    // the N2 check re-freeze shape (上次 SKIP 未消费即终态) → 「上次跳过未成功,请重新裁决」 copy
    dmlResolutionSkipVerdictPending() {
      return !!(this.dmlResolutionProgress && this.dmlResolutionProgress.skipVerdictPending === true);
    },
    ticketProgressSteps() {
      const steps = [
        {
          key: 'CREATE',
          title: this.$t('chuang-jian'),
          time: this.ticketDetail.gmtCreate,
          startTime: this.ticketDetail.gmtCreate,
          handler: this.ticketDetail.userName,
          statusText: this.TICKET_PROCESS_STATUS.FINISH,
          statusClass: 'finished',
          state: 'finished',
          icon: 'md-checkmark',
          stage: 'CREATE',
          activities: []
        }
      ];
      const processes = this.ticketDetail.ticketProcessVOList || [];
      let activeFound = TICKET_TERMINAL_STATUSES.has(this.ticketDetail.ticketStatus);
      processes.forEach((process) => {
        let state = 'pending';
        let icon = 'md-time';
        if (process.ticketProcessStatus === 'FINISH') {
          state = 'finished';
          icon = 'md-checkmark';
        } else if (['REJECT', 'FAIL', 'CLOSED'].includes(process.ticketProcessStatus)) {
          state = 'failed';
          icon = 'md-close';
          activeFound = true;
        } else if (!activeFound) {
          state = 'active';
          icon = 'md-time';
          activeFound = true;
        }
        let statusText = this.TICKET_PROCESS_STATUS[process.ticketProcessStatus] || '-';
        if (process.ticketStage === 'EXPLAIN' && state !== 'finished') {
          statusText = this.analysisProcessStatusText;
        }
        if (state === 'active' && process.ticketStage === 'APPROVAL') {
          statusText = this.$t('deng-dai-shen-pi');
        }
        let startTime = process.gmtCreate;
        const activityStartTimes = (process.activityList || []).map((activity) => activity.startTime).filter(Boolean);
        if (activityStartTimes.length) {
          startTime = activityStartTimes[0];
        }
        steps.push({
          key: String(process.ticketProcessId),
          title: process.ticketStageTitle,
          time: process.finishTime || startTime,
          startTime,
          handler: state === 'pending' ? '' : process.execUserName,
          statusText,
          statusClass: state === 'pending' ? 'init' : state,
          state,
          icon,
          stage: process.ticketStage,
          activities: process.activityList || [],
          process
        });
      });
      return steps;
    },
    selectedTicketStep() {
      return this.ticketProgressSteps.find((step) => step.key === this.selectedStepKey) || this.ticketProgressSteps[0];
    },
    selectedTicketRejectionReason() {
      if (this.selectedTicketStep?.stage !== 'APPROVAL' || this.selectedTicketStep.process?.ticketProcessStatus !== 'REJECT') {
        return '';
      }
      return this.ticketDetail.approComment?.trim() || '';
    },
    formattedAuths() {
      return this.authList.map((authItem) => {
        let effectiveTime = this.$t('yong-jiu');
        if (authItem.startTime && authItem.endTime) {
          effectiveTime = `${authItem.startTime} - ${authItem.endTime}`;
        } else if (authItem.startTime) {
          effectiveTime = `${this.$t('cong-0')} ${authItem.startTime} ${this.$t('kai-shi-zhi-yong-jiu')}`;
        } else if (authItem.endTime) {
          effectiveTime = `${this.$t('cong-shen-pi-tong-guo-dao')} ${authItem.endTime} ${this.$t('jie-shu')}`;
        }
        return {
          resId: authItem.resId,
          resInstId: authItem.resInstId || String(authItem.resId),
          resDesc: authItem.resDesc || authItem.resInstId || String(authItem.resId),
          dataSourceType: authItem.dataSourceType,
          resPaths: `/${authItem.resPaths.join(' / ')}`,
          authLabels: authItem.authLabels,
          effectiveTime
        };
      });
    },
    recognizedBehaviorRows() {
      const behaviorItem = this.analysisItems.find((item) => item.activityTitle === 'BEHAVIOR_ANALYSIS');
      const behaviors = behaviorItem?.behaviors ?? this.analysisBehaviors;
      return [...behaviors]
        .map((behavior) => ({
          ...behavior,
          actionItems: Object.keys(behavior.actionCounts || {}).length
            ? Object.entries(behavior.actionCounts).map(([action, count]) => ({ action, count }))
            : [...(behavior.actions || [])].sort().map((action) => ({ action, count: null }))
        }))
        .sort((left, right) => {
          const typeCompare = (left.resourceType || '').localeCompare(right.resourceType || '');
          return typeCompare || (left.resourcePath || '').localeCompare(right.resourcePath || '');
        });
    },
    analysisProcess() {
      return (this.ticketDetail.ticketProcessVOList || []).find((item) => item.ticketStage === 'EXPLAIN');
    },
    analysisItems() {
      return [...(this.analysisProcess?.activityList || [])].sort(
        (left, right) => (left.displayOrder ?? Number.MAX_SAFE_INTEGER) - (right.displayOrder ?? Number.MAX_SAFE_INTEGER)
      );
    },
    analysisRuleResults() {
      const ruleItem = this.analysisItems.find((item) => item.activityTitle === 'SECURITY_RULE');
      return ruleItem?.ruleResults ?? this.noPassedRuleList;
    },
    analysisRunningCount() {
      return this.analysisItems.filter((item) => item.activityStatus === 'RUNNING').length;
    },
    analysisProcessStatusText() {
      if (this.analysisItems.length > 0 && this.analysisItems.every((item) => item.activityStatus === 'COMPLETED')) {
        return this.$t('ticket-analysis-complete');
      }
      if (this.analysisRunningCount > 0) {
        return this.$t('ticket-analysis-running-count', { count: this.analysisRunningCount });
      }
      if (this.analysisItems.some((item) => item.activityStatus === 'REFUSE')) {
        return this.$t('ticket-analysis-failed');
      }
      return this.$t('ticket-analysis-waiting');
    },
    hasError() {
      return this.analysisRuleResults.some((rule) => rule.ruleLevel !== 'SUGGEST');
    },
    selectedAutoExecTaskIndex() {
      return this.autoExecTaskList.findIndex((task) => task.taskId === this.selectedAutoExecTask.taskId);
    },
    canViewPreviousAutoExecTask() {
      if (this.selectedAutoExecTaskIndex < 0) {
        return false;
      }
      return (this.page - 1) * this.pageSize + this.selectedAutoExecTaskIndex > 0;
    },
    canViewNextAutoExecTask() {
      if (this.selectedAutoExecTaskIndex < 0) {
        return false;
      }
      return (this.page - 1) * this.pageSize + this.selectedAutoExecTaskIndex < this.total - 1;
    }
  },
  watch: {
    analysisItems(items) {
      if (!items.some((item) => item.activityTitle === this.analysisResultTab)) {
        this.analysisResultTab = items[0]?.activityTitle || '';
      }
    },
    canReturnTicket() {
      const status = this.ticketDetail.ticketStatus;
      const hasFailedGroup = this.v2GroupList.some((g) => g.execStatus === 'FAILED');
      const isOwner = this.userInfo && this.ticketDetail.ownerUid && this.userInfo.uid === this.ticketDetail.ownerUid;
      return (status === 'EXEC_FAIL' || status === 'WAIT_EXEC') && hasFailedGroup && isOwner;
    }
  },
  methods: {
    isCk,
    isMongoDB,
    handleBack() {
      this.$router.push('/ticket');
    },
    autoExecJobStatusText(status) {
      const key = AUTO_EXEC_JOB_STATUS_I18N_KEYS[status];
      return key ? this.$t(key) : status || '-';
    },
    autoExecTaskStatusText(status) {
      const key = AUTO_EXEC_TASK_STATUS_I18N_KEYS[status];
      return key ? this.$t(key) : status || '-';
    },
    formatResourcePath(targetInfo) {
      if (!targetInfo) {
        return '-';
      }
      return targetInfo.replace(/^\/+/, '');
    },
    selectTicketStep(step) {
      this.ticketStepManuallySelected = true;
      this.selectedStepKey = step.key;
    },
    isAnalysisResultExpanded(activityTitle) {
      return this.analysisResultsExpanded && this.analysisResultTab === activityTitle;
    },
    toggleAnalysisResult(activityTitle) {
      if (this.isAnalysisResultExpanded(activityTitle)) {
        this.analysisResultsExpanded = false;
        return;
      }
      this.analysisResultTab = activityTitle;
      this.analysisResultsExpanded = true;
    },
    behaviorStatementCount(item) {
      return item.statementCount ?? this.analysisSqlCount;
    },
    behaviorSummaryText(item) {
      const values = {
        statementCount: this.behaviorStatementCount(item) || 0,
        objectCount: this.recognizedBehaviorRows.length,
        behaviorCount: item.behaviorCount || 0
      };
      return item.behaviorCount == null
        ? this.$t('ticket-analysis-behavior-summary-legacy', values)
        : this.$t('ticket-analysis-behavior-summary', values);
    },
    dmlExplainTableColumns() {
      let statementCountTitle = this.$t('ticket-analysis-dml-explain-statement-count');
      const maxStatements = this.dmGlobalSetting.approvalExplainMaxSize;
      if (maxStatements > 0) {
        statementCountTitle = this.$t('ticket-analysis-dml-explain-statement-count-with-limit', {
          count: maxStatements
        });
      }
      return [
        {
          title: this.$t('ticket-analysis-dml-explain-rows'),
          slot: 'estimatedAffectedRows',
          width: 150
        },
        {
          title: this.$t('ticket-analysis-dml-explain-actions'),
          slot: 'actions',
          width: 180
        },
        {
          title: this.$t('ticket-analysis-dml-explain-subjects'),
          slot: 'subjects'
        },
        {
          title: statementCountTitle,
          slot: 'statementCount',
          width: 320
        },
        {
          title: this.$t('shuo-ming'),
          slot: 'description',
          width: 220
        }
      ];
    },
    analysisSummaryText(item) {
      if (item.activityTitle === 'BEHAVIOR_ANALYSIS' && item.activityStatus === 'COMPLETED') {
        return this.behaviorSummaryText(item);
      }
      if (item.activityTitle === 'DML_EXPLAIN' && item.activityStatus === 'COMPLETED') {
        return this.dmlExplainDetailText(item);
      }
      return this.analysisResultText(item);
    },
    behaviorActionText(action) {
      return action.count == null ? action.action : this.$t('ticket-analysis-action-summary', action);
    },
    analysisTypeText(type) {
      const keyMap = {
        SQL_RECOGNITION: 'ticket-analysis-sql-recognition',
        BEHAVIOR_ANALYSIS: 'ticket-analysis-behavior',
        SECURITY_RULE: 'ticket-analysis-security-rule',
        DML_EXPLAIN: 'ticket-analysis-dml-explain'
      };
      return this.$t(keyMap[type] || type);
    },
    analysisStatusText(status) {
      const keyMap = {
        NEW: 'ticket-analysis-waiting',
        RUNNING: 'ticket-analysis-running',
        COMPLETED: 'ticket-analysis-complete',
        REFUSE: 'ticket-analysis-failed'
      };
      return this.$t(keyMap[status] || status);
    },
    isExecutionActivity(activity) {
      return String(activity.activityTitle || '').startsWith('EXECUTION_');
    },
    stepNodeLabel(activity) {
      return this.isExecutionActivity(activity) ? this.$t('ticket-exec-step') : this.$t('shen-pi-jie-dian');
    },
    stepStatusText(activity) {
      const statusMap = this.isExecutionActivity(activity) ? this.executionActivityStatus : this.activityStatus;
      return statusMap[activity.activityStatus] || activity.activityStatus;
    },
    analysisStatusClass(status) {
      const classMap = {
        NEW: 'init',
        RUNNING: 'running',
        COMPLETED: 'finished',
        REFUSE: 'failed',
        CANCELED: 'failed'
      };
      return `status-${classMap[status] || 'init'}`;
    },
    analysisResultText(item) {
      if (item.activityStatus === 'REFUSE') {
        return item.remark || this.$t('ticket-analysis-failed');
      }
      if (item.activityStatus === 'NEW') {
        return '--';
      }
      if (item.activityStatus === 'RUNNING') {
        if (item.totalBytes > 0 && item.processedBytes != null) {
          const percentage = Math.min(100, Math.floor((item.processedBytes * 100) / item.totalBytes));
          return this.$t('ticket-analysis-read-progress', {
            processed: this.formatFileSize(item.processedBytes),
            total: this.formatFileSize(item.totalBytes),
            percentage,
            count: item.processedCount || 0
          });
        }
        let progressText;
        if (item.totalCount > 0) {
          const processed = item.processedCount || 0;
          const percentage = Math.min(100, Math.floor((processed * 100) / item.totalCount));
          progressText = this.$t('ticket-analysis-count-progress', {
            processed,
            total: item.totalCount,
            percentage
          });
        } else {
          progressText =
            item.processedCount == null
              ? this.$t('ticket-analysis-running')
              : this.$t('ticket-analysis-processed-count', { count: item.processedCount });
        }
        const phaseKey = item.analysisPhase ? `ticket-analysis-phase-${item.analysisPhase}` : null;
        return phaseKey ? this.$t('ticket-analysis-phase-progress', { phase: this.$t(phaseKey), progress: progressText }) : progressText;
      }
      if (item.activityTitle === 'SQL_RECOGNITION' && item.statementCount != null) {
        return this.$t('ticket-analysis-sql-result', { count: item.statementCount });
      }
      if (item.activityTitle === 'BEHAVIOR_ANALYSIS' && item.objectCount != null) {
        return this.$t('ticket-analysis-behavior-summary-legacy', {
          statementCount: item.statementCount ?? this.analysisSqlCount ?? 0,
          objectCount: item.objectCount
        });
      }
      if (item.activityTitle === 'SECURITY_RULE' && item.ruleCount != null) {
        return item.ruleCount === 0
          ? this.$t('ticket-analysis-security-passed')
          : this.$t('ticket-analysis-security-result', { count: item.ruleCount });
      }
      if (item.activityTitle === 'DML_EXPLAIN' && item.dmlStatementCount != null) {
        return this.$t('ticket-analysis-dml-explain-result', {
          total: item.dmlStatementCount,
          skipped: (item.skippedBySizeLimit || 0) + (item.skippedByCountLimit || 0)
        });
      }
      return '--';
    },
    analysisEmptyText(item) {
      if (item.activityStatus === 'RUNNING') {
        return this.$t('ticket-analysis-running-detail');
      }
      return this.analysisResultText(item);
    },
    dmlExplainDetailText(item) {
      const failed = item.failedExplainCount || 0;
      const total = item.dmlStatementCount || 0;
      if (total === 0) {
        return this.$t('ticket-analysis-dml-explain-summary-empty', { total });
      }
      const sizeSkipped = item.skippedBySizeLimit || 0;
      const countSkipped = item.skippedByCountLimit || 0;
      const skipped = sizeSkipped + countSkipped;
      const estimates = (item.explainResults || []).map((result) => result.estimatedAffectedRows).filter((value) => value != null);
      let text;
      if (estimates.length) {
        const affectedRows = estimates.reduce((sum, value) => sum + value, 0);
        const summaryKey = skipped > 0 || failed > 0 ? 'ticket-analysis-dml-explain-summary-minimum' : 'ticket-analysis-dml-explain-summary';
        text = this.$t(summaryKey, { total, affectedRows });
      } else {
        text = this.$t('ticket-analysis-dml-explain-summary-without-estimate', { total });
      }
      if (skipped > 0) {
        const reasons = [];
        if (sizeSkipped > 0) {
          reasons.push(this.$t('ticket-analysis-dml-explain-summary-oversized', { count: sizeSkipped }));
        }
        if (countSkipped > 0) {
          reasons.push(this.$t('ticket-analysis-dml-explain-summary-over-count', { count: countSkipped }));
        }
        text += this.$t('ticket-analysis-dml-explain-summary-skipped', {
          skipped,
          reasons: reasons.join(this.$t('ticket-analysis-dml-explain-summary-reason-separator'))
        });
      }
      if (failed > 0) {
        text += this.$t('ticket-analysis-dml-explain-summary-failed', { failed });
      }
      return text;
    },
    dmlExplainStatementText(row) {
      if (!row.statementStartLines.length) {
        return this.$t('ticket-analysis-dml-explain-statement-summary-without-lines', {
          count: row.statementCount
        });
      }
      return this.$t('ticket-analysis-dml-explain-statement-summary', {
        count: row.statementCount,
        lines: row.statementStartLines.join(this.$t('ticket-analysis-dml-explain-line-separator'))
      });
    },
    dmlExplainDescription(row) {
      const status = row.status
        .split(' / ')
        .map((value) => this.$t(`ticket-analysis-dml-explain-status-${value}`))
        .join(' / ');
      if (!row.message) {
        return status;
      }
      return this.$t('ticket-analysis-dml-explain-description-with-reason', { status, reason: row.message });
    },
    dmlExplainRows(item) {
      const statements = new Map();
      [...(item.explainResults || [])]
        .sort((left, right) => left.index - right.index)
        .forEach((row) => {
          if (!statements.has(row.index)) {
            statements.set(row.index, []);
          }
          statements.get(row.index).push(row);
        });

      // A segment only combines adjacent SQL statements whose complete action and object sets match.
      const segments = [];
      for (const [index, details] of statements) {
        const signature = JSON.stringify(details.map(dmlExplainChangeKey).sort());
        const previous = segments[segments.length - 1];
        if (previous && index === previous.lastIndex + 1 && signature === previous.signature) {
          previous.lastIndex = index;
          previous.details.push(...details);
        } else {
          segments.push({
            signature,
            lastIndex: index,
            details: [...details]
          });
        }
      }
      return segments.flatMap((segment) => aggregateDmlExplainDetails(segment.details));
    },
    analysisElapsed(item) {
      if (!item.startTimeUtc) {
        return '--';
      }
      const end = item.finishTimeUtc || this.durationNow;
      return this.formatElapsed(end - item.startTimeUtc);
    },
    formatElapsed(milliseconds) {
      const totalSeconds = Math.max(0, Math.floor(milliseconds / 1000));
      const hours = Math.floor(totalSeconds / 3600);
      const minutes = Math.floor((totalSeconds % 3600) / 60);
      const seconds = totalSeconds % 60;
      return [hours, minutes, seconds].map((value) => String(value).padStart(2, '0')).join(':');
    },
    async loadTicketSqlContent(append = false) {
      if (this.ticketSqlLoadingMore) {
        return;
      }
      const lineCount = 1000;
      const startLine = append ? this.ticketSqlNextStartLine : 1;
      if (append && startLine > this.ticketSqlTotalLines) {
        return;
      }
      this.ticketSqlLoadingMore = true;
      try {
        const res = await this.$services.dmTicketPreviewApprovalSql({
          data: {
            ticketId: this.ticketId,
            startLine,
            lineCount
          }
        });
        if (!res.success) {
          return;
        }
        const content = res.data?.content || '';
        this.ticketSqlTotalLines = res.data?.totalLines || 1;
        this.ticketSqlNextStartLine = Math.min(this.ticketSqlTotalLines + 1, startLine + lineCount);
        if (!append) {
          this.ticketSqlContent = content;
          return;
        }
        this.ticketSqlContent += `\n${content}`;
      } finally {
        this.ticketSqlLoadingMore = false;
      }
    },
    async loadNextTicketSqlContent() {
      await this.loadTicketSqlContent(true);
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
    stopTicketAutoRefresh() {
      this.ticketAutoRefreshActive = false;
      if (this.ticketAutoRefreshTimer) {
        window.clearTimeout(this.ticketAutoRefreshTimer);
        this.ticketAutoRefreshTimer = null;
      }
    },
    scheduleTicketAutoRefresh() {
      if (this.ticketAutoRefreshTimer) {
        window.clearTimeout(this.ticketAutoRefreshTimer);
        this.ticketAutoRefreshTimer = null;
      }

      if (!this.ticketAutoRefreshActive || TICKET_TERMINAL_STATUSES.has(this.ticketDetail.ticketStatus)) {
        return;
      }

      this.ticketAutoRefreshTimer = window.setTimeout(() => {
        this.refreshTicketAutomatically();
      }, TICKET_AUTO_REFRESH_INTERVAL_MS);
    },
    async refreshTicketAutomatically() {
      this.ticketAutoRefreshTimer = null;
      if (document.hidden || this.loading) {
        this.scheduleTicketAutoRefresh();
        return;
      }

      try {
        await this.getTicketDetail('auto');
      } finally {
        this.scheduleTicketAutoRefresh();
      }
    },
    handleShowEndAutoExecJobModal() {
      this.showEndAutoExecJobModal = true;
    },
    handleShowRetryAutoExecJobModal() {
      this.showRetryAutoExecJobModal = true;
    },
    handleShowStopAutoExecJobModal() {
      this.showStopAutoExecJobModal = true;
    },
    handleShowSkipAutoExecTaskModal(task) {
      this.showSkipAutoExecTaskModal = true;
      this.selectedAutoExecTask = task;
    },
    handleShowContinueAutoExecTaskModal(task) {
      this.showContinueSkipAutoExecTaskModal = true;
      this.selectedAutoExecTask = task;
    },
    async handleEndAutoExecJob() {
      const res = await this.$services.dmTicketEndAutoExecJob({
        data: {
          ticketId: this.ticketId
        }
      });

      if (res.success) {
        this.$Message.success(this.$t('cao-zuo-cheng-gong'));
        await this.getTicketDetail();
        await this.queryAutoExecJobInfo();
        await this.queryAutoExecTaskList();
      }
    },
    async handleRetryAutoExecJob() {
      const res = await this.$services.dmTicketRetryAutoExecJob({
        data: {
          ticketId: this.ticketId
        }
      });

      if (res.success) {
        this.$Message.success(this.$t('zhong-shi-cheng-gong'));
        await this.getTicketDetail();
        await this.queryAutoExecJobInfo();
        await this.queryAutoExecTaskList();
      }
      this.handleCloseModal();
    },
    async handleStopAutoExecJob() {
      const res = await this.$services.dmTicketStopAutoExecJob({
        data: {
          ticketId: this.ticketId
        }
      });

      if (res.success) {
        this.$Message.success(this.$t('zan-ting-cheng-gong'));
        await this.getTicketDetail();
        await this.queryAutoExecJobInfo();
        await this.queryAutoExecTaskList();
      }
      this.handleCloseModal();
    },
    async handleSkipAutoExecTask() {
      const res = await this.$services.dmTicketSkipAutoExecTask({
        data: {
          taskId: this.selectedAutoExecTask.taskId,
          ticketId: this.ticketId
        }
      });

      if (res.success) {
        this.$Message.success(this.$t('tiao-guo-cheng-gong'));
        await this.getTicketDetail();
        await this.queryAutoExecJobInfo();
        await this.queryAutoExecTaskList();
      }
      this.handleCloseModal();
    },
    async handleContinueAutoExecTask() {
      const res = await this.$services.dmTicketContinueAutoExecTask({
        data: {
          taskId: this.selectedAutoExecTask.taskId,
          ticketId: this.ticketId
        }
      });

      if (res.success) {
        this.$Message.success(this.$t('cao-zuo-cheng-gong'));
        await this.getTicketDetail();
        await this.queryAutoExecJobInfo();
        await this.queryAutoExecTaskList();
      }
    },
    handleTaskPageChange(page) {
      this.page = page;
      this.queryAutoExecTaskList();
    },
    async handleAutoExecSQL(task) {
      this.autoExecTaskSqlLoading = true;
      try {
        const res = await this.$services.dmTicketQueryAutoExecTaskSql({
          data: {
            ticketId: this.ticketId,
            taskId: task.taskId
          }
        });
        if (res.success) {
          this.selectedAutoExecTask = task;
          this.selectedAutoExecTaskSql = res.data || '';
          this.showAutoExecTaskSQLModal = true;
        }
      } finally {
        this.autoExecTaskSqlLoading = false;
      }
    },
    async handleSwitchAutoExecSQL(direction) {
      let targetIndex = this.selectedAutoExecTaskIndex + direction;
      if (targetIndex < 0 || targetIndex >= this.autoExecTaskList.length) {
        const loaded = await this.queryAutoExecTaskList(this.page + direction);
        if (!loaded) {
          return;
        }
        targetIndex = direction < 0 ? this.autoExecTaskList.length - 1 : 0;
      }
      const targetTask = this.autoExecTaskList[targetIndex];
      if (targetTask) {
        await this.handleAutoExecSQL(targetTask);
      }
    },
    handleRefreshTaskList() {
      this.queryAutoExecJobInfo();
      this.queryAutoExecTaskList();
      this.queryAutoExecJobInfo();
    },
    async handleAutoExecLog(task = null) {
      const res = await this.$services.dmTicketAutoExecLog({
        data: {
          taskId: task ? task.taskId : null,
          jobId: this.autoExecJobInfo.id,
          dependBizType: task ? 'AUTO_EXEC_TASK' : 'AUTO_EXEC_JOB'
        }
      });

      if (res.success) {
        if (!task) {
          this.autoExecJobLogList = res.data;
          this.showAutoExecJobLogModal = true;
        } else {
          this.autoExecTaskLogList = res.data;
          this.showAutoExecTaskLogModal = true;
        }
      }
    },
    async queryAutoExecJobInfo() {
      const res = await this.$services.dmTicketQueryAutoExecJobInfo({
        data: {
          ticketId: this.ticketId
        }
      });

      if (res.success) {
        this.autoExecJobInfo = res.data;
        this.autoExecTaskColumns = res.data.enableTransactional ? this.autoExecTaskColumnsWithTrans : this.autoExecTaskColumnsWithoutTrans;
      }
    },
    async queryAutoExecTaskList(targetPage = this.page) {
      const res = await this.$services.dmTicketQueryAutoExecTaskList({
        data: {
          ticketId: this.ticketId,
          page: {
            pageNum: targetPage,
            pageSize: this.pageSize
          }
        }
      });

      if (res.success) {
        this.autoExecTaskList = res.data.records;
        this.page = res.data.current;
        this.pageSize = res.data.size;
        this.total = res.data.total;
        return true;
      }
      return false;
    },
    handleShowManualExecuteModal(type) {
      this.confirmInfo = {
        ticketId: this.ticketId,
        confirmActionType: type,
        confirmUid: this.userInfo.uid,
        conformerUid: this.userInfo.uid,
        comment: '',
        ddlSqlExecType: 'DIRECT',
        noneDdlSqlExecType: 'DIRECT'
      };
      this.showManualExecuteModal = true;
    },
    handleShowAutoExecuteModal(type) {
      this.confirmInfo = {
        ticketId: this.ticketId,
        confirmActionType: type,
        comment: '',
        ddlSqlExecType: 'DIRECT',
        noneDdlSqlExecType: 'DIRECT',
        autoExecConfig: {
          // B7 N3 (裁决 #11): a batched ticket never carries transactional=true — the switch is
          // disabled in the dialog and the server-side dispatch re-validation forces it false
          enableTransactional: false,
          errorStrategy: 'NONE',
          retryWaitTime: 111, // Unit seconds
          retryCount: 2, // Number of retries
          autoExecType: 'IMMEDIATE', // [IMMEDITE, SPECIFY TIME]
          execTime: new Date() // Scheduled implementation time
        }
      };
      this.showAutoExecuteModal = true;
    },
    handleShowRollbackSqlModal() {
      this.showRollbackSqlModal = true;
    },
    handleShowTicketContentModal() {
      this.showTicketContentModal = true;
    },
    async loadFullTicketSql() {
      const chunks = [];
      let startLine = 1;
      let totalLines = Math.max(1, this.ticketSqlTotalLines);
      while (startLine <= totalLines) {
        const res = await this.$services.dmTicketPreviewApprovalSql({
          data: {
            ticketId: this.ticketId,
            startLine,
            lineCount: 1000
          }
        });
        if (!res.success) {
          return '';
        }
        chunks.push(res.data?.content || '');
        totalLines = Math.max(1, res.data?.totalLines || totalLines);
        startLine += 1000;
      }
      return chunks.join('\n');
    },
    async handleCopyTicketSql() {
      this.sqlContentAction = 'copy';
      try {
        const content = await this.loadFullTicketSql();
        if (content) {
          await this.copyText(content);
        }
      } finally {
        this.sqlContentAction = '';
      }
    },
    async handleDownloadTicketSql() {
      this.sqlContentAction = 'download';
      try {
        const content = await this.loadFullTicketSql();
        if (!content) {
          return;
        }
        const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = this.ticketDetail.attachmentFileName || `ticket-${this.ticketDetail.id || this.ticketId}.sql`;
        link.click();
        URL.revokeObjectURL(url);
      } finally {
        this.sqlContentAction = '';
      }
    },
    handleShowCloseTicketModal() {
      this.showCloseTicketModal = true;
    },
    async getTicketDetail(type) {
      const showLoading = type !== 'auto';
      if (showLoading) {
        this.loading = true;
      }
      const data = {
        ticketId: this.ticketId,
        refreshCache: type === 'refresh'
      };
      const res = await this.$services.rdpTicketQueryTicketBaseInfo({ data, modal: showLoading });

      if (showLoading) {
        this.loading = false;
      }
      if (res.success) {
        this.ticketType = res.data?.approBiz;
        this.ticketDetail = res.data;
        this.ticketDetail.ticketProcessVOList.forEach((item) => {
          item.execUserName = '';
          item.execMsg = '';
          if (item.stageContext) {
            const stageContext = JSON.parse(item.stageContext);
            item.execUserName = stageContext.execUserName ? stageContext.execUserName.join(',') : '';
            item.execUserNameList = stageContext.execUserName;
            item.execMsg = stageContext.execMsg;
          }
          if (!item.execUserName && ['EXPLAIN', 'EXECUTION'].includes(item.ticketStage)) {
            const systemHandler = this.$t('clouddm-system-handler');
            item.execUserName = systemHandler;
            item.execUserNameList = [systemHandler];
          }
        });
        const processSteps = this.ticketDetail.ticketProcessVOList || [];
        const validStepKeys = new Set(['CREATE', ...processSteps.map((process) => String(process.ticketProcessId))]);
        if (!this.ticketStepManuallySelected || !validStepKeys.has(this.selectedStepKey)) {
          const currentStep = this.ticketProgressSteps.find((step) => ['active', 'failed'].includes(step.state));
          const defaultStep = currentStep || this.ticketProgressSteps[this.ticketProgressSteps.length - 1];
          this.selectedStepKey = defaultStep?.key || 'CREATE';
          this.ticketStepManuallySelected = false;
        }

        switch (res.data?.approBiz) {
          case 'DATA_SOURCE_AUTH':
            const resAuth = await this.$services.rdpTicketQueryDataSourceAuthTicketDetail({ data: { ticketId: this.ticketId } });
            if (resAuth.success) {
              this.ticketDetail.applyAuths = resAuth.data.applyAuths;
              this.authList = resAuth.data.applyAuths;
            }
            break;
          case 'DM_QUERY':
          case 'DM_CHANGE':
            const initializeSqlContent = !this.ticketSqlContentInitialized;
            const resQuery = await this.$services.dmTicketQueryQueryTicketDetail({
              data: {
                ticketId: this.ticketId
              }
            });
            if (resQuery.success) {
              this.noPassedRuleList = resQuery.data.checkedList || [];
              this.analysisBehaviors = resQuery.data.behaviors || [];
              this.analysisSqlCount = resQuery.data.totalCount ?? null;
              this.autoExec = resQuery.data.autoExec;
              if (resQuery.data?.autoExec) {
                await this.queryAutoExecJobInfo();
                await this.queryAutoExecTaskList();
              }
              this.ticketDetail.ticketMessage = resQuery.data?.ticketMessage || '';
              this.ticketDetail.rollBackSql = resQuery.data?.rollBackSql || '';
              this.ticketDetail.rollBackSqlGenerated = resQuery.data?.rollBackSqlGenerated || false;
              this.ticketDetail.riskLevel = resQuery.data?.riskLevel || '';
              this.ticketDetail.impactScopeText = resQuery.data?.impactScopeText || '';
              this.ticketDetail.contentType = resQuery.data?.contentType || 'INLINE';
              this.ticketDetail.attachmentId = resQuery.data?.attachmentId;
              this.ticketDetail.attachmentFileName = resQuery.data?.attachmentFileName || '';
              this.ticketDetail.attachmentFileSize = resQuery.data?.attachmentFileSize || 0;
              if (initializeSqlContent) {
                await this.loadTicketSqlContent();
                this.ticketSqlContentInitialized = true;
              }
            }
            break;
          default:
            break;
        }
        if (this.ticketDetail.ticketType) {
          await this.loadV2GroupList();
        }
        if (this.ticketType === 'DM_PROD_RELEASE') {
          await this.loadReleaseSummary();
        }
      }
    },
    async loadV2GroupList() {
      if (!this.ticketDetail.ticketType) {
        return;
      }
      this.v2GroupLoading = true;
      try {
        const res = await this.$services.dbChangeV2GroupList({
          data: { ticketId: this.ticketId },
          modal: false
        });
        if (res.success) {
          this.v2GroupList = res.data || [];
          // Pre-populate the resubmit SQL map with current group SQL for REVISION editing
          const sqlMap = {};
          for (const g of this.v2GroupList) {
            if (g.execStatus === 'FAILED') {
              sqlMap[g.groupId] = g.sqlContent || '';
            }
          }
          this.v2ResubmitSqlMap = sqlMap;
        }
      } finally {
        this.v2GroupLoading = false;
      }
    },
    async handleRetryGroup(groupId) {
      if (this.v2RetryLoading) {
        return;
      }
      // B7 N3 (design §7.4): a group whose FAILED DML_BATCH segment froze the unresolvedIntent
      // marker MUST carry the manual verdict (the backend rejects a bare retry with
      // RESOLUTION_REQUIRED) — intercept into the adjudication modal. The modal is DM_GHOST_EXEC
      // -gated (裁决 #15); a read-only user gets the guidance message instead of a doomed request.
      const group = this.v2GroupList.find((g) => g.groupId === groupId);
      const unresolvedSeg = group ? this.findUnresolvedBatchSegment(group) : null;
      if (unresolvedSeg) {
        if (!this.canGhostExec) {
          this.$Message.warning(this.$t('fen-pi-wu-cai-jue-quan-xian'));
          return;
        }
        this.openDmlResolutionModal(group, unresolvedSeg);
        return;
      }
      await this.doRetryGroup(groupId, null);
    },
    async doRetryGroup(groupId, dmlBatchResolution) {
      if (this.v2RetryLoading) {
        return;
      }
      this.v2RetryLoading = true;
      try {
        const data = { groupId };
        if (dmlBatchResolution) {
          data.dmlBatchResolution = dmlBatchResolution;
        }
        const res = await this.$services.dbChangeV2RetryGroup({ data });
        if (res.success) {
          this.$Message.success(this.$t('gov-v2-retry-success'));
          await this.getTicketDetail();
          await this.loadV2GroupList();
        }
      } finally {
        this.v2RetryLoading = false;
      }
    },
    async handleReturnTicket() {
      if (this.v2ReturnLoading) {
        return;
      }
      this.v2ReturnLoading = true;
      try {
        const res = await this.$services.dbChangeV2ReturnTicket({
          data: { ticketId: this.ticketId, reason: this.v2ReturnReason || undefined }
        });
        if (res.success) {
          this.$Message.success(this.$t('gov-v2-return-success'));
          this.showReturnTicketModal = false;
          this.v2ReturnReason = '';
          await this.getTicketDetail();
          await this.loadV2GroupList();
        }
      } finally {
        this.v2ReturnLoading = false;
      }
    },
    openResubmitModal(groupId) {
      const sqlContent = this.v2ResubmitSqlMap[groupId];
      if (!sqlContent || !sqlContent.trim()) {
        this.$Message.warning(this.$t('gov-v2-resubmit-empty'));
        return;
      }
      this.v2ResubmitGroupId = groupId;
      this.v2ResubmitReason = '';
      this.v2ResubmitRollBackSql = '';
      this.showResubmitModal = true;
    },
    async handleResubmitSubmit() {
      const groupId = this.v2ResubmitGroupId;
      if (this.v2ResubmitLoading || !groupId) {
        return;
      }
      const sqlContent = this.v2ResubmitSqlMap[groupId];
      if (!sqlContent || !sqlContent.trim()) {
        this.$Message.warning(this.$t('gov-v2-resubmit-empty'));
        this.showResubmitModal = false;
        return;
      }
      this.v2ResubmitLoading = true;
      try {
        const res = await this.$services.dbChangeV2Resubmit({
          data: {
            ticketId: this.ticketId,
            groups: [{ groupId, sqlContent }],
            reason: this.v2ResubmitReason || undefined,
            rollBackSql: this.v2ResubmitRollBackSql?.trim() || undefined
          }
        });
        if (res.success) {
          this.$Message.success(this.$t('gov-v2-resubmit-success'));
          this.showResubmitModal = false;
          this.v2ResubmitReason = '';
          this.v2ResubmitRollBackSql = '';
          this.v2ResubmitGroupId = null;
          await this.getTicketDetail();
          await this.loadV2GroupList();
        }
      } finally {
        this.v2ResubmitLoading = false;
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
    },
    // B6 M1 ghost route marks (precheck_result passthrough) — message is backend pre-translated
    // text (B3 privilegeHint precedent), rendered verbatim
    ghostRoutesOf(group) {
      const precheck = this.parsePrecheckResult(group.precheckResult);
      return precheck?.ghostRoutes || [];
    },
    // ==================== B6 M4: segment/statement list (design §6.8) ====================
    // Segment carrier of a group: the RUNTIME (execSegments VO passthrough) when present, else
    // the precheck PLAN (precheck_result.segments, rendered all-PENDING before the first
    // dispatch). Never-throw; [] for unsegmented groups → the caller renders nothing at all
    // (legacy / pure-normal / M1 flags-only groups keep their exact pre-M4 shape).
    groupSegments(group) {
      const runtime = this.parseSegmentList(group.execSegments);
      if (runtime.length) {
        return runtime;
      }
      const precheck = this.parsePrecheckResult(group.precheckResult);
      if (precheck && Array.isArray(precheck.segments)) {
        return precheck.segments.filter((seg) => seg && typeof seg === 'object');
      }
      return [];
    },
    parseSegmentList(json) {
      if (!json) {
        return [];
      }
      try {
        const arr = JSON.parse(json);
        if (!Array.isArray(arr)) {
          return [];
        }
        return arr.filter((seg) => seg && typeof seg === 'object');
      } catch (e) {
        return [];
      }
    },
    isSegmentedGroup(group) {
      return this.groupSegments(group).length > 0;
    },
    // the user toggle wins; the default keeps in-flight/failed groups open (progress visibility,
    // R6) and finished groups collapsed (the segment list stays one click away)
    segmentsOpen(group) {
      const overridden = this.segmentsExpanded[group.groupId];
      if (overridden !== undefined) {
        return overridden;
      }
      return group.execStatus !== 'SUCCESS';
    },
    toggleSegments(group) {
      this.segmentsExpanded = { ...this.segmentsExpanded, [group.groupId]: !this.segmentsOpen(group) };
    },
    segmentStatusOf(seg) {
      // the plan carrier has no status field — pre-dispatch every segment is PENDING by contract
      return seg.status || 'PENDING';
    },
    segmentStatusText(seg) {
      const key = SEGMENT_STATUS_I18N[this.segmentStatusOf(seg)];
      if (!key) {
        return this.segmentStatusOf(seg);
      }
      return this.$t(key);
    },
    segmentRangeText(seg) {
      const range = segmentRangeI18n(seg);
      if (!range) {
        return '';
      }
      return this.$t(range.key, range.args);
    },
    // statements[].index is the 0-BASED precheck preview index while segment bounds are 1-BASED
    // in the exec_order domain (R11) — the +1 bridge is the ONLY legal conversion between them
    segmentStatements(group, seg) {
      const precheck = this.parsePrecheckResult(group.precheckResult);
      if (!precheck || !Array.isArray(precheck.statements)) {
        return [];
      }
      // B7: a DML_BATCH segment is single-statement like GHOST (stmtIndex key, no stmtRange)
      if (seg.type === 'GHOST' || seg.type === 'DML_BATCH') {
        return precheck.statements.filter((stmt) => stmt.index + 1 === seg.stmtIndex);
      }
      if (!Array.isArray(seg.stmtRange) || seg.stmtRange.length !== 2) {
        return [];
      }
      const from = seg.stmtRange[0];
      const to = seg.stmtRange[1];
      return precheck.statements.filter((stmt) => stmt.index + 1 >= from && stmt.index + 1 <= to);
    },
    // GhostGroupPanel props for one GHOST segment (the panel keeps its M1 group-VO-shaped
    // contract — zero component change). The group-level M1 projection carries the NEWEST bound
    // ghost task's facts: they belong to THIS segment only when the task ids match; earlier ghost
    // segments of a multi-ghost group fall back to their own per-sessionId tailStatus data (the
    // backend synthesizes status/oldTableName from that segment's own task record after release).
    segmentPanelGroup(group, seg) {
      const panelGroup = {
        ghostSessionId: seg.sessionId,
        ghostTaskStatus: null,
        ghostOldTableName: undefined,
        ghostOldTableDropped: undefined
      };
      const projected = seg.taskId != null && group.ghostTaskId != null && seg.taskId === group.ghostTaskId;
      if (projected) {
        panelGroup.ghostTaskStatus = group.ghostTaskStatus || null;
        panelGroup.ghostOldTableName = group.ghostOldTableName;
        panelGroup.ghostOldTableDropped = group.ghostOldTableDropped;
      } else if (seg.status === 'SUCCESS') {
        panelGroup.ghostTaskStatus = 'COMPLETED';
      } else if (seg.status === 'FAILED') {
        panelGroup.ghostTaskStatus = 'FAILED';
      }
      return panelGroup;
    },
    // M1-exact for unsegmented groups (the panel replaces the flags once a session exists); a
    // segmented group keeps its route announcements in every phase — they carry the ALERT_PASS
    // marks and threshold numbers the segment badges do not
    showGhostRouteFlags(group) {
      if (!this.ghostRoutesOf(group).length) {
        return false;
      }
      if (this.isSegmentedGroup(group)) {
        return true;
      }
      return !group.ghostSessionId;
    },
    // ==================== B7 N3: DML_BATCH segment face (design §10) ====================
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
    // the sweep's terminal-replay copy for a user-stopped task is mechanical English — present the
    // ABORTED semantics instead (N2 check LOW #5: 部分成功保留+无自动回滚); anything else verbatim
    segmentErrorText(seg) {
      const mappedKey = seg.type === 'DML_BATCH' ? mapDmlBatchSegmentError(seg.error) : null;
      if (mappedKey) {
        return this.$t(mappedKey);
      }
      return seg.error;
    },
    // the batch panel renders once the segment is live/terminal (or the projection attached) —
    // a pre-dispatch PLAN/PENDING segment has nothing to show (the ghost panel's no-sessionId
    // minimalism; the segment head row already carries the PENDING status)
    showDmlBatchPanel(group, seg) {
      const p = group.dmlBatchProgress;
      if (p && p.segmentIndex === seg.segIdx) {
        return true;
      }
      return !!seg.status && seg.status !== 'PENDING';
    },
    // the resolution guard's authority is the SEGMENT SUMMARY marker (N2 补充裁决 (b)) — never
    // the projection's display copy
    findUnresolvedBatchSegment(group) {
      return (
        this.groupSegments(group).find(
          (seg) => seg && seg.type === 'DML_BATCH' && seg.status === 'FAILED' && seg.summary && seg.summary.unresolvedIntent === true
        ) || null
      );
    },
    openDmlResolutionModal(group, seg) {
      this.dmlResolutionGroup = group;
      this.dmlResolutionSeg = seg;
      this.dmlResolutionForm.resolution = 'RERUN_BATCH';
      this.showDmlResolutionModal = true;
    },
    closeDmlResolutionModal() {
      this.showDmlResolutionModal = false;
      this.dmlResolutionGroup = null;
      this.dmlResolutionSeg = null;
    },
    async submitDmlResolution() {
      const group = this.dmlResolutionGroup;
      if (!group || this.dmlResolutionSubmitting) {
        return;
      }
      this.dmlResolutionSubmitting = true;
      try {
        await this.doRetryGroup(group.groupId, this.dmlResolutionForm.resolution);
        this.closeDmlResolutionModal();
      } finally {
        this.dmlResolutionSubmitting = false;
      }
    },
    async loadReleaseSummary() {
      const releaseId = this.ticketDetail?.releaseId;
      if (!releaseId) {
        this.releaseSummary = null;
        return;
      }
      try {
        const res = await this.$services.govReleaseDetail({ data: { releaseId }, modal: false });
        if (res.success && res.data) {
          this.releaseSummary = res.data;
        } else {
          this.releaseSummary = null;
        }
      } catch {
        this.releaseSummary = null;
      }
    },
    goToRelease(releaseId) {
      if (releaseId) {
        this.$router.push(`/dbChange/release/${releaseId}`);
      }
    },
    releaseStatusText(status) {
      const keys = {
        APPROVING: 'gov-release-status-APPROVING',
        APPROVED: 'gov-release-status-APPROVED',
        EXECUTING: 'gov-release-status-EXECUTING',
        DONE: 'gov-release-status-DONE',
        PARTIAL_FAILED: 'gov-release-status-PARTIAL_FAILED',
        REJECTED: 'gov-release-status-REJECTED',
        CANCELLED: 'gov-release-status-CANCELLED'
      };
      return keys[status] ? this.$t(keys[status]) : status || '-';
    },
    releaseStatusColor(status) {
      if (status === 'DONE') return 'success';
      if (status === 'PARTIAL_FAILED') return 'warning';
      if (status === 'REJECTED' || status === 'CANCELLED') return 'error';
      return 'primary';
    },
    async cancelTicket() {
      const data = {
        ticketId: this.ticketId,
        approvalType: this.ticketDetail.approType,
        approIdentity: this.ticketDetail.approIdentity
      };
      const res = await this.$services.dmTicketCancel({ data });
      if (res.success) {
        this.$Message.success(this.$t('che-xiao-cheng-gong'));
        this.showCancelTicketModal = false;
        await this.getTicketDetail();
      }
    },
    async handleConfirmTicket() {
      if (this.confirmSubmitting) {
        return;
      }
      this.confirmSubmitting = true;
      appLogger.debug(this.confirmInfo.confirmActionType);
      try {
        const data = { ...this.confirmInfo };
        if (this.confirmInfo.confirmActionType === 'CONFIRM') {
          data.autoExecConfig.execTime = Date.parse(data.autoExecConfig.execTime);
        }
        const res = await this.$services.dmTicketConfirm({ data });
        if (res.success) {
          this.$Message.success(this.$t('cao-zuo-cheng-gong'));
          this.handleCloseModal();
          await this.getTicketDetail();
        }
      } finally {
        this.confirmSubmitting = false;
      }
    },
    async handleFinishTicket() {
      if (this.confirmSubmitting) {
        return;
      }
      this.confirmSubmitting = true;
      this.confirmInfo.confirmActionType = 'CONFIRM';
      try {
        const data = { ...this.confirmInfo };
        data.autoExecConfig.execTime = null;
        const res = await this.$services.dmTicketConfirm({ data });
        if (res.success) {
          this.$Message.success(this.$t('cao-zuo-cheng-gong'));
          this.handleCloseModal();
          await this.getTicketDetail();
        }
      } finally {
        this.confirmSubmitting = false;
      }
    },

    handleShowApprovalModal() {
      this.showApprovalModal = true;
    },
    async handleApproval() {
      const { action, comment } = this.approvalData;
      const res = await this.$services.rdpTicketApproval({
        data: {
          ticketId: this.ticketId,
          comment,
          rejected: action === 'reject',
          returned: action === 'return'
        }
      });

      if (res.success) {
        this.$Message.success(this.$t('shen-pi-cheng-gong'));
        this.handleCloseModal();
        await this.getTicketDetail();
      }
    },
    handleCloseModal() {
      this.approvalData = {
        action: 'approve',
        comment: ''
      };
      this.showApprovalModal = false;
      this.showCancelTicketModal = false;
      this.showRollbackSqlModal = false;
      this.showTicketContentModal = false;
      this.showManualExecuteModal = false;
      this.showCloseTicketModal = false;
      this.showAutoExecuteModal = false;
      this.showAutoExecJobLogModal = false;
      this.showAutoExecTaskLogModal = false;
      this.showAutoExecTaskSQLModal = false;
      this.selectedAutoExecTaskSql = '';
      this.showStopAutoExecJobModal = false;
      this.showRetryAutoExecJobModal = false;
      this.showEndAutoExecJobModal = false;
      this.showSkipAutoExecTaskModal = false;
      this.showContinueSkipAutoExecTaskModal = false;
    },
    async closeTicket() {
      const data = {
        ticketId: this.ticketId
      };
      const res = await this.$services.rdpTicketClose({ data });
      if (res.success) {
        this.$Message.success(this.$t('guan-bi-cheng-gong'));
        await this.getTicketDetail();
      }
      this.handleCloseModal();
    },
    handleGoToTheApproval() {
      window.open(this.ticketDetail.pcUrl);
    },
    checkRoleResultList() {
      if (!this.showCheckedOnlyError) {
        return this.analysisRuleResults;
      } else {
        return this.analysisRuleResults.filter((rule) => rule.ruleLevel !== 'SUGGEST');
      }
    }
  }
};
</script>

<style lang="less" scoped>
.horizontal-align {
  display: flex;
  align-items: center;
  gap: 10px;
}

.ticket-detail-container {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 0;
  box-sizing: border-box;
  padding: 20px;
  overflow-x: hidden;
  overflow-y: auto;

  .header {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .ticket-detail-status {
    margin-top: 16px;

    :deep(.ant-card-body) {
      padding: 12px !important;
    }
  }

  .ticket-title-p {
    line-height: 20px;
    margin-bottom: 12px;
  }

  .ivu-card-head p,
  .ivu-card-head-inner {
    overflow: visible;
  }

  .ticket-title {
    font-size: 14px;
    font-family: PingFangSC-Semibold;
    font-weight: 500;
  }

  .ticket-status-total {
    display: flex;
    align-items: center;
    border: 1px solid #f8d090;
    background: #fff8ec;
    border-radius: 10px;
    color: #ffa30e;
    font-size: 12px;
    padding: 2px 8px;
    margin-left: 8px;
    margin-right: 5px;
  }

  .ticket-detail-summary {
    font-size: 12px;
    font-family: PingFangSC-Regular;
    font-weight: 400;
    padding-right: 200px;

    &.with-analysis-summary {
      padding-right: 500px;
    }

    .ticket-detail-item {
      margin-top: 6px;
      margin-right: 80px;
      color: @font-color;
      display: inline-block;

      .ticket-detail-item-title {
        color: @icon-color;
      }
    }
  }

  .ticket-analysis-summary {
    position: absolute;
    top: 18px;
    right: 132px;
    width: 300px;
    padding-left: 24px;
    border-left: 1px solid #e8eaec;
    font-size: 12px;

    > div {
      display: grid;
      grid-template-columns: 76px 1fr;
      gap: 8px;
      margin-bottom: 10px;
    }

    span {
      color: @icon-color;
    }

    strong {
      font-weight: 500;
    }
  }

  .ticket-status-total.analysis-status {
    color: #1677ff;
    border-color: #91caff;
    background: #e6f4ff;
  }

  .ticket-detail-operators {
    position: absolute;
    right: 14px;
    top: 10px;
    display: flex;

    button {
      margin-left: 10px;
    }
  }

  .ticket-content {
    margin-top: 20px;

    :deep(.ivu-card-body) {
      padding: 0 0;
    }

    :deep(.ivu-table-wrapper-with-border) {
      border: 0;
    }

    .analysis-result-empty,
    .analysis-result-overview {
      padding: 24px;
      color: @icon-color;
      text-align: center;
    }

    .analysis-result-overview {
      color: @text-color;
      font-size: 14px;
    }

    .analysis-result-tabs {
      :deep(.ivu-tabs-bar) {
        margin-bottom: 0;
      }

      :deep(.ivu-tabs-tabpane) {
        min-height: 72px;
      }
    }

    .analysis-rule-toolbar {
      display: flex;
      justify-content: flex-end;
      padding: 12px 16px 0;
    }

    .collapsible-card-title {
      display: flex;
      align-items: center;
      justify-content: space-between;
      width: 100%;
    }

    .ticket-content-title {
      display: flex;
      align-items: center;
      justify-content: space-between;
      min-width: 0;
    }

    .ticket-content-title-main,
    .ticket-attachment-meta {
      display: flex;
      align-items: center;
    }

    .ticket-attachment-meta {
      gap: 8px;
      margin-left: 16px;
      color: @icon-color;
      font-size: 12px;
      font-weight: 400;
    }
  }

  .compact-ticket-content {
    :deep(.ivu-card-body) {
      padding: 0;
    }

    .ticket-content-entry {
      display: flex;
      align-items: center;
      justify-content: space-between;
      min-height: 52px;
      padding: 0 20px;
      cursor: pointer;

      &:hover {
        background: #f8f8f9;
      }

      &:focus-visible {
        outline: 2px solid #57a3f3;
        outline-offset: -2px;
      }
    }

    .ticket-content-title-main {
      min-width: 0;

      .parse-error-msgContent {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }

    .ticket-content-entry-meta {
      display: flex;
      flex-shrink: 0;
      align-items: center;
      gap: 8px;
      margin-left: 16px;
      color: @icon-color;
      font-size: 12px;
    }
  }

  .ticket-detail-wrapper {
    position: relative;
    color: @font-color;

    .step-item {
      padding: 7px;
      display: flex;
      align-items: center;
      width: 100%;

      &.current-step {
        box-shadow: rgba(0, 0, 0, 0.16) 0px 1px 4px;
        border-radius: 5px;
        cursor: pointer;
      }

      &.analysis-step {
        flex-direction: column;
        align-items: stretch;

        &.current-step {
          box-shadow: none;
          border: 1px solid #d6e4ff;
          background: #f7faff;
        }
      }

      .step-item-item {
        position: relative;
        width: 100%;
        display: flex;
        align-items: center;
        flex: 1;

        .step-detail-label {
          min-width: 40px;
        }

        .step-detail-value,
        .content {
          display: inline-block;
          vertical-align: middle;
        }

        .line {
          //height: 20px;
          width: 2px;
          background: red;
          position: absolute;
          left: 9px;
          bottom: 22px;
        }

        .status {
          display: flex;
          align-items: center;
          border-radius: 12px;
          padding-right: 4px;
          font-weight: bold;
          margin-right: 5px;
        }
      }

      &:last-child {
        margin-bottom: 0;
      }
    }

    .analysis-toggle {
      margin-left: auto;
      padding: 0 4px;
      color: @font-color;
    }

    .analysis-detail-list {
      width: 75%;
      margin: 4px 0 0 25%;
    }

    .analysis-detail-row {
      display: grid;
      grid-template-columns: 22% 16% 1fr 100px;
      align-items: center;
      min-height: 42px;
      border-top: 1px solid #e8eaec;

      > div {
        padding: 8px 12px;
      }
    }

    .analysis-detail-header {
      min-height: 36px;
      color: @icon-color;
      font-weight: 500;
    }

    .analysis-item-status {
      display: inline-block;
      padding: 2px 8px;
      border-radius: 3px;
      font-size: 12px;

      &.status-finished {
        color: #389e0d;
        background: #f6ffed;
      }

      &.status-running {
        color: #1677ff;
        background: #e6f4ff;
      }

      &.status-failed {
        color: #cf1322;
        background: #fff1f0;
      }

      &.status-init {
        color: #8c8c8c;
        background: #f5f5f5;
      }
    }

    .analysis-result {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .ticket-status {
    display: flex;
    align-items: center;
    margin-left: 5px;

    .content {
      padding: 2px 5px;
      border-radius: 2px;
      color: #fff;
      font-weight: bold;
    }
  }
}

.responsive-sql-modal-editor {
  position: relative;
  height: clamp(320px, 62vh, 720px);
  overflow: hidden;

  :deep(.read-only-editor-wrapper),
  :deep(.read-only-editor) {
    height: 100% !important;
  }
}

.ticket-execute-form {
  padding: 8px 4px 12px;

  :deep(.ivu-form-item) {
    margin-bottom: 28px;
  }

  :deep(.ivu-form-item:last-child) {
    margin-bottom: 0;
  }

  :deep(.ivu-form-item-label) {
    float: none;
    display: block;
    width: auto !important;
    margin-bottom: 12px;
    padding: 0;
    color: var(--text-secondary);
    font-size: 14px;
    font-weight: 500;
    line-height: 22px;
    text-align: left;
  }

  :deep(.ivu-form-item-content) {
    margin-left: 0 !important;
    line-height: 22px;
  }

  :deep(.ivu-input) {
    padding: 10px 12px;
    line-height: 22px;
  }

  :deep(.ticket-execute-comment) {
    min-height: 88px;
    padding: 10px 12px;
    line-height: 22px;
    resize: vertical;
  }
}

.ticket-execute-mode-options {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 32px;

  :deep(.ivu-radio-wrapper) {
    margin-right: 0;
    color: var(--text-primary);
  }
}

.ticket-execute-schedule-row {
  display: flex;
  align-items: flex-start;
  flex-direction: column;
  gap: 8px;
  margin-top: 16px;
}

.ticket-execute-schedule-label {
  flex: none;
  color: var(--text-secondary);
  font-size: 14px;
  font-weight: 500;
  line-height: 22px;
}

.ticket-execute-schedule {
  width: 100%;
  max-width: 320px;
}

.ticket-execute-transaction {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  padding: 14px 16px;
  border-radius: 8px;
  color: var(--text-secondary);
  background: var(--bg-secondary);
  line-height: 22px;

  :deep(.ivu-switch) {
    flex: none;
  }
}

// B7 N3: confirm-dialog batch disclosures (semantics as TEXT color — skill §7, no big backgrounds)
.ticket-execute-batch-hint {
  margin-top: 8px;
  font-size: 13px;
  color: var(--text-secondary);
}

.ticket-execute-batch-transaction-hint {
  color: #d46b08;
}

// B7 N3: the window-crash manual-verdict modal
.dml-resolution {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.dml-resolution__locate {
  font-size: 14px;
  color: var(--text-primary, #181d26);
  line-height: 22px;
}

.dml-resolution__skip-again {
  font-size: 13px;
  color: #d46b08;
}

.dml-resolution__idempotent {
  font-size: 13px;
  line-height: 20px;

  &.is-non-idempotent {
    color: var(--error-color, #ff1815);
  }

  &.is-idempotent {
    color: var(--success-color, #52c41a);
  }
}

.dml-resolution__last-error {
  font-size: 13px;
  color: var(--text-secondary);
  white-space: pre-wrap;
  word-break: break-all;
}

.dml-resolution__options {
  display: flex;
  flex-direction: column;
  gap: 8px;

  .dml-resolution__option-desc {
    margin-left: 8px;
    font-size: 12px;
    color: var(--text-secondary);
  }
}

.ellipsis {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}

.parse-error-msgContent {
  margin-left: 20px;
  color: red;
}

.validation-content {
  padding: 16px;

  .rule-item {
    background: white;
    border: 1px solid #f0f0f0;
    border-radius: 6px;
    padding: 12px;
    padding-bottom: 6px;
    margin-bottom: 8px;
    box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);

    &:last-child {
      margin-bottom: 0;
    }

    .rule-header {
      display: flex;
      align-items: center;
      margin-bottom: 5px;

      .rule-level {
        margin-right: 8px;
        font-weight: 500;
      }

      .rule-name {
        font-weight: 600;
        color: #262626;
      }

      .rule-lines {
        display: flex;
        align-items: center;
        font-size: 12px;
        padding-left: 10px;

        .lines-label {
          color: #8c8c8c;
          margin-right: 4px;
        }

        .lines-content {
          color: #595959;
          background: #f5f5f5;
          padding: 2px 6px;
          margin-right: 5px;
          border-radius: 3px;
          font-family: monospace;
        }
      }
    }

    .rule-desc {
      color: #595959;
      line-height: 1.5;
      margin-bottom: 5px;
    }
  }
}

.ticket-detail-page {
  width: 100%;
  height: 100%;
  min-height: 0;
  color: var(--text-primary);
  background: var(--bg-card);
  overflow: hidden;
}

.ticket-detail-back {
  margin-bottom: 8px;
}

.ticket-detail-page .ticket-detail-container {
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 14px;
  width: 100%;
  height: 100%;
  min-height: 0;
  padding: 16px 24px 24px;
  overflow-x: hidden;
  overflow-y: auto;
  scrollbar-gutter: stable;
}

.ticket-detail-page .page-section {
  flex: none;
  margin: 0;
}

.ticket-info-section,
.ticket-progress-card,
.ticket-auth-section {
  padding: 20px 24px;
  border: 1px solid var(--border-primary);
  border-radius: 8px;
  background: var(--bg-card);
  box-shadow: 0 2px 8px rgba(31, 41, 55, 0.05);
}

.ticket-info-section,
.ticket-progress-card {
  padding-top: 16px;
  padding-bottom: 16px;
}

.ticket-progress-card {
  container-type: inline-size;
}

.ticket-auth-list {
  margin-top: 18px;
  border-top: 1px solid var(--border-light);
  border-bottom: 1px solid var(--border-light);
}

.ticket-auth-record {
  padding: 18px 0;
}

.ticket-auth-record + .ticket-auth-record {
  border-top: 1px solid var(--border-light);
}

.ticket-auth-record__meta {
  display: grid;
  grid-template-columns: minmax(180px, 0.8fr) minmax(180px, 1fr) minmax(240px, 1.2fr);
  gap: 24px;
}

.ticket-auth-record__field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.ticket-auth-record__field > span,
.ticket-auth-record__permissions-title {
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 18px;
}

.ticket-auth-record__field > strong {
  overflow-wrap: anywhere;
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 500;
  line-height: 22px;
}

.ticket-auth-record__datasource {
  display: flex;
  gap: 8px;
  align-items: center;
}

.ticket-auth-record__datasource-info {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.ticket-auth-record__datasource-name,
.ticket-auth-record__datasource-id {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ticket-auth-record__datasource-name {
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.ticket-auth-record__datasource-id {
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 400;
  line-height: 18px;
}

.ticket-auth-record__permissions {
  display: grid;
  grid-template-columns: 112px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px dashed var(--border-light);
}

.ticket-auth-record__permissions-title {
  padding-top: 3px;
}

.ticket-auth-labels {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.ticket-auth-label {
  display: inline-flex;
  align-items: center;
  min-height: 28px;
  padding: 3px 10px;
  border: 1px solid var(--border-light);
  border-radius: 6px;
  color: var(--text-primary);
  background: var(--bg-secondary);
  font-size: 13px;
  line-height: 20px;
}

.ticket-auth-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  min-height: 132px;
  margin-top: 18px;
  border-radius: 8px;
  color: var(--text-secondary);
  background: var(--bg-secondary);
}

.ticket-auth-empty__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 8px;
  color: var(--primary-color);
  background: color-mix(in srgb, var(--primary-color) 10%, var(--bg-card));
  font-size: 20px;
}

.ticket-overview {
  margin-top: 12px;
}

.ticket-info-section__header,
.ticket-info-section__heading,
.ticket-overview__actions,
.ticket-meta-item__value,
.ticket-meta-item__label--with-icon,
.ticket-state-badge,
.ticket-progress,
.ticket-progress-step,
.ticket-progress-step__content,
.analysis-summary-row__elapsed,
.ticket-sql-toolbar,
.ticket-sql-toolbar__meta,
.ticket-sql-toolbar__actions,
.ticket-sql-file {
  display: flex;
  align-items: center;
}

.ticket-info-section__header,
.ticket-sql-toolbar {
  justify-content: space-between;
}

.ticket-info-section__header {
  align-items: center;
  gap: 24px;
}

.ticket-info-section__heading {
  flex: 1;
  gap: 24px;
  min-width: 0;
}

.ticket-info-section__label {
  flex: none;
  color: var(--text-secondary);
  font-size: 14px;
  font-weight: 500;
  line-height: 28px;
  white-space: nowrap;
}

.ticket-info-section__title {
  flex: 1;
  min-width: 0;
  color: var(--text-primary);
  font-size: 18px;
  font-weight: 600;
  line-height: 28px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ticket-overview__actions {
  flex: none;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.ticket-overview__actions :deep(.ivu-btn) {
  margin: 0;
}

.ticket-state-badge,
.analysis-item-status {
  width: fit-content;
  min-height: 24px;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  line-height: 20px;
  white-space: nowrap;
}

.ticket-state-badge {
  gap: 5px;
}

.ticket-state-badge--success,
.analysis-item-status.status-finished {
  color: var(--success-color);
  background: color-mix(in srgb, var(--success-color) 10%, var(--bg-card));
}

.ticket-state-badge--running,
.analysis-item-status.status-running,
.analysis-item-status.status-active {
  color: #1677ff;
  background: #e6f4ff;
}

.ticket-state-badge--error,
.analysis-item-status.status-failed {
  color: var(--error-color);
  background: color-mix(in srgb, var(--error-color) 8%, var(--bg-card));
}

.ticket-state-badge--neutral,
.analysis-item-status.status-init,
.analysis-item-status.status-pending {
  color: var(--text-secondary);
  background: var(--bg-tertiary);
}

.ticket-overview__primary-grid,
.ticket-overview__secondary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0;
  padding: 14px 0;
  border-top: 1px solid var(--border-light);
}

.ticket-overview__primary-grid--with-target-database {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.ticket-overview__secondary-grid--with-target-database {
  grid-template-columns: minmax(180px, 1fr) minmax(180px, 1fr) minmax(320px, 2fr);
}

.ticket-overview__secondary-grid {
  padding-bottom: 0;
}

.ticket-meta-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  padding: 0 20px;
  border-left: 1px solid var(--border-light);
  color: var(--text-primary);
  font-size: 14px;
}

.ticket-overview__primary-grid .ticket-meta-item:first-child,
.ticket-overview__secondary-grid .ticket-meta-item:first-child {
  padding-left: 0;
  border-left: 0;
}

.ticket-overview__primary-grid .ticket-meta-item:last-child,
.ticket-overview__secondary-grid .ticket-meta-item:last-child {
  padding-right: 0;
}

.ticket-meta-item__label,
.ticket-step-summary__item > span,
.ticket-activity-row > div > span,
.analysis-summary-row__elapsed > span {
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 400;
}

.ticket-meta-item__label--with-icon {
  gap: 7px;
}

.ticket-meta-item__label--with-icon :deep(.ivu-icon) {
  color: var(--primary-color);
  font-size: 17px;
}

.ticket-meta-item strong,
.ticket-step-summary__item strong,
.ticket-activity-row strong {
  color: var(--text-primary);
  font-weight: 600;
}

.ticket-meta-item__value,
.ticket-sql-file {
  gap: 6px;
  min-width: 0;
}

.ticket-meta-item__ticket-id {
  justify-content: flex-start;
}

.ticket-meta-item__copy {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: 0;
  border-radius: 4px;
  color: var(--text-secondary);
  background: transparent;
  cursor: pointer;
}

.ticket-meta-item__copy:hover,
.ticket-meta-item__copy:focus-visible {
  color: var(--primary-color);
  background: color-mix(in srgb, var(--primary-color) 8%, transparent);
}

.ticket-meta-item__copy:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--primary-color) 35%, transparent);
  outline-offset: 1px;
}

.ticket-meta-item__product-icon {
  display: block;
  flex: none;
  width: 20px;
  height: 20px;
  object-fit: contain;
}

.ticket-meta-item__database {
  display: flex;
  align-items: center;
}

.ticket-meta-item__database-icon {
  flex: none;
  line-height: 1;
}

.ticket-meta-item__database-info {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.ticket-meta-item__database-name,
.ticket-meta-item__database-path {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ticket-meta-item__database-name {
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.ticket-meta-item__database-path {
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 18px;
}

.ticket-meta-item--target-database :deep(.ivu-tooltip-rel) {
  display: block;
  min-width: 0;
  max-width: 100%;
}

.ticket-meta-item--description {
  min-width: 0;
}

.ticket-meta-item__description {
  display: block;
  max-width: 100%;
  line-height: 22px;
  overflow-wrap: anywhere;
}

.ticket-overview__secondary-grid > .ticket-meta-item:not(.ticket-meta-item--description) > span:last-child {
  white-space: nowrap;
}

.ticket-meta-item--description :deep(.ivu-tooltip-rel) {
  display: block;
  max-width: 100%;
}

.ticket-overview__error {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 16px;
  padding: 10px 12px;
  border-radius: 6px;
  color: var(--error-color);
  background: color-mix(in srgb, var(--error-color) 6%, var(--bg-card));
}

.ticket-overview__revision,
.ticket-overview__wait-release {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 16px;
  padding: 10px 12px;
  border-radius: 6px;
  color: var(--warning-color, #ffa30e);
  background: color-mix(in srgb, var(--warning-color, #ffa30e) 8%, var(--bg-card, #fff));
}

.ticket-progress-section {
  margin-top: 12px;
}

.ticket-progress-scroll {
  width: 100%;
  overflow-x: auto;
  padding-bottom: 4px;
  scrollbar-color: var(--border-primary) transparent;
  scrollbar-width: thin;
}

.ticket-progress-scroll::-webkit-scrollbar {
  height: 6px;
}

.ticket-progress-scroll::-webkit-scrollbar-track {
  background: transparent;
}

.ticket-progress-scroll::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: var(--border-primary);
}

.ticket-progress {
  --ticket-progress-icon-size: clamp(26px, 2.6cqw, 32px);
  --ticket-progress-content-width: clamp(104px, 11.5cqw, 140px);
  --ticket-progress-step-gap: clamp(6px, 0.8cqw, 10px);
  --ticket-progress-connector-width: clamp(16px, 3cqw, 36px);
  --ticket-progress-connector-gap: clamp(4px, 0.8cqw, 12px);

  position: relative;
  align-items: flex-start;
  width: 100%;
  min-width: 840px;
}

.ticket-progress-step {
  position: relative;
  flex: none;
  gap: var(--ticket-progress-step-gap);
  min-width: 0;
  padding: 3px 0;
  border: 0;
  color: var(--text-secondary);
  background: transparent;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.ticket-progress-step:hover {
  background: transparent;
}

.ticket-progress-step:hover .ticket-progress-step__icon {
  transform: translateY(-1px);
}

.ticket-progress-step.is-selected .ticket-progress-step__content strong,
.ticket-progress-step--active .ticket-progress-step__content strong {
  color: var(--primary-color);
}

.ticket-progress-step:focus-visible {
  outline: 2px solid var(--primary-color);
  outline-offset: 2px;
}

.ticket-progress-step__icon {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: var(--ticket-progress-icon-size);
  height: var(--ticket-progress-icon-size);
  border-radius: 50%;
  color: var(--text-tertiary);
  background: var(--bg-tertiary);
  font-size: clamp(15px, 1.4cqw, 17px);
  transition: transform 0.15s ease;
}

.ticket-progress-step--finished .ticket-progress-step__icon {
  color: var(--text-inverse);
  background: var(--success-color);
}

.ticket-progress-step--active .ticket-progress-step__icon {
  color: var(--text-inverse);
  background: var(--primary-color);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--primary-color) 12%, transparent);
}

.ticket-progress-step--failed .ticket-progress-step__icon {
  color: var(--text-inverse);
  background: var(--error-color);
}

.ticket-progress-step__content {
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
  width: var(--ticket-progress-content-width);
  min-width: var(--ticket-progress-content-width);
  background: transparent;
}

.ticket-progress-step__content strong {
  color: var(--text-primary);
  font-size: clamp(12px, 1.15cqw, 14px);
  font-weight: 600;
}

.ticket-progress-step__content span {
  display: block;
  max-width: 100%;
  overflow: hidden;
  color: var(--text-tertiary);
  font-size: clamp(11px, 1cqw, 12px);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ticket-progress-step__content .ticket-progress-step__handler {
  color: var(--text-secondary);
}

.ticket-progress-connector {
  flex: 1 1 var(--ticket-progress-connector-width);
  min-width: var(--ticket-progress-connector-width);
  height: 2px;
  margin: calc(var(--ticket-progress-icon-size) / 2 + 2px) var(--ticket-progress-connector-gap) 0;
  border-radius: 1px;
  background: var(--border-primary);
}

.ticket-progress-connector.is-reached {
  background: var(--success-color);
}

.ticket-sql-section {
  padding: 16px 24px 20px;
  border: 1px solid var(--border-primary);
  border-radius: 8px;
  background: var(--bg-card);
  box-shadow: 0 2px 8px rgba(31, 41, 55, 0.05);
}

.ticket-detail-page .ticket-sql-section {
  display: flex;
  flex: 1 0 344px;
  flex-direction: column;
  min-height: 344px;
}

.ticket-sql-section > :deep(.read-only-editor-wrapper) {
  flex: 1;
  min-height: 240px;
}

.ticket-sql-section > :deep(.read-only-editor-wrapper .read-only-editor) {
  height: 100% !important;
  min-height: 240px;
}

.ticket-sql-section :deep(.monaco-scrollable-element > .scrollbar.vertical) {
  display: none !important;
}

.ticket-step-detail {
  margin-top: 16px;
}

.page-section__title {
  position: relative;
  padding-left: 12px;
  color: var(--text-primary);
  font-size: 16px;
  font-weight: 600;
  line-height: 24px;
}

.page-section__title::before {
  position: absolute;
  top: 3px;
  bottom: 3px;
  left: 0;
  width: 3px;
  border-radius: 2px;
  background: var(--primary-color);
  content: '';
}

.ticket-step-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 24px;
  margin-top: 8px;
  padding: 12px 0 4px;
  background: var(--bg-card);
}

.ticket-step-summary__item,
.ticket-activity-row > div {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.ticket-step-summary__item + .ticket-step-summary__item {
  padding-left: 24px;
  border-left: 1px solid var(--border-light);
}

.ticket-step-rejection-reason {
  display: grid;
  grid-template-columns: 40px minmax(120px, 0.8fr) minmax(240px, 2fr);
  gap: 16px;
  align-items: center;
  min-height: 72px;
  margin: 12px 0 20px;
  padding: 12px 16px;
  border: 1px solid var(--border-light);
  border-radius: 8px;
  background: var(--bg-card);
}

.ticket-step-rejection-reason__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 8px;
  color: var(--error-color);
  background: color-mix(in srgb, var(--error-color) 8%, var(--bg-card));
  font-size: 22px;
}

.ticket-step-rejection-reason > strong {
  color: var(--text-primary);
  font-weight: 500;
}

.ticket-step-rejection-reason p {
  min-width: 0;
  margin: 0;
  color: var(--text-secondary);
  line-height: 22px;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.ticket-step-name {
  color: var(--primary-color) !important;
}

.ticket-execution-detail {
  margin-top: 20px;
}

.ticket-execution-summary-row {
  grid-template-columns: 28px 40px minmax(110px, 0.7fr) auto minmax(220px, 1.4fr) auto;
}

.analysis-summary-item.analysis-summary-item--table,
.analysis-summary-item.ticket-execution-card {
  overflow: hidden;
  border: 1px solid var(--border-light);
  border-radius: 8px;
  background: var(--bg-card);
}

.analysis-summary-item--table > .analysis-summary-row,
.ticket-execution-card > .ticket-execution-summary-row {
  border: 0;
  border-radius: 0;
  background: var(--bg-card);
}

.analysis-summary-item--table.is-expanded > .analysis-summary-row,
.ticket-execution-card.is-expanded > .ticket-execution-summary-row {
  border-radius: 0;
}

.ticket-execution-status-wrap,
.ticket-execution-context,
.ticket-execution-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 12px;
}

.ticket-execution-summary-row__icon {
  color: var(--info-color);
}

.ticket-execution-context {
  min-width: 0;
  align-items: flex-start;
  flex-direction: column;
  flex-wrap: nowrap;
  gap: 4px;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 20px;
}

.ticket-execution-context > span {
  display: block;
  white-space: nowrap;
}

.ticket-execution-context > span + span {
  padding-left: 0;
}

.ticket-execution-status,
.ticket-task-status {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 2px 8px;
  border-radius: 999px;
  color: var(--text-secondary);
  background: var(--bg-secondary);
  font-size: 12px;
  line-height: 18px;
}

.ticket-execution-status.is-FINISH,
.ticket-task-status.is-FINISH {
  color: #16845b;
  background: #edf9f4;
}

.ticket-execution-status.is-EXECUTING,
.ticket-task-status.is-EXECUTING {
  color: #1b61c9;
  background: #eef5ff;
}

.ticket-execution-status.is-FAILED,
.ticket-execution-status.is-TERMINATION,
.ticket-task-status.is-FAILED {
  color: #c53b3b;
  background: #fff1f0;
}

.ticket-execution-actions {
  flex: none;
  flex-wrap: nowrap;
  justify-content: flex-end;
  gap: 4px;
  white-space: nowrap;
}

.ticket-execution-actions :deep(.ivu-btn) {
  min-height: 30px;
  padding: 0 8px;
  border: 0;
  border-radius: 6px;
  color: var(--text-secondary);
  background: transparent;
  box-shadow: none;
  transition:
    color 0.2s ease,
    background-color 0.2s ease,
    transform 0.2s ease;
}

.ticket-execution-actions :deep(.ivu-btn:hover) {
  color: var(--primary-color);
  background: color-mix(in srgb, var(--primary-color) 8%, var(--bg-card));
}

.ticket-execution-actions :deep(.ivu-btn:active) {
  transform: translateY(1px);
}

.analysis-result-details.ticket-execution-body {
  padding: 0;
  border-top: 0;
  background: var(--bg-card);
}

.ticket-execution-table {
  overflow-x: auto;
}

.ticket-execution-table :deep(.ivu-table-wrapper-with-border),
.analysis-result-details--table :deep(.ivu-table-wrapper-with-border),
.ticket-execution-table :deep(.ant-table-wrapper),
.analysis-result-details--table :deep(.ant-table-wrapper) {
  border: 0;
  border-radius: 0;
}

.ticket-execution-table :deep(.ivu-table::before),
.ticket-execution-table :deep(.ivu-table::after),
.analysis-result-details--table :deep(.ivu-table::before),
.analysis-result-details--table :deep(.ivu-table::after) {
  display: none;
}

.ticket-execution-table :deep(.ivu-table-border th:last-child),
.ticket-execution-table :deep(.ivu-table-border td:last-child),
.analysis-result-details--table :deep(.ivu-table-border th:last-child),
.analysis-result-details--table :deep(.ivu-table-border td:last-child) {
  border-right: 0;
}

.ticket-execution-table :deep(.ivu-table-tbody tr:last-child td),
.analysis-result-details--table :deep(.ivu-table-tbody tr:last-child td),
.analysis-result-details--table :deep(.ant-table-tbody > tr:last-child > td) {
  border-bottom: 0 !important;
}

.ticket-execution-table :deep(.ivu-table-wrapper),
.ticket-execution-table :deep(.ant-table-wrapper) {
  min-width: 720px;
}

.ticket-execution-table :deep(.ivu-table td:nth-child(4) .ivu-table-cell) {
  font-family: ui-monospace, Menlo, Monaco, Consolas, 'Liberation Mono', monospace;
  font-size: 13px;
}

.ticket-execution-table :deep(.ivu-btn-text) {
  padding: 0 4px;
  color: var(--text-secondary);
}

.ticket-execution-table :deep(.ivu-btn-text:hover) {
  color: var(--primary-color);
  background: transparent;
}

.ticket-execution-pagination {
  display: flex;
  justify-content: flex-end;
  padding: 12px 16px;
}

.analysis-summary-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 10px;
}

.analysis-summary-list > .analysis-summary-item > .analysis-summary-row {
  min-height: 48px;
  padding: 6px 16px;
}

.analysis-summary-list .analysis-summary-row__icon {
  width: 32px;
  height: 32px;
  font-size: 18px;
}

.analysis-summary-item {
  overflow: hidden;
  border: 1px solid var(--border-light);
  border-radius: 8px;
  background: var(--bg-card);
}

.analysis-summary-row {
  display: grid;
  grid-template-columns: 28px 40px minmax(120px, 0.8fr) 96px minmax(240px, 2fr) 130px;
  gap: 16px;
  align-items: center;
  min-height: 72px;
  padding: 12px 16px;
  cursor: pointer;
}

.analysis-summary-row:hover {
  background: var(--bg-hover);
}

.analysis-summary-row:focus-visible {
  outline: 2px solid #1677ff;
  outline-offset: -2px;
}

.analysis-summary-row__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 8px;
  color: var(--info-color);
  background: color-mix(in srgb, var(--info-color) 8%, var(--bg-card));
  font-size: 22px;
}

.analysis-summary-row > strong {
  color: var(--text-primary);
  font-weight: 500;
}

.analysis-summary-row__result {
  min-width: 0;
  overflow: hidden;
  color: var(--text-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.analysis-summary-row__elapsed {
  justify-content: flex-end;
  gap: 12px;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}

.analysis-summary-row__expand {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  color: var(--text-secondary);
  background: transparent;
  font-size: 18px;
  cursor: pointer;
}

.analysis-summary-row__expand:hover {
  color: var(--primary-color);
  background: var(--bg-hover);
}

.analysis-summary-row:hover .analysis-summary-row__expand {
  color: var(--primary-color);
}

.analysis-summary-row__expand :deep(.ivu-icon) {
  transition: transform 0.24s cubic-bezier(0.4, 0, 0.2, 1);
}

.analysis-summary-row__expand.is-expanded :deep(.ivu-icon) {
  transform: rotate(90deg);
}

.analysis-result-collapse {
  display: grid;
  grid-template-rows: 0fr;
  opacity: 0;
  transition:
    grid-template-rows 0.32s cubic-bezier(0.4, 0, 0.2, 1),
    opacity 0.22s ease-out;
}

.analysis-result-collapse.is-expanded {
  grid-template-rows: 1fr;
  opacity: 1;
}

.analysis-result-collapse__content {
  min-height: 0;
  overflow: hidden;
}

.analysis-result-details {
  padding: 16px;
  border-top: 1px solid var(--border-light);
  background: var(--bg-secondary);
  opacity: 0;
  transform: translateY(-6px);
  transition:
    opacity 0.24s ease-out 0.06s,
    transform 0.32s cubic-bezier(0.4, 0, 0.2, 1);
}

.analysis-result-details.analysis-result-details--table {
  padding: 0;
  border-top: 0;
  background: var(--bg-card);
}

.analysis-result-collapse.is-expanded .analysis-result-details {
  opacity: 1;
  transform: translateY(0);
}

.analysis-result-details .page-panel-body {
  padding: 0;
}

.analysis-result-empty {
  min-height: 80px;
  padding: 28px 16px;
  color: var(--text-tertiary);
  text-align: center;
}

.analysis-rule-toolbar {
  display: flex;
  justify-content: flex-end;
  padding-bottom: 12px;
}

.ticket-activity-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 20px;
}

.ticket-activity-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr)) auto;
  gap: 24px;
  align-items: center;
  min-height: 60px;
  padding: 10px 16px;
  border: 1px solid var(--border-light);
  border-radius: 8px;
  background: var(--bg-card);
}

.ticket-detail-page .ticket-content {
  flex: none;
  margin: 0;
  border: 0;
  background: var(--bg-card);
  box-shadow: none;
}

.ticket-detail-page .ticket-content :deep(.ivu-card-head) {
  border-bottom: 0;
}

.ticket-sql-toolbar {
  align-items: flex-start;
  gap: 24px;
  min-height: 32px;
  margin-bottom: 12px;
}

.ticket-sql-toolbar__meta,
.ticket-sql-toolbar__actions {
  flex-wrap: wrap;
  gap: 8px 16px;
}

.ticket-sql-toolbar__meta {
  min-width: 0;
}

.ticket-sql-file {
  padding: 6px 10px;
  border-radius: 6px;
  color: var(--text-secondary);
  background: var(--bg-secondary);
  font-size: 13px;
}

.ticket-sql-toolbar__actions {
  flex: none;
  justify-content: flex-end;
}

.ticket-sql-toolbar__actions :deep(.ivu-btn) {
  height: 32px;
  padding: 0 4px;
  border: 0;
  color: var(--text-secondary);
  background: transparent;
  box-shadow: none;
}

.ticket-sql-toolbar__actions :deep(.ivu-btn:hover) {
  color: var(--primary-color);
}

.ticket-sql-toolbar__actions :deep(.ivu-icon) {
  margin-right: 5px;
}

.parse-error-msgContent {
  margin-left: 0;
  color: var(--error-color);
}

@media (max-width: 1365px) {
  .ticket-overview__primary-grid,
  .ticket-overview__primary-grid.ticket-overview__primary-grid--with-target-database {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    row-gap: 22px;
  }

  .ticket-overview__primary-grid .ticket-meta-item:nth-child(odd) {
    padding-left: 0;
    border-left: 0;
  }

  .ticket-overview__secondary-grid,
  .ticket-overview__secondary-grid.ticket-overview__secondary-grid--with-target-database {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    row-gap: 22px;
  }

  .ticket-overview__secondary-grid .ticket-meta-item--description {
    grid-column: 1 / span 2;
    padding-left: 0;
    border-left: 0;
  }
}

@media (max-width: 1279px) {
  .ticket-step-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .ticket-step-summary__item:nth-child(odd) {
    padding-left: 0;
    border-left: 0;
  }

  .analysis-summary-row {
    grid-template-columns: 28px 40px minmax(120px, 1fr) 96px minmax(180px, 2fr);
  }

  .analysis-summary-row__elapsed {
    grid-column: 3 / 6;
    justify-content: flex-start;
  }
}

@media (max-width: 1079px) {
  .ticket-detail-page .ticket-detail-container {
    gap: 20px;
    padding: 12px 16px 20px;
  }

  .ticket-overview,
  .ticket-info-section,
  .ticket-progress-card,
  .ticket-auth-section {
    padding: 16px;
  }

  .ticket-overview {
    padding: 0;
  }

  .ticket-info-section__header,
  .ticket-sql-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .ticket-info-section__heading {
    width: 100%;
  }

  .ticket-overview__actions {
    justify-content: flex-start;
  }

  .ticket-overview__primary-grid,
  .ticket-overview__primary-grid.ticket-overview__primary-grid--with-target-database,
  .ticket-overview__secondary-grid,
  .ticket-overview__secondary-grid.ticket-overview__secondary-grid--with-target-database,
  .ticket-step-summary,
  .ticket-activity-row {
    grid-template-columns: 1fr;
  }

  .ticket-step-summary__item + .ticket-step-summary__item {
    padding-left: 0;
    border-left: 0;
  }

  .ticket-step-rejection-reason {
    grid-template-columns: 40px minmax(0, 1fr);
  }

  .ticket-step-rejection-reason p {
    grid-column: 2;
  }

  .ticket-overview__primary-grid,
  .ticket-overview__secondary-grid {
    row-gap: 0;
  }

  .ticket-meta-item,
  .ticket-overview__primary-grid .ticket-meta-item:nth-child(odd),
  .ticket-overview__secondary-grid .ticket-meta-item--description {
    padding: 12px 0;
    border-top: 1px solid var(--border-light);
    border-left: 0;
  }

  .ticket-overview__secondary-grid .ticket-meta-item--description {
    grid-column: auto;
  }

  .ticket-overview__primary-grid .ticket-meta-item:first-child,
  .ticket-overview__secondary-grid .ticket-meta-item:first-child {
    padding-top: 0;
    border-top: 0;
  }

  .analysis-summary-row {
    grid-template-columns: 28px 40px 1fr auto;
  }

  .ticket-execution-summary-row {
    grid-template-columns: 28px 40px 1fr auto;
  }

  .analysis-summary-row__result,
  .analysis-summary-row__elapsed {
    grid-column: 3 / 5;
    justify-content: flex-start;
  }

  .ticket-sql-toolbar__actions {
    justify-content: flex-start;
  }

  .ticket-execution-actions {
    grid-column: 3 / 5;
    width: auto;
    flex-wrap: wrap;
    justify-content: flex-start;
    white-space: normal;
  }

  .ticket-execution-context {
    grid-column: 3 / 5;
  }

  .ticket-auth-record__meta,
  .ticket-auth-record__permissions {
    grid-template-columns: 1fr;
  }

  .ticket-auth-record__meta {
    gap: 14px;
  }

  .ticket-auth-record__permissions {
    gap: 8px;
  }

  .ticket-auth-record__permissions-title {
    padding-top: 0;
  }
}

@media (max-width: 767px) {
  .ticket-detail-page .ticket-detail-container {
    padding-right: 12px;
    padding-left: 12px;
  }

  .ticket-info-section__heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .analysis-result-collapse,
  .analysis-result-details,
  .analysis-summary-row__expand :deep(.ivu-icon),
  .ticket-execution-actions :deep(.ivu-btn) {
    transition-duration: 0.01ms;
  }
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

    // B6 M1: pre-dispatch ghost route flags (message = backend pre-translated text, verbatim)
    &__ghost-routes {
      display: flex;
      flex-direction: column;
      gap: 4px;

      .ghost-route-flag {
        display: flex;
        align-items: center;
        gap: 8px;
        font-size: 12px;
        padding: 4px 8px;
        border-radius: 4px;

        &.is-ghost {
          background: rgba(255, 153, 0, 0.08);
        }

        &.is-alert-pass {
          background: var(--surface-soft, #f8fafc);
        }

        .ghost-route-msg {
          color: #41454d;
        }
      }
    }

    &__actions {
      display: flex;
      gap: 8px;
    }
  }

  // B6 M4: collapsible segment/statement list — flat style (soft surface + white segment rows,
  // depth via contrast/spacing, no nested frames per ui-layout-consistency)
  .gov-v2-segments {
    background: var(--bg-secondary, #f8fafc);
    border-radius: 6px;
    padding: 8px 12px;

    .gov-v2-segments__head {
      display: flex;
      align-items: center;
      gap: 8px;
      cursor: pointer;
      user-select: none;
      font-size: 13px;
      color: #181d26;
    }

    .gov-v2-segments__title {
      font-weight: 500;
    }

    .gov-v2-segments__count {
      color: #707070;
      font-size: 12px;
    }

    .gov-v2-segments__action {
      margin-left: auto;
      color: #707070;
      font-size: 12px;
    }

    .gov-v2-segments__body {
      display: flex;
      flex-direction: column;
      gap: 10px;
      padding-top: 10px;
    }
  }

  .gov-v2-segment {
    display: flex;
    flex-direction: column;
    gap: 6px;
    background: var(--bg-primary, #ffffff);
    border-radius: 6px;
    padding: 8px 12px;

    .gov-v2-segment__head {
      display: flex;
      align-items: center;
      gap: 8px;
      flex-wrap: wrap;
      font-size: 12px;
    }

    .gov-v2-segment__range {
      color: #41454d;
    }

    .gov-v2-segment__table {
      color: #707070;
    }

    .gov-v2-segment__status {
      margin-left: auto;
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

    .gov-v2-segment__stmts {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    // statement rows reuse the GovTicketV2Create check-stmt-row shape (design §6.8)
    .gov-v2-segment__stmt-row {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 12px;

      .gov-v2-segment__stmt-sql {
        color: #41454d;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        max-width: 600px;
      }
    }

    .gov-v2-segment__error {
      font-size: 12px;
      color: #ed4014;
      white-space: pre-wrap;
      word-break: break-all;

      span:first-child {
        font-weight: 500;
        margin-right: 4px;
      }
    }
  }
}

.gov-release-summary-section {
  .gov-release-summary-card {
    background: var(--surface-soft, #f8fafc);
    padding: 16px 24px;
    border-radius: 10px;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .gov-release-summary-row {
    display: flex;
    align-items: center;
    gap: 12px;
    font-size: 14px;
  }

  .gov-release-summary-label {
    min-width: 100px;
    color: var(--muted, #41454d);
    flex-shrink: 0;
  }

  .gov-release-summary-actions {
    margin-top: 8px;
    display: flex;
    gap: 8px;
  }

  .gov-mono {
    font-family: Menlo, Monaco, 'Courier New', monospace;
    font-size: 13px;
    word-break: break-all;
  }
}

.resubmit-rollback-section {
  margin-top: 12px;

  .resubmit-rollback-label {
    display: block;
    margin-bottom: 4px;
    font-size: 14px;
    font-weight: 500;
    color: #181d26;
  }
}
</style>
