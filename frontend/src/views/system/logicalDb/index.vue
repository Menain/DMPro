<template>
  <div class="logical-db-list">
    <div class="table-list-layout">
      <div class="table-list">
        <div class="content">
          <div class="option border-radius-card">
            <div class="left">
              <Input
                v-model.trim="searchKeyword"
                :placeholder="$t('qing-shu-ru-zi-yuan-ming-cheng')"
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
              <Button v-if="myAuth.includes('RDP_LOGICAL_DB_MANAGE')" type="primary" icon="md-add" @click="handleOpenCreate">
                {{ $t('chuang-jian-luo-ji-ku') }}
              </Button>
            </div>
          </div>
          <div class="table-container">
            <Table :columns="dbColumns" :data="showDbList" size="small" :loading="loading" border stripe>
              <template #status="{ row }">
                <Tag :color="row.status === 'ENABLED' ? 'success' : 'default'">
                  {{ row.status === 'ENABLED' ? $t('qi-yong') : $t('jin-yong') }}
                </Tag>
              </template>
              <template #action="{ row }">
                <Button type="text" size="small" @click="handleOpenBindingEdit(row)">{{ $t('zi-yuan-bang-ding') }}</Button>
                <Button v-if="myAuth.includes('RDP_LOGICAL_DB_MANAGE')" type="text" size="small" @click="handleOpenEdit(row)">
                  {{ $t('bian-ji') }}
                </Button>
                <Poptip
                  v-if="myAuth.includes('RDP_LOGICAL_DB_MANAGE')"
                  confirm
                  transfer
                  :cancel-text="$t('qu-xiao')"
                  :ok-text="$t('que-ding')"
                  :title="$t('que-ding-shan-chu-gai-luo-ji-ku-ma')"
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

    <CCModal v-model="formVisible" :mask-closable="false" :width="520" :title="formTitle" @on-cancel="formVisible = false">
      <Form ref="dbForm" :model="formData" :rules="formRules" :label-width="100">
        <FormItem v-if="formMode === 'create'" :label="$t('zi-yuan-bian-ma')" prop="resourceCode">
          <Input v-model.trim="formData.resourceCode" :placeholder="$t('qing-shu-ru-zi-yuan-bian-ma')" />
        </FormItem>
        <FormItem :label="$t('zi-yuan-ming-cheng')" prop="resourceName">
          <Input v-model.trim="formData.resourceName" :placeholder="$t('qing-shu-ru-zi-yuan-ming-cheng')" />
        </FormItem>
        <FormItem :label="$t('miao-shu')" prop="description">
          <Input v-model.trim="formData.description" type="textarea" :rows="3" maxlength="512" />
        </FormItem>
      </Form>
      <template #footer>
        <Button @click="formVisible = false">{{ $t('qu-xiao') }}</Button>
        <Button type="primary" :loading="submitLoading" @click="handleSubmitForm">{{ $t('que-ding') }}</Button>
      </template>
    </CCModal>

    <CCModal v-model="bindingVisible" :mask-closable="false" :width="900" :title="$t('zi-yuan-bang-ding')" @on-cancel="bindingVisible = false">
      <div class="binding-section">
        <div class="binding-toolbar">
          <Button type="primary" icon="md-add" @click="handleAddBindingRow">{{ $t('tian-jia-bang-ding') }}</Button>
        </div>
        <div class="binding-table-container">
          <Table :columns="bindingColumns" :data="bindingRows" size="small" border>
            <template #envId="{ row, index }">
              <Select v-model="bindingRows[index].envId" filterable transfer :placeholder="$t('qing-xuan-ze-huan-jing')" :disabled="row._existing">
                <Option v-for="env in envList" :key="env.id" :value="env.id">{{ env.envName }}</Option>
              </Select>
            </template>
            <template #dsId="{ row, index }">
              <Select v-model="bindingRows[index].dsId" filterable transfer :placeholder="$t('qing-xuan-ze-shu-ju-yuan')" :disabled="row._existing">
                <Option v-for="ds in dsList" :key="ds.id" :value="ds.id">
                  {{ ds.instanceDesc || ds.instanceId }}
                  <span v-if="ds.dsEnvName">（{{ ds.dsEnvName }}）</span>
                </Option>
              </Select>
            </template>
            <template #resPath="{ row, index }">
              <Input v-model="bindingRows[index].resPath" :placeholder="$t('qing-shu-ru-zi-yuan-lu-jing')" :disabled="row._existing" />
            </template>
            <template #bindingAction="{ row, index }">
              <Button type="text" size="small" @click="handleRemoveBindingRow(index)">{{ $t('yi-chu') }}</Button>
            </template>
          </Table>
        </div>
      </div>
      <template #footer>
        <Button @click="bindingVisible = false">{{ $t('qu-xiao') }}</Button>
        <Button type="primary" :loading="bindingLoading" @click="handleSaveBindings">{{ $t('bao-cun') }}</Button>
      </template>
    </CCModal>
  </div>
