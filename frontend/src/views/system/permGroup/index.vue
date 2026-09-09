<template>
  <div class="perm-group-list">
    <div class="table-list-layout">
      <div class="table-list">
        <div class="content">
          <div class="option border-radius-card">
            <div class="left">
              <Input
                v-model.trim="searchKeyword"
                :placeholder="$t('qing-shu-ru-zu-ming-cheng')"
                style="width: 280px; margin-right: 10px"
                @on-keydown="handleEnterSearch"
                @on-clear="handleSearch"
                clearable
              />
              <Button :loading="loading" type="primary" ghost @click="handleSearch">
                {{ $t('cha-xun') }}
              </Button>
            </div>
            <div class="right">
              <Button v-if="myAuth.includes('RDP_PERM_GROUP_MANAGE')" type="primary" icon="md-add" @click="handleOpenCreate">
                {{ $t('chuang-jian-quan-xian-zu') }}
              </Button>
            </div>
          </div>
          <div class="table-container">
            <Table :columns="groupColumns" :data="showGroupList" size="small" :loading="loading" border stripe>
              <template #status="{ row }">
                <Tag :color="row.status === 'ACTIVE' ? 'success' : 'default'">
                  {{ row.status === 'ACTIVE' ? $t('qi-yong') : $t('ting-yong') }}
                </Tag>
              </template>
              <template #action="{ row }">
                <Button type="text" size="small" @click="handleViewDetail(row)">{{ $t('pei-zhi') }}</Button>
                <Poptip
                  v-if="myAuth.includes('RDP_PERM_GROUP_MANAGE')"
                  confirm
                  transfer
                  :cancel-text="$t('qu-xiao')"
                  :ok-text="$t('que-ding')"
                  :title="row.status === 'ACTIVE' ? $t('que-ding-ting-yong-gai-zu-ma') : $t('que-ding-qi-yong-gai-zu-ma')"
                  @on-ok="handleToggleStatus(row)"
                >
                  <Button type="text" size="small">
                    {{ row.status === 'ACTIVE' ? $t('ting-yong') : $t('qi-yong') }}
                  </Button>
                </Poptip>
                <Poptip
                  v-if="myAuth.includes('RDP_PERM_GROUP_MANAGE')"
                  confirm
                  transfer
                  :cancel-text="$t('qu-xiao')"
                  :ok-text="$t('que-ding')"
                  :title="$t('que-ding-shan-chu-gai-quan-xian-zu-ma')"
                  @on-ok="handleDelete(row)"
                >
                  <Button type="text" size="small">{{ $t('shan-chu') }}</Button>
                </Poptip>
              </template>
            </Table>
          </div>
        </div>
      </div>
      <div class="footer">
        <Page
          :total="total"
          show-total
          show-elevator
          show-sizer
          :page-size="pageSize"
          :model-value="pageNum"
          @on-change="handlePageChange"
          @on-page-size-change="handlePageSizeChange"
        />
      </div>
    </div>

    <CCModal v-model="formVisible" :mask-closable="false" :width="520" :title="$t('chuang-jian-quan-xian-zu')" @on-cancel="handleCloseForm">
      <Form ref="groupForm" :model="formData" :rules="formRules" :label-width="100">
        <FormItem :label="$t('zu-bian-ma')" prop="groupCode">
          <Input v-model.trim="formData.groupCode" :placeholder="$t('qing-shu-ru-zu-bian-ma')" />
        </FormItem>
        <FormItem :label="$t('zu-ming-cheng')" prop="groupName">
          <Input v-model.trim="formData.groupName" :placeholder="$t('qing-shu-ru-zu-ming-cheng')" />
        </FormItem>
        <FormItem :label="$t('miao-shu')" prop="description">
          <Input v-model.trim="formData.description" type="textarea" :rows="3" />
        </FormItem>
      </Form>
      <template #footer>
        <Button @click="handleCloseForm">{{ $t('qu-xiao') }}</Button>
        <Button type="primary" :loading="submitLoading" @click="handleSubmitForm">{{ $t('que-ding') }}</Button>
      </template>
    </CCModal>
  </div>
</template>

<script>
import { mapState } from 'vuex';

