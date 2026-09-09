<!--
  Resource permission tab for permission group detail.
  Tree lazy-loading and node structure adapted from views/system/subaccount/auth/authDm.vue:
  - @wsfe/vue-tree with Instance → CATALOG → SCHEMA → TABLE hierarchy
  - dmAuthListElementsOfLevel (non-leaf) / dmAuthListElementOfLeaf (leaf) services
  - levels array + parent chain for resPath construction
  Selection output maps to PermGroupResourceFO (groupId, authKind, resId, resPaths, authLabels, startTime, endTime).
-->
<template>
  <div class="perm-group-resource-tab">
    <div class="grant-section">
      <div class="grant-section__tree">
        <a-spin v-if="treeLoading" size="small" />
        <v-tree
          ref="resourceTree"
          keyField="key"
          checkable
          :cascade="false"
          :emptyText="$t('zan-wu-shu-ju')"
          :render="renderNode"
          :expandedKeys="expandedKeys"
          @expand="handleNodeExpand"
          @checked-change="handleCheckedChange"
        />
      </div>
      <div class="grant-section__settings">
        <Form :label-width="100">
          <FormItem :label="$t('quan-xian-biao-qian')">
            <Select v-model="grantData.authLabels" multiple filterable allow-create transfer :placeholder="$t('qing-shu-ru')"></Select>
          </FormItem>
          <FormItem :label="$t('shou-quan-shi-jian')">
            <div class="time-range-presets">
              <button
                v-for="range in timeRanges"
                :key="range.key"
                type="button"
                class="time-preset-btn"
                :class="{ 'is-active': curRangeKey === range.key }"
                @click="handleRangeChange(range.key)"
              >
                {{ range.label }}
              </button>
            </div>
            <div v-if="showCustomTime" class="custom-time">
              <a-date-picker v-model:value="grantData.startTime" show-time format="YYYY-MM-DD HH:mm:ss" :placeholder="$t('kai-shi-shi-jian')" />
              <span class="time-separator">~</span>
              <a-date-picker v-model:value="grantData.endTime" show-time format="YYYY-MM-DD HH:mm:ss" :placeholder="$t('jie-shu-shi-jian')" />
            </div>
          </FormItem>
        </Form>
        <div class="grant-section__submit">
          <Button type="primary" :loading="grantLoading" :disabled="checkedNodes.length === 0" @click="handleGrant">
            {{ $t('shou-quan-zi-yuan') }}
          </Button>
          <span v-if="checkedNodes.length" class="checked-count">{{ $t('yi-xuan-ze-n-ge', [checkedNodes.length]) }}</span>
        </div>
      </div>
    </div>

    <div class="table-container">
      <Table :columns="resourceColumns" :data="resourceList" size="small" :loading="resourceLoading" border stripe>
        <template #authKind="{ row }">{{ row.authKind }}</template>
        <template #resId="{ row }">{{ dsNameMap[row.resId] || row.resId }}</template>
        <template #authLabels="{ row }">
          <div class="auth-labels">
            <Tag v-for="label in row.authLabels || []" :key="label" size="small">{{ label }}</Tag>
          </div>
        </template>
        <template #timeRange="{ row }">
          <span v-if="!row.startTime && !row.endTime">{{ $t('yong-jiu') }}</span>
          <span v-else>{{ formatDate(row.startTime) }} ~ {{ formatDate(row.endTime) }}</span>
        </template>
        <template #action="{ row }">
          <Poptip
            v-if="myAuth.includes('RDP_PERM_GROUP_MANAGE')"
            confirm
            transfer
            :cancel-text="$t('qu-xiao')"
            :ok-text="$t('que-ding')"
            :title="$t('que-ding-hui-shou-gai-zi-yuan-ma')"
            @on-ok="handleRevoke(row)"
          >
            <Button type="text" size="small">{{ $t('hui-shou-zi-yuan') }}</Button>
          </Poptip>
        </template>
      </Table>
    </div>
  </div>
</template>

<script>
import { h } from 'vue';
import { mapState } from 'vuex';
import VTree from '@wsfe/vue-tree';
import dayjs from 'dayjs';

