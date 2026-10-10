<template>
  <a-table
    class="dm-table"
    :class="{ 'dm-table--stripe': stripe }"
    :columns="antdColumns"
    :data-source="dataSource"
    :loading="loading"
    :size="tableSize"
    :bordered="border"
    :pagination="false"
    :scroll="mergedScroll"
    :row-key="rowKey"
    :row-selection="rowSelection"
    :locale="tableLocale"
    @resize-column="handleResizeColumn"
  >
    <template #bodyCell="{ column, record, index }">
      <DmTableRenderCell
        v-if="column.__legacyRender"
        :render-cell="column.__legacyRender"
        :row="record"
        :column="column.__legacyColumn"
        :index="index"
      />
      <slot v-else-if="column.__slot" :name="column.__slot" :row="record" :index="index" />
      <DmTableOverflowCell
        v-else-if="column.__tooltip && column.dataIndex !== undefined && column.dataIndex !== null"
        :content="record[column.dataIndex]"
        :max-width="column.__tooltipMaxWidth"
      />
      <template v-else-if="column.dataIndex !== undefined && column.dataIndex !== null">
        {{ record[column.dataIndex] }}
      </template>
    </template>
    <template v-if="$slots.empty" #emptyText>
      <slot name="empty" />
    </template>
  </a-table>
</template>

<script>
import { h, resolveComponent } from 'vue';
import { convertTableColumns } from '@/utils/convertTableColumns';

const DmTableOverflowCell = {
  name: 'DmTableOverflowCell',
  props: {
    content: {
      type: [String, Number],
      default: ''
    },
    maxWidth: {
      type: Number,
      default: 250
    }
  },
  data() {
    return {
      tooltipDisabled: true
    };
  },
  methods: {
    updateOverflow() {
      const content = this.$refs.content;
      if (!content) {
        return;
      }
      this.tooltipDisabled = content.scrollWidth <= content.clientWidth;
    }
  },
  mounted() {
    this.$nextTick(this.updateOverflow);
  },
  updated() {
    this.$nextTick(this.updateOverflow);
  },
  render() {
    const Poptip = resolveComponent('Poptip');
    return h(
      Poptip,
      {
        transfer: true,
        trigger: 'hover',
        placement: 'top',
        content: String(this.content ?? ''),
        disabled: this.tooltipDisabled,
        width: this.maxWidth,
        wordWrap: true,
        padding: '8px 12px',
        class: 'dm-table-cell-tooltip'
      },
      {
        default: () =>
          h(
            'span',
            {
              ref: 'content',
              class: 'dm-table-cell-tooltip-content',
              style: {
                display: 'block',
                width: '100%',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap'
              },
              onMouseenter: this.updateOverflow
            },
            this.content
          ),
        content: () =>
          h(
            'div',
            {
              style: {
                maxHeight: '240px',
                overflow: 'auto',
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-all',
                cursor: 'text',
                userSelect: 'text'
              }
            },
            this.content
          )
      }
    );
  }
};

const DmTableRenderCell = {
  name: 'DmTableRenderCell',
  props: {
    renderCell: {
      type: Function,
      required: true
    },
    row: {
      type: Object,
      required: true
    },
    column: {
      type: Object,
      required: true
    },
    index: {
      type: Number,
      required: true
    }
  },
  render() {
    return this.renderCell(h, {
      row: this.row,
      column: this.column,
      index: this.index
    });
  }
};

export default {
  name: 'DmTable',
  components: { DmTableRenderCell, DmTableOverflowCell },
  props: {
    columns: {
      type: Array,
      default: () => []
    },
    data: {
      type: Array,
      default: () => []
    },
    loading: {
      type: Boolean,
      default: false
    },
    size: {
      type: String,
      default: 'small'
    },
    border: {
      type: Boolean,
      default: false
    },
    stripe: {
      type: Boolean,
      default: false
    },
    locale: {
      type: Object,
      default: () => ({})
    },
    scroll: {
      type: Object,
      default: undefined
    },
    rowKey: {
      type: [String, Function],
      default: (_, index) => index
    },
    rowSelection: {
      type: Object,
      default: undefined
    }
  },
  data() {
    return {
      // 用户拖拽后的列宽:{ [列 key]: px }。仅内存态,不回写页面 columns、不持久化(刷新/切页回默认宽)
      widthOverrides: {},
      // 双击刚恢复默认宽的列;用于吞掉 antd DragHandle 在 mouseup 后经 rAF 补发的一次 resizeColumn(旧宽),避免覆盖恢复结果
      pendingWidthReset: {}
    };
  },
  computed: {
    convertedColumns() {
      return convertTableColumns(this.columns).map((col) => {
        if (!col.resizable) {
          return col;
        }
        const columnKey = col.key;
        return {
          ...col,
          // 双击拖拽把手恢复默认宽度:经 customHeaderCell 注入 th 级监听,闭包携带列 key,无需 th 序号对位
          customHeaderCell: () => ({
            onDblclick: (event) => this.handleResizeHandleDblclick(event, columnKey)
          })
        };
      });
    },
    antdColumns() {
      if (!Object.keys(this.widthOverrides).length) {
        return this.convertedColumns;
      }
      return this.convertedColumns.map((col) => {
        const width = this.widthOverrides[col.key];
        if (width === undefined || width === col.width) {
          return col;
        }
        return { ...col, width };
      });
    },
    mergedScroll() {
      if (!this.scroll || typeof this.scroll.x !== 'number') {
        return this.scroll;
      }
      // 拖宽/拖窄后把宽度增量累加到 scroll.x,保持 fixed 列 sticky 偏移与横向滚动范围一致
      let delta = 0;
      for (const col of this.convertedColumns) {
        const width = this.widthOverrides[col.key];
        if (width !== undefined) {
          delta += width - col.width;
        }
      }
      if (!delta) {
        return this.scroll;
      }
      return { ...this.scroll, x: this.scroll.x + delta };
    },
    dataSource() {
      return this.data;
    },
    tableSize() {
      if (this.size === 'large') {
        return 'middle';
      }
      return this.size === 'small' ? 'small' : 'middle';
    },
    tableLocale() {
      return {
        emptyText: this.locale?.emptyText || this.$t('zan-wu-shu-ju')
      };
    }
  },
  methods: {
    handleResizeColumn(width, originColumn) {
      const key = originColumn.key;
      if (this.pendingWidthReset[key]) {
        delete this.pendingWidthReset[key];
        return;
      }
      this.widthOverrides[key] = width;
    },
    handleResizeHandleDblclick(event, key) {
      if (!event.target.closest('.ant-table-resize-handle')) {
        return;
      }
      delete this.widthOverrides[key];
      this.pendingWidthReset[key] = true;
    }
  }
};
</script>

<style scoped>
.dm-table-cell-tooltip {
  display: block;
  width: 100%;
}

.dm-table-cell-tooltip :deep(.ivu-poptip-rel) {
  display: block;
}
</style>