export default {
  name: 'PermGroupList',
  computed: {
    ...mapState(['myAuth']),
    groupColumns() {
      return [
        { title: this.$t('zu-bian-ma'), key: 'groupCode', minWidth: 140 },
        { title: this.$t('zu-ming-cheng'), key: 'groupName', minWidth: 160 },
        { title: this.$t('miao-shu'), key: 'description', minWidth: 200, ellipsis: true, tooltip: true },
        { title: this.$t('zhuang-tai'), slot: 'status', width: 100 },
        { title: this.$t('cheng-yuan-shu'), key: 'memberCount', width: 90, align: 'center' },
        { title: this.$t('zi-yuan-shu'), key: 'resourceCount', width: 90, align: 'center' },
        { title: this.$t('chuang-jian-shi-jian'), key: 'gmtCreate', width: 170, sortable: true },
        { title: this.$t('cao-zuo'), slot: 'action', width: 280, fixed: 'right' }
      ];
    }
  },
  data() {
    return {
      loading: false,
      searchKeyword: '',
      groupList: [],
      showGroupList: [],
      total: 0,
      pageNum: 1,
      pageSize: 20,
      formVisible: false,
      submitLoading: false,
      formData: {
        groupCode: '',
        groupName: '',
        description: ''
      },
      formRules: {
        groupCode: [{ required: true, message: this.$t('qing-shu-ru-zu-bian-ma'), trigger: 'blur' }],
        groupName: [{ required: true, message: this.$t('qing-shu-ru-zu-ming-cheng'), trigger: 'blur' }]
      }
    };
  },
  methods: {
    handleEnterSearch(event) {
      if (event.key === 'Enter' || event.keyCode === 13) {
        this.handleSearch();
      }
    },
    handleSearch() {
      this.pageNum = 1;
      this.setTableShowData();
    },
    handlePageChange(pageNum) {
      this.pageNum = pageNum;
      this.setTableShowData();
    },
    handlePageSizeChange(pageSize) {
      this.pageSize = pageSize;
      this.handleSearch();
    },
    setTableShowData() {
      const keyword = (this.searchKeyword || '').trim().toLowerCase();
      let filtered = this.groupList;
      if (keyword) {
        filtered = this.groupList.filter((group) => {
          const name = `${group.groupName || ''}`.toLowerCase();
          const code = `${group.groupCode || ''}`.toLowerCase();
          return name.includes(keyword) || code.includes(keyword);
        });
      }
      this.total = filtered.length;
      this.showGroupList = filtered.slice((this.pageNum - 1) * this.pageSize, this.pageNum * this.pageSize);
    },
    handleViewDetail(row) {
      this.$router.push(`/manager/permGroup/${row.id}`);
    },
    handleOpenCreate() {
      this.formData = { groupCode: '', groupName: '', description: '' };
      this.formVisible = true;
      this.$nextTick(() => {
        this.$refs.groupForm.resetFields();
      });
    },
    handleCloseForm() {
      this.formVisible = false;
    },
    async handleSubmitForm() {
      const valid = await this.$refs.groupForm.validate();
      if (!valid) {
        return;
      }
      this.submitLoading = true;
      const res = await this.$services.permGroupCreate({
        data: {
          groupCode: this.formData.groupCode,
          groupName: this.formData.groupName,
          description: this.formData.description
        },
        msg: this.$t('cao-zuo-cheng-gong')
      });
      this.submitLoading = false;
      if (res.success) {
        this.formVisible = false;
        await this.getGroupList();
      }
    },
    async handleToggleStatus(row) {
      const newStatus = row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
      const res = await this.$services.permGroupUpdateStatus({
        data: { groupId: row.id, status: newStatus },
        msg: newStatus === 'ACTIVE' ? this.$t('qi-yong-cheng-gong') : this.$t('ting-yong-cheng-gong')
      });
      if (res.success) {
        await this.getGroupList();
      }
    },
    async handleDelete(row) {
      const res = await this.$services.permGroupDelete({
        data: { groupId: row.id },
        msg: this.$t('shan-chu-cheng-gong')
      });
      if (res.success) {
        await this.getGroupList();
      }
    },
    async getGroupList() {
      this.loading = true;
      const res = await this.$services.permGroupList({});
      this.loading = false;
      if (res.success) {
        this.groupList = res.data || [];
        this.setTableShowData();
      }
    }
  },
  mounted() {
    this.getGroupList();
  }
};
</script>

<style lang="less" scoped>
.perm-group-list {
  height: 100%;
  display: flex;
  flex-direction: column;
}
</style>
