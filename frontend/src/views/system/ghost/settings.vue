<template>
  <div class="page-shell ghost-settings">
    <div class="page-shell__body">
      <div class="page-panel-body ghost-settings__panel">
        <Spin v-if="loading" fix />

        <Form
          v-if="loaded"
          ref="settingsForm"
          :key="revision"
          :model="draftValues"
          :rules="formRules"
          label-position="top"
          class="ghost-settings__form"
        >
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
                <PreferenceSettingField :field="field" :model-value="draftValues[field.key]" @update:model-value="setDraftValue(field.key, $event)" />
              </FormItem>
              <FormItem :label="$t('wu-suo-param-cut-over-type')" prop="cutOverType">
                <!-- gh-ost --cut-over enum tokens (GhostCutOverType), rendered verbatim -->
                <Select v-model="draftValues.cutOverType" transfer style="width: 100%">
                  <Option v-for="opt in cutOverTypes" :key="opt" :value="opt">{{ opt }}</Option>
                </Select>
              </FormItem>
              <FormItem v-for="field in ghostBoolFields" :key="field.key" :label="$t(field.labelKey)" :prop="field.key">
                <PreferenceSettingField :field="field" :model-value="draftValues[field.key]" @update:model-value="setDraftValue(field.key, $event)" />
              </FormItem>
            </div>
            <div class="ghost-settings__restore">
              <Button :loading="restoring" :disabled="optionsStoredBlank || loading || saving" @click="handleRestoreDefaults">
                {{ $t('wu-suo-hui-fu-mo-ren') }}
              </Button>
            </div>
          </section>
        </Form>

        <div v-else-if="!loading" class="ghost-settings__empty">{{ $t('preference-load-failed') }}</div>
      </div>
    </div>

    <div class="page-shell__footer ghost-settings__footer">
      <Button :disabled="!dirty || loading || saving" @click="resetDraft">{{ $t('qu-xiao') }}</Button>
      <Button type="primary" :loading="saving" :disabled="!dirty || loading || restoring" @click="handleSave">{{ $t('bao-cun') }}</Button>
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
  ghostDefaultOptions: 'wu-suo-can-shu'
};
const SCALAR_KV_KEYS = [KV_AUTO_ROUTE, KV_SIZE_MB, KV_ROW_THRESHOLD, KV_ALERT_PUSH];

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
      originalValues: {},
      draftValues: {},
      optionsJsonInvalid: false,
      optionsStoredBlank: true,
      autoRouteField: AUTO_ROUTE_FIELD,
      sizeThresholdField: SIZE_THRESHOLD_FIELD,
      rowThresholdField: ROW_THRESHOLD_FIELD,
      alertPushField: ALERT_PUSH_FIELD,
      ghostNumberFields: GHOST_NUMBER_FIELDS,
      ghostBoolFields: GHOST_BOOL_FIELDS,
      cutOverTypes: CUT_OVER_TYPES
    };
  },
  computed: {
    formRules() {
      const rules = {};
      [SIZE_THRESHOLD_FIELD, ROW_THRESHOLD_FIELD, ...GHOST_NUMBER_FIELDS].forEach((field) => {
        rules[field.key] = [{ trigger: 'change', validator: this.buildNumberValidator(field) }];
      });
      return rules;
    },
    dirty() {
      if (!this.loaded) {
        return false;
      }
      // an invalid stored JSON keeps the page dirty so the next save repairs the KV
      if (this.optionsJsonInvalid) {
        return true;
      }
      return [...SCALAR_KV_KEYS, ...OPTION_KEYS].some((key) => this.draftValues[key] !== this.originalValues[key]);
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
        [...SCALAR_KV_KEYS, KV_OPTIONS].forEach((key) => {
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
        this.loaded = true;
        this.revision += 1;
        await this.$nextTick();
        this.$refs.settingsForm?.clearValidate?.();
      } finally {
        this.loading = false;
      }
    },
    setDraftValue(key, value) {
      this.draftValues[key] = value;
    },
    resetDraft() {
      this.draftValues = { ...this.originalValues };
      this.revision += 1;
      this.$nextTick(() => {
        this.$refs.settingsForm?.clearValidate?.();
      });
    },
    collectWrites() {
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
    async handleSave() {
      if (!this.dirty || this.saving || this.restoring) {
        return;
      }
      const valid = await this.$refs.settingsForm.validate();
      if (!valid) {
        return;
      }
      this.saving = true;
      try {
        // one upsert per KV so a partial failure can be reported per item (modal:false — the
        // per-item message below is the report, the generic error queue must not double it)
        let failed = 0;
        for (const write of this.collectWrites()) {
          const res = await this.$services.rdpUserConfigUpsertUserConfigs({ data: this.buildUpsertData(write.key, write.value), modal: false });
          if (!res.success) {
            failed += 1;
            this.reportItemFailure(write.key, res);
          }
        }
        if (!failed) {
          this.$Message.success(this.$t('pei-zhi-yi-xiu-gai-cheng-gong'));
        }
        await this.load();
      } finally {
        this.saving = false;
      }
    },
    handleRestoreDefaults() {
      // the confirm-then-reload below discards pending edits in the other sections too — say so
      const hasDraftEdits = [...SCALAR_KV_KEYS, ...OPTION_KEYS].some((key) => this.draftValues[key] !== this.originalValues[key]);
      let content = this.$t('wu-suo-hui-fu-mo-ren-ti-shi');
      if (hasDraftEdits) {
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
        await this.load();
      } finally {
        this.restoring = false;
      }
    },
    confirmDiscardIfNeeded() {
      if (!this.dirty) {
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
      if (!this.dirty) {
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

  &__empty {
    padding: 48px 0;
    color: var(--text-tertiary);
    text-align: center;
  }

  .page-shell__footer {
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    justify-content: center;
    gap: 8px;
    min-height: 64px;
    padding: 12px 24px;
    border-top: 1px solid var(--border-light);
    background: var(--bg-card);
  }

  @media (max-width: 767px) {
    .page-panel-body {
      padding: 16px;
    }

    .ghost-settings__fields {
      grid-template-columns: minmax(0, 1fr);
      gap: 4px;
    }

    .page-shell__footer {
      flex-wrap: wrap;
      padding: 12px 16px;
    }
  }
}
</style>
