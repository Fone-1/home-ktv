import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useRecommendationsStore } from './recommendations'
import api from '../api/client'

describe('useRecommendationsStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  it('每个 Tab 独立维护 loading, error, data 与 lastUpdated', async () => {
    const mockSongs = [{ id: 1, title: '反方向的钟' }]
    vi.spyOn(api, 'ranking').mockResolvedValue(mockSongs)

    const store = useRecommendationsStore()
    expect(store.tabs.hot.data).toHaveLength(0)
    expect(store.tabs.hot.lastUpdated).toBe(0)

    await store.fetchTab('hot')

    expect(store.tabs.hot.data).toEqual(mockSongs)
    expect(store.tabs.hot.loading).toBe(false)
    expect(store.tabs.hot.lastUpdated).toBeGreaterThan(0)

    // 验证 new Tab 保持独立未受影响
    expect(store.tabs.new.data).toHaveLength(0)
  })

  it('有效缓存期内避免重复触发 API 请求', async () => {
    const spy = vi.spyOn(api, 'ranking').mockResolvedValue([{ id: 1, title: '晴天' }])
    const store = useRecommendationsStore()

    await store.fetchTab('hot')
    expect(spy).toHaveBeenCalledTimes(1)

    // 立即再次获取，直接命中缓存
    await store.fetchTab('hot')
    expect(spy).toHaveBeenCalledTimes(1)

    // force=true 强制刷新
    await store.fetchTab('hot', true)
    expect(spy).toHaveBeenCalledTimes(2)
  })
})
