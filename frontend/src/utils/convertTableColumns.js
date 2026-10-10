export function convertTableColumns(columns = []) {
  return columns.map((col, index) => {
    const key = col.key || col.dataIndex || col.slot || `col-${index}`;
    const width = col.width || col.minWidth;
    // 列宽拖拽(antd DragHandle)要求数值 width;有数值宽度的列默认开启,页面显式 resizable:false 可关
    let resizable = col.resizable;
    if (resizable === undefined) {
      resizable = typeof width === 'number';
    }
    return {
      title: col.title,
      dataIndex: col.key || col.dataIndex,
      key,
      width,
      align: col.align,
      fixed: col.fixed,
      ellipsis: col.ellipsis,
      sorter: col.sorter,
      sortDirections: col.sortDirections,
      defaultSortOrder: col.defaultSortOrder,
      resizable,
      minWidth: col.minWidth,
      maxWidth: col.maxWidth,
      __slot: col.slot,
      __legacyRender: col.render,
      __legacyColumn: col,
      __tooltip: col.tooltip,
      __tooltipMaxWidth: col.tooltipMaxWidth
    };
  });
}
