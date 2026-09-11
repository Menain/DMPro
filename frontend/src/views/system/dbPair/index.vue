<template>
  <div class="db-pair-page">
    <AppPageTabs :model-value="activeTab" :tabs="tabs" @change="handleTabChange" />

    <div class="table-list-layout">
      <div class="table-list">
        <div class="content">
          <div class="option border-radius-card">
            <div class="left">
              <Input
                v-model.trim="searchKeyword"
                :placeholder="$t('qing-shu-ru-guan-jian-zi')"
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
              <Button v-if="activeTab === 'pair' && myAuth.includes('GOV_DB_PAIR_MANAGE')" type="primary" icon="md-add" @click="handleOpenPairCreate">
                {{ $t('xin-jian-ku-ying-she') }}
              </Button>
              <Button
                v-if="activeTab === 'service' && myAuth.includes('GOV_DB_PAIR_MANAGE')"
                type="primary"
                icon="md-add"
                @click="handleOpenServiceCreate"
              >
                {{ $t('xin-jian-fu-wu') }}
              </Button>
            </div>
          </div>

          <div v-if="activeTab === 'pair'" class="table-container">
            <Table :columns="pairColumns" :data="showPairList" size="small" :loading="loading" border stripe>
              <template #preSide="{ row }">
                <span v-if="row.preDsId">
                  {{ row.preDsName || row.preDsId }}
                  <span v-if="row.preEnvName" class="text-muted">（{{ row.preEnvName }}）</span>
                  <br />
                  <span class="db-name-text">{{ row.preDbName }}</span>
                </span>
                <span v-else class="text-muted">{{ $t('zhan-wei-fu') }}</span>
              </template>
              <template #prodSide="{ row }">
                {{ row.prodDsName || row.prodDsId }}
                <span v-if="row.prodEnvName" class="text-muted">（{{ row.prodEnvName }}）</span>
                <br />
                <span class="db-name-text">{{ row.prodDbName }}</span>
              </template>
              <template #services="{ row }">
                <Tag v-for="svc in row.services" :key="svc.id" size="small">
                  {{ svc.serviceName }}
                </Tag>
              </template>
              <template #status="{ row }">
                <Tag :color="row.status === 'ENABLED' ? 'success' : 'default'">
                  {{ row.status === 'ENABLED' ? $t('qi-yong') : $t('jin-yong') }}
                </Tag>
              </template>
              <template #pairAction="{ row }">
                <Button v-if="myAuth.includes('GOV_DB_PAIR_MANAGE')" type="text" size="small" @click="handleOpenPairEdit(row)">
                  {{ $t('bian-ji') }}
                </Button>
                <Poptip
                  v-if="myAuth.includes('GOV_DB_PAIR_MANAGE')"
                  confirm
                  transfer
                  :cancel-text="$t('qu-xiao')"
                  :ok-text="$t('que-ding')"
                  :title="$t('que-ding-shan-chu-gai-ku-ying-she-ma')"
                  @on-ok="handlePairDelete(row)"
                >
                  <Button type="text" size="small">{{ $t('shan-chu') }}</Button>
                </Poptip>
              </template>
            </Table>
          </div>

          <div v-if="activeTab === 'service'" class="table-container">
            <Table :columns="serviceColumns" :data="showServiceList" size="small" :loading="loading" border stripe>
              <template #svcAction="{ row }">
                <Button v-if="myAuth.includes('GOV_DB_PAIR_MANAGE')" type="text" size="small" @click="handleOpenServiceEdit(row)">
                  {{ $t('bian-ji') }}
                </Button>
                <Poptip
                  v-if="myAuth.includes('GOV_DB_PAIR_MANAGE')"
                  confirm
                  transfer
                  :cancel-text="$t('qu-xiao')"
                  :ok-text="$t('que-ding')"
                  :title="$t('que-ding-shan-chu-gai-fu-wu-ma')"
                  @on-ok="handleServiceDelete(row)"
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
          :total="activeTab === 'pair' ? pairTotal : serviceTotal"
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

    <CCModal
      v-model="pairFormVisible"
      :mask-closable="false"
      :width="620"
      :title="pairFormMode === 'create' ? $t('xin-jian-ku-ying-she') : $t('bian-ji-ku-ying-she')"
      @on-cancel="pairFormVisible = false"
    >
      <Form ref="pairForm" :model="pairFormData" :rules="pairFormRules" :label-width="120">
        <FormItem :label="$t('yu-sheng-chan-shu-ju-yuan')" prop="preDsId">
          <Select
            v-model="pairFormData.preDsId"
            filterable
            transfer
            clearable
            :placeholder="$t('qing-xuan-ze-yu-sheng-chan-shu-ju-yuan')"
            :disabled="pairFormMode === 'edit'"
            @on-change="handlePreDsChange"
          >
            <Option v-for="ds in dsList" :key="ds.id" :value="ds.id">
              {{ ds.instanceDesc || ds.instanceId }}
              <span v-if="ds.dsEnvName">（{{ ds.dsEnvName }}）</span>
            </Option>
          </Select>
        </FormItem>
        <FormItem :label="$t('yu-sheng-chan-ku-ming')" prop="preDbName">
          <AutoComplete
            v-model="pairFormData.preDbName"
            transfer
            filterable
            :placeholder="$t('qing-shu-ru-yu-xuan-ze-yu-sheng-chan-ku-ming')"
            :disabled="pairFormMode === 'edit'"
          >
            <Option v-for="name in preDbCandidates" :key="name" :value="name">{{ name }}</Option>
          </AutoComplete>
        </FormItem>
        <FormItem :label="$t('sheng-chan-shu-ju-yuan')" prop="prodDsId">
          <Select
            v-model="pairFormData.prodDsId"
            filterable
            transfer
            :placeholder="$t('qing-xuan-ze-sheng-chan-shu-ju-yuan')"
            :disabled="pairFormMode === 'edit'"
            @on-change="handleProdDsChange"
          >
            <Option v-for="ds in dsList" :key="ds.id" :value="ds.id">
              {{ ds.instanceDesc || ds.instanceId }}
              <span v-if="ds.dsEnvName">（{{ ds.dsEnvName }}）</span>
            </Option>
          </Select>
        </FormItem>
        <FormItem :label="$t('sheng-chan-ku-ming')" prop="prodDbName">
          <AutoComplete
            v-model="pairFormData.prodDbName"
            transfer
            filterable
            :placeholder="$t('qing-shu-ru-yu-xuan-ze-sheng-chan-ku-ming')"
            :disabled="pairFormMode === 'edit'"
          >
            <Option v-for="name in prodDbCandidates" :key="name" :value="name">{{ name }}</Option>
          </AutoComplete>
        </FormItem>
        <FormItem :label="$t('guan-lian-fu-wu')" prop="serviceIds">
          <Select v-model="pairFormData.serviceIds" multiple filterable transfer :placeholder="$t('qing-xuan-ze-guan-lian-fu-wu')">
            <Option v-for="svc in serviceOptions" :key="svc.id" :value="svc.id">{{ svc.serviceName }}（{{ svc.serviceCode }}）</Option>
          </Select>
        </FormItem>
        <FormItem v-if="pairFormMode === 'edit'" :label="$t('zhuang-tai')" prop="status">
          <Select v-model="pairFormData.status" transfer>
            <Option value="ENABLED">{{ $t('qi-yong') }}</Option>
            <Option value="DISABLED">{{ $t('jin-yong') }}</Option>
          </Select>
        </FormItem>
        <FormItem :label="$t('bei-zhu')" prop="remark">
          <Input v-model.trim="pairFormData.remark" type="textarea" :rows="3" :maxlength="512" />
        </FormItem>
      </Form>
      <template #footer>
        <Button @click="pairFormVisible = false">{{ $t('qu-xiao') }}</Button>
        <Button type="primary" :loading="pairSubmitLoading" @click="handlePairSubmit">
          {{ $t('que-ding') }}
        </Button>
      </template>
    </CCModal>

    <CCModal
      v-model="serviceFormVisible"
      :mask-closable="false"
      :width="520"
      :title="serviceFormMode === 'create' ? $t('xin-jian-fu-wu') : $t('bian-ji-fu-wu')"
      @on-cancel="serviceFormVisible = false"
    >
      <Form ref="serviceForm" :model="serviceFormData" :rules="serviceFormRules" :label-width="100">
        <FormItem v-if="serviceFormMode === 'create'" :label="$t('fu-wu-bian-ma')" prop="serviceCode">
          <Input v-model.trim="serviceFormData.serviceCode" :placeholder="$t('qing-shu-ru-fu-wu-bian-ma')" />
        </FormItem>
        <FormItem v-else :label="$t('fu-wu-bian-ma')">
          <Input :model-value="serviceFormData.serviceCode" disabled />
        </FormItem>
        <FormItem :label="$t('fu-wu-ming-cheng')" prop="serviceName">
          <Input v-model.trim="serviceFormData.serviceName" :placeholder="$t('qing-shu-ru-fu-wu-ming-cheng')" />
        </FormItem>
        <FormItem :label="$t('bei-zhu')" prop="remark">
          <Input v-model.trim="serviceFormData.remark" type="textarea" :rows="3" :maxlength="512" />
        </FormItem>
      </Form>
      <template #footer>
        <Button @click="serviceFormVisible = false">{{ $t('qu-xiao') }}</Button>
        <Button type="primary" :loading="serviceSubmitLoading" @click="handleServiceSubmit">
          {{ $t('que-ding') }}
        </Button>
      </template>
    </CCModal>
  </div>
