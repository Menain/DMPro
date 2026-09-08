<template>
  <div class="perm-group-detail">
    <div class="detail-header">
      <div class="detail-header__back">
        <Button type="text" @click="handleBack">{{ $t('fan-hui') }}</Button>
        <span class="detail-header__title">{{ groupDetail.groupName || '' }}</span>
        <Tag v-if="groupDetail.status" :color="groupDetail.status === 'ACTIVE' ? 'success' : 'default'" class="detail-header__status">
          {{ groupDetail.status === 'ACTIVE' ? $t('qi-yong') : $t('ting-yong') }}
        </Tag>
      </div>
    </div>

    <AppPageTabs v-model="activeTab" :tabs="tabs" class="detail-tabs" />

    <div class="detail-body">
      <div v-show="activeTab === 'info'" class="tab-panel">
        <div class="info-section">
          <div class="info-row">
            <span class="info-label">{{ $t('zu-bian-ma') }}</span>
            <span class="info-value">{{ groupDetail.groupCode || '-' }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">{{ $t('zu-ming-cheng') }}</span>
            <span class="info-value">{{ groupDetail.groupName || '-' }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">{{ $t('miao-shu') }}</span>
            <span class="info-value">{{ groupDetail.description || '-' }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">{{ $t('cheng-yuan-shu') }}</span>
            <span class="info-value">{{ displayCount(groupDetail.memberCount) }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">{{ $t('zi-yuan-shu') }}</span>
            <span class="info-value">{{ displayCount(groupDetail.resourceCount) }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">{{ $t('chuang-jian-ren') }}</span>
            <span class="info-value">{{ groupDetail.creatorUid || '-' }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">{{ $t('chuang-jian-shi-jian') }}</span>
            <span class="info-value">{{ groupDetail.gmtCreate || '-' }}</span>
          </div>
        </div>
        <div v-if="myAuth.includes('RDP_PERM_GROUP_MANAGE')" class="info-actions">
          <Button type="primary" @click="handleOpenEdit">{{ $t('bian-ji') }}</Button>
        </div>
      </div>

      <div v-show="activeTab === 'members'" class="tab-panel">
        <div class="tab-toolbar">
          <Button v-if="myAuth.includes('RDP_PERM_GROUP_MANAGE')" type="primary" icon="md-add" @click="openAddMember">
            {{ $t('tian-jia-cheng-yuan') }}
          </Button>
        </div>
        <div class="table-container">
          <Table :columns="memberColumns" :data="memberList" size="small" :loading="memberLoading" border stripe>
            <template #action="{ row }">
              <Poptip
                v-if="myAuth.includes('RDP_PERM_GROUP_MANAGE')"
                confirm
                transfer
                :cancel-text="$t('qu-xiao')"
                :ok-text="$t('que-ding')"
                :title="$t('que-ding-yi-chu-gai-cheng-yuan-ma')"
                @on-ok="handleRemoveMember(row)"
              >
                <Button type="text" size="small">{{ $t('yi-chu-cheng-yuan') }}</Button>
              </Poptip>
            </template>
          </Table>
        </div>
      </div>

      <div v-show="activeTab === 'resources'" class="tab-panel">
        <PermGroupResourceTab :group-id="groupId" />
      </div>
    </div>

    <CCModal v-model="editVisible" :mask-closable="false" :width="520" :title="$t('bian-ji-quan-xian-zu')" @on-cancel="editVisible = false">
      <Form ref="editForm" :model="editData" :rules="editRules" :label-width="100">
        <FormItem :label="$t('zu-ming-cheng')" prop="groupName">
          <Input v-model.trim="editData.groupName" :placeholder="$t('qing-shu-ru-zu-ming-cheng')" />
        </FormItem>
        <FormItem :label="$t('miao-shu')" prop="description">
          <Input v-model.trim="editData.description" type="textarea" :rows="3" />
        </FormItem>
      </Form>
      <template #footer>
        <Button @click="editVisible = false">{{ $t('qu-xiao') }}</Button>
        <Button type="primary" :loading="editLoading" @click="handleSaveEdit">{{ $t('que-ding') }}</Button>
      </template>
    </CCModal>

    <CCModal v-model="showAddMember" :mask-closable="false" :width="600" :title="$t('tian-jia-cheng-yuan')" @on-cancel="showAddMember = false">
      <Form :label-width="80">
        <FormItem :label="$t('cheng-yuan')">
          <Select v-model="selectedUids" multiple filterable transfer :placeholder="$t('qing-xuan-ze-cheng-yuan')" :loading="userListLoading">
            <Option v-for="user in availableUsers" :key="user.uid" :value="user.uid" :label="userDisplay(user)">
              {{ userDisplay(user) }}
            </Option>
          </Select>
        </FormItem>
      </Form>
      <template #footer>
        <Button @click="showAddMember = false">{{ $t('qu-xiao') }}</Button>
        <Button type="primary" :loading="addMemberLoading" :disabled="!selectedUids.length" @click="handleAddMembers">
          {{ $t('que-ding') }}
        </Button>
      </template>
    </CCModal>
  </div>
</template>

<script>
import { mapState } from 'vuex';
import AppPageTabs from '@/components/layout/AppPageTabs';
import PermGroupResourceTab from './PermGroupResourceTab';

export default {
  name: 'PermGroupDetail',
  components: { AppPageTabs, PermGroupResourceTab },
  computed: {
    ...mapState(['myAuth']),
    groupId() {
      return Number(this.$route.params.groupId);
    },
    tabs() {
      return [
        { name: 'info', label: this.$t('ji-ben-xin-xi') },
        { name: 'members', label: this.$t('cheng-yuan') },
        { name: 'resources', label: this.$t('zi-yuan-quan-xian') }
      ];
    },
    memberColumns() {
      return [
        { title: this.$t('yong-hu-ming'), key: 'username', minWidth: 140 },
        { title: 'UID', key: 'uid', minWidth: 200 },
        { title: this.$t('chuang-jian-shi-jian'), key: 'gmtCreate', width: 170 },
        { title: this.$t('cao-zuo'), slot: 'action', width: 140, fixed: 'right' }
      ];
    },
    availableUsers() {
      const memberUids = new Set(this.memberList.map((m) => m.uid));
      return this.allUsers.filter((user) => !memberUids.has(user.uid));
    }
  },
  data() {
    return {
      activeTab: 'info',
      groupDetail: {},
      editVisible: false,
      editLoading: false,
      editData: { groupId: 0, groupName: '', description: '' },
      editRules: {
        groupName: [{ required: true, message: this.$t('qing-shu-ru-zu-ming-cheng'), trigger: 'blur' }]
      },
      memberLoading: false,
      memberList: [],
      showAddMember: false,
      userListLoading: false,
      addMemberLoading: false,
      allUsers: [],
      selectedUids: []
    };
  },
  watch: {
    activeTab(tab) {
      if (tab === 'members' && this.memberList.length === 0) {
        this.getMemberList();
      }
    }
  },
  methods: {
    handleBack() {
      this.$router.push('/manager/permGroup');
    },
    displayCount(value) {
      return value != null ? String(value) : String(0);
    },
    userDisplay(user) {
      if (user.username) {
        return user.username;
      }
      return user.uid;
    },
    handleOpenEdit() {
      this.editData = {
        groupId: this.groupId,
        groupName: this.groupDetail.groupName || '',
        description: this.groupDetail.description || ''
      };
      this.editVisible = true;
    },
    async handleSaveEdit() {
      const valid = await this.$refs.editForm.validate();
      if (!valid) {
        return;
      }
      this.editLoading = true;
      const res = await this.$services.permGroupUpdate({
        data: {
          groupId: this.editData.groupId,
          groupName: this.editData.groupName,
          description: this.editData.description
        },
        msg: this.$t('bao-cun-cheng-gong')
      });
      this.editLoading = false;
      if (res.success) {
        this.editVisible = false;
        await this.getGroupDetail();
      }
    },
    async getGroupDetail() {
      const res = await this.$services.permGroupDetail({ data: { groupId: this.groupId } });
      if (res.success) {
        this.groupDetail = res.data || {};
      }
    },
    async getMemberList() {
      this.memberLoading = true;
      const res = await this.$services.permGroupMemberList({ data: { groupId: this.groupId } });
      this.memberLoading = false;
      if (res.success) {
        this.memberList = res.data || [];
      }
    },
    async loadUserList() {
      this.userListLoading = true;
      const res = await this.$services.rdpUserManagerListSubAccounts({
        data: { roleId: 0, userNameOrSubAccountPrefix: '' }
      });
      this.userListLoading = false;
      if (res.success && Array.isArray(res.data)) {
        this.allUsers = res.data.filter((user) => !user.disable);
      }
    },
    openAddMember() {
      this.selectedUids = [];
      this.showAddMember = true;
    },
    async handleAddMembers() {
      this.addMemberLoading = true;
      const res = await this.$services.permGroupMemberAdd({
        data: { groupId: this.groupId, uids: this.selectedUids },
        msg: this.$t('cao-zuo-cheng-gong')
      });
      this.addMemberLoading = false;
      if (res.success) {
        this.showAddMember = false;
        this.selectedUids = [];
        await this.getMemberList();
        await this.getGroupDetail();
      }
    },
    async handleRemoveMember(row) {
      const res = await this.$services.permGroupMemberRemove({
        data: { groupId: this.groupId, uids: [row.uid] },
        msg: this.$t('cao-zuo-cheng-gong')
      });
      if (res.success) {
        await this.getMemberList();
        await this.getGroupDetail();
      }
    }
  },
  mounted() {
    this.getGroupDetail();
    this.loadUserList();
  }
};
</script>

<style lang="less" scoped>
.perm-group-detail {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 12px;

  &__back {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  &__title {
    font-size: 16px;
    font-weight: 500;
  }

  &__status {
    margin-left: 4px;
  }
}

.detail-tabs {
  margin-bottom: 16px;
}

.detail-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.tab-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.info-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.info-row {
  display: flex;
  align-items: flex-start;
  gap: 16px;
}

.info-label {
  flex: 0 0 120px;
  color: var(--text-secondary, #41454d);
  font-size: 14px;
}

.info-value {
  flex: 1;
  font-size: 14px;
  word-break: break-all;
}

.info-actions {
  display: flex;
  gap: 8px;
}

.tab-toolbar {
  display: flex;
  justify-content: flex-end;
}

.table-container {
  overflow-x: auto;
}
</style>
