<!--
  Operation-permission tree panel for permission group resource authorization.
  Ported from views/system/subaccount/auth/authDm.vue middle column patterns:
  - rdpAuthFetchAuthTreeDef payload { kind, dsType, elementType }
  - availability cache keyed by dsType|elementType (hasAuthTreeDefForElementType pattern)
  - dsType resolved via parent chain (getNodeDataSourceType pattern)
  - v-tree with titleField="i18nName" + custom render
  No cross-node state: parent map owns per-node label selection memory.
-->
<template>
  <div class="auth-label-tree-panel">
    <div v-if="loading" class="auth-label-tree-panel__state">
      <a-spin size="small" />
    </div>
    <div v-else-if="!node" class="auth-label-tree-panel__state">
      <span class="auth-label-tree-panel__hint">{{ $t('qing-dian-ji-zi-yuan-jie-dian-pei-zhi-cao-zuo-quan-xian') }}</span>
    </div>
    <div v-else-if="!hasDefinition" class="auth-label-tree-panel__state">
      <span class="auth-label-tree-panel__hint">{{ $t('zan-wu-cao-zuo-quan-xian-ding-yi') }}</span>
    </div>
    <div v-else class="auth-label-tree-panel__tree">
      <v-tree
        ref="authTree"
        keyField="key"
        checkable
        ignoreMode="parents"
        :emptyText="$t('zan-wu-shu-ju')"
        titleField="i18nName"
        :render="renderNode"
        :defaultExpandAll="true"
        @checked-change="handleCheckedChange"
      />
    </div>
  </div>
</template>

<script>
import { h } from 'vue';
import VTree from '@wsfe/vue-tree';

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
  name: 'AuthLabelTreePanel',
  components: { VTree },
  props: {
    node: { type: Object, default: null },
    modelValue: { type: Array, default: () => [] }
  },
  emits: ['update:modelValue'],
  data() {
    return {
      loading: false,
      hasDefinition: false,
      availabilityCache: {}
    };
  },
  watch: {
    node: {
      handler(node, oldNode) {
        if (node && oldNode && node.key === oldNode.key) {
          return;
        }
        this.loadAuthTreeDef(node);
      },
      immediate: true
    }
  },
  methods: {
    normalizeElementType(objType) {
      const normalized = objType === 'EXTERNAL_SCHEMA' ? 'SCHEMA' : objType === 'EXTERNAL_CATALOG' ? 'CATALOG' : objType;
      return ELEMENT_TYPE_MAP[normalized] || normalized;
    },
    getNodeDataSourceType(node) {
      let current = node;
      while (current) {
        if (current.objAttr?.dsType) {
          return current.objAttr.dsType;
        }
        current = current.parent || current._parent;
      }
      return '';
    },
    getCacheKey(dsType, elementType) {
      return [dsType || '', elementType || ''].join('|');
    },
    async fetchAuthTreeDef(dsType, elementType) {
      const cacheKey = this.getCacheKey(dsType, elementType);
      const cached = this.availabilityCache[cacheKey];
      if (cached !== undefined) {
        if (cached instanceof Promise) {
          return cached;
        }
        return cached;
      }
      const request = this.$services
        .rdpAuthFetchAuthTreeDef({
          data: { kind: 'DataSource', dsType, elementType }
        })
        .then((res) => {
          const data = Array.isArray(res?.data) ? res.data : [];
          this.availabilityCache[cacheKey] = data;
          return data;
        })
        .catch(() => {
          this.availabilityCache[cacheKey] = [];
          return [];
        });
      this.availabilityCache[cacheKey] = request;
      return request;
    },
    async loadAuthTreeDef(node) {
      if (!node) {
        this.hasDefinition = false;
        return;
      }
      const normalizedElementType = this.normalizeElementType(node.objType);
      if (!normalizedElementType) {
        this.hasDefinition = false;
        return;
      }
      const dsType = this.getNodeDataSourceType(node);
      const elementType = ELEMENT_REVERSE_TYPE_MAP[normalizedElementType] || normalizedElementType;
      const cacheKey = this.getCacheKey(dsType, elementType);

      const cached = this.availabilityCache[cacheKey];
      const isResolved = cached !== undefined && !(cached instanceof Promise);
      if (!isResolved) {
        this.loading = true;
      }

      try {
        const treeData = await this.fetchAuthTreeDef(dsType, elementType);
        this.loading = false;
        if (treeData && treeData.length > 0) {
          this.hasDefinition = true;
          await this.$nextTick();
          const markedData = this.markChecked(treeData, this.modelValue || []);
          this.$refs.authTree?.setData(markedData);
        } else {
          this.hasDefinition = false;
        }
      } catch {
        this.loading = false;
        this.hasDefinition = false;
      }
    },
    handleCheckedChange(checkedNodes, checkedKeys) {
      this.$emit('update:modelValue', checkedKeys || []);
    },
    markChecked(nodes, checkedKeys) {
      const keySet = new Set(checkedKeys);
      const traverse = (items) =>
        items.map((node) => {
          const newNode = { ...node };
          newNode.checked = keySet.has(node.key);
          if (Array.isArray(node.children) && node.children.length > 0) {
            newNode.children = traverse(node.children);
          }
          return newNode;
        });
      return traverse(nodes);
    },
    renderNode(node) {
      return h('span', { class: 'auth-label-node' }, node.i18nName || '-');
    }
  }
};
</script>

<style lang="less" scoped>
.auth-label-tree-panel {
  height: 100%;
  display: flex;
  flex-direction: column;

  &__state {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  &__hint {
    font-size: 13px;
    color: #999;
  }

  &__tree {
    flex: 1;
    overflow-y: auto;
    min-height: 0;
  }
}

.auth-label-node {
  font-size: 14px;
}
</style>
