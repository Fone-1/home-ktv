import { defineStore } from 'pinia'
import api from '../api/client'

/**
 * 首页快捷推荐选歌全局 Store：
 * 每个 Tab 独立维护 loading、error、data、lastUpdated，优先展示缓存并支持静默后台刷新。
 */
export const useRecommendationsStore = defineStore('recommendations', {
  state: () => ({
    tabs: {
      hot: { data: [], loading: false, error: null, lastUpdated: 0 },
      new: { data: [], loading: false, error: null, lastUpdated: 0 },
      duet: { data: [], loading: false, error: null, lastUpdated: 0 },
      ktv: { data: [], loading: false, error: null, lastUpdated: 0 }
    }
  }),
  actions: {
    /**
     * 获取指定 Tab 的歌曲列表。
     * @param {string} key - Tab 标识（hot / new / duet / ktv）
     * @param {boolean} force - 是否强制跳过缓存刷新
     */
    async fetchTab(key, force = false) {
      const tab = this.tabs[key]
      if (!tab) return

      const now = Date.now()
      // 若非强制刷新且已有数据并在 60 秒有效期内，直接复用内存缓存
      if (!force && tab.data.length > 0 && now - tab.lastUpdated < 60000) {
        return
      }

      // 若已有缓存，静默后台更新（避免界面抖动与闪烁）；若无数据则开启 loading
      tab.loading = tab.data.length === 0
      tab.error = null

      try {
        let list = []
        if (key === 'hot') {
          list = await api.ranking(30).catch(() => [])
          if (!list.length) list = await api.newSongs().catch(() => [])
        } else if (key === 'new') {
          list = await api.newSongs().catch(() => [])
        } else if (key === 'duet') {
          list = await api.browseSongs({ vocalForm: '对唱', limit: 30 }).catch(() => [])
        } else if (key === 'ktv') {
          list = await api.browseSongs({ mediaType: 'KTV_VIDEO', limit: 30 }).catch(() => [])
        }
        tab.data = list
        tab.lastUpdated = Date.now()
      } catch (err) {
        tab.error = err.message || '加载推荐歌曲失败'
      } finally {
        tab.loading = false
      }
    }
  }
})
