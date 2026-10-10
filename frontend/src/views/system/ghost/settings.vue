<template>
  <div class="page-shell ghost-settings">
    <div class="page-shell__body">
      <div class="page-panel-body ghost-settings__panel">
        <Spin v-if="loading" fix />

        <template v-if="loaded">
          <!-- 区块一:无锁结构变更(gh-ost,B6 7e)——区块级独立保存,不组装/不触碰 DML 区块 KV -->
          <Form ref="ghostForm" :key="'g' + revision" :model="draftValues" :rules="ghostRules" label-position="top" class="ghost-settings__form">
            <section class="page-section ghost-settings__section">
              <div class="page-section__title">{{ $t('wu-suo-she-zhi-zi-dong-lu-you') }}</div>
              <div class="ghost-settings__fields">
                <FormItem :label="$t('wu-suo-she-zhi-zi-dong-lu-you-kai-guan')" prop="ghostAutoRoute">
                  <PreferenceSettingField
                    :field="autoRouteField"
                    :model-value="draftValues.ghostAutoRoute"
                    @update:model-value="setDraftValue('ghostAutoRoute', $event)"
                  />
                  <p class="ghost-settings__help">{{ $t('wu-suo-she-zhi-zi-dong-lu-you-ti-shi') }}</p>
                </FormItem>
                <FormItem :label="$t('wu-suo-she-zhi-da-xiao-yu-zhi')" prop="ghostSizeThresholdMb">
                  <PreferenceSettingField
                    :field="sizeThresholdField"
                    :model-value="draftValues.ghostSizeThresholdMb"
                    @update:model-value="setDraftValue('ghostSizeThresholdMb', $event)"
                  />
                </FormItem>
                <FormItem :label="$t('wu-suo-she-zhi-hang-shu-yu-zhi')" prop="ghostRowThreshold">
                  <PreferenceSettingField
                    :field="rowThresholdField"
                    :model-value="draftValues.ghostRowThreshold"
                    @update:model-value="setDraftValue('ghostRowThreshold', $event)"
                  />
                  <p class="ghost-settings__help">{{ $t('wu-suo-she-zhi-yu-zhi-ti-shi') }}</p>
                </FormItem>
              </div>
            </section>

            <section class="page-section ghost-settings__section">
              <div class="page-section__title">{{ $t('wu-suo-she-zhi-gao-jing') }}</div>
              <div class="ghost-settings__fields">
                <FormItem :label="$t('wu-suo-she-zhi-gao-jing-kai-guan')" prop="alertPushEnable">
                  <PreferenceSettingField
                    :field="alertPushField"
                    :model-value="draftValues.alertPushEnable"
                    @update:model-value="setDraftValue('alertPushEnable', $event)"
                  />
                  <p class="ghost-settings__help">
                    {{ $t('wu-suo-she-zhi-gao-jing-ti-shi') }}
                    <router-link to="/integrations/im">{{ $t('wu-suo-she-zhi-ji-qi-ren-pei-zhi') }}</router-link>
                  </p>
                </FormItem>
              </div>
            </section>

            <section class="page-section ghost-settings__section">
              <div class="page-section__title">{{ $t('wu-suo-can-shu') }}</div>
              <p class="ghost-settings__hint">{{ $t('wu-suo-can-shu-ti-shi') }}</p>
              <Alert v-if="optionsJsonInvalid" type="error" show-icon class="ghost-settings__alert">
                {{ $t('wu-suo-json-wu-xiao') }}
              </Alert>
              <div class="ghost-settings__fields">
                <FormItem v-for="field in ghostNumberFields" :key="field.key" :label="$t(field.labelKey)" :prop="field.key">
                  <PreferenceSettingField
                    :field="field"
                    :model-value="draftValues[field.key]"
                    @update:model-value="setDraftValue(field.key, $event)"
                  />
                </FormItem>
                <FormItem :label="$t('wu-suo-param-cut-over-type')" prop="cutOverType">
                  <!-- gh-ost --cut-over enum tokens (GhostCutOverType), rendered verbatim -->
                  <Select v-model="draftValues.cutOverType" transfer style="width: 100%">
                    <Option v-for="opt in cutOverTypes" :key="opt" :value="opt">{{ opt }}</Option>
                  </Select>
                </FormItem>
                <FormItem v-for="field in ghostBoolFields" :key="field.key" :label="$t(field.labelKey)" :prop="field.key">
                  <PreferenceSettingField
                    :field="field"
                    :model-value="draftValues[field.key]"
                    @update:model-value="setDraftValue(field.key, $event)"
                  />
                </FormItem>
              </div>
              <div class="ghost-settings__restore">
                <Button :loading="restoring" :disabled="optionsStoredBlank || loading || saving" @click="handleRestoreDefaults">
                  {{ $t('wu-suo-hui-fu-mo-ren') }}
                </Button>
              </div>
            </section>

            <div class="ghost-settings__actions">
              <Button :disabled="!dirtyGhost || loading || saving || restoring" @click="resetDraft">{{ $t('qu-xiao') }}</Button>
              <Button type="primary" :loading="saving" :disabled="!dirtyGhost || loading || restoring" @click="handleSaveGhost">
                {{ $t('bao-cun') }}
              </Button>
            </div>
          </Form>

          <!-- 区块二:无锁数据变更(B7 N2,裁决 #14)——六 KV,区块级独立保存互不干扰。
               独立 dmlDraftValues 模型:gh-ost 的 JSON 选项与 B7 KV 存在同名键(dmlBatchSize——
               gh-ost --dml-batch-size 存于 ghostDefaultOptions JSON,B7 批大小是顶层 KV 行),
               共享扁平草稿会互相覆盖/交叉置脏,故两区块草稿物理隔离 -->
          <Form ref="dmlForm" :key="'d' + revision" :model="dmlDraftValues" :rules="dmlRules" label-position="top" class="ghost-settings__form">
            <section class="page-section ghost-settings__section">
              <div class="page-section__title">{{ $t('fen-pi-kuai-biao-ti') }}</div>
              <p class="ghost-settings__hint">{{ $t('fen-pi-kuai-ti-shi') }}</p>
              <div class="ghost-settings__fields">
                <FormItem :label="$t('fen-pi-zi-dong-lu-you-kai-guan')" prop="dmlBatchAutoRoute">
                  <PreferenceSettingField
                    :field="dmlAutoRouteField"
                    :model-value="dmlDraftValues.dmlBatchAutoRoute"
                    @update:model-value="setDmlDraftValue('dmlBatchAutoRoute', $event)"
                  />
                  <p class="ghost-settings__help">{{ $t('fen-pi-zi-dong-lu-you-ti-shi') }}</p>
                </FormItem>
                <FormItem v-for="field in dmlNumberFields" :key="field.key" :label="$t(field.labelKey)" :prop="field.key">
                  <PreferenceSettingField
                    :field="field"
                    :model-value="dmlDraftValues[field.key]"
                    @update:model-value="setDmlDraftValue(field.key, $event)"
                  />
                  <p v-if="field.helpKey" class="ghost-settings__help">{{ $t(field.helpKey) }}</p>
                </FormItem>
              </div>
            </section>

            <div class="ghost-settings__actions">
              <Button :loading="restoring" :disabled="loading || saving" @click="handleRestoreDmlDefaults">
                {{ $t('fen-pi-hui-fu-mo-ren') }}
              </Button>
              <Button :disabled="!dirtyDml || loading || saving || restoring" @click="resetDmlBlock">{{ $t('qu-xiao') }}</Button>
              <Button type="primary" :loading="saving" :disabled="!dirtyDml || loading || restoring" @click="handleSaveDml">
                {{ $t('bao-cun') }}
              </Button>
            </div>
          </Form>

          <!-- 区块三:钉钉审批桥接(task 10-09,design §3.2)——四 KV,区块级独立保存;草稿物理隔离(同名键碰撞教训):独立 bridgeDraftValues 模型,保存/恢复只提交本区块键;认证令牌 isSecret——加密存储不回显,留空保持不变 -->
          <Form
            ref="bridgeForm"
            :key="'b' + revision"
            :model="bridgeDraftValues"
            :rules="bridgeRules"
            label-position="top"
            class="ghost-settings__form"
          >
            <section class="page-section ghost-settings__section">
              <div class="page-section__title">{{ $t('ding-qiao-biao-ti') }}</div>
              <p class="ghost-settings__hint">{{ $t('ding-qiao-kuai-ti-shi') }}</p>
              <div class="ghost-settings__fields">
                <FormItem :label="$t('ding-qiao-fu-wu-di-zhi')" prop="dingBridgeBaseUrl">
                  <PreferenceSettingField
                    :field="bridgeBaseUrlField"
                    :model-value="bridgeDraftValues.dingBridgeBaseUrl"
                    @update:model-value="setBridgeDraftValue('dingBridgeBaseUrl', $event)"
                  />
                  <p class="ghost-settings__help">{{ $t('ding-qiao-fu-wu-di-zhi-ti-shi') }}</p>
                </FormItem>
                <FormItem :label="$t('ding-qiao-ren-zheng-ling-pai')" prop="dingBridgeAuthToken">
                  <PreferenceSettingField
                    :field="bridgeAuthTokenField"
                    :model-value="bridgeDraftValues.dingBridgeAuthToken"
                    :configured="bridgeTokenConfigured"
                    @update:model-value="setBridgeDraftValue('dingBridgeAuthToken', $event)"
                  />
                  <p class="ghost-settings__help">{{ $t('ding-qiao-ren-zheng-ling-pai-ti-shi') }}</p>
                </FormItem>
                <FormItem :label="$t('ding-qiao-chao-shi')" prop="dingBridgeTimeoutMs">
                  <PreferenceSettingField
                    :field="bridgeTimeoutField"
                    :model-value="bridgeDraftValues.dingBridgeTimeoutMs"
                    @update:model-value="setBridgeDraftValue('dingBridgeTimeoutMs', $event)"
                  />
                </FormItem>
                <FormItem :label="$t('ding-qiao-chu-zhan-kai-guan')" prop="dingBridgeOutboundEnable">
                  <PreferenceSettingField
                    :field="bridgeEnableField"
                    :model-value="bridgeDraftValues.dingBridgeOutboundEnable"
                    @update:model-value="setBridgeDraftValue('dingBridgeOutboundEnable', $event)"
                  />
                  <p class="ghost-settings__help">{{ $t('ding-qiao-chu-zhan-kai-guan-ti-shi') }}</p>
                </FormItem>
              </div>
            </section>

            <div class="ghost-settings__actions">
              <Button :loading="restoring" :disabled="loading || saving" @click="handleRestoreBridgeDefaults">
                {{ $t('fen-pi-hui-fu-mo-ren') }}
              </Button>
              <Button :disabled="!dirtyBridge || loading || saving || restoring" @click="resetBridgeBlock">{{ $t('qu-xiao') }}</Button>
              <Button type="primary" :loading="saving" :disabled="!dirtyBridge || loading || restoring" @click="handleSaveBridge">
                {{ $t('bao-cun') }}
              </Button>
            </div>
          </Form>
        </template>

        <div v-else-if="!loading" class="ghost-settings__empty">{{ $t('preference-load-failed') }}</div>
      </div>
    </div>
  </div>
</template>

<script>
import PreferenceSettingField from '@/views/system/PreferenceSettingField';

// PLUGIN_DEFAULTS mirrors the GhostOptionsDTO field initializers (cgdm-plugin-sdk
// sdk/model/ghost/GhostOptionsDTO.java:33-56) and the GhostPluginConfig @ConfigDef defaults —
// the two backend sources are kept equal by GhostServiceImpl.pluginDefaults(). If either drifts,
// this constant MUST be updated too (R14 merge chain: admin ghostDefaultOptions JSON overlays
// the plugin defaults key by key; the effective value shown here = stored JSON over defaults).
const PLUGIN_DEFAULTS = {
  assumeRbr: true,
  allowOnMaster: true,
  cutOverLockTimeoutSec: 10,
  dmlBatchSize: 10,
  chunkSize: 3000,
  verbose: false,
  cutOverType: 'DEFAULT',
  exactRowCount: false,
  concurrentRowCount: false,
  hbIntervalMs: 2000,
  timestampOldTable: true,
  initiallyDropGhostTable: true,
  assumeDsProxyInFront: true
};
const OPTION_KEYS = Object.keys(PLUGIN_DEFAULTS);
const NUMBER_OPTION_KEYS = ['chunkSize', 'dmlBatchSize', 'cutOverLockTimeoutSec', 'hbIntervalMs'];
const BOOL_OPTION_KEYS = [
  'assumeRbr',
  'allowOnMaster',
  'verbose',
  'exactRowCount',
  'concurrentRowCount',
  'timestampOldTable',
  'initiallyDropGhostTable',
  'assumeDsProxyInFront'
];

// gh-ost --cut-over enum values (GhostCutOverType: DEFAULT / ATOMIC)
const CUT_OVER_TYPES = ['DEFAULT', 'ATOMIC'];

// The five RootUserConfig KVs of this page (RootUserConfig.java:299-328). KV_DEFAULTS is the typed
// mirror of the @UserConfigDef defaultValue entries — the last-resort fallback when a KV is
// missing from the getCurrUserConfigs response or its stored value is blank/garbage (mirrors the
// fail-safe fetchSystemConf reads in GhostServiceImpl / AlertPushService / GovTicketV2ServiceImpl).
const KV_AUTO_ROUTE = 'ghostAutoRoute';
const KV_SIZE_MB = 'ghostSizeThresholdMb';
const KV_ROW_THRESHOLD = 'ghostRowThreshold';
const KV_ALERT_PUSH = 'alertPushEnable';
const KV_OPTIONS = 'ghostDefaultOptions';
const KV_DEFAULTS = {
  ghostAutoRoute: true,
  ghostSizeThresholdMb: 1024,
  ghostRowThreshold: 10000000,
  alertPushEnable: true
};
const KV_LABEL_KEYS = {
  ghostAutoRoute: 'wu-suo-she-zhi-zi-dong-lu-you-kai-guan',
  ghostSizeThresholdMb: 'wu-suo-she-zhi-da-xiao-yu-zhi',
  ghostRowThreshold: 'wu-suo-she-zhi-hang-shu-yu-zhi',
  alertPushEnable: 'wu-suo-she-zhi-gao-jing-kai-guan',
  ghostDefaultOptions: 'wu-suo-can-shu',
  dmlBatchAutoRoute: 'fen-pi-zi-dong-lu-you-kai-guan',
  dmlRowThreshold: 'fen-pi-hang-shu-yu-zhi',
  dmlBatchSize: 'fen-pi-dan-pi-hang-shu',
  dmlBatchIntervalMs: 'fen-pi-pi-jian-ge',
  dmlLagThresholdSec: 'fen-pi-yan-chi-yu-zhi',
  dmlBatchTimeoutSec: 'fen-pi-dan-pi-chao-shi',
  dingBridgeBaseUrl: 'ding-qiao-fu-wu-di-zhi',
  dingBridgeAuthToken: 'ding-qiao-ren-zheng-ling-pai',
  dingBridgeTimeoutMs: 'ding-qiao-chao-shi',
  dingBridgeOutboundEnable: 'ding-qiao-chu-zhan-kai-guan'
};
const SCALAR_KV_KEYS = [KV_AUTO_ROUTE, KV_SIZE_MB, KV_ROW_THRESHOLD, KV_ALERT_PUSH];
const GHOST_ALL_KEYS = [...SCALAR_KV_KEYS, ...OPTION_KEYS];

// The six B7 lock-free-DML RootUserConfig KVs (RootUserConfig dml* block). DML_KV_DEFAULTS is the
// typed mirror of the @UserConfigDef defaultValue entries (same last-resort fallback contract).
// Restoring defaults BLANKS the six rows — a blank/missing value falls back to the backend
// fail-safe defaults (fetchSystemConf null → default), the 7e ghostDefaultOptions pattern.
const KV_DML_AUTO_ROUTE = 'dmlBatchAutoRoute';
const KV_DML_ROW_THRESHOLD = 'dmlRowThreshold';
const KV_DML_BATCH_SIZE = 'dmlBatchSize';
const KV_DML_INTERVAL_MS = 'dmlBatchIntervalMs';
const KV_DML_LAG_SEC = 'dmlLagThresholdSec';
const KV_DML_TIMEOUT_SEC = 'dmlBatchTimeoutSec';
const DML_KV_KEYS = [KV_DML_AUTO_ROUTE, KV_DML_ROW_THRESHOLD, KV_DML_BATCH_SIZE, KV_DML_INTERVAL_MS, KV_DML_LAG_SEC, KV_DML_TIMEOUT_SEC];
const DML_KV_DEFAULTS = {
  dmlBatchAutoRoute: true,
  dmlRowThreshold: 1000000,
  dmlBatchSize: 5000,
  dmlBatchIntervalMs: 1000,
  dmlLagThresholdSec: 10,
  dmlBatchTimeoutSec: 300
};

// threshold ranges mirror WhiteListServiceForBasic/Full userConfigRange (server-side authority)
const AUTO_ROUTE_FIELD = { key: KV_AUTO_ROUTE, widget: 'switch', labelKey: KV_LABEL_KEYS[KV_AUTO_ROUTE] };
const SIZE_THRESHOLD_FIELD = {
  key: KV_SIZE_MB,
  widget: 'number',
  labelKey: KV_LABEL_KEYS[KV_SIZE_MB],
  min: 1,
  max: 1048576,
  unitKey: 'preference-unit-mb'
};
const ROW_THRESHOLD_FIELD = {
  key: KV_ROW_THRESHOLD,
  widget: 'number',
  labelKey: KV_LABEL_KEYS[KV_ROW_THRESHOLD],
  min: 1,
  max: 100000000000,
  unitKey: 'wu-suo-dan-wei-hang'
};
const ALERT_PUSH_FIELD = { key: KV_ALERT_PUSH, widget: 'switch', labelKey: KV_LABEL_KEYS[KV_ALERT_PUSH] };

// B7 DML block fields — ranges mirror the WhiteListServiceForBasic/Full dml* Range entries
// (dmlRowThreshold 1~1000000000 / dmlBatchSize 1000~50000 / dmlBatchIntervalMs 0~60000 /
// dmlLagThresholdSec 1~3600 / dmlBatchTimeoutSec 10~3600; the boolean kill switch has no Range).
const DML_AUTO_ROUTE_FIELD = { key: KV_DML_AUTO_ROUTE, widget: 'switch', labelKey: KV_LABEL_KEYS[KV_DML_AUTO_ROUTE] };
const DML_NUMBER_FIELDS = [
  {
    key: KV_DML_ROW_THRESHOLD,
    widget: 'number',
    labelKey: KV_LABEL_KEYS[KV_DML_ROW_THRESHOLD],
    min: 1,
    max: 1000000000,
    unitKey: 'wu-suo-dan-wei-hang',
    helpKey: 'fen-pi-hang-shu-yu-zhi-ti-shi'
  },
  { key: KV_DML_BATCH_SIZE, widget: 'number', labelKey: KV_LABEL_KEYS[KV_DML_BATCH_SIZE], min: 1000, max: 50000, unitKey: 'wu-suo-dan-wei-hang' },
  { key: KV_DML_INTERVAL_MS, widget: 'number', labelKey: KV_LABEL_KEYS[KV_DML_INTERVAL_MS], min: 0, max: 60000, unitKey: 'fen-pi-dan-wei-hao-miao' },
  {
    key: KV_DML_LAG_SEC,
    widget: 'number',
    labelKey: KV_LABEL_KEYS[KV_DML_LAG_SEC],
    min: 1,
    max: 3600,
    unitKey: 'preference-unit-seconds',
    helpKey: 'fen-pi-yan-chi-ti-shi'
  },
  { key: KV_DML_TIMEOUT_SEC, widget: 'number', labelKey: KV_LABEL_KEYS[KV_DML_TIMEOUT_SEC], min: 10, max: 3600, unitKey: 'preference-unit-seconds' }
];

// The four DingTalk approval bridge RootUserConfig KVs (task 10-09 design §3.2, RootUserConfig
// dingBridge* block). BRIDGE_KV_DEFAULTS is the typed mirror of the @UserConfigDef defaultValue
// entries (same last-resort fallback contract). Restoring defaults BLANKS the four rows — a blank
// value falls back to the backend fail-safe reads (switch fails CLOSED, blank baseUrl skips all
// outbound calls, timeout defaults to 10000ms).
const KV_BRIDGE_BASE_URL = 'dingBridgeBaseUrl';
const KV_BRIDGE_AUTH_TOKEN = 'dingBridgeAuthToken';
const KV_BRIDGE_TIMEOUT_MS = 'dingBridgeTimeoutMs';
const KV_BRIDGE_OUTBOUND_ENABLE = 'dingBridgeOutboundEnable';
const BRIDGE_KV_KEYS = [KV_BRIDGE_BASE_URL, KV_BRIDGE_AUTH_TOKEN, KV_BRIDGE_TIMEOUT_MS, KV_BRIDGE_OUTBOUND_ENABLE];
const BRIDGE_KV_DEFAULTS = {
  dingBridgeBaseUrl: '',
  dingBridgeAuthToken: '',
  dingBridgeTimeoutMs: 10000,
  dingBridgeOutboundEnable: false
};

// Bridge block fields — the timeout range mirrors the WhiteListServiceForBasic/Full
// dingBridgeTimeoutMs Range(1000, 60000); the token is isSecret (never echoed — the draft stays
// blank, `configured` drives the keep-unchanged placeholder, tencentCloudSecretKey precedent).
const BRIDGE_BASE_URL_FIELD = { key: KV_BRIDGE_BASE_URL, widget: 'text', labelKey: KV_LABEL_KEYS[KV_BRIDGE_BASE_URL] };
const BRIDGE_AUTH_TOKEN_FIELD = { key: KV_BRIDGE_AUTH_TOKEN, widget: 'password', labelKey: KV_LABEL_KEYS[KV_BRIDGE_AUTH_TOKEN] };
const BRIDGE_TIMEOUT_FIELD = {
  key: KV_BRIDGE_TIMEOUT_MS,
  widget: 'number',
  labelKey: KV_LABEL_KEYS[KV_BRIDGE_TIMEOUT_MS],
  min: 1000,
  max: 60000,
  unitKey: 'fen-pi-dan-wei-hao-miao'
};
const BRIDGE_ENABLE_FIELD = { key: KV_BRIDGE_OUTBOUND_ENABLE, widget: 'switch', labelKey: KV_LABEL_KEYS[KV_BRIDGE_OUTBOUND_ENABLE] };

// mins mirror the standalone launch form precedent (b38c752f GhostLaunchForm); the backend applies
// no range validation inside the JSON blob, so no invented maxima — InputNumber clamps at min.
const GHOST_NUMBER_FIELDS = [
  { key: 'chunkSize', widget: 'number', labelKey: 'wu-suo-param-chunk-size', min: 100 },
  { key: 'dmlBatchSize', widget: 'number', labelKey: 'wu-suo-param-dml-batch-size', min: 1 },
  { key: 'cutOverLockTimeoutSec', widget: 'number', labelKey: 'wu-suo-param-cut-over-lock-timeout', min: 1 },
  { key: 'hbIntervalMs', widget: 'number', labelKey: 'wu-suo-param-hb-interval', min: 100 }
];
const GHOST_BOOL_FIELDS = [
  { key: 'assumeRbr', widget: 'switch', labelKey: 'wu-suo-param-assume-rbr' },
  { key: 'allowOnMaster', widget: 'switch', labelKey: 'wu-suo-param-allow-on-master' },
  { key: 'assumeDsProxyInFront', widget: 'switch', labelKey: 'wu-suo-param-assume-ds-proxy' },
  { key: 'timestampOldTable', widget: 'switch', labelKey: 'wu-suo-param-timestamp-old-table' },
  { key: 'initiallyDropGhostTable', widget: 'switch', labelKey: 'wu-suo-param-initially-drop-ghost-table' },
  { key: 'exactRowCount', widget: 'switch', labelKey: 'wu-suo-param-exact-row-count' },
  { key: 'concurrentRowCount', widget: 'switch', labelKey: 'wu-suo-param-concurrent-row-count' },
  { key: 'verbose', widget: 'switch', labelKey: 'wu-suo-param-verbose' }
];

function rawOf(meta, localDefault) {
  if (!meta) {
    return localDefault;
  }
  if (meta.configValue !== undefined && meta.configValue !== null) {
    return meta.configValue;
  }
  if (meta.defaultValue !== undefined && meta.defaultValue !== null) {
    return meta.defaultValue;
  }
  return localDefault;
}

function parseBool(raw, fallback) {
  const text = String(raw ?? '').trim();
  if (!text) {
    return fallback;
  }
  return text.toLowerCase() === 'true';
}

function parseInteger(raw, fallback) {
  const text = String(raw ?? '').trim();
  if (!text) {
    return fallback;
  }
  const value = Number(text);
  if (!Number.isInteger(value)) {
    return fallback;
  }
  return value;
}

// Java Integer.valueOf mirror (backend readInt): JSON numbers truncate like Number.intValue();
// strings must be strict Java integer literals — Number() would also accept '3.0'/'1e3'/'0x10'
// and out-of-int-range digits, all of which Integer.valueOf rejects (the backend then discards
// the WHOLE admin layer, so accepting them here would show effective values the backend ignores)
const JAVA_INT_PATTERN = /^[+-]?\d+$/;
function readAdminInt(value) {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return Math.trunc(value);
  }
  const text = String(value).trim();
  if (!JAVA_INT_PATTERN.test(text)) {
    throw new Error(`invalid integer: ${text}`);
  }
  const parsed = Number(text);
  if (parsed < -2147483648 || parsed > 2147483647) {
    throw new Error(`integer out of range: ${text}`);
  }
  return parsed;
}

