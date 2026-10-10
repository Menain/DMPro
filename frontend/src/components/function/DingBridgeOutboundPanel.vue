<template>
  <div class="ding-bridge-outbound">
    <div class="ding-bridge-outbound__row">
      <span class="ding-bridge-outbound__label">{{ $t('ding-qiao-chu-zhan-zhuang-tai') }}</span>
      <Tag :color="statusTag">{{ statusI18nKey ? $t(statusI18nKey) : normalizedStatus }}</Tag>
      <Button v-if="canReissue" size="small" :loading="reissuing" :disabled="!reissueAllowed" @click="$emit('reissue')">
        {{ $t('ding-qiao-chong-xin-fa-qi') }}
      </Button>
    </div>
    <div v-if="flowId" class="ding-bridge-outbound__row">
      <span class="ding-bridge-outbound__label">{{ $t('ding-qiao-liu-cheng-hao') }}</span>
      <span class="ding-bridge-outbound__mono">{{ flowId }}</span>
    </div>
    <div v-if="outboundError" class="ding-bridge-outbound__row ding-bridge-outbound__row--error">
      <span class="ding-bridge-outbound__label">{{ $t('ding-qiao-shi-bai-yuan-yin') }}</span>
      <span class="ding-bridge-outbound__error">{{ outboundError }}</span>
    </div>
  </div>
</template>

<script>
// 钉钉审批桥接出站状态面板(task 10-09,design §3.1/§4)——工单详情与发布单详情共用:
// 状态词汇表/守卫镜像在 @/const/dingBridge;数据源 = queryApprovalBaseInfo 的 additive 字段
// (outboundStatus/outboundError)+ approIdentity(flowId),随各详情页既有刷新链带出,零新轮询器。
import { BRIDGE_OUTBOUND_STATUS_I18N, BRIDGE_OUTBOUND_STATUS_TAG, bridgeOutboundStatusOf, canReissueBridgeFlow } from '@/const/dingBridge';

export default {
  name: 'DingBridgeOutboundPanel',
  props: {
    // dm_approval.outbound_status wire value; NULL/空 = NOT_STARTED
    outboundStatus: {
      type: String,
      default: null
    },
    // 最近一次失败原因(后端已截断),仅 FAILED 时非空
    outboundError: {
      type: String,
      default: ''
    },
    // approIdentity = 钉钉流程号(出站回填与入站命令双源先到先得)
    flowId: {
      type: String,
      default: ''
    },
    reissuing: Boolean,
    // 权限位由宿主页面判定(端点 = HIGH + RDP_WORKER_ORDER_REQUEST/APPROVE 任一,镜像后端注解)
    canReissue: Boolean
  },
  emits: ['reissue'],
  computed: {
    normalizedStatus() {
      return bridgeOutboundStatusOf(this.outboundStatus);
    },
    statusTag() {
      return BRIDGE_OUTBOUND_STATUS_TAG[this.normalizedStatus] || 'default';
    },
    statusI18nKey() {
      return BRIDGE_OUTBOUND_STATUS_I18N[this.normalizedStatus] || null;
    },
    reissueAllowed() {
      return canReissueBridgeFlow(this.outboundStatus);
    }
  }
};
</script>

<style lang="less" scoped>
.ding-bridge-outbound {
  display: flex;
  flex-direction: column;
  gap: 8px;

  &__row {
    display: flex;
    align-items: center;
    gap: 12px;
    font-size: 14px;

    &--error {
      align-items: flex-start;
    }
  }

  &__label {
    flex: none;
    color: var(--text-tertiary);
    font-size: 13px;
  }

  &__mono {
    color: var(--text-primary);
    font-family: Menlo, Monaco, 'Courier New', monospace;
    word-break: break-all;
  }

  &__error {
    color: #ed4014;
    font-size: 13px;
    line-height: 1.5;
    white-space: pre-wrap;
    word-break: break-all;
  }
}
</style>
