<template>
  <!-- Toast 消息容器 / Toast message container -->
  <transition name="toast">
    <div v-if="message" class="proto-toast">
      <span class="toast-text">{{ message }}</span>
      <button v-if="actionText" class="toast-btn" @click="handleAction">
        {{ actionText }}
      </button>
    </div>
  </transition>
</template>

<script setup>
/**
 * Toast 全局宿主组件。挂载在应用根节点，通过 useToast composable 接收并展示全局提示消息。
 * 支持可选快捷操作按钮（如快速插播「设为下一首」）。
 *
 * Global toast host component. Mounted at the app root, it receives and displays
 * global toast messages via the useToast composable, with optional quick action buttons.
 */
import { useToast } from '../composables/useToast'

// 从 composable 中解构出响应式引用 / Destructure reactive refs from composable
const { message, actionText, onAction, dismiss } = useToast()

/**
 * 点击 Toast 行动项按钮
 * Click handler for toast action button
 */
function handleAction() {
  const fn = onAction.value
  dismiss()
  if (typeof fn === 'function') {
    fn()
  }
}
</script>

<style scoped>
.proto-toast {
  position: fixed; left: 50%; bottom: 90px; transform: translateX(-50%);
  background: rgba(18,22,30,.95); backdrop-filter: blur(18px);
  border: 1px solid rgba(255,198,75,.3); color: #f0f2f7;
  padding: 10px 16px; border-radius: 999px; font-size: 13px; z-index: 999; white-space: nowrap;
  box-shadow: 0 14px 45px rgba(0,0,0,.6), 0 0 20px rgba(255,198,75,.15);
  display: flex; align-items: center; gap: 10px;
}
.toast-text {
  line-height: 1.4;
}
.toast-btn {
  background: var(--gold); color: #1a1400; font-weight: 700; font-size: 11px;
  padding: 4px 10px; border-radius: 999px; flex: none; transition: transform .15s ease;
}
.toast-btn:active {
  transform: scale(.92);
}
.toast-enter-active, .toast-leave-active { transition: opacity .25s; }
.toast-enter-from, .toast-leave-to { opacity: 0; }
</style>