// Java Boolean.valueOf mirror (backend readBool): only case-insensitive 'true' is true
function readAdminBool(value) {
  if (typeof value === 'boolean') {
    return value;
  }
  return String(value).trim().toLowerCase() === 'true';
}

// Mirrors GhostServiceImpl.overlayAdminJson (admin layer of the R14 merge chain): blank = layer
// absent; malformed JSON or any invalid value discards the WHOLE layer (fallback to plugin
// defaults); unknown keys are ignored. invalid=true drives the red alert + save-to-repair.
function parseOptionsLayer(raw) {
  if (!raw || !String(raw).trim()) {
    return { values: { ...PLUGIN_DEFAULTS }, invalid: false };
  }
  let map;
  try {
    map = JSON.parse(raw);
  } catch (e) {
    return { values: { ...PLUGIN_DEFAULTS }, invalid: true };
  }
  if (map === null || typeof map !== 'object' || Array.isArray(map)) {
    return { values: { ...PLUGIN_DEFAULTS }, invalid: true };
  }
  const values = { ...PLUGIN_DEFAULTS };
  try {
    NUMBER_OPTION_KEYS.forEach((key) => {
      if (map[key] !== undefined && map[key] !== null) {
        values[key] = readAdminInt(map[key]);
      }
    });
    BOOL_OPTION_KEYS.forEach((key) => {
      if (map[key] !== undefined && map[key] !== null) {
        values[key] = readAdminBool(map[key]);
      }
    });
    if (map.cutOverType !== undefined && map.cutOverType !== null) {
      const token = String(map.cutOverType).trim();
      if (!CUT_OVER_TYPES.includes(token)) {
        throw new Error(`unknown cutOverType: ${token}`);
      }
      values.cutOverType = token;
    }
  } catch (e) {
    return { values: { ...PLUGIN_DEFAULTS }, invalid: true };
  }
  return { values, invalid: false };
}