const START_RECORD_NAMES_CONUT = 2;

const ELEMENT_TYPE_MAP = {
  Instance: 'INSTANCE',
  INSTANCE: 'INSTANCE',
  Catalog: 'CATALOG',
  CATALOG: 'CATALOG',
  EXTERNAL_CATALOG: 'CATALOG',
  Schema: 'SCHEMA',
  SCHEMA: 'SCHEMA',
  EXTERNAL_SCHEMA: 'SCHEMA',
  Table: 'TABLE',
  TABLE: 'TABLE'
};

const ELEMENT_REVERSE_TYPE_MAP = {
  INSTANCE: 'Instance',
  CATALOG: 'Catalog',
  SCHEMA: 'Schema',
  TABLE: 'Table'
};

export default {
  name: 'PermGroupResourceTab',
  components: { VTree },
  props: {
    groupId: { type: Number, required: true }
  },
  computed: {
    ...mapState(['myAuth']),
    resourceColumns() {
      return [
        { title: this.$t('shou-quan-lei-xing'), slot: 'authKind', width: 120 },
        { title: this.$t('shu-ju-yuan-shi-li'), slot: 'resId', minWidth: 160 },
        { title: this.$t('zi-yuan-lu-jing'), key: 'resPath', minWidth: 200, ellipsis: true, tooltip: true },
        { title: this.$t('quan-xian-biao-qian'), slot: 'authLabels', minWidth: 180 },
        { title: this.$t('shou-quan-shi-jian'), slot: 'timeRange', minWidth: 200 },
        { title: this.$t('chuang-jian-shi-jian'), key: 'gmtCreate', width: 170 },
        { title: this.$t('cao-zuo'), slot: 'action', width: 130, fixed: 'right' }
      ];
    },
    timeRanges() {
      return [
        { key: 'permanent', label: this.$t('yong-jiu') },
        { key: '1d', label: this.$t('yi-tian') },
        { key: '1w', label: this.$t('yi-zhou') },
        { key: '1m', label: this.$t('yi-ge-yue') },
        { key: '1y', label: this.$t('yi-nian') },
        { key: 'custom', label: this.$t('zi-ding-yi') }
      ];
    },
    showCustomTime() {
      return this.curRangeKey === 'custom';
    }
  },
  data() {
    return {
      resourceLoading: false,
      resourceList: [],
      dsList: [],
      dsNameMap: {},
      treeLoading: false,
      originTree: [],
      expandedKeys: [],
      checkedNodes: [],
      grantLoading: false,
      curRangeKey: 'permanent',
      grantData: {
        authLabels: [],
        startTime: null,
        endTime: null
      }
    };
  },
  watch: {
    groupId() {
      this.getResourceList();
      this.loadDsList();
      this.loadTree();
    }
  },
  methods: {
    formatDate(date) {
      if (!date) return '-';
      return dayjs(date).format('YYYY-MM-DD HH:mm:ss');
    },
    genUniqueId() {
      return `${Date.now()}-${Math.floor(Math.random() * 100000000)}`;
    },
    isLeafNode(node) {
      return node?.levels?.length > START_RECORD_NAMES_CONUT && node?.objType !== 'CATALOG' && node?.objType !== 'EXTERNAL_CATALOG';
    },
    getParentObjIds(currentNode) {
      const parentObjIds = [];
      let current = currentNode;
      while (current && current.parent) {
        parentObjIds.push(current.parent.objId);
        current = current.parent;
      }
      return parentObjIds.reverse();
    },
    getResPathByIdAndName(node) {
      if (!node) return [];
      let resPath = node?.levels;
      if (node?.levels?.length > START_RECORD_NAMES_CONUT) {
        resPath = node.levels.slice(0, 2).concat(node.objName);
        const parentNames = [];
        let curNode = node.parent;
        while (curNode && curNode.levels?.length > START_RECORD_NAMES_CONUT) {
          parentNames.push(curNode.objName);
          curNode = curNode.parent;
        }
        resPath = resPath.concat(parentNames);
      }
      if (resPath.length === 4) {
        [resPath[2], resPath[3]] = [resPath[3], resPath[2]];
      }
      return resPath;
    },
    getLeafType(node) {
      const nodeType = ELEMENT_TYPE_MAP[node?.objType] || node?.objType;
      if (nodeType === 'SCHEMA') {
        return ELEMENT_REVERSE_TYPE_MAP.TABLE || 'Table';
      }
      if (nodeType === 'CATALOG') {
        return ELEMENT_REVERSE_TYPE_MAP.SCHEMA || 'Schema';
      }
      return '';
    },
    renderNode(node) {
      return h('span', { class: 'tree-node-label' }, node.objName || '');
    },
    findInstanceAncestor(node) {
      let current = node;
      while (current) {
        if (current.objType === 'Instance' || current.objType === 'INSTANCE') {
          return current;
        }
        current = current.parent;
      }
      return null;
    },
    buildResPathString(node) {
      const instance = this.findInstanceAncestor(node);
      if (!instance) {
        return '';
      }
      if (node === instance) {
        return '';
      }
      const names = [];
      let current = node;
      while (current && current !== instance) {
        names.unshift(current.objName);
        current = current.parent;
      }
      return names.join('/');
    },
    handleNodeExpand(node) {
      if (!node) {
        return;
      }
      const idx = this.expandedKeys.indexOf(node.key);
      if (idx === -1) {
        this.expandedKeys.push(node.key);
      }
      if (node.loaded || this.isResourceLeaf(node)) {
        return;
      }
      this.loadChildren(node);
    },
    isResourceLeaf(node) {
      return !!node?.isLeaf || ELEMENT_TYPE_MAP[node?.objType] === 'TABLE';
    },
    async loadTree() {
      this.treeLoading = true;
      try {
        const res = await this.$services.dmAuthListElementsOfLevel({
          data: { authKind: 'DataSource', resPaths: [] }
        });
        if (res.success && Array.isArray(res.data)) {
          this.originTree = res.data.map((item) => {
            item.children = [{}];
            item.loaded = false;
            item.levels = [item.objId];
            item.key = this.genUniqueId();
            item.parent = null;
            item.levels = [item.objId];
            item.isLeaf = ELEMENT_TYPE_MAP[item.objType] === 'TABLE';
            return item;
          });
          this.$refs.resourceTree?.setData(this.originTree);
        }
      } finally {
        this.treeLoading = false;
      }
    },
    async loadChildren(node) {
      const resPaths = this.getResPathByIdAndName(node);
      let res = { data: [] };
      if (this.isLeafNode(node)) {
        const leafType = this.getLeafType(node);
        if (!leafType) {
          node.loaded = true;
          delete node.children;
          return;
        }
        res = await this.$services.dmAuthListElementOfLeaf({
          data: { levels: resPaths, leafType }
        });
      } else {
        res = await this.$services.dmAuthListElementsOfLevel({
          data: { authKind: 'DataSource', resPaths }
        });
      }
      if (!res.success || !res.data?.length) {
        node.loaded = true;
        delete node.children;
        node.isLeaf = true;
        this.refreshTree();
        return;
      }
      const children = res.data.map((item) => {
        item.children = [{}];
        item.loaded = false;
        item.key = this.genUniqueId();
        item.parent = node;
        const parentObjIds = this.getParentObjIds(item);
        item.levels = [...parentObjIds, item.objId];
        item.isLeaf = ELEMENT_TYPE_MAP[item.objType] === 'TABLE';
        return item;
      });
      node.children = children;
      node.loaded = true;
      this.refreshTree();
    },
    refreshTree() {
      this.$refs.resourceTree?.setData(this.originTree);
    },
    handleCheckedChange(checkedNodes) {
      this.checkedNodes = checkedNodes || [];
    },
    handleRangeChange(key) {
      this.curRangeKey = key;
      if (key === 'permanent') {
        this.grantData.startTime = null;
        this.grantData.endTime = null;
      } else if (key !== 'custom') {
        const now = dayjs();
        this.grantData.startTime = now.toDate();
        const durationMap = { '1d': 1, '1w': 7, '1m': 30, '1y': 365 };
        this.grantData.endTime = now.add(durationMap[key], 'day').toDate();
      }
    },
    async handleGrant() {
      if (this.checkedNodes.length === 0) {
        this.$Message.warning(this.$t('qing-gou-xuan-zi-yuan'));
        return;
      }
      const groups = {};
      this.checkedNodes.forEach((node) => {
        const instance = this.findInstanceAncestor(node);
        if (!instance) {
          return;
        }
        const resId = instance.objId;
        if (!groups[resId]) {
          groups[resId] = [];
        }
        const path = this.buildResPathString(node);
        groups[resId].push(path);
      });
      const resIds = Object.keys(groups);
      if (resIds.length === 0) {
        this.$Message.warning(this.$t('qing-gou-xuan-zi-yuan'));
        return;
      }
      const timePayload = {};
      if (this.grantData.startTime) {
        timePayload.startTime = dayjs(this.grantData.startTime).format('YYYY-MM-DD HH:mm:ss');
      }
      if (this.grantData.endTime) {
        timePayload.endTime = dayjs(this.grantData.endTime).format('YYYY-MM-DD HH:mm:ss');
      }
      this.grantLoading = true;
      let allSuccess = true;
      for (const resId of resIds) {
        const payload = {
          groupId: this.groupId,
          authKind: 'DataSource',
          resId: Number(resId),
          resPaths: groups[resId],
          authLabels: this.grantData.authLabels || [],
          ...timePayload
        };
        const res = await this.$services.permGroupResourceGrant({
          data: payload,
          modal: false
        });
        if (!res.success) {
          allSuccess = false;
        }
      }
      this.grantLoading = false;
      if (allSuccess) {
        this.$Message.success(this.$t('shou-quan-cheng-gong'));
        this.checkedNodes = [];
        this.$refs.resourceTree?.setData(this.originTree);
        await this.getResourceList();
      }
    },
    async handleRevoke(row) {
      const res = await this.$services.permGroupResourceRevoke({
        data: { groupId: this.groupId, groupResourceIds: [row.id] },
        msg: this.$t('cao-zuo-cheng-gong')
      });
      if (res.success) {
        await this.getResourceList();
      }
    },
    async getResourceList() {
      this.resourceLoading = true;
      const res = await this.$services.permGroupResourceList({ data: { groupId: this.groupId } });
      this.resourceLoading = false;
      if (res.success) {
        this.resourceList = res.data || [];
      }
    },
    async loadDsList() {
      const res = await this.$services.dmDataSourceListByCondition({ data: { useVisibility: true } });
      if (res.success && Array.isArray(res.data)) {
        this.dsList = res.data;
        const map = {};
        res.data.forEach((ds) => {
          map[ds.objId] = ds.objName;
        });
        this.dsNameMap = map;
      }
    }
  },
  mounted() {
    this.getResourceList();
    this.loadDsList();
    this.loadTree();
  }
};
</script>

<style lang="less" scoped>
.perm-group-resource-tab {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.grant-section {
  display: flex;
  gap: 16px;

  &__tree {
    flex: 1;
    min-width: 0;
    max-height: 400px;
    overflow-y: auto;
    border: 1px solid #eee;
    border-radius: 6px;
    padding: 8px;
  }

  &__settings {
    flex: 0 0 320px;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
  }

  &__submit {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-top: 8px;
  }

  @media (max-width: 1024px) {
    flex-direction: column;

    &__settings {
      flex: 1;
      width: 100%;
    }
  }
}

.checked-count {
  font-size: 13px;
  color: #41454d;
}

.table-container {
  overflow-x: auto;
}

.auth-labels {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.time-range-presets {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.time-preset-btn {
  padding: 4px 12px;
  border: 1px solid #ddd;
  border-radius: 6px;
  background: #fff;
  cursor: pointer;
  font-size: 13px;
  transition: all 0.15s ease;

  &:hover {
    border-color: #181d26;
  }

  &.is-active {
    background: #181d26;
    border-color: #181d26;
    color: #fff;
  }
}

.custom-time {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.time-separator {
  color: #999;
}

.tree-node-label {
  font-size: 14px;
}
</style>
