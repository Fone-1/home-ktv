import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useToast } from './useToast'

describe('useToast composable', () => {
  const { message, actionText, toast, dismiss } = useToast()

  beforeEach(() => {
    dismiss()
  })

  it('正确触发普通文本 toast', () => {
    toast('已加入队列')
    expect(message.value).toBe('已加入队列')
    expect(actionText.value).toBe('')
  })

  it('正确支持带 actionText 和 onAction 的快捷操作 toast', () => {
    const mockAction = vi.fn()
    toast('已加入队列', {
      actionText: '设为下一首',
      onAction: mockAction,
      ms: 3000
    })

    expect(message.value).toBe('已加入队列')
    expect(actionText.value).toBe('设为下一首')
  })

  it('dismiss 清空消息与状态', () => {
    toast('待关闭消息', { actionText: '按钮' })
    dismiss()
    expect(message.value).toBe('')
    expect(actionText.value).toBe('')
  })
})
