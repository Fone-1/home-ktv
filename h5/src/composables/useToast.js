/**
 * 全局轻量 toast 提示 composable。
 * 用于点歌反馈等场景，例如「已加入队列，前面还有 N 首」，并支持携带快捷行动按钮（如「设为下一首」插播）。
 *
 * Global lightweight toast composable.
 * Used for song-queue feedback such as "Added to queue, N songs ahead",
 * with optional action buttons like "Set as next song" (priority queueing).
 *
 * @module useToast
 */
import { reactive, toRefs } from 'vue'

/** 全局共享响应式 Toast 状态 / Shared reactive toast state */
const state = reactive({
  message: '',
  actionText: '',
  onAction: null
})

let timer = null

/**
 * 创建并返回 toast 实例。
 *
 * Creates and returns a toast instance.
 *
 * @returns {{
 *   message: import('vue').Ref<string>,
 *   actionText: import('vue').Ref<string>,
 *   toast: (msg: string, options?: number | { ms?: number, actionText?: string, onAction?: () => void }) => void,
 *   dismiss: () => void
 * }}
 */
export function useToast() {
  /**
   * 关闭当前 Toast
   * Dismiss current toast
   */
  function dismiss() {
    if (timer) clearTimeout(timer)
    timer = null
    state.message = ''
    state.actionText = ''
    state.onAction = null
  }

  /**
   * 显示一条 toast 提示，并在指定毫秒后自动消失。支持携带操作按钮（如快速插播）。
   *
   * Shows a toast message that auto-dismisses after the given milliseconds.
   * Supports an optional action button (e.g. rapid priority boost).
   *
   * @param {string} msg  - 提示文本 / toast text
   * @param {number|object} [options=2200] - 显示时长或配置对象 / duration ms or options object
   */
  function toast(msg, options = 2200) {
    const isNum = typeof options === 'number'
    const ms = isNum ? options : (options?.ms || 2200)
    const actionText = isNum ? '' : (options?.actionText || '')
    const onAction = isNum ? null : (options?.onAction || null)

    state.message = msg
    state.actionText = actionText
    state.onAction = onAction

    if (timer) clearTimeout(timer)
    timer = setTimeout(dismiss, ms)
  }

  return {
    ...toRefs(state),
    toast,
    dismiss
  }
}