</template>

<script>
import { mapState } from 'vuex';

export default {
  name: 'LogicalDbList',
  computed: {
    ...mapState(['myAuth']),
    dbColumns() {
      return [
        { title: this.$t('zi-yuan-bian-ma'), key: 'resourceCode', minWidth: 140 },
        { title: this.$t('zi-yuan-ming-cheng'), key: 'resourceName', minWidth: 160 },
        { title: this.$t('miao-shu'), key: 'description', minWidth: 200, ellipsis: true, tooltip: true },
        { title: this.$t('zhuang-tai'), slot: 'status', width: 100 },
        { title: this.$t('chuang-jian-shi-jian'), key: 'gmtCreate', width: 170, sortable: true },
        { title: this.$t('cao-zuo'), slot: 'action', width: 280, fixed: 'right' }
      ];
    },
    bindingColumns() {
      return [
        { title: this.$t('huan-jing'), slot: 'envId', width: 180 },
        { title: this.$t('shu-ju-yuan'), slot: 'dsId', width: 200 },
        { title: this.$t('zi-yuan-lu-jing'), slot: 'resPath', minWidth: 200 },
        { title: this.$t('cao-zuo'), slot: 'bindingAction', width: 90, fixed: 'right' }
      ];
    },
    formTitle() {
      return this.formMode === 'create' ? this.$t('chuang-jian-luo-ji-ku') : this.$t('bian-ji-luo-ji-ku');
    }
  },
  data() {
    return {
      loading: false,
      searchKeyword: '',
      dbList: [],
      showDbList: [],
      total: 0,
      pageNum: 1,
      pageSize: 20,
      formVisible: false,
      formMode: 'create',
      submitLoading: false,
      formData: {
        resourceCode: '',
        resourceName: '',
        description: ''
      },
      formRules: {
        resourceCode: [{ required: true, message: this.$t('qing-shu-ru-zi-yuan-bian-ma'), trigger: 'blur' }],
        resourceName: [{ required: true, message: this.$t('qing-shu-ru-zi-yuan-ming-cheng'), trigger: 'blur' }]
      },
      bindingVisible: false,
      bindingLoading: false,
      bindingRows: [],
      editingLogicalDbId: null,
      envList: [],
      dsList: [],
      envLoading: false,
      dsLoading: false
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
      let filtered = this.dbList;
      if (keyword) {
        filtered = this.dbList.filter((db) => {
          const name = `${db.resourceName || ''}`.toLowerCase();
          const code = `${db.resourceCode || ''}`.toLowerCase();
          return name.includes(keyword) || code.includes(keyword);
        });
      }
      this.total = filtered.length;
      this.showDbList = filtered.slice((this.pageNum - 1) * this.pageSize, this.pageNum * this.pageSize);
    },
    handleOpenCreate() {
      this.formMode = 'create';
      this.formData = { resourceCode: '', resourceName: '', description: '' };
      this.formVisible = true;
      this.$nextTick(() => {
        this.$refs.dbForm.resetFields();
      });
    },
    handleOpenEdit(row) {
      this.formMode = 'edit';
      this.formData = {
        id: row.id,
        resourceCode: row.resourceCode,
        resourceName: row.resourceName,
        description: row.description
      };
      this.formVisible = true;
    },
    async handleSubmitForm() {
      const valid = await this.$refs.dbForm.validate();
      if (!valid) {
        return;
      }
      this.submitLoading = true;
      if (this.formMode === 'create') {
        const res = await this.$services.logicalDbCreate({
          data: {
            resourceCode: this.formData.resourceCode,
            resourceName: this.formData.resourceName,
            description: this.formData.description
          },
          msg: this.$t('cao-zuo-cheng-gong')
        });
        this.submitLoading = false;
        if (res.success) {
          this.formVisible = false;
          await this.getDbList();
        }
      } else {
        const res = await this.$services.logicalDbUpdate({
          data: {
            id: this.formData.id,
            resourceName: this.formData.resourceName,
            description: this.formData.description
          },
          msg: this.$t('bao-cun-cheng-gong')
        });
        this.submitLoading = false;
        if (res.success) {
          this.formVisible = false;
          await this.getDbList();
        }
      }
    },
    async handleDelete(row) {
      const res = await this.$services.logicalDbDelete({
        data: { id: row.id },
        msg: this.$t('shan-chu-cheng-gong')
      });
      if (res.success) {
        await this.getDbList();
      }
    },
    async handleOpenBindingEdit(row) {
      this.editingLogicalDbId = row.id;
      this.bindingVisible = true;
      this.bindingRows = [];
      await this.loadBindingList(row.id);
      if (this.envList.length === 0) {
        this.loadEnvList();
      }
      if (this.dsList.length === 0) {
        this.loadDsList();
      }
    },
    async loadBindingList(logicalDbId) {
      const res = await this.$services.logicalDbBindingList({ data: { id: logicalDbId } });
      if (res.success && Array.isArray(res.data)) {
        this.bindingRows = res.data.map((binding) => ({
          _existing: true,
          bindingId: binding.bindingId,
          envId: binding.envId,
          envName: binding.envName,
          dsId: binding.dsId,
          dsName: binding.dsName,
          resPath: binding.resPath
        }));
      }
    },
    handleAddBindingRow() {
      this.bindingRows.push({
        _existing: false,
        envId: null,
        dsId: null,
        resPath: ''
      });
    },
    handleRemoveBindingRow(index) {
      this.bindingRows.splice(index, 1);
    },
    async handleSaveBindings() {
      for (let i = 0; i < this.bindingRows.length; i++) {
        const row = this.bindingRows[i];
        if (!row.envId || !row.dsId || !row.resPath) {
          this.$Message.warning(this.$t('qing-tian-xie-wan-zheng-bang-ding-xin-xi'));
          return;
        }
      }
      const bindings = this.bindingRows.map((row) => ({
        envId: row.envId,
        dsId: row.dsId,
        resPath: row.resPath
      }));
      this.bindingLoading = true;
      const res = await this.$services.logicalDbBindingSet({
        data: {
          logicalDbId: this.editingLogicalDbId,
          bindings
        },
        msg: this.$t('bao-cun-cheng-gong')
      });
      this.bindingLoading = false;
      if (res.success) {
        this.bindingVisible = false;
        await this.loadBindingList(this.editingLogicalDbId);
      }
    },
    async loadEnvList() {
      this.envLoading = true;
      const res = await this.$services.rdpDsEnvList({ data: { envName: '' } });
      this.envLoading = false;
      if (res.success && Array.isArray(res.data)) {
        this.envList = res.data;
      }
    },
    async loadDsList() {
      this.dsLoading = true;
      const res = await this.$services.dmDataSourceListByCondition({
        data: { useVisibility: true }
      });
      this.dsLoading = false;
      if (res.success && Array.isArray(res.data)) {
        this.dsList = res.data;
      }
    },
    async getDbList() {
      this.loading = true;
      const res = await this.$services.logicalDbList({
        data: { keyword: this.searchKeyword }
      });
      this.loading = false;
      if (res.success && Array.isArray(res.data)) {
        this.dbList = res.data;
        this.setTableShowData();
      }
    }
  },
  mounted() {
    this.getDbList();
  }
};
</script>

<style lang="less" scoped>
.logical-db-list {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.binding-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.binding-toolbar {
  display: flex;
  justify-content: flex-end;
}

.binding-table-container {
  overflow-x: auto;
}

.text-muted {
  color: #999;
  font-size: 13px;
}

.text-success {
  color: #52c41a;
  font-size: 13px;
}
</style>
