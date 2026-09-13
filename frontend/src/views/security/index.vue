<template>
  <div class="security-rule-page">
    <AppPageTabs :model-value="activeSection" :tabs="sectionTabs" @change="handleSectionClick" />
    <div class="entry-note">{{ entryNote }}</div>
    <div class="security-rule-content">
      <SpecList v-if="activeSection === 'security'" />
      <RuleList v-if="activeSection === 'template'" />
    </div>
  </div>
</template>

<script>
import AppPageTabs from '@/components/layout/AppPageTabs';
import SpecList from '@/views/security/spec/index';
import RuleList from '@/views/security/rule/index';

export default {
  name: 'SecurityRules',
  components: {
    AppPageTabs,
    SpecList,
    RuleList
  },
  created() {
    this.handleLegacyRedirect();
  },
  data() {
    return {
      activeSection: 'security'
    };
  },
  computed: {
    effectiveRuleKind() {
      return this.$route.meta.fixedRuleKind || this.$route.query.ruleKind || null;
    },
    entryPath() {
      return this.effectiveRuleKind === 'SENSITIVE' ? '/data-access/desens' : '/data-access/rules';
    },
    sectionTabs() {
      if (this.effectiveRuleKind === 'SENSITIVE') {
        return [
          { name: 'security', label: this.$t('tuo-min-gui-fan-ji') },
          { name: 'template', label: this.$t('tuo-min-gui-ze') }
        ];
      }
      return [
        { name: 'security', label: this.$t('an-quan-gui-fan') },
        { name: 'template', label: this.$t('gui-ze-mo-ban') }
      ];
    },
    entryNote() {
      if (this.effectiveRuleKind === 'SENSITIVE') {
        return this.$t('tuo-min-guan-li-shuo-ming');
      }
      return this.$t('an-quan-gui-ze-shuo-ming');
    }
  },
  watch: {
    '$route.query': {
      handler() {
        this.syncActiveSection();
      },
      immediate: true
    }
  },
  methods: {
    handleLegacyRedirect() {
      if (this.$route.path === '/data-access/rules' && this.$route.query.ruleKind === 'SENSITIVE') {
        const query = { ...this.$route.query };
        delete query.ruleKind;
        this.$router.replace({ path: '/data-access/desens', query });
      }
    },
    syncActiveSection() {
      const { tab, ruleKind } = this.$route.query || {};
      this.activeSection = tab === 'template' || ruleKind ? 'template' : 'security';
    },
    handleSectionClick(name) {
      const query = { ...(this.$route.query || {}), tab: name };
      delete query.ruleKind;
      this.$router.replace({
        path: this.entryPath,
        query
      });
    }
  }
};
</script>

<style lang="less" scoped>
.security-rule-page {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.entry-note {
  margin: 12px 0 0;
  padding: 8px 12px;
  background: #f8fafc;
  border-radius: 6px;
  color: #5b667a;
  font-size: 13px;
  line-height: 20px;
}

.security-rule-content {
  flex: 1;
  min-height: 0;
}
</style>