export default {
  name: 'GhostSettings',
  components: { PreferenceSettingField },
  data() {
    return {
      loading: false,
      loaded: false,
      saving: false,
      restoring: false,
      revision: 0,
      configMeta: {},
      // three PHYSICALLY SEPARATE draft models — the gh-ost JSON options and the B7 KVs share a key
      // name (dmlBatchSize: gh-ost's --dml-batch-size inside ghostDefaultOptions vs the top-level
      // B7 batch-size KV). A single flat draft would let one block's value overwrite/cross-dirty
      // the other (violating 区块级独立保存互不干扰), so each block owns its own draft/original —
      // the bridge block (task 10-09) follows the same isolation.
      originalValues: {},
      draftValues: {},
      dmlOriginalValues: {},
      dmlDraftValues: {},
      bridgeOriginalValues: {},
      bridgeDraftValues: {},
      optionsJsonInvalid: false,
      optionsStoredBlank: true,
      autoRouteField: AUTO_ROUTE_FIELD,
      sizeThresholdField: SIZE_THRESHOLD_FIELD,
      rowThresholdField: ROW_THRESHOLD_FIELD,
      alertPushField: ALERT_PUSH_FIELD,
      ghostNumberFields: GHOST_NUMBER_FIELDS,
      ghostBoolFields: GHOST_BOOL_FIELDS,
      cutOverTypes: CUT_OVER_TYPES,
      dmlAutoRouteField: DML_AUTO_ROUTE_FIELD,
      dmlNumberFields: DML_NUMBER_FIELDS,
      bridgeBaseUrlField: BRIDGE_BASE_URL_FIELD,
      bridgeAuthTokenField: BRIDGE_AUTH_TOKEN_FIELD,
      bridgeTimeoutField: BRIDGE_TIMEOUT_FIELD,
      bridgeEnableField: BRIDGE_ENABLE_FIELD
    };
  },
  computed: {
    ghostRules() {
      const rules = {};
      [SIZE_THRESHOLD_FIELD, ROW_THRESHOLD_FIELD, ...GHOST_NUMBER_FIELDS].forEach((field) => {
        rules[field.key] = [{ trigger: 'change', validator: this.buildNumberValidator(field) }];
      });
      return rules;
    },
    dmlRules() {
      const rules = {};
      DML_NUMBER_FIELDS.forEach((field) => {
        rules[field.key] = [{ trigger: 'change', validator: this.buildNumberValidator(field) }];
      });
      return rules;
    },
    // block-scoped dirty flags (区块级 draft 隔离 — each block's save/cancel/leave-warning keys on
    // its OWN draft; an invalid stored ghost JSON keeps the GHOST block dirty so its next save
    // repairs the KV)
    dirtyGhost() {
      if (!this.loaded) {
        return false;
      }
      if (this.optionsJsonInvalid) {
        return true;
      }
      return GHOST_ALL_KEYS.some((key) => this.draftValues[key] !== this.originalValues[key]);
    },
    dirtyDml() {
      if (!this.loaded) {
        return false;
      }
      return DML_KV_KEYS.some((key) => this.dmlDraftValues[key] !== this.dmlOriginalValues[key]);
    },
    bridgeRules() {
      const rules = {};
      rules[KV_BRIDGE_TIMEOUT_MS] = [{ trigger: 'change', validator: this.buildNumberValidator(BRIDGE_TIMEOUT_FIELD) }];
      return rules;
    },
    dirtyBridge() {
      if (!this.loaded) {
        return false;
      }
      return BRIDGE_KV_KEYS.some((key) => this.bridgeDraftValues[key] !== this.bridgeOriginalValues[key]);
    },
    // isSecret 回显口径(tencentCloudSecretKey 消费面先例):已存储且非空 → 空草稿输入框
    // 呈现「已配置,留空保持不变」占位
    bridgeTokenConfigured() {
      const meta = this.configMeta[KV_BRIDGE_AUTH_TOKEN];
      return Boolean(meta && meta.secret && meta.configured);
    }
  },
  mounted() {
    window.addEventListener('beforeunload', this.handleBeforeUnload);
    this.load();
  },
  beforeUnmount() {
    window.removeEventListener('beforeunload', this.handleBeforeUnload);
  },
  beforeRouteLeave(to, from, next) {
    this.confirmDiscardIfNeeded().then((confirmed) => {
      next(confirmed);
    });
  },
  methods: {
    buildNumberValidator(field) {
      return (rule, value, callback) => {
        if (typeof value !== 'number' || !Number.isInteger(value) || value < field.min) {
          callback(new Error(this.$t('preference-validation-integer')));
          return;
        }
        if (field.max !== undefined && value > field.max) {
          callback(new Error(this.$t('preference-validation-range', [field.min, field.max])));
          return;
        }
        callback();
      };
    },
    async load() {
      this.loading = true;
      try {
        const res = await this.$services.rdpUserConfigGetCurrUserConfigs();
        if (!res.success) {
          return;
        }
        const configMap = {};
        (res.data || []).forEach((config) => {
          configMap[config.configName] = config;
        });
        const configMeta = {};
        [...SCALAR_KV_KEYS, KV_OPTIONS, ...DML_KV_KEYS, ...BRIDGE_KV_KEYS].forEach((key) => {
          configMeta[key] = configMap[key] || null;
        });
        this.configMeta = configMeta;

        const optionsRaw = rawOf(configMeta[KV_OPTIONS], '');
        this.optionsStoredBlank = !String(optionsRaw).trim();
        const options = parseOptionsLayer(optionsRaw);
        this.optionsJsonInvalid = options.invalid;

        const values = {
          ghostAutoRoute: parseBool(rawOf(configMeta[KV_AUTO_ROUTE], ''), KV_DEFAULTS[KV_AUTO_ROUTE]),
          alertPushEnable: parseBool(rawOf(configMeta[KV_ALERT_PUSH], ''), KV_DEFAULTS[KV_ALERT_PUSH]),
          ghostSizeThresholdMb: parseInteger(rawOf(configMeta[KV_SIZE_MB], ''), KV_DEFAULTS[KV_SIZE_MB]),
          ghostRowThreshold: parseInteger(rawOf(configMeta[KV_ROW_THRESHOLD], ''), KV_DEFAULTS[KV_ROW_THRESHOLD]),
          ...options.values
        };
        this.originalValues = values;
        this.draftValues = { ...values };
        // B7 DML KVs live in their OWN draft object — NOT merged into `values` above, because the
        // gh-ost options layer already owns the `dmlBatchSize` key name (a flat merge would let the
        // B7 KV overwrite the gh-ost option and cross-wire the two blocks' dirty/save logic).
        const dmlValues = {
          dmlBatchAutoRoute: parseBool(rawOf(configMeta[KV_DML_AUTO_ROUTE], ''), DML_KV_DEFAULTS[KV_DML_AUTO_ROUTE]),
          dmlRowThreshold: parseInteger(rawOf(configMeta[KV_DML_ROW_THRESHOLD], ''), DML_KV_DEFAULTS[KV_DML_ROW_THRESHOLD]),
          dmlBatchSize: parseInteger(rawOf(configMeta[KV_DML_BATCH_SIZE], ''), DML_KV_DEFAULTS[KV_DML_BATCH_SIZE]),
          dmlBatchIntervalMs: parseInteger(rawOf(configMeta[KV_DML_INTERVAL_MS], ''), DML_KV_DEFAULTS[KV_DML_INTERVAL_MS]),
          dmlLagThresholdSec: parseInteger(rawOf(configMeta[KV_DML_LAG_SEC], ''), DML_KV_DEFAULTS[KV_DML_LAG_SEC]),
          dmlBatchTimeoutSec: parseInteger(rawOf(configMeta[KV_DML_TIMEOUT_SEC], ''), DML_KV_DEFAULTS[KV_DML_TIMEOUT_SEC])
        };
        this.dmlOriginalValues = dmlValues;
        this.dmlDraftValues = { ...dmlValues };
        // The bridge KVs live in their OWN draft model as well (草稿物理隔离). The auth token is
        // isSecret — the server never echoes it: draft/original both stay '' (blank = keep
        // unchanged; typing a new value is the only way to dirty the row).
        const bridgeValues = {
          dingBridgeBaseUrl: String(rawOf(configMeta[KV_BRIDGE_BASE_URL], '')),
          dingBridgeAuthToken: '',
          dingBridgeTimeoutMs: parseInteger(rawOf(configMeta[KV_BRIDGE_TIMEOUT_MS], ''), BRIDGE_KV_DEFAULTS.dingBridgeTimeoutMs),
          dingBridgeOutboundEnable: parseBool(rawOf(configMeta[KV_BRIDGE_OUTBOUND_ENABLE], ''), BRIDGE_KV_DEFAULTS.dingBridgeOutboundEnable)
        };
        this.bridgeOriginalValues = bridgeValues;
        this.bridgeDraftValues = { ...bridgeValues };
        this.loaded = true;
        this.revision += 1;
        await this.$nextTick();
        this.$refs.ghostForm?.clearValidate?.();
        this.$refs.dmlForm?.clearValidate?.();
        this.$refs.bridgeForm?.clearValidate?.();
      } finally {
        this.loading = false;
      }
    },
    setDraftValue(key, value) {
      this.draftValues[key] = value;
    },
    setDmlDraftValue(key, value) {
      this.dmlDraftValues[key] = value;
    },
    setBridgeDraftValue(key, value) {
      this.bridgeDraftValues[key] = value;
    },
    // block-scoped cancel: only THIS block's own draft reverts (the other block is untouched)
    resetDraft() {
      this.draftValues = { ...this.originalValues };
      this.revision += 1;
      this.$nextTick(() => {
        this.$refs.ghostForm?.clearValidate?.();
      });
    },
    resetDmlBlock() {
      this.dmlDraftValues = { ...this.dmlOriginalValues };
      this.revision += 1;
      this.$nextTick(() => {
        this.$refs.dmlForm?.clearValidate?.();
      });
    },
    resetBridgeBlock() {
      this.bridgeDraftValues = { ...this.bridgeOriginalValues };
      this.revision += 1;
      this.$nextTick(() => {
        this.$refs.bridgeForm?.clearValidate?.();
      });
    },
    // reload after a block-scoped save/restore while PRESERVING the OTHER blocks' drafts
    // (区块级独立保存互不干扰: saving/restoring one block never discards another's edits).
    // load() rebuilds all three drafts from the server; we snapshot the other blocks'
    // in-progress drafts and restore them over the reload so their unsaved edits survive.
    async reloadKeepingOthers(savedBlock) {
      const preserved = {
        ghost: { ...this.draftValues },
        dml: { ...this.dmlDraftValues },
        bridge: { ...this.bridgeDraftValues }
      };
      await this.load();
      if (savedBlock !== 'ghost') {
        this.draftValues = preserved.ghost;
      }
      if (savedBlock !== 'dml') {
        this.dmlDraftValues = preserved.dml;
      }
      if (savedBlock !== 'bridge') {
        this.bridgeDraftValues = preserved.bridge;
      }
    },
    collectGhostWrites() {
      const writes = [];
      const draft = this.draftValues;
      const original = this.originalValues;
      SCALAR_KV_KEYS.forEach((key) => {
        if (draft[key] !== original[key]) {
          writes.push({ key, value: String(draft[key]) });
        }
      });
      const optionsDirty = this.optionsJsonInvalid || OPTION_KEYS.some((key) => draft[key] !== original[key]);
      if (optionsDirty) {
        const options = {};
        OPTION_KEYS.forEach((key) => {
          options[key] = draft[key];
        });
        writes.push({ key: KV_OPTIONS, value: JSON.stringify(options) });
      }
      return writes;
    },
    collectDmlWrites() {
      const writes = [];
      DML_KV_KEYS.forEach((key) => {
        if (this.dmlDraftValues[key] !== this.dmlOriginalValues[key]) {
          writes.push({ key, value: String(this.dmlDraftValues[key]) });
        }
      });
      return writes;
    },
    collectBridgeWrites() {
      const writes = [];
      BRIDGE_KV_KEYS.forEach((key) => {
        if (this.bridgeDraftValues[key] !== this.bridgeOriginalValues[key]) {
          writes.push({ key, value: String(this.bridgeDraftValues[key]).trim() });
        }
      });
      return writes;
    },
    buildUpsertData(key, value) {
      const meta = this.configMeta[key];
      if (meta && !meta.needCreated) {
        return { updateConfigs: { [key]: value }, needCreateConfigs: {} };
      }
      return { updateConfigs: {}, needCreateConfigs: { [key]: value } };
    },
    reportItemFailure(key, res) {
      const reason = res.msg || this.$t('cao-zuo-shi-bai');
      this.$Message.error({ content: this.$t('wu-suo-bao-cun-shi-bai-xiang', [this.$t(KV_LABEL_KEYS[key]), reason]), duration: 5 });
    },
    // one upsert per KV so a partial failure can be reported per item (modal:false — the
    // per-item message below is the report, the generic error queue must not double it)
    async runUpserts(writes) {
      let failed = 0;
      for (const write of writes) {
        const res = await this.$services.rdpUserConfigUpsertUserConfigs({ data: this.buildUpsertData(write.key, write.value), modal: false });
        if (!res.success) {
          failed += 1;
          this.reportItemFailure(write.key, res);
        }
      }
      return failed;
    },
    async handleSaveGhost() {
      if (!this.dirtyGhost || this.saving || this.restoring) {
        return;
      }
      const valid = await this.$refs.ghostForm.validate();
      if (!valid) {
        return;
      }
      this.saving = true;
      try {
        const failed = await this.runUpserts(this.collectGhostWrites());
        if (!failed) {
          this.$Message.success(this.$t('pei-zhi-yi-xiu-gai-cheng-gong'));
        }
        await this.reloadKeepingOthers('ghost');
      } finally {
        this.saving = false;
      }
    },
    async handleSaveDml() {
      if (!this.dirtyDml || this.saving || this.restoring) {
        return;
      }
      const valid = await this.$refs.dmlForm.validate();
      if (!valid) {
        return;
      }
      this.saving = true;
      try {
        const failed = await this.runUpserts(this.collectDmlWrites());
        if (!failed) {
          this.$Message.success(this.$t('pei-zhi-yi-xiu-gai-cheng-gong'));
        }
        await this.reloadKeepingOthers('dml');
      } finally {
        this.saving = false;
      }
    },
    async handleSaveBridge() {
      if (!this.dirtyBridge || this.saving || this.restoring) {
        return;
      }
      const valid = await this.$refs.bridgeForm.validate();
      if (!valid) {
        return;
      }
      this.saving = true;
      try {
        const failed = await this.runUpserts(this.collectBridgeWrites());
        if (!failed) {
          this.$Message.success(this.$t('pei-zhi-yi-xiu-gai-cheng-gong'));
        }
        await this.reloadKeepingOthers('bridge');
      } finally {
        this.saving = false;
      }
    },
    handleRestoreDefaults() {
      // block-scoped (N2): the reload below PRESERVES the DML block's draft — the discard
      // warning only concerns THIS block's pending edits
      let content = this.$t('wu-suo-hui-fu-mo-ren-ti-shi');
      if (this.dirtyGhost) {
        content += ` ${this.$t('wu-suo-hui-fu-mo-ren-diu-qi')}`;
      }
      this.$Modal.confirm({
        title: this.$t('wu-suo-hui-fu-mo-ren-que-ren'),
        content,
        okText: this.$t('que-ren'),
        cancelText: this.$t('qu-xiao'),
        onOk: () => this.doRestoreDefaults()
      });
    },
    async doRestoreDefaults() {
      this.restoring = true;
      try {
        const res = await this.$services.rdpUserConfigUpsertUserConfigs({ data: this.buildUpsertData(KV_OPTIONS, ''), modal: false });
        if (!res.success) {
          this.reportItemFailure(KV_OPTIONS, res);
          return;
        }
        this.$Message.success(this.$t('pei-zhi-yi-xiu-gai-cheng-gong'));
        await this.reloadKeepingOthers('ghost');
      } finally {
        this.restoring = false;
      }
    },
    handleRestoreDmlDefaults() {
      // 恢复默认仅清本区块 (裁决 #14): blanks the six DML KV rows — blank values fall back to the
      // backend fail-safe defaults (fetchSystemConf null → @UserConfigDef default), and the
      // confirm's discard warning only concerns THIS block's draft (the ghost draft survives)
      let content = this.$t('fen-pi-hui-fu-mo-ren-ti-shi');
      if (this.dirtyDml) {
        content += ` ${this.$t('fen-pi-hui-fu-mo-ren-diu-qi')}`;
      }
      this.$Modal.confirm({
        title: this.$t('fen-pi-hui-fu-mo-ren-que-ren'),
        content,
        okText: this.$t('que-ren'),
        cancelText: this.$t('qu-xiao'),
        onOk: () => this.doRestoreDmlDefaults()
      });
    },
    async doRestoreDmlDefaults() {
      this.restoring = true;
      try {
        let failed = 0;
        for (const key of DML_KV_KEYS) {
          const res = await this.$services.rdpUserConfigUpsertUserConfigs({ data: this.buildUpsertData(key, ''), modal: false });
          if (!res.success) {
            failed += 1;
            this.reportItemFailure(key, res);
          }
        }
        if (!failed) {
          this.$Message.success(this.$t('pei-zhi-yi-xiu-gai-cheng-gong'));
        }
        await this.reloadKeepingOthers('dml');
      } finally {
        this.restoring = false;
      }
    },
    handleRestoreBridgeDefaults() {
      // 恢复默认仅清本区块:blanks the four bridge KV rows — blank falls back to the backend
      // fail-safe defaults (baseUrl 空=出站整体跳过、超时 10000、开关关闭=止血),令牌一并清空
      let content = this.$t('ding-qiao-hui-fu-mo-ren-ti-shi');
      if (this.dirtyBridge) {
        content += ` ${this.$t('fen-pi-hui-fu-mo-ren-diu-qi')}`;
      }
      this.$Modal.confirm({
        title: this.$t('fen-pi-hui-fu-mo-ren-que-ren'),
        content,
        okText: this.$t('que-ren'),
        cancelText: this.$t('qu-xiao'),
        onOk: () => this.doRestoreBridgeDefaults()
      });
    },
    async doRestoreBridgeDefaults() {
      this.restoring = true;
      try {
        let failed = 0;
        for (const key of BRIDGE_KV_KEYS) {
          const res = await this.$services.rdpUserConfigUpsertUserConfigs({ data: this.buildUpsertData(key, ''), modal: false });
          if (!res.success) {
            failed += 1;
            this.reportItemFailure(key, res);
          }
        }
        if (!failed) {
          this.$Message.success(this.$t('pei-zhi-yi-xiu-gai-cheng-gong'));
        }
        await this.reloadKeepingOthers('bridge');
      } finally {
        this.restoring = false;
      }
    },
    confirmDiscardIfNeeded() {
      if (!this.dirtyGhost && !this.dirtyDml && !this.dirtyBridge) {
        return Promise.resolve(true);
      }
      return new Promise((resolve) => {
        this.$Modal.confirm({
          title: this.$t('preference-unsaved-title'),
          content: this.$t('wu-suo-wei-bao-cun-ti-shi'),
          okText: this.$t('preference-discard-and-continue'),
          cancelText: this.$t('qu-xiao'),
          onOk: () => resolve(true),
          onCancel: () => resolve(false)
        });
      });
    },
    handleBeforeUnload(event) {
      if (!this.dirtyGhost && !this.dirtyDml && !this.dirtyBridge) {
        return;
      }
      event.preventDefault();
      event.returnValue = '';
    }
  }
};
</script>

