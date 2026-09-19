<template>
  <!-- Page-root fixed layer instead of per-row Poptip/Teleport: transferred poppers inside Table slots
       corrupt view-ui-plus keyed row patching (el-null vnodes → parentNode/__vnode TypeErrors on route
       leave). One instance per page, outside every row vnode tree. -->
  <div v-if="visible" class="sql-hover-popover" :style="posStyle" @mouseenter="cancelHide" @mouseleave="hide">
    <pre class="sql-hover-popover__pre">{{ text }}</pre>
    <Button size="small" type="primary" @click="copyText(text)">{{ $t('fu-zhi') }}</Button>
  </div>
</template>

<script>
import copyMixin from '@/mixins/copyMixin';

const HIDE_DELAY_MS = 200;
const POPOVER_WIDTH = 500;
const POPOVER_MAX_HEIGHT = 320;

export default {
  name: 'SqlHoverPopover',
  mixins: [copyMixin],
  data() {
    return {
      visible: false,
      text: '',
      top: 0,
      left: 0,
      hideTimer: null
    };
  },
  computed: {
    posStyle() {
      return { top: `${this.top}px`, left: `${this.left}px` };
    }
  },
  beforeUnmount() {
    this.clearTimer();
  },
  methods: {
    open(text, anchorRect) {
      this.clearTimer();
      this.text = text;
      const below = anchorRect.bottom + 4;
      this.top = below + POPOVER_MAX_HEIGHT > window.innerHeight ? Math.max(8, anchorRect.top - POPOVER_MAX_HEIGHT - 4) : below;
      this.left = Math.max(8, Math.min(anchorRect.left, window.innerWidth - POPOVER_WIDTH - 8));
      this.visible = true;
    },
    closeSoon() {
      this.clearTimer();
      this.hideTimer = window.setTimeout(() => {
        this.visible = false;
      }, HIDE_DELAY_MS);
    },
    cancelHide() {
      this.clearTimer();
    },
    hide() {
      this.clearTimer();
      this.visible = false;
    },
    clearTimer() {
      if (this.hideTimer) {
        window.clearTimeout(this.hideTimer);
        this.hideTimer = null;
      }
    }
  }
};
</script>

<style lang="less" scoped>
.sql-hover-popover {
  position: fixed;
  z-index: 900;
  width: 500px;
  max-height: 320px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: var(--bg-secondary, #f8fafc);
  border: 1px solid var(--border-color, #dddddd);
  border-radius: 6px;
  padding: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
}

.sql-hover-popover__pre {
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  max-height: 260px;
  overflow: auto;
  margin: 0;
}
</style>
