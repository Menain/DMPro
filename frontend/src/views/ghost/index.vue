<template>
  <div class="page-shell ghost-page">
    <div class="page-shell__body">
      <section class="page-section">
        <AppPageTabs v-model="activeTab" :tabs="tabs" />
        <div class="ghost-page__body">
          <GhostLaunchForm v-if="activeTab === 'launch'" @started="handleStarted" />
          <GhostExecutionView
            v-else-if="activeTab === 'execution' && activeSession"
            :key="activeSession.sessionId"
            :session-id="activeSession.sessionId"
            :old-table-dropped="activeSession.oldTableDropped"
            @closed="handleClosed"
            @dropped="handleDropped"
          />
          <GhostTaskList v-else-if="activeTab === 'tasks'" @view-session="handleViewSession" />
        </div>
      </section>
    </div>
  </div>
</template>

<script>
import AppPageTabs from '@/components/layout/AppPageTabs';
import GhostLaunchForm from './GhostLaunchForm.vue';
import GhostExecutionView from './GhostExecutionView.vue';
import GhostTaskList from './GhostTaskList.vue';

export default {
  name: 'GhostOnlineDdl',
  components: {
    AppPageTabs,
    GhostLaunchForm,
    GhostExecutionView,
    GhostTaskList
  },
  data() {
    return {
      activeTab: 'launch',
      // {sessionId, oldTableDropped} of the session shown in the execution view; v-if per tab
      // destroys/recreates the panels, so the execution view's poll timer dies on tab switch.
      activeSession: null
    };
  },
  computed: {
    tabs() {
      return [
        { name: 'launch', label: this.$t('wu-suo-fa-qi-qian-yi') },
        { name: 'execution', label: this.$t('wu-suo-zhi-xing-shi-tu'), disabled: !this.activeSession },
        { name: 'tasks', label: this.$t('wu-suo-ren-wu-lie-biao') }
      ];
    }
  },
  methods: {
    handleStarted(sessionId) {
      this.activeSession = { sessionId, oldTableDropped: false };
      this.activeTab = 'execution';
    },
    handleViewSession(row) {
      this.activeSession = { sessionId: row.sessionId, oldTableDropped: row.oldTableDropped };
      this.activeTab = 'execution';
    },
    handleClosed() {
      this.activeSession = null;
      this.activeTab = 'tasks';
    },
    handleDropped() {
      if (this.activeSession) {
        this.activeSession.oldTableDropped = true;
      }
    }
  }
};
</script>

<style lang="less" scoped>
.ghost-page__body {
  margin-top: 16px;
}
</style>