</template>

<script>
import { mapState } from 'vuex';
import AppPageTabs from '@/components/layout/AppPageTabs';

export default {
  name: 'DbPairPage',
  components: { AppPageTabs },
  computed: {
    ...mapState(['myAuth']),
    tabs() {
      return [
        { name: 'pair', label: this.$t('ku-ying-she') },
        { name: 'service', label: this.$t('fu-wu-lie-biao') }
      ];
    },
    pairColumns() {
      return [
        { title: this.$t('yu-sheng-chan-shu-ju-yuan'), slot: 'preSide', minWidth: 200 },
        { title: this.$t('sheng-chan-shu-ju-yuan'), slot: 'prodSide', minWidth: 200 },
        { title: this.$t('guan-lian-fu-wu'), slot: 'services', minWidth: 200 },
        { title: this.$t('zhuang-tai'), slot: 'status', width: 100 },
        { title: this.$t('bei-zhu'), key: 'remark', minWidth: 160, ellipsis: true, tooltip: true },
        { title: this.$t('cao-zuo'), slot: 'pairAction', width: 160, fixed: 'right' }
      ];
    },
    serviceColumns() {
      return [
        { title: this.$t('fu-wu-bian-ma'), key: 'serviceCode', minWidth: 140 },
        { title: this.$t('fu-wu-ming-cheng'), key: 'serviceName', minWidth: 160 },
        { title: this.$t('guan-lian-ying-she-shu'), key: 'pairCount', width: 120 },
        { title: this.$t('bei-zhu'), key: 'remark', minWidth: 200, ellipsis: true, tooltip: true },
        { title: this.$t('chuang-jian-shi-jian'), key: 'gmtCreate', width: 170 },
        { title: this.$t('cao-zuo'), slot: 'svcAction', width: 160, fixed: 'right' }
      ];
    }
  },
  data() {
    return {
      activeTab: 'pair',
      loading: false,
      searchKeyword: '',
      pageNum: 1,
      pageSize: 20,
      pairList: [],
      showPairList: [],
      pairTotal: 0,
      serviceList: [],
      showServiceList: [],
      serviceTotal: 0,
      serviceOptions: [],
      dsList: [],
      dsLoading: false,
      preDbCandidates: [],
      prodDbCandidates: [],
      pairFormVisible: false,
      pairFormMode: 'create',
      pairSubmitLoading: false,
      pairFormData: {
        id: null,
        preDsId: null,
        preDbName: '',
        prodDsId: null,
        prodDbName: '',
        serviceIds: [],
        status: 'ENABLED',
        remark: ''
      },
      pairFormRules: {
        prodDsId: [{ required: true, type: 'number', message: this.$t('qing-xuan-ze-sheng-chan-shu-ju-yuan'), trigger: 'change' }],
        prodDbName: [{ required: true, message: this.$t('qing-shu-ru-yu-xuan-ze-sheng-chan-ku-ming'), trigger: 'blur' }]
      },
      serviceFormVisible: false,
      serviceFormMode: 'create',
      serviceSubmitLoading: false,
      serviceFormData: {
        id: null,
        serviceCode: '',
        serviceName: '',
        remark: ''
      },
      serviceFormRules: {
        serviceCode: [{ required: true, message: this.$t('qing-shu-ru-fu-wu-bian-ma'), trigger: 'blur' }],
        serviceName: [{ required: true, message: this.$t('qing-shu-ru-fu-wu-ming-cheng'), trigger: 'blur' }]
      }
    };
  },
  methods: {
    handleTabChange(tab) {
      this.activeTab = tab;
      this.pageNum = 1;
      this.searchKeyword = '';
      if (tab === 'pair') {
        this.setPairTableData();
      } else {
        this.loadServiceList();
      }
    },
    handleEnterSearch(event) {
      if (event.key === 'Enter' || event.keyCode === 13) {
        this.handleSearch();
      }
    },
    handleSearch() {
      this.pageNum = 1;
      if (this.activeTab === 'pair') {
        this.setPairTableData();
      } else {
        this.setServiceTableData();
      }
    },
    handlePageChange(pageNum) {
      this.pageNum = pageNum;
      if (this.activeTab === 'pair') {
        this.setPairTableData();
      } else {
        this.setServiceTableData();
      }
    },
    handlePageSizeChange(pageSize) {
      this.pageSize = pageSize;
      this.handleSearch();
    },
    setPairTableData() {
      const keyword = (this.searchKeyword || '').trim().toLowerCase();
      let filtered = this.pairList;
      if (keyword) {
        filtered = this.pairList.filter((pair) => {
          const pre = `${pair.preDbName || ''}`.toLowerCase();
          const prod = `${pair.prodDbName || ''}`.toLowerCase();
          const remark = `${pair.remark || ''}`.toLowerCase();
          return pre.includes(keyword) || prod.includes(keyword) || remark.includes(keyword);
        });
      }
      this.pairTotal = filtered.length;
      this.showPairList = filtered.slice((this.pageNum - 1) * this.pageSize, this.pageNum * this.pageSize);
    },
    setServiceTableData() {
      const keyword = (this.searchKeyword || '').trim().toLowerCase();
      let filtered = this.serviceList;
      if (keyword) {
        filtered = this.serviceList.filter((svc) => {
          const code = `${svc.serviceCode || ''}`.toLowerCase();
          const name = `${svc.serviceName || ''}`.toLowerCase();
          return code.includes(keyword) || name.includes(keyword);
        });
      }
      this.serviceTotal = filtered.length;
      this.showServiceList = filtered.slice((this.pageNum - 1) * this.pageSize, this.pageNum * this.pageSize);
    },

    // ==================== Pair ====================

    handleOpenPairCreate() {
      this.pairFormMode = 'create';
      this.pairFormData = {
        id: null,
        preDsId: null,
        preDbName: '',
        prodDsId: null,
        prodDbName: '',
        serviceIds: [],
        status: 'ENABLED',
        remark: ''
      };
      this.preDbCandidates = [];
      this.prodDbCandidates = [];
      this.pairFormVisible = true;
    },
    handleOpenPairEdit(row) {
      this.pairFormMode = 'edit';
      this.pairFormData = {
        id: row.id,
        preDsId: row.preDsId,
        preDbName: row.preDbName || '',
        prodDsId: row.prodDsId,
        prodDbName: row.prodDbName,
        serviceIds: (row.services || []).map((s) => s.id),
        status: row.status,
        remark: row.remark || ''
      };
      this.preDbCandidates = [];
      this.prodDbCandidates = [];
      this.pairFormVisible = true;
    },
    async handlePairSubmit() {
      const valid = await this.$refs.pairForm.validate();
      if (!valid) {
        return;
      }
      this.pairSubmitLoading = true;
      if (this.pairFormMode === 'create') {
        const res = await this.$services.dbPairCreate({
          data: {
            preDsId: this.pairFormData.preDsId || null,
            preDbName: this.pairFormData.preDbName || null,
            prodDsId: this.pairFormData.prodDsId,
            prodDbName: this.pairFormData.prodDbName,
            serviceIds: this.pairFormData.serviceIds,
            remark: this.pairFormData.remark
          },
          msg: this.$t('cao-zuo-cheng-gong')
        });
        this.pairSubmitLoading = false;
        if (res.success) {
          this.pairFormVisible = false;
          await this.loadPairList();
        }
      } else {
        const res = await this.$services.dbPairUpdate({
          data: {
            id: this.pairFormData.id,
            status: this.pairFormData.status,
            serviceIds: this.pairFormData.serviceIds,
            remark: this.pairFormData.remark
          },
          msg: this.$t('bao-cun-cheng-gong')
        });
        this.pairSubmitLoading = false;
        if (res.success) {
          this.pairFormVisible = false;
          await this.loadPairList();
        }
      }
    },
    async handlePairDelete(row) {
      const res = await this.$services.dbPairDelete({
        data: { id: row.id },
        msg: this.$t('shan-chu-cheng-gong')
      });
      if (res.success) {
        await this.loadPairList();
      }
    },
    async loadPairList() {
      this.loading = true;
      const res = await this.$services.dbPairList({
        data: { keyword: this.searchKeyword }
      });
      this.loading = false;
      if (res.success && Array.isArray(res.data)) {
        this.pairList = res.data;
        this.setPairTableData();
      }
    },

    // ==================== Service ====================

    handleOpenServiceCreate() {
      this.serviceFormMode = 'create';
      this.serviceFormData = { id: null, serviceCode: '', serviceName: '', remark: '' };
      this.serviceFormVisible = true;
    },
    handleOpenServiceEdit(row) {
      this.serviceFormMode = 'edit';
      this.serviceFormData = {
        id: row.id,
        serviceCode: row.serviceCode,
        serviceName: row.serviceName,
        remark: row.remark || ''
      };
      this.serviceFormVisible = true;
    },
    async handleServiceSubmit() {
      const valid = await this.$refs.serviceForm.validate();
      if (!valid) {
        return;
      }
      this.serviceSubmitLoading = true;
      if (this.serviceFormMode === 'create') {
        const res = await this.$services.dbServiceCreate({
          data: {
            serviceCode: this.serviceFormData.serviceCode,
            serviceName: this.serviceFormData.serviceName,
            remark: this.serviceFormData.remark
          },
          msg: this.$t('cao-zuo-cheng-gong')
        });
        this.serviceSubmitLoading = false;
        if (res.success) {
          this.serviceFormVisible = false;
          await this.loadServiceList();
        }
      } else {
        const res = await this.$services.dbServiceUpdate({
          data: {
            id: this.serviceFormData.id,
            serviceName: this.serviceFormData.serviceName,
            remark: this.serviceFormData.remark
          },
          msg: this.$t('bao-cun-cheng-gong')
        });
        this.serviceSubmitLoading = false;
        if (res.success) {
          this.serviceFormVisible = false;
          await this.loadServiceList();
        }
      }
    },
    async handleServiceDelete(row) {
      const res = await this.$services.dbServiceDelete({
        data: { id: row.id },
        msg: this.$t('shan-chu-cheng-gong')
      });
      if (res.success) {
        await this.loadServiceList();
      }
    },
    async loadServiceList() {
      this.loading = true;
      const res = await this.$services.dbServiceList({
        data: { keyword: this.searchKeyword }
      });
      this.loading = false;
      if (res.success && Array.isArray(res.data)) {
        this.serviceList = res.data;
        this.setServiceTableData();
      }
    },

    // ==================== DS dropdown & DB name candidates ====================

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
    async loadServiceOptions() {
      const res = await this.$services.dbServiceList({ data: {} });
      if (res.success && Array.isArray(res.data)) {
        this.serviceOptions = res.data;
      }
    },
    async handlePreDsChange(dsId) {
      this.preDbCandidates = [];
      if (!dsId) {
        return;
      }
      const ds = this.dsList.find((d) => d.id === dsId);
      if (!ds) {
        return;
      }
      await this.loadDbCandidates(ds, 'pre');
    },
    async handleProdDsChange(dsId) {
      this.prodDbCandidates = [];
      if (!dsId) {
        return;
      }
      const ds = this.dsList.find((d) => d.id === dsId);
      if (!ds) {
        return;
      }
      await this.loadDbCandidates(ds, 'prod');
    },
    async loadDbCandidates(ds, side) {
      if (!ds.dsEnvId) {
        return;
      }
      try {
        const res = await this.$services.dmBrowseListLevels({
          data: { levels: [ds.dsEnvId, ds.id] }
        });
        if (res.success && Array.isArray(res.data)) {
          const names = res.data.map((item) => item.objName).filter((n) => n);
          if (side === 'pre') {
            this.preDbCandidates = names;
          } else {
            this.prodDbCandidates = names;
          }
        } else {
          this.$Message.warning(this.$t('ku-ming-hou-xuan-jia-zai-shi-bai-ke-shou-dong-shu-ru'));
        }
      } catch (e) {
        this.$Message.warning(this.$t('ku-ming-hou-xuan-jia-zai-shi-bai-ke-shou-dong-shu-ru'));
      }
    }
  },
  async mounted() {
    await this.loadPairList();
    if (this.dsList.length === 0) {
      this.loadDsList();
    }
    this.loadServiceOptions();
  }
};
</script>

<style lang="less" scoped>
.db-pair-page {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.text-muted {
  color: #999;
  font-size: 13px;
}

.db-name-text {
  font-weight: 500;
}
</style>