<style lang="less" scoped>
.ghost-settings {
  &.page-shell {
    display: flex;
    flex: 1;
    flex-direction: column;
    min-height: 0;
    height: 100%;
  }

  .page-shell__body {
    position: relative;
    display: flex;
    flex: 1;
    flex-direction: column;
    min-height: 0;
    overflow: hidden;
  }

  .page-panel-body {
    padding: 16px 24px;
  }

  &__panel {
    position: relative;
    flex: 1;
    min-height: 0;
    overflow-y: auto;
  }

  &__form {
    display: flex;
    flex-direction: column;
    gap: 32px;
    max-width: 1040px;
  }

  &__form + &__form {
    margin-top: 32px;
  }

  &__section {
    margin: 0;
  }

  .page-section__title {
    position: relative;
    margin-bottom: 16px;
    padding-left: 11px;
    color: var(--text-primary);
    font-size: 16px;
    font-weight: 500;
    line-height: 1.5;
  }

  .page-section__title::before {
    position: absolute;
    top: 5px;
    bottom: 5px;
    left: 0;
    width: 3px;
    border-radius: 2px;
    background: var(--primary-color);
    content: '';
  }

  &__fields {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px 32px;
    padding-left: 11px;
  }

  &__form :deep(.ivu-form-item) {
    min-width: 0;
    margin-bottom: 16px;
  }

  &__form :deep(.ivu-form-item-label) {
    padding-bottom: 8px;
    color: var(--text-primary);
    font-size: 14px;
    font-weight: 500;
  }

  &__hint {
    margin: 0 0 16px;
    padding-left: 11px;
    color: var(--text-tertiary);
    font-size: 13px;
    line-height: 1.5;
  }

  &__alert {
    margin-bottom: 16px;
  }

  &__help {
    margin-top: 7px;
    color: var(--text-tertiary);
    font-size: 12px;
    line-height: 1.5;
  }

  &__restore {
    padding-top: 8px;
    padding-left: 11px;
  }

  &__actions {
    display: flex;
    gap: 8px;
    padding-left: 11px;
  }

  &__empty {
    padding: 48px 0;
    color: var(--text-tertiary);
    text-align: center;
  }

  @media (max-width: 767px) {
    .page-panel-body {
      padding: 16px;
    }

    .ghost-settings__fields {
      grid-template-columns: minmax(0, 1fr);
      gap: 4px;
    }
  }
}
</style>
